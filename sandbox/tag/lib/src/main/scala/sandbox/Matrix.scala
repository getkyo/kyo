package sandbox

import kyo.Tag
import scala.compiletime.summonInline
import scala.compiletime.testing.typeCheckErrors

// The shape matrix. Every kyo opaque declaration shape, each with in-scope cells: explicit
// derivations that must be accepted and equal the out-of-scope tag (values), and collapsed or
// genuine-underlying derivations that must be refused (probes carrying the error list).

object Shapes:

    /** Frame's shape: an upper bound over a plain underlying. */
    object Upper:
        opaque type Fr <: AnyRef = String
        object Fr:
            def apply(s: String): Fr           = s
            val derived: Tag[Fr]               = Tag.derive[Fr]
            val inferredErrors: List[String]   = errors(typeCheckErrors("inferred(Fr(\"a\"))"))
            val stringErrors: List[String]     = errors(typeCheckErrors("inferred(\"a\")"))
            val nestedErrors: List[String]     = errors(typeCheckErrors("inferred(List(\"a\"))"))
            val secondArgErrors: List[String]  = errors(typeCheckErrors("inferred(Map(1 -> \"a\"))"))
            val unrelated: Tag[Int]            = Tag.derive[Int]
            val unrelatedNested: Tag[List[Int]] = Tag.derive[List[Int]]
        end Fr
    end Upper

    /** Bounds that differ from the underlying on both sides. */
    class Animal
    class Mammal extends Animal
    class Cat    extends Mammal
    object Lower:
        opaque type Pet >: Cat <: Animal = Mammal
        object Pet:
            def apply(m: Mammal): Pet        = m
            val derived: Tag[Pet]            = Tag.derive[Pet]
            val inferredErrors: List[String] = errors(typeCheckErrors("inferred(Pet(new Mammal))"))
            val mammalErrors: List[String]   = errors(typeCheckErrors("inferred(new Mammal)"))
            val catOk: Tag[Cat]              = Tag.derive[Cat]
            val animalOk: Tag[Animal]        = Tag.derive[Animal]
        end Pet
    end Lower

    /** JsonRpcId's shape: a union underlying. */
    object Union:
        opaque type Id = String | Long
        object Id:
            def apply(s: String): Id          = s
            val derived: Tag[Id]              = Tag.derive[Id]
            val inferredErrors: List[String]  = errors(typeCheckErrors("inferred(Id(\"a\"))"))
            val unionErrors: List[String]     = errors(typeCheckErrors("Tag.derive[String | Long]"))
            val reorderedErrors: List[String] = errors(typeCheckErrors("Tag.derive[Long | String]"))
            val widerErrors: List[String]     = errors(typeCheckErrors("Tag.derive[String | Long | Int]"))
            val nestedWiderErrors: List[String] = errors(typeCheckErrors("Tag.derive[List[Int | String | Long]]"))
            val memberOk: Tag[String]         = Tag.derive[String]
            val otherUnionOk: Tag[String | Int] = Tag.derive[String | Int]
            val optionUnionOk: Tag[Option[String] | Long] = Tag.derive[Option[String] | Long]
        end Id
    end Union

    /** Async's shape: an intersection underlying with a reordered upper bound. */
    trait Sync
    trait Join
    trait Extra
    object Inter:
        opaque type Async <: (Sync & Join) = Join & Sync
        object Async:
            val derived: Tag[Async]        = Tag.derive[Async]
            val interErrors: List[String]  = errors(typeCheckErrors("Tag.derive[Sync & Join]"))
            val widerErrors: List[String]  = errors(typeCheckErrors("Tag.derive[Sync & Join & Extra]"))
            val memberOk: Tag[Sync]        = Tag.derive[Sync]
            val otherInterOk: Tag[Sync & Extra] = Tag.derive[Sync & Extra]
        end Async
    end Inter

    /** Parameterized shapes in every variance. */
    object Param:
        opaque type Box[A]        = List[A]
        opaque type Cov[+A]       = List[A]
        opaque type Contra[-A]    = A => Unit
        opaque type Mixed[+A, -B] = B => A
        object Box:
            def apply[A](a: A): Box[A]        = List(a)
            val derivedInt: Tag[Box[Int]]     = Tag.derive[Box[Int]]
            val inferredErrors: List[String]  = errors(typeCheckErrors("inferred(Box(1))"))
            val listIntErrors: List[String]   = errors(typeCheckErrors("Tag.derive[List[Int]]"))
            val nestedErrors: List[String]    = errors(typeCheckErrors("Tag.derive[Option[List[Int]]]"))
            val vectorOk: Tag[Vector[Int]]    = Tag.derive[Vector[Int]]
            val boxOfListErrors: List[String] = errors(typeCheckErrors("Tag.derive[Box[List[Int]]]"))
        end Box
        object Mixed:
            val derived: Tag[Mixed[Int, String]] = Tag.derive[Mixed[Int, String]]
            val fnErrors: List[String]           = errors(typeCheckErrors("Tag.derive[String => Int]"))
            val contraErrors: List[String]       = errors(typeCheckErrors("Tag.derive[Int => Unit]"))
            val curriedErrors: List[String]      = errors(typeCheckErrors("Tag.derive[Int => String => Int]"))
            val otherFnOk: Tag[(Int, Int) => Int] = Tag.derive[(Int, Int) => Int]
        end Mixed
    end Param

    /** Span's shape: a wildcard in the underlying. */
    object Wild:
        opaque type Sp[+A] = Array[? <: A]
        object Sp:
            val derived: Tag[Sp[Int]]           = Tag.derive[Sp[Int]]
            val arrayWildErrors: List[String]   = errors(typeCheckErrors("Tag.derive[Array[? <: Int]]"))
            val nestedWildErrors: List[String]  = errors(typeCheckErrors("Tag.derive[List[Array[? <: Int]]]"))
            val arrayIntOk: Tag[Array[Int]]     = Tag.derive[Array[Int]]
        end Sp
    end Wild

    /** Queue.Unbounded's shape: a chain in one template. */
    object Chain:
        opaque type Inner         = Int
        opaque type Outer <: Inner = Inner
        object Outer:
            def apply(i: Int): Outer         = i
            val derived: Tag[Outer]          = Tag.derive[Outer]
            val innerDerived: Tag[Inner]     = Tag.derive[Inner]
            val inferredErrors: List[String] = errors(typeCheckErrors("inferred(Outer(1))"))
            val intErrors: List[String]      = errors(typeCheckErrors("Tag.derive[Int]"))
        end Outer
    end Chain

    /** A chain across templates: Outer collapses to ChainA.Inner inside ChainB. */
    object ChainA:
        opaque type Inner = Int
        object Inner:
            def apply(i: Int): Inner = i
    object ChainB:
        opaque type Outer = ChainA.Inner
        object Outer:
            def apply(i: Int): Outer         = ChainA.Inner(i)
            val derived: Tag[Outer]          = Tag.derive[Outer]
            val inferredErrors: List[String] = errors(typeCheckErrors("inferred(Outer(1))"))
            val innerErrors: List[String]    = errors(typeCheckErrors("Tag.derive[ChainA.Inner]"))
            val intOk: Tag[Int]              = Tag.derive[Int]
        end Outer
    end ChainB

    /** Two brands over one underlying in separate templates. */
    object BrandA:
        opaque type Feet = Double
        object Feet:
            def apply(d: Double): Feet = d
    object BrandB:
        opaque type Metres = Double
        object Metres:
            def apply(d: Double): Metres   = d
            val derived: Tag[Metres]       = Tag.derive[Metres]
            val feetOk: Tag[BrandA.Feet]   = Tag.derive[BrandA.Feet]
            val feetInferredOk: Tag[BrandA.Feet] = inferred(BrandA.Feet(1.0))
            val doubleErrors: List[String] = errors(typeCheckErrors("Tag.derive[Double]"))
        end Metres
    end BrandB

    /** A constructor used unapplied. */
    object Higher:
        opaque type Wrap[A] = List[A]
        class Holder[F[_]]
        object Wrap:
            val derived: Tag[Holder[Wrap]]   = Tag.derive[Holder[Wrap]]
            val listErrors: List[String]     = errors(typeCheckErrors("Tag.derive[Holder[List]]"))
            // A term's own type survives the collapse even in a higher-kinded position.
            val inferredOk: Tag[Holder[Wrap]] = inferred(new Holder[Wrap])
            val vectorOk: Tag[Holder[Vector]] = Tag.derive[Holder[Vector]]
        end Wrap
    end Higher

    /** An underlying the matcher cannot walk: every derivation in scope is refused. */
    object MatchT:
        type PickImpl[A] = A match
            case Int => Long
            case _   => String
        opaque type Pick[A] = PickImpl[A]
        object Pick:
            val anyErrors: List[String]     = errors(typeCheckErrors("Tag.derive[Boolean]"))
            val derivedErrors: List[String] = errors(typeCheckErrors("Tag.derive[Pick[Int]]"))
    end MatchT
    trait Foo:
        def a: Int
    object Refined:
        opaque type R = Foo { def a: Int }
        object R:
            val anyErrors: List[String] = errors(typeCheckErrors("Tag.derive[Int]"))
    end Refined

    /** A trait template: transparent inside it, opaque in a class mixing it in. */
    trait TraitScope:
        opaque type Tv = Int
        def mk(i: Int): Tv               = i
        val inferredErrors: List[String] = errors(typeCheckErrors("inferred(mk(1))"))
        val intErrors: List[String]      = errors(typeCheckErrors("Tag.derive[Int]"))
    end TraitScope
    class TraitMixer extends TraitScope:
        val intOk: Tag[Int]      = Tag.derive[Int]
        val tvDerived: Tag[Tv]   = Tag.derive[Tv]
        val tvInferred: Tag[Tv]  = inferred(mk(1))
    end TraitMixer

    /** Sites within one template: the template itself, a nested object in the companion, a
      * sibling object inside the template, and a sibling object outside it.
      */
    object Nested:
        opaque type N = Int
        val templateErrors: List[String] = errors(typeCheckErrors("Tag.derive[Int]"))
        object N:
            object Deeper:
                val deeperErrors: List[String] = errors(typeCheckErrors("Tag.derive[Int]"))
                val derived: Tag[N]            = Tag.derive[N]
        object Sibling:
            val siblingErrors: List[String] = errors(typeCheckErrors("Tag.derive[Int]"))
    end Nested
    object NestedOutside:
        val intOk: Tag[Int]     = Tag.derive[Int]
        val nOk: Tag[Nested.N]  = Tag.derive[Nested.N]
        val nInferred: Tag[Nested.N] = inferred(1.asInstanceOf[Nested.N])

    /** Fiber's shape: an opaque type inside another's underlying, both transparent. */
    object FiberShape:
        class Pending[+A, -S]
        trait AsyncE
        class Promise[A, S]
        opaque type Kyo[+A, -S] = A | Pending[A, S]
        opaque type Fib[A, S] = Promise[Any, Kyo[A, AsyncE & S]]
        object Fib:
            val derived: Tag[Fib[Int, Any]]     = Tag.derive[Fib[Int, Any]]
            // Kyo is transparent here too, so a collapsed Fib never stops at this spelling: written
            // explicitly it means what it says.
            val promiseWithKyoOk: Tag[Promise[Any, Kyo[Int, AsyncE & Any]]] = Tag.derive[Promise[Any, Kyo[Int, AsyncE & Any]]]
            val collapsedErrors: List[String]   = errors(typeCheckErrors("Tag.derive[Promise[Any, Int | Pending[Int, AsyncE]]]"))
            val kyoCollapsedErrors: List[String] = errors(typeCheckErrors("Tag.derive[Int | Pending[Int, Any]]"))
            val promiseOtherOk: Tag[Promise[Int, Int]] = Tag.derive[Promise[Int, Int]]
        end Fib
    end FiberShape

    /** S9: inline bodies defined in scope and expanded outside. */
    object InlineScope:
        opaque type Iv = Int
        object Iv:
            def apply(i: Int): Iv                                   = i
            inline def tagOfIv(): Tag[Iv]                           = summonInline[Tag[Iv]]
            inline def tagOfInferred[A](a: A): Tag[A]               = summonInline[Tag[A]]
            inline def tagViaUsing[A](a: A)(using t: Tag[A]): Tag[A] = t
            inline def deriveIv(): Tag[Iv]                          = Tag.derive[Iv]
            val inScopeSummonErrors: List[String]                   = errors(typeCheckErrors("summonInline[Tag[Iv]]"))
        end Iv
    end InlineScope

    /** A chained inline given for the opaque type itself, defined in scope. */
    object ChainedGiven:
        opaque type Cg[A] = List[A]
        object Cg:
            val givenErrors: List[String] =
                errors(typeCheckErrors("inline given cgTag[A](using Tag[A]): Tag[Cg[A]] = Tag.derive[Cg[A]]"))
            val plainGivenErrors: List[String] = errors(typeCheckErrors("given cgIntTag: Tag[Cg[Int]] = Tag.derive[Cg[Int]]"))
            val anonymousGivenErrors: List[String] = errors(typeCheckErrors("given Tag[Cg[Int]] = Tag.derive[Cg[Int]]"))
            val otherGivenOk: Tag[Vector[Int]] = { given v: Tag[Vector[Int]] = Tag.derive[Vector[Int]]; v }
        end Cg
    end ChainedGiven

    /** RESIDUAL: an inline given's body only expands at a use site, so its definition cannot be
      * refused, and inside the scope it answers a collapsed query for the underlying type.
      */
    object InlineGivenHarm:
        opaque type Cg2[A] = List[A]
        object Cg2:
            inline given cg2Tag[A](using Tag[A]): Tag[Cg2[A]] = Tag.derive[Cg2[A]]
            val listInferred: Tag[List[Int]]                    = inferred(List(1))
        end Cg2
    end InlineGivenHarm
end Shapes
