package kyo

/** Property-style tests for kyo-stm.
  *
  * These use scalatest's `TableDrivenPropertyChecks` with hand-picked representative rows (edge cases: empty / single / boundary) exercised
  * inside `STM.run`. Rows are iterated with `Kyo.foreachDiscard` so the per-row assertions compose with kyo effects.
  */
class STMPropertyTest extends kyo.test.Test[Any]:

    "STM laws" - {

        "STM.run - identity property: STM.run(pure x) yields x" in {
            val ints = Seq(Int.MinValue, -1, 0, 1, 42, Int.MaxValue)
            val strs = Seq("", "a", "alpha123", "  spaced  ")
            for
                _ <- Kyo.foreachDiscard(ints.toSeq) { x =>
                    STM.run(x).map(r => assert(r == x))
                }
                _ <- Kyo.foreachDiscard(strs.toSeq) { s =>
                    STM.run(s).map(r => assert(r == s))
                }
            yield ()
            end for
        }

        "STM.run - nested flatness: STM.run(STM.run(x)) yields same value as STM.run(x) for any pure x" in {
            val ints = Seq(-1000, -1, 0, 1, 500, 1000)
            Kyo.foreachDiscard(ints.toSeq) { x =>
                for
                    outer  <- STM.run(STM.run(x))
                    direct <- STM.run(x)
                yield assert(outer == x && direct == x)
            }.unit
        }

        "STM.retry - property: STM.run wrapping STM.retry never returns a value, always fails with FailedTransaction" in {
            val caps = Seq(0, 1, 2, 3, 5)
            Kyo.foreachDiscard(caps.toSeq) { cap =>
                Abort.run[FailedTransaction](STM.run(Schedule.repeat(cap))(STM.retry)).map { result =>
                    assert(result.isFailure)
                    assert(result.failure.exists(_.isInstanceOf[FailedTransaction]))
                }
            }.unit
        }

        "STM.retryIf - boolean property: false branch returns Unit, true branch aborts with FailedTransaction" in {
            val conds = Seq(true, false)
            Kyo.foreachDiscard(conds.toSeq) { cond =>
                Abort.run[FailedTransaction](STM.run(Schedule.repeat(0))(STM.retryIf(cond))).map { result =>
                    if cond then
                        assert(result.isFailure)
                        assert(result.failure.exists(_.isInstanceOf[FailedTransaction]))
                    else
                        assert(result == Result.succeed(()))
                }
            }.unit
        }
    }

    "TRef laws" - {

        "TRef.init - get property: TRef.init(v).get yields v for any value v" in {
            val ints = Seq(Int.MinValue, -1, 0, 1, 42, Int.MaxValue)
            val strs = Seq("", "a", "alpha123")
            val opts = Seq(None, Some(0), Some(-100), Some(100))
            for
                _ <- Kyo.foreachDiscard(ints.toSeq)(v => STM.run(TRef.init(v).map(_.get)).map(r => assert(r == v)))
                _ <- Kyo.foreachDiscard(strs.toSeq)(v => STM.run(TRef.init(v).map(_.get)).map(r => assert(r == v)))
                _ <- Kyo.foreachDiscard(opts.toSeq)(v => STM.run(TRef.init(v).map(_.get)).map(r => assert(r == v)))
            yield ()
            end for
        }

        "TRef.set - last-write-wins property: a sequence of sets within one transaction commits the final value" in {
            val cases = Seq(
                (0, Seq(1)),
                (-5, Seq(10, 20)),
                (7, Seq(1, 2, 3, 4, 5)),
                (100, Seq(100)),
                (3, (1 to 50).toSeq)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (init, writes) =>
                STM.run {
                    for
                        ref <- TRef.init(init)
                        _   <- Kyo.foreachDiscard(writes)(w => ref.set(w))
                        v   <- ref.get
                    yield v
                }.map(r => assert(r == writes.last))
            }.unit
        }

        "TRef.update - functional property: TRef.init(v).update(f).get yields f(v) for any total f" in {
            val fns = Seq(
                ("id", (x: Int) => x),
                ("inc", (x: Int) => x + 1),
                ("double", (x: Int) => x * 2),
                ("neg", (x: Int) => -x),
                ("addSeven", (x: Int) => x + 7)
            )
            val vs = Seq(-1000, -1, 0, 1, 1000)
            Kyo.foreachDiscard(vs.toSeq) { v =>
                Kyo.foreachDiscard(fns.toSeq) { case (name, f) =>
                    STM.run {
                        for
                            ref <- TRef.init(v)
                            _   <- ref.update(x => f(x))
                            v2  <- ref.get
                        yield v2
                    }.map(r => assert(r == f(v), s"fn=$name init=$v expected=${f(v)} got=$r"))
                }
            }.unit
        }

        "TRef.update - composition law: update(f) then update(g) is equivalent to update(g ∘ f)" in {
            val fns = Seq(
                (x: Int) => x + 1,
                (x: Int) => x - 1,
                (x: Int) => x * 2,
                (x: Int) => -x,
                (x: Int) => x % 17
            )
            val vs = Seq(-100, -1, 0, 1, 100)
            Kyo.foreachDiscard(vs.toSeq) { v =>
                Kyo.foreachDiscard(fns.toSeq) { f =>
                    Kyo.foreachDiscard(fns.toSeq) { g =>
                        for
                            lhs <- STM.run {
                                for
                                    ref <- TRef.init(v)
                                    _   <- ref.update(f)
                                    _   <- ref.update(g)
                                    x   <- ref.get
                                yield x
                            }
                            rhs <- STM.run {
                                for
                                    ref <- TRef.init(v)
                                    _   <- ref.update(x => g(f(x)))
                                    x   <- ref.get
                                yield x
                            }
                        yield assert(lhs == rhs, s"v=$v lhs=$lhs rhs=$rhs")
                    }
                }
            }.unit
        }

        "TRef.use - law: use(f) equals f(get) for any pure f" in {
            val fns = Seq(
                ("toString", (i: Int) => i.toString),
                ("hex", (i: Int) => i.toHexString),
                ("constA", (_: Int) => "A"),
                ("len", (i: Int) => "x" * (i.abs % 5))
            )
            val vs = Seq(-100, -1, 0, 1, 100)
            Kyo.foreachDiscard(vs.toSeq) { v =>
                Kyo.foreachDiscard(fns.toSeq) { case (name, f) =>
                    STM.run(TRef.init(v).map(_.use(a => f(a)))).map(r => assert(r == f(v), s"fn=$name v=$v"))
                }
            }.unit
        }

        "TRef.initWith - identity property: initWith(v)(_.get) yields v" in {
            val vs = Seq(-1000, -1, 0, 1, 42, 1000)
            Kyo.foreachDiscard(vs.toSeq) { v =>
                STM.run(TRef.initWith(v)(_.get)).map(r => assert(r == v))
            }.unit
        }
    }

    "TMap laws" - {

        // Representative entry sets shared by the TMap snapshot/size/isEmpty laws.
        val entrySets: Seq[Seq[(String, Int)]] = Seq(
            Seq.empty[(String, Int)],
            Seq("a" -> 1),
            Seq("a" -> 1, "b" -> 2, "c" -> 3),
            Seq("a" -> 1, "a" -> 2), // duplicate key — toMap keeps last
            Seq("x" -> 0, "y" -> 0, "z" -> 0), // duplicate values
            (1 to 30).map(i => s"k$i" -> i) // larger map
        )

        "TMap.init - snapshot round-trip: TMap.init(entries*).snapshot equals entries.toMap" in {
            val cases = Seq(entrySets*)
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                STM.run(TMap.init(entries*).map(_.snapshot)).map { actual =>
                    assert(actual == entries.toMap, s"entries=$entries")
                }
            }.unit
        }

        "TMap - size property: TMap.init(entries*).size equals entries.toMap.size for any entries" in {
            val cases = Seq(entrySets*)
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                STM.run(TMap.init(entries*).map(_.size)).map { n =>
                    assert(n == entries.toMap.size, s"entries=$entries")
                }
            }.unit
        }

        "TMap - isEmpty property: TMap.init(entries*).isEmpty agrees with entries.toMap.isEmpty" in {
            val cases = Seq(entrySets*)
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                STM.run {
                    TMap.init(entries*).map(m => m.isEmpty.map(a => m.nonEmpty.map(b => (a, b))))
                }.map { case (isE, nonE) =>
                    assert(isE == entries.toMap.isEmpty, s"entries=$entries")
                    assert(nonE == entries.toMap.nonEmpty, s"entries=$entries")
                    assert(isE != nonE, s"isEmpty=$isE nonEmpty=$nonE must be complementary")
                }
            }.unit
        }

        "TMap.contains - property: TMap.init(entries*).contains(k) iff entries.toMap.contains(k)" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "x"),
                (Seq("a" -> 1), "a"),
                (Seq("a" -> 1), "b"),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "b"),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "z"),
                ((1 to 30).map(i => s"k$i" -> i), "k15"),
                ((1 to 30).map(i => s"k$i" -> i), "missing")
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, probe) =>
                STM.run(TMap.init(entries*).map(_.contains(probe))).map { actual =>
                    assert(actual == entries.toMap.contains(probe), s"entries=$entries probe=$probe")
                }
            }.unit
        }

        "TMap.get - property: TMap.init(entries*).get(k) yields Maybe.fromOption(entries.toMap.get(k))" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "x"),
                (Seq("a" -> 1), "a"),
                (Seq("a" -> 1), "b"),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "c"),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "z"),
                ((1 to 30).map(i => s"k$i" -> i), "k7")
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, probe) =>
                STM.run(TMap.init(entries*).map(_.get(probe))).map { actual =>
                    assert(actual == Maybe.fromOption(entries.toMap.get(probe)), s"entries=$entries probe=$probe")
                }
            }.unit
        }

        "TMap - get/contains consistency: get(k).isDefined == contains(k) for all (map, k)" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "x"),
                (Seq("a" -> 1), "a"),
                (Seq("a" -> 1), "b"),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "b"),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "z")
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, probe) =>
                STM.run {
                    TMap.init(entries*).map(m => m.get(probe).map(g => m.contains(probe).map(h => (g, h))))
                }.map { case (got, has) =>
                    assert(got.isDefined == has, s"entries=$entries probe=$probe")
                }
            }.unit
        }

        "TMap.put - get round-trip: after put(k, v), get(k) yields Maybe(v) regardless of prior state" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "a", 1),
                (Seq("a" -> 1), "a", 99),
                (Seq("a" -> 1), "b", 2),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "d", -7),
                ((1 to 30).map(i => s"k$i" -> i), "k15", 1000)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, k, v) =>
                STM.run {
                    TMap.init(entries*).map(m => m.put(k, v).andThen(m.get(k)))
                }.map(got => assert(got == Maybe(v), s"entries=$entries k=$k v=$v"))
            }.unit
        }

        "TMap.put - contains property: after put(k, v), contains(k) is true" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "a", 1),
                (Seq("a" -> 1), "a", 99),
                (Seq("a" -> 1), "b", 2),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "d", -7)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, k, v) =>
                STM.run {
                    TMap.init(entries*).map(m => m.put(k, v).andThen(m.contains(k)))
                }.map(has => assert(has, s"after put($k,$v) on entries=$entries, contains returned false"))
            }.unit
        }

        "TMap.put / remove - inverse property: put(k, v) then remove(k) yields Maybe(v) and afterwards contains(k) is false" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "a", 1),
                (Seq("a" -> 1), "a", 99),
                (Seq("a" -> 1, "b" -> 2), "c", 3),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "d", -7)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, k, v) =>
                STM.run {
                    TMap.init(entries*).map { m =>
                        for
                            _       <- m.put(k, v)
                            removed <- m.remove(k)
                            has     <- m.contains(k)
                        yield (removed, has)
                    }
                }.map { case (removed, has) =>
                    assert(removed == Maybe(v), s"removed=$removed expected=${Maybe(v)} entries=$entries")
                    assert(!has, s"after remove($k) on entries=$entries, contains returned true")
                }
            }.unit
        }

        "TMap.put - size-delta property: put(k, v) increments size by 1 iff k was absent" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "a", 1),
                (Seq("a" -> 1), "a", 99),
                (Seq("a" -> 1), "b", 2),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "d", -7),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "b", 0)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, k, v) =>
                val was    = entries.toMap.contains(k)
                val before = entries.toMap.size
                STM.run {
                    TMap.init(entries*).map(m => m.put(k, v).andThen(m.size))
                }.map { after =>
                    assert(after == (if was then before else before + 1), s"was=$was before=$before after=$after k=$k")
                }
            }.unit
        }

        "TMap.filter - post-condition: after filter(p), every remaining entry satisfies p" in {
            val preds = Seq(
                ("all", (_: String, _: Int) => true),
                ("none", (_: String, _: Int) => false),
                ("even", (_: String, v: Int) => v % 2 == 0),
                ("lowerHead", (k: String, _: Int) => k.nonEmpty && k.head.isLower),
                ("positive", (_: String, v: Int) => v > 0)
            )
            val data = Seq(entrySets*)
            Kyo.foreachDiscard(data.toSeq) { entries =>
                Kyo.foreachDiscard(preds.toSeq) { case (name, p) =>
                    STM.run {
                        TMap.init(entries*).map(m => m.filter((k, v) => p(k, v)).andThen(m.snapshot))
                    }.map { snap =>
                        assert(snap.forall { case (k, v) => p(k, v) }, s"pred=$name post-filter map=$snap")
                        assert(snap == entries.toMap.filter { case (k, v) => p(k, v) }, s"pred=$name entries=$entries")
                    }
                }
            }.unit
        }

        "TMap.fold - count property: fold(0)((acc,_,_) => acc + 1) equals size" in {
            val cases = Seq(entrySets*)
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                STM.run(TMap.init(entries*).map(_.fold(0)((acc, _, _) => acc + 1))).map { count =>
                    assert(count == entries.toMap.size, s"entries=$entries")
                }
            }.unit
        }

        "TMap.fold - sum property: fold(0)(_ + value) equals sum of distinct-key values" in {
            val cases = Seq(entrySets*)
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                STM.run(TMap.init(entries*).map(_.fold(0L)((acc, _, v) => acc + v.toLong))).map { sum =>
                    assert(sum == entries.toMap.values.map(_.toLong).sum, s"entries=$entries")
                }
            }.unit
        }

        "TMap.findFirst - empty-map property: returns Absent and predicate is never invoked" in {
            val counter = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                TMap.init[String, Int]().map(_.findFirst { (k, v) =>
                    counter.incrementAndGet()
                    Maybe(s"$k=$v")
                })
            }.map { result =>
                assert(result == Absent)
                assert(counter.get() == 0, s"predicate invoked ${counter.get()} times on empty map")
            }
        }

        "TMap.findFirst - match property: returns Present when some entry satisfies the predicate" in {
            val preds = Seq(
                ("all", (_: String, _: Int) => true),
                ("none", (_: String, _: Int) => false),
                ("even", (_: String, v: Int) => v % 2 == 0),
                ("positive", (_: String, v: Int) => v > 0)
            )
            val data = Seq(
                Seq("a" -> 1),
                Seq("a" -> 1, "b"  -> 2, "c" -> 3),
                Seq("x" -> -1, "y" -> -2),
                (1 to 30).map(i => s"k$i" -> i)
            )
            Kyo.foreachDiscard(data.toSeq) { entries =>
                Kyo.foreachDiscard(preds.toSeq) { case (name, p) =>
                    val expected = entries.toMap.exists { case (k, v) => p(k, v) }
                    STM.run {
                        TMap.init(entries*).map(_.findFirst { (k, v) =>
                            if p(k, v) then Maybe(v) else Absent
                        })
                    }.map { actual =>
                        assert(actual.isDefined == expected, s"pred=$name entries=$entries actual=$actual")
                        actual match
                            case Present(v) =>
                                assert(
                                    entries.toMap.exists { case (k, v2) => v == v2 && p(k, v2) },
                                    s"returned value=$v not in entries matching predicate $name"
                                )
                            case Absent => ()
                        end match
                    }
                }
            }.unit
        }

        "TMap.keys - consistency property: keys.toSet equals snapshot.keys.toSet" in {
            val cases = Seq(entrySets*)
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                val expected = entries.toMap.keySet
                for
                    keysResult <- STM.run(TMap.init(entries*).map(_.keys.map(_.toSet)))
                    snapKeys   <- STM.run(TMap.init(entries*).map(_.snapshot.map(_.keySet)))
                yield
                    assert(keysResult == expected, s"keys=$keysResult expected=$expected")
                    assert(snapKeys == expected, s"snapKeys=$snapKeys expected=$expected")
                end for
            }.unit
        }

        "TMap.values - consistency property: values.toMultiSet equals snapshot.values.toMultiSet" in {
            // Value range 0-5 forces duplicate values across distinct keys.
            val cases = Seq(
                Seq.empty[(String, Int)],
                Seq("a" -> 1),
                Seq("a" -> 0, "b" -> 0, "c" -> 0),
                Seq("a" -> 1, "b" -> 2, "c" -> 1, "d" -> 3),
                (1 to 20).map(i => s"k$i" -> (i % 6))
            )
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                STM.run(TMap.init(entries*).map(_.values.map(_.toSeq.sorted))).map { valuesL =>
                    assert(valuesL == entries.toMap.values.toSeq.sorted, s"entries=$entries")
                }
            }.unit
        }

        "TMap.entries - consistency property: entries.toMap equals snapshot for any initial entries" in {
            val cases = Seq(entrySets*)
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                for
                    e <- STM.run(TMap.init(entries*).map(_.entries.map(_.toMap)))
                    s <- STM.run(TMap.init(entries*).map(_.snapshot))
                yield
                    assert(e == entries.toMap, s"entries=$e expected=${entries.toMap}")
                    assert(s == entries.toMap, s"snapshot=$s expected=${entries.toMap}")
                    assert(e == s)
            }.unit
        }

        "TMap.getOrElse - equivalence property: getOrElse(k, d) equals get(k).getOrElse(d)" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "a", -1),
                (Seq("a" -> 1), "a", -1),
                (Seq("a" -> 1), "b", -1),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "c", 999),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "z", 999)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, k, d) =>
                for
                    lhs <- STM.run(TMap.init(entries*).map(_.getOrElse(k, d)))
                    rhs <- STM.run(TMap.init(entries*).map(_.get(k).map(_.getOrElse(d))))
                yield assert(lhs == rhs, s"getOrElse=$lhs get.getOrElse=$rhs entries=$entries k=$k d=$d")
            }.unit
        }

        "TMap.getOrElse - laziness property: default expression is NOT evaluated when key is present" in {
            val cases = Seq(
                (1, Seq.empty[(String, Int)]),
                (-100, Seq("e1" -> 10)),
                (100, Seq("e1" -> 10, "e2" -> 20)),
                (0, Seq("e1" -> 10, "e2" -> 20, "e3" -> 30))
            )
            Kyo.foreachDiscard(cases.toSeq) { case (v, extras) =>
                val k        = "presentKey"
                val counter  = new java.util.concurrent.atomic.AtomicInteger(0)
                val combined = (k -> v) +: extras.filterNot(_._1 == k)
                STM.run(TMap.init(combined*).map(_.getOrElse(
                    k, {
                        counter.incrementAndGet()
                        -1
                    }
                ))).map { got =>
                    assert(got == v, s"got=$got expected=$v")
                    assert(counter.get() == 0, s"orElse evaluated ${counter.get()} times despite key being present")
                }
            }.unit
        }

        "TMap.updateWith - remove property: updateWith(k)(_ => Absent) removes k from the map" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "a"),
                (Seq("a" -> 1), "a"),
                (Seq("a" -> 1), "b"),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "b"),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "z")
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, k) =>
                STM.run {
                    TMap.init(entries*).map(m => m.updateWith(k)(_ => Maybe.empty).andThen(m.snapshot))
                }.map { after =>
                    assert(after == (entries.toMap - k), s"after=$after expected=${entries.toMap - k}")
                }
            }.unit
        }

        "TMap.updateWith - set property: updateWith(k)(_ => Present(v)) sets k to v" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], "a", 1),
                (Seq("a" -> 1), "a", 99),
                (Seq("a" -> 1), "b", 2),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), "d", -7)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, k, v) =>
                STM.run {
                    TMap.init(entries*).map(m => m.updateWith(k)(_ => Maybe(v)).andThen(m.get(k)))
                }.map(after => assert(after == Maybe(v), s"entries=$entries k=$k v=$v"))
            }.unit
        }

        "TMap.clear - empties property: after clear, size == 0 and isEmpty == true" in {
            val cases = Seq(entrySets*)
            Kyo.foreachDiscard(cases.toSeq) { entries =>
                STM.run {
                    TMap.init(entries*).map(m => m.clear.andThen(m.size.map(s => m.isEmpty.map(e => (s, e)))))
                }.map { case (s, e) =>
                    assert(s == 0 && e, s"after clear: size=$s isEmpty=$e on entries=$entries")
                }
            }.unit
        }

        "TMap.removeAll - property: after removeAll(keys), none of keys are present" in {
            val cases = Seq(
                (Seq.empty[(String, Int)], Seq.empty[String]),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), Seq.empty[String]),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), Seq("a")),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), Seq("a", "b", "c")),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), Seq("x", "y")),
                (Seq("a" -> 1, "b" -> 2, "c" -> 3), Seq("a", "a", "z")),
                ((1 to 20).map(i => s"k$i" -> i), (1 to 10).map(i => s"k$i"))
            )
            Kyo.foreachDiscard(cases.toSeq) { case (entries, keys) =>
                STM.run {
                    TMap.init(entries*).map(m => m.removeAll(keys).andThen(m.snapshot))
                }.map { after =>
                    assert(after == (entries.toMap -- keys), s"after=$after expected=${entries.toMap -- keys} keys=$keys")
                    assert(keys.forall(k => !after.contains(k)))
                }
            }.unit
        }
    }

    "TChunk laws" - {

        val chunkSets: Seq[Chunk[Int]] = Seq(
            Chunk.empty[Int],
            Chunk(42),
            Chunk(1, 2, 3),
            Chunk(-5, 0, 5, 10),
            Chunk.from(1 to 30)
        )

        val nonEmptyChunkSets: Seq[Chunk[Int]] = Seq(
            Chunk(42),
            Chunk(1, 2, 3),
            Chunk(-5, 0, 5, 10),
            Chunk.from(1 to 20)
        )

        "TChunk.init - snapshot round-trip: TChunk.init(chunk).snapshot equals chunk" in {
            val cases = Seq(chunkSets*)
            Kyo.foreachDiscard(cases.toSeq) { c =>
                STM.run(TChunk.init(c).map(_.snapshot)).map(got => assert(got == c, s"got=$got expected=$c"))
            }.unit
        }

        "TChunk.init - varargs round-trip: TChunk.init(values*).snapshot equals Chunk.from(values)" in {
            val cases = Seq(
                Seq.empty[Int],
                Seq(42),
                Seq(1, 2, 3),
                (1 to 20).toSeq
            )
            Kyo.foreachDiscard(cases.toSeq) { vs =>
                STM.run(TChunk.init(vs*).map(_.snapshot)).map(got => assert(got == Chunk.from(vs)))
            }.unit
        }

        "TChunk.size - property: TChunk.init(chunk).size equals chunk.size" in {
            val cases = Seq(chunkSets*)
            Kyo.foreachDiscard(cases.toSeq) { c =>
                STM.run(TChunk.init(c).map(_.size)).map(n => assert(n == c.size, s"chunk=$c"))
            }.unit
        }

        "TChunk.isEmpty - property: TChunk.init(chunk).isEmpty agrees with chunk.isEmpty" in {
            val cases = Seq(chunkSets*)
            Kyo.foreachDiscard(cases.toSeq) { c =>
                STM.run(TChunk.init(c).map(_.isEmpty)).map(got => assert(got == c.isEmpty, s"chunk=$c"))
            }.unit
        }

        "TChunk.get - indexing property: TChunk.init(chunk).get(i) equals chunk(i) for every valid i" in {
            val cases = Seq(nonEmptyChunkSets*)
            Kyo.foreachDiscard(cases.toSeq) { c =>
                Kyo.foreachDiscard(0 until c.size) { i =>
                    STM.run(TChunk.init(c).map(_.get(i))).map(got => assert(got == c(i), s"chunk=$c i=$i"))
                }
            }.unit
        }

        "TChunk.head - property: TChunk.init(chunk).head equals chunk.head" in {
            val cases = Seq(nonEmptyChunkSets*)
            Kyo.foreachDiscard(cases.toSeq) { c =>
                STM.run(TChunk.init(c).map(_.head)).map(got => assert(got == c.head, s"chunk=$c"))
            }.unit
        }

        "TChunk.last - property: TChunk.init(chunk).last equals chunk.last" in {
            val cases = Seq(nonEmptyChunkSets*)
            Kyo.foreachDiscard(cases.toSeq) { c =>
                STM.run(TChunk.init(c).map(_.last)).map(got => assert(got == c.last, s"chunk=$c"))
            }.unit
        }

        "TChunk.append - snapshot property: after append(x), snapshot equals chunk :+ x and last equals x" in {
            val cases = Seq(
                (Chunk.empty[Int], 1),
                (Chunk(42), 7),
                (Chunk(1, 2, 3), -100),
                (Chunk.from(1 to 30), 1000)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (c, x) =>
                for
                    after <- STM.run(TChunk.init(c).map(tc => tc.append(x).andThen(tc.snapshot)))
                    last  <- STM.run(TChunk.init(c).map(tc => tc.append(x).andThen(tc.last)))
                    n     <- STM.run(TChunk.init(c).map(tc => tc.append(x).andThen(tc.size)))
                yield
                    assert(after == (c :+ x), s"got=$after expected=${c :+ x}")
                    assert(last == x)
                    assert(n == c.size + 1)
            }.unit
        }

        // n values include negative, in-range, boundary, and past-end.
        val nValues = Seq(-5, -1, 0, 1, 2, 3, 15, 30, 35)

        "TChunk.take - snapshot property: after take(n), snapshot equals chunk.take(n)" in {
            val chunks = Seq(chunkSets*)
            Kyo.foreachDiscard(chunks.toSeq) { c =>
                Kyo.foreachDiscard(nValues.toSeq) { n =>
                    STM.run(TChunk.init(c).map(tc => tc.take(n).andThen(tc.snapshot))).map { after =>
                        assert(after == c.take(n), s"got=$after expected=${c.take(n)} n=$n c=$c")
                    }
                }
            }.unit
        }

        "TChunk.drop - snapshot property: after drop(n), snapshot equals chunk.drop(n)" in {
            val chunks = Seq(chunkSets*)
            Kyo.foreachDiscard(chunks.toSeq) { c =>
                Kyo.foreachDiscard(nValues.toSeq) { n =>
                    STM.run(TChunk.init(c).map(tc => tc.drop(n).andThen(tc.snapshot))).map { after =>
                        assert(after == c.drop(n), s"got=$after expected=${c.drop(n)} n=$n c=$c")
                    }
                }
            }.unit
        }

        "TChunk.dropRight - snapshot property: after dropRight(n), snapshot equals chunk.dropRight(n)" in {
            val chunks = Seq(chunkSets*)
            Kyo.foreachDiscard(chunks.toSeq) { c =>
                Kyo.foreachDiscard(nValues.toSeq) { n =>
                    STM.run(TChunk.init(c).map(tc => tc.dropRight(n).andThen(tc.snapshot))).map { after =>
                        assert(after == c.dropRight(n), s"got=$after expected=${c.dropRight(n)} n=$n c=$c")
                    }
                }
            }.unit
        }

        "TChunk.slice - snapshot property: after slice(from, until), snapshot equals chunk.slice(from, until) for any from, until" in {
            val chunks  = Seq(chunkSets*)
            val indices = Seq(-5, -1, 0, 1, 2, 5, 15, 30, 35)
            Kyo.foreachDiscard(chunks.toSeq) { c =>
                Kyo.foreachDiscard(indices) { from =>
                    Kyo.foreachDiscard(indices) { until =>
                        STM.run(TChunk.init(c).map(tc => tc.slice(from, until).andThen(tc.snapshot))).map { after =>
                            assert(
                                after == c.slice(from, until),
                                s"got=$after expected=${c.slice(from, until)} from=$from until=$until c=$c"
                            )
                        }
                    }
                }
            }.unit
        }

        "TChunk.concat - snapshot property: after concat(other), snapshot equals chunk ++ other" in {
            val others = Seq(Chunk.empty[Int], Chunk(99), Chunk(7, 8, 9), Chunk.from(100 to 120))
            val chunks = Seq(chunkSets*)
            Kyo.foreachDiscard(chunks.toSeq) { c =>
                Kyo.foreachDiscard(others) { o =>
                    STM.run(TChunk.init(c).map(tc => tc.concat(o).andThen(tc.snapshot))).map { after =>
                        assert(after == (c ++ o), s"got=$after expected=${c ++ o}")
                    }
                }
            }.unit
        }

        "TChunk.filter - snapshot property: after filter(p), snapshot equals chunk.filter(p)" in {
            val preds = Seq(
                ("all", (_: Int) => true),
                ("none", (_: Int) => false),
                ("even", (x: Int) => x % 2 == 0),
                ("positive", (x: Int) => x > 0),
                ("smallAbs", (x: Int) => x.abs < 50)
            )
            val chunks = Seq(chunkSets*)
            Kyo.foreachDiscard(chunks.toSeq) { c =>
                Kyo.foreachDiscard(preds.toSeq) { case (name, p) =>
                    STM.run(TChunk.init(c).map(tc => tc.filter(x => p(x)).andThen(tc.snapshot))).map { after =>
                        assert(after == c.filter(p), s"pred=$name got=$after expected=${c.filter(p)}")
                        assert(after.forall(p), s"pred=$name post-filter chunk has violating element: $after")
                    }
                }
            }.unit
        }

        "TChunk.compact - content-preserving property: after compact, snapshot equals pre-compact snapshot" in {
            val cases = Seq(chunkSets*)
            Kyo.foreachDiscard(cases.toSeq) { c =>
                STM.run {
                    TChunk.init(c).map(tc => tc.snapshot.map(s0 => tc.compact.andThen(tc.snapshot.map(s1 => (s0, s1)))))
                }.map { case (s0, s1) =>
                    assert(s0 == s1)
                    assert(s1 == c)
                }
            }.unit
        }

        "TChunk.snapshot - idempotency property: two snapshots without mutation yield equal values" in {
            val cases = Seq(chunkSets*)
            Kyo.foreachDiscard(cases.toSeq) { c =>
                STM.run {
                    TChunk.init(c).map(tc => tc.snapshot.map(a => tc.snapshot.map(b => (a, b))))
                }.map { case (a, b) =>
                    assert(a == b)
                    assert(a == c)
                }
            }.unit
        }
    }

    "TTable laws" - {

        "TTable - init empty property: a freshly initialised table is empty" in {
            val ns = Seq(1, 2, 5, 10)
            Kyo.foreachDiscard(ns.toSeq) { n =>
                Kyo.foreachDiscard(1 to n) { _ =>
                    for
                        table <- TTable.init["name" ~ String & "age" ~ Int]
                        sz    <- STM.run(table.size)
                        em    <- STM.run(table.isEmpty)
                    yield assert(sz == 0 && em, s"new table not empty: size=$sz isEmpty=$em")
                }
            }.unit
        }

        "TTable.insert - round-trip property: insert(r) returns id, get(id) yields Maybe(r), size increments by 1" in {
            val cases = Seq(
                Seq(0),
                Seq(1, 2, 3),
                Seq(-100, 0, 100),
                (1 to 10).toSeq
            )
            Kyo.foreachDiscard(cases.toSeq) { values =>
                val records = values.map(v => "name" ~ s"r$v" & "age" ~ v)
                for
                    table <- TTable.init["name" ~ String & "age" ~ Int]
                    ids   <- STM.run(Kyo.foreach(records)(r => table.insert(r)))
                    rt    <- STM.run(Kyo.foreach(ids.zip(records)) { case (id, r) => table.get(id).map(_ == Maybe(r)) })
                    n     <- STM.run(table.size)
                yield
                    assert(ids.size == records.size)
                    assert(ids.toSet.size == ids.size, s"duplicate ids: $ids")
                    assert(rt.forall(identity), s"round-trip mismatch: $rt")
                    assert(n == records.size)
                end for
            }.unit
        }

        "TTable.update - round-trip property: insert(r1) then update(id, r2); get(id) yields Maybe(r2) and update returns Maybe(r1)" in {
            val cases = Seq(
                (0, 1),
                (10, 10),
                (-5, 100),
                (42, -42)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (v1, v2) =>
                val r1 = "name" ~ s"a$v1" & "age" ~ v1
                val r2 = "name" ~ s"b$v2" & "age" ~ v2
                for
                    table <- TTable.init["name" ~ String & "age" ~ Int]
                    res <- STM.run {
                        for
                            id   <- table.insert(r1)
                            prev <- table.update(id, r2)
                            cur  <- table.get(id)
                        yield (prev, cur)
                    }
                yield
                    val (prev, cur) = res
                    assert(prev == Maybe(r1), s"update returned $prev, expected ${Maybe(r1)}")
                    assert(cur == Maybe(r2), s"after update, get returned $cur, expected ${Maybe(r2)}")
                end for
            }.unit
        }

        "TTable.update - non-existent property: update on a nonexistent id returns Absent and does not insert" in {
            val cases = Seq(
                (0, 1),
                (1, -5),
                (500, 42),
                (1000, 0)
            )
            Kyo.foreachDiscard(cases.toSeq) { case (fakeId, value) =>
                val record = "name" ~ s"x$value" & "age" ~ value
                for
                    table <- TTable.init["name" ~ String & "age" ~ Int]
                    res <- STM.run {
                        for
                            rr <- table.update(table.unsafeId(fakeId), record)
                            gg <- table.get(table.unsafeId(fakeId))
                            sz <- table.size
                        yield (rr, gg, sz)
                    }
                yield
                    val (rr, gg, sz) = res
                    assert(rr == Absent)
                    assert(gg == Absent)
                    assert(sz == 0, s"update on non-existent id inserted: size=$sz")
                end for
            }.unit
        }

        "TTable.Indexed.upsert - index-consistency property: after upsert(id, r2) following upsert(id, r1), queryIds on r1's old field value does NOT contain id" in {
            val cases = Seq(
                ("alpha", "beta", 1),
                ("x", "y", -5),
                ("first", "second", 100),
                ("aaa", "bbb", 0)
            )
            Kyo.foreach(cases.toSeq) { case (n1, n2, v) =>
                for
                    table <- TTable.Indexed.init["name" ~ String & "value" ~ Int, "name" ~ String & "value" ~ Int]
                    res <- STM.run {
                        val id0 = table.unsafeId(0)
                        for
                            _  <- table.upsert(id0, "name" ~ n1 & "value" ~ v)
                            _  <- table.upsert(id0, "name" ~ n2 & "value" ~ v)
                            qo <- table.queryIds("name" ~ n1)
                            qn <- table.queryIds("name" ~ n2)
                        yield (qo, qn, id0)
                        end for
                    }
                yield
                    val (queryOld, queryNew, id0) = res
                    // law: stale index entry must be gone, fresh entry must be present
                    assert(!queryOld.contains(id0) && queryNew.contains(id0), s"n1=$n1 n2=$n2 value=$v")
            }.unit
        }
    }

end STMPropertyTest
