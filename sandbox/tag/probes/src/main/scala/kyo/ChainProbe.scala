package sandbox.probes

// Recorded 2026-08-26, Scala 3.8.4:
//   B.collapsed     seen=A.Inner(opaque)            an Outer inferred inside B arrives as A.Inner, not Int
//   B.explicitOuter dealias=A.Inner                 dealias stops at the first opaque not transparent here
//   B.sameIn1       Outer =:= A.Inner: true         =:= sees through Outer inside B
//   B.sameIn2       A.Inner =:= Int: false          and not through A.Inner there
//   Outside.same3   Feet =:= Double: false          outside a scope, comparison is the typer's answer
//   Outside.same4   Opt[Int] >:> Absent|Present[Int] only via the declared lower bound
//   C.collapsed     seen=Int                        a same-template chain collapses all the way
//   T.inTrait       seen=Int                        transparent in the trait template
//   K.inClass       seen=K.this.Tv(opaque)          not transparent in a class mixing the trait in
//   D.inLambda/Anon owner chain still reaches Dv$   lambdas and anonymous classes do not hide the scope
//   PkgId owners    PkgId$ <- PkgId$package$ <- pkg  a top-level opaque type is declared in the file's
//                                                   wrapper class, and its companion is owned by it
//   PkgId seen      seen=PkgId(opaque), =:= Long     in that companion an argument-driven query keeps PkgId
//                                                   while comparison sees through; an expected type
//                                                   Tag[PkgId] on the definition collapses it to Long
//   Pick underlying [A] =>> PickImpl[A]              an alias applied to a lambda parameter never dealiases

import sandbox.internal.ProbeMacro

// Chain across templates: what does Outer collapse to inside B, and what does B's macro see?
object A:
    opaque type Inner = Int
    object Inner:
        def apply(i: Int): Inner = i

object B:
    opaque type Outer = A.Inner
    inline def infer[X](x: X): String = ProbeMacro.seen[X]
    val collapsed: String      = infer(A.Inner(1): Outer)
    val explicitOuter: String  = ProbeMacro.seen[Outer]
    val explicitInner: String  = ProbeMacro.seen[A.Inner]
    val ownersHere: String     = ProbeMacro.owners
    val sameIn1: String        = ProbeMacro.same[Outer, A.Inner]
    val sameIn2: String        = ProbeMacro.same[A.Inner, Int]
    val sameIn3: String        = ProbeMacro.same[Outer, Int]
end B
object Outside:
    val same1: String = ProbeMacro.same[B.Outer, A.Inner]
    val same2: String = ProbeMacro.same[A.Inner, Int]
    val same3: String = ProbeMacro.same[sandbox.TwoBrands.Feet, Double]
    val same4: String = ProbeMacro.same[sandbox.Opt.Opt[Int], sandbox.Absent | sandbox.Opt.Present[Int]]
    val same5: String = ProbeMacro.same[Int, sandbox.Opt.Opt[Int]]
    val same6: String = ProbeMacro.same[sandbox.Opt.Present[Int], Int]

// Chain in one template.
object C:
    opaque type Inner = Int
    opaque type Outer = Inner
    inline def infer[X](x: X): String = ProbeMacro.seen[X]
    val collapsed: String      = infer((1: Inner): Outer)
end C

// Trait template and a class mixing it in.
trait T:
    opaque type Tv = Int
    def mk(i: Int): Tv         = i
    inline def infer[X](x: X): String = ProbeMacro.seen[X]
    val inTrait: String        = infer(mk(1))
    val ownersInTrait: String  = ProbeMacro.owners
end T
class K extends T:
    val inClass: String       = infer(mk(1))
    val ownersInClass: String = ProbeMacro.owners

// Lambda and anonymous class inside a companion.
object D:
    opaque type Dv = Int
    object Dv:
        def apply(i: Int): Dv         = i
        val inLambda: String          = List(1).map(_ => ProbeMacro.owners).head
        val inAnon: String            = { var o = ""; new Runnable { def run() = o = ProbeMacro.owners }.run(); o }
        val collapsedInLambda: String = List(Dv(1)).map(x => infer(x)).head
        inline def infer[X](x: X): String    = ProbeMacro.seen[X]
    end Dv
end D

object Main:
    def main(args: Array[String]): Unit =
        println("B.collapsed       " + B.collapsed)
        println("B.explicitOuter   " + B.explicitOuter)
        println("B.explicitInner   " + B.explicitInner)
        println("B.owners          " + B.ownersHere)
        println("B.sameIn1         " + B.sameIn1)
        println("B.sameIn2         " + B.sameIn2)
        println("B.sameIn3         " + B.sameIn3)
        println("Outside.same1     " + Outside.same1)
        println("Outside.same2     " + Outside.same2)
        println("Outside.same3     " + Outside.same3)
        println("Outside.same4     " + Outside.same4)
        println("Outside.same5     " + Outside.same5)
        println("Outside.same6     " + Outside.same6)
        println("C.collapsed       " + C.collapsed)
        val k = new K
        println("T.inTrait         " + k.inTrait)
        println("T.ownersInTrait   " + k.ownersInTrait)
        println("K.inClass         " + k.inClass)
        println("K.ownersInClass   " + k.ownersInClass)
        println("D.inLambda        " + D.Dv.inLambda)
        println("D.inAnon          " + D.Dv.inAnon)
        println("D.collapsedLambda " + D.Dv.collapsedInLambda)
    end main
end Main
