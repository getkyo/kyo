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
        // Identity memo (original node object -> its interned canonical). Keyed by reference, not by
        // structural TypeKey, so a rebuild reads each child in O(1) without re-deriving the key of a
        // deep subtree (the same reason IdentitySet avoids structural hashCode on nested types).
        val internedByObj = new java.util.IdentityHashMap[Tasty.Type, Tasty.Type]()

        // Reconstruct `t` from its children, applying `f` to each child type. This is the single
        // structural enumeration of Tasty.Type, used both to enumerate children (with a collecting
        // `f`) and to rebuild a node from its interned children (with `f = internOf`).
        def rebuild(t: Tasty.Type, f: Tasty.Type => Tasty.Type): Tasty.Type =
            t match
                case Tasty.Type.Named(_)        => t
                case Tasty.Type.RecThis(_)      => t
                case Tasty.Type.ParamRef(_, _)  => t
                case Tasty.Type.ConstantType(_) => t
                case Tasty.Type.ThisType(_)     => t
                case Tasty.Type.Applied(base, args) =>
                    Tasty.Type.Applied(f(base), args.map(f))
                case Tasty.Type.Function(ps, r) =>
                    Tasty.Type.Function(ps.map(f), f(r))
                case Tasty.Type.ContextFunction(ps, r) =>
                    Tasty.Type.ContextFunction(ps.map(f), f(r))
                case Tasty.Type.Tuple(elems) =>
                    Tasty.Type.Tuple(elems.map(f))
                case Tasty.Type.ByName(u) =>
                    Tasty.Type.ByName(f(u))
                case Tasty.Type.Repeated(e) =>
                    Tasty.Type.Repeated(f(e))
                case Tasty.Type.Array(e) =>
                    Tasty.Type.Array(f(e))
                case Tasty.Type.AndType(l, r) =>
                    Tasty.Type.AndType(f(l), f(r))
                case Tasty.Type.OrType(l, r) =>
                    Tasty.Type.OrType(f(l), f(r))
                case Tasty.Type.Refinement(p, n, i) =>
                    Tasty.Type.Refinement(f(p), n, f(i))
                case Tasty.Type.Rec(p) =>
                    Tasty.Type.Rec(f(p))
                case Tasty.Type.Annotated(u, annotation) =>
                    Tasty.Type.Annotated(f(u), annotation)
                case Tasty.Type.SuperType(s, m) =>
                    Tasty.Type.SuperType(f(s), f(m))
                case Tasty.Type.Wildcard(lo, hi) =>
                    Tasty.Type.Wildcard(f(lo), f(hi))
                case Tasty.Type.Skolem(u) =>
                    Tasty.Type.Skolem(f(u))
                case Tasty.Type.MatchType(b, sc, cs) =>
                    Tasty.Type.MatchType(f(b), f(sc), cs.map(f))
                case Tasty.Type.FlexibleType(u) =>
                    Tasty.Type.FlexibleType(f(u))
                case Tasty.Type.TermRef(p, n) =>
                    Tasty.Type.TermRef(f(p), n)
                case Tasty.Type.TypeLambda(paramIds, body) =>
                    Tasty.Type.TypeLambda(paramIds, f(body))
                case Tasty.Type.MatchCase(pat, rhs) =>
                    Tasty.Type.MatchCase(f(pat), f(rhs))
                case Tasty.Type.TypeRef(qual, name) =>
                    Tasty.Type.TypeRef(f(qual), name)
                case Tasty.Type.Bounds(lo, hi) =>
                    Tasty.Type.Bounds(f(lo), f(hi))
                case Tasty.Type.Nothing => t
                case Tasty.Type.Any     => t
            end match
        end rebuild

        // The interned canonical for an already-visited child. Every node is recorded in
        // `internedByObj` at its enter (the dedup-skip paths) or its exit (the processed path), and a
        // node's children are all visited before its exit rebuild, so this is an O(1) identity lookup
        // in the common case. The structural fallback runs only for the rare back-reference that closes
        // a Rec/RecThis cycle.
        def internOf(child: Tasty.Type): Tasty.Type =
            val memo = internedByObj.get(child)
            if memo != null then memo
            else
                val k = TypeKey.of(child)
                canonical.map.get(k).orElse(inProgress.get(k)).getOrElse(child)
            end if
        end internOf

        // Iterative post-order interning. An explicit work-list replaces the former call recursion so
        // that types nested up to MaxDepth do not consume O(depth) JVM call stack and can never
        // overflow it, matching TypeKey.of and IdentitySet which are stack-safe for the same reason.
        // Each node is visited twice: an enter frame applies the depth guard, dedups against
        // `canonical`/`inProgress`, and schedules its children; the following exit frame rebuilds it
        // from their now-interned results. `inProgress` carries the Rec/RecThis cycle-break placeholder
        // exactly as the recursive form did; `internedByObj` memoises every visited object so the
        // rebuild reads its children in O(1).
        def process(root: Tasty.Type): Unit =
            val work = new mutable.ArrayDeque[(Tasty.Type, Int, Boolean)]()
            work.append((root, 0, false))
            while work.nonEmpty do
                val (t, depth, isExit) = work.removeLast()
                if isExit then
                    val key     = TypeKey.of(t)
                    val rebuilt = rebuild(t, internOf)
                    canonical.map(key) = rebuilt
                    discard(inProgress.remove(key))
                    discard(internedByObj.put(t, rebuilt))
                else if depth >= TypeArena.MaxDepth then
                    throw new TypeArena.DepthExceededException(
                        s"TypeArena.internRec depth ${TypeArena.MaxDepth} exceeded; pathological nesting"
                    )
                else if internedByObj.get(t) == null then
                    val key = TypeKey.of(t)
                    canonical.map.get(key) match
                        case Some(canon) => discard(internedByObj.put(t, canon))
                        case None =>
                            inProgress.get(key) match
                                case Some(placeholder) => discard(internedByObj.put(t, placeholder))
                                case None =>
                                    inProgress(key) = t
                                    work.append((t, depth, true))
                                    discard(rebuild(t, child => { work.append((child, depth + 1, false)); child }))
                            end match
                    end match
                end if
            end while
        end process

        for (_, t) <- map do process(t)
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
