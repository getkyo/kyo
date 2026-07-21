// Phase E Cleanup 3, cast budget:
// - Primitive-lookup: collapsed from 9 per-type casts to 1 boundary cast in summonPrimitiveFromExpr.
// - buildDirect invariance-dodge: eliminated by changing buildMaybe/Chunk/Field/Tilde return types to FromExpr[A].
// - Genuinely unavoidable reflection-seam casts:
//     * Outermost-return of buildMaybe/Chunk/Field/Tilde: FromExpr[t] → FromExpr[A] (type variable mismatch).
//     * Class.forName / ctor.newInstance / field.get / method.invoke: return Object at JVM boundary.
//     * Quoted-list invariance: List[Any] ↔ List[FromExpr[Any]] at splice boundaries.
//
// Phase F.5 recursion guard, all added casts are the SAME `FromExpr`-invariance category as the
// existing "Quoted-list invariance" / "type variable mismatch" channel above (no new category):
//   * deriveFor cycle arm + deriveRecursive return: `Ref(fe$k).asExpr` carries the singleton type
//     `fe$k.type`; the emitted `lazy val` is typed `FromExpr[<type-ctor>[Any]]`. `FromExpr` is
//     invariant, so the phantom type-argument refinement (`Query[Any]` ↦ `Query[T]`) needs one
//     controlled cast. The matcher logic is phantom in `A` (every `unapply` walks `Term` trees
//     structurally; `A` is load-bearing only in `instantiate[A]`'s final cast).
//   * deriveMaybe/deriveChunk/deriveTilde erase the inner FromExpr to `FromExpr[Any]` (so a
//     recursion-guard sibling `lazy val` typed at the saturated form splices in), same erasure
//     channel as deriveProduct's `fieldFEs: List[FromExpr[Any]]`; the matcher result is widened
//     back to the field type at the `Option` boundary.
//   * deriveOr's branch list and buildDirect's union arm: `List(...).asInstanceOf[List[FromExpr[A]]]`
//     identical to deriveSum's existing childFEs cast.
//   * The `buildDirect` test-path guard (DirectCtx / LazyFromExpr): the cycle map
//     `DirectCtx.inProgress` is keyed by `String`, so its values are `LazyFromExpr[?]`; the
//     re-encounter arm narrows the retrieved placeholder back to `FromExpr[A]`. `LazyFromExpr[A]` is
//     itself a typed `FromExpr[A]` whose `delegate` field is `FromExpr[A]`; `delegate` is a plain
//     `var`, macro-expansion-local single-threaded state, not concurrent shared state, so
//     feedback_atomic_not_var (which targets concurrent mutable state) does not apply.
//   * `anyConstantFromExpr` (the `FromExpr[Any]` for `Any`-positioned AST leaves) is cast to
//     `FromExpr[A]` where `A =:= Any` is established structurally, a no-op refinement.
//   `DeriveCtx` / `DirectCtx` themselves are STRONGLY typed (parameterised on the singleton `Q`);
//   the ctx fields hold real `q.reflect.Ref` / `q.reflect.ValDef`, zero `Any` fields, zero casts
//   on ctx contents.
//   These are not violations of feedback_no_casts; the JVM erases generics at reflection call sites.
package kyo.internal

import kyo.*
import kyo.Record.~
import scala.annotation.tailrec
import scala.quoted.*

/** Macro implementation for `kyo.FromExpr.derived[A]`.
  *
  * Emits a `scala.quoted.FromExpr[A]` instance that knows how to read values of type `A` out of quoted expressions. The derivation handles:
  *
  *   - Primitive types (`Int`, `Long`, `String`, `Boolean`, `Double`, `Float`, `Char`, `Byte`, `Short`), delegates to the stdlib
  *     `FromExpr` instances by summoning them at the use site.
  *   - `Maybe[A]`, matches `Maybe.empty`, `Absent`, `Maybe(...)`, `Present(...)` (via runtime reflection on the term tree).
  *   - `Chunk[A]`, matches `Chunk.empty`, `Chunk(args*)` (via runtime reflection on the term tree).
  *   - `Field[N, V]`, matches a `Field.apply(...)` call and reconstructs the field from the lifted name + summoned `Tag[V]`.
  *   - `N ~ V` (tilde-tagged value, a phantom type), delegates to `FromExpr[V]`.
  *   - `SqlSchema[A]`, delegates to the `given FromExpr[SqlSchema[A]]` in `object SqlSchema` (kyo-sql), located via `Expr.summon`.
  *   - `Record[F]`, emits a placeholder FromExpr (Record construction sites need backend-specific lifting).
  *   - Case classes, emits a FromExpr that walks the constructor tree and lifts each argument via a recursively derived FromExpr.
  *   - Sealed traits / enums, emits a FromExpr that tries each child case in turn.
  *
  * Implementation strategy: rather than use Scala's quoted pattern matching (which suffers from type-capture issues when the inner type
  * variable is reused across quote boundaries), this macro emits FromExpr instances whose `unapply` methods walk the `Term` tree directly
  * via `quotes.reflect.*`. This sidesteps the `t` vs `t²` capture problems entirely and gives us a stable runtime shape.
  *
  * This macro is intentionally **independent** of `FocusMacro`, it duplicates the Mirror-walk pattern instead of refactoring the 1500-line
  * `FocusMacro.derivedImpl` (Phase 6.6 prep doc flagged that refactor as too risky for one phase; the shared helper can be extracted
  * later).
  */
