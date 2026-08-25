package kyo.internal.postgres

import kyo.*
import kyo.db.Idiom

/** The PostgreSQL SQL flavor. Postgres numbers bind placeholders ($1, $2) and quotes identifiers with double quotes, and carries every
  * construct the renderer emits at every version it claims, so nothing is version-gated. Baseline for most hooks; it keeps only the two
  * places it diverges from the portable baseline (the NUMERIC-cast integral quotient and ILIKE). A class with a companion object so the
  * static-render macro can reach it through a public zero-argument constructor.
  */
class PostgresDialect extends Idiom:

    val id: Idiom.Id = Idiom.Id("postgres")

    /** PostgreSQL 11, the oldest release carrying every construct this dialect renders unconditionally. */
    val capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(11, 0, 0)

    def quoteIdent(ident: String): String =
        val escaped = ident.replace("\"", "\"\"")
        s""""$escaped""""
    end quoteIdent

    def placeholder(position: Int): String = s"$$$position"

    override def supportsReturning: Boolean = true

    /** PostgreSQL's `/` on two integers truncates, so the exact fractional quotient casts the dividend to NUMERIC (numeric / int4
      * resolves to numeric / numeric). Only the dividend needs it. Every other arithmetic arm is the baseline.
      */
    override def arithmetic(ctx: Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit =
        ar.op match
            case Sql.Arithmetic.Op.DivideIntegral =>
                ctx.append("(CAST(")
                term(ctx, ar.left)
                ctx.append(" AS NUMERIC) / ")
                term(ctx, ar.right)
                ctx.append(")")
            case _ => super.arithmetic(ctx, ar)

    /** PostgreSQL has a dedicated case-insensitive match operator, ILIKE, so it overrides the portable LOWER(...) LIKE LOWER(...) baseline. */
    override def caseInsensitiveLike(ctx: Idiom.Ctx, expr: Sql.Term[String], pattern: Sql.Term[String], negated: Boolean): Unit =
        binary(ctx, expr, if negated then "NOT ILIKE" else "ILIKE", pattern)

end PostgresDialect

/** The shared [[PostgresDialect]] instance, used wherever this repository names the Postgres flavor directly. */
object PostgresDialect extends PostgresDialect
