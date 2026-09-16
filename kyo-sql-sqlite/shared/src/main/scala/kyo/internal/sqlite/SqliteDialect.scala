package kyo.internal.sqlite

import kyo.*
import kyo.db.Idiom

/** The SQLite SQL flavor.
  *
  * SQLite quotes identifiers the way PostgreSQL does and leaves its placeholders unnumbered the way MySQL does, so the two abstract
  * rendering members are each borrowed from a different sibling. What diverges is everything downstream of one property: SQLite resolves
  * types and names permissively instead of failing, which turns four constructs the baseline renders into silently wrong answers rather
  * than errors. Those four are the reason most of this file exists, and each carries the measurement in its own comment.
  *
  * Four constructs SQLite simply lacks fail typed at every version: LATERAL, the row-locking clauses, the ALL forms of INTERSECT and
  * EXCEPT, and the DEFAULT keyword in an expression position.
  *
  * Two engine behaviours a caller of this dialect should know, because neither shows up in rendered SQL:
  *
  *   - An INTERRUPTED write inside an explicit transaction rolls the WHOLE transaction back, which the other two engines do not do. A
  *     caller that cancels a statement and expects to carry on inside the same transaction is carrying on without one.
  *   - A prepared statement outlives a COMMIT on its own connection and goes on yielding rows, measured. A stream consumed after its
  *     transaction committed therefore reads rows the transaction is no longer protecting.
  *
  * A class with a companion object so the static-render macro can reach it through a public zero-argument constructor.
  */
