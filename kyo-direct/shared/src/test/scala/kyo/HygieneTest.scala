package kyo

import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class HygieneTest extends AnyFreeSpec with Assertions:

    "use of var" in {
        assertDoesNotCompile("""
          defer {
            var willFail = 1
            IO(1).now
          }
        """)
    }

    "use of return" in {
        assertDoesNotCompile("""
          defer {
            return 42
            IO(1).now
          }
        """)
    }

    "nested defer block" in {
        assertDoesNotCompile("""
          defer {
            defer {
              IO(1).now
            }
          }
        """)
    }

    "lazy val" in {
        assertDoesNotCompile("""
          defer {
            lazy val x = 10
            IO(1).now
          }
        """)
    }

    "function containing await" in {
        assertDoesNotCompile("""
          defer {
            def foo() = IO(1).now
            foo()
          }
        """)
    }

    "try/catch" in {
        assertDoesNotCompile("""
          defer {
            try {
              IO(1).now
            } catch {
              case _: Exception => IO(2).now
            }
          }
        """)
    }

    "class declaration" in {
        assertDoesNotCompile("""
          defer {
            class A(val x: Int)
            IO(1).now
          }
        """)
    }

    "object declaration" in {
        assertDoesNotCompile("""
          defer {
            object A
            IO(1).now
          }
        """)
    }

    "trait declaration" in {
        assertDoesNotCompile("""
          defer {
            trait A
            IO(1).now
          }
        """)
    }

    "for-comprehension" in {
        assertDoesNotCompile("""
          defer {
            for {
              x <- IO(1).now
              y <- IO(2).now
            } yield x + y
          }
        """)
    }

    "try without catch or finally" in {
        assertDoesNotCompile("""
          defer {
            try {
              IO(1).now
            }
          }
        """)
    }

    "try with only finally" in {
        assertDoesNotCompile("""
          defer {
            try {
              IO(1).now
            } finally {
              println("Cleanup")
            }
          }
        """)
    }

    "new instance with by-name parameter" in {
        assertDoesNotCompile("""
          class A(x: => String)
          defer {
              new A(IO("blah").now)
          }
        """)
    }

    "match expression without cases" in {
        assertDoesNotCompile("""
          defer {
            IO(1).now match {}
          }
        """)
    }

    "for-comprehension without yield" in {
        assertDoesNotCompile("""
          defer {
            for {
              x <- IO(1).now
              y <- IO(2).now
            } x + y
          }
        """)
    }

    "nested functions" in {
        assertDoesNotCompile("""
          defer {
            def outer() = {
              def inner() = IO(1).now
              inner()
            }
            outer()
          }
        """)
    }

    "lambdas with await" in {
        assertDoesNotCompile("""
          defer {
            val f = (x: Int) => IO(1).now + x
            f(10)
          }
        """)
    }

    "throw" in {
        assertDoesNotCompile("""
          defer {
              if IO("foo").now == "bar" then
                  throw new Exception
              else
                  2
          }
        """)
    }

    "synchronized" in {
        assertDoesNotCompile("""
          defer {
              val x = synchronized(1)
              IO(x).now
          }
        """)
    }
end HygieneTest
