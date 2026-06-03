package kyo.internal.tasty.type_

import kyo.*

/** Structural key for Tasty.Type values used by TypeArena's hash-cons map.
  *
  * Hash is computed once at construction from the type's structure using prime mixing. Equality is structural (not reference equality),
  * except for Named(sym) where sym identity is used because symbols are canonical after Phase 3.
  *
  * Cycle safety: a thread-local in-progress set prevents infinite hash recursion on Rec/RecThis cycles. If a type is encountered again
  * during its own hash computation, 0 is returned for that sub-term.
  */
final class TypeKey(val hash: Int, val t: Tasty.Type):
    override def hashCode(): Int = hash
    override def equals(other: Any): Boolean = other match
        case that: TypeKey => TypeKey.structuralEquals(t, that.t)
        case _             => false
end TypeKey

object TypeKey:

    def of(t: Tasty.Type): TypeKey = new TypeKey(computeHash(t), t)

    private def computeHash(t: Tasty.Type): Int =
        val inProgress = PlatformHashingState.get()
        if inProgress.contains(t) then return 0
        discard(inProgress.add(t))
        val h =
            try hashOf(t)
            finally discard(inProgress.remove(t))
        h
    end computeHash

    private def hashOf(t: Tasty.Type): Int =
        t match
            case Tasty.Type.Named(id) =>
                31 * id.value
            case Tasty.Type.TermRef(prefix, name) =>
                31 * computeHash(prefix) + name.hashCode
            case Tasty.Type.Applied(base, args) =>
                args.foldLeft(31 * computeHash(base))((acc, a) => acc * 31 + computeHash(a))
            case Tasty.Type.TypeLambda(ps, body) =>
                31 * ps.length + computeHash(body)
            case Tasty.Type.Function(ps, r, ctx) =>
                val psHash = ps.foldLeft(1)((acc, p) => acc * 31 + computeHash(p))
                31 * psHash + computeHash(r) + (if ctx then 1 else 0)
            case Tasty.Type.ContextFunction(ps, r) =>
                val psHash = ps.foldLeft(1)((acc, p) => acc * 31 + computeHash(p))
                // Offset by 17 to avoid colliding with Function hash (which uses 0 or 1)
                31 * psHash + computeHash(r) + 17
            case Tasty.Type.Tuple(elems) =>
                elems.foldLeft(1)((acc, e) => acc * 31 + computeHash(e))
            case Tasty.Type.ByName(u) =>
                31 * computeHash(u) + 2
            case Tasty.Type.Repeated(e) =>
                31 * computeHash(e) + 3
            case Tasty.Type.Array(e) =>
                31 * computeHash(e) + 4
            case Tasty.Type.Refinement(p, n, i) =>
                31 * computeHash(p) + n.hashCode + computeHash(i)
            case Tasty.Type.Rec(p) =>
                31 * computeHash(p) + 5
            case Tasty.Type.RecThis(rec) =>
                31 * computeHash(rec) + 6
            case Tasty.Type.AndType(l, r) =>
                31 * computeHash(l) + computeHash(r) + 7
            case Tasty.Type.OrType(l, r) =>
                31 * computeHash(l) + computeHash(r) + 8
            case Tasty.Type.Annotated(u, ann) =>
                31 * computeHash(u) + ann.hashCode
            case Tasty.Type.ConstantType(c) =>
                c.hashCode
            case Tasty.Type.ThisType(id) =>
                31 * id.value + 9
            case Tasty.Type.SuperType(s, m) =>
                31 * computeHash(s) + computeHash(m) + 10
            case Tasty.Type.ParamRef(b, i) =>
                31 * b.value + i
            case Tasty.Type.Wildcard(lo, hi) =>
                31 * computeHash(lo) + computeHash(hi) + 11
            case Tasty.Type.Skolem(u) =>
                31 * computeHash(u) + 12
            case Tasty.Type.MatchType(b, sc, cs) =>
                val csHash = cs.foldLeft(0)((acc, c) => acc + computeHash(c))
                31 * computeHash(b) + computeHash(sc) + csHash
            case Tasty.Type.FlexibleType(u) =>
                31 * computeHash(u) + 13
            case Tasty.Type.MatchCase(pat, rhs) =>
                31 * computeHash(pat) + computeHash(rhs) + 14
            case Tasty.Type.TypeRef(qual, name) =>
                31 * computeHash(qual) + name.hashCode + 15
            case Tasty.Type.Bounds(lo, hi) =>
                31 * computeHash(lo) + computeHash(hi) + 16
            case Tasty.Type.Nothing => 18
            case Tasty.Type.Any     => 19
            case Tasty.Type.Unknown => 20

    def structuralEquals(a: Tasty.Type, b: Tasty.Type): Boolean =
        (a, b) match
            case (Tasty.Type.Named(s1), Tasty.Type.Named(s2)) =>
                s1 == s2
            case (Tasty.Type.TermRef(p1, n1), Tasty.Type.TermRef(p2, n2)) =>
                structuralEquals(p1, p2) && n1 == n2
            case (Tasty.Type.Applied(b1, a1), Tasty.Type.Applied(b2, a2)) =>
                structuralEquals(b1, b2) && a1.length == a2.length &&
                a1.zip(a2).forall((x, y) => structuralEquals(x, y))
            case (Tasty.Type.TypeLambda(ps1, body1), Tasty.Type.TypeLambda(ps2, body2)) =>
                ps1.length == ps2.length && structuralEquals(body1, body2)
            case (Tasty.Type.Function(ps1, r1, ctx1), Tasty.Type.Function(ps2, r2, ctx2)) =>
                ctx1 == ctx2 && ps1.length == ps2.length &&
                ps1.zip(ps2).forall((x, y) => structuralEquals(x, y)) &&
                structuralEquals(r1, r2)
            case (Tasty.Type.ContextFunction(ps1, r1), Tasty.Type.ContextFunction(ps2, r2)) =>
                ps1.length == ps2.length &&
                ps1.zip(ps2).forall((x, y) => structuralEquals(x, y)) &&
                structuralEquals(r1, r2)
            case (Tasty.Type.Tuple(e1), Tasty.Type.Tuple(e2)) =>
                e1.length == e2.length && e1.zip(e2).forall((x, y) => structuralEquals(x, y))
            case (Tasty.Type.ByName(u1), Tasty.Type.ByName(u2)) =>
                structuralEquals(u1, u2)
            case (Tasty.Type.Repeated(e1), Tasty.Type.Repeated(e2)) =>
                structuralEquals(e1, e2)
            case (Tasty.Type.Array(e1), Tasty.Type.Array(e2)) =>
                structuralEquals(e1, e2)
            case (Tasty.Type.Refinement(p1, n1, i1), Tasty.Type.Refinement(p2, n2, i2)) =>
                structuralEquals(p1, p2) && n1 == n2 && structuralEquals(i1, i2)
            case (Tasty.Type.Rec(p1), Tasty.Type.Rec(p2)) =>
                structuralEquals(p1, p2)
            case (Tasty.Type.RecThis(rec1), Tasty.Type.RecThis(rec2)) =>
                structuralEquals(rec1, rec2)
            case (Tasty.Type.AndType(l1, r1), Tasty.Type.AndType(l2, r2)) =>
                structuralEquals(l1, l2) && structuralEquals(r1, r2)
            case (Tasty.Type.OrType(l1, r1), Tasty.Type.OrType(l2, r2)) =>
                structuralEquals(l1, l2) && structuralEquals(r1, r2)
            case (Tasty.Type.Annotated(u1, ann1), Tasty.Type.Annotated(u2, ann2)) =>
                structuralEquals(u1, u2) && ann1.equals(ann2)
            case (Tasty.Type.ConstantType(c1), Tasty.Type.ConstantType(c2)) =>
                c1.equals(c2)
            case (Tasty.Type.ThisType(s1), Tasty.Type.ThisType(s2)) =>
                s1 == s2
            case (Tasty.Type.SuperType(s1, m1), Tasty.Type.SuperType(s2, m2)) =>
                structuralEquals(s1, s2) && structuralEquals(m1, m2)
            case (Tasty.Type.ParamRef(b1, i1), Tasty.Type.ParamRef(b2, i2)) =>
                b1 == b2 && i1 == i2
            case (Tasty.Type.Wildcard(lo1, hi1), Tasty.Type.Wildcard(lo2, hi2)) =>
                structuralEquals(lo1, lo2) && structuralEquals(hi1, hi2)
            case (Tasty.Type.Skolem(u1), Tasty.Type.Skolem(u2)) =>
                structuralEquals(u1, u2)
            case (Tasty.Type.MatchType(b1, sc1, cs1), Tasty.Type.MatchType(b2, sc2, cs2)) =>
                structuralEquals(b1, b2) && structuralEquals(sc1, sc2) &&
                cs1.length == cs2.length && cs1.zip(cs2).forall((x, y) => structuralEquals(x, y))
            case (Tasty.Type.FlexibleType(u1), Tasty.Type.FlexibleType(u2)) =>
                structuralEquals(u1, u2)
            case (Tasty.Type.MatchCase(p1, r1), Tasty.Type.MatchCase(p2, r2)) =>
                structuralEquals(p1, p2) && structuralEquals(r1, r2)
            case (Tasty.Type.TypeRef(q1, n1), Tasty.Type.TypeRef(q2, n2)) =>
                structuralEquals(q1, q2) && n1 == n2
            case (Tasty.Type.Bounds(lo1, hi1), Tasty.Type.Bounds(lo2, hi2)) =>
                structuralEquals(lo1, lo2) && structuralEquals(hi1, hi2)
            case _ =>
                false

end TypeKey
