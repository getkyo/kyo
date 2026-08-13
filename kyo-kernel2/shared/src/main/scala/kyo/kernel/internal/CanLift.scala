package kyo.kernel.internal

import kyo.kernel.<
import scala.annotation.implicitNotFound
import scala.quoted.*
import scala.util.NotGiven

/** CanLift is the lift's evidence: the lint that a type may lift at all.
  *
  * The pending check is the NotGiven parameter: it fails the derivation where a concretely nested type is written, surfacing the guidance
  * below, and it resolves once at the site the conversion is written, so an inline method with an abstract type parameter passes it there
  * and bakes the evidence, waiving generic paths from the lint while the lift's runtime box keeps them sound. The macro is the rest of the
  * lint: it rejects kyo module singletons with a guided message.
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

    inline given derived[A](using inline ng: NotGiven[A <:< (Any < Nothing)]): CanLift[A] = ${ CanLift.deriveImpl[A] }

    /** The runtime arm of the lift: tests and boxes a value that may be a computation held as data. A plain method rather than inline, so
      * the conversion's expansion at any site calls through this public bridge instead of reaching for the internal box directly.
      */
    def lift[A, S](v: A): A < S = Nested.lift(v)

    object unsafe:
        /** Unconditionally provides evidence; the lift's runtime box keeps the bypass sound. */
        inline given bypass[A]: CanLift[A] = null
    end unsafe

    private[internal] def deriveImpl[A: Type](using Quotes): Expr[CanLift[A]] =
        import quotes.reflect.*

        val sym = TypeRepr.of[A].dealias.widen.dealias.typeSymbol
        if sym.fullName.startsWith("kyo.") && sym.flags.is(Flags.Module) && !sym.flags.is(Flags.Case) then
            report.errorAndAbort(s"Cannot lift '${sym.fullName}' to a '${sym.name} < S'", Position.ofMacroExpansion)
        '{ CanLift.unsafe.bypass[A] }
    end deriveImpl

end CanLift
