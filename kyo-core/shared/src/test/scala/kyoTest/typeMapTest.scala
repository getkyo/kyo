package kyoTest

import kyo.Tag
import kyo.TypeMap

class typeMapTest extends KyoTest:

    private trait A
    private val a = new A {}
    private trait B extends A
    private val b = new B {}
    private trait C
    private val c = new C {}
    private trait D extends A with C
    private val d = new D {}

    "empty" - {
        "TypeMap.empty" in {
            assert(TypeMap.empty.isEmpty)
        }
        "get[A]" in {
            assertDoesNotCompile("""
                  | TypeMap.empty.get[String]
                  |""".stripMargin)
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
        "distinct" in {
            assertDoesNotCompile("TypeMap(0, 0)")
        }
    }
    "fatal" - {
        import scala.util.{Failure, Try}

        def test[A: Tag](e: TypeMap[A], contents: String, tpe: String) =
            Try(e.get[A]) match
                case Failure(error) => assert(
                        error.getMessage == s"fatal: kyo.TypeMap of contents [$contents] missing value of type: [$tpe]."
                    )
                case _ => fail("Expected error")

        "empty" in {
            val e: TypeMap[Boolean] = TypeMap.empty.asInstanceOf[TypeMap[Boolean]]
            test(e, "TypeMap()", "scala.Boolean")
        }
        "non-empty" in {
            val e: TypeMap[String & Boolean] = TypeMap[Boolean](true).asInstanceOf[TypeMap[String & Boolean]]
            test[String](e, """TypeMap(scala.Boolean -> true)""", "java.lang.String")
        }
    }

    ".get" - {
        "A & B" in {
            assertDoesNotCompile(
                """
                  | def intersection[A, B](m: TypeMap[A & B]) =
                  |     m.get[A & B]
                  |""".stripMargin
            )
        }
        "A | B" in {
            assertDoesNotCompile(
                """
                  | def union[A, B](m: TypeMap[A & B]) =
                  |     m.get[A | B]
                  |""".stripMargin
            )
        }
        "subtype" in {
            val e: TypeMap[A & B] = TypeMap(b)

            assert(e.get[A] eq b)
        }
    }

    ".add" - {
        "TypeMap[Int] -> TypeMap[Int & Boolean]" in {
            val e1: TypeMap[Int]           = TypeMap(42)
            val e2: TypeMap[Int & Boolean] = e1.add(true)
            assert(e2.get[Int] == 42)
            assert(e2.get[Boolean])
        }
        "A & B" in {
            assertDoesNotCompile("""
                  | def intersection[A, B](ab: A & B) =
                  |     TypeMap.empty.add(ab)
                  |""".stripMargin)
        }
        "A | B" in {
            assertDoesNotCompile("""
                  | def union[A, B](ab: A | B) =
                  |     TypeMap.empty.add(ab)
                  |""".stripMargin)
        }
        "cannot narrow" in {
            val e1: TypeMap[A] = TypeMap(a)
            assertDoesNotCompile("""
                  | e1.add[B](b)
                  |""".stripMargin)
        }
        "cannot widen" in {
            val e1: TypeMap[B] = TypeMap(b)
            assertDoesNotCompile("""
                  | e1.add[A](a)
                  |""".stripMargin)
        }
    }

    ".replace" - {
        "replaces" in {
            val e1: TypeMap[A] = TypeMap(a)
            val e2             = e1.replace(b)
            assert(e2.size == 1)
            assert(e2.get[A] eq b)
            assert(e2.get[B] eq b)
        }
        "narrows" in {
            val e1: TypeMap[A] = TypeMap(a)
            val e2             = e1.replace(b)
            assert(e2.size == 1)
            assert(e2.get[A] eq b)
            assert(e2.get[B] eq b)
        }
        "intersection" in {
            val e1: TypeMap[A & C] = TypeMap(a).add(c)
            val e2                 = e1.replace(b)
            assert(e2.size == 2)
            assert(e2.get[A] eq b)
            assert(e2.get[B] eq b)
            assert(e2.get[C] eq c)
        }
    }

    ".replaceAll" - {
        "superset" in {
            val e1: TypeMap[A & C] = TypeMap(a).add(c)
            val e2                 = e1.replaceAll(d)
            assert(e2.size == 1)
            assert(e2.get[A] eq d)
            assert(e2.get[C] eq d)
            assert(e2.get[D] eq d)
        }
    }

    ".union" - {
        "TypeMap[Int] + TypeMap[Char] -> TypeMap[Int & Char]" in {
            val e1: TypeMap[Int]        = TypeMap(42)
            val e2: TypeMap[Char]       = TypeMap('c')
            val e3: TypeMap[Int & Char] = e1.union(e2)
            assert(e3.get[Int] == 42)
            assert(e3.get[Char] == 'c')
        }
        "must be distinct" in {
            val e1: TypeMap[Int & Char] = TypeMap(42).add('c')
            val e2: TypeMap[Char]       = TypeMap('c')
            assertDoesNotCompile("""
                  |val e3 = e1.union(e2)
                  |""".stripMargin)
        }
    }

    ".merge" - {
        "intersection" in {
            val e1: TypeMap[A & C] = TypeMap(a).add(c)
            val e2: TypeMap[B]     = TypeMap(b)
            val e3                 = e1.merge(e2)
            assert(e3.size == 2)
            assert(e3.get[A] eq b)
            assert(e3.get[B] eq b)
            assert(e3.get[C] eq c)
        }
        "superset" in {
            val e1: TypeMap[A & C] = TypeMap(a).add(c)
            val e2: TypeMap[D]     = TypeMap(d)
            val e3                 = e1.merge(e2)
            assert(e3.size == 1)
            assert(e3.get[A] eq d)
            assert(e3.get[C] eq d)
            assert(e3.get[D] eq d)
        }
    }

    ".prune" - {
        "[Any] noop" in {
            val e: TypeMap[Int & String & Boolean & Char] = TypeMap(42, "", true, 'c')
            val p: TypeMap[Any]                           = e.prune[Any]
            assert(p.size == 4)
            assert(p.asInstanceOf[AnyRef] eq e.asInstanceOf[AnyRef])
        }
        "Env[Int & String] -> Env[Int]" in {
            val e: TypeMap[Int & String] = TypeMap(42, "")
            val p: TypeMap[Int]          = e.prune[Int]
            assert(p.size == 1)
        }
        "Env[Sub] -> Env[Super]" in {
            val e: TypeMap[RuntimeException] = TypeMap(new RuntimeException)
            val p: TypeMap[Throwable]        = e.prune[Exception].prune[Throwable]
            assert(p.size == 1)
        }
        "Env[Super] -> Env[Sub]" in {
            assertDoesNotCompile("""
                  | val e = TypeMap(new Throwable)
                  | val p = e.prune[Exception]
                  |""".stripMargin)
        }
        "intersection" in pendingUntilFixed {
            assertCompiles("""
                |val e = TypeMap(true, "")
                |val p = e.prune[Boolean & String]
                |assert(p.size == 2)
                |""".stripMargin)
        }
    }

    ".show" - {
        "many" in {
            val t = TypeMap("str", true, 42, 'c').add(None).add(List[Any]()).add(Map('k' -> 'v'))
            val expected =
                "TypeMap(" +
                    "java.lang.String -> str, " +
                    "scala.Boolean -> true, " +
                    "scala.Char -> c, " +
                    "scala.Int -> 42, " +
                    "scala.None$ -> None, " +
                    "scala.collection.immutable.List -> List(), " +
                    "scala.collection.immutable.Map -> Map(k -> v)" +
                    ")"
            assert(t.show == expected)
        }
    }

end typeMapTest
