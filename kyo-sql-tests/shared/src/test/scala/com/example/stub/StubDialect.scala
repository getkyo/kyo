package com.example.stub

import kyo.*
import kyo.db.Idiom

/** An out-of-tree SQL flavor, defined against the public [[kyo.db.Idiom]] surface alone.
  *
  * Its only reason to exist is to prove, by compiling, that a backend outside the `kyo` package can supply the four abstract flavor facts and
  * override the walk with nothing but the published hooks. It carries the two override-surface shapes Section 7 documents, so the render tests
  * pin the composition conventions by code rather than by scaladoc: an INTERCEPTION that prepends its own text and then defers to `super`, and a
  * CLAUSE OVERRIDE that replaces the baseline rendering of a clause outright.
  */
class StubDialect extends Idiom:

    val id: Idiom.Id = Idiom.Id("stub")

    /** The stub claims every construct at its floor, so nothing it renders is version-gated. */
    val capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(1, 0, 0)

    def quoteIdent(ident: String): String =
        val escaped = ident.replace("\"", "\"\"")
        s""""$escaped""""
    end quoteIdent

    def placeholder(position: Int): String = "?"

    /** Interception with `super` fallback: the stub prepends a marker to every projected select and then renders the baseline `SELECT ... FROM`
      * through `super`, so the composition is additive rather than a rewrite. This is the shape a dialect uses when it wants to decorate a
      * clause without reimplementing it.
      */
    override def select(ctx: Idiom.Ctx, s: Sql.Select[?, ?, ?]): Unit =
        ctx.append("/* stub */ ")
        super.select(ctx, s)

    /** Clause override: the stub renders `FETCH FIRST n ROWS ONLY` in place of the baseline `LIMIT n`, replacing the clause outright rather than
      * deferring to `super`. This is the shape a dialect uses when its wire syntax for a clause differs from the portable baseline.
      */
    override def limit(ctx: Idiom.Ctx, l: Sql.Limit[?]): Unit =
        query(ctx, l.sql)
        ctx.append(s" FETCH FIRST ${l.n} ROWS ONLY")
        if l.offset > 0 then ctx.append(s" OFFSET ${l.offset} ROWS")
    end limit

end StubDialect

/** The shared [[StubDialect]] instance, reached wherever the stub flavor is named directly. */
object StubDialect extends StubDialect
