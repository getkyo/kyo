package kyo

import kyo.*
import kyo.internal.RegisterFunction
import kyo.internal.TagHash
import kyo.internal.TagTestMacro.test
import scala.annotation.nowarn

class TagTest extends kyo.test.Test[Any]:

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

        opaque type Covariant[+A]     = List[A]
        opaque type Contravariant[-A] = A => Unit
        opaque type Mixed[+A, -B]     = B => A

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

            "different type arguments are different types" - {
                test[Box[Int], Box[String]]
                test[Box[String], Box[Int]]
                test[Pair[Int, String], Pair[String, Int]]
                test[Nested[Int], Nested[String]]
            }

            "invariant type parameter" - {
                test[Box[Int], Box[Any]]
                test[Box[Any], Box[Int]]
            }

            "covariant type parameter" - {
                test[Covariant[Int], Covariant[Any]]
                test[Covariant[Any], Covariant[Int]]
            }

            "contravariant type parameter" - {
                test[Contravariant[Any], Contravariant[Int]]
                test[Contravariant[Int], Contravariant[Any]]
            }

            "mixed variance" - {
                test[Mixed[Int, Any], Mixed[Any, Int]]
                test[Mixed[Any, Int], Mixed[Int, Any]]
            }
        }

    }

    // Inside the template that declares an opaque type, and inside its companion, the compiler
    // replaces the opaque type with its underlying type before any macro runs. A tag derived there
    // still has to agree with one derived outside, or a value handed across that boundary is looked
    // up under a different key than it was stored under.
    object ScopedMeters:
        opaque type Meters = Long
        object Meters:
            def apply(value: Long): Meters = value

            // Says that Long on a tag surface means Meters throughout this scope.
            given tag: Tag[Meters] = Tag.derive[Meters]

            def bare: Tag[Meters]                = Tag[Meters]
            def nested: Tag[List[Meters]]        = Tag[List[Meters]]
            def deeper: Tag[Map[String, Meters]] = Tag[Map[String, Meters]]
            def inUnion: Tag[Meters | String]    = Tag[Meters | String]
        end Meters
    end ScopedMeters

    // A second brand over the same underlying type, in its own scope. Sharing one scope with Meters
    // would leave nothing able to say which of the two a Long there means.
    object ScopedFeet:
        opaque type Feet = Long
        object Feet:
            given tag: Tag[Feet] = Tag.derive[Feet]
            def bare: Tag[Feet]  = Tag[Feet]
        end Feet
    end ScopedFeet

    // A union underlying type collapses to a union, which carries no type arguments to match on.
    object ScopedUnion:
        opaque type Id = String | Long
        object Id:
            given tag: Tag[Id]              = Tag.derive[Id]
            def bare: Tag[Id]               = Tag[Id]
            def nested: Tag[List[Id]]       = Tag[List[Id]]
            def wildcard: Tag[Set[? <: Id]] = Tag[Set[? <: Id]]
        end Id
    end ScopedUnion

    // A parameterized opaque type collapses to its underlying applied to the node's own arguments,
    // so recovering it means matching that shape and reading the arguments back out.
    object ScopedBoxed:
        opaque type Boxed[A] = List[A]
        object Boxed:
            inline given tag[A: Tag]: Tag[Boxed[A]]  = Tag.derive[Boxed[A]]
            def bare: Tag[Boxed[Int]]                = Tag[Boxed[Int]]
            def nested: Tag[Map[String, Boxed[Int]]] = Tag[Map[String, Boxed[Int]]]
        end Boxed
    end ScopedBoxed

    // An opaque type constructor used unapplied, so it reaches the macro as the underlying type
    // lambda rather than as an applied type.
    object ScopedHigher:
        trait Higher[F[_]]
        opaque type Boxed[A] = List[A]
        object Boxed:
            inline given tag[A: Tag]: Tag[Boxed[A]] = Tag.derive[Boxed[A]]

            val value: Higher[Boxed] = new Higher[Boxed] {}

            def infer[F[_]](x: Higher[F])(using t: Tag[Higher[F]]): Tag[Higher[F]] = t

            def explicitInside: Tag[Higher[Boxed]] = Tag[Higher[Boxed]]
            def inferredInside                     = infer(value)
        end Boxed
    end ScopedHigher

    // No declared tag, so a derivation naming the underlying type inside this scope has nothing
    // saying which type was meant. The probes sit inside the scope because that is where the
    // substitution happens; compiled from the test body they would say nothing.
    object RefusesUndeclared:
        opaque type Undeclared = Long
        object Undeclared:
            def bare(using kyo.test.AssertScope, Frame): Unit =
                typeCheckFailure("Tag[Long]")("nothing here says whether")
            def nested(using kyo.test.AssertScope, Frame): Unit =
                typeCheckFailure("Tag[List[Long]]")("nothing here says whether")
            def inArgument(using kyo.test.AssertScope, Frame): Unit =
                typeCheckFailure("Tag[Map[String, Long]]")("nothing here says whether")
        end Undeclared
    end RefusesUndeclared

    // Two brands over one underlying type, neither declaring a tag: the underlying names both, so
    // no declaration could say which was meant and the derivation is refused rather than guessing.
    //
    // Declaring a tag for one of them does not reach this rule. Inside the scope Tag[Feet] and
    // Tag[Double] are the same type, so implicit search answers a Tag[Double] query with that
    // declaration before the macro runs, and the derivation silently means Feet.
    object RefusesAmbiguous:
        opaque type Feet   = Double
        opaque type Metres = Double
        object Probe:
            def ambiguous(using kyo.test.AssertScope, Frame): Unit =
                typeCheckFailure("Tag[Double]")("all of them transparent here")
        end Probe
    end RefusesAmbiguous

    // One opaque type over another, each in its own scope so neither is transparent where the
    // other is declared.
    object ScopedChain:
        object Level1:
            opaque type Inner = Int
            object Inner:
                def apply(value: Int): Inner = value
                given tag: Tag[Inner]        = Tag.derive[Inner]
                def bare: Tag[Inner]         = Tag[Inner]
            end Inner
        end Level1

        object Level2:
            import Level1.Inner
            opaque type Outer = Inner
            object Outer:
                given tag: Tag[Outer] = Tag.derive[Outer]
                def bare: Tag[Outer]  = Tag[Outer]
            end Outer
        end Level2
    end ScopedChain

    "with an opaque type in its own scope" - {
        import ScopedMeters.*
        import ScopedFeet.*

        "a tag derived inside equals one derived outside" - {
            "bare" in assert(Meters.bare =:= Tag[Meters])
            "nested in a type argument" in assert(Meters.nested =:= Tag[List[Meters]])
            "nested deeper" in assert(Meters.deeper =:= Tag[Map[String, Meters]])
            "in a union" in assert(Meters.inUnion =:= Tag[Meters | String])
        }

        "and is not the underlying type's tag" - {
            "bare" in assert(Meters.bare =!= Tag[Long])
            "nested in a type argument" in assert(Meters.nested =!= Tag[List[Long]])
        }

        "two opaque types over the same underlying stay distinct" - {
            "derived inside their own scopes" in assert(Meters.bare =!= Feet.bare)
            "derived outside" in assert(Tag[Meters] =!= Tag[Feet])
        }

        "with a union underlying type" - {
            import ScopedUnion.*
            "bare" in assert(Id.bare =:= Tag[Id])
            "nested in a type argument" in assert(Id.nested =:= Tag[List[Id]])
            "in a wildcard bound" in assert(Id.wildcard =:= Tag[Set[? <: Id]])
            "not the underlying union's tag" in assert(Id.nested =!= Tag[List[String | Long]])
        }

        "with type parameters" - {
            import ScopedBoxed.*
            "bare" in assert(Boxed.bare =:= Tag[Boxed[Int]])
            "nested in a type argument" in assert(Boxed.nested =:= Tag[Map[String, Boxed[Int]]])
            "not the underlying type's tag" in assert(Boxed.bare =!= Tag[List[Int]])
            "distinct per type argument" in assert(Tag[Boxed[Int]] =!= Tag[Boxed[String]])
        }

        "used unapplied in a higher-kinded position" - {
            import ScopedHigher.*
            "explicitly written inside the scope" in {
                assert(Boxed.explicitInside =:= Tag[Higher[Boxed]])
            }
            "inferred inside the scope" in {
                assert(Boxed.inferredInside =:= Tag[Higher[Boxed]])
            }
            "and neither is the underlying type constructor's tag" in {
                assert(Boxed.explicitInside =!= Tag[Higher[List]])
                assert(Boxed.inferredInside =!= Tag[Higher[List]])
            }
        }

        "an ambiguous derivation is refused" - {
            "a bare underlying type" in RefusesUndeclared.Undeclared.bare
            "nested in a type argument" in RefusesUndeclared.Undeclared.nested
            "in one argument of several" in RefusesUndeclared.Undeclared.inArgument
            "when two opaque types claim the same underlying" in RefusesAmbiguous.Probe.ambiguous
        }

        "the scoped types answer subtyping the way the compiler does" - {
            import ScopedMeters.*
            import ScopedBoxed.*
            test[Meters, Long]
            test[Long, Meters]
            test[Meters, Any]
            test[Boxed[Int], List[Int]]
            test[List[Int], Boxed[Int]]
            test[Boxed[Int], Boxed[String]]
        }

        "one opaque type over another" - {
            import ScopedChain.Level1.Inner
            import ScopedChain.Level2.Outer
            "each agrees with its own scope" in {
                assert(Inner.bare =:= Tag[Inner])
                assert(Outer.bare =:= Tag[Outer])
            }
            "and all three stay distinct" in {
                assert(Tag[Outer] =!= Tag[Inner])
                assert(Tag[Inner] =!= Tag[Int])
                assert(Tag[Outer] =!= Tag[Int])
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

        "type params".pendingUntilFixed("Tag.show does not yet render type parameters (Tag[Test[Int]].show omits the type argument)") in {
            class Test[A]
            assert(Tag[Test[Int]].show == s"${classOf[Test[?]].getName}[scala.Int]")
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
            // kyo-test runs leaf bodies in a deferred closure (the AssertScope context function), which adds one owner level to leaf-local type names.
            assert(Tag[CustomType].show == "kyo.TagTest._$_$CustomType")
        }
    }

    "type with large name" in {
        class A0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789
        val tag = Tag[A0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789]
        assert(tag =:= tag && tag <:< tag)
    }

    // Regression: deriving a Tag for a type whose Java supertypes are *parameterized*
    // crashed the macro. A Java wildcard `<?>` argument surfaces through `typeArgs`
    // as a bare `TypeBounds(Nothing, FromJavaObject)`, which `TagMacro` did not
    // handle, raising `AssertionError: TypeBounds(...)` inside the dotty compiler.
    "Java parameterized supertypes (wildcard type args)" - {
        "LocalDateTime derives without crashing" in {
            val tag = Tag[java.time.LocalDateTime]
            assert(tag.show.nonEmpty)
            assert(tag =:= tag)
            assert(tag <:< tag)
        }

        "OffsetDateTime derives without crashing" in {
            val tag = Tag[java.time.OffsetDateTime]
            assert(tag.show.nonEmpty)
            assert(tag =:= tag && tag <:< tag)
        }

        "ZonedDateTime derives without crashing" in {
            val tag = Tag[java.time.ZonedDateTime]
            assert(tag.show.nonEmpty)
            assert(tag =:= tag && tag <:< tag)
        }

        "LocalDate / LocalTime still derive (unparameterized supertypes)" in {
            assert(Tag[java.time.LocalDate].show.nonEmpty)
            assert(Tag[java.time.LocalTime].show.nonEmpty)
        }

        "distinct java.time tags are not equal" in {
            assert(Tag[java.time.LocalDateTime] =!= Tag[java.time.LocalDate])
            assert(Tag[java.time.LocalDateTime] =!= Tag[java.time.LocalTime])
            assert(Tag[java.time.LocalDateTime] =!= Tag[java.time.OffsetDateTime])
        }

        "direct wildcard-parameterized Java type derives" in {
            // java.lang.Class is declared as Class<T> with no wildcard; use a
            // type whose own supertype list contains a parameterized interface.
            val tag = Tag[java.util.concurrent.atomic.AtomicReference[String]]
            assert(tag.show.nonEmpty)
            assert(tag =:= tag)
        }

        "case class with a LocalDateTime field can summon its field Tag" in {
            final case class HasTime(at: java.time.LocalDateTime, label: String)
            val tag = Tag[HasTime]
            assert(tag.show.nonEmpty)
            assert(tag =:= tag && tag <:< tag)
        }
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

                // kyo-test runs leaf bodies in a deferred closure (the AssertScope context function), which adds one owner level to leaf-local type names.
                assert(Tag[A].show == "kyo.TagTest._$_$A")
                assert(showSet(Tag[A | B]) == Set("kyo.TagTest._$_$B", "kyo.TagTest._$_$A"))
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
            test[Any, AnyRef]
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
            def test2[A: Tag] = test[Box2[A], Box2[A]]
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
            typeCheck("testGeneric[Int]")
            typeCheck("testGeneric[String]")
        }

        "generic with bounds" in {
            def testBounded[A <: AnyVal](using Tag[A]) = Tag[Option[A]]
            typeCheck("testBounded[Int]")
            typeCheck("testBounded[Double]")
        }

        "nested generic" in {
            def testNestedGeneric[A, B](using Tag[A], Tag[B]) = Tag[Map[A, List[B]]]
            typeCheck("testNestedGeneric[String, Int]")
            typeCheck("testNestedGeneric[Int, Boolean]")
        }

        "generic with variance" - {
            "covariance" in {
                class Covariant[+A]
                def testCovariance[A](using Tag[A]) = Tag[Covariant[A]]
                typeCheck("testCovariance[Int]")
                typeCheck("testCovariance[String]")
            }

            "contravariance" in {
                class Contravariant[-A]
                def testContravariance[A](using Tag[A]) = Tag[Contravariant[A]]
                typeCheck("testContravariance[Int]")
                typeCheck("testContravariance[String]")
            }

            "invariance" in {
                class Invariant[A]
                def testInvariance[A](using Tag[A]) = Tag[Invariant[A]]
                typeCheck("testInvariance[Int]")
                typeCheck("testInvariance[String]")
            }

            "mixed variance" in {
                class Mixed[+A, -B, C]
                def testMixed[A, B, C](using Tag[A], Tag[B], Tag[C]) = Tag[Mixed[A, B, C]]
                typeCheck("testMixed[Int, String, Boolean]")
            }
        }

        "sealed trait with generic parameters" - {
            "one" in {
                sealed trait SealedGeneric[A]
                def testSealed[A: Tag] = Tag[SealedGeneric[A]]
                typeCheck("testSealed[Int]")
                typeCheck("testSealed[String]")
            }
            "two" in {
                sealed trait SealedGeneric[A, B]
                def testSealed[A, B](using Tag[A], Tag[B]) = Tag[SealedGeneric[A, B]]
                typeCheck("testSealed[Int, String]")
                typeCheck("testSealed[Boolean, Double]")
            }
        }

        "opaque types" - {
            import Opaques.*

            "simple opaque type" in {
                def testOpaque = Tag[MyInt]
                typeCheck("testOpaque")
            }

            "opaque type with type parameter" in {
                def testOpaqueGeneric[A](using Tag[A]) = Tag[MyList[A]]
                typeCheck("testOpaqueGeneric[Int]")
            }

            "nested opaque types" in {
                def testNestedOpaque[A](using Tag[A]) = Tag[Outer[A]]
                typeCheck("testNestedOpaque[String]")
            }
        }
    }

    // Declared in their own object so their aliases are transparent only inside it. Declared at
    // class level they would be transparent throughout the suite, which both makes every derivation
    // mentioning Int or List ambiguous and silently turns Tag[MyInt] here into Tag[Int].
    object Opaques:
        opaque type MyInt     = Int
        opaque type MyList[A] = List[A]
        opaque type Inner[A]  = List[A]
        opaque type Outer[B]  = Inner[B]
    end Opaques

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

        "different types with similar string representation" - {
            test[1, 1.0]
            test[1, 1L]
            test['a', "a"]
            test[true, "true"]
            test[1.0f, 1.0]
            test[0, 0.0]
            test[0, 0L]
            class Box[A]
            test[Box[1], Box[1.0]]
        }

        "Null vs literals" - {
            test[Null, 1]
            test[Null, "A"]
            test[List[Null], List["A"]]
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

    // In its own object so `Vector[Any]` below still means `Vector[Any]`; declared at class level
    // the compiler would substitute V for it throughout the suite.
    object Bounded:
        opaque type V <: Vector[Any] = Vector[Any]
    end Bounded

    "opaque type bounds with variance (bug #1368)" in {
        import Bounded.*
        abstract class Variant[+A]:
            def method[AA >: A](using Tag[AA]): Unit

        def x: Variant[V] = ???
        def works1        = x.method[V]
        def works2        = x.method[Vector[Any]]

        typeCheck("x.method")
    }

    "show determinism" - {

        "intersection order is canonical" - {
            trait SA
            trait SB
            trait SC

            "A & B == B & A" in {
                assert(Tag[SA & SB].show == Tag[SB & SA].show)
            }

            "A & B & C == C & B & A" in {
                assert(Tag[SA & SB & SC].show == Tag[SC & SB & SA].show)
            }

            "A & B & C == B & C & A" in {
                assert(Tag[SA & SB & SC].show == Tag[SB & SC & SA].show)
            }
        }

        "union order is canonical" - {
            trait SA
            trait SB
            trait SC

            "A | B == B | A" in {
                assert(Tag[SA | SB].show == Tag[SB | SA].show)
            }

            "A | B | C == C | B | A" in {
                assert(Tag[SA | SB | SC].show == Tag[SC | SB | SA].show)
            }

            "A | B | C == B | C | A" in {
                assert(Tag[SA | SB | SC].show == Tag[SB | SC | SA].show)
            }
        }

        // An opaque type's arguments reach the encoding, so whatever canonical order the encoder
        // gives a union or an intersection has to survive being one.
        "opaque type arguments are canonical" - {
            import OpaqueTypes.*
            trait SA
            trait SB

            "Box[A | B] == Box[B | A]" in {
                assert(Tag[Box[SA | SB]].show == Tag[Box[SB | SA]].show)
                assert(Tag[Box[SA | SB]].hash == Tag[Box[SB | SA]].hash)
            }

            "Box[A & B] == Box[B & A]" in {
                assert(Tag[Box[SA & SB]].show == Tag[Box[SB & SA]].show)
                assert(Tag[Box[SA & SB]].hash == Tag[Box[SB & SA]].hash)
            }

            "Pair[A & B, Int] == Pair[B & A, Int]" in {
                assert(Tag[Pair[SA & SB, Int]].show == Tag[Pair[SB & SA, Int]].show)
                assert(Tag[Pair[SA & SB, Int]].hash == Tag[Pair[SB & SA, Int]].hash)
            }

            "argument order is not canonicalized away" in {
                assert(Tag[Pair[Int, String]].show != Tag[Pair[String, Int]].show)
            }
        }

        "nested intersection and union" - {
            trait SA
            trait SB
            trait SC
            trait SD

            "(A & B) | (C & D) == (D & C) | (B & A)" in {
                assert(Tag[(SA & SB) | (SC & SD)].show == Tag[(SD & SC) | (SB & SA)].show)
            }

            "(A | B) & (C | D) == (D | C) & (B | A)" in {
                assert(Tag[(SA | SB) & (SC | SD)].show == Tag[(SD | SC) & (SB | SA)].show)
            }
        }

        "parameterized types" - {
            "List[Int] is stable" in {
                assert(Tag[List[Int]].show == Tag[List[Int]].show)
            }

            "Map[String, Int] is stable" in {
                assert(Tag[Map[String, Int]].show == Tag[Map[String, Int]].show)
            }
        }

        "dynamic tags" - {
            "simple dynamic tag is stable" in {
                def mkTag[A: Tag] = Tag[List[A]].show
                assert(mkTag[Int] == mkTag[Int])
                assert(mkTag[String] == mkTag[String])
                assert(mkTag[Int] != mkTag[String])
            }

            "dynamic intersection is canonical" in {
                def mkIntersectionAB[A: Tag, B: Tag] = Tag[A & B].show
                def mkIntersectionBA[A: Tag, B: Tag] = Tag[B & A].show
                assert(mkIntersectionAB[Int, String] == mkIntersectionBA[Int, String])
            }

            "dynamic union is canonical" in {
                def mkUnionAB[A: Tag, B: Tag] = Tag[A | B].show
                def mkUnionBA[A: Tag, B: Tag] = Tag[B | A].show
                assert(mkUnionAB[Int, String] == mkUnionBA[Int, String])
            }

            "nested dynamic is stable" in {
                def mkTag[A: Tag, B: Tag] = Tag[Map[A, List[B]]].show
                assert(mkTag[String, Int] == mkTag[String, Int])
                assert(mkTag[String, Int] != mkTag[Int, String])
            }

            "dynamic with intersection reorder" in {
                def mk1[A: Tag, B: Tag, C: Tag] = Tag[A & B & C].show
                def mk2[A: Tag, B: Tag, C: Tag] = Tag[C & A & B].show
                assert(mk1[Int, String, Boolean] == mk2[Int, String, Boolean])
            }
        }

        "repeated calls produce same result" in {
            trait SA
            trait SB
            val results = (1 to 10).map(_ => Tag[SA & SB].show)
            assert(results.distinct.size == 1)
        }
    }

    "hash content-stability" - {

        "intersection order is canonical (the hash is content-derived, not identity)" in {
            trait HA
            trait HB
            assert(Tag[HA & HB].hash == Tag[HB & HA].hash)
        }

        "repeated calls produce the same hash" in {
            trait HC
            assert((1 to 10).map(_ => Tag[HC].hash).distinct.size == 1)
        }

        "distinct types have distinct hashes" in {
            assert(Tag[Int].hash != Tag[String].hash)
            assert(Tag[List[Int]].hash != Tag[List[String]].hash)
        }

        // kyo-aeron derives aeron stream ids from `Tag.hash`, so a publish and a subscribe of the same
        // type in separate JVM processes must hash identically. Pinning to constants is the in-JVM proxy:
        // the hash is content-derived (XXH32 applied to the encoded tag's JLS string hash), so it
        // reproduces these values on any JVM. An identity-derived hash would vary across processes
        // and break that.
        "is pinned to its content-derived constant (process-independent determinism)" in {
            assert(Tag[Int].hash == -1492440803, s"Tag[Int].hash = ${Tag[Int].hash}")
            assert(Tag[String].hash == -59591402, s"Tag[String].hash = ${Tag[String].hash}")
        }
    }

    // `TagHash` is the dispatch hash, not the content-stable `Tag.hash` above: it memoizes on the
    // platforms whose `String.hashCode` does not. What has to hold is that memoizing changes nothing,
    // so each case reads a tag twice, once filling the memo and once through it.
    "dispatch hash memoization" - {

        "agrees with hashCode, before and after the memo is filled" in {
            trait MA
            val tag    = Tag[MA]
            val direct = tag.hashCode
            assert(TagHash.of(tag) == direct)
            assert(TagHash.of(tag) == direct)
        }

        "distinct types keep distinct dispatch hashes" in {
            assert(TagHash.of(Tag[Int]) != TagHash.of(Tag[String]))
        }

        "repeated comparisons hold their verdict once the memo is warm" in {
            trait MB
            trait MC extends MB
            val sub    = (1 to 10).map(_ => Tag[MC] <:< Tag[MB])
            val notSub = (1 to 10).map(_ => Tag[MB] <:< Tag[MC])
            val notEq  = (1 to 10).map(_ => Tag[MB] =:= Tag[MC])
            assert(sub.distinct.size == 1 && sub.head)
            assert(notSub.distinct.size == 1 && !notSub.head)
            assert(notEq.distinct.size == 1 && !notEq.head)
        }
    }

    // TODO: fix this to use `pendingUntilFixed` instead of `ignore`
    given RegisterFunction = (name, test, pending) =>
        if pending then name.ignore in test
        else name in { test; succeed("the real check is the scala.Predef.assert inside test; succeed registers the leaf with AssertScope") }

end TagTest
