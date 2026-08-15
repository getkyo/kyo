// package kyo.kernel.internal
//
// import kyo.Arrow
// import kyo.Tag
// import kyo.discard
// import kyo.kernel.*
// import org.scalatest.freespec.AnyFreeSpec
// import scala.annotation.tailrec
// import scala.util.control.NoStackTrace
//
// class EffectTraceTest extends AnyFreeSpec:
//
//     type Const[A] = [B] =>> A
//
//     sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
//     sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
//
//     class Boom  extends RuntimeException("boom")
//     class Quiet extends RuntimeException("quiet") with NoStackTrace
//
//     def ask: Int < Ask             = ArrowEffect.suspend[Any](Tag[Ask], ())
//     def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)
//
//     def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
//         ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.continue(value))
//
//     def dropSay[A, S](v: A < (Say & S)): A < S =
//         ArrowEffect.handleLoop(Tag[Say], v)([X] => _ => Loop.continue(()))
//
//     def innerStep(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
//     def outerStep(v: Int < Ask): Int < Ask = innerStep(v).map(_ + 1)
//
//     def quietStep(v: Int < Ask): Int < Ask = v.map(_ => throw new Quiet)
//
//     def stepA(v: Int < Ask): Int < Ask = v.map(_ + 1)
//     def stepB(v: Int < Ask): Int < Ask = v.map(_ + 2)
//
//     // alternating sites so consecutive frames differ: a run of one frame collapses
//     // to one element, which is what a loop over a single map site produces
//     def deepChain(depth: Int): Int < Ask =
//         @tailrec def loop(i: Int, acc: Int < Ask): Int < Ask =
//             if i == 0 then acc
//             else loop(i - 1, if i % 2 == 0 then stepA(acc) else stepB(acc))
//         loop(depth, innerStep(ask))
//     end deepChain
//
//     private def carrier(ex: Throwable): EffectTrace =
//         ex.getSuppressed.collectFirst { case c: EffectTrace => c } match
//             case Some(c) => c
//             case None    => fail(s"no effect trace attached to $ex")
//
//     private def methods(ex: Throwable): List[String] =
//         carrier(ex).elements.iterator.map(_.getMethodName).toList
//
//     private def classes(ex: Throwable): List[String] =
//         carrier(ex).elements.iterator.map(_.getClassName).toList
//
//     "the effect frames of a throw inside a mapped step" - {
//
//         "are carried through a drive" in {
//             val ex = intercept[Boom](answerAsk(1)(outerStep(ask)).eval)
//             assert(methods(ex).contains("innerStep"))
//             assert(methods(ex).contains("outerStep"))
//             assert(methods(ex).contains("ask"))
//         }
//
//         "are carried through a catching guard" in {
//             val ex = intercept[Boom](answerAsk(1)(Effect.catching(outerStep(ask))(e => throw e)).eval)
//             assert(methods(ex).contains("innerStep"))
//             assert(methods(ex).contains("outerStep"))
//             assert(classes(ex).exists(_.startsWith("catching @ ")))
//         }
//
//         "name the call site's callee and the enclosing definition" in {
//             val ex  = intercept[Boom](answerAsk(1)(outerStep(ask)).eval)
//             val els = carrier(ex).elements.toList
//             val inner = els.find(_.getMethodName == "innerStep") match
//                 case Some(e) => e
//                 case None    => fail("no element for innerStep")
//             assert(inner.getClassName == s"map @ ${classOf[EffectTraceTest].getName}")
//             assert(inner.getFileName == "EffectTraceTest.scala")
//             assert(inner.getLineNumber > 0)
//         }
//
//         "run innermost first" in {
//             val ex = intercept[Boom](answerAsk(1)(outerStep(ask)).eval)
//             val ms = methods(ex)
//             assert(ms.indexOf("ask") < ms.indexOf("innerStep"))
//             assert(ms.indexOf("innerStep") < ms.indexOf("outerStep"))
//         }
//
//         "skip the internal frame placeholder" in {
//             val ex = intercept[Boom](answerAsk(1)(outerStep(ask)).eval)
//             assert(carrier(ex).elements.forall(_.getFileName != "<internal>"))
//         }
//     }
//
//     "a suspension boundary the physical stack cannot cross" in {
//         var raw: Array[StackTraceElement] = Array.empty
//         def thrower(v: Int < Ask): Int < Ask =
//             v.map { _ =>
//                 val ex = new Boom
//                 raw = ex.getStackTrace
//                 throw ex
//             }
//         def around(v: Int < Ask): Int < Ask = thrower(v).map(_ + 1)
//
//         val ex = intercept[Boom](answerAsk(1)(around(ask)).eval)
//         assert(!raw.exists(_.getMethodName == "around"))
//         assert(methods(ex).contains("around"))
//         assert(methods(ex).contains("thrower"))
//     }
//
//     "a Defer bounce" - {
//
//         "carries the deferred site" in {
//             def deferred: Int < Any = Effect.defer[Int, Any](throw new Boom)
//             val ex                  = intercept[Boom](deferred.eval)
//             assert(methods(ex).contains("deferred"))
//             assert(classes(ex).exists(_.startsWith("defer @ ")))
//         }
//
//         "carries the steps after a budget rescue" in {
//             def rescued: Int < Any =
//                 @tailrec def loop(i: Int, acc: Int < Any): Int < Any =
//                     if i == 0 then acc else loop(i - 1, acc.map(_ + 1))
//                 loop(600, boomLater(0))
//             end rescued
//             def boomLater(v: Int): Int < Any = Effect.defer[Int, Any](throw new Boom)
//
//             val ex = intercept[Boom](rescued.eval)
//             assert(methods(ex).contains("boomLater") || methods(ex).contains("rescued"))
//         }
//     }
//
//     "region nesting" - {
//
//         "appears as one element per handler tag, innermost first" in {
//             def useAsk: Int < (Ask & Say) = outerStep(ask).map(v => say("x").map(_ => v))
//             val ex                        = intercept[Boom](dropSay(answerAsk(1)(useAsk)).eval)
//             val cs                        = classes(ex)
//             assert(cs.exists(_.endsWith("Ask")))
//             assert(cs.exists(_.endsWith("Say")))
//             assert(cs.indexWhere(_.endsWith("Ask")) < cs.indexWhere(_.endsWith("Say")))
//         }
//
//         "names each region exactly once" in {
//             def useAsk: Int < (Ask & Say) = outerStep(ask).map(v => say("x").map(_ => v))
//             val ex                        = intercept[Boom](dropSay(answerAsk(1)(useAsk)).eval)
//             assert(classes(ex).count(_.endsWith("Ask")) == 1)
//             assert(classes(ex).count(_.endsWith("Say")) == 1)
//         }
//     }
//
//     "nested drives accumulate outward" in {
//         def innerDrive(): Int   = answerAsk(1)(outerStep(ask)).eval
//         def crossing: Int < Say = say("x").map(_ => innerDrive())
//         val ex                  = intercept[Boom](dropSay(crossing).eval)
//         val cs                  = classes(ex)
//         assert(methods(ex).contains("innerStep"))
//         assert(methods(ex).contains("crossing"))
//         assert(cs.count(_.endsWith("Ask")) == 1)
//         assert(cs.count(_.endsWith("Say")) == 1)
//         assert(cs.indexWhere(_.endsWith("Ask")) < cs.indexWhere(_.endsWith("Say")))
//     }
//
//     "a second crossing rewrites the spliced trace rather than duplicating it" in {
//         def rethrown: Int < Any = Effect.catching(answerAsk(1)(outerStep(ask)).eval)(e => throw e)
//         val ex                  = intercept[Boom](rethrown.eval)
//         assert(ex.getStackTrace.count(_.getMethodName == "innerStep") == 1)
//         assert(ex.getStackTrace.count(_.getMethodName == "outerStep") == 1)
//         assert(ex.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1)
//     }
//
//     "the synthesized frames lead the spliced trace" in {
//         val ex = intercept[Boom](answerAsk(1)(outerStep(ask)).eval)
//         val es = ex.getStackTrace
//         val cs = carrier(ex).elements
//         assert(es.length >= cs.length)
//         assert(es.take(cs.length).sameElements(cs))
//     }
//
//     "a NoStackTrace failure keeps its carrier and its empty stack" in {
//         val ex = intercept[Quiet](answerAsk(1)(quietStep(ask)).eval)
//         assert(carrier(ex).elements.nonEmpty)
//         assert(methods(ex).contains("quietStep"))
//         assert(ex.getStackTrace.isEmpty)
//     }
//
//     "a fatal error propagates unenriched and unswallowed" in {
//         val fatal                    = new StackOverflowError("fatal")
//         val before                   = fatal.getStackTrace
//         def fatalStep: Int < Ask     = ask.map(_ => throw fatal)
//         var caught: Throwable | Null = null
//         try discard(answerAsk(1)(fatalStep).eval)
//         catch case ex: Throwable => caught = ex
//         assert(caught eq fatal)
//         assert(fatal.getSuppressed.isEmpty)
//         assert(fatal.getStackTrace.sameElements(before))
//     }
//
//     "the cap" - {
//
//         "stops the walk and records what it did not reach" in {
//             val ex = intercept[Boom](answerAsk(1)(deepChain(200)).eval)
//             assert(carrier(ex).elements.length == 64)
//             assert(carrier(ex).dropped > 0)
//         }
//
//         "bounds a chain far deeper than the Java stack" in {
//             val ex = intercept[Boom](answerAsk(1)(deepChain(1000000)).eval)
//             assert(carrier(ex).elements.length == 64)
//             assert(carrier(ex).dropped > 0)
//         }
//     }
//
//     "a failure of the walk itself leaves the original failure travelling" in {
//         // a node whose frame cannot be read: describing a failure must never
//         // replace the failure being described
//         val unreadable =
//             new Kyo.Suspend[Const[Unit], Const[Int], Ask, Any, Int, Ask]:
//                 def tag   = Tag[Ask]
//                 def input = ()
//                 def frame = throw new IllegalStateException("frame read failed")
//                 def cont  = Arrow[Int]
//
//         val ex = intercept[Boom](answerAsk(1)((unreadable: Int < Ask).map(_ => throw new Boom)).eval)
//         assert(ex.getMessage == "boom")
//     }
//
//     "the carrier renders the frames as a message" in {
//         val ex  = intercept[Boom](answerAsk(1)(outerStep(ask)).eval)
//         val msg = carrier(ex).getMessage
//         assert(msg.startsWith("effect trace:"))
//         assert(msg.contains("innerStep"))
//         assert(msg.contains("outerStep"))
//     }
//
// end EffectTraceTest
