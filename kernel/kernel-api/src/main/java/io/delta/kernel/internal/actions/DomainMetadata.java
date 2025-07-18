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
package io.delta.kernel.internal.actions;

import static io.delta.kernel.internal.util.InternalUtils.requireNonNull;
import static io.delta.kernel.internal.util.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.Row;
import io.delta.kernel.internal.data.GenericRow;
import io.delta.kernel.internal.rowtracking.RowTrackingMetadataDomain;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import java.util.*;

/** Delta log action representing an `DomainMetadata` action */
public class DomainMetadata {

  private static final Set<String> SUPPORTED_SYSTEM_DOMAINS =
      Collections.unmodifiableSet(
          new HashSet<>(Collections.singletonList(RowTrackingMetadataDomain.DOMAIN_NAME)));

  /** Whether the provided {@code domain} is a user-controlled domain */
  public static boolean isUserControlledDomain(String domain) {
    // Domain identifiers are case-sensitive, but we don't want to allow users to set domains
    // with prefixes like `DELTA.` either, so perform case-insensitive check for this purpose
    return !domain.toLowerCase(Locale.ROOT).startsWith("delta.");
  }

  /**
   * Checks whether the provided {@code domain} is a system domain that is supported for set in a
   * Delta transaction via addDomainMetadata.
   *
   * <p>By default, system domains are not allowed to be set through transaction-level domain
   * metadata due to their reserved nature. However, there are specific system domains—such as
   * {@link RowTrackingMetadataDomain#DOMAIN_NAME}—that are explicitly allowed to be set in this
   * context. This method defines the allowlist of such supported domains and checks against it.
   */
  public static boolean isSystemDomainSupportedSetFromTxn(String domain) {
    return SUPPORTED_SYSTEM_DOMAINS.contains(domain);
  }

  /** Full schema of the {@link DomainMetadata} action in the Delta Log. */
  public static final StructType FULL_SCHEMA =
      new StructType()
          .add("domain", StringType.STRING, false /* nullable */)
          .add("configuration", StringType.STRING, false /* nullable */)
          .add("removed", BooleanType.BOOLEAN, false /* nullable */);

  public static DomainMetadata fromColumnVector(ColumnVector vector, int rowId) {
    if (vector.isNullAt(rowId)) {
      return null;
    }
    return new DomainMetadata(
        requireNonNull(vector.getChild(0), rowId, "domain").getString(rowId),
        requireNonNull(vector.getChild(1), rowId, "configuration").getString(rowId),
        requireNonNull(vector.getChild(2), rowId, "removed").getBoolean(rowId));
  }

  /**
   * Creates a {@link DomainMetadata} instance from a Row with the schema being {@link
   * DomainMetadata#FULL_SCHEMA}.
   *
   * @param row the Row object containing the DomainMetadata action
   * @return a DomainMetadata instance or null if the row is null
   * @throws IllegalArgumentException if the schema of the row does not match {@link
   *     DomainMetadata#FULL_SCHEMA}
   */
  public static DomainMetadata fromRow(Row row) {
    if (row == null) {
      return null;
    }
    checkArgument(
        row.getSchema().equals(FULL_SCHEMA),
        "Expected schema: %s, found: %s",
        FULL_SCHEMA,
        row.getSchema());
    return new DomainMetadata(
        requireNonNull(row, 0, "domain").getString(0),
        requireNonNull(row, 1, "configuration").getString(1),
        requireNonNull(row, 2, "removed").getBoolean(2));
  }

  private final String domain;
  private final String configuration;
  private final boolean removed;

  /**
   * The domain metadata action contains a configuration string for a named metadata domain. Two
   * overlapping transactions conflict if they both contain a domain metadata action for the same
   * metadata domain. Per-domain conflict resolution logic can be implemented.
   *
   * @param domain A string used to identify a specific domain.
   * @param configuration A string containing configuration for the metadata domain.
   * @param removed If it is true it serves as a tombstone to logically delete a {@link
   *     DomainMetadata} action.
   */
  public DomainMetadata(String domain, String configuration, boolean removed) {
    this.domain = requireNonNull(domain, "domain is null");
    this.configuration = requireNonNull(configuration, "configuration is null");
    this.removed = removed;
  }

  public String getDomain() {
    return domain;
  }

  public String getConfiguration() {
    return configuration;
  }

  public boolean isRemoved() {
    return removed;
  }

  /**
   * Encode as a {@link Row} object with the schema {@link DomainMetadata#FULL_SCHEMA}.
   *
   * @return {@link Row} object with the schema {@link DomainMetadata#FULL_SCHEMA}
   */
  public Row toRow() {
    Map<Integer, Object> domainMetadataMap = new HashMap<>();
    domainMetadataMap.put(0, domain);
    domainMetadataMap.put(1, configuration);
    domainMetadataMap.put(2, removed);

    return new GenericRow(DomainMetadata.FULL_SCHEMA, domainMetadataMap);
  }

  public DomainMetadata removed() {
    checkArgument(!removed, "Cannot remove a domain metadata tombstone (already removed)");
    return new DomainMetadata(domain, configuration, true /* removed */);
  }

  @Override
  public String toString() {
    return String.format(
        "DomainMetadata{domain='%s', configuration='%s', removed='%b'}",
        domain, configuration, removed);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    DomainMetadata that = (DomainMetadata) obj;
    return removed == that.removed
        && domain.equals(that.domain)
        && configuration.equals(that.configuration);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(domain, configuration, removed);
  }
}
