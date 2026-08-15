// package kyo.kernel
//
// import kyo.Frame
// import kyo.Tag
// import kyo.kernel.internal.*
// import org.scalatest.freespec.AnyFreeSpec
//
// class EffectTest extends AnyFreeSpec:
//
//     given Frame = Frame.internal
//
//     type Const[A] = [B] =>> A
//
//     sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]
//
//     def testEffect1(i: Int): String < TestEffect1 =
//         ArrowEffect.suspend[Any](Tag[TestEffect1], i)
//
//     sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[Unit]]
//
//     def testEffect2(s: String): Unit < TestEffect2 =
//         ArrowEffect.suspend[Any](Tag[TestEffect2], s)
//
//     def box[A](v: A): A < Any = v
//
//     private val Period = 512
//
//     def burn(n: Int): Int < Any =
//         if n == 0 then 0 else (0: Int < Any).map(_ => burn(n - 1))
//
//     "catching" - {
//         "match" in {
//             val effect = Effect.catching {
//                 throw new RuntimeException("Test exception")
//             } {
//                 case _: RuntimeException => 42
//             }
//
//             assert(effect.eval == 42)
//         }
//
//         "no match" in {
//             intercept[Exception] {
//                 Effect.catching {
//                     throw new Exception("Test exception")
//                 } {
//                     case _: RuntimeException => 42
//                 }.eval
//             }
//         }
//
//         "failure in map" in {
//             val effect = Effect.catching {
//                 testEffect1(42).map(_ => (throw new RuntimeException("Test exception")): String)
//             } {
//                 case _: RuntimeException => "caught"
//             }
//
//             val result = ArrowEffect.handle(Tag[TestEffect1], effect)(
//                 [C] => (input, cont) => cont(input.toString)
//             )
//
//             assert(result.eval == "caught")
//         }
//
//         "multiple exception types" in {
//             def testCatching(ex: Throwable) = Effect.catching {
//                 throw ex
//             } {
//                 case _: IllegalArgumentException => "Illegal Argument"
//                 case _: RuntimeException         => "Runtime"
//                 case _                           => "Other"
//             }
//
//             assert(testCatching(new RuntimeException()).eval == "Runtime")
//             assert(testCatching(new IllegalArgumentException()).eval == "Illegal Argument")
//             assert(testCatching(new Exception()).eval == "Other")
//         }
//
//         "failure in a map after a region" in {
//             val region = ArrowEffect.handleLoop(Tag[TestEffect1], testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
//                 [C] => input => Loop.continue(input.toString)
//             )
//             val effect = Effect.catching {
//                 region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
//             } {
//                 case _: RuntimeException => "caught"
//             }
//             assert(effect.eval == "caught")
//         }
//
//         "failure in a map after a first region" in {
//             // a first-operation region is the stateful handleLoop with Loop.done
//             // carrying the resumed remainder out
//             val region =
//                 ArrowEffect.handleLoop(Tag[TestEffect1], (), testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
//                     done = (_, a) => (a: String < TestEffect1),
//                     handle = [C] => (input, _, cont) => cont(input.toString).map(Loop.done(_))
//                 )
//             val effect = Effect.catching {
//                 region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
//             } {
//                 case _: RuntimeException => "caught"
//             }
//             val result = ArrowEffect.handle(Tag[TestEffect1], effect)([C] => (input, cont) => cont(input.toString))
//             assert(result.eval == "caught")
//         }
//
//         "failure in a map after a stateful region" in {
//             val region = ArrowEffect.handleLoop(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
//                 [C] => (input, state, cont) => Loop.continue(state + 1, cont((input * state).toString))
//             )
//             val effect = Effect.catching {
//                 region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
//             } {
//                 case _: RuntimeException => "caught"
//             }
//             assert(effect.eval == "caught")
//         }
//
//         "failure after a stateful region reached through a continuation" in {
//             val effect = Effect.catching {
//                 testEffect1(3).map { prefix =>
//                     val region = ArrowEffect.handleLoop(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
//                         [C] => (input, state, cont) => Loop.continue(state + 1, cont((input * state).toString))
//                     )
//                     region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else prefix + s)
//                 }
//             } {
//                 case _: RuntimeException => "caught"
//             }
//             val result = ArrowEffect.handle(Tag[TestEffect1], effect)(
//                 [C] => (input, cont) => cont(input.toString)
//             )
//             assert(result.eval == "caught")
//         }
//
//         "catching catches past the budget rescue" in {
//             val effect = Effect.catching {
//                 burn(Period * 2).map(_ => (throw new RuntimeException("Test exception")): Int)
//             } {
//                 case _: RuntimeException => -1
//             }
//             assert(effect.eval == -1)
//         }
//
//         "catching catches past the budget inside a stateful region" in {
//             val body = testEffect1(1).map(a => burn(Period * 2).map(_ => testEffect1(2).map(b => a + b)))
//             val region = ArrowEffect.handleLoop(Tag[TestEffect1], 7, body)(
//                 [C] => (input, state, cont) => Loop.continue(state + 1, cont((input * state).toString))
//             )
//             val effect = Effect.catching {
//                 region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
//             } {
//                 case _: RuntimeException => "caught"
//             }
//             assert(effect.eval == "caught")
//         }
//
//         "catching does not reach into a boxed computation" in {
//             val fallback: String < TestEffect1 = "caught"
//             val boxed = Effect.catching {
//                 box(testEffect1(1).map(s => (throw new RuntimeException("Test exception")): String))
//             } {
//                 case _: RuntimeException => box(fallback)
//             }
//             val inner   = boxed.eval
//             val handled = ArrowEffect.handle(Tag[TestEffect1], inner)([C] => (input, cont) => cont(input.toString))
//             intercept[RuntimeException](handled.eval)
//         }
//
//         "catching guards a stateful region across a park" in {
//             val body = testEffect1(1).map(a => testEffect2("park").map(_ => testEffect1(2).map(b => a + b)))
//             val region = ArrowEffect.handleLoop(Tag[TestEffect1], 7, body)(
//                 [C] => (input, state, cont) => Loop.continue(state + 1, cont((input * state).toString))
//             )
//             val effect = Effect.catching {
//                 region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
//             } {
//                 case _: RuntimeException => "caught"
//             }
//             val parked = Eval.partial(effect)
//             assert(parked.evalNow.isEmpty)
//             assert(ArrowEffect.handle(Tag[TestEffect2], parked)([C] => (_, cont) => cont(())).eval == "caught")
//         }
//
//         "a stateful region threads state under catching" in {
//             val region = ArrowEffect.handleLoop(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
//                 [C] => (input, state, cont) => Loop.continue(state + 1, cont((input * state).toString))
//             )
//             val effect = Effect.catching(region) {
//                 case _: RuntimeException => "caught"
//             }
//             assert(effect.eval == "716")
//         }
//     }
//
//     "defer" - {
//
//         "simple" in {
//             var executed = false
//             val effect = Effect.defer {
//                 executed = true
//                 42
//             }
//             assert(!executed)
//             assert(effect.eval == 42)
//             assert(executed)
//         }
//
//         "nested defer calls" in {
//             var order = List.empty[Int]
//             val effect = Effect.defer {
//                 order = 1 :: order
//                 Effect.defer {
//                     order = 2 :: order
//                     Effect.defer {
//                         order = 3 :: order
//                         42
//                     }
//                 }
//             }
//             assert(effect.eval == 42)
//             assert(order == List(3, 2, 1))
//         }
//     }
//
//     "defer with catching" in {
//         val effect = Effect.defer {
//             Effect.catching {
//                 throw new RuntimeException("Test exception")
//             } {
//                 case _: RuntimeException => 42
//             }
//         }
//         assert(effect.eval == 42)
//     }
//
//     "combining multiple effects" in {
//         val effect =
//             for
//                 a <- Effect.defer(1)
//                 b <- Effect.catching(2 / 0) { case _: ArithmeticException => 2 }
//                 c <- Effect.defer(3)
//             yield a + b + c
//
//         assert(effect.eval == 6)
//     }
//
//     // Parked with the removal of ContextEffect and Effect.detach from kyo-kernel2.
//     // Restore against the replacement design.
//     /*
//     "detach" - {
//
//         sealed trait TestCtx extends ContextEffect[Int]
//
//         def testCtx: Int < TestCtx = ContextEffect.suspend(Tag[TestCtx])
//
//         // a computation nested under detach genuinely raises the effect it uses,
//         // but detach itself erases the row to Any, so wrapping the detach call with
//         // a ContextEffect.handle over that effect needs the row cast back. Harmless:
//         // the row is phantom, and the handler installed dynamically (found on `hs`
//         // when the detach suspension is answered) is what makes the transplant
//         // real, not this ascription. The same direct cast this file's suite already
//         // uses elsewhere to build fixtures whose declared row does not match
//         // dynamic behavior (e.g. "the innermost handler of a tag answers")
//
//         // extracts a still-pending, boxed child from a fully driven outer
//         // computation. Not `.eval`: its own settle step picks the primitive or
//         // the Nested branch from the *static* type, and here the static type is
//         // itself a pending type whose payload is a JVM primitive ((Int < TestCtx)
//         // < Any), which reads as the primitive case and unboxes the Nested
//         // wrapper itself instead of what it carries. Nested.unnest checks the
//         // *runtime* shape instead, so it has no such blind spot: the currency
//         // discipline's own cast-at-the-boundary pattern (CONTRIBUTING.md) for
//         // exactly this class of erased-type read
//         def extract[A, S](v: A < S): A = Nested.unnest(Eval(v))
//
//         "a fork transplants a standing binding onto the detached child" in {
//             val forked = Effect.detach(testCtx).asInstanceOf[(Int < TestCtx) < TestCtx]
//             val bound  = ContextEffect.handle(Tag[TestCtx], 42)(forked)
//             // the child is dynamically self-answering: its own transplanted cell
//             // resolves TestCtx on a fresh drive. Its declared row still names
//             // TestCtx, so it is cast back to Any to call `.eval`; the value
//             // position is a plain Int, so that final `.eval` is not the footgun
//             // `extract` exists to route around
//             val child = extract(bound).asInstanceOf[Int < Any]
//             assert(child.eval == 42)
//         }
//
//         "a computation with no standing bindings detaches unchanged" in {
//             val child = Effect.detach(42: Int < Any).eval
//             assert(child.eval == 42)
//         }
//
//         "the marker survives an intervening map, resolving against the stack live at that point" in {
//             val forked = Effect.detach(testCtx).map(c => c.map(_ + 1)).asInstanceOf[(Int < TestCtx) < TestCtx]
//             val bound  = ContextEffect.handle(Tag[TestCtx], 7)(forked)
//             assert(extract(bound).asInstanceOf[Int < Any].eval == 8)
//         }
//
//         "the child ships boxed as data and evaluates correctly on a separate, fresh drive" in {
//             val forked = Effect.detach(testCtx).asInstanceOf[(Int < TestCtx) < TestCtx]
//             val bound  = ContextEffect.handle(Tag[TestCtx], 100)(forked)
//             val child  = extract(bound).asInstanceOf[Int < Any]
//             // a second, independent Eval call: the child is a self-contained value,
//             // not something still wired into the drive that produced it
//             assert(Eval(child).eval == 100)
//             assert(child.eval == 100)
//         }
//     }
//      */
//
// end EffectTest
