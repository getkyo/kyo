import kyo._
import kyo.ios._
import kyo.direct._
import kyo.TestSupport._
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class hygieneTest extends AnyFreeSpec with Assertions {

  "use of var" in {
    assertDoesNotCompile("""
        defer {
          var x = 1
          await(IOs(1))
        }
      """)
  }

  "use of return" in {
    assertDoesNotCompile("""
        defer {
          return 42
          await(IOs(1))
        }
      """)
  }

  "nested defer block" in {
    assertDoesNotCompile("""
        defer {
          defer {
            await(IOs(1))
          }
        }
      """)
  }

  "lazy val" in {
    assertDoesNotCompile("""
        defer {
          lazy val x = 10
          await(IOs(1))
        }
      """)
  }

  "function containing await" in {
    assertDoesNotCompile("""
        defer {
          def foo() = await(IOs(1))
          foo()
        }
      """)
  }

  "try/catch" in {
    assertDoesNotCompile("""
        defer {
          try {
            await(IOs(1))
          } catch {
            case _: Exception => await(IOs(2))
          }
        }
      """)
  }

  "class declaration" in {
    assertDoesNotCompile("""
        defer {
          class A(val x: Int)
          await(IOs(1))
        }
      """)
  }

  "object declaration" in {
    assertDoesNotCompile("""
        defer {
          object A
          await(IOs(1))
        }
      """)
  }

  "trait declaration" in {
    assertDoesNotCompile("""
        defer {
          trait A
          await(IOs(1))
        }
      """)
  }

  "for-comprehension" in {
    assertDoesNotCompile("""
        defer {
          for {
            x <- await(IOs(1))
            y <- await(IOs(2))
          } yield x + y
        }
      """)
  }

  "throw expression" in {
    assertDoesNotCompile("""
        defer {
          throw new RuntimeException("Error!")
          await(IOs(1))
        }
      """)
  }
  "try without catch or finally" in {
    assertDoesNotCompile("""
        defer {
          try {
            await(IOs(1))
          }
        }
      """)
  }

  "try with only finally" in {
    assertDoesNotCompile("""
        defer {
          try {
            await(IOs(1))
          } finally {
            println("Cleanup")
          }
        }
      """)
  }

  "by-name parameters" in {
    assertDoesNotCompile("""
        defer {
          def foo(x: => Int) = x + 1
          foo(await(IOs(1)))
        }
      """)
  }

  "new instance with by-name parameter" in {
    assertDoesNotCompile("""
        defer {
          class A(x: => Int)
          new A(await(IOs(1)))
        }
      """)
  }

  "match expression without cases" in {
    assertDoesNotCompile("""
        defer {
          await(IOs(1)) match {}
        }
      """)
  }

  "for-comprehension without yield" in {
    assertDoesNotCompile("""
        defer {
          for {
            x <- await(IOs(1))
            y <- await(IOs(2))
          } x + y
        }
      """)
  }

  "nested functions" in {
    assertDoesNotCompile("""
        defer {
          def outer() = {
            def inner() = await(IOs(1))
            inner()
          }
          outer()
        }
      """)
  }

  "lambdas with await" in {
    assertDoesNotCompile("""
        defer {
          val f = (x: Int) => await(IOs(1)) + x
          f(10)
        }
      """)
  }
}
