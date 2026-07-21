package kyo

/** Kyo's FromExpr typeclass, a distinct typeclass (opaque around `scala.quoted.FromExpr`) so the derivation macro can nest summons
  * unambiguously: `Expr.summon[kyo.FromExpr[T]]` triggers user-provided kyo instances or recursive derivation, never the stdlib's
  * primitive `FromExpr` accidentally.
  *
  * The `<: scala.quoted.FromExpr[A]` bound makes a `kyo.FromExpr[A]` usable wherever `scala.quoted.FromExpr[A]` is expected (stdlib code,
  * macro pattern matches on `Expr(...)`), so interop is transparent.
  */
opaque type FromExpr[A] <: scala.quoted.FromExpr[A] = scala.quoted.FromExpr[A]

object FromExpr:
    /** Wrap an existing `scala.quoted.FromExpr[A]` (e.g. stdlib's `IntFromExpr`) as a `kyo.FromExpr[A]`. */
    def fromStd[A](fe: scala.quoted.FromExpr[A]): FromExpr[A] = fe

    inline def derived[A]: FromExpr[A]       = ${ internal.FromExprDerived.derivedImpl[A] }
    inline def derivedMirror[A]: FromExpr[A] = ${ internal.FromExprMirrorMacro.derivedImpl[A] }
end FromExpr
