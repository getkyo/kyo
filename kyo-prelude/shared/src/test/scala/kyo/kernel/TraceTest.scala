package kyo.kernel

import java.io.PrintWriter
import java.io.StringWriter
import kyo.*
import kyo.Tagged.*

class TraceTest extends Test:

    def ex = new Exception("test exception")

    def boom[S](x: Int < S): Int < S = x.map(_ => throw ex)

    def evalOnly = boom(10).eval

    def withEffects =
        val x = Env.use[Int](_ + 1)
        val y = Env.use[Int](_ * 2)
        val z = Kyo.zip(x, y).map(_ + _).map(boom)
        Env.run(1)(z).eval
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
                |	at kyo.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at                        def evalOnly = boom(10).eval @ kyo.kernel.TraceTest.evalOnly(TraceTest.scala:14)
                """
            )
        }

        "with effects" taggedAs jvmOnly in {
            assertTrace(
                withEffects,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at          val z = Kyo.zip(x, y).map(_ + _).map(boom) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at                    val z = Kyo.zip(x, y).map(_ + _) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at                         val y = Env.use[Int](_ * 2) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:18)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at                         val x = Env.use[Int](_ + 1) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:17)
                |	at                                  Env.run(1)(z).eval @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:20)
                """
            )
        }

        "repeated frames" taggedAs jvmOnly in {
            assertTrace(
                repeatedFrames,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at          else ((depth - 1): Int < Any).map(loop(_)) @ kyo.kernel.TraceTest.loop(TraceTest.scala:25)                
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
                |	at kyo.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at                        def evalOnly = boom(10).eval @ kyo.kernel.TraceTest.evalOnly(TraceTest.scala:14)
                """
            )
            ()
        }

        "with effects" taggedAs jsOnly in pendingUntilFixed {
            assertTrace(
                withEffects,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at          val z = Kyo.zip(x, y).map(_ + _).map(boom) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at                    val z = Kyo.zip(x, y).map(_ + _) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at                         val y = Env.use[Int](_ * 2) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:18)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at                         val x = Env.use[Int](_ + 1) @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:17)
                |	at                                  Env.run(1)(z).eval @ kyo.kernel.TraceTest.withEffects(TraceTest.scala:20)
                """
            )
            ()
        }

        "repeated frames" taggedAs jvmOnly in {
            assertTrace(
                repeatedFrames,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at          else ((depth - 1): Int < Any).map(loop(_)) @ kyo.kernel.TraceTest.loop(TraceTest.scala:25)                
                """
            )
        }
    }

    "no trace if exception is NoStackTrace" - {
        "jvm" taggedAs jvmOnly in {
            import scala.util.control.NoStackTrace
            assertTrace(
                throw new NoStackTrace {},
                "kyo.kernel.TraceTest$$anon$1"
            )
        }
        "js" taggedAs jsOnly in {
            import scala.util.control.NoStackTrace
            assertTrace(
                throw new NoStackTrace {},
                """
                |kyo.kernel.TraceTest$$anon$2
                |  <no stack trace available>                
                """
            )
        }
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
