package kyoTest

import kyo.*

class addersTest extends KyoTest:

    "LongAdder" - {
        "should initialize to 0" in IOs.run {
            for
                ref <- Adders.initLong
                v   <- ref.get
            yield assert(v == 0)
        }
        "should add value" in IOs.run {
            for
                ref <- Adders.initLong
                _   <- ref.add(5)
                v   <- ref.get
            yield assert(v == 5)
        }
        "should increment the value" in IOs.run {
            for
                ref <- Adders.initLong
                _   <- ref.add(5)
                _   <- ref.increment
                v   <- ref.get
            yield assert(v == 6)
        }
        "should decrement the value" in IOs.run {
            for
                ref <- Adders.initLong
                _   <- ref.add(5)
                _   <- ref.decrement
                v   <- ref.get
            yield assert(v == 4)
        }
        "should reset the value" in IOs.run {
            for
                ref <- Adders.initLong
                _   <- ref.add(5)
                _   <- ref.reset
                v   <- ref.get
            yield assert(v == 0)
        }
        "should sum and reset the value" in IOs.run {
            for
                ref <- Adders.initLong
                _   <- ref.add(5)
                v1  <- ref.sumThenReset
                v2  <- ref.get
            yield assert(v1 == 5 && v2 == 0)
        }
    }

    "DoubleAdder" - {
        "should initialize to 0" in IOs.run {
            for
                ref <- Adders.initDouble
                v   <- ref.get
            yield assert(v == 0.0)
        }
        "should add value" in IOs.run {
            for
                ref <- Adders.initDouble
                _   <- ref.add(5.0)
                v   <- ref.get
            yield assert(v == 5.0)
        }
        "should reset the value" in IOs.run {
            for
                ref <- Adders.initDouble
                _   <- ref.add(5.0)
                _   <- ref.reset
                v   <- ref.get
            yield assert(v == 0.0)
        }
    }
end addersTest
