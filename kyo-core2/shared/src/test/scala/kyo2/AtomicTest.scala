package kyo2

class AtomicTest extends Test:

    "int" - {
        "should initialize to the provided value" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.get
            yield assert(v == 5)
        }
        "should set the value" in run {
            for
                ref <- AtomicInt.init(5)
                _   <- ref.set(10)
                v   <- ref.get
            yield assert(v == 10)
        }
        "should compare and set the value" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.cas(5, 10)
                r   <- ref.get
            yield
                assert(v == true)
                assert(r == 10)
        }
        "should increment and get the value" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.incrementAndGet
            yield assert(v == 6)
        }
        "should get and increment the value" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.getAndIncrement
            yield assert(v == 5)
        }
        "should decrement and get the value" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.decrementAndGet
            yield assert(v == 4)
        }
        "should get and decrement the value" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.getAndDecrement
            yield assert(v == 5)
        }
        "should add and get the value" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.addAndGet(5)
            yield assert(v == 10)
        }
        "should get and add the value" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.getAndAdd(5)
            yield assert(v == 5)
        }
        "lazySet" in run {
            for
                ref <- AtomicInt.init(5)
                _   <- ref.lazySet(5)
                v   <- ref.get
            yield assert(v == 5)
        }
        "getAndSet" in run {
            for
                ref <- AtomicInt.init(5)
                v1  <- ref.getAndSet(6)
                v2  <- ref.get
            yield assert(v1 == 5 && v2 == 6)
        }
    }

    "long" - {
        "should initialize to the provided value" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.get
            yield assert(v == 5L)
        }
        "should set the value" in run {
            for
                ref <- AtomicLong.init(5L)
                _   <- ref.set(10L)
                v   <- ref.get
            yield assert(v == 10L)
        }
        "should compare and set the value" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.cas(5L, 10L)
                r   <- ref.get
            yield
                assert(v == true)
                assert(r == 10L)
        }
        "should increment and get the value" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.incrementAndGet
            yield assert(v == 6L)
        }
        "should get and increment the value" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.getAndIncrement
            yield assert(v == 5L)
        }
        "should decrement and get the value" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.decrementAndGet
            yield assert(v == 4L)
        }
        "should get and decrement the value" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.getAndDecrement
            yield assert(v == 5L)
        }
        "should add and get the value" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.addAndGet(5L)
            yield assert(v == 10L)
        }
        "should get and add the value" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.getAndAdd(5L)
            yield assert(v == 5L)
        }
        "lazySet" in run {
            for
                ref <- AtomicLong.init(5)
                _   <- ref.lazySet(5)
                v   <- ref.get
            yield assert(v == 5)
        }
        "getAndSet" in run {
            for
                ref <- AtomicLong.init(5)
                v1  <- ref.getAndSet(6)
                v2  <- ref.get
            yield assert(v1 == 5 && v2 == 6)
        }
    }

    "boolean" - {
        "should initialize to the provided value" in run {
            for
                ref <- AtomicBoolean.init(true)
                v   <- ref.get
            yield assert(v == true)
        }
        "should set the value" in run {
            for
                ref <- AtomicBoolean.init(true)
                _   <- ref.set(false)
                v   <- ref.get
            yield assert(v == false)
        }
        "should compare and set the value" in run {
            for
                ref <- AtomicBoolean.init(true)
                v   <- ref.cas(true, false)
                r   <- ref.get
            yield
                assert(v == true)
                assert(r == false)
        }
        "lazySet" in run {
            for
                ref <- AtomicBoolean.init(true)
                _   <- ref.lazySet(false)
                v   <- ref.get
            yield assert(!v)
        }
        "getAndSet" in run {
            for
                ref <- AtomicBoolean.init(true)
                v1  <- ref.getAndSet(false)
                v2  <- ref.get
            yield assert(v1 && !v2)
        }
    }

    "ref" - {
        "should initialize to the provided value" in run {
            for
                ref <- AtomicRef.init("initial")
                v   <- ref.get
            yield assert(v == "initial")
        }
        "should set the value" in run {
            for
                ref <- AtomicRef.init("initial")
                _   <- ref.set("new")
                v   <- ref.get
            yield assert(v == "new")
        }
        "should compare and set the value" in run {
            for
                ref <- AtomicRef.init("initial")
                v   <- ref.cas("initial", "new")
                r   <- ref.get
            yield
                assert(v == true)
                assert(r == "new")
        }
        "should fail compare and set the value" in run {
            for
                ref <- AtomicRef.init("initial")
                v   <- ref.cas("not-initial", "new")
                r   <- ref.get
            yield
                assert(v == false)
                assert(r == "initial")
        }
        "should get and set the value" in run {
            for
                ref <- AtomicRef.init("initial")
                v   <- ref.getAndSet("new")
                r   <- ref.get
            yield
                assert(v == "initial")
                assert(r == "new")
        }
        "should lazy set the value" in run {
            for
                ref <- AtomicRef.init("initial")
                _   <- ref.lazySet("new")
                v   <- ref.get
            yield assert(v == "new")
        }
    }
end AtomicTest
