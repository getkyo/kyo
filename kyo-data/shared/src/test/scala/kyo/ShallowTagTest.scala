package kyo

import scala.reflect.ClassTag

class ShallowTagTest extends kyo.test.Test[Any]:

    trait Animal
    trait Mammal  extends Animal
    trait Reptile extends Animal
    class Dog     extends Mammal
    class Cat     extends Mammal
    class Snake   extends Reptile

    trait Swimmer
    trait Flyer

    class Duck    extends Animal with Swimmer with Flyer
    class Penguin extends Animal with Swimmer

    abstract class Base
    class Derived extends Base with Swimmer
    class Other   extends Base with Swimmer with Flyer
    class Leaf    extends Derived

    opaque type UserId = String

    enum Color:
        case Red, Green, Blue

    case class Person(name: String, age: Int)

    // The array class a ShallowTag allocates must be the one a compiler-synthesized ClassTag allocates.
    inline def parity[A](using ct: ClassTag[A], scope: kyo.test.AssertScope): Unit =
        val shallow  = ShallowTag[A].newArray(1).getClass
        val expected = ct.newArray(1).getClass
        assert(shallow eq expected, s"ShallowTag array class ${shallow.getName} != ClassTag array class ${expected.getName}")
    end parity

    "matches the array class of a synthesized ClassTag" - {

        "primitives" in {
            parity[Int]
            parity[Long]
            parity[Double]
            parity[Float]
            parity[Byte]
            parity[Short]
            parity[Char]
            parity[Boolean]
            parity[Unit]
        }

        "top types" in {
            parity[Any]
            parity[AnyVal]
            parity[AnyRef]
            parity[Matchable]
        }

        "classes and traits" in {
            parity[String]
            parity[Dog]
            parity[Animal]
            parity[Person]
            parity[Color]
            parity[CustomInt]
            parity[java.lang.Integer]
        }

        "applied types" in {
            parity[List[Int]]
            parity[Option[String]]
            parity[Map[String, Int]]
            parity[Chunk[Int]]
            parity[Int => String]
            parity[Result[String, Int]]
        }

        "opaque types" in {
            parity[Duration]
            parity[UserId]
            parity[Maybe[Int]]
            parity[Maybe[String]]
            parity[Span[Int]]
            parity[IArray[Int]]
        }

        "arrays" in {
            parity[Array[Byte]]
            parity[Array[String]]
            parity[Array[List[Int]]]
            parity[Array[Array[Int]]]
            parity[Array[Duration]]
            parity[Array[Unit]]
        }

        "Span, an array with a wildcard element" in {
            parity[Span[Int]]
            parity[Span[String]]
            parity[Span[Any]]
            parity[Span[AnyVal]]
            parity[Span[Int | String]]
            parity[Span[Int | Double]]
            parity[Span[Duration]]
            parity[Span[Maybe[Int]]]
            parity[Span[Span[Int]]]
            parity[Array[Span[Int]]]
        }

        "unions" in {
            parity[Int | String]
            parity[Dog | Cat]
            parity[Dog | Snake]
            parity[Duck | Penguin]
            parity[Penguin | Duck]
            parity[Derived | Other]
            parity[Other | Derived]
            parity[Leaf | Derived]
            parity[Leaf | Other]
            parity[Dog | Duck]
            parity[Int | Unit]
            parity[String | CharSequence]
            parity[Int | Long]
            parity[String | Null]
            parity[Array[Int] | Array[Int]]
            parity[Array[Int] | Array[Long]]
            parity[Array[String] | Array[Dog]]
        }

        "intersections" in {
            parity[Dog & Swimmer]
            parity[Swimmer & Dog]
            parity[Swimmer & Flyer]
            parity[Flyer & Swimmer]
            parity[Mammal & Swimmer]
            parity[Base & Swimmer]
            parity[Derived & Base]
        }

        "singletons and literals" in {
            parity["a"]
            parity[1]
            parity[Color.Red.type]
            parity[Absent.type]
            parity[EmptyTuple]
        }

        "tuples" in {
            parity[(Int, String)]
            parity[Int *: EmptyTuple]
            parity[Int *: String *: EmptyTuple]
            parity[Tuple]
            parity[NonEmptyTuple]
        }
    }

    "erasedClass" - {
        "primitive" in {
            assert(ShallowTag[Int].erasedClass eq java.lang.Integer.TYPE)
        }

        "drops type arguments" in {
            assert(ShallowTag[List[Int]].erasedClass eq classOf[List[?]])
            assert(ShallowTag[List[Int]] == ShallowTag[List[String]])
        }

        "opaque type takes its underlying class" in {
            assert(ShallowTag[Duration].erasedClass eq java.lang.Long.TYPE)
            assert(ShallowTag[UserId].erasedClass eq classOf[String])
        }

        "union of unrelated types is Object" in {
            assert(ShallowTag[Int | String].erasedClass eq classOf[Object])
        }

        "wildcard array element takes its bound when one array kind holds every value" in {
            assert(ShallowTag[Array[? <: Int]].erasedClass eq classOf[Array[Int]])
            assert(ShallowTag[Array[? <: String]].erasedClass eq classOf[Array[String]])
            assert(ShallowTag[Array[? <: Duration]].erasedClass eq classOf[Array[Long]])
        }

        "wildcard array element that needs two array kinds is Object" in {
            assert(ShallowTag[Array[? <: Any]].erasedClass eq classOf[Object])
            assert(ShallowTag[Array[? <: AnyVal]].erasedClass eq classOf[Object])
            assert(ShallowTag[Array[? <: Int | Double]].erasedClass eq classOf[Object])
        }

        "Unit is BoxedUnit" in {
            assert(ShallowTag[Unit].erasedClass eq classOf[scala.runtime.BoxedUnit])
        }
    }

    "derivation" - {
        "derive, summon and apply produce the same tag" in {
            val derived  = ShallowTag.derive[Dog]
            val summoned = summon[ShallowTag[Dog]]
            val applied  = ShallowTag[Dog]
            assert(derived.erasedClass eq classOf[Dog])
            assert(summoned.erasedClass eq classOf[Dog])
            assert(applied.erasedClass eq classOf[Dog])
        }

        "derive is the given a using clause resolves to" in {
            def tagOf[A](using tag: ShallowTag[A]): ShallowTag[A] = tag
            assert(tagOf[Maybe[Int]].erasedClass eq ShallowTag.derive[Maybe[Int]].erasedClass)
        }

        "a derives clause is rejected, since ShallowTag is not a class type" in {
            typeCheckFailure("case class Point(x: Int) derives kyo.ShallowTag")("not a class type")
        }

        "evidence passed from the caller" in {
            def tagOf[A](using ShallowTag[A]): ShallowTag[A] = summon[ShallowTag[A]]
            assert(tagOf[String].erasedClass eq classOf[String])
            assert(tagOf[Int].erasedClass eq java.lang.Integer.TYPE)
        }

        "ignores a ClassTag given in scope" in {
            given ClassTag[Dog] = ClassTag(classOf[Object])
            assert(ShallowTag[Dog].erasedClass eq classOf[Dog])
        }

        "refuses an abstract type" in {
            typeCheckFailure("def f[A] = ShallowTag[A]")("This method requires a ShallowTag")
        }

        "refuses an abstract type with a ClassTag in scope" in {
            typeCheckFailure("def f[A: scala.reflect.ClassTag] = ShallowTag[A]")("This method requires a ShallowTag")
        }

        "refuses Nothing" in {
            typeCheckFailure("ShallowTag[Nothing]")("This method requires a ShallowTag")
        }

        "refuses Null" in {
            typeCheckFailure("ShallowTag[Null]")("This method requires a ShallowTag")
        }
    }

    "accepts and unapply" - {
        "an instance of the class and its subclasses" in {
            assert(ShallowTag[Dog].accepts(new Dog))
            assert(ShallowTag[Mammal].accepts(new Cat))
            assert(!ShallowTag[Dog].accepts(new Cat))
            assert(!ShallowTag[Mammal].accepts(new Snake))
        }

        "only the outer class of a generic type" in {
            assert(ShallowTag[List[Int]].accepts(List("a")))
            assert(!ShallowTag[List[Int]].accepts(Vector(1)))
        }

        "boxed values for a primitive type" in {
            assert(ShallowTag[Int].accepts(1))
            assert(ShallowTag[Boolean].accepts(true))
            assert(ShallowTag[Char].accepts('a'))
            assert(!ShallowTag[Int].accepts("1"))
        }

        "the boxed unit for Unit" in {
            assert(ShallowTag[Unit].accepts(()))
            assert(!ShallowTag[Unit].accepts(1))
        }

        "every value for a union erased to Object" in {
            assert(ShallowTag[Int | String].accepts(1.5))
        }

        "never null" in {
            assert(!ShallowTag[String].accepts(null))
            assert(!ShallowTag[AnyRef].accepts(null))
        }

        "unapply narrows in a pattern" in {
            val tag                 = ShallowTag[Dog]
            val dog                 = new Dog
            val matched: Maybe[Dog] =
                (dog: Any) match
                    case tag(d) => Present(d)
                    case _      => Absent
            assert(matched.exists(_ eq dog))
            assert(tag.unapply(new Cat).isEmpty)
        }
    }

    "fromClass" - {
        "void maps to BoxedUnit" in {
            assert(ShallowTag.fromClass[Unit](java.lang.Void.TYPE).erasedClass eq classOf[scala.runtime.BoxedUnit])
        }

        "reference class" in {
            assert(ShallowTag.fromClass[Dog](classOf[Dog]) == ShallowTag[Dog])
        }
    }

    "fromArray" - {
        "primitive array" in {
            assert(ShallowTag.fromArray(Array(1, 2)) == ShallowTag[Int])
        }

        "reference array keeps its runtime class" in {
            val animals: Array[Animal] = Array[Dog](new Dog).asInstanceOf[Array[Animal]]
            assert(ShallowTag.fromArray(animals).newArray(1).getClass eq animals.getClass)
        }
    }

    "newArray" - {
        "primitive" in {
            val arr = ShallowTag[Long].newArray(3)
            assert(arr.getClass eq classOf[Array[Long]])
            assert(arr.length == 3)
        }

        "reference" in {
            val arr = ShallowTag[String].newArray(2)
            assert(arr.getClass eq classOf[Array[String]])
            arr(0) = "a"
            assert(arr(0) == "a")
        }

        "fresh array per call" in {
            assert(!(ShallowTag[Int].newArray(1) eq ShallowTag[Int].newArray(1)))
        }

        "length zero is the shared empty array" in {
            assert(ShallowTag[String].newArray(0) eq ShallowTag[String].emptyArray)
            assert(ShallowTag[Int].newArray(0) eq Array.emptyIntArray)
        }
    }

    "emptyArray" - {
        "primitives are the scala empties" in {
            assert(ShallowTag[Int].emptyArray eq Array.emptyIntArray)
            assert(ShallowTag[Long].emptyArray eq Array.emptyLongArray)
            assert(ShallowTag[Double].emptyArray eq Array.emptyDoubleArray)
            assert(ShallowTag[Float].emptyArray eq Array.emptyFloatArray)
            assert(ShallowTag[Byte].emptyArray eq Array.emptyByteArray)
            assert(ShallowTag[Short].emptyArray eq Array.emptyShortArray)
            assert(ShallowTag[Char].emptyArray eq Array.emptyCharArray)
            assert(ShallowTag[Boolean].emptyArray eq Array.emptyBooleanArray)
        }

        "reference class is shared across calls" in {
            val a = ShallowTag[String].emptyArray
            val b = ShallowTag[String].emptyArray
            assert(a eq b)
            assert(a.length == 0)
        }

        "each class has its own array class" in {
            assert(ShallowTag[String].emptyArray.getClass eq classOf[Array[String]])
            assert(ShallowTag[Dog].emptyArray.getClass eq classOf[Array[Dog]])
            assert(ShallowTag[List[Int]].emptyArray.getClass eq classOf[Array[List[?]]])
        }

        "shared with fromArray of the same class" in {
            assert(ShallowTag.fromArray(Array("x")).emptyArray eq ShallowTag[String].emptyArray)
        }
    }

end ShallowTagTest
