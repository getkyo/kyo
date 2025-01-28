package kyo.offheap

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemoryTest extends Test:
    "Memory allocation and safety" - {
        "zero initialization" in run {
            Arena.run {
                for
                    mem <- Memory.init[Int](5)
                    v0  <- mem.get(0)
                    v4  <- mem.get(4)
                yield
                    assert(v0 == 0)
                    assert(v4 == 0)
            }
        }

        "stack safety" - {
            "recursive method" - {
                "no effect" in run {
                    def allocateMany(n: Int): Unit < Arena =
                        if n <= 0 then IO.unit
                        else Memory.init[Int](1).flatMap(_ => allocateMany(n - 1))

                    Arena.run(allocateMany(10000))
                }

                "effect at start" in run {
                    def allocateMany(n: Int): Unit < Arena =
                        if n <= 0 then IO.unit
                        else Memory.init[Int](1).set(0, 42).flatMap(_ => allocateMany(n - 1))

                    Arena.run(allocateMany(10000))
                }

                "effect at end" in run {
                    def allocateMany(n: Int): Unit < Arena =
                        if n <= 0 then IO.unit
                        else allocateMany(n - 1).flatMap(_ => Memory.init[Int](1).set(0, 42))

                    Arena.run(allocateMany(10000))
                }

                "multiple effects" in run {
                    def allocateMany(n: Int): Unit < Arena =
                        if n <= 0 then IO.unit
                        else
                            for
                                mem <- Memory.init[Int](1)
                                _   <- mem.set(0, 42)
                                _   <- allocateMany(n - 1)
                                _   <- mem.set(0, 24)
                            yield ()

                    Arena.run(allocateMany(10000))
                }
            }
        }

        "bounds checking" in run {
            Arena.run {
                for
                    mem <- Memory.init[Int](2)
                    r1  <- Abort.run(mem.get(-1))
                    r2  <- Abort.run(mem.get(2))
                yield
                    assert(r1.isPanic)
                    assert(r2.isPanic)
            }
        }
    }

    "Arena lifecycle" - {
        "cleanup after scope" in run {
            for
                mem <- Arena.run(Memory.init[Int](1))
                r   <- Abort.run(mem.get(0))
            yield assert(r.isPanic)
        }

        "effect isolation" in run {
            Arena.run {
                for
                    mem <- Memory.init[Int](1)
                    _   <- mem.set(0, 42)
                    r <- Abort.run {
                        for
                            _ <- mem.set(0, 24)
                            _ <- Abort.fail("test")
                        yield ()
                    }
                    v <- mem.get(0)
                yield
                    assert(r.isPanic)
                    assert(v == 42)
            }
        }
    }

    "Primitive type support" - {
        "byte operations" in run {
            Arena.run {
                for
                    mem <- Memory.init[Byte](2)
                    _   <- mem.set(0, Byte.MaxValue)
                    _   <- mem.set(1, Byte.MinValue)
                    v1  <- mem.get(0)
                    v2  <- mem.get(1)
                yield
                    assert(v1 == Byte.MaxValue)
                    assert(v2 == Byte.MinValue)
            }
        }

        "long operations" in run {
            Arena.run {
                for
                    mem <- Memory.init[Long](2)
                    _   <- mem.set(0, Long.MaxValue)
                    _   <- mem.set(1, Long.MinValue)
                    v1  <- mem.get(0)
                    v2  <- mem.get(1)
                yield
                    assert(v1 == Long.MaxValue)
                    assert(v2 == Long.MinValue)
            }
        }
    }

    "Memory views" - {
        "view bounds" in run {
            Arena.run {
                for
                    mem <- Memory.init[Int](5)
                    _   <- mem.fill(42)
                    r1  <- Abort.run(mem.view(-1, 2))
                    r2  <- Abort.run(mem.view(0, 6))
                    r3  <- Abort.run(mem.view(4, 2))
                yield assert(r1.isPanic && r2.isPanic && r3.isPanic)
            }
        }

        "view isolation" in run {
            Arena.run {
                for
                    mem  <- Memory.init[Int](5)
                    _    <- mem.fill(42)
                    view <- mem.view(1, 2)
                    _    <- view.set(0, 24)
                    v1   <- view.get(0)
                    v2   <- mem.get(1)
                yield
                    assert(v1 == 24)
                    assert(v2 == 24)
            }
        }
    }

    "Memory operations" - {
        "basic operations" - {
            "fill" in run {
                Arena.run {
                    for
                        mem <- Memory.init[Int](5)
                        _   <- mem.fill(42)
                        v1  <- mem.get(0)
                        v2  <- mem.get(4)
                    yield
                        assert(v1 == 42)
                        assert(v2 == 42)
                }
            }

            "foreachIndexed" in run {
                Arena.run {
                    for
                        mem <- Memory.init[Int](5)
                        _   <- mem.fill(1)
                        sum <- IO.fold(0 until 5)(0) { (acc, i) =>
                            mem.get(i).map(_ + acc)
                        }
                    yield assert(sum == 5)
                }
            }
        }

        "stack safety" - {
            "recursive method" - {
                "no effect" in run {
                    def allocateMany(n: Int): Unit < Arena =
                        if n <= 0 then IO.unit
                        else Memory.init[Int](1).flatMap(_ => allocateMany(n - 1))

                    Arena.run(allocateMany(10000))
                }

                "effect at start" in run {
                    def allocateMany(n: Int): Unit < Arena =
                        if n <= 0 then IO.unit
                        else Memory.init[Int](1).set(0, 42).flatMap(_ => allocateMany(n - 1))

                    Arena.run(allocateMany(10000))
                }

                "effect at end" in run {
                    def allocateMany(n: Int): Unit < Arena =
                        if n <= 0 then IO.unit
                        else allocateMany(n - 1).flatMap(_ => Memory.init[Int](1).set(0, 42))

                    Arena.run(allocateMany(10000))
                }

                "multiple effects" in run {
                    def allocateMany(n: Int): Unit < Arena =
                        if n <= 0 then IO.unit
                        else
                            for
                                mem <- Memory.init[Int](1)
                                _   <- mem.set(0, 42)
                                _   <- allocateMany(n - 1)
                                _   <- mem.set(0, 24)
                            yield ()

                    Arena.run(allocateMany(10000))
                }
            }
        }

        "boundary handling" - {
            "no context effect suspension" in run {
                Arena.run {
                    for
                        mem <- Memory.init[Int](1)
                        _   <- mem.set(0, 42)
                        v   <- mem.get(0)
                    yield assert(v == 42)
                }
            }

            "isolates runtime effect" in run {
                Arena.run {
                    for
                        mem <- Memory.init[Int](1)
                        _   <- mem.set(0, 42)
                        r <- Abort.run {
                            for
                                _ <- mem.set(0, 24)
                                _ <- Abort.fail("test")
                            yield ()
                        }
                        v <- mem.get(0)
                    yield
                        assert(r.isPanic)
                        assert(v == 42)
                }
            }
        }

        "effect composition" - {
            "with Env" in run {
                Arena.run {
                    for
                        mem <- Memory.init[Int](1)
                        env <- Env.get[Arena.State]
                        _   <- mem.set(0, 42)
                    yield assert(env != null)
                }
            }

            "with Abort" in run {
                Arena.run {
                    for
                        mem <- Memory.init[Int](1)
                        _   <- mem.set(0, 42)
                        r1  <- Abort.run(mem.get(0))
                        r2  <- Abort.run(mem.get(-1))
                    yield
                        assert(r1.isSuccess)
                        assert(r2.isPanic)
                }
            }
        }
    }
end MemoryTest

class MemoryManagementSpec extends AnyFlatSpec with Matchers:
    "Arena" should "allocate and free memory correctly" in {
        val arena   = new Arena()
        val segment = arena.allocate[Int](10) 
        segment.allocate(42)           
        segment.ptr should not be null 

        arena.close(segment) // Free the allocated memory
    }
end MemoryManagementSpec
