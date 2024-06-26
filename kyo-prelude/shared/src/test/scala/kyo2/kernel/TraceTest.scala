package kyo2.kernel

import java.io.PrintWriter
import java.io.StringWriter
import kyo2.*
import kyo2.Tagged.jvmOnly

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

    "jvm" - {
        "only eval" taggedAs jvmOnly in {
            assertTrace(
                evalOnly,
                """
                |java.lang.Exception: test exception
                |	at kyo2.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at  x.map(_ => throw ex) @ kyo2.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at         boom(10).eval @ kyo2.kernel.TraceTest.evalOnly(TraceTest.scala:14)
                |	at kyo2.kernel.TraceTest.evalOnly(TraceTest.scala:14)                
                """
            )
        }

        "with effects" taggedAs jvmOnly in {
            assertTrace(
                withEffects,
                """
                |java.lang.Exception: test exception
                |	at kyo2.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at       x.map(_ => throw ex) @ kyo2.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at  , y).map(_ + _).map(boom) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at   Kyo.zip(x, y).map(_ + _) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at              Kyo.zip(x, y) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at        Env.use[Int](_ * 2) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:18)
                |	at              Kyo.zip(x, y) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at        Env.use[Int](_ + 1) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:17)
                |	at         Env.run(1)(z).eval @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:20)
                |	at kyo2.kernel.TraceTest.withEffects(TraceTest.scala:20)                
                """
            )
        }
    }

    // TODO The logic that finds the position of the stack to insert the trace
    // frames breaks in JS because the generated JS doesn't keep the file names
    // and positions. It doesn't fail, only generates a stack trace with Kyo's
    // frames at the wrong poition.
    "js" - {
        "only eval" taggedAs jvmOnly in pendingUntilFixed {
            assertTrace(
                evalOnly,
                """
                |java.lang.Exception: test exception
                |	at kyo2.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at  x.map(_ => throw ex) @ kyo2.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at         boom(10).eval @ kyo2.kernel.TraceTest.evalOnly(TraceTest.scala:14)
                |	at kyo2.kernel.TraceTest.evalOnly(TraceTest.scala:14)      
                """
            )
            ()
        }

        "with effects" taggedAs jvmOnly in pendingUntilFixed {
            assertTrace(
                withEffects,
                """
                |java.lang.Exception: test exception
                |	at kyo2.kernel.TraceTest.ex(TraceTest.scala:10)
                |	at       x.map(_ => throw ex) @ kyo2.kernel.TraceTest.boom(TraceTest.scala:12)
                |	at  , y).map(_ + _).map(boom) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at   Kyo.zip(x, y).map(_ + _) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at              Kyo.zip(x, y) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at        Env.use[Int](_ * 2) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:18)
                |	at              Kyo.zip(x, y) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:19)
                |	at        Env.use[Int](_ + 1) @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:17)
                |	at         Env.run(1)(z).eval @ kyo2.kernel.TraceTest.withEffects(TraceTest.scala:20)
                |	at kyo2.kernel.TraceTest.withEffects(TraceTest.scala:20)  
                """
            )
            ()
        }
    }

    def assertTrace[T](f: => T, expected: String) =
        try
            f
            fail()
        catch
            case ex: Throwable =>
                val stringWriter = new StringWriter()
                val printWriter  = new PrintWriter(stringWriter)
                ex.printStackTrace(printWriter)
                printWriter.flush()
                val trace = stringWriter.toString.linesIterator.takeWhile(!_.contains("$init$$$anonfun")).mkString("\n")
                assert(trace == expected.stripMargin.trim)

end TraceTest
