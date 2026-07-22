package kyo.internal

import kyo.*
import kyo.SqlAst.*
import kyo.SqlSchema.BoundValue

/** Walks the [[kyo.SqlAst]] tree to produce a SQL string with positional bind placeholders, plus the ordered bind values.
  *
  * Backend differences:
  *   - **Postgres** uses `$1`, `$2`, … for placeholders and `"…"` for identifier quoting.
  *   - **MySQL** uses `?` for placeholders (un-numbered) and `` `…` `` for identifier quoting.
  *
  * The renderer's pattern matches over the sealed [[SqlAst]] families are closed, every new node forces an explicit case here.
  *
  * Post-Phase-6.5: the AST is pure data. [[GroupBy.view]] / [[GroupBy.havingTerm]] / [[Projection.Resolved]] / [[OrderingSpecs.Resolved]]
  * carry already-materialised payloads; the renderer never materialises a `Record[F]` view itself.
  */
object SqlRender:

    /** Bundles a macro-time bind value with its `SqlSchema`, captured in lockstep by the renderer when it processes a `Literal[A]` or
      * `Fragment.Bind[A]` node.
      *
      * Carries no `scala.quoted.Type[A]`: a `quoted.Type` cannot be synthesised from the free type parameter `A` inside the generic
      * `appendPlaceholder[A]` call site. The static macro's `liftParams` recovers each bind's concrete type by dispatching on the bound
      * value's runtime class instead.
      */
    final case class RenderedBind[A](value: A, schema: SqlSchema[A])

    /** Result of rendering a statement or query.
      *
      * `params` is the runtime JDBC bind list. `binds` is macro-only metadata captured in lockstep with `params`, the static-SQL macro
      * lifts each `RenderedBind` to an `Expr[BoundValue[?]]`.
      */
    final case class Rendered private[kyo] (
        sql: String,
        params: Chunk[BoundValue[?]],
        binds: Chunk[RenderedBind[?]]
    )

    /** Unified render entry point, accepts any [[SqlAst.SqlAst]] node (queries, actions, terms, fragments). */
    def render(ast: SqlAst.SqlAst[?], backend: SqlBackend): Rendered =
        val s = new State(backend, Frame.internal)
        s.dispatch(ast)
        s.result
    end render

    /** Runtime variant with call-site [[Frame]] for actionable backend-unsupported errors. */
    private[kyo] def render(ast: SqlAst.SqlAst[?], backend: SqlBackend, frame: Frame): Rendered =
        val s = new State(backend, frame)
        s.dispatch(ast)
        s.result
    end render

    def render(statement: Action[?], backend: SqlBackend): Rendered =
        val s = new State(backend, Frame.internal)
        s.action(statement)
        s.result
    end render

    /** Runtime variant that carries the call-site [[Frame]] so backend-unsupported operations surface a typed, actionable error.
      *
      * Prefer this variant in all runtime paths (SqlRun, SqlRunMacro). The compile-time macro path (SqlStaticMacro) uses the frameless
      * variant above because no user Frame exists during macro expansion; any throw there surfaces as a macro compile error.
      */
    private[kyo] def render(statement: Action[?], backend: SqlBackend, frame: Frame): Rendered =
        val s = new State(backend, frame)
        s.action(statement)
        s.result
    end render

    def render(query: Query[?], backend: SqlBackend): Rendered =
        val s = new State(backend, Frame.internal)
        s.query(query)
        s.result
    end render

    private[kyo] def render(query: Query[?], backend: SqlBackend, frame: Frame): Rendered =
        val s = new State(backend, frame)
        s.query(query)
        s.result
    end render

    def render(term: Term[?], backend: SqlBackend): Rendered =
        val s = new State(backend, Frame.internal)
        s.term(term)
        s.result
    end render

    private[kyo] def render(term: Term[?], backend: SqlBackend, frame: Frame): Rendered =
        val s = new State(backend, frame)
        s.term(term)
        s.result
    end render

    /** Backend-aware identifier quoting. */
    def quoteIdent(ident: String, backend: SqlBackend): String =
        backend match
            case _: SqlBackend.Postgres =>
                val escaped = ident.replace("\"", "\"\"")
                s""""$escaped""""
            case _: SqlBackend.Mysql =>
                val escaped = ident.replace("`", "``")
                s"`$escaped`"

    // (Table-name extraction is now carried by the `tableName` field on `Table` / `Insert` / `Update` / `Delete`, derived from the
    // case class label at construction time.)

    /** Mutable rendering accumulator. Not thread-safe, one instance per render call.
      *
      * @param backend
      *   the target SQL backend; drives all backend-specific rendering forks.
      * @param frame
      *   call-site frame carried from the render entry point. Backend-unsupported operations (e.g. MySQL `ON DUPLICATE KEY UPDATE … WHERE`)
      *   throw a [[kyo.SqlUnsupportedException]] leaf using this frame. The frameless render entry points pass [[Frame.internal]] so the
      *   throw still identifies the render layer.
      */
    final private class State(backend: SqlBackend, frame: Frame):
        private val sb         = new StringBuilder
        private val paramBuf   = scala.collection.mutable.ArrayBuffer.empty[BoundValue[?]]
        private val bindBuf    = scala.collection.mutable.ArrayBuffer.empty[RenderedBind[?]]
        private var nextParamN = 1

        def result: Rendered = Rendered(sb.toString, Chunk.from(paramBuf.toSeq), Chunk.from(bindBuf.toSeq))
        def append(s: String): Unit =
            sb.append(s)
            ()
        def quoted(ident: String): String     = quoteIdent(ident, backend)
        def appendQuoted(ident: String): Unit = append(quoted(ident))

        private def joinWith[A](sep: String)(items: IterableOnce[A])(f: A => Unit): Unit =
            var first = true
            items.iterator.foreach { item =>
                if !first then append(sep)
                first = false
                f(item)
            }
        end joinWith

        def appendPlaceholder[A](v: A, sch: SqlSchema[A]): Unit =
            paramBuf.append(BoundValue(v, sch))
            bindBuf.append(RenderedBind[A](v, sch))
            backend match
                case _: SqlBackend.Postgres =>
                    append(s"$$$nextParamN")
                    nextParamN += 1
                case _: SqlBackend.Mysql =>
                    append("?")
            end match
        end appendPlaceholder

        // --- Dispatch --- (routes the unified SqlAst[?] root to the correct renderer)

        def dispatch(ast: SqlAst.SqlAst[?]): Unit = ast match
            case q: Query[?]  => query(q)
            case a: Action[?] => action(a)
            case t: Term[?]   => term(t)

        // --- Actions (DML) ---

        def action(s: Action[?]): Unit = s match
            case i: Insert[?, ?] => insert(i)
            case u: Update[?, ?] => update(u)
            case d: Delete[?, ?] => delete(d)

        // --- Queries ---

        def query(q: Query[?]): Unit = q match
            case t: Table[?, ?]      => appendSelectStar(t.alias, t.columnNames); append(" FROM "); fromSource(t)
            case n: Nested[?, ?]     => appendSelectStar(n.alias, n.columnNames); append(" FROM "); fromSource(n)
            case l: Lateral[?, ?]    => appendSelectStar(l.alias, l.columnNames); append(" FROM "); fromSource(l)
            case v: ValuesFrom[?, ?] => appendSelectStar(v.alias, v.columnNames); append(" FROM "); fromSource(v)
            // Joins project a tuple result whose column↔field alignment is enforced by `Select.to`'s
            // `Mirror.ProductOf` constraint; the SELECT-* positional hazard (WARN-1) is single-table only,
            // and a merged join projection has no single Schema-field order, so joins keep the literal `*`.
            case j: Join[?, ?]       => append("SELECT * FROM "); fromSource(j)
            case cj: CrossJoin[?, ?] => append("SELECT * FROM "); fromSource(cj)
            case w: Where[?, ?] =>
                appendSelectStarFor(w.sql); append(" FROM "); fromSource(w.sql); append(" WHERE "); term(w.predicate)
            case g: GroupBy[?, ?]     => selectStarGroupBy(g)
            case s: Select[?, ?]      => selectClause(s)
            case o: OrderBy[?]        => selectStarOrderBy(o)
            case l: Limit[?]          => limitClause(l)
            case lk: Lock[?]          => lockClause(lk)
            case so: SetOp[?]         => setOp(so)
            case w: With[?]           => cte(w.ctes, w.body, recursive = false)
            case wr: WithRecursive[?] => cte(wr.ctes, wr.body, recursive = true)
            case a: AggregateQuery[?] => aggregateQuery(a)

        /** Emits the `SELECT` clause for a single-table implicit-projection (`SELECT *`-shaped) query.
          *
          * Instead of a literal `*`, the renderer emits the source's columns explicitly, in `Schema`-field-declaration order, supplied by
          * the `From` node's `columnNames` (computed at construction by `SqlMacros.columnNames[T]`, which walks `caseFields`). This
          * guarantees the n-th projected column is always the n-th Schema field, so the positional row decoder
          * (`PostgresRowReader`/`MysqlRowReader`) is safe even when a table's physical DDL column order diverges from the case-class field
          * order. A literal `*` would surface columns in DDL order and silently mis-decode such tables.
          *
          * `columnNames` is the reliable order source: the `columns` `Record`'s `Dict` does not preserve declaration order. Falls back to a
          * literal `*` only when `columnNames` is empty (a zero-field row, which the prior renderer also rendered as `*`).
          */
        private def appendSelectStar(alias: String, columnNames: Chunk[String]): Unit =
            append("SELECT ")
            if columnNames.isEmpty then append("*")
            else
                joinWith(", ")(columnNames) { name =>
                    if alias.nonEmpty then
                        appendQuoted(alias)
                        append(".")
                    appendQuoted(name)
                }
            end if
        end appendSelectStar

        private def selectStarGroupBy(g: GroupBy[?, ?]): Unit =
            // A grouped query's implicit projection keeps the literal `*`: the WARN-1 positional-decoding hazard
            // is single-table-shaped, and a grouped result's column set is not a `Schema`-field-ordered row.
            append("SELECT * FROM ")
            g.source match
                case f: From[?, ?] => fromSource(f)
                case other         => append("("); query(other); append(") AS sub")
            renderGroupByClause(g)
            g.havingTerm.foreach: t =>
                append(" HAVING "); term(t)
        end selectStarGroupBy

        private def renderGroupByClause(g: GroupBy[?, ?]): Unit =
            g.kind match
                case GroupBy.Kind.Plain =>
                    append(" GROUP BY "); renderKeys(g.keys)
                case GroupBy.Kind.Rollup =>
                    backend match
                        case _: SqlBackend.Postgres =>
                            append(" GROUP BY ROLLUP ("); renderKeys(g.keys); append(")")
                        case _: SqlBackend.Mysql =>
                            append(" GROUP BY "); renderKeys(g.keys); append(" WITH ROLLUP")
                case GroupBy.Kind.Cube =>
                    backend match
                        case _: SqlBackend.Postgres =>
                            append(" GROUP BY CUBE ("); renderKeys(g.keys); append(")")
                        case _: SqlBackend.Mysql =>
                            // MySQL 8.0+ supports the same CUBE keyword as Postgres.
                            append(" GROUP BY CUBE ("); renderKeys(g.keys); append(")")
                case GroupBy.Kind.GroupingSets(sets) =>
                    // Per SQL:1999, GROUPING SETS lists each grouping in parens; the empty grouping renders as `()` and corresponds to
                    // the grand-total row. Same syntax on Postgres and MySQL 8.0+.
                    append(" GROUP BY GROUPING SETS (")
                    var first = true
                    sets.foreach { setKeys =>
                        if !first then append(", ")
                        first = false
                        append("(")
                        renderKeys(setKeys)
                        append(")")
                    }
                    append(")")
        end renderGroupByClause

        private def selectStarOrderBy(o: OrderBy[?]): Unit =
            appendSelectStarFor(o.sql)
            append(" FROM ")
            innerSource(o.sql)
            append(" ORDER BY "); renderOrdering(o.specs)
        end selectStarOrderBy

        /** Emits the `SELECT <cols>` clause for an implicit-projection query that wraps another query (ORDER BY / WHERE). Recurses to the
          * single-table column-bearing source; falls back to a literal `*` only if no such source can be located.
          */
        private def appendSelectStarFor(q: Query[?]): Unit =
            sourceColumns(q) match
                case Present((alias, columnNames)) => appendSelectStar(alias, columnNames)
                case Absent                        => append("SELECT *")
        end appendSelectStarFor

        /** Locates the single-table `(alias, columnNames)` projected by an implicit-projection query, recursing through wrappers.
          *
          * Returns `Absent` for joins (a merged projection has no single Schema-field order) and arbitrary sub-queries; the caller then
          * falls back to a literal `*`. The positional-decoding hazard (WARN-1) is single-table only, so this is sufficient.
          */
        private def sourceColumns(q: Query[?]): Maybe[(String, Chunk[String])] = q match
            case t: Table[?, ?]      => Maybe((t.alias, t.columnNames))
            case n: Nested[?, ?]     => Maybe((n.alias, n.columnNames))
            case l: Lateral[?, ?]    => Maybe((l.alias, l.columnNames))
            case v: ValuesFrom[?, ?] => Maybe((v.alias, v.columnNames))
            case w: Where[?, ?]      => sourceColumns(w.sql)
            case o: OrderBy[?]       => sourceColumns(o.sql)
            case l: Limit[?]         => sourceColumns(l.sql)
            case lk: Lock[?]         => sourceColumns(lk.sql)
            case _                   => Absent
        end sourceColumns

        private def selectClause(s: Select[?, ?]): Unit =
            append(if s.isDistinct then "SELECT DISTINCT " else "SELECT ")
            renderProjection(s.terms)
            append(" FROM "); innerSource(s.sql)
        end selectClause

        private def limitClause(l: Limit[?]): Unit =
            query(l.sql)
            append(s" LIMIT ${l.n}")
            if l.offset > 0 then append(s" OFFSET ${l.offset}")
        end limitClause

        private def lockClause(lk: Lock[?]): Unit =
            query(lk.sql)
            val modeKw = lk.mode match
                case Lock.Mode.Update => "FOR UPDATE"
                case Lock.Mode.Share  => "FOR SHARE"
            append(" "); append(modeKw)
            if lk.ofTables.nonEmpty then
                append(" OF ")
                append(lk.ofTables.toSeq.map(quoted).mkString(", "))
            lk.behavior match
                case Lock.Behavior.Wait       => ()
                case Lock.Behavior.NoWait     => append(" NOWAIT")
                case Lock.Behavior.SkipLocked => append(" SKIP LOCKED")
            end match
        end lockClause

        private def setOp(s: SetOp[?]): Unit =
            val isIntersectOrExcept = s.kind match
                case SetOp.Kind.Intersect | SetOp.Kind.IntersectAll |
                    SetOp.Kind.Except | SetOp.Kind.ExceptAll => true
                case _ => false
            if isIntersectOrExcept then
                backend match
                    case m: SqlBackend.Mysql if !m.supportsIntersectExcept =>
                        throw SqlUnsupportedMysqlVersionFeatureException(
                            "INTERSECT / EXCEPT",
                            "8.0.31",
                            s"${m.serverVersion._1}.${m.serverVersion._2}.${m.serverVersion._3}"
                        )(using frame)
                    case _ =>
                end match
            end if
            query(s.left)
            val kw = s.kind match
                case SetOp.Kind.Union        => " UNION "
                case SetOp.Kind.UnionAll     => " UNION ALL "
                case SetOp.Kind.Intersect    => " INTERSECT "
                case SetOp.Kind.IntersectAll => " INTERSECT ALL "
                case SetOp.Kind.Except       => " EXCEPT "
                case SetOp.Kind.ExceptAll    => " EXCEPT ALL "
            append(kw)
            query(s.right)
        end setOp

        private def cte(ctes: Chunk[CommonTable[?]], body: Query[?], recursive: Boolean): Unit =
            if recursive then
                backend match
                    case m: SqlBackend.Mysql if !m.supportsRecursiveCte =>
                        throw SqlUnsupportedMysqlVersionFeatureException(
                            "WITH RECURSIVE",
                            "8.0",
                            s"${m.serverVersion._1}.${m.serverVersion._2}.${m.serverVersion._3}"
                        )(using frame)
                    case _ =>
                        append("WITH RECURSIVE ")
                end match
            else
                append("WITH ")
            end if
            joinWith(", ")(ctes) { c =>
                appendQuoted(c.name); append(" AS ("); query(c.query); append(")")
            }
            append(" "); query(body)
        end cte

        private def aggregateQuery(a: AggregateQuery[?]): Unit =
            append("SELECT ")
            term(a.agg)
            append(" FROM ")
            innerSource(a.source)
        end aggregateQuery

        // --- FROM sources ---

        private def fromSource(f: From[?, ?]): Unit = f match
            case t: Table[?, ?] =>
                appendQuoted(t.tableName); append(" "); appendQuoted(t.alias)
            case n: Nested[?, ?] =>
                append("("); query(n.source); append(") "); appendQuoted(n.alias)
            case l: Lateral[?, ?] =>
                backend match
                    case m: SqlBackend.Mysql if !m.supportsLateral =>
                        throw SqlUnsupportedMysqlVersionFeatureException(
                            "LATERAL",
                            "8.0.14",
                            s"${m.serverVersion._1}.${m.serverVersion._2}.${m.serverVersion._3}"
                        )(using frame)
                    case _ =>
                        append("LATERAL ("); query(l.source); append(") "); appendQuoted(l.alias)
                end match
            case v: ValuesFrom[?, ?] =>
                renderValuesFrom(v)
            case j: Join[?, ?] =>
                j.kind match
                    case JoinKind.FullOuter =>
                        backend match
                            case _: SqlBackend.Postgres =>
                                fromSource(j.left)
                                append(" FULL OUTER JOIN ")
                                fromSource(j.right)
                                append(" ON ")
                                term(j.predicate)
                            case _: SqlBackend.Mysql =>
                                // MySQL has no FULL OUTER JOIN, synthesize via LEFT JOIN UNION RIGHT JOIN.
                                fromSource(j.left)
                                append(" LEFT JOIN ")
                                fromSource(j.right)
                                append(" ON ")
                                term(j.predicate)
                                append(" UNION SELECT * FROM ")
                                fromSource(j.left)
                                append(" RIGHT JOIN ")
                                fromSource(j.right)
                                append(" ON ")
                                term(j.predicate)
                    case _ =>
                        fromSource(j.left)
                        val jk = j.kind match
                            case JoinKind.Inner     => " INNER JOIN "
                            case JoinKind.Left      => " LEFT JOIN "
                            case JoinKind.Right     => " RIGHT JOIN "
                            case JoinKind.FullOuter => " FULL OUTER JOIN " // unreachable; covered above
                        append(jk); fromSource(j.right); append(" ON "); term(j.predicate)
            case cj: CrossJoin[?, ?] =>
                fromSource(cj.left); append(" CROSS JOIN "); fromSource(cj.right)

        /** For sources that wrap a query (Where/GroupBy/etc.), reuse the chain or render as a sub-query. */
        private def innerSource(q: Query[?]): Unit = q match
            case f: From[?, ?] => fromSource(f)
            case w: Where[?, ?] =>
                fromSource(w.sql); append(" WHERE "); term(w.predicate)
            case g: GroupBy[?, ?] =>
                innerSource(g.source)
                renderGroupByClause(g)
                g.havingTerm.foreach(t =>
                    append(" HAVING "); term(t)
                )
            case o: OrderBy[?] =>
                innerSource(o.sql); append(" ORDER BY "); renderOrdering(o.specs)
            case l: Limit[?] =>
                innerSource(l.sql); append(s" LIMIT ${l.n}")
                if l.offset > 0 then append(s" OFFSET ${l.offset}")
            case other =>
                append("("); query(other); append(") AS sub")

        private def renderValuesFrom(v: ValuesFrom[?, ?]): Unit =
            append("(VALUES ")
            renderDecomposedRows(v.rows)
            append(") "); appendQuoted(v.alias)
        end renderValuesFrom

        /** Renders decomposed VALUES rows (`Chunk[Chunk[BoundValue[?]]]`) as `(…), (…)`. Each cell is emitted as an inline literal for
          * primitive SQL-safe types (numerics, String, Boolean, NULL) and as a positional placeholder (`$N` / `?`) for all other types
          * (e.g. `java.time.Duration`, `java.time.LocalDate`, custom binary types). This ensures that types without a valid SQL literal
          * representation (such as INTERVAL / TIME) are correctly bound via the extended-query protocol.
          */
        private def renderDecomposedRows(rows: Chunk[Chunk[BoundValue[?]]]): Unit =
            joinWith(", ")(rows) { row =>
                append("(")
                joinWith(", ")(row) { cell => renderBoundValueCell(cell) }
                append(")")
            }
        end renderDecomposedRows

        // Dispatch on the concrete BoundValue[A] type to preserve the typed appendPlaceholder[A] call.
        private def renderBoundValueCell[A](bv: BoundValue[A]): Unit =
            bv.value match
                case s: String      => append("'"); append(s.replace("'", "''")); append("'")
                case b: Boolean     => append(if b then "TRUE" else "FALSE")
                case _: Absent.type => append("NULL")
                case _: Int | _: Long | _: Short | _: Byte | _: Float | _: Double | _: BigDecimal | _: BigInt =>
                    append(bv.value.toString)
                case _ => appendPlaceholder(bv.value, bv.schema)

        // --- SELECT projection and ORDER BY ---

        private def renderProjection(p: Projection): Unit = p match
            case Projection.Resolved(terms) =>
                if terms.isEmpty then append("*")
                else renderTermList(terms)

        private def renderOrdering(o: OrderingSpecs): Unit = o match
            case OrderingSpecs.Resolved(specs) => renderOrderSpecList(specs)

        private def renderTermList(terms: Chunk[Term[?]]): Unit =
            joinWith(", ")(terms)(renderProjectedTerm)
        end renderTermList

        private def renderOrderSpecList(specs: Chunk[OrderSpec]): Unit =
            joinWith(", ")(specs)(orderSpec)
        end renderOrderSpecList

        private def renderProjectedTerm(t: Term[?]): Unit = t match
            case lt: LabelledTerm[?, ?] =>
                term(lt.term); append(" AS "); appendQuoted(lt.label)
            case other =>
                term(other)

        private def orderSpec(s: OrderSpec): Unit =
            backend match
                case _: SqlBackend.Mysql =>
                    // MySQL has fixed null-ordering semantics (NULLs first for ASC, NULLs last for DESC).
                    // The NULLS FIRST / NULLS LAST syntax is a parse error on MySQL.
                    // Lower to a two-term expression when the user requests non-default null placement:
                    //   ASC  NULLS FIRST → no change      (MySQL ASC default: NULLs first)
                    //   ASC  NULLS LAST  → `<x> IS NULL, <x> ASC`  (pushes NULLs to the end)
                    //   DESC NULLS FIRST → `<x> IS NOT NULL, <x> DESC` (pulls NULLs to the front)
                    //   DESC NULLS LAST  → no change      (MySQL DESC default: NULLs last)
                    (s.direction, s.nulls) match
                        case (OrderSpec.Direction.Asc, OrderSpec.Nulls.Last) =>
                            term(s.expr); append(" IS NULL, "); term(s.expr); append(" ASC")
                        case (OrderSpec.Direction.Desc, OrderSpec.Nulls.First) =>
                            term(s.expr); append(" IS NOT NULL, "); term(s.expr); append(" DESC")
                        case (OrderSpec.Direction.Asc, _) =>
                            term(s.expr); append(" ASC")
                        case (OrderSpec.Direction.Desc, _) =>
                            term(s.expr); append(" DESC")
                case _: SqlBackend.Postgres =>
                    term(s.expr)
                    s.direction match
                        case OrderSpec.Direction.Asc  => append(" ASC")
                        case OrderSpec.Direction.Desc => append(" DESC")
                    s.nulls match
                        case OrderSpec.Nulls.Default => ()
                        case OrderSpec.Nulls.First   => append(" NULLS FIRST")
                        case OrderSpec.Nulls.Last    => append(" NULLS LAST")
                    end match
        end orderSpec

        private def renderKeys(keys: Chunk[Term[?]]): Unit =
            joinWith(", ")(keys)(term)
        end renderKeys

        // --- Terms ---

        def term(t: Term[?]): Unit = t match
            case c: Column[?, ?] =>
                if c.alias.nonEmpty then
                    appendQuoted(c.alias)
                    append(".")
                appendQuoted(c.sqlName)
            case l: Literal[?]           => renderLiteral(l)
            case lt: LabelledTerm[?, ?]  => term(lt.term)
            case gc: GroupedColumn[?, ?] => term(gc.column)
            case c: SqlAst.Comparison[?] => binary(c.left, opSql(c.op), c.right)
            case lg: Logical             => binary(lg.left, opSql(lg.op), lg.right)
            case ar: Arithmetic[?]       => binary(ar.left, opSql(ar.op), ar.right)
            case sm: StringMatch         => stringMatch(sm)
            case n: Not                  => append("(NOT "); term(n.expr); append(")")
            case bt: BoolTest =>
                append("("); term(bt.expr); append(" "); append(predSql(bt.pred)); append(")")
            case inn: IsNull[?]      => append("("); term(inn.expr); append(" IS NULL)")
            case inotn: IsNotNull[?] => append("("); term(inotn.expr); append(" IS NOT NULL)")
            case iu: IsUnknown       => append("("); term(iu.expr); append(" IS UNKNOWN)")
            case inu: IsNotUnknown   => append("("); term(inu.expr); append(" IS NOT UNKNOWN)")
            case b: Between[?] =>
                append("("); term(b.expr); append(" BETWEEN "); term(b.low); append(" AND "); term(b.high); append(")")
            case iv: InValues[?]       => inList(iv.expr, iv.values, negated = false)
            case niv: NotInValues[?]   => inList(niv.expr, niv.values, negated = true)
            case is: InSubquery[?]     => inSub(is.expr, is.subquery, negated = false)
            case nis: NotInSubquery[?] => inSub(nis.expr, nis.subquery, negated = true)
            case e: Exists             => append("EXISTS ("); query(e.subquery); append(")")
            case ne: NotExists         => append("NOT EXISTS ("); query(ne.subquery); append(")")
            case ss: ScalarSub[?]      => append("("); query(ss.subquery); append(")")
            case co: Coalesce[?] =>
                append("COALESCE(")
                joinWith(", ")(co.exprs)(term)
                append(")")
            case nf: NullIf[?] =>
                append("NULLIF("); term(nf.left); append(", "); term(nf.right); append(")")
            case cn: Concat =>
                backend match
                    case _: SqlBackend.Postgres =>
                        append("(")
                        joinWith(" || ")(cn.parts)(term)
                        append(")")
                    case _: SqlBackend.Mysql =>
                        // MySQL uses CONCAT(a, b, …), flatten any nested Concat nodes.
                        append("CONCAT(")
                        joinWith(", ")(flatConcatParts(cn))(term)
                        append(")")
            case sub: Substring =>
                append("SUBSTRING(")
                term(sub.expr); append(" FROM "); term(sub.start)
                sub.length.foreach(l =>
                    append(" FOR "); term(l)
                )
                append(")")
            case nf: NumericFn[?] =>
                val name = nf.op match
                    case NumericFn.Op.Abs     => "ABS"
                    case NumericFn.Op.Ceiling => "CEILING"
                    case NumericFn.Op.Floor   => "FLOOR"
                    case NumericFn.Op.Round   => "ROUND"
                append(name); append("("); term(nf.expr); append(")")
            case sf: StringFn =>
                val name = sf.op match
                    case StringFn.Op.Upper => "UPPER"
                    case StringFn.Op.Lower => "LOWER"
                    case StringFn.Op.Trim  => "TRIM"
                append(name); append("("); term(sf.expr); append(")")
            case sl: StringLength => append("LENGTH("); term(sl.expr); append(")")
            case ce: CaseExpr[?] =>
                append("CASE")
                ce.whens.foreach: w =>
                    append(" WHEN "); term(w._1); append(" THEN "); term(w._2)
                append(" ELSE "); term(ce.otherwise); append(" END")
            case cen: CaseExprNullable[?] =>
                append("CASE")
                cen.whens.foreach: w =>
                    append(" WHEN "); term(w._1); append(" THEN "); term(w._2)
                append(" END")
            case cast: Cast[?, ?] =>
                append("CAST("); term(cast.expr); append(" AS "); append(cast.sqlTypeName); append(")")
            case _: Default[?] => append("DEFAULT")
            case fc: FunctionCall[?] =>
                append(fc.name); append("(")
                joinWith(", ")(fc.args)(term)
                append(")")
            case raw: RawSql[?]  => append(raw.sql)
            case fr: Fragment[?] => fragment(fr)
            case ex: Excluded[?, ?] =>
                backend match
                    case _: SqlBackend.Postgres => append("EXCLUDED."); appendQuoted(ex.column.sqlName)
                    case _: SqlBackend.Mysql    => append("VALUES("); appendQuoted(ex.column.sqlName); append(")")
            case w: Windowed[?] =>
                w.inner match
                    case ac: Aggregate.Call[?] => aggregateCall(ac)
                    case wf: WindowFunction[?] => windowFn(wf)
                append(" OVER ("); windowSpecBody(w.spec); append(")")
            case ag: Aggregate.Call[?] => aggregateCall(ag)
            case q: Query[?]           => append("("); query(q); append(")")
        end term

        private def renderLiteral(l: Literal[?]): Unit =
            // Type-erased value with its schema. The placeholder + schema-typed bind is safe; schema enforces value type.
            l match
                case lit: Literal[a] => appendPlaceholder[a](lit.value, lit.schema)

        private def fragment(f: Fragment[?]): Unit =
            f.parts.foreach:
                case Fragment.Lit(t)     => append(t)
                case b: Fragment.Bind[a] => appendPlaceholder[a](b.value, b.schema)
                case Fragment.Embed(t)   => term(t)

        private def binary(left: Term[?], op: String, right: Term[?]): Unit =
            append("("); term(left); append(" "); append(op); append(" "); term(right); append(")")

        private def inList(expr: Term[?], values: Chunk[Term[?]], negated: Boolean): Unit =
            append("("); term(expr); append(if negated then " NOT IN (" else " IN (")
            joinWith(", ")(values)(term)
            append("))")
        end inList

        private def inSub(expr: Term[?], q: Query[?], negated: Boolean): Unit =
            append("("); term(expr); append(if negated then " NOT IN (" else " IN (")
            query(q); append("))")

        private def aggregateCall(a: Aggregate.Call[?]): Unit = a match
            case c: Aggregate.Count =>
                append("COUNT(")
                if c.distinct then append("DISTINCT ")
                c.expr match
                    case Present(e) => term(e)
                    case _          => append("*")
                append(")")
            case s: Aggregate.Sum[?] =>
                append("SUM(")
                if s.distinct then append("DISTINCT ")
                term(s.expr); append(")")
            case a: Aggregate.Avg[?] =>
                append("AVG(")
                if a.distinct then append("DISTINCT ")
                term(a.expr); append(")")
            case mn: Aggregate.Min[?] => append("MIN("); term(mn.expr); append(")")
            case mx: Aggregate.Max[?] => append("MAX("); term(mx.expr); append(")")

        private def windowFn(w: WindowFunction[?]): Unit = w match
            case _: WindowFunction.RowNumber.type   => append("ROW_NUMBER()")
            case _: WindowFunction.Rank.type        => append("RANK()")
            case _: WindowFunction.DenseRank.type   => append("DENSE_RANK()")
            case _: WindowFunction.PercentRank.type => append("PERCENT_RANK()")
            case _: WindowFunction.CumeDist.type    => append("CUME_DIST()")
            case nt: WindowFunction.Ntile           => append("NTILE("); term(nt.n); append(")")
            case ld: WindowFunction.Lead[?] =>
                append("LEAD("); term(ld.expr); append(", "); term(ld.offset)
                ld.default.foreach(d =>
                    append(", "); term(d)
                )
                append(")")
            case lg: WindowFunction.Lag[?] =>
                append("LAG("); term(lg.expr); append(", "); term(lg.offset)
                lg.default.foreach(d =>
                    append(", "); term(d)
                )
                append(")")
            case fv: WindowFunction.FirstValue[?] => append("FIRST_VALUE("); term(fv.expr); append(")")
            case lv: WindowFunction.LastValue[?]  => append("LAST_VALUE("); term(lv.expr); append(")")
            case nv: WindowFunction.NthValue[?] =>
                append("NTH_VALUE("); term(nv.expr); append(", "); term(nv.n); append(")")

        private def windowSpecBody(s: WindowSpec): Unit =
            var needSpace = false
            if s.partitionBy.nonEmpty then
                append("PARTITION BY ")
                joinWith(", ")(s.partitionBy)(term)
                needSpace = true
            end if
            if s.orderBy.nonEmpty then
                if needSpace then append(" ")
                append("ORDER BY ")
                joinWith(", ")(s.orderBy)(orderSpec)
                needSpace = true
            end if
            s.frame.foreach: f =>
                if needSpace then append(" ")
                val kw = f.kind match
                    case SqlAst.WindowFrame.Kind.Rows   => "ROWS"
                    case SqlAst.WindowFrame.Kind.Range  => "RANGE"
                    case SqlAst.WindowFrame.Kind.Groups => "GROUPS"
                append(kw); append(" ")
                f.end match
                    case Present(end) =>
                        append("BETWEEN "); frameBound(f.start); append(" AND "); frameBound(end)
                    case _ =>
                        frameBound(f.start)
                end match
        end windowSpecBody

        private def frameBound(b: FrameBound): Unit = b match
            case _: FrameBound.UnboundedPreceding.type => append("UNBOUNDED PRECEDING")
            case _: FrameBound.UnboundedFollowing.type => append("UNBOUNDED FOLLOWING")
            case _: FrameBound.CurrentRow.type         => append("CURRENT ROW")
            case p: FrameBound.Preceding               => term(p.n); append(" PRECEDING")
            case f: FrameBound.Following               => term(f.n); append(" FOLLOWING")

        private def opSql(op: SqlAst.Comparison.Op): String = op match
            case SqlAst.Comparison.Op.Eq    => "="
            case SqlAst.Comparison.Op.NotEq => "<>"
            case SqlAst.Comparison.Op.Lt    => "<"
            case SqlAst.Comparison.Op.Lte   => "<="
            case SqlAst.Comparison.Op.Gt    => ">"
            case SqlAst.Comparison.Op.Gte   => ">="

        private def opSql(op: Logical.Op): String = op match
            case Logical.Op.And => "AND"
            case Logical.Op.Or  => "OR"

        private def opSql(op: Arithmetic.Op): String = op match
            case Arithmetic.Op.Add => "+"
            case Arithmetic.Op.Sub => "-"
            case Arithmetic.Op.Mul => "*"
            case Arithmetic.Op.Div => "/"
            case Arithmetic.Op.Mod => "%"

        // ILike and NotILike are handled by `stringMatch` which forks on backend before calling here;
        // this method is only reached for Like and NotLike.
        private def opSql(op: StringMatch.Op): String = op match
            case StringMatch.Op.Like     => "LIKE"
            case StringMatch.Op.NotLike  => "NOT LIKE"
            case StringMatch.Op.ILike    => "ILIKE"     // fallback; normally handled by stringMatch
            case StringMatch.Op.NotILike => "NOT ILIKE" // fallback; normally handled by stringMatch

        private def predSql(p: BoolTest.Predicate): String = p match
            case BoolTest.Predicate.IsTrue     => "IS TRUE"
            case BoolTest.Predicate.IsNotTrue  => "IS NOT TRUE"
            case BoolTest.Predicate.IsFalse    => "IS FALSE"
            case BoolTest.Predicate.IsNotFalse => "IS NOT FALSE"

        /** Renders a [[StringMatch]] node, forking on backend for ILike/NotILike.
          *   - Postgres: `<expr> ILIKE <pattern>` / `<expr> NOT ILIKE <pattern>` (wrapped by [[binary]]).
          *   - MySQL: `LOWER(<expr>) LIKE LOWER(<pattern>)` / `LOWER(<expr>) NOT LIKE LOWER(<pattern>)` (no outer parens).
          *   - Like / NotLike: unchanged on both backends.
          */
        private def stringMatch(sm: StringMatch): Unit = sm.op match
            case StringMatch.Op.ILike =>
                backend match
                    case _: SqlBackend.Postgres => binary(sm.expr, "ILIKE", sm.pattern)
                    case _: SqlBackend.Mysql =>
                        append("LOWER("); term(sm.expr); append(") LIKE LOWER("); term(sm.pattern); append(")")
            case StringMatch.Op.NotILike =>
                backend match
                    case _: SqlBackend.Postgres => binary(sm.expr, "NOT ILIKE", sm.pattern)
                    case _: SqlBackend.Mysql =>
                        append("LOWER("); term(sm.expr); append(") NOT LIKE LOWER("); term(sm.pattern); append(")")
            case other =>
                binary(sm.expr, opSql(other), sm.pattern)

        /** Recursively flattens nested [[Concat]] nodes into a single-level list of parts. Used for the MySQL CONCAT(a, b, …) rendering to
          * avoid CONCAT(CONCAT(a,b), c) nesting.
          */
        private def flatConcatParts(cn: Concat): Seq[Term[String]] =
            cn.parts.toSeq.flatMap:
                case inner: Concat => flatConcatParts(inner)
                case other         => Seq(other)

        // --- INSERT / UPDATE / DELETE ---

        def insert(i: Insert[?, ?]): Unit =
            // MySQL DoNothing is expressed as INSERT IGNORE INTO (no separate ON CONFLICT clause).
            val isMysqlIgnore = backend match
                case _: SqlBackend.Mysql =>
                    i.onConflict.exists(_.isInstanceOf[Insert.OnConflict.DoNothing[?]])
                case _: SqlBackend.Postgres => false
            if isMysqlIgnore then append("INSERT IGNORE INTO ") else append("INSERT INTO ")
            appendQuoted(i.tableName)
            i.source match
                case v: Insert.Values[?, ?]         => insertValues(i, v)
                case pv: Insert.PartialValues[?, ?] => insertPartialValues(pv)
                case fs: Insert.FromSelect[?, ?, ?] => insertFromSelect(fs)
            end match
            i.onConflict.foreach(onConflict)
            // Emit RETURNING clause, user-specified columns take priority over autoKey.
            // MySQL does not support RETURNING; the driver reads last_insert_id from the OK packet instead.
            i.returning match
                case Maybe.Present(cols) =>
                    backend match
                        case _: SqlBackend.Postgres =>
                            append(" RETURNING ")
                            append(cols.toSeq.map(c => quoted(c.sqlName)).mkString(", "))
                        case _: SqlBackend.Mysql =>
                            throw SqlUnsupportedReturningOnMysqlException()(using frame)
                    end match
                case Maybe.Absent =>
                    // Auto-emit RETURNING <pk> on Postgres when the AST carries an `autoKey` column (computed at
                    // construction time in `Sql.insert` via the "first-column-if-Long" fallback).
                    backend match
                        case _: SqlBackend.Postgres =>
                            i.autoKey.foreach: col =>
                                append(" RETURNING "); appendQuoted(col)
                        case _: SqlBackend.Mysql =>
                            ()
                    end match
            end match
        end insert

        private def insertValues(i: Insert[?, ?], v: Insert.Values[?, ?]): Unit =
            append(" ("); append(i.columnNames.toSeq.map(quoted).mkString(", ")); append(") VALUES ")
            renderDecomposedRows(v.rows)
        end insertValues

        private def insertPartialValues(pv: Insert.PartialValues[?, ?]): Unit =
            val cols = pv.sets.map(_.column)
            append(" ("); append(cols.toSeq.map(s => quoted(s.sqlName)).mkString(", ")); append(") VALUES (")
            joinWith(", ")(pv.sets) { s => term(s.value) }
            append(")")
        end insertPartialValues

        private def insertFromSelect(fs: Insert.FromSelect[?, ?, ?]): Unit =
            append(" ("); append(fs.columns.toSeq.map(c => quoted(c.sqlName)).mkString(", ")); append(") ")
            query(fs.query)

        private def onConflict(c: Insert.OnConflict[?]): Unit = c match
            case dn: Insert.OnConflict.DoNothing[?] =>
                backend match
                    case _: SqlBackend.Postgres =>
                        // No-target form: ON CONFLICT DO NOTHING.  With targets: ON CONFLICT (col, …) DO NOTHING.
                        if dn.targets.nonEmpty then
                            append(" ON CONFLICT (")
                            append(dn.targets.toSeq.map(c => quoted(c.sqlName)).mkString(", "))
                            append(") DO NOTHING")
                        else
                            append(" ON CONFLICT DO NOTHING")
                    case _: SqlBackend.Mysql =>
                        // MySQL DoNothing is expressed via INSERT IGNORE INTO in the header, nothing here.
                        ()
            case du: Insert.OnConflict.DoUpdate[?] =>
                backend match
                    case _: SqlBackend.Postgres =>
                        append(" ON CONFLICT (")
                        append(du.targets.toSeq.map(c => quoted(c.sqlName)).mkString(", "))
                        append(") DO UPDATE SET ")
                        joinWith(", ")(du.sets) { s =>
                            appendQuoted(s.column.sqlName); append(" = "); term(s.value)
                        }
                        du.where.foreach(w =>
                            append(" WHERE "); term(w)
                        )
                    case _: SqlBackend.Mysql =>
                        // MySQL: ON DUPLICATE KEY UPDATE set-list.  Target columns are ignored.
                        // Excluded references are rendered as VALUES(col) via the Excluded term handler.
                        // MySQL does not support a WHERE clause on ON DUPLICATE KEY UPDATE.
                        // Silently dropping the predicate would produce wrong updates, raise a typed error instead.
                        if du.where.isDefined then
                            throw SqlUnsupportedUpsertWhereClauseOnMysqlException()(using frame)
                        end if
                        append(" ON DUPLICATE KEY UPDATE ")
                        joinWith(", ")(du.sets) { s =>
                            appendQuoted(s.column.sqlName); append(" = "); term(s.value)
                        }

        def update(u: Update[?, ?]): Unit =
            append("UPDATE "); appendQuoted(u.tableName); append(" SET ")
            joinWith(", ")(u.sets) { s =>
                appendQuoted(s.column.sqlName); append(" = "); term(s.value)
            }
            u.whereClause.foreach(w =>
                append(" WHERE "); term(w)
            )
            u.returning.foreach: cols =>
                backend match
                    case _: SqlBackend.Postgres =>
                        append(" RETURNING ")
                        append(cols.toSeq.map(c => quoted(c.sqlName)).mkString(", "))
                    case _: SqlBackend.Mysql =>
                        throw SqlUnsupportedReturningOnMysqlException()(using frame)
                end match
        end update

        def delete(d: Delete[?, ?]): Unit =
            append("DELETE FROM "); appendQuoted(d.tableName)
            d.whereClause.foreach(w =>
                append(" WHERE "); term(w)
            )
            d.returning.foreach: cols =>
                backend match
                    case _: SqlBackend.Postgres =>
                        append(" RETURNING ")
                        append(cols.toSeq.map(c => quoted(c.sqlName)).mkString(", "))
                    case _: SqlBackend.Mysql =>
                        throw SqlUnsupportedReturningOnMysqlException()(using frame)
                end match
        end delete

    end State

end SqlRender
