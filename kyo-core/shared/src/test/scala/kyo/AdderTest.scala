package kyo

class AdderTest extends Test:

    "long" - {
        "should initialize to 0" in run {
            for
                ref <- LongAdder.init
                v   <- ref.get
            yield assert(v == 0)
        }
        "should add value" in run {
            for
                ref <- LongAdder.init
                _   <- ref.add(5)
                v   <- ref.get
            yield assert(v == 5)
        }
        "should increment the value" in run {
            for
                ref <- LongAdder.init
                _   <- ref.add(5)
                _   <- ref.increment
                v   <- ref.get
            yield assert(v == 6)
        }
        "should decrement the value" in run {
            for
                ref <- LongAdder.init
                _   <- ref.add(5)
                _   <- ref.decrement
                v   <- ref.get
            yield assert(v == 4)
        }
        "should reset the value" in run {
            for
                ref <- LongAdder.init
                _   <- ref.add(5)
                _   <- ref.reset
                v   <- ref.get
            yield assert(v == 0)
        }
        "should sum and reset the value" in run {
            for
                ref <- LongAdder.init
                _   <- ref.add(5)
                v1  <- ref.sumThenReset
                v2  <- ref.get
            yield assert(v1 == 5 && v2 == 0)
        }
    }

    "double" - {
        "should initialize to 0" in run {
            for
                ref <- DoubleAdder.init
                v   <- ref.get
            yield assert(v == 0.0)
        }
        "should add value" in run {
            for
                ref <- DoubleAdder.init
                _   <- ref.add(5.0)
                v   <- ref.get
            yield assert(v == 5.0)
        }
        "should reset the value" in run {
            for
                ref <- DoubleAdder.init
                _   <- ref.add(5.0)
                _   <- ref.reset
                v   <- ref.get
            yield assert(v == 0.0)
        }
        "should sum and reset the value" in run {
            for
                ref <- DoubleAdder.init
                _   <- ref.add(5)
                v1  <- ref.sumThenReset
                v2  <- ref.get
            yield assert(v1 == 5 && v2 == 0)
        }
    }
end AdderTest
