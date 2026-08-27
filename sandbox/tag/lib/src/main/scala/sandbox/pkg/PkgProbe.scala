package sandbox.pkg

import sandbox.internal.ProbeMacro

object PkgProbe:
    val owners: String  = PkgId.probeOwners
    val opaques: String = PkgId.probeOpaques
