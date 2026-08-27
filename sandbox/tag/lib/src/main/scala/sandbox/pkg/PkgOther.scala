package sandbox.pkg

import kyo.Tag
import sandbox.inferred

/** Same package, other file: PkgId is opaque here, so nothing collapses and nothing is refused. */
object PkgOther:
    val longOk: Tag[Long]         = Tag.derive[Long]
    val idOk: Tag[PkgId]          = Tag.derive[PkgId]
    val idInferred: Tag[PkgId]    = inferred(PkgId(1L))
end PkgOther

/** An object in the package that happens to share a name with nothing: also opaque here. */
object PkgIdHolder:
    val longOk: Tag[Long] = Tag.derive[Long]
