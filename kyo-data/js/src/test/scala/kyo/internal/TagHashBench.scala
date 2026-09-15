package kyo.internal

import kyo.Chunk
import kyo.Tag
import scala.scalajs.js

/** Finite comparison of the original native-map lookup and the production TagHash path.
  * Run as a JS main after correctness tests; timings are observations, not test thresholds.
  */
object TagHashBench:

    private val Operations       = 1000000
    private val WarmupPairs      = 10
    private val MeasurementPairs = 25

    private object Baseline:
        // Unsafe: js.Map implements these exact native members; retain the original memo's
        // existing facade boundary. The public Option-returning wrapper allocates per lookup.
        private val memo = js.Map.empty[String, Int].asInstanceOf[RawTagMemo]

        def of(tag: Any): Int =
            tag match
                case tag: String =>
                    val cached = memo.get(tag)
                    if cached.isDefined then cached.get
                    else
                        val hash = tag.hashCode
                        memo.set(tag, hash)
                        hash
                    end if
                case tag => tag.hashCode
    end Baseline

    final private case class Sample(baselineNanos: Long, productionNanos: Long):
        def ratio: Double = productionNanos.toDouble / baselineNanos.toDouble

    private def literal[A](tag: Tag[A]): String =
        tag match
            case value: String => value
            case _             => throw new AssertionError("Benchmark input must be a derived static Tag")

    // Separate loops keep the benchmark from adding a function-value call to every lookup.
    // The only local mutation is the index/checksum; all four keys are visited equally.
    private def baseline(tags: Chunk.Indexed[String]): Int =
        var index    = 0
        var checksum = 0
        while index < Operations do
            checksum += Baseline.of(tags(index & 3))
            index += 1
        checksum
    end baseline

    private def production(tags: Chunk.Indexed[String]): Int =
        var index    = 0
        var checksum = 0
        while index < Operations do
            checksum += TagHash.of(tags(index & 3))
            index += 1
        checksum
    end production

    private def measure(body: => Int, expected: Int): Long =
        val start    = java.lang.System.nanoTime()
        val checksum = body
        val elapsed  = java.lang.System.nanoTime() - start
        assert(checksum == expected, s"Invalid hash checksum: $checksum != $expected")
        elapsed
    end measure

    private def pair(tags: Chunk.Indexed[String], expected: Int, baselineFirst: Boolean): Sample =
        if baselineFirst then
            val first  = measure(baseline(tags), expected)
            val second = measure(production(tags), expected)
            Sample(first, second)
        else
            val first  = measure(production(tags), expected)
            val second = measure(baseline(tags), expected)
            Sample(second, first)

    private def median(values: Chunk[Double]): Double =
        val sorted = values.sorted
        sorted(sorted.size / 2)

    private def run(name: String, tags: Chunk.Indexed[String]): Unit =
        assert(tags.size == 4 && tags.distinct.size == 4)
        tags.foreach { tag =>
            val expected = tag.hashCode
            assert(Baseline.of(tag) == expected)
            assert(TagHash.of(tag) == expected)
        }
        val expected = tags.foldLeft(0)((sum, tag) => sum + tag.hashCode) * (Operations / tags.size)
        (0 until WarmupPairs).foreach(index => pair(tags, expected, index % 2 == 0))
        val samples = Chunk.from(0 until MeasurementPairs).map { index =>
            val sample = pair(tags, expected, index % 2 == 0)
            println(
                s"TAG_HASH_BENCH sample=$name pair=$index baseline_first=${index % 2 == 0} " +
                    s"baseline_ns=${sample.baselineNanos} production_ns=${sample.productionNanos} " +
                    s"ratio=${sample.ratio} checksum=$expected"
            )
            sample
        }
        val baselineMedian   = median(samples.map(_.baselineNanos.toDouble))
        val productionMedian = median(samples.map(_.productionNanos.toDouble))
        println(
            s"TAG_HASH_BENCH summary=$name lengths=${tags.map(_.length).mkString(",")} operations=$Operations " +
                s"warmup_pairs=$WarmupPairs measured_pairs=$MeasurementPairs baseline_median_ns=$baselineMedian " +
                s"production_median_ns=$productionMedian ratio_of_medians=${productionMedian / baselineMedian} " +
                s"median_paired_ratio=${median(samples.map(_.ratio))} checksum=$expected"
        )
    end run

    def main(args: Array[String]): Unit =
        require(args.isEmpty, "This benchmark has a fixed finite workload")
        val small = Chunk.from(Array(
            literal(Tag[Int]),
            literal(Tag[Long]),
            literal(Tag[Boolean]),
            literal(Tag[Double])
        ))
        val large = Chunk.from(Array(
            literal(Tag[Map[String, List[Either[(Int, Long, Boolean), Vector[Option[Set[Char]]]]]]]),
            literal(Tag[Map[String, List[Either[(Int, Long, Double), Vector[Option[Set[Char]]]]]]]),
            literal(Tag[Map[String, List[Either[(Int, Long, Boolean), Vector[Option[Set[String]]]]]]]),
            literal(Tag[Map[String, List[Either[(Int, Long, Double), Vector[Option[Set[String]]]]]]])
        ))
        run("small", small)
        run("large", large)
    end main

end TagHashBench
