package kyo.internal.mysql

import kyo.*
import kyo.db.Idiom

/** The MySQL SQL flavor.
  *
  * MySQL leaves its bind placeholders unnumbered (`?`) and quotes identifiers with backticks. Three of its constructs arrived in specific 8.0
  * patch releases, so their floors take the server version; asking for one below its release renders nothing and raises
  * [[kyo.SqlUnsupportedDialectFeatureException]] instead of emitting SQL the server would reject. Two constructs MySQL has never had,
  * `RETURNING` and a predicate on the conflict-update clause, fail the same way at every version.
  *
  * A class with a companion object so the static-render macro can reach it through a public zero-argument constructor.
  */
class MysqlDialect extends Idiom:

    val id: Idiom.Id = Idiom.Id("mysql")

    /** MySQL 8.0.31, the oldest release carrying every construct this dialect renders unconditionally. The newest such construct is the
      * `INTERSECT` / `EXCEPT` pair, which 8.0.31 introduced; `LATERAL` arrived in 8.0.14 and `WITH RECURSIVE` in 8.0.0. Rendering at this
      * floor therefore leaves all three available.
      */
    val capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(8, 0, 31)

    def quoteIdent(ident: String): String =
        val escaped = ident.replace("`", "``")
        s"`$escaped`"
    end quoteIdent

    def placeholder(position: Int): String = "?"

    // MySQL has neither grouping-set construct at any release; only GROUP BY ... WITH ROLLUP, which groupBy emits.
    override def supportsGroupingSets: Boolean = false

    override def lateralSince: Maybe[Idiom.ServerVersion]         = Present(Idiom.ServerVersion(8, 0, 14))
    override def recursiveCteSince: Maybe[Idiom.ServerVersion]    = Present(Idiom.ServerVersion(8, 0, 0))
    override def intersectExceptSince: Maybe[Idiom.ServerVersion] = Present(Idiom.ServerVersion(8, 0, 31))

    /** MySQL 8.0.19 introduced the `VALUES` table value constructor. No earlier release can read from a `VALUES` list at all. */
    override def valuesConstructorSince: Maybe[Idiom.ServerVersion] = Present(Idiom.ServerVersion(8, 0, 19))

    /** MySQL's `CAST` accepts a fixed short list of target types rather than every type it can store, so several types a MySQL column holds
      * happily have no cast spelling at all and answer `Absent`: there is no interval type to cast to, no zone-carrying time type, no array
      * type, and no extension types. The integer and boolean cases all land on `SIGNED`, MySQL's single signed-integer cast target, and the
      * string-shaped ones on `CHAR`, which is how MySQL stores a UUID.
      */
    override def castTypeName(target: SqlType.Type): Maybe[String] = target match
        case SqlType.Type.Text      => Present("CHAR")
        case SqlType.Type.SmallInt  => Present("SIGNED")
        case SqlType.Type.Int       => Present("SIGNED")
        case SqlType.Type.BigInt    => Present("SIGNED")
        case SqlType.Type.Boolean   => Present("SIGNED")
        case SqlType.Type.Float32   => Present("FLOAT")
        case SqlType.Type.Float64   => Present("DOUBLE")
        case SqlType.Type.Bytes     => Present("BINARY")
        case SqlType.Type.Uuid      => Present("CHAR")
        case SqlType.Type.Json      => Present("JSON")
        case SqlType.Type.Date      => Present("DATE")
        case SqlType.Type.Time      => Present("TIME")
        case SqlType.Type.DateTime  => Present("DATETIME")
        case SqlType.Type.Timestamp => Present("DATETIME")
        case SqlType.Type.Numeric(precision, scale) => (precision, scale) match
                case (Present(p), Present(s)) => Present(s"DECIMAL($p, $s)")
                case (Present(p), Absent)     => Present(s"DECIMAL($p)")
                case _                        => Present("DECIMAL")
        case SqlType.Type.TimeWithOffset   => Absent
        case SqlType.Type.CalendarInterval => Absent
        case SqlType.Type.Array(_)         => Absent
        case SqlType.Type.Extension(_)     => Absent
    end castTypeName

    /** MySQL spells rollup as a trailing modifier on the key list, `GROUP BY <keys> WITH ROLLUP`, rather than the function-call form
      * `GROUP BY ROLLUP (<keys>)` the baseline emits. Only the Rollup arm diverges; every other grouping kind is the baseline (CUBE and
      * GROUPING SETS fail typed, MySQL having no grouping sets). Overrides the clause seam alone, so the grouped source and the optional
      * HAVING stay the baseline's ([[Idiom.groupBy]] renders them around this), reusing the baseline key list ([[Idiom.groupByKeys]]).
      */
    override def groupByClause(ctx: Idiom.Ctx, g: Sql.GroupBy[?, ?]): Unit =
        g.kind match
            case Sql.GroupBy.Kind.Rollup =>
                ctx.append(" GROUP BY ")
                groupByKeys(ctx, g.keys)
                ctx.append(" WITH ROLLUP")
            case _ => super.groupByClause(ctx, g)

    /** MySQL's `/` is fractional for every operand type, `SELECT 7/2` being `3.5000`, so the exact fractional quotient (`DivideIntegral`)
      * is the baseline plain `/`. MySQL has no truncating `/`; it spells the truncated quotient `DIV`, so that arm diverges.
      */
    override def arithmetic(ctx: Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit =
        ar.op match
            case Sql.Arithmetic.Op.DivideTruncating => binary(ctx, ar.left, "DIV", ar.right)
            case _                                  => super.arithmetic(ctx, ar)

    /** MySQL's table value constructor names every row with the `ROW` keyword, `VALUES ROW(1, 2), ROW(3, 4)`, and rejects the bare
      * parenthesised form the baseline uses. The columns it produces are named `column_0`, `column_1`, and so on, which is why the renderer
      * always follows the constructor with an alias column list.
      */
    override def valuesRow(ctx: Idiom.Ctx, cells: Chunk[Sql.BoundValue[?]]): Unit =
        ctx.append("ROW(")
        ctx.joinWith(", ")(cells)(cell => ctx.appendBind(cell))
        ctx.append(")")
    end valuesRow

    /** MySQL fixes its null ordering (NULLs first ascending, last descending) and treats `NULLS FIRST` / `NULLS LAST` as a parse error. A
      * request that matches the fixed behaviour renders plainly; the other two lower to a leading null-ness term that moves the NULLs to the
      * requested end:
      *   - `ASC NULLS LAST` renders `<x> IS NULL, <x> ASC`, which pushes NULLs to the end.
      *   - `DESC NULLS FIRST` renders `<x> IS NOT NULL, <x> DESC`, which pulls NULLs to the front.
      */
    override def orderSpec(ctx: Idiom.Ctx, spec: Sql.OrderSpec): Unit =
        (spec.direction, spec.absent) match
            case (Sql.OrderSpec.Direction.Asc, Sql.OrderSpec.AbsentPlacement.Last) =>
                term(ctx, spec.expr)
                ctx.append(" IS NULL, ")
                term(ctx, spec.expr)
                ctx.append(" ASC")
            case (Sql.OrderSpec.Direction.Desc, Sql.OrderSpec.AbsentPlacement.First) =>
                term(ctx, spec.expr)
                ctx.append(" IS NOT NULL, ")
                term(ctx, spec.expr)
                ctx.append(" DESC")
            case (Sql.OrderSpec.Direction.Asc, _) =>
                term(ctx, spec.expr)
                ctx.append(" ASC")
            case (Sql.OrderSpec.Direction.Desc, _) =>
                term(ctx, spec.expr)
                ctx.append(" DESC")
        end match
    end orderSpec

    /** MySQL concatenates through a variadic function, so nested Concat nodes flatten into one call rather than nesting. */
    override def concat(ctx: Idiom.Ctx, cn: Sql.Concat): Unit =
        ctx.append("CONCAT(")
        ctx.joinWith(", ")(flatten(cn))(p => term(ctx, p))
        ctx.append(")")
    end concat

    /** MySQL references the incoming row inside a conflict-update through `VALUES(col)` rather than the baseline `EXCLUDED.col`. */
    override def excluded(ctx: Idiom.Ctx, columnName: String): Unit =
        ctx.append("VALUES(")
        ctx.appendQuoted(columnName)
        ctx.append(")")
    end excluded

    /** MySQL discards conflicting rows through the opening keyword, not a trailing clause: `INSERT IGNORE INTO` for a do-nothing conflict,
      * the plain `INSERT INTO` otherwise.
      */
    override def insertKeyword(onConflict: Maybe[Sql.Insert.OnConflict[?]]): String =
        onConflict match
            case Present(_: Sql.Insert.OnConflict.DoNothing[?]) => "INSERT IGNORE INTO "
            case Present(_: Sql.Insert.OnConflict.DoUpdate[?])  => "INSERT INTO "
            case Absent                                         => "INSERT INTO "

    /** Already expressed by `INSERT IGNORE INTO` in the opening keyword, so nothing is emitted here. */
    override def onConflictDoNothing(ctx: Idiom.Ctx, doNothing: Sql.Insert.OnConflict.DoNothing[?]): Unit =
        ()

    /** MySQL keys the update off any duplicate key rather than named target columns, so the targets do not appear. References to the
      * incoming row render as `VALUES(col)` through [[excluded]].
      */
    override def onConflictDoUpdate(ctx: Idiom.Ctx, doUpdate: Sql.Insert.OnConflict.DoUpdate[?]): Unit =
        if doUpdate.where.isDefined then
            // MySQL accepts no predicate on ON DUPLICATE KEY UPDATE. Dropping it silently would update rows the caller excluded.
            ctx.unsupported("ON CONFLICT ... WHERE", Absent)
        ctx.append(" ON DUPLICATE KEY UPDATE ")
        ctx.joinWith(", ")(doUpdate.sets)(spec => assignment(ctx, spec))
    end onConflictDoUpdate

    /** MySQL has no `FULL OUTER JOIN`. Emulate it as a `LEFT JOIN` unioned with the matching `RIGHT JOIN`, wrapped as a derived table so the
      * whole union is one FROM item and an enclosing clause attaches to all of it. Every other join kind is the baseline.
      */
    override def join(ctx: Idiom.Ctx, j: Sql.Join[?, ?]): Unit =
        j.kind match
            case Sql.JoinKind.FullOuter =>
                ctx.append("(SELECT * FROM ")
                from(ctx, j.left)
                ctx.append(" LEFT JOIN ")
                from(ctx, j.right)
                ctx.append(" ON ")
                term(ctx, j.predicate)
                ctx.append(" UNION SELECT * FROM ")
                from(ctx, j.left)
                ctx.append(" RIGHT JOIN ")
                from(ctx, j.right)
                ctx.append(" ON ")
                term(ctx, j.predicate)
                ctx.append(")")
                derivedAlias(ctx, "sub")
            case _ => super.join(ctx, j)

    /** Flattens nested [[Sql.Concat]] nodes into one level, so `CONCAT(CONCAT(a, b), c)` renders as `CONCAT(a, b, c)`. */
    private def flatten(concat: Sql.Concat): Seq[Sql.Term[String]] =
        concat.parts.toSeq.flatMap:
            case inner: Sql.Concat => flatten(inner)
            case other             => Seq(other)

end MysqlDialect

/** The shared [[MysqlDialect]] instance, used wherever this repository names the MySQL flavor directly. */
object MysqlDialect extends MysqlDialect
