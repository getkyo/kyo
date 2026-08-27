package sandbox.probes

import sandbox.internal.ProbeMacro

object ScopeProbe:
    type PickImpl[A] = A match
        case Int => Long
        case _   => String
    opaque type Pick[A] = PickImpl[A]
    opaque type Ref     = Runnable { def run(): Unit }
    object Pick:
        val pick: String = ProbeMacro.underlyingOf[Pick[Int]]
        val ref: String  = ProbeMacro.underlyingOf[Ref]
end ScopeProbe

object PkgProbeMain:
    def main(args: Array[String]): Unit =
        println("Pick underlying   " + ScopeProbe.Pick.pick)
        println("Ref underlying    " + ScopeProbe.Pick.ref)
        println("PkgId owners      " + sandbox.pkg.PkgProbe.owners)
        println("PkgId pkg opaques " + sandbox.pkg.PkgProbe.opaques)
        println("PkgId seen        " + sandbox.pkg.PkgId.probeSeen)
        println("PkgId same        " + sandbox.pkg.PkgId.probeSame)
    end main
end PkgProbeMain
