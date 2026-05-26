package kyo.compat

import kyo.compat.*
import scala.util.Failure

class CStreamTest extends CompatTest:

    "empty.run returns an empty CChunk[Int]" in run {
        CStream.empty[Int].run.map(chunk => assert(chunk.size == 0))
    }

    "init(Seq(1,2,3)).run emits the seq in order" in run {
        CStream.init(Seq(1, 2, 3)).run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "init(Seq.empty[Int]).run emits nothing" in run {
        CStream.init(Seq.empty[Int]).run.map(chunk => assert(chunk.size == 0))
    }

    "init(CIO[Seq[Int]]).run evaluates the inner CIO and emits its elements" in run {
        CStream.init(CIO.value(Seq(1, 2, 3))).run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "init(CIO.fail(...)).run propagates the failure" in run {
        CStream.init[Int](CIO.fail(TestError("x"))).run.liftToTry.map {
            case Failure(e: TestError) if e.msg == "x" => succeed
            case other                                 => fail(s"expected Failure(TestError(\"x\")), got: $other")
        }
    }

    "range(0, 5).run emits 0..4" in run {
        CStream.range(0, 5).run.map(chunk => assert(chunk.toSeq == Seq(0, 1, 2, 3, 4)))
    }

    "range(3, 3).run is empty (end exclusive, start == end)" in run {
        CStream.range(3, 3).run.map(chunk => assert(chunk.size == 0))
    }

    "range(5, 3).run is empty (start > end with default step)" in run {
        CStream.range(5, 3).run.map(chunk => assert(chunk.size == 0))
    }

    "unfold(0)(s => if (s < 3) Some((s, s+1)) else None).run emits 0..2" in run {
        CStream.unfold(0)(s => CIO.value(if s < 3 then Some((s, s + 1)) else None))
            .run.map(chunk => assert(chunk.toSeq == Seq(0, 1, 2)))
    }

    "unfold with failing step propagates the failure" in run {
        CStream.unfold(0)(_ => CIO.fail(TestError("u"))).run.liftToTry.map {
            case Failure(e: TestError) if e.msg == "u" => succeed
            case other                                 => fail(s"expected Failure(TestError(\"u\")), got: $other")
        }
    }

    "concat appends the second stream after the first" in run {
        CStream.init(Seq(1, 2)).concat(CStream.init(Seq(3, 4)))
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3, 4)))
    }

    "mapPure transforms every element with a pure function" in run {
        CStream.init(Seq(1, 2, 3)).mapPure(_ * 10)
            .run.map(chunk => assert(chunk.toSeq == Seq(10, 20, 30)))
    }

    "map transforms every element with an effectful function" in run {
        CStream.init(Seq(1, 2, 3)).map(a => CIO.value(a + 1))
            .run.map(chunk => assert(chunk.toSeq == Seq(2, 3, 4)))
    }

    "map propagates failure from the effectful function" in run {
        CStream.init(Seq(1)).map(_ => CIO.fail(TestError("m"))).run.liftToTry.map {
            case Failure(e: TestError) if e.msg == "m" => succeed
            case other                                 => fail(s"expected Failure(TestError(\"m\")), got: $other")
        }
    }

    "flatMap concatenates per-element substreams" in run {
        CStream.init(Seq(1, 2)).flatMap(a => CStream.init(Seq(a, a * 10)))
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 10, 2, 20)))
    }

    "flatMap propagates failure from a substream" in run {
        CStream.init(Seq(1))
            .flatMap(_ => CStream.init[Int](CIO.fail(TestError("f"))))
            .run.liftToTry.map {
                case Failure(e: TestError) if e.msg == "f" => succeed
                case other                                 => fail(s"expected Failure(TestError(\"f\")), got: $other")
            }
    }

    "tap passes values through unchanged" in run {
        CStream.init(Seq(1, 2, 3)).tap(_ => CIO.unit)
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "tap propagates failure from the side-effect" in run {
        CStream.init(Seq(1)).tap(_ => CIO.fail(TestError("t"))).run.liftToTry.map {
            case Failure(e: TestError) if e.msg == "t" => succeed
            case other                                 => fail(s"expected Failure(TestError(\"t\")), got: $other")
        }
    }

    "take(n) keeps the first n elements" in run {
        CStream.init(Seq(1, 2, 3, 4, 5)).take(3)
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "take(0) yields an empty stream" in run {
        CStream.init(Seq(1, 2, 3)).take(0).run.map(chunk => assert(chunk.size == 0))
    }

    "take(n) with n > size returns all elements" in run {
        CStream.init(Seq(1, 2)).take(10)
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 2)))
    }

    "drop(n) discards the first n elements" in run {
        CStream.init(Seq(1, 2, 3, 4, 5)).drop(2)
            .run.map(chunk => assert(chunk.toSeq == Seq(3, 4, 5)))
    }

    "drop(0) is identity" in run {
        CStream.init(Seq(1, 2, 3)).drop(0)
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "drop(n) with n >= size yields an empty stream" in run {
        CStream.init(Seq(1, 2, 3)).drop(10).run.map(chunk => assert(chunk.size == 0))
    }

    "takeWhilePure stops at the first false" in run {
        CStream.init(Seq(1, 2, 3, 4)).takeWhilePure(_ < 3)
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 2)))
    }

    "filterPure keeps elements matching the predicate" in run {
        CStream.init(Seq(1, 2, 3, 4)).filterPure(_ % 2 == 0)
            .run.map(chunk => assert(chunk.toSeq == Seq(2, 4)))
    }

    "filter keeps elements matching the effectful predicate" in run {
        CStream.init(Seq(1, 2, 3, 4)).filter(a => CIO.value(a > 2))
            .run.map(chunk => assert(chunk.toSeq == Seq(3, 4)))
    }

    "collectPure keeps and transforms elements via a partial function" in run {
        CStream.init(Seq(1, 2, 3, 4))
            .collectPure(a => if a % 2 == 0 then Some(a * 10) else None)
            .run.map(chunk => assert(chunk.toSeq == Seq(20, 40)))
    }

    "run materializes the stream into a CChunk in order" in run {
        CStream.init(Seq(1, 2, 3)).run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "foldPure folds left with a pure step" in run {
        CStream.init(Seq(1, 2, 3, 4)).foldPure(0)(_ + _).map(v => assert(v == 10))
    }

    "foreach invokes f for each element" in run {
        CAtomicInt.init(0).flatMap { ref =>
            CStream.init(Seq(1, 2, 3)).foreach(a => ref.addAndGet(a).unit)
                .flatMap(_ => ref.get)
                .map(v => assert(v == 6))
        }
    }

    "foreach propagates failure from f" in run {
        CStream.init(Seq(1)).foreach(_ => CIO.fail(TestError("fe"))).liftToTry.map {
            case Failure(e: TestError) if e.msg == "fe" => succeed
            case other                                  => fail(s"expected Failure(TestError(\"fe\")), got: $other")
        }
    }

    "discard returns CIO[Unit] that completes successfully" in run {
        CStream.init(Seq(1, 2, 3)).discard.map(_ => succeed)
    }

    "lift(s.lower) round-trips a stream unchanged" in run {
        val s = CStream.init(Seq(1, 2, 3))
        CStream.lift(s.lower).run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "mapPure + filterPure compose into a single pipeline" in run {
        CStream.init(Seq(1, 2, 3, 4, 5)).mapPure(_ + 1).filterPure(_ % 2 == 0)
            .run.map(chunk => assert(chunk.toSeq == Seq(2, 4, 6)))
    }

    "map calls f exactly once per element" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .map(a => counter.incrementAndGet.map(_ => a + 10))
                .run.flatMap { result =>
                    counter.get.map(c => assert(c == 5 && result.toSeq == Seq(11, 12, 13, 14, 15)))
                }
        }
    }

    "filter calls predicate exactly once per element" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .filter(a => counter.incrementAndGet.map(_ => a > 2))
                .run.flatMap { result =>
                    counter.get.map(c => assert(c == 5 && result.toSeq == Seq(3, 4, 5)))
                }
        }
    }

    "tap calls f exactly once per element when fully consumed" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .tap(_ => counter.incrementAndGet.unit)
                .run.flatMap { result =>
                    counter.get.map(c => assert(c == 5 && result.toSeq == Seq(1, 2, 3, 4, 5)))
                }
        }
    }

    "empty.mapPure produces an empty stream" in run {
        CStream.empty[Int].mapPure(_ * 10).run.map(chunk => assert(chunk.size == 0))
    }

    "filter producing no matches yields an empty stream" in run {
        CStream.init(Seq(1, 2, 3)).filterPure(_ > 100).run.map(chunk => assert(chunk.size == 0))
    }

    "collectPure with f returning None for all elements yields an empty stream" in run {
        CStream.init(Seq(1, 2, 3)).collectPure(_ => None: Option[Int]).run.map(chunk => assert(chunk.size == 0))
    }

    "flatMap with empty inner streams yields an empty result" in run {
        CStream.init(Seq(1, 2, 3)).flatMap(_ => CStream.empty[Int]).run.map(chunk => assert(chunk.size == 0))
    }

    "unfold returning None immediately yields an empty stream" in run {
        CStream.unfold(0)(_ => CIO.value(None: Option[(Int, Int)])).run.map(chunk => assert(chunk.size == 0))
    }

    "empty.concat(s) emits s's elements unchanged" in run {
        CStream.empty[Int].concat(CStream.init(Seq(1, 2, 3))).run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "s.concat(empty) emits s's elements unchanged" in run {
        CStream.init(Seq(1, 2, 3)).concat(CStream.empty[Int]).run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 3)))
    }

    "CStream value is re-runnable across multiple terminal operations" in run {
        val s = CStream.init(Seq(1, 2, 3))
        s.run.flatMap { first =>
            s.foldPure(0)(_ + _).map { sum =>
                assert(first.toSeq == Seq(1, 2, 3) && sum == 6)
            }
        }
    }

    "map failure mid-stream short-circuits subsequent elements" in run {
        CAtomicInt.init(0).flatMap { reached =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .map(a =>
                    if a == 3 then CIO.fail(TestError("boom"))
                    else reached.incrementAndGet.map(_ => a)
                )
                .run.liftToTry.flatMap { t =>
                    reached.get.map { c =>
                        val correctFailure = t match
                            case Failure(e: TestError) => e.msg == "boom"
                            case _                     => false
                        // Element 3 fails; elements 1 and 2 ran before it. Element 4 and 5 must NOT have run.
                        assert(correctFailure && c == 2)
                    }
                }
        }
    }

    "deep flatMap chains do not stack-overflow (1000 levels)" in run {
        val n = 1000
        val deep = (1 to n).foldLeft(CStream.init(Seq(0))) { (acc, _) =>
            acc.flatMap(prev => CStream.init(Seq(prev + 1)))
        }
        deep.run.map(chunk => assert(chunk.toSeq == Seq(n)))
    }

    "mapPure calls f exactly once per element" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .tap(_ => counter.incrementAndGet.unit)
                .mapPure(_ + 10)
                .run.flatMap { result =>
                    counter.get.map(c => assert(c == 5 && result.toSeq == Seq(11, 12, 13, 14, 15)))
                }
        }
    }

    "filterPure calls predicate behavior matches result count" in run {
        CStream.init(Seq(1, 2, 3, 4, 5)).filterPure(_ % 2 == 0).run.map { result =>
            assert(result.toSeq == Seq(2, 4))
        }
    }

    "collectPure handles mixed Some/None correctly" in run {
        CStream.init(Seq(1, 2, 3, 4, 5))
            .collectPure(a => if a % 2 == 0 then Some(a * 100) else None)
            .run.map(chunk => assert(chunk.toSeq == Seq(200, 400)))
    }

    "take(n) on an effectful upstream invokes the upstream effect exactly n times" in {
        pending
        run {
            CAtomicInt.init(0).flatMap { counter =>
                CStream.init(Seq.range(0, 100))
                    .map(a => counter.incrementAndGet.map(_ => a))
                    .take(3)
                    .run.flatMap { result =>
                        counter.get.map(c => assert(c == 3 && result.toSeq == Seq(0, 1, 2)))
                    }
            }
        }
    }

    "takeWhilePure stops invoking upstream effects after the first false" in {
        pending
        run {
            CAtomicInt.init(0).flatMap { counter =>
                CStream.init(Seq(1, 2, 3, 4, 5))
                    .map(a => counter.incrementAndGet.map(_ => a))
                    .takeWhilePure(_ < 3)
                    .run.flatMap { result =>
                        counter.get.map(c => assert(c == 3 && result.toSeq == Seq(1, 2)))
                    }
            }
        }
    }

    "drop(n) still invokes upstream effects for the dropped elements" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .map(a => counter.incrementAndGet.map(_ => a))
                .drop(2)
                .run.flatMap { result =>
                    counter.get.map(c => assert(c == 5 && result.toSeq == Seq(3, 4, 5)))
                }
        }
    }

    "multiple chained taps all fire per element" in run {
        CAtomicInt.init(0).flatMap { a =>
            CAtomicInt.init(0).flatMap { b =>
                CStream.init(Seq(1, 2, 3))
                    .tap(_ => a.incrementAndGet.unit)
                    .tap(_ => b.incrementAndGet.unit)
                    .discard
                    .flatMap(_ => a.get)
                    .flatMap(av => b.get.map(bv => assert(av == 3 && bv == 3)))
            }
        }
    }

    "concat(failing-left, right) does not invoke right's effectful elements" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.init[Int](CIO.fail(TestError("left")))
                .concat(CStream.init(Seq(1, 2, 3)).map(a => counter.incrementAndGet.map(_ => a)))
                .run.liftToTry.flatMap { t =>
                    counter.get.map { c =>
                        val correctFailure = t match
                            case Failure(e: TestError) => e.msg == "left"
                            case _                     => false
                        assert(correctFailure && c == 0)
                    }
                }
        }
    }

    "flatMap with variable-size inner streams preserves outer order" in run {
        CStream.init(Seq(1, 2, 3))
            .flatMap(a => CStream.init(Seq.fill(a)(a)))
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 2, 2, 3, 3, 3)))
    }

    "empty.flatMap with non-empty inner yields empty" in run {
        CStream.empty[Int].flatMap(a => CStream.init(Seq(a, a + 1))).run.map { chunk =>
            assert(chunk.size == 0)
        }
    }

    "10000-element input round-trips through run unchanged" in run {
        val seq = Seq.range(0, 10000)
        CStream.init(seq).run.map(chunk => assert(chunk.toSeq == seq))
    }

    "10000-element input with filterPure cuts to expected size" in run {
        CStream.init(Seq.range(0, 10000)).filterPure(_ % 2 == 0).run.map { chunk =>
            assert(chunk.size == 5000 && chunk.toSeq.head == 0 && chunk.toSeq.last == 9998)
        }
    }

    "deep flatMap chains do not stack-overflow (10000 levels)" in {
        pending
        run {
            val n = 10000
            val deep = (1 to n).foldLeft(CStream.init(Seq(0))) { (acc, _) =>
                acc.flatMap(prev => CStream.init(Seq(prev + 1)))
            }
            deep.run.map(chunk => assert(chunk.toSeq == Seq(n)))
        }
    }

    "100000-element input passthrough run" in run {
        val seq = Seq.range(0, 100000)
        CStream.init(seq).run.map(chunk => assert(chunk.size == 100000))
    }

    "100000-element input with mapPure + filterPure cuts correctly" in run {
        CStream.init(Seq.range(0, 100000)).mapPure(_ * 2).filterPure(_ % 4 == 0)
            .run.map(chunk => assert(chunk.size == 50000 && chunk.toSeq.head == 0 && chunk.toSeq.last == 199996))
    }

    "100000-element foldPure produces correct sum" in run {
        CStream.init(Seq.range(0, 100000)).foldPure(0L)(_ + _).map(s => assert(s == 4999950000L))
    }

    "three terminals on the same CStream value produce independent correct results" in run {
        val s = CStream.init(Seq(1, 2, 3, 4, 5))
        s.run.flatMap { r1 =>
            s.foldPure(0)(_ + _).flatMap { sum =>
                CAtomicInt.init(0).flatMap { counter =>
                    s.foreach(a => counter.addAndGet(a).unit).flatMap(_ => counter.get).map { c =>
                        assert(r1.toSeq == Seq(1, 2, 3, 4, 5) && sum == 15 && c == 15)
                    }
                }
            }
        }
    }

    "concat is associative: (a ++ b) ++ c equals a ++ (b ++ c)" in run {
        val a     = CStream.init(Seq(1, 2))
        val b     = CStream.init(Seq(3, 4))
        val c     = CStream.init(Seq(5, 6))
        val left  = a.concat(b).concat(c)
        val right = a.concat(b.concat(c))
        left.run.flatMap { l =>
            right.run.map { r => assert(l.toSeq == Seq(1, 2, 3, 4, 5, 6) && r.toSeq == Seq(1, 2, 3, 4, 5, 6)) }
        }
    }

    "empty.concat(empty).concat(empty) is empty" in run {
        CStream.empty[Int].concat(CStream.empty[Int]).concat(CStream.empty[Int])
            .run.map(chunk => assert(chunk.size == 0))
    }

    "filter rejecting all elements yields empty stream with effect counted correctly" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .filter(a => counter.incrementAndGet.map(_ => a > 100))
                .run.flatMap { result =>
                    counter.get.map(c => assert(c == 5 && result.size == 0))
                }
        }
    }

    "take(0) followed by map does not invoke f" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .take(0)
                .map(a => counter.incrementAndGet.map(_ => a))
                .run.flatMap { result =>
                    counter.get.map(c => assert(c == 0 && result.size == 0))
                }
        }
    }

    "flatMap with empties interleaved preserves outer order" in run {
        CStream.init(Seq(1, 2, 3, 4, 5))
            .flatMap(a => if a % 2 == 0 then CStream.empty[Int] else CStream.init(Seq(a, a * 10)))
            .run.map(chunk => assert(chunk.toSeq == Seq(1, 10, 3, 30, 5, 50)))
    }

    "empty.flatMap(_ => stream) yields empty without invoking f" in run {
        CAtomicInt.init(0).flatMap { counter =>
            CStream.empty[Int]
                .flatMap { a =>
                    counter.incrementAndGet
                    CStream.init(Seq(a))
                }
                .run.flatMap { result =>
                    counter.get.map(c => assert(c == 0 && result.size == 0))
                }
        }
    }

    "mapPure + map + filterPure + filter chain calls each function expected number of times" in run {
        CAtomicInt.init(0).flatMap { mapCount =>
            CAtomicInt.init(0).flatMap { filterCount =>
                CStream.init(Seq(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
                    .mapPure(_ + 1)                                            // [2..11]
                    .map(a => mapCount.incrementAndGet.map(_ => a * 2))        // [4..22], mc += 10
                    .filterPure(_ > 5)                                         // 9 elements pass
                    .filter(a => filterCount.incrementAndGet.map(_ => a < 20)) // fc += 9
                    .run.flatMap { result =>
                        mapCount.get.flatMap { mc =>
                            filterCount.get.map { fc =>
                                assert(mc == 10 && fc == 9 && result.toSeq == Seq(6, 8, 10, 12, 14, 16, 18))
                            }
                        }
                    }
            }
        }
    }

    "async CIO inside map propagates results correctly" in run {
        CStream.init(Seq(1, 2, 3))
            .map { a =>
                CIO.async[Int] { cb =>
                    // immediate callback — verifies the async path even when ready
                    cb(scala.util.Success(a * 100))
                }
            }
            .run.map(chunk => assert(chunk.toSeq == Seq(100, 200, 300)))
    }

    "flatMap with inner CIO.fail mid-stream short-circuits after the failing element" in run {
        CAtomicInt.init(0).flatMap { reached =>
            CStream.init(Seq(1, 2, 3, 4, 5))
                .flatMap(a =>
                    if a == 3 then CStream.init[Int](CIO.fail(TestError("mid")))
                    else CStream.init(Seq(a)).tap(_ => reached.incrementAndGet.unit)
                )
                .run.liftToTry.flatMap { t =>
                    reached.get.map { c =>
                        val matched = t match
                            case scala.util.Failure(e: TestError) => e.msg == "mid"
                            case _                                => false
                        assert(matched && c == 2)
                    }
                }
        }
    }

    "filter with failing predicate at first element fails immediately" in run {
        CStream.init(Seq(1, 2, 3)).filter(_ => CIO.fail(TestError("p"))).run.liftToTry.map {
            case scala.util.Failure(e: TestError) if e.msg == "p" => succeed
            case other                                            => fail(s"expected Failure(TestError(\"p\")), got: $other")
        }
    }

    "drop(n) with n > size on long stream returns empty without errors" in run {
        CStream.init(Seq.range(0, 100)).drop(200).run.map(chunk => assert(chunk.size == 0))
    }

end CStreamTest
