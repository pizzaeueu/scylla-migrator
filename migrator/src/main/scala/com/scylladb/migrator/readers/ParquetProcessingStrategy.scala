package com.scylladb.migrator.readers

import com.scylladb.migrator.config.{ MigratorConfig, SourceSettings, TargetSettings }
import org.apache.spark.sql.SparkSession

/**
  * Strategy interface for processing Parquet files during migration.
  *
  * Two implementations are provided:
  * - ParallelParquetStrategy: Reads all files at once with maximum parallelism (no savepoints)
  * - SequentialParquetStrategy: Processes files one-by-one with savepoint support
  */
trait ParquetProcessingStrategy {

  /**
    * Execute the Parquet-to-Scylla migration using this strategy.
    *
    * @param config Complete migrator configuration
    * @param source Parquet source configuration
    * @param target Scylla target configuration
    * @param spark Implicit Spark session
    */
  def migrate(config: MigratorConfig,
              source: SourceSettings.Parquet,
              target: TargetSettings.Scylla)(implicit spark: SparkSession): Unit
}
