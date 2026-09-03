package kyo.kernel

import kyo.*
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.ListBuffer

class ContextEffectTest extends AnyFreeSpec:

    sealed trait TestRuntimeEffect1 extends ContextEffect[Int]
    sealed trait TestRuntimeEffect2 extends ContextEffect[String]
    sealed trait TestRuntimeEffect3 extends ContextEffect[Boolean]

    def testRuntimeEffect1: Int < TestRuntimeEffect1 =
        ContextEffect.suspend(Tag[TestRuntimeEffect1])

    def testRuntimeEffect2: String < TestRuntimeEffect2 =
        ContextEffect.suspend(Tag[TestRuntimeEffect2])

    def testRuntimeEffect3: Boolean < TestRuntimeEffect3 =
        ContextEffect.suspend(Tag[TestRuntimeEffect3])

    sealed trait Count extends ContextEffect[Int]
    def count: Int < Count = ContextEffect.suspend(Tag[Count])

    sealed trait Cfg    extends ContextEffect[Int]
    sealed trait CfgSub extends Cfg

    sealed trait MapCtx extends ContextEffect[Map[String, Int]]

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    "suspend" in {
        val effect: Int < TestRuntimeEffect1 = testRuntimeEffect1
        discard(effect)
        // ContextEffect.suspend produces an Int < TestRuntimeEffect1; the type ascription above is the verification
        succeed
    }

    "handle" - {

        "const value" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 42)(effect)
            assert(result.eval == 42)
        }

        "single effect" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 42, _ + 1)(effect)
            assert(result.eval == 42)
        }

        "two effects" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                yield s"$i-$s"

            val result =
                ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 42, _ + 1) {
                    ContextEffect.handleInheritable(Tag[TestRuntimeEffect2], "default", _.toUpperCase)(effect)
                }

            assert(result.eval == "42-default")
        }

        "three effects" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                    b <- testRuntimeEffect3
                yield s"$i-$s-$b"

            val result =
                ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 42, _ + 1) {
                    ContextEffect.handleInheritable(Tag[TestRuntimeEffect2], "default", _.toUpperCase) {
                        ContextEffect.handleInheritable(Tag[TestRuntimeEffect3], false, !_)(effect): String < (TestRuntimeEffect1 &
                            TestRuntimeEffect2)
                    }
                }

            assert(result.eval == "42-default-false")
        }

        "ifUndefined behavior" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 100, _ * 2)(effect)
            assert(result.eval == 100)
        }

        "ifDefined behavior" in {
            val effect =
                for
                    _ <- testRuntimeEffect1
                    i <- testRuntimeEffect1
                yield i

            val result =
                ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 100, _ * 2) {
                    ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 100, _ * 2)(effect)
                }
            assert(result.eval == 200)
        }

        "multiple uses of the same effect" in {
            val effect =
                for
                    i1 <- testRuntimeEffect1
                    i2 <- testRuntimeEffect1
                    i3 <- testRuntimeEffect1
                yield i1 + i2 + i3

            val result = ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 10, _ + 1)(effect)
            assert(result.eval == 30)
        }

        "nested effects" in {
            val innerEffect = testRuntimeEffect2
            val outerEffect =
                for
                    i <- testRuntimeEffect1
                    s <- ContextEffect.handleInheritable(Tag[TestRuntimeEffect2], "inner", _.toUpperCase)(innerEffect)
                yield s"$i-$s"

            val result = ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 42, _ + 1)(outerEffect)
            assert(result.eval == "42-inner")
        }

        "effect order preservation" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                    b <- testRuntimeEffect3
                yield s"$i-$s-$b"

            val result =
                ContextEffect.handleInheritable(Tag[TestRuntimeEffect3], true, !_) {
                    ContextEffect.handleInheritable(Tag[TestRuntimeEffect2], "middle", _.toUpperCase) {
                        ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 10, _ * 2)(effect): String < (TestRuntimeEffect2 &
                            TestRuntimeEffect3)
                    }
                }

            assert(result.eval == "10-middle-true")
        }

        "with transformation" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                yield s"$i-$s"

            val result = ContextEffect.handleInheritable(Tag[TestRuntimeEffect1], 1, i => if i < 10 then i * 2 else i / 2) {
                ContextEffect.handleInheritable(Tag[TestRuntimeEffect2], "start", s => s + s.length.toString)(effect)
            }

            assert(result.eval == "1-start")
        }
    }

    "reads what is bound" - {

        "a read transforms in the same step" in {
            val v = ContextEffect.suspendWith(Tag[Count])(c => c * 2)
            assert(ContextEffect.handleInheritable(Tag[Count], 21)(v).eval == 42)
        }
    }

    "layering" - {

        "the innermost binding is what a read takes" in {
            val r =
                ContextEffect.handleInheritable(Tag[Count], 1) {
                    ContextEffect.handleInheritable(Tag[Count], 2)(count)
                }
            assert(r.eval == 2)
        }

        "a merging binding keeps what an enclosing one holds" in {
            val v: Map[String, Int] < MapCtx = ContextEffect.suspend(Tag[MapCtx])
            val r =
                ContextEffect.handleInheritable(Tag[MapCtx], Map("a" -> 1), _.updated("a", 1)) {
                    ContextEffect.handleInheritable(Tag[MapCtx], Map("b" -> 2), _.updated("b", 2))(v)
                }
            assert(r.eval == Map("a" -> 1, "b" -> 2))
        }
    }

    "extent" - {

        "a binding does not reach a read after it" in {
            val inner = ContextEffect.handleInheritable(Tag[Count], 42)(count)
            val v     = inner.map(a => ContextEffect.suspend(Tag[Count], -1).map(b => (a, b)))
            assert(v.eval == ((42, -1)))
        }

        "a read with a default and nothing bound takes the default" in {
            assert(ContextEffect.suspend(Tag[Count], -1).eval == -1)
        }

        "a read with a default takes a binding over it" in {
            val v = ContextEffect.suspend(Tag[Count], -1)
            assert(ContextEffect.handleInheritable(Tag[Count], 7)(v).eval == 7)
        }

        "a required read with nothing bound is a bug" in {
            intercept[Throwable] {
                val _ = count.asInstanceOf[Int < Any].eval
            }
        }
    }

    "crossing regions" - {

        "a binding stands while an operation is answered outside it" in {
            val v: Int < (Count & Ask) = ask.map(a => count.map(c => a + c))
            val bound: Int < Ask       = ContextEffect.handleInheritable(Tag[Count], 2)(v)
            val r                      = ArrowEffect.handleCont(Tag[Ask], bound)([C] => (_, cont) => cont(40), a => a)
            assert(r.eval == 42)
        }

        "a region installed inside a binding reads it" in {
            val v: Int < (Count & Ask) = ask.map(a => count.map(c => a + c))
            val r: Int < Count         = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(40), a => a)
            assert(ContextEffect.handleInheritable(Tag[Count], 2)(r).eval == 42)
        }

        "a clause of a region under a binding reads it" in {
            val v: Int < (Count & Ask) = ask.map(_ + 1)
            val bound: Int < Any =
                ContextEffect.handleInheritable(Tag[Count], 41) {
                    ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => count.map(c => cont(c)), a => a)
                }
            assert(bound.eval == 42)
        }

        "a continuation captured under a binding carries it" in {
            val v: Int < (Count & Ask & Say) = say("x").map(_ => ask.map(a => count.map(c => a + c)))
            val bound: Int < (Ask & Say)     = ContextEffect.handleInheritable(Tag[Count], 2)(v)
            val sayHandled: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], bound)([C] => (_, cont) => cont(()), a => a)
            val r = ArrowEffect.handleCont(Tag[Ask], sayHandled)([C] => (_, cont) => cont(40), a => a)
            assert(r.eval == 42)
        }
    }

    "resuming" - {

        "the same bound computation evaluates the same way twice" in {
            val v = ContextEffect.handleInheritable(Tag[Count], 21)(count.map(_ * 2))
            assert(v.eval == 42)
            assert(v.eval == 42)
        }

        "a captured binding resolves against the scope it resumes in" in {
            val v: Int < (Count & Ask) = ask.map(a => count.map(c => a + c))
            val bound: Int < Ask       = ContextEffect.handleInheritable(Tag[Count], 1, _ + 1)(v)
            val r =
                ContextEffect.handleInheritable(Tag[Count], 10) {
                    ArrowEffect.handleCont(Tag[Ask], bound)([C] => (_, cont) => cont(0), a => a)
                }
            assert(r.eval == 11)
        }
    }

    "completion and release" - {

        def held[A, S](value: Int, onExit: Int => Unit)(v: A < (Count & S)): A < S =
            ContextEffect.handle(Tag[Count])(
                derive = (_: Maybe[Int]) => value,
                fork = (s: Int) => s,
                join = (parent: Int, _: Int, _: Int) => parent,
                done = (i: Int) => onExit(i),
                release = (i: Int, _: Throwable) => onExit(i)
            )(v)

        "runs when the extent ends" in {
            var released = Maybe.empty[Int]
            val v        = held(42, i => released = Maybe(i))(count.map(_ + 1))
            assert(v.eval == 43)
            assert(released == Maybe(42))
        }

        "runs before what follows the extent" in {
            var order = List.empty[String]
            val v = held(1, _ => order = order :+ "release")(count.map(_ => order = order :+ "body"))
                .map(_ => order = order :+ "after")
            v.eval
            assert(order == List("body", "release", "after"))
        }

        "runs when the computation throws, and the failure still leaves" in {
            var released = false
            val v        = held(1, _ => released = true)(count.map(_ => (throw new RuntimeException("boom")): Int))
            intercept[RuntimeException] {
                val _ = v.eval
            }
            assert(released)
        }

        "runs when a clause discards the continuation" in {
            var released     = false
            val v: Int < Ask = held(1, _ => released = true)(ask.map(a => count.map(_ + a)))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => 99, a => a)
            assert(r.eval == 99)
            assert(released)
        }

        "runs once when the extent ends and the eval then drains" in {
            var count0 = 0
            val v      = held(1, _ => count0 += 1)(count.map(_ + 1))
            assert(v.eval == 2)
            assert(count0 == 1)
        }

        def logged[A, S](log: ListBuffer[String])(v: A < (Count & S)): A < S =
            ContextEffect.handle(Tag[Count])(
                derive = (_: Maybe[Int]) => 1,
                fork = (s: Int) => s,
                join = (parent: Int, _: Int, _: Int) => parent,
                done = (_: Int) => discard(log += "done"),
                release = (_: Int, _: Throwable) => discard(log += "release")
            )(v)

        "a region crossed to a foreign loop answered with a pending outcome completes without a release" in {
            val log          = ListBuffer.empty[String]
            val v: Int < Ask = logged(log)(ask.map(a => count.map(_ + a)))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Effect.defer(Loop.continue((), 41: Int < Any)), a => a)
            assert(r.eval == 42)
            assert(log.toList == List("done"))
        }

        "a region crossed to a foreign clause that resumes inside a nested region completes without a release" in {
            val log          = ListBuffer.empty[String]
            val v: Int < Ask = logged(log)(ask.map(a => count.map(_ + a)))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)(
                [C] => (_, cont) => ArrowEffect.handleCont(Tag[Say], cont(41))([C2] => (_, k) => k(()), a => a),
                a => a
            )
            assert(r.eval == 42)
            assert(log.toList == List("done"))
        }

        "a settled body still derives from the binding around it" in {
            var seen = Maybe.empty[Maybe[Int]]
            val v: Int < Any =
                ContextEffect.handleInheritable(Tag[Count], 5)(
                    Effect.defer(
                        ContextEffect.handleInheritable(Tag[Count]) { (outer: Maybe[Int]) =>
                            seen = Maybe(outer)
                            outer.getOrElse(0) + 1
                        }(42: Int < Count)
                    )
                )
            assert(v.eval == 42)
            assert(seen == Maybe(Maybe(5)))
        }
    }

    "tag subtyping" - {
        "an inner region at the supertype tag leaves no binding behind once its outer subtype region exits" in {
            val v: Int < Any =
                ContextEffect.handleInheritable(Tag[CfgSub], 1)(
                    ContextEffect.handleInheritable(Tag[Cfg], 2)(ContextEffect.suspend(Tag[Cfg]))
                ).map(_ => ContextEffect.suspend(Tag[Cfg], -1))
            assert(v.eval == -1)
        }

        "a read takes the innermost binding whether its tag is exact or a subtype" in {
            val exactInner: Int < Any =
                ContextEffect.handleInheritable(Tag[CfgSub], 1)(
                    ContextEffect.handleInheritable(Tag[Cfg], 2)(ContextEffect.suspend(Tag[Cfg]))
                )
            val subInner: Int < Any =
                ContextEffect.handleInheritable(Tag[Cfg], 1)(
                    ContextEffect.handleInheritable(Tag[CfgSub], 2)(ContextEffect.suspend(Tag[Cfg]))
                )
            assert(exactInner.eval == 2)
            assert(subInner.eval == 2)
        }

        "an outer exact binding uncovered by an inner exit does not shadow a subtype binding between them" in {
            val v: (Int, Int) < Any =
                ContextEffect.handleInheritable(Tag[Cfg], 1)(
                    ContextEffect.handleInheritable(Tag[CfgSub], 2)(
                        ContextEffect.handleInheritable(Tag[Cfg], 3)(ContextEffect.suspend(Tag[Cfg]))
                            .map(inner => ContextEffect.suspend(Tag[Cfg]).map(after => (inner, after)))
                    )
                )
            assert(v.eval == ((3, 2)))
        }

        "a region derives from the innermost related binding" in {
            val v: Int < Any =
                ContextEffect.handleInheritable(Tag[Cfg], 1)(
                    ContextEffect.handleInheritable(Tag[CfgSub], 2)(
                        ContextEffect.handleInheritable(Tag[Cfg], 0, _ + 10)(ContextEffect.suspend(Tag[Cfg]))
                    )
                )
            assert(v.eval == 12)
        }
    }

    "reading audit pins" - {
        def hooked[A, S](log: ListBuffer[String], name: String, value: Int)(v: A < (Count & S)): A < S =
            ContextEffect.handle(Tag[Count])(
                derive = (_: Maybe[Int]) => value,
                fork = (s: Int) => s,
                join = (parent: Int, _: Int, _: Int) => parent,
                done = (s: Int) => discard(log += s"done $name $s"),
                release = (s: Int, _: Throwable) => discard(log += s"release $name $s")
            )(v)

        "each shot of a crossing drains the debts it re-installs" in {
            val log = ListBuffer[String]()
            val body: Int < (Ask & Say) =
                hooked(log, "outer", 1)(hooked(log, "inner", 2)(say("s").map(_ => 0)).map(a => ask.map(_ + a)))
            val handledSay: Int < Ask = ArrowEffect.handleCont(Tag[Say], body)([C] => (_, cont) => cont(()), a => a)
            val twice: Int < Any = ArrowEffect.handleCont(Tag[Ask], handledSay)(
                [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                a => a
            )
            assert(twice.eval == 30)
            assert(log.count(_ == "done outer 1") == 2)
            assert(log.count(_ == "release outer 1") == 0)
        }

        "a throwing done is followed by one release carrying the failure" in {
            val log  = ListBuffer[String]()
            val boom = new RuntimeException("boom")
            val r: Int < Any = ContextEffect.handle(Tag[Count])(
                derive = (_: Maybe[Int]) => 7,
                fork = (p: Int) => p,
                join = (p: Int, _: Int, _: Int) => p,
                done = (_: Int) => throw boom,
                release = (s: Int, ex: Throwable) => discard(log += s"release $s ${ex eq boom}")
            )(count)
            assert(intercept[RuntimeException](r.eval) eq boom)
            assert(log.toList == List("release 7 true"))
        }

        def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
            ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)

        "a crossing resumed in a nested eval inside the clause is out of contract: its region is released again at the owner's exit" in {
            val log             = ListBuffer[String]()
            val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => answerAsk(0)(cont(41)).eval + 1,
                a => a
            )
            assert(r.eval == 43)
            assert(log.toList == List("done cfg 1", "release cfg 1"))
        }

        "a binding below the answering handler is the resume site's, one above it is the captured one" in {
            var stash = Maybe.empty[Arrow[Int, (Int, Int), Ask & Count]]
            val body: (Int, Int) < (Ask & Count & Cfg) =
                ask.map(a => count.map(c => ContextEffect.suspend(Tag[Cfg]).map(c2 => (c + a, c2))))
            val inside: (Int, Int) < (Ask & Count) = ContextEffect.handleInheritable(Tag[Cfg], 2)(body)
            val handled: (Int, Int) < Count = ArrowEffect.handleCont(Tag[Ask], inside)(
                [C] =>
                    (_, cont) =>
                        stash = Maybe(cont)
                        (-1, -1)
                ,
                a => a
            )
            assert(ContextEffect.handleInheritable(Tag[Count], 1)(handled).eval == ((-1, -1)))
            val resumed: (Int, Int) < Any =
                ContextEffect.handleInheritable(Tag[Count], 100)(
                    ContextEffect.handleInheritable(Tag[Cfg], 200)(answerAsk(0)(stash.get(0)))
                )
            assert(resumed.eval == ((100, 2)))
        }

        "each shot re-establishes a hooked region and completes it before the clause continues" in {
            val log             = ListBuffer[String]()
            val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        cont(1).map { a =>
                            log += s"shot $a"
                            cont(2).map { b =>
                                log += s"shot $b"
                                a + b
                            }
                    },
                a =>
                    log += "handler done"
                    a
            )
            assert(r.eval == 5)
            assert(log.toList == List("done cfg 1", "shot 2", "done cfg 1", "shot 3", "handler done"))
        }

        "a handleFirst remainder re-enters a raw region the region's end already released" in {
            val log             = ListBuffer[String]()
            val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))
            val first: Int < Ask = ArrowEffect.handleFirst[Const[Unit], Const[Int], Ask, Int, Int, Any, Ask](Tag[Ask], body)(
                handle = [C] =>
                    (_, cont) =>
                        log += "clause"
                        cont(41)
                ,
                done = a => a
            )
            assert(answerAsk(0)(first).eval == 42)
            assert(log.toList == List("clause", "release cfg 1", "done cfg 1"))
        }
    }

end ContextEffectTest
