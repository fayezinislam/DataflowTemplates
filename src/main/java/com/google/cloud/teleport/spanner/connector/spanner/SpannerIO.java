/*
 * Copyright (C) 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.teleport.spanner.connector.spanner;

import static com.google.cloud.teleport.spanner.connector.spanner.MutationUtils.isPointDelete;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.cloud.ServiceFactory;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.PartitionOptions;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TimestampBound;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.UnsignedBytes;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;

import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.ApproximateQuantiles;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.Wait;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental {@link PTransform Transforms} for reading from and writing to <a
 * href="https://cloud.google.com/spanner">Google Cloud Spanner</a>.
 *
 * <h3>Reading from Cloud Spanner</h3>
 *
 * <p>To read from Cloud Spanner, apply {@link SpannerIO.Read} transformation. It will return a
 * {@link PCollection} of {@link Struct Structs}, where each element represents
 * an individual row returned from the read operation. Both Query and Read APIs are supported.
 * See more information about <a href="https://cloud.google.com/spanner/docs/reads">reading from
 * Cloud Spanner</a>
 *
 * <p>To execute a <strong>query</strong>, specify a {@link SpannerIO.Read#withQuery(Statement)} or
 * {@link SpannerIO.Read#withQuery(String)} during the construction of the transform.
 *
 * <pre>{@code
 *  PCollection<Struct> rows = p.apply(
 *      SpannerIO.read()
 *          .withInstanceId(instanceId)
 *          .withDatabaseId(dbId)
 *          .withQuery("SELECT id, name, email FROM users"));
 * }</pre>
 *
 * <p>To use the Read API, specify a {@link SpannerIO.Read#withTable(String) table name} and
 * a {@link SpannerIO.Read#withColumns(List) list of columns}.
 *
 * <pre>{@code
 * PCollection<Struct> rows = p.apply(
 *    SpannerIO.read()
 *        .withInstanceId(instanceId)
 *        .withDatabaseId(dbId)
 *        .withTable("users")
 *        .withColumns("id", "name", "email"));
 * }</pre>
 *
 * <p>To optimally read using index, specify the index name using {@link SpannerIO.Read#withIndex}.
 *
 * <p>The transform is guaranteed to be executed on a consistent snapshot of data, utilizing the
 * power of read only transactions. Staleness of data can be controlled using
 * {@link SpannerIO.Read#withTimestampBound} or {@link SpannerIO.Read#withTimestamp(Timestamp)}
 * methods. <a href="https://cloud.google.com/spanner/docs/transactions">Read more</a> about
 * transactions in Cloud Spanner.
 *
 * <p>It is possible to read several {@link PCollection PCollections} within a single transaction.
 * Apply {@link SpannerIO#createTransaction()} transform, that lazily creates a transaction. The
 * result of this transformation can be passed to read operation using
 * {@link SpannerIO.Read#withTransaction(PCollectionView)}.
 *
 * <pre>{@code
 * SpannerConfig spannerConfig = ...
 *
 * PCollectionView<Transaction> tx =
 * p.apply(
 *    SpannerIO.createTransaction()
 *        .withSpannerConfig(spannerConfig)
 *        .withTimestampBound(TimestampBound.strong()));
 *
 * PCollection<Struct> users = p.apply(
 *    SpannerIO.read()
 *        .withSpannerConfig(spannerConfig)
 *        .withQuery("SELECT name, email FROM users")
 *        .withTransaction(tx));
 *
 * PCollection<Struct> tweets = p.apply(
 *    SpannerIO.read()
 *        .withSpannerConfig(spannerConfig)
 *        .withQuery("SELECT user, tweet, date FROM tweets")
 *        .withTransaction(tx));
 * }</pre>
 *
 * <h3>Writing to Cloud Spanner</h3>
 *
 * <p>The Cloud Spanner {@link SpannerIO.Write} transform writes to Cloud Spanner by executing a
 * collection of input row {@link Mutation Mutations}. The mutations grouped into batches for
 * efficiency.
 *
 * <p>To configure the write transform, create an instance using {@link #write()} and then specify
 * the destination Cloud Spanner instance ({@link Write#withInstanceId(String)} and destination
 * database ({@link Write#withDatabaseId(String)}). For example:
 *
 * <pre>{@code
 * // Earlier in the pipeline, create a PCollection of Mutations to be written to Cloud Spanner.
 * PCollection<Mutation> mutations = ...;
 * // Write mutations.
 * mutations.apply(
 *     "Write", SpannerIO.write().withInstanceId("instance").withDatabaseId("database"));
 * }</pre>
 *
 * <p>The default size of the batch is set to 1MB, to override this use {@link
 * Write#withBatchSizeBytes(long)}. Setting batch size to a small value or zero practically disables
 * batching.
 *
 * <p>The transform does not provide same transactional guarantees as Cloud Spanner. In particular,
 *
 * <ul>
 *   <li>Mutations are not submitted atomically;
 *   <li>A mutation is applied at least once;
 *   <li>If the pipeline was unexpectedly stopped, mutations that were already applied will not get
 *       rolled back.
 * </ul>
 *
 * <p>Use {@link MutationGroup} to ensure that a small set mutations is bundled together. It is
 * guaranteed that mutations in a group are submitted in the same transaction. Build
 * {@link SpannerIO.Write} transform, and call {@link Write#grouped()} method. It will return a
 * transformation that can be applied to a PCollection of MutationGroup.
 */
