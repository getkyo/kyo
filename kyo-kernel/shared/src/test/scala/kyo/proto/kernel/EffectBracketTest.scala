package kyo.proto.kernel

import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec

class EffectBracketTest extends AnyFreeSpec:

    private def eval[A, S](v: A < S): A =
        Nested.unnest[A](Eval(v))

    private def requestStop(): Unit =
        discard(Safepoint.get())
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop

    private object Boom extends RuntimeException("boom", null, false, false)

    sealed trait Ask extends ArrowEffect[kyo.Const[Unit], kyo.Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    "bracket" - {
        "releases with Absent on completion, after use" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Effect.bracket(Effect.defer { log += "open"; 42 }) { (a, outcome) =>
                discard(log += s"close $a ${outcome.isEmpty}")
            } { a =>
                Effect.defer { log += "use"; a + 1 }
            }
            assert(eval(v) == 43)
            assert(log.toList == List("open", "use", "close 42 true"))
        }

        "a pure use completes the bracket through the settled fast path" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer(1))((_, _) => count += 1)(a => a + 1)
            assert(eval(v) == 2)
            assert(count == 1)
        }

        "a use that throws during application still releases" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { _ =>
                (throw Boom): Int < Any
            }
            val ex = intercept[RuntimeException](eval(v))
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "releases exactly once" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer(1))((_, _) => count += 1)(a => Effect.defer(a))
            assert(eval(v) == 1)
            assert(count == 1)
        }

        "releases with the failure when the use throws" in {
            var seen = Maybe.empty[Throwable]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = outcome) { _ =>
                Effect.defer((throw Boom): Int)
            }
            val ex = intercept[RuntimeException](eval(v))
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
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
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
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(eval(parked) == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "nested brackets release innermost first on failure" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner")) { _ =>
                    Effect.defer((throw Boom): Int)
                }
            }
            val ex = intercept[RuntimeException](eval(v))
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
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(count == 1)
            discard(intercept[kyo.Closed](eval(parked)))
            assert(count == 1)
        }

        "a loop clause answering done releases a bracket opened inside" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => kyo.proto.Loop.done(-1), b => b)
            assert(eval(r) == -1)
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
            assert(eval(parked) == 7)
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
            assert(eval(dropped) == -1)
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
            assert(eval(resumed) == 8)
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
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
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
                [C] => _ => Effect.defer(kyo.proto.Loop.continue((), 1: Int < Ask)),
                b => b
            )
            assert(eval(r) == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "an effectful loop clause answering done releases through the eval root" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Effect.defer(kyo.proto.Loop.done(-1)),
                b => b
            )
            assert(eval(r) == -1)
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
            val ex = intercept[RuntimeException](eval(r))
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "a leaked capture resumed after its region completed is refused as closed" in {
            val outcomes = collection.mutable.ListBuffer[Maybe[Throwable]]()
            var leaked   = Maybe.empty[kyo.proto.Arrow[Int, Int, Ask]]
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
            assert(eval(r) == -1)
            assert(outcomes.size == 1)
            assert(outcomes.head.isDefined)
            discard(intercept[kyo.Closed](eval(leaked.get(1))))
            assert(outcomes.size == 1)
        }

        "a throwing release on the unwind does not starve the ones after it" in {
            object Bad extends RuntimeException("bad", null, false, false)
            val failure = new RuntimeException("failure")
            val log     = collection.mutable.ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2)) { (_, _) =>
                    discard(log += "inner")
                    throw Bad
                } { _ =>
                    Effect.defer((throw failure): Int)
                }
            }
            val ex = intercept[RuntimeException](eval(v))
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
            val ex = intercept[RuntimeException](eval(v))
            assert(ex eq Bad)
            assert(outerSeen.exists(_.exists(_ eq Bad)))
        }

        "a release failure on the unwind is suppressed onto the failure" in {
            val failure = new RuntimeException("failure")
            object Bad extends RuntimeException("bad", null, false, false)
            val v = Effect.bracket(Effect.defer(1))((_, _) => throw Bad) { _ =>
                Effect.defer((throw failure): Int)
            }
            val ex = intercept[RuntimeException](eval(v))
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
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            Eval.release(parked, signal)
            assert(signal.getSuppressed.exists(_ eq Bad))
        }
    }

    "mixed with other kernel features" - {
        "a bracket and a binding dumped together release inner first on discard" in {
            val log = collection.mutable.ListBuffer[String]()
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
            assert(eval(dropped) == -1)
            assert(log.toList == List("binding", "bracket"))
        }

        "a multi-shot capture over a bracket refuses the second shot" in {
            val outcomes = collection.mutable.ListBuffer[Maybe[Throwable]]()
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => discard(outcomes += outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 100 + y)),
                b => b
            )
            discard(intercept[kyo.Closed](eval(r)))
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
            assert(p1.isInstanceOf[Kyo.Park[?, ?]])
            val p2 = Eval.partial(p1)
            assert(p2.isInstanceOf[Kyo.Park[?, ?]])
            assert(eval(p2) == 8)
            assert(count == 1)
            assert(seen == Maybe(Maybe.empty))
        }

        "a contextual isolate inside a bracket forks an inert obligation" in {
            val outcomes = collection.mutable.ListBuffer[Maybe[Throwable]]()
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => discard(outcomes += outcome)) { a =>
                Isolate.internal.Contextual.run(Effect.defer(a + 1)).map(_ + 1)
            }
            assert(eval(v) == 9)
            assert(outcomes.toList == List(Maybe.empty))
        }

        "a failing acquire never releases" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer((throw Boom): Int))((_, _) => count += 1)(a => Effect.defer(a))
            val ex    = intercept[RuntimeException](eval(v))
            assert(ex eq Boom)
            assert(count == 0)
        }

        "a failing inner acquire releases the outer bracket only" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer((throw Boom): Int))((_, _) => discard(log += "inner"))(b => Effect.defer(b))
            }
            val ex = intercept[RuntimeException](eval(v))
            assert(ex eq Boom)
            assert(log.toList == List("outer"))
        }

        "a bracket under a recovering handler releases with the failure, before the recovery" in {
            var seen  = Maybe.empty[Maybe[Throwable]]
            val order = collection.mutable.ListBuffer[String]()
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7)) { (_, outcome) =>
                    seen = Maybe(outcome)
                    discard(order += "release")
                }(_ => Effect.defer((throw Boom): Int))
            val recovered: Int < Any =
                ArrowEffect.handleCont[kyo.Const[Unit], kyo.Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], body)(
                    [C] => (_, cont) => cont(0),
                    a => a,
                    _ =>
                        discard(order += "recover")
                        Maybe(9)
                )
            assert(eval(recovered) == 9)
            assert(seen.exists(_.exists(_ eq Boom)))
            assert(order.toList == List("release", "recover"))
        }

        "a bracket failing after a crossing still releases before the recovery" in {
            var seen  = Maybe.empty[Maybe[Throwable]]
            val order = collection.mutable.ListBuffer[String]()
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7)) { (_, outcome) =>
                    seen = Maybe(outcome)
                    discard(order += "release")
                }(_ => ask.map(_ => (throw Boom): Int))
            val recovered: Int < Any =
                ArrowEffect.handleCont[kyo.Const[Unit], kyo.Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], body)(
                    [C] => (_, cont) => cont(0),
                    a => a,
                    _ =>
                        discard(order += "recover")
                        Maybe(9)
                )
            assert(eval(recovered) == 9)
            assert(seen.exists(_.exists(_ eq Boom)))
            assert(order.toList == List("release", "recover"))
        }

        "two stacked brackets abandoned together release innermost first" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner")) { b =>
                    Effect.defer {
                        requestStop()
                        Effect.defer(b + 1)
                    }
                }
            }
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
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
            val ex = intercept[RuntimeException](eval(v))
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
            val ex = intercept[RuntimeException](eval(v))
            assert(ex eq Bad)
            assert(count == 1)
        }

        "nested brackets complete innermost first" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner"))(b => Effect.defer(b))
            }
            assert(eval(v) == 2)
            assert(log.toList == List("inner", "outer"))
        }

        "a bracket releases before the next one acquires" in {
            val log = collection.mutable.ListBuffer[String]()
            def one(name: String): Int < Any =
                Effect.bracket(Effect.defer {
                    discard(log += s"acquire $name")
                    name
                })((_, _) => discard(log += s"release $name"))(_ => Effect.defer(1))
            val v = one("a").map(_ => one("b"))
            assert(eval(v) == 1)
            assert(log.toList == List("acquire a", "release a", "acquire b", "release b"))
        }

        "a bracket owned by an isolated child releases at the child's completion" in {
            val outcomes = collection.mutable.ListBuffer[Maybe[Throwable]]()
            val v = Isolate.internal.Contextual.run(
                Effect.bracket(Effect.defer(7))((_, outcome) => discard(outcomes += outcome))(a => Effect.defer(a + 1))
            ).map(_ + 1)
            assert(eval(v) == 9)
            assert(outcomes.toList == List(Maybe.empty))
        }

        "a failure inside an isolated child releases the child's bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Isolate.internal.Contextual.run(
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome))(_ => Effect.defer((throw Boom): Int))
            )
            val ex = intercept[RuntimeException](eval(v))
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
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(seen.isEmpty)
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "a discarded capture's bracket is told the discard signal" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome))(a => ask.map(x => a + x))
            val dropped: Int < Any =
                ArrowEffect.handleCont[kyo.Const[Unit], kyo.Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], body)(
                    [C] => (_, _) => -1,
                    b => b
                )
            assert(eval(dropped) == -1)
            assert(seen.exists(_.exists(_.isInstanceOf[kyo.KyoException])))
        }
    }

    "unit acquire" - {
        "runs after completion with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Effect.bracket(())((_, outcome) => seen = Maybe(outcome))(_ => Effect.defer(5))
            assert(eval(v) == 5)
            assert(seen == Maybe(Maybe.empty))
        }

        "runs with the failure on unwind" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Effect.bracket(())((_, outcome) => seen = Maybe(outcome))(_ => Effect.defer((throw Boom): Int))
            val ex   = intercept[RuntimeException](eval(v))
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }
    }
end EffectBracketTest
