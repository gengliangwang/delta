/*
 * Copyright (2024) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.delta.kernel.defaults

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.Collections.emptyMap
import java.util.Optional

import scala.collection.JavaConverters._
import scala.collection.immutable.{ListMap, Seq}

import io.delta.golden.GoldenTableUtils.goldenTablePath
import io.delta.kernel.{Meta, Operation, Table, Transaction, TransactionBuilder, TransactionCommitResult}
import io.delta.kernel.Operation.MANUAL_UPDATE
import io.delta.kernel.data.{ColumnarBatch, ColumnVector, FilteredColumnarBatch, Row}
import io.delta.kernel.defaults.internal.data.DefaultColumnarBatch
import io.delta.kernel.defaults.utils.{TestRow, TestUtils}
import io.delta.kernel.engine.Engine
import io.delta.kernel.expressions.{Column, Literal}
import io.delta.kernel.expressions.Literal.ofInt
import io.delta.kernel.hook.PostCommitHook.PostCommitHookType
import io.delta.kernel.internal.{ScanImpl, SnapshotImpl, TableConfig, TableImpl, TransactionImpl}
import io.delta.kernel.internal.actions.{DomainMetadata, Metadata, Protocol, SingleAction}
import io.delta.kernel.internal.fs.{Path => DeltaPath}
import io.delta.kernel.internal.util.{Clock, FileNames, VectorUtils}
import io.delta.kernel.internal.util.SchemaUtils.casePreservingPartitionColNames
import io.delta.kernel.internal.util.Utils.singletonCloseableIterator
import io.delta.kernel.internal.util.Utils.toCloseableIterator
import io.delta.kernel.statistics.DataFileStatistics
import io.delta.kernel.types.IntegerType.INTEGER
import io.delta.kernel.types.StructType
import io.delta.kernel.utils.{CloseableIterable, CloseableIterator, DataFileStatus, FileStatus}
import io.delta.kernel.utils.CloseableIterable.{emptyIterable, inMemoryIterable}

import org.apache.spark.sql.delta.VersionNotFoundException

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.io.FileUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scalatest.funsuite.AnyFunSuite

/**
 * Common utility methods for write test suites.
 */
trait DeltaTableWriteSuiteBase extends AnyFunSuite with TestUtils {
  val OBJ_MAPPER = new ObjectMapper()
  val testEngineInfo = "test-engine"

  /** Test table schemas and test */
  val testSchema = new StructType().add("id", INTEGER)
  val dataBatches1 = generateData(testSchema, Seq.empty, Map.empty, 200, 3)
  val dataBatches2 = generateData(testSchema, Seq.empty, Map.empty, 400, 5)

  val testPartitionColumns = Seq("part1", "part2")
  val testPartitionSchema = new StructType()
    .add("id", INTEGER)
    .add("part1", INTEGER) // partition column
    .add("part2", INTEGER) // partition column

  val dataPartitionBatches1 = generateData(
    testPartitionSchema,
    testPartitionColumns,
    Map("part1" -> ofInt(1), "part2" -> ofInt(2)),
    batchSize = 237,
    numBatches = 3)

  val dataPartitionBatches2 = generateData(
    testPartitionSchema,
    testPartitionColumns,
    Map("part1" -> ofInt(4), "part2" -> ofInt(5)),
    batchSize = 876,
    numBatches = 7)

  val testClusteringColumns = List(new Column("part1"), new Column("part2"))
  val dataClusteringBatches1 = generateData(
    testPartitionSchema,
    partitionCols = Seq.empty,
    partitionValues = Map.empty,
    batchSize = 200,
    numBatches = 3)

  val dataClusteringBatches2 = generateData(
    testPartitionSchema,
    partitionCols = Seq.empty,
    partitionValues = Map.empty,
    batchSize = 456,
    numBatches = 5)

