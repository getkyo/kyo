package kyo.test.snapshot

import kyo.Schema
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for `SnapshotConfig`: the builder's identity, accumulate, and scrub semantics.
  *
  * All assertions are synchronous plain-value checks (no file I/O, no Sync/Async boundary); uses ScalaTest directly, mirroring
  * `SnapshotCodecTest`.
  */
class SnapshotConfigTest extends AnyFunSuite with NonImplicitAssertions:

    case class Point(x: Int, y: Int) derives CanEqual, Schema
    case class Event(id: Int, ts: Long) derives CanEqual, Schema

    test("the empty config from SnapshotConfig.apply is identity normalization") {
        val config = SnapshotConfig.apply[Point]
        val result = config.modify.applyTo(Point(1, 2))
        assert(result == Point(1, 2), s"Expected Point(1, 2) unchanged, got $result")
    }

    test("normalize accumulates: .normalize(setX).normalize(setY) applies BOTH passes, not just the last") {
        val config = SnapshotConfig.apply[Point].normalize(_.set(_.x)(7)).normalize(_.set(_.y)(9))
        val result = config.modify.applyTo(Point(1, 2))
        assert(result == Point(7, 9), s"Expected Point(7, 9) (both passes applied), got $result")
    }

    test("the built Modify scrubs a real field: normalize(_.set(_.ts)(0L)) zeroes the timestamp") {
        val config = SnapshotConfig.apply[Event].normalize(_.set(_.ts)(0L))
        val result = config.modify.applyTo(Event(1, 999L))
        assert(result == Event(1, 0L), s"Expected Event(1, 0L) with ts scrubbed, got $result")
    }

end SnapshotConfigTest
