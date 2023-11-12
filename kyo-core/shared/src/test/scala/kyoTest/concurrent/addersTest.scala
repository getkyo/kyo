package kyoTest.concurrent

import kyo.concurrent.adders._
import kyo._
import kyoTest.KyoTest

class addersTest extends KyoTest {

  "LongAdder" - {
    "should initialize to 0" in run {
      for {
        ref <- Adders.initLong
        v   <- ref.get
      } yield assert(v == 0)
    }
    "should add value" in run {
      for {
        ref <- Adders.initLong
        _   <- ref.add(5)
        v   <- ref.get
      } yield assert(v == 5)
    }
    "should increment the value" in run {
      for {
        ref <- Adders.initLong
        _   <- ref.add(5)
        _   <- ref.increment
        v   <- ref.get
      } yield assert(v == 6)
    }
    "should decrement the value" in run {
      for {
        ref <- Adders.initLong
        _   <- ref.add(5)
        _   <- ref.decrement
        v   <- ref.get
      } yield assert(v == 4)
    }
    "should reset the value" in run {
      for {
        ref <- Adders.initLong
        _   <- ref.add(5)
        _   <- ref.reset
        v   <- ref.get
      } yield assert(v == 0)
    }
  }

  "DoubleAdder" - {
    "should initialize to 0" in run {
      for {
        ref <- Adders.initDouble
        v   <- ref.get
      } yield assert(v == 0.0)
    }
    "should add value" in run {
      for {
        ref <- Adders.initDouble
        _   <- ref.add(5.0)
        v   <- ref.get
      } yield assert(v == 5.0)
    }
    "should reset the value" in run {
      for {
        ref <- Adders.initDouble
        _   <- ref.add(5.0)
        _   <- ref.reset
        v   <- ref.get
      } yield assert(v == 0.0)
    }
  }
}
