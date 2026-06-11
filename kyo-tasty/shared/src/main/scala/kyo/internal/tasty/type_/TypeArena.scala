package kyo.internal.tasty.type_

import kyo.*
import kyo.Tasty.SymbolId
import scala.collection.mutable

/** Per-thread hash-cons arena for Tasty.Type values.
  *
  * During Phase B decode, each fiber/thread holds one TypeArena and interns every constructed type into it. Structurally equal types
  * produced at different sites within the same file share the same canonical reference after interning.
  *
  * After all Phase B fibers complete, Phase C merges all per-thread arenas into a single canonical arena via TypeArena.merge. The merge
  * algorithm handles Rec/RecThis cycles without stack overflow.
  *
  * TypeArena is NOT thread-safe. It is intended to be allocated once per decode fiber.
  */
final class TypeArena:
    private val map = new mutable.HashMap[TypeKey, Tasty.Type]

    /** Intern a type: look up by structural key, insert if absent, return canonical. */
    def intern(t: Tasty.Type): Tasty.Type =
        val key = TypeKey.of(t)
        map.getOrElseUpdate(key, t)

    /** Drain all entries from this arena into `canonical`, resolving cycles.
      *
      * Called single-threaded in Phase C. After this call, every type that was in `this` arena has a corresponding canonical entry in
      * `canonical`.
      *
      * The algorithm recurses structurally into each type, uses an `inProgress` map as a cycle-break placeholder, and inserts the interned
      * result into `canonical`.
      */
    def merge(canonical: TypeArena): Unit =
        val inProgress = new mutable.HashMap[TypeKey, Tasty.Type]()

        def recurse(t: Tasty.Type, depth: Int): Tasty.Type =
            t match
                case Tasty.Type.Named(_)        => t
                case Tasty.Type.RecThis(_)      => t
                case Tasty.Type.ParamRef(_, _)  => t
                case Tasty.Type.ConstantType(_) => t
                case Tasty.Type.ThisType(_)     => t
                case Tasty.Type.Applied(base, args) =>
                    Tasty.Type.Applied(internRec(base, depth), args.map(internRec(_, depth)))
                case Tasty.Type.Function(ps, r) =>
                    Tasty.Type.Function(ps.map(internRec(_, depth)), internRec(r, depth))
                case Tasty.Type.ContextFunction(ps, r) =>
                    Tasty.Type.ContextFunction(ps.map(internRec(_, depth)), internRec(r, depth))
                case Tasty.Type.Tuple(elems) =>
                    Tasty.Type.Tuple(elems.map(internRec(_, depth)))
                case Tasty.Type.ByName(u) =>
                    Tasty.Type.ByName(internRec(u, depth))
                case Tasty.Type.Repeated(e) =>
                    Tasty.Type.Repeated(internRec(e, depth))
                case Tasty.Type.Array(e) =>
                    Tasty.Type.Array(internRec(e, depth))
                case Tasty.Type.AndType(l, r) =>
                    Tasty.Type.AndType(internRec(l, depth), internRec(r, depth))
                case Tasty.Type.OrType(l, r) =>
                    Tasty.Type.OrType(internRec(l, depth), internRec(r, depth))
                case Tasty.Type.Refinement(p, n, i) =>
                    Tasty.Type.Refinement(internRec(p, depth), n, internRec(i, depth))
                case Tasty.Type.Rec(p) =>
                    Tasty.Type.Rec(internRec(p, depth))
                case Tasty.Type.Annotated(u, annotation) =>
                    Tasty.Type.Annotated(internRec(u, depth), annotation)
                case Tasty.Type.SuperType(s, m) =>
                    Tasty.Type.SuperType(internRec(s, depth), internRec(m, depth))
                case Tasty.Type.Wildcard(lo, hi) =>
                    Tasty.Type.Wildcard(internRec(lo, depth), internRec(hi, depth))
                case Tasty.Type.Skolem(u) =>
                    Tasty.Type.Skolem(internRec(u, depth))
                case Tasty.Type.MatchType(b, sc, cs) =>
                    Tasty.Type.MatchType(internRec(b, depth), internRec(sc, depth), cs.map(internRec(_, depth)))
                case Tasty.Type.FlexibleType(u) =>
                    Tasty.Type.FlexibleType(internRec(u, depth))
                case Tasty.Type.TermRef(p, n) =>
                    Tasty.Type.TermRef(internRec(p, depth), n)
                case Tasty.Type.TypeLambda(paramIds, body) =>
                    Tasty.Type.TypeLambda(paramIds, internRec(body, depth))
                case Tasty.Type.MatchCase(pat, rhs) =>
                    Tasty.Type.MatchCase(internRec(pat, depth), internRec(rhs, depth))
                case Tasty.Type.TypeRef(qual, name) =>
                    Tasty.Type.TypeRef(internRec(qual, depth), name)
                case Tasty.Type.Bounds(lo, hi) =>
                    Tasty.Type.Bounds(internRec(lo, depth), internRec(hi, depth))
                case Tasty.Type.Nothing => t
                case Tasty.Type.Any     => t
            end match
        end recurse

        def internRec(t: Tasty.Type, depth: Int): Tasty.Type =
            if depth >= TypeArena.MaxDepth then
                throw new TypeArena.DepthExceededException(
                    s"TypeArena.internRec depth ${TypeArena.MaxDepth} exceeded; pathological nesting"
                )
            end if
            val key = TypeKey.of(t)
            canonical.map.get(key) match
                case Some(canon) => canon
                case None =>
                    inProgress.get(key) match
                        case Some(placeholder) => placeholder
                        case None =>
                            inProgress(key) = t
                            val recurInterned = recurse(t, depth + 1)
                            discard(inProgress.remove(key))
                            canonical.map(key) = recurInterned
                            recurInterned
                    end match
            end match
        end internRec

        for (_, t) <- map do internRec(t, 0)
    end merge

    /** All type values currently in this arena. */
    def values: Iterable[Tasty.Type] = map.values

end TypeArena

object TypeArena:
    /** Factory for a fresh (empty) canonical arena used in Phase C. */
    def canonical(): TypeArena = new TypeArena

    /** Maximum recursion depth for TypeArena.internRec before DepthExceededException is thrown. */
    val MaxDepth: Int = 1024

    /** Thrown when TypeArena.internRec encounters type nesting deeper than MaxDepth.
      *
      * Internal sentinel: thrown from the recursive intern loop and caught by the adjacent type-pass
      * driver, which converts it into a structured `TastyError.MalformedSection` on the `Abort[TastyError]`
      * row. Deliberately bypasses `KyoException` (no public-API crossing) and uses
      * `enableSuppression=false, writableStackTrace=false` to skip stack-trace materialisation on the throw path.
      */
    final class DepthExceededException(msg: String)
        extends RuntimeException(msg, null, false, false)
end TypeArena
