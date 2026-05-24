package kyo.internal.reflect.type_

import kyo.*
import scala.collection.mutable

/** Per-thread hash-cons arena for Reflect.Type values.
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
    private val map = new mutable.HashMap[TypeKey, Reflect.Type]

    /** Intern a type: look up by structural key, insert if absent, return canonical. */
    def intern(t: Reflect.Type): Reflect.Type =
        val key = TypeKey.of(t)
        map.getOrElseUpdate(key, t)

    /** Drain all entries from this arena into `canonical`, resolving cycles.
      *
      * Called single-threaded in Phase C. After this call, every type that was in `this` arena has a corresponding canonical entry in
      * `canonical`.
      *
      * The algorithm follows the pseudocode in DESIGN.md section 9: it recurses structurally into each type, uses an `inProgress` map as a
      * cycle-break placeholder, and inserts the interned result into `canonical`.
      */
    def merge(canonical: TypeArena): Unit =
        val inProgress = new mutable.HashMap[TypeKey, Reflect.Type]()

        def recurse(t: Reflect.Type): Reflect.Type =
            t match
                case Reflect.Type.Named(_)        => t
                case Reflect.Type.RecThis(_)      => t
                case Reflect.Type.ParamRef(_, _)  => t
                case Reflect.Type.ConstantType(_) => t
                case Reflect.Type.ThisType(_)     => t
                case Reflect.Type.Applied(base, args) =>
                    Reflect.Type.Applied(internRec(base), args.map(internRec))
                case Reflect.Type.Function(ps, r, ctx) =>
                    Reflect.Type.Function(ps.map(internRec), internRec(r), ctx)
                case Reflect.Type.Tuple(elems) =>
                    Reflect.Type.Tuple(elems.map(internRec))
                case Reflect.Type.ByName(u) =>
                    Reflect.Type.ByName(internRec(u))
                case Reflect.Type.Repeated(e) =>
                    Reflect.Type.Repeated(internRec(e))
                case Reflect.Type.Array(e) =>
                    Reflect.Type.Array(internRec(e))
                case Reflect.Type.AndType(l, r) =>
                    Reflect.Type.AndType(internRec(l), internRec(r))
                case Reflect.Type.OrType(l, r) =>
                    Reflect.Type.OrType(internRec(l), internRec(r))
                case Reflect.Type.Refinement(p, n, i) =>
                    Reflect.Type.Refinement(internRec(p), n, internRec(i))
                case Reflect.Type.Rec(p) =>
                    Reflect.Type.Rec(internRec(p))
                case Reflect.Type.Annotated(u, ann) =>
                    Reflect.Type.Annotated(internRec(u), ann)
                case Reflect.Type.SuperType(s, m) =>
                    Reflect.Type.SuperType(internRec(s), internRec(m))
                case Reflect.Type.Wildcard(lo, hi) =>
                    Reflect.Type.Wildcard(internRec(lo), internRec(hi))
                case Reflect.Type.Skolem(u) =>
                    Reflect.Type.Skolem(internRec(u))
                case Reflect.Type.MatchType(b, sc, cs) =>
                    Reflect.Type.MatchType(internRec(b), internRec(sc), cs.map(internRec))
                case Reflect.Type.FlexibleType(u) =>
                    Reflect.Type.FlexibleType(internRec(u))
                case Reflect.Type.TermRef(p, n) =>
                    Reflect.Type.TermRef(internRec(p), n)
                case Reflect.Type.TypeLambda(ps, body) =>
                    Reflect.Type.TypeLambda(ps, internRec(body))
            end match
        end recurse

        def internRec(t: Reflect.Type): Reflect.Type =
            val key = TypeKey.of(t)
            canonical.map.get(key) match
                case Some(canon) => canon
                case None =>
                    inProgress.get(key) match
                        case Some(placeholder) => placeholder
                        case None =>
                            inProgress(key) = t
                            val recurInterned = recurse(t)
                            discard(inProgress.remove(key))
                            canonical.map(key) = recurInterned
                            recurInterned
                    end match
            end match
        end internRec

        for (_, t) <- map do internRec(t)
    end merge

    /** All type values currently in this arena. */
    def values: Iterable[Reflect.Type] = map.values

end TypeArena

