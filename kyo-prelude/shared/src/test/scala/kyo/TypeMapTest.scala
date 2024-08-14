package kyo2

import kyo.Tag
import kyo2.TypeMap

class TypeMapTest extends Test:
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
            abstract class A
            class B extends A
            val b = new B

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
            assertDoesNotCompile(
                """
                  | def union[A, B](ab: A | B) =
                  |     TypeMap.empty.add(ab)
                  |""".stripMargin
            )
        }
        "subtype" in {
            abstract class A
            val a = new A {}
            abstract class B extends A
            val b1 = new B {}

            val e1: TypeMap[A] = TypeMap(a)
            val e2             = e1.add[A](b1)
            assert(e2.get[A] eq b1)
            assertDoesNotCompile(
                """
                  | e2.get[B]
                  |""".stripMargin
            )
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
        "complex" in {
            val e =
                TypeMap
                    .empty
                    .add(new Throwable)
                    .add(new Exception)
                    .add(new RuntimeException)
                    .add(new NullPointerException)
                    .add(new ClassCastException)
            val p = e.prune[RuntimeException]
            assert(p.size == 3)
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

end TypeMapTest
