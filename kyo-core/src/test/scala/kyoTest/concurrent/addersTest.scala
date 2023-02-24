package kyoTest.concurrent

import kyo.core._
import kyo.concurrent.adders._

import kyoTest.KyoTest

class addersTest extends KyoTest {

  "LongAdder" - {
    "should initialize to 0" in {
      for {
        ref <- Adders.makeLong
        v   <- ref.get
      } yield assert(v == 0)
    }
    "should add value" in {
      for {
        ref <- Adders.makeLong
        _   <- ref.add(5)
        v   <- ref.get
      } yield assert(v == 5)
    }
    "should decrement the value" in {
      for {
        ref <- Adders.makeLong
        _   <- ref.add(5)
        _   <- ref.decrement
        v   <- ref.get
      } yield assert(v == 4)
    }
    "should reset the value" in {
      for {
        ref <- Adders.makeLong
        _   <- ref.add(5)
        _   <- ref.reset
        v   <- ref.get
      } yield assert(v == 0)
    }
  }

  "DoubleAdder" - {
    "should initialize to 0" in {
      for {
        ref <- Adders.makeDouble
        v   <- ref.get
      } yield assert(v == 0.0)
    }
    "should add value" in {
      for {
        ref <- Adders.makeDouble
        _   <- ref.add(5.0)
        v   <- ref.get
      } yield assert(v == 5.0)
    }
    "should reset the value" in {
      for {
        ref <- Adders.makeDouble
        _   <- ref.add(5.0)
        _   <- ref.reset
        v   <- ref.get
      } yield assert(v == 0.0)
    }
  }
}
