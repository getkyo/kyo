package kyo.internal

import kyo.*
import kyo.SqlAst.*
import scala.annotation.tailrec
import scala.collection.mutable
import scala.quoted.*

/** Provides `FromExpr[Record[F]]` by TASTy-tree walking of inline-expanded `buildColumns` / `&` expressions.
  *
  * Inline methods that produce `Record[F]` values expand into a known set of TASTy tree shapes:
  *   - `buildColumns[T, N]` → `"alias" ~ new Record(Dict(...) ++ Dict(...) ++ ...)` where the inner dict maps field names to
  *     `Column(alias, fieldName)` values. Because `buildColumns` is `transparent inline` but `~` is a regular method, the TASTy at the call
  *     site shows the `~` invocation with the fully-inlined inner record as its argument.
  *   - `&` merge → `left.&(right)` which at the call site is always an inline-expanded Dict union. `FromExpr` recursively reconstructs both
  *     sides and merges their dicts.
  *   - `buildGroupedView` → a plain `def` producing `GroupedColumn` / `UngroupedView` entries at runtime; TASTy shows the opaque function
  *     call. The recursion-guarded `FromExpr.derived[Query[?]]` / `FromExpr.derived[Chunk[Term[?]]]` lift the `source` / `keys` arguments,
  *     then `SqlGroupedView.buildGroupedView` (a pure `def`) is re-executed at expand time, byte-for-byte parity with the runtime path.
  *
  * Placement in kyo-sql (not kyo-schema) lets this file reference `Column`, `GroupedColumn`, `UngroupedView`, and `SqlGroupedView` directly
  * zero reflection, zero `Class.forName`.
  *
  * Summon reachability: `fromExprRecord` is NOT in `Record`'s implicit companion scope. Call sites that invoke `FromExpr.derived` for
  * Record-carrying AST nodes MUST have this given in scope via `import kyo.internal.RecordFromExpr.given`.
  */