  def verifyLastCheckpointMetadata(tablePath: String, checkpointAt: Long, expSize: Long): Unit = {
    val filePath = f"$tablePath/_delta_log/_last_checkpoint"

    val source = scala.io.Source.fromFile(filePath)
    val result =
      try source.getLines().mkString(",")
      finally source.close()

    assert(result === s"""{"version":$checkpointAt,"size":$expSize}""")
  }

  /**
   * Helper method to remove the delta files before the given version, to make sure the read is
   * using a checkpoint as base for state reconstruction.
   */
  def deleteDeltaFilesBefore(tablePath: String, beforeVersion: Long): Unit = {
    Seq.range(0, beforeVersion).foreach { version =>
      val filePath = new Path(f"$tablePath/_delta_log/$version%020d.json")
      new Path(tablePath).getFileSystem(new Configuration()).delete(
        filePath,
        false /* recursive */ )
    }

    // try to query a version < beforeVersion
    val ex = intercept[VersionNotFoundException] {
      spark.read.format("delta").option("versionAsOf", beforeVersion - 1).load(tablePath)
    }
    assert(ex.getMessage().contains(
      s"Cannot time travel Delta table to version ${beforeVersion - 1}"))
  }

  def setCheckpointInterval(tablePath: String, interval: Int): Unit = {
    spark.sql(s"ALTER TABLE delta.`$tablePath` " +
      s"SET TBLPROPERTIES ('delta.checkpointInterval' = '$interval')")
  }

  def dataFileCount(tablePath: String): Int = {
    Files.walk(Paths.get(tablePath)).iterator().asScala
      .count(path => path.toString.endsWith(".parquet") && !path.toString.contains("_delta_log"))
  }

  def checkpointFilePath(tablePath: String, checkpointVersion: Long): String = {
    f"$tablePath/_delta_log/$checkpointVersion%020d.checkpoint.parquet"
  }

  def assertCheckpointExists(tablePath: String, atVersion: Long): Unit = {
    val cpPath = checkpointFilePath(tablePath, checkpointVersion = atVersion)
    assert(new File(cpPath).exists())
  }

  def copyTable(goldenTableName: String, targetLocation: String): Unit = {
    val source = new File(goldenTablePath(goldenTableName))
    val target = new File(targetLocation)
    FileUtils.copyDirectory(source, target)
  }

  def checkpointIfReady(
      engine: Engine,
      tablePath: String,
      result: TransactionCommitResult,
      expSize: Long): Unit = {
    result.getPostCommitHooks.forEach(hook => {
      if (hook.getType == PostCommitHookType.CHECKPOINT) {
        hook.threadSafeInvoke(engine)
        verifyLastCheckpointMetadata(tablePath, checkpointAt = result.getVersion, expSize)
      }
    })
  }

  /**
   * Helper method to read the commit file of the given version and return the value at the given
   * ordinal if it is not null and the consumer returns a value, otherwise return null.
   */
  def readCommitFile(
      engine: Engine,
      tablePath: String,
      version: Long,
      consumer: Row => Option[Any]): Option[Any] = {
    val table = Table.forPath(engine, tablePath)
    val logPath = new DeltaPath(table.getPath(engine), "_delta_log")
    val file = FileStatus.of(FileNames.deltaFile(logPath, version), 0, 0)
    val columnarBatches = engine.getJsonHandler.readJsonFiles(
      singletonCloseableIterator(file),
      SingleAction.FULL_SCHEMA,
      Optional.empty())
    while (columnarBatches.hasNext) {
      val batch = columnarBatches.next
      val rows = batch.getRows
      while (rows.hasNext) {
        val row = rows.next
        val ret = consumer(row)
        if (ret.isDefined) {
          return ret
        }
      }
    }
    Option.empty
  }

  def getMetadata(engine: Engine, tablePath: String): Metadata = {
    Table.forPath(engine, tablePath).getLatestSnapshot(engine)
      .asInstanceOf[SnapshotImpl].getMetadata
  }

