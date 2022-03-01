/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.benchmark

import org.apache.spark.SparkConf
import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.sql.internal.SQLConf

/**
 * Benchmark to measure read performance with Filter pushdown.
 * To run this benchmark:
 * {{{
 *   1. without sbt: bin/spark-submit --class <this class>
 *      --jars <spark core test jar>,<spark catalyst test jar> <spark sql test jar>
 *   2. build/sbt "sql/test:runMain <this class>"
 *   3. generate result: SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain <this class>"
 *      Results will be written to "benchmarks/FilterPushdownBenchmark-results.txt".
 * }}}
 */
object FilterPushdownBenchmark extends SqlBasedBenchmark {

  override def getSparkSession: SparkSession = {
    val conf = new SparkConf()
      .setAppName(this.getClass.getSimpleName)
      // Since `spark.master` always exists, overrides this value
      .set("spark.master", "local[1]")
      .setIfMissing("spark.driver.memory", "3g")
      .setIfMissing("spark.executor.memory", "3g")
      .setIfMissing("orc.compression", "snappy")
      .setIfMissing("spark.sql.sources.useV1SourceList", "")
      .setIfMissing("spark.sql.parquet.compression.codec", "snappy")

    SparkSession.builder().config(conf).getOrCreate()
  }

  private val numRows = 1024 * 1024 * 15
  // For Parquet/ORC, we will use the same value for block size and compression size
  private val blockSize = org.apache.parquet.hadoop.ParquetWriter.DEFAULT_PAGE_SIZE

  def withTempTable(tableNames: String*)(f: => Unit): Unit = {
    try f finally tableNames.foreach(spark.catalog.dropTempView)
  }

  def filterPushDownBenchmark(
      values: Int,
      title: String,
      whereExpr: String,
      selectExpr: String = "*"): Unit = {
    val benchmark = new Benchmark(title, values, minNumIters = 5, output = output)

    Seq(false, true).foreach { pushDownEnabled =>
      val name = s"Parquet Vectorized with partition ${if (pushDownEnabled) s"(Pushdown)" else ""}"
      benchmark.addCase(name) { _ =>
        withSQLConf(SQLConf.PARQUET_FILTER_PUSHDOWN_ENABLED.key -> "true",
          SQLConf.PARQUET_FILTER_PUSHDOWN_PARTITION_ENABLED.key -> s"$pushDownEnabled") {
          spark.sql(s"SELECT $selectExpr FROM parquetTable WHERE $whereExpr").noop()
        }
      }
    }

    benchmark.run()
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    runBenchmark("Pushdown benchmark for Partition Filter") {
      withTempPath { dir =>
        withTempTable("parquetTable") {
          val df = spark.range(numRows).withColumnRenamed("id", "a")
            .withColumn("b", col("a"))
          val partDf = df.withColumn("part", lit(0))
            .union(df.withColumn("part", lit(1)))
            .union(df.withColumn("part", lit(2)))
          partDf.write.mode("overwrite")
            .partitionBy("part")
            .option("parquet.block.size", blockSize).parquet(dir.getAbsolutePath)
          spark.read.parquet(dir.getAbsolutePath).createOrReplaceTempView("parquetTable")
          Seq("(a = 10 and part = 0) or (a = 10240 and part = 1) or (part = 2)",
          "(a > 10 and part = 0) or (a <= 10 and part >=1 and part < 3)")
            .foreach { whereExpr =>
              val title = s"Data filter with partitions: (${whereExpr})"
                filterPushDownBenchmark(numRows, title, whereExpr)
            }
        }
      }
    }
  }
}
