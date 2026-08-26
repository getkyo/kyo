package kyo.kernel

import kyo.Const
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.discard
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Safepoint

class ContextEffectTest extends kyo.Test:

    sealed trait Count extends ContextEffect[Int]
    sealed trait Name  extends ContextEffect[String]
    sealed trait Flag  extends ContextEffect[Boolean]

    def count: Int < Count   = ContextEffect.suspend(Tag[Count])
    def label: String < Name = ContextEffect.suspend(Tag[Name])
    def flag: Boolean < Flag = ContextEffect.suspend(Tag[Flag])

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    "reads what is bound" - {

        "a binding answers a read under it" in {
            assert(Eval(ContextEffect.handle(Tag[Count], 42)(count)) == 42)
        }

        "a read transforms in the same step" in {
            val v = ContextEffect.suspendWith(Tag[Count])(c => c * 2)
            assert(Eval(ContextEffect.handle(Tag[Count], 21)(v)) == 42)
        }

        "every read under one binding takes the same value" in {
            val v = count.map(a => count.map(b => count.map(c => a + b + c)))
            assert(Eval(ContextEffect.handle(Tag[Count], 10, _ + 1)(v)) == 30)
        }

        "bindings of different effects stand together" in {
            val v = count.map(c => label.map(n => flag.map(f => s"$c-$n-$f")))
            val r =
                ContextEffect.handle(Tag[Flag], true, !_) {
                    ContextEffect.handle(Tag[Name], "middle", _.toUpperCase) {
                        ContextEffect.handle(Tag[Count], 10, _ * 2)(v): String < (Name & Flag)
                    }
                }
            assert(Eval(r) == "10-middle-true")
        }
    }

    "layering" - {

        "nothing bound outside takes ifUndefined" in {
            assert(Eval(ContextEffect.handle(Tag[Count], 100, _ * 2)(count)) == 100)
        }

        "a binding inside another applies ifDefined to it" in {
            val r =
                ContextEffect.handle(Tag[Count], 100, _ * 2) {
                    ContextEffect.handle(Tag[Count], 100, _ * 2)(count)
                }
            assert(Eval(r) == 200)
        }

        "the innermost binding is what a read takes" in {
            val r =
                ContextEffect.handle(Tag[Count], 1) {
                    ContextEffect.handle(Tag[Count], 2)(count)
                }
            assert(Eval(r) == 2)
        }

        // the shape Local rests on: one tag, one map, each binding merging itself into what is around it
        "a merging binding keeps what an enclosing one holds" in {
            val v: Map[String, Int] < MapCtx = ContextEffect.suspend(Tag[MapCtx])
            val r =
                ContextEffect.handle(Tag[MapCtx], Map("a" -> 1), _.updated("a", 1)) {
                    ContextEffect.handle(Tag[MapCtx], Map("b" -> 2), _.updated("b", 2))(v)
                }
            assert(Eval(r) == Map("a" -> 1, "b" -> 2))
        }
    }

    "extent" - {

        "a binding does not reach a read after it" in {
            val inner = ContextEffect.handle(Tag[Count], 42)(count)
            val v     = inner.map(a => ContextEffect.suspend(Tag[Count], -1).map(b => (a, b)))
            assert(Eval(v) == ((42, -1)))
        }

        "a read with a default and nothing bound takes the default" in {
            assert(Eval(ContextEffect.suspend(Tag[Count], -1)) == -1)
        }

        "a read with a default takes a binding over it" in {
            val v = ContextEffect.suspend(Tag[Count], -1)
            assert(Eval(ContextEffect.handle(Tag[Count], 7)(v)) == 7)
        }

        "a required read with nothing bound is a bug" in {
            intercept[Throwable] {
                val _ = Eval(count.asInstanceOf[Int < Any])
            }
        }
    }

    "crossing regions" - {

        "a binding stands while an operation is answered outside it" in {
            val v: Int < (Count & Ask) = ask.map(a => count.map(c => a + c))
            val bound: Int < Ask       = ContextEffect.handle(Tag[Count], 2)(v)
            val r                      = ArrowEffect.handleCont(Tag[Ask], bound)([C] => (_, cont) => cont(40), a => a)
            assert(Eval(r) == 42)
        }

        "a region installed inside a binding reads it" in {
            val v: Int < (Count & Ask) = ask.map(a => count.map(c => a + c))
            val r: Int < Count         = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(40), a => a)
            assert(Eval(ContextEffect.handle(Tag[Count], 2)(r)) == 42)
        }

        "a clause of a region under a binding reads it" in {
            val v: Int < (Count & Ask) = ask.map(_ + 1)
            val bound: Int < Ask =
                ContextEffect.handle(Tag[Count], 41) {
                    ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => count.map(c => cont(c)), a => a)
                }
            assert(Eval(bound) == 42)
        }

        "a continuation captured under a binding carries it" in {
            val v: Int < (Count & Ask & Say) = say("x").map(_ => ask.map(a => count.map(c => a + c)))
            val bound: Int < (Ask & Say)     = ContextEffect.handle(Tag[Count], 2)(v)
            val sayHandled: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], bound)([C] => (_, cont) => cont(()), a => a)
            val r = ArrowEffect.handleCont(Tag[Ask], sayHandled)([C] => (_, cont) => cont(40), a => a)
            assert(Eval(r) == 42)
        }
    }

    "resuming" - {

        // the stop-parked resume tests live in the jvm-native ContextEffectThreadingTest: parking
        // through a stop is the jvm-native preemption mechanism

        "the same bound computation evaluates the same way twice" in {
            val v = ContextEffect.handle(Tag[Count], 21)(count.map(_ * 2))
            assert(Eval(v) == 42)
            assert(Eval(v) == 42)
        }

        // what makes an inherited binding merge into the scope it is resumed in rather than the one it left:
        // a binding resolves when it is installed, and a resumption installs it again
        "a captured binding resolves against the scope it resumes in" in {
            val v: Int < (Count & Ask) = ask.map(a => count.map(c => a + c))
            val bound: Int < Ask       = ContextEffect.handle(Tag[Count], 1, _ + 1)(v)
            val r =
                ContextEffect.handle(Tag[Count], 10) {
                    ArrowEffect.handleCont(Tag[Ask], bound)([C] => (_, cont) => cont(0), a => a)
                }
            assert(Eval(r) == 11)
        }
    }

    "release" - {

        def held[A, S](value: Int, onRelease: Int => Unit)(v: A < (Count & S)): A < S =
            ContextEffect.handle(Tag[Count], value, (_: Int) => value, release = Maybe((i: Int, _: Result[Any, A]) => onRelease(i)))(v)

        "runs when the extent ends" in {
            var released = Maybe.empty[Int]
            val v        = held(42, i => released = Maybe(i))(count.map(_ + 1))
            assert(Eval(v) == 43)
            assert(released == Maybe(42))
        }

        "runs before what follows the extent" in {
            var order = List.empty[String]
            val v = held(1, _ => order = order :+ "release")(count.map(_ => order = order :+ "body"))
                .map(_ => order = order :+ "after")
            Eval(v)
            assert(order == List("body", "release", "after"))
        }

        "runs when the computation throws, and the failure still leaves" in {
            var released = false
            val v        = held(1, _ => released = true)(count.map(_ => (throw new RuntimeException("boom")): Int))
            intercept[RuntimeException] {
                val _ = Eval(v)
            }
            assert(released)
        }

        // the path a plain exit hook cannot cover: the clause drops the continuation, so nothing ever flows
        // back through the entry and only the drain is left to run it
        "runs when a clause discards the continuation" in {
            var released     = false
            val v: Int < Ask = held(1, _ => released = true)(ask.map(a => count.map(_ + a)))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => 99, a => a)
            assert(Eval(r) == 99)
            assert(released)
        }

        "runs once when the extent ends and the eval then drains" in {
            var count0 = 0
            val v      = held(1, _ => count0 += 1)(count.map(_ + 1))
            assert(Eval(v) == 2)
            assert(count0 == 1)
        }

        "may be a deferred computation" in {
            var released = false
            val v = ContextEffect.handle(
                Tag[Count],
                1,
                (_: Int) => 1,
                release = Maybe((_: Int, _: Result[Any, Int]) => Effect.defer { released = true })
            )(count.map(_ + 1))
            assert(Eval(v) == 2)
            assert(released)
        }
    }

    sealed trait MapCtx extends ContextEffect[Map[String, Int]]

    // the cross-thread park-resume and concurrent-enclosure tests live in the jvm-native
    // ContextEffectThreadingTest: they park through stops and resume on real threads

end ContextEffectTest