  def getProtocol(engine: Engine, tablePath: String): Protocol = {
    Table.forPath(engine, tablePath).getLatestSnapshot(engine)
      .asInstanceOf[SnapshotImpl].getProtocol
  }

  /**
   *  Helper method to read the Metadata from the commit file of the given version if it is not
   *  null, otherwise return null.
   *  TODO: get rid of this and use getMetadata instead
   */
  def getMetadataActionFromCommit(
      engine: Engine,
      table: Table,
      version: Long): Option[Row] = {
    readCommitFile(
      engine,
      table.getPath(engine),
      version,
      (row) => {
        val ord = row.getSchema.indexOf("metaData")
        if (!row.isNullAt(ord)) {
          Option(row.getStruct(ord))
        } else {
          Option.empty
        }
      }).map { case metadata: Row => Some(metadata) }.getOrElse(Option.empty)
  }

  /**
   *  Helper method to read the Protocol from the commit file of the given version if it is not
   *  null, otherwise return null.
   *  TODO: get rid of this and use getProtocol instead
   */
  def getProtocolActionFromCommit(engine: Engine, table: Table, version: Long): Option[Row] = {
    readCommitFile(
      engine,
      table.getPath(engine),
      version,
      (row) => {
        val ord = row.getSchema.indexOf("protocol")
        if (!row.isNullAt(ord)) {
          Some(row.getStruct(ord))
        } else {
          Option.empty
        }
      }).map { case protocol: Row => Some(protocol) }.getOrElse(Option.empty)
  }

  def generateData(
      schema: StructType,
      partitionCols: Seq[String],
      partitionValues: Map[String, Literal],
      batchSize: Int,
      numBatches: Int): Seq[FilteredColumnarBatch] = {
    val partitionValuesSchemaCase =
      casePreservingPartitionColNames(partitionCols.asJava, partitionValues.asJava)

    var batches = Seq.empty[ColumnarBatch]
    for (_ <- 0 until numBatches) {
      var vectors = Seq.empty[ColumnVector]
      schema.fields().forEach { field =>
        val colType = field.getDataType
        val partValue = partitionValuesSchemaCase.get(field.getName)
        if (partValue != null) {
          // handle the partition column by inserting a vector with single value
          val vector = testSingleValueVector(colType, batchSize, partValue.getValue)
          vectors = vectors :+ vector
        } else {
          // handle the regular columns
          val vector = testColumnVector(batchSize, colType)
          vectors = vectors :+ vector
        }
      }
      batches = batches :+ new DefaultColumnarBatch(batchSize, schema, vectors.toArray)
    }
    batches.map(batch => new FilteredColumnarBatch(batch, Optional.empty()))
  }

  def createWriteTxnBuilder(table: Table): TransactionBuilder = {
    table.createTransactionBuilder(defaultEngine, testEngineInfo, Operation.WRITE)
  }

  def stageData(
      state: Row,
      partitionValues: Map[String, Literal],
      data: Seq[FilteredColumnarBatch])
      : CloseableIterator[Row] = {
    val physicalDataIter = Transaction.transformLogicalData(
      defaultEngine,
      state,
      toCloseableIterator(data.toIterator.asJava),
      partitionValues.asJava)

    val writeContext = Transaction.getWriteContext(defaultEngine, state, partitionValues.asJava)

    val writeResultIter = defaultEngine
      .getParquetHandler
      .writeParquetFiles(
        writeContext.getTargetDirectory,
        physicalDataIter,
        writeContext.getStatisticsColumns)

    Transaction.generateAppendActions(defaultEngine, state, writeResultIter, writeContext)
  }

