package kyo.test.snapshot

import kyo.Schema
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for `GoldenConfig`: the builder's defaults, immutable accumulation, and normalize composition order.
  *
  * All assertions are synchronous plain-value checks (no file I/O, no Sync/Async boundary); uses ScalaTest directly, mirroring
  * `SnapshotConfigTest`.
  */
class GoldenConfigTest extends AnyFunSuite with NonImplicitAssertions:

    case class Point(x: Int, y: Int) derives CanEqual, Schema

    test("the empty config from GoldenConfig.apply has defaults sampleCount 20, seed 0L, size 10, and identity normalization") {
        val config = GoldenConfig.apply[Point]
        assert(config.sampleCount == 20, s"Expected sampleCount 20, got ${config.sampleCount}")
        assert(config.seed == 0L, s"Expected seed 0L, got ${config.seed}")
        assert(config.size == 10, s"Expected size 10, got ${config.size}")
        val result = config.modify.applyTo(Point(1, 2))
        assert(result == Point(1, 2), s"Expected Point(1, 2) unchanged (identity normalization), got $result")
    }

    test("each setter returns a fresh instance, leaving the original unchanged (immutable additive)") {
        val cfg1 = GoldenConfig.apply[Point].sampleCount(5)
        val cfg2 = cfg1.seed(9L)
        assert(cfg1.seed == 0L, s"Expected original cfg1.seed unchanged at 0L, got ${cfg1.seed}")
        assert(cfg2.sampleCount == 5, s"Expected cfg2 to retain the accumulated sampleCount 5, got ${cfg2.sampleCount}")
        assert(cfg2.seed == 9L, s"Expected cfg2.seed 9L, got ${cfg2.seed}")
        assert(cfg1 ne cfg2, "Expected cfg1 and cfg2 to be distinct instances")
    }

    test("normalize composes in call order: f1 then f2, not reversed") {
        val config = GoldenConfig.apply[Point].normalize(_.set(_.x)(1)).normalize(_.set(_.x)(2))
        val result = config.modify.applyTo(Point(0, 0))
        assert(result == Point(2, 0), s"Expected x==2 (f2 applied after f1, left-to-right accumulation), got $result")
    }

    test("each of the four setters threads the other three fields unchanged (field-preservation matrix)") {
        val base = GoldenConfig.apply[Point].sampleCount(7).seed(3L).size(5).normalize(_.set(_.x)(9))

        val viaSampleCount = base.sampleCount(50)
        assert(viaSampleCount.sampleCount == 50, s"Expected sampleCount 50, got ${viaSampleCount.sampleCount}")
        assert(viaSampleCount.seed == 3L, s"Expected seed unchanged at 3L, got ${viaSampleCount.seed}")
        assert(viaSampleCount.size == 5, s"Expected size unchanged at 5, got ${viaSampleCount.size}")
        assert(
            viaSampleCount.modify.applyTo(Point(0, 0)) == Point(9, 0),
            s"Expected normalize unchanged (x==9), got ${viaSampleCount.modify.applyTo(Point(0, 0))}"
        )

        val viaSeed = base.seed(50L)
        assert(viaSeed.seed == 50L, s"Expected seed 50L, got ${viaSeed.seed}")
        assert(viaSeed.sampleCount == 7, s"Expected sampleCount unchanged at 7, got ${viaSeed.sampleCount}")
        assert(viaSeed.size == 5, s"Expected size unchanged at 5, got ${viaSeed.size}")
        assert(
            viaSeed.modify.applyTo(Point(0, 0)) == Point(9, 0),
            s"Expected normalize unchanged (x==9), got ${viaSeed.modify.applyTo(Point(0, 0))}"
        )

        val viaSize = base.size(50)
        assert(viaSize.size == 50, s"Expected size 50, got ${viaSize.size}")
        assert(viaSize.sampleCount == 7, s"Expected sampleCount unchanged at 7, got ${viaSize.sampleCount}")
        assert(viaSize.seed == 3L, s"Expected seed unchanged at 3L, got ${viaSize.seed}")
        assert(
            viaSize.modify.applyTo(Point(0, 0)) == Point(9, 0),
            s"Expected normalize unchanged (x==9), got ${viaSize.modify.applyTo(Point(0, 0))}"
        )

        val viaNormalize = base.normalize(_.set(_.y)(4))
        assert(viaNormalize.sampleCount == 7, s"Expected sampleCount unchanged at 7, got ${viaNormalize.sampleCount}")
        assert(viaNormalize.seed == 3L, s"Expected seed unchanged at 3L, got ${viaNormalize.seed}")
        assert(viaNormalize.size == 5, s"Expected size unchanged at 5, got ${viaNormalize.size}")
        assert(
            viaNormalize.modify.applyTo(Point(0, 0)) == Point(9, 4),
            s"Expected both normalize passes composed (x==9 from base, y==4 from this call), got ${viaNormalize.modify.applyTo(Point(0, 0))}"
        )
    }

end GoldenConfigTest
