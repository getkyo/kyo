package kyo.internal.reflect.type_

import kyo.*
import kyo.internal.reflect.query.Classpath as InternalClasspath

/** Subtype checking for `Reflect.Type` values.
  *
  * Implements a structural covariant subtyping relation between `Reflect.Type` ADT cases. All rules are purely structural: they do not
  * require TASTy or classfile I/O beyond what the `Classpath` already has in memory.
  *
  * Supported rules:
  *   - `Named(A) <: Named(B)` if `A eq B` (reflexivity) or if `B` appears in `A`'s transitive parent chain.
  *   - `Applied(C[As]) <: Applied(C[Bs])` iff variance-respecting element-wise subtyping holds for each argument pair.
  *   - `AndType(L, R) <: T` iff `L <: T` or `R <: T`.
  *   - `T <: OrType(L, R)` iff `T <: L` or `T <: R`.
  *   - `TypeLambda` structural alpha-equivalence (params renamed to positional indices before comparison).
  *   - `Wildcard(lo, hi) <: Wildcard(lo', hi')` iff `lo' <: lo` (contravariant lower) and `hi <: hi'` (covariant upper).
  *   - `Rec` unfolds one level per unfolding step. Budget: 64 steps. If exhausted, returns `false` (conservative: not-a-subtype).
  *   - `Nothing <: T` for all `T` (bottom type).
  *
  * Depth budget for Rec:
  *
  * A `Rec` type carries a recursive back-reference (`RecThis`) that points to itself. Naive structural equality/subtyping over `Rec` would
  * diverge. We defend against this by threading a `budget: Int` parameter through the recursion. Each time a `Rec` node is unfolded (i.e.,
  * `RecThis` references inside its body are substituted with the `Rec` node itself for the next comparison step), the budget decrements by 1.
  * When the budget reaches 0, `isSubtype` returns `false` immediately without further recursion. Budget starts at 64 per top-level call, so
  * even a maximally-nested Rec type will terminate. The caller (`Type.isSubtypeOf`) always starts with `budget = 64`.
  *
  * The budget applies only to Rec unfolding, not to structural recursion depth over other ADT cases.
  *
  * Pure (v3 Phase 3): all parent-chain lookups read from pre-populated `_parents` SingleAssign slots via AllowUnsafe. No Sync or Abort
  * effects are required.
  */
