package kyo

import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class HygieneTest extends Test:

    "use of var" in {
        typeCheckFailure("""
          defer {
            var willFail = 1
            IO(1).now
          }
        """)(
            "`var` declarations are not allowed inside a `defer` block."
        )
    }

    "use of return" in {
        typeCheckFailure("""
          defer {
            return 42
            IO(1).now
          }
        """)(
            "Exception occurred while executing macro expansion"
        )
    }

    "nested defer block" in {
        typeCheckFailure("""
          defer {
            defer {
              IO(1).now
            }
          }
        """)(
            "Effectful computations must explicitly use either .now or .later in a defer block."
        )
    }

    "lazy val" in {
        typeCheckFailure("""
          defer {
            lazy val x = 10
            IO(1).now
          }
        """)(
            "`lazy val` and `object` declarations are not allowed inside a `defer` block."
        )
    }

    "function containing await" in {
        typeCheckFailure("""
          defer {
            def foo() = IO(1).now
            foo()
          }
        """)(
            "Method definitions containing .now are not supported inside `defer` blocks."
        )
    }

    "try/catch" in {
        typeCheckFailure("""
          defer {
            try {
              IO(1).now
            } catch {
              case _: Exception => IO(2).now
            }
          }
        """)(
            "`try`/`catch` blocks are not supported inside `defer` blocks."
        )
    }

    "class declaration" in {
        typeCheckFailure("""
          defer {
            class A(val x: Int)
            IO(1).now
          }
        """)(
            "`class` and `trait` declarations are not allowed inside `defer` blocks."
        )
    }

    "object declaration" in {
        typeCheckFailure("""
          defer {
            object A
            IO(1).now
          }
        """)(
            "`class` and `trait` declarations are not allowed inside `defer` blocks."
        )
    }

    "trait declaration" in {
        typeCheckFailure("""
          defer {
            trait A
            IO(1).now
          }
        """)(
            "`class` and `trait` declarations are not allowed inside `defer` blocks."
        )
    }

    "for-comprehension" in {
        typeCheckFailure("""
          defer {
            for {
              x <- IO(1).now
              y <- IO(2).now
            } yield x + y
          }
        """)(
            "value flatMap is not a member of Int"
        )
    }

    "try without catch or finally" in {
        typeCheckFailure("""
          defer {
            try {
              IO(1).now
            }
          }
        """)(
            "`try`/`catch` blocks are not supported inside `defer` blocks."
        )
    }

    "try with only finally" in {
        typeCheckFailure("""
          defer {
            try {
              IO(1).now
            } finally {
              println("Cleanup")
            }
          }
        """)(
            "`try`/`catch` blocks are not supported inside `defer` blocks."
        )
    }

    "new instance with by-name parameter" in {
        typeCheckFailure("""
          class A(x: => String)
          defer {
              new A(IO("blah").now)
          }
        """)(
            "Can't find AsyncShift (Found:    cps.runtime.CpsMonadSelfAsyncShift[[A] =>> A < kyo.IO"
        )
    }

    "match expression without cases" in {
        typeCheckFailure("""
          defer {
            IO(1).now match {}
          }
        """)(
            "case' expected, but '}' found"
        )
    }

    "for-comprehension without yield" in {
        typeCheckFailure("""
          defer {
            for {
              x <- IO(1).now
              y <- IO(2).now
            } x + y
          }
        """)(
            "value foreach is not a member of Int"
        )
    }

    "nested functions" in {
        typeCheckFailure("""
          defer {
            def outer() = {
              def inner() = IO(1).now
              inner()
            }
            outer()
          }
        """)(
            "Method definitions containing .now are not supported inside `defer` blocks"
        )
    }

    "lambdas with await" in {
        typeCheckFailure("""
          defer {
            val f = (x: Int) => IO(1).now + x
            f(10)
          }
        """)(
            "async lambda can't be result of expression"
        )
    }

    "throw" in {
        typeCheckFailure("""
          defer {
              if IO("foo").now == "bar" then
                  throw new Exception
              else
                  2
          }
        """)(
            "`throw` expressions are not allowed inside a `defer` block."
        )
    }

    "synchronized" in {
        typeCheckFailure("""
          defer {
              val x = synchronized(1)
              IO(x).now
          }
        """)(
            "`synchronized` blocks are not allowed inside a `defer` block."
        )
    }
end HygieneTest
