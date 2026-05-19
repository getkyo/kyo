package kyo.compat

import kyo.compat.*

class AtomicNumTest extends CompatTest:

    // ----- CAtomicInt -----

    "AtomicInt init returns the initial value via get" in run {
        val c =
            CAtomicInt.init(7).flatMap { a =>
                a.get
            }
        c.map(v => assert(v == 7))
    }

    "AtomicInt incrementAndGet returns the post-increment value" in run {
        val c =
            CAtomicInt.init(0).flatMap { a =>
                a.incrementAndGet
            }
        c.map(v => assert(v == 1))
    }

    "AtomicInt getAndIncrement returns the pre-increment value" in run {
        val c =
            CAtomicInt.init(0).flatMap { a =>
                a.getAndIncrement.flatMap { old =>
                    a.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == 0 && now == 1)
        }
    }

    "AtomicInt compareAndSet succeeds on match and fails on mismatch" in run {
        // Single-thread but exhaustive CAS: succeed on match, fail on mismatch.
        val c =
            CAtomicInt.init(5).flatMap { a =>
                a.compareAndSet(5, 10).flatMap { ok1 =>
                    a.compareAndSet(5, 20).flatMap { ok2 =>
                        a.get.flatMap { now =>
                            CIO.defer((ok1, ok2, now))
                        }
                    }
                }
            }
        c.map { case (ok1, ok2, now) =>
            assert(ok1 == true && ok2 == false && now == 10)
        }
    }

    "AtomicInt addAndGet decrements when given a negative delta" in run {
        val c =
            CAtomicInt.init(10).flatMap { a =>
                a.addAndGet(-3)
            }
        c.map(v => assert(v == 7))
    }

    // ----- CAtomicLong -----

    "AtomicLong init returns the initial value via get" in run {
        val c =
            CAtomicLong.init(7L).flatMap { a =>
                a.get
            }
        c.map(v => assert(v == 7L))
    }

    "AtomicLong incrementAndGet returns the post-increment value" in run {
        val c =
            CAtomicLong.init(0L).flatMap { a =>
                a.incrementAndGet
            }
        c.map(v => assert(v == 1L))
    }

    "AtomicLong getAndIncrement returns the pre-increment value" in run {
        val c =
            CAtomicLong.init(0L).flatMap { a =>
                a.getAndIncrement.flatMap { old =>
                    a.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == 0L && now == 1L)
        }
    }

    "AtomicLong compareAndSet succeeds on match and fails on mismatch" in run {
        val c =
            CAtomicLong.init(5L).flatMap { a =>
                a.compareAndSet(5L, 10L).flatMap { ok1 =>
                    a.compareAndSet(5L, 20L).flatMap { ok2 =>
                        a.get.flatMap { now =>
                            CIO.defer((ok1, ok2, now))
                        }
                    }
                }
            }
        c.map { case (ok1, ok2, now) =>
            assert(ok1 == true && ok2 == false && now == 10L)
        }
    }

    "AtomicLong addAndGet decrements when given a negative delta" in run {
        val c =
            CAtomicLong.init(10L).flatMap { a =>
                a.addAndGet(-3L)
            }
        c.map(v => assert(v == 7L))
    }

    // ----- CAtomicBoolean -----

    "AtomicBoolean init returns the initial value via get" in run {
        val c =
            CAtomicBoolean.init(false).flatMap { a =>
                a.get
            }
        c.map(v => assert(v == false))
    }

    "AtomicBoolean getAndSet(true) returns the prior false" in run {
        val c =
            CAtomicBoolean.init(false).flatMap { a =>
                a.getAndSet(true).flatMap { old =>
                    a.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == false && now == true)
        }
    }

    "AtomicBoolean getAndSet(false) returns the prior true" in run {
        val c =
            CAtomicBoolean.init(true).flatMap { a =>
                a.getAndSet(false).flatMap { old =>
                    a.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == true && now == false)
        }
    }

    "AtomicBoolean compareAndSet succeeds on match and fails on mismatch" in run {
        val c =
            CAtomicBoolean.init(false).flatMap { a =>
                a.compareAndSet(false, true).flatMap { ok1 =>
                    a.compareAndSet(false, true).flatMap { ok2 =>
                        a.get.flatMap { now =>
                            CIO.defer((ok1, ok2, now))
                        }
                    }
                }
            }
        c.map { case (ok1, ok2, now) =>
            assert(ok1 == true && ok2 == false && now == true)
        }
    }

    "AtomicBoolean compareAndSet round-trips false→true→false" in run {
        val c =
            CAtomicBoolean.init(false).flatMap { a =>
                a.compareAndSet(false, true).flatMap { ok1 =>
                    a.compareAndSet(true, false).flatMap { ok2 =>
                        a.get.flatMap { now =>
                            CIO.defer((ok1, ok2, now))
                        }
                    }
                }
            }
        c.map { case (ok1, ok2, now) =>
            assert(ok1 == true && ok2 == true && now == false)
        }
    }
    "AtomicInt get returns current value after init" in run {
        val c =
            CAtomicInt.init(42).flatMap { a =>
                a.get
            }
        c.map(v => assert(v == 42))
    }
    "AtomicInt set then get returns set value" in run {
        val c =
            CAtomicInt.init(0).flatMap { a =>
                a.set(7).flatMap { _ =>
                    a.get
                }
            }
        c.map(v => assert(v == 7))
    }
    "AtomicInt decrementAndGet returns post-decrement value" in run {
        val c =
            CAtomicInt.init(5).flatMap { a =>
                a.decrementAndGet
            }
        c.map(v => assert(v == 4))
    }
    "AtomicInt getAndDecrement returns pre-decrement value and decrements" in run {
        val c =
            CAtomicInt.init(5).flatMap { a =>
                a.getAndDecrement.flatMap { old =>
                    a.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == 5 && now == 4)
        }
    }
    "AtomicInt getAndAdd returns pre-add value with positive delta" in run {
        val c =
            CAtomicInt.init(10).flatMap { a =>
                a.getAndAdd(3).flatMap { old =>
                    a.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == 10 && now == 13)
        }
    }
    "AtomicInt concurrent incrementAndGet across 1000 fibers yields 1000" in run {
        val c =
            CAtomicInt.init(0).flatMap { ref =>
                CIO.foreach(1 to 1000)(_ => CFiber.init(ref.incrementAndGet)).flatMap { fibers =>
                    CIO.collectAll(fibers.toSeq.map(_.get)).flatMap { _ =>
                        ref.get
                    }
                }
            }
        c.map(v => assert(v == 1000))
    }
    "AtomicLong get returns current value after init" in run {
        val c =
            CAtomicLong.init(42L).flatMap { a =>
                a.get
            }
        c.map(v => assert(v == 42L))
    }
    "AtomicLong set then get returns set value" in run {
        val c =
            CAtomicLong.init(0L).flatMap { a =>
                a.set(7L).flatMap { _ =>
                    a.get
                }
            }
        c.map(v => assert(v == 7L))
    }
    "AtomicLong decrementAndGet returns post-decrement value" in run {
        val c =
            CAtomicLong.init(5L).flatMap { a =>
                a.decrementAndGet
            }
        c.map(v => assert(v == 4L))
    }
    "AtomicLong getAndDecrement returns pre-decrement value and decrements" in run {
        val c =
            CAtomicLong.init(5L).flatMap { a =>
                a.getAndDecrement.flatMap { old =>
                    a.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == 5L && now == 4L)
        }
    }
    "AtomicLong getAndAdd returns pre-add value with positive delta" in run {
        val c =
            CAtomicLong.init(10L).flatMap { a =>
                a.getAndAdd(3L).flatMap { old =>
                    a.get.flatMap { now =>
                        CIO.defer((old, now))
                    }
                }
            }
        c.map { case (old, now) =>
            assert(old == 10L && now == 13L)
        }
    }
    "AtomicBoolean concurrent toggle across 100 fibers does not crash and yields well-defined state" in run {
        // Stress test: 100 fibers each toggle the boolean via getAndSet.
        // We don't assert a specific final value (that depends on execution order),
        // but we assert (a) no crash, and (b) the final value is a valid boolean.
        val c =
            CAtomicBoolean.init(false).flatMap { ref =>
                CIO.foreach(1 to 100) { _ =>
                    CFiber.init(
                        ref.get.flatMap { cur =>
                            ref.getAndSet(!cur)
                        }
                    )
                }.flatMap { fibers =>
                    CIO.collectAll(fibers.toSeq.map(_.get)).flatMap { _ =>
                        ref.get
                    }
                }
            }
        c.map { v =>
            // Boolean is always either true or false — but this confirms no crash.
            assert(v == true || v == false)
        }
    }
    "CAtomicInt lift/lower round-trip preserves observable behavior" in run {
        // lower returns the underlying carrier; lift wraps it back.
        // The re-lifted view must behave like the original: get returns 7,
        // incrementAndGet returns 8.
        val c =
            CAtomicInt.init(7).flatMap { original =>
                val relifted = CAtomicInt.lift(original.lower)
                relifted.get.flatMap { v =>
                    relifted.incrementAndGet.flatMap { v2 =>
                        CIO.defer((v, v2))
                    }
                }
            }
        c.map { case (v, v2) =>
            assert(v == 7, s"expected get == 7, got $v")
            assert(v2 == 8, s"expected incrementAndGet == 8, got $v2")
        }
    }
    "CAtomicLong lift/lower round-trip preserves observable behavior" in run {
        val c =
            CAtomicLong.init(7L).flatMap { original =>
                val relifted = CAtomicLong.lift(original.lower)
                relifted.get.flatMap { v =>
                    relifted.incrementAndGet.flatMap { v2 =>
                        CIO.defer((v, v2))
                    }
                }
            }
        c.map { case (v, v2) =>
            assert(v == 7L, s"expected get == 7L, got $v")
            assert(v2 == 8L, s"expected incrementAndGet == 8L, got $v2")
        }
    }
    "CAtomicBoolean lift/lower round-trip preserves observable behavior" in run {
        // init(true), lower, lift back. get returns true; getAndSet(false)
        // returns true (the prior value); subsequent get returns false.
        val c =
            CAtomicBoolean.init(true).flatMap { original =>
                val relifted = CAtomicBoolean.lift(original.lower)
                relifted.get.flatMap { v1 =>
                    relifted.getAndSet(false).flatMap { prior =>
                        relifted.get.flatMap { v2 =>
                            CIO.defer((v1, prior, v2))
                        }
                    }
                }
            }
        c.map { case (v1, prior, v2) =>
            assert(v1 == true, s"expected initial get == true, got $v1")
            assert(prior == true, s"expected getAndSet(false) to return prior true, got $prior")
            assert(v2 == false, s"expected get after getAndSet(false) == false, got $v2")
        }
    }

end AtomicNumTest
