package kyo.kernel.proto

import scala.annotation.implicitNotFound
import scala.quoted.*
import scala.util.NotGiven

/** CanLift is the lift's evidence, and its companion carries the lift's one macro.
  *
  * The split follows what each half must do at expansion time. The lint may not re-expand: it rides the NotGiven parameter of a macro-free
  * given, so it resolves once where the conversion is written and is baked as a value, which waives an inline method with an abstract type
  * parameter and keeps it sound through the boxing emission. The emission must re-expand: it is the macro, so an inline method instantiated
  * at a concrete type still gets that type's strategy, the bare cast where a value of the type can never be a computation and the runtime
  * boxing test everywhere else.
  *
  * Invariance is load-bearing: with a covariant evidence the derivation leaves the type under-constrained and the negation becomes
  * satisfiable through Nothing, so the lint never fires.
  */
@implicitNotFound("""
Cannot lift `${A}` to a pending computation.

If the type is nested (`X < S1 < S2`), this usually comes from type inference
nesting effect computations instead of merging them: call `.flatten` to merge
the nested effects, or split the expression into smaller statements so the
effect rows unify.

If the value's effect row simply does not fit the expected row (for example a
`Unit < S1` where a `Unit < S2` is expected), consider removing or adjusting
the type constraint on the left-hand side.
More info : https://github.com/getkyo/kyo/issues/903
""")
opaque type CanLift[A] = Null

object CanLift:

    inline given derived[A](using inline ng: NotGiven[A <:< (Any < Nothing)]): CanLift[A] = null

    object unsafe:
        /** Unconditionally provides evidence; the emission's own analysis keeps the representation sound. */
        inline given bypass[A]: CanLift[A] = null
    end unsafe

    /** The lift's emission, expanded at the site the conversion lands on. */
    private[proto] inline def lift[A, S](inline v: A): A < S = ${ liftImpl[A, S]('v) }

    private def liftImpl[A: Type, S: Type](v: Expr[A])(using Quotes): Expr[A < S] =
        import quotes.reflect.*

        val tpe  = TypeRepr.of[A].dealias
        val wide = tpe.widen.dealias
        val sym  = wide.typeSymbol

        def isNothing = tpe =:= TypeRepr.of[Nothing]
        def isModule  = sym.fullName.startsWith("kyo.") && sym.flags.is(Flags.Module) && !sym.flags.is(Flags.Case)
        def isValue   = wide <:< TypeRepr.of[AnyVal] || wide <:< TypeRepr.of[String]
        // a final class admits no Boxed subtype, so a value of the type is
        // provably not a computation and the box test can never fire
        def isSafeFinalClass =
            sym.isClassDef && sym.flags.is(Flags.Final) && !sym.flags.is(Flags.Trait) &&
                !(wide <:< TypeRepr.of[Boxed])

        if isModule then
            report.errorAndAbort(s"Cannot lift '${sym.fullName}' to a '${sym.name} < S'", Position.ofMacroExpansion)
        else if isNothing || isValue || isSafeFinalClass then '{ $v.asInstanceOf[A < S] } else '{ Nested.nest[A, S]($v) }
        end if
    end liftImpl

end CanLift
