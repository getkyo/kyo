package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

class TypeSetTest extends AnyFreeSpec:

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
            val typeSet = TypeSet[A]
            assertType[typeSet.AsTuple, A *: EmptyTuple]
        }

        "concrete class" in {
            val typeSet = TypeSet[Concrete]
            assertType[typeSet.AsTuple, Concrete *: EmptyTuple]
        }

        "case class" in {
            val typeSet = TypeSet[Data]
            assertType[typeSet.AsTuple, Data *: EmptyTuple]
        }

        "generic trait" in {
            val typeSet = TypeSet[Generic[String]]
            assertType[typeSet.AsTuple, Generic[String] *: EmptyTuple]
        }

        "empty trait with mixed-in generic" in {
            val typeSet = TypeSet[Empty]
            assertType[typeSet.AsTuple, Empty *: EmptyTuple]
        }

        "null type" in {
            val typeSet = TypeSet[Null]
            assertType[typeSet.AsTuple, Null *: EmptyTuple]
        }
    }

    "intersection types" - {
        "two types" in {
            val typeSet = TypeSet[A & B]
            assertType[typeSet.AsTuple, A *: B *: EmptyTuple]
        }

        "three types" in {
            val typeSet = TypeSet[A & B & C]
            assertType[typeSet.AsTuple, A *: B *: C *: EmptyTuple]
        }

        "four types" in {
            val typeSet = TypeSet[A & B & C & D]
            assertType[typeSet.AsTuple, A *: B *: C *: D *: EmptyTuple]
        }

        "nested intersections" in {
            val typeSet = TypeSet[(A & B) & (C & D)]
            assertType[typeSet.AsTuple, A *: B *: C *: D *: EmptyTuple]
        }

        "with concrete type" in {
            val typeSet = TypeSet[Concrete & A & B]
            assertType[typeSet.AsTuple, Concrete *: A *: B *: EmptyTuple]
        }

        "with generic types" in {
            val typeSet = TypeSet[Generic[Int] & Generic[String]]
            assertType[typeSet.AsTuple, Generic[Int] *: Generic[String] *: EmptyTuple]
        }

        "with type parameters" in {
            val typeSet = TypeSet[MultiParam[Int, String] & A]
            assertType[typeSet.AsTuple, MultiParam[Int, String] *: A *: EmptyTuple]
        }

        "with recursive bounds" in {
            val typeSet = TypeSet[Recursive[A] & A]
            assertType[typeSet.AsTuple, Recursive[A] *: A *: EmptyTuple]
        }

        "with Nothing type" in {
            val typeSet = TypeSet[A & Nothing]
            assertType[typeSet.AsTuple, Nothing *: EmptyTuple]
        }
    }

    "Map operation" - {
        "over single type" in {
            class F[T]
            val typeSet = TypeSet[A]
            assertType[typeSet.Map[F], F[A]]
        }

        "over intersection" in {
            class F[T]
            val typeSet = TypeSet[A & B]
            assertType[typeSet.Map[F], F[A] & F[B]]
        }

        "over nested intersection" in {
            class F[T]
            val typeSet = TypeSet[(A & B) & (C & D)]
            assertType[typeSet.Map[F], F[A] & F[B] & F[C] & F[D]]
        }

        "over generic types" in {
            class F[T]
            val typeSet = TypeSet[Generic[Int] & Generic[String]]
            assertType[typeSet.Map[F], F[Generic[Int]] & F[Generic[String]]]
        }

        "with multiple type parameters" in {
            class F[T]
            val typeSet = TypeSet[MultiParam[Int, String]]
            assertType[typeSet.Map[F], F[MultiParam[Int, String]]]
        }
    }

    "type relationships" - {
        "inheritance" in {
            val typeSet = TypeSet[Child]
            type Result = typeSet.AsTuple
            assertType[Result, Child *: EmptyTuple]
        }

        "multiple inheritance" in {
            trait MA extends A with B
            val typeSet = TypeSet[MA]
            assertType[typeSet.AsTuple, MA *: EmptyTuple]
        }

        "deep inheritance" in {
            trait Deep   extends A with B
            class Deeper extends Deep with C
            val typeSet = TypeSet[Deeper]
            assertType[typeSet.AsTuple, Deeper *: EmptyTuple]
        }

        "generic inheritance" in {
            trait GenericChild[T] extends Generic[T]
            val typeSet = TypeSet[GenericChild[Int]]
            assertType[typeSet.AsTuple, GenericChild[Int] *: EmptyTuple]
        }

        "mixed generic and concrete inheritance" in {
            trait MixedInheritance extends Generic[Int] with A
            val typeSet = TypeSet[MixedInheritance]
            assertType[typeSet.AsTuple, MixedInheritance *: EmptyTuple]
        }

        "self-recursive type" in {
            trait SelfRef extends Base:
                self: A =>
            val typeSet = TypeSet[SelfRef & A]
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
            val instances = TypeSet.summonAll[A & B & C, TC]
            assert(instances.map(_.name) == List("A", "B", "C"))
        }

        "preserves order" in {
            val instances = TypeSet.summonAll[C & A & B, TC]
            assert(instances.map(_.name) == List("C", "A", "B"))
        }

        "maintains priority of overlapping instances" in {
            given TC[A & B] with
                def name = "AB"

            val instances = TypeSet.summonAll[A & B, TC]
            assert(instances.map(_.name) == List("A", "B"))
        }
    }

    inline def assertType[A, B](using ev: A =:= B): Unit = ()
end TypeSetTest
