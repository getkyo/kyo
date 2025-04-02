package kyo

class StreamTest extends Test:

    "factory" - {
        "mergeAll" in run {
            Choice.run {
                for
                    size <- Choice.get(Seq(0, 1, 32, 100))
                    s1     = Stream.init(0 to 99 by 3)
                    s2     = Stream.init(1 to 99 by 3)
                    s3     = Stream.init(2 to 99 by 3)
                    merged = Stream.mergeAll(Seq(s1, s2, s3), size)
                    res <- merged.run
                yield assert(res.sorted == (0 to 99))
            }.andThen(succeed)
        }

        "mergeAllRacing" in run {
            Choice.run {
                for
                    size <- Choice.get(Seq(0, 1, 32, 1024))
                    s1     = Stream(Loop.forever(Emit.value(Chunk(100))))
                    s2     = Stream.init(0 to 50)
                    merged = Stream.mergeAllHalting(Seq(s1, s2), size)
                    res <- merged.run
                yield assert(res.toSet == (0 to 50).toSet + 100)
            }.andThen(succeed)
        }
    }

    "combinator" - {
        "mergeHaltingLeft" - {
            "should halt if left completes" in run {
                Choice.run {
                    for
                        size <- Choice.get(Seq(0, 1, 32, 1024))
                        s1     = Stream.init(0 to 50)
                        s2     = Stream(Loop.forever(Emit.value(Chunk(100))))
                        merged = s1.mergeHaltingLeft(s2, size)
                        res <- merged.run
                    yield assert(res.sorted.startsWith(0 to 50))
                }.andThen(succeed)
            }

            "should not halt if right completes" in run {
                Choice.run {
                    for
                        size <- Choice.get(Seq(0, 1, 32, 1024))
                        s1 = Stream:
                            Kyo.foreachDiscard(0 to 100)(i => Emit.value(Chunk(i)))
                        s2     = Stream.init(Seq(101, 102, 103))
                        merged = s1.mergeHaltingLeft(s2, size)
                        res <- merged.run
                    yield assert(res.sorted.startsWith(0 to 100))
                }.andThen(succeed)
            }
        }

        "mergeHaltingRight" - {
            "should halt if right completes" in run {
                Choice.run {
                    for
                        size <- Choice.get(Seq(0, 1, 32, 1024))
                        s1     = Stream.init(0 to 50)
                        s2     = Stream(Loop.forever(Emit.value(Chunk(100))))
                        merged = s2.mergeHaltingRight(s1, size)
                        res <- merged.run
                    yield assert(res.sorted.startsWith(0 to 50))
                }.andThen(succeed)
            }

            "should not halt if left completes" in run {
                Choice.run {
                    for
                        size <- Choice.get(Seq(0, 1, 32, 1024))
                        s1 = Stream:
                            Kyo.foreachDiscard(0 to 100)(i => Emit.value(Chunk(i)))
                        s2     = Stream.init(Seq(101, 102, 103))
                        merged = s2.mergeHaltingRight(s1, size)
                        res <- merged.run
                    yield assert(res.sorted.startsWith(0 to 100))
                }.andThen(succeed)
            }
        }
    }

end StreamTest
