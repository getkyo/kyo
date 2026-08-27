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

/** S2 shape: two brands over one underlying in one template, one of them declaring a tag. */
object TwoBrands:
    opaque type Feet   = Double
    opaque type Metres = Double
    object Feet:
        def apply(d: Double): Feet = d
    object Metres:
        def apply(d: Double): Metres = d
    given feetTag: Tag[Feet] = Tag.derive[Feet]
    // An inferred query for Tag[Metres] arrives as Tag[Double] here.
    val metresInferred: Tag[Metres] = inferred(Metres(1.0))
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
        val inferredIntErrors: List[String] = typeCheckErrors("inferred(Opt(1))").map(_.message)
end Opt

/** Duration's shape with the Memo pattern: a companion deriving tags, nothing declared. */
object Time:
    opaque type Duration = Long
    object Duration:
        def apply(l: Long): Duration        = l
        val derived: Tag[Duration]          = Tag.derive[Duration]
        val applyErrors: List[String]       = typeCheckErrors("Tag[Duration]").map(_.message)
        val inferredErrors: List[String]    = typeCheckErrors("inferred(Duration(1L))").map(_.message)
        val genuineLongErrors: List[String] = typeCheckErrors("inferred(1L)").map(_.message)
end Time
