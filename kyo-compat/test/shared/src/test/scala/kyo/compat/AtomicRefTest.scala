package kyo.compat

import kyo.compat.*

class AtomicRefTest extends CompatTest:

    "init returns a usable ref handle" in run {
        val c = CAtomicRef.init[Int](42).flatMap(_ => CIO.defer { "ok" })
        c.map(v => assert(v == "ok"))
    }

    "get returns the initial value" in run {
        val c =
            CAtomicRef.init[Int](42).flatMap { r =>
                r.get
            }
        c.map(v => assert(v == 42))
    }

    "set then get returns the new value" in run {
        val c =
            CAtomicRef.init[Int](1).flatMap { r =>
                r.set(99).flatMap { _ =>
                    r.get
                }
            }
        c.map(v => assert(v == 99))
    }

    "getAndSet returns the old value and writes the new one" in run {
        val c =
            CAtomicRef.init[Int](1).flatMap { r =>
                r.getAndSet(99).flatMap { old =>
                    r.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == 1 && now == 99)
        }
    }

    "updateAndGet returns the post-update value" in run {
        val c =
            CAtomicRef.init[Int](10).flatMap { r =>
                r.updateAndGet(_ + 5)
            }
        c.map(v => assert(v == 15))
    }

    "getAndUpdate returns the pre-update value" in run {
        val c =
            CAtomicRef.init[Int](10).flatMap { r =>
                r.getAndUpdate(_ + 5).flatMap { old =>
                    r.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == 10 && now == 15)
        }
    }

    "compareAndSet swaps the value when the expected matches" in run {
        val c =
            CAtomicRef.init[Int](10).flatMap { r =>
                r.compareAndSet(10, 99).flatMap { ok =>
                    r.get.flatMap { now =>
                        CIO.defer((ok, now))
                    }
                }
            }
        c.map { case (ok, now) =>
            assert(ok == true && now == 99)
        }
    }

    "compareAndSet leaves the value unchanged when the expected differs" in run {
        val c =
            CAtomicRef.init[Int](10).flatMap { r =>
                r.compareAndSet(5, 99).flatMap { ok =>
                    r.get.flatMap { now =>
                        CIO.defer((ok, now))
                    }
                }
            }
        c.map { case (ok, now) =>
            assert(ok == false && now == 10)
        }
    }

    "set does not alias or mutate the caller's reference value" in run {
        // Set + get of a List value: the value comes back == but the
        // implementation must not mutate the supplied List under the covers.
        val initial  = List(1, 2, 3)
        val replaced = List(9, 9, 9)
        val c =
            CAtomicRef.init[List[Int]](initial).flatMap { r =>
                r.set(replaced).flatMap { _ =>
                    r.get
                }
            }
        c.map { v =>
            // Verify content + that initial was not mutated to replaced.
            assert(v == replaced && initial == List(1, 2, 3))
        }
    }

    "set accepts None for nullable Option-typed refs" in run {
        // AtomicReference allows null. We use Option-typed value because
        // typed E semantics on backends differ for null. Set Some -> None.
        val c =
            CAtomicRef.init[Option[Int]](Some(1)).flatMap { r =>
                r.set(None).flatMap { _ =>
                    r.get
                }
            }
        c.map(v => assert(v == None))
    }
    "updateAndGet with f returning same value is an observable no-op" in run {
        val c =
            CAtomicRef.init[String]("a").flatMap { r =>
                r.updateAndGet(s => s).flatMap { _ =>
                    r.get
                }
            }
        c.map(v => assert(v == "a"))
    }
    "AtomicRef lift/lower round-trip preserves value and supports set" in run {
        val c =
            CAtomicRef.init[String]("x").flatMap { r =>
                // lower gives the underlying AtomicReference; lift wraps it back
                val underlying = r.lower
                val lifted     = CAtomicRef.lift(underlying)
                lifted.get.flatMap { v1 =>
                    lifted.set("y").flatMap { _ =>
                        lifted.get.flatMap { v2 =>
                            CIO.defer((v1, v2))
                        }
                    }
                }
            }
        c.map { case (v1, v2) =>
            assert(v1 == "x" && v2 == "y")
        }
    }
    "concurrent update across 100 fibers yields final value of 100" in run {
        val c =
            CAtomicRef.init[Int](0).flatMap { ref =>
                CIO.foreach(1 to 100)(_ => CFiber.init(ref.updateAndGet(_ + 1))).flatMap { fibers =>
                    CIO.collectAll(fibers.toSeq.map(_.get)).flatMap { _ =>
                        ref.get
                    }
                }
            }
        c.map(v => assert(v == 100))
    }

end AtomicRefTest
