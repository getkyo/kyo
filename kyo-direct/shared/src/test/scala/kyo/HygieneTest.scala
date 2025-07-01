package kyo

class HygieneTest extends Test:

    "use of var" in {
        typeCheckFailure("""
          direct {
            var willFail = 1
            Sync(1).now
          }
        """)(
            "`var` declarations are not allowed inside a `direct` block."
        )
    }

    "use of return" in {
        typeCheckFailure("""
          direct {
            return 42
            Sync(1).now
          }
        """)(
            "Exception occurred while executing macro expansion"
        )
    }

    "nested direct block" in {
        typeCheckFailure("""
          direct {
            direct {
              Sync(1).now
            }
          }
        """)(
            "Effectful computations must explicitly use either .now or .later in a direct block."
        )
    }

    "lazy val" in {
        typeCheckFailure("""
          direct {
            lazy val x = 10
            Sync(1).now
          }
        """)(
            "`lazy val` and `object` declarations are not allowed inside a `direct` block."
        )
    }

    "function containing await" in {
        typeCheckFailure("""
          direct {
            def foo() = Sync(1).now
            foo()
          }
        """)(
            "Method definitions containing .now are not supported inside `direct` blocks."
        )
    }

    "try/catch" in {
        typeCheckFailure("""
          direct {
            try {
              Sync(1).now
            } catch {
              case _: Exception => Sync(2).now
            }
          }
        """)(
            "`try`/`catch` blocks are not supported inside `direct` blocks."
        )
    }

    "class declaration" in {
        typeCheckFailure("""
          direct {
            class A(val x: Int)
            Sync(1).now
          }
        """)(
            "`class` and `trait` declarations are not allowed inside `direct` blocks."
        )
    }

    "object declaration" in {
        typeCheckFailure("""
          direct {
            object A
            Sync(1).now
          }
        """)(
            "`class` and `trait` declarations are not allowed inside `direct` blocks."
        )
    }

    "trait declaration" in {
        typeCheckFailure("""
          direct {
            trait A
            Sync(1).now
          }
        """)(
            "`class` and `trait` declarations are not allowed inside `direct` blocks."
        )
    }

    "for-comprehension" in {
        typeCheckFailure("""
          direct {
            for {
              x <- Sync(1).now
              y <- Sync(2).now
            } yield x + y
          }
        """)(
            "value flatMap is not a member of Int"
        )
    }

    "try without catch or finally" in {
        typeCheckFailure("""
          direct {
            try {
              Sync(1).now
            }
          }
        """)(
            "`try`/`catch` blocks are not supported inside `direct` blocks."
        )
    }

    "try with only finally" in {
        typeCheckFailure("""
          direct {
            try {
              Sync(1).now
            } finally {
              println("Cleanup")
            }
          }
        """)(
            "`try`/`catch` blocks are not supported inside `direct` blocks."
        )
    }

    "new instance with by-name parameter" in {
        typeCheckFailure("""
          class A(x: => String)
          direct {
              new A(Sync("blah").now)
          }
        """)(
            "Can't find AsyncShift (Found:    cps.runtime.CpsMonadSelfAsyncShift[[A] =>> A < kyo.Sync"
        )
    }

    "match expression without cases" in {
        typeCheckFailure("""
          direct {
            Sync(1).now match {}
          }
        """)(
            "case' expected, but '}' found"
        )
    }

    "for-comprehension without yield" in {
        typeCheckFailure("""
          direct {
            for {
              x <- Sync(1).now
              y <- Sync(2).now
            } x + y
          }
        """)(
            "value foreach is not a member of Int"
        )
    }

    "nested functions" in {
        typeCheckFailure("""
          direct {
            def outer() = {
              def inner() = Sync(1).now
              inner()
            }
            outer()
          }
        """)(
            "Method definitions containing .now are not supported inside `direct` blocks"
        )
    }

    "lambdas with await" in {
        typeCheckFailure("""
          direct {
            val f = (x: Int) => Sync(1).now + x
            f(10)
          }
        """)(
            "Method definitions containing .now are not supported inside `direct` blocks."
        )
    }

    "throw" in {
        typeCheckFailure("""
          direct {
              if Sync("foo").now == "bar" then
                  throw new Exception
              else
                  2
          }
        """)(
            "`throw` expressions are not allowed inside a `direct` block."
        )
    }

    "synchronized" in {
        typeCheckFailure("""
          direct {
              val x = synchronized(1)
              Sync(x).now
          }
        """)(
            "`synchronized` blocks are not allowed inside a `direct` block."
        )
    }

    "nested var" in {
        typeCheckFailure("""direct {{var x = 1; Sync(x)}.now}""")("`var` declarations are not allowed inside a `direct` block.")
    }

    "nested nested var" in {
        typeCheckFailure("""direct {{val y = 1;{var x = 1; Sync(x)}}.now}""")("`var` declarations are not allowed inside a `direct` block.")
    }

    "nested now in def" in {
        typeCheckFailure("""
             direct {
               val i = Sync(1).later
               def f =  i.now > 0
               f
             }""")("Method definitions containing .now are not supported inside `direct` blocks.")
    }

    "asyncShift explicit .later" in {
        typeCheckFailure(
            """
              val default:Int < Any = 2
              val value = scala.util.Try(1)
              direct(value.getOrElse(default))
             """
        )("Effectful computations must explicitly use either .now or .later in a direct block.")
    }

    "direct drop" in {
        typeCheckFailure(
            """
                 val default: Unit < Abort[String] = ()
                 val x: Unit < Emit[Int] = direct(default.now)
                 
               """.stripMargin
        )("Cannot lift `Unit < kyo.Abort[scala.Predef.String]` to the expected type (`Unit < ?`).")
    }

    "opaque types issue #993" in {
        val maybe1: Maybe[Int] < Sync = Maybe(1)
        val maybe0: Maybe[Int]        = Maybe(0)
        direct:
            maybe1.now.fold(2)(_ + 1)
            maybe1.now.contains(1)
            maybe0.contains(1)

        assertionSuccess
    }
end HygieneTest