@Experimental(Experimental.Kind.SOURCE_SINK)
public class SpannerIO {
  private static final Logger LOG = LoggerFactory.getLogger(
      SpannerIO.class);

  private static final long DEFAULT_BATCH_SIZE_BYTES = 1024L * 1024L; // 1 MB
  // Max number of mutations to batch together.
  private static final int DEFAULT_MAX_NUM_MUTATIONS = 5000;
  // The maximum number of keys to fit in memory when computing approximate quantiles.
  private static final long MAX_NUM_KEYS = (long) 1e6;
  // TODO calculate number of samples based on the size of the input.
  private static final int DEFAULT_NUM_SAMPLES = 1000;

  /**
   * Creates an uninitialized instance of {@link Read}. Before use, the {@link Read} must be
   * configured with a {@link Read#withInstanceId} and {@link Read#withDatabaseId} that identify the
   * Cloud Spanner database.
   */
  @Experimental(Experimental.Kind.SOURCE_SINK)
  public static Read read() {
    return new AutoValue_SpannerIO_Read.Builder()
        .setSpannerConfig(SpannerConfig.create())
        .setTimestampBound(TimestampBound.strong())
        .setReadOperation(ReadOperation.create())
        .setBatching(true)
        .build();
  }

  /**
   * A {@link PTransform} that works like {@link #read}, but executes read operations coming from a
   * {@link PCollection}.
   */
  @Experimental(Experimental.Kind.SOURCE_SINK)
  public static ReadAll readAll() {
    return new AutoValue_SpannerIO_ReadAll.Builder()
        .setSpannerConfig(SpannerConfig.create())
        .setTimestampBound(TimestampBound.strong())
        .setBatching(true)
        .build();
  }

  /**
   * Returns a transform that creates a batch transaction. By default,
   * {@link TimestampBound#strong()} transaction is created, to override this use
   * {@link CreateTransaction#withTimestampBound(TimestampBound)}.
   */
  @Experimental
  public static CreateTransaction createTransaction() {
    return new AutoValue_SpannerIO_CreateTransaction.Builder()
        .setSpannerConfig(SpannerConfig.create())
        .setTimestampBound(TimestampBound.strong())
        .build();
  }

  /**
   * Creates an uninitialized instance of {@link Write}. Before use, the {@link Write} must be
   * configured with a {@link Write#withInstanceId} and {@link Write#withDatabaseId} that identify
   * the Cloud Spanner database being written.
   */
  @Experimental
  public static Write write() {
    return new AutoValue_SpannerIO_Write.Builder()
        .setSpannerConfig(SpannerConfig.create())
        .setBatchSizeBytes(DEFAULT_BATCH_SIZE_BYTES)
        .setMaxNumMutations(DEFAULT_MAX_NUM_MUTATIONS)
        .setNumSamples(DEFAULT_NUM_SAMPLES)
        .setFailureMode(FailureMode.FAIL_FAST)
        .build();
  }

  /** Implementation of {@link #readAll}. */
  @Experimental(Experimental.Kind.SOURCE_SINK)
  @AutoValue
  public abstract static class ReadAll
      extends PTransform<PCollection<ReadOperation>, PCollection<Struct>> {

    abstract SpannerConfig getSpannerConfig();

    @Nullable
    abstract PCollectionView<Transaction> getTransaction();

    @Nullable
    abstract TimestampBound getTimestampBound();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setSpannerConfig(SpannerConfig spannerConfig);

      abstract Builder setTransaction(PCollectionView<Transaction> transaction);

      abstract Builder setTimestampBound(TimestampBound timestampBound);

      abstract Builder setBatching(Boolean batching);

      abstract ReadAll build();
    }

