package kyo.db

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Sql
import kyo.SqlType

/** The SQL flavor one backend speaks, and the translation of a [[kyo.Sql]] tree into that flavor's statement text.
  *
  * One type carries both halves: the facts that identify a flavor and gate its features, and a method per AST node family that emits the
  * text. A dialect is a stateless value, one per backend, shared by every render on every fiber, so nothing a render mutates lives here.
  * The accumulating text, the collected binds, the resolved server version, and the call-site frame live in [[Idiom.Ctx]], one instance per
  * [[render]] call. The server version is therefore never a field: it travels with the tree at each render, and a caller that does not know
  * it renders against [[capabilityFloor]].
  *
  * #### Writing a dialect
  *
  * Four members are abstract, the facts no default can guess: [[id]], [[capabilityFloor]], [[quoteIdent]], and [[placeholder]]. Everything
  * else has a standard-SQL body, so a subclass declares its divergences and inherits the rest. Capability claims are defaulted to what the
  * standard provides: a plain `supports*` predicate where a flavor either has a construct or never had it, a `*Since` floor where it gained
  * one in a release, and the `supports*(version)` gates derived from those floors so a gate and its floor cannot disagree. A construct the
  * target cannot express fails through [[Idiom.Ctx.unsupported]], never as text the server would reject.
  *
  * #### Overriding
  *
  * Every emitting method is public and concrete, and each is reached through `this`, so an override retargets every path that leads to it:
  * overriding [[limit]] changes pagination under plain queries, set operations, CTE bodies, and subqueries at once. Five shapes cover what
  * a flavor needs.
  *
  *   - Intercept one arm of a family and delegate the rest. The dispatchers ([[sql]], [[query]], [[action]], [[from]], [[term]]) and the
  *     group methods exist for this, and it is how an arm with no dedicated method is reached.
  *   - Override a clause rather than the statement carrying it, which is what the clause methods are named for.
  *   - Reuse the baseline's named parts instead of copying them: an override of [[groupBy]] that appends its own rollup keyword still calls
  *     [[groupByKeys]] for the key list. [[decomposedRows]], [[returning]], [[emptyIn]], [[derivedAlias]], [[commonTable]], [[assignment]],
  *     and [[setValue]] are there to be composed the same way.
  *   - Wrap the baseline by emitting around a `super` call, emission being ordered into one buffer.
  *   - Replace a whole statement where the flavor's statement shape differs, calling the clause methods for the parts it does not
  *     restructure. That is the intended top of the ladder; needing to replace a statement in order to change one clause is not.
  *
  * The interception shape. An override that omits the fallback line fails with a `MatchError` at render time rather than falling back:
  *
  * ```scala
  * override def arithmetic(ctx: Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit = ar.op match
  *     case Sql.Arithmetic.Op.Mod =>
  *         ctx.append("MOD("); term(ctx, ar.left); ctx.append(", "); term(ctx, ar.right); ctx.append(")")
  *     case _ => super.arithmetic(ctx, ar)
  * ```
  *
  * The operator spelling tables stay private: an operator a flavor spells as a function call rather than infix cannot be expressed as a
  * string, so such a flavor intercepts the group method instead.
  *
  * IMPORTANT: statement text the caller wrote stays the caller's. [[kyo.Sql.RawSql]] and a [[kyo.Sql.Fragment]]'s literal parts reach the
  * output verbatim, and [[placeholder]] is the single authority on placeholder spelling, applied here at each bind position and never
  * rewritten downstream. An override of [[term]] can reach raw text before delegating, so this is a contract rather than something a
  * signature enforces.
  *
  * @see
  *   [[Idiom.Ctx]] for the render state every emitting method writes through
  * @see
  *   [[render]] for the entry that owns the invariants around the walk
  * @see
  *   [[Idiom.Id]] and [[Idiom.ServerVersion]] for the identity and version vocabulary
  * @see
  *   [[kyo.db.Backend]] for the plug point that names a dialect for a URL scheme
  */
