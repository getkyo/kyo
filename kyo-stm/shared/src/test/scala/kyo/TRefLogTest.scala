package kyo

import kyo.TRefLog.*

class TRefLogTest extends kyo.test.Test[Any]:

    given [A, B]: CanEqual[A, B] = CanEqual.derived

    "TRefLog" - {
        "empty" in {
            val log = TRefLog.empty
            assert(log.toMap.isEmpty)
        }

        "put" in {
            Sync.Unsafe.defer {
                val tick  = STM.Tick.next()
                val ref   = new TRef[Int](Read(tick, 0))
                val entry = Write(tick, 42, 0)
                val log   = TRefLog.empty.put(ref, entry)
                assert(log.toMap.size == 1)
                assert(log.toMap.head._1 == ref)
                assert(log.toMap.head._2 == entry)
            }
        }

        "get" in {
            Sync.Unsafe.defer {
                val tick  = STM.Tick.next()
                val ref   = new TRef[Int](Read(tick, 0))
                val entry = Write(tick, 42, 0)
                val log   = TRefLog.empty.put(ref, entry)
                assert(log.get(ref) == Maybe(entry))
                assert(log.get(new TRef[Int](Read(tick, 0))).isEmpty)
            }
        }

        "toSeq" in {
            Sync.Unsafe.defer {
                val tick   = STM.Tick.next()
                val ref1   = new TRef[Int](Read(tick, 0))
                val ref2   = new TRef[Int](Read(tick, 0))
                val entry1 = Write(tick, 42, 0)
                val entry2 = Read(tick, 24)

                val log = TRefLog.empty
                    .put(ref1, entry1)
                    .put(ref2, entry2)

                val seq = log.toMap.toSeq
                assert(seq.size == 2)
                assert(seq.contains((ref1, entry1)))
                assert(seq.contains((ref2, entry2)))
            }
        }

        "TRefLog symbols are accessible from kyo package" in {
            Sync.Unsafe.defer {
                val log: TRefLog      = TRefLog.empty
                val read: Entry[Int]  = Read(STM.Tick.next(), 1)
                val write: Entry[Int] = Write(STM.Tick.next(), 2, 1)
                assert(log.toMap.isEmpty)
                assert(read.value == 1)
                assert(write.value == 2)
            }
        }

        "populates entries while a transaction is running" in {
            for
                ref1    <- TRef.init(10)
                ref2    <- TRef.init(20)
                entries <- STM.run {
                    for
                        _   <- ref1.get
                        _   <- ref2.set(99)
                        log <- Var.get[TRefLog]
                    yield log.toMap
                }
            yield
                assert(entries.size == 2)
                assert(entries.keySet.exists(_ eq ref1))
                assert(entries.keySet.exists(_ eq ref2))
        }

        "a first read logs the ref's committed entry until a write replaces it" in {
            for
                ref    <- TRef.init(1)
                first  <- STM.run(ref.get.andThen(Var.use[TRefLog](_.get(ref))))
                second <- STM.run(ref.get.andThen(Var.use[TRefLog](_.get(ref))))
                _      <- STM.run(ref.set(2))
                third  <- STM.run(ref.get.andThen(Var.use[TRefLog](_.get(ref))))
            yield
                assert(first.get eq second.get)
                assert(!(third.get eq first.get))
                assert(third.map(_.value) == Present(2))
        }

        "set records the value the transaction saw before writing as observed" in {
            for
                fresh   <- TRef.init(3)
                read    <- TRef.init(7)
                entries <- STM.run {
                    for
                        _   <- fresh.set(4)
                        _   <- fresh.set(5)
                        _   <- read.get
                        _   <- read.set(8)
                        log <- Var.get[TRefLog]
                    yield (log.get(fresh), log.get(read))
                }
            yield
                val (freshEntry, readEntry) = entries
                assert(freshEntry.map(e => (e.value, e.observed)) == Present((5, 3)))
                assert(readEntry.map(e => (e.value, e.observed)) == Present((8, 7)))
        }

        "log holds entries of distinct type parameters via Any erasure" in {
            Sync.Unsafe.defer {
                val tick      = STM.Tick.next()
                val intRef    = new TRef[Int](Read(tick, 0))
                val strRef    = new TRef[String](Read(tick, ""))
                val boolRef   = new TRef[Boolean](Read(tick, false))
                val intEntry  = Write(tick, 42, 0)
                val strEntry  = Write(tick, "hello", "")
                val boolEntry = Read(tick, true)
                val log       = TRefLog.empty
                    .put(intRef, intEntry)
                    .put(strRef, strEntry)
                    .put(boolRef, boolEntry)
                val gotInt  = log.get(intRef)
                val gotStr  = log.get(strRef)
                val gotBool = log.get(boolRef)
                assert(log.toMap.size == 3)
                assert(gotInt == Maybe(intEntry))
                assert(gotStr == Maybe(strEntry))
                assert(gotBool == Maybe(boolEntry))
                assert(gotInt.get.value == 42)
                assert(gotStr.get.value == "hello")
                assert(gotBool.get.value == true)
            }
        }

        "empty is the same instance across reads and is unchanged by put on derivatives" in {
            Sync.Unsafe.defer {
                val tick    = STM.Tick.next()
                val ref     = new TRef[Int](Read(tick, 0))
                val a       = TRefLog.empty
                val b       = TRefLog.empty
                val derived = a.put(ref, Write(tick, 1, 0))
                assert(a.toMap eq b.toMap)
                assert(TRefLog.empty.toMap.isEmpty)
                assert(a.toMap.isEmpty)
                assert(derived.toMap.size == 1)
            }
        }

        "put replaces a Read with a Write under the same ref" in {
            Sync.Unsafe.defer {
                val tick    = STM.Tick.next()
                val ref     = new TRef[Int](Read(tick, 0))
                val read    = Read(tick, 7)
                val write   = Write(tick, 13, 7)
                val initial = TRefLog.empty.put(ref, read)
                val updated = initial.put(ref, write)
                assert(initial.toMap.size == 1)
                assert(initial.get(ref) == Maybe(read))
                assert(updated.toMap.size == 1)
                assert(updated.get(ref) == Maybe(write))
                assert(updated.get(ref).get.isInstanceOf[TRefLog.Write[?]])
            }
        }

        "put replaces a Write with a Read under the same ref" in {
            Sync.Unsafe.defer {
                val tick    = STM.Tick.next()
                val ref     = new TRef[Int](Read(tick, 0))
                val write   = Write(tick, 99, 0)
                val read    = Read(tick, 50)
                val initial = TRefLog.empty.put(ref, write)
                val updated = initial.put(ref, read)
                assert(updated.toMap.size == 1)
                assert(updated.get(ref) == Maybe(read))
                assert(updated.get(ref).get.isInstanceOf[TRefLog.Read[?]])
            }
        }

        "put replaces a Read with a Read under the same ref" in {
            Sync.Unsafe.defer {
                val t1     = STM.Tick.next()
                val t2     = STM.Tick.next()
                val ref    = new TRef[Int](Read(t1, 0))
                val first  = Read(t1, 1)
                val second = Read(t2, 2)
                val l1     = TRefLog.empty.put(ref, first)
                val l2     = l1.put(ref, second)
                assert(l2.toMap.size == 1)
                assert(l2.get(ref) == Maybe(second))
                assert(l2.get(ref).get.tick == t2)
            }
        }

        "put replaces a Write with a newer Write under the same ref" in {
            Sync.Unsafe.defer {
                val t1  = STM.Tick.next()
                val t2  = STM.Tick.next()
                val ref = new TRef[Int](Read(t1, 0))
                val w1  = Write(t1, 11, 0)
                val w2  = Write(t2, 22, 0)
                val l1  = TRefLog.empty.put(ref, w1)
                val l2  = l1.put(ref, w2)
                assert(l2.toMap.size == 1)
                assert(l2.get(ref) == Maybe(w2))
                assert(l2.get(ref).get.value == 22)
            }
        }

        "put with null value round-trips through get" in {
            Sync.Unsafe.defer {
                val tick                     = STM.Tick.next()
                val ref                      = new TRef[String](Read(tick, ""))
                val nullWrite: Write[String] = Write(tick, null.asInstanceOf[String], "")
                val log                      = TRefLog.empty.put(ref, nullWrite)
                val got                      = log.get(ref)
                assert(log.toMap.size == 1)
                assert(got.isDefined)
                assert(got.get.value == null)
                assert(got == Maybe(nullWrite))
            }
        }

        "put rejects mismatched ref/entry type parameters at compile time" in {
            typeCheckFailure(
                """
                import kyo.*
                import kyo.TRefLog.*
                val tick     = STM.Tick.next()(using AllowUnsafe.embrace.danger)
                val intRef   = new TRef[Int](Read(tick, 0))
                val strEntry = Write(tick, "hello", "")
                TRefLog.empty.put(intRef, strEntry)
                """
            )("String")
        }

        "put stores the exact ref instance as the map key" in {
            Sync.Unsafe.defer {
                val tick  = STM.Tick.next()
                val ref   = new TRef[Int](Read(tick, 0))
                val entry = Write(tick, 42, 0)
                val log   = TRefLog.empty.put(ref, entry)
                val key   = log.toMap.head._1
                assert((key: AnyRef) eq (ref: AnyRef))
            }
        }

        "put stores the exact Entry instance as the map value" in {
            Sync.Unsafe.defer {
                val tick  = STM.Tick.next()
                val ref   = new TRef[Int](Read(tick, 0))
                val entry = Write(tick, 42, 0)
                val log   = TRefLog.empty.put(ref, entry)
                val value = log.toMap.head._2
                assert(value eq entry)
                assert(value.value == 42)
            }
        }

        "get from empty log returns Absent" in {
            Sync.Unsafe.defer {
                val tick = STM.Tick.next()
                val ref  = new TRef[Int](Read(tick, 0))
                val log  = TRefLog.empty
                val got  = log.get(ref)
                assert(got.isEmpty)
                assert(got == Maybe.empty)
            }
        }

        "get returns the latest entry after the same ref is put twice" in {
            Sync.Unsafe.defer {
                val tick   = STM.Tick.next()
                val ref    = new TRef[Int](Read(tick, 0))
                val first  = Write(tick, 1, 0)
                val second = Write(tick, 2, 0)
                val log    = TRefLog.empty.put(ref, first).put(ref, second)
                val got    = log.get(ref)
                assert(got == Maybe(second))
                assert(got.get.value == 2)
                assert(log.toMap.size == 1)
            }
        }

        "get retrieves the correct entry from a 10-entry log" in {
            Sync.Unsafe.defer {
                val tick    = STM.Tick.next()
                val refs    = (0 until 10).map(i => new TRef[Int](Read(tick, i)))
                val entries = (0 until 10).map(i => Write(tick, i * 100, i))
                val log     = refs.zip(entries).foldLeft(TRefLog.empty) {
                    case (acc, (r, e)) => acc.put(r, e)
                }
                val got = refs.zip(entries).map { case (r, e) => (log.get(r), e) }
                assert(log.toMap.size == 10)
                got.foreach { case (g, e) => assert(g == Maybe(e)) }
                ()
            }
        }

        "get uses ref identity for lookup not equality" in {
            Sync.Unsafe.defer {
                val tick        = STM.Tick.next()
                val original    = new TRef[Int](Read(tick, 42))
                val twin        = new TRef[Int](Read(tick, 42))
                val log         = TRefLog.empty.put(original, Write(tick, 100, 42))
                val gotOriginal = log.get(original)
                val gotTwin     = log.get(twin)
                assert(gotOriginal == Maybe(Write(tick, 100, 42)))
                assert(gotTwin.isEmpty)
            }
        }

        "get returns Entry[A] whose value compiles against A" in {
            Sync.Unsafe.defer {
                val tick   = STM.Tick.next()
                val intRef = new TRef[Int](Read(tick, 0))
                val strRef = new TRef[String](Read(tick, ""))
                val log    = TRefLog.empty
                    .put(intRef, Write(tick, 7, 0))
                    .put(strRef, Write(tick, "abc", ""))
                val ig: Maybe[TRefLog.Entry[Int]]    = log.get(intRef)
                val sg: Maybe[TRefLog.Entry[String]] = log.get(strRef)
                val iv: Int                          = ig.get.value
                val sv: String                       = sg.get.value
                assert(iv == 7)
                assert(sv == "abc")
            }
        }

        "toMap returns the underlying map by identity" in {
            Sync.Unsafe.defer {
                val tick = STM.Tick.next()
                val ref  = new TRef[Int](Read(tick, 0))
                val log  = TRefLog.empty.put(ref, Write(tick, 1, 0))
                val m1   = log.toMap
                val m2   = log.toMap
                assert(m1 eq m2)
            }
        }

        "toMap returns an immutable Map subtype" in {
            Sync.Unsafe.defer {
                val tick = STM.Tick.next()
                val ref  = new TRef[Int](Read(tick, 0))
                val log  = TRefLog.empty.put(ref, Write(tick, 1, 0))
                val m    = log.toMap
                // Build a derived map through the TRefLog API (no raw Map.updated /
                // no asInstanceOf widening of the invariant TRef/Entry types).
                val derived = log.put(ref, Read(tick, 2)).toMap
                assert(log.toMap.head._2 == Write(tick, 1, 0))
                assert(derived ne m)
                assert(derived.head._2 == Read(tick, 2))
            }
        }

        "failed nested STM.run restores the parent's entries and keeps what it read" in {
            for
                ref     <- TRef.init(0)
                other   <- TRef.init(5)
                outcome <- STM.run {
                    for
                        _ <- ref.set(1)
                        r <- Abort.run[String] {
                            STM.run {
                                for
                                    _ <- ref.set(2)
                                    _ <- other.get
                                    _ <- Abort.fail("boom")
                                yield ()
                            }
                        }
                        log <- Var.get[TRefLog]
                        v   <- ref.get
                    yield (r, log.get(ref).map(_.value), log.get(other), v)
                }
            yield
                val (r, refValue, otherEntry, v) = outcome
                assert(r.failure.contains("boom"))
                assert(refValue == Present(1))
                assert(otherEntry.exists(e => e.isInstanceOf[Read[?]] && e.value == 5))
                assert(v == 1)
        }

        "a failed block nested in a block that catches it and succeeds leaves its reads in the outermost log" in {
            for
                x     <- TRef.init(3)
                entry <- STM.run {
                    for
                        _   <- STM.run(Abort.run[String](STM.run(x.set(4).andThen(Abort.fail("inner")))))
                        log <- Var.get[TRefLog]
                    yield log.get(x)
                }
            yield assert(entry.exists(e => e.isInstanceOf[Read[?]] && e.value == 3))
        }

        "a failed block nested in a block that catches it and then fails leaves its reads in the outermost log" in {
            for
                x       <- TRef.init(3)
                outcome <- STM.run {
                    for
                        r <- Abort.run[String] {
                            STM.run {
                                Abort.run[String](STM.run(x.set(4).andThen(Abort.fail("inner"))))
                                    .andThen(Abort.fail("middle"))
                            }
                        }
                        log <- Var.get[TRefLog]
                    yield (r, log.get(x))
                }
            yield
                val (r, entry) = outcome
                assert(r.failure.contains("middle"))
                assert(entry.exists(e => e.isInstanceOf[Read[?]] && e.value == 3))
        }

        "rollback keeps the parent's entry for a ref the failed block also wrote" in {
            Sync.Unsafe.defer {
                val tick       = STM.Tick.next()
                val ref        = new TRef[Int](Read(tick, 0))
                val parent     = TRefLog.empty.put(ref, Write(tick, 1, 0))
                val rolledBack = parent.rollback(parent.put(ref, Write(tick, 2, 0)))
                assert(rolledBack.get(ref) == Present(Write(tick, 1, 0)))
            }
        }

        "rollback keeps a Read for a ref only the failed block read" in {
            Sync.Unsafe.defer {
                val tick       = STM.Tick.next()
                val ref        = new TRef[Int](Read(tick, 3))
                val rolledBack = TRefLog.empty.rollback(TRefLog.empty.put(ref, Read(tick, 3)))
                assert(rolledBack.get(ref) == Present(Read(tick, 3)))
            }
        }

        "rollback turns a Write only the failed block made into a Read of the value it observed" in {
            Sync.Unsafe.defer {
                val tick       = STM.Tick.next()
                val ref        = new TRef[Int](Read(tick, 3))
                val rolledBack = TRefLog.empty.rollback(TRefLog.empty.put(ref, Write(tick, 9, 3)))
                assert(rolledBack.get(ref) == Present(Read(tick, 3)))
            }
        }

        "rollback turns a Write whose ref has moved into a Read of the observed value that fails validation" in {
            Sync.Unsafe.defer {
                val base       = STM.Tick.next()
                val ref        = new TRef[Int](Read(STM.Tick.next(), 4))
                val rolledBack = TRefLog.empty.rollback(TRefLog.empty.put(ref, Write(base, 9, 3)))
                assert(rolledBack.get(ref) == Present(Read(base, 3)))
                assert(!ref.validate(rolledBack.get(ref).get))
            }
        }

        "nested STM.run on empty inner log preserves parent log identity" in {
            for
                ref    <- TRef.init(7)
                result <- STM.run {
                    for
                        _      <- ref.set(8)
                        before <- Var.get[TRefLog]
                        _      <- STM.run {
                            (): Unit
                        }
                        after <- Var.get[TRefLog]
                        v     <- ref.get
                    yield (before.toMap, after.toMap, v)
                }
            yield
                val (beforeMap, afterMap, v) = result
                assert(beforeMap eq afterMap)
                assert(v == 8)
        }

        "nested STM.run merges nested Write over parent Read for same ref" in {
            for
                ref      <- TRef.init(0)
                logState <- STM.run {
                    for
                        _ <- ref.get
                        _ <- STM.run {
                            ref.set(42)
                        }
                        parent <- Var.get[TRefLog]
                        v      <- ref.get
                    yield (parent.get(ref), v)
                }
            yield
                val (entryUnderRef, v) = logState
                assert(entryUnderRef.isDefined)
                assert(entryUnderRef.get.isInstanceOf[TRefLog.Write[?]])
                assert(entryUnderRef.get.value == 42)
                assert(v == 42)
        }

        "Entry abstract members surface tick and value" in {
            Sync.Unsafe.defer {
                val tick                         = STM.Tick.next()
                val asEntry1: TRefLog.Entry[Int] = Read(tick, 10)
                val asEntry2: TRefLog.Entry[Int] = Write(tick, 20, 10)
                val t1                           = asEntry1.tick
                val v1                           = asEntry1.value
                val t2                           = asEntry2.tick
                val v2                           = asEntry2.value
                assert(t1 == tick)
                assert(v1 == 10)
                assert(t2 == tick)
                assert(v2 == 20)
                assert(asEntry1.observed == 10)
                assert(asEntry2.observed == 10)
            }
        }

        "Read tolerates a null value field" in {
            Sync.Unsafe.defer {
                val tick            = STM.Tick.next()
                val r: Read[String] = Read(tick, null.asInstanceOf[String])
                val t               = r.tick
                val v               = r.value
                val s               = r.toString
                assert(t == tick)
                assert(v == null)
                assert(s.contains("Read"))
            }
        }

        "Read.copy updates fields and preserves equality semantics" in {
            Sync.Unsafe.defer {
                val t1 = STM.Tick.next()
                val t2 = STM.Tick.next()
                val r0 = Read(t1, 42)
                val r1 = r0.copy(value = 99)
                val r2 = r0.copy(tick = t2)
                val r3 = r0.copy()
                assert(r1 == Read(t1, 99))
                assert(r2 == Read(t2, 42))
                assert(r3 == r0)
                assert(r3 ne r0)
            }
        }

        "Read pattern match extracts tick and value" in {
            Sync.Unsafe.defer {
                val tick                            = STM.Tick.next()
                val r: TRefLog.Entry[Int]           = Read(tick, 77)
                val (extractedTick, extractedValue) = r match
                    case Read(t, v) => (t, v)
                    case _          => fail("expected Read")
                assert(extractedTick == tick)
                assert(extractedValue == 77)
            }
        }

        "Write tolerates a null value field" in {
            Sync.Unsafe.defer {
                val tick             = STM.Tick.next()
                val w: Write[String] = Write(tick, null.asInstanceOf[String], null.asInstanceOf[String])
                val v                = w.value
                val s                = w.toString
                assert(w.tick == tick)
                assert(v == null)
                assert(s.contains("Write"))
            }
        }

        "Write.copy updates fields and preserves equality semantics" in {
            Sync.Unsafe.defer {
                val t1 = STM.Tick.next()
                val t2 = STM.Tick.next()
                val w0 = Write(t1, 42, 1)
                val w1 = w0.copy(value = 99)
                val w2 = w0.copy(tick = t2)
                val w3 = w0.copy()
                assert(w1 == Write(t1, 99, 1))
                assert(w2 == Write(t2, 42, 1))
                assert(w3 == w0)
                assert(w3 ne w0)
            }
        }

        "Write pattern match extracts tick, value and observed" in {
            Sync.Unsafe.defer {
                val tick                                    = STM.Tick.next()
                val w: TRefLog.Entry[Int]                   = Write(tick, 88, 1)
                val (extractedTick, extractedValue, before) = w match
                    case Write(t, v, o) => (t, v, o)
                    case _              => fail("expected Write")
                assert(extractedTick == tick)
                assert(extractedValue == 88)
                assert(before == 1)
            }
        }

        "Read and Write are not equal even with identical fields" in {
            Sync.Unsafe.defer {
                val tick                  = STM.Tick.next()
                val r: TRefLog.Entry[Int] = Read(tick, 42)
                val w: TRefLog.Entry[Int] = Write(tick, 42, 42)
                val eqResult              = (r == w) || (w == r)
                assert(!eqResult)
                assert(r != w)
                assert(w != r)
                assert(r.isInstanceOf[TRefLog.Read[?]])
                assert(w.isInstanceOf[TRefLog.Write[?]])
                assert(!r.isInstanceOf[TRefLog.Write[?]])
                assert(!w.isInstanceOf[TRefLog.Read[?]])
            }
        }

        "Read with identical tick/value satisfies equals and hashCode contract" in {
            Sync.Unsafe.defer {
                val tick = STM.Tick.next()
                val a    = Read(tick, 7)
                val b    = Read(tick, 7)
                val c    = Read(tick, 8)
                assert(a == b)
                assert(!(a == c))
                assert(a.hashCode == b.hashCode)
            }
        }

        "Write with identical fields satisfies equals and hashCode contract" in {
            Sync.Unsafe.defer {
                val tick = STM.Tick.next()
                val a    = Write(tick, "x", "o")
                val b    = Write(tick, "x", "o")
                val c    = Write(tick, "y", "o")
                val d    = Write(tick, "x", "p")
                assert(a == b)
                assert(!(a == c))
                assert(!(a == d))
                assert(a.hashCode == b.hashCode)
            }
        }

        "each STM.run starts with a fresh empty log" in {
            for
                logSize1 <- STM.run(Var.use[TRefLog](l => l.toMap.size))
                _        <- TRef.init(1).map(_ => ())
                logSize2 <- STM.run(Var.use[TRefLog](l => l.toMap.size))
            yield
                assert(logSize1 == 0)
                assert(logSize2 == 0)
        }

        "extension methods are dispatched on any TRefLog value" in {
            Sync.Unsafe.defer {
                for
                    ref       <- TRef.init(5)
                    seqResult <- STM.run {
                        for
                            _     <- ref.set(6)
                            inner <- Var.get[TRefLog]
                            gotInside = inner.get(ref)
                            mapInside = inner.toMap
                            _      <- ref.get
                            inner2 <- Var.get[TRefLog]
                            afterPut = inner2.put(ref, Write(STM.Tick.next(), 9, 5))
                        yield (gotInside.isDefined, mapInside.size, afterPut.get(ref).map(_.value))
                    }
                yield
                    val (g, sz, afterVal) = seqResult
                    assert(g)
                    assert(sz >= 1)
                    assert(afterVal == Maybe(9))
            }
        }

        "repeated put on the same ref collapses to a single entry" in {
            Sync.Unsafe.defer {
                val tick = STM.Tick.next()
                val ref  = new TRef[Int](Read(tick, 0))
                val log  = (1 to 5).foldLeft(TRefLog.empty) { case (l, i) =>
                    l.put(ref, Write(tick, i, 0))
                }
                val got = log.get(ref)
                val sz  = log.toMap.size
                assert(sz == 1)
                assert(got == Maybe(Write(tick, 5, 0)))
            }
        }

        "two distinct refs each retain their entry in toMap" in {
            Sync.Unsafe.defer {
                val tick = STM.Tick.next()
                val r1   = new TRef[Int](Read(tick, 0))
                val r2   = new TRef[Int](Read(tick, 0))
                val e1   = Write(tick, 11, 0)
                val e2   = Read(tick, 22)
                val log  = TRefLog.empty.put(r1, e1).put(r2, e2)
                val got1 = log.get(r1)
                val got2 = log.get(r2)
                assert(log.toMap.size == 2)
                assert(got1 == Maybe(e1))
                assert(got2 == Maybe(e2))
            }
        }

        "get returns Absent rather than throwing on a missing ref" in {
            Sync.Unsafe.defer {
                val tick      = STM.Tick.next()
                val unrelated = new TRef[Int](Read(tick, 0))
                val log       = TRefLog.empty
                val got       = scala.util.Try(log.get(unrelated))
                assert(got.isSuccess)
                assert(got.get.isEmpty)
            }
        }

        "nested STM.run propagates Abort.fail from inner to outer" in {
            for
                result <- Abort.run[String] {
                    STM.run {
                        STM.run {
                            Abort.fail("inner-fail")
                        }
                    }
                }
            yield
                assert(result.isFailure)
                assert(result.failure.contains("inner-fail"))
        }

        "nested STM.run returns the inner value when it succeeds" in {
            for
                ref    <- TRef.init(0)
                result <- STM.run {
                    STM.run {
                        ref.set(1).andThen(ref.get).map(v => v + 100)
                    }
                }
            yield assert(result == 101)
        }

        "sealed Entry hierarchy is exhaustively matchable as Read and Write" in {
            Sync.Unsafe.defer {
                val tick                              = STM.Tick.next()
                val entries: List[TRefLog.Entry[Int]] = List(Read(tick, 1), Write(tick, 2, 1))
                val labels                            = entries.map {
                    case Read(_, v)     => s"R$v"
                    case Write(_, v, _) => s"W$v"
                }
                assert(labels == List("R1", "W2"))
            }
        }
    }
end TRefLogTest
