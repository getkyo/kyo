package kyo

class AtomicTest extends Test:

    "int" - {
        "should initialize to default value (0)" in run {
            for
                ref <- AtomicInt.init
                v   <- ref.get
            yield assert(v == 0)
        }
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
                v   <- ref.compareAndSet(5, 10)
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
        "should use current value with transformation" in run {
            for
                ref <- AtomicInt.init(5)
                v   <- ref.use(x => x * 2)
            yield assert(v == 10)
        }
        "should use new atomic with function" in run {
            for
                v <- AtomicInt.use(ref => ref.incrementAndGet)
            yield assert(v == 1)
        }
        "should use new atomic with initial value" in run {
            for
                v <- AtomicInt.use(5)(ref => ref.incrementAndGet)
            yield assert(v == 6)
        }
    }

    "long" - {
        "should initialize to default value (0)" in run {
            for
                ref <- AtomicLong.init
                v   <- ref.get
            yield assert(v == 0L)
        }
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
                v   <- ref.compareAndSet(5L, 10L)
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
        "should use current value with transformation" in run {
            for
                ref <- AtomicLong.init(5L)
                v   <- ref.use(x => x * 2)
            yield assert(v == 10L)
        }
        "should use new atomic with function" in run {
            for
                v <- AtomicLong.use(ref => ref.incrementAndGet)
            yield assert(v == 1L)
        }
        "should use new atomic with initial value" in run {
            for
                v <- AtomicLong.use(5L)(ref => ref.incrementAndGet)
            yield assert(v == 6L)
        }
    }

    "boolean" - {
        "should initialize to default value (false)" in run {
            for
                ref <- AtomicBoolean.init
                v   <- ref.get
            yield assert(v == false)
        }
        "should initialize to the provided value" in run {
            for
                ref <- AtomicBoolean.init(true)
                v   <- ref.get
            yield assert(v)
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
                v   <- ref.compareAndSet(true, false)
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
        "should use current value with transformation" in run {
            for
                ref <- AtomicBoolean.init(true)
                v   <- ref.use(x => !x)
            yield assert(!v)
        }
        "should use new atomic with function" in run {
            for
                v <- AtomicBoolean.use(ref => ref.get)
            yield assert(!v) // default value is false
        }
        "should use new atomic with initial value" in run {
            for
                v <- AtomicBoolean.use(true)(ref => ref.get)
            yield assert(v)
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
                v   <- ref.compareAndSet("initial", "new")
                r   <- ref.get
            yield
                assert(v == true)
                assert(r == "new")
        }
        "should fail compare and set the value" in run {
            for
                ref <- AtomicRef.init("initial")
                v   <- ref.compareAndSet("not-initial", "new")
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
        "should use current value with transformation" in run {
            for
                ref <- AtomicRef.init("hello")
                v   <- ref.use(x => x.toUpperCase)
            yield assert(v == "HELLO")
        }
        "should use new atomic with initial value" in run {
            for
                v <- AtomicRef.use("hello")(ref => ref.get)
            yield assert(v == "hello")
        }
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        "int" - {
            "should initialize to default value (0)" in {
                val ref = AtomicInt.Unsafe.init
                assert(ref.get() == 0)
            }
            "should initialize to the provided value" in {
                val ref = AtomicInt.Unsafe.init(5)
                assert(ref.get() == 5)
            }
            "should set the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                ref.set(10)
                assert(ref.get() == 10)
            }
            "should compare and set the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                val v   = ref.compareAndSet(5, 10)
                assert(v == true)
                assert(ref.get() == 10)
            }
            "should increment and get the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                val v   = ref.incrementAndGet()
                assert(v == 6)
            }
            "should get and increment the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                val v   = ref.getAndIncrement()
                assert(v == 5)
                assert(ref.get() == 6)
            }
            "should decrement and get the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                val v   = ref.decrementAndGet()
                assert(v == 4)
            }
            "should get and decrement the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                val v   = ref.getAndDecrement()
                assert(v == 5)
                assert(ref.get() == 4)
            }
            "should add and get the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                val v   = ref.addAndGet(5)
                assert(v == 10)
            }
            "should get and add the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                val v   = ref.getAndAdd(5)
                assert(v == 5)
                assert(ref.get() == 10)
            }
            "should lazy set the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                ref.lazySet(10)
                assert(ref.get() == 10)
            }
            "should get and set the value" in {
                val ref = AtomicInt.Unsafe.init(5)
                val v   = ref.getAndSet(10)
                assert(v == 5)
                assert(ref.get() == 10)
            }
        }

        "long" - {
            "should initialize to default value (0)" in {
                val ref = AtomicLong.Unsafe.init
                assert(ref.get() == 0L)
            }
            "should initialize to the provided value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                assert(ref.get() == 5L)
            }
            "should set the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                ref.set(10L)
                assert(ref.get() == 10L)
            }
            "should compare and set the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                val v   = ref.compareAndSet(5L, 10L)
                assert(v == true)
                assert(ref.get() == 10L)
            }
            "should increment and get the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                val v   = ref.incrementAndGet()
                assert(v == 6L)
            }
            "should get and increment the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                val v   = ref.getAndIncrement()
                assert(v == 5L)
                assert(ref.get() == 6L)
            }
            "should decrement and get the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                val v   = ref.decrementAndGet()
                assert(v == 4L)
            }
            "should get and decrement the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                val v   = ref.getAndDecrement()
                assert(v == 5L)
                assert(ref.get() == 4L)
            }
            "should add and get the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                val v   = ref.addAndGet(5L)
                assert(v == 10L)
            }
            "should get and add the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                val v   = ref.getAndAdd(5L)
                assert(v == 5L)
                assert(ref.get() == 10L)
            }
            "should lazy set the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                ref.lazySet(10L)
                assert(ref.get() == 10L)
            }
            "should get and set the value" in {
                val ref = AtomicLong.Unsafe.init(5L)
                val v   = ref.getAndSet(10L)
                assert(v == 5L)
                assert(ref.get() == 10L)
            }
        }
    }
end AtomicTest
