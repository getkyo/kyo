package kyo

import Ansi.*
import kyo.kernel.Effect
import kyo.kernel.Safepoint

object Debug:

    def apply[A, S](v: => A < S)(using frame: Frame): A < S =
        Effect.catching {
            v.map { value =>
                println(frame.show)
                printValue(value)
                value
            }
        } { ex =>
            println(frame.show)
            printValue(ex)
            throw ex
        }
    end apply

    def trace[A, S](v: => A < S): A < S =
        val interceptor = new Safepoint.Interceptor:
            def enter(frame: Frame, value: Any): Boolean =
                printValue(value)
                println(frame.parse.show)
                true
            end enter
            def addEnsure(f: () => Unit): Unit    = ()
            def removeEnsure(f: () => Unit): Unit = ()

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

    private def printValue(value: Any) =
        println("──────────────────────────────".dim)
        println(pprint(value).render)
        println("──────────────────────────────".dim)
    end printValue
end Debug
