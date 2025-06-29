package kyo.kernel.internal

import kyo.<
import scala.annotation.implicitNotFound
import scala.quoted.*
import scala.util.NotGiven

/** CanLift is a "soft" constraint that indicates a type should not contain nested effect computations (A < S), or A is not a module from
  * kyo (like Abort.type).
  *
  * This constraint helps:
  *  - prevent accidental nesting of effects that would require flattening, but cannot be strictly enforced in all generic contexts,
  *  - prevent calling combinators from (A < S) on modules, like Abort.foldAbort.
  *
  * @tparam A
  *   The type to check for nested effects
  */
@implicitNotFound("""
Type '${A}' may contain a nested effect computation.

This usually means you have a value of type 'X < S' where you need a plain value.
To fix this, you can:

1. Call .flatten to combine the nested effects:
     (x: (Int < S1) < S2).flatten  // Result: Int < (S1 & S2)

2. Split the computation into multiple statements:
     val x: Int < S1 = computeFirst()
     val y: Int < S2 = useResult(x)
""")
opaque type CanLift[A] = Null

object CanLiftMacro:
    inline given derived[A](using inline ng: NotGiven[A <:< (Any < Nothing)]): CanLift[A] = ${ liftImpl[A] }

    private[internal] def liftImpl[A: Type](using Quotes): Expr[CanLift[A]] =
        import quotes.reflect.*
        val tpe = TypeRepr.of[A].dealias

        val sym = tpe.typeSymbol
        if sym.fullName.startsWith("kyo.") && sym.flags.is(Flags.Module) && !sym.flags.is(Flags.Case) then
            report.error(s"Cannot lift '${sym.fullName}' to a '${sym.name} < S'", Position.ofMacroExpansion)
            return '{ ??? }

        // Passed checks
        '{ CanLift.unsafe.bypass.asInstanceOf[CanLift[A]] }
    end liftImpl
end CanLiftMacro

object CanLift:

    export CanLiftMacro.derived

    inline given CanLift[Nothing] = CanLift.unsafe.bypass

    object unsafe:
        /** Unconditionally provides CanLift evidence for any type.
          *
          * Warning: This bypasses normal type safety checks and should only be used when you can guarantee through other means that no
          * problematic effect nesting will occur.
          */
        inline given bypass[A]: CanLift[A] = null
    end unsafe
end CanLift
