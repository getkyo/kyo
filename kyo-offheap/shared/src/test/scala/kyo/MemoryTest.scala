package kyo

import kyo.Memory.Unsafe

class MemoryTest extends Test:

    "init" in run {
        Arena.run {
            for
                mem <- Memory.init[Int](5)
                v   <- mem.get(0)
            yield assert(v == 0)
        }
    }

    "initWith" in run {
        Arena.run {
            Memory.initWith[Int](5)(_.get(0)).map(v => assert(v == 0))
        }
    }

    "set/get" in run {
        Arena.run {
            for
                mem <- Memory.init[Int](5)
                _   <- mem.set(0, 42)
                v   <- mem.get(0)
            yield assert(v == 42)
        }
    }

    "fill" in run {
        Arena.run {
            for
                mem <- Memory.init[Int](5)
                _   <- mem.fill(42)
                v1  <- mem.get(0)
                v2  <- mem.get(4)
            yield assert(v1 == 42 && v2 == 42)
        }
    }

    "fold" in run {
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

    "findIndex" in run {
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

    "exists" in run {
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

    "view" in run {
        Arena.run {
            for
                mem  <- Memory.init[Int](5)
                _    <- mem.fill(42)
                view <- mem.view(1, 2)
                v    <- view.get(0)
            yield assert(v == 42 && view.size == 2)
        }
    }

    "copy segment" in run {
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

    "copy to target" in run {
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

    "unsafe" - {

        "get/set" in run {
            Arena.run {
                for
                    mem <- Memory.init[Int](5)
                    v <- Sync.Unsafe {
                        val unsafe: Unsafe[Int] = mem.unsafe
                        unsafe.set(0, 42)
                        unsafe.get(0)
                    }
                yield assert(v == 42)
            }
        }

        "fill" in run {
            Arena.run {
                for
                    mem <- Memory.init[Int](5)
                    (v0, v4) <- Sync.Unsafe {
                        val unsafe = mem.unsafe
                        unsafe.fill(42)
                        (unsafe.get(0), unsafe.get(4))
                    }
                yield assert(v0 == 42 && v4 == 42)
            }
        }

        "fold" in run {
            Arena.run {
                for
                    mem <- Memory.init[Int](3)
                    v <- Sync.Unsafe {
                        val unsafe = mem.unsafe
                        unsafe.set(0, 1)
                        unsafe.set(1, 2)
                        unsafe.set(2, 3)
                        unsafe.fold(0)(_ + _)
                    }
                yield assert(v == 6)
            }
        }
    }

    "cannot use memory after arena closes" in run {
        for
            mem <- Arena.run(Memory.init[Int](5))
            r   <- Abort.run(Arena.run(mem.get(0)))
        yield assert(r.isPanic)
    }

    "other supported layouts" - {
        "byte" in run {
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

        "short" in run {
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

        "float" in run {
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

        "double" in run {
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

        "long" in run {
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