  // scalastyle:off argcount
  def createTxn(
      engine: Engine = defaultEngine,
      tablePath: String,
      isNewTable: Boolean = false,
      schema: StructType = null,
      partCols: Seq[String] = null,
      tableProperties: Map[String, String] = null,
      clock: Clock = () => System.currentTimeMillis,
      withDomainMetadataSupported: Boolean = false,
      maxRetries: Int = -1,
      clusteringColsOpt: Option[List[Column]] = None,
      logCompactionInterval: Int = 10): Transaction = {
    // scalastyle:on argcount

    var txnBuilder = createWriteTxnBuilder(
      TableImpl.forPath(engine, tablePath, clock))

    if (isNewTable) {
      txnBuilder = txnBuilder.withSchema(engine, schema)
      if (partCols != null) {
        txnBuilder = txnBuilder.withPartitionColumns(engine, partCols.asJava)
      }
    }

    if (clusteringColsOpt.isDefined) {
      txnBuilder = txnBuilder.withClusteringColumns(engine, clusteringColsOpt.get.asJava)
    }

    if (tableProperties != null) {
      txnBuilder = txnBuilder.withTableProperties(engine, tableProperties.asJava)
    }

    if (withDomainMetadataSupported) {
      txnBuilder = txnBuilder.withDomainMetadataSupported()
    }

    if (maxRetries >= 0) {
      txnBuilder = txnBuilder.withMaxRetries(maxRetries)
    }

    txnBuilder = txnBuilder.withLogCompactionInverval(logCompactionInterval)

    txnBuilder.build(engine)
  }

  def createTxnWithDomainMetadatas(
      engine: Engine,
      tablePath: String,
      domainMetadatas: Seq[DomainMetadata],
      useInternalApi: Boolean = false): Transaction = {

    val txnBuilder = createWriteTxnBuilder(TableImpl.forPath(engine, tablePath))
    if (domainMetadatas.nonEmpty && !useInternalApi) {
      txnBuilder.withDomainMetadataSupported()
    }
    val txn = txnBuilder.build(engine).asInstanceOf[TransactionImpl]

    domainMetadatas.foreach { dm =>
      if (dm.isRemoved) {
        if (useInternalApi) {
          txn.removeDomainMetadataInternal(dm.getDomain)
        } else {
          txn.removeDomainMetadata(dm.getDomain)
        }
      } else {
        if (useInternalApi) {
          txn.addDomainMetadataInternal(dm.getDomain, dm.getConfiguration)
        } else {
          txn.addDomainMetadata(dm.getDomain, dm.getConfiguration)
        }
      }
    }
    txn
  }

  def getAppendActions(
      txn: Transaction,
      data: Seq[(Map[String, Literal], Seq[FilteredColumnarBatch])]): CloseableIterable[Row] = {

    val txnState = txn.getTransactionState(defaultEngine)

    val actions = data.map { case (partValues, partData) =>
      stageData(txnState, partValues, partData)
    }

    actions.reduceLeftOption(_ combine _) match {
      case Some(combinedActions) =>
        inMemoryIterable(combinedActions)
      case None =>
        emptyIterable[Row]
    }
  }

  def commitAppendData(
      engine: Engine = defaultEngine,
      txn: Transaction,
      data: Seq[(Map[String, Literal], Seq[FilteredColumnarBatch])]): TransactionCommitResult = {
    commitTransaction(txn, engine, getAppendActions(txn, data))
  }

  /** Utility to create table, with no data */
  def createEmptyTable(
      engine: Engine = defaultEngine,
      tablePath: String,
      schema: StructType,
      partCols: Seq[String] = Seq.empty,
      clock: Clock = () => System.currentTimeMillis,
      tableProperties: Map[String, String] = null,
      clusteringColsOpt: Option[List[Column]] = None): TransactionCommitResult = {

    appendData(
      engine,
      tablePath,
      isNewTable = true,
      schema,
      partCols,
      data = Seq.empty,
      clock,
      tableProperties,
      clusteringColsOpt)
  }

  /** Update an existing table - metadata only changes (no data changes) */
  def updateTableMetadata(
      engine: Engine = defaultEngine,
      tablePath: String,
      schema: StructType = null, // non-null schema means schema change
      clock: Clock = () => System.currentTimeMillis,
      tableProperties: Map[String, String] = null,
      clusteringColsOpt: Option[List[Column]] = None): TransactionCommitResult = {
    appendData(
      engine,
      tablePath,
      isNewTable = false,
      schema,
      Seq.empty,
      data = Seq.empty,
      clock,
      tableProperties,
      clusteringColsOpt)
  }

