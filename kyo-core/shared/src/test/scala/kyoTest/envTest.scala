package kyoTest

import kyo.*

class envTest extends KyoTest:
    "empty" - {
        "Env.empty" in {
            assert(Env.empty.isEmpty)
        }
        "get[A]" in {
            assertDoesNotCompile(
                """
                  | Env.empty.get[String]
                  |""".stripMargin
            )
        }
    }
    "single" - {
        "Env[String]" in {
            val e = Env("Hello")
            assert(e.get[String] == "Hello")
            assert(e.size == 1)
        }
        "Env[Int]" in {
            val e = Env(123)
            assert(e.get[Int] == 123)
            assert(e.size == 1)
        }
        "Env[trait]" in {
            trait A
            given CanEqual[A, A] = CanEqual.derived
            val a                = new A {}
            val e                = Env(a)
            assert(e.get[A] == a)
            assert(e.size == 1)
        }
    }
    "intersection" - {
        "two" in {
            val e = Env("Hello", 123)
            assert(e.get[String] == "Hello")
            assert(e.get[Int] == 123)
            assert(e.size == 2)
        }
        "three" in {
            val e = Env("Hello", 123, true)
            assert(e.get[String] == "Hello")
            assert(e.get[Int] == 123)
            assert(e.get[Boolean])
            assert(e.size == 3)
        }
        "four" in {
            val e = Env("Hello", 123, true, 'c')
            assert(e.get[String] == "Hello")
            assert(e.get[Int] == 123)
            assert(e.get[Boolean])
            assert(e.get[Char] == 'c')
            assert(e.size == 4)
        }
        "distinct" in pendingUntilFixed {
            assertDoesNotCompile("Env(0, 0)")
        }
    }
    "fatal" - {
        import scala.util.Try
        import scala.util.Failure

        def test[T: Tag](e: Env[T], contents: String, tpe: String) =
            Try(e.get[T]) match
                case Failure(error) => assert(
                        error.getMessage == s"fatal: kyo.Env of contents [$contents] missing value of type: [$tpe]."
                    )
                case _ => fail("Expected error")

        "empty" in {
            val e: Env[Boolean] = Env.empty.asInstanceOf[Env[Boolean]]
            test(e, "HashMap()", "scala.Boolean")
        }
        "non-empty" in {
            val e: Env[String & Boolean] = Env[Boolean](true).asInstanceOf[Env[String & Boolean]]
            test[String](e, """HashMap(Tag(-4scala.Boolean;,%scala.AnyVal;"b!M;"V!A;) -> true)""", "java.lang.String")
        }
    }

    "add" - {
        "Env[Int] -> Env[Int & Boolean]" in {
            val e1: Env[Int]           = Env(42)
            val e2: Env[Int & Boolean] = e1.add(true)
            assert(e2.get[Int] == 42)
            assert(e2.get[Boolean])
        }
    }

    "union" - {
        "Env[Int] + Env[Char] -> Env[Int & Char]" in {
            val e1: Env[Int]        = Env(42)
            val e2: Env[Char]       = Env('c')
            val e3: Env[Int & Char] = e1.union(e2)
            assert(e3.get[Int] == 42)
            assert(e3.get[Char] == 'c')
        }
    }

    "prune" - {
        "Env[Int & String] -> Env[Int]" in pendingUntilFixed {
            assertCompiles(
                """
                  | val e = Env(42, "")
                  | val p = e.prune[Int]
                  | assert(p.size == 1)
                  |""".stripMargin
            )
        }
    }

end envTest
