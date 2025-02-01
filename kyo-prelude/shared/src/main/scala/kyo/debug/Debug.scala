package kyo.debug

import kyo.*
import kyo.Ansi.*
import kyo.kernel.Effect
import kyo.kernel.internal.Safepoint
import scala.collection.mutable.LinkedHashMap
import scala.language.implicitConversions
import scala.quoted.*

object Debug:

    private inline def maxValueLength = 500

    /** Applies debugging to the given effect, printing the frame and value.
      *
      * @param v
      *   The effect to debug
      * @param frame
      *   The implicit Frame
      * @return
      *   The original effect with debugging applied
      */
    def apply[A, S](v: => A < S)(using frame: Frame): A < S =
        Effect.catching {
            v.map { value =>
                println(frame.render)
                printValue(value)
                value
            }
        } { ex =>
            println(frame.render)
            printValue(ex)
            throw ex
        }
    end apply

    /** Traces the execution of the given effect, printing intermediate values and frames.
      *
      * @param v
      *   The effect to trace
      * @param Frame
      *   The implicit Frame
      * @return
      *   The original effect with tracing applied
      */
    def trace[A, S](v: => A < S)(using Frame): A < S =
        val interceptor = new Safepoint.Interceptor:
            var lastFrame = Frame.internal
            var lastValue = Maybe.empty[Any]
            def enter(frame: Frame, value: Any): Boolean =
                if frame ne lastFrame then
                    lastValue.foreach(printValue)
                    println(frame.render)
                    lastFrame = frame
                end if
                lastValue = Present(value)
                true
            end enter
            def addFinalizer(f: () => Unit): Unit    = ()
            def removeFinalizer(f: () => Unit): Unit = ()

        Safepoint.propagating(interceptor) {
            Effect.catching {
                v.map { value =>
                    printValue(value)
                    value
                }
            } { ex =>
                printValue(ex)
                throw ex
            }
        }
    end trace

    /** Prints the values of the given parameters along with their code representations.
      *
      * @param params
      *   The parameters to print
      * @param frame
      *   The implicit Frame
      */
    def values(params: Param[?]*)(using frame: Frame): Unit =
        val tuples = LinkedHashMap(params.map(p => (p.code, p.value))*)
        val string = pprint(tuples).render.replaceFirst("LinkedHashMap", "Params")
        println(frame.render)
        println(string)
    end values

    final case class Param[T](code: String, value: T) derives CanEqual

    object Param:

        /** Derives a Param instance from a given value.
          *
          * @param v
          *   The value to derive the Param from
          * @return
          *   A Param instance containing the code representation and value
          */
        implicit inline def derive[T](v: => T): Param[T] =
            ${ paramImpl('v) }

        private def paramImpl[T: Type](v: Expr[T])(using Quotes): Expr[Param[T]] =
            import quotes.reflect.*
            val code = Expr(v.asTerm.pos.sourceCode.get)
            '{ Param($code, $v) }
        end paramImpl

    end Param

    private def printValue(value: Any) =
        println("───────────────────────────────────".dim)
        val rendered = pprint(value).render
        val truncated =
            if rendered.length > maxValueLength then
                rendered.take(maxValueLength) + " ... (truncated)"
            else
                rendered
        println(truncated)
        println("───────────────────────────────────".dim)
    end printValue
end Debug