  def appendData(
      engine: Engine = defaultEngine,
      tablePath: String,
      isNewTable: Boolean = false,
      schema: StructType = null,
      partCols: Seq[String] = null,
      data: Seq[(Map[String, Literal], Seq[FilteredColumnarBatch])],
      clock: Clock = () => System.currentTimeMillis,
      tableProperties: Map[String, String] = null,
      clusteringColsOpt: Option[List[Column]] = None): TransactionCommitResult = {

    val txn = createTxn(
      engine,
      tablePath,
      isNewTable,
      schema,
      partCols,
      tableProperties,
      clock,
      clusteringColsOpt = clusteringColsOpt)
    commitAppendData(engine, txn, data)
  }

  def assertMetadataProp(
      snapshot: SnapshotImpl,
      key: TableConfig[_ <: Any],
      expectedValue: Any): Unit = {
    assert(key.fromMetadata(snapshot.getMetadata) == expectedValue)
  }

  def assertHasNoMetadataProp(snapshot: SnapshotImpl, key: TableConfig[_ <: Any]): Unit = {
    assertMetadataProp(snapshot, key, Optional.empty())
  }

  def assertHasWriterFeature(snapshot: SnapshotImpl, writerFeature: String): Unit = {
    assert(snapshot.getProtocol.getWriterFeatures.contains(writerFeature))
  }

  def assertHasNoWriterFeature(snapshot: SnapshotImpl, writerFeature: String): Unit = {
    assert(!snapshot.getProtocol.getWriterFeatures.contains(writerFeature))
  }

  def setTablePropAndVerify(
      engine: Engine,
      tablePath: String,
      isNewTable: Boolean = true,
      key: TableConfig[_ <: Any],
      value: String,
      expectedValue: Any,
      clock: Clock = () => System.currentTimeMillis): Unit = {

    val table = Table.forPath(engine, tablePath)

    createTxn(
      engine,
      tablePath,
      isNewTable,
      testSchema,
      Seq.empty,
      tableProperties = Map(key.getKey -> value),
      clock)
      .commit(engine, emptyIterable())

    val snapshot = table.getLatestSnapshot(engine).asInstanceOf[SnapshotImpl]
    assertMetadataProp(snapshot, key, expectedValue)
  }

  protected def verifyWrittenContent(
      path: String,
      expSchema: StructType,
      expData: Seq[TestRow]): Unit = {
    val actSchema = tableSchema(path)
    assert(actSchema === expSchema)

    // verify data using Kernel reader
    checkTable(path, expData)

    // verify data using Spark reader.
    // Spark reads the timestamp partition columns in local timezone vs. Kernel reads in UTC. We
    // need to set the timezone to UTC before reading the data using Spark to make the tests pass
    withSparkTimeZone("UTC") {
      val resultSpark = spark.sql(s"SELECT * FROM delta.`$path`").collect().map(TestRow(_))
      checkAnswer(resultSpark, expData)
    }
  }

  def verifyCommitInfo(
      tablePath: String,
      version: Long,
      partitionCols: Seq[String] = Seq.empty,
      operation: Operation = MANUAL_UPDATE): Unit = {
    val row = spark.sql(s"DESCRIBE HISTORY delta.`$tablePath`")
      .filter(s"version = $version")
      .select(
        "version",
        "operationParameters.partitionBy",
        "isBlindAppend",
        "engineInfo",
        "operation")
      .collect().last

    assert(row.getAs[Long]("version") === version)
    assert(row.getAs[Long]("partitionBy") ===
      (if (partitionCols == null) null else OBJ_MAPPER.writeValueAsString(partitionCols.asJava)))
    // For now we've hardcoded isBlindAppend=false, once we support more precise setting of this
    // field we should update this check
    assert(!row.getAs[Boolean]("isBlindAppend"))
    assert(row.getAs[Seq[String]]("engineInfo") ===
      "Kernel-" + Meta.KERNEL_VERSION + "/" + testEngineInfo)
    assert(row.getAs[String]("operation") === operation.getDescription)
  }

