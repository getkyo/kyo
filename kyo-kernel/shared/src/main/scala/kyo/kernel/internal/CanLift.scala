package kyo.kernel.internal

import kyo.kernel.<
import scala.annotation.implicitNotFound
import scala.quoted.*
import scala.util.NotGiven

/** CanLift is a "soft" constraint that indicates a type should not contain nested effect computations (A < S), or A is not a module from
  * kyo (like Abort.type).
  *
  * This constraint helps:
  *   - prevent accidental nesting of effects that would require flattening, but cannot be strictly enforced in all generic contexts,
  *   - prevent calling combinators from (A < S) on modules, like Abort.foldAbort.
  *
  * @tparam A
  *   The type to check for nested effects
  */
@implicitNotFound("""
Type '${A}' may contain a nested effect computation.
This usually means you have a value of type `X < S1 < S2` (i.e. `(X < S1) < S2`) where a plain value `X < S` is expected.

This often happens due to *type inference*: some effect computations are nested when chaining operations, and Scala infers a value with nested effects instead of merging them.

To fix this, you can:

1. Call `.flatten` to merge the nested effects:
    val x: (Int < S1) < S2 = ...
    val y: Int < (S1 & S2) = x.flatten

   This collapses the nested effect layers into a single computation with a combined effect set.

2. Split the computation into multiple statements:
   Breaking the code into smaller expressions helps Scala infer the correct types incrementally, avoiding nested effects.
    val x: Int < S1 = computeFirst()
    val y: Int < S2 = useResult(x)

""")
opaque type CanLift[A] = Null

// Diverges from main: main derives every instance through one splice macro (LiftMacro.liftMacro)
// and exposes `CanLift.unsafe.bypass`. Here the common case is a plain given guarded by NotGiven,
// singletons go through the macro check (the module and nested-effect rejections), and there is
// no bypass.
object CanLift:

    inline given derived[A](using inline ng: NotGiven[A <:< (Any < Nothing)], inline ns: NotGiven[A <:< Singleton]): CanLift[A] = null

    inline given derivedCaseObject[A <: Singleton & Product](using inline ng: NotGiven[A <:< (Any < Nothing)]): CanLift[A] = null

    inline given derivedSingleton[A <: Singleton]: CanLift[A] = CanLiftMacro.checkSingleton[A]

    inline given nothing: CanLift[Nothing] = null

end CanLift

private[kernel] object CanLiftMacro:

    inline def checkSingleton[A]: CanLift[A] = ${ checkImpl[A] }

    private[kernel] def checkImpl[A: Type](using Quotes): Expr[CanLift[A]] =
        import quotes.reflect.*
        val tpe = TypeRepr.of[A]
        val sym = tpe.typeSymbol

        if sym.fullName.startsWith("kyo.") && sym.flags.is(Flags.Module) && !sym.flags.is(Flags.Case) then
            report.errorAndAbort(s"Cannot lift '${sym.fullName}' to a '${sym.name} < S'", Position.ofMacroExpansion)

        if tpe <:< TypeRepr.of[Any < Nothing] then
            report.errorAndAbort(s"Type '${tpe.show}' may contain a nested effect computation.", Position.ofMacroExpansion)

        '{ null.asInstanceOf[CanLift[A]] }
    end checkImpl

end CanLiftMacro

// Diverges from main: main's LiftMacro (its own file) is the lift itself; here the lift is the
// plain implicit in Implicits and this macro only produces the issue-903 guidance when a Unit
// computation with the wrong row is lifted.
private[kernel] object LiftMacro:

    def abortCastUnitMacro[S1: Type, S2: Type](v: Expr[Unit < S1])(using Quotes): Expr[Unit < S2] =
        import quotes.reflect.*
        val source = TypeRepr.of[S1].show
        report.errorAndAbort(
            s"""Cannot lift `Unit < ${source}` to the expected type (`Unit < ?`).
               |This may be due to an effect type mismatch.
               |Consider removing or adjusting the type constraint on the left-hand side.
               |More info : https://github.com/getkyo/kyo/issues/903""".stripMargin
        )
    end abortCastUnitMacro

end LiftMacro
