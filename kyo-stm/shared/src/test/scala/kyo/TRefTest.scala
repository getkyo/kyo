package kyo

import kyo.debug.Debug

class TRefTest extends Test:

    "init and get" in run {
        for
            ref   <- TRef.initNow(42)
            value <- STM.run(ref.get)
        yield assert(value == 42)
    }

    "set and get" in run {
        for
            ref   <- TRef.initNow(42)
            _     <- STM.run(ref.set(100))
            value <- STM.run(ref.get)
        yield assert(value == 100)
    }

    "multiple operations in transaction" in run {
        for
            ref1 <- TRef.initNow(10)
            ref2 <- TRef.initNow(20)
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
end TRefTest
