package kyo.internal.dolt

import kyo.*
import kyo.db.Idiom
import kyo.internal.mysql.MysqlDialect

/** The SQL flavor Dolt renders, which is MySQL's with its own identity.
  *
  * Dolt runs go-mysql-server rather than MySQL, so being wire-compatible does not by itself make it SQL-compatible. Each divergence below
  * was measured by the cross-engine conformance battery, where Dolt is registered as a backend. The version gate needs nothing special: a
  * 2.3.4 server answers `8.0.31` to `version()`, exactly [[MysqlDialect.capabilityFloor]]. Dolt's own release number is not a SQL version
  * and no gate reads it.
  *
  * The id is its own rather than MySQL's, so a `mysql` extension payload is not silently acceptable here and the static renderer has one
  * entry per engine. A class with a companion object, so the static-render macro can reach it through a public zero-argument constructor.
  */
class DoltDialect extends MysqlDialect:

    override val id: Idiom.Id = Idiom.Id("dolt")

    /** Dolt has no `GROUP BY ... WITH ROLLUP`, where MySQL does: go-mysql-server does not implement the clause, and a 2.3.4 server answers
      * `syntax error at position 84 near 'WITH'` for SQL MySQL accepts.
      */
    override def supportsRollup: Boolean = false

    /** Refuses the rollup arm, which [[supportsRollup]] cannot do on its own: MySQL overrides `groupByClause` to emit its own `WITH ROLLUP`
      * spelling and never consults the flag, so inheriting it would render the clause whatever the flag says.
      */
    override def groupByClause(ctx: Idiom.Ctx, g: Sql.GroupBy[?, ?]): Unit =
        g.kind match
            case Sql.GroupBy.Kind.Rollup => ctx.unsupported("GROUP BY ROLLUP", Absent)
            case _                       => super.groupByClause(ctx, g)

    /** Refuses a RANGE frame with an offset bound, which this engine computes wrongly and silently.
      *
      * Measured against a 2.3.4 server: over the values 1, 2, 2, 3 and NULL,
      * `SUM(v) OVER (ORDER BY v RANGE BETWEEN 2 PRECEDING AND CURRENT ROW)` answers 8 for every row, the whole partition, where the frame
      * selects 1, 5, 5, 8 and nothing for the absent row. Refused rather than declared as a capability, because a window that quietly
      * widens to the whole partition returns plausible numbers a caller has no way to notice. ROWS and GROUPS frames are untouched, and so
      * is a RANGE frame with no offset to get wrong.
      */
    override def windowSpec(ctx: Idiom.Ctx, s: Sql.WindowSpec): Unit =
        if Idiom.frameRequiresSingleOrderExpression(s.frame) then
            ctx.unsupported("a RANGE frame with an offset bound", Absent)
        else super.windowSpec(ctx, s)

    /** Writes a frame offset as a literal, because this engine refuses a bind parameter in that position.
      *
      * Measured: `RANGE BETWEEN ? PRECEDING AND CURRENT ROW` answers `unknown error: Code: INVALID_ARGUMENT`, while the same frame with the
      * number written in runs and gives the right windows. Anything but a literal falls through to the baseline and reaches the server as a
      * bind, where it fails with the server's own message rather than being silently rendered as something else.
      */
    override def frameBound(ctx: Idiom.Ctx, b: Sql.FrameBound): Unit =
        b match
            case p: Sql.FrameBound.Preceding => inlineOffset(ctx, p.n, "PRECEDING", () => super.frameBound(ctx, b))
            case f: Sql.FrameBound.Following => inlineOffset(ctx, f.n, "FOLLOWING", () => super.frameBound(ctx, b))
            case _                           => super.frameBound(ctx, b)

    private def inlineOffset(ctx: Idiom.Ctx, offset: Sql.Term[Int], keyword: String, fallback: () => Unit): Unit =
        offset match
            // The term is typed Int, so writing the literal out cannot produce anything but digits.
            case lit: Sql.Literal[?] =>
                ctx.append(lit.value.toString)
                ctx.append(" ")
                ctx.append(keyword)
            case _ => fallback()

end DoltDialect

/** The shared [[DoltDialect]] instance, used wherever this repository names the Dolt flavor directly. */
object DoltDialect extends DoltDialect
