package kyo.kernel

import kyo.Arrow
import kyo.Closed
import kyo.Const
import kyo.Loop
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Tag
import kyo.discard
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Pending
import kyo.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

class EffectBracketTest extends AnyFreeSpec:

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
            val v = Effect.bracket(Effect.defer { log += "open"; 42 }) { (a, outcome) =>
                discard(log += s"close $a ${outcome.isEmpty}")
            } { a =>
                Effect.defer { log += "use"; a + 1 }
            }
            assert(v.eval == 43)
            assert(log.toList == List("open", "use", "close 42 true"))
        }

        "a pure use completes the bracket through the settled fast path" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer(1))((_, _) => count += 1)(a => a + 1)
            assert(v.eval == 2)
            assert(count == 1)
        }

        "a use that throws during application still releases" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { _ =>
                (throw Boom): Int < Any
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "releases exactly once" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer(1))((_, _) => count += 1)(a => Effect.defer(a))
            assert(v.eval == 1)
            assert(count == 1)
        }

        "releases with the failure when the use throws" in {
            var seen = Maybe.empty[Throwable]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = outcome) { _ =>
                Effect.defer((throw Boom): Int)
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(seen.exists(_ eq Boom))
        }

        "releases when a parked remainder is abandoned" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(seen.isEmpty)
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "a resumed parked bracket completes and releases with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(parked.eval == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "nested brackets release innermost first on failure" in {
            val log = ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner")) { _ =>
                    Effect.defer((throw Boom): Int)
                }
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(log.toList == List("inner", "outer"))
        }

        "a stop landing as the acquire settles still installs the region" in {
            var count = 0
            val v = Effect.bracket(Effect.defer {
                requestStop()
                7
            })((_, _) => count += 1)(a => Effect.defer(a + 1))
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(count == 1)
            discard(intercept[kyo.Closed](parked.eval))
            assert(count == 1)
        }

        "a foreign loop handler answering in place keeps the bracket live until the use completes" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val log  = ListBuffer[String]()
            val body: Int < (Ask & Str) =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    str(1).map { s =>
                        discard(log += s"use $s ${seen.isEmpty}")
                        ask.map(x => a + x)
                    }
                }
            val inner: Int < Str = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue((), 1: Int < Any), b => b)
            val r: Int < Any =
                ArrowEffect.handleLoop(Tag[Str], inner)([C] => n => Loop.continue((), s"s$n": String < Any), b => b)
            assert(r.eval == 8)
            assert(log.toList == List("use s1 true"))
            assert(seen == Maybe(Maybe.empty))
        }

        "a loop clause answering done releases a bracket opened inside" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(-1), b => b)
            assert(r.eval == -1)
            assert(seen.exists(_.isDefined))
        }

        "the acquire is not guarded before it settles" in {
            var count = 0
            val v = Effect.bracket(Effect.defer {
                requestStop()
                Effect.defer(7)
            })((_, _) => count += 1)(a => Effect.defer(a))
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending[?, ?]])
            Eval.release(parked, Boom)
            assert(count == 0)
            assert(parked.eval == 7)
            assert(count == 1)
        }
    }

    "captured continuations" - {
        "a discarded captured continuation still releases the bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val dropped: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
            assert(dropped.eval == -1)
            assert(seen.exists(_.isDefined))
        }

        "a captured continuation resumed in the clause completes the bracket there" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val resumed: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), b => b)
            assert(resumed.eval == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "a park after a crossing resume still owes the bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map { x =>
                        requestStop()
                        Effect.defer(a + x)
                    }
                }
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
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Effect.defer(Loop.continue((), 1: Int < Ask)),
                b => b
            )
            assert(r.eval == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "an effectful loop clause answering done releases through the eval root" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Effect.defer(Loop.done(-1)),
                b => b
            )
            assert(r.eval == -1)
            assert(seen.exists(_.isDefined))
        }

        "a clause that throws after capturing releases the bracket with the failure" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => (throw Boom): Int, b => b)
            val ex = intercept[RuntimeException](r.eval)
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "a leaked capture resumed after its region completed is refused as closed" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            var leaked   = Maybe.empty[Arrow[Int, Int, Ask]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => discard(outcomes += outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        leaked = Maybe(cont)
                        -1
                ,
                b => b
            )
            assert(r.eval == -1)
            assert(outcomes.size == 1)
            assert(outcomes.head.isDefined)
            discard(intercept[kyo.Closed](answerAsk(0)(leaked.get(1)).eval))
            assert(outcomes.size == 1)
        }

        "a throwing release on the unwind does not starve the ones after it" in {
            object Bad extends RuntimeException("bad", null, false, false)
            val failure = new RuntimeException("failure")
            val log     = ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2)) { (_, _) =>
                    discard(log += "inner")
                    throw Bad
                } { _ =>
                    Effect.defer((throw failure): Int)
                }
            }
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
            val v = Effect.bracket(Effect.defer(1))((_, outcome) => outerSeen = Maybe(outcome)) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => throw Bad)(b => Effect.defer(b))
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Bad)
            assert(outerSeen.exists(_.exists(_ eq Bad)))
        }

        "a release failure on the unwind is suppressed onto the failure" in {
            val failure = new RuntimeException("failure")
            object Bad extends RuntimeException("bad", null, false, false)
            val v = Effect.bracket(Effect.defer(1))((_, _) => throw Bad) { _ =>
                Effect.defer((throw failure): Int)
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq failure)
            assert(ex.getSuppressed.exists(_ eq Bad))
        }

        "a release failure on abandonment is suppressed onto the holder's signal" in {
            val signal = new RuntimeException("signal")
            object Bad extends RuntimeException("bad", null, false, false)
            val v = Effect.bracket(Effect.defer(1))((_, _) => throw Bad) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }
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
                Effect.bracket(Effect.defer(1))((_, _) => discard(log += "bracket")) { a =>
                    ContextEffect.handle(Tag[Cfg])(
                        (_: Maybe[Int]).getOrElse(0),
                        fork = (parent: Int) => parent,
                        join = (parent: Int, _: Int, _: Int) => parent,
                        release = (_: Int, _: Throwable) => discard(log += "binding")
                    )(ask.map(x => a + x))
                }
            val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
            assert(dropped.eval == -1)
            assert(log.toList == List("binding", "bracket"))
        }

        "a multi-shot capture over a bracket refuses the second shot" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => discard(outcomes += outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 100 + y)),
                b => b
            )
            discard(intercept[kyo.Closed](r.eval))
            assert(outcomes.toList == List(Maybe.empty))
        }

        "a bracket held across two parks releases once on completion" in {
            var count = 0
            var seen  = Maybe.empty[Maybe[Throwable]]
            val v = Effect.bracket(Effect.defer(7)) { (_, outcome) =>
                count += 1
                seen = Maybe(outcome)
            } { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer {
                        requestStop()
                        Effect.defer(a + 1)
                    }
                }
            }
            val p1 = Eval.partial(v)
            assert(p1.isInstanceOf[Pending.Park[?, ?]])
            val p2 = Eval.partial(p1)
            assert(p2.isInstanceOf[Pending.Park[?, ?]])
            assert(p2.eval == 8)
            assert(count == 1)
            assert(seen == Maybe(Maybe.empty))
        }

        "a contextual isolate inside a bracket forks an inert obligation" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => discard(outcomes += outcome)) { a =>
                Isolate.internal.Contextual.run(Effect.defer(a + 1)).map(_ + 1)
            }
            assert(v.eval == 9)
            assert(outcomes.toList == List(Maybe.empty))
        }

        "a failing acquire never releases" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer((throw Boom): Int))((_, _) => count += 1)(a => Effect.defer(a))
            val ex    = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(count == 0)
        }

        "a failing inner acquire releases the outer bracket only" in {
            val log = ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer((throw Boom): Int))((_, _) => discard(log += "inner"))(b => Effect.defer(b))
            }
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(log.toList == List("outer"))
        }

        "a bracket under a recovering handler releases with the failure, before the recovery" in {
            var seen  = Maybe.empty[Maybe[Throwable]]
            val order = ListBuffer[String]()
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7)) { (_, outcome) =>
                    seen = Maybe(outcome)
                    discard(order += "release")
                }(_ => Effect.defer((throw Boom): Int))
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
                Effect.bracket(Effect.defer(7)) { (_, outcome) =>
                    seen = Maybe(outcome)
                    discard(order += "release")
                }(_ => ask.map(_ => (throw Boom): Int))
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
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner")) { b =>
                    Effect.defer {
                        requestStop()
                        Effect.defer(b + 1)
                    }
                }
            }
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(log.toList == List("inner", "outer"))
        }

        "a release that throws on completion runs exactly once" in {
            object Bad extends RuntimeException("bad", null, false, false)
            var count = 0
            val v = Effect.bracket(Effect.defer(1)) { (_, _) =>
                count += 1
                throw Bad
            }(a => Effect.defer(a))
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Bad)
            assert(count == 1)
        }

        "a release that throws after a pure use runs exactly once" in {
            object Bad extends RuntimeException("bad", null, false, false)
            var count = 0
            val v = Effect.bracket(Effect.defer(1)) { (_, _) =>
                count += 1
                throw Bad
            }(a => a + 1)
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Bad)
            assert(count == 1)
        }

        "nested brackets complete innermost first" in {
            val log = ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner"))(b => Effect.defer(b))
            }
            assert(v.eval == 2)
            assert(log.toList == List("inner", "outer"))
        }

        "a bracket releases before the next one acquires" in {
            val log = ListBuffer[String]()
            def one(name: String): Int < Any =
                Effect.bracket(Effect.defer {
                    discard(log += s"acquire $name")
                    name
                })((_, _) => discard(log += s"release $name"))(_ => Effect.defer(1))
            val v = one("a").map(_ => one("b"))
            assert(v.eval == 1)
            assert(log.toList == List("acquire a", "release a", "acquire b", "release b"))
        }

        "a bracket owned by an isolated child releases at the child's completion" in {
            val outcomes = ListBuffer[Maybe[Throwable]]()
            val v = Isolate.internal.Contextual.run(
                Effect.bracket(Effect.defer(7))((_, outcome) => discard(outcomes += outcome))(a => Effect.defer(a + 1))
            ).map(_ + 1)
            assert(v.eval == 9)
            assert(outcomes.toList == List(Maybe.empty))
        }

        "a failure inside an isolated child releases the child's bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Isolate.internal.Contextual.run(
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome))(_ => Effect.defer((throw Boom): Int))
            )
            val ex = intercept[RuntimeException](v.eval)
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "abandoning an isolated child releases its bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Isolate.internal.Contextual.run(
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    Effect.defer {
                        requestStop()
                        Effect.defer(a + 1)
                    }
                }
            )
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending.Park[?, ?]])
            assert(seen.isEmpty)
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "a discarded capture's bracket is told the discard signal" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome))(a => ask.map(x => a + x))
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
        "a leaked capture's refusal is recoverable by the region that resumes it" in {
            var leaked          = Maybe.empty[Arrow[Int, Int, Ask]]
            val body: Int < Ask = Effect.bracket(Effect.defer(7))((_, _) => ())(a => ask.map(_ + a))
            val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        leaked = Maybe(cont)
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

        "a capture inside an isolated child, resumed after the bracket ended, is refused" in {
            var leaked    = Maybe.empty[Arrow[Int, Int, Ask]]
            var released  = false
            var usedAfter = false
            val body: Int < Any =
                Effect.bracket(Effect.defer(7))((_, _) => released = true) { a =>
                    ArrowEffect.handleCont(
                        Tag[Ask],
                        Isolate.internal.Contextual.run(ask.map { n =>
                            usedAfter = released
                            n + a
                        })
                    )(
                        [C] =>
                            (_, cont) =>
                                leaked = Maybe(cont)
                                -1
                        ,
                        b => b
                    )
                }
            assert(body.eval == -1)
            assert(released)
            val again: Int < Any =
                ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], leaked.get(1))(
                    [C] => (_, cont) => cont(0),
                    b => b,
                    ex => Maybe(if ex.isInstanceOf[Closed] then -2 else -3)
                )
            assert(again.eval == -2)
            assert(!usedAfter)
        }

        "a bracket outside the answering handler is not carried by an escaped continuation, so the remainder runs after the release" in {
            var stash = Maybe.empty[Arrow[Int, Int, Ask]]
            val log   = ListBuffer[String]()
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, _) => discard(log += "release")) { r =>
                    ArrowEffect.handleCont(
                        Tag[Ask],
                        ask.map { a =>
                            log += s"use $r"
                            a + r
                        }
                    )(
                        [C] =>
                            (_, cont) =>
                                stash = Maybe(cont)
                                -1
                        ,
                        a => a
                    )
                }
            assert(v.eval == -1)
            assert(log.toList == List("release"))
            assert(answerAsk(0)(stash.get(41)).eval == 42)
            assert(log.toList == List("release", "use 1"))
        }

        "a park taken in a clause before it resumes a crossing carries the crossed bracket" in {
            def program(seen: ListBuffer[Maybe[Throwable]]): Int < Any =
                val body: Int < Ask =
                    Effect.bracket(Effect.defer(7))((_, outcome) => discard(seen += outcome))(a => ask.map(_ + a))
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
    }

    "unit acquire" - {
        "runs after completion with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Effect.bracket(())((_, outcome) => seen = Maybe(outcome))(_ => Effect.defer(5))
            assert(v.eval == 5)
            assert(seen == Maybe(Maybe.empty))
        }

        "runs with the failure on unwind" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Effect.bracket(())((_, outcome) => seen = Maybe(outcome))(_ => Effect.defer((throw Boom): Int))
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
            val v        = Effect.bracket(Effect.defer(1))((r, _) => released = Maybe(r))(r => ask.map(_ + r))
            val stopped  = ArrowEffect.handleLoopState(Tag[Ask], 0, v)([C] => (_, _) => Loop.done(-1), (_, a) => a)
            assert(stopped.eval == -1)
            assert(released == Maybe(1))
        }

        "a stateful clause that stops after advancing still releases" in {
            var released = Maybe.empty[Int]
            val v        = Effect.bracket(Effect.defer(1))((r, _) => released = Maybe(r))(r => ask.map(a => ask.map(b => a + b + r)))
            val stopped =
                ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                    [C] => (s, _) => if s == 1 then Loop.done(-1) else Loop.continue(s + 1, 1: Int < Any),
                    (_, a) => a
                )
            assert(stopped.eval == -1)
            assert(released == Maybe(1))
        }

        "every outstanding bracket releases when a clause stops the computation" in {
            var released = List.empty[String]
            val v =
                Effect.bracket(Effect.defer("outer"))((r, _) => released :+= r) { _ =>
                    Effect.bracket(Effect.defer("inner"))((r, _) => released :+= r) { _ =>
                        ask.map(_ + 1)
                    }
                }
            val stopped = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a)
            assert(stopped.eval == -1)
            assert(released == List("inner", "outer"))
        }

        "a discarded continuation releases every outstanding bracket" in {
            var released = List.empty[String]
            val v =
                Effect.bracket(Effect.defer("outer"))((r, _) => released :+= r) { _ =>
                    Effect.bracket(Effect.defer("inner"))((r, _) => released :+= r) { _ =>
                        ask.map(_ + 1)
                    }
                }
            val dropped = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(dropped.eval == -1)
            assert(released == List("inner", "outer"))
        }

        "does not release when the acquire never completes" in {
            var released = false
            val v =
                Effect.bracket(ask.map(_ => 1))((_, _) => released = true)(r => r + 1)
            val never = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(never.eval == -1)
            assert(!released)
        }
    }

    "parks, budget, and stack safety" - {
        "an acquire resumed twice owes a release for each resume" in {
            var released = List.empty[Int]
            val v        = Effect.bracket(ask)((r, _) => released :+= r)(r => r * 10)
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
            val v = Effect.bracket(Effect.defer(0))((_, _) => count += 1)(r => chain(1000, r))
            assert(v.eval == 1000)
            assert(count == 1)
        }

        "a slice stopped inside a bracket releases once, at the end" in {
            var released = 0
            val v: Int < Any =
                Effect.bracket(Effect.defer {
                    requestStop()
                    1
                })((_, _) => released += 1)(r =>
                    Effect.defer {
                        requestStop()
                        r + 1
                    }.map(_ + 1)
                )

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
                else Effect.bracket(Effect.defer(n))((_, _) => count += 1)(_ => nest(n - 1))
            assert(nest(1000).eval == 0)
            assert(count == 1000)
        }

        "many sequential brackets each release" in {
            var count = 0
            def loop(n: Int, acc: Int < Any): Int < Any =
                if n == 0 then acc
                else loop(n - 1, acc.map(a => Effect.bracket(Effect.defer(1))((_, _) => count += 1)(r => a + r)))
            assert(loop(1000, 0: Int < Any).eval == 1000)
            assert(count == 1000)
        }

        "a use that fails after a resume still releases once with the failure" in {
            var released = 0
            var seen     = Maybe.empty[Maybe[Throwable]]
            val boom     = new RuntimeException("late")
            val v: Int < Any =
                Effect.bracket(Effect.defer(1)) { (_, outcome) =>
                    released += 1
                    seen = Maybe(outcome)
                } { r =>
                    Effect.defer {
                        requestStop()
                        r
                    }.map(x => if x == 1 then throw boom else x)
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
                Effect.bracket(Effect.defer(1))((_, _) => released :+= "outer") { a =>
                    Effect.bracket(Effect.defer(2))((_, _) => released :+= "inner") { b =>
                        Effect.defer {
                            requestStop()
                            a + b
                        }.map(_ + 39)
                    }
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(released.isEmpty)
            assert(p.eval == 42)
            assert(released == List("inner", "outer"))
        }

        "a park evaluated twice refuses the second evaluation after release" in {
            var released = 0
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, _) => released += 1)(r =>
                    Effect.defer {
                        requestStop()
                        r
                    }.map(_ + 41)
                )
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(p.eval == 42)
            discard(intercept[kyo.Closed](p.eval))
            assert(released == 1)
        }
    }

    "nesting and composition" - {
        "a bracket inside a nested eval releases at that eval's boundary" in {
            var events = List.empty[String]
            val inner  = Effect.bracket(Effect.defer(1))((_, _) => events :+= "inner release")(r => r + 1)
            val outer =
                Effect.bracket(Effect.defer(2))((_, _) => events :+= "outer release") { r =>
                    val got = inner.eval
                    events :+= s"inner = $got"
                    r
                }
            assert(outer.eval == 2)
            assert(events == List("inner release", "inner = 2", "outer release"))
        }

        "an acquire that is itself a bracket releases both" in {
            var released = List.empty[String]
            val acquire  = Effect.bracket(Effect.defer("a"))((r, _) => released :+= r)(r => r + "!")
            val v        = Effect.bracket(acquire)((r, _) => released :+= r)(r => r.length)
            assert(v.eval == 2)
            assert(released == List("a", "a!"))
        }

        "nested brackets separated by a handler still release innermost first on a failure" in {
            var order = List.empty[String]
            val boom  = new RuntimeException("boom")
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, _) => order :+= "outer") { _ =>
                    answerAsk(1) {
                        Effect.bracket(Effect.defer(2))((_, _) => order :+= "inner") { i =>
                            Effect.defer(i).map(_ => (throw boom): Int)
                        }
                    }
                }
            assert(intercept[RuntimeException](v.eval) eq boom)
            assert(order == List("inner", "outer"))
        }
    }

    "acquire and release failure edges" - {
        "a release that throws during unwind does not lose the failure or the recovery" in {
            var seen       = List.empty[String]
            var suppressed = List.empty[String]
            val v: Int < Any = recovering[Int](
                Effect.bracket(Effect.defer(1))((_, _) => throw new IllegalStateException("release")) { _ =>
                    (throw new UnsupportedOperationException("body")): Int
                }
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
            val v: Int < Any = Effect.bracket(Effect.defer(1))((_, outcome) => out = Maybe(outcome)) { _ =>
                ArrowEffect.handleCont(Tag[Ask], ask.map(_ => (throw original): Int))(
                    [C] => (_, cont) => cont(0),
                    a => a,
                    _ => Maybe((throw fromRecovery): Int)
                )
            }
            val ex = intercept[IllegalStateException](v.eval)
            assert(ex eq fromRecovery)
            assert(out.exists(_.exists(_ eq fromRecovery)))
        }

        "an effectful acquire whose handler fails owes no release" in {
            var released = 0
            val boom     = new RuntimeException("clause")
            val v: Int < Str =
                Effect.bracket(str(1))((_, _) => released += 1)(r => r.length)
            val handled: Int < Any =
                ArrowEffect.handleCont(Tag[Str], v)([C] => (_, _) => throw boom, a => a)
            assert(intercept[RuntimeException](handled.eval) eq boom)
            assert(released == 0)
        }

        "an interior recovery turns the release outcome into the recovered success" in {
            var out = Maybe.empty[Maybe[Throwable]]
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, outcome) => out = Maybe(outcome)) { a =>
                    ArrowEffect.handleCont(Tag[Ask], ask.map(_ => (throw new RuntimeException("use")): Int))(
                        [C] => (_, cont) => cont(0),
                        a2 => a2,
                        _ => Maybe(a + 41)
                    )
                }
            assert(v.eval == 42)
            assert(out == Maybe(Maybe.empty))
        }

        "a failing use with an effectful acquire still releases with the failure" in {
            var out  = Maybe.empty[Maybe[Throwable]]
            val boom = new RuntimeException("use")
            val v: String < Str =
                Effect.bracket(str(10))((_, outcome) => out = Maybe(outcome)) { a =>
                    if a == "10" then throw boom else a
                }
            val handled: String < Any =
                ArrowEffect.handleCont(Tag[Str], v)([C] => (input, cont) => cont(input.toString))
            assert(intercept[RuntimeException](handled.eval) eq boom)
            assert(out.exists(_.exists(_ eq boom)))
        }

        "a release that throws during abandonment does not silence the others" in {
            var order = List.empty[String]
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, _) => order :+= "outer") { a =>
                    Effect.bracket(Effect.defer(2)) { (_, _) =>
                        order :+= "inner"
                        throw new IllegalStateException("inner-release")
                    } { b =>
                        Effect.defer {
                            requestStop()
                            a + b
                        }.map(_ + 39)
                    }
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            Eval.release(p, Boom)
            assert(order == List("inner", "outer"))
        }

        "every release runs even when several throw" in {
            var released = List.empty[String]
            def level(name: String, failing: Boolean)(inner: Int < Ask): Int < Ask =
                Effect.bracket(Effect.defer(name))((r, _) =>
                    if failing then throw new IllegalStateException(s"$r release")
                    else released :+= r
                )(_ => inner)
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
            val v        = Effect.bracket(Effect.defer("res"))((_, _) => released = true)(r => r)
            assert(v.eval == "res")
            assert(released)
        }

        "a use that ignores the resource still releases it" in {
            var released = Maybe.empty[Int]
            val v        = Effect.bracket(Effect.defer(1))((r, _) => released = Maybe(r))(_ => "done")
            assert(v.eval == "done")
            assert(released == Maybe(1))
        }
    }

    "bracket vs regions" - {
        "a bracket interleaved with a region releases after the region completes" in {
            var events = List.empty[String]
            val v =
                Effect.bracket(Effect.defer(1))((r, _) => events :+= s"release $r") { r =>
                    answerAsk(41)(ask.map { a =>
                        events :+= "region answered"
                        a + r
                    })
                }
            assert(v.eval == 42)
            assert(events == List("region answered", "release 1"))
        }

        "a region installed inside the use does not intercept the release" in {
            var events = List.empty[String]
            val v =
                Effect.bracket(Effect.defer(1))((r, _) => events :+= s"release $r") { r =>
                    ArrowEffect.handleCont(Tag[Ask], ask.map(_ + r))(
                        [C] =>
                            (_, _) =>
                                events :+= "clause stopped"
                                -1
                        ,
                        a => a
                    )
                }
            assert(v.eval == -1)
            assert(events == List("clause stopped", "release 1"))
        }

        "a discarded continuation releases when its region completes, before the handler's continuation" in {
            var events = List.empty[String]
            val acquire: Int < Ask = Effect.defer {
                events :+= "acquire"
                1
            }
            val v       = Effect.bracket(acquire)((r, _) => events :+= s"release $r")(r => ask.map(_ + r))
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
                            Effect.bracket(Effect.defer {
                                events :+= "clause acquire"
                                41
                            })((_, _) => events :+= "clause release")(r => cont(r)),
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
                Effect.bracket(Effect.defer {
                    events :+= "acquire"
                    1
                })((r, _) => events :+= s"release $r") { r =>
                    ArrowEffect.handleCont(Tag[Ask], ask.map(_ + r))([C] => (_, _) => -1, a => a).map { a =>
                        events :+= s"inner done $a"
                        a + 100
                    }
                }
            assert(v.eval == 99)
            assert(events == List("acquire", "inner done -1", "release 1"))
        }
    }

    "multi-shot clauses" - {
        "a multi-shot clause that acquires per branch releases each where its branch ends" in {
            var events = List.empty[String]
            val v =
                Effect.bracket(ask)((r, _) => events :+= s"release $r") { r =>
                    events :+= s"use $r"
                    r * 10
                }
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
                Effect.bracket(acquire)((r, _) => events :+= s"release $r") { r =>
                    ask.map { a =>
                        if a < 0 then throw boom else a + r
                    }
                }
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(-1).map(b => a + b)),
                    a => a
                )
            val failure = intercept[kyo.Closed](twice.eval)
            assert(!failure.getSuppressed.contains(boom))
            assert(events == List("acquire", "release 1"))
        }

        "no branch of a multi-shot clause reads a resource that was already released" in {
            var closed             = false
            var seen               = List.empty[String]
            val acquire: Int < Ask = Effect.defer(1)
            val v =
                Effect.bracket(acquire)((_, _) => closed = true) { r =>
                    ask.map { a =>
                        seen :+= (if closed then s"branch $a after release" else s"branch $a")
                        a + r
                    }
                }
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            discard(intercept[kyo.Closed](twice.eval))
            assert(seen == List("branch 10"))
        }

        "a Choice-shaped clause is refused at its second branch" in {
            var closed             = false
            var seen               = List.empty[String]
            val acquire: Int < Ask = Effect.defer(100)
            val v =
                Effect.bracket(acquire)((_, _) => closed = true) { r =>
                    ask.map { a =>
                        seen :+= (if closed then s"branch $a after release" else s"branch $a")
                        a + r
                    }
                }
            val branches =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(1).map(a => cont(2).map(b => cont(3).map(c => a + b + c))),
                    a => a
                )
            discard(intercept[kyo.Closed](branches.eval))
            assert(seen == List("branch 1"))
        }

        "nested brackets shared by a multi-shot clause are refused at the second branch" in {
            var events           = List.empty[String]
            val outer: Int < Ask = Effect.defer(1)
            val v =
                Effect.bracket(outer)((_, _) => events :+= "release outer") { o =>
                    Effect.bracket(Effect.defer(2))((_, _) => events :+= "release inner") { i =>
                        ask.map { a =>
                            events :+= s"branch $a"
                            a + o + i
                        }
                    }
                }
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            discard(intercept[kyo.Closed](twice.eval))
            assert(events == List("branch 10", "release inner", "release outer"))
        }

        "a continuation held past the end of the eval refuses every time it is applied" in {
            var count = 0
            var stash = Maybe.empty[Arrow[Int, Int, Ask & Any]]
            val v     = Effect.bracket(Effect.defer(1))((_, _) => count += 1)(r => ask.map(_ + r))
            val dropped =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            stash = Maybe(cont)
                            -1
                    ,
                    a => a
                )
            assert(dropped.eval == -1)
            assert(count == 1)
            discard(intercept[kyo.Closed](answerAsk(0)(stash.get(2)).eval))
            discard(intercept[kyo.Closed](answerAsk(0)(stash.get(5)).eval))
            assert(count == 1)
        }

        "a handleFirst clause runs before the release its remainder runs after" in {
            var closed             = false
            var closedAtClause     = false
            var seen               = List.empty[String]
            val acquire: Int < Ask = Effect.defer(1)
            val v =
                Effect.bracket(acquire)((_, _) => closed = true) { r =>
                    ask.map { a =>
                        seen :+= (if closed then s"branch $a after release" else s"branch $a")
                        a + r
                    }
                }
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
            assert(seen == List())
        }

        "branches of a multi-shot clause share the resource the use closed over" in {
            var acquired = 0
            var seen     = List.empty[Int]
            val acquire: Int < Ask = Effect.defer {
                acquired += 1
                acquired
            }
            val v =
                Effect.bracket(acquire)((_, _) => ()) { r =>
                    ask.map { a =>
                        seen :+= r
                        a + r
                    }
                }
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            discard(intercept[kyo.Closed](twice.eval))
            assert(acquired == 1)
            assert(seen == List(1))
        }

        "a branch built inside a clause and evaluated later is refused" in {
            var closed = false
            var seen   = List.empty[String]
            var stash  = Maybe.empty[Int < Ask]
            val v =
                Effect.bracket(Effect.defer(1))((_, _) => closed = true) { r =>
                    ask.map { a =>
                        seen :+= (if closed then s"branch $a after release" else s"branch $a")
                        a + r
                    }
                }
            val built =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            stash = Maybe(cont(10))
                            -1
                    ,
                    a => a
                )
            assert(built.eval == -1)
            assert(closed)
            discard(intercept[kyo.Closed](answerAsk(0)(stash.get).eval))
            assert(seen == Nil)
        }

        "a bracket that encloses a multi-shot region releases after every branch" in {
            var closed = false
            var seen   = List.empty[String]
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, _) => closed = true) { r =>
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
                }
            assert(v.eval == 32)
            assert(seen == List("branch 10", "branch 20"))
            assert(closed)
        }
    }

    "release before recovery and fatal failures" - {
        "a release that throws on the completing path still runs the outer release before a recovery" in {
            var order    = List.empty[String]
            var outcomes = List.empty[Maybe[Throwable]]
            val boom     = new IllegalStateException("inner release")
            val v: Int < Any =
                recovering[Int](
                    Effect.bracket(Effect.defer("outer")) { (_, outcome) =>
                        order :+= "outer release"
                        outcomes :+= outcome
                    } { _ =>
                        Effect.bracket(Effect.defer("inner"))((_, _) => throw boom)(_ => 1)
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
                    Effect.bracket(Effect.defer(1)) { (_, outcome) =>
                        order :+= "release"
                        outcomes :+= outcome
                    } { r =>
                        ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + r))(
                            [C] => _ => Loop.continue((), 1: Int < Any),
                            _ => (throw boom): Int
                        )
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
            val boom  = new InterruptedException("fatal")
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, _) => order :+= "outer") { _ =>
                    Effect.bracket(Effect.defer(2))((_, _) => order :+= "inner") { _ =>
                        (throw boom): Int
                    }
                }
            assert(intercept[InterruptedException](v.eval) eq boom)
            assert(order == List("inner", "outer"))
        }

        "a fatal failure thrown from a map after the acquire runs the release" in {
            var order = List.empty[String]
            val boom  = new InterruptedException("fatal")
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, _) => order :+= "release") { r =>
                    Effect.defer(r).map(_ => (throw boom): Int)
                }
            assert(intercept[InterruptedException](v.eval) eq boom)
            assert(order == List("release"))
        }

        "a fatal failure runs the release" in {
            var order = List.empty[String]
            val boom  = new InterruptedException("fatal")
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, _) => order :+= "release")(_ => (throw boom): Int)
            assert(intercept[InterruptedException](v.eval) eq boom)
            assert(order == List("release"))
        }

        "a fatal failure runs the release and is not answered by a recovery" in {
            var order = List.empty[String]
            val boom  = new InterruptedException("fatal")
            val v: Int < Any =
                recovering[Int](
                    Effect.bracket(Effect.defer(1))((_, _) => order :+= "release")(_ => (throw boom): Int)
                ) { _ =>
                    order :+= "recover"
                    -1
                }
            assert(intercept[InterruptedException](v.eval) eq boom)
            assert(order == List("release"))
        }
    }

end EffectBracketTest
