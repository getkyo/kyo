package kyoTest.concurrent

import kyo.core._
import kyo.concurrent.sums._

import kyoTest.KyoTest

class sumsTest extends KyoTest {

  "LongSum" - {
    "should initialize to 0" in {
      for {
        ref <- LongSum()
        v   <- ref.get
      } yield assert(v == 0)
    }
    "should add value" in {
      for {
        ref <- LongSum()
        _   <- ref.add(5)
        v   <- ref.get
      } yield assert(v == 5)
    }
    "should decrement the value" in {
      for {
        ref <- LongSum()
        _   <- ref.add(5)
        _   <- ref.decrement
        v   <- ref.get
      } yield assert(v == 4)
    }
    "should reset the value" in {
      for {
        ref <- LongSum()
        _   <- ref.add(5)
        _   <- ref.reset
        v   <- ref.get
      } yield assert(v == 0)
    }
  }

  "DoubleSum" - {
    "should initialize to 0" in {
      for {
        ref <- DoubleSum()
        v   <- ref.get
      } yield assert(v == 0.0)
    }
    "should add value" in {
      for {
        ref <- DoubleSum()
        _   <- ref.add(5.0)
        v   <- ref.get
      } yield assert(v == 5.0)
    }
    "should reset the value" in {
      for {
        ref <- DoubleSum()
        _   <- ref.add(5.0)
        _   <- ref.reset
        v   <- ref.get
      } yield assert(v == 0.0)
    }
  }
}
