package kyo.internal.tasty.type_

import kyo.*
import scala.collection.mutable

/** Structural key for Tasty.Type values used by TypeArena's hash-cons map.
  *
  * Hash is computed once at construction from the type's structure using prime mixing. Equality is structural (not reference equality),
  * except for Named(sym) where sym identity is used because symbols are canonical after finalizeMerge.
  *
  * Cycle safety: a thread-local in-progress set prevents infinite hash recursion on Rec/RecThis cycles. If a type is encountered again
  * during its own hash computation, 0 is returned for that sub-term.
  *
  * Stack safety: both computeHash and structuralEquals use an iterative work-list instead of direct recursion. This prevents
  * StackOverflowError on deeply nested types (e.g. Applied chains at MaxDepth-1 levels or Rec nesting at MaxDepth-1) regardless of
  * scoverage instrumentation overhead.
  */
final class TypeKey(val hash: Int, val t: Tasty.Type):
    override def hashCode(): Int = hash
    override def equals(other: Any): Boolean = other match
        case that: TypeKey => TypeKey.structuralEquals(t, that.t)
        case _             => false
end TypeKey

object TypeKey:

    def of(t: Tasty.Type): TypeKey = new TypeKey(computeHash(t), t)

    // ---------------------------------------------------------------------------
    // Iterative hash computation
    // ---------------------------------------------------------------------------
    //
    // The work stack (LIFO via removeLast/append on an ArrayDeque) contains either
    // Tasty.Type nodes (to be hashed) or CombineNode instances (to combine
    // accumulated results). Processing a Tasty.Type pushes exactly one Int onto
    // the result stack. Processing a CombineNode pops 0..N Ints and pushes exactly
    // one (or zero for EndInProg).
    //
    // Ordering convention for pushHashWork: items pushed *last* (via append) are
    // processed *first* (LIFO). For a binary operation "31 * hash(a) + hash(b) + offset":
    //   work.append(new Combine2(offset))  // processed last
    //   work.append(b)                     // processed second
    //   work.append(a)                     // processed first
    //   -> results after evaluating a and b: [hash(a), hash(b)]
    //   -> Combine2 pops b (top/last), then a: result = 31*hash(a) + hash(b) + offset

    sealed abstract private class CombineNode:
        def apply(results: mutable.ArrayDeque[Int]): Unit

    // Push a constant hash without consuming any result.
    final private class InjectHash(h: Int) extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit = r.append(h)

    // result = 31 * r0 + offset  (pops 1, r0 = top)
    final private class Combine1(offset: Int) extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit =
            val r0 = r.removeLast()
            r.append(31 * r0 + offset)
    end Combine1

    // Add a constant to the top result.  result = r0 + c  (pops 1)
    final private class AddConst(c: Int) extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit =
            val r0 = r.removeLast()
            r.append(r0 + c)
    end AddConst

    // result = 31 * r0 + r1 + offset  (pops 2; r0 = deeper/older, r1 = top/newer)
    final private class Combine2(offset: Int) extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit =
            val r1 = r.removeLast()
            val r0 = r.removeLast()
            r.append(31 * r0 + r1 + offset)
        end apply
    end Combine2

    // result = 31 * r0 + r1 + r2 + offset  (pops 3; r0 = deepest, r2 = top)
    final private class Combine3(offset: Int) extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit =
            val r2 = r.removeLast()
            val r1 = r.removeLast()
            val r0 = r.removeLast()
            r.append(31 * r0 + r1 + r2 + offset)
        end apply
    end Combine3

    // Fold step: new_acc = acc * 31 + arg  (pops 2; acc = deeper, arg = top)
    private object FoldArg extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit =
            val arg = r.removeLast()
            val acc = r.removeLast()
            r.append(acc * 31 + arg)
        end apply
    end FoldArg

    // Multiply-by-31: result = r0 * 31  (pops 1). Used to seed an Applied fold.
    private object SeedFold extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit =
            val r0 = r.removeLast()
            r.append(r0 * 31)
    end SeedFold

    // Sum step: new_acc = acc + arg  (pops 2). Used for MatchType case accumulation.
    private object AddTop extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit =
            val arg = r.removeLast()
            val acc = r.removeLast()
            r.append(acc + arg)
        end apply
    end AddTop

    // Remove a type from the in-progress set after its sub-tree is fully hashed.
    // Pops nothing, pushes nothing.
    final private class EndInProg(t: Tasty.Type, inProgress: IdentitySet[Tasty.Type])
        extends CombineNode:
        def apply(r: mutable.ArrayDeque[Int]): Unit = discard(inProgress.remove(t))

    /** Compute a structural hash for the given type without JVM recursion.
      *
      * The work stack (LIFO) holds either Tasty.Type nodes or CombineNode instances. Evaluating a type node marks it in-progress, pushes
      * an EndInProg sentinel, then pushes the sub-evaluation work items (via pushHashWork). CombineNode instances fold accumulated
      * results. Cycle safety is preserved by the thread-local in-progress set; any type encountered again during its own traversal
      * contributes 0.
      */
    def computeHash(t: Tasty.Type): Int =
        val inProgress = PlatformHashingState.get()
        val work       = new mutable.ArrayDeque[AnyRef](64)
        val results    = new mutable.ArrayDeque[Int](64)

        work.append(t)

        while work.nonEmpty do
            work.removeLast() match
                case cn: CombineNode =>
                    cn(results)
                case typ: Tasty.Type =>
                    if inProgress.contains(typ) then
                        // Cycle: contribute 0. Do NOT add EndInProg (not added to inProgress).
                        results.append(0)
                    else
                        discard(inProgress.add(typ))
                        // EndInProg sits below the hash work (pushed first = processed last).
                        work.append(new EndInProg(typ, inProgress))
                        pushHashWork(typ, work)
        end while

        if results.nonEmpty then results.removeLast() else 0
    end computeHash

    /** Push work items that together produce exactly one hash result for the given type.
      *
      * Items pushed last (via append) are processed first (LIFO). The combined result of all pushed items leaves exactly one Int on the
      * result stack.
      */
    private def pushHashWork(t: Tasty.Type, work: mutable.ArrayDeque[AnyRef]): Unit =
        t match
            case Tasty.Type.Named(id) =>
                // Leaf: inject hash directly.
                work.append(new InjectHash(31 * id.value))

            case Tasty.Type.TermRef(prefix, name) =>
                // 31 * hash(prefix) + name.hashCode
                work.append(new Combine1(name.hashCode))
                work.append(prefix)

            case Tasty.Type.Applied(base, args) =>
                // args.foldLeft(31 * hash(base))((acc, a) => acc * 31 + hash(a))
                // Processing order (last appended = first processed):
                //   base -> SeedFold -> args(0) -> FoldArg -> args(1) -> FoldArg -> ...
                var i = args.length - 1
                while i >= 0 do
                    work.append(FoldArg)
                    work.append(args(i))
                    i -= 1
                end while
                work.append(SeedFold)
                work.append(base)

            case Tasty.Type.TypeLambda(ps, body) =>
                // 31 * ps.length + hash(body)  =  hash(body) + 31 * ps.length
                work.append(new AddConst(31 * ps.length))
                work.append(body)

            case Tasty.Type.Function(ps, r) =>
                // psHash = ps.foldLeft(1)((acc, p) => acc * 31 + hash(p))
                // result  = 31 * psHash + hash(r) + 7
                // Constant 7 is distinct from the ContextFunction constant (17) to keep
                // Function and ContextFunction hashes distinguishable after isContext removal.
                work.append(new Combine2(7))
                work.append(r)
                var i = ps.length - 1
                while i >= 0 do
                    work.append(FoldArg)
                    work.append(ps(i))
                    i -= 1
                end while
                work.append(new InjectHash(1))

            case Tasty.Type.ContextFunction(ps, r) =>
                // psHash = ps.foldLeft(1)((acc, p) => acc * 31 + hash(p))
                // result  = 31 * psHash + hash(r) + 17
                work.append(new Combine2(17))
                work.append(r)
                var i = ps.length - 1
                while i >= 0 do
                    work.append(FoldArg)
                    work.append(ps(i))
                    i -= 1
                end while
                work.append(new InjectHash(1))

            case Tasty.Type.Tuple(elems) =>
                // elems.foldLeft(1)((acc, e) => acc * 31 + hash(e))
                var i = elems.length - 1
                while i >= 0 do
                    work.append(FoldArg)
                    work.append(elems(i))
                    i -= 1
                end while
                work.append(new InjectHash(1))

            case Tasty.Type.ByName(u) =>
                // 31 * hash(u) + 2
                work.append(new Combine1(2))
                work.append(u)

            case Tasty.Type.Repeated(e) =>
                // 31 * hash(e) + 3
                work.append(new Combine1(3))
                work.append(e)

            case Tasty.Type.Array(e) =>
                // 31 * hash(e) + 4
                work.append(new Combine1(4))
                work.append(e)

            case Tasty.Type.Refinement(p, n, i) =>
                // 31 * hash(p) + n.hashCode + hash(i)
                work.append(new Combine2(n.hashCode))
                work.append(i)
                work.append(p)

            case Tasty.Type.Rec(p) =>
                // 31 * hash(p) + 5
                work.append(new Combine1(5))
                work.append(p)

            case Tasty.Type.RecThis(rec) =>
                // 31 * hash(rec) + 6
                work.append(new Combine1(6))
                work.append(rec)

            case Tasty.Type.AndType(l, r) =>
                // 31 * hash(l) + hash(r) + 7
                work.append(new Combine2(7))
                work.append(r)
                work.append(l)

            case Tasty.Type.OrType(l, r) =>
                // 31 * hash(l) + hash(r) + 8
                work.append(new Combine2(8))
                work.append(r)
                work.append(l)

            case Tasty.Type.Annotated(u, ann) =>
                // 31 * hash(u) + ann.hashCode
                work.append(new Combine1(ann.hashCode))
                work.append(u)

            case Tasty.Type.ConstantType(c) =>
                work.append(new InjectHash(c.hashCode))

            case Tasty.Type.ThisType(id) =>
                work.append(new InjectHash(31 * id.value + 9))

            case Tasty.Type.SuperType(s, m) =>
                // 31 * hash(s) + hash(m) + 10
                work.append(new Combine2(10))
                work.append(m)
                work.append(s)

            case Tasty.Type.ParamRef(b, i) =>
                work.append(new InjectHash(31 * b.value + i))

            case Tasty.Type.Wildcard(lo, hi) =>
                // 31 * hash(lo) + hash(hi) + 11
                work.append(new Combine2(11))
                work.append(hi)
                work.append(lo)

            case Tasty.Type.Skolem(u) =>
                // 31 * hash(u) + 12
                work.append(new Combine1(12))
                work.append(u)

            case Tasty.Type.MatchType(b, sc, cs) =>
                // 31 * hash(b) + hash(sc) + cs.foldLeft(0)((acc, c) => acc + hash(c))
                // Push order (last appended = first processed):
                //   b, sc, InjectHash(0), cs(0), AddTop, cs(1), AddTop, ..., Combine3(0)
                work.append(new Combine3(0))
                var i = cs.length - 1
                while i >= 0 do
                    work.append(AddTop)
                    work.append(cs(i))
                    i -= 1
                end while
                work.append(new InjectHash(0))
                work.append(sc)
                work.append(b)

            case Tasty.Type.FlexibleType(u) =>
                // 31 * hash(u) + 13
                work.append(new Combine1(13))
                work.append(u)

            case Tasty.Type.MatchCase(pat, rhs) =>
                // 31 * hash(pat) + hash(rhs) + 14
                work.append(new Combine2(14))
                work.append(rhs)
                work.append(pat)

            case Tasty.Type.TypeRef(qual, name) =>
                // 31 * hash(qual) + name.hashCode + 15
                work.append(new Combine1(name.hashCode + 15))
                work.append(qual)

            case Tasty.Type.Bounds(lo, hi) =>
                // 31 * hash(lo) + hash(hi) + 16
                work.append(new Combine2(16))
                work.append(hi)
                work.append(lo)

            case Tasty.Type.Nothing =>
                work.append(new InjectHash(18))

            case Tasty.Type.Any =>
                work.append(new InjectHash(19))
        end match
    end pushHashWork

    // ---------------------------------------------------------------------------
    // Iterative structural equality
    // ---------------------------------------------------------------------------

    /** Compare two types for structural equality without JVM recursion.
      *
      * Uses an explicit work-list (ArrayDeque) so that deeply nested types (e.g. Applied chains at TypeArena.MaxDepth-1 levels) do not
      * overflow the JVM call stack. This is important under scoverage instrumentation, which enlarges each stack frame.
      *
      * Cycle safety: when both nodes are reference-equal (x eq y), the pair is skipped immediately. This handles the common case after
      * Phase C canonicalization (structurally equal types share the same reference) and terminates any Rec/RecThis back-edge pairs that
      * alias the same object. The kyo type decoder does not create circular object graphs in the Rec/RecThis encoding (RecThis.rec is
      * always an ancestor binder, never a heap cycle), so reference-equality short-circuiting is sufficient.
      */
    def structuralEquals(a: Tasty.Type, b: Tasty.Type): Boolean =
        val work = new mutable.ArrayDeque[(Tasty.Type, Tasty.Type)]()
        work.addOne((a, b))
        var result = true
        while result && work.nonEmpty do
            val (x, y) = work.removeHead()
            if !(x eq y) then
                (x, y) match
                    case (Tasty.Type.Named(s1), Tasty.Type.Named(s2)) =>
                        if s1 != s2 then result = false
                    case (Tasty.Type.TermRef(p1, n1), Tasty.Type.TermRef(p2, n2)) =>
                        if n1 != n2 then result = false
                        else work.addOne((p1, p2))
                    case (Tasty.Type.Applied(b1, a1), Tasty.Type.Applied(b2, a2)) =>
                        if a1.length != a2.length then result = false
                        else
                            work.addOne((b1, b2))
                            var i = 0
                            while i < a1.length do
                                work.addOne((a1(i), a2(i)))
                                i += 1
                            end while
                    case (Tasty.Type.TypeLambda(ps1, body1), Tasty.Type.TypeLambda(ps2, body2)) =>
                        if ps1.length != ps2.length then result = false
                        else work.addOne((body1, body2))
                    case (Tasty.Type.Function(ps1, r1), Tasty.Type.Function(ps2, r2)) =>
                        if ps1.length != ps2.length then result = false
                        else
                            work.addOne((r1, r2))
                            var i = 0
                            while i < ps1.length do
                                work.addOne((ps1(i), ps2(i)))
                                i += 1
                            end while
                    case (Tasty.Type.ContextFunction(ps1, r1), Tasty.Type.ContextFunction(ps2, r2)) =>
                        if ps1.length != ps2.length then result = false
                        else
                            work.addOne((r1, r2))
                            var i = 0
                            while i < ps1.length do
                                work.addOne((ps1(i), ps2(i)))
                                i += 1
                            end while
                    case (Tasty.Type.Tuple(e1), Tasty.Type.Tuple(e2)) =>
                        if e1.length != e2.length then result = false
                        else
                            var i = 0
                            while i < e1.length do
                                work.addOne((e1(i), e2(i)))
                                i += 1
                            end while
                    case (Tasty.Type.ByName(u1), Tasty.Type.ByName(u2)) =>
                        work.addOne((u1, u2))
                    case (Tasty.Type.Repeated(e1), Tasty.Type.Repeated(e2)) =>
                        work.addOne((e1, e2))
                    case (Tasty.Type.Array(e1), Tasty.Type.Array(e2)) =>
                        work.addOne((e1, e2))
                    case (Tasty.Type.Refinement(p1, n1, i1), Tasty.Type.Refinement(p2, n2, i2)) =>
                        if n1 != n2 then result = false
                        else
                            work.addOne((p1, p2))
                            work.addOne((i1, i2))
                    case (Tasty.Type.Rec(p1), Tasty.Type.Rec(p2)) =>
                        work.addOne((p1, p2))
                    case (Tasty.Type.RecThis(rec1), Tasty.Type.RecThis(rec2)) =>
                        work.addOne((rec1, rec2))
                    case (Tasty.Type.AndType(l1, r1), Tasty.Type.AndType(l2, r2)) =>
                        work.addOne((l1, l2))
                        work.addOne((r1, r2))
                    case (Tasty.Type.OrType(l1, r1), Tasty.Type.OrType(l2, r2)) =>
                        work.addOne((l1, l2))
                        work.addOne((r1, r2))
                    case (Tasty.Type.Annotated(u1, ann1), Tasty.Type.Annotated(u2, ann2)) =>
                        if !ann1.equals(ann2) then result = false
                        else work.addOne((u1, u2))
                    case (Tasty.Type.ConstantType(c1), Tasty.Type.ConstantType(c2)) =>
                        if !c1.equals(c2) then result = false
                    case (Tasty.Type.ThisType(s1), Tasty.Type.ThisType(s2)) =>
                        if s1 != s2 then result = false
                    case (Tasty.Type.SuperType(s1, m1), Tasty.Type.SuperType(s2, m2)) =>
                        work.addOne((s1, s2))
                        work.addOne((m1, m2))
                    case (Tasty.Type.ParamRef(b1, i1), Tasty.Type.ParamRef(b2, i2)) =>
                        if b1 != b2 || i1 != i2 then result = false
                    case (Tasty.Type.Wildcard(lo1, hi1), Tasty.Type.Wildcard(lo2, hi2)) =>
                        work.addOne((lo1, lo2))
                        work.addOne((hi1, hi2))
                    case (Tasty.Type.Skolem(u1), Tasty.Type.Skolem(u2)) =>
                        work.addOne((u1, u2))
                    case (Tasty.Type.MatchType(b1, sc1, cs1), Tasty.Type.MatchType(b2, sc2, cs2)) =>
                        if cs1.length != cs2.length then result = false
                        else
                            work.addOne((b1, b2))
                            work.addOne((sc1, sc2))
                            var i = 0
                            while i < cs1.length do
                                work.addOne((cs1(i), cs2(i)))
                                i += 1
                            end while
                    case (Tasty.Type.FlexibleType(u1), Tasty.Type.FlexibleType(u2)) =>
                        work.addOne((u1, u2))
                    case (Tasty.Type.MatchCase(p1, r1), Tasty.Type.MatchCase(p2, r2)) =>
                        work.addOne((p1, p2))
                        work.addOne((r1, r2))
                    case (Tasty.Type.TypeRef(q1, n1), Tasty.Type.TypeRef(q2, n2)) =>
                        if n1 != n2 then result = false
                        else work.addOne((q1, q2))
                    case (Tasty.Type.Bounds(lo1, hi1), Tasty.Type.Bounds(lo2, hi2)) =>
                        work.addOne((lo1, lo2))
                        work.addOne((hi1, hi2))
                    case (Tasty.Type.Nothing, Tasty.Type.Nothing) => ()
                    case (Tasty.Type.Any, Tasty.Type.Any)         => ()
                    case _ =>
                        result = false
                end match
            end if
        end while
        result
    end structuralEquals

end TypeKey
