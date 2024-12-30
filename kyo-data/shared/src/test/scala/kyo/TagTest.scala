package kyo

import izumi.reflect.Tag as ITag
import kyo.*
import kyo.internal.RegisterFunction
import kyo.internal.TagTestMacro.test
import org.scalatest.NonImplicitAssertions
import org.scalatest.compatible.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import scala.annotation.nowarn
import scala.concurrent.Future

class TagTest extends AsyncFreeSpec with NonImplicitAssertions:
    // TODO: fix this to use `pendingUntilFixed` instead of `ignore`
    given RegisterFunction = (name, test, pending) =>
        if pending then name ignore Future.successful(test)
        else name in Future.successful(test)

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
            class Super
            class Sub[A] extends Super
            test[Sub[Int], Super]
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

    "with opaque types" - {
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
    }

    "intersection types" - {
        "intersection subtype" - {
            trait A
            trait B extends A
            test[B & A, A]
        }

        "intersection supertype" - {
            trait A
            trait B extends A
            test[A, B & A]
        }

        "intersection not subtype" - {
            trait A
            trait B
            println(s"A & B raw: ${Tag[A & B].raw}")
            println(s"A | B raw: ${Tag[A | B].raw}")
            test[A & B, A | B]
        }

        "intersection not supertype" - {
            trait A
            trait B
            test[A | B, A & B]
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
    }

    "union types" - {
        "union subtype" - {
            trait A
            trait B extends A
            test[B, A | B]
        }

        "union supertype" - {
            trait A
            trait B extends A
            test[A | B, A]
        }

        "union not subtype" - {
            trait A
            trait B
            test[A | B, A & B]
        }

        "union not supertype" - {
            trait A
            trait B
            test[A & B, A | B]
        }

        "union equality" - {
            trait A
            trait B
            test[A | B, A | B]
        }

        "union inequality" - {
            trait A
            trait B
            trait C
            test[A | B, A | C]
        }

        "union with Any" - {
            class A
            test[A, A | Any]
        }

        "union with a subtype" - {
            class A
            class B extends A
            test[B, B | A]
        }

        "union with a supertype" - {
            class A
            class B extends A
            test[B | A, A]
        }
    }

    "base types" - {
        "Nothing" - {
            test[Nothing, Any]
            test[Nothing, AnyRef]
            test[Nothing, AnyVal]
            test[List[Nothing], List[Int]]
        }

        // "Null" - {
        //     test[Null, AnyRef]
        //     test[Null, String]
        //     test[List[Null], List[String]]
        // }

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

        "multiple" - {
            trait A
            trait B
            trait Bounded[T <: A & B]
            class C extends A with B
            test[Bounded[C], Bounded[C]]
        }
    }

    // TOOD: fix the rendering of higher kinded types
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

    "showTpe" - {
        "primitive" in {
            assert(Tag[Int].showTpe == "scala.Int")
            assert(Tag[Long].showTpe == "scala.Long")
            assert(Tag[Float].showTpe == "scala.Float")
            assert(Tag[Double].showTpe == "scala.Double")
            assert(Tag[Boolean].showTpe == "scala.Boolean")
        }
        "parameterized" in {
            class Box[A]
            assert(Tag[Box[Int]].showTpe == "kyo.TagTest.Box[scala.Int]")
            assert(Tag[Box[Box[Int]]].showTpe == "kyo.TagTest.Box[kyo.TagTest.Box[scala.Int]]", Tag[Box[Box[Int]]].raw)
        }
        "inheritance" in {
            class Super[A]
            class Sub[B] extends Super[B]
            assert(Tag[Sub[Int]].showTpe == "kyo.TagTest.Sub[scala.Int]")
            assert(Tag[Super[String]].showTpe == "kyo.TagTest.Super[java.lang.String]")
        }
        "tuples" in {
            assert(Tag[(Int, String)].showTpe == "scala.Tuple2[scala.Int, java.lang.String]")
            assert(Tag[(Int, String, Boolean)].showTpe == "scala.Tuple3[scala.Int, java.lang.String, scala.Boolean]")
        }
        "bounded" in {
            trait Bounded[A <: AnyVal]
            assert(Tag[Bounded[Int]].showTpe == "kyo.TagTest.Bounded[scala.Int]")
        }
        "class" in {
            object outer:
                object inner:
                    class NestedClass
            assert(Tag[outer.inner.NestedClass].showTpe == "kyo.TagTest.outer$.inner$.NestedClass")
        }
        "object" in {
            object A
            assert(Tag[A.type].showTpe == "kyo.TagTest.A$")
        }
        "alias" in {
            type StringList = List[String]
            assert(Tag[StringList].showTpe == "scala.collection.immutable.List[java.lang.String]")
        }
        "opaque" in {
            assert(Tag[OpaqueAny].showTpe == "scala.Any")
        }
        "union/intersection" in {
            trait A
            trait B
            trait C[Z]
            assert(Tag[A | B].showTpe == "kyo.TagTest.A | kyo.TagTest.B")
            assert(Tag[A & B].showTpe == "kyo.TagTest.A & kyo.TagTest.B")
            assert(Tag[A | B & A].showTpe == "kyo.TagTest.A | kyo.TagTest.B & kyo.TagTest.A")
            assert(Tag[C[A] & C[B]].showTpe == "kyo.TagTest.C[kyo.TagTest.A] & kyo.TagTest.C[kyo.TagTest.B]")
            assert(Tag[C[A] | C[B]].showTpe == "kyo.TagTest.C[kyo.TagTest.A] | kyo.TagTest.C[kyo.TagTest.B]")
        }
        "recursive mixins" in {
            trait X extends Comparable[X] with Ordering[X] with PartialOrdering[X]
            assert(Tag[X].showTpe == "kyo.TagTest.X")
        }
        "higher-kinded types" in {
            trait IO[A]
            trait Monad[F[_]]
            assert(Tag[Monad[IO]].showTpe == "kyo.TagTest.Monad[kyo.TagTest.IO]")
        }
    }

    "show" - {
        "Tag[Any]" in {
            assert(Tag[Any].show == "Tag[scala.Any]")
        }
    }

    "stable" - {
        "Tag[Nothing]" in {
            assert(Tag[Nothing].raw == "C:scala.Nothing:C:scala.Any")
        }
        "Tag[Any]" in {
            assert(Tag[Any].raw == "C:scala.Any")
        }
    }

    "members" - {
        object Big:
            trait Small
            class Sub extends Small

        "raw" in {
            assert(Tag[Big.Small].raw == "C:kyo.TagTest._$Big$.Small:C:java.lang.Object:C:scala.Matchable:C:scala.Any")
            assert(
                Tag[Big.Sub].raw == "C:kyo.TagTest._$Big$.Sub:C:kyo.TagTest._$Big$.Small:C:java.lang.Object:C:scala.Matchable:C:scala.Any"
            )
        }

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

end TagTest
