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
  * parent chain absent from the classpath). Callers should pattern-match all three cases or check `v == SubtypeVerdict.Sub` when
  * `Indeterminate` should be treated as a non-match.
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

    /** Check whether `sub <: sup`, using `classpath` for parent-chain lookups.
      *
      * Returns `Result.Success(verdict)` for the three-way verdict, or `Result.Failure(TastyError.UnhandledSubtypingCase)`
      * on the first parent-walk shape not covered by this engine.
      *
      * @param sub
      *   candidate subtype
      * @param sup
      *   candidate supertype
      * @param classpath
      *   classpath for parent-chain resolution (accessed via pure reads on immutable post-open Symbol._parents slots)
      * @param budget
      *   remaining Rec-unfolding steps; 0 means return Result.Success(Indeterminate)
      */
    def isSubtype(
        sub: Tasty.Type,
        sup: Tasty.Type,
        classpath: Tasty.Classpath,
        budget: Int
    ): Result[TastyError, SubtypeVerdict] =
        if budget <= 0 then Result.Success(Indeterminate)
        else
            // Any is supertype of everything
            sup match
                // ADT sentinel: Type.Any short-circuits before any classpath lookup
                case Tasty.Type.Any =>
                    Result.Success(Sub)
                case Tasty.Type.Named(supId) if {
                        import Tasty.Name.asString; classpath.symbol(supId).map(_.name.asString).getOrElse("") == AnyName
                    } =>
                    Result.Success(Sub)
                // T <: OrType(L, R): Sub if either side is Sub; NotSub only if both are NotSub; else Indeterminate
                case Tasty.Type.OrType(supLeft, supRight) =>
                    isSubtype(sub, supLeft, classpath, budget) match
                        case f: Result.Failure[TastyError] @unchecked => f
                        case Result.Success(leftVerdict) =>
                            if leftVerdict == Sub then Result.Success(Sub)
                            else
                                isSubtype(sub, supRight, classpath, budget) match
                                    case f: Result.Failure[TastyError] @unchecked => f
                                    case Result.Success(rightVerdict) =>
                                        if rightVerdict == Sub then Result.Success(Sub)
                                        else if leftVerdict == NotSub && rightVerdict == NotSub then Result.Success(NotSub)
                                        else Result.Success(Indeterminate)
                                end match
                            end if
                case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                    _: Tasty.Type.TypeLambda | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                    _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                    _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.Rec |
                    _: Tasty.Type.RecThis | _: Tasty.Type.AndType | _: Tasty.Type.Annotated |
                    _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                    _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                    _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                    _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing =>
                    sub match
                        // ADT sentinel: Type.Nothing short-circuits before any classpath lookup
                        case Tasty.Type.Nothing =>
                            Result.Success(Sub)
                        // Nothing is subtype of everything
                        case Tasty.Type.Named(subId) if {
                                import Tasty.Name.asString; classpath.symbol(subId).map(_.name.asString).getOrElse("") == NothingName
                            } =>
                            Result.Success(Sub)

                        // Named reflexivity and nominal subtyping
                        case Tasty.Type.Named(subId) =>
                            sup match
                                case Tasty.Type.Named(supId) =>
                                    if subId == supId then Result.Success(Sub)
                                    else isNamedSubNamed(subId, supId, classpath, budget)
                                case _: Tasty.Type.TermRef | _: Tasty.Type.Applied | _: Tasty.Type.TypeLambda |
                                    _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                                    _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                                    _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                                    _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                                    _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                                    _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                                    _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                                    _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing |
                                    Tasty.Type.Any =>
                                    Result.Success(NotSub)

                        // Applied: same base type, variance-respecting args
                        case Tasty.Type.Applied(subBase, subArgs) =>
                            sup match
                                case Tasty.Type.Applied(supBase, supArgs) =>
                                    isSubtype(subBase, supBase, classpath, budget) match
                                        case f: Result.Failure[TastyError] @unchecked => f
                                        case Result.Success(baseVerdict) =>
                                            if baseVerdict != Sub then
                                                Result.Success(if baseVerdict == Indeterminate then Indeterminate else NotSub)
                                            else if subArgs.length != supArgs.length then Result.Success(NotSub)
                                            else
                                                // Resolve base symbol for variance lookup.
                                                val baseSymOpt: Maybe[Tasty.Symbol] = subBase match
                                                    case Tasty.Type.Named(id) => classpath.symbol(id)
                                                    case _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                                                        _: Tasty.Type.TypeLambda | _: Tasty.Type.Function |
                                                        _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                                                        _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                                                        _: Tasty.Type.Array | _: Tasty.Type.Refinement |
                                                        _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                                                        _: Tasty.Type.AndType | _: Tasty.Type.OrType |
                                                        _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType |
                                                        _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                                                        _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard |
                                                        _: Tasty.Type.Skolem | _: Tasty.Type.MatchType |
                                                        _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                                                        _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds |
                                                        Tasty.Type.Nothing | Tasty.Type.Any =>
                                                        Maybe.Absent
                                                checkAppliedArgs(subArgs, supArgs, baseSymOpt, classpath, budget)
                                            end if
                                case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.TypeLambda |
                                    _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                                    _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                                    _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                                    _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                                    _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                                    _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                                    _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                                    _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing |
                                    Tasty.Type.Any =>
                                    Result.Success(NotSub)

                        // AndType(L, R) <: T: Sub if either side is Sub; NotSub only if both are NotSub; else Indeterminate
                        case Tasty.Type.AndType(left, right) =>
                            isSubtype(left, sup, classpath, budget) match
                                case f: Result.Failure[TastyError] @unchecked => f
                                case Result.Success(leftVerdict) =>
                                    if leftVerdict == Sub then Result.Success(Sub)
                                    else
                                        isSubtype(right, sup, classpath, budget) match
                                            case f: Result.Failure[TastyError] @unchecked => f
                                            case Result.Success(rightVerdict) =>
                                                if rightVerdict == Sub then Result.Success(Sub)
                                                else if leftVerdict == NotSub && rightVerdict == NotSub then Result.Success(NotSub)
                                                else Result.Success(Indeterminate)
                                        end match
                                    end if

                        // TypeLambda: alpha-equivalence
                        case Tasty.Type.TypeLambda(subParamIds, subBody) =>
                            sup match
                                case Tasty.Type.TypeLambda(supParamIds, supBody) =>
                                    if subParamIds.length != supParamIds.length then Result.Success(NotSub)
                                    else if alphaEquiv(
                                            Tasty.Type.TypeLambda(subParamIds, subBody),
                                            Tasty.Type.TypeLambda(supParamIds, supBody)
                                        )
                                    then Result.Success(Sub)
                                    else Result.Success(NotSub)
                                case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                                    _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                                    _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                                    _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                                    _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                                    _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                                    _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                                    _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                                    _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing |
                                    Tasty.Type.Any =>
                                    Result.Success(NotSub)

                        // Wildcard(lo, hi) <: Wildcard(lo', hi') iff lo' <: lo and hi <: hi'
                        case Tasty.Type.Wildcard(subLo, subHi) =>
                            sup match
                                case Tasty.Type.Wildcard(supLo, supHi) =>
                                    // Contravariant lower, covariant upper
                                    isSubtype(supLo, subLo, classpath, budget) match
                                        case f: Result.Failure[TastyError] @unchecked => f
                                        case Result.Success(loVerdict) =>
                                            isSubtype(subHi, supHi, classpath, budget) match
                                                case f: Result.Failure[TastyError] @unchecked => f
                                                case Result.Success(hiVerdict) =>
                                                    Result.Success(combineAnd(loVerdict, hiVerdict))
                                case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                                    _: Tasty.Type.TypeLambda | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                                    _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                                    _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.Rec |
                                    _: Tasty.Type.RecThis | _: Tasty.Type.AndType | _: Tasty.Type.OrType |
                                    _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType |
                                    _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef | _: Tasty.Type.Skolem |
                                    _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                                    _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing |
                                    Tasty.Type.Any =>
                                    Result.Success(NotSub)

                        // Rec: unfold one level, decrement budget
                        case Tasty.Type.Rec(subParent) =>
                            sup match
                                case Tasty.Type.Rec(supParent) =>
                                    val subUnfolded = substituteRecThis(subParent, sub)
                                    val supUnfolded = substituteRecThis(supParent, sup)
                                    isSubtype(subUnfolded, supUnfolded, classpath, budget - 1)
                                case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                                    _: Tasty.Type.TypeLambda | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                                    _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                                    _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.RecThis |
                                    _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                                    _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                                    _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                                    _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                                    _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing |
                                    Tasty.Type.Any =>
                                    val subUnfolded = substituteRecThis(subParent, sub)
                                    isSubtype(subUnfolded, sup, classpath, budget - 1)

                        case _: Tasty.Type.TermRef | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                            _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                            _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.RecThis |
                            _: Tasty.Type.OrType | _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType |
                            _: Tasty.Type.ThisType | _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef |
                            _: Tasty.Type.Skolem | _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType |
                            _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds |
                            Tasty.Type.Any =>
                            Result.Success(NotSub)
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
      * Resolves SymbolId -> Symbol via classpath.symbol(id); parentTypes is a direct field.
      */
    private def isNamedSubNamed(
        subId: SymbolId,
        supId: SymbolId,
        classpath: Tasty.Classpath,
        budget: Int
    ): Result[TastyError, SubtypeVerdict] =
        val parents = classpath.symbol(subId) match
            case c: Tasty.Symbol.ClassLike => c.parentTypes
            case _: Tasty.Symbol.Method | _: Tasty.Symbol.Val | _: Tasty.Symbol.Var |
                _: Tasty.Symbol.Field | _: Tasty.Symbol.TypeAlias | _: Tasty.Symbol.OpaqueType |
                _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam | _: Tasty.Symbol.Parameter |
                _: Tasty.Symbol.Package | Maybe.Absent =>
                Chunk.empty[Tasty.Type]
        checkParents(parents, supId, classpath, budget)
    end isNamedSubNamed

    /** Check if `supId` appears directly in the given parent list or transitively in any parent's parents. */
    private def checkParents(
        parents: Chunk[Tasty.Type],
        supId: SymbolId,
        classpath: Tasty.Classpath,
        budget: Int
    ): Result[TastyError, SubtypeVerdict] =
        if parents.isEmpty then Result.Success(NotSub)
        else
            checkOneParent(parents.head, parents.tail, supId, classpath, budget)

    private def checkOneParent(
        parent: Tasty.Type,
        remaining: Chunk[Tasty.Type],
        supId: SymbolId,
        classpath: Tasty.Classpath,
        budget: Int
    ): Result[TastyError, SubtypeVerdict] =
        parent match
            case Tasty.Type.Named(parentId) =>
                if parentId == supId then Result.Success(Sub)
                else checkTransitiveAndTail(parentId, remaining, supId, classpath, budget)
            case Tasty.Type.Applied(Tasty.Type.Named(parentId), _) =>
                if parentId == supId then Result.Success(Sub)
                else checkTransitiveAndTail(parentId, remaining, supId, classpath, budget)
            case tr: Tasty.Type.TypeRef =>
                // A transitive parent can surface as a structural TypeRef rather than a resolved Named
                // (e.g. a parent named through an object prefix). Resolve it to its symbol by the same
                // fully-qualified-name path the annotation matcher uses, then walk it like a Named parent.
                classpath.findSymbol(classpath.typeFullNameString(tr)) match
                    case Maybe.Present(sym) =>
                        if sym.id == supId then Result.Success(Sub)
                        else checkTransitiveAndTail(sym.id, remaining, supId, classpath, budget)
                    case Maybe.Absent =>
                        // The TypeRef names a symbol outside the loaded closure (or a prefix that does not
                        // reduce to a dotted name, e.g. a `this`-qualified ref): it cannot be walked, so it
                        // cannot prove the relation. Continue with the remaining parents; if none establish
                        // Sub, the unresolved parent leaves the verdict Indeterminate, never a false NotSub.
                        checkParents(remaining, supId, classpath, budget) match
                            case f: Result.Failure[TastyError] @unchecked => f
                            case Result.Success(Sub)                      => Result.Success(Sub)
                            case Result.Success(_)                        => Result.Success(Indeterminate)
            case other =>
                // Unhandled parent shape: fail on first occurrence with a structured diagnostic.
                Result.Failure(TastyError.UnhandledSubtypingCase(
                    shape = shapeName(other),
                    lhs = other,
                    rhs = Tasty.Type.Named(supId),
                    file = "<unknown>"
                ))

    private def checkTransitiveAndTail(
        parentId: SymbolId,
        remaining: Chunk[Tasty.Type],
        supId: SymbolId,
        classpath: Tasty.Classpath,
        budget: Int
    ): Result[TastyError, SubtypeVerdict] =
        isNamedSubNamed(parentId, supId, classpath, budget) match
            case f: Result.Failure[TastyError] @unchecked => f
            case Result.Success(transitiveVerdict) =>
                if transitiveVerdict == Sub then Result.Success(Sub)
                else
                    checkParents(remaining, supId, classpath, budget) match
                        case f: Result.Failure[TastyError] @unchecked => f
                        case Result.Success(tailVerdict) =>
                            if tailVerdict == Sub then Result.Success(Sub)
                            else if transitiveVerdict == Indeterminate || tailVerdict == Indeterminate then
                                Result.Success(Indeterminate)
                            else Result.Success(NotSub)

    /** Variance-respecting argument check for Applied types.
      *
      * If the base type symbol is available, uses its typeParams to determine variance flags. Falls back to invariant when variance
      * information is not available.
      */
    private def checkAppliedArgs(
        subArgs: Chunk[Tasty.Type],
        supArgs: Chunk[Tasty.Type],
        baseSymOpt: Maybe[Tasty.Symbol],
        classpath: Tasty.Classpath,
        budget: Int
    ): Result[TastyError, SubtypeVerdict] =
        val typeParamsOpt: Maybe[Chunk[Tasty.Symbol]] = baseSymOpt.flatMap { baseSym =>
            val tps = (baseSym match
                case c: Tasty.Symbol.ClassLike   => c.typeParamIds
                case m: Tasty.Symbol.Method      => m.typeParamIds
                case ta: Tasty.Symbol.TypeAlias  => ta.typeParamIds
                case ot: Tasty.Symbol.OpaqueType => ot.typeParamIds
                case _: Tasty.Symbol.Val | _: Tasty.Symbol.Var | _: Tasty.Symbol.Field |
                    _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam | _: Tasty.Symbol.Parameter |
                    _: Tasty.Symbol.Package =>
                    Chunk.empty
            ).flatMap(id => classpath.symbol(id).toChunk)
            if tps.nonEmpty then Maybe(tps) else Maybe.Absent
        }
        checkArgPairs(subArgs, supArgs, typeParamsOpt, 0, classpath, budget)
    end checkAppliedArgs

    private def checkArgPairs(
        subArgs: Chunk[Tasty.Type],
        supArgs: Chunk[Tasty.Type],
        typeParamsOpt: Maybe[Chunk[Tasty.Symbol]],
        idx: Int,
        classpath: Tasty.Classpath,
        budget: Int
    ): Result[TastyError, SubtypeVerdict] =
        if idx >= subArgs.length then Result.Success(Sub)
        else
            val subArg = subArgs(idx)
            val supArg = supArgs(idx)
            val varianceOpt: Maybe[Int] = typeParamsOpt.flatMap { tps =>
                if idx < tps.length then
                    val tp = tps(idx)
                    if tp.flags.contains(Tasty.Flag.Covariant) then Maybe(1)
                    else if tp.flags.contains(Tasty.Flag.Contravariant) then Maybe(-1)
                    else Maybe(0) // invariant
                else Maybe.Absent
            }
            // Default to invariant (0) when variance info is absent
            val variance = varianceOpt.getOrElse(0)
            if variance == 1 then
                // covariant
                isSubtype(subArg, supArg, classpath, budget) match
                    case f: Result.Failure[TastyError] @unchecked => f
                    case Result.Success(Sub)    => checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, classpath, budget)
                    case Result.Success(NotSub) => Result.Success(NotSub)
                    case Result.Success(Indeterminate) =>
                        checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, classpath, budget) match
                            case f: Result.Failure[TastyError] @unchecked => f
                            case Result.Success(restVerdict) =>
                                Result.Success(if restVerdict == NotSub then NotSub else Indeterminate)
            else if variance == -1 then
                // contravariant
                isSubtype(supArg, subArg, classpath, budget) match
                    case f: Result.Failure[TastyError] @unchecked => f
                    case Result.Success(Sub)    => checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, classpath, budget)
                    case Result.Success(NotSub) => Result.Success(NotSub)
                    case Result.Success(Indeterminate) =>
                        checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, classpath, budget) match
                            case f: Result.Failure[TastyError] @unchecked => f
                            case Result.Success(restVerdict) =>
                                Result.Success(if restVerdict == NotSub then NotSub else Indeterminate)
            else
                // invariant: both directions
                isSubtype(subArg, supArg, classpath, budget) match
                    case f: Result.Failure[TastyError] @unchecked => f
                    case Result.Success(fwdVerdict) =>
                        isSubtype(supArg, subArg, classpath, budget) match
                            case f: Result.Failure[TastyError] @unchecked => f
                            case Result.Success(bwdVerdict) =>
                                val argVerdict = combineAnd(fwdVerdict, bwdVerdict)
                                argVerdict match
                                    case Sub =>
                                        checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, classpath, budget)
                                    case NotSub =>
                                        Result.Success(NotSub)
                                    case Indeterminate =>
                                        checkArgPairs(subArgs, supArgs, typeParamsOpt, idx + 1, classpath, budget) match
                                            case f: Result.Failure[TastyError] @unchecked => f
                                            case Result.Success(restVerdict) =>
                                                Result.Success(if restVerdict == NotSub then NotSub else Indeterminate)
                                end match
            end if

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
        case _: Tasty.Type.Named | _: Tasty.Type.TypeLambda | _: Tasty.Type.Function |
            _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple | _: Tasty.Type.ByName |
            _: Tasty.Type.Repeated | _: Tasty.Type.Array | _: Tasty.Type.Refinement |
            _: Tasty.Type.RecThis | _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType |
            _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard |
            _: Tasty.Type.Skolem | _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType |
            _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
            t.getClass.getSimpleName

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
                val pos1 = idx1.get(s1)
                val pos2 = idx2.get(s2)
                if pos1.isDefined && pos2.isDefined then pos1.get == pos2.get
                else if pos1.isEmpty && pos2.isEmpty then s1 == s2
                else false
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
            // Carve-out: tuple cross-product over (Tasty.Type, Tasty.Type); cross-constructor pairs are not alpha-equivalent
            case _ =>
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
