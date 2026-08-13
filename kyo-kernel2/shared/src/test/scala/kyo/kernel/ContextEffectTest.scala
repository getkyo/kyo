package kyo.kernel

import org.scalatest.freespec.AnyFreeSpec

// Parked with the removal of ContextEffect from kyo-kernel2. The suite is preserved
// verbatim below; restore it against the replacement design.
class ContextEffectTest extends AnyFreeSpec:
    "parked: ContextEffect was removed from kyo-kernel2" ignore { succeed }
end ContextEffectTest

/*
import kyo.Tag
import kyo.discard
import kyo.kernel.internal.*
import org.scalatest.freespec.AnyFreeSpec

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

    "suspend" in {
        val effect: Int < TestRuntimeEffect1 = testRuntimeEffect1
        discard(effect)
        succeed
    }

    "handle" - {

        "const value" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 42)(effect)
            assert(result.eval == 42)
        }

        "single effect" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1)(effect)
            assert(result.eval == 42)
        }

        "two effects" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                yield s"$i-$s"

            val result =
                ContextEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1) {
                    ContextEffect.handle(Tag[TestRuntimeEffect2], "default", _.toUpperCase)(effect)
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
                ContextEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1) {
                    ContextEffect.handle(Tag[TestRuntimeEffect2], "default", _.toUpperCase) {
                        ContextEffect.handle(Tag[TestRuntimeEffect3], false, !_)(effect): String < (TestRuntimeEffect1 & TestRuntimeEffect2)
                    }
                }

            assert(result.eval == "42-default-false")
        }

        "ifUndefined behavior" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2)(effect)
            assert(result.eval == 100)
        }

        "ifDefined behavior" in {
            val effect =
                for
                    _ <- testRuntimeEffect1
                    i <- testRuntimeEffect1
                yield i

            val result =
                ContextEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2) {
                    ContextEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2)(effect)
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

            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 10, _ + 1)(effect)
            assert(result.eval == 30)
        }

        "nested effects" in {
            val innerEffect = testRuntimeEffect2
            val outerEffect =
                for
                    i <- testRuntimeEffect1
                    s <- ContextEffect.handle(Tag[TestRuntimeEffect2], "inner", _.toUpperCase)(innerEffect)
                yield s"$i-$s"

            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1)(outerEffect)
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
                ContextEffect.handle(Tag[TestRuntimeEffect3], true, !_) {
                    ContextEffect.handle(Tag[TestRuntimeEffect2], "middle", _.toUpperCase) {
                        ContextEffect.handle(Tag[TestRuntimeEffect1], 10, _ * 2)(effect): String < (TestRuntimeEffect2 & TestRuntimeEffect3)
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

            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 1, i => if i < 10 then i * 2 else i / 2) {
                ContextEffect.handle(Tag[TestRuntimeEffect2], "start", s => s + s.length.toString)(effect)
            }

            assert(result.eval == "1-start")
        }

        "default resolves when no handler provides a value" in {
            val effect = ContextEffect.suspend(Tag[TestRuntimeEffect1], 7)
            assert(effect.eval == 7)
        }

        "default is ignored when a handler provides a value" in {
            val effect = ContextEffect.suspend(Tag[TestRuntimeEffect1], 7)
            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 42)(effect.asInstanceOf[Int < TestRuntimeEffect1])
            assert(result.eval == 42)
        }
    }

    "fork inheritance" - {

        sealed trait Ctx1              extends ContextEffect[Int]
        sealed trait Ctx2              extends ContextEffect[String]
        sealed trait NoninheritableCtx extends ContextEffect[Int] with ContextEffect.Noninheritable

        def ctx1: Int < Ctx1    = ContextEffect.suspend(Tag[Ctx1])
        def ctx2: String < Ctx2 = ContextEffect.suspend(Tag[Ctx2])
        // defaulted so a dropped Noninheritable cell surfaces as a value (the
        // default), not an IllegalStateException, isolating that one assertion
        def noninheritableCtx: Int < NoninheritableCtx = ContextEffect.suspend(Tag[NoninheritableCtx], -1)

        // a computation nested under detach genuinely raises the effect it uses,
        // but detach itself erases the row to Any, so wrapping the detach call with
        // a ContextEffect.handle over that effect needs the row cast back. Harmless:
        // the row is phantom, and the handler installed dynamically (found on `hs`
        // when the detach suspension is answered) is what makes the transplant
        // real, not this ascription. The same direct cast EvalTest and HandlersTest
        // already use to build fixtures whose declared row does not match dynamic
        // behavior (see "the innermost handler of a tag answers" in EvalTest)

        // extracts a still-pending, boxed child from a fully driven outer
        // computation that wraps a detach call directly. Not `.eval`: its settle
        // step picks the primitive or the Nested branch from the *static* type,
        // and here the static type is itself a pending type over a JVM primitive
        // ((Int < Ctx1) < Any, say), which reads as the primitive case and unboxes
        // the Nested wrapper itself instead of what it carries. Nested.unnest
        // checks the *runtime* shape instead, so it has no such blind spot: the
        // currency discipline's own cast-at-the-boundary pattern (CONTRIBUTING.md)
        // for exactly this class of erased-type read
        def extract[A, S](v: A < S): A = Nested.unnest(Eval(v))

        "a const binding crosses a fork" in {
            val forked = Effect.detach(ctx1).asInstanceOf[(Int < Ctx1) < Ctx1]
            val bound  = ContextEffect.handle(Tag[Ctx1], 42)(forked)
            // the child is dynamically self-answering (its own transplanted cell
            // resolves it), but its declared row still names Ctx1; cast back to Any
            // so `.eval` is callable (the value position is a plain Int now, so
            // this final `.eval` is not the footgun `extract` routes around)
            val child = extract(bound).asInstanceOf[Int < Any]
            assert(child.eval == 42)
        }

        "a Noninheritable binding does not cross a fork; the child falls to its own default" in {
            val forked = Effect.detach(noninheritableCtx).asInstanceOf[(Int < NoninheritableCtx) < NoninheritableCtx]
            val bound  = ContextEffect.handle(Tag[NoninheritableCtx], 99)(forked)
            val child  = extract(bound).asInstanceOf[Int < Any]
            assert(child.eval == -1)
        }

        "every Noninheritable cell for a tag is dropped by the fork, not only the innermost" in {
            val forked     = Effect.detach(noninheritableCtx).asInstanceOf[(Int < NoninheritableCtx) < NoninheritableCtx]
            val innerBound = ContextEffect.handle(Tag[NoninheritableCtx], 2)(forked)
            val outerBound = ContextEffect.handle(Tag[NoninheritableCtx], 1)(innerBound)
            val child      = extract(outerBound).asInstanceOf[Int < Any]
            // if only the innermost cell were dropped the child would read 1 (the
            // outer cell); dropping both falls all the way to the default
            assert(child.eval == -1)
        }

        "a Noninheritable binding for one tag does not block a different tag's inheritable binding" in {
            val program: String < (Ctx1 & NoninheritableCtx) =
                for
                    a <- ctx1
                    b <- noninheritableCtx
                yield s"$a-$b"
            val forked = Effect.detach(program).asInstanceOf[(String < (Ctx1 & NoninheritableCtx)) < (Ctx1 & NoninheritableCtx)]
            val bound =
                ContextEffect.handle(Tag[NoninheritableCtx], 99) {
                    ContextEffect.handle(Tag[Ctx1], 7)(forked)
                }
            val child = extract(bound).asInstanceOf[String < Any]
            assert(child.eval == "7--1")
        }

        "layered bindings resolve in the child exactly as in the parent, lazily, per read" in {
            def readTwice: Int < Ctx1 =
                for
                    a <- ctx1
                    b <- ctx1
                yield a + b

            // baseline: the same layered nesting with no fork at all
            val parentOnly =
                ContextEffect.handle(Tag[Ctx1], 1, _ + 10) {
                    ContextEffect.handle(Tag[Ctx1], 0, _ + 100)(readTwice)
                }
            val parentResult = parentOnly.eval

            val forked = Effect.detach(readTwice).asInstanceOf[(Int < Ctx1) < Ctx1]
            val bound =
                ContextEffect.handle(Tag[Ctx1], 1, _ + 10) {
                    ContextEffect.handle(Tag[Ctx1], 0, _ + 100)(forked)
                }
            val child = extract(bound).asInstanceOf[Int < Any]
            assert(child.eval == parentResult)
        }

        "a stateful region (handleLoop) does not cross a fork, even over a context effect's own tag" in {
            val forked = Effect.detach(ctx1).asInstanceOf[(Int < Ctx1) < Ctx1]
            val statefulBound = ArrowEffect.handleLoop(Tag[Ctx1], 7, forked)(
                [X] => (_, state) => Loop.continue(state + 1, state)
            )
            val child = extract(statefulBound).asInstanceOf[Int < Any]
            // the recognizer is structural: a user-installed handleLoop over a
            // context tag never mixes in Provision, so it is not transplanted and
            // the child's mandatory read of ctx1 finds no handler at all
            intercept[IllegalStateException](child.eval)
        }

        "nesting order of multiple provision cells is preserved: the inner binding wins in the child, as in the parent" in {
            val program: Int < Ctx1 =
                for
                    a <- ctx1
                    b <- ctx1
                yield a + b
            val forked       = Effect.detach(program).asInstanceOf[(Int < Ctx1) < Ctx1]
            val innerWrapped = ContextEffect.handle(Tag[Ctx1], 5)(forked)
            val outerWrapped = ContextEffect.handle(Tag[Ctx1], 1)(innerWrapped)
            val child        = extract(outerWrapped).asInstanceOf[Int < Any]
            assert(child.eval == 10)
        }

        "distinct-tag cells resolve independently after the fork" in {
            val program: String < (Ctx1 & Ctx2) =
                for
                    i <- ctx1
                    s <- ctx2
                yield s"$i-$s"
            val forked = Effect.detach(program).asInstanceOf[(String < (Ctx1 & Ctx2)) < (Ctx1 & Ctx2)]
            val bound =
                ContextEffect.handle(Tag[Ctx2], "outer") {
                    ContextEffect.handle(Tag[Ctx1], 7)(forked)
                }
            val child = extract(bound).asInstanceOf[String < Any]
            assert(child.eval == "7-outer")
        }
    }

end ContextEffectTest
 */
