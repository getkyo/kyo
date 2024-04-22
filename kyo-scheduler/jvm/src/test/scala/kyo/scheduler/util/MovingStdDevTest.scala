package kyo.scheduler.util

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class MovingStdDevTest extends AnyFreeSpec with NonImplicitAssertions:

    "incomplete window" in {
        val movingStdDev = new MovingStdDev(4)
        val values       = Seq(2L, 4L)
        values.foreach(movingStdDev.observe)
        assert(movingStdDev.avg() == 3.0)
        assert((movingStdDev.dev() * 100).toInt == 141)
    }

    "complete window" in {
        val movingStdDev = new MovingStdDev(4)
        val values       = Seq(2L, 4L, 5L, 7L)
        values.foreach(movingStdDev.observe)
        assert(movingStdDev.avg() == 4.5)
        assert((movingStdDev.dev() * 100).toInt == 208)
    }

    "moving window" in {
        val movingStdDev = new MovingStdDev(4)
        val values       = Seq(2L, 4L, 5L, 7L, 9L, 10L)
        values.foreach(movingStdDev.observe)
        assert(movingStdDev.avg() == 7.75)
        assert((movingStdDev.dev() * 100).toInt == 221)
    }

    "window size of one" in {
        val movingStdDev = new MovingStdDev(1)
        movingStdDev.observe(10L)
        assert(movingStdDev.avg() == 10.0)
        assert(movingStdDev.dev() == 0.0)
    }

    "all observations the same" in {
        val movingStdDev = new MovingStdDev(5)
        (1 to 5).foreach(_ => movingStdDev.observe(7L))
        assert(movingStdDev.avg() == 7.0)
        assert(movingStdDev.dev() == 0.0)
    }

    "zeros and negative numbers" in {
        val movingStdDev = new MovingStdDev(3)
        Seq(0L, -1L, -5L).foreach(movingStdDev.observe)
        assert((movingStdDev.avg() * 100).toInt == -233)
        assert((movingStdDev.dev() * 100).toInt == 230)
    }

end MovingStdDevTest