    /** Specifies the Cloud Spanner configuration. */
    public ReadAll withSpannerConfig(SpannerConfig spannerConfig) {
      return toBuilder().setSpannerConfig(spannerConfig).build();
    }

    /** Specifies the Cloud Spanner project. */
    public ReadAll withProjectId(String projectId) {
      return withProjectId(ValueProvider.StaticValueProvider.of(projectId));
    }

    /** Specifies the Cloud Spanner project. */
    public ReadAll withProjectId(ValueProvider<String> projectId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withProjectId(projectId));
    }

    /** Specifies the Cloud Spanner instance. */
    public ReadAll withInstanceId(String instanceId) {
      return withInstanceId(ValueProvider.StaticValueProvider.of(instanceId));
    }

    /** Specifies the Cloud Spanner instance. */
    public ReadAll withInstanceId(ValueProvider<String> instanceId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withInstanceId(instanceId));
    }

    /** Specifies the Cloud Spanner database. */
    public ReadAll withDatabaseId(String databaseId) {
      return withDatabaseId(ValueProvider.StaticValueProvider.of(databaseId));
    }

    /** Specifies the Cloud Spanner host. */
    public ReadAll withHost(ValueProvider<String> host) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withHost(host));
    }

    public ReadAll withHost(String host) {
      return withHost(ValueProvider.StaticValueProvider.of(host));
    }

    /** Specifies the Cloud Spanner database. */
    public ReadAll withDatabaseId(ValueProvider<String> databaseId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withDatabaseId(databaseId));
    }

    @VisibleForTesting
    ReadAll withServiceFactory(ServiceFactory<Spanner, SpannerOptions> serviceFactory) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withServiceFactory(serviceFactory));
    }

    public ReadAll withTransaction(PCollectionView<Transaction> transaction) {
      return toBuilder().setTransaction(transaction).build();
    }

    public ReadAll withTimestamp(Timestamp timestamp) {
      return withTimestampBound(TimestampBound.ofReadTimestamp(timestamp));
    }

    public ReadAll withTimestampBound(TimestampBound timestampBound) {
      return toBuilder().setTimestampBound(timestampBound).build();
    }

    /**
     * By default Batch API is used to read data from Cloud Spanner.
     * It is useful to disable batching when the underlying query is not root-partitionable.
     */
    public ReadAll withBatching(boolean batching) {
      return toBuilder().setBatching(batching).build();
    }

    abstract Boolean getBatching();

    @Override
    public PCollection<Struct> expand(PCollection<ReadOperation> input) {
      PTransform<PCollection<ReadOperation>, PCollection<Struct>> readTransform;
      if (getBatching()) {
        readTransform = BatchSpannerRead
            .create(getSpannerConfig(), getTransaction(), getTimestampBound());
      } else {
        readTransform = NaiveSpannerRead
            .create(getSpannerConfig(), getTransaction(), getTimestampBound());
      }
      return input
          .apply("Reshuffle", Reshuffle.viaRandomKey())
          .apply("Read from Cloud Spanner", readTransform);
    }
  }

  /** Implementation of {@link #read}. */
  @Experimental(Experimental.Kind.SOURCE_SINK)
  @AutoValue
  public abstract static class Read extends PTransform<PBegin, PCollection<Struct>> {

    abstract SpannerConfig getSpannerConfig();

    abstract ReadOperation getReadOperation();

    @Nullable
    abstract TimestampBound getTimestampBound();

    @Nullable
    abstract PCollectionView<Transaction> getTransaction();

    @Nullable
    abstract PartitionOptions getPartitionOptions();

    abstract Boolean getBatching();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {

      abstract Builder setSpannerConfig(SpannerConfig spannerConfig);

      abstract Builder setReadOperation(ReadOperation readOperation);

      abstract Builder setTimestampBound(TimestampBound timestampBound);

      abstract Builder setTransaction(PCollectionView<Transaction> transaction);

      abstract Builder setPartitionOptions(PartitionOptions partitionOptions);

      abstract Builder setBatching(Boolean batching);

      abstract Read build();
    }

    /** Specifies the Cloud Spanner configuration. */
    public Read withSpannerConfig(SpannerConfig spannerConfig) {
      return toBuilder().setSpannerConfig(spannerConfig).build();
    }

    /** Specifies the Cloud Spanner project. */
    public Read withProjectId(String projectId) {
      return withProjectId(ValueProvider.StaticValueProvider.of(projectId));
    }

    /** Specifies the Cloud Spanner project. */
    public Read withProjectId(ValueProvider<String> projectId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withProjectId(projectId));
    }

    /** Specifies the Cloud Spanner instance. */
    public Read withInstanceId(String instanceId) {
      return withInstanceId(ValueProvider.StaticValueProvider.of(instanceId));
    }

    /** Specifies the Cloud Spanner instance. */
    public Read withInstanceId(ValueProvider<String> instanceId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withInstanceId(instanceId));
    }

    /** Specifies the Cloud Spanner database. */
    public Read withDatabaseId(String databaseId) {
      return withDatabaseId(ValueProvider.StaticValueProvider.of(databaseId));
    }

    /** Specifies the Cloud Spanner database. */
    public Read withDatabaseId(ValueProvider<String> databaseId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withDatabaseId(databaseId));
    }

    /** Specifies the Cloud Spanner host. */
    public Read withHost(ValueProvider<String> host) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withHost(host));
    }

    public Read withHost(String host) {
      return withHost(ValueProvider.StaticValueProvider.of(host));
    }

    /** If true the uses Cloud Spanner batch API. */
    public Read withBatching(boolean batching) {
      return toBuilder().setBatching(batching).build();
    }

    @VisibleForTesting
    Read withServiceFactory(ServiceFactory<Spanner, SpannerOptions> serviceFactory) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withServiceFactory(serviceFactory));
    }

    public Read withTransaction(PCollectionView<Transaction> transaction) {
      return toBuilder().setTransaction(transaction).build();
    }

    public Read withTimestamp(Timestamp timestamp) {
      return withTimestampBound(TimestampBound.ofReadTimestamp(timestamp));
    }

    public Read withTimestampBound(TimestampBound timestampBound) {
      return toBuilder().setTimestampBound(timestampBound).build();
    }

    public Read withTable(String table) {
      return withReadOperation(getReadOperation().withTable(table));
    }

    public Read withReadOperation(ReadOperation operation) {
      return toBuilder().setReadOperation(operation).build();
    }

    public Read withColumns(String... columns) {
      return withColumns(Arrays.asList(columns));
    }

    public Read withColumns(List<String> columns) {
      return withReadOperation(getReadOperation().withColumns(columns));
    }

    public Read withQuery(Statement statement) {
      return withReadOperation(getReadOperation().withQuery(statement));
    }

    public Read withQuery(String sql) {
      return withQuery(Statement.of(sql));
    }

    public Read withKeySet(KeySet keySet) {
      return withReadOperation(getReadOperation().withKeySet(keySet));
    }

    public Read withIndex(String index) {
      return withReadOperation(getReadOperation().withIndex(index));
    }

    public Read withPartitionOptions(PartitionOptions partitionOptions) {
      return withReadOperation(getReadOperation().withPartitionOptions(partitionOptions));
    }

    @Override
    public PCollection<Struct> expand(PBegin input) {
      getSpannerConfig().validate();
      checkArgument(
          getTimestampBound() != null,
          "SpannerIO.read() runs in a read only transaction and requires timestamp to be set "
              + "with withTimestampBound or withTimestamp method");

      if (getReadOperation().getQuery() != null) {
        // TODO: validate query?
      } else if (getReadOperation().getTable() != null) {
        // Assume read
        checkNotNull(
            getReadOperation().getColumns(),
            "For a read operation SpannerIO.read() requires a list of "
                + "columns to set with withColumns method");
        checkArgument(
            !getReadOperation().getColumns().isEmpty(),
            "For a read operation SpannerIO.read() requires a"
                + " list of columns to set with withColumns method");
      } else {
        throw new IllegalArgumentException(
            "SpannerIO.read() requires configuring query or read operation.");
      }


      ReadAll readAll = readAll()
          .withSpannerConfig(getSpannerConfig())
          .withTimestampBound(getTimestampBound())
          .withBatching(getBatching())
          .withTransaction(getTransaction());
      return input.apply(Create.of(getReadOperation())).apply("Execute query", readAll);
    }
  }

  /**
   * A {@link PTransform} that create a transaction.
   *
   * @see SpannerIO
   */
  @Experimental(Experimental.Kind.SOURCE_SINK)
  @AutoValue
  public abstract static class CreateTransaction
      extends PTransform<PBegin, PCollectionView<Transaction>> {

    abstract SpannerConfig getSpannerConfig();

    @Nullable
    abstract TimestampBound getTimestampBound();

    abstract Builder toBuilder();

    @Override
    public PCollectionView<Transaction> expand(PBegin input) {
      getSpannerConfig().validate();

      return input
          .apply(Create.of(1))
          .apply("Create transaction", ParDo.of(new CreateTransactionFn(this)))
          .apply("As PCollectionView", View.asSingleton());
    }

    /** Specifies the Cloud Spanner configuration. */
    public CreateTransaction withSpannerConfig(SpannerConfig spannerConfig) {
      return toBuilder().setSpannerConfig(spannerConfig).build();
    }

    /** Specifies the Cloud Spanner project. */
    public CreateTransaction withProjectId(String projectId) {
      return withProjectId(ValueProvider.StaticValueProvider.of(projectId));
    }

    /** Specifies the Cloud Spanner project. */
    public CreateTransaction withProjectId(ValueProvider<String> projectId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withProjectId(projectId));
    }

    /** Specifies the Cloud Spanner instance. */
    public CreateTransaction withInstanceId(String instanceId) {
      return withInstanceId(ValueProvider.StaticValueProvider.of(instanceId));
    }

    /** Specifies the Cloud Spanner instance. */
    public CreateTransaction withInstanceId(ValueProvider<String> instanceId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withInstanceId(instanceId));
    }

    /** Specifies the Cloud Spanner database. */
    public CreateTransaction withDatabaseId(String databaseId) {
      return withDatabaseId(ValueProvider.StaticValueProvider.of(databaseId));
    }

    /** Specifies the Cloud Spanner database. */
    public CreateTransaction withDatabaseId(ValueProvider<String> databaseId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withDatabaseId(databaseId));
    }

    /** Specifies the Cloud Spanner host. */
    public CreateTransaction withHost(ValueProvider<String> host) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withHost(host));
    }

    public CreateTransaction withHost(String host) {
      return withHost(ValueProvider.StaticValueProvider.of(host));
    }

    @VisibleForTesting
    CreateTransaction withServiceFactory(
        ServiceFactory<Spanner, SpannerOptions> serviceFactory) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withServiceFactory(serviceFactory));
    }

    public CreateTransaction withTimestampBound(TimestampBound timestampBound) {
      return toBuilder().setTimestampBound(timestampBound).build();
    }

    /** A builder for {@link CreateTransaction}. */
    @AutoValue.Builder public abstract static class Builder {

      public abstract Builder setSpannerConfig(SpannerConfig spannerConfig);

      public abstract Builder setTimestampBound(TimestampBound newTimestampBound);

      public abstract CreateTransaction build();
    }
  }

  /**
   * A failure handling strategy.
   */
  public enum FailureMode {
    /** Invalid write to Spanner will cause the pipeline to fail. A default strategy. */
    FAIL_FAST,
    /** Invalid mutations will be returned as part of the result of the write transform. */
    REPORT_FAILURES
  }

  /**
   * A {@link PTransform} that writes {@link Mutation} objects to Google Cloud Spanner.
   *
   * @see SpannerIO
   */
  @Experimental(Experimental.Kind.SOURCE_SINK)
  @AutoValue
  public abstract static class Write extends PTransform<PCollection<Mutation>, SpannerWriteResult> {

    abstract SpannerConfig getSpannerConfig();

    abstract long getBatchSizeBytes();

    abstract long getMaxNumMutations();

    abstract int getNumSamples();

    abstract FailureMode getFailureMode();

    @Nullable
     abstract PTransform<PCollection<KV<String, byte[]>>, PCollection<KV<String, List<byte[]>>>>
         getSampler();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {

      abstract Builder setSpannerConfig(SpannerConfig spannerConfig);

      abstract Builder setBatchSizeBytes(long batchSizeBytes);

      abstract Builder setMaxNumMutations(long maxNumMutations);

      abstract Builder setNumSamples(int numSamples);

      abstract Builder setFailureMode(FailureMode failureMode);

      abstract Builder setSampler(
          PTransform<PCollection<KV<String, byte[]>>, PCollection<KV<String, List<byte[]>>>>
              sampler);

      abstract Write build();
    }

    /** Specifies the Cloud Spanner configuration. */
    public Write withSpannerConfig(SpannerConfig spannerConfig) {
      return toBuilder().setSpannerConfig(spannerConfig).build();
    }

    /** Specifies the Cloud Spanner project. */
    public Write withProjectId(String projectId) {
      return withProjectId(ValueProvider.StaticValueProvider.of(projectId));
    }

    /** Specifies the Cloud Spanner project. */
    public Write withProjectId(ValueProvider<String> projectId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withProjectId(projectId));
    }

    /** Specifies the Cloud Spanner instance. */
    public Write withInstanceId(String instanceId) {
      return withInstanceId(ValueProvider.StaticValueProvider.of(instanceId));
    }

    /** Specifies the Cloud Spanner instance. */
    public Write withInstanceId(ValueProvider<String> instanceId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withInstanceId(instanceId));
    }

    /** Specifies the Cloud Spanner database. */
    public Write withDatabaseId(String databaseId) {
      return withDatabaseId(ValueProvider.StaticValueProvider.of(databaseId));
    }

    /** Specifies the Cloud Spanner database. */
    public Write withDatabaseId(ValueProvider<String> databaseId) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withDatabaseId(databaseId));
    }

    /** Specifies the Cloud Spanner host. */
    public Write withHost(ValueProvider<String> host) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withHost(host));
    }

    /** Specifies the Cloud Spanner host. */
    public Write withHost(String host) {
      return withHost(ValueProvider.StaticValueProvider.of(host));
    }

    @VisibleForTesting
    Write withServiceFactory(ServiceFactory<Spanner, SpannerOptions> serviceFactory) {
      SpannerConfig config = getSpannerConfig();
      return withSpannerConfig(config.withServiceFactory(serviceFactory));
    }

    @VisibleForTesting
    Write withSampler(
        PTransform<PCollection<KV<String, byte[]>>, PCollection<KV<String, List<byte[]>>>>
            sampler) {
      return toBuilder().setSampler(sampler).build();
    }

    /**
     * Same transform but can be applied to {@link PCollection} of {@link MutationGroup}.
     */
    public WriteGrouped grouped() {
      return new WriteGrouped(this);
    }

    /** Specifies the batch size limit. */
    public Write withBatchSizeBytes(long batchSizeBytes) {
      return toBuilder().setBatchSizeBytes(batchSizeBytes).build();
    }

    /** Specifies failure mode. {@link FailureMode#FAIL_FAST} mode is selected by default. */
    public Write withFailureMode(FailureMode failureMode) {
      return toBuilder().setFailureMode(failureMode).build();
    }

    /** Specifies the cell mutation limit. */
    public Write withMaxNumMutations(long maxNumMutations) {
      return toBuilder().setMaxNumMutations(maxNumMutations).build();
    }

    @Override
    public SpannerWriteResult expand(PCollection<Mutation> input) {
      getSpannerConfig().validate();

      return input
          .apply("To mutation group", ParDo.of(new ToMutationGroupFn()))
          .apply("Write mutations to Cloud Spanner", new WriteGrouped(this));
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      getSpannerConfig().populateDisplayData(builder);
      builder.add(
          DisplayData.item("batchSizeBytes", getBatchSizeBytes()).withLabel("Batch Size in Bytes"));
    }
  }

  /**
   * A singleton that wraps {@code UnsignedBytes#lexicographicalComparator} which unfortunately
   * is not serializable.
   */
  @VisibleForTesting
  enum SerializableBytesComparator implements Comparator<byte[]>, Serializable {
    INSTANCE {
      @Override public int compare(byte[] a, byte[] b) {
        return UnsignedBytes.lexicographicalComparator().compare(a, b);
      }
    }
  }

  /** Same as {@link Write} but supports grouped mutations. */
  public static class WriteGrouped
      extends PTransform<PCollection<MutationGroup>, SpannerWriteResult> {
    private final Write spec;

    public WriteGrouped(Write spec) {
      this.spec = spec;
    }

    @Override
    public SpannerWriteResult expand(PCollection<MutationGroup> input) {

      PTransform<PCollection<KV<String, byte[]>>, PCollection<KV<String, List<byte[]>>>>
          sampler = spec.getSampler();
      if (sampler == null) {
        sampler = createDefaultSampler();
      }
      // First, read the Cloud Spanner schema.
      final PCollectionView<SpannerSchema> schemaView =
          input.getPipeline()
              .apply("Create seed", Create.of((Void) null))
              // Wait for input mutations so it is possible to chain transforms.
              .apply(Wait.on(input))
              .apply(
                  "Read information schema",
                  ParDo.of(new ReadSpannerSchema(spec.getSpannerConfig())))
              .apply("Schema View", View.asSingleton());

      // Serialize mutations, we don't need to encode/decode them while reshuffling.
      // The primary key is encoded via OrderedCode so we can calculate quantiles.
      PCollection<SerializedMutation> serialized = input
          .apply("Serialize mutations",
              ParDo.of(new SerializeMutationsFn(schemaView)).withSideInputs(schemaView))
          .setCoder(SerializedMutationCoder.of());

      // Sample primary keys using ApproximateQuantiles.
      PCollectionView<Map<String, List<byte[]>>> keySample =
          serialized
              .apply("Extract keys", ParDo.of(new ExtractKeys()))
              .apply("Sample keys", sampler)
              .apply("Keys sample as view", View.asMap());

      TupleTag<Void> mainTag = new TupleTag<>("mainOut");
      TupleTag<MutationGroup> failedTag = new TupleTag<>("failedMutations");
      // Assign partition based on the closest element in the sample and group mutations.
      AssignPartitionFn assignPartitionFn = new AssignPartitionFn(keySample);
      PCollectionTuple result = serialized
          .apply("Partition input", ParDo.of(assignPartitionFn).withSideInputs(keySample))
          .setCoder(KvCoder.of(StringUtf8Coder.of(), SerializedMutationCoder.of()))
          .apply("Group by partition", GroupByKey.create())
          .apply("Batch mutations together", ParDo.of(new BatchFn(spec.getBatchSizeBytes(),
              spec.getMaxNumMutations(), spec.getSpannerConfig(), schemaView))
                  .withSideInputs(schemaView))
          .apply("Write mutations to Spanner",
              ParDo.of(new WriteToSpannerFn(spec.getSpannerConfig(), spec.getFailureMode(),
                  failedTag))
                  .withOutputTags(mainTag, TupleTagList.of(failedTag)));
      PCollection<MutationGroup> failedMutations = result.get(failedTag);
      failedMutations.setCoder(SerializableCoder.of(MutationGroup.class));
      return new SpannerWriteResult(input.getPipeline(), result.get(mainTag), failedMutations,
          failedTag);

    }

    private PTransform<PCollection<KV<String, byte[]>>, PCollection<KV<String, List<byte[]>>>>
        createDefaultSampler() {
      return Combine.perKey(ApproximateQuantiles.ApproximateQuantilesCombineFn
          .create(spec.getNumSamples(), SerializableBytesComparator.INSTANCE, MAX_NUM_KEYS,
              1. / spec.getNumSamples()));
    }
  }

  private static class ToMutationGroupFn extends DoFn<Mutation, MutationGroup> {
    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      Mutation value = c.element();
      c.output(MutationGroup.create(value));
    }
  }

  /**
   * Serializes mutations to ((table name, serialized key), serialized value) tuple.
   */
  private static class SerializeMutationsFn
      extends DoFn<MutationGroup, SerializedMutation> {

    final PCollectionView<SpannerSchema> schemaView;

    private SerializeMutationsFn(PCollectionView<SpannerSchema> schemaView) {
      this.schemaView = schemaView;
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
      MutationGroup g = c.element();
      Mutation m = g.primary();
      SpannerSchema schema = c.sideInput(schemaView);
      String table = m.getTable();
      MutationGroupEncoder mutationGroupEncoder = new MutationGroupEncoder(schema);

      byte[] key;
      if (m.getOperation() != Mutation.Op.DELETE) {
        key = mutationGroupEncoder.encodeKey(m);
      } else if (isPointDelete(m)) {
        Key next = m.getKeySet().getKeys().iterator().next();
        key = mutationGroupEncoder.encodeKey(m.getTable(), next);
      } else {
        // The key is left empty for non-point deletes, since there is no general way to batch them.
        key = new byte[] {};
      }
      byte[] value = mutationGroupEncoder.encode(g);
      c.output(SerializedMutation.create(table, key, value));
    }
  }

  private static class ExtractKeys
      extends DoFn<SerializedMutation, KV<String, byte[]>> {

    @ProcessElement
    public void processElement(ProcessContext c) {
      SerializedMutation m = c.element();
      c.output(KV.of(m.getTableName().toLowerCase(), m.getEncodedKey()));
    }
  }

  /**
   * Assigns a partition to the mutation group token based on the sampled data.
   */
  private static class AssignPartitionFn
      extends
      DoFn<SerializedMutation, KV<String, SerializedMutation>> {

    final PCollectionView<Map<String, List<byte[]>>> sampleView;

    public AssignPartitionFn(PCollectionView<Map<String, List<byte[]>>> sampleView) {
      this.sampleView = sampleView;
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
      Map<String, List<byte[]>> sample = c.sideInput(sampleView);
      SerializedMutation g = c.element();
      String table = g.getTableName().toLowerCase();
      byte[] key = g.getEncodedKey();
      String groupKey;
      if (key.length == 0) {
        // This is a range or multi-key delete mutation. We cannot group it with other mutations
        // so we assign a random group key to it so it is applied independently.
        groupKey = UUID.randomUUID().toString();
      } else {
        int partition = Collections
            .binarySearch(sample.get(table), key, SerializableBytesComparator.INSTANCE);
        if (partition < 0) {
          partition = -partition - 1;
        }
        groupKey = table + "%" + partition;
      }
      c.output(KV.of(groupKey, g));
    }
  }

  /**
   * Batches mutations together.
   */
  private static class BatchFn
      extends
      DoFn<KV<String, Iterable<SerializedMutation>>, Iterable<MutationGroup>> {

    private final long maxBatchSizeBytes;
    private final long maxNumMutations;
    private final SpannerConfig spannerConfig;
    private final PCollectionView<SpannerSchema> schemaView;

    private transient SpannerAccessor spannerAccessor;
    // Current batch of mutations to be written.
    private transient ImmutableList.Builder<MutationGroup> batch;
    // total size of the current batch.
    private long batchSizeBytes;
    // total number of mutated cells including indices.
    private long batchCells;

    private BatchFn(
        long maxBatchSizeBytes,
        long maxNumMutations,
        SpannerConfig spannerConfig,
        PCollectionView<SpannerSchema> schemaView) {
      this.maxBatchSizeBytes = maxBatchSizeBytes;
      this.maxNumMutations = maxNumMutations;
      this.spannerConfig = spannerConfig;
      this.schemaView = schemaView;
    }

    @Setup
    public void setup() {
      batch = ImmutableList.builder();
      batchSizeBytes = 0;
      batchCells = 0;
      spannerAccessor = spannerConfig.connectToSpanner();
    }

    @Teardown
    public void teardown() {
      spannerAccessor.close();
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      SpannerSchema spannerSchema = c.sideInput(schemaView);
      MutationGroupEncoder mutationGroupEncoder = new MutationGroupEncoder(spannerSchema);

      KV<String, Iterable<SerializedMutation>> element = c.element();
      for (SerializedMutation kv : element.getValue()) {
        byte[] value = kv.getMutationGroupBytes();
        MutationGroup mg = mutationGroupEncoder.decode(value);
        long groupSize = MutationSizeEstimator.sizeOf(mg);
        long groupCells = MutationCellCounter
            .countOf(spannerSchema, mg);
        if (batchCells + groupCells > maxNumMutations
            || batchSizeBytes + groupSize > maxBatchSizeBytes) {
          ImmutableList<MutationGroup> mutations = batch.build();
          c.output(mutations);
          batch = ImmutableList.builder();
          batchSizeBytes = 0;
          batchCells = 0;
        }
        batch.add(mg);
        batchSizeBytes += groupSize;
        batchCells += groupCells;
      }
      ImmutableList<MutationGroup> mutations = batch.build();
      if (!mutations.isEmpty()) {
        c.output(mutations);
        batch = ImmutableList.builder();
        batchSizeBytes = 0;
        batchCells = 0;
      }

    }
  }

  private static class WriteToSpannerFn
      extends DoFn<Iterable<MutationGroup>, Void> {

    private transient SpannerAccessor spannerAccessor;
    private final SpannerConfig spannerConfig;
    private final FailureMode failureMode;

    private final TupleTag<MutationGroup> failedTag;

    WriteToSpannerFn(SpannerConfig spannerConfig, FailureMode failureMode,
        TupleTag<MutationGroup> failedTag) {
      this.spannerConfig = spannerConfig;
      this.failureMode = failureMode;
      this.failedTag = failedTag;
    }

    @Setup
    public void setup() throws Exception {
      spannerAccessor = spannerConfig.connectToSpanner();
    }

    @Teardown
    public void teardown() throws Exception {
      spannerAccessor.close();
    }


    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      Iterable<MutationGroup> mutations = c.element();
      boolean tryIndividual = false;
      // Batch upsert rows.
      try {
        Iterable<Mutation> batch = Iterables.concat(mutations);
        spannerAccessor.getDatabaseClient().writeAtLeastOnce(batch);
      } catch (SpannerException e) {
        if (failureMode == FailureMode.REPORT_FAILURES) {
          tryIndividual = true;
        } else if (failureMode == FailureMode.FAIL_FAST) {
          throw e;
        } else {
          throw new IllegalArgumentException("Unknown failure mode " + failureMode);
        }
      }
      if (tryIndividual) {
        for (MutationGroup mg : mutations) {
          try {
            spannerAccessor.getDatabaseClient().writeAtLeastOnce(mg);
          } catch (SpannerException e) {
            LOG.warn("Failed to submit the mutation group", e);
            c.output(failedTag, mg);
          }
        }
      }
    }

  }

  private SpannerIO() {} // Prevent construction.
}
