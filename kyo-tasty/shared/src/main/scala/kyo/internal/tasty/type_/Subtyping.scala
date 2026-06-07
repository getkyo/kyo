package kyo.internal.tasty.type_

import kyo.*
import kyo.Tasty.SymbolId
import kyo.TastyError
import kyo.internal.tasty.symbol.SymbolKind

/** Subtype checking for `Tasty.Type` values.
  *
  * Implements a structural covariant subtyping relation between `Tasty.Type` ADT cases. All rules are purely structural: they do not
  * require TASTy or classfile I/O beyond what the `Classpath` already has in memory.
  *
  * Supported rules:
  *   - `Named(A) <: Named(B)` if `A eq B` (reflexivity) or if `B` appears in `A`'s transitive parent chain.
  *   - `Applied(C[As]) <: Applied(C[Bs])` iff variance-respecting element-wise subtyping holds for each argument pair.
  *   - `AndType(L, R) <: T` iff `L <: T` or `R <: T`.
  *   - `T <: OrType(L, R)` iff `T <: L` or `T <: R`.
  *   - `TypeLambda` structural alpha-equivalence (params renamed to positional indices before comparison).
  *   - `Wildcard(lo, hi) <: Wildcard(lo', hi')` iff `lo' <: lo` (contravariant lower) and `hi <: hi'` (covariant upper).
  *   - `Rec` unfolds one level per unfolding step. Budget: 64 steps. If exhausted, returns `Indeterminate`.
  *   - `Nothing <: T` for all `T` (bottom type).
  *
  * Three-way verdict:
  *
  * Results are `Tasty.SubtypeVerdict`: `Sub` (relation holds), `NotSub` (relation does not hold), or `Indeterminate` (budget exhausted or
  * parent chain absent from the classpath). Callers should pattern-match all three cases; the idiomatic migration shape is
  * `if v == SubtypeVerdict.Sub then ...`.
  *
  * Depth budget for Rec:
  *
  * A `Rec` type carries a recursive back-reference (`RecThis`) that points to itself. Naive structural equality/subtyping over `Rec` would
  * diverge. We defend against this by threading a `budget: Int` parameter through the recursion. Each time a `Rec` node is unfolded (i.e.,
  * `RecThis` references inside its body are substituted with the `Rec` node itself for the next comparison step), the budget decrements by 1.
  * When the budget reaches 0, `isSubtype` returns `Indeterminate` immediately without further recursion. Budget starts at 64 per top-level
  * call, so even a maximally-nested Rec type will terminate. The caller (`Type.isSubtypeOf`) always starts with `budget = 64`.
  *
  * The budget applies only to Rec unfolding, not to structural recursion depth over other ADT cases.
  *
  * Pure: all parent-chain lookups read from the immutable `parentTypes` field on Symbol (populated during Phase C construction). No Sync,
  * Abort, or AllowUnsafe effects are required.
  */