object RecordFromExpr:

    /** `FromExpr[Record[F]]` that walks the inline-expanded TASTy tree of `buildColumns` and `&` expressions. */
    given fromExprRecord[F]: scala.quoted.FromExpr[Record[F]] with
        def unapply(x: Expr[Record[F]])(using Quotes): Option[Record[F]] =
            // Record[F] is `final class Record[F](dict: Dict[String,Any])`. The type parameter F is
            // phantom at runtime, every Record instance is the same class regardless of F. The
            // walker internally produces Option[Record[?]] via Record.init[Any]; we unify to the
            // concrete F here at the FromExpr boundary via the centralised phantom-type helper.
            val ctx = new WalkContext
            MacroSupport.narrowOption(ctx.walkExpr(x))
        end unapply
    end fromExprRecord

    // --- walk context (one per unapply invocation) ---

    /** Encapsulates per-invocation state (val-binding map) so concurrent macro expansion is safe. */
    private class WalkContext(using Quotes):
        import quotes.reflect.*

        private val bindings: mutable.Map[Symbol, Term] = mutable.Map.empty

        /** Entry point: converts an `Expr` to its underlying `Term` and then walks it.
          *
          * `resolveBindings` first substitutes every block-local `val` reference deeply (the same pass `FromExprDerived.deriveProduct`
          * runs), so the `stageNamed` staging closure (`fn$proxyN`) and the intermediate `cols` binding are inlined before the walk,
          * `betaUnwrap` can then beta-reduce the per-column closure applications.
          */
        def walkExpr(x: Expr[?]): Option[Record[?]] =
            walkRecord(kyo.internal.FromExprDerived.resolveBindings(x.asTerm))

        // --- top-level Record walker ---

        /** Walks a `Term` representing a `Record[?]` value and reconstructs it. */
        private def walkRecord(t: Term): Option[Record[?]] =
            unwrap(t) match
                // `"alias" ~ innerRecord`, `transparent inline buildColumns` exposes this call at the use
                // site; the `~` extension on String is NOT inline, so the TASTy shows the method call.
                case Apply(TypeApply(Select(selfExpr, n), _), List(innerExpr))
                    if n == "$tilde" || n == "~" =>
                    for
                        alias <- strLit(selfExpr)
                        inner <- walkRecord(innerExpr)
                    yield Record.init[Any](Dict[String, Any](alias -> inner))
                // `~` as a regular (non-extension-receiver) method: the `self` string is the first
                // value-argument, not the `Select` qualifier. The `~` method itself surfaces as a bare
                // `Ident("~")` (or a `Select(_, "~")`): `Apply(TypeApply(Apply(~Ref, List(aliasExpr)),
                // _), List(innerExpr))`.
                case Apply(TypeApply(Apply(tildeRef, List(aliasExpr)), _), List(innerExpr))
                    if isTildeRef(tildeRef) =>
                    for
                        alias <- strLit(aliasExpr)
                        inner <- walkRecord(innerExpr)
                    yield Record.init[Any](Dict[String, Any](alias -> inner))
                // `left.&(right)`, merges two Record dicts. Used by CrossJoin / Join column construction.
                case Apply(TypeApply(Select(lhsExpr, "&"), _), List(rhsExpr)) =>
                    for
                        lhs <- walkRecord(lhsExpr)
                        rhs <- walkRecord(rhsExpr)
                    yield Record.init[Any](lhs.dict ++ rhs.dict)
                // `new Record(dict)`, direct constructor (from `StageNamedOps.apply` after inlining).
                // The ctor is type-applied (`new Record[F](dict)`), so the `Select(New(_), "<init>")`
                // is wrapped in a `TypeApply`; the non-type-applied form is kept as a fallback.
                case Apply(TypeApply(Select(New(_), "<init>"), _), List(dictExpr)) =>
                    walkDictChain(dictExpr).map(d => Record.init[Any](d))
                case Apply(Select(New(_), "<init>"), List(dictExpr)) =>
                    walkDictChain(dictExpr).map(d => Record.init[Any](d))
                // `buildGroupedView(source, keys)`, plain def; TASTy shows the opaque call.
                // Re-execute the pure builder at expand time using recursion-guarded
                // FromExpr[Query[?]] / FromExpr[Chunk[Term[?]]]. `SqlGroupedView.buildGroupedView`
                // is a pure def, safe to invoke during macro expansion, so `GroupBy.view` lifts
                // via the same eager builder the runtime path uses, guaranteeing byte-for-byte parity.
                case Apply(TypeApply(Select(_, "buildGroupedView"), _), List(srcExpr, keysExpr)) =>
                    // `import quotes.reflect.*` shadows `kyo.SqlAst.Term` with the reflection `Term`,
                    // so the AST term type must be fully qualified as `SqlAst.Term` here.
                    // The column-projection FromExpr givens must be in scope so `FromExpr.derived`
                    // can lift the `Column` / grouped-view leaves the source query carries.
                    import kyo.internal.ColumnFromExpr.given
                    given scala.quoted.FromExpr[SqlAst.Query[?]]       = kyo.FromExpr.derived
                    given scala.quoted.FromExpr[Chunk[SqlAst.Term[?]]] = kyo.FromExpr.derived
                    for
                        src <- summon[scala.quoted.FromExpr[SqlAst.Query[?]]]
                            .unapply(srcExpr.asExprOf[SqlAst.Query[?]])
                        keys <- summon[scala.quoted.FromExpr[Chunk[SqlAst.Term[?]]]]
                            .unapply(keysExpr.asExprOf[Chunk[SqlAst.Term[?]]])
                    yield kyo.internal.SqlGroupedView.buildGroupedView[Any](src, keys)
                    end for
                case _ =>
                    None
            end match
        end walkRecord

        // --- Dict chain walker ---

        /** Walks a `Dict[String, Any]` term: handles `++` chains, single-entry `Dict(k -> v)`, and `Dict.empty`.
          *
          * `++` is an `extension` method on `Dict` (`Dict.scala:103,263`). At the inline-expanded `buildColumns` call site the extension
          * call surfaces in receiver-as-first-arg form, `Dict.++(left)(right)`, i.e.
          * `Apply(Apply(TypeApply(Select(Dict, "++"), _), List(left)), List(right))`. The other (qualifier-receiver) forms are kept as
          * fallbacks for `&` / merge construction.
          */
        private def walkDictChain(t: Term): Option[Dict[String, Any]] =
            unwrap(t) match
                // `leftDict ++ rightDict` as an extension method, receiver is the first value-arg, the
                // `Select` qualifier is the `Dict` module: `Dict.++(left)(right)`.
                case Apply(Apply(TypeApply(Select(qual, "++"), _), List(leftExpr)), List(rightExpr))
                    if qual.symbol.name == "Dict" || qual.symbol.name == "Dict$" =>
                    for
                        left  <- walkDictChain(leftExpr)
                        right <- walkDictChain(rightExpr)
                    yield left ++ right
                case Apply(Apply(Select(qual, "++"), List(leftExpr)), List(rightExpr))
                    if qual.symbol.name == "Dict" || qual.symbol.name == "Dict$" =>
                    for
                        left  <- walkDictChain(leftExpr)
                        right <- walkDictChain(rightExpr)
                    yield left ++ right
                // `leftDict ++ rightDict` with the receiver as the `Select` qualifier (non-extension form).
                case Apply(TypeApply(Select(leftExpr, "++"), _), List(rightExpr)) =>
                    for
                        left  <- walkDictChain(leftExpr)
                        right <- walkDictChain(rightExpr)
                    yield left ++ right
                case Apply(Select(leftExpr, "++"), List(rightExpr)) =>
                    for
                        left  <- walkDictChain(leftExpr)
                        right <- walkDictChain(rightExpr)
                    yield left ++ right
                // `Dict.apply(entries: (K, V)*)`, single-entry or vararg form
                case Apply(TypeApply(Select(qual, "apply"), _), args)
                    if qual.symbol.name == "Dict" || qual.symbol.name == "Dict$" =>
                    extractDictEntries(args)
                case Apply(Select(qual, "apply"), args)
                    if qual.symbol.name == "Dict" || qual.symbol.name == "Dict$" =>
                    extractDictEntries(args)
                // `Dict.empty`, base case of stageNamedLoop recursion. The call surfaces either bare
                // (`TypeApply(Select(Dict, "empty"), _)`) or, after older inlining, wrapped in an `Apply`.
                case Select(qual, "empty")
                    if qual.symbol.name == "Dict" || qual.symbol.name == "Dict$" =>
                    Some(Dict.empty[String, Any])
                case TypeApply(Select(qual, "empty"), _)
                    if qual.symbol.name == "Dict" || qual.symbol.name == "Dict$" =>
                    Some(Dict.empty[String, Any])
                case Apply(TypeApply(Select(qual, "empty"), _), _)
                    if qual.symbol.name == "Dict" || qual.symbol.name == "Dict$" =>
                    Some(Dict.empty[String, Any])
                case _ =>
                    None
            end match
        end walkDictChain

        /** Extracts the `(K, V)` pairs from `Dict.apply(entries*)` and builds a `Dict`. */
        private def extractDictEntries(args: List[Term]): Option[Dict[String, Any]] =
            val pairs: List[Term] = args match
                case List(Typed(Repeated(elems, _), _)) => elems
                case List(Repeated(elems, _))           => elems
                case flat                               => flat
            val extracted = pairs.map(extractPair)
            if extracted.forall(_.isDefined) then
                Some(Dict.from(extracted.flatMap(_.toList).toMap))
            else
                None
            end if
        end extractDictEntries

        /** Extracts `(String, Any)` from a `"key" -> value` pair expression.
          *
          * `stageNamedLoop` builds each entry as `key -> value` via `Predef.ArrowAssoc`, which surfaces as `ArrowAssoc(key).->(value)`,
          * `Apply(TypeApply(Select(Apply(TypeApply(Ident("ArrowAssoc"), _), List(key)), "->"), _), List(value))`. The `Tuple2.apply` forms
          * are kept as fallbacks.
          */
        private def extractPair(t: Term): Option[(String, Any)] =
            unwrap(t) match
                // `ArrowAssoc(key) -> value`, `Predef.ArrowAssoc` extension producing the pair.
                case Apply(TypeApply(Select(arrow, "->"), _), List(valueExpr)) if arrowKey(arrow).isDefined =>
                    for
                        key   <- arrowKey(arrow)
                        value <- extractValue(valueExpr)
                    yield (key, value)
                case Apply(Select(arrow, "->"), List(valueExpr)) if arrowKey(arrow).isDefined =>
                    for
                        key   <- arrowKey(arrow)
                        value <- extractValue(valueExpr)
                    yield (key, value)
                // `Tuple2.apply("key", value)`, desugaring of `"key" -> value`
                case Apply(TypeApply(Select(_, "apply"), _), List(keyExpr, valueExpr)) =>
                    for
                        key   <- strLit(keyExpr)
                        value <- extractValue(valueExpr)
                    yield (key, value)
                case Apply(Select(_, "apply"), List(keyExpr, valueExpr)) =>
                    for
                        key   <- strLit(keyExpr)
                        value <- extractValue(valueExpr)
                    yield (key, value)
                case _ =>
                    None

        /** Extracts the key string from an `ArrowAssoc(key)` receiver, accepting both the type-applied and plain `Ident("ArrowAssoc")` /
          * `Select(_, "ArrowAssoc")` forms.
          */
        private def arrowKey(t: Term): Option[String] =
            unwrap(t) match
                case Apply(TypeApply(arrowRef, _), List(keyExpr)) if isArrowAssocRef(arrowRef) => strLit(keyExpr)
                case Apply(arrowRef, List(keyExpr)) if isArrowAssocRef(arrowRef)               => strLit(keyExpr)
                case _                                                                         => None

        private def isArrowAssocRef(t: Term): Boolean =
            t match
                case Ident(n)     => n == "ArrowAssoc"
                case Select(_, n) => n == "ArrowAssoc"
                case _            => false

        /** Extracts a value from a Dict entry: `Column(alias, name, sqlName)` or a nested `Record`.
          *
          * `buildColumns` builds each entry value by applying the staging closure to a synthesised `Field`:
          * `[n, v] => (g: Field[n, v]) => Column[n & String, v](alias, g.name, resolveSqlName[T](g.name))`. `resolveSqlName[T]` is an
          * `inline def` that expands to either a string literal (no schema in scope) or `SqlNameResolver.columnName(name, schema)` (schema
          * in scope). `Term.betaReduce` reduces the closure application; `sqlNameValue` handles both resulting shapes.
          */
        private def extractValue(t: Term): Option[Any] =
            betaUnwrap(t) match
                // `Column[N, V](alias, name, sqlName)`, the primary dict value for buildColumns (three-arg form).
                // `sqlName` is resolved by `resolveSqlName[T]` at inline-expansion time.
                case Apply(TypeApply(Select(qual, "apply"), _), List(aliasExpr, nameExpr, sqlNameExpr))
                    if qual.symbol.name == "Column" || qual.symbol.name == "Column$" =>
                    for
                        alias   <- strLit(aliasExpr)
                        name    <- columnName(nameExpr)
                        sqlName <- sqlNameValue(sqlNameExpr)
                    yield Column[String & scala.Singleton, Any](alias, name.asInstanceOf[String & scala.Singleton], sqlName)
                case Apply(Select(qual, "apply"), List(aliasExpr, nameExpr, sqlNameExpr))
                    if qual.symbol.name == "Column" || qual.symbol.name == "Column$" =>
                    for
                        alias   <- strLit(aliasExpr)
                        name    <- columnName(nameExpr)
                        sqlName <- sqlNameValue(sqlNameExpr)
                    yield Column[String & scala.Singleton, Any](alias, name.asInstanceOf[String & scala.Singleton], sqlName)
                // `new Column[N, V](alias, name, sqlName)`, init form.
                case Apply(TypeApply(Select(New(_), "<init>"), _), List(aliasExpr, nameExpr, sqlNameExpr)) =>
                    for
                        alias   <- strLit(aliasExpr)
                        name    <- columnName(nameExpr)
                        sqlName <- sqlNameValue(sqlNameExpr)
                    yield Column[String & scala.Singleton, Any](alias, name.asInstanceOf[String & scala.Singleton], sqlName)
                // Nested Record, for the outer-alias wrap: `Dict("alias" -> innerRecord)`.
                // Option is covariant and Record[?] <: Any, so no cast is needed.
                case other =>
                    walkRecord(other)
            end match
        end extractValue

        /** Resolves the `Column`'s `name` argument: either a direct string literal or `Field(<name>, …).name`, the `stageNamed` closure
          * builds `Column(alias, g.name, sqlN)` where `g` is the synthesised `Field`.
          */
        private def columnName(t: Term): Option[String] =
            betaUnwrap(t) match
                case Literal(StringConstant(s)) => Some(s)
                // `Field.apply(<name>, …).name` / `new Field(<name>, …).name`, the field's first ctor
                // argument is its name; pull it out of the construction the `g.name` selection targets.
                case Select(fieldCtor, "name") => fieldFirstArg(fieldCtor).flatMap(strLit)
                case _                         => None

        /** Recognises a `Select(qual, "columnName")` where `qual` refers to `SqlNameResolver` or its inlined alias.
          *
          * After `resolveSqlName` inlining the Scala 3 compiler renames `SqlNameResolver` to `inline$SqlNameResolver$i1(kyo.internal)`. We
          * match on the method name `"columnName"` and check both the qualifier symbol name AND the `show` representation for
          * "SqlNameResolver" so both the direct and inlined forms are accepted.
          */
        private def isColumnNameCall(sel: Term): Boolean =
            sel match
                case Select(qual, "columnName") =>
                    val symName  = qual.symbol.name
                    val showText = qual.show
                    symName.contains("SqlNameResolver") || symName.contains("inline$SqlNameResolver") ||
                    showText.contains("SqlNameResolver")
                case _ => false

        private def sqlNameValue(t: Term): Option[String] =
            betaUnwrap(t) match
                case Literal(StringConstant(s)) => Some(s)
                // `SqlNameResolver.columnName(scalaNameArg, schemaArg)`, non-type-applied form.
                // After `resolveSqlName` inlining, the qualifier may be renamed; match by method name.
                // `resolveStableGiven` uses JVM reflection; falls back to `scalaName` when the schema is
                // defined in the same compilation unit (e.g. a test class not yet compiled to bytecode).
                case Apply(sel, List(scalaNameArg, schemaArg)) if isColumnNameCall(sel) =>
                    columnName(scalaNameArg).map { scalaName =>
                        val schemaOpt = FromExprDerived.resolveStableGiven[SqlSchema[Any]](
                            MacroSupport.expectExpr[SqlSchema[Any]](betaUnwrap(schemaArg).asExpr)
                        )
                        schemaOpt match
                            case Some(schema) => SqlNameResolver.columnName(scalaName, schema)
                            case None         => scalaName
                    }
                // `SqlNameResolver.columnName[T](scalaNameArg, schemaArg)`, TypeApply wrapper form.
                // Emitted by `resolveSqlName[T]` when a `SqlSchema[T]` is in scope at the `buildColumns`
                // call site. Same fallback as the non-TypeApply form.
                case Apply(TypeApply(sel, _), List(scalaNameArg, schemaArg)) if isColumnNameCall(sel) =>
                    columnName(scalaNameArg).map { scalaName =>
                        val schemaOpt = FromExprDerived.resolveStableGiven[SqlSchema[Any]](
                            MacroSupport.expectExpr[SqlSchema[Any]](betaUnwrap(schemaArg).asExpr)
                        )
                        schemaOpt match
                            case Some(schema) => SqlNameResolver.columnName(scalaName, schema)
                            case None         => scalaName
                    }
                // `Field(...).name` or typed string literal, delegate to `columnName` which handles both.
                case other => columnName(other)
            end match
        end sqlNameValue

        /** Extracts the first constructor argument of a `Field.apply(...)` / `new Field(...)` term. */
        private def fieldFirstArg(t: Term): Option[Term] =
            betaUnwrap(t) match
                case Apply(TypeApply(Select(qual, "apply"), _), first :: _)
                    if qual.symbol.name == "Field" || qual.symbol.name == "Field$" =>
                    Some(first)
                case Apply(Select(qual, "apply"), first :: _)
                    if qual.symbol.name == "Field" || qual.symbol.name == "Field$" =>
                    Some(first)
                case Apply(TypeApply(Select(New(_), "<init>"), _), first :: _) => Some(first)
                case Apply(Select(New(_), "<init>"), first :: _)               => Some(first)
                case _                                                         => None

        // --- generic utilities ---

        /** Strips common TASTy wrappers: `Inlined`, `Block`, `Typed`, `asInstanceOf`, substitution evidence, and Ident bindings. Adapted
          * from the proven `R.unwrap` in `SqlStatic.scala`.
          */
        @tailrec
        private def unwrap(t: Term): Term =
            t match
                case Inlined(_, defs, inner) =>
                    captureBindings(defs)
                    unwrap(inner)
                case Block(stats, inner) =>
                    captureBindings(stats)
                    unwrap(inner)
                case Typed(inner, _) => unwrap(inner)
                case TypeApply(Select(inner, "asInstanceOf" | "$asInstanceOf$"), _) =>
                    unwrap(inner)
                case Apply(TypeApply(Select(_, "substituteCo" | "substituteContra"), _), List(i)) =>
                    unwrap(i)
                case id @ Ident(_) if bindings.contains(id.symbol) =>
                    unwrap(bindings(id.symbol))
                case other => other

        /** Like `unwrap`, but additionally beta-reduces closure applications. `buildColumns`'s `stageNamed` closure is applied to a
          * synthesised `Field` per column; `Term.betaReduce` performs the same substitution the compiler does, so the dict value reduces to
          * a literal `Column.apply(...)` tree. `betaReduce` returns `None` when the term is not a reducible application, then the
          * un-reduced `unwrap` result stands.
          *
          * When the Apply's function position is an `Ident` bound in `bindings` (e.g. a `val resolveColName = (s: String) => ...`), we
          * resolve it before calling `betaReduce` so the substitution can succeed.
          */
        @tailrec
        private def betaUnwrap(t: Term): Term =
            val u = unwrap(t)
            u match
                case Apply(fn, args) =>
                    // Resolve Ident references in the function position before beta-reducing.
                    // Also strip `lambda.apply(args)`, the Scala compiler emits `Select(lambda, "apply")`
                    // for SAM / Function1 invocations; stripping the `.apply` select lets `betaReduce` see
                    // the bare lambda and perform the substitution.
                    val resolvedFn = fn match
                        case id @ Ident(_) if bindings.contains(id.symbol) => unwrap(bindings(id.symbol))
                        case Select(inner, "apply")                        => inner
                        case other                                         => other
                    val forBeta = if resolvedFn ne fn then Apply.copy(u)(resolvedFn, args) else u
                    Term.betaReduce(forBeta) match
                        case Some(reduced) if reduced ne forBeta => betaUnwrap(reduced)
                        case _                                   => u
                case _ => u
            end match
        end betaUnwrap

        private def captureBindings(stmts: List[Statement]): Unit =
            stmts.foreach:
                case vd @ ValDef(_, _, Some(rhs)) => bindings(vd.symbol) = rhs
                case _                            => ()

        /** Extracts a compile-time String constant from a Term. */
        private def strLit(t: Term): Option[String] =
            unwrap(t) match
                case Literal(StringConstant(s)) => Some(s)
                case _                          => None

        /** Recognises a reference to the `~` field-pair constructor, surfaces as a bare `Ident("~")` or a `Select(_, "~")` depending on
          * how the call site resolved the method.
          */
        private def isTildeRef(t: Term): Boolean =
            t match
                case Ident(n)     => n == "~" || n == "$tilde"
                case Select(_, n) => n == "~" || n == "$tilde"
                case _            => false

    end WalkContext

end RecordFromExpr
