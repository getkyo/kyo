package kyo

import izumi.reflect.Tag as ITag
import kyo.*
import kyo.Tag.Type.*
import kyo.internal.RegisterFunction
import kyo.internal.TagTestMacro.test
import scala.annotation.nowarn
import scala.concurrent.Future

class TagTest extends Test:

    "without variance" - {
        "equal tags" - {
            class Test[A]
            test[Test[Int], Test[Int]]
        }

        "not equal tags (different type parameters)" - {
            class Test[A]
            test[Test[String], Test[Int]]
        }

        "not equal tags (different classes)" - {
            class Test1[A]
            class Test2[A]
            test[Test1[Int], Test2[Int]]
        }

        "not subtype (invariant)" - {
            class Test[A]
            test[Test[String], Test[Any]]
        }

        "not supertype (invariant)" - {
            class Test[A]
            test[Test[Any], Test[String]]
        }

        "not subtype or supertype (unrelated types)" - {
            class Test[A]
            test[Test[String], Test[Int]]
        }

        "subtype with type parameter" - {
            class Parent[A]
            class Child[A] extends Parent[String]
            test[Child[Int], Parent[String]]
        }
        "supertype with type parameter" - {
            test[String, Comparable[String]]
        }
    }

    "with variance" - {
        "contravariance" - {
            class Test[-A]
            test[Test[String], Test[Any]]
        }

        "covariance" - {
            class Test[+A]
            test[Test[String], Test[Any]]
        }

        "nested contravariance" - {
            class Test[-A]
            class NestedTest[-B]
            test[Test[NestedTest[Any]], Test[NestedTest[String]]]
        }

        "nested covariance" - {
            class Test[+A]
            class NestedTest[+B]
            test[Test[NestedTest[String]], Test[NestedTest[Any]]]
        }

        "mixed variances" - {
            class Test[+A, -B]
            test[Test[String, Any], Test[Any, String]]
        }

        "invariant type parameter" - {
            class Test[A, +B]
            test[Test[String, String], Test[String, Any]]
        }

        "complex variance scenario" - {
            class Test[-A, +B]
            class NestedTest[+V, -W]
            test[Test[NestedTest[String, Any], Any], Test[NestedTest[Any, String], String]]
        }

        "recursive mixin with variance" - {
            trait C[-A]
            trait X extends C[X]
            test[X, C[Any]]
        }
    }

    "with variance and inheritance" - {
        class Super
        class Sub extends Super

        "contravariance with inheritance" - {
            class Test[-A]
            test[Test[Sub], Test[Super]]
        }

        "covariance with inheritance" - {
            class Test[+A]
            test[Test[Sub], Test[Super]]
        }

        "nested contravariance with inheritance" - {
            class Test[-A]
            class NestedTest[-B]
            test[Test[NestedTest[Super]], Test[NestedTest[Sub]]]
        }

        "nested covariance with inheritance" - {
            class Test[+A]
            class NestedTest[+B]
            test[Test[NestedTest[Sub]], Test[NestedTest[Super]]]
        }

        "mixed variances with inheritance" - {
            class Test[+A, -B]
            test[Test[Sub, Super], Test[Super, Sub]]
        }

        "invariant type parameter with inheritance" - {
            class Test[A, +B]
            test[Test[Sub, Sub], Test[Sub, Super]]
        }

        "complex variance scenario with inheritance" - {
            class Test[-A, +B]
            class NestedTest[+V, -W]
            test[Test[NestedTest[Sub, Super], Super], Test[NestedTest[Super, Sub], Sub]]
        }
    }

    object OpaqueTypes:
        opaque type Test  = String
        opaque type Test1 = String
        opaque type Test2 = Int

        class Super
        class Sub extends Super
        opaque type OpaqueSub   = Sub
        opaque type OpaqueSuper = Super

        class Test3[+A]
        opaque type OpaqueString = String
        opaque type OpaqueAny    = Any

        class TestContra[-A]

        class TestNested[A]
        opaque type OpaqueTest = TestNested[OpaqueString]

        opaque type BoundedInt >: Int <: AnyVal       = Int
        opaque type BoundedString >: String <: AnyRef = String

        trait Animal
        class Mammal extends Animal
        class Cat    extends Mammal

        opaque type BoundedCat >: Cat <: Animal = Mammal

        opaque type UnionWithBounds >: Int <: Any = Int | String

        trait Readable
        trait Writable
        class FileImpl extends Readable with Writable

        opaque type IntersectionWithBounds >: FileImpl <: Readable = Readable & Writable

        trait Graph[A]
        trait Node extends Graph[Node]
        opaque type GraphBounded >: Node <: Graph[Node] = Node

        opaque type Box[A]     = List[A]
        opaque type Pair[A, B] = (A, B)
        opaque type Nested[A]  = Box[Box[A]]

    end OpaqueTypes

    "with opaque types" - {
        import OpaqueTypes.*
        "equal opaque types" - {
            test[Test, Test]
        }

        "not equal opaque types" - {
            test[Test1, Test2]
        }

        "subtype with opaque type" - {
            test[OpaqueSub, OpaqueSuper]
        }

        "not subtype with opaque type" - {
            test[OpaqueSuper, OpaqueSub]
        }

        "opaque type with variance" - {
            test[Test3[OpaqueString], Test3[OpaqueAny]]
        }

        "opaque type with contravariance" - {
            test[TestContra[OpaqueAny], TestContra[OpaqueString]]
        }

        "nested opaque types" - {
            test[OpaqueTest, TestNested[String]]
        }

        "opaque types with explicit bounds" - {
            test[BoundedInt, AnyVal]
            test[Int, BoundedInt]
            test[BoundedString, AnyRef]
            test[String, BoundedString]
        }

        "opaque types with bounds different from underlying" - {
            test[BoundedCat, Animal]
            test[Cat, BoundedCat]
            test[BoundedCat, Mammal]
        }

        "bounded opaque types with union underlying type" - {
            test[UnionWithBounds, Any]
            test[Int, UnionWithBounds]
            test[String, UnionWithBounds]
        }

        "bounded opaque types with intersection underlying type" - {
            test[IntersectionWithBounds, Readable]
            test[FileImpl, IntersectionWithBounds]
            test[IntersectionWithBounds, Writable]
        }

        "parameterized opaque types" - {
            "equality with same type parameter" - {
                test[Box[Int], Box[Int]]
            }

            "subtyping relationship with underlying type" - {
                test[Box[Int], List[Int]]
                test[List[Int], Box[Int]]
            }

            "multiple type parameters" - {
                test[Pair[Int, String], Pair[Int, String]]
                test[Pair[Int, String], (Int, String)]
                test[(Int, String), Pair[Int, String]]
            }

            "nested parameterized opaque types" - {
                test[Nested[Int], Box[Box[Int]]]
                test[Nested[Int], List[List[Int]]]
            }
        }

    }

    "show" - {

        "compact" in {
            assert(Tag[Object].show == "java.lang.Object")
            assert(Tag[Matchable].show == "scala.Matchable")
            assert(Tag[Any].show == "scala.Any")
            assert(Tag[Nothing].show == "scala.Nothing")
            assert(Tag[Null].show == "scala.Null")
            assert(Tag[String].show == "java.lang.String")
        }

        "no type params" in {
            assert(Tag[Int].show == "scala.Int")
            assert(Tag[Thread].show == "java.lang.Thread")
        }

        "type params" in pendingUntilFixed {
            class Test[A]
            assert(Tag[Test[Int]].show == s"${classOf[Test[?]].getName}[scala.Int]")
            ()
        }

        "primitive" in {
            assert(Tag[Int].show == "scala.Int")
            assert(Tag[Long].show == "scala.Long")
            assert(Tag[Float].show == "scala.Float")
            assert(Tag[Double].show == "scala.Double")
            assert(Tag[Boolean].show == "scala.Boolean")
        }
        "custom" in {
            trait CustomType
            assert(Tag[CustomType].show == "kyo.TagTest._$CustomType")
        }
    }

    "type with large name" in {
        class A0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789
        val tag = Tag[A0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789]
        assert(tag =:= tag && tag <:< tag)
    }

    "type unions" - {

        "union subtype" - {
            class A
            class B extends A
            class C extends A
            test[B | C, A]
        }

        "union supertype" - {
            class A
            class B extends A
            class C
            test[B, A | C]
        }

        "union subtype of union" - {
            class A
            class B extends A
            class C extends A
            class D
            test[B | C, A | D]
        }

        "union not subtype" - {
            class A
            class B
            class C
            test[A | B, C]
        }

        "union not supertype" - {
            class A
            class B extends A
            class C extends B
            test[A, B | C]
        }

        "union not subtype of union" - {
            class A
            class B
            class C
            class D
            test[A | B, C | D]
        }

        "union equality" - {
            class A
            class B
            test[A | B, A | B]
        }

        "union inequality" - {
            class A
            class B
            class C
            test[A | B, A | C]
        }
        "union with Any" - {
            class A
            test[A | Any, Any]
        }

        "union with Nothing" - {
            class A
            test[A | Nothing, A]
        }

        "union with Any" - {
            class A
            test[A | Any, A]
        }

        "union with a subtype" - {
            class A
            class B extends A
            test[A | B, A]
        }

        "union with a supertype" - {
            class A
            class B extends A
            test[A, A | B]
        }

        "union of a type with itself" - {
            class A
            test[A | A, A]
        }

        "union of two unrelated types" - {
            class A
            class B
            test[A | B, AnyRef]
        }

        "union of a type and its subtype" - {
            class A
            class B extends A
            test[A | B, A]
        }

        "union of a type and its supertype" - {
            class A
            class B extends A
            test[B, A | B]
        }

        "union on both sides" - {
            class A
            class B extends A
            class C extends A
            class D
            class E extends D
            class F extends D

            "union subtype of union (shared elements)" - {
                test[B | C, A | D]
            }

            "union subtype of union (all elements are subtypes)" - {
                test[B | C, A | A]
            }

            "union not subtype of union (no shared elements)" - {
                test[B | C, D | E]
            }

            "complex union relationships" - {
                test[B | E, A | D]
            }

            "union subtype with mixed class hierarchies" - {
                class X
                class Y extends X
                class Z extends Y

                test[Y | Z, X | Z]
            }

            "union with equality on both sides" - {
                test[A | D, A | D]
            }

            "union with reordered elements" - {
                test[A | D, D | A]
            }

            "union with repeated types" - {
                test[A | A | B, A | C]
            }
        }

        "show" - {
            def showSet[A](tag: Tag[A]) = tag.show.drop(1).dropRight(1).split('|').map(_.trim).toSet
            "primitive" in {
                assert(Tag[Int].show == "scala.Int")
                assert(showSet(Tag[Int | Boolean]) == Set("scala.Int", "scala.Boolean"))
                assert(showSet(Tag[Int | Boolean | String]) == Set("java.lang.String", "scala.Int", "scala.Boolean"))
            }
            "custom" in {
                trait A
                trait B

                assert(Tag[A].show == "kyo.TagTest._$A")
                assert(showSet(Tag[A | B]) == Set("kyo.TagTest._$B", "kyo.TagTest._$A"))
            }
        }
    }

    "type intersections" - {

        "intersection subtype" - {
            trait A
            trait B
            class C extends A with B
            test[C, A & B]
        }

        "intersection supertype" - {
            trait A
            trait B
            trait C
            class D extends A with B with C
            test[A & B, D]
        }

        "intersection subtype of intersection" - {
            trait A
            trait B extends A
            trait C extends A
            trait D
            test[B & C, A & D]
        }

        "intersection edge case 1" - {
            trait A
            class B
            test[A & B, A]
        }

        "intersection edge case 2" - {
            trait A
            trait B
            class C extends A
            class D extends A with B
            test[C & B, D]
        }

        "intersection not subtype" - {
            trait A
            trait B
            trait C
            test[A & B, C]
        }

        "intersection not supertype" - {
            trait A
            trait B extends A
            class C extends B
            test[A, B & C]
        }

        "intersection not subtype of intersection" - {
            trait A
            trait B
            trait C
            trait D
            test[A & B, C & D]
        }

        "intersection equality" - {
            trait A
            trait B
            test[A & B, A & B]
        }

        "intersection inequality" - {
            trait A
            trait B
            trait C
            test[A & B, A & C]
        }

        "intersection with Any" - {
            class A
            test[A & Any, A]
        }

        "intersection with a subtype" - {
            class A
            class B extends A
            test[A & B, B]
        }

        "intersection with a supertype" - {
            class A
            class B extends A
            test[B & A, B]
        }

        "intersection of a type with itself" - {
            class A
            test[A & A, A]
        }

        "intersection of a type and its subtype" - {
            class A
            class B extends A
            test[A & B, B]
        }

        "intersection of a type and its supertype" - {
            class A
            class B extends A
            test[B & A, B]
        }
    }

    "mixed unions and intersections" - {
        trait A
        trait B extends A
        trait C extends B
        trait D
        trait E extends D
        trait F extends E

        "intersection subtype of union" - {
            test[B & C, A | D]
        }

        "union subtype of intersection" - {
            test[B | F, A & D]
        }

        "union of intersections on both sides" - {
            test[(B & C) | (E & F), (A & B) | (D & E)]
        }

        "intersection of unions on both sides" - {
            test[(B | C) & (E | F), (A | C) & (D | F)]
        }

        "complex nested type relationships" - {
            test[(B & C) | (E & F), (A | D) & ((B | C) | (E | F))]
        }

        "multiple nested unions and intersections" - {
            test[((A & B) | (C & D)) & ((E | F) & (A | B)), ((A & B) | (C & D)) | ((E & F) | (A & C))]
        }

        "union and intersection with Nothing and Any" - {
            test[(A | Nothing) & (B | Any), A & B]
        }

        "distribution of union over intersection" - {
            test[(A | B) & C, (A & C) | (B & C)]
        }

        "distribution of intersection over union" - {
            test[(A & C) | (B & C), (A | B) & C]
        }
    }

    "distributive properties" - {
        trait A
        trait B
        trait C

        "union distributes over intersection (left)" - {
            test[A | (B & C), (A | B) & (A | C)]
        }

        "union distributes over intersection (right)" - {
            test[(A & B) | C, (A | C) & (B | C)]
        }

        "intersection distributes over union (left)" - {
            test[A & (B | C), (A & B) | (A & C)]
        }

        "intersection distributes over union (right)" - {
            test[(A | B) & C, (A & C) | (B & C)]
        }
    }

    "base types" - {
        "Nothing" - {
            test[Nothing, Any]
            test[Nothing, AnyRef]
            test[Nothing, AnyVal]
            test[List[Nothing], List[Int]]
        }
        "Null" - {
            test[Null, AnyRef]
            test[Null, String]
            test[List[Null], List[String]]
        }
        "Any" - {
            test[Any, Any]
            test[Any, AnyRef](skipIzumiWarning = true) // known izumi limitation
            test[Any, AnyVal]
            test[List[Any], List[Int]]
        }
    }

    "bounded" - {
        "upper" - {
            trait Bounded[A <: Number]
            test[Bounded[java.lang.Integer], Bounded[java.lang.Double]]
        }
        "lower" - {
            trait Bounded[A >: Null]
            test[Bounded[String], Bounded[AnyRef]]
        }
    }

    "higher kinded types" - {
        type Id[A] = A
        trait Higher[F[_]]
        trait Monad[F[_]]
        "simple higher kinded equality" - {
            test[Higher[List], Higher[List]]
        }

        "different higher kinded types" - {
            test[Higher[List], Higher[Vector]]
        }

        "nested higher kinded types" - {
            test[Monad[Id], Monad[List]]
        }
    }

    "members" - {
        object Big:
            trait Small
            class Sub extends Small

        "equal" - {
            test[Big.Small, Big.Small]
        }

        "subtype" - {
            test[Big.Sub, Big.Small]
        }

        "generic" - {
            class Box[A]
            test[Box[Big.Small], Box[Big.Small]]

            class Box2[A]
            def test2[A: Tag: izumi.reflect.Tag] = test[Box2[A], Box2[A]]
            test2[Big.Sub]
        }
    }

    "mixing tag types" - {

        class A
        class B extends A
        class C extends B
        class D
        class E extends D
        class F extends D

        "union, tag" - test[B | C, A]
        "tag, intersection" - test[C, A & B]
        "intersection, union" - test[E & F, D | A]
        "union, union" - test[B | C, C | B]
        "intersection, intersection" - test[E & F, F & E]
        "union, intersection" - test[B | C, B & C]
        "tag, union" - test[B, C | D]
        "tag, intersection 2" - test[B, C & D]
        "union, tag 2" - test[A | D, B]
        "intersection, tag" - test[A & D, B]

        "complex scenario" - {
            trait A
            trait B extends A
            trait C extends B
            class D
            class E extends D
            class F extends E
            class G extends F
            test[C & G, A & D | B & E]
        }

        "edge case 1" - {
            trait A
            class B extends A
            test[B & A, B | A]
        }

        "edge case 2" - {
            trait A
            class B extends A
            test[A | B, B & A]
        }

        "edge case 3" - {
            trait A
            class B extends A
            class C extends B
            test[A & B & C, C]
        }

        "edge case 4" - {
            trait A
            class B extends A
            class C extends B
            test[C, A | B | C]
        }
    }

    "generic parameters requiring tags" - {
        "simple generic" in {
            trait Test[A]
            def testGeneric[A](using Tag[A]) = Tag[Test[A]]
            assertCompiles("testGeneric[Int]")
            assertCompiles("testGeneric[String]")
        }

        "generic with bounds" in {
            def testBounded[A <: AnyVal](using Tag[A]) = Tag[Option[A]]
            assertCompiles("testBounded[Int]")
            assertCompiles("testBounded[Double]")
        }

        "nested generic" in {
            def testNestedGeneric[A, B](using Tag[A], Tag[B]) = Tag[Map[A, List[B]]]
            assertCompiles("testNestedGeneric[String, Int]")
            assertCompiles("testNestedGeneric[Int, Boolean]")
        }

        "generic with variance" - {
            "covariance" in {
                class Covariant[+A]
                def testCovariance[A](using Tag[A]) = Tag[Covariant[A]]
                assertCompiles("testCovariance[Int]")
                assertCompiles("testCovariance[String]")
            }

            "contravariance" in {
                class Contravariant[-A]
                def testContravariance[A](using Tag[A]) = Tag[Contravariant[A]]
                assertCompiles("testContravariance[Int]")
                assertCompiles("testContravariance[String]")
            }

            "invariance" in {
                class Invariant[A]
                def testInvariance[A](using Tag[A]) = Tag[Invariant[A]]
                assertCompiles("testInvariance[Int]")
                assertCompiles("testInvariance[String]")
            }

            "mixed variance" in {
                class Mixed[+A, -B, C]
                def testMixed[A, B, C](using Tag[A], Tag[B], Tag[C]) = Tag[Mixed[A, B, C]]
                assertCompiles("testMixed[Int, String, Boolean]")
            }
        }

        "sealed trait with generic parameters" - {
            "one" in {
                sealed trait SealedGeneric[A]
                def testSealed[A: Tag] = Tag[SealedGeneric[A]]
                assertCompiles("testSealed[Int]")
                assertCompiles("testSealed[String]")
            }
            "two" in {
                sealed trait SealedGeneric[A, B]
                def testSealed[A, B](using Tag[A], Tag[B]) = Tag[SealedGeneric[A, B]]
                assertCompiles("testSealed[Int, String]")
                assertCompiles("testSealed[Boolean, Double]")
            }
        }

        "opaque types" - {
            "simple opaque type" in {
                def testOpaque = Tag[MyInt]
                assertCompiles("testOpaque")
            }

            "opaque type with type parameter" in {
                def testOpaqueGeneric[A](using Tag[A]) = Tag[MyList[A]]
                assertCompiles("testOpaqueGeneric[Int]")
            }

            "nested opaque types" in {
                def testNestedOpaque[A](using Tag[A]) = Tag[Outer[A]]
                assertCompiles("testNestedOpaque[String]")
            }
        }
    }

    opaque type MyInt     = Int
    opaque type MyList[A] = List[A]
    opaque type Inner[A]  = List[A]
    opaque type Outer[B]  = Inner[B]

    "type lambdas in super types" - {
        "simple super type with type lambda" - {
            trait Higher[F[_]]
            class StringBox extends Higher[[X] =>> List[X]]
            test[StringBox, Higher[[X] =>> List[X]]]
        }

        "applied type lambda in super type" - {
            trait Container[A]
            type BoxMaker = [X] =>> Container[X]
            class IntBox extends BoxMaker[Int]
            test[IntBox, Container[Int]]
        }

        "ArrowEffect" - {
            type Const[A] = [B] =>> A
            abstract class ArrowEffect[-Input[_], +Output[_]]
            sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]
            test[TestEffect1, ArrowEffect[Const[Int], Const[Thread]]]
        }

        "ArrowEffect/Join" - {
            type Join[A] = [B] =>> (A, B)
            abstract class ArrowEffect[-Input[_], +Output[_]]
            sealed trait TestEffect1 extends ArrowEffect[Join[Int], Join[String]]
            test[TestEffect1, ArrowEffect[Join[Int], Join[String]]]
        }
    }

    "literal type tests" - {
        "numeric literal types" - {
            "integer literal types" - {
                "equality of same literal" in {
                    assert(Tag[1] =:= Tag[1])
                    assert(!(Tag[1] =:= Tag[2]))
                }

                "subtyping with Int" in {
                    assert(Tag[1] <:< Tag[Int])
                    assert(!(Tag[Int] <:< Tag[1]))
                }

                "mixing multiple integer literals" in {
                    assert(Tag[1 | 2 | 3] <:< Tag[Int])
                    assert(!(Tag[1 | 2] <:< Tag[3 | 4]))
                }
            }

            "floating-point literal types" - {
                "equality of same literal" in {
                    assert(Tag[1.0] =:= Tag[1.0])
                    assert(!(Tag[1.0] =:= Tag[2.0]))
                }

                "subtyping with Double" in {
                    assert(Tag[1.0] <:< Tag[Double])
                    assert(!(Tag[Double] <:< Tag[1.0]))
                }

                "mixing multiple float literals" in {
                    assert(Tag[1.0 | 2.0 | 3.0] <:< Tag[Double])
                    assert(!(Tag[1.0 | 2.0] <:< Tag[3.0 | 4.0]))
                }
            }
        }

        "string literal types" - {
            "equality of same literal" in {
                assert(Tag["hello"] =:= Tag["hello"])
                assert(!(Tag["hello"] =:= Tag["world"]))
            }

            "subtyping with String" in {
                assert(Tag["hello"] <:< Tag[String])
                assert(!(Tag[String] <:< Tag["hello"]))
            }

            "mixing multiple string literals" in {
                assert(Tag["hello" | "world"] <:< Tag[String])
                assert(!(Tag["hello" | "hi"] <:< Tag["world" | "bye"]))
            }

            "empty string literal" in {
                assert(Tag[""] =:= Tag[""])
                assert(Tag[""] <:< Tag[String])
            }
        }

        "boolean literal types" - {
            "true and false literals" in {
                assert(Tag[true] =:= Tag[true])
                assert(Tag[false] =:= Tag[false])
                assert(!(Tag[true] =:= Tag[false]))
            }

            "subtyping with Boolean" in {
                assert(Tag[true] <:< Tag[Boolean])
                assert(Tag[false] <:< Tag[Boolean])
                assert(!(Tag[Boolean] <:< Tag[true]))
            }
        }

        "char literal types" - {
            "equality of same literal" in {
                assert(Tag['a'] =:= Tag['a'])
                assert(!(Tag['a'] =:= Tag['b']))
            }

            "subtyping with Char" in {
                assert(Tag['a'] <:< Tag[Char])
                assert(!(Tag[Char] <:< Tag['a']))
            }

            "mixing multiple char literals" in {
                assert(Tag['a' | 'b' | 'c'] <:< Tag[Char])
                assert(!(Tag['a' | 'b'] <:< Tag['c' | 'd']))
            }
        }

        "union of different literal types" in {
            val unionTag = Tag[1 | "hello" | 'a' | true]
            assert(!(unionTag <:< Tag[Int]))
            assert(!(unionTag <:< Tag[String]))
            assert(!(unionTag <:< Tag[Char]))
            assert(!(unionTag <:< Tag[Boolean]))
            assert(unionTag <:< Tag[Int | String | Char | Boolean])
            assert(unionTag <:< Tag[AnyVal | String])
        }

        "literal types with variance" - {
            "covariance with literal types" in {
                class Box[+A]

                assert(Tag[Box[1]] <:< Tag[Box[Int]])
                assert(Tag[Box["hello"]] <:< Tag[Box[String]])
                assert(!(Tag[Box[Int]] <:< Tag[Box[1]]))
            }

            "contravariance with literal types" in {
                class Box[-A]

                assert(Tag[Box[Int]] <:< Tag[Box[1]])
                assert(Tag[Box[String]] <:< Tag[Box["hello"]])
                assert(!(Tag[Box[1]] <:< Tag[Box[Int]]))
            }
        }

        "literal types in collections and tuples" - {
            "list of literal types" in {
                assert(Tag[List[1]] <:< Tag[List[Int]])
                assert(Tag[List[1 | 2]] <:< Tag[List[Int]])
            }

            "tuple with literal types" in {
                assert(Tag[(1, "hello")] <:< Tag[(Int, String)])
                assert(!(Tag[(Int, String)] <:< Tag[(1, "hello")]))
            }

            "union of tuples with literals" in {
                assert(Tag[(1, "a") | (2, "b")] <:< Tag[(Int, String)])
            }
        }
    }

    "subtype and supertype with different type argument (bug #551)" - {
        class Super[A]
        class Sub[A] extends Super[String]
        test[Sub[Int], Super[String]]
    }

    "intersection subtype 3 (bug #552)" - {
        trait A
        trait B
        class C extends A with B
        test[C, A & B]
    }

    // TODO: fix this to use `pendingUntilFixed` instead of `ignore`
    given RegisterFunction = (name, test, pending) =>
        if pending then name ignore Future.successful(test)
        else name in Future.successful(test)

end TagTest
