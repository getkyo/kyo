package kyo.kernel

import kyo.Arrow
import kyo.Closed
import kyo.Const
import kyo.Loop
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Tag
import kyo.discard
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Pending
import kyo.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

class BracketTest extends AnyFreeSpec:

    private def requestStop(): Unit =
        discard(Safepoint.get())
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop

    private object Boom extends RuntimeException("boom", null, false, false)

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    "bracket" - {
        "releases with Absent on completion, after use" in {
            val log = ListBuffer[String]()
            val v = Bracket(Effect.defer { log += "open"; 42 }) { a =>
                Effect.defer { log += "use"; a + 1 }
            } { (a, outcome) =>
                discard(log += s"close $a ${outcome.isEmpty}")
            }
            assert(v.eval == 43)
            assert(log.toList == List("open", "use", "close 42 true"))
        }

        "a pure use completes the bracket through the settled fast path" in {
            var count = 0
            val v     = Bracket(Effect.defer(1))(a => a + 1)((_, _) => count += 1)
            assert(v.eval == 2)
            assert(count == 1)
        }

        "a use that throws during application still releases" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Bracket(Effect.defer(7)) { _ =>
                (throw Boom): Int < Any
            }((_, outcome) => seen = Maybe(outcome))
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "releases exactly once" in {
            var count = 0
            val v     = Bracket(Effect.defer(1))(a => Effect.defer(a))((_, _) => count += 1)
            assert(v.eval == 1)
            assert(count == 1)
        }

        "releases with the failure when the use throws" in {
            var seen = Maybe.empty[Throwable]
            val v = Bracket(Effect.defer(7)) { _ =>
                Effect.defer((throw Boom): Int)
            }((_, outcome) => seen = outcome)
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(seen.exists(_ eq Boom))
        }

        "releases when a parked remainder is abandoned" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Bracket(Effect.defer(7)) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }((_, outcome) => seen = Maybe(outcome))
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(seen.isEmpty)
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        // A polling `map` between the acquire's settled value and the outer bracket parks the interrupt in the gap, leaving the bracket's
        // `Ensure` un-applied in the inner region's continuation. Abandonment applies it with the settled resource (42), not a dummy.
        "abandonment releases a bracket stranded by a polling map after the acquire settled" in {
            var released     = 0
            var ended        = 0
            var seenResource = Maybe.empty[Any]
            val v =
                Bracket(
                    Bracket.ensuring(_ => discard(ended += 1)) {
                        Effect.defer { requestStop(); 42 }.map(x => x)
                    }
                )(a => Effect.defer(a)) { (a, _) =>
                    seenResource = Maybe(a)
                    released += 1
                }
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(released == 1 && ended == 1, s"released=$released ended=$ended (0 released = the leak)")
            assert(seenResource.exists(_.toString == "42"), s"release ran with the wrong resource: $seenResource")
        }

        // Soundness boundary: a value-changing `map` sits between the stranded `Ensure` and the value the remainder parked with, so the
        // `Ensure` would receive the transformed value ("wrapped-42"), which the walk cannot reconstruct without running the transform.
        // It declines (the bracket leaks) rather than release with the pre-transform value: never a wrong resource. The leak itself is
        // the crossing family's gap, closed by installing the region as the value arrives.
        "abandonment declines a stranded release rather than hand it a pre-transform resource" in {
            var seen = Maybe.empty[Any]
            val v =
                Bracket(
                    Bracket.ensuring(_ => ()) {
                        Effect.defer { requestStop(); 42 }.map(x => x)
                    }.map(r => s"wrapped-$r")
                )(a => Effect.defer(a))((a, _) => seen = Maybe(a))
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(!seen.exists(_.toString == "42"), s"release got the pre-transform resource: $seen")
        }

        "a resumed parked bracket completes and releases with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Bracket(Effect.defer(7)) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }((_, outcome) => seen = Maybe(outcome))
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(parked.eval == 8)
            assert(seen.exists(_.isEmpty))
        }

        "nested brackets release innermost first on failure" in {
            val log = ListBuffer[String]()
            val v = Bracket(Effect.defer(1)) { _ =>
                Bracket(Effect.defer(2)) { _ =>
                    Effect.defer((throw Boom): Int)
                }((_, _) => discard(log += "inner"))
            }((_, _) => discard(log += "outer"))
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(log.toList == List("inner", "outer"))
        }

        "a stop landing as the acquire settles still installs the region" in {
            var count = 0
            val v = Bracket(Effect.defer {
                requestStop()
                7
            })(a => Effect.defer(a + 1))((_, _) => count += 1)
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(count == 1)
            // re-evaluating an abandoned park re-enters a released region, which is refused
            discard(intercept[kyo.Closed](parked.eval))
            assert(count == 1)
        }

        "a foreign loop handler answering in place keeps the bracket live until the use completes" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val log  = ListBuffer[String]()
            val body: Int < (Ask & Str) =
                Bracket(Effect.defer(7)) { a =>
                    str(1).map { s =>
                        discard(log += s"use $s ${seen.isEmpty}")
                        ask.map(x => a + x)
                    }
                }((_, outcome) => seen = Maybe(outcome))
            val inner: Int < Str = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue(1), b => b)
            val r: Int < Any =
                ArrowEffect.handleLoop(Tag[Str], inner)([C] => n => Loop.continue(s"s$n"), b => b)
            assert(r.eval == 8)
            assert(log.toList == List("use s1 true"))
            assert(seen.exists(_.isEmpty))
        }

        "a loop clause answering done releases a bracket opened inside" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map(x => a + x)
                }((_, outcome) => seen = Maybe(outcome))
            val r: Int < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(-1), b => b)
            assert(r.eval == -1)
            // the clause answers done without resuming, so the bracket's use never ran to an end: discard signal
            assert(seen.exists(_.exists(_.isInstanceOf[kyo.KyoException])))
        }

        "the acquire is not guarded before it settles" in {
            var count = 0
            val v = Bracket(Effect.defer {
                requestStop()
                Effect.defer(7)
            })(a => Effect.defer(a))((_, _) => count += 1)
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending[?, ?]])
            Eval.release(parked, Boom)
            assert(count == 0)
            assert(parked.eval == 7)
            assert(count == 1)
        }

        // A fiber's regions stand above its boundary, so an operation the boundary answers dumps them into
        // the continuation it parks with, and the park itself holds only what stood at or below the
        // answering region. Abandoning that park must still release what the continuation carries.
        "releases a region dumped into the continuation of a park at a region below it" in {
            var seen            = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask = Bracket(Effect.defer(7))(a => ask.map(_ + a))((_, outcome) => seen = Maybe(outcome))
            val handled: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)(
                    [C] =>
                        (_, cont) =>
                            requestStop()
                            ArrowEffect.suspendWith[Any](Tag[Ask], ())(r => cont(r))
                    ,
                    a => a
                )
            val parked = Eval.partial(handled)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(seen.isEmpty)
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }
    }

    "captured continuations" - {
        "a captured continuation resumed in the clause completes the bracket there" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map(x => a + x)
                }((_, outcome) => seen = Maybe(outcome))
            val resumed: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), b => b)
            assert(resumed.eval == 8)
            assert(seen.exists(_.isEmpty))
        }

        "a park after a crossing resume still owes the bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map { x =>
                        requestStop()
                        Effect.defer(a + x)
                    }
                }((_, outcome) => seen = Maybe(outcome))
            val resumed: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), b => b)
            val parked = Eval.partial(resumed)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "an effectful loop clause resuming after the pop completes the bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map(x => a + x)
                }((_, outcome) => seen = Maybe(outcome))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Effect.defer(Loop.continue(1: Int < Ask)),
                b => b
            )
            assert(r.eval == 8)
            assert(seen.exists(_.isEmpty))
        }

        "an effectful loop clause answering done releases through the eval root" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map(x => a + x)
                }((_, outcome) => seen = Maybe(outcome))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Effect.defer(Loop.done(-1)),
                b => b
            )
            assert(r.eval == -1)
            // the clause answers done without resuming, so the bracket's use never ran to an end: discard signal
            assert(seen.exists(_.exists(_.isInstanceOf[kyo.KyoException])))
        }

        "a clause that throws after capturing releases the bracket with the failure" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map(x => a + x)
                }((_, outcome) => seen = Maybe(outcome))
            val r: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => (throw Boom): Int, b => b)
            val ex = intercept[RuntimeException](r.eval)
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "a leaked capture resumed after its region was released is refused as closed" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            var leaked   = Maybe.empty[Arrow[Int, Int, Ask]]
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map(x => a + x)
                }((_, outcome) => discard(outcomes += outcome))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        leaked = Maybe(Region.leak(cont))
                        -1
                ,
                b => b
            )
            assert(r.eval == -1)
            assert(outcomes.size == 1)
            // the leaked continuation is dropped, so the bracket's use never ran to an end: discard signal
            assert(outcomes.head.exists(_.isInstanceOf[kyo.KyoException]))
            // the leaked continuation re-enters a region already released, which is refused
            discard(intercept[kyo.Closed](answerAsk(0)(leaked.get(1)).eval))
            assert(outcomes.size == 1)
        }

        "a throwing release on the unwind does not starve the ones after it" in {
            object Bad extends RuntimeException("bad", null, false, false)
            val failure = new RuntimeException("failure")
            val log     = ListBuffer[String]()
            val v = Bracket(Effect.defer(1)) { _ =>
                Bracket(Effect.defer(2)) { _ =>
                    Effect.defer((throw failure): Int)
                } { (_, _) =>
                    discard(log += "inner")
                    throw Bad
                }
            }((_, _) => discard(log += "outer"))
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq failure)
            assert(ex.getSuppressed.exists(_ eq Bad))
            assert(log.toList == List("inner", "outer"))
        }
    }

    "finalizer failures" - {
        "a release that throws on completion fails the computation and releases the outer bracket" in {
            object Bad extends RuntimeException("bad", null, false, false)
            var outerSeen = Maybe.empty[Maybe[Throwable]]
            val v = Bracket(Effect.defer(1)) { _ =>
                Bracket(Effect.defer(2))(b => Effect.defer(b))((_, _) => throw Bad)
            }((_, outcome) => outerSeen = Maybe(outcome))
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Bad)
            assert(outerSeen.exists(_.exists(_ eq Bad)))
        }

        "a release failure on the unwind is suppressed onto the failure" in {
            val failure = new RuntimeException("failure")
            object Bad extends RuntimeException("bad", null, false, false)
            val v = Bracket(Effect.defer(1)) { _ =>
                Effect.defer((throw failure): Int)
            }((_, _) => throw Bad)
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq failure)
            assert(ex.getSuppressed.exists(_ eq Bad))
        }

        "a release failure on abandonment is suppressed onto the holder's signal" in {
            val signal = new RuntimeException("signal")
            object Bad extends RuntimeException("bad", null, false, false)
            val v = Bracket(Effect.defer(1)) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }((_, _) => throw Bad)
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            Eval.release(parked, signal)
            assert(signal.getSuppressed.exists(_ eq Bad))
        }
    }

    "mixed with other kernel features" - {
        "a bracket and a binding dumped together release inner first on discard" in {
            val log = ListBuffer[String]()
            sealed trait Cfg extends ContextEffect[Int]
            val body: Int < Ask =
                Bracket(Effect.defer(1)) { a =>
                    ContextEffect.handle(
                        Tag[Cfg],
                        (_: Maybe[Int]).getOrElse(0),
                        fork = (parent: Int) => parent,
                        join = (parent: Int, _: Int, _: Int) => parent,
                        release = (_: Int, _: Maybe[Throwable]) => discard(log += "binding")
                    )(ask.map(x => a + x))
                }((_, _) => discard(log += "bracket"))
            val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
            assert(dropped.eval == -1)
            assert(log.toList == List("binding", "bracket"))
        }

        "a multi-shot capture over a bracket runs every shot against the live resource and releases once" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map(x => a + x)
                }((_, outcome) => discard(outcomes += outcome))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 100 + y)),
                b => b
            )
            // both shots run against the live resource (7); the bracket releases once, where the holder ends
            assert(r.eval == 809)
            assert(outcomes.toList == List(Maybe.empty))
        }

        "a bracket held across two parks releases once on completion" in {
            var count = 0
            var seen  = Maybe.empty[Maybe[Throwable]]
            val v = Bracket(Effect.defer(7)) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer {
                        requestStop()
                        Effect.defer(a + 1)
                    }
                }
            } { (_, outcome) =>
                count += 1
                seen = Maybe(outcome)
            }
            val p1 = Eval.partial(v)
            assert(p1.isInstanceOf[Pending.Park[?, ?]])
            val p2 = Eval.partial(p1)
            assert(p2.isInstanceOf[Pending.Park[?, ?]])
            assert(p2.eval == 8)
            assert(count == 1)
            assert(seen.exists(_.isEmpty))
        }

        "a contextual isolate inside a bracket forks an inert obligation" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            val v = Bracket(Effect.defer(7)) { a =>
                Isolate.internal.Contextual.run(Effect.defer(a + 1)).map(_ + 1)
            }((_, outcome) => discard(outcomes += outcome))
            assert(v.eval == 9)
            assert(outcomes.toList.map(_.isEmpty) == List(true))
        }

        "a failing acquire never releases" in {
            var count = 0
            val v     = Bracket(Effect.defer((throw Boom): Int))(a => Effect.defer(a))((_, _) => count += 1)
            val ex    = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(count == 0)
        }

        "a failing inner acquire releases the outer bracket only" in {
            val log = ListBuffer[String]()
            val v = Bracket(Effect.defer(1)) { _ =>
                Bracket(Effect.defer((throw Boom): Int))(b => Effect.defer(b))((_, _) => discard(log += "inner"))
            }((_, _) => discard(log += "outer"))
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(log.toList == List("outer"))
        }

        "a bracket under a recovering handler releases with the failure, before the recovery" in {
            var seen  = Maybe.empty[Maybe[Throwable]]
            val order = ListBuffer[String]()
            val body: Int < Ask =
                Bracket(Effect.defer(7))(_ => Effect.defer((throw Boom): Int)) { (_, outcome) =>
                    seen = Maybe(outcome)
                    discard(order += "release")
                }
            val recovered: Int < Any =
                ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], body)(
                    [C] => (_, cont) => cont(0),
                    a => a,
                    _ =>
                        discard(order += "recover")
                        Maybe(9)
                )
            assert(recovered.eval == 9)
            assert(seen.exists(_.exists(_ eq Boom)))
            assert(order.toList == List("release", "recover"))
        }

        "a bracket failing after a crossing still releases before the recovery" in {
            var seen  = Maybe.empty[Maybe[Throwable]]
            val order = ListBuffer[String]()
            val body: Int < Ask =
                Bracket(Effect.defer(7))(_ => ask.map(_ => (throw Boom): Int)) { (_, outcome) =>
                    seen = Maybe(outcome)
                    discard(order += "release")
                }
            val recovered: Int < Any =
                ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], body)(
                    [C] => (_, cont) => cont(0),
                    a => a,
                    _ =>
                        discard(order += "recover")
                        Maybe(9)
                )
            assert(recovered.eval == 9)
            assert(seen.exists(_.exists(_ eq Boom)))
            assert(order.toList == List("release", "recover"))
        }

        "two stacked brackets abandoned together release innermost first" in {
            val log = ListBuffer[String]()
            val v = Bracket(Effect.defer(1)) { _ =>
                Bracket(Effect.defer(2)) { b =>
                    Effect.defer {
                        requestStop()
                        Effect.defer(b + 1)
                    }
                }((_, _) => discard(log += "inner"))
            }((_, _) => discard(log += "outer"))
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(log.toList == List("inner", "outer"))
        }

        "a release that throws on completion runs exactly once" in {
            object Bad extends RuntimeException("bad", null, false, false)
            var count = 0
            val v = Bracket(Effect.defer(1))(a => Effect.defer(a)) { (_, _) =>
                count += 1
                throw Bad
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Bad)
            assert(count == 1)
        }

        "a release that throws after a pure use runs exactly once" in {
            object Bad extends RuntimeException("bad", null, false, false)
            var count = 0
            val v = Bracket(Effect.defer(1))(a => a + 1) { (_, _) =>
                count += 1
                throw Bad
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Bad)
            assert(count == 1)
        }

        "nested brackets complete innermost first" in {
            val log = ListBuffer[String]()
            val v = Bracket(Effect.defer(1)) { _ =>
                Bracket(Effect.defer(2))(b => Effect.defer(b))((_, _) => discard(log += "inner"))
            }((_, _) => discard(log += "outer"))
            assert(v.eval == 2)
            assert(log.toList == List("inner", "outer"))
        }

        "a bracket releases before the next one acquires" in {
            val log = ListBuffer[String]()
            def one(name: String): Int < Any =
                Bracket(Effect.defer {
                    discard(log += s"acquire $name")
                    name
                })(_ => Effect.defer(1))((_, _) => discard(log += s"release $name"))
            val v = one("a").map(_ => one("b"))
            assert(v.eval == 1)
            assert(log.toList == List("acquire a", "release a", "acquire b", "release b"))
        }

        "a bracket owned by an isolated child releases at the child's completion" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            val v = Isolate.internal.Contextual.run(
                Bracket(Effect.defer(7))(a => Effect.defer(a + 1))((_, outcome) => discard(outcomes += outcome))
            ).map(_ + 1)
            assert(v.eval == 9)
            assert(outcomes.toList.map(_.isEmpty) == List(true))
        }

        "a failure inside an isolated child releases the child's bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Isolate.internal.Contextual.run(
                Bracket(Effect.defer(7))(_ => Effect.defer((throw Boom): Int))((_, outcome) => seen = Maybe(outcome))
            )
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "abandoning an isolated child releases its bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Isolate.internal.Contextual.run(
                Bracket(Effect.defer(7)) { a =>
                    Effect.defer {
                        requestStop()
                        Effect.defer(a + 1)
                    }
                }((_, outcome) => seen = Maybe(outcome))
            )
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(seen.isEmpty)
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "two brackets dumped together release inner first on discard" in {
            val log = ListBuffer[String]()
            val body: Int < Ask =
                Bracket(Effect.defer(1)) { a =>
                    Bracket.ensuring(_ => discard(log += "inner"))(ask.map(x => a + x))
                }((_, _) => discard(log += "outer"))
            val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
            assert(dropped.eval == -1)
            assert(log.toList == List("inner", "outer"))
        }

        // The bracket's release belongs to the handler that took the continuation: every shot runs against the live
        // resource, and the release runs once, where the handler ends, told a clean end since the extent ran to one.
        "a multi-shot capture over a bracket runs every shot against the live resource and releases once at the handler's end" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            var uses     = 0
            val body: Int < Ask =
                Bracket(Effect.defer(7)) { a =>
                    ask.map { x =>
                        uses += 1
                        a + x
                    }
                }((_, outcome) => discard(outcomes += outcome))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 100 + y)),
                b => b
            )
            assert(r.eval == 809)
            assert(uses == 2)
            assert(outcomes.toList.map(_.isEmpty) == List(true))
        }

        "a discarded capture's bracket is told the discard signal" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Bracket(Effect.defer(7))(a => ask.map(x => a + x))((_, outcome) => seen = Maybe(outcome))
            val dropped: Int < Any =
                ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], body)(
                    [C] => (_, _) => -1,
                    b => b
                )
            assert(dropped.eval == -1)
            assert(seen.exists(_.exists(_.isInstanceOf[kyo.KyoException])))
        }
    }

    "reading audit pins" - {
        "a leaked capture resumed by another region after release is refused as closed" in {
            var leaked          = Maybe.empty[Arrow[Int, Int, Ask]]
            val body: Int < Ask = Bracket(Effect.defer(7))(a => ask.map(_ + a))((_, _) => ())
            val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        leaked = Maybe(Region.leak(cont))
                        -1
                ,
                a => a
            )
            assert(first.eval == -1)
            val again: Int < Any =
                ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], leaked.get(1))(
                    [C] => (_, cont) => cont(0),
                    a => a,
                    ex => Maybe(if ex.isInstanceOf[Closed] then -2 else -3)
                )
            // leaked.get(1) re-enters a released region, refused with Closed, which the recover arm turns into -2
            assert(again.eval == -2)
        }

        "a bracket does not cross into an isolated child: a capture inside the child, resumed after the bracket ended, runs" in {
            // The child copy a bracket forks is inert, so a continuation escaping the child carries no obligation
            // and no refusal.
            var leaked    = Maybe.empty[Arrow[Int, Int, Ask]]
            var released  = false
            var usedAfter = false
            val body: Int < Any =
                Bracket(Effect.defer(7)) { a =>
                    ArrowEffect.handleCont(
                        Tag[Ask],
                        Isolate.internal.Contextual.run(ask.map { n =>
                            usedAfter = released
                            n + a
                        })
                    )(
                        [C] =>
                            (_, cont) =>
                                leaked = Maybe(Region.leak(cont))
                                -1
                        ,
                        b => b
                    )
                }((_, _) => released = true)
            assert(body.eval == -1)
            assert(released)
            val again: Int < Any =
                ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], leaked.get(1))(
                    [C] => (_, cont) => cont(0),
                    b => b,
                    ex => Maybe(if ex.isInstanceOf[Closed] then -2 else -3)
                )
            assert(again.eval == 8)
            assert(usedAfter)
        }

        "an isolated child built inside a bracket and evaluated after the bracket ended is not refused" in {
            // The shape of a spawned fiber: built in the parent's extent, evaluated after the bracket released.
            var released = false
            var child    = Maybe.empty[Int < Any]
            val body: Int < Any =
                Bracket(Effect.defer(7)) { a =>
                    Isolate.internal.Contextual.capture { st =>
                        child = Maybe(Isolate.internal.Contextual.restore(Isolate.internal.Contextual.isolate(st, Effect.defer(a + 1))))
                        -1
                    }
                }((_, _) => released = true)
            assert(body.eval == -1)
            assert(released)
            assert(child.get.eval == 8)
        }

        "a bracket outside the answering handler is not carried by an escaped continuation, so the remainder runs after the release" in {
            var stash = Maybe.empty[Arrow[Int, Int, Ask]]
            val log   = ListBuffer[String]()
            val v: Int < Any =
                Bracket(Effect.defer(1)) { r =>
                    ArrowEffect.handleCont(
                        Tag[Ask],
                        ask.map { a =>
                            log += s"use $r"
                            a + r
                        }
                    )(
                        [C] =>
                            (_, cont) =>
                                stash = Maybe(Region.leak(cont))
                                -1
                        ,
                        a => a
                    )
                }((_, _) => discard(log += "release"))
            assert(v.eval == -1)
            assert(log.toList == List("release"))
            assert(answerAsk(0)(stash.get(41)).eval == 42)
            assert(log.toList == List("release", "use 1"))
        }

        "a park taken in a clause before it resumes a crossing carries the crossed bracket" in {
            def program(seen: ListBuffer[Maybe[Throwable]]): Int < Any =
                val body: Int < Ask =
                    Bracket(Effect.defer(7))(a => ask.map(_ + a))((_, outcome) => discard(seen += outcome))
                ArrowEffect.handleCont(Tag[Ask], body)(
                    [C] =>
                        (_, cont) =>
                            Effect.defer {
                                requestStop()
                                ()
                            }.map(_ => Effect.defer(cont(1))),
                    a => a
                )
            end program
            val abandoned = ListBuffer[Maybe[Throwable]]()
            val p1        = Eval.partial(program(abandoned))
            assert(p1.evalNow.isEmpty)
            Eval.release(p1, Boom)
            assert(abandoned.size == 1)
            assert(abandoned.head.exists(_ eq Boom))
            val resumed = ListBuffer[Maybe[Throwable]]()
            val p2      = Eval.partial(program(resumed))
            assert(p2.evalNow.isEmpty)
            assert(p2.eval == 8)
            assert(resumed.size == 1)
            assert(resumed.head.isEmpty)
        }

        "a leaked capture's refusal is recoverable by the region that resumes it" in {
            var leaked          = Maybe.empty[Arrow[Int, Int, Ask]]
            val body: Int < Ask = Bracket(Effect.defer(7))(a => ask.map(_ + a))((_, _) => ())
            val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        leaked = Maybe(Region.leak(cont))
                        -1
                ,
                a => a
            )
            assert(first.eval == -1)
            val again: Int < Any =
                ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], leaked.get(1))(
                    [C] => (_, cont) => cont(0),
                    a => a,
                    ex => Maybe(if ex.isInstanceOf[Closed] then -2 else -3)
                )
            assert(again.eval == -2)
        }
    }

    "unit acquire" - {
        "runs after completion with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Bracket(())(_ => Effect.defer(5))((_, outcome) => seen = Maybe(outcome))
            assert(v.eval == 5)
            assert(seen.exists(_.isEmpty))
        }

        "runs with the failure on unwind" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Bracket(())(_ => Effect.defer((throw Boom): Int))((_, outcome) => seen = Maybe(outcome))
            val ex   = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }
    }
    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(value), a => a)

    sealed trait Str extends ArrowEffect[Const[Int], Const[String]]
    def str(i: Int): String < Str = ArrowEffect.suspend[Any](Tag[Str], i)

    sealed trait Wrap extends ArrowEffect[Const[Unit], Const[Unit]]
    def recovering[A](v: A < Wrap)(f: Throwable => A): A < Any =
        ArrowEffect.handleCont(Tag[Wrap], v)([C] => (_, cont) => cont(()), a => a, ex => Maybe(f(ex)))

    "clause stops and discards" - {
        "a handleLoopState clause that stops the computation still releases" in {
            var released = Maybe.empty[Int]
            val v        = Bracket(Effect.defer(1))(r => ask.map(_ + r))((r, _) => released = Maybe(r))
            val stopped  = ArrowEffect.handleLoopState(Tag[Ask], 0, v)([C] => (_, _) => Loop.done(-1), (_, a) => a)
            assert(stopped.eval == -1)
            assert(released == Maybe(1))
        }

        "a stateful clause that stops after advancing still releases" in {
            var released = Maybe.empty[Int]
            val v        = Bracket(Effect.defer(1))(r => ask.map(a => ask.map(b => a + b + r)))((r, _) => released = Maybe(r))
            val stopped =
                ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                    [C] => (s, _) => if s == 1 then Loop.done(-1) else Loop.continue(s + 1, 1),
                    (_, a) => a
                )
            assert(stopped.eval == -1)
            assert(released == Maybe(1))
        }

        "every outstanding bracket releases when a clause stops the computation" in {
            var released = List.empty[String]
            val v =
                Bracket(Effect.defer("outer")) { _ =>
                    Bracket(Effect.defer("inner")) { _ =>
                        ask.map(_ + 1)
                    }((r, _) => released :+= r)
                }((r, _) => released :+= r)
            val stopped = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a)
            assert(stopped.eval == -1)
            assert(released == List("inner", "outer"))
        }

        "a discarded continuation releases every outstanding bracket" in {
            var released = List.empty[String]
            val v =
                Bracket(Effect.defer("outer")) { _ =>
                    Bracket(Effect.defer("inner")) { _ =>
                        ask.map(_ + 1)
                    }((r, _) => released :+= r)
                }((r, _) => released :+= r)
            val dropped = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(dropped.eval == -1)
            assert(released == List("inner", "outer"))
        }

        "does not release when the acquire never completes" in {
            var released = false
            val v =
                Bracket(ask.map(_ => 1))(r => r + 1)((_, _) => released = true)
            val never = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(never.eval == -1)
            assert(!released)
        }
    }

    "parks, budget, and stack safety" - {
        "an acquire resumed twice owes a release for each resume" in {
            var released = List.empty[Int]
            val v        = Bracket(ask)(r => r * 10)((r, _) => released :+= r)
            val r =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                    a => a
                )
            assert(r.eval == 30)
            assert(released == List(1, 2))
        }

        "a bracket spanning a budget park releases once" in {
            var count = 0
            def chain(n: Int, v: Int < Any): Int < Any =
                if n == 0 then v else chain(n - 1, v.map(_ + 1))
            val v = Bracket(Effect.defer(0))(r => chain(1000, r))((_, _) => count += 1)
            assert(v.eval == 1000)
            assert(count == 1)
        }

        "a slice stopped inside a bracket releases once, at the end" in {
            var released = 0
            val v: Int < Any =
                Bracket(Effect.defer {
                    requestStop()
                    1
                })(r =>
                    Effect.defer {
                        requestStop()
                        r + 1
                    }.map(_ + 1)
                )((_, _) => released += 1)

            @tailrec def run(v: Int < Any, steps: Int): (Int, Int) =
                v.evalNow match
                    case Present(a) => (a, steps)
                    case Absent =>
                        assert(released == 0)
                        assert(steps < 100)
                        run(Eval.partial(v), steps + 1)

            val (result, steps) = run(v, 0)
            assert(result == 3)
            assert(steps > 1)
            assert(released == 1)
        }

        "deeply nested brackets release in bounded stack" in {
            var count = 0
            def nest(n: Int): Int < Any =
                if n == 0 then 0
                else Bracket(Effect.defer(n))(_ => nest(n - 1))((_, _) => count += 1)
            assert(nest(1000).eval == 0)
            assert(count == 1000)
        }

        "many sequential brackets each release" in {
            var count = 0
            def loop(n: Int, acc: Int < Any): Int < Any =
                if n == 0 then acc
                else loop(n - 1, acc.map(a => Bracket(Effect.defer(1))(r => a + r)((_, _) => count += 1)))
            assert(loop(1000, 0: Int < Any).eval == 1000)
            assert(count == 1000)
        }

        "a use that fails after a resume still releases once with the failure" in {
            var released = 0
            var seen     = Maybe.empty[Maybe[Throwable]]
            val boom     = new RuntimeException("late")
            val v: Int < Any =
                Bracket(Effect.defer(1)) { r =>
                    Effect.defer {
                        requestStop()
                        r
                    }.map(x => if x == 1 then throw boom else x)
                } { (_, outcome) =>
                    released += 1
                    seen = Maybe(outcome)
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(released == 0)
            assert(intercept[RuntimeException](p.eval) eq boom)
            assert(released == 1)
            assert(seen.exists(_.exists(_ eq boom)))
        }

        "a park inside nested brackets carries both releases" in {
            var released = List.empty[String]
            val v: Int < Any =
                Bracket(Effect.defer(1)) { a =>
                    Bracket(Effect.defer(2)) { b =>
                        Effect.defer {
                            requestStop()
                            a + b
                        }.map(_ + 39)
                    }((_, _) => released :+= "inner")
                }((_, _) => released :+= "outer")
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(released.isEmpty)
            assert(p.eval == 42)
            assert(released == List("inner", "outer"))
        }

        "a park evaluated twice refuses the second evaluation after release" in {
            var released = 0
            val v: Int < Any =
                Bracket(Effect.defer(1))(r =>
                    Effect.defer {
                        requestStop()
                        r
                    }.map(_ + 41)
                )((_, _) => released += 1)
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(p.eval == 42)
            // the first evaluation completes and releases; the second re-enters a released region and is refused
            discard(intercept[kyo.Closed](p.eval))
            assert(released == 1)
        }
    }

    "nesting and composition" - {
        "a bracket inside a nested eval releases at that eval's boundary" in {
            var events = List.empty[String]
            val inner  = Bracket(Effect.defer(1))(r => r + 1)((_, _) => events :+= "inner release")
            val outer =
                Bracket(Effect.defer(2)) { r =>
                    val got = inner.eval
                    events :+= s"inner = $got"
                    r
                }((_, _) => events :+= "outer release")
            assert(outer.eval == 2)
            assert(events == List("inner release", "inner = 2", "outer release"))
        }

        "an acquire that is itself a bracket releases both" in {
            var released = List.empty[String]
            val acquire  = Bracket(Effect.defer("a"))(r => r + "!")((r, _) => released :+= r)
            val v        = Bracket(acquire)(r => r.length)((r, _) => released :+= r)
            assert(v.eval == 2)
            assert(released == List("a", "a!"))
        }

        "nested brackets separated by a handler still release innermost first on a failure" in {
            var order = List.empty[String]
            val boom  = new RuntimeException("boom")
            val v: Int < Any =
                Bracket(Effect.defer(1)) { _ =>
                    answerAsk(1) {
                        Bracket(Effect.defer(2)) { i =>
                            Effect.defer(i).map(_ => (throw boom): Int)
                        }((_, _) => order :+= "inner")
                    }
                }((_, _) => order :+= "outer")
            assert(intercept[RuntimeException](v.eval) eq boom)
            assert(order == List("inner", "outer"))
        }
    }

    "acquire and release failure edges" - {
        "a release that throws during unwind does not lose the failure or the recovery" in {
            var seen       = List.empty[String]
            var suppressed = List.empty[String]
            val v: Int < Any = recovering[Int](
                Bracket(Effect.defer(1)) { _ =>
                    (throw new UnsupportedOperationException("body")): Int
                }((_, _) => throw new IllegalStateException("release"))
            ) { ex =>
                seen :+= ex.getMessage
                suppressed = ex.getSuppressed.toList.map(_.getMessage).filterNot(_.startsWith("effect trace"))
                -1
            }
            assert(v.eval == -1)
            assert(seen == List("body"))
            assert(suppressed == List("release"))
        }

        "a recovery that throws surfaces its own failure with the original suppressed" in {
            val original     = new UnsupportedOperationException("body")
            val fromRecovery = new IllegalStateException("recovery")
            var out          = Maybe.empty[Maybe[Throwable]]
            val v: Int < Any = Bracket(Effect.defer(1)) { _ =>
                ArrowEffect.handleCont(Tag[Ask], ask.map(_ => (throw original): Int))(
                    [C] => (_, cont) => cont(0),
                    a => a,
                    _ => Maybe((throw fromRecovery): Int)
                )
            }((_, outcome) => out = Maybe(outcome))
            val ex = intercept[IllegalStateException](v.eval)
            assert(ex eq fromRecovery)
            assert(out.exists(_.exists(_ eq fromRecovery)))
        }

        "an effectful acquire whose handler fails owes no release" in {
            var released = 0
            val boom     = new RuntimeException("clause")
            val v: Int < Str =
                Bracket(str(1))(r => r.length)((_, _) => released += 1)
            val handled: Int < Any =
                ArrowEffect.handleCont(Tag[Str], v)([C] => (_, _) => throw boom, a => a)
            assert(intercept[RuntimeException](handled.eval) eq boom)
            assert(released == 0)
        }

        "an interior recovery turns the release outcome into the recovered success" in {
            var out = Maybe.empty[Maybe[Throwable]]
            val v: Int < Any =
                Bracket(Effect.defer(1)) { a =>
                    ArrowEffect.handleCont(Tag[Ask], ask.map(_ => (throw new RuntimeException("use")): Int))(
                        [C] => (_, cont) => cont(0),
                        a2 => a2,
                        _ => Maybe(a + 41)
                    )
                }((_, outcome) => out = Maybe(outcome))
            assert(v.eval == 42)
            assert(out.exists(_.isEmpty))
        }

        "a failing use with an effectful acquire still releases with the failure" in {
            var out  = Maybe.empty[Maybe[Throwable]]
            val boom = new RuntimeException("use")
            val v: String < Str =
                Bracket(str(10)) { a =>
                    if a == "10" then throw boom else a
                }((_, outcome) => out = Maybe(outcome))
            val handled: String < Any =
                ArrowEffect.handleCont(Tag[Str], v)([C] => (input, cont) => cont(input.toString))
            assert(intercept[RuntimeException](handled.eval) eq boom)
            assert(out.exists(_.exists(_ eq boom)))
        }

        "a release that throws during abandonment does not silence the others" in {
            var order = List.empty[String]
            val v: Int < Any =
                Bracket(Effect.defer(1)) { a =>
                    Bracket(Effect.defer(2)) { b =>
                        Effect.defer {
                            requestStop()
                            a + b
                        }.map(_ + 39)
                    } { (_, _) =>
                        order :+= "inner"
                        throw new IllegalStateException("inner-release")
                    }
                }((_, _) => order :+= "outer")
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            Eval.release(p, Boom)
            assert(order == List("inner", "outer"))
        }

        "every release runs even when several throw" in {
            var released = List.empty[String]
            def level(name: String, failing: Boolean)(inner: Int < Ask): Int < Ask =
                Bracket(Effect.defer(name))(_ => inner)((r, _) =>
                    if failing then throw new IllegalStateException(s"$r release")
                    else released :+= r
                )
            val v       = level("a", false)(level("b", true)(level("c", true)(ask.map(_ + 1))))
            val stopped = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            val caught =
                try
                    discard(stopped.eval)
                    Maybe.empty[(String, List[String])]
                catch
                    case ex: Throwable =>
                        Maybe((ex.getMessage, ex.getSuppressed.toList.map(_.getMessage)))
            assert(released == List("a"))
            discard(caught)
        }
    }

    "resource plumbing" - {
        "a resource the use hands back is still released" in {
            var released = false
            val v        = Bracket(Effect.defer("res"))(r => r)((_, _) => released = true)
            assert(v.eval == "res")
            assert(released)
        }

        "a use that ignores the resource still releases it" in {
            var released = Maybe.empty[Int]
            val v        = Bracket(Effect.defer(1))(_ => "done")((r, _) => released = Maybe(r))
            assert(v.eval == "done")
            assert(released == Maybe(1))
        }
    }

    "bracket vs regions" - {
        "a bracket interleaved with a region releases after the region completes" in {
            var events = List.empty[String]
            val v =
                Bracket(Effect.defer(1)) { r =>
                    answerAsk(41)(ask.map { a =>
                        events :+= "region answered"
                        a + r
                    })
                }((r, _) => events :+= s"release $r")
            assert(v.eval == 42)
            assert(events == List("region answered", "release 1"))
        }

        "a region installed inside the use does not intercept the release" in {
            var events = List.empty[String]
            val v =
                Bracket(Effect.defer(1)) { r =>
                    ArrowEffect.handleCont(Tag[Ask], ask.map(_ + r))(
                        [C] =>
                            (_, _) =>
                                events :+= "clause stopped"
                                -1
                        ,
                        a => a
                    )
                }((r, _) => events :+= s"release $r")
            assert(v.eval == -1)
            assert(events == List("clause stopped", "release 1"))
        }

        "a discarded continuation releases when its region completes, before the handler's continuation" in {
            var events = List.empty[String]
            val acquire: Int < Ask = Effect.defer {
                events :+= "acquire"
                1
            }
            val v       = Bracket(acquire)(r => ask.map(_ + r))((r, _) => events :+= s"release $r")
            val dropped = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            val after =
                dropped.map { a =>
                    events :+= s"after $a"
                    a
                }
            assert(after.eval == -1)
            assert(events == List("acquire", "release 1", "after -1"))
        }

        "a bracket a clause opens around the continuation releases when the body result settles" in {
            var events       = List.empty[String]
            val v: Int < Ask = ask.map(_ + 1)
            val handled =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            Bracket(Effect.defer {
                                events :+= "clause acquire"
                                41
                            })(r => cont(r))((_, _) => events :+= "clause release"),
                    a =>
                        events :+= s"done $a"
                        a
                )
            assert(handled.eval == 42)
            assert(events == List("clause acquire", "clause release", "done 42"))
        }

        "a bracket outside two regions releases at its own extent, not when an inner region discards" in {
            var events = List.empty[String]
            val v: Int < Any =
                Bracket(Effect.defer {
                    events :+= "acquire"
                    1
                }) { r =>
                    ArrowEffect.handleCont(Tag[Ask], ask.map(_ + r))([C] => (_, _) => -1, a => a).map { a =>
                        events :+= s"inner done $a"
                        a + 100
                    }
                }((r, _) => events :+= s"release $r")
            assert(v.eval == 99)
            assert(events == List("acquire", "inner done -1", "release 1"))
        }
    }

    "multi-shot clauses" - {

        // A held region records that its extent ended instead of releasing, and an unwind overrides that record:
        // the extent is being abandoned, so the release is owed the failure.
        "an unwind after a branch ended tells the release it failed" in {
            var outcome: Maybe[Maybe[Throwable]] = Absent
            val v =
                Bracket(Effect.defer(1)) { r =>
                    ask.map(a => if a == 20 then throw Boom else a + r)
                }((_, o) => outcome = Maybe(o))
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            assert(intercept[RuntimeException](twice.eval) eq Boom)
            assert(outcome.exists(_.exists(_ eq Boom)), s"the release was told: $outcome")
        }

        // The recovering overload exists so a clause needing both does not have to drop to `handleCont` for the
        // recovery and lose the holding, which is not a convenience: it changes when the dumped regions discharge.
        "a recovering multi-shot clause still holds its regions" in {
            var events = List.empty[String]
            val v =
                Bracket(Effect.defer(1)) { r =>
                    ask.map(a => a + r)
                }((r, _) => events :+= s"release $r")
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a,
                    _ => Absent
                )
            assert(twice.eval == 32)
            assert(events == List("release 1"))
        }

        "a recovering multi-shot clause answers a failure raised in a branch" in {
            var outcome: Maybe[Maybe[Throwable]] = Absent
            val v =
                Bracket(Effect.defer(1)) { r =>
                    ask.map(a => if a == 20 then throw Boom else a + r)
                }((_, o) => outcome = Maybe(o))
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a,
                    _ => Maybe(-1)
                )
            assert(twice.eval == -1)
            assert(outcome.exists(_.exists(_ eq Boom)), s"the release was told: $outcome")
        }

        "a recovering multi-shot clause declining lets the failure through" in {
            val v = Bracket(Effect.defer(1))(r => ask.map(a => if a == 20 then throw Boom else a + r))((_, _) => ())
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a,
                    _ => Absent
                )
            assert(intercept[RuntimeException](twice.eval) eq Boom)
        }

        "a recovering multi-shot clause answers a throw raised while its input is built" in {
            def boomInput: Int < Ask = throw Boom
            val twice =
                ArrowEffect.handleCont(Tag[Ask], boomInput)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a,
                    _ => Maybe(-2)
                )
            assert(twice.eval == -2)
        }

        "a multi-shot clause that acquires per branch releases each where its branch ends" in {
            var events = List.empty[String]
            val v =
                Bracket(ask) { r =>
                    events :+= s"use $r"
                    r * 10
                }((r, _) => events :+= s"release $r")
            val thrice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(1).map(a => cont(2).map(b => cont(3).map(c => a + b + c))),
                    a => a
                )
            assert(thrice.eval == 60)
            assert(events == List("use 1", "release 1", "use 2", "release 2", "use 3", "release 3"))
        }

        "a later branch is refused before it can run, throwing branch included" in {
            var events = List.empty[String]
            val acquire: Int < Ask = Effect.defer {
                events :+= "acquire"
                1
            }
            val boom = new RuntimeException("boom")
            val v =
                Bracket(acquire) { r =>
                    ask.map { a =>
                        if a < 0 then throw boom else a + r
                    }
                }((r, _) => events :+= s"release $r")
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(-1).map(b => a + b)),
                    a => a
                )
            // uniform: no refusal; cont(10) runs, cont(-1) throws, unwinding the held bracket with the failure
            assert(intercept[RuntimeException](twice.eval) eq boom)
            assert(events == List("acquire", "release 1"))
        }

        "every branch of a multi-shot clause reads the live resource" in {
            var closed             = false
            var seen               = List.empty[String]
            val acquire: Int < Ask = Effect.defer(1)
            val v =
                Bracket(acquire) { r =>
                    ask.map { a =>
                        seen :+= (if closed then s"branch $a after release" else s"branch $a")
                        a + r
                    }
                }((_, _) => closed = true)
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            // uniform: the resource stays live across shots and is released once, after both branches
            assert(twice.eval == 32)
            assert(seen == List("branch 10", "branch 20"))
            assert(closed)
        }

        "a Choice-shaped clause runs every branch against the live resource" in {
            var closed             = false
            var seen               = List.empty[String]
            val acquire: Int < Ask = Effect.defer(100)
            val v =
                Bracket(acquire) { r =>
                    ask.map { a =>
                        seen :+= (if closed then s"branch $a after release" else s"branch $a")
                        a + r
                    }
                }((_, _) => closed = true)
            val branches =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(1).map(a => cont(2).map(b => cont(3).map(c => a + b + c))),
                    a => a
                )
            // uniform: the resource stays live across all three shots, released once at the end
            assert(branches.eval == 306)
            assert(seen == List("branch 1", "branch 2", "branch 3"))
            assert(closed)
        }

        "nested brackets shared by a multi-shot clause are refused at the second branch" in {
            var events           = List.empty[String]
            val outer: Int < Ask = Effect.defer(1)
            val v =
                Bracket(outer) { o =>
                    Bracket(Effect.defer(2)) { i =>
                        ask.map { a =>
                            events :+= s"branch $a"
                            a + o + i
                        }
                    }((_, _) => events :+= "release inner")
                }((_, _) => events :+= "release outer")
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            // uniform: both branches run against the live nested resources, released once each at the holder
            assert(twice.eval == 36)
            assert(events == List("branch 10", "branch 20", "release inner", "release outer"))
        }

        "a continuation held past the end of the eval refuses every time it is applied" in {
            var count = 0
            var stash = Maybe.empty[Arrow[Int, Int, Ask & Any]]
            val v     = Bracket(Effect.defer(1))(r => ask.map(_ + r))((_, _) => count += 1)
            val dropped =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            stash = Maybe(Region.leak(cont))
                            -1
                    ,
                    a => a
                )
            assert(dropped.eval == -1)
            assert(count == 1)
            // the region was released when the eval ended; the held continuation re-enters it, which is refused
            discard(intercept[kyo.Closed](answerAsk(0)(stash.get(2)).eval))
            discard(intercept[kyo.Closed](answerAsk(0)(stash.get(5)).eval))
            assert(count == 1)
        }

        "a handleFirst remainder that is never resumed releases at the enclosing region's exit, as discarded" in {
            // The clause drops the continuation: the bracket it carries is owed to the region below the
            // handleFirst and drained when that region exits.
            var outcome        = Maybe.empty[Maybe[Throwable]]
            var closedAtClause = false
            var closedAfter    = false
            val v              = Bracket(Effect.defer(1))(r => ask.map(_ + r))((_, o) => outcome = Maybe(o))
            val dropped: Int < Ask =
                ArrowEffect.handleFirst(Tag[Ask], v)(
                    handle = [C] =>
                        (_, _) =>
                            closedAtClause = outcome.nonEmpty
                        0
                    ,
                    done = a => a
                )
            val r = answerAsk(0)(dropped.map { a =>
                closedAfter = outcome.nonEmpty
                a
            })
            assert(r.eval == 0)
            assert(!closedAtClause)
            assert(!closedAfter)
            // the remainder is never resumed, so the bracket's use never ran to an end: discard signal
            assert(outcome.exists(_.exists(_.isInstanceOf[kyo.KyoException])))
        }

        "a park between the hand-out and the resume carries the remainder's debt" in {
            var outcome = Maybe.empty[Maybe[Throwable]]
            val v       = Bracket(Effect.defer(1))(r => ask.map(_ + r))((_, o) => outcome = Maybe(o))
            val first: Int < Ask =
                ArrowEffect.handleFirst(Tag[Ask], v)(
                    handle = [C] =>
                        (_, cont) =>
                            Effect.defer {
                                requestStop()
                                Effect.defer(cont(10))
                        },
                    done = a => a
                )
            val parked = Eval.partial(answerAsk(0)(first))
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(outcome.isEmpty)
            assert(parked.eval == 11)
            assert(outcome.exists(_.isEmpty))
        }

        "a park between the hand-out and the resume, abandoned, releases the remainder's bracket" in {
            var outcome = Maybe.empty[Maybe[Throwable]]
            val v       = Bracket(Effect.defer(1))(r => ask.map(_ + r))((_, o) => outcome = Maybe(o))
            val first: Int < Ask =
                ArrowEffect.handleFirst(Tag[Ask], v)(
                    handle = [C] =>
                        (_, cont) =>
                            Effect.defer {
                                requestStop()
                                Effect.defer(cont(10))
                        },
                    done = a => a
                )
            val parked = Eval.partial(answerAsk(0)(first))
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(outcome.isEmpty)
            Eval.release(parked, Boom)
            assert(outcome.exists(_.exists(_ eq Boom)))
        }

        "nested hand-outs descend the debt through each region, and the remainder resumed through both completes the bracket" in {
            // Each region hands its remainder out as a value with the bracket-carrying one inside, so the debt
            // descends region by region to where the resume finds it.
            var outcome         = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask = Bracket(Effect.defer(1))(r => ask.map(_ + r))((_, o) => outcome = Maybe(o))
            val inner: Maybe[Arrow[Int, Int, Ask]] < Any =
                ArrowEffect.handleFirst[Const[Unit], Const[Int], Ask, Int, Maybe[Arrow[Int, Int, Ask]], Any, Any](Tag[Ask], body)(
                    handle = [C] => (_, cont) => Maybe(cont),
                    done = _ => Maybe.empty
                )
            val outerBody: Int < (Str & Ask) = inner.map(k => str(1).map(_ => k.get(10)))
            val outer: Maybe[Arrow[String, Int, Str & Ask]] < Ask =
                ArrowEffect.handleFirst[Const[Int], Const[String], Str, Int, Maybe[Arrow[String, Int, Str & Ask]], Ask, Any](
                    Tag[Str],
                    outerBody
                )(
                    handle = [C] => (_, cont) => Maybe(cont),
                    done = _ => Maybe.empty
                )
            val r: Int < Any =
                answerAsk(0) {
                    outer.map { k =>
                        assert(outcome.isEmpty)
                        ArrowEffect.handleCont(Tag[Str], k.get("s"))([C] => (_, cont) => cont("x"), a => a)
                    }
                }
            assert(r.eval == 11)
            assert(outcome.exists(_.isEmpty))
        }

        "nested hand-outs: an outer remainder dropped with the inner one inside releases at the enclosing exit" in {
            var outcome         = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask = Bracket(Effect.defer(1))(r => ask.map(_ + r))((_, o) => outcome = Maybe(o))
            val inner: Maybe[Arrow[Int, Int, Ask]] < Any =
                ArrowEffect.handleFirst[Const[Unit], Const[Int], Ask, Int, Maybe[Arrow[Int, Int, Ask]], Any, Any](Tag[Ask], body)(
                    handle = [C] => (_, cont) => Maybe(cont),
                    done = _ => Maybe.empty
                )
            val outerBody: Int < (Str & Ask) = inner.map(k => str(1).map(_ => k.get(10)))
            val outer: Maybe[Arrow[String, Int, Str & Ask]] < Ask =
                ArrowEffect.handleFirst[Const[Int], Const[String], Str, Int, Maybe[Arrow[String, Int, Str & Ask]], Ask, Any](
                    Tag[Str],
                    outerBody
                )(
                    handle = [C] => (_, cont) => Maybe(cont),
                    done = _ => Maybe.empty
                )
            var openAfterDrop = false
            val r: Int < Any =
                answerAsk(0) {
                    outer.map { _ =>
                        openAfterDrop = outcome.isEmpty
                        -1
                    }
                }
            assert(r.eval == -1)
            assert(openAfterDrop)
            // the nested remainder is dropped without being resumed, so its bracket's use never ran: discard signal
            assert(outcome.exists(_.exists(_.isInstanceOf[kyo.KyoException])))
        }

        "branches of a multi-shot clause share the resource the use closed over" in {
            var acquired = 0
            var seen     = List.empty[Int]
            val acquire: Int < Ask = Effect.defer {
                acquired += 1
                acquired
            }
            val v =
                Bracket(acquire) { r =>
                    ask.map { a =>
                        seen :+= r
                        a + r
                    }
                }((_, _) => ())
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            // uniform: both shots run against the one live resource
            assert(twice.eval == 32)
            assert(acquired == 1)
            assert(seen == List(1, 1))
        }

        "a branch built inside a clause and evaluated later is refused" in {
            var closed = false
            var seen   = List.empty[String]
            var stash  = Maybe.empty[Int < Ask]
            val v =
                Bracket(Effect.defer(1)) { r =>
                    ask.map { a =>
                        seen :+= (if closed then s"branch $a after release" else s"branch $a")
                        a + r
                    }
                }((_, _) => closed = true)
            val built =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            stash = Maybe(Region.leak(cont)(10))
                            -1
                    ,
                    a => a
                )
            assert(built.eval == -1)
            assert(closed)
            // the branch was built after the region released; evaluating it re-enters the released region, refused
            discard(intercept[kyo.Closed](answerAsk(0)(stash.get).eval))
            assert(seen == Nil)
        }

        "a bracket that encloses a multi-shot region releases after every branch" in {
            var closed = false
            var seen   = List.empty[String]
            val v: Int < Any =
                Bracket(Effect.defer(1)) { r =>
                    ArrowEffect.handleCont(
                        Tag[Ask],
                        ask.map { a =>
                            seen :+= (if closed then s"branch $a after release" else s"branch $a")
                            a + r
                        }
                    )(
                        [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                        a => a
                    )
                }((_, _) => closed = true)
            assert(v.eval == 32)
            assert(seen == List("branch 10", "branch 20"))
            assert(closed)
        }

        // Disabled: needs ArrowEffect.handleContRepeated, which this kernel does not provide.
        /*
        "a recovering multi-shot clause releases once at its end" in {
            var events = List.empty[String]
            val v =
                Bracket(Effect.defer(1)) { r =>
                    ask.map(a => a + r)
                }((r, _) => events :+= s"release $r")
            val twice =
                ArrowEffect.handleContRepeated(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a,
                    _ => Absent
                )
            assert(twice.eval == 32)
            assert(events == List("release 1"))
        }
         */

        // Disabled: needs ArrowEffect.handleContRepeated, which this kernel does not provide.
        /*
        // Every resumption re-enters the region, and the re-entered region repeats as the outer one does: a bracket
        // acquired inside one resumption and captured by an inner occurrence's continuation is held across that
        // clause's resumptions and released where the re-entered region ends, before the outer clause resumes again.
        "a bracket inside a re-entered region is released where that region ends, before the next resumption" in {
            var events = List.empty[String]
            val v =
                ask.map { a =>
                    Bracket(Effect.defer(a)) { r =>
                        ask.map { b =>
                            events :+= s"use $r $b"
                            r + b
                        }
                    }((r, _) => events :+= s"release $r")
                }
            val twice =
                ArrowEffect.handleContRepeated(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(x => cont(20).map(y => x + y)),
                    a => a
                )
            assert(twice.eval == 120)
            assert(events == List("use 10 10", "use 10 20", "release 10", "use 20 10", "use 20 20", "release 20"))
        }
         */

        "a branch that throws after another ended tells the release it failed" in {
            var events = List.empty[String]
            val acquire: Int < Ask = Effect.defer {
                events :+= "acquire"
                1
            }
            val boom = new RuntimeException("boom")
            val v =
                Bracket(acquire) { r =>
                    ask.map { a =>
                        if a < 0 then throw boom else a + r
                    }
                }((r, outcome) => events :+= s"release $r ${outcome.exists(_ eq boom)}")
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(-1).map(b => a + b)),
                    a => a
                )
            assert(intercept[RuntimeException](twice.eval) eq boom)
            assert(events == List("acquire", "release 1 true"))
        }

        "nested brackets shared by a multi-shot clause release inner first at the handler's end" in {
            var events           = List.empty[String]
            val outer: Int < Ask = Effect.defer(1)
            val v =
                Bracket(outer) { o =>
                    Bracket(Effect.defer(2)) { i =>
                        ask.map { a =>
                            events :+= s"branch $a"
                            a + o + i
                        }
                    }((_, _) => events :+= "release inner")
                }((_, _) => events :+= "release outer")
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            assert(twice.eval == 36)
            assert(events == List("branch 10", "branch 20", "release inner", "release outer"))
        }

        "a handleFirst remainder carries the bracket it was handed: the first shot completes it, the second is refused" in {
            // The clause hands the continuation out as the region's value, so the remainder carries the bracket
            // dumped into it and releases it when it completes. One-shot: the second application finds the cell
            // released and is refused.
            var closed             = false
            var closedAtClause     = false
            var seen               = List.empty[String]
            val acquire: Int < Ask = Effect.defer(1)
            val v =
                Bracket(acquire) { r =>
                    ask.map { a =>
                        seen :+= (if closed then s"branch $a after release" else s"branch $a")
                        a + r
                    }
                }((_, _) => closed = true)
            val branches: Int < Ask =
                ArrowEffect.handleFirst(Tag[Ask], v)(
                    handle = [C] =>
                        (_, cont) =>
                            closedAtClause = closed
                            cont(10).map(a => cont(20).map(b => a + b))
                    ,
                    done = a => a
                )
            discard(intercept[kyo.Closed](answerAsk(0)(branches).eval))
            assert(!closedAtClause)
            assert(closed)
            assert(seen == List("branch 10"))
        }

        "a handleFirst remainder resumed once releases when it completes, before the scope below ends" in {
            var outcome     = Maybe.empty[Maybe[Throwable]]
            var closedAfter = false
            val v           = Bracket(Effect.defer(1))(r => ask.map(_ + r))((_, o) => outcome = Maybe(o))
            val first: Int < Ask =
                ArrowEffect.handleFirst(Tag[Ask], v)(
                    handle = [C] => (_, cont) => cont(10),
                    done = a => a
                )
            val r = answerAsk(0)(first.map { a =>
                closedAfter = outcome.isDefined
                a
            })
            assert(r.eval == 11)
            assert(closedAfter, "the bracket was still open after its remainder completed")
            assert(outcome.exists(_.isEmpty), s"the bracket saw $outcome")
        }
    }

    "release before recovery and fatal failures" - {
        "a release that throws on the completing path still runs the outer release before a recovery" in {
            var order    = List.empty[String]
            var outcomes = List.empty[Maybe[Throwable]]
            val boom     = new IllegalStateException("inner release")
            val v: Int < Any =
                recovering[Int](
                    Bracket(Effect.defer("outer")) { _ =>
                        Bracket(Effect.defer("inner"))(_ => 1)((_, _) => throw boom)
                    } { (_, outcome) =>
                        order :+= "outer release"
                        outcomes :+= outcome
                    }
                ) { _ =>
                    order :+= "recover"
                    -1
                }
            assert(v.eval == -1)
            assert(order == List("outer release", "recover"))
            assert(outcomes.exists(_.exists(_ eq boom)))
        }

        "a done transform that throws releases the bracket below it before a recovery" in {
            var order    = List.empty[String]
            var outcomes = List.empty[Maybe[Throwable]]
            val boom     = new RuntimeException("done")
            val v: Int < Any =
                recovering[Int](
                    Bracket(Effect.defer(1)) { r =>
                        ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + r))(
                            [C] => _ => Loop.continue(1),
                            _ => (throw boom): Int
                        )
                    } { (_, outcome) =>
                        order :+= "release"
                        outcomes :+= outcome
                    }
                ) { _ =>
                    order :+= "recover"
                    -1
                }
            assert(v.eval == -1)
            assert(order == List("release", "recover"))
            assert(outcomes.exists(_.exists(_ eq boom)))
        }

        "a fatal failure runs nested releases innermost first" in {
            var order = List.empty[String]
            val boom  = new OutOfMemoryError("fatal")
            val v: Int < Any =
                Bracket(Effect.defer(1)) { _ =>
                    Bracket(Effect.defer(2)) { _ =>
                        (throw boom): Int
                    }((_, _) => order :+= "inner")
                }((_, _) => order :+= "outer")
            assert(intercept[OutOfMemoryError](v.eval) eq boom)
            assert(order == List("inner", "outer"))
        }

        "a fatal failure thrown from a map after the acquire runs the release" in {
            var order = List.empty[String]
            val boom  = new OutOfMemoryError("fatal")
            val v: Int < Any =
                Bracket(Effect.defer(1)) { r =>
                    Effect.defer(r).map(_ => (throw boom): Int)
                }((_, _) => order :+= "release")
            assert(intercept[OutOfMemoryError](v.eval) eq boom)
            assert(order == List("release"))
        }

        "a fatal failure runs the release" in {
            var order = List.empty[String]
            val boom  = new OutOfMemoryError("fatal")
            val v: Int < Any =
                Bracket(Effect.defer(1))(_ => (throw boom): Int)((_, _) => order :+= "release")
            assert(intercept[OutOfMemoryError](v.eval) eq boom)
            assert(order == List("release"))
        }

        "a fatal failure runs the release and is not answered by a recovery" in {
            var order = List.empty[String]
            val boom  = new OutOfMemoryError("fatal")
            val v: Int < Any =
                recovering[Int](
                    Bracket(Effect.defer(1))(_ => (throw boom): Int)((_, _) => order :+= "release")
                ) { _ =>
                    order :+= "recover"
                    -1
                }
            assert(intercept[OutOfMemoryError](v.eval) eq boom)
            assert(order == List("release"))
        }
    }

    "ensuringWith" - {
        "makes a state for the run, hands it to the body, and releases with it" in {
            var seen = Maybe.empty[(AnyRef, Maybe[Throwable])]
            var got  = Maybe.empty[AnyRef]
            val v = Bracket.ensuringWith(new AnyRef)((s, outcome) => seen = Maybe((s, outcome))) { s =>
                Effect.defer {
                    got = Maybe(s)
                    1
                }
            }
            assert(v.eval == 1)
            assert(got.isDefined && seen.isDefined)
            assert(seen.get._1 eq got.get)
            assert(seen.get._2.isEmpty)
        }

        "each run makes a state of its own" in {
            val states = ListBuffer[AnyRef]()
            val v      = Bracket.ensuringWith(new AnyRef)((s, _) => discard(states += s))(_ => Effect.defer(1))
            assert(v.eval == 1)
            assert(v.eval == 1)
            assert(states.size == 2)
            assert(states(0) ne states(1))
        }

        "releases with the failure and the state when the body throws" in {
            var seen = Maybe.empty[(Int, Maybe[Throwable])]
            val v = Bracket.ensuringWith(7)((s, outcome) => seen = Maybe((s, outcome))) { _ =>
                Effect.defer((throw Boom): Int)
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(seen.exists((s, outcome) => s == 7 && outcome.exists(_ eq Boom)))
        }

        // The region is a node from the start, so a computation abandoned before it ran a single step still
        // owes the release, told a state made for that run which nothing ever wrote.
        "releases with a fresh state when abandoned before a step" in {
            var made = 0
            var seen = Maybe.empty[(Int, Maybe[Throwable])]
            var ran  = false
            val v = Bracket.ensuringWith {
                made += 1
                made
            }((s, outcome) => seen = Maybe((s, outcome))) { _ =>
                Effect.defer {
                    ran = true
                    1
                }
            }
            Eval.release(v, Boom)
            assert(!ran)
            assert(made == 1)
            assert(seen.exists((s, outcome) => s == 1 && outcome.exists(_ eq Boom)))
        }
    }

end BracketTest