object FromExprDerived:

    // ---------------------------------------------------------------------
    // Phase F.5, recursive-ADT derivation guard
    // ---------------------------------------------------------------------

    /** Per-`FromExpr.derived` accumulator that breaks recursive-ADT derivation cycles (`Query` ↔ `Term`).
      *
      * Created once by `derivedImpl`, threaded through every `deriveFor` call. Not thread-shared: one instance per macro expansion.
      *
      * Strongly typed on the singleton `Quotes` instance `Q` (the proven `SqlStaticMacro.R` pattern, see `SqlStatic.scala`) so `refs` /
      * `bindings` hold real `q.reflect.Ref` / `q.reflect.ValDef`, no `Any` fields, no `asInstanceOf` on the ctx contents.
      */
    final private class DeriveCtx[Q <: Quotes & Singleton](using val q: Q):
        import q.reflect.*

        /** Type-constructor-key → the `lazy val`'s `Ref`, for cycle-closers already being derived. A type is "in progress" while its body
          * is being built; a nested `deriveFor` for the same key returns `refs(key)` instead of recursing.
          */
        val refs: scala.collection.mutable.LinkedHashMap[String, Ref] =
            scala.collection.mutable.LinkedHashMap.empty

        /** `lazy val` bindings emitted so far, in discovery order, assembled into the final `Block`. */
        val bindings: scala.collection.mutable.ListBuffer[ValDef] =
            scala.collection.mutable.ListBuffer.empty
    end DeriveCtx

    /** Type-constructor cycle key. Collapses `Query[T]`, `Query[?]`, `Query[Any]` to one key (`kyo.SqlAst.Query`) so the cycle is detected
      * regardless of the phantom type argument.
      */
    private def cycleKey(using q: Quotes)(tpe: q.reflect.TypeRepr): String =
        import q.reflect.*
        tpe match
            case AppliedType(c, _) => c.typeSymbol.fullName
            case other             => other.typeSymbol.fullName
    end cycleKey

    /** Phase F.5, saturates a type so it has no free type parameters / wildcards: applies `Any` to every parameter of an unapplied type
      * constructor, and replaces wildcard / abstract-parameter arguments of an applied type with `Any`.
      *
      * The recursion guard reaches AST nodes (`Literal[A]`, `ValuesFrom[T, F]`, …) at unapplied or wildcard positions when the parent
      * sealed type was itself reached at `Query[?]`. `FromExpr.derived` cannot derive a `FromExpr` for a free type parameter, so each such
      * position is collapsed to `Any`. The matcher logic is phantom in those parameters (every `unapply` walks `Term` trees structurally),
      * so the `Any`-saturated form is matcher-equivalent.
      */
    private def applyAnyToFreeParams(using q: Quotes)(tpe: q.reflect.TypeRepr): q.reflect.TypeRepr =
        import q.reflect.*
        given CanEqual[q.reflect.Symbol, q.reflect.Symbol] = CanEqual.derived
        val anyTpe                                         = TypeRepr.of[Any]
        // Non-concrete = a free type parameter / abstract type member / wildcard-bounds. Type *aliases*
        // (`Predef.String`) and applied / structural types are concrete, only `isAbstractType` (or a
        // raw `TypeBounds`) marks a position the recursion guard must collapse to `Any`. (Checking
        // `isClassDef` here wrongly rejected `Predef.String`, an alias, collapsing `SqlSchema[String]`
        // to `SqlSchema[Any]`.)
        def isConcrete(t: TypeRepr): Boolean =
            t match
                case _: TypeBounds => false
                case ref: TypeRef  => !ref.typeSymbol.isAbstractType
                case _             => true
        // Upper bound declared on a type-parameter symbol, read from its `TypeDef` tree (avoids the
        // `@experimental` `Symbol.info`). Returns `Any` when unbounded.
        def declaredUpperBound(sym: Symbol): TypeRepr =
            sym.tree match
                case TypeDef(_, bt: TypeBoundsTree) => bt.hi.tpe
                case _                              => anyTpe
        // Saturation target for a non-concrete position: the position's declared upper bound when it
        // is narrower than `Any` (so a bounded phantom param like `Column.N <: String` collapses to
        // `String`, keeping the saturated type well-bounded), otherwise `Any`.
        def saturate(t: TypeRepr): TypeRepr =
            val hi =
                t match
                    case TypeBounds(_, h)                           => h
                    case ref: TypeRef if ref.typeSymbol.isTypeParam => declaredUpperBound(ref.typeSymbol)
                    case _                                          => anyTpe
            if hi =:= anyTpe || !isConcrete(hi) then anyTpe else hi
        end saturate
        // A class's OWN declared type parameters, `typeMembers` also surfaces inherited type members
        // (e.g. `String <: Comparable[T]` exposes `T`), which must NOT be treated as the class's params.
        def ownTypeParams(sym: Symbol): List[Symbol] =
            sym.typeMembers.filter(m => m.isTypeParam && m.owner == sym)
        // Phase G.5: saturate a non-concrete argument at applied position `i` of constructor `c`. A bare
        // wildcard `?` is `TypeBounds(Nothing, Any)` and carries no knowledge of `c`'s declared parameter
        // bound, so `saturate` alone would collapse `SetSpec[?, ?]`'s `N <: String` to `Any`. Fall back to
        // the constructor parameter's own declared upper bound when it is narrower and concrete (keeping
        // `SetSpec[String, Any]`, `Column[String, Any]`, well-bounded).
        def saturateArg(c: TypeRepr, idx: Int, a: TypeRepr): TypeRepr =
            val direct = saturate(a)
            if !(direct =:= anyTpe) then direct
            else
                val params = ownTypeParams(c.typeSymbol)
                if idx < params.length then
                    val pb = declaredUpperBound(params(idx))
                    if !(pb =:= anyTpe) && isConcrete(pb) then pb else anyTpe
                else anyTpe
                end if
            end if
        end saturateArg
        tpe match
            case AppliedType(c, args) =>
                AppliedType(c, args.zipWithIndex.map((a, i) => if isConcrete(a) then a else saturateArg(c, i, a)))
            case ref: TypeRef if ref.typeSymbol.isClassDef =>
                val params = ownTypeParams(ref.typeSymbol)
                if params.isEmpty then ref
                else AppliedType(ref, params.map(p => saturate(p.typeRef)))
            case _: TypeBounds => anyTpe
            case other         => other
        end match
    end applyAnyToFreeParams

    /** Per-`buildDirect` accumulator, the test-path twin of `DeriveCtx`. Holds runtime `FromExpr` values (not `Expr`s), so it does not
      * need a `Quotes` type parameter.
      */
    final private class DirectCtx:
        val inProgress: scala.collection.mutable.Map[String, LazyFromExpr[?]] =
            scala.collection.mutable.Map.empty
    end DirectCtx

    /** A `FromExpr` whose delegate is filled in after construction, closing value-level recursion in the `buildDirect` test path.
      * `delegate` starts as a fail-soft `None`-returning instance and is replaced with the real one immediately after the recursive build
      * completes, macro-expansion-local single-threaded state.
      */
    final private class LazyFromExpr[A] extends scala.quoted.FromExpr[A]:
        var delegate: scala.quoted.FromExpr[A] =
            new scala.quoted.FromExpr[A]:
                def unapply(x: Expr[A])(using Quotes): Option[A] = None
        def unapply(x: Expr[A])(using Quotes): Option[A] = delegate.unapply(x)
    end LazyFromExpr

    /** Phase F.5 / Phase G, `FromExpr[Any]` for `Any`-positioned AST leaves (`Literal[Any].value`). An arbitrary `Any` is not liftable,
      * but at a concrete static-query call site such a leaf is either a compile-time constant literal or one of the SQL bind-value types
      * (`java.time.LocalDate` / `LocalDateTime` / `LocalTime`, `kyo.Instant`, `kyo.Span[Byte]`) constructed by a known factory call. This
      * matches both shapes and reconstructs the value. Public so the emitted production-path quote can reference it.
      */
    val anyConstantFromExpr: scala.quoted.FromExpr[Any] =
        new scala.quoted.FromExpr[Any]:
            def unapply(x: Expr[Any])(using q: Quotes): Option[Any] =
                import q.reflect.*
                given CanEqual[String, String] = CanEqual.derived
                def unwrap(t: Term): Term =
                    t match
                        case Inlined(_, _, inner) => unwrap(inner)
                        case Block(_, inner)      => unwrap(inner)
                        case Typed(inner, _)      => unwrap(inner)
                        case other                => other
                // Strips a constant literal to its `Int` value (for the `*.of(...)` factory args).
                def intArg(t: Term): Option[Int] =
                    unwrap(t) match
                        case Literal(IntConstant(n)) => Some(n)
                        case _                       => None
                def longArg(t: Term): Option[Long] =
                    unwrap(t) match
                        case Literal(LongConstant(n)) => Some(n)
                        case Literal(IntConstant(n))  => Some(n.toLong)
                        case _                        => None
                // Callee name of an `Apply` chain, normalised (`$package` / `$` segments stripped) and
                // its innermost value-argument list, ignoring wrappers + type-arg / implicit (e.g.
                // `ClassTag`) argument lists.
                def calleeOf(t: Term): (String, List[Term]) =
                    def loop(h: Term, firstArgs: List[Term]): (Term, List[Term]) =
                        h match
                            case Apply(fun, args)  => loop(fun, args)
                            case TypeApply(fun, _) => loop(fun, firstArgs)
                            case other             => (other, firstArgs)
                    val (head, args) = loop(t, Nil)
                    val name =
                        try head.symbol.fullName.replace("$package", "").replace("$", "")
                        catch case _: Throwable => ""
                    (name, args)
                end calleeOf
                def endsWith(s: String, suffix: String): Boolean = s == suffix || s.endsWith("." + suffix)
                unwrap(x.asTerm) match
                    case Literal(c) =>
                        c match
                            case UnitConstant() | NullConstant() | ClassOfConstant(_) => None
                            case _                                                    => Some(c.value)
                    case app @ Apply(_, _) =>
                        val (name, args) = calleeOf(app)
                        if endsWith(name, "LocalDate.of") && args.length == 3 then
                            for
                                y <- intArg(args(0)); m <- intArg(args(1)); d <- intArg(args(2))
                            yield java.time.LocalDate.of(y, m, d)
                        else if endsWith(name, "LocalDateTime.of") && args.length == 7 then
                            for
                                y <- intArg(args(0)); mo <- intArg(args(1)); d <- intArg(args(2))
                                h <- intArg(args(3)); mi <- intArg(args(4)); s <- intArg(args(5))
                                n <- intArg(args(6))
                            yield java.time.LocalDateTime.of(y, mo, d, h, mi, s, n)
                        else if endsWith(name, "LocalTime.of") && args.length == 4 then
                            for
                                h <- intArg(args(0)); m <- intArg(args(1)); s <- intArg(args(2)); n <- intArg(args(3))
                            yield java.time.LocalTime.of(h, m, s, n)
                        else if endsWith(name, "Instant.fromJava") && args.length == 1 then
                            unwrap(args(0)) match
                                case ofApp @ Apply(_, _) =>
                                    val (ofName, ofArgs) = calleeOf(ofApp)
                                    if endsWith(ofName, "Instant.ofEpochMilli") && ofArgs.length == 1 then
                                        longArg(ofArgs(0)).map(ms => kyo.Instant.fromJava(java.time.Instant.ofEpochMilli(ms)))
                                    else None
                                case _ => None
                        else if endsWith(name, "Span.from") && args.length == 1 then
                            // `Span.from(Array(b*))`, `calleeOf` peels the `Array.apply` chain (including
                            // its implicit `ClassTag` argument list) down to the innermost vararg list.
                            unwrap(args(0)) match
                                case arrApp @ Apply(_, _) =>
                                    val (_, arrArgs) = calleeOf(arrApp)
                                    val elems =
                                        arrArgs match
                                            case List(Typed(Repeated(es, _), _)) => es
                                            case List(Repeated(es, _))           => es
                                            case other                           => other
                                    val bytes = elems.map { e =>
                                        unwrap(e) match
                                            case Literal(ByteConstant(b)) => Some(b)
                                            case Literal(IntConstant(n))  => Some(n.toByte)
                                            case _                        => None
                                    }
                                    if bytes.nonEmpty && bytes.forall(_.isDefined) then
                                        Some(kyo.Span.from(bytes.flatMap(_.toList).toArray))
                                    else None
                                case _ => None
                        else None
                        end if
                    case _ => None
                end match
            end unapply
    end anyConstantFromExpr

    def derivedImpl[A: Type](using q: Quotes): Expr[scala.quoted.FromExpr[A]] =
        import q.reflect.*
        val ctx: DeriveCtx[q.type] = new DeriveCtx[q.type]
        val rootExpr               = deriveFor[A, q.type](ctx)
        if ctx.bindings.isEmpty then
            rootExpr // no recursive type encountered, unchanged single-quote path
        else
            Block(ctx.bindings.toList, rootExpr.asTerm).asExprOf[scala.quoted.FromExpr[A]]
        end if
    end derivedImpl

    /** Test-only: applies the derived FromExpr to a captured `Expr[A]` at macro-expansion time and returns the lifted `Option[A]` as a
      * literal expression. This sidesteps the production path's `Expr[FromExpr[A]]` packaging, useful for unit testing the matcher logic.
      *
      * Implementation: we emit the FromExpr's code via `derivedImpl`, then wrap a `scala.quoted.staging`-style compile-time evaluation
      * using `Expr.summon` on the already-derived given. Because that's brittle, we instead emit a self-contained `unapply` call inside a
      * quote and rely on the result being computed at *runtime* of the test, but with the `value` parameter being a literal at that time.
      *
      * Concretely: the test macro emits `{ val fe: FromExpr[A] = $derived ; fe.unapply(reifiedExpr).isDefined }`. The trick is we have to
      * re-quote the captured `Expr[A]` into a literal `Expr[Expr[A]]` so it's available at the runtime of the test. We do this by lifting
      * the value (which is itself a code tree) into a quoted expression of an expression, i.e., a nested quote.
      */
    def applyMatchedImpl[A: Type](value: Expr[A])(using q: Quotes): Expr[Boolean] =
        // Build the FromExpr as a plain Scala value at macro-expansion time (no Expr wrapping), then apply its unapply against the
        // captured `value` Expr. Emit the result as a literal Boolean.
        val directFE: scala.quoted.FromExpr[A] = buildDirect[A](new DirectCtx)
        val result                             = directFE.unapply(value)
        Expr(result.isDefined)
    end applyMatchedImpl

    /** Same idea as `applyMatchedImpl` but emits the `Option[A].toString` instead of just isDefined. Useful when tests want to verify the
      * lifted *value*.
      */
    def applyReprImpl[A: Type](value: Expr[A])(using q: Quotes): Expr[String] =
        val directFE: scala.quoted.FromExpr[A] = buildDirect[A](new DirectCtx)
        val result                             = directFE.unapply(value)
        Expr(result.toString)
    end applyReprImpl

    /** Test-only: lifts a value via its derived `FromExpr`, then, when the reconstruction yields a value carrying a `kyo.Record`
      * (directly, or as a product field, e.g. `Table.columns`), emits that record's field names. Nested records (e.g. the alias-keyed
      * wrapper a `buildColumns` record carries) are flattened: each level's sorted keys are joined by `;`. Emits `"<none>"` when `unapply`
      * returns `None` and `"<no-record>"` when the reconstruction has no `Record`. Lets a test assert the *structure* of a reconstructed
      * `Record`, its `toString` is an opaque identity hash.
      */
    def applyRecordFieldNamesImpl[A: Type](value: Expr[A])(using q: Quotes): Expr[String] =
        val directFE: scala.quoted.FromExpr[A] = buildDirect[A](new DirectCtx)
        def firstRecord(v: Any): Option[kyo.Record[?]] =
            v match
                case r: kyo.Record[?] => Some(r)
                case p: Product       => p.productIterator.flatMap(e => firstRecord(e).iterator).nextOption()
                case _                => None
        def levels(rec: kyo.Record[?]): List[String] =
            val entries = rec.dict.toMap
            val here    = entries.keys.toList.sorted.mkString(",")
            // Descend into a nested record value when this level wraps exactly one (the alias wrapper).
            entries.values.toList match
                case (nested: kyo.Record[?]) :: Nil => here :: levels(nested)
                case _                              => List(here)
        end levels
        directFE.unapply(value) match
            case None => Expr("<none>")
            case Some(value) =>
                firstRecord(value) match
                    case Some(rec) => Expr(levels(rec).mkString(";"))
                    case None      => Expr("<no-record>")
        end match
    end applyRecordFieldNamesImpl

    /** Builds a `scala.quoted.FromExpr[A]` as a plain Scala value at macro-expansion time. Mirrors `deriveFor` but produces a runtime value
      * directly instead of an `Expr` of one. Used for the test-only `applyMatchedImpl` / `applyReprImpl` entry points.
      */
    private def buildDirect[A: Type](ctx: DirectCtx)(using q: Quotes): scala.quoted.FromExpr[A] =
        import q.reflect.*
        val tpe = TypeRepr.of[A].dealias
        val sym = tpe.typeSymbol
        // Phase F.5: as in `deriveFor`, the cycle guard must NOT intercept `Maybe` / `Chunk` (themselves
        // `sealed`), they have dedicated arms. Check the special-type predicates first.
        val isSpecial =
            isPrimitive[A] || isMaybe(tpe) || isChunk(tpe) || isField(tpe) || isTilde(tpe) ||
                isSqlSchema(tpe) || isRecord(tpe) || isColumnProjection(tpe)
        // A `case object` carries both `Case` and `Module` flags. Route it to `deriveSingleton` rather than
        // `deriveProduct` (the latter walks case-field mirrors, which yield no useful matcher for a singleton).
        val isProductOrSum =
            !isSpecial && sym.isClassDef && !sym.flags.is(Flags.Module) &&
                (sym.flags.is(Flags.Sealed) || sym.flags.is(Flags.Enum) || sym.flags.is(Flags.Case))

        if isProductOrSum then
            // Phase F.5 cycle guard (test path, §4 option 4a). On re-encounter of an in-progress
            // recursive product/sum type, return the LazyFromExpr placeholder whose `delegate` is
            // populated by the time any `unapply` runs.
            val key = cycleKey(tpe)
            ctx.inProgress.get(key) match
                case Some(lazyFE) =>
                    // Cycle: A is already being derived. Reuse its placeholder.
                    lazyFE.asInstanceOf[scala.quoted.FromExpr[A]]
                case None =>
                    val placeholder = new LazyFromExpr[A]
                    ctx.inProgress.update(key, placeholder)
                    val real =
                        if sym.flags.is(Flags.Case) then buildProduct[A](ctx, tpe)
                        else buildSum[A](ctx, tpe)
                    placeholder.delegate = real
                    placeholder
            end match
        else if isPrimitive[A] then
            // Summon stdlib FromExpr[A] via Expr.summon. This requires us to evaluate the summoned Expr, but stdlib FromExprs are
            // `given` instances, so `Expr.summon` returns the actual instance reference wrapped in Expr. We can extract via .value if
            // the instance is a stable identifier; otherwise we fall back to summoning the stdlib FromExpr directly via TypeRepr lookup.
            summonPrimitiveFromExpr[A]
        else if tpe =:= TypeRepr.of[Any] then
            // Phase F.5: `Any`-positioned AST leaves, lift compile-time constant literals (see deriveFor).
            anyConstantFromExpr.asInstanceOf[scala.quoted.FromExpr[A]]
        else if isMaybe(tpe) then
            buildMaybe[A](ctx, tpe)
        else if isChunk(tpe) then
            buildChunk[A](ctx, tpe)
        else if isField(tpe) then
            buildField[A](tpe)
        else if isTilde(tpe) then
            buildTilde[A](ctx, tpe)
        else if isSqlSchema(tpe) then
            buildSqlSchema[A](tpe)
        else if isColumnProjection(tpe) then
            buildSummonedFromExpr[A]
        else if isRecord(tpe) then
            // Phase F.5: the test path mirrors `deriveRecord`, summon a `given FromExpr[Record[F]]`
            // (e.g. kyo-sql's `RecordFromExpr.fromExprRecord`, when imported at the macro use-site) and
            // delegate to it. `applyMatchedImpl` / `applyReprImpl` invoke this `unapply` at macro-
            // expansion time, so `Expr.summon` resolves against the use-site implicit scope; the
            // summoned given is then retrieved via JVM reflection on its defining module (the same
            // seam `buildSqlSchema` uses). Falls back to `None` (pre-Phase-F behaviour) when no given
            // is in scope.
            buildSummonedFromExpr[A]
        else if tpe.typeSymbol.flags.is(Flags.Module) then
            buildSingleton[A](tpe)
        else if tpe.typeSymbol.flags.is(Flags.Enum) then
            buildSingleton[A](tpe)
        else
            tpe match
                case OrType(left, right) =>
                    // Phase F.5: union-typed AST field, try each branch in turn (see deriveOr).
                    val leftFE = applyAnyToFreeParams(left).asType match
                        case '[l] => buildDirect[l](ctx).asInstanceOf[scala.quoted.FromExpr[A]]
                    val rightFE = applyAnyToFreeParams(right).asType match
                        case '[r] => buildDirect[r](ctx).asInstanceOf[scala.quoted.FromExpr[A]]
                    new scala.quoted.FromExpr[A]:
                        def unapply(x: Expr[A])(using Quotes): Option[A] =
                            leftFE.unapply(x).orElse(rightFE.unapply(x))
                case AndType(_, _) =>
                    // The singleton-narrowing introduced `String & Singleton` (and similar) at field
                    // sites, the `Singleton` half is a phantom marker with no runtime content. Strip
                    // phantom marker traits from the AndType and recurse on the substantive operand.
                    // FromExpr is invariant, so the recursion cast at the outer boundary is sound.
                    def stripPhantom(t: TypeRepr): TypeRepr =
                        t match
                            case AndType(l, r) =>
                                val ls = stripPhantom(l)
                                val rs = stripPhantom(r)
                                if isPhantomMarker(ls) then rs
                                else if isPhantomMarker(rs) then ls
                                else AndType(ls, rs)
                            case other => other
                    val stripped = stripPhantom(tpe)
                    if stripped =:= tpe then
                        report.errorAndAbort(s"buildDirect: cannot build FromExpr for ${tpe.show}")
                    else
                        stripped.asType match
                            case '[t] => buildDirect[t](ctx).asInstanceOf[scala.quoted.FromExpr[A]]
                    end if
                case _ =>
                    report.errorAndAbort(s"buildDirect: cannot build FromExpr for ${tpe.show}")
        end if
    end buildDirect

    private def summonPrimitiveFromExpr[A: Type](using q: Quotes): scala.quoted.FromExpr[A] =
        import q.reflect.*
        val tpe = TypeRepr.of[A].dealias
        // Select the correct stdlib FromExpr. Type.of[A] match in a non-inline context binds T <: Primitive
        // rather than T =:= Primitive, so one cast at the outermost return is unavoidable. The 9 original
        // per-primitive casts collapse to this single boundary cast.
        val fe: scala.quoted.FromExpr[?] =
            if tpe =:= TypeRepr.of[Int] then scala.quoted.FromExpr.IntFromExpr
            else if tpe =:= TypeRepr.of[Long] then scala.quoted.FromExpr.LongFromExpr
            else if tpe =:= TypeRepr.of[String] then scala.quoted.FromExpr.StringFromExpr
            else if tpe =:= TypeRepr.of[Boolean] then scala.quoted.FromExpr.BooleanFromExpr
            else if tpe =:= TypeRepr.of[Double] then scala.quoted.FromExpr.DoubleFromExpr
            else if tpe =:= TypeRepr.of[Float] then scala.quoted.FromExpr.FloatFromExpr
            else if tpe =:= TypeRepr.of[Char] then scala.quoted.FromExpr.CharFromExpr
            else if tpe =:= TypeRepr.of[Byte] then scala.quoted.FromExpr.ByteFromExpr
            else if tpe =:= TypeRepr.of[Short] then scala.quoted.FromExpr.ShortFromExpr
            else report.errorAndAbort(s"No stdlib FromExpr for ${tpe.show}")
        fe.asInstanceOf[scala.quoted.FromExpr[A]]
    end summonPrimitiveFromExpr

    private def buildMaybe[A: Type](using q: Quotes)(ctx: DirectCtx, tpe: q.reflect.TypeRepr): scala.quoted.FromExpr[A] =
        import q.reflect.*
        tpe match
            case AppliedType(_, List(inner)) =>
                inner.asType match
                    case '[t] =>
                        val innerFE = buildDirect[t](ctx)
                        (new scala.quoted.FromExpr[Maybe[t]]:
                            def unapply(x: Expr[Maybe[t]])(using qctx: Quotes): Option[Maybe[t]] =
                                import qctx.reflect.*
                                given CanEqual[String, String] = CanEqual.derived
                                def unwrap(t: Term): Term =
                                    t match
                                        case Inlined(_, _, inner) => unwrap(inner)
                                        case Block(Nil, inner)    => unwrap(inner)
                                        case Typed(inner, _)      => unwrap(inner)
                                        case other                => other
                                val term = unwrap(x.asTerm)
                                def head(t: Term): Term =
                                    t match
                                        case Apply(fun, _)     => head(fun)
                                        case TypeApply(fun, _) => head(fun)
                                        case _                 => t
                                val callee = head(term)
                                val sname =
                                    try callee.symbol.fullName
                                    catch
                                        case _: Throwable => ""
                                // Normalize the fullName: strip `$package` markers and `$` (companion/module suffixes) so that names like
                                // `kyo.Maybe$package$.Maybe$.apply` reduce to `kyo.Maybe.Maybe.apply`, which lets us match by suffix.
                                val norm                                         = sname.replace("$package", "").replace("$", "")
                                def endsWith(s: String, suffix: String): Boolean = s == suffix || s.endsWith("." + suffix)
                                if endsWith(norm, "Maybe.empty") then Some(Maybe.empty[t])
                                else if endsWith(norm, "Maybe.Absent") || endsWith(norm, ".Absent") then Some(Maybe.empty[t])
                                else
                                    term match
                                        case Apply(_, List(arg)) if endsWith(norm, "Maybe.apply") =>
                                            innerFE.unapply(arg.asExprOf[t]).map(Maybe.apply[t])
                                        case Apply(_, List(arg))
                                            if endsWith(norm, "Maybe.Present.apply") || endsWith(norm, ".Present.apply") =>
                                            innerFE.unapply(arg.asExprOf[t]).map(Maybe.Present.apply[t])
                                        case _ => None
                                end if
                            end unapply
                        ).asInstanceOf[scala.quoted.FromExpr[A]]
                    case _ => report.errorAndAbort(s"Cannot extract inner type of Maybe: ${tpe.show}")
            case _ => report.errorAndAbort(s"Maybe must be applied: ${tpe.show}")
        end match
    end buildMaybe

    private def buildChunk[A: Type](using q: Quotes)(ctx: DirectCtx, tpe: q.reflect.TypeRepr): scala.quoted.FromExpr[A] =
        import q.reflect.*
        tpe match
            case AppliedType(_, List(rawInner)) =>
                // Phase G.5: saturate a plain case-class element's free params / wildcards (see `deriveChunk`).
                val rawInnerDealiased = rawInner.dealias
                // A *plain* case-class element (no special / custom-given arm) needs saturation before its
                // FromExpr is derived; the special arms handle their own wildcards.
                val isPlainCaseElem =
                    !isMaybe(rawInnerDealiased) && !isChunk(rawInnerDealiased) && !isField(rawInnerDealiased) &&
                        !isTilde(rawInnerDealiased) && !isSqlSchema(rawInnerDealiased) &&
                        !isRecord(rawInnerDealiased) && !isColumnProjection(rawInnerDealiased) &&
                        rawInnerDealiased.typeSymbol.isClassDef && rawInnerDealiased.typeSymbol.flags.is(Flags.Case)
                val inner = if isPlainCaseElem then applyAnyToFreeParams(rawInner) else rawInner
                inner.asType match
                    case '[t] =>
                        val innerFE = buildDirect[t](ctx)
                        (new scala.quoted.FromExpr[Chunk[t]]:
                            def unapply(x: Expr[Chunk[t]])(using qctx: Quotes): Option[Chunk[t]] =
                                import qctx.reflect.*
                                given CanEqual[String, String] = CanEqual.derived
                                // `resolveBindings` substituted block-local `val`s, descend past any `Block`.
                                def unwrap(t: Term): Term =
                                    t match
                                        case Inlined(_, _, inner) => unwrap(inner)
                                        case Block(_, inner)      => unwrap(inner)
                                        case Typed(inner, _)      => unwrap(inner)
                                        case other                => other
                                val term = unwrap(kyo.internal.FromExprDerived.resolveBindings(x.asTerm))
                                def head(t: Term): Term =
                                    t match
                                        case Apply(fun, _)     => head(fun)
                                        case TypeApply(fun, _) => head(fun)
                                        case _                 => t
                                val callee = head(term)
                                val sname =
                                    try callee.symbol.fullName
                                    catch
                                        case _: Throwable => ""
                                // Chunk.apply is inherited from IterableFactory, so callee.symbol.fullName may be `scala.collection.IterableFactory.apply`.
                                // Instead, match on the Select's qualifier (the Chunk module reference).
                                def isChunkRef(t: Term): Boolean =
                                    t match
                                        case Inlined(_, _, inner) => isChunkRef(inner)
                                        case Typed(inner, _)      => isChunkRef(inner)
                                        case TypeApply(fun, _)    => isChunkRef(fun)
                                        case Select(qualifier, _) => isChunkRef(qualifier)
                                        case Ident(name)          => name == "Chunk"
                                        case _                    => false
                                def endsWith(s: String, suffix: String): Boolean = s == suffix || s.endsWith("." + suffix)
                                val norm                                         = sname.replace("$package", "").replace("$", "")
                                if endsWith(norm, "Chunk.empty") then Some(Chunk.empty[t])
                                else
                                    // Phase H.5, also handles `<varargs>.map(<closure>)` (see `deriveChunk`):
                                    // beta-reduces the `.map` closure against each `Repeated` element.
                                    def extractElems(t: Term): Option[List[Term]] =
                                        unwrap(t) match
                                            case Typed(Repeated(elems, _), _) => Some(elems)
                                            case Repeated(elems, _)           => Some(elems)
                                            case Apply(TypeApply(Select(recv, "map"), _), List(closure)) =>
                                                extractElems(recv).map(_.map { elem =>
                                                    kyo.internal.FromExprDerived.betaReduceFully(
                                                        Apply(Select.unique(closure, "apply"), List(elem))
                                                    )
                                                })
                                            case Apply(Select(recv, "map"), List(closure)) =>
                                                extractElems(recv).map(_.map { elem =>
                                                    kyo.internal.FromExprDerived.betaReduceFully(
                                                        Apply(Select.unique(closure, "apply"), List(elem))
                                                    )
                                                })
                                            case _ => None
                                    // `<ev>.toChunk(arg)`, `arg` is a `TupleN.apply(e*)` or a single value.
                                    def tupleOrSingle(t: Term): List[Term] =
                                        def isTuple(c: Term): Boolean =
                                            val n =
                                                try c.symbol.fullName
                                                catch case _: Throwable => ""
                                            n.startsWith("scala.Tuple")
                                        end isTuple
                                        unwrap(t) match
                                            case Apply(TypeApply(Select(q2, "apply"), _), as) if isTuple(q2) => as
                                            case Apply(Select(q2, "apply"), as) if isTuple(q2)               => as
                                            case single                                                      => List(single)
                                        end match
                                    end tupleOrSingle
                                    val elemsOpt: Option[List[Term]] =
                                        term match
                                            case Apply(fun, List(reps))
                                                if isChunkRef(fun) && (endsWith(
                                                    norm,
                                                    "Chunk.apply"
                                                ) || endsWith(norm, "IterableFactory.apply")) =>
                                                extractElems(reps)
                                            case Apply(Select(_, "toChunk"), List(arg)) =>
                                                Some(tupleOrSingle(arg))
                                            case _ => None
                                    elemsOpt.flatMap { elems =>
                                        val lifted: List[Option[t]] =
                                            elems.map(e => innerFE.unapply(e.asExprOf[t]))
                                        if lifted.forall(_.isDefined) then Some(Chunk.from[t](lifted.map(_.get)))
                                        else None
                                    }
                                end if
                            end unapply
                        ).asInstanceOf[scala.quoted.FromExpr[A]]
                    case _ => report.errorAndAbort(s"Cannot extract inner type of Chunk: ${tpe.show}")
                end match
            case _ => report.errorAndAbort(s"Chunk must be applied: ${tpe.show}")
        end match
    end buildChunk

    private def buildField[A: Type](using q: Quotes)(tpe: q.reflect.TypeRepr): scala.quoted.FromExpr[A] =
        import q.reflect.*
        tpe match
            case AppliedType(_, List(nameTpe, valueTpe)) =>
                (nameTpe.asType, valueTpe.asType) match
                    case ('[n], '[v]) =>
                        // Tag[v] cannot be summoned as a runtime value at macro-expansion time without staging; we reconstruct the Field
                        // via reflection on its case-class constructor, passing a placeholder tag. Callers that need a tag-accurate Field
                        // should rely on the production `kyo.FromExpr.derived` path which emits a quoted construction with the proper
                        // Tag in scope at the splice site.
                        (new scala.quoted.FromExpr[Field[n & String, v]]:
                            def unapply(x: Expr[Field[n & String, v]])(using qctx: Quotes): Option[Field[n & String, v]] =
                                import qctx.reflect.*
                                def unwrap(t: Term): Term =
                                    t match
                                        case Inlined(_, _, inner) => unwrap(inner)
                                        case Block(Nil, inner)    => unwrap(inner)
                                        case Typed(inner, _)      => unwrap(inner)
                                        case other                => other
                                def isFieldRef(t: Term): Boolean =
                                    t match
                                        case Inlined(_, _, inner) => isFieldRef(inner)
                                        case Typed(inner, _)      => isFieldRef(inner)
                                        case TypeApply(fun, _)    => isFieldRef(fun)
                                        case Apply(fun, _)        => isFieldRef(fun)
                                        case Select(qual, _)      => isFieldRef(qual)
                                        case Ident(name)          => name == "Field"
                                        case _                    => false
                                val term = unwrap(x.asTerm)
                                // Extract the string literal name from the type-level singleton.
                                // The name type `n` is either a singleton literal type or `"x" & String`.
                                // We extract the string constant by inspecting the type's first ConstantType.
                                def extractNameFromType: Option[String] =
                                    val nameTpeRepr = TypeRepr.of[n]
                                    def loop(t: TypeRepr): Option[String] =
                                        t match
                                            case ConstantType(c) =>
                                                c.value match
                                                    case s: String => Some(s)
                                                    case _         => None
                                            case AndType(a, b) => loop(a).orElse(loop(b))
                                            case _             => None
                                    loop(nameTpeRepr)
                                end extractNameFromType
                                if isFieldRef(term) then
                                    // Reconstruct a Field with the name extracted from the type and null tag (placeholder).
                                    // Reflection-seam casts: ctor.newInstance returns Object; nameValue (String <: AnyRef) boxes.
                                    extractNameFromType.flatMap { nameValue =>
                                        try
                                            val cls  = Class.forName("kyo.Field")
                                            val ctor = cls.getDeclaredConstructors.find(_.getParameterCount == 4).get
                                            ctor.setAccessible(true)
                                            Some(ctor.newInstance(
                                                nameValue,
                                                null,
                                                Nil,
                                                Maybe.empty[v]
                                            ).asInstanceOf[Field[n & String, v]])
                                        catch case _: Throwable => None
                                    }
                                else None
                                end if
                            end unapply
                        ).asInstanceOf[scala.quoted.FromExpr[A]]
                    case _ => report.errorAndAbort(s"Cannot extract Field type params: ${tpe.show}")
            case _ => report.errorAndAbort(s"Field must be applied: ${tpe.show}")
        end match
    end buildField

    private def buildTilde[A: Type](using q: Quotes)(ctx: DirectCtx, tpe: q.reflect.TypeRepr): scala.quoted.FromExpr[A] =
        import q.reflect.*
        tpe match
            case AppliedType(_, List(_, valueTpe)) =>
                valueTpe.asType match
                    case '[v] =>
                        val inner = buildDirect[v](ctx)
                        (new scala.quoted.FromExpr[v]:
                            def unapply(x: Expr[v])(using Quotes): Option[v] = inner.unapply(x)
                        ).asInstanceOf[scala.quoted.FromExpr[A]]
                    case _ => report.errorAndAbort(s"Cannot extract ~ value type: ${tpe.show}")
            case _ => report.errorAndAbort(s"~ must be applied: ${tpe.show}")
        end match
    end buildTilde

    private def buildProduct[A: Type](using q: Quotes)(ctx: DirectCtx, tpe: q.reflect.TypeRepr): scala.quoted.FromExpr[A] =
        import q.reflect.*
        val sym    = tpe.typeSymbol
        val fields = sym.caseFields

        val fieldFEs: List[scala.quoted.FromExpr[Any]] =
            fields.map { field =>
                // Phase F.5: saturate free parameters / wildcards with `Any` (see deriveProduct).
                val ft = applyAnyToFreeParams(tpe.memberType(field).dealias)
                ft.asType match
                    // Reflection-seam cast: invariance of FromExpr[_] means FromExpr[t] cannot be
                    // widened to FromExpr[Any] without a cast at the call site.
                    case '[t] => buildDirect[t](ctx).asInstanceOf[scala.quoted.FromExpr[Any]]
                end match
            }
        val arity         = fields.length
        val symbolName    = sym.fullName
        val caseClassName = sym.name

        new scala.quoted.FromExpr[A]:
            def unapply(x: Expr[A])(using qctx: Quotes): Option[A] =
                import qctx.reflect.*
                given CanEqual[String, String] = CanEqual.derived
                // Phase F.5: inline-expanded DSL constructors (`groupBy(...)`, …) produce a `Block` whose
                // `val` bindings the constructor args reference by `Ident`. Resolve those bindings deeply
                // so field matchers receive binding-free arg trees.
                val term = kyo.internal.FromExprDerived.resolveBindings(x.asTerm)
                def unwrap(t: Term): Term =
                    t match
                        case Inlined(_, _, inner) => unwrap(inner)
                        // `resolveBindings` already substituted block-local `val`s into `inner`, so the
                        // statements are dead, descend past any `Block`, not only the empty form.
                        case Block(_, inner) => unwrap(inner)
                        case Typed(inner, _) => unwrap(inner)
                        case other           => other
                def collectArgs(t: Term, acc: List[Term]): Option[(Term, List[Term])] =
                    unwrap(t) match
                        case Apply(fun, args)  => collectArgs(fun, args ::: acc)
                        case TypeApply(fun, _) => collectArgs(fun, acc)
                        case head              => Some((head, acc))
                // The constructor head must name THIS case class, otherwise a 0-arg case class
                // (`Default()`) would match any term reducing to a non-`Apply` head, and an n-arg
                // case class would match any unrelated n-arg `Apply`.
                def headMatches(head: Term): Boolean =
                    head match
                        case Select(New(tpt), "<init>") => tpt.tpe.typeSymbol.name == caseClassName
                        case Select(qual, "apply") =>
                            val n = qual.symbol.name
                            n == caseClassName || n == caseClassName + "$"
                        case Ident("apply") => false
                        case _              => false
                collectArgs(term, Nil) match
                    case Some((head, args)) if args.length == arity && headMatches(head) =>
                        val lifted = args.zip(fieldFEs).map { case (arg, fe) =>
                            // Reflection-seam: arg.asExprOf[Any] is idiomatic (Term → Expr[T]);
                            // fe returns Option[Any], boxing each lifted value.
                            fe.unapply(arg.asExprOf[Any])
                        }
                        if lifted.forall(_.isDefined) then
                            instantiate(symbolName, arity, lifted.map(_.get))
                        else None
                        end if
                    case _ => None
                end match
            end unapply
        end new
    end buildProduct

    /** Reflection helper: builds a binary JVM class name from a Scala dotted name (e.g. `kyo.internal.FromExprTestFixtures.Wrap` ->
      * `kyo.internal.FromExprTestFixtures$Wrap`) and instantiates the case class via its declared constructor. Returns None if any
      * reflection step fails. Public so the emitted FromExpr code (inside production quotes) can call it.
      */
    def instantiate[A](scalaName: String, arity: Int, args: List[Any]): Option[A] =
        val candidates = binaryNameCandidates(scalaName)
        // Box Any → AnyRef once here at the JVM reflection boundary.
        val argsRef = args.map(_.asInstanceOf[AnyRef])
        candidates.iterator
            .flatMap { name =>
                try
                    val cls  = Class.forName(name)
                    val ctor = cls.getDeclaredConstructors.find(_.getParameterCount == arity)
                    ctor.map { c =>
                        c.setAccessible(true)
                        c.newInstance(argsRef*).asInstanceOf[A]
                    }
                catch case _: Throwable => None
            }
            .nextOption()
    end instantiate

    /** Generates candidate JVM binary class names for a Scala dotted name. Nested classes in objects use `$` joins; top-level classes in
      * packages use `.` joins. We try several plausible decompositions because we don't know at this layer which segments are objects.
      */
    def binaryNameCandidates(scalaName: String): List[String] =
        // First, normalize: strip trailing `$` and convert `$.` to `.` (treating $-marked segments as object names that the type-symbol's
        // fullName artificially marks). Then split on `.` and generate package-vs-nested splits.
        val normalized = scalaName.replace("$.", ".").stripSuffix("$")
        val parts      = normalized.split('.').toList
        if parts.length <= 1 then List(normalized, scalaName)
        else
            // Build the cross-product: pick a split point i; segments 0..i are joined by '.', segments i..n by '$'.
            val candidates = (1 to parts.length).reverse.map { i =>
                val pkgPart  = parts.take(i).mkString(".")
                val nestPart = parts.drop(i).mkString("$")
                if nestPart.isEmpty then pkgPart else s"$pkgPart$$$nestPart"
            }.toList
            candidates ::: List(normalized, scalaName)
        end if
    end binaryNameCandidates

    /** Resolves the runtime value of an `Expr[A]` that is a reference to a stable `given` definition (a `given val` / `given def` on a
      * module, e.g. `kyo.SqlSchema.given_SqlSchema_Int`). Walks `x` to its symbol, splits the fully-qualified name into owner module +
      * member, and retrieves the value by JVM reflection on the owner's `MODULE$`. Returns `None` when `x` is not such a stable reference
      * or reflection fails.
      *
      * Public so kyo-sql's `SqlSchema.fromExprSqlSchema` can delegate here, it must resolve a `SqlSchema[…]` term directly from the
      * supplied expression rather than re-summoning `SqlSchema[A]`, which fails when `A` was saturated to `Any` by the recursion guard.
      *
      * ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────── WHY
      * REFLECTION IS UNAVOIDABLE HERE. At macro-expansion time we hold an `Expr[A]` that *is* a reference to a `given`, but we need its
      * concrete *runtime value* (a `SqlSchema[…]` instance), not the tree. The two non-reflective options both fail:
      *
      *   1. `Expr.summon[A]` / `FromExpr[A].unapply`, fails by construction: `A` is frequently `Any` (the recursion guard saturates a free
      *      type parameter to `Any` when it lifts a `Literal[Any]` field reached through the `Term` sum), and there is no `FromExpr[Any]`.
      *      A primitive `FromExpr` cannot lift a `SqlSchema` object regardless.
      *   2. `Ref(symbol).asExpr` re-quotation, only rebuilds the *tree*; evaluating that tree to a value still needs a `FromExpr`, so it
      *      is circular.
      *
      * The `given` being referenced lives in an already-compiled dependency (e.g. `kyo-sql`) that is on the classpath when this macro
      * expands in a downstream module, so `Class.forName` + `MODULE$` reflection genuinely *can* recover the object, it is the only
      * mechanism that does. The cost is fragility: it depends on Scala 3's binary-naming scheme for module-level `given`s, which is not a
      * stable contract.
      *
      * FAILS CLOSED. Every failure path, symbol unavailable, name not a dotted owner.member, no matching class on the classpath, no
      * `MODULE$`, no accessor method, or any reflection exception, returns `None`. `None` is the documented `FromExpr.unapply` "not
      * statically liftable" signal; the static-SQL macro converts it into a clear `report.errorAndAbort`. There is no path on which a
      * reflection failure produces a wrong value: a miss is always `None`, never a mis-resolved instance.
      * ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
      */
    def resolveStableGiven[A](using q: Quotes)(x: Expr[A]): Option[A] =
        import q.reflect.*
        def unwrap(t: Term): Term =
            t match
                case Inlined(_, _, inner) => unwrap(inner)
                case Block(Nil, inner)    => unwrap(inner)
                case Typed(inner, _)      => unwrap(inner)
                case Apply(fun, Nil)      => unwrap(fun)
                case other                => other
        val term = unwrap(x.asTerm)
        val symName =
            try term.symbol.fullName
            catch case _: Throwable => ""
        if symName.isEmpty then None
        else
            val normalized = symName.replace("$.", ".").stripSuffix("$")
            val dotIdx     = normalized.lastIndexOf('.')
            if dotIdx < 0 then None
            else
                val ownerName  = normalized.take(dotIdx)
                val memberName = normalized.drop(dotIdx + 1)
                try
                    val ownerCandidates = binaryNameCandidates(ownerName).map(_ + "$") ::: List(ownerName + "$")
                    val ownerCls = ownerCandidates.iterator.flatMap { name =>
                        try Some(Class.forName(name))
                        catch case _: Throwable => None
                    }.nextOption()
                    ownerCls.flatMap { cls =>
                        try
                            val moduleField = cls.getDeclaredField("MODULE$")
                            moduleField.setAccessible(true)
                            val module = moduleField.get(null)
                            val method =
                                try cls.getMethod(memberName)
                                catch case _: NoSuchMethodException => cls.getDeclaredMethod(memberName)
                            method.setAccessible(true)
                            // Reflection-seam cast: method.invoke returns Object.
                            Some(method.invoke(module).asInstanceOf[A])
                        catch case _: Throwable => None
                    }
                catch case _: Throwable => None
                end try
            end if
        end if
    end resolveStableGiven

    private def buildSum[A: Type](using q: Quotes)(ctx: DirectCtx, tpe: q.reflect.TypeRepr): scala.quoted.FromExpr[A] =
        import q.reflect.*
        val sym      = tpe.typeSymbol
        val children = sym.children
        if children.isEmpty then
            report.errorAndAbort(s"Sealed/enum type ${tpe.show} has no children to derive FromExpr against.")

        val childFEs: List[scala.quoted.FromExpr[A]] =
            children.map { child =>
                if child.flags.is(Flags.Module) then
                    buildSingleton[A](child.termRef)
                else if child.isType then
                    // Phase F.5: saturate free parameters / wildcards with `Any` (see deriveSum).
                    val childTpe = applyAnyToFreeParams(child.typeRef)
                    childTpe.asType match
                        case '[t] => buildDirect[t](ctx).asInstanceOf[scala.quoted.FromExpr[A]]
                else
                    // Term-symbol enum case without parameters (e.g. `enum Color: case Red`). Build a singleton matcher.
                    buildEnumCaseSingleton[A](child)
                end if
            }

        new scala.quoted.FromExpr[A]:
            def unapply(x: Expr[A])(using Quotes): Option[A] =
                childFEs.iterator.flatMap(_.unapply(x)).nextOption()
    end buildSum

    /** Builds a singleton matcher for an enum case symbol that is a term (not a type). The matcher dispatches on the symbol's full name and
      * reads the enum singleton via reflection on the parent companion.
      */
    private def buildEnumCaseSingleton[A: Type](using q: Quotes)(child: q.reflect.Symbol): scala.quoted.FromExpr[A] =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val expectedName               = child.fullName
        val ownerName                  = child.owner.fullName

        new scala.quoted.FromExpr[A]:
            def unapply(x: Expr[A])(using qctx: Quotes): Option[A] =
                import qctx.reflect.*
                given CanEqual[String, String] = CanEqual.derived
                def unwrap(t: Term): Term =
                    t match
                        case Inlined(_, _, inner) => unwrap(inner)
                        case Block(Nil, inner)    => unwrap(inner)
                        case Typed(inner, _)      => unwrap(inner)
                        case other                => other
                val term = unwrap(x.asTerm)
                val symName =
                    try term.symbol.fullName
                    catch case _: Throwable => ""
                def normalize(s: String): String = s.replace("$package", "").replace("$", "")
                if normalize(symName) == normalize(expectedName) then
                    try
                        val candidates = binaryNameCandidates(ownerName).map(_ + "$")
                        val moduleClass = candidates.iterator.flatMap { name =>
                            try Some(Class.forName(name))
                            catch case _: Throwable => None
                        }.nextOption()
                        moduleClass.flatMap { companionClass =>
                            val caseFieldName = expectedName.split('.').last
                            // Scala 3 enum cases compile to static fields on the companion class.
                            // Try static field first; fall back to instance method getter.
                            try
                                val staticField = companionClass.getDeclaredField(caseFieldName)
                                staticField.setAccessible(true)
                                Some(staticField.get(null).asInstanceOf[A])
                            catch
                                case _: NoSuchFieldException =>
                                    val moduleField = companionClass.getDeclaredField("MODULE$")
                                    moduleField.setAccessible(true)
                                    val module    = moduleField.get(null)
                                    val caseField = companionClass.getDeclaredMethod(caseFieldName)
                                    Some(caseField.invoke(module).asInstanceOf[A])
                            end try
                        }
                    catch
                        case _: Throwable => None
                else None
                end if
            end unapply
        end new
    end buildEnumCaseSingleton

    private def buildSingleton[A: Type](using q: Quotes)(tpe: q.reflect.TypeRepr): scala.quoted.FromExpr[A] =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val sym                        = tpe.typeSymbol
        val moduleSym                  = if sym.flags.is(Flags.Module) then sym else sym.companionModule
        // Try to compute a class loader path for the singleton instance.
        val singletonFullName = if moduleSym != Symbol.noSymbol then moduleSym.fullName else sym.fullName
        val expectedName      = sym.fullName

        new scala.quoted.FromExpr[A]:
            def unapply(x: Expr[A])(using qctx: Quotes): Option[A] =
                import qctx.reflect.*
                given CanEqual[String, String] = CanEqual.derived
                def matchesName(t: Term): Boolean =
                    val symName =
                        try t.symbol.fullName
                        catch case _: Throwable => ""
                    symName == expectedName ||
                    symName == expectedName + "$" ||
                    symName.stripSuffix("$") == expectedName ||
                    // `case object` children of sealed traits: the TermRef's typeSymbol is the
                    // module CLASS (fullName ends in "$"), but the call-site term symbol is the
                    // module VALUE (fullName has no trailing "$"). Adding "$" to the term name
                    // recovers the module-class name so the match succeeds.
                    symName + "$" == expectedName
                end matchesName
                def unwrap(t: Term): Term =
                    t match
                        case Inlined(_, _, inner) => unwrap(inner)
                        case Block(Nil, inner)    => unwrap(inner)
                        case Typed(inner, _)      => unwrap(inner)
                        case other                => other
                val term = unwrap(x.asTerm)
                if matchesName(term) then
                    try
                        val candidates = binaryNameCandidates(singletonFullName).map(_ + "$") :::
                            List(if singletonFullName.endsWith("$") then singletonFullName else singletonFullName + "$")
                        val cls = candidates.iterator.flatMap { name =>
                            try Some(Class.forName(name))
                            catch case _: Throwable => None
                        }.nextOption()
                        cls.flatMap { c =>
                            val field = c.getDeclaredField("MODULE$")
                            field.setAccessible(true)
                            Some(field.get(null).asInstanceOf[A])
                        }
                    catch case _: Throwable => None
                else None
                end if
            end unapply
        end new
    end buildSingleton

    private def deriveFor[A: Type, Q <: Quotes & Singleton](ctx: DeriveCtx[Q]): Expr[scala.quoted.FromExpr[A]] =
        // Use `ctx.q` as the single Quotes instance so `ctx.refs` / `ctx.bindings` (typed in `ctx.q.reflect`)
        // align path-dependently with every reflection value built here, no cross-Quotes cast.
        given q: ctx.q.type = ctx.q
        import q.reflect.*

        val tpe = TypeRepr.of[A].dealias
        val sym = tpe.typeSymbol
        // Phase F.5: the cycle guard applies ONLY to case classes / sealed / enum class-defs that are
        // genuine AST product/sum nodes. `Maybe` / `Chunk` are themselves `sealed`, but they have
        // dedicated derivation arms (`deriveMaybe` / `deriveChunk`) and are NOT cycle keys, their
        // *contents* re-enter `deriveFor` through those arms. So the special-type predicates must be
        // checked first; the guard never intercepts them.
        val isSpecial =
            isPrimitive[A] || isMaybe(tpe) || isChunk(tpe) || isField(tpe) || isTilde(tpe) ||
                isSqlSchema(tpe) || isRecord(tpe) || isColumnProjection(tpe)
        // A `case object` carries both `Case` and `Module` flags. Route it to `deriveSingleton` rather than
        // `deriveProduct` (the latter walks case-field mirrors, which yield no useful matcher for a singleton).
        val isProductOrSum =
            !isSpecial && sym.isClassDef && !sym.flags.is(Flags.Module) &&
                (sym.flags.is(Flags.Sealed) || sym.flags.is(Flags.Enum) || sym.flags.is(Flags.Case))

        if isProductOrSum then
            // Phase F.5 cycle guard (production path, §2.3). On first encounter of a recursive
            // product/sum type, reserve a `lazy val` symbol and register its `Ref` BEFORE building
            // the body; nested `deriveFor` for the same type-constructor key short-circuits to that
            // `Ref` instead of re-deriving (Magnolia forward-reference technique).
            val key = cycleKey(tpe)
            ctx.refs.get(key) match
                case Some(selfRef) =>
                    // Cycle: this type is already being derived, emit a reference to its lazy val.
                    // `ctx.q` IS `q` (DeriveCtx is parameterised on the singleton `Q`), so `selfRef`
                    // is a `q.reflect.Ref`, no cross-Quotes cast. The single budgeted phantom cast:
                    // `Ref(fe$k)` has the singleton type `fe$k.type` (a subtype of the lazy val's
                    // declared `FromExpr[<ctor>[Any]]`), and `A` may be `<ctor>[T]`. `asExprOf` would
                    // reject the singleton↔invariant-`FromExpr` mismatch, so `asExpr` + a cast. The
                    // matcher logic is phantom in `A`. See the top-of-file cast budget.
                    val selfTerm: Term = selfRef
                    selfTerm.asExpr.asInstanceOf[Expr[scala.quoted.FromExpr[A]]]
                case None =>
                    deriveRecursive[A, Q](ctx, key)
            end match
        else if isPrimitive[A] then
            Expr.summon[scala.quoted.FromExpr[A]].getOrElse(
                report.errorAndAbort(s"No stdlib FromExpr available for primitive ${tpe.show}")
            )
        else if tpe =:= TypeRepr.of[Any] then
            // Phase F.5: `Any`-typed leaves arise from saturating an AST node's free value parameter
            // (`Literal[A].value` ↦ `Literal[Any].value: Any`). An arbitrary `Any` is not derivable, but
            // at a concrete static-query call site the term is a primitive constant; emit a `FromExpr`
            // that lifts compile-time constant literals (the only `Any`-positioned values in the AST).
            '{ kyo.internal.FromExprDerived.anyConstantFromExpr }.asExprOf[scala.quoted.FromExpr[A]]
        else if isMaybe(tpe) then
            deriveMaybe[A, Q](ctx, tpe)
        else if isChunk(tpe) then
            deriveChunk[A, Q](ctx, tpe)
        else if isField(tpe) then
            deriveField[A](tpe)
        else if isTilde(tpe) then
            deriveTilde[A, Q](ctx, tpe)
        else if isSqlSchema(tpe) then
            deriveSqlSchema[A](tpe)
        else if isRecord(tpe) then
            deriveRecord[A](tpe)
        else if isColumnProjection(tpe) then
            deriveColumnProjection[A](tpe)
        else if tpe.typeSymbol.flags.is(Flags.Module) then
            deriveSingleton[A](tpe)
        else if tpe.typeSymbol.flags.is(Flags.Enum) then
            // Enum case without parameters (singleton case), treat as a singleton.
            deriveSingleton[A](tpe)
        else
            tpe match
                case OrType(left, right) =>
                    // Phase F.5: a union-typed AST field (`Windowed.inner: Aggregate.Call[A] |
                    // WindowFunction[A]`). Derive each branch and emit a `FromExpr` that tries them
                    // in turn, same shape as `deriveSum`.
                    deriveOr[A, Q](ctx, left, right)
                case _ =>
                    Expr.summon[scala.quoted.FromExpr[A]].getOrElse(
                        report.errorAndAbort(
                            s"FromExpr.derived: cannot derive scala.quoted.FromExpr[${tpe.show}]. " +
                                "Provide a given scala.quoted.FromExpr for this type."
                        )
                    )
        end if
    end deriveFor

    /** Phase F.5, derives a `FromExpr` for a union type `L | R`: tries the `FromExpr[L]` matcher, then the `FromExpr[R]` matcher.
      */
    private def deriveOr[A: Type, Q <: Quotes & Singleton](
        ctx: DeriveCtx[Q],
        leftTpe: ctx.q.reflect.TypeRepr,
        rightTpe: ctx.q.reflect.TypeRepr
    ): Expr[scala.quoted.FromExpr[A]] =
        given q: ctx.q.type = ctx.q
        import q.reflect.*
        val leftFE: Expr[Any] = applyAnyToFreeParams(leftTpe).asType match
            case '[l] => deriveFor[l, Q](ctx).asExprOf[Any]
        val rightFE: Expr[Any] = applyAnyToFreeParams(rightTpe).asType match
            case '[r] => deriveFor[r, Q](ctx).asExprOf[Any]
        '{
            new scala.quoted.FromExpr[A]:
                private lazy val branches: List[scala.quoted.FromExpr[A]] =
                    List($leftFE, $rightFE).asInstanceOf[List[scala.quoted.FromExpr[A]]]
                def unapply(x: Expr[A])(using Quotes): Option[A] =
                    branches.iterator.flatMap(_.unapply(x)).nextOption()
            end new
        }
    end deriveOr

    /** Phase F.5, closes a recursion cycle. On first encounter of a recursive product/sum type, reserves a `lazy val` symbol, registers
      * its `Ref` (so mutually-recursive re-entry resolves to it), builds the body normally, then stashes the `ValDef`. The top-level
      * `derivedImpl` assembles all reserved `lazy val`s into one `Block`.
      */
    private def deriveRecursive[A: Type, Q <: Quotes & Singleton](
        ctx: DeriveCtx[Q],
        key: String
    ): Expr[scala.quoted.FromExpr[A]] =
        given q: ctx.q.type = ctx.q
        import q.reflect.*
        val tpeR: TypeRepr = TypeRepr.of[A].dealias
        // 1. Create a `lazy val` symbol typed FromExpr[A].
        val feType  = TypeRepr.of[scala.quoted.FromExpr].appliedTo(tpeR)
        val lazySym = Symbol.newVal(Symbol.spliceOwner, s"fe$$${ctx.refs.size}", feType, Flags.Lazy, Symbol.noSymbol)
        // 2. Register its Ref BEFORE building the body so nested deriveFor[A] (mutual recursion) resolves here.
        val selfRef = Ref(lazySym)
        ctx.refs.update(key, selfRef)
        // 3. Build the body, the normal product/sum derivation. Recursive deriveFor calls inside now hit
        //    ctx.refs and return selfRef (or a sibling's ref).
        val bodyExpr: Expr[scala.quoted.FromExpr[A]] =
            if tpeR.typeSymbol.flags.is(Flags.Case) then deriveProduct[A, Q](ctx, tpeR)
            else deriveSum[A, Q](ctx, tpeR)
        // 4. Emit the ValDef. `changeOwner` is applied defensively, the quoted `new FromExpr` body is built
        //    under the splice owner, not `lazySym`; ValDef construction needs the body owned by `lazySym`.
        val valDef = ValDef(lazySym, Some(bodyExpr.asTerm.changeOwner(lazySym)))
        ctx.bindings += valDef
        // 5. Return the `lazy val` Ref. `Ref(lazySym)` has the singleton type `lazySym.type`; `asExprOf`
        //    rejects the singleton↔`FromExpr` mismatch, so `asExpr` + the same budgeted phantom cast as
        //    deriveFor's cycle arm (one logical "Ref-to-FromExpr" refinement, see top-of-file budget).
        selfRef.asExpr.asInstanceOf[Expr[scala.quoted.FromExpr[A]]]
    end deriveRecursive

    /** Phase H.5, beta-reduces an application to a fixpoint. `Term.betaReduce` reduces one application redex per call, but it does so by
      * emitting a `Block` of `val` bindings (one per parameter) rather than substituting the arguments, so the reduced body still
      * references the parameter `val`s, and a redex captured behind such a binding (the nested `(_(columns))(_.name)` form the DSL's
      * `specs.map(_(columns))` produces) is not reduced further. Each iteration therefore first runs `resolveBindings` to inline those
      * `val` bindings, exposing the next redex, then `betaReduce`s it. Iterates (bounded) until no further redex appears. Public so emitted
      * production-path quotes can call it.
      */
    def betaReduceFully(using q: Quotes)(term: q.reflect.Term): q.reflect.Term =
        import q.reflect.*
        given CanEqual[String, String] = CanEqual.derived
        // Strips wrappers to expose an application redex `<closure>(args)` / `<closure>.apply(args)`.
        @tailrec
        def peel(t: Term): Term =
            t match
                case Inlined(_, _, inner) => peel(inner)
                case Block(_, inner)      => peel(inner)
                case Typed(inner, _)      => peel(inner)
                case other                => other
        def isRedex(t: Term): Boolean =
            peel(t) match
                case Apply(Select(_, "apply"), _) => true
                case Apply(_: Block, _)           => true
                case Apply(_: Closure, _)         => true
                case _                            => false
        @tailrec
        def loop(cur: Term, fuel: Int): Term =
            if fuel <= 0 then cur
            else
                // Inline the parameter `val`s `betaReduce` introduced so the next redex is exposed.
                val resolved = resolveBindings(cur)
                if !isRedex(resolved) then resolved
                else
                    Term.betaReduce(peel(resolved)) match
                        case Some(reduced) => loop(reduced, fuel - 1)
                        case None          => resolved
                end if
        loop(term, 16)
    end betaReduceFully

    /** Phase F.5, deeply resolves block-local `val` bindings in a term tree.
      *
      * Inline-expanded DSL constructors (`groupBy(...)`, `select(...)`, …) emit a `Block` whose statements bind intermediate `val`s that
      * the constructor `Apply` references by `Ident`. The `FromExpr` matchers walk `Apply` argument trees structurally, so an unresolved
      * `Ident("ks")` arg never matches `Chunk.apply(...)`. This rewriter collects every `val name = rhs` binding reachable in the tree and
      * substitutes each `Ident` reference with its (recursively resolved) `rhs`, so the matchers receive binding-free trees. Public so
      * emitted production-path quotes can call it.
      */
    def resolveBindings(using q: Quotes)(root: q.reflect.Term): q.reflect.Term =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val bindings                   = scala.collection.mutable.Map.empty[Symbol, Term]
        // Pass 1, collect every `val sym = rhs` binding anywhere in the tree.
        // Visited-set on Tree identity: an inline-heavy input can share subtrees referentially
        // (the same closure body reached via multiple Inlined wrappers). Without this, the
        // TreeTraverser walks each shared subtree once per pointer, which compounds across
        // betaReduceFully iterations and was the bottleneck termMemo alone couldn't fix.
        val collectorVisited = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
        object collector extends TreeTraverser:
            override def traverseTree(t: Tree)(owner: Symbol): Unit =
                if collectorVisited.put(t, java.lang.Boolean.TRUE) == null then
                    t match
                        case vd @ ValDef(_, _, Some(rhs)) =>
                            bindings(vd.symbol) = rhs
                        case _ =>
                    end match
                    super.traverseTree(t)(owner)
                end if
            end traverseTree
        end collector
        collector.traverseTree(root)(Symbol.spliceOwner)
        given CanEqual[String, String] = CanEqual.derived
        // Strips wrappers that may sit between a `Select` and the case-class `Apply` it targets.
        // `Block` / `Inlined` statement lists are dead by the time `asConstruction` runs, the
        // substituter has already inlined every `val` binding, so they are stripped regardless of
        // their (now-unused) statement lists.
        def peel(t: Term): Term =
            t match
                case Inlined(_, _, inner) => peel(inner)
                case Block(_, inner)      => peel(inner)
                case Typed(inner, _)      => peel(inner)
                case other                => other
        // Recognises a case-class constructor application `Companion.apply(args)` / `new C(args…)`
        // (including curried / multi-parameter-list `<init>` forms) and returns
        // `(caseClassSymbol, flattenedArgs)`.
        def asConstruction(t: Term): Option[(Symbol, List[Term])] =
            // Collect every value-argument list down a (possibly nested) `Apply` chain, left-to-right.
            def collect(cur: Term, acc: List[Term]): (Term, List[Term]) =
                peel(cur) match
                    case Apply(fun, args)  => collect(fun, args ::: acc)
                    case TypeApply(fun, _) => collect(fun, acc)
                    case head              => (head, acc)
            val (head, args) = collect(t, Nil)
            head match
                case Select(qual, "apply")      => Some((qual.symbol, args))
                case Select(New(tpt), "<init>") => Some((tpt.tpe.typeSymbol, args))
                case _                          => None
            end match
        end asConstruction
        // Pass 2, substitute `Ident` references to bound symbols with their rhs, AND fold
        // `Select(<case-class-apply>, caseField)` to the matching constructor argument, both to a
        // fixpoint. The case-field fold lets a column-projection receiver (`Table.apply(cols, …)
        // .columns`) resolve to the `buildColumns` expansion directly so `RecordFromExpr` / the
        // column-projection FromExpr can walk it.
        // Memoize Ident substitution by binding symbol. Bindings are read-only during pass 2, so
        // the transformed rhs for a given binding symbol is invariant. Without this cache, an Ident
        // referenced from N call sites re-walks its (recursively-substituted) rhs N times, producing
        // exponential compute on inline-heavy macro output. Fuel cap converts any remaining runaway
        // recursion into a clean compile error at the macro use site, rather than a silent hang.
        // Two memo layers: identMemo caches per-binding-symbol substituted rhs (avoids
        // recomputation when the same Ident is referenced from N sites). termMemo caches per-input-
        // Term substitution result (preserves referential sharing in the output, so the next
        // iteration's collector, which is now visited-set guarded, walks the shared subtree once).
        val identMemo = scala.collection.mutable.Map.empty[Symbol, Term]
        val termMemo  = new java.util.IdentityHashMap[Term, Term]()
        val Fuel      = 1000000
        var visits    = 0
        object substituter extends TreeMap:
            override def transformTerm(t: Term)(owner: Symbol): Term =
                visits += 1
                if visits > Fuel then
                    val pos = Position.ofMacroExpansion
                    report.errorAndAbort(
                        s"substituter fuel exhausted visits=$visits at ${pos.sourceFile.path}:${pos.startLine + 1}"
                    )
                end if
                val cached = termMemo.get(t)
                if cached != null then return cached
                val result = t match
                    case id: Ident if bindings.contains(id.symbol) =>
                        identMemo.getOrElseUpdate(id.symbol, transformTerm(bindings(id.symbol))(owner))
                    // `<ev>.substituteCo[F](x)` / `substituteContra` from `=:=` evidence is a pure
                    // identity coercion, strip it so field matchers see the wrapped term directly.
                    case Apply(TypeApply(Select(_, "substituteCo" | "substituteContra"), _), List(inner)) =>
                        transformTerm(inner)(owner)
                    case sel @ Select(receiver, fieldName) =>
                        val resolvedReceiver = transformTerm(receiver)(owner)
                        // Peel `Inlined` / `Block` / `Typed` wrappers off the resolved receiver before
                        // checking if it is a case-class constructor application. `inline def` bodies
                        // emit `Inlined(...)` wrappers around `<self>.field` Selects whose receiver,
                        // after binding substitution, is a case-class apply wrapped in `Inlined(...)`,
                        // `asConstruction` already peels via `peel`, but the un-peeled wrapper would
                        // otherwise prevent the case-flag check (`caseSym.flags.is(Flags.Case)`) from
                        // seeing the right symbol. Peeling once here also enables further Select-folds
                        // when `Sql.windowSpec.build` style chains nest multiple inline defs.
                        val peeledReceiver = peel(resolvedReceiver)
                        val asCon          = asConstruction(peeledReceiver)
                        // Case-field fold. The original guard only checked the head symbol
                        // (`caseSym.flags.is(Case)` / `caseSym.companionClass.flags.is(Case)`), which
                        // fails when the head names an `export` / re-export alias (e.g. `kyo.SqlAst`
                        // re-exports `WindowSpecBuilder` from `kyo.internal.dsl`, the alias's
                        // `companionClass` is `NoSymbol`). The added arm accepts the fold when the
                        // field accessor's owner IS a case class AND the construction's arg count
                        // matches that class's caseFields count, a conservative shape check that
                        // ensures we're indexing the right arg list even when the head symbol is
                        // an alias rather than the case-class companion directly.
                        //
                        // Both `sel.symbol` and `caseSym.companionClass` accesses can throw on a
                        // NoDenotation, so each lookup is wrapped in a Try-style guard that returns
                        // an empty result when the underlying symbol arithmetic fails.
                        def safeFlagsIs(s: Symbol, f: Flags): Boolean =
                            try s != Symbol.noSymbol && s.flags.is(f)
                            catch case _: Throwable => false
                        def safeCompanionClass(s: Symbol): Symbol =
                            try s.companionClass
                            catch case _: Throwable => Symbol.noSymbol
                        def safeOwner(s: Symbol): Symbol =
                            try if s == Symbol.noSymbol then Symbol.noSymbol else s.owner
                            catch case _: Throwable => Symbol.noSymbol
                        def safeCaseFields(s: Symbol): List[Symbol] =
                            try if s == Symbol.noSymbol then Nil else s.caseFields
                            catch case _: Throwable => Nil
                        val selSym      = sel.symbol
                        val ownerCls    = safeOwner(selSym)
                        val ownerIsCase = safeFlagsIs(ownerCls, Flags.Case)
                        asCon match
                            case Some((caseSym, args))
                                if safeFlagsIs(caseSym, Flags.Case) || safeFlagsIs(safeCompanionClass(caseSym), Flags.Case) =>
                                val caseFields = safeCaseFields(ownerCls)
                                caseFields.zipWithIndex.find(_._1.name == fieldName).map(_._2) match
                                    case Some(i) if i < args.size => transformTerm(args(i))(owner)
                                    case _                        => Select.copy(sel)(resolvedReceiver, fieldName)
                            case Some((_, args)) if ownerIsCase && args.length == safeCaseFields(ownerCls).length =>
                                val caseFields = safeCaseFields(ownerCls)
                                caseFields.zipWithIndex.find(_._1.name == fieldName).map(_._2) match
                                    case Some(i) if i < args.size => transformTerm(args(i))(owner)
                                    case _                        => Select.copy(sel)(resolvedReceiver, fieldName)
                            case _ => Select.copy(sel)(resolvedReceiver, fieldName)
                        end match
                    case other => super.transformTerm(other)(owner)
                termMemo.put(t, result)
                result
            end transformTerm
        end substituter
        substituter.transformTerm(root)(Symbol.spliceOwner)
    end resolveBindings

    /** Phase F.5, `buildDirect` test-path counterpart of `deriveRecord`. Builds a `FromExpr[A]` whose `unapply` summons a
      * `given FromExpr[A]` at the macro use-site (e.g. `RecordFromExpr.fromExprRecord` imported in a kyo-sql test) and delegates to it. The
      * summoned given is retrieved by JVM reflection on its defining module, the same seam `buildSqlSchema` uses for `SqlSchema` givens.
      * Returns `None` when no given is in implicit scope, preserving the pre-Phase-F `Record` placeholder behaviour.
      */
    private def buildSummonedFromExpr[A: Type](using q: Quotes): scala.quoted.FromExpr[A] =
        new scala.quoted.FromExpr[A]:
            def unapply(x: Expr[A])(using qctx: Quotes): Option[A] =
                import qctx.reflect.*
                Expr.summon[scala.quoted.FromExpr[A]] match
                    case None => None
                    case Some(feExpr) =>
                        def unwrap(t: Term): Term =
                            t match
                                case Inlined(_, _, inner) => unwrap(inner)
                                case Block(Nil, inner)    => unwrap(inner)
                                case Typed(inner, _)      => unwrap(inner)
                                case TypeApply(fun, _)    => unwrap(fun)
                                case Apply(fun, Nil)      => unwrap(fun)
                                case other                => other
                        val sym = unwrap(feExpr.asTerm).symbol
                        val symName =
                            try sym.fullName
                            catch case _: Throwable => ""
                        if symName.isEmpty then None
                        else
                            val normalized = symName.replace("$.", ".").stripSuffix("$")
                            val dotIdx     = normalized.lastIndexOf('.')
                            if dotIdx < 0 then None
                            else
                                val ownerName  = normalized.take(dotIdx)
                                val memberName = normalized.drop(dotIdx + 1)
                                try
                                    val ownerCandidates = binaryNameCandidates(ownerName).map(_ + "$") :::
                                        List(ownerName + "$")
                                    val ownerCls = ownerCandidates.iterator.flatMap { name =>
                                        try Some(Class.forName(name))
                                        catch case _: Throwable => None
                                    }.nextOption()
                                    ownerCls.flatMap { cls =>
                                        try
                                            val moduleField = cls.getDeclaredField("MODULE$")
                                            moduleField.setAccessible(true)
                                            val module = moduleField.get(null)
                                            // `fromExprRecord[F]` is a generic no-value-arg given method.
                                            val method =
                                                try cls.getMethod(memberName)
                                                catch
                                                    case _: NoSuchMethodException =>
                                                        cls.getDeclaredMethod(memberName)
                                            method.setAccessible(true)
                                            // Reflection-seam cast: method.invoke returns Object.
                                            val fe = method.invoke(module).asInstanceOf[scala.quoted.FromExpr[A]]
                                            fe.unapply(x)
                                        catch case _: Throwable => None
                                    }
                                catch case _: Throwable => None
                                end try
                            end if
                        end if
                end match
            end unapply
        end new
    end buildSummonedFromExpr

    // ---------------------------------------------------------------------
    // Type-shape predicates
    // ---------------------------------------------------------------------

    private def isPrimitive[A: Type](using q: Quotes): Boolean =
        import q.reflect.*
        val tpe = TypeRepr.of[A].dealias
        tpe =:= TypeRepr.of[Int] ||
        tpe =:= TypeRepr.of[Long] ||
        tpe =:= TypeRepr.of[String] ||
        tpe =:= TypeRepr.of[Boolean] ||
        tpe =:= TypeRepr.of[Double] ||
        tpe =:= TypeRepr.of[Float] ||
        tpe =:= TypeRepr.of[Char] ||
        tpe =:= TypeRepr.of[Byte] ||
        tpe =:= TypeRepr.of[Short]
    end isPrimitive

    private def isMaybe(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val target                     = TypeRepr.of[Maybe[Any]].typeSymbol
        tpe match
            case AppliedType(c, _) => c.typeSymbol == target
            case _                 => tpe.typeSymbol == target
    end isMaybe

    /** Marker traits with no runtime content that appear in narrowed type bounds (e.g. `String & Singleton`).
      * The [[buildDirect]] `AndType` arm strips these so derivation recurses on the substantive operand.
      */
    private def isPhantomMarker(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val sym                        = tpe.typeSymbol
        sym == TypeRepr.of[scala.Singleton].typeSymbol ||
        sym == TypeRepr.of[Serializable].typeSymbol ||
        sym == TypeRepr.of[Product].typeSymbol ||
        sym == TypeRepr.of[Matchable].typeSymbol ||
        sym == TypeRepr.of[Equals].typeSymbol ||
        sym == TypeRepr.of[AnyRef].typeSymbol ||
        sym == TypeRepr.of[AnyVal].typeSymbol ||
        sym == TypeRepr.of[Any].typeSymbol
    end isPhantomMarker

    private def isChunk(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val target                     = TypeRepr.of[Chunk[Any]].typeSymbol
        tpe match
            case AppliedType(c, _) => c.typeSymbol == target
            case _                 => false
    end isChunk

    private def isField(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val target                     = TypeRepr.of[Field[?, ?]].typeSymbol
        tpe match
            case AppliedType(c, _) => c.typeSymbol == target
            case _                 => false
    end isField

    private def isTilde(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val target                     = TypeRepr.of[~[?, ?]].typeSymbol
        tpe match
            case AppliedType(c, _) => c.typeSymbol == target
            case _                 => false
    end isTilde

    private def isRecord(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val target                     = TypeRepr.of[Record[Any]].typeSymbol
        tpe match
            case AppliedType(c, _) => c.typeSymbol == target
            case _                 => tpe.typeSymbol == target
    end isRecord

    private def isSqlSchema(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
        symbolMatches(tpe, sqlSchemaTypeSym)

    /** True for the AST `Term` leaves that need a custom `FromExpr` (in kyo-sql's `ColumnFromExpr`) rather than the generic product walker.
      * `Column` / `GroupedColumn` / `UngroupedView` arise from `Record` field projections (`selectDynamic` chains); `Cast` carries a
      * `sqlTypeName` argument that is a method call on a `SqlSchema` given, not a string literal. The product walker handles none of these.
      */
    private def isColumnProjection(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
        columnProjectionTypeSyms.exists(t => symbolMatches(tpe, t))

    /** The SqlSchema opaque-type symbol resolved at macro-expansion time. kyo-schema cannot statically
      * import `kyo.SqlSchema` (kyo-sql depends on kyo-schema, not the other way). `Symbol.requiredModule`
      * finds the companion `object SqlSchema`; the opaque type of the same source name lives as a type
      * member of the same enclosing owner (the synthesized `$package$` module). Returns `Symbol.noSymbol`
      * when kyo-sql is not on the classpath; `symbolMatches` short-circuits to `false` in that case.
      */
    private def sqlSchemaTypeSym(using q: Quotes): q.reflect.Symbol =
        import q.reflect.*
        try
            val mod = Symbol.requiredModule("kyo.SqlSchema")
            if !mod.exists then Symbol.noSymbol else mod.maybeOwner.typeMember("SqlSchema")
        catch case _: Throwable => Symbol.noSymbol
        end try
    end sqlSchemaTypeSym

    /** The `Column` / `GroupedColumn` / `UngroupedView` / `Cast` type-member symbols of `object kyo.SqlAst`, resolved at macro-expansion
      * time; empty when kyo-sql is not on the classpath.
      */
    private def columnProjectionTypeSyms(using q: Quotes): List[q.reflect.Symbol] =
        import q.reflect.*
        val sqlAst =
            try Symbol.requiredModule("kyo.SqlAst")
            catch case _: Throwable => Symbol.noSymbol
        if !sqlAst.exists then Nil
        else List("Column", "GroupedColumn", "UngroupedView", "Cast").map(sqlAst.typeMember)
    end columnProjectionTypeSyms

    /** True when `tpe`'s head-constructor type symbol matches `target`. Guards against `NoSymbol == NoSymbol` false-positives: union /
      * intersection types have no class symbol, and stubbed lookups also resolve to `NoSymbol`; both must fail this check.
      */
    private def symbolMatches(using q: Quotes)(tpe: q.reflect.TypeRepr, target: q.reflect.Symbol): Boolean =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        if !target.exists then false
        else
            val tsym = tpe.dealias match
                case AppliedType(c, _) => c.typeSymbol
                case other             => other.typeSymbol
            tsym.exists && tsym == target
        end if
    end symbolMatches

    /** Production-path arm for `Column` / `GroupedColumn` / `UngroupedView`: delegates to the `given FromExpr` summoned at the macro
      * use-site (`ColumnFromExpr.fromExprColumn` etc., when imported in kyo-sql). Mirrors `deriveSqlSchema` / `deriveRecord`.
      */
    private def deriveColumnProjection[A: Type](using q: Quotes)(tpe: q.reflect.TypeRepr): Expr[scala.quoted.FromExpr[A]] =
        import q.reflect.*
        Expr.summon[scala.quoted.FromExpr[A]].getOrElse(
            report.errorAndAbort(
                s"FromExpr.derived: no FromExpr[${tpe.show}] found. " +
                    "Ensure kyo.internal.ColumnFromExpr givens are in scope (import kyo.internal.ColumnFromExpr.given)."
            )
        )
    end deriveColumnProjection

    private def deriveSqlSchema[A: Type](using q: Quotes)(tpe: q.reflect.TypeRepr): Expr[scala.quoted.FromExpr[A]] =
        import q.reflect.*
        // Delegate to the given FromExpr[SqlSchema[A]] defined in object SqlSchema (kyo-sql).
        // At kyo-sql compile time, that given is in scope and Expr.summon finds it.
        Expr.summon[scala.quoted.FromExpr[A]].getOrElse(
            report.errorAndAbort(
                s"FromExpr.derived: no FromExpr[${tpe.show}] found. " +
                    "Ensure kyo.SqlSchema.fromExprSqlSchema is in scope (import kyo.SqlSchema)."
            )
        )
    end deriveSqlSchema

    /** Builds a plain `scala.quoted.FromExpr[A]` value for the `buildDirect` path (test-only).
      *
      * For the `deriveFor` (production) path, `deriveSqlSchema` returns an `Expr[FromExpr[A]]` that contains a quoted delegation to
      * `Expr.summon`. Here we build the plain FromExpr directly: its `unapply` method resolves the `SqlSchema` value by finding the stable
      * given symbol via `Expr.summon[A]`, extracting the fully-qualified member name from the symbol, then retrieving the value through JVM
      * reflection on the owner companion class (for `given val` and `given def` definitions).
      *
      * This is the `buildDirect`-only path used by `applyMatchedImpl` / `applyReprImpl` in the test harness.
      */
    private def buildSqlSchema[A: Type](using q: Quotes)(tpe: q.reflect.TypeRepr): scala.quoted.FromExpr[A] =
        new scala.quoted.FromExpr[A]:
            def unapply(x: Expr[A])(using qctx: Quotes): Option[A] =
                // Resolve the schema from the supplied expression `x` itself, it is the actual
                // `SqlSchema[…]` reference (e.g. `given_SqlSchema_Int`). Re-summoning `SqlSchema[A]`
                // fails when `A` was saturated to `Any` (a `Literal[Any]` field reached via the
                // `Term` sum), so we use `x` directly.
                kyo.internal.FromExprDerived.resolveStableGiven[A](x)
            end unapply
        end new
    end buildSqlSchema

    // ---------------------------------------------------------------------
    // Derivations for kyo data types
    // ---------------------------------------------------------------------

    /** Builds a FromExpr that uses term-tree pattern matching via `quotes.reflect.*` instead of quote splicing. The matcher receives a
      * `Term`, walks through `Inlined`/`Block`/`Typed`/`TypeApply` wrappers, and dispatches on `Apply(fun, args)` / module references by
      * their fully-qualified symbol names. This is the workhorse for kyo data types.
      */
    private def deriveMaybe[A: Type, Q <: Quotes & Singleton](
        ctx: DeriveCtx[Q],
        tpe: ctx.q.reflect.TypeRepr
    ): Expr[scala.quoted.FromExpr[A]] =
        given q: ctx.q.type = ctx.q
        import q.reflect.*
        tpe match
            case AppliedType(_, List(inner)) =>
                inner.asType match
                    case '[t] =>
                        // Phase F.5: erase the inner FromExpr to `FromExpr[Any]` (see deriveChunk).
                        val innerFromExpr = deriveFor[t, Q](ctx).asExprOf[Any]
                        val result = '{
                            new scala.quoted.FromExpr[Maybe[t]]:
                                // `lazy` (Phase F.5 R2): defers forcing the inner FromExpr to `unapply` time.
                                private lazy val innerFE: scala.quoted.FromExpr[Any] =
                                    $innerFromExpr.asInstanceOf[scala.quoted.FromExpr[Any]]
                                def unapply(x: Expr[Maybe[t]])(using qctx: Quotes): Option[Maybe[t]] =
                                    import qctx.reflect.*
                                    given CanEqual[String, String] = CanEqual.derived
                                    def unwrap(t: Term): Term =
                                        t match
                                            case Inlined(_, _, inner) => unwrap(inner)
                                            case Block(Nil, inner)    => unwrap(inner)
                                            case Typed(inner, _)      => unwrap(inner)
                                            case other                => other
                                    val term = unwrap(x.asTerm)
                                    def head(t: Term): Term =
                                        t match
                                            case Apply(fun, _)           => head(fun)
                                            case TypeApply(fun, _)       => head(fun)
                                            case Select(_, _) | Ident(_) => t
                                            case _                       => t
                                    val callee = head(term)
                                    val sname =
                                        try callee.symbol.fullName
                                        catch case _: Throwable => ""

                                    // `Maybe` is an opaque type; its members' `fullName` carries
                                    // `$package` / `$` segments (`kyo.Maybe$package$.Maybe$.empty`).
                                    // Normalise and suffix-match, mirroring the test-path `buildMaybe`.
                                    def endsWith(s: String, suffix: String): Boolean =
                                        s == suffix || s.endsWith("." + suffix)
                                    val norm = sname.replace("$package", "").replace("$", "")
                                    if endsWith(norm, "Maybe.empty") then
                                        Some(Maybe.empty[t])
                                    else if endsWith(norm, "Maybe.Absent") || endsWith(norm, ".Absent") then
                                        Some(Maybe.empty[t])
                                    else
                                        term match
                                            case Apply(_, List(arg)) if endsWith(norm, "Maybe.apply") =>
                                                innerFE.unapply(arg.asExprOf[Any]).map(v => Maybe.apply[t](v.asInstanceOf[t]))
                                            case Apply(_, List(arg)) if endsWith(norm, "Present.apply") =>
                                                innerFE.unapply(arg.asExprOf[Any]).map(v => Maybe.Present.apply[t](v.asInstanceOf[t]))
                                            case _ => None
                                    end if
                                end unapply
                            end new
                        }
                        result.asExprOf[scala.quoted.FromExpr[A]]
                    case _ => report.errorAndAbort(s"Cannot extract inner type of Maybe: ${tpe.show}")
            case _ => report.errorAndAbort(s"Maybe must be applied: ${tpe.show}")
        end match
    end deriveMaybe

    private def deriveChunk[A: Type, Q <: Quotes & Singleton](
        ctx: DeriveCtx[Q],
        tpe: ctx.q.reflect.TypeRepr
    ): Expr[scala.quoted.FromExpr[A]] =
        given q: ctx.q.type = ctx.q
        import q.reflect.*
        tpe match
            case AppliedType(_, List(rawInner)) =>
                // Phase G.5: derive a *case-class* element's `FromExpr` against the SATURATED element type.
                // A sealed element (`Term[?]`) is cycle-keyed regardless of its phantom arg, and special
                // elements (`Column[?,?]`, `Record[?]`, `SqlSchema[?]`) are served by wildcard-keyed custom
                // givens, saturating those to `[Any,…]` would make `Expr.summon` miss the given. A plain
                // case-class element reached at a wildcard (`BoundValue[?]`), however, would have
                // `deriveProduct` read `memberType(field)` against the wildcard and resolve an abstract type
                // member (`BoundValue.A`) for which no `FromExpr` exists, so it alone needs saturation. The
                // outer `FromExpr[Chunk[t]]` keeps the ORIGINAL element type (`t = rawInner`) so the result
                // type matches `A`; the element matcher is erased to `FromExpr[Any]`, so the
                // saturated/unsaturated distinction is phantom.
                val rawInnerDealiased = rawInner.dealias
                // A *plain* case-class element (no special / custom-given arm) needs saturation before its
                // FromExpr is derived; the special arms handle their own wildcards.
                val isPlainCaseElem =
                    !isMaybe(rawInnerDealiased) && !isChunk(rawInnerDealiased) && !isField(rawInnerDealiased) &&
                        !isTilde(rawInnerDealiased) && !isSqlSchema(rawInnerDealiased) &&
                        !isRecord(rawInnerDealiased) && !isColumnProjection(rawInnerDealiased) &&
                        rawInnerDealiased.typeSymbol.isClassDef && rawInnerDealiased.typeSymbol.flags.is(Flags.Case)
                val saturatedInner = if isPlainCaseElem then applyAnyToFreeParams(rawInner) else rawInner
                rawInner.asType match
                    case '[t] =>
                        // Phase F.5: erase the inner FromExpr to `FromExpr[Any]`. When the recursion guard
                        // resolves `t` (e.g. `Term[Boolean]`) it returns a sibling `lazy val` typed at the
                        // saturated form (`FromExpr[Term[Any]]`); splicing that into a precisely-typed
                        // `FromExpr[t]` val fails the invariance check. Erasing matches `deriveProduct`'s
                        // `fieldFEs: List[FromExpr[Any]]` channel; the matcher logic is phantom in `t`.
                        val innerFromExpr = saturatedInner.asType match
                            case '[st] => deriveFor[st, Q](ctx).asExprOf[Any]
                        val result = '{
                            new scala.quoted.FromExpr[Chunk[t]]:
                                // `lazy` (Phase F.5 R2): defers forcing the inner FromExpr to `unapply` time.
                                private lazy val innerFE: scala.quoted.FromExpr[Any] =
                                    $innerFromExpr.asInstanceOf[scala.quoted.FromExpr[Any]]
                                def unapply(x: Expr[Chunk[t]])(using qctx: Quotes): Option[Chunk[t]] =
                                    import qctx.reflect.*
                                    given CanEqual[String, String] = CanEqual.derived
                                    // `resolveBindings` substituted block-local `val`s, descend past any `Block`.
                                    def unwrap(t: Term): Term =
                                        t match
                                            case Inlined(_, _, inner) => unwrap(inner)
                                            case Block(_, inner)      => unwrap(inner)
                                            case Typed(inner, _)      => unwrap(inner)
                                            case other                => other
                                    // Resolve inline-expansion `Block` `val` bindings so element matchers
                                    // receive binding-free trees (see `deriveProduct` / `resolveBindings`).
                                    val term = unwrap(kyo.internal.FromExprDerived.resolveBindings(x.asTerm))
                                    def head(t: Term): Term =
                                        t match
                                            case Apply(fun, _)     => head(fun)
                                            case TypeApply(fun, _) => head(fun)
                                            case _                 => t
                                    val callee = head(term)
                                    val sname =
                                        try callee.symbol.fullName
                                        catch
                                            case _: Throwable => ""
                                    // `Chunk.apply` is inherited from `IterableFactory`, so the callee
                                    // symbol's `fullName` is `scala.collection.IterableFactory.apply`, not
                                    // `kyo.Chunk.apply`. Recognise the `Chunk` module by the `Select`
                                    // qualifier as well, mirroring the test-path `buildChunk`.
                                    def isChunkRef(t: Term): Boolean =
                                        t match
                                            case Inlined(_, _, inner) => isChunkRef(inner)
                                            case Typed(inner, _)      => isChunkRef(inner)
                                            case TypeApply(fun, _)    => isChunkRef(fun)
                                            case Select(qualifier, _) => isChunkRef(qualifier)
                                            case Ident(name)          => name == "Chunk"
                                            case _                    => false
                                    def endsWith(s: String, suffix: String): Boolean =
                                        s == suffix || s.endsWith("." + suffix)
                                    val norm = sname.replace("$package", "").replace("$", "")
                                    if endsWith(norm, "Chunk.empty") then
                                        Some(Chunk.empty[t])
                                    else
                                        // Chunk.apply(varargs) / Chunk.from(varargs), the args term is a single
                                        // Typed(Repeated(...), _) or Repeated(elems, _), possibly wrapped in `Inlined`
                                        // when it arrives through an inline def (e.g. `InsertBuilder.values`).
                                        //
                                        // Phase H.5, also handles `<varargs>.map(<closure>)`: the DSL's INSERT
                                        // `ON CONFLICT` / `overriding` builders write `Chunk.from(specs.map(_(columns)))`,
                                        // where `specs` is a varargs `Repeated`. A runtime `Seq.map` over closures is not
                                        // pure data, so the elements are recovered by beta-reducing the `.map` closure
                                        // against each `Repeated` element at this (already fully-typed) macro-expansion
                                        // point, `Term.betaReduce` reduces the captured-closure application to the
                                        // underlying projection / `SetSpec` tree.
                                        def extractElems(t: Term): Option[List[Term]] =
                                            unwrap(t) match
                                                case Typed(Repeated(elems, _), _) => Some(elems)
                                                case Repeated(elems, _)           => Some(elems)
                                                case Apply(TypeApply(Select(recv, "map"), _), List(closure)) =>
                                                    extractElems(recv).map(_.map { elem =>
                                                        kyo.internal.FromExprDerived.betaReduceFully(
                                                            Apply(Select.unique(closure, "apply"), List(elem))
                                                        )
                                                    })
                                                case Apply(Select(recv, "map"), List(closure)) =>
                                                    extractElems(recv).map(_.map { elem =>
                                                        kyo.internal.FromExprDerived.betaReduceFully(
                                                            Apply(Select.unique(closure, "apply"), List(elem))
                                                        )
                                                    })
                                                case other => None
                                        // `<ev>.toChunk(arg)`, `arg` is a `TupleN.apply(e*)` or a single value.
                                        def tupleOrSingle(t: Term): List[Term] =
                                            def isTuple(c: Term): Boolean =
                                                val n =
                                                    try c.symbol.fullName
                                                    catch case _: Throwable => ""
                                                n.startsWith("scala.Tuple")
                                            end isTuple
                                            unwrap(t) match
                                                case Apply(TypeApply(Select(q2, "apply"), _), as) if isTuple(q2) => as
                                                case Apply(Select(q2, "apply"), as) if isTuple(q2)               => as
                                                case single                                                      => List(single)
                                            end match
                                        end tupleOrSingle
                                        val elemsOpt: Option[List[Term]] =
                                            term match
                                                case Apply(fun, List(reps))
                                                    if isChunkRef(fun) &&
                                                        (endsWith(norm, "Chunk.apply") ||
                                                            endsWith(norm, "IterableFactory.apply") ||
                                                            // `Chunk.from(varargs)`, produced by inline-def varargs forwarding
                                                            // such as `InsertBuilder.values(rows: T*)` → `Chunk.from(rows)`.
                                                            endsWith(norm, "Chunk.from")) =>
                                                    extractElems(reps)
                                                case Apply(Select(_, "toChunk"), List(arg)) =>
                                                    Some(tupleOrSingle(arg))
                                                case _ => None
                                        elemsOpt.flatMap { elems =>
                                            val lifted: List[Option[Any]] =
                                                elems.map(e => innerFE.unapply(e.asExprOf[Any]))
                                            if lifted.forall(_.isDefined) then
                                                // FromExpr[Any]-erased channel: one boundary cast back to `t`.
                                                Some(Chunk.from(lifted.map(_.get)).asInstanceOf[Chunk[t]])
                                            else None
                                            end if
                                        }
                                    end if
                                end unapply
                            end new
                        }
                        result.asExprOf[scala.quoted.FromExpr[A]]
                    case _ => report.errorAndAbort(s"Cannot extract inner type of Chunk: ${tpe.show}")
                end match
            case _ => report.errorAndAbort(s"Chunk must be applied: ${tpe.show}")
        end match
    end deriveChunk

    private def deriveField[A: Type](using q: Quotes)(tpe: q.reflect.TypeRepr): Expr[scala.quoted.FromExpr[A]] =
        import q.reflect.*
        tpe match
            case AppliedType(_, List(nameTpe, valueTpe)) =>
                (nameTpe.asType, valueTpe.asType) match
                    case ('[n], '[v]) =>
                        // Tag[v] must be summonable here; if not, fall back to a None-returning FromExpr.
                        Expr.summon[Tag[v]] match
                            case Some(tagExpr) =>
                                val result = '{
                                    new scala.quoted.FromExpr[Field[n & String, v]]:
                                        private val tag: Tag[v] = $tagExpr
                                        def unapply(x: Expr[Field[n & String, v]])(using qctx: Quotes): Option[Field[n & String, v]] =
                                            import qctx.reflect.*
                                            def unwrap(t: Term): Term =
                                                t match
                                                    case Inlined(_, _, inner) => unwrap(inner)
                                                    case Block(Nil, inner)    => unwrap(inner)
                                                    case Typed(inner, _)      => unwrap(inner)
                                                    case other                => other
                                            val term = unwrap(x.asTerm)
                                            term match
                                                case Apply(_, args) if args.nonEmpty =>
                                                    args.head.asExpr match
                                                        case '{ $nm: String } =>
                                                            nm.value.map { nameValue =>
                                                                new Field[n & String, v](
                                                                    nameValue.asInstanceOf[n & String],
                                                                    tag,
                                                                    Nil,
                                                                    Maybe.empty[v]
                                                                )
                                                            }
                                                        case _ => None
                                                case _ => None
                                            end match
                                        end unapply
                                    end new
                                }
                                result.asExprOf[scala.quoted.FromExpr[A]]
                            case None =>
                                val result = '{
                                    new scala.quoted.FromExpr[Field[n & String, v]]:
                                        def unapply(x: Expr[Field[n & String, v]])(using qctx: Quotes): Option[Field[n & String, v]] = None
                                    end new
                                }
                                result.asExprOf[scala.quoted.FromExpr[A]]
                        end match
                    case _ => report.errorAndAbort(s"Cannot extract Field type params: ${tpe.show}")
            case _ => report.errorAndAbort(s"Field must be applied: ${tpe.show}")
        end match
    end deriveField

    private def deriveTilde[A: Type, Q <: Quotes & Singleton](
        ctx: DeriveCtx[Q],
        tpe: ctx.q.reflect.TypeRepr
    ): Expr[scala.quoted.FromExpr[A]] =
        given q: ctx.q.type = ctx.q
        import q.reflect.*
        tpe match
            case AppliedType(_, List(_, valueTpe)) =>
                valueTpe.asType match
                    case '[v] =>
                        // Phase F.5: erase the inner FromExpr to `FromExpr[Any]` (see deriveChunk).
                        val inner = deriveFor[v, Q](ctx).asExprOf[Any]
                        val result = '{
                            new scala.quoted.FromExpr[v]:
                                // `lazy` (Phase F.5 R2): defers forcing the inner FromExpr to `unapply` time.
                                private lazy val innerFE: scala.quoted.FromExpr[Any] =
                                    $inner.asInstanceOf[scala.quoted.FromExpr[Any]]
                                def unapply(x: Expr[v])(using qctx: Quotes): Option[v] =
                                    innerFE.unapply(x.asExprOf[Any]).map(_.asInstanceOf[v])
                            end new
                        }
                        result.asExprOf[scala.quoted.FromExpr[A]]
                    case _ => report.errorAndAbort(s"Cannot extract ~ value type: ${tpe.show}")
            case _ => report.errorAndAbort(s"~ must be applied: ${tpe.show}")
        end match
    end deriveTilde

    private def deriveRecord[A: Type](using q: Quotes)(tpe: q.reflect.TypeRepr): Expr[scala.quoted.FromExpr[A]] =
        // Prefer a given `FromExpr[A]` already in scope at the macro use-site (e.g. `RecordFromExpr.fromExprRecord`
        // imported in kyo-sql). Falls back to a `None`-returning placeholder when no given is found, preserving the
        // pre-Phase-F behaviour for contexts that do not import the kyo-sql given.
        Expr.summon[scala.quoted.FromExpr[A]].getOrElse('{
            new scala.quoted.FromExpr[A]:
                def unapply(x: Expr[A])(using qctx: Quotes): Option[A] = None
            end new
        })
    end deriveRecord

    // ---------------------------------------------------------------------
    // Product / Sum derivations
    // ---------------------------------------------------------------------

    private def deriveProduct[A: Type, Q <: Quotes & Singleton](
        ctx: DeriveCtx[Q],
        tpe: ctx.q.reflect.TypeRepr
    ): Expr[scala.quoted.FromExpr[A]] =
        given q: ctx.q.type = ctx.q
        import q.reflect.*
        val sym    = tpe.typeSymbol
        val fields = sym.caseFields

        // Build the field-FromExprs list as an Expr[List[Any]] to dodge FromExpr's invariance, we cast back to FromExpr[Any] at use site.
        val fieldFromExprs: List[Expr[Any]] =
            fields.map { field =>
                // Phase F.5: saturate any free parameter / wildcard in the field type with `Any` so a
                // field typed by an unapplied parameter (`Literal.value: A`, `Chunk[Term[?]]`) derives
                // its most general applied form.
                val ft = applyAnyToFreeParams(tpe.memberType(field).dealias)
                ft.asType match
                    case '[t] => deriveFor[t, Q](ctx).asExprOf[Any]
            }

        val fieldFromExprsList: Expr[List[Any]] = Expr.ofList(fieldFromExprs)
        val arity                               = fields.length
        val symbolName                          = sym.fullName
        val caseClassName                       = sym.name

        '{
            new scala.quoted.FromExpr[A]:
                // `lazy` (Phase F.5 R2): defers forcing the field FromExprs until `unapply` runs, so the
                // mutually-recursive `lazy val` block's initialization order is irrelevant by construction.
                private lazy val fieldFEs: List[scala.quoted.FromExpr[Any]] =
                    $fieldFromExprsList.asInstanceOf[List[scala.quoted.FromExpr[Any]]]
                private val expectedArity: Int            = ${ Expr(arity) }
                private val expectedSymbolName: String    = ${ Expr(symbolName) }
                private val expectedCaseClassName: String = ${ Expr(caseClassName) }

                def unapply(x: Expr[A])(using qctx: Quotes): Option[A] =
                    import qctx.reflect.*
                    given CanEqual[String, String] = CanEqual.derived
                    def unwrap(t: Term): Term =
                        t match
                            case Inlined(_, _, inner) => unwrap(inner)
                            // `resolveBindings` substituted block-local `val`s, descend past any `Block`.
                            case Block(_, inner) => unwrap(inner)
                            case Typed(inner, _) => unwrap(inner)
                            case other           => other
                    def collectArgs(t: Term, acc: List[Term]): Option[(Term, List[Term])] =
                        unwrap(t) match
                            // Prepend each argument list as-is: the innermost `Apply` (processed last)
                            // carries the leftmost arg list, so `args ::: acc` rebuilds left-to-right
                            // order. (Reversing each list mis-orders multi-field constructors.)
                            case Apply(fun, args)  => collectArgs(fun, args ::: acc)
                            case TypeApply(fun, _) => collectArgs(fun, acc)
                            case head              => Some((head, acc))
                    // The constructor head must name THIS case class, otherwise a 0-arg case class
                    // (`Default()`) matches any non-`Apply` head and an n-arg case class matches any
                    // unrelated n-arg `Apply`.
                    def headMatches(head: Term): Boolean =
                        head match
                            case Select(New(tpt), "<init>") => tpt.tpe.typeSymbol.name == expectedCaseClassName
                            case Select(qual, "apply") =>
                                val n = qual.symbol.name
                                n == expectedCaseClassName || n == expectedCaseClassName + "$"
                            case _ => false
                    // Phase F.5: resolve inline-expansion `Block` `val` bindings (see resolveBindings).
                    val term = kyo.internal.FromExprDerived.resolveBindings(x.asTerm)
                    collectArgs(term, Nil) match
                        case Some((head, args)) if args.length == expectedArity && headMatches(head) =>
                            val lifted = args.zip(fieldFEs).map { case (arg, fe) =>
                                fe.unapply(arg.asExprOf[Any])
                            }
                            if lifted.forall(_.isDefined) then
                                kyo.internal.FromExprDerived.instantiate[A](
                                    expectedSymbolName,
                                    expectedArity,
                                    lifted.map(_.get)
                                )
                            else None
                            end if
                        case _ => None
                    end match
                end unapply
            end new
        }
    end deriveProduct

    private def deriveSum[A: Type, Q <: Quotes & Singleton](
        ctx: DeriveCtx[Q],
        tpe: ctx.q.reflect.TypeRepr
    ): Expr[scala.quoted.FromExpr[A]] =
        given q: ctx.q.type = ctx.q
        import q.reflect.*
        val sym      = tpe.typeSymbol
        val children = sym.children

        if children.isEmpty then
            report.errorAndAbort(s"Sealed/enum type ${tpe.show} has no children to derive FromExpr against.")

        // Children are FromExpr[child-type], not FromExpr[A]. We erase to `Expr[Any]` to dodge invariance and cast back at use site.
        val childFromExprs: List[Expr[Any]] =
            children.map { child =>
                // Phase F.5: a child case class / module may carry its own type arguments (e.g.
                // `Literal[A]`, `ValuesFrom[T, F]`, `WindowFunction.RowNumber extends
                // WindowFunction[Long]`) that differ from the parent's. Derive each child at its own
                // saturated type, never at the parent's `A`, so `deriveSingleton` / `deriveFor`
                // produce a well-typed `Ref`. Saturate free parameters with `Any`.
                applyAnyToFreeParams(child.typeRef).asType match
                    case '[t] =>
                        if child.flags.is(Flags.Module) then
                            deriveSingleton[t](TypeRepr.of[t]).asExprOf[Any]
                        else
                            deriveFor[t, Q](ctx).asExprOf[Any]
            }

        val listExpr: Expr[List[Any]] = Expr.ofList(childFromExprs)

        '{
            new scala.quoted.FromExpr[A]:
                // `lazy` (Phase F.5 R2): the mutually-recursive `lazy val` block resolves child refs only
                // when `unapply` runs, so initialization order is irrelevant by construction.
                private lazy val childFEs: List[scala.quoted.FromExpr[A]] =
                    $listExpr.asInstanceOf[List[scala.quoted.FromExpr[A]]]
                def unapply(x: Expr[A])(using qctx: Quotes): Option[A] =
                    childFEs.iterator.flatMap(_.unapply(x)).nextOption()
            end new
        }
    end deriveSum

    private def deriveSingleton[A: Type](using q: Quotes)(tpe: q.reflect.TypeRepr): Expr[scala.quoted.FromExpr[A]] =
        import q.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val sym                        = tpe.typeSymbol
        // `Ref(...)` needs a TERM symbol. For a singleton type `Empty.type`, `tpe.typeSymbol` returns the
        // module CLASS (`Empty$`) even though `Flags.Module` is set on both val and class. Prefer the
        // term-side symbol: use `sym` directly when it is already a term, else its companion module val.
        val moduleSym = if sym.isTerm then sym else sym.companionModule
        val singletonRef: Expr[A] =
            if moduleSym != Symbol.noSymbol then
                Ref(moduleSym).asExprOf[A]
            else
                val parentSym = sym.owner
                if parentSym.companionModule != Symbol.noSymbol then
                    Select.unique(Ref(parentSym.companionModule), sym.name).asExprOf[A]
                else
                    report.errorAndAbort(s"Cannot synthesize singleton reference for ${tpe.show}")
                end if

        val symFullName = sym.fullName

        '{
            new scala.quoted.FromExpr[A]:
                private val singleton: A         = $singletonRef
                private val expectedName: String = ${ Expr(symFullName) }
                def unapply(x: Expr[A])(using qctx: Quotes): Option[A] =
                    import qctx.reflect.*
                    given CanEqual[String, String] = CanEqual.derived
                    def matchesName(t: Term): Boolean =
                        val symName =
                            try t.symbol.fullName
                            catch case _: Throwable => ""
                        symName == expectedName ||
                        symName == expectedName + "$" ||
                        symName.stripSuffix("$") == expectedName ||
                        // `case object` at the call site: the call-site term symbol is the module VAL
                        // (`kyo.pkg.Empty`), but `expectedName` came from `TypeRepr.of[Empty.type].typeSymbol.fullName`
                        // which resolves to the module CLASS (`kyo.pkg.Empty$`). Adding `"$"` to the term name
                        // recovers the module-class name so the match succeeds.
                        symName + "$" == expectedName
                    end matchesName
                    def go(term: Term): Option[A] =
                        term match
                            case Inlined(_, _, t)    => go(t)
                            case Block(Nil, t)       => go(t)
                            case Typed(t, _)         => go(t)
                            case t if matchesName(t) => Some(singleton)
                            case _                   => None
                    go(x.asTerm)
                end unapply
            end new
        }
    end deriveSingleton

end FromExprDerived
