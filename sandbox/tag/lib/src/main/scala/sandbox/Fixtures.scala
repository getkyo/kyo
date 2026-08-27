package sandbox

import kyo.Tag
import scala.compiletime.testing.typeCheckErrors

// Opaque types declared the way kyo declares its own, compiled separately from the tests that
// read them, so the tests see them through TASTy exactly as user code sees kyo's.
//
// A cell expected to be accepted is a value. A cell expected to be refused is a typeCheckErrors
// probe placed at the site whose answer is wanted: the string is typechecked there, inside the
// scope, so the refusal (or its absence) is that site's answer.

def inferred[A](a: A)(using t: Tag[A]): Tag[A] = t

def errors(es: List[scala.compiletime.testing.Error]): List[String] = es.map(_.message)

/** S2 shape: two brands over one underlying in one template. */
object TwoBrands:
    opaque type Feet   = Double
    opaque type Metres = Double
    object Feet:
        def apply(d: Double): Feet = d
    object Metres:
        def apply(d: Double): Metres = d
    val feetDerived: Tag[Feet]     = Tag.derive[Feet]
    val metresDerived: Tag[Metres] = Tag.derive[Metres]
    val givenErrors: List[String]  = errors(typeCheckErrors("given feetTag: Tag[Feet] = Tag.derive[Feet]"))
    val metresInferredErrors: List[String] = errors(typeCheckErrors("inferred(Metres(1.0))"))
    val doubleErrors: List[String]         = errors(typeCheckErrors("inferred(1.0)"))
end TwoBrands

/** Maybe's shape, verbatim: a union underlying with a matching lower bound. */
sealed abstract class Absent
case object Absent extends Absent
final class PresentAbsent
object Opt:
    opaque type Present[+A] = A | PresentAbsent
    opaque type Opt[+A] >: (Absent | Present[A]) = Absent | Present[A]
    object Opt:
        def apply[A](a: A): Opt[A]          = a
        val derivedInt: Tag[Opt[Int]]       = Tag.derive[Opt[Int]]
        val inferredIntErrors: List[String] = errors(typeCheckErrors("inferred(Opt(1))"))
        val listErrors: List[String]        = errors(typeCheckErrors("inferred(List(Opt(1)))"))
        val unrelated: Tag[List[String]]    = Tag.derive[List[String]]
    end Opt
end Opt

/** Duration's shape with the Memo pattern: a companion deriving tags, nothing declared. */
object Time:
    opaque type Duration = Long
    object Duration:
        def apply(l: Long): Duration        = l
        val derived: Tag[Duration]          = Tag.derive[Duration]
        val applyErrors: List[String]       = errors(typeCheckErrors("Tag[Duration]"))
        val inferredErrors: List[String]    = errors(typeCheckErrors("inferred(Duration(1L))"))
        val genuineLongErrors: List[String] = errors(typeCheckErrors("inferred(1L)"))
        val unrelated: Tag[Int]             = Tag.derive[Int]
    end Duration
end Time
