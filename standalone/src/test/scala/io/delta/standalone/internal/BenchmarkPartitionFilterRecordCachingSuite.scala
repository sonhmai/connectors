package io.delta.standalone.internal

import java.io.File

import scala.collection.mutable

import org.apache.hadoop.conf.Configuration
import org.scalatest.{FunSuite, Ignore}

import io.delta.standalone.Operation
import io.delta.standalone.expressions.{And, EqualTo, Literal}
import io.delta.standalone.types._

import io.delta.standalone.internal.actions.{AddFile, Metadata}
import io.delta.standalone.internal.logging.Logging
import io.delta.standalone.internal.sources.StandaloneHadoopConf
import io.delta.standalone.internal.util.TestUtils._

@Ignore
class BenchmarkPartitionFilterRecordCachingSuite extends FunSuite with Logging {

  private val op = new Operation(Operation.Name.WRITE)

  private val schema = new StructType(Array(
    new StructField("col1", new StringType(), true),
    new StructField("col2", new StringType(), true),
    new StructField("col3", new StringType(), true),
    new StructField("col4", new IntegerType(), true)
  ))

  private val partitionSchema = new StructType(Array(
    new StructField("col1", new StringType(), true),
    new StructField("col2", new StringType(), true),
    new StructField("col3", new StringType(), true),
  ))

  private val metadata = Metadata(
    partitionColumns = partitionSchema.getFieldNames,
    schemaString = schema.toJson
  )

  private val addFiles = (1 to 10000).map { i =>
    val partitionValues = Map(
      "col1" -> (i % 2).toString,
      "col2" -> (i % 3).toString,
      "col3" -> (i % 2).toString,
    )
    AddFile(i.toString, partitionValues, 1L, 1L, dataChange = true)
  }

  private val filter = new And(
    new EqualTo(partitionSchema.column("col3"), Literal.of("1")),
    new And(
      new EqualTo(partitionSchema.column("col1"), Literal.of("1")),
      new EqualTo(partitionSchema.column("col2"), Literal.of("2"))
    )
  )

  private def scanAndMeasureTime(configuration: Configuration, file: File): Long = {
    val deltaLog = DeltaLogImpl.forTable(configuration, file.getCanonicalPath)
    deltaLog.startTransaction().commit(metadata :: Nil, op, "engineInfo")
    deltaLog.startTransaction().commit(addFiles, op, "engineInfo")
    val scan = deltaLog.update().scan(filter)

    val start = System.nanoTime()

    val iter = scan.getFiles
    while (iter.hasNext) {
      iter.hasNext
      iter.next()
    }
    iter.close()

    val elapsed = System.nanoTime() - start
    elapsed
  }

  test("micro-benchmark with/ without partition filter record caching") {
    val conf = new Configuration()
    val confDisabledCaching = new Configuration()
    confDisabledCaching.setBoolean(StandaloneHadoopConf.PARTITION_FILTER_RECORD_CACHING_KEY, false)

    val elapsedTimesWithCaching = mutable.ArrayBuffer.empty[Long]
    val elapsedTimesWithoutCaching = mutable.ArrayBuffer.empty[Long]


    (1 to 200).foreach { _ =>
      withTempDir { dir =>
        val elapsed = scanAndMeasureTime(conf, dir)
        elapsedTimesWithCaching.append(elapsed)
      }

      withTempDir { dir =>
        val elapsed = scanAndMeasureTime(confDisabledCaching, dir)
        elapsedTimesWithoutCaching.append(elapsed)
      }
    }

    val totalTimesCaching = elapsedTimesWithCaching.sum
    val totalTimesNoCaching = elapsedTimesWithoutCaching.sum

    assert( totalTimesCaching < totalTimesNoCaching)
  }
}
