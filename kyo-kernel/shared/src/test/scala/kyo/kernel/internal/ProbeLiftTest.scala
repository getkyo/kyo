package kyo.kernel.internal

import kyo.*
import scala.compiletime.testing.typeCheckErrors

object ProbeOpaque:
    sealed trait Eff
    opaque type Info[+A, -S] >: (A < (Eff & S)) = A < (Eff & S)
    opaque type Plain[A]                        = A < Any
    val stable: Info[Int, Eff] = (??? : Int < Eff)
end ProbeOpaque

class ProbeLiftTest extends Test:

    inline def show(inline code: String): Unit =
        val errs = typeCheckErrors(code).map(_.message.linesIterator.filter(_.nonEmpty).take(2).mkString(" / "))
        println(s"PROBE [$code] => " + (if errs.isEmpty then "COMPILES" else errs.mkString(" || ")))

    "probe" in {
        import ProbeOpaque.*
        show("implicitly[CanLift[Info[Int, Eff]]]")
        show("implicitly[CanLift[Any]]")
        show("implicitly[CanLift[String < Env[Int]]]")
        show("""def f(x: Info[Int, Eff]): Info[Any, Any] = x""")
        show("""val y: Info[Any, Any] = ProbeOpaque.stable""")
        show("""val y: Info[Any, Any] = stable""")
        show("""def f(x: Info[Int, Eff]) = { val z = x; val y: Info[Any, Any] = z; y }""")
        show("""implicitly[CanLift[ProbeOpaque.stable.type]]""")
        show("""val x: Any = 1; val y: Any < Eff = x""")
        show("""implicitly[CanLift[Plain[Int]]]""")
        show("""implicitly[CanLift[AnyRef]]""")
        show("""implicitly[CanLift[Matchable]]""")
        show("""def f[A] = implicitly[CanLift[A]]""")
        show("""def f[A >: Int] = implicitly[CanLift[A]]""")
        show("""def f[A >: (Int < Any)] = implicitly[CanLift[A]]""")
        show("""implicitly[CanLift[Maybe[Int < Eff]]]""")
        show("""implicitly[CanLift[Any | Int]]""")
        show("""implicitly[CanLift[Any & Int]]""")
        show("""val y: ProbeOpaque.stable.type < Any = ProbeOpaque.stable""")
        show("""val y = (??? : Int < Any).map(_ => ProbeOpaque.stable)""")
        show("""val y: Info[Any, Any] < Any = (??? : Int < Any).map(_ => ProbeOpaque.stable)""")
        show("""val y: Info[Int, Eff] < Any = (??? : Int < Any).map(_ => ProbeOpaque.stable)""")
        show("""val y: Info[Int, Eff] < Any = ProbeOpaque.stable""")
        show("""val y = (??? : Int < Any).map(_ => (??? : Info[Int, Eff]))""")
        show("""object Plain; val y = (??? : Int < Any).map(_ => Plain)""")
        show("""val y = (??? : Int < Any).map(_ => 1)""")
        show("""val y = (??? : Int < Any).map(_ => Maybe.Absent)""")
        show("""val y = (??? : Int < Any).map(_ => ProbeOpaque)""")
        succeed
    }
end ProbeLiftTest
