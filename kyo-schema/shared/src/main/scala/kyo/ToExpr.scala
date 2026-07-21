package kyo

/** Kyo's ToExpr typeclass, opaque wrapper around `scala.quoted.ToExpr`. See [[FromExpr]]. */
opaque type ToExpr[A] <: scala.quoted.ToExpr[A] = scala.quoted.ToExpr[A]

object ToExpr:
    /** Wrap an existing `scala.quoted.ToExpr[A]` as a `kyo.ToExpr[A]`. */
    def fromStd[A](te: scala.quoted.ToExpr[A]): ToExpr[A] = te

    inline def derived[A]: ToExpr[A] = ${ internal.ToExprMirrorMacro.derivedImpl[A] }
end ToExpr