object Subtyping:

    import Tasty.SubtypeVerdict
    import Tasty.SubtypeVerdict.*

    // Using simple name for scala.Nothing / scala.Any checks. These are approximate but safe:
    // false positives are impossible (a symbol named "Any" that is not scala.Any would also pass
    // the sub-check but that is an acceptable over-approximation for the bottom-type short-circuit).
    private val NothingName: String = "Nothing"
    private val AnyName: String     = "Any"

    /** Check whether `sub <: sup`, using `cp` for parent-chain lookups.
      *
      * @param sub
      *   candidate subtype
      * @param sup
      *   candidate supertype
      * @param cp
      *   classpath for parent-chain resolution (accessed via pure reads on immutable post-open Symbol._parents slots)
      * @param budget
      *   remaining Rec-unfolding steps; 0 means return Indeterminate
      * @param errAcc
      *   nullable accumulator for TastyError.UnhandledSubtypingCase diagnostics; null means no accumulation
      */
    def isSubtype(
        sub: Tasty.Type,
        sup: Tasty.Type,
        cp: Tasty.Classpath,
        budget: Int,
        errAcc: scala.collection.mutable.ArrayBuffer[TastyError]
    ): SubtypeVerdict =
        if budget <= 0 then Indeterminate
        else
            // Any is supertype of everything
            sup match
                // ADT sentinel: Type.Any short-circuits before any classpath lookup
                case Tasty.Type.Any =>
                    Sub
                case Tasty.Type.Named(supId) if {
                        import Tasty.Name.asString; cp.symbol(supId).map(_.name.asString).getOrElse("") == AnyName
                    } =>
                    Sub
                // T <: OrType(L, R): Sub if either side is Sub; NotSub only if both are NotSub; else Indeterminate
                case Tasty.Type.OrType(supLeft, supRight) =>
                    val leftVerdict = isSubtype(sub, supLeft, cp, budget, errAcc)
                    if leftVerdict == Sub then Sub
                    else
                        val rightVerdict = isSubtype(sub, supRight, cp, budget, errAcc)
                        if rightVerdict == Sub then Sub
                        else if leftVerdict == NotSub && rightVerdict == NotSub then NotSub
                        else Indeterminate
                    end if
                case _ =>
                    sub match
                        // ADT sentinel: Type.Nothing short-circuits before any classpath lookup
                        case Tasty.Type.Nothing =>
                            Sub
                        // Nothing is subtype of everything
                        case Tasty.Type.Named(subId) if {
                                import Tasty.Name.asString; cp.symbol(subId).map(_.name.asString).getOrElse("") == NothingName
                            } =>
                            Sub

                        // Named reflexivity and nominal subtyping
                        case Tasty.Type.Named(subId) =>
                            sup match
                                case Tasty.Type.Named(supId) =>
                                    if subId == supId then Sub
                                    else isNamedSubNamed(subId, supId, cp, budget, errAcc)
                                case _ =>
                                    NotSub

                        // Applied: same base type, variance-respecting args
                        case Tasty.Type.Applied(subBase, subArgs) =>
                            sup match
                                case Tasty.Type.Applied(supBase, supArgs) =>
                                    val baseVerdict = isSubtype(subBase, supBase, cp, budget, errAcc)
                                    if baseVerdict != Sub then
                                        if baseVerdict == Indeterminate then Indeterminate else NotSub
                                    else if subArgs.length != supArgs.length then NotSub
                                    else
                                        // Resolve base symbol for variance lookup.
                                        val baseSymOpt: Maybe[Tasty.Symbol] = subBase match
                                            case Tasty.Type.Named(id) => cp.symbol(id)
                                            case _                    => Maybe.Absent
                                        checkAppliedArgs(subArgs, supArgs, baseSymOpt, cp, budget, errAcc)
                                    end if
                                case _ =>
                                    NotSub

                        // AndType(L, R) <: T: Sub if either side is Sub; NotSub only if both are NotSub; else Indeterminate
                        case Tasty.Type.AndType(left, right) =>
                            val leftVerdict = isSubtype(left, sup, cp, budget, errAcc)
                            if leftVerdict == Sub then Sub
                            else
                                val rightVerdict = isSubtype(right, sup, cp, budget, errAcc)
                                if rightVerdict == Sub then Sub
                                else if leftVerdict == NotSub && rightVerdict == NotSub then NotSub
                                else Indeterminate
                            end if

                        // TypeLambda: alpha-equivalence
                        case Tasty.Type.TypeLambda(subParamIds, subBody) =>
                            sup match
                                case Tasty.Type.TypeLambda(supParamIds, supBody) =>
                                    if subParamIds.length != supParamIds.length then NotSub
                                    else if alphaEquiv(
                                            Tasty.Type.TypeLambda(subParamIds, subBody),
                                            Tasty.Type.TypeLambda(supParamIds, supBody)
                                        )
                                    then Sub
                                    else NotSub
                                case _ =>
                                    NotSub

                        // Wildcard(lo, hi) <: Wildcard(lo', hi') iff lo' <: lo and hi <: hi'
                        case Tasty.Type.Wildcard(subLo, subHi) =>
                            sup match
                                case Tasty.Type.Wildcard(supLo, supHi) =>
                                    // Contravariant lower, covariant upper
                                    combineAnd(
                                        isSubtype(supLo, subLo, cp, budget, errAcc),
                                        isSubtype(subHi, supHi, cp, budget, errAcc)
                                    )
                                case _ =>
                                    NotSub

                        // Rec: unfold one level, decrement budget
                        case Tasty.Type.Rec(subParent) =>
                            sup match
                                case Tasty.Type.Rec(supParent) =>
                                    val subUnfolded = substituteRecThis(subParent, sub)
                                    val supUnfolded = substituteRecThis(supParent, sup)
                                    isSubtype(subUnfolded, supUnfolded, cp, budget - 1, errAcc)
                                case _ =>
                                    val subUnfolded = substituteRecThis(subParent, sub)
                                    isSubtype(subUnfolded, sup, cp, budget - 1, errAcc)

                        case _ =>
                            NotSub
        end if
    end isSubtype

    /** Combine two verdicts with AND semantics: Sub iff both Sub; NotSub if either NotSub; else Indeterminate. */
    private def combineAnd(a: SubtypeVerdict, b: SubtypeVerdict): SubtypeVerdict =
        if a == Sub && b == Sub then Sub
        else if a == NotSub || b == NotSub then NotSub
        else Indeterminate

    /** Combine two verdicts with OR semantics: Sub if either Sub; NotSub if both NotSub; else Indeterminate. */
    private def combineOr(a: SubtypeVerdict, b: SubtypeVerdict): SubtypeVerdict =
        if a == Sub || b == Sub then Sub
        else if a == NotSub && b == NotSub then NotSub
        else Indeterminate

    /** Check `Named(subId) <: Named(supId)` by walking the sub symbol's transitive parent chain.
      *
      * Resolves SymbolId -> Symbol via cp.symbol(id); parentTypes is a direct field.
      */
    private def isNamedSubNamed(
        subId: SymbolId,
        supId: SymbolId,
        cp: Tasty.Classpath,
        budget: Int,
        errAcc: scala.collection.mutable.ArrayBuffer[TastyError]
    ): SubtypeVerdict =
        val parents = cp.symbol(subId) match
            case c: Tasty.Symbol.ClassLike => c.parentTypes
            case _                         => Chunk.empty[Tasty.Type]
        checkParents(parents, supId, cp, budget, errAcc)
    end isNamedSubNamed

    /** Check if `supId` appears directly in the given parent list or transitively in any parent's parents. */
    private def checkParents(
        parents: Chunk[Tasty.Type],
        supId: SymbolId,
        cp: Tasty.Classpath,
        budget: Int,
        errAcc: scala.collection.mutable.ArrayBuffer[TastyError]
    ): SubtypeVerdict =
        if parents.isEmpty then NotSub
        else
            parents.head match
                case Tasty.Type.Named(parentId) =>
                    if parentId == supId then Sub
                    else
                        val transitiveVerdict = isNamedSubNamed(parentId, supId, cp, budget, errAcc)
                        if transitiveVerdict == Sub then Sub
                        else
                            val tailVerdict = checkParents(parents.tail, supId, cp, budget, errAcc)
                            if tailVerdict == Sub then Sub
                            else if transitiveVerdict == Indeterminate || tailVerdict == Indeterminate then Indeterminate
                            else NotSub
                        end if
                case Tasty.Type.Applied(Tasty.Type.Named(parentId), _) =>
                    if parentId == supId then Sub
                    else
                        val transitiveVerdict = isNamedSubNamed(parentId, supId, cp, budget, errAcc)
                        if transitiveVerdict == Sub then Sub
                        else
                            val tailVerdict = checkParents(parents.tail, supId, cp, budget, errAcc)
                            if tailVerdict == Sub then Sub
                            else if transitiveVerdict == Indeterminate || tailVerdict == Indeterminate then Indeterminate
                            else NotSub
                        end if
                case other =>
                    // Unhandled parent shape. Record diagnostic if accumulator is present.
                    if errAcc != null then
                        errAcc += TastyError.UnhandledSubtypingCase(
                            shape = shapeName(other),
                            lhs = other,
                            rhs = Tasty.Type.Named(supId),
                            file = "<unknown>"
                        )
                    end if
                    val tailVerdict = checkParents(parents.tail, supId, cp, budget, errAcc)
                    if tailVerdict == Sub then Sub else Indeterminate

    /** Variance-respecting argument check for Applied types.
      *
      * If the base type symbol is available, uses its typeParams to determine variance flags. Falls back to invariant when variance
      * information is not available.
      */
    private def checkAppliedArgs(
        subArgs: Chunk[Tasty.Type],
        supArgs: Chunk[Tasty.Type],
        baseSymOpt: Maybe[Tasty.Symbol],
        cp: Tasty.Classpath,
        budget: Int,
        errAcc: scala.collection.mutable.ArrayBuffer[TastyError]
    ): SubtypeVerdict =
        val typeParamsOpt: Maybe[Chunk[Tasty.Symbol]] = baseSymOpt.flatMap: baseSym =>
            val tps = (baseSym match
                case c: Tasty.Symbol.ClassLike   => c.typeParamIds
                case m: Tasty.Symbol.Method      => m.typeParamIds
                case ta: Tasty.Symbol.TypeAlias  => ta.typeParamIds
                case ot: Tasty.Symbol.OpaqueType => ot.typeParamIds
                case _                           => Chunk.empty
            ).flatMap(id => cp.symbol(id).toChunk)
            if tps.nonEmpty then Maybe(tps) else Maybe.Absent
        checkArgPairs(subArgs, supArgs, typeParamsOpt, 0, cp, budget, errAcc)
    end checkAppliedArgs

    private def checkArgPairs(
        subArgs: Chunk[Tasty.Type],
        supArgs: Chunk[Tasty.Type],
        typeParamsOpt: Maybe[Chunk[Tasty.Symbol]],
        idx: Int,
        cp: Tasty.Classpath,
        budget: Int,
        errAcc: scala.collection.mutable.ArrayBuffer[TastyError]
    ): SubtypeVerdict =
        if idx >= subArgs.length then Sub
        else
            val subArg = subArgs(idx)
            val supArg = supArgs(idx)
            val varianceOpt: Maybe[Int] = typeParamsOpt.flatMap: tps =>
                if idx < tps.length then
                    val tp = tps(idx)
                    if tp.flags.contains(Tasty.Flag.CoVariant) then Maybe(1)
                    else if tp.flags.contains(Tasty.Flag.ContraVariant) then Maybe(-1)
                    else Maybe(0) // invariant
                else Maybe.Absent
            // Default to invariant (0) when variance info is absent
            val variance = varianceOpt.getOrElse(0)
            val argVerdict: SubtypeVerdict =
                if variance == 1 then
                    isSubtype(subArg, supArg, cp, budget, errAcc) // covariant
                else if variance == -1 then
                    isSubtype(supArg, subArg, cp, budget, errAcc) // contravariant
                else
                    // invariant: both directions
                    combineAnd(
                        isSubtype(subArg, supArg, cp, budget, errAcc),
                        isSubtype(supArg, subArg, cp, budget, errAcc)
                    )
            argVerdict match
                case Sub =>
                    checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, cp, budget, errAcc)
                case NotSub =>
                    NotSub
                case Indeterminate =>
                    // Check remaining pairs but propagate Indeterminate if no NotSub found
                    val restVerdict = checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, cp, budget, errAcc)
                    if restVerdict == NotSub then NotSub else Indeterminate
            end match

    /** Produce a short human-readable label for a Type constructor, used in UnhandledSubtypingCase diagnostics. */
    private def shapeName(t: Tasty.Type): String = t match
        case _: Tasty.Type.TermRef                        => "TermRef"
        case _: Tasty.Type.TypeRef                        => "TypeRef"
        case Tasty.Type.Applied(_: Tasty.Type.TermRef, _) => "Applied(TermRef,_)"
        case _: Tasty.Type.Applied                        => "Applied"
        case _: Tasty.Type.AndType                        => "AndType"
        case _: Tasty.Type.OrType                         => "OrType"
        case _: Tasty.Type.Annotated                      => "Annotated"
        case _: Tasty.Type.Rec                            => "Rec"
        case _                                            => t.getClass.getSimpleName

    /** Structural alpha-equivalence for TypeLambda: rename params to positional de Bruijn-like indices.
      *
      * Two TypeLambda types are alpha-equivalent iff their bodies are structurally equal after replacing each parameter SymbolId with its
      * positional index in the parameter list.
      */
    private def alphaEquiv(t1: Tasty.Type.TypeLambda, t2: Tasty.Type.TypeLambda): Boolean =
        val idx1 = buildParamIndex(t1.paramIds, Dict.empty[SymbolId, Int])
        val idx2 = buildParamIndex(t2.paramIds, Dict.empty[SymbolId, Int])
        typeEquivAlpha(t1.body, t2.body, idx1, idx2)
    end alphaEquiv

    private def buildParamIndex(params: Chunk[SymbolId], base: Dict[SymbolId, Int]): Dict[SymbolId, Int] =
        var result = base
        var i      = 0
        while i < params.length do
            result = result.update(params(i), base.size + i)
            i += 1
        result
    end buildParamIndex

    private def typeEquivAlpha(
        t1: Tasty.Type,
        t2: Tasty.Type,
        idx1: Dict[SymbolId, Int],
        idx2: Dict[SymbolId, Int]
    ): Boolean =
        (t1, t2) match
            case (Tasty.Type.Named(s1), Tasty.Type.Named(s2)) =>
                // Either both are bound params at same position, or both are the same external SymbolId
                (idx1.get(s1), idx2.get(s2)) match
                    case (Maybe.Present(i), Maybe.Present(j)) => i == j
                    case (Maybe.Absent, Maybe.Absent)         => s1 == s2
                    case _                                    => false
            case (Tasty.Type.Applied(b1, a1), Tasty.Type.Applied(b2, a2)) =>
                typeEquivAlpha(b1, b2, idx1, idx2) &&
                a1.length == a2.length &&
                chunkPairsEquivAlpha(a1, a2, idx1, idx2)
            case (Tasty.Type.TypeLambda(p1, bd1), Tasty.Type.TypeLambda(p2, bd2)) =>
                if p1.length != p2.length then false
                else
                    // Nested lambda: extend index maps with new param positions
                    val ext1 = buildParamIndex(p1, idx1)
                    val ext2 = buildParamIndex(p2, idx2)
                    typeEquivAlpha(bd1, bd2, ext1, ext2)
            case (Tasty.Type.AndType(l1, r1), Tasty.Type.AndType(l2, r2)) =>
                typeEquivAlpha(l1, l2, idx1, idx2) && typeEquivAlpha(r1, r2, idx1, idx2)
            case (Tasty.Type.OrType(l1, r1), Tasty.Type.OrType(l2, r2)) =>
                typeEquivAlpha(l1, l2, idx1, idx2) && typeEquivAlpha(r1, r2, idx1, idx2)
            case (Tasty.Type.Wildcard(lo1, hi1), Tasty.Type.Wildcard(lo2, hi2)) =>
                typeEquivAlpha(lo1, lo2, idx1, idx2) && typeEquivAlpha(hi1, hi2, idx1, idx2)
            case (Tasty.Type.Rec(p1), Tasty.Type.Rec(p2)) =>
                typeEquivAlpha(p1, p2, idx1, idx2)
            case (Tasty.Type.RecThis(r1), Tasty.Type.RecThis(r2)) =>
                typeEquivAlpha(r1, r2, idx1, idx2)
            case (Tasty.Type.Function(ps1, r1), Tasty.Type.Function(ps2, r2)) =>
                ps1.length == ps2.length &&
                chunkPairsEquivAlpha(ps1, ps2, idx1, idx2) &&
                typeEquivAlpha(r1, r2, idx1, idx2)
            case (Tasty.Type.ContextFunction(ps1, r1), Tasty.Type.ContextFunction(ps2, r2)) =>
                ps1.length == ps2.length &&
                chunkPairsEquivAlpha(ps1, ps2, idx1, idx2) &&
                typeEquivAlpha(r1, r2, idx1, idx2)
            case _ =>
                // No structural match: not alpha-equivalent
                false

    private def chunkPairsEquivAlpha(
        a1: Chunk[Tasty.Type],
        a2: Chunk[Tasty.Type],
        idx1: Dict[SymbolId, Int],
        idx2: Dict[SymbolId, Int]
    ): Boolean =
        var i = 0
        while i < a1.length do
            if !typeEquivAlpha(a1(i), a2(i), idx1, idx2) then return false
            i += 1
        true
    end chunkPairsEquivAlpha

    /** Substitute all `RecThis(rec)` nodes where `rec eq recNode` with `recNode` itself. Used to unfold one level of Rec. */
    private def substituteRecThis(tpe: Tasty.Type, recNode: Tasty.Type): Tasty.Type =
        tpe match
            case Tasty.Type.RecThis(rec) if rec eq recNode => recNode
            case Tasty.Type.RecThis(_)                     => tpe
            case Tasty.Type.Rec(parent) =>
                Tasty.Type.Rec(substituteRecThis(parent, recNode))
            case Tasty.Type.Applied(base, args) =>
                Tasty.Type.Applied(
                    substituteRecThis(base, recNode),
                    args.map(substituteRecThis(_, recNode))
                )
            case Tasty.Type.AndType(l, r) =>
                Tasty.Type.AndType(substituteRecThis(l, recNode), substituteRecThis(r, recNode))
            case Tasty.Type.OrType(l, r) =>
                Tasty.Type.OrType(substituteRecThis(l, recNode), substituteRecThis(r, recNode))
            case Tasty.Type.TypeLambda(paramIds, body) =>
                Tasty.Type.TypeLambda(paramIds, substituteRecThis(body, recNode))
            case Tasty.Type.Wildcard(lo, hi) =>
                Tasty.Type.Wildcard(substituteRecThis(lo, recNode), substituteRecThis(hi, recNode))
            case Tasty.Type.Function(params, result) =>
                Tasty.Type.Function(params.map(substituteRecThis(_, recNode)), substituteRecThis(result, recNode))
            case Tasty.Type.ContextFunction(params, result) =>
                Tasty.Type.ContextFunction(params.map(substituteRecThis(_, recNode)), substituteRecThis(result, recNode))
            case other => other

end Subtyping