abstract class Idiom:

    /** Identifies this flavor, `Idiom.Id("postgres")` or `Idiom.Id("mysql")`. Keys every dialect-keyed structure and names this dialect in
      * the typed failures a render raises.
      */
    def id: Idiom.Id

    /** The oldest server version this dialect renders correctly for, and the version a render assumes when the caller names none.
      *
      * Every capability the dialect renders unconditionally is available at this version, so rendering at the floor produces SQL that any
      * supported server accepts. A statically rendered statement is rendered here, which is why a feature the connected server has but the
      * floor does not is unavailable at splice time.
      */
    def capabilityFloor: Idiom.ServerVersion

    /** Quotes `ident` in this flavor's identifier syntax, escaping any embedded quote character.
      *
      * Reached through [[Idiom.Ctx.quoted]] and [[Idiom.Ctx.appendQuoted]] at every position a name is emitted, so this one answer covers
      * tables, columns, aliases, and CTE names alike.
      */
    def quoteIdent(ident: String): String

    /** The placeholder standing for the bind at 1-based `position`, `"$1"` on Postgres and `"?"` on MySQL.
      *
      * The single authority on placeholder spelling. It is applied at each bind position while the statement is rendered, and no later
      * stage rewrites the text.
      */
    def placeholder(position: Int): String

    /** This flavor's spelling of `target` inside a `CAST(... AS ...)` clause, or `Absent` when the flavor cannot cast to that type at all.
      *
      * The spellings diverge even where two flavors have the type, and the cast grammars are not the same size: a type a flavor stores
      * happily in a column may still have no cast spelling. `Absent` says exactly that, and [[cast]] turns it into a typed failure rather
      * than a type name the server would reject. The default answers with the standard spellings and passes an extension type's own
      * declared name through unchanged.
      */
    def castTypeName(target: SqlType.Type): Maybe[String] =
        target match
            case SqlType.Type.Text             => Maybe("TEXT")
            case SqlType.Type.SmallInt         => Maybe("SMALLINT")
            case SqlType.Type.Int              => Maybe("INTEGER")
            case SqlType.Type.BigInt           => Maybe("BIGINT")
            case SqlType.Type.Boolean          => Maybe("BOOLEAN")
            case SqlType.Type.Float32          => Maybe("REAL")
            case SqlType.Type.Float64          => Maybe("DOUBLE PRECISION")
            case SqlType.Type.Bytes            => Maybe("BYTEA")
            case SqlType.Type.Uuid             => Maybe("UUID")
            case SqlType.Type.Json             => Maybe("JSONB")
            case SqlType.Type.Date             => Maybe("DATE")
            case SqlType.Type.Time             => Maybe("TIME")
            case SqlType.Type.TimeWithOffset   => Maybe("TIME WITH TIME ZONE")
            case SqlType.Type.DateTime         => Maybe("TIMESTAMP")
            case SqlType.Type.Timestamp        => Maybe("TIMESTAMPTZ")
            case SqlType.Type.CalendarInterval => Maybe("INTERVAL")
            case SqlType.Type.Numeric(precision, scale) =>
                (precision, scale) match
                    case (Maybe.Present(p), Maybe.Present(s)) => Maybe(s"NUMERIC($p, $s)")
                    case (Maybe.Present(p), _)                => Maybe(s"NUMERIC($p)")
                    case _                                    => Maybe("NUMERIC")
            case SqlType.Type.Array(element)  => castTypeName(element).map(name => s"$name[]")
            case SqlType.Type.Extension(name) => Maybe(name)

    /** True when this flavor has `RETURNING` on `INSERT`, `UPDATE`, and `DELETE`. Not version-gated: a flavor either has the clause or has
      * never had it.
      *
      * Defaults to false, the honest claim for standard SQL, and [[returning]] fails typed against a flavor that leaves it false rather
      * than dropping the caller's request.
      */
    def supportsReturning: Boolean =
        false

    /** True when this flavor has SQL:1999's grouping-set constructs, `GROUP BY CUBE (...)` and `GROUP BY GROUPING SETS (...)`. Defaults to
      * true, and is not version-gated: a flavor that lacks them lacks them at every release.
      *
      * `GROUP BY ... WITH ROLLUP` is not covered here. Rollup is a spelling difference rather than a missing construct, so a flavor that
      * spells it its own way overrides [[groupBy]].
      */
    def supportsGroupingSets: Boolean =
        true

    /** The release that introduced `LATERAL` subqueries, or `Absent`, the default, when every version of this flavor has them. */
    def lateralSince: Maybe[Idiom.ServerVersion] =
        Maybe.Absent

    /** The release that introduced `WITH RECURSIVE`, or `Absent`, the default, when every version of this flavor has it. */
    def recursiveCteSince: Maybe[Idiom.ServerVersion] =
        Maybe.Absent

    /** The release that introduced the `INTERSECT` and `EXCEPT` set operators, or `Absent`, the default, when every version of this flavor
      * has them.
      */
    def intersectExceptSince: Maybe[Idiom.ServerVersion] =
        Maybe.Absent

    /** The release that introduced the table value constructor, the `VALUES` list a query reads from as if it were a table, or `Absent`,
      * the default, when every version of this flavor has it.
      */
    def valuesConstructorSince: Maybe[Idiom.ServerVersion] =
        Maybe.Absent

    /** True when `version` supports `LATERAL` subqueries, which is [[lateralSince]] naming no floor or `version` being at or above it. */
    final def supportsLateral(version: Idiom.ServerVersion): Boolean =
        versionGate(lateralSince, version)

    /** True when `version` supports `WITH RECURSIVE`, which is [[recursiveCteSince]] naming no floor or `version` being at or above it. */
    final def supportsRecursiveCte(version: Idiom.ServerVersion): Boolean =
        versionGate(recursiveCteSince, version)

    /** True when `version` supports `INTERSECT` and `EXCEPT`, which is [[intersectExceptSince]] naming no floor or `version` being at or
      * above it.
      */
    final def supportsIntersectExcept(version: Idiom.ServerVersion): Boolean =
        versionGate(intersectExceptSince, version)

    /** True when `version` supports a table value constructor, which is [[valuesConstructorSince]] naming no floor or `version` being at or
      * above it.
      */
    final def supportsValuesConstructor(version: Idiom.ServerVersion): Boolean =
        versionGate(valuesConstructorSince, version)

    /** Renders any node, a query through [[query]], an action through [[action]], and a term through [[term]].
      *
      * The outermost interception point: an override that matches the nodes it treats specially, then closes with
      * `case _ => super.sql(ctx, ast)`, sees every node before the rest of the walk does.
      */
    def sql(ctx: Idiom.Ctx, ast: Sql[?]): Unit =
        ast match
            case q: Sql.Query[?]  => query(ctx, q)
            case a: Sql.Action[?] => action(ctx, a)
            case t: Sql.Term[?]   => term(ctx, t)

    /** Renders `q` as a complete statement, each node family through its own clause method.
      *
      * A bare source renders as a whole statement here, projection included; [[from]] renders the same node in FROM position instead. To
      * change one family without a dedicated method, match it here and close with `case _ => super.query(ctx, q)`.
      */
    def query(ctx: Idiom.Ctx, q: Sql.Query[?]): Unit =
        q match
            case f: Sql.From[?, ?] =>
                appendSelectStarFor(ctx, f)
                ctx.append(" FROM ")
                from(ctx, f)
            case w: Sql.Where[?, ?]       => where(ctx, w)
            case s: Sql.Select[?, ?, ?]   => select(ctx, s)
            case o: Sql.OrderBy[?]        => orderBy(ctx, o)
            case l: Sql.Limit[?]          => limit(ctx, l)
            case lk: Sql.Lock[?]          => lock(ctx, lk)
            case so: Sql.SetOp[?]         => setOp(ctx, so)
            case w: Sql.With[?]           => withClause(ctx, w)
            case wr: Sql.WithRecursive[?] => withRecursive(ctx, wr)
            case a: Sql.AggregateQuery[?] => aggregateQuery(ctx, a)

    /** Renders `a`, dispatching to [[insert]], [[update]], or [[delete]]. An override closes with `case _ => super.action(ctx, a)`. */
    def action(ctx: Idiom.Ctx, a: Sql.Action[?]): Unit =
        a match
            case i: Sql.Insert[?, ?] => insert(ctx, i)
            case u: Sql.Update[?, ?] => update(ctx, u)
            case d: Sql.Delete[?, ?] => delete(ctx, d)

    /** Renders `f` in FROM position: the item alone, with neither the projection nor the `FROM` keyword, which the enclosing clause emits.
      *
      * Dispatches to [[table]], [[nested]], [[lateral]], [[valuesSource]], [[join]], and [[crossJoin]]. An override closes with
      * `case _ => super.from(ctx, f)`.
      */
    def from(ctx: Idiom.Ctx, f: Sql.From[?, ?]): Unit =
        f match
            case t: Sql.Table[?, ?]      => table(ctx, t)
            case n: Sql.Nested[?, ?]     => nested(ctx, n)
            case l: Sql.Lateral[?, ?]    => lateral(ctx, l)
            case v: Sql.ValuesFrom[?, ?] => valuesSource(ctx, v)
            case j: Sql.Join[?, ?]       => join(ctx, j)
            case cj: Sql.CrossJoin[?, ?] => crossJoin(ctx, cj)

    /** Renders `t` in place, dispatching each term family to its own method.
      *
      * Every subterm returns through here, so an override sees nested terms as well as top-level ones, and the structural arms with no
      * dedicated method (a column, a literal, the null and truth-value tests, `BETWEEN`, `COALESCE`, `NULLIF`, a function call, raw text, a
      * nested query) are reached by matching them here and closing with `case _ => super.term(ctx, t)`.
      *
      * A literal renders as a bind, never as text inside the statement, which keeps the statement text dependent on a tree's shape rather
      * than on its values.
      */
    def term(ctx: Idiom.Ctx, t: Sql.Term[?]): Unit =
        t match
            case c: Sql.Column[?, ?] =>
                if c.alias.nonEmpty then
                    ctx.appendQuoted(c.alias)
                    ctx.append(".")
                ctx.appendQuoted(c.sqlName)
            case lit: Sql.Literal[a]         => ctx.appendBind(Sql.BoundValue(lit.value, lit.schema, lit.typeName))
            case lt: Sql.LabelledTerm[?, ?]  => term(ctx, lt.term)
            case gc: Sql.GroupedColumn[?, ?] => term(ctx, gc.column)
            case c: Sql.Comparison           => binary(ctx, c.left, comparisonOp(c.op), c.right)
            case lg: Sql.Logical             => binary(ctx, lg.left, logicalOp(lg.op), lg.right)
            case ar: Sql.Arithmetic[?]       => arithmetic(ctx, ar)
            case sm: Sql.StringMatch         => stringMatch(ctx, sm)
            case n: Sql.Not                  => ctx.append("(NOT "); term(ctx, n.expr); ctx.append(")")
            case bt: Sql.BoolTest =>
                ctx.append("("); term(ctx, bt.expr); ctx.append(" "); ctx.append(boolTestPredicate(bt.pred)); ctx.append(")")
            case ia: Sql.IsAbsent[?]   => ctx.append("("); term(ctx, ia.expr); ctx.append(" IS NULL)")
            case ip: Sql.IsPresent[?]  => ctx.append("("); term(ctx, ip.expr); ctx.append(" IS NOT NULL)")
            case iu: Sql.IsUnknown     => ctx.append("("); term(ctx, iu.expr); ctx.append(" IS UNKNOWN)")
            case inu: Sql.IsNotUnknown => ctx.append("("); term(ctx, inu.expr); ctx.append(" IS NOT UNKNOWN)")
            case b: Sql.Between[?] =>
                ctx.append("("); term(ctx, b.expr); ctx.append(" BETWEEN "); term(ctx, b.low)
                ctx.append(" AND "); term(ctx, b.high); ctx.append(")")
            case iv: Sql.InValues[?]       => inList(ctx, iv.expr, iv.values, negated = false)
            case niv: Sql.NotInValues[?]   => inList(ctx, niv.expr, niv.values, negated = true)
            case is: Sql.InSubquery[?]     => inSubquery(ctx, is.expr, is.subquery, negated = false)
            case nis: Sql.NotInSubquery[?] => inSubquery(ctx, nis.expr, nis.subquery, negated = true)
            case e: Sql.Exists             => ctx.append("EXISTS ("); query(ctx, e.subquery); ctx.append(")")
            case ne: Sql.NotExists         => ctx.append("NOT EXISTS ("); query(ctx, ne.subquery); ctx.append(")")
            case ss: Sql.ScalarSub[?]      => ctx.append("("); query(ctx, ss.subquery); ctx.append(")")
            case co: Sql.Coalesce[?] =>
                ctx.append("COALESCE(")
                ctx.joinWith(", ")(co.exprs)(e => term(ctx, e))
                ctx.append(")")
            case af: Sql.AbsentIf[?] =>
                ctx.append("NULLIF("); term(ctx, af.left); ctx.append(", "); term(ctx, af.right); ctx.append(")")
            case cn: Sql.Concat            => concat(ctx, cn)
            case sub: Sql.Substring        => substring(ctx, sub)
            case nf: Sql.NumericFn[?]      => numericFn(ctx, nf)
            case sf: Sql.StringFn          => stringFn(ctx, sf)
            case sl: Sql.StringLength      => stringLength(ctx, sl)
            case ce: Sql.CaseExpr[?]       => caseExpr(ctx, ce)
            case cen: Sql.CaseExprMaybe[?] => caseExprMaybe(ctx, cen)
            case c: Sql.Cast[?, ?]         => cast(ctx, c)
            case fc: Sql.FunctionCall[?] =>
                ctx.append(fc.name); ctx.append("(")
                ctx.joinWith(", ")(fc.args)(a => term(ctx, a))
                ctx.append(")")
            case raw: Sql.RawSql[?]        => ctx.append(raw.sql)
            case fr: Sql.Fragment[?]       => fragment(ctx, fr)
            case ex: Sql.Excluded[?, ?]    => excluded(ctx, ex.column.sqlName)
            case w: Sql.Windowed[?]        => windowed(ctx, w)
            case ag: Sql.Aggregate.Call[?] => aggregateCall(ctx, ag)
            case q: Sql.Query[?]           => ctx.append("("); query(ctx, q); ctx.append(")")

    /** Renders a table in FROM position: its quoted name followed by its quoted alias. */
    def table(ctx: Idiom.Ctx, t: Sql.Table[?, ?]): Unit =
        ctx.appendQuoted(t.tableName)
        ctx.append(" ")
        ctx.appendQuoted(t.alias)
    end table

    /** Renders a derived table in FROM position: the source query parenthesized, then its alias through [[derivedAlias]]. */
    def nested(ctx: Idiom.Ctx, n: Sql.Nested[?, ?]): Unit =
        ctx.append("(")
        query(ctx, n.source)
        ctx.append(")")
        derivedAlias(ctx, n.alias)
    end nested

    /** Renders a `LATERAL` derived table in FROM position: the keyword, the parenthesized source, then its alias through [[derivedAlias]].
      *
      * Fails typed on a target below [[lateralSince]].
      */
    def lateral(ctx: Idiom.Ctx, l: Sql.Lateral[?, ?]): Unit =
        if !supportsLateral(ctx.serverVersion) then ctx.unsupported("LATERAL", lateralSince)
        ctx.append("LATERAL (")
        query(ctx, l.source)
        ctx.append(")")
        derivedAlias(ctx, l.alias)
    end lateral

    /** Renders a table value constructor in FROM position: the parenthesized `VALUES` list with each row through [[valuesRow]], the alias
      * through [[derivedAlias]], and the alias column list.
      *
      * The column list is not decoration. A `VALUES` list names its own columns and no flavor names them after the row type's fields, so
      * the projection reading this source resolves its columns only because the list renames them. Fails typed on a target below
      * [[valuesConstructorSince]].
      */
    def valuesSource(ctx: Idiom.Ctx, v: Sql.ValuesFrom[?, ?]): Unit =
        if !supportsValuesConstructor(ctx.serverVersion) then ctx.unsupported("VALUES source", valuesConstructorSince)
        ctx.append("(VALUES ")
        ctx.joinWith(", ")(v.rows)(row => valuesRow(ctx, row))
        ctx.append(")")
        derivedAlias(ctx, v.alias)
        if v.columnNames.nonEmpty then
            ctx.append("(")
            ctx.joinWith(", ")(v.columnNames)(name => ctx.appendQuoted(name))
            ctx.append(")")
        end if
    end valuesSource

    /** Renders both sides through [[from]] around the join kind's keyword, then `ON` and the predicate.
      *
      * What this emits is one FROM item, and an enclosing clause attaches to all of it. An override keeps that property: a flavor that
      * emulates full outer joins by uniting two joins wraps the union as a derived table, or an enclosing `WHERE` attaches to the second
      * arm of the union alone.
      */
    def join(ctx: Idiom.Ctx, j: Sql.Join[?, ?]): Unit =
        from(ctx, j.left)
        val kw = j.kind match
            case Sql.JoinKind.Inner     => " INNER JOIN "
            case Sql.JoinKind.Left      => " LEFT JOIN "
            case Sql.JoinKind.Right     => " RIGHT JOIN "
            case Sql.JoinKind.FullOuter => " FULL OUTER JOIN "
        ctx.append(kw)
        from(ctx, j.right)
        ctx.append(" ON ")
        term(ctx, j.predicate)
    end join

    /** Renders both sides through [[from]] around `CROSS JOIN`. */
    def crossJoin(ctx: Idiom.Ctx, cj: Sql.CrossJoin[?, ?]): Unit =
        from(ctx, cj.left)
        ctx.append(" CROSS JOIN ")
        from(ctx, cj.right)
    end crossJoin

    /** Renders a filtered query: the source's projection, its FROM item, then `WHERE` and the predicate. */
    def where(ctx: Idiom.Ctx, w: Sql.Where[?, ?]): Unit =
        appendSelectStarFor(ctx, w.sql)
        ctx.append(" FROM ")
        from(ctx, w.sql)
        ctx.append(" WHERE ")
        term(ctx, w.predicate)
    end where

    /** Renders an explicit projection: `SELECT`, `DISTINCT` when the node carries it, the projected terms, then `FROM` and the source.
      *
      * A source whose clauses belong at this altitude is flattened into this statement; any other source is wrapped as a derived table,
      * which is what stops a `LIMIT` or an `ORDER BY` inside the source from moving onto this projection.
      */
    def select(ctx: Idiom.Ctx, s: Sql.Select[?, ?, ?]): Unit =
        ctx.append(if s.isDistinct then "SELECT DISTINCT " else "SELECT ")
        projection(ctx, s.terms)
        ctx.append(" FROM ")
        innerSource(ctx, s.sql)
    end select

    /** Renders an ordered query: its projection and source, then `ORDER BY` and the specs through [[orderSpec]].
      *
      * A source that is itself an explicit projection renders through [[select]], carrying its own `SELECT` and `FROM`; any other source
      * takes the implicit projection.
      */
    def orderBy(ctx: Idiom.Ctx, o: Sql.OrderBy[?]): Unit =
        o.sql match
            case s: Sql.Select[?, ?, ?] => select(ctx, s)
            case other =>
                appendSelectStarFor(ctx, other)
                ctx.append(" FROM ")
                innerSource(ctx, other)
        end match
        ctx.append(" ORDER BY ")
        ordering(ctx, o.specs)
    end orderBy

    /** Renders the inner query, then `LIMIT` with the count, and `OFFSET` only when the offset is above zero. */
    def limit(ctx: Idiom.Ctx, l: Sql.Limit[?]): Unit =
        query(ctx, l.sql)
        ctx.append(s" LIMIT ${l.n}")
        if l.offset > 0 then ctx.append(s" OFFSET ${l.offset}")
    end limit

    /** Renders the inner query, the row-lock mode keyword, the quoted `OF` list when the node names tables, then the wait behavior.
      *
      * A flavor with no row locks refuses here through [[Idiom.Ctx.unsupported]].
      */
    def lock(ctx: Idiom.Ctx, lk: Sql.Lock[?]): Unit =
        query(ctx, lk.sql)
        val modeKw = lk.mode match
            case Sql.Lock.Mode.Update => "FOR UPDATE"
            case Sql.Lock.Mode.Share  => "FOR SHARE"
        ctx.append(" ")
        ctx.append(modeKw)
        if lk.ofTables.nonEmpty then
            ctx.append(" OF ")
            ctx.joinWith(", ")(lk.ofTables)(name => ctx.appendQuoted(name))
        lk.behavior match
            case Sql.Lock.Behavior.Wait       => ()
            case Sql.Lock.Behavior.NoWait     => ctx.append(" NOWAIT")
            case Sql.Lock.Behavior.SkipLocked => ctx.append(" SKIP LOCKED")
        end match
    end lock

    /** Renders the left operand, the set operator's keyword, and the right operand. `INTERSECT` and `EXCEPT` fail typed on a target below
      * [[intersectExceptSince]].
      */
    def setOp(ctx: Idiom.Ctx, s: Sql.SetOp[?]): Unit =
        val isIntersectOrExcept = s.kind match
            case Sql.SetOp.Kind.Intersect | Sql.SetOp.Kind.IntersectAll |
                Sql.SetOp.Kind.Except | Sql.SetOp.Kind.ExceptAll => true
            case _ => false
        if isIntersectOrExcept && !supportsIntersectExcept(ctx.serverVersion) then
            ctx.unsupported("INTERSECT / EXCEPT", intersectExceptSince)
        query(ctx, s.left)
        val kw = s.kind match
            case Sql.SetOp.Kind.Union        => " UNION "
            case Sql.SetOp.Kind.UnionAll     => " UNION ALL "
            case Sql.SetOp.Kind.Intersect    => " INTERSECT "
            case Sql.SetOp.Kind.IntersectAll => " INTERSECT ALL "
            case Sql.SetOp.Kind.Except       => " EXCEPT "
            case Sql.SetOp.Kind.ExceptAll    => " EXCEPT ALL "
        ctx.append(kw)
        query(ctx, s.right)
    end setOp

    /** Renders a non-recursive `WITH` prelude, its common tables through [[commonTable]], and the body query.
      *
      * Named for the clause rather than for its node, `with` being a Scala keyword; [[withRecursive]] is the sibling.
      */
    def withClause(ctx: Idiom.Ctx, w: Sql.With[?]): Unit =
        cte(ctx, w.ctes, w.body, recursive = false)

    /** Renders a `WITH RECURSIVE` prelude, its common tables through [[commonTable]], and the body query. Fails typed on a target below
      * [[recursiveCteSince]].
      */
    def withRecursive(ctx: Idiom.Ctx, wr: Sql.WithRecursive[?]): Unit =
        cte(ctx, wr.ctes, wr.body, recursive = true)

    /** Renders an aggregate over a source, `SELECT <aggregate> FROM <source>`.
      *
      * A FROM item flattens because it is already one, and a `WHERE` flattens because its predicate becomes this statement's own. Every
      * other source is wrapped as a derived table through [[derivedAlias]], because flattening would move the source's clause onto the
      * aggregate: a `LIMIT` would count the whole source and then return the aggregate's single row, so the limit would silently do
      * nothing, and an `ORDER BY` on a column that is neither grouped nor aggregated is rejected by the server.
      */
    def aggregateQuery(ctx: Idiom.Ctx, a: Sql.AggregateQuery[?]): Unit =
        ctx.append("SELECT ")
        term(ctx, a.agg)
        ctx.append(" FROM ")
        a.source match
            case f: Sql.From[?, ?]  => from(ctx, f)
            case w: Sql.Where[?, ?] => from(ctx, w.sql); ctx.append(" WHERE "); term(ctx, w.predicate)
            case other =>
                ctx.append("(")
                query(ctx, other)
                ctx.append(")")
                derivedAlias(ctx, "sub")
        end match
    end aggregateQuery

    /** Renders the grouped source, the `GROUP BY` clause through [[groupByClause]], and `HAVING` when the node carries a predicate.
      *
      * The source flattening and the `HAVING` rendering are shared, so a flavor that spells the clause differently, a rollup as a
      * trailing keyword for instance, overrides [[groupByClause]] alone and composes with them instead of re-implementing the statement.
      */
    def groupBy(ctx: Idiom.Ctx, g: Sql.GroupBy[?, ?]): Unit =
        innerSource(ctx, g.source)
        groupByClause(ctx, g)
        g.havingTerm match
            case Maybe.Present(t) => ctx.append(" HAVING "); term(ctx, t)
            case Maybe.Absent     => ()
    end groupBy

    /** Renders the `GROUP BY` clause for the node's kind, the keys through [[groupByKeys]].
      *
      * The override point for a flavor whose clause is spelled differently: MySQL, for instance, overrides only the [[Sql.GroupBy.Kind.Rollup]]
      * arm to a trailing `WITH ROLLUP` and delegates the rest to `super`. `CUBE` and `GROUPING SETS` fail typed where [[supportsGroupingSets]]
      * is false.
      */
    def groupByClause(ctx: Idiom.Ctx, g: Sql.GroupBy[?, ?]): Unit =
        g.kind match
            case Sql.GroupBy.Kind.Plain =>
                ctx.append(" GROUP BY ")
                groupByKeys(ctx, g.keys)
            case Sql.GroupBy.Kind.Rollup =>
                ctx.append(" GROUP BY ROLLUP (")
                groupByKeys(ctx, g.keys)
                ctx.append(")")
            case Sql.GroupBy.Kind.Cube =>
                if !supportsGroupingSets then ctx.unsupported("GROUP BY CUBE", Maybe.Absent)
                ctx.append(" GROUP BY CUBE (")
                groupByKeys(ctx, g.keys)
                ctx.append(")")
            case Sql.GroupBy.Kind.GroupingSets(sets) =>
                if !supportsGroupingSets then ctx.unsupported("GROUP BY GROUPING SETS", Maybe.Absent)
                ctx.append(" GROUP BY GROUPING SETS (")
                ctx.joinWith(", ")(sets) { setKeys =>
                    ctx.append("(")
                    groupByKeys(ctx, setKeys)
                    ctx.append(")")
                }
                ctx.append(")")
        end match
    end groupByClause

    /** Renders the grouping keys, comma separated. Called by [[groupByClause]], and the piece an override of it reuses. */
    def groupByKeys(ctx: Idiom.Ctx, keys: Chunk[Sql.Term[?]]): Unit =
        ctx.joinWith(", ")(keys)(k => term(ctx, k))

    /** Renders an `INSERT`: the keyword from [[insertKeyword]], the quoted table, the source's clause ([[insertValues]],
      * [[insertPartialValues]], or [[insertFromSelect]]), the conflict clause ([[onConflictDoNothing]] or [[onConflictDoUpdate]]), then
      * [[returning]].
      *
      * The returning clause carries the caller's columns when it named any. With none, a flavor that has the clause returns the node's
      * auto-key column, and a flavor that has not emits nothing, its driver reading the generated key off the acknowledgement instead.
      *
      * An engine whose `INSERT` shape differs, one whose returned columns sit between the column list and `VALUES` for instance, overrides
      * this method and calls the clause pieces for the parts it does not restructure.
      */
    def insert(ctx: Idiom.Ctx, i: Sql.Insert[?, ?]): Unit =
        ctx.append(insertKeyword(i.onConflict))
        ctx.appendQuoted(i.tableName)
        i.source match
            case v: Sql.Insert.Values[?, ?]         => insertValues(ctx, i, v)
            case pv: Sql.Insert.PartialValues[?, ?] => insertPartialValues(ctx, i, pv)
            case fs: Sql.Insert.FromSelect[?, ?, ?] => insertFromSelect(ctx, i, fs)
        end match
        i.onConflict.foreach {
            case dn: Sql.Insert.OnConflict.DoNothing[?] => onConflictDoNothing(ctx, dn)
            case du: Sql.Insert.OnConflict.DoUpdate[?]  => onConflictDoUpdate(ctx, du)
        }
        i.returning match
            case Maybe.Present(cols) => returning(ctx, cols)
            case Maybe.Absent =>
                if supportsReturning then
                    i.autoKey.foreach { col =>
                        ctx.append(" RETURNING ")
                        ctx.appendQuoted(col)
                    }
        end match
    end insert

    /** Renders an `UPDATE`: the quoted table, the `SET` list through [[assignment]], then the optional `WHERE` and [[returning]]. */
    def update(ctx: Idiom.Ctx, u: Sql.Update[?, ?]): Unit =
        ctx.append("UPDATE ")
        ctx.appendQuoted(u.tableName)
        ctx.append(" SET ")
        ctx.joinWith(", ")(u.sets)(spec => assignment(ctx, spec))
        u.whereClause.foreach { w =>
            ctx.append(" WHERE ")
            term(ctx, w)
        }
        u.returning.foreach(cols => returning(ctx, cols))
    end update

    /** Renders a `DELETE FROM`: the quoted table, then the optional `WHERE` and [[returning]]. */
    def delete(ctx: Idiom.Ctx, d: Sql.Delete[?, ?]): Unit =
        ctx.append("DELETE FROM ")
        ctx.appendQuoted(d.tableName)
        d.whereClause.foreach { w =>
            ctx.append(" WHERE ")
            term(ctx, w)
        }
        d.returning.foreach(cols => returning(ctx, cols))
    end delete

    /** The keyword opening an `INSERT` whose conflict resolution is `onConflict`, trailing space included.
      *
      * Defaults to `"INSERT INTO "` whatever the resolution, the standard carrying conflict handling in a trailing clause. A flavor that
      * encodes conflict-discarding in the opening keyword instead answers differently here.
      */
    def insertKeyword(onConflict: Maybe[Sql.Insert.OnConflict[?]]): String =
        "INSERT INTO "

    /** Renders the quoted column list, `VALUES`, and the rows of a full-row `INSERT`, the rows through [[decomposedRows]].
      *
      * Every column of the row is emitted, a detected auto-key column included: auto-key detection reads the Scala type and cannot know
      * whether the server generates the column. A column override the caller supplied replaces that column's cell in every row, which is
      * how a `DEFAULT` reaches a generated key's slot and the row's own value for it never travels.
      */
    def insertValues(ctx: Idiom.Ctx, i: Sql.Insert[?, ?], v: Sql.Insert.Values[?, ?]): Unit =
        ctx.append(" (")
        ctx.joinWith(", ")(i.columnNames)(name => ctx.appendQuoted(name))
        ctx.append(") VALUES ")
        if i.overrides.isEmpty then decomposedRows(ctx, v.rows)
        else
            // One override lookup per column rather than per cell: the columns are the same for every row.
            val perColumn = i.columnNames.map(name => overrideFor(i.overrides, name))
            ctx.joinWith(", ")(v.rows) { row =>
                ctx.append("(")
                ctx.joinWith(", ")(0 until row.size) { idx =>
                    val overridden = if idx < perColumn.size then perColumn(idx) else Maybe.Absent
                    overridden match
                        case Maybe.Present(spec) => setValue(ctx, spec.value)
                        case Maybe.Absent        => ctx.appendBind(row(idx))
                }
                ctx.append(")")
            }
        end if
    end insertValues

    /** Renders the caller's own column list and its values, a column override replacing the value of any column it names.
      *
      * The emitted column list is the caller's: a column override naming a column this statement does not send cannot be applied, and is
      * not silently added to the list.
      */
    def insertPartialValues(ctx: Idiom.Ctx, i: Sql.Insert[?, ?], pv: Sql.Insert.PartialValues[?, ?]): Unit =
        val cols = pv.sets.map(_.column)
        ctx.append(" (")
        ctx.joinWith(", ")(cols)(c => ctx.appendQuoted(c.sqlName))
        ctx.append(") VALUES (")
        ctx.joinWith(", ")(pv.sets) { s =>
            overrideFor(i.overrides, s.column.sqlName) match
                case Maybe.Present(spec) => setValue(ctx, spec.value)
                case Maybe.Absent        => setValue(ctx, s.value)
        }
        ctx.append(")")
    end insertPartialValues

    /** Renders the quoted column list and the feeding query of an `INSERT ... SELECT`.
      *
      * A column override cannot be honored here and is refused typed rather than dropped: the values come from a projection, which takes no
      * `DEFAULT` keyword, so there is no cell for it to replace.
      */
    def insertFromSelect(ctx: Idiom.Ctx, i: Sql.Insert[?, ?], fs: Sql.Insert.FromSelect[?, ?, ?]): Unit =
        if i.overrides.nonEmpty then ctx.unsupported("INSERT ... SELECT with an overridden column", Maybe.Absent)
        ctx.append(" (")
        ctx.joinWith(", ")(fs.columns)(c => ctx.appendQuoted(c.sqlName))
        ctx.append(") ")
        query(ctx, fs.query)
    end insertFromSelect

    /** Renders the clause that leaves conflicting rows untouched, narrowed to the quoted target columns when the node names any.
      *
      * The standard has no conflict clause, so the default is the majority spelling. A flavor with no upsert refuses here through
      * [[Idiom.Ctx.unsupported]].
      */
    def onConflictDoNothing(ctx: Idiom.Ctx, doNothing: Sql.Insert.OnConflict.DoNothing[?]): Unit =
        if doNothing.targets.isEmpty then ctx.append(" ON CONFLICT DO NOTHING")
        else
            ctx.append(" ON CONFLICT (")
            ctx.joinWith(", ")(doNothing.targets)(c => ctx.appendQuoted(c.sqlName))
            ctx.append(") DO NOTHING")

    /** Renders the clause that updates conflicting rows: the quoted conflict targets, the assignment list through [[assignment]], then the
      * optional predicate.
      *
      * The majority spelling, for the reason on [[onConflictDoNothing]]. A flavor that accepts no predicate on the clause refuses that arm
      * typed rather than emitting an update it cannot narrow.
      */
    def onConflictDoUpdate(ctx: Idiom.Ctx, doUpdate: Sql.Insert.OnConflict.DoUpdate[?]): Unit =
        ctx.append(" ON CONFLICT (")
        ctx.joinWith(", ")(doUpdate.targets)(c => ctx.appendQuoted(c.sqlName))
        ctx.append(") DO UPDATE SET ")
        ctx.joinWith(", ")(doUpdate.sets)(spec => assignment(ctx, spec))
        doUpdate.where.foreach { predicate =>
            ctx.append(" WHERE ")
            term(ctx, predicate)
        }
    end onConflictDoUpdate

    /** Renders the rows of an `INSERT` as comma-separated parenthesized lists.
      *
      * Every cell binds, an absent one included. Keeping values out of the statement text makes the text depend on a row's shape alone,
      * which is what a server-side statement cache is keyed on, and it leaves each flavor's spelling of absence to its parameter writer.
      */
    def decomposedRows(ctx: Idiom.Ctx, rows: Chunk[Chunk[Sql.BoundValue[?]]]): Unit =
        ctx.joinWith(", ")(rows) { row =>
            ctx.append("(")
            ctx.joinWith(", ")(row)(cell => ctx.appendBind(cell))
            ctx.append(")")
        }

    /** Renders one row of a table value constructor, the `(1, 2)` in `FROM (VALUES (1, 2)) AS "v"("x", "y")`.
      *
      * Bare parentheses is the standard spelling; a flavor whose constructor names each row overrides here. Every cell binds, as in
      * [[decomposedRows]].
      */
    def valuesRow(ctx: Idiom.Ctx, cells: Chunk[Sql.BoundValue[?]]): Unit =
        ctx.append("(")
        ctx.joinWith(", ")(cells)(cell => ctx.appendBind(cell))
        ctx.append(")")
    end valuesRow

    /** Renders `RETURNING` and the quoted columns, failing typed on a flavor whose [[supportsReturning]] is false rather than dropping the
      * caller's request.
      */
    def returning(ctx: Idiom.Ctx, cols: Chunk[Sql.Column[?, ?]]): Unit =
        if !supportsReturning then ctx.unsupported("RETURNING", Maybe.Absent)
        ctx.append(" RETURNING ")
        ctx.joinWith(", ")(cols)(c => ctx.appendQuoted(c.sqlName))
    end returning

    /** Renders one column assignment, the quoted column, `=`, and the value through [[setValue]]. The shape a `SET` list is built from, in
      * an `UPDATE` and in a conflict-update clause alike.
      */
    def assignment(ctx: Idiom.Ctx, spec: Sql.SetSpec[?, ?]): Unit =
        ctx.appendQuoted(spec.column.sqlName)
        ctx.append(" = ")
        setValue(ctx, spec.value)
    end assignment

    /** Renders an assignment's right-hand side: the `DEFAULT` keyword for the column's own default, otherwise the term.
      *
      * `DEFAULT` is legal in exactly the positions that reach this method, which is why it is not a [[kyo.Sql.Term]] and cannot render into
      * a predicate or a projection.
      */
    def setValue(ctx: Idiom.Ctx, value: Sql.SetValue[?]): Unit =
        value match
            case _: Sql.SetValue.Default[?] => ctx.append("DEFAULT")
            case t: Sql.Term[?]             => term(ctx, t)

    /** Renders an arithmetic node, the operands around the operator's spelling.
      *
      * Both integral quotients take the plain division operator, which is what a flavor with no separate spelling wants. A flavor whose
      * division truncates, or which spells the truncating form differently, intercepts the arm it diverges on.
      */
    def arithmetic(ctx: Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit =
        binary(ctx, ar.left, arithmeticOp(ar.op), ar.right)

    /** Renders a pattern match. The case-sensitive pair renders as an infix match; the case-insensitive pair routes to
      * [[caseInsensitiveLike]], which is where a flavor with a dedicated operator overrides.
      */
    def stringMatch(ctx: Idiom.Ctx, sm: Sql.StringMatch): Unit =
        sm.op match
            case Sql.StringMatch.Op.ILike    => caseInsensitiveLike(ctx, sm.expr, sm.pattern, negated = false)
            case Sql.StringMatch.Op.NotILike => caseInsensitiveLike(ctx, sm.expr, sm.pattern, negated = true)
            case other                       => binary(ctx, sm.expr, stringMatchOp(other), sm.pattern)

    /** Renders a case-insensitive pattern match, negated when `negated` is true.
      *
      * Defaults to folding both sides to lower case around `LIKE`, which is the portable spelling; a flavor with a dedicated operator
      * overrides.
      */
    def caseInsensitiveLike(ctx: Idiom.Ctx, expr: Sql.Term[String], pattern: Sql.Term[String], negated: Boolean): Unit =
        ctx.append("LOWER(")
        term(ctx, expr)
        ctx.append(if negated then ") NOT LIKE LOWER(" else ") LIKE LOWER(")
        term(ctx, pattern)
        ctx.append(")")
    end caseInsensitiveLike

    /** Renders the operand and its parenthesized value list, negated on request.
      *
      * An empty list has no SQL spelling, `IN ()` being a syntax error, so it routes to [[emptyIn]] instead.
      */
    def inList(ctx: Idiom.Ctx, expr: Sql.Term[?], values: Chunk[Sql.Term[?]], negated: Boolean): Unit =
        if values.isEmpty then emptyIn(ctx, negated)
        else
            ctx.append("(")
            term(ctx, expr)
            ctx.append(if negated then " NOT IN (" else " IN (")
            ctx.joinWith(", ")(values)(v => term(ctx, v))
            ctx.append("))")

    /** The constant an empty `IN` list means: membership of the empty set is false for every operand, so the plain form is false and the
      * negated form is true.
      *
      * Parenthesized like the predicate it replaces, so a surrounding `AND` or `NOT` composes with it unchanged.
      */
    def emptyIn(ctx: Idiom.Ctx, negated: Boolean): Unit =
        ctx.append(if negated then "(TRUE)" else "(FALSE)")

    /** Renders the operand and the parenthesized subquery around `IN` or `NOT IN`. */
    def inSubquery(ctx: Idiom.Ctx, expr: Sql.Term[?], subquery: Sql.Query[?], negated: Boolean): Unit =
        ctx.append("(")
        term(ctx, expr)
        ctx.append(if negated then " NOT IN (" else " IN (")
        query(ctx, subquery)
        ctx.append("))")
    end inSubquery

    /** Renders a `CASE` expression: one `WHEN` and `THEN` per branch, the `ELSE` term, then `END`. */
    def caseExpr(ctx: Idiom.Ctx, ce: Sql.CaseExpr[?]): Unit =
        ctx.append("CASE")
        ce.whens.foreach { w =>
            ctx.append(" WHEN ")
            term(ctx, w._1)
            ctx.append(" THEN ")
            term(ctx, w._2)
        }
        ctx.append(" ELSE ")
        term(ctx, ce.otherwise)
        ctx.append(" END")
    end caseExpr

    /** Renders a `CASE` expression with no `ELSE`, so an unmatched row yields SQL NULL. [[caseExpr]] is the total form. */
    def caseExprMaybe(ctx: Idiom.Ctx, cen: Sql.CaseExprMaybe[?]): Unit =
        ctx.append("CASE")
        cen.whens.foreach { w =>
            ctx.append(" WHEN ")
            term(ctx, w._1)
            ctx.append(" THEN ")
            term(ctx, w._2)
        }
        ctx.append(" END")
    end caseExprMaybe

    /** Renders `CAST(expr AS <name>)`, the name being [[castTypeName]]'s spelling of the node's portable [[SqlType.Type]].
      *
      * The target type is carried on the node, guaranteed present by the `SqlType[B]` the cast summoned, so the only failure left is a type
      * this flavor cannot cast to, named in a typed failure. A cast to a type with no portable SQL type never reaches here: it is a compile
      * error at `.cast`.
      */
    def cast(ctx: Idiom.Ctx, c: Sql.Cast[?, ?]): Unit =
        castTypeName(c.target) match
            case Maybe.Absent => ctx.unsupported(s"CAST AS ${c.target}", Maybe.Absent)
            case Maybe.Present(name) =>
                ctx.append("CAST(")
                term(ctx, c.expr)
                ctx.append(" AS ")
                ctx.append(name)
                ctx.append(")")
        end match
    end cast

    /** Renders the parts joined by the standard concatenation operator, parenthesized. A flavor that concatenates through a function
      * overrides.
      */
    def concat(ctx: Idiom.Ctx, cn: Sql.Concat): Unit =
        ctx.append("(")
        ctx.joinWith(" || ")(cn.parts)(p => term(ctx, p))
        ctx.append(")")
    end concat

    /** Renders a reference to the value column `columnName` would have received, readable inside a conflict-resolution clause. Defaults to
      * the majority spelling, the `EXCLUDED` qualifier and the quoted column.
      */
    def excluded(ctx: Idiom.Ctx, columnName: String): Unit =
        ctx.append("EXCLUDED.")
        ctx.appendQuoted(columnName)

    /** Renders `SUBSTRING` with the `FROM` start position, and the `FOR` length when the node carries one. */
    def substring(ctx: Idiom.Ctx, sub: Sql.Substring): Unit =
        ctx.append("SUBSTRING(")
        term(ctx, sub.expr)
        ctx.append(" FROM ")
        term(ctx, sub.start)
        sub.length.foreach { l =>
            ctx.append(" FOR ")
            term(ctx, l)
        }
        ctx.append(")")
    end substring

    /** Renders a numeric function applied to its operand, under the standard name of the node's op. */
    def numericFn(ctx: Idiom.Ctx, nf: Sql.NumericFn[?]): Unit =
        val name = nf.op match
            case Sql.NumericFn.Op.Abs     => "ABS"
            case Sql.NumericFn.Op.Ceiling => "CEILING"
            case Sql.NumericFn.Op.Floor   => "FLOOR"
            case Sql.NumericFn.Op.Round   => "ROUND"
        ctx.append(name)
        ctx.append("(")
        term(ctx, nf.expr)
        ctx.append(")")
    end numericFn

    /** Renders a string function applied to its operand, under the standard name of the node's op. */
    def stringFn(ctx: Idiom.Ctx, sf: Sql.StringFn): Unit =
        val name = sf.op match
            case Sql.StringFn.Op.Upper => "UPPER"
            case Sql.StringFn.Op.Lower => "LOWER"
            case Sql.StringFn.Op.Trim  => "TRIM"
        ctx.append(name)
        ctx.append("(")
        term(ctx, sf.expr)
        ctx.append(")")
    end stringFn

    /** Renders `LENGTH` applied to its operand. */
    def stringLength(ctx: Idiom.Ctx, sl: Sql.StringLength): Unit =
        ctx.append("LENGTH(")
        term(ctx, sl.expr)
        ctx.append(")")
    end stringLength

    /** Renders an aggregate over its operand, with `DISTINCT` where the node carries it and a star for a count with no operand. */
    def aggregateCall(ctx: Idiom.Ctx, a: Sql.Aggregate.Call[?]): Unit =
        a match
            case c: Sql.Aggregate.Count =>
                ctx.append("COUNT(")
                if c.distinct then ctx.append("DISTINCT ")
                c.expr match
                    case Maybe.Present(e) => term(ctx, e)
                    case Maybe.Absent     => ctx.append("*")
                ctx.append(")")
            case s: Sql.Aggregate.Sum[?] =>
                ctx.append("SUM(")
                if s.distinct then ctx.append("DISTINCT ")
                term(ctx, s.expr)
                ctx.append(")")
            case av: Sql.Aggregate.Avg[?] =>
                ctx.append("AVG(")
                if av.distinct then ctx.append("DISTINCT ")
                term(ctx, av.expr)
                ctx.append(")")
            case mn: Sql.Aggregate.Min[?] =>
                ctx.append("MIN(")
                term(ctx, mn.expr)
                ctx.append(")")
            case mx: Sql.Aggregate.Max[?] =>
                ctx.append("MAX(")
                term(ctx, mx.expr)
                ctx.append(")")

    /** Renders a windowed expression: the operand, an [[aggregateCall]] or a [[windowFunction]], then `OVER` and the parenthesized
      * [[windowSpec]].
      */
    def windowed(ctx: Idiom.Ctx, w: Sql.Windowed[?]): Unit =
        w.inner match
            case ac: Sql.Aggregate.Call[?] => aggregateCall(ctx, ac)
            case wf: Sql.WindowFunction[?] => windowFunction(ctx, wf)
        ctx.append(" OVER (")
        windowSpec(ctx, w.spec)
        ctx.append(")")
    end windowed

    /** Renders a ranking or positional window function under its SQL name, with the arguments the node carries: a tile count, the offset
      * and optional default of a lead or lag, the target row of an nth value.
      */
    def windowFunction(ctx: Idiom.Ctx, wf: Sql.WindowFunction[?]): Unit =
        wf match
            case _: Sql.WindowFunction.RowNumber.type   => ctx.append("ROW_NUMBER()")
            case _: Sql.WindowFunction.Rank.type        => ctx.append("RANK()")
            case _: Sql.WindowFunction.DenseRank.type   => ctx.append("DENSE_RANK()")
            case _: Sql.WindowFunction.PercentRank.type => ctx.append("PERCENT_RANK()")
            case _: Sql.WindowFunction.CumeDist.type    => ctx.append("CUME_DIST()")
            case nt: Sql.WindowFunction.Ntile =>
                ctx.append("NTILE(")
                term(ctx, nt.n)
                ctx.append(")")
            case ld: Sql.WindowFunction.Lead[?] =>
                ctx.append("LEAD(")
                term(ctx, ld.expr)
                ctx.append(", ")
                term(ctx, ld.offset)
                ld.default.foreach { d =>
                    ctx.append(", ")
                    term(ctx, d)
                }
                ctx.append(")")
            case lg: Sql.WindowFunction.Lag[?] =>
                ctx.append("LAG(")
                term(ctx, lg.expr)
                ctx.append(", ")
                term(ctx, lg.offset)
                lg.default.foreach { d =>
                    ctx.append(", ")
                    term(ctx, d)
                }
                ctx.append(")")
            case fv: Sql.WindowFunction.FirstValue[?] =>
                ctx.append("FIRST_VALUE(")
                term(ctx, fv.expr)
                ctx.append(")")
            case lv: Sql.WindowFunction.LastValue[?] =>
                ctx.append("LAST_VALUE(")
                term(ctx, lv.expr)
                ctx.append(")")
            case nv: Sql.WindowFunction.NthValue[?] =>
                ctx.append("NTH_VALUE(")
                term(ctx, nv.expr)
                ctx.append(", ")
                term(ctx, nv.n)
                ctx.append(")")

    /** Renders a window specification's body: the `PARTITION BY` terms, the `ORDER BY` specs through [[orderSpec]], and the frame, each
      * emitted only when present and separated by a single space.
      *
      * The frame takes its mode keyword and either one bound or `BETWEEN` with both, each through [[frameBound]]. The surrounding
      * parentheses belong to [[windowed]], not here.
      */
    def windowSpec(ctx: Idiom.Ctx, s: Sql.WindowSpec): Unit =
        var needSpace = false
        if s.partitionBy.nonEmpty then
            ctx.append("PARTITION BY ")
            ctx.joinWith(", ")(s.partitionBy)(t => term(ctx, t))
            needSpace = true
        end if
        if s.orderBy.nonEmpty then
            if needSpace then ctx.append(" ")
            ctx.append("ORDER BY ")
            ctx.joinWith(", ")(s.orderBy)(spec => orderSpec(ctx, spec))
            needSpace = true
        end if
        s.frame.foreach { f =>
            if needSpace then ctx.append(" ")
            val kw = f.kind match
                case Sql.WindowFrame.Kind.Rows   => "ROWS"
                case Sql.WindowFrame.Kind.Range  => "RANGE"
                case Sql.WindowFrame.Kind.Groups => "GROUPS"
            ctx.append(kw)
            ctx.append(" ")
            f.end match
                case Maybe.Present(e) =>
                    ctx.append("BETWEEN ")
                    frameBound(ctx, f.start)
                    ctx.append(" AND ")
                    frameBound(ctx, e)
                case Maybe.Absent =>
                    frameBound(ctx, f.start)
            end match
        }
    end windowSpec

    /** Renders one frame bound's keywords, with the offset term ahead of `PRECEDING` or `FOLLOWING`. */
    def frameBound(ctx: Idiom.Ctx, b: Sql.FrameBound): Unit =
        b match
            case _: Sql.FrameBound.UnboundedPreceding.type => ctx.append("UNBOUNDED PRECEDING")
            case _: Sql.FrameBound.UnboundedFollowing.type => ctx.append("UNBOUNDED FOLLOWING")
            case _: Sql.FrameBound.CurrentRow.type         => ctx.append("CURRENT ROW")
            case p: Sql.FrameBound.Preceding               => term(ctx, p.n); ctx.append(" PRECEDING")
            case f: Sql.FrameBound.Following               => term(ctx, f.n); ctx.append(" FOLLOWING")

    /** Renders one `ORDER BY` element: the term, its direction keyword, and the null placement when the spec names one.
      *
      * Defaults to the standard `NULLS FIRST` and `NULLS LAST`. A flavor without them lowers the placement into an equivalent expression
      * here, which is why the placement is not a capability.
      */
    def orderSpec(ctx: Idiom.Ctx, spec: Sql.OrderSpec): Unit =
        term(ctx, spec.expr)
        spec.direction match
            case Sql.OrderSpec.Direction.Asc  => ctx.append(" ASC")
            case Sql.OrderSpec.Direction.Desc => ctx.append(" DESC")
        spec.absent match
            case Sql.OrderSpec.AbsentPlacement.Default => ()
            case Sql.OrderSpec.AbsentPlacement.First   => ctx.append(" NULLS FIRST")
            case Sql.OrderSpec.AbsentPlacement.Last    => ctx.append(" NULLS LAST")
        end match
    end orderSpec

    /** Renders one common table expression: the quoted name, `AS`, and the parenthesized query. Both [[withClause]] and [[withRecursive]]
      * emit their entries through it.
      */
    def commonTable(ctx: Idiom.Ctx, c: Sql.CommonTable[?]): Unit =
        ctx.appendQuoted(c.name)
        ctx.append(" AS (")
        query(ctx, c.query)
        ctx.append(")")
    end commonTable

    /** Renders the alias of a derived table: a space and the quoted name, with no `AS`, which is the portable form, some engines rejecting
      * the keyword on a table alias.
      *
      * Every derived table the walk wraps takes its alias from here, so a flavor that wants the keyword changes them all at once.
      */
    def derivedAlias(ctx: Idiom.Ctx, alias: String): Unit =
        ctx.append(" ")
        ctx.appendQuoted(alias)

    /** Renders `ast` in this flavor, targeting `version`, or [[capabilityFloor]] when it is `Absent`.
      *
      * The entry every render goes through, and the owner of the invariants around the walk: exactly one [[Idiom.Ctx]] per call, the
      * version resolved once against [[capabilityFloor]], and a result carrying one entry keyed by this dialect's [[id]] that records the
      * resolved version, beside the binds in placeholder order. `frame` is the call site the render was asked for, carried into any typed
      * failure the render raises; the compile-time path has no user call site and passes an internal frame.
      *
      * A construct the target cannot express raises [[kyo.SqlUnsupportedException]] rather than suspending, the render path being a pure
      * function on both sides of its use: at run time the caller's `Abort[SqlException]` catches it, and at splice time it surfaces as a
      * macro compile error.
      */
    final def render(ast: Sql[?], version: Maybe[Idiom.ServerVersion], frame: Frame): Sql.Rendered =
        val one = renderRaw(ast, version, frame)
        Sql.Rendered(Map(id -> Sql.Rendered.Dialected(one.sql, one.version)), one.params)
    end render

    /** Renders `ast` into this one flavor's text and ordered binds, the [[Idiom.Rendering]] carrier that [[render]] lifts into
      * [[kyo.Sql.Rendered]]. The entry the connection tier and the static-render macro reach for one flavor's own text.
      */
    private[kyo] def renderRaw(ast: Sql[?], version: Maybe[Idiom.ServerVersion], frame: Frame): Idiom.Rendering =
        val ctx = new Idiom.Ctx(this, version, frame)
        sql(ctx, ast)
        Idiom.Rendering(ctx.text, ctx.binds, ctx.serverVersion)
    end renderRaw

    /** Renders `left op right`, parenthesized so precedence never depends on the surrounding text. The shared shape of every infix operator
      * the term walk emits.
      */
    private[kyo] def binary(ctx: Idiom.Ctx, left: Sql.Term[?], op: String, right: Sql.Term[?]): Unit =
        ctx.append("(")
        term(ctx, left)
        ctx.append(" ")
        ctx.append(op)
        ctx.append(" ")
        term(ctx, right)
        ctx.append(")")
    end binary

    /** True when `since` names no floor, or `version` is at or above it. The shared body of the four derived gates, so a gate and its floor
      * cannot disagree.
      */
    private def versionGate(since: Maybe[Idiom.ServerVersion], version: Idiom.ServerVersion): Boolean =
        since match
            case Maybe.Present(required) => version.atLeast(required)
            case Maybe.Absent            => true

    private def comparisonOp(op: Sql.Comparison.Op): String =
        op match
            case Sql.Comparison.Op.Eq    => "="
            case Sql.Comparison.Op.NotEq => "<>"
            case Sql.Comparison.Op.Lt    => "<"
            case Sql.Comparison.Op.Lte   => "<="
            case Sql.Comparison.Op.Gt    => ">"
            case Sql.Comparison.Op.Gte   => ">="

    private def logicalOp(op: Sql.Logical.Op): String =
        op match
            case Sql.Logical.Op.And => "AND"
            case Sql.Logical.Op.Or  => "OR"

    private def arithmeticOp(op: Sql.Arithmetic.Op): String =
        op match
            case Sql.Arithmetic.Op.Add              => "+"
            case Sql.Arithmetic.Op.Sub              => "-"
            case Sql.Arithmetic.Op.Mul              => "*"
            case Sql.Arithmetic.Op.Mod              => "%"
            case Sql.Arithmetic.Op.Divide           => "/"
            case Sql.Arithmetic.Op.DivideIntegral   => "/"
            case Sql.Arithmetic.Op.DivideTruncating => "/"

    private def stringMatchOp(op: Sql.StringMatch.Op): String =
        op match
            case Sql.StringMatch.Op.Like     => "LIKE"
            case Sql.StringMatch.Op.NotLike  => "NOT LIKE"
            case Sql.StringMatch.Op.ILike    => "ILIKE"
            case Sql.StringMatch.Op.NotILike => "NOT ILIKE"

    private def boolTestPredicate(pred: Sql.BoolTest.Predicate): String =
        pred match
            case Sql.BoolTest.Predicate.IsTrue     => "IS TRUE"
            case Sql.BoolTest.Predicate.IsNotTrue  => "IS NOT TRUE"
            case Sql.BoolTest.Predicate.IsFalse    => "IS FALSE"
            case Sql.BoolTest.Predicate.IsNotFalse => "IS NOT FALSE"

    /** Emits `SELECT` and the source's columns, alias qualified, in schema-declaration order.
      *
      * The columns are named explicitly rather than left as a literal star so that the n-th projected column is always the n-th schema
      * field, which is what keeps positional row decoding correct when a table's physical column order diverges from its field order. A
      * literal star is emitted only for a source with no columns. Private for that reason: opening it would turn a rendering customization
      * into a silent mis-decode.
      */
    private def appendSelectStar(ctx: Idiom.Ctx, alias: String, columnNames: Chunk[String]): Unit =
        ctx.append("SELECT ")
        if columnNames.isEmpty then ctx.append("*")
        else
            ctx.joinWith(", ")(columnNames) { name =>
                if alias.nonEmpty then
                    ctx.appendQuoted(alias)
                    ctx.append(".")
                ctx.appendQuoted(name)
            }
        end if
    end appendSelectStar

    private def appendSelectStarFor(ctx: Idiom.Ctx, q: Sql.Query[?]): Unit =
        sourceColumns(q) match
            case Maybe.Present((alias, columnNames)) => appendSelectStar(ctx, alias, columnNames)
            case Maybe.Absent                        => ctx.append("SELECT *")

    /** The alias and column names of the single-table source a wrapper projects, recursing through the wrappers that preserve it.
      *
      * `Absent` for a join or an arbitrary subquery, neither of which has one field order, and the caller then falls back to a literal
      * star: the positional-decoding hazard is single-table only.
      */
    private def sourceColumns(q: Sql.Query[?]): Maybe[(String, Chunk[String])] =
        q match
            case t: Sql.Table[?, ?]      => Maybe((t.alias, t.columnNames))
            case n: Sql.Nested[?, ?]     => Maybe((n.alias, n.columnNames))
            case l: Sql.Lateral[?, ?]    => Maybe((l.alias, l.columnNames))
            case v: Sql.ValuesFrom[?, ?] => Maybe((v.alias, v.columnNames))
            case w: Sql.Where[?, ?]      => sourceColumns(w.sql)
            case o: Sql.OrderBy[?]       => sourceColumns(o.sql)
            case l: Sql.Limit[?]         => sourceColumns(l.sql)
            case lk: Sql.Lock[?]         => sourceColumns(lk.sql)
            case _                       => Maybe.Absent

    /** Emits the FROM position of a projection, flattening the source into the enclosing statement or wrapping it as a derived table.
      *
      * A FROM item, a `WHERE`, a grouping, an `ORDER BY` and a `LIMIT` flatten; any other query is wrapped, because flattening a source
      * that already carries a clause moves that clause onto the enclosing projection, where it either means something else or is rejected
      * outright. The policy is private for that reason. The spelling of the wrap is not: that is [[derivedAlias]].
      */
    private def innerSource(ctx: Idiom.Ctx, q: Sql.SelectSource[?]): Unit =
        q match
            case f: Sql.From[?, ?] => from(ctx, f)
            case w: Sql.Where[?, ?] =>
                from(ctx, w.sql)
                ctx.append(" WHERE ")
                term(ctx, w.predicate)
            case g: Sql.GroupBy[?, ?] => groupBy(ctx, g)
            case o: Sql.OrderBy[?] =>
                innerSource(ctx, o.sql)
                ctx.append(" ORDER BY ")
                ordering(ctx, o.specs)
            case l: Sql.Limit[?] =>
                innerSource(ctx, l.sql)
                ctx.append(s" LIMIT ${l.n}")
                if l.offset > 0 then ctx.append(s" OFFSET ${l.offset}")
            case other: Sql.Query[?] =>
                ctx.append("(")
                query(ctx, other)
                ctx.append(")")
                derivedAlias(ctx, "sub")

    private def projection(ctx: Idiom.Ctx, p: Sql.Projection): Unit =
        p match
            case Sql.Projection.Resolved(terms) =>
                if terms.isEmpty then ctx.append("*")
                else ctx.joinWith(", ")(terms)(t => projectedTerm(ctx, t))

    private def projectedTerm(ctx: Idiom.Ctx, t: Sql.Term[?]): Unit =
        t match
            case lt: Sql.LabelledTerm[?, ?] =>
                term(ctx, lt.term)
                ctx.append(" AS ")
                ctx.appendQuoted(lt.label)
            case other =>
                term(ctx, other)

    private def ordering(ctx: Idiom.Ctx, o: Sql.OrderingSpecs): Unit =
        o match
            case Sql.OrderingSpecs.Resolved(specs) => ctx.joinWith(", ")(specs)(spec => orderSpec(ctx, spec))

    private def cte(ctx: Idiom.Ctx, ctes: Chunk[Sql.CommonTable[?]], body: Sql.Query[?], recursive: Boolean): Unit =
        if recursive then
            if !supportsRecursiveCte(ctx.serverVersion) then ctx.unsupported("WITH RECURSIVE", recursiveCteSince)
            ctx.append("WITH RECURSIVE ")
        else ctx.append("WITH ")
        end if
        ctx.joinWith(", ")(ctes)(c => commonTable(ctx, c))
        ctx.append(" ")
        query(ctx, body)
    end cte

    private def overrideFor(overrides: Chunk[Sql.SetSpec[?, ?]], column: String): Maybe[Sql.SetSpec[?, ?]] =
        overrides.foldLeft(Maybe.empty[Sql.SetSpec[?, ?]]) { (found, spec) =>
            if found.isDefined || spec.column.sqlName != column then found else Maybe(spec)
        }

    private def fragment(ctx: Idiom.Ctx, f: Sql.Fragment[?]): Unit =
        f.parts.foreach {
            case Sql.Fragment.Lit(text)  => ctx.append(text)
            case b: Sql.Fragment.Bind[a] => ctx.appendBind(Sql.BoundValue(b.value, b.schema, b.typeName))
            case Sql.Fragment.Embed(t)   => term(ctx, t)
        }

end Idiom

/** The vocabulary a dialect and a render share: the flavor's identity, the server version a render targets, and the state one render
  * accumulates.
  *
  * [[Idiom.Id]] names the flavor and keys every dialect-keyed structure, the per-dialect SQL a static render produces and the dialect field
  * of each typed failure that names a backend among them. [[Idiom.ServerVersion]] is the `(major, minor, patch)` triple a client captures
  * from the server handshake, which every version-gated feature compares against. [[Idiom.Ctx]] is the per-render state each emitting
  * method writes through.
  */
object Idiom:

    /** One flavor's rendered statement: the SQL text, the binds in placeholder order, and the server version the render resolved.
      *
      * The single-dialect carrier [[Idiom.render]] lifts into the user-facing [[kyo.Sql.Rendered]] map, and the value the connection
      * tier and the static-render macro reach when they need one flavor's own text rather than the keyed map. Internal, and spelled
      * apart from `Sql.Rendered` on purpose so the two never collide in this namespace.
      */
    final case class Rendering private[kyo] (
        sql: String,
        params: Chunk[Sql.BoundValue[?]],
        version: Idiom.ServerVersion
    )

    /** Identifier for a SQL flavor, `"postgres"` or `"mysql"`. Wraps a plain string so an id cannot be passed where an arbitrary string was
      * meant, or the reverse.
      */
    opaque type Id = String

    object Id:

        inline given CanEqual[Id, Id] =
            CanEqual.derived

        /** Wraps a raw dialect name. */
        def apply(name: String): Id =
            name

        extension (self: Id)
            /** The raw dialect name this id wraps. */
            def value: String =
                self
        end extension

    end Id

    /** A database server's `(major, minor, patch)` version, packed into a single `Long`.
      *
      * The packing keeps the natural ordering of the triple, so two versions compare as their underlying longs. Every value denotes a real
      * version: there is no in-band absence, so a caller that may not know the version carries `Maybe[ServerVersion]` and a render resolves
      * it against [[Idiom.capabilityFloor]].
      */
    opaque type ServerVersion = Long

    object ServerVersion:

        inline given CanEqual[ServerVersion, ServerVersion] =
            CanEqual.derived

        /** Orders by the packed value, which the component layout makes equivalent to comparing the triple lexicographically. */
        given Ordering[ServerVersion] =
            Ordering.Long

        /** Packs a `(major, minor, patch)` triple. Each component contributes its low bits, an upper bound far above any real server
          * version.
          */
        def apply(major: Int, minor: Int, patch: Int): ServerVersion =
            ((major.toLong & componentMask) << (componentBits * 2)) |
                ((minor.toLong & componentMask) << componentBits) |
                (patch.toLong & componentMask)

        /** Reads the leading version out of the string a server reports at handshake, `Absent` when it carries none.
          *
          * Engines pad the version they announce with build detail a comparison must ignore: a build suffix (`"8.0.34-log"`), a packaging
          * note (`"15.6 (Debian 15.6-1.pgdg120+2)"`), a pre-release letter (`"17beta1"`). Parsing takes the leading `major[.minor[.patch]]`
          * run and stops at the first character that cannot belong to it. A component the server omits reads as zero, which is what
          * omitting it means: `"16"` denotes 16.0.0. A string with no leading version at all is `Absent` rather than a zero version.
          */
        def parse(reported: String): Maybe[ServerVersion] =
            components(reported) match
                case Maybe.Present((major, minor, patch)) => Maybe(apply(major, minor, patch))
                case Maybe.Absent                         => Maybe.Absent

        extension (self: ServerVersion)
            /** The major component. */
            def major: Int =
                ((self >>> (componentBits * 2)) & componentMask).toInt

            /** The minor component. */
            def minor: Int =
                ((self >>> componentBits) & componentMask).toInt

            /** The patch component. */
            def patch: Int =
                (self & componentMask).toInt

            /** The triple as `"major.minor.patch"`. */
            def show: String =
                s"${self.major}.${self.minor}.${self.patch}"

            /** True when this version is at or above `other`, the shape a version-gated capability check takes. */
            def atLeast(other: ServerVersion): Boolean =
                self >= other

            /** True when this version is below `other`. */
            def below(other: ServerVersion): Boolean =
                self < other
        end extension

        private val componentBits: Int =
            20

        private val componentMask: Long =
            (1L << componentBits) - 1

        private def components(reported: String): Maybe[(Int, Int, Int)] =
            val leading = reported.takeWhile(c => c.isDigit || c == '.')
            leading.split('.').toSeq.filter(_.nonEmpty) match
                case Seq()                        => Maybe.Absent
                case Seq(major)                   => Maybe((component(major), 0, 0))
                case Seq(major, minor)            => Maybe((component(major), component(minor), 0))
                case Seq(major, minor, patch, _*) => Maybe((component(major), component(minor), component(patch)))
            end match
        end components

        private def component(digits: String): Int =
            digits.take(componentDigits).foldLeft(0)((acc, digit) => acc * 10 + (digit - '0'))

        private val componentDigits: Int =
            6

    end ServerVersion

    /** The state one render accumulates, and the seam every emitting method writes through.
      *
      * One instance per [[Idiom.render]] call, which is its only construction site. An [[Idiom]] is a stateless value shared by every
      * render, so the output buffer, the collected binds, the placeholder numbering, the resolved server version, and the call-site frame
      * live here instead. Not thread-safe, and it does not need to be: one render owns its `Ctx` for that render's duration.
      *
      * A `Ctx` carries no dispatch. A method that needs a subterm calls the idiom's own [[Idiom.term]] or one of its siblings, passing this
      * `ctx` along, which is what makes an override of any of them retarget every path that reaches it. Every flavor writes through this
      * same seam: what varies by flavor lives on the [[Idiom]].
      *
      * Because only [[Idiom.render]] can construct one, the emitting methods are inert outside a render: callable, but with no `Ctx` to
      * call them with.
      *
      * @param dialect
      *   the dialect this render targets, which the members here ask for the flavor's own spellings
      * @param version
      *   the version the render was asked for, `Absent` to target the dialect's capability floor; [[serverVersion]] is the resolved value
      * @param captureFrame
      *   the call site the render was asked for, read back as [[frame]]
      */
    final class Ctx private[kyo] (
        private[kyo] val dialect: Idiom,
        private[kyo] val version: Maybe[Idiom.ServerVersion],
        private[kyo] val captureFrame: Frame
    ):

        /** The server version this render targets: the caller's version when it named one, [[Idiom.capabilityFloor]] otherwise.
          *
          * Total, and resolved once when the `Ctx` is constructed, so a version gate is a plain comparison at every point of the walk.
          */
        def serverVersion: Idiom.ServerVersion =
            resolvedVersion

        /** The call site that asked for this render, carried into any typed failure the render raises. */
        def frame: Frame =
            captureFrame

        /** Appends `text` verbatim to the output. */
        def append(text: String): Unit =
            output.append(text)
            ()

        /** Records `value` as the next bind and emits [[Idiom.placeholder]] for its position.
          *
          * The one seam every bind reaches: an inserted row's cell, an `sql"..."` argument, an `UPDATE ... SET` value, and every literal a
          * predicate carries. A placeholder stands for exactly one parameter, and a [[kyo.Sql.BoundValue]] carries an
          * [[kyo.SqlSchema.Column]], which occupies exactly one column by construction, so the pairing is an invariant of the types rather
          * than a runtime check.
          */
        final def appendBind(value: Sql.BoundValue[?]): Unit =
            bindBuffer.append(value)
            append(dialect.placeholder(nextBindPosition))
            nextBindPosition += 1
        end appendBind

        /** `ident` in the target flavor's identifier syntax, which is [[Idiom.quoteIdent]]'s answer. */
        def quoted(ident: String): String =
            dialect.quoteIdent(ident)

        /** Appends `ident`, quoted in the target flavor's identifier syntax. */
        def appendQuoted(ident: String): Unit =
            append(quoted(ident))

        /** Applies `f` to each of `items`, appending `sep` between consecutive applications. */
        def joinWith[A](sep: String)(items: IterableOnce[A])(f: A => Unit): Unit =
            var first = true
            items.iterator.foreach { item =>
                if !first then append(sep)
                first = false
                f(item)
            }
        end joinWith

        /** Fails the render because the target cannot express `feature`, naming the release that introduced it when one exists.
          *
          * The typed-failure exit for the whole walk, and the answer a flavor gives for a construct it lacks, in place of text the server
          * would reject. It raises [[kyo.SqlUnsupportedDialectFeatureException]] rather than suspending, the render path being a pure
          * function on both sides of its use: at run time the caller's `Abort[SqlException]` catches it, and at splice time it surfaces as
          * a macro compile error.
          */
        final def unsupported(feature: String, requiredVersion: Maybe[Idiom.ServerVersion]): Nothing =
            // The handshake version is the caller's own Maybe, not the floor-resolved value, so it stays Absent when no version was
            // captured rather than reporting a fabricated one, per the exception's contract.
            throw kyo.SqlUnsupportedDialectFeatureException(
                feature,
                dialect.id,
                requiredVersion,
                version
            )(using frame)

        /** The statement text accumulated so far. */
        private[kyo] def text: String =
            output.toString

        /** The collected binds, in the order their placeholders were emitted. */
        private[kyo] def binds: Chunk[Sql.BoundValue[?]] =
            Chunk.from(bindBuffer.toSeq)

        private val resolvedVersion: Idiom.ServerVersion =
            version match
                case Maybe.Present(v) => v
                case Maybe.Absent     => dialect.capabilityFloor

        private val output: StringBuilder =
            new StringBuilder

        private val bindBuffer: scala.collection.mutable.ArrayBuffer[Sql.BoundValue[?]] =
            scala.collection.mutable.ArrayBuffer.empty[Sql.BoundValue[?]]

        private var nextBindPosition: Int =
            1

    end Ctx

end Idiom