class SqliteDialect extends Idiom:

    val id: Idiom.Id = Idiom.Id("sqlite")

    /** SQLite 3.39.0, the oldest release carrying every construct this dialect renders unconditionally. RIGHT and FULL OUTER JOIN arrived
      * there and are the newest such construct; RETURNING came in 3.35.0 and window functions in 3.25.0, so both are available at this
      * floor. The module vendors 3.53.4, which dominates all of them.
      */
    val capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(3, 39, 0)

    /** Double quotes with the embedded quote doubled, byte-identical to PostgreSQL.
      *
      * SQLite also accepts backticks and brackets, which are compatibility spellings for other engines rather than its own.
      */
    def quoteIdent(ident: String): String =
        val escaped = ident.replace("\"", "\"\"")
        s"\"$escaped\""
    end quoteIdent

    def placeholder(position: Int): String = "?"

    /** RETURNING on INSERT, UPDATE and DELETE since 3.35.0, below the floor above. */
    override def supportsReturning: Boolean = true

    /** SQLite has neither GROUPING SETS nor CUBE at any release. ROLLUP is missing too, but this flag does not cover it; see
      * [[groupByClause]].
      */
    override def supportsGroupingSets: Boolean = false

    /** SQLite's CAST never fails on an unknown type name. It picks a storage affinity by substring-matching the name and returns a value of
      * that class, so a wrong target produces a wrong value with nothing red.
      *
      * Measured against `'42.7'`, twelve of the baseline's seventeen spellings are wrong. `INTERVAL` contains `INT` and so yields the
      * integer `42`. `BYTEA` matches nothing and falls to NUMERIC, so the baseline's byte spelling does not produce bytes. `BOOLEAN`,
      * `UUID`, `JSONB`, `DATE` and `TIMESTAMP` all collapse to the same `real`. `TEXT[]` contains `TEXT`, so the array-ness is dropped.
      * `NUMERIC(10, 2)` ignores its precision and scale.
      *
      * So the map answers only for the five storage classes SQLite actually has, and everything else is [[Absent]], which [[cast]] turns
      * into a typed refusal. `Extension` is the load-bearing one: the baseline passes an extension type's declared name straight through,
      * which is exactly the invented-name case, so it must refuse rather than forward.
      *
      * `Numeric` answers `TEXT` rather than refusing, because that is where the type mapping already stores it: a decimal lives in a
      * TEXT-affinity column so its scale and its digits past a double survive, and a cast that moved it to REAL would undo that.
      */
    override def castTypeName(target: SqlType.Type): Maybe[String] = target match
        case SqlType.Type.Text          => Present("TEXT")
        case SqlType.Type.SmallInt      => Present("INTEGER")
        case SqlType.Type.Int           => Present("INTEGER")
        case SqlType.Type.BigInt        => Present("INTEGER")
        case SqlType.Type.Float32       => Present("REAL")
        case SqlType.Type.Float64       => Present("REAL")
        case SqlType.Type.Bytes         => Present("BLOB")
        case SqlType.Type.Numeric(_, _) => Present("TEXT")
        case SqlType.Type.Boolean | SqlType.Type.Uuid | SqlType.Type.Json | SqlType.Type.Date |
            SqlType.Type.Time | SqlType.Type.TimeWithOffset | SqlType.Type.DateTime | SqlType.Type.Timestamp |
            SqlType.Type.CalendarInterval | SqlType.Type.Array(_) | SqlType.Type.Extension(_) => Absent
    end castTypeName

    /** SQLite has no rollup at any version.
      *
      * A flag rather than an override of the rendering, so a caller can ask before rendering and so the flag cannot drift from what
      * [[groupByClause]] does: the baseline reads this and refuses for every flavor that answers false.
      *
      * Refusing at render time matters more here than the missing construct suggests. `GROUP BY ROLLUP (x)` is not a parse error on SQLite:
      * it parses as a scalar function call and fails at NAME RESOLUTION, with `no such function: ROLLUP`. A connection carrying a
      * user-defined function of that name would therefore run the statement and return silently wrong groups, which is why the text must
      * never reach the server.
      */
    override def supportsRollup: Boolean = false

    /** SQLite has no LATERAL at any version, and no keyword-free equivalent: a correlated subquery in FROM position fails too, with
      * `no such column`.
      *
      * Refused here rather than through [[lateralSince]], which is a floor and has no value meaning "never". Naming an impossibly high
      * version would leak into the failure, since `SqlUnsupportedDialectFeatureException` carries `requiredVersion` and would name a
      * release that does not exist.
      */
    /** Refused at every version, with no required version named.
      *
      * `lateralSince` is a FLOOR, so there is no value of it that means "never". Overriding the hook is what expresses that, and it keeps
      * the never-answer out of a shared SPI that no other engine needs it in.
      */
    override def lateral(ctx: Idiom.Ctx, l: Sql.Lateral[?, ?]): Unit =
        ctx.unsupported("LATERAL", Absent)

    /** SQLite has no row locking. Its concurrency is one writer at a time over the whole database, so there is nothing for a per-row clause
      * to mean. The whole clause is refused, the `OF` list and the NOWAIT / SKIP LOCKED behaviours included, since none has a spelling.
      */
    override def lock(ctx: Idiom.Ctx, lk: Sql.Lock[?]): Unit =
        ctx.unsupported("FOR UPDATE / FOR SHARE", Absent)

    /** SQLite has `INTERSECT` and `EXCEPT` and has always had them, so [[intersectExceptSince]] stays absent. It has neither ALL form at
      * any version, and there is no separate flag for those, so the two arms refuse here.
      */
    override def setOp(ctx: Idiom.Ctx, s: Sql.SetOp[?]): Unit =
        s.kind match
            case Sql.SetOp.Kind.IntersectAll => ctx.unsupported("INTERSECT ALL", Absent)
            case Sql.SetOp.Kind.ExceptAll    => ctx.unsupported("EXCEPT ALL", Absent)
            case _                           => super.setOp(ctx, s)

    /** SQLite rejects an alias column list on a derived table, which is the whole point of the baseline's rendering: a VALUES list names
      * its own columns and the projection reading it resolves only because the list renames them.
      *
      * SQLite's own names are `column1`, `column2`, and so on, so the rename moves inside a wrapping SELECT. The row list is left untouched
      * so the bind order is unchanged, which was measured with real placeholders rather than assumed.
      */
    override def valuesSource(ctx: Idiom.Ctx, v: Sql.ValuesFrom[?, ?]): Unit =
        if v.columnNames.isEmpty then
            ctx.append("(VALUES ")
            ctx.joinWith(", ")(v.rows)(row => valuesRow(ctx, row))
            ctx.append(")")
            derivedAlias(ctx, v.alias)
        else
            ctx.append("(SELECT ")
            ctx.joinWith(", ")(v.columnNames.zipWithIndex) { (name, idx) =>
                ctx.appendQuoted(s"column${idx + 1}")
                ctx.append(" AS ")
                ctx.appendQuoted(name)
            }
            ctx.append(" FROM (VALUES ")
            ctx.joinWith(", ")(v.rows)(row => valuesRow(ctx, row))
            ctx.append("))")
            derivedAlias(ctx, v.alias)
        end if
    end valuesSource

    /** SQLite rejects `SUBSTRING(x FROM a FOR b)` and takes the comma form.
      *
      * `SUBSTR` rather than `SUBSTRING`: the latter is an alias added in 3.34, and using the older name keeps this hook out of the floor
      * calculus entirely, which matters if the floor is ever lowered.
      */
    override def substring(ctx: Idiom.Ctx, sub: Sql.Substring): Unit =
        ctx.append("SUBSTR(")
        term(ctx, sub.expr)
        ctx.append(", ")
        term(ctx, sub.start)
        sub.length.foreach { l =>
            ctx.append(", ")
            term(ctx, l)
        }
        ctx.append(")")
    end substring

    /** Two arms diverge, both because SQLite's arithmetic silently narrows its operands.
      *
      * `Divide` and `DivideIntegral` must both answer the fraction. PostgreSQL reaches that by casting the dividend to NUMERIC, which does
      * nothing here: `CAST(7 AS NUMERIC) / NULLIF(2, 0)` is `3`, because NUMERIC affinity leaves an integer an integer. `CAST(... AS REAL)`
      * is the spelling that answers `3.5`.
      *
      * `Divide` needs it even though its own definition says both flavors' `/` already yields the fractional quotient, because that was
      * written about the two engines that shipped first. A DECIMAL column here is declared with TEXT affinity to keep its digits, and text
      * that parses as an integer divides as one, so a stored 10 over 4 answers 2 rather than 2.5: not a different answer, a truncated one,
      * with nothing downstream to flag it.
      *
      * A bound decimal is fixed at the source instead, by [[SqliteParamWriter.bigDecimal]] always carrying a decimal point. That covers the
      * divisor and nothing else, which is why the cast is still needed here: one DECIMAL COLUMN divided by another involves no bound value
      * at all, so both sides would parse as integers. `DivideTruncating` is deliberately absent, since truncating is what it asks for.
      *
      * `Mod` truncates BOTH operands to integers before computing, so `7.5 % 2.5` is `1.0` where both other engines answer `0.0`. The
      * result is typed `real`, so nothing downstream flags it. This is reachable from the typed DSL, `Sql.Term.%` being gated on
      * `SqlNumeric`, which has instances for `Float`, `Double`, `BigDecimal` and `BigInt`. The lowering is the definition of a remainder
      * under truncating division, which is the sign convention all three engines use.
      */
    override def arithmetic(ctx: Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit =
        ar.op match
            case Sql.Arithmetic.Op.Divide | Sql.Arithmetic.Op.DivideIntegral =>
                ctx.append("(CAST(")
                term(ctx, ar.left)
                ctx.append(" AS REAL) / ")
                term(ctx, guardingDivisor(ar).right)
                ctx.append(")")
            case Sql.Arithmetic.Op.Mod =>
                val divisor = guardingDivisor(ar).right
                ctx.append("(")
                term(ctx, ar.left)
                ctx.append(" - CAST(")
                term(ctx, ar.left)
                ctx.append(" / ")
                term(ctx, divisor)
                ctx.append(" AS INTEGER) * ")
                term(ctx, divisor)
                ctx.append(")")
            case _ => super.arithmetic(ctx, ar)
        end match
    end arithmetic

    /** SQLite has no expression meaning "this column's declared default", so a `DEFAULT` reaching an assignment refuses.
      *
      * This is the UPDATE path and the conflict-update path, which builds its SET list through the same seam. The INSERT path never gets
      * here: [[insertValues]] and [[insertPartialValues]] drop a defaulted column from the column list before its cell is rendered.
      */
    override def setValue(ctx: Idiom.Ctx, value: Sql.SetValue[?]): Unit =
        value match
            case _: Sql.SetValue.Default[?] => ctx.unsupported("DEFAULT as a value", Absent)
            case t: Sql.Term[?]             => term(ctx, t)

    /** Omits every column whose value is `DEFAULT`, rather than rendering a keyword SQLite does not have.
      *
      * Omission is the only correct lowering, and `NULL` is the trap it avoids. `NULL` happens to auto-assign for an `INTEGER PRIMARY KEY`,
      * which is the auto-key case this method exists to serve, so substituting it passes the generated-key tests and stores NULL instead of
      * the declared default for every other column. A silently wrong value rather than an error.
      */
    override def insertValues(ctx: Idiom.Ctx, i: Sql.Insert[?, ?], v: Sql.Insert.Values[?, ?]): Unit =
        val perColumn = i.columnNames.map(name => overrideFor(i.overrides, name))
        val keptIdx   = i.columnNames.indices.filterNot(idx => isDefaultOverride(perColumn, idx))
        ctx.append(" (")
        ctx.joinWith(", ")(keptIdx)(idx => ctx.appendQuoted(i.columnNames(idx)))
        ctx.append(") VALUES ")
        ctx.joinWith(", ")(v.rows) { row =>
            ctx.append("(")
            ctx.joinWith(", ")(keptIdx) { idx =>
                val overridden = if idx < perColumn.size then perColumn(idx) else Maybe.Absent
                overridden match
                    case Maybe.Present(spec) => setValue(ctx, spec.value)
                    case Maybe.Absent        => ctx.appendBind(row(idx))
            }
            ctx.append(")")
        }
    end insertValues

    /** The partial-row counterpart of [[insertValues]]: a column whose value is `DEFAULT`, whether from the caller's own set list or from
      * an override, leaves the column list entirely.
      */
    override def insertPartialValues(ctx: Idiom.Ctx, i: Sql.Insert[?, ?], pv: Sql.Insert.PartialValues[?, ?]): Unit =
        val effective = pv.sets.map { s =>
            val value = overrideFor(i.overrides, s.column.sqlName) match
                case Maybe.Present(spec) => spec.value
                case Maybe.Absent        => s.value
            (s.column, value)
        }
        val kept = effective.filterNot((_, value) => isDefault(value))
        ctx.append(" (")
        ctx.joinWith(", ")(kept)((column, _) => ctx.appendQuoted(column.sqlName))
        ctx.append(") VALUES (")
        ctx.joinWith(", ")(kept)((_, value) => setValue(ctx, value))
        ctx.append(")")
    end insertPartialValues

    /** Wraps the feeding query of an `INSERT ... SELECT` that also carries a conflict clause.
      *
      * SQLite's grammar reads the `ON` of `ON CONFLICT` as the start of a join constraint, which sqlite.org documents as a parsing
      * ambiguity. A trailing `WHERE` in the fed SELECT disambiguates it, but appending one to the query itself is wrong for a query that
      * already ends in a WHERE, and invisible for one that already carries a join `ON`. Wrapping the query as a derived table and putting
      * the tautology on the WRAPPER is correct for every shape, which was measured across a plain select, one with its own WHERE, one with
      * ORDER BY and LIMIT, a UNION, and one with a trailing RETURNING.
      */
    override def insert(ctx: Idiom.Ctx, i: Sql.Insert[?, ?]): Unit =
        i.source match
            case fs: Sql.Insert.FromSelect[?, ?, ?] if i.onConflict.nonEmpty =>
                if i.overrides.nonEmpty then ctx.unsupported("INSERT ... SELECT with an overridden column", Absent)
                ctx.append(insertKeyword(i.onConflict))
                ctx.appendQuoted(i.tableName)
                ctx.append(" (")
                ctx.joinWith(", ")(fs.columns)(c => ctx.appendQuoted(c.sqlName))
                ctx.append(") SELECT * FROM (")
                query(ctx, fs.query)
                ctx.append(") ")
                ctx.appendQuoted(SqliteDialect.UpsertSourceAlias)
                ctx.append(" WHERE 1")
                i.onConflict.foreach {
                    case dn: Sql.Insert.OnConflict.DoNothing[?] => onConflictDoNothing(ctx, dn)
                    case du: Sql.Insert.OnConflict.DoUpdate[?]  => onConflictDoUpdate(ctx, du)
                }
                i.returning match
                    case Maybe.Present(cols) => returning(ctx, cols)
                    case Maybe.Absent =>
                        i.autoKey.foreach { col =>
                            ctx.append(" RETURNING ")
                            ctx.appendQuoted(col)
                        }
                end match
            case _ => super.insert(ctx, i)
        end match
    end insert

    /** Two arms diverge, and only the first is a syntax difference.
      *
      * `IS UNKNOWN` does not exist. For a boolean-valued expression it means exactly `IS NULL`, measured: `NULL IS NULL` is true, a decided
      * comparison is not, and a comparison against NULL is. The lowering is exact rather than an approximation.
      *
      * The `IS TRUE` family is the second arm, and it is lowered for the reason in [[emptyIn]]: its predicate spells `TRUE` and `FALSE`,
      * which a column of those names shadows.
      */
    override def term(ctx: Idiom.Ctx, t: Sql.Term[?]): Unit =
        t match
            case iu: Sql.IsUnknown =>
                ctx.append("(")
                term(ctx, iu.expr)
                ctx.append(" IS NULL)")
            case inu: Sql.IsNotUnknown =>
                ctx.append("(")
                term(ctx, inu.expr)
                ctx.append(" IS NOT NULL)")
            case bt: Sql.BoolTest =>
                ctx.append("(")
                term(ctx, bt.expr)
                ctx.append(" ")
                ctx.append(SqliteDialect.boolTestSpelling(bt.pred))
                ctx.append(")")
            case _ => super.term(ctx, t)
        end match
    end term

    /** `0` and `1` rather than `FALSE` and `TRUE`, because SQLite's boolean literals are not reserved words.
      *
      * sqlite.org's 3.23.0 notes state it: "Recognize TRUE and FALSE as constants. (For compatibility, if there exist columns named 'true'
      * or 'false', then the identifiers refer to the columns rather than Boolean constants.)" They are fallback constants, live only when
      * nothing in scope claims the name, and this renders in predicate position where the table's columns are in scope.
      *
      * Measured: over a table with a column named `false` holding 1, `WHERE (FALSE)` returns the row, so an empty `IN ()` would match every
      * row instead of none. A bare integer cannot be a column reference, so the shadow is unreachable from `0` and `1`, and SQLite's
      * booleans are integers anyway.
      */
    override def emptyIn(ctx: Idiom.Ctx, negated: Boolean): Unit =
        ctx.append(if negated then "(1)" else "(0)")

    private def isDefault(value: Sql.SetValue[?]): Boolean =
        value match
            case _: Sql.SetValue.Default[?] => true
            case _                          => false

    private def isDefaultOverride(perColumn: Seq[Maybe[Sql.SetSpec[?, ?]]], idx: Int): Boolean =
        idx < perColumn.size && (perColumn(idx) match
            case Maybe.Present(spec) => isDefault(spec.value)
            case Maybe.Absent        => false)

end SqliteDialect

object SqliteDialect:

    /** Alias for the derived table wrapping an upsert's feeding query. Named rather than inlined so the reason survives: the wrapper exists
      * to carry a tautological WHERE past SQLite's `ON CONFLICT` parsing ambiguity, not to rename anything.
      */
    private[sqlite] val UpsertSourceAlias = "kyo_upsert_src"

    /** The `IS TRUE` family spelled without the unreserved boolean literals. See [[SqliteDialect.emptyIn]] for why they cannot be used. */
    private[sqlite] def boolTestSpelling(pred: Sql.BoolTest.Predicate): String =
        pred match
            case Sql.BoolTest.Predicate.IsTrue     => "IS 1"
            case Sql.BoolTest.Predicate.IsNotTrue  => "IS NOT 1"
            case Sql.BoolTest.Predicate.IsFalse    => "IS 0"
            case Sql.BoolTest.Predicate.IsNotFalse => "IS NOT 0"

end SqliteDialect
