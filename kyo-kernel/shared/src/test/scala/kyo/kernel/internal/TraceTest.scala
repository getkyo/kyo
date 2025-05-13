package kyo.kernel.internal

import java.io.PrintWriter
import java.io.StringWriter
import kyo.*
import kyo.Tagged.*
import kyo.kernel.*

class TraceTest extends Test:

    sealed trait TestEffect extends ArrowEffect[Const[Int], Const[Int]]
    object TestEffect:
        def apply(i: Int): Int < TestEffect = ArrowEffect.suspend[Unit](Tag[TestEffect], i)
        def run[A, S](v: A < (TestEffect & S)) =
            ArrowEffect.handle(Tag[TestEffect], v)(
                [C] => (input, cont) => cont(input + 1)
            )
    end TestEffect

    def ex = new Exception("test exception")

    def boom[S](x: Int < S): Int < S = x.map(_ => throw ex)

    def evalOnly = boom(10).eval

    def withEffects =
        val x = TestEffect(1)
        val y = TestEffect(1)
        val z = Kyo.zip(x, y).map(_ + _).map(boom)
        TestEffect.run(z).eval
    end withEffects

    def loop(depth: Int): Int < Any =
        if depth == 0 then boom(depth)
        else ((depth - 1): Int < Any).map(loop(_))

    def repeatedFrames = loop(100).eval

    "jvm" - {
        "only eval" taggedAs jvmOnly in {
            assertTrace(
                evalOnly,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:20)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:22)
                |	at                        def evalOnly = boom(10).eval @ kyo.kernel.internal.TraceTest.evalOnly(TraceTest.scala:24)
                """
            )
        }

        "with effects" taggedAs jvmOnly in {
            assertTrace(
                withEffects,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:20)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:22)
                |	at          val z = Kyo.zip(x, y).map(_ + _).map(boom) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                |	at                    val z = Kyo.zip(x, y).map(_ + _) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                |	at                                                   ) @ kyo.kernel.internal.TraceTest.TestEffect$.run(TraceTest.scala:17)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                |	at                                                   ) @ kyo.kernel.internal.TraceTest.TestEffect$.run(TraceTest.scala:17)
                |	at                              TestEffect.run(z).eval @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:30)
                """
            )
        }

        "repeated frames" taggedAs jvmOnly in {
            assertTrace(
                repeatedFrames,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:20)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:22)
                |	at          else ((depth - 1): Int < Any).map(loop(_)) @ kyo.kernel.internal.TraceTest.loop(TraceTest.scala:35)
                """
            )
        }
    }

    // TODO The logic that finds the position of the stack to insert the trace
    // frames breaks in JS because the generated JS doesn't keep the file names
    // and positions. It doesn't fail, only generates a stack trace with Kyo's
    // frames at the wrong poition.
    "js" - {
        "only eval" taggedAs jsOnly in pendingUntilFixed {
            assertTrace(
                evalOnly,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:20)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:22)
                |	at                        def evalOnly = boom(10).eval @ kyo.kernel.internal.TraceTest.evalOnly(TraceTest.scala:24)
                """
            )
            ()
        }

        "with effects" taggedAs jsOnly in pendingUntilFixed {
            assertTrace(
                withEffects,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:20)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:22)
                |	at          val z = Kyo.zip(x, y).map(_ + _).map(boom) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                |	at                    val z = Kyo.zip(x, y).map(_ + _) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                |	at                                                   ) @ kyo.kernel.internal.TraceTest.TestEffect$.run(TraceTest.scala:17)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                |	at                                                   ) @ kyo.kernel.internal.TraceTest.TestEffect$.run(TraceTest.scala:17)
                |	at                              TestEffect.run(z).eval @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:30)
                """
            )
            ()
        }
    }

    "no trace if exception is NoStackTrace" - {
        "jvm" taggedAs jvmOnly in {
            import scala.util.control.NoStackTrace
            assertTrace(
                throw new NoStackTrace {},
                "kyo.kernel.internal.TraceTest$$anon$1"
            )
        }
        "js" taggedAs jsOnly in {
            import scala.util.control.NoStackTrace
            assertTrace(
                throw new NoStackTrace {},
                """
                |kyo.kernel.internal.TraceTest$$anon$2
                |  <no stack trace available>
                """
            )
        }
    }

    "bug #1172 null frames" in {
        val safepoint = Safepoint.get // Trace.Owner
        safepoint.pushFrame(null.asInstanceOf[Frame])
        safepoint.enrich(new Exception)
        succeed
    }

    def assertTrace[A](f: => A, expected: String) =
        try
            f
            fail()
        catch
            case ex: Throwable =>
                val stringWriter = new StringWriter()
                val printWriter  = new PrintWriter(stringWriter)
                ex.printStackTrace(printWriter)
                printWriter.flush()
                val full   = stringWriter.toString
                val top    = full.linesIterator.takeWhile(!_.contains("@")).toList
                val bottom = full.linesIterator.drop(top.length).takeWhile(_.contains("@")).toList
                val trace  = (top.mkString("\n") + "\n" + bottom.mkString("\n")).trim()
                assert(trace == expected.stripMargin.trim)

end TraceTest
