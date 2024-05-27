package kyoTest

import kyo.*

class typeMapTest extends KyoTest:
    "empty" - {
        "TypeMap.empty" in {
            assert(TypeMap.empty.isEmpty)
        }
        "get[A]" in {
            assertDoesNotCompile(
                """
                  | TypeMap.empty.get[String]
                  |""".stripMargin
            )
        }
    }
    "single" - {
        "TypeMap[String]" in {
            val e = TypeMap("Hello")
            assert(e.get[String] == "Hello")
            assert(e.size == 1)
        }
        "TypeMap[Int]" in {
            val e = TypeMap(123)
            assert(e.get[Int] == 123)
            assert(e.size == 1)
        }
        "TypeMap[trait]" in {
            trait A
            given CanEqual[A, A] = CanEqual.derived
            val a                = new A {}
            val e                = TypeMap(a)
            assert(e.get[A] == a)
            assert(e.size == 1)
        }
    }
    "intersection" - {
        "two" in {
            val e = TypeMap("Hello", 123)
            assert(e.get[String] == "Hello")
            assert(e.get[Int] == 123)
            assert(e.size == 2)
        }
        "three" in {
            val e = TypeMap("Hello", 123, true)
            assert(e.get[String] == "Hello")
            assert(e.get[Int] == 123)
            assert(e.get[Boolean])
            assert(e.size == 3)
        }
        "four" in {
            val e = TypeMap("Hello", 123, true, 'c')
            assert(e.get[String] == "Hello")
            assert(e.get[Int] == 123)
            assert(e.get[Boolean])
            assert(e.get[Char] == 'c')
            assert(e.size == 4)
        }
        "distinct" in pendingUntilFixed {
            assertDoesNotCompile("TypeMap(0, 0)")
        }
    }
    "fatal" - {
        import scala.util.Try
        import scala.util.Failure

        def test[A: Tag](e: TypeMap[A], contents: String, tpe: String) =
            Try(e.get[A]) match
                case Failure(error) => assert(
                        error.getMessage == s"fatal: kyo.TypeMap of contents [$contents] missing value of type: [$tpe]."
                    )
                case _ => fail("Expected error")

        "empty" in {
            val e: TypeMap[Boolean] = TypeMap.empty.asInstanceOf[TypeMap[Boolean]]
            test(e, "HashMap()", "scala.Boolean")
        }
        "non-empty" in {
            val e: TypeMap[String & Boolean] = TypeMap[Boolean](true).asInstanceOf[TypeMap[String & Boolean]]
            test[String](e, """HashMap(Tag(-4scala.Boolean;,%scala.AnyVal;"b!M;"V!A;) -> true)""", "java.lang.String")
        }
    }

    "add" - {
        "TypeMap[Int] -> TypeMap[Int & Boolean]" in {
            val e1: TypeMap[Int]           = TypeMap(42)
            val e2: TypeMap[Int & Boolean] = e1.add(true)
            assert(e2.get[Int] == 42)
            assert(e2.get[Boolean])
        }
    }

    "union" - {
        "TypeMap[Int] + TypeMap[Char] -> TypeMap[Int & Char]" in {
            val e1: TypeMap[Int]        = TypeMap(42)
            val e2: TypeMap[Char]       = TypeMap('c')
            val e3: TypeMap[Int & Char] = e1.union(e2)
            assert(e3.get[Int] == 42)
            assert(e3.get[Char] == 'c')
        }
    }

    "prune" - {
        "Env[Int & String] -> Env[Int]" in pendingUntilFixed {
            assertCompiles(
                """
                  | val e = TypeMap(42, "")
                  | val p = e.prune[Int]
                  | assert(p.size == 1)
                  |""".stripMargin
            )
        }
    }

end typeMapTest