object Subtyping:

    private val NothingFqn: String = "scala.Nothing"
    private val AnyFqn: String     = "scala.Any"

    /** Check whether `sub <: sup`, using `cp` for parent-chain lookups.
      *
      * @param sub
      *   candidate subtype
      * @param sup
      *   candidate supertype
      * @param cp
      *   classpath for parent-chain resolution (accessed via pure AllowUnsafe reads on Symbol._parents slots)
      * @param budget
      *   remaining Rec-unfolding steps; 0 means return false conservatively
      */
    def isSubtype(sub: Reflect.Type, sup: Reflect.Type, cp: InternalClasspath, budget: Int): Boolean =
        if budget <= 0 then false
        else
            // Any is supertype of everything
            sup match
                case Reflect.Type.Named(supSym) if supSym.fullName.asString == AnyFqn =>
                    true
                // T <: OrType(L, R) iff T <: L or T <: R (applies to all sub-types)
                case Reflect.Type.OrType(supLeft, supRight) =>
                    isSubtype(sub, supLeft, cp, budget) || isSubtype(sub, supRight, cp, budget)
                case _ =>
                    sub match
                        // Nothing is subtype of everything
                        case Reflect.Type.Named(subSym) if subSym.fullName.asString == NothingFqn =>
                            true

                        // Named reflexivity and nominal subtyping
                        case Reflect.Type.Named(subSym) =>
                            sup match
                                case Reflect.Type.Named(supSym) =>
                                    if subSym eq supSym then true
                                    else isNamedSubNamed(subSym, supSym, cp, budget)
                                case _ =>
                                    false

                        // Applied: same base type, variance-respecting args
                        case Reflect.Type.Applied(subBase, subArgs) =>
                            sup match
                                case Reflect.Type.Applied(supBase, supArgs) =>
                                    if !isSubtype(subBase, supBase, cp, budget) then false
                                    else if subArgs.length != supArgs.length then false
                                    else
                                        val baseSymOpt = subBase match
                                            case Reflect.Type.Named(s) => Maybe(s)
                                            case _                     => Maybe.Absent
                                        checkAppliedArgs(subArgs, supArgs, baseSymOpt, cp, budget)
                                case _ =>
                                    false

                        // AndType(L, R) <: T iff L <: T or R <: T
                        case Reflect.Type.AndType(left, right) =>
                            isSubtype(left, sup, cp, budget) || isSubtype(right, sup, cp, budget)

                        // TypeLambda: alpha-equivalence
                        case Reflect.Type.TypeLambda(subParams, subBody) =>
                            sup match
                                case Reflect.Type.TypeLambda(supParams, supBody) =>
                                    if subParams.length != supParams.length then false
                                    else
                                        alphaEquiv(
                                            Reflect.Type.TypeLambda(subParams, subBody),
                                            Reflect.Type.TypeLambda(supParams, supBody)
                                        )
                                case _ =>
                                    false

                        // Wildcard(lo, hi) <: Wildcard(lo', hi') iff lo' <: lo and hi <: hi'
                        case Reflect.Type.Wildcard(subLo, subHi) =>
                            sup match
                                case Reflect.Type.Wildcard(supLo, supHi) =>
                                    // Contravariant lower, covariant upper
                                    isSubtype(supLo, subLo, cp, budget) && isSubtype(subHi, supHi, cp, budget)
                                case _ =>
                                    false

                        // Rec: unfold one level, decrement budget
                        case Reflect.Type.Rec(subParent) =>
                            sup match
                                case Reflect.Type.Rec(supParent) =>
                                    val subUnfolded = substituteRecThis(subParent, sub)
                                    val supUnfolded = substituteRecThis(supParent, sup)
                                    isSubtype(subUnfolded, supUnfolded, cp, budget - 1)
                                case _ =>
                                    val subUnfolded = substituteRecThis(subParent, sub)
                                    isSubtype(subUnfolded, sup, cp, budget - 1)

                        case _ =>
                            false
    end isSubtype

    /** Check `Named(subSym) <: Named(supSym)` by walking `subSym`'s transitive parent chain. */
    private def isNamedSubNamed(
        subSym: Reflect.Symbol,
        supSym: Reflect.Symbol,
        cp: InternalClasspath,
        budget: Int
    ): Boolean =
        // Unsafe: SingleAssign is an unsafe-tier helper; AllowUnsafe is embraced here for the parents accessor.
        // Reading immutable Ready-state data set during open, before any user access.
        import AllowUnsafe.embrace.danger
        if subSym._parents.isSet then
            val parents = subSym._parents.get()
            checkParents(parents, supSym, cp, budget)
        else
            // Parents not yet populated (symbol from a not-fully-loaded classpath): conservative false
            false
        end if
    end isNamedSubNamed

    /** Check if `supSym` appears directly in the given parent list or transitively in any parent's parents. */
    private def checkParents(
        parents: Chunk[Reflect.Type],
        supSym: Reflect.Symbol,
        cp: InternalClasspath,
        budget: Int
    ): Boolean =
        if parents.isEmpty then false
        else
            parents.head match
                case Reflect.Type.Named(parentSym) =>
                    if parentSym eq supSym then true
                    else if isNamedSubNamed(parentSym, supSym, cp, budget) then true
                    else checkParents(parents.tail, supSym, cp, budget)
                case Reflect.Type.Applied(Reflect.Type.Named(parentSym), _) =>
                    if parentSym eq supSym then true
                    else if isNamedSubNamed(parentSym, supSym, cp, budget) then true
                    else checkParents(parents.tail, supSym, cp, budget)
                case _ =>
                    checkParents(parents.tail, supSym, cp, budget)

    /** Variance-respecting argument check for Applied types.
      *
      * If the base type symbol is available, uses its typeParams to determine variance flags. Falls back to invariant when variance
      * information is not available.
      */
    private def checkAppliedArgs(
        subArgs: Chunk[Reflect.Type],
        supArgs: Chunk[Reflect.Type],
        baseSymOpt: Maybe[Reflect.Symbol],
        cp: InternalClasspath,
        budget: Int
    ): Boolean =
        // Unsafe: SingleAssign is an unsafe-tier helper.
        // Reading immutable Ready-state data set during open, before any user access.
        import AllowUnsafe.embrace.danger
        val typeParamsOpt: Maybe[Chunk[Reflect.Symbol]] = baseSymOpt.flatMap: baseSym =>
            if baseSym._typeParams.isSet then Maybe(baseSym._typeParams.get())
            else Maybe.Absent
        checkArgPairs(subArgs, supArgs, typeParamsOpt, 0, cp, budget)
    end checkAppliedArgs

    private def checkArgPairs(
        subArgs: Chunk[Reflect.Type],
        supArgs: Chunk[Reflect.Type],
        typeParamsOpt: Maybe[Chunk[Reflect.Symbol]],
        idx: Int,
        cp: InternalClasspath,
        budget: Int
    ): Boolean =
        if idx >= subArgs.length then true
        else
            val subArg = subArgs(idx)
            val supArg = supArgs(idx)
            val varianceOpt: Maybe[Int] = typeParamsOpt.flatMap: tps =>
                if idx < tps.length then
                    val tp = tps(idx)
                    if tp.flags.contains(Reflect.Flag.CoVariant) then Maybe(1)
                    else if tp.flags.contains(Reflect.Flag.ContraVariant) then Maybe(-1)
                    else Maybe(0) // invariant
                else Maybe.Absent
            // Default to invariant (0) when variance info is absent
            val variance = varianceOpt.getOrElse(0)
            val argOk: Boolean =
                if variance == 1 then
                    isSubtype(subArg, supArg, cp, budget) // covariant
                else if variance == -1 then
                    isSubtype(supArg, subArg, cp, budget) // contravariant
                else
                    // invariant: both directions
                    isSubtype(subArg, supArg, cp, budget) && isSubtype(supArg, subArg, cp, budget)
            if !argOk then false
            else checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, cp, budget)

    /** Structural alpha-equivalence for TypeLambda: rename params to positional de Bruijn-like indices.
      *
      * Two TypeLambda types are alpha-equivalent iff their bodies are structurally equal after replacing each parameter symbol with its
      * positional index in the parameter list.
      */
    private def alphaEquiv(t1: Reflect.Type.TypeLambda, t2: Reflect.Type.TypeLambda): Boolean =
        val idx1 = buildParamIndex(t1.params, Map.empty)
        val idx2 = buildParamIndex(t2.params, Map.empty)
        typeEquivAlpha(t1.body, t2.body, idx1, idx2)
    end alphaEquiv

    private def buildParamIndex(params: Chunk[Reflect.Symbol], base: Map[Reflect.Symbol, Int]): Map[Reflect.Symbol, Int] =
        var result = base
        var i      = 0
        while i < params.length do
            result = result + (params(i) -> (base.size + i))
            i += 1
        result
    end buildParamIndex

    private def typeEquivAlpha(
        t1: Reflect.Type,
        t2: Reflect.Type,
        idx1: Map[Reflect.Symbol, Int],
        idx2: Map[Reflect.Symbol, Int]
    ): Boolean =
        (t1, t2) match
            case (Reflect.Type.Named(s1), Reflect.Type.Named(s2)) =>
                // Either both are bound params at same position, or both are the same external symbol
                (idx1.get(s1), idx2.get(s2)) match
                    case (Some(i), Some(j)) => i == j
                    case (None, None)       => s1 eq s2
                    case _                  => false
            case (Reflect.Type.Applied(b1, a1), Reflect.Type.Applied(b2, a2)) =>
                typeEquivAlpha(b1, b2, idx1, idx2) &&
                a1.length == a2.length &&
                chunkPairsEquivAlpha(a1, a2, idx1, idx2)
            case (Reflect.Type.TypeLambda(p1, bd1), Reflect.Type.TypeLambda(p2, bd2)) =>
                if p1.length != p2.length then false
                else
                    // Nested lambda: extend index maps with new param positions
                    val ext1 = buildParamIndex(p1, idx1)
                    val ext2 = buildParamIndex(p2, idx2)
                    typeEquivAlpha(bd1, bd2, ext1, ext2)
            case (Reflect.Type.AndType(l1, r1), Reflect.Type.AndType(l2, r2)) =>
                typeEquivAlpha(l1, l2, idx1, idx2) && typeEquivAlpha(r1, r2, idx1, idx2)
            case (Reflect.Type.OrType(l1, r1), Reflect.Type.OrType(l2, r2)) =>
                typeEquivAlpha(l1, l2, idx1, idx2) && typeEquivAlpha(r1, r2, idx1, idx2)
            case (Reflect.Type.Wildcard(lo1, hi1), Reflect.Type.Wildcard(lo2, hi2)) =>
                typeEquivAlpha(lo1, lo2, idx1, idx2) && typeEquivAlpha(hi1, hi2, idx1, idx2)
            case (Reflect.Type.Rec(p1), Reflect.Type.Rec(p2)) =>
                typeEquivAlpha(p1, p2, idx1, idx2)
            case (Reflect.Type.RecThis(r1), Reflect.Type.RecThis(r2)) =>
                typeEquivAlpha(r1, r2, idx1, idx2)
            case (Reflect.Type.Function(ps1, r1, ctx1), Reflect.Type.Function(ps2, r2, ctx2)) =>
                ctx1 == ctx2 &&
                ps1.length == ps2.length &&
                chunkPairsEquivAlpha(ps1, ps2, idx1, idx2) &&
                typeEquivAlpha(r1, r2, idx1, idx2)
            case _ =>
                // No structural match: not alpha-equivalent
                false

    private def chunkPairsEquivAlpha(
        a1: Chunk[Reflect.Type],
        a2: Chunk[Reflect.Type],
        idx1: Map[Reflect.Symbol, Int],
        idx2: Map[Reflect.Symbol, Int]
    ): Boolean =
        var i = 0
        while i < a1.length do
            if !typeEquivAlpha(a1(i), a2(i), idx1, idx2) then return false
            i += 1
        true
    end chunkPairsEquivAlpha

    /** Substitute all `RecThis(rec)` nodes where `rec eq recNode` with `recNode` itself. Used to unfold one level of Rec. */
    private def substituteRecThis(tpe: Reflect.Type, recNode: Reflect.Type): Reflect.Type =
        tpe match
            case Reflect.Type.RecThis(rec) if rec eq recNode => recNode
            case Reflect.Type.RecThis(_)                     => tpe
            case Reflect.Type.Rec(parent) =>
                Reflect.Type.Rec(substituteRecThis(parent, recNode))
            case Reflect.Type.Applied(base, args) =>
                Reflect.Type.Applied(
                    substituteRecThis(base, recNode),
                    args.map(substituteRecThis(_, recNode))
                )
            case Reflect.Type.AndType(l, r) =>
                Reflect.Type.AndType(substituteRecThis(l, recNode), substituteRecThis(r, recNode))
            case Reflect.Type.OrType(l, r) =>
                Reflect.Type.OrType(substituteRecThis(l, recNode), substituteRecThis(r, recNode))
            case Reflect.Type.TypeLambda(params, body) =>
                Reflect.Type.TypeLambda(params, substituteRecThis(body, recNode))
            case Reflect.Type.Wildcard(lo, hi) =>
                Reflect.Type.Wildcard(substituteRecThis(lo, recNode), substituteRecThis(hi, recNode))
            case Reflect.Type.Function(params, result, ctx) =>
                Reflect.Type.Function(params.map(substituteRecThis(_, recNode)), substituteRecThis(result, recNode), ctx)
            case other => other

end Subtyping