  def verifyCommitResult(
      result: TransactionCommitResult,
      expVersion: Long,
      expIsReadyForCheckpoint: Boolean): Unit = {
    assert(result.getVersion === expVersion)
    assertCheckpointReadiness(result, expIsReadyForCheckpoint)
  }

  // TODO: Change this to use the table metadata and protocol and
  // not rely on DESCRIBE which adds some properties based on the protocol.
  def verifyTableProperties(
      tablePath: String,
      expProperties: ListMap[String, Any],
      minReaderVersion: Int,
      minWriterVersion: Int): Unit = {
    val resultProperties = spark.sql(s"DESCRIBE EXTENDED delta.`$tablePath`")
      .filter("col_name = 'Table Properties'")
      .select("data_type")
      .collect().map(TestRow(_))

    val builder = new StringBuilder("[")

    expProperties.foreach { case (key, value) =>
      builder.append(s"$key=$value,")
    }

    builder.append(s"delta.minReaderVersion=$minReaderVersion,")
    builder.append(s"delta.minWriterVersion=$minWriterVersion")
    builder.append("]")
    checkAnswer(resultProperties, Seq(builder.toString()).map(TestRow(_)))
  }

  def assertCheckpointReadiness(
      txnResult: TransactionCommitResult,
      isReadyForCheckpoint: Boolean): Unit = {
    assert(
      txnResult.getPostCommitHooks
        .stream()
        .anyMatch(hook => hook.getType == PostCommitHookType.CHECKPOINT) === isReadyForCheckpoint)
  }

  def collectStatsFromAddFiles(engine: Engine, path: String): Seq[String] = {
    val snapshot = Table.forPath(engine, path).getLatestSnapshot(engine)
    val scan = snapshot.getScanBuilder.build()
    val scanFiles = scan.asInstanceOf[ScanImpl].getScanFiles(engine, true)

    scanFiles.asScala.toList.flatMap { scanFile =>
      scanFile.getRows.asScala.toList.flatMap { row =>
        val add = row.getStruct(row.getSchema.indexOf("add"))
        val idx = add.getSchema.indexOf("stats")
        if (idx >= 0 && !add.isNullAt(idx)) List(add.getString(idx)) else Nil
      }
    }
  }

  /**
   * Commit transaction, all child suites should use this instead of txn.commit
   * directly and could override it for specific test cases (e.g. commit and write CRC).
   */
  protected def commitTransaction(
      txn: Transaction,
      engine: Engine,
      dataActions: CloseableIterable[Row]): TransactionCommitResult = {
    txn.commit(engine, dataActions)
  }

  protected def generateDataFileStatus(
      tablePath: String,
      fileName: String,
      fileSize: Long = 1000,
      includeStats: Boolean = true): DataFileStatus = {
    val filePath = defaultEngine.getFileSystemClient.resolvePath(tablePath + "/" + fileName)
    new DataFileStatus(
      filePath,
      fileSize,
      10,
      if (includeStats) {
        Optional.of(new DataFileStatistics(
          100,
          emptyMap(),
          emptyMap(),
          emptyMap()))
      } else Optional.empty())
  }

  protected def assertCommitResultHasClusteringCols(
      commitResult: TransactionCommitResult,
      expectedClusteringCols: Seq[Column]): Unit = {
    val actualClusteringCols = commitResult.getTransactionReport.getClusteringColumns.asScala

    assert(
      actualClusteringCols === expectedClusteringCols,
      s"Expected clustering columns: $expectedClusteringCols, but got: $actualClusteringCols")
  }
}
