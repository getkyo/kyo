package kyo.internal

import kyo.BackendSql
import kyo.BoundValue
import kyo.Chunk
import kyo.SqlAst
import kyo.SqlBackend
import kyo.SqlSchema
import kyo.SqlStatic
import scala.quoted.*

private[kyo] object SqlStaticMacro:

    def impl(q: Expr[SqlAst.Executable[?]])(using Quotes): Expr[SqlStatic.Rendered] =
        import quotes.reflect.*
        liftAst(q) match
            case Some(ast) => renderLifted(ast, q)
            case None      =>
                // Walk the query expression and report a specific positioned error for each opaque
                // construct that prevented static folding. Returns the number of specific errors emitted.
                val emitted = emitOpaqueCauses(q.asTerm)
                if emitted > 0 then
                    report.errorAndAbort(
                        ".runStatic / staticSql: query cannot be folded at compile time " +
                            "(see the specific errors above). Use .run for opportunistic static folding " +
                            "with runtime fallback, or .runDynamic to skip static folding entirely.",
                        q.asTerm.pos
                    )
                else
                    report.errorAndAbort(
                        ".runStatic / staticSql: query cannot be folded at compile time. " +
                            "Ensure all SqlSchema instances are declared as `inline given` " +
                            "and all DSL fragments are inline-constructed. " +
                            "Use .run for opportunistic static folding with runtime fallback.",
                        q.asTerm.pos
                    )
                end if
        end match
    end impl

    /** Walks the query expression and emits a positioned `report.error` for every sub-expression that prevented static folding. Returns the
      * number of errors emitted so the caller can decide whether to follow up with a generic message.
      *
      * Currently identifies runtime `SqlNameResolver.tableName` / `columnName` calls — these come from the [[SqlMacros]] fallback path when
      * a `SqlSchema[T]` is summoned but its construction isn't statically extractable (the schema is a plain `given` rather than
      * `inline given`). Each error names the schema's element type and suggests `inline given` plus the per-run-mode alternatives.
      */
    private def emitOpaqueCauses(term: quoted.Quotes#reflectModule#Tree)(using q: Quotes): Int =
        import q.reflect.*
        var count                   = 0
        val sqlNameResolverFullName = "kyo.internal.SqlNameResolver$"

        object Traverser extends TreeTraverser:
            override def traverseTree(tree: Tree)(owner: Symbol): Unit =
                tree match
                    case apply: Apply =>
                        val sym = apply.symbol
                        if (sym.name == "tableName" || sym.name == "columnName") &&
                            sym.maybeOwner.fullName == sqlNameResolverFullName
                        then
                            val methodLabel = sym.name match
                                case "tableName"  => "table name"
                                case "columnName" => "column name"
                                case other        => other
                            val schemaTypeShown: String =
                                apply.args match
                                    case _ :: schemaArg :: Nil =>
                                        // Schema arg has type `SqlSchema[T]` (which is opaque-aliased to `Schema[T]`).
                                        // TypeRepr inspection is more reliable than `'[SqlSchema[t]]` quote matching
                                        // because opaque-type aliases sometimes erase to the underlying type at this point.
                                        schemaArg.tpe.dealias match
                                            case AppliedType(_, arg :: Nil) => arg.show
                                            case other                      => other.show
                                    case _ => "?"
                            val msg =
                                s".runStatic / staticSql: SqlSchema[$schemaTypeShown] cannot be folded at compile time " +
                                    s"for resolving the $methodLabel. " +
                                    s"Declare it as `inline given SqlSchema[$schemaTypeShown] = ...` " +
                                    "(plain `given` definitions have invisible bodies in the Scala 3 macro API). " +
                                    "If you don't need static folding, use `.run` (opportunistic + runtime fallback) " +
                                    "or `.runDynamic` (skip static folding)."
                            report.error(msg, apply.pos)
                            count += 1
                        end if
                        super.traverseTree(apply)(owner)
                    case other => super.traverseTree(other)(owner)
        end Traverser

        Traverser.traverseTree(term.asInstanceOf[Tree])(Symbol.spliceOwner)
        count
    end emitOpaqueCauses

    /** Same as [[impl]] but returns `None` instead of `errorAndAbort` when the AST is not liftable.
      *
      * Used by `SqlRunMacro`'s `.run` fast-path to avoid two FromExpr traversals (one to probe liftability, another to render): the lift
      * and the render happen in a single pass and the result is returned. Saves a full AST walk per static call site at compile time
      * (PHASE-7 audit W-3).
      */
    def tryImpl(q: Expr[SqlAst.Executable[?]])(using Quotes): Option[Expr[SqlStatic.Rendered]] =
        liftAst(q).map(ast => renderLifted(ast, q))

    /** Single FromExpr-driven lift of the full Executable AST. Returns `None` when the AST cannot be lifted (dynamic schema, non-inline
      * record fragment, etc.). Imports the column-projection / Record givens that the derivation needs.
      */
    private def liftAst(q: Expr[SqlAst.Executable[?]])(using Quotes): Option[SqlAst.Executable[?]] =
        import kyo.internal.ColumnFromExpr.given
        import kyo.internal.RecordFromExpr.given
        given scala.quoted.FromExpr[SqlAst.Executable[?]] = kyo.FromExpr.derived
        q.value
    end liftAst

    /** Renders a pre-lifted AST for both backends and lifts the result back to an `Expr[SqlStatic.Rendered]`. `posExpr` is only used to
      * anchor the `report.errorAndAbort` for the cross-backend bind-count divergence check.
      */
    private def renderLifted(ast: SqlAst.Executable[?], posExpr: Expr[?])(using Quotes): Expr[SqlStatic.Rendered] =
        import quotes.reflect.*

        // Render the lifted AST for both backends at compile time. SqlRender.render is a pure function —
        // safe to call during macro expansion.
        val pgRendered = kyo.internal.SqlRender.render(ast, SqlBackend.Postgres)
        val myRendered = kyo.internal.SqlRender.render(ast, SqlBackend.Mysql)

        // Defensive check: bind param count must agree across backends.
        if pgRendered.binds.size != myRendered.binds.size then
            report.errorAndAbort(
                s"staticSql: backend divergence — " +
                    s"Postgres produced ${pgRendered.binds.size} binds, " +
                    s"MySQL produced ${myRendered.binds.size}",
                posExpr.asTerm.pos
            )
        end if

        val pgSql: Expr[String]                = Expr(pgRendered.sql)
        val mySql: Expr[String]                = Expr(myRendered.sql)
        val params: Expr[Chunk[BoundValue[?]]] = liftParams(pgRendered)

        '{
            SqlStatic.Rendered(
                BackendSql($pgSql, $mySql),
                $params
            )
        }
    end renderLifted

    /** Lifts the renderer's `RenderedBind` list back to an `Expr[Chunk[BoundValue[?]]]`.
      *
      * Each `RenderedBind` carries only the runtime `value` + `SqlSchema` (no `quoted.Type`, which cannot be synthesised from a free type
      * parameter). `liftOne` dispatches on the bound value's runtime class to recover the concrete type, then emits a
      * `BoundValue[t](valueExpr, schemaExpr)` with the stdlib / `SqlSchema`-provided `ToExpr` and the summoned `SqlSchema` in scope.
      */
    private def liftParams(rendered: kyo.internal.SqlRender.Rendered)(using Quotes): Expr[Chunk[BoundValue[?]]] =
        import quotes.reflect.*
        val exprs: List[Expr[BoundValue[?]]] = rendered.binds.toList.map(rb => liftOne(rb))
        '{ Chunk.from(${ Expr.ofList(exprs) }) }
    end liftParams

    /** Builds a `BoundValue[t]` expression for one rendered bind, with the concrete `t` recovered from the value's runtime class. */
    private def liftOne(rb: kyo.internal.SqlRender.RenderedBind[?])(using Quotes): Expr[BoundValue[?]] =
        import quotes.reflect.*

        // Emits `BoundValue[T](valueExpr, schemaExpr)` for a known concrete type `T`, summoning its
        // `SqlSchema[T]` at the macro use site. The `ToExpr[T]` is supplied by the caller arm.
        def bound[T: Type](valueExpr: Expr[T]): Expr[BoundValue[?]] =
            val schemaExpr: Expr[SqlSchema[T]] =
                Expr.summon[SqlSchema[T]].getOrElse {
                    report.errorAndAbort(
                        s"staticSql: cannot summon SqlSchema[${Type.show[T]}] for a bound value. " +
                            "Use a stable given definition (not a local val)."
                    )
                }
            '{ BoundValue[T]($valueExpr, $schemaExpr) }.asExprOf[BoundValue[?]]
        end bound

        // A type-pattern binds `v` at `T & rb.A` (a GADT refinement of the existential bind type);
        // the `(v: T)` ascription drops the phantom `& rb.A` intersection so the concrete `ToExpr[T]`
        // (stdlib or `SqlSchema`-provided) resolves.
        rb.value match
            case v: Int        => bound[Int](Expr(v: Int))
            case v: Long       => bound[Long](Expr(v: Long))
            case v: String     => bound[String](Expr(v: String))
            case v: Boolean    => bound[Boolean](Expr(v: Boolean))
            case v: Double     => bound[Double](Expr(v: Double))
            case v: Float      => bound[Float](Expr(v: Float))
            case v: Short      => bound[Short](Expr(v: Short))
            case v: Byte       => bound[Byte](Expr(v: Byte))
            case v: BigDecimal => bound[BigDecimal](Expr(v: BigDecimal))
            case v: java.time.LocalDate =>
                given ToExpr[java.time.LocalDate] = SqlSchema.toExprLocalDate
                bound[java.time.LocalDate](Expr(v: java.time.LocalDate))
            case v: java.time.LocalDateTime =>
                given ToExpr[java.time.LocalDateTime] = SqlSchema.toExprLocalDateTime
                bound[java.time.LocalDateTime](Expr(v: java.time.LocalDateTime))
            case v: java.time.LocalTime =>
                given ToExpr[java.time.LocalTime] = SqlSchema.toExprLocalTime
                bound[java.time.LocalTime](Expr(v: java.time.LocalTime))
            case v: kyo.Instant @unchecked =>
                given ToExpr[kyo.Instant] = SqlSchema.toExprKyoInstant
                bound[kyo.Instant](Expr(v: kyo.Instant))
            // @unchecked: Span is phantom in its element type at runtime; the pattern is safe by construction.
            case v: kyo.Span[Byte] @unchecked =>
                // SqlSchema only registers `SqlSchema[Span[Byte]]`, so any `Span` bind is byte-typed;
                // `Span` is phantom in its element type at runtime.
                given ToExpr[kyo.Span[Byte]] = SqlSchema.toExprSpanByte
                bound[kyo.Span[Byte]](Expr(v: kyo.Span[Byte]))
            case other =>
                report.errorAndAbort(
                    s"staticSql: no ToExpr available for bind value of runtime type ${other.getClass.getName}. " +
                        "Add a ToExpr instance to object SqlSchema and a dispatch arm to SqlStaticMacro.liftOne."
                )
        end match
    end liftOne

end SqlStaticMacro
