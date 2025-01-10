package kyo

class TRefTest extends Test:

    "init and get" in run {
        for
            ref   <- TRef.init(42)
            value <- STM.run(ref.get)
        yield assert(value == 42)
    }

    "set and get" in run {
        for
            ref   <- TRef.init(42)
            _     <- STM.run(ref.set(100))
            value <- STM.run(ref.get)
        yield assert(value == 100)
    }

    "multiple operations in transaction" in run {
        for
            ref1 <- TRef.init(10)
            ref2 <- TRef.init(20)
            result <- STM.run {
                for
                    v1 <- ref1.get
                    v2 <- ref2.get
                    _  <- ref1.set(v2)
                    _  <- ref2.set(v1)
                    r1 <- ref1.get
                    r2 <- ref2.get
                yield (r1, r2)
            }
        yield assert(result == (20, 10))
    }

    "initNow behavior" - {
        "creates ref with new transaction ID outside transaction" in run {
            for
                ref   <- TRef.init(1)
                value <- STM.run(ref.get)
            yield assert(value == 1)
        }

        "uses current transaction ID within transaction" in run {
            STM.run {
                for
                    ref1 <- TRef.init(1)
                    ref2 <- TRef.init(2)
                    _    <- ref1.set(3)
                    _    <- ref2.set(4)
                    val1 <- ref1.get
                    val2 <- ref2.get
                yield assert(val1 == 3 && val2 == 4)
            }
        }

        "nests properly in nested transactions" in run {
            STM.run {
                for
                    ref1 <- TRef.init(1)
                    result <- STM.run {
                        for
                            ref2 <- TRef.init(2)
                            _    <- ref1.set(3)
                            _    <- ref2.set(4)
                            v1   <- ref1.get
                            v2   <- ref2.get
                        yield (v1, v2)
                    }
                yield assert(result == (3, 4))
            }
        }
    }
end TRefTest
