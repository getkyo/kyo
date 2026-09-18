package kyo.internal.sqlite

import kyo.*
import kyo.db.Idiom

/** The SQLite SQL flavor.
  *
  * SQLite resolves types and names permissively instead of failing, so several constructs the baseline renders would produce silently wrong
  * answers here rather than errors. The overrides below correct those.
  *
  * One engine behaviour that shows up in no rendered SQL: a prepared statement outlives a COMMIT on its own connection and goes on yielding
  * rows, so a stream consumed after its transaction committed reads rows the transaction is no longer protecting.
  *
  * A class with a companion object so the static-render macro can reach it through a public zero-argument constructor.
  */
class SqliteDialect extends Idiom:

    val id: Idiom.Id = Idiom.Id("sqlite")

    /** SQLite 3.39.0, where RIGHT and FULL OUTER JOIN arrived: the newest construct this dialect renders unconditionally. RETURNING
      * (3.35.0) and window functions (3.25.0) sit below it. The module vendors 3.53.4.
      */
    val capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(3, 39, 0)

    def quoteIdent(ident: String): String =
        val escaped = ident.replace("\"", "\"\"")
        s"\"$escaped\""
    end quoteIdent

    def placeholder(position: Int): String = "?"

    /** RETURNING on INSERT, UPDATE and DELETE since 3.35.0, below the floor above. */
    override def supportsReturning: Boolean = true

    /** Neither GROUPING SETS nor CUBE at any release. ROLLUP is missing too but is not covered by this flag; see [[supportsRollup]]. */
    override def supportsGroupingSets: Boolean = false

    /** SQLite's CAST never fails on an unknown type name. It picks a storage affinity by substring-matching the name and returns a value of
      * that class, so a wrong target produces a wrong value with nothing red: `INTERVAL` contains `INT` and yields an integer, `BYTEA`
      * matches nothing and falls to NUMERIC, `BOOLEAN` and `UUID` and `DATE` all collapse to `real`.
      *
      * So the map answers only for the five storage classes SQLite actually has, and everything else is [[Absent]], which [[cast]] turns
      * into a typed refusal. `Extension` must refuse rather than forward: the baseline passes an extension type's declared name straight
      * through, which is exactly the invented-name case.
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
      * `GROUP BY ROLLUP (x)` is not a parse error here: it parses as a scalar function call and fails at name resolution, with `no such
      * function: ROLLUP`. A connection carrying a user-defined function of that name would run the statement and return silently wrong
      * groups, so the text must never reach the server.
      */
    override def supportsRollup: Boolean = false

    /** SQLite has no LATERAL at any version, and no keyword-free equivalent: a correlated subquery in FROM position fails too, with
      * `no such column`.
      *
      * Refused by overriding the hook rather than through [[lateralSince]], which is a floor and so has no value meaning "never": an
      * impossibly high version would leak into `SqlUnsupportedDialectFeatureException`, naming a release that does not exist.
      */
    override def lateral(ctx: Idiom.Ctx, l: Sql.Lateral[?, ?]): Unit =
        ctx.unsupported("LATERAL", Absent)

    /** SQLite has no row locking: its concurrency is one writer at a time over the whole database. The whole clause is refused, the `OF`
      * list and the NOWAIT / SKIP LOCKED behaviours included, since none has a spelling.
      */
    override def lock(ctx: Idiom.Ctx, lk: Sql.Lock[?]): Unit =
        ctx.unsupported("FOR UPDATE / FOR SHARE", Absent)

    /** Neither `INTERSECT ALL` nor `EXCEPT ALL` exists at any version, and no flag covers them, so the two arms refuse here. */
    override def setOp(ctx: Idiom.Ctx, s: Sql.SetOp[?]): Unit =
        s.kind match
            case Sql.SetOp.Kind.IntersectAll => ctx.unsupported("INTERSECT ALL", Absent)
            case Sql.SetOp.Kind.ExceptAll    => ctx.unsupported("EXCEPT ALL", Absent)
            case _                           => super.setOp(ctx, s)

    /** SQLite rejects an alias column list on a derived table, which is what the baseline's rendering relies on: a VALUES list names
      * its own columns and the projection reading it resolves only because the list renames them.
      *
      * SQLite's own names are `column1`, `column2`, and so on, so the rename moves inside a wrapping SELECT. The row list is left untouched
      * so the bind order is unchanged.
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

    /** SQLite rejects `SUBSTRING(x FROM a FOR b)` and takes the comma form. `SUBSTR` rather than `SUBSTRING`, which is an alias added in
      * 3.34, so this hook stays out of the floor calculus.
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
      * `Divide` and `DivideIntegral` must both answer the fraction, and casting the dividend to NUMERIC, which is what PostgreSQL does,
      * achieves nothing: NUMERIC affinity leaves an integer an integer, so `CAST(7 AS NUMERIC) / NULLIF(2, 0)` is `3` and only
      * `CAST(... AS REAL)` answers `3.5`. `Divide` needs the cast as much as `DivideIntegral` does, because a DECIMAL column is declared
      * with TEXT affinity to keep its digits and text that parses as an integer divides as one: a stored 10 over 4 would answer 2 rather
      * than 2.5. [[SqliteParamWriter.bigDecimal]] covers a bound divisor by always carrying a decimal point, but one DECIMAL column divided
      * by another involves no bound value at all. `DivideTruncating` is deliberately absent, since truncating is what it asks for.
      *
      * `Mod` truncates both operands to integers before computing, so `7.5 % 2.5` is `1.0` where both other engines answer `0.0`, typed
      * `real` with nothing downstream to flag it. The lowering is the remainder under truncating division.
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
      * The INSERT path never gets here: [[insertValues]] and [[insertPartialValues]] drop a defaulted column from the column list before
      * its cell is rendered.
      */
    override def setValue(ctx: Idiom.Ctx, value: Sql.SetValue[?]): Unit =
        value match
            case _: Sql.SetValue.Default[?] => ctx.unsupported("DEFAULT as a value", Absent)
            case t: Sql.Term[?]             => term(ctx, t)

    /** Omits every column whose value is `DEFAULT`, rather than rendering a keyword SQLite does not have.
      *
      * `NULL` is the trap omission avoids: it happens to auto-assign for an `INTEGER PRIMARY KEY`, so substituting it passes the
      * generated-key tests while storing NULL instead of the declared default for every other column.
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

    /** The partial-row counterpart of [[insertValues]]: a `DEFAULT` value, from the caller's set list or from an override, leaves the
      * column list entirely.
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
      * SQLite's grammar reads the `ON` of `ON CONFLICT` as the start of a join constraint, a parsing ambiguity sqlite.org documents, and a
      * trailing `WHERE` in the fed SELECT resolves it. Appending one to the query itself is wrong for a query that already ends in a WHERE
      * and invisible for one that already carries a join `ON`, so the tautology goes on a derived-table wrapper, which is correct for every
      * shape.
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

    /** `IS UNKNOWN` does not exist, and for a boolean-valued expression it means exactly `IS NULL`, so the lowering is exact rather than an
      * approximation.
      *
      * The `IS TRUE` family is lowered for the reason in [[emptyIn]]: its predicate spells `TRUE` and `FALSE`, which a column of those
      * names shadows.
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
      * They are fallback constants that live only when nothing in scope claims the name, and this renders in predicate position where the
      * table's columns are in scope. Over a table with a column named `false` holding 1, `WHERE (FALSE)` returns the row, so an empty
      * `IN ()` would match every row instead of none. A bare integer cannot be a column reference, and SQLite's booleans are integers
      * anyway.
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

/** The shared [[SqliteDialect]] instance. */
object SqliteDialect extends SqliteDialect:

    /** Alias for the derived table wrapping an upsert's feeding query, which exists to carry a tautological WHERE past SQLite's
      * `ON CONFLICT` parsing ambiguity rather than to rename anything.
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
