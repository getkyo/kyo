package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

class TypeIntersectionTest extends AnyFreeSpec:

    sealed trait Base
    trait A                        extends Base
    trait B                        extends Base
    trait C                        extends Base
    trait D                        extends Base
    trait E                        extends Base
    class Concrete                 extends Base
    class Child                    extends Concrete
    case class Data(value: String) extends Base
    trait Generic[T]               extends Base
    trait MultiParam[A, B]         extends Base
    trait Recursive[T <: Base]     extends Base
    trait Empty                    extends Base with Generic[String]

    "basic type decomposition" - {
        "single trait" in {
            val typeSet = TypeIntersection[A]
            assertType[typeSet.AsTuple, A *: EmptyTuple]
        }

        "concrete class" in {
            val typeSet = TypeIntersection[Concrete]
            assertType[typeSet.AsTuple, Concrete *: EmptyTuple]
        }

        "case class" in {
            val typeSet = TypeIntersection[Data]
            assertType[typeSet.AsTuple, Data *: EmptyTuple]
        }

        "generic trait" in {
            val typeSet = TypeIntersection[Generic[String]]
            assertType[typeSet.AsTuple, Generic[String] *: EmptyTuple]
        }

        "empty trait with mixed-in generic" in {
            val typeSet = TypeIntersection[Empty]
            assertType[typeSet.AsTuple, Empty *: EmptyTuple]
        }

        "null type" in {
            val typeSet = TypeIntersection[Null]
            assertType[typeSet.AsTuple, Null *: EmptyTuple]
        }
    }

    "intersection types" - {
        "two types" in {
            val typeSet = TypeIntersection[A & B]
            assertType[typeSet.AsTuple, A *: B *: EmptyTuple]
        }

        "three types" in {
            val typeSet = TypeIntersection[A & B & C]
            assertType[typeSet.AsTuple, A *: B *: C *: EmptyTuple]
        }

        "four types" in {
            val typeSet = TypeIntersection[A & B & C & D]
            assertType[typeSet.AsTuple, A *: B *: C *: D *: EmptyTuple]
        }

        "nested intersections" in {
            val typeSet = TypeIntersection[(A & B) & (C & D)]
            assertType[typeSet.AsTuple, A *: B *: C *: D *: EmptyTuple]
        }

        "with concrete type" in {
            val typeSet = TypeIntersection[Concrete & A & B]
            assertType[typeSet.AsTuple, Concrete *: A *: B *: EmptyTuple]
        }

        "with generic types" in {
            val typeSet = TypeIntersection[Generic[Int] & Generic[String]]
            assertType[typeSet.AsTuple, Generic[Int] *: Generic[String] *: EmptyTuple]
        }

        "with type parameters" in {
            val typeSet = TypeIntersection[MultiParam[Int, String] & A]
            assertType[typeSet.AsTuple, MultiParam[Int, String] *: A *: EmptyTuple]
        }

        "with recursive bounds" in {
            val typeSet = TypeIntersection[Recursive[A] & A]
            assertType[typeSet.AsTuple, Recursive[A] *: A *: EmptyTuple]
        }

        "with Nothing type" in {
            val typeSet = TypeIntersection[A & Nothing]
            assertType[typeSet.AsTuple, Nothing *: EmptyTuple]
        }
    }

    "Map operation" - {
        "over single type" in {
            class F[T]
            val typeSet = TypeIntersection[A]
            assertType[typeSet.Map[F], F[A]]
        }

        "over intersection" in {
            class F[T]
            val typeSet = TypeIntersection[A & B]
            assertType[typeSet.Map[F], F[A] & F[B]]
        }

        "over nested intersection" in {
            class F[T]
            val typeSet = TypeIntersection[(A & B) & (C & D)]
            assertType[typeSet.Map[F], F[A] & F[B] & F[C] & F[D]]
        }

        "over generic types" in {
            class F[T]
            val typeSet = TypeIntersection[Generic[Int] & Generic[String]]
            assertType[typeSet.Map[F], F[Generic[Int]] & F[Generic[String]]]
        }

        "with multiple type parameters" in {
            class F[T]
            val typeSet = TypeIntersection[MultiParam[Int, String]]
            assertType[typeSet.Map[F], F[MultiParam[Int, String]]]
        }
    }

    "type relationships" - {
        "inheritance" in {
            val typeSet = TypeIntersection[Child]
            type Result = typeSet.AsTuple
            assertType[Result, Child *: EmptyTuple]
        }

        "multiple inheritance" in {
            trait MA extends A with B
            val typeSet = TypeIntersection[MA]
            assertType[typeSet.AsTuple, MA *: EmptyTuple]
        }

        "deep inheritance" in {
            trait Deep   extends A with B
            class Deeper extends Deep with C
            val typeSet = TypeIntersection[Deeper]
            assertType[typeSet.AsTuple, Deeper *: EmptyTuple]
        }

        "generic inheritance" in {
            trait GenericChild[T] extends Generic[T]
            val typeSet = TypeIntersection[GenericChild[Int]]
            assertType[typeSet.AsTuple, GenericChild[Int] *: EmptyTuple]
        }

        "mixed generic and concrete inheritance" in {
            trait MixedInheritance extends Generic[Int] with A
            val typeSet = TypeIntersection[MixedInheritance]
            assertType[typeSet.AsTuple, MixedInheritance *: EmptyTuple]
        }

        "self-recursive type" in {
            trait SelfRef extends Base:
                self: A =>
            val typeSet = TypeIntersection[SelfRef & A]
            assertType[typeSet.AsTuple, SelfRef *: A *: EmptyTuple]
        }
    }

    "summonAll" - {
        trait TC[A]:
            def name: String

        given TC[A] with
            def name = "A"
        given TC[B] with
            def name = "B"
        given TC[C] with
            def name = "C"

        "collects type class instances" in {
            val instances = TypeIntersection.summonAll[A & B & C, TC]
            assert(instances.map(_.name) == List("A", "B", "C"))
        }

        "preserves order" in {
            val instances = TypeIntersection.summonAll[C & A & B, TC]
            assert(instances.map(_.name) == List("C", "A", "B"))
        }

        "maintains priority of overlapping instances" in {
            given TC[A & B] with
                def name = "AB"

            val instances = TypeIntersection.summonAll[A & B, TC]
            assert(instances.map(_.name) == List("A", "B"))
        }

        "large intersection" in {
            enum Large:
                case v1, v2, v3, v4, v5, v6, v7, v8, v9, v10,
                    v11, v12, v13, v14, v15, v16, v17, v18, v19, v20,
                    v21, v22, v23, v24, v25, v26, v27, v28, v29, v30,
                    v31, v32, v33, v34, v35, v36, v37, v38, v39, v40
            end Large
            import Large.*

            given [V]: TC[V] with
                def name = "test"

            type Values = v1.type & v2.type & v3.type & v4.type & v5.type & v6.type & v7.type & v8.type & v9.type & v10.type &
                v11.type & v12.type & v13.type & v14.type & v15.type & v16.type & v17.type & v18.type & v19.type & v20.type &
                v21.type & v22.type & v23.type & v24.type & v25.type & v26.type & v27.type & v28.type & v29.type & v30.type &
                v31.type & v32.type & v33.type & v34.type & v35.type & v36.type & v37.type & v38.type & v39.type & v40.type

            assertCompiles("TypeIntersection.summonAll[Values, TC]")
        }
    }

    inline def assertType[A, B](using ev: A =:= B): Unit = ()
end TypeIntersectionTest
