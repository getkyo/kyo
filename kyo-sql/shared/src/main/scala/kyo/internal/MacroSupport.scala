package kyo.internal

import scala.quoted.Expr

/** Typed helpers that centralise every FromExpr phantom-type `asInstanceOf` site.
  *
  * `Record[F]`, `Column[N, V]`, `GroupedColumn[N, V]`, `UngroupedView[V]`, and `Cast[A, B]` are all phantom in their type parameters at
  * runtime. FromExpr walkers internally produce the erased `[Any]` / `[?]` variant and then need to unify with the concrete type parameter
  * the surrounding `FromExpr[T]` instance declares. These helpers collect every such cast in one place, each paired with the same soundness
  * argument: the type parameter is phantom, so the cast is safe and unchecked.
  *
  * The Expr-typed helper (`expectExpr`) handles macro quote trees whose static type must be narrowed for a downstream API call. The value
  * is guaranteed by the surrounding pattern match (caller verifies the tree shape before calling), so the cast is sound.
  */
object MacroSupport:

    /** Casts `Expr[Any]` to `Expr[X]` by narrowing the static type.
      *
      * Safe when the surrounding TASTy pattern guarantees the underlying term has type `X`. Used wherever `FromExprDerived` returns a
      * stable-given reference typed as `Expr[Any]` that must be passed to an API expecting `Expr[X]`.
      *
      * Note: `Expr[X]` is phantom in `X` at the JVM level (erased to `Expr`), so the cast is unchecked but safe by contract.
      */
    def expectExpr[X](e: Expr[Any]): Expr[X] =
        // Safe: FromExpr phantom-type unification — the caller's pattern match guarantees the tree is Expr[X].
        e.asInstanceOf[Expr[X]]

    /** Refines `Option[T]` to `Option[X]` when `T` and `X` share the same runtime class and `X` is phantom in its type parameters.
      *
      * Used at `FromExpr[X]` boundaries where the walker produces `Option[T]` (erased) and the interface demands `Option[X]` (concrete).
      * Safe because all phantom-typed wrappers (`Column`, `GroupedColumn`, `UngroupedView`, `Cast`, `Record`) are value-equal under
      * erasure.
      */
    def narrowOption[X](opt: Option[?]): Option[X] =
        // Safe: FromExpr phantom-type unification — the phantom type parameters are erased at runtime.
        opt.asInstanceOf[Option[X]]

    /** Refines a value to `X` when `X` is phantom in its type parameters (i.e., erased to the same runtime class).
      *
      * Used where `Record[Any]` or similar must be unified with `Record[F]` at a FromExpr return boundary.
      */
    def narrowPhantom[X](v: Any): X =
        // Safe: FromExpr phantom-type unification — the phantom type parameters are erased at runtime.
        v.asInstanceOf[X]

end MacroSupport
