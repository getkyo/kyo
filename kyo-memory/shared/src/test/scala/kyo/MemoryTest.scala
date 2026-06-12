package kyo

import kyo.Memory.Unsafe

class MemoryTest extends kyo.test.Test[Any]:

    "init" in {
        Arena.run {
            for
                mem <- Memory.init[Int](5)
                v   <- mem.get(0)
            yield assert(v == 0)
        }
    }

    "initWith" in {
        Arena.run {
            Memory.initWith[Int](5)(_.get(0)).map(v => assert(v == 0))
        }
    }

    "set/get" in {
        Arena.run {
            for
                mem <- Memory.init[Int](5)
                _   <- mem.set(0, 42)
                v   <- mem.get(0)
            yield assert(v == 42)
        }
    }

    "fill" in {
        Arena.run {
            for
                mem <- Memory.init[Int](5)
                _   <- mem.fill(42)
                v1  <- mem.get(0)
                v2  <- mem.get(4)
            yield assert(v1 == 42 && v2 == 42)
        }
    }

    "fold" in {
        Arena.run {
            for
                mem <- Memory.init[Int](3)
                _   <- mem.set(0, 1)
                _   <- mem.set(1, 2)
                _   <- mem.set(2, 3)
                sum <- mem.fold(0)(_ + _)
            yield assert(sum == 6)
        }
    }

    "findIndex" in {
        Arena.run {
            for
                mem  <- Memory.init[Int](3)
                _    <- mem.set(0, 1)
                _    <- mem.set(1, 2)
                _    <- mem.set(2, 3)
                idx  <- mem.findIndex(_ == 2)
                none <- mem.findIndex(_ == 42)
            yield assert(idx == Present(1) && none == Absent)
        }
    }

    "exists" in {
        Arena.run {
            for
                mem    <- Memory.init[Int](3)
                _      <- mem.set(0, 1)
                _      <- mem.set(1, 2)
                _      <- mem.set(2, 3)
                exists <- mem.exists(_ == 2)
                absent <- mem.exists(_ == 42)
            yield assert(exists && !absent)
        }
    }

    "view" in {
        Arena.run {
            for
                mem  <- Memory.init[Int](5)
                _    <- mem.fill(42)
                view <- mem.view(1, 2)
                v    <- view.get(0)
            yield assert(v == 42 && view.size == 2)
        }
    }

    "copy segment" in {
        Arena.run {
            for
                mem  <- Memory.init[Int](3)
                _    <- mem.set(0, 1)
                _    <- mem.set(1, 2)
                _    <- mem.set(2, 3)
                copy <- mem.copy(1, 2)
                v1   <- copy.get(0)
                v2   <- copy.get(1)
            yield assert(v1 == 2 && v2 == 3 && copy.size == 2)
        }
    }

    "copy to target" in {
        Arena.run {
            for
                src    <- Memory.init[Int](3)
                _      <- src.fill(42)
                target <- Memory.init[Int](3)
                _      <- src.copyTo(target, 0, 0, 3)
                v      <- target.get(0)
            yield assert(v == 42)
        }
    }

    "view shares storage with its parent, writes visible both ways" in {
        Arena.run {
            for
                mem       <- Memory.init[Int](6)
                _         <- mem.fill(1)
                window    <- mem.view(2, 3)
                _         <- window.set(0, 99)
                viaParent <- mem.get(2)
                _         <- mem.set(4, 77)
                viaWindow <- window.get(2)
            yield assert(viaParent == 99 && viaWindow == 77)
        }
    }

    "copy is independent of its source, writes do not propagate either way" in {
        Arena.run {
            for
                src  <- Memory.init[Int](4)
                _    <- src.fill(1)
                dup  <- src.copy(0, 4)
                _    <- dup.set(0, 99)
                _    <- src.set(1, 55)
                src0 <- src.get(0)
                dup1 <- dup.get(1)
            yield assert(src0 == 1 && dup1 == 1)
        }
    }

    "copyTo writes a partial range at an offset, leaving the rest untouched" in {
        Arena.run {
            for
                src    <- Memory.init[Byte](4)
                _      <- src.set(0, 0xca.toByte)
                _      <- src.set(1, 0xfe.toByte)
                target <- Memory.init[Byte](8)
                _      <- target.fill(0.toByte)
                _      <- src.copyTo(target, 0, 4, 2)
                t3     <- target.get(3)
                t4     <- target.get(4)
                t5     <- target.get(5)
                t6     <- target.get(6)
            yield assert(t3 == 0.toByte && t4 == 0xca.toByte && t5 == 0xfe.toByte && t6 == 0.toByte)
        }
    }

    "bounds checking" - {
        "get past the end is a managed error" in {
            Abort.run(Arena.run(Memory.init[Int](4).map(_.get(4)))).map(r => assert(r.isPanic))
        }
        "get at a negative index is a managed error" in {
            Abort.run(Arena.run(Memory.init[Int](4).map(_.get(-1)))).map(r => assert(r.isPanic))
        }
        "set past the end is a managed error" in {
            Abort.run(Arena.run(Memory.init[Int](4).map(_.set(4, 0)))).map(r => assert(r.isPanic))
        }
        "view beyond the segment is a managed error" in {
            Abort.run(Arena.run(Memory.init[Int](4).map(_.view(2, 4)))).map(r => assert(r.isPanic))
        }
        "copy beyond the segment is a managed error" in {
            Abort.run(Arena.run(Memory.init[Int](4).map(_.copy(2, 4)))).map(r => assert(r.isPanic))
        }
        "copyTo past the target end is a managed error" in {
            Abort.run(Arena.run {
                for
                    src <- Memory.init[Int](4)
                    tgt <- Memory.init[Int](4)
                    _   <- src.copyTo(tgt, 0, 2, 4)
                yield ()
            }).map(r => assert(r.isPanic))
        }
        "in-range access still works" in {
            Arena.run {
                for
                    mem <- Memory.init[Int](4)
                    _   <- mem.set(3, 9)
                    v   <- mem.get(3)
                yield assert(v == 9)
            }
        }
    }

    "unsafe" - {

        "get/set" in {
            Arena.run {
                for
                    mem <- Memory.init[Int](5)
                    v <- Sync.Unsafe.defer {
                        val unsafe: Unsafe[Int] = mem.unsafe
                        unsafe.set(0, 42)
                        unsafe.get(0)
                    }
                yield assert(v == 42)
            }
        }

        "fill" in {
            Arena.run {
                for
                    mem <- Memory.init[Int](5)
                    (v0, v4) <- Sync.Unsafe.defer {
                        val unsafe = mem.unsafe
                        unsafe.fill(42)
                        (unsafe.get(0), unsafe.get(4))
                    }
                yield assert(v0 == 42 && v4 == 42)
            }
        }

        "fold" in {
            Arena.run {
                for
                    mem <- Memory.init[Int](3)
                    v <- Sync.Unsafe.defer {
                        val unsafe = mem.unsafe
                        unsafe.set(0, 1)
                        unsafe.set(1, 2)
                        unsafe.set(2, 3)
                        unsafe.fold(0)(_ + _)
                    }
                yield assert(v == 6)
            }
        }

        "findIndex and exists" in {
            Arena.run {
                Memory.init[Int](4).map { mem =>
                    Sync.Unsafe.defer {
                        val u = mem.unsafe
                        u.set(0, 10)
                        u.set(1, 20)
                        u.set(2, 30)
                        u.set(3, 40)
                        assert(
                            u.findIndex(_ == 30) == Present(2) &&
                                u.findIndex(_ == 99) == Absent &&
                                u.exists(_ > 35) &&
                                !u.exists(_ > 100)
                        )
                    }
                }
            }
        }

        "view shares storage with its parent" in {
            Arena.run {
                Memory.init[Int](6).map { mem =>
                    Sync.Unsafe.defer {
                        val u = mem.unsafe
                        u.fill(1)
                        val window = u.view(2, 3)
                        window.set(0, 99)
                        assert(u.get(2) == 99 && window.get(0) == 99)
                    }
                }
            }
        }

        "copy keeps the Arena effect and is independent of the source" in {
            Arena.run {
                for
                    mem <- Memory.init[Int](4)
                    _   <- mem.fill(7)
                    dup <- mem.unsafe.copy(0, 4)
                    (src0, dup1) <- Sync.Unsafe.defer {
                        dup.set(0, 99)
                        mem.unsafe.set(1, 55)
                        (mem.unsafe.get(0), dup.get(1))
                    }
                yield assert(src0 == 7 && dup1 == 7)
            }
        }

        "copyTo into another segment" in {
            Arena.run {
                for
                    src <- Memory.init[Int](3)
                    _   <- src.fill(5)
                    tgt <- Memory.init[Int](3)
                    v <- Sync.Unsafe.defer {
                        src.unsafe.copyTo(tgt.unsafe, 0, 0, 3)
                        tgt.unsafe.get(0)
                    }
                yield assert(v == 5)
            }
        }

        "size and the safe round-trip" in {
            Arena.run {
                Memory.init[Int](10).map { mem =>
                    Sync.Unsafe.defer {
                        val u = mem.unsafe
                        assert(u.size == 10L && u.safe.unsafe.size == 10L)
                    }
                }
            }
        }
    }

    "cannot use memory after arena closes" in {
        for
            mem <- Arena.run(Memory.init[Int](5))
            r   <- Abort.run(Arena.run(mem.get(0)))
        yield assert(r.isPanic)
    }

    "frees segments when the arena body panics" in {
        for
            ref <- AtomicRef.init[Maybe[Memory[Int]]](Absent)
            _ <- Abort.run(Arena.run {
                for
                    m <- Memory.init[Int](5)
                    _ <- ref.set(Present(m))
                    _ <- Sync.defer[Unit, Any](throw new RuntimeException("boom"))
                yield ()
            })
            captured <- ref.get
            panicked <- captured match
                case Present(m) => Abort.run(Arena.run(m.get(0))).map(_.isPanic)
                case Absent     => Sync.defer(false)
        yield assert(captured.isDefined && panicked)
    }

    "frees segments when the arena body aborts".pendingUntilFixed(
        "Arena.run builds on Sync.ensure, whose finalizer defers to fiber teardown on Abort.fail rather than running at scope exit; see kyo-core SyncTest 'runs finalizer on Abort.fail'"
    ) in {
        for
            ref <- AtomicRef.init[Maybe[Memory[Int]]](Absent)
            _ <- Abort.run(Arena.run {
                for
                    m <- Memory.init[Int](5)
                    _ <- ref.set(Present(m))
                    _ <- Abort.fail(new RuntimeException("boom"))
                yield ()
            })
            captured <- ref.get
            panicked <- captured match
                case Present(m) => Abort.run(Arena.run(m.get(0))).map(_.isPanic)
                case Absent     => Sync.defer(false)
        yield assert(captured.isDefined && panicked)
    }

    "empty allocation has size 0 and well-defined bulk reads" in {
        Arena.run {
            for
                mem <- Memory.init[Int](0)
                sz = mem.size
                sum <- mem.fold(0)(_ + _)
                idx <- mem.findIndex(_ => true)
                any <- mem.exists(_ => true)
            yield assert(sz == 0L && sum == 0 && idx == Absent && !any)
        }
    }

    "a segment is shared across fibers within the arena scope" in {
        Arena.run {
            for
                mem <- Memory.init[Int](2)
                _   <- Async.zip(mem.set(0, 99), mem.set(1, 88))
                a   <- mem.get(0)
                b   <- mem.get(1)
            yield assert(a == 99 && b == 88)
        }
    }

    "other supported layouts" - {
        "byte" in {
            Arena.run {
                for
                    mem <- Memory.init[Byte](3)
                    _   <- mem.set(0, 42.toByte)
                    _   <- mem.set(1, (-5).toByte)
                    _   <- mem.set(2, 127.toByte)
                    v1  <- mem.get(0)
                    v2  <- mem.get(1)
                    v3  <- mem.get(2)
                yield assert(v1 == 42 && v2 == -5 && v3 == 127)
            }
        }

        "short" in {
            Arena.run {
                for
                    mem <- Memory.init[Short](3)
                    _   <- mem.set(0, 1000.toShort)
                    _   <- mem.set(1, (-500).toShort)
                    _   <- mem.set(2, 32767.toShort)
                    v1  <- mem.get(0)
                    v2  <- mem.get(1)
                    v3  <- mem.get(2)
                yield assert(v1 == 1000 && v2 == -500 && v3 == 32767)
            }
        }

        "float" in {
            Arena.run {
                for
                    mem <- Memory.init[Float](3)
                    _   <- mem.set(0, 3.14f)
                    _   <- mem.set(1, -2.5f)
                    _   <- mem.set(2, 1000.001f)
                    v1  <- mem.get(0)
                    v2  <- mem.get(1)
                    v3  <- mem.get(2)
                yield assert(v1 == 3.14f && v2 == -2.5f && v3 == 1000.001f)
            }
        }

        "double" in {
            Arena.run {
                for
                    mem <- Memory.init[Double](3)
                    _   <- mem.set(0, 3.14159265359)
                    _   <- mem.set(1, -2.71828182846)
                    _   <- mem.set(2, 1000.00000001)
                    v1  <- mem.get(0)
                    v2  <- mem.get(1)
                    v3  <- mem.get(2)
                yield assert(v1 == 3.14159265359 && v2 == -2.71828182846 && v3 == 1000.00000001)
            }
        }

        "long" in {
            Arena.run {
                for
                    mem <- Memory.init[Long](3)
                    _   <- mem.set(0, 9223372036854775807L)
                    _   <- mem.set(1, -9223372036854775808L)
                    _   <- mem.set(2, 42L)
                    v1  <- mem.get(0)
                    v2  <- mem.get(1)
                    v3  <- mem.get(2)
                yield assert(v1 == 9223372036854775807L && v2 == -9223372036854775808L && v3 == 42L)
            }
        }
    }
end MemoryTest
