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
    "State" - {
        import TRef.State
        import TRef.State.*

        "free" - {
            "can acquire writer" in {
                assert(State.free.acquireWriter(0L).isDefined)
            }
            "can acquire reader" in {
                assert(State.free.acquireReader.isDefined)
            }
            "has zero readTick" in {
                assert(State.free.readTick == 0L)
            }
            "asString is free" in {
                assert(State.free.render == "free")
            }
        }

        "readTick" - {
            "withReadTick sets tick" in {
                val s = State.free.withReadTick(42L)
                assert(s.readTick == 42L)
            }
            "withReadTick preserves lock state" in {
                val s = State.free.acquireReader.get.withReadTick(100L)
                assert(s.readTick == 100L)
                assert(s.acquireWriter(100L).isEmpty)
                assert(s.render == "1 readers")
            }
            "withoutReadTick clears tick" in {
                val s = State.free.withReadTick(42L).withoutReadTick
                assert(s.readTick == 0L)
            }
            "withoutReadTick preserves lock state" in {
                val s = State.free.acquireReader.get.withReadTick(100L).withoutReadTick
                assert(s.readTick == 0L)
                assert(s.acquireWriter(0L).isEmpty)
            }
        }

        "reader lock" - {
            "acquireReader increments count" in {
                val s = State.free.acquireReader.get
                assert(s.acquireWriter(0L).isEmpty)
                assert(s.acquireReader.isDefined)
                assert(s.render == "1 readers")
            }
            "multiple readers stack" in {
                val s = State.free.acquireReader.get.acquireReader.get.acquireReader.get
                assert(s.render == "3 readers")
            }
            "releaseReader decrements count" in {
                val s = State.free.acquireReader.get.acquireReader.get.releaseReader
                assert(s.render == "1 readers")
            }
            "releaseReader back to free" in {
                val s = State.free.acquireReader.get.releaseReader
                assert(s.acquireWriter(0L).isDefined)
                assert(s.render == "free")
            }
        }

        "writer lock" - {
            "acquireWriter acquires write lock" in {
                val s = State.free.acquireWriter(0L).get
                assert(s.acquireWriter(0L).isEmpty)
                assert(s.acquireReader.isEmpty)
                assert(s.render == "writer")
            }
            "acquireWriter preserves readTick" in {
                val s = State.free.withReadTick(42L).acquireWriter(100L).get
                assert(s.readTick == 42L)
                assert(s.render == "writer")
            }
            "acquireWriter blocked by newer readTick" in {
                val s = State.free.withReadTick(100L)
                assert(s.acquireWriter(50L).isEmpty)
                assert(s.acquireWriter(100L).isDefined)
                assert(s.acquireWriter(200L).isDefined)
            }
        }

        "large tick values" - {
            "readTick larger than Int.MaxValue" in {
                val tick = Int.MaxValue.toLong + 1000L
                val s    = State.free.withReadTick(tick)
                assert(s.readTick == tick)
                assert(s.acquireWriter(tick).isDefined)
                assert(s.render == "free")
            }
            "readTick near 56-bit max" in {
                val tick = (1L << 55) - 1
                val s    = State.free.withReadTick(tick)
                assert(s.readTick == tick)
                assert(s.acquireWriter(tick).isDefined)
            }
            "large tick preserves reader lock" in {
                val tick = Int.MaxValue.toLong * 2
                val s    = State.free.acquireReader.get.acquireReader.get.withReadTick(tick)
                assert(s.readTick == tick)
                assert(s.render == "2 readers")
                assert(s.acquireWriter(tick).isEmpty)
            }
            "large tick preserves writer lock" in {
                val tick = Int.MaxValue.toLong * 2
                val s    = State.free.withReadTick(tick).acquireWriter(tick).get
                assert(s.readTick == tick)
                assert(s.render == "writer")
            }
            "withoutReadTick clears large tick" in {
                val tick = Int.MaxValue.toLong * 3
                val s    = State.free.acquireReader.get.withReadTick(tick).withoutReadTick
                assert(s.readTick == 0L)
                assert(s.render == "1 readers")
            }
            "reader operations don't affect large tick" in {
                val tick = Int.MaxValue.toLong + 12345L
                val s    = State.free.withReadTick(tick).acquireReader.get.acquireReader.get.releaseReader
                assert(s.readTick == tick)
                assert(s.render == "1 readers")
            }
        }

        "acquireWriter conflict detection with large ticks" - {
            "writer allowed when readTick <= writerTick (both large)" in {
                val readerTick = Int.MaxValue.toLong + 100L
                val writerTick = Int.MaxValue.toLong + 200L
                val s          = State.free.withReadTick(readerTick)
                assert(s.acquireWriter(writerTick).isDefined)
            }
            "writer blocked when readTick > writerTick (both large)" in {
                val readerTick = Int.MaxValue.toLong + 300L
                val writerTick = Int.MaxValue.toLong + 200L
                val s          = State.free.withReadTick(readerTick)
                assert(s.acquireWriter(writerTick).isEmpty)
            }
            "writer allowed when readTick equals writerTick (large)" in {
                val tick = Int.MaxValue.toLong + 500L
                val s    = State.free.withReadTick(tick)
                assert(s.acquireWriter(tick).isDefined)
            }
            "comparison works across Int.MaxValue boundary" in {
                val smallTick = Int.MaxValue.toLong - 10L
                val largeTick = Int.MaxValue.toLong + 10L
                val s         = State.free.withReadTick(smallTick)
                assert(s.acquireWriter(largeTick).isDefined)
                assert(s.acquireWriter(smallTick - 1).isEmpty)
            }
            "comparison works with zero readTick and large writerTick" in {
                val writerTick = Int.MaxValue.toLong * 2
                val s          = State.free // readTick is 0
                assert(s.acquireWriter(writerTick).isDefined)
            }
            "comparison works near 56-bit boundary" in {
                val nearMax = (1L << 55) - 100L
                val atMax   = (1L << 55) - 1L
                val s       = State.free.withReadTick(nearMax)
                assert(s.acquireWriter(atMax).isDefined)
                assert(s.acquireWriter(nearMax).isDefined)
                assert(s.acquireWriter(nearMax - 1).isEmpty)
            }
        }

    }

    "early writer abort" - {
        "writer succeeds when readTick <= tick" in run {
            for
                ref   <- TRef.init(0)
                _     <- STM.run(ref.set(42))
                value <- STM.run(ref.get)
            yield assert(value == 42)
        }

        "concurrent readers and writers maintain consistency" in run {
            // This tests that the early abort optimization works correctly
            // under concurrent load - writers yield to fresher readers
            for
                ref <- TRef.init(0)
                // Many concurrent readers registering readTick
                readerFiber <- Fiber.initUnscoped {
                    Async.fill(50, 50) {
                        STM.run(ref.get)
                    }
                }
                // Writers trying to write - some may abort early due to readTick
                writerFiber <- Fiber.initUnscoped {
                    Async.fill(10, 10) {
                        STM.run(ref.update(_ + 1))
                    }
                }
                _          <- readerFiber.get
                _          <- writerFiber.get
                finalValue <- STM.run(ref.get)
            yield
                // All writes should have completed
                assert(finalValue == 10)
        }
    }
end TRefTest
