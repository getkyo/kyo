package kyo

import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class HygieneTest extends AnyFreeSpec with Assertions:

    "use of var" in {
        assertDoesNotCompile("""
          defer {
            var willFail = 1
            ~IO(1)
          }
        """)
    }

    "use of return" in {
        assertDoesNotCompile("""
          defer {
            return 42
            ~IO(1)
          }
        """)
    }

    "nested defer block" in {
        assertDoesNotCompile("""
          defer {
            defer {
              ~IO(1)
            }
          }
        """)
    }

    "lazy val" in {
        assertDoesNotCompile("""
          defer {
            lazy val x = 10
            ~IO(1)
          }
        """)
    }

    "function containing await" in {
        assertDoesNotCompile("""
          defer {
            def foo() = ~IO(1)
            foo()
          }
        """)
    }

    "try/catch" in {
        assertDoesNotCompile("""
          defer {
            try {
              ~IO(1)
            } catch {
              case _: Exception => ~IO(2)
            }
          }
        """)
    }

    "class declaration" in {
        assertDoesNotCompile("""
          defer {
            class A(val x: Int)
            ~IO(1)
          }
        """)
    }

    "object declaration" in {
        assertDoesNotCompile("""
          defer {
            object A
            ~IO(1)
          }
        """)
    }

    "trait declaration" in {
        assertDoesNotCompile("""
          defer {
            trait A
            ~IO(1)
          }
        """)
    }

    "for-comprehension" in {
        assertDoesNotCompile("""
          defer {
            for {
              x <- ~IO(1)
              y <- ~IO(2)
            } yield x + y
          }
        """)
    }

    "try without catch or finally" in {
        assertDoesNotCompile("""
          defer {
            try {
              ~IO(1)
            }
          }
        """)
    }

    "try with only finally" in {
        assertDoesNotCompile("""
          defer {
            try {
              ~IO(1)
            } finally {
              println("Cleanup")
            }
          }
        """)
    }

    "new instance with by-name parameter" in {
        assertDoesNotCompile("""
          defer {
            class A(x: => Int)
            new A(~IO(1))
          }
        """)
    }

    "match expression without cases" in {
        assertDoesNotCompile("""
          defer {
            ~IO(1) match {}
          }
        """)
    }

    "for-comprehension without yield" in {
        assertDoesNotCompile("""
          defer {
            for {
              x <- ~IO(1)
              y <- ~IO(2)
            } x + y
          }
        """)
    }

    "nested functions" in {
        assertDoesNotCompile("""
          defer {
            def outer() = {
              def inner() = ~IO(1)
              inner()
            }
            outer()
          }
        """)
    }

    "lambdas with await" in {
        assertDoesNotCompile("""
          defer {
            val f = (x: Int) => ~IO(1) + x
            f(10)
          }
        """)
    }
end HygieneTest
