package kyoTest

import izumi.reflect.Tag as ITag
import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec

class tagsTest extends AsyncFreeSpec with NonImplicitAssertions:

    def test[T1: Tag: ITag, T2: Tag: ITag]: Unit =
        "T1 <:< T2" in {
            val kresult = Tag[T1] <:< Tag[T2]
            val iresult = ITag[T1] <:< ITag[T2]
            assert(
                kresult == iresult,
                s"Tag[T1] <:< Tag[T2] is $kresult but ITag[T1] <:< ITag[T2] is $iresult"
            )
        }
        "T2 <:< T1" in {
            val kresult = Tag[T2] <:< Tag[T1]
            val iresult = ITag[T2] <:< ITag[T1]
            assert(
                kresult == iresult,
                s"Tag[T2] <:< Tag[T1] is $kresult but ITag[T2] <:< ITag[T1] is $iresult"
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

        "no type params" in {
            assert(Tag[Int].show == "scala.Int")
            assert(Tag[Thread].show == "java.lang.Thread")
        }

        "type params" in pendingUntilFixed {
            class Test[T]
            assert(Tag[Test[Int]].show == s"${classOf[Test[?]].getName}[scala.Int]")
            ()
        }
    }

end tagsTest
