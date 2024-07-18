package kyoTest

import izumi.reflect.Tag as ITag
import kyo.*
import kyo.Tag.Intersection
import kyo.Tag.Union
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.annotation.nowarn

class tagsTest extends AsyncFreeSpec with NonImplicitAssertions:

    inline def test[T1, T2](using k1: Tag[T1], i1: ITag[T1], k2: Tag[T2], i2: ITag[T2]): Unit =
        "T1 <:< T2" in {
            val kresult = k1 <:< k2
            val iresult = i1 <:< i2
            assert(
                kresult == iresult,
                s"Tag[T1] <:< Tag[T2] is $kresult but ITag[T1] <:< ITag[T2] is $iresult"
            )
        }
        "T2 <:< T1" in {
            val kresult = k2 <:< k1
            val iresult = i2 <:< i1
            assert(
                kresult == iresult,
                s"Tag[T2] <:< Tag[T1] is $kresult but ITag[T2] <:< ITag[T1] is $iresult"
            )
        }
        "T2 =:= T1" in {
            val kresult = k2 =:= k1
            val iresult = i2 =:= i1
            assert(
                kresult == iresult,
                s"Tag[T2] =:= Tag[T1] is $kresult but ITag[T2] =:= ITag[T1] is $iresult"
            )
        }
        "T2 =!= T1" in {
            val kresult = k2 =!= k1
            val iresult = !(i2 =:= i1)
            assert(
                kresult == iresult,
                s"Tag[T2] =!= Tag[T1] is $kresult but ITag[T2] =!= ITag[T1] is $iresult"
            )
        }
        ()
    end test

    "without variance" - {
        "equal tags" - {
            class Test[T]
            test[Test[Int], Test[Int]]
        }

        "not equal tags (different type parameters)" - {
            class Test[T]
            test[Test[String], Test[Int]]
        }

        "not equal tags (different classes)" - {
            class Test1[T]
            class Test2[T]
            test[Test1[Int], Test2[Int]]
        }

        "not subtype (invariant)" - {
            class Test[T]
            test[Test[String], Test[Any]]
        }

        "not supertype (invariant)" - {
            class Test[T]
            test[Test[Any], Test[String]]
        }

        "not subtype or supertype (unrelated types)" - {
            class Test[T]
            test[Test[String], Test[Int]]
        }

        "subtype with type parameter" - {
            class Super
            class Sub[A] extends Super
            test[Sub[Int], Super]
        }
    }

    "with variance" - {
        "contravariance" - {
            class Test[-T]
            test[Test[String], Test[Any]]
        }

        "covariance" - {
            class Test[+T]
            test[Test[String], Test[Any]]
        }

        "nested contravariance" - {
            class Test[-T]
            class NestedTest[-U]
            test[Test[NestedTest[Any]], Test[NestedTest[String]]]
        }

        "nested covariance" - {
            class Test[+T]
            class NestedTest[+U]
            test[Test[NestedTest[String]], Test[NestedTest[Any]]]
        }

        "mixed variances" - {
            class Test[+T, -U]
            test[Test[String, Any], Test[Any, String]]
        }

        "invariant type parameter" - {
            class Test[T, +U]
            test[Test[String, String], Test[String, Any]]
        }

        "complex variance scenario" - {
            class Test[-T, +U]
            class NestedTest[+V, -W]
            test[Test[NestedTest[String, Any], Any], Test[NestedTest[Any, String], String]]
        }
    }

    "with variance and inheritance" - {
        class Super
        class Sub extends Super

        "contravariance with inheritance" - {
            class Test[-T]
            test[Test[Sub], Test[Super]]
        }

        "covariance with inheritance" - {
            class Test[+T]
            test[Test[Sub], Test[Super]]
        }

        "nested contravariance with inheritance" - {
            class Test[-T]
            class NestedTest[-U]
            test[Test[NestedTest[Super]], Test[NestedTest[Sub]]]
        }

        "nested covariance with inheritance" - {
            class Test[+T]
            class NestedTest[+U]
            test[Test[NestedTest[Sub]], Test[NestedTest[Super]]]
        }

        "mixed variances with inheritance" - {
            class Test[+T, -U]
            test[Test[Sub, Super], Test[Super, Sub]]
        }

        "invariant type parameter with inheritance" - {
            class Test[T, +U]
            test[Test[Sub, Sub], Test[Sub, Super]]
        }

        "complex variance scenario with inheritance" - {
            class Test[-T, +U]
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

    class Test3[+T]
    opaque type OpaqueString = String
    opaque type OpaqueAny    = Any

    class TestContra[-T]

    class TestNested[T]
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

    trait UnsupportedTest:
        type T
    val test = new UnsupportedTest {}

    "unsupported types" in {
        assertDoesNotCompile("Tag[Nothing]")
        assertDoesNotCompile("Tag[UnsupportedTest#T]")
        assertDoesNotCompile("Tag[UnsupportedTest.T]")
        assertDoesNotCompile("Tag[String & Int]")
        assertDoesNotCompile("Tag[String | Int]")
    }

    "show" - {

        "compact" in {
            assert(Tag[Object].show == "Tag[java.lang.Object]")
            assert(Tag[Matchable].show == "Tag[scala.Matchable]")
            assert(Tag[Any].show == "Tag[scala.Any]")
            assert(Tag[String].show == "Tag[java.lang.String]")
        }

        "no type params" in {
            assert(Tag[Int].show == "Tag[scala.Int]")
            assert(Tag[Thread].show == "Tag[java.lang.Thread]")
        }

        "type params" in pendingUntilFixed {
            class Test[T]
            assert(Tag[Test[Int]].show == s"Tag[${classOf[Test[?]].getName}[scala.Int]]")
            ()
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
        "custom" in {
            trait CustomType
            assert(Tag[CustomType].showTpe == "kyoTest.tagsTest._$CustomType")
        }
    }

    "type with large name" in pendingUntilFixed {
        assertCompiles(
            """
            class A0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789
            Tag[A0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789]
            """
        )
    }

    "type unions" - {

        inline def test[T1, T2](using k1: Union[T1], i1: ITag[T1], k2: Union[T2], i2: ITag[T2]): Unit =
            "T1 <:< T2" in {
                val kresult = k1 <:< k2
                val iresult = i1 <:< i2
                assert(
                    kresult == iresult,
                    s"Tag[T1] <:< Tag[T2] is $kresult but ITag[T1] <:< ITag[T2] is $iresult"
                )
            }
            "T2 <:< T1" in {
                val kresult = k2 <:< k1
                val iresult = i2 <:< i1
                assert(
                    kresult == iresult,
                    s"Tag[T2] <:< Tag[T1] is $kresult but ITag[T2] <:< ITag[T1] is $iresult"
                )
            }
            "T2 =:= T1" in {
                val kresult = k2 =:= k1
                val iresult = i2 =:= i1
                assert(
                    kresult == iresult,
                    s"Tag[T2] =:= Tag[T1] is $kresult but ITag[T2] =:= ITag[T1] is $iresult"
                )
            }
            "T2 =!= T1" in {
                val kresult = k2 =!= k1
                val iresult = !(i2 =:= i1)
                assert(
                    kresult == iresult,
                    s"Tag[T2] =!= Tag[T1] is $kresult but ITag[T2] =!= ITag[T1] is $iresult"
                )
            }
            ()
        end test

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

        "unsupported" in {
            @nowarn
            trait A
            @nowarn
            trait B
            assertDoesNotCompile("Union[A & B]")
            assertDoesNotCompile("Union[A & B | A]")
            assertDoesNotCompile("Union[A | B & A]")
        }

        "showTpe" - {
            "primitive" in {
                assert(Union[Int].showTpe == "scala.Int")
                assert(Union[Int | Boolean].showTpe == "scala.Int | scala.Boolean")
                assert(Union[Int | Boolean | String].showTpe == "scala.Int | scala.Boolean | java.lang.String")
            }
            "custom" in {
                trait A
                trait B

                assert(Union[A].showTpe == "kyoTest.tagsTest._$A")
                assert(Union[A | B].showTpe == "kyoTest.tagsTest._$A | kyoTest.tagsTest._$B")
            }
        }
    }

    "type intersections" - {

        inline def test[T1, T2](using k1: Intersection[T1], i1: ITag[T1], k2: Intersection[T2], i2: ITag[T2]): Unit =
            "T1 <:< T2" in {
                val kresult = k1 <:< k2
                val iresult = i1 <:< i2
                assert(
                    kresult == iresult,
                    s"Tag[T1] <:< Tag[T2] is $kresult but ITag[T1] <:< ITag[T2] is $iresult"
                )
            }
            "T2 <:< T1" in {
                val kresult = k2 <:< k1
                val iresult = i2 <:< i1
                assert(
                    kresult == iresult,
                    s"Tag[T2] <:< Tag[T1] is $kresult but ITag[T2] <:< ITag[T1] is $iresult"
                )
            }
            "T2 =:= T1" in {
                val kresult = k2 =:= k1
                val iresult = i2 =:= i1
                assert(
                    kresult == iresult,
                    s"Tag[T2] =:= Tag[T1] is $kresult but ITag[T2] =:= ITag[T1] is $iresult"
                )
            }
            "T2 =!= T1" in {
                val kresult = k2 =!= k1
                val iresult = !(i2 =:= i1)
                assert(
                    kresult == iresult,
                    s"Tag[T2] =!= Tag[T1] is $kresult but ITag[T2] =!= ITag[T1] is $iresult"
                )
            }
            ()
        end test

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

        "unsupported" in {
            @nowarn
            trait A
            @nowarn
            trait B
            assertDoesNotCompile("Intersection[A | B]")
            assertDoesNotCompile("Intersection[A & B | A]")
            assertDoesNotCompile("Intersection[A | B & A]")
        }

        "showTpe" - {
            "primitive" in {
                assert(Intersection[Int].showTpe == "scala.Int")
                assert(Intersection[Int & Boolean].showTpe == "scala.Int & scala.Boolean")
                assert(Intersection[Int & Boolean & String].showTpe == "scala.Int & scala.Boolean & java.lang.String")
            }
            "custom" in {
                trait A
                trait B

                assert(Intersection[A].showTpe == "kyoTest.tagsTest._$A")
                assert(Intersection[A & B].showTpe == "kyoTest.tagsTest._$A & kyoTest.tagsTest._$B")
            }
        }

    }

    "mixing tag types" - {

        inline def test[T1, T2](using k1: Tag.Set[T1], i1: ITag[T1], k2: Tag.Set[T2], i2: ITag[T2]): Unit =
            "T1 <:< T2" in {
                val kresult = k1 <:< k2
                val iresult = i1 <:< i2
                assert(
                    kresult == iresult,
                    s"Tag[T1] <:< Tag[T2] is $kresult but ITag[T1] <:< ITag[T2] is $iresult"
                )
            }
            "T2 <:< T1" in {
                val kresult = k2 <:< k1
                val iresult = i2 <:< i1
                assert(
                    kresult == iresult,
                    s"Tag[T2] <:< Tag[T1] is $kresult but ITag[T2] <:< ITag[T1] is $iresult"
                )
            }
            "T2 =:= T1" in {
                val kresult = k2 =:= k1
                val iresult = i2 =:= i1
                assert(
                    kresult == iresult,
                    s"Tag[T2] =:= Tag[T1] is $kresult but ITag[T2] =:= ITag[T1] is $iresult"
                )
            }
            "T2 =!= T1" in {
                val kresult = k2 =!= k1
                val iresult = !(i2 =:= i1)
                assert(
                    kresult == iresult,
                    s"Tag[T2] =!= Tag[T1] is $kresult but ITag[T2] =!= ITag[T1] is $iresult"
                )
            }
            ()
        end test

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

end tagsTest
