package kyo.kernel.internal

import kyo.<
import scala.annotation.implicitNotFound
import scala.quoted.*
import scala.util.NotGiven

/** The constraint the implicit lift carries, rejecting what should not be lifted into a computation.
  *
  * Two things are refused: a computation (lifting one into another nests it; fix with `.flatten` or by splitting the expression) and a kyo
  * module object (`Abort` where `Abort(...)` was meant would otherwise become `Abort.type < S`, hiding the missing argument list).
  *
  * It is a soft constraint: it tests whether the type being lifted is a computation. At a concrete type it can answer; inside a generic
  * function the type parameter is abstract and cannot be tested, so the lift fires and a nested computation results. Nesting therefore
  * happens exactly where this constraint cannot see what it is looking at.
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

// Two macros: the singleton check that derives a `CanLift`, and the error raised when a Unit computation
// is lifted to the wrong row.
object CanLiftMacro:
    inline def checkSingleton[A]: CanLift[A] = ${ liftImpl[A] }

    private[internal] def liftImpl[A: Type](using Quotes): Expr[CanLift[A]] =
        import quotes.reflect.*
        val tpe = TypeRepr.of[A]
        val sym = tpe.typeSymbol

        if sym.fullName.startsWith("kyo.") && sym.flags.is(Flags.Module) && !sym.flags.is(Flags.Case) then
            report.errorAndAbort(s"Cannot lift '${sym.fullName}' to a '${sym.name} < S'", Position.ofMacroExpansion)

        if tpe <:< TypeRepr.of[Any < Nothing] then
            report.errorAndAbort(s"Type '${tpe.show}' may contain a nested effect computation.", Position.ofMacroExpansion)

        '{ null.asInstanceOf[CanLift[A]] }
    end liftImpl

    def abortCastUnitImpl[S1: Type, S2: Type](v: Expr[Unit < S1])(using quotes: Quotes): Expr[Unit < S2] =
        import quotes.reflect.*
        val source = TypeRepr.of[S1].show
        report.errorAndAbort(
            s"""Cannot lift `Unit < ${source}` to the expected type (`Unit < ?`).
               |This may be due to an effect type mismatch.
               |Consider removing or adjusting the type constraint on the left-hand side.
               |More info : https://github.com/getkyo/kyo/issues/903""".stripMargin
        )
    end abortCastUnitImpl

end CanLiftMacro

object CanLift:

    // Three givens rather than one macro: only the third case needs the macro, and the first covers almost every
    // lift in a program. Keeping the macro off that path matters twice: a type test rather than a compiler
    // expansion at every lift site, and a file that summons a same-module macro is suspended to a retry run, a
    // cascade this module sits close to.

    /** The common case: anything that is neither a computation nor a singleton, settled by two `NotGiven` tests and no expansion. */
    inline given derived[A](using inline ng: NotGiven[A <:< (Any < Nothing)], inline ns: NotGiven[A <:< Singleton]): CanLift[A] = null

    /** A case object is a singleton but never a kyo module, so it is admitted without asking the macro. */
    inline given derivedCaseObject[A <: Singleton & Product](using inline ng: NotGiven[A <:< (Any < Nothing)]): CanLift[A] = null

    /** Every other singleton, where the module-object check has to run. */
    inline given derivedSingleton[A <: Singleton]: CanLift[A] = CanLiftMacro.checkSingleton[A]

    inline given CanLift[Nothing] = null
end CanLift
