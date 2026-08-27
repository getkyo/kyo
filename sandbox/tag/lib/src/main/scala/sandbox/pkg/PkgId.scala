package sandbox.pkg

import kyo.Tag
import sandbox.errors
import sandbox.inferred
import scala.compiletime.testing.typeCheckErrors

/** A package-level opaque type: transparent only in its companion, which is in this file. */
opaque type PkgId = Long

object PkgId:
    def apply(l: Long): PkgId          = l
    val derived: Tag[PkgId]            = Tag.derive[PkgId]
    // Measured: in the companion of a top-level opaque type the collapse depends on the query.
    // Driven by the argument alone the query keeps PkgId; driven by an expected type it arrives
    // as Long. Comparison sees through PkgId there either way.
    val inferredUntyped                = inferred(PkgId(1L))
    val inferredTypedErrors: List[String] = errors(typeCheckErrors("val x: Tag[PkgId] = inferred(PkgId(1L)); x"))
    val longErrors: List[String]       = errors(typeCheckErrors("Tag.derive[Long]"))
    val unrelated: Tag[Int]            = Tag.derive[Int]
    val probeOwners: String            = sandbox.internal.ProbeMacro.owners
    val probeOpaques: String           = sandbox.internal.ProbeMacro.packageOpaques
    inline def infer[X](x: X): String  = sandbox.internal.ProbeMacro.seen[X]
    val probeSeen: String              = infer(PkgId(1L))
    val probeSame: String              = sandbox.internal.ProbeMacro.same[PkgId, Long]
end PkgId