object TypeArena:
    /** Factory for a fresh (empty) canonical arena used in Phase C. */
    def canonical(): TypeArena = new TypeArena
end TypeArena

/** Structural key for Reflect.Type values used by TypeArena's hash-cons map.
  *
  * Hash is computed once at construction from the type's structure using prime mixing. Equality is structural (not reference equality),
  * except for Named(sym) where sym identity is used because symbols are canonical after Phase 3.
  *
  * Cycle safety: a thread-local in-progress set prevents infinite hash recursion on Rec/RecThis cycles. If a type is encountered again
  * during its own hash computation, 0 is returned for that sub-term.
  */
final class TypeKey(val hash: Int, val t: Reflect.Type):
    override def hashCode(): Int = hash
    override def equals(other: Any): Boolean = other match
        case that: TypeKey => TypeKey.structuralEquals(t, that.t)
        case _             => false
end TypeKey

object TypeKey:
    private val hashingInProgress: ThreadLocal[mutable.HashSet[Reflect.Type]] =
        ThreadLocal.withInitial(() => new mutable.HashSet[Reflect.Type]())

    def of(t: Reflect.Type): TypeKey = new TypeKey(computeHash(t), t)

    private def computeHash(t: Reflect.Type): Int =
        val inProgress = hashingInProgress.get()
        if inProgress.contains(t) then return 0
        discard(inProgress.add(t))
        val h =
            try hashOf(t)
            finally discard(inProgress.remove(t))
        h
    end computeHash

    private def hashOf(t: Reflect.Type): Int =
        t match
            case Reflect.Type.Named(sym) =>
                31 * java.lang.System.identityHashCode(sym)
            case Reflect.Type.TermRef(prefix, name) =>
                31 * computeHash(prefix) + name.hashCode
            case Reflect.Type.Applied(base, args) =>
                args.foldLeft(31 * computeHash(base))((acc, a) => acc * 31 + computeHash(a))
            case Reflect.Type.TypeLambda(ps, body) =>
                31 * ps.length + computeHash(body)
            case Reflect.Type.Function(ps, r, ctx) =>
                val psHash = ps.foldLeft(1)((acc, p) => acc * 31 + computeHash(p))
                31 * psHash + computeHash(r) + (if ctx then 1 else 0)
            case Reflect.Type.Tuple(elems) =>
                elems.foldLeft(1)((acc, e) => acc * 31 + computeHash(e))
            case Reflect.Type.ByName(u) =>
                31 * computeHash(u) + 2
            case Reflect.Type.Repeated(e) =>
                31 * computeHash(e) + 3
            case Reflect.Type.Array(e) =>
                31 * computeHash(e) + 4
            case Reflect.Type.Refinement(p, n, i) =>
                31 * computeHash(p) + n.hashCode + computeHash(i)
            case Reflect.Type.Rec(p) =>
                31 * computeHash(p) + 5
            case Reflect.Type.RecThis(rec) =>
                31 * computeHash(rec) + 6
            case Reflect.Type.AndType(l, r) =>
                31 * computeHash(l) + computeHash(r) + 7
            case Reflect.Type.OrType(l, r) =>
                31 * computeHash(l) + computeHash(r) + 8
            case Reflect.Type.Annotated(u, ann) =>
                31 * computeHash(u) + ann.hashCode
            case Reflect.Type.ConstantType(c) =>
                c.hashCode
            case Reflect.Type.ThisType(sym) =>
                31 * java.lang.System.identityHashCode(sym) + 9
            case Reflect.Type.SuperType(s, m) =>
                31 * computeHash(s) + computeHash(m) + 10
            case Reflect.Type.ParamRef(b, i) =>
                31 * java.lang.System.identityHashCode(b) + i
            case Reflect.Type.Wildcard(lo, hi) =>
                31 * computeHash(lo) + computeHash(hi) + 11
            case Reflect.Type.Skolem(u) =>
                31 * computeHash(u) + 12
            case Reflect.Type.MatchType(b, sc, cs) =>
                val csHash = cs.foldLeft(0)((acc, c) => acc + computeHash(c))
                31 * computeHash(b) + computeHash(sc) + csHash
            case Reflect.Type.FlexibleType(u) =>
                31 * computeHash(u) + 13

    def structuralEquals(a: Reflect.Type, b: Reflect.Type): Boolean =
        (a, b) match
            case (Reflect.Type.Named(s1), Reflect.Type.Named(s2)) =>
                s1 eq s2
            case (Reflect.Type.TermRef(p1, n1), Reflect.Type.TermRef(p2, n2)) =>
                structuralEquals(p1, p2) && n1 == n2
            case (Reflect.Type.Applied(b1, a1), Reflect.Type.Applied(b2, a2)) =>
                structuralEquals(b1, b2) && a1.length == a2.length &&
                a1.zip(a2).forall((x, y) => structuralEquals(x, y))
            case (Reflect.Type.TypeLambda(ps1, body1), Reflect.Type.TypeLambda(ps2, body2)) =>
                ps1.length == ps2.length && structuralEquals(body1, body2)
            case (Reflect.Type.Function(ps1, r1, ctx1), Reflect.Type.Function(ps2, r2, ctx2)) =>
                ctx1 == ctx2 && ps1.length == ps2.length &&
                ps1.zip(ps2).forall((x, y) => structuralEquals(x, y)) &&
                structuralEquals(r1, r2)
            case (Reflect.Type.Tuple(e1), Reflect.Type.Tuple(e2)) =>
                e1.length == e2.length && e1.zip(e2).forall((x, y) => structuralEquals(x, y))
            case (Reflect.Type.ByName(u1), Reflect.Type.ByName(u2)) =>
                structuralEquals(u1, u2)
            case (Reflect.Type.Repeated(e1), Reflect.Type.Repeated(e2)) =>
                structuralEquals(e1, e2)
            case (Reflect.Type.Array(e1), Reflect.Type.Array(e2)) =>
                structuralEquals(e1, e2)
            case (Reflect.Type.Refinement(p1, n1, i1), Reflect.Type.Refinement(p2, n2, i2)) =>
                structuralEquals(p1, p2) && n1 == n2 && structuralEquals(i1, i2)
            case (Reflect.Type.Rec(p1), Reflect.Type.Rec(p2)) =>
                structuralEquals(p1, p2)
            case (Reflect.Type.RecThis(rec1), Reflect.Type.RecThis(rec2)) =>
                structuralEquals(rec1, rec2)
            case (Reflect.Type.AndType(l1, r1), Reflect.Type.AndType(l2, r2)) =>
                structuralEquals(l1, l2) && structuralEquals(r1, r2)
            case (Reflect.Type.OrType(l1, r1), Reflect.Type.OrType(l2, r2)) =>
                structuralEquals(l1, l2) && structuralEquals(r1, r2)
            case (Reflect.Type.Annotated(u1, ann1), Reflect.Type.Annotated(u2, ann2)) =>
                structuralEquals(u1, u2) && ann1.equals(ann2)
            case (Reflect.Type.ConstantType(c1), Reflect.Type.ConstantType(c2)) =>
                c1.equals(c2)
            case (Reflect.Type.ThisType(s1), Reflect.Type.ThisType(s2)) =>
                s1 eq s2
            case (Reflect.Type.SuperType(s1, m1), Reflect.Type.SuperType(s2, m2)) =>
                structuralEquals(s1, s2) && structuralEquals(m1, m2)
            case (Reflect.Type.ParamRef(b1, i1), Reflect.Type.ParamRef(b2, i2)) =>
                (b1 eq b2) && i1 == i2
            case (Reflect.Type.Wildcard(lo1, hi1), Reflect.Type.Wildcard(lo2, hi2)) =>
                structuralEquals(lo1, lo2) && structuralEquals(hi1, hi2)
            case (Reflect.Type.Skolem(u1), Reflect.Type.Skolem(u2)) =>
                structuralEquals(u1, u2)
            case (Reflect.Type.MatchType(b1, sc1, cs1), Reflect.Type.MatchType(b2, sc2, cs2)) =>
                structuralEquals(b1, b2) && structuralEquals(sc1, sc2) &&
                cs1.length == cs2.length && cs1.zip(cs2).forall((x, y) => structuralEquals(x, y))
            case (Reflect.Type.FlexibleType(u1), Reflect.Type.FlexibleType(u2)) =>
                structuralEquals(u1, u2)
            case _ =>
                false

end TypeKey
