package kyo.internal

import kyo.*
import kyo.Sql.*
import scala.quoted.*

/** Macro entry points for the `.run` / `.runStatic` extension methods on `Query[A]` and `Action[A]` (Insert / Update / Delete).
  *
  * Both spellings emit the same call-site shape: take the SQL for the client's own dialect out of a [[kyo.Sql.Rendered]], then hand it and
  * the bind values to the `internalExecute*` entry matching the statement kind. What differs is where that `Rendered` comes from.
  *
  *   - `.runStatic` takes it from [[SqlStaticMacro]], which renders during expansion for every backend on the compile classpath, so the SQL
  *     strings are constants in the emitted tree and the run does no rendering at all. A statement that cannot be folded is a compile error.
  *   - `.run` attempts the same fold and, when it does not succeed, emits `.runDynamic` instead. Which path it took is invisible to the
  *     caller by design: both execute the same statement.
  *
  * The static path's cost is that a compile-time render knows no server version and so targets the dialect's capability floor, while
  * `.runDynamic` renders against the version the client handshook with. A statement using a construct above the floor therefore belongs on
  * `.runDynamic`.
  */
object SqlRunMacro:

    def runQueryImpl[A: Type](q: Expr[Query[A]])(using Quotes): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        val ev = summonEvidence[A](q)
        SqlStaticMacro.tryImpl(widenStatement(q)) match
            case Present(rendered) => emitQuery[A](rendered, ev)
            // The using args are supplied explicitly because `A` is abstract in this macro, so the quote cannot resolve the evidence
            // itself; `Frame` resolves at the splice site. No naming is threaded: the decode is positional by construction on both
            // the static path and this fallback.
            case Absent => '{ $q.runDynamic(using summon[Frame], $ev) }
        end match
    end runQueryImpl

    def runQueryStaticImpl[A: Type](q: Expr[Query[A]])(using Quotes): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        // SqlStaticMacro.impl reports the precise position and message when the AST cannot be folded.
        val ev = summonEvidence[A](q)
        emitQuery[A](SqlStaticMacro.impl(widenStatement(q)), ev)
    end runQueryStaticImpl

    // --- Insert[T, F] ---

    def runInsertImpl[T: Type, F: Type](ins: Expr[Insert[T, F]])(using
        Quotes
    ): Expr[SqlClient.InsertOutcome < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement(ins)) match
            case Present(rendered) => emitInsert(rendered)
            case Absent            => '{ $ins.runDynamic(using summon[Frame]) }

    def runInsertStaticImpl[T: Type, F: Type](ins: Expr[Insert[T, F]])(using
        Quotes
    ): Expr[SqlClient.InsertOutcome < (Abort[SqlException] & DB)] =
        emitInsert(SqlStaticMacro.impl(widenStatement(ins)))

    // --- Update[T, F] ---

    def runUpdateImpl[T: Type, F: Type](upd: Expr[Update[T, F]])(using Quotes): Expr[Long < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement(upd)) match
            case Present(rendered) => emitUpdate(rendered)
            case Absent            => '{ $upd.runDynamic(using summon[Frame]) }

    def runUpdateStaticImpl[T: Type, F: Type](upd: Expr[Update[T, F]])(using
        Quotes
    ): Expr[Long < (Abort[SqlException] & DB)] =
        emitUpdate(SqlStaticMacro.impl(widenStatement(upd)))

    // --- Delete[T, F] ---

    def runDeleteImpl[T: Type, F: Type](del: Expr[Delete[T, F]])(using Quotes): Expr[Long < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement(del)) match
            case Present(rendered) => emitUpdate(rendered)
            case Absent            => '{ $del.runDynamic(using summon[Frame]) }

    def runDeleteStaticImpl[T: Type, F: Type](del: Expr[Delete[T, F]])(using
        Quotes
    ): Expr[Long < (Abort[SqlException] & DB)] =
        emitUpdate(SqlStaticMacro.impl(widenStatement(del)))

    /** Widens a typed `Expr[Executable subtype]` to `Expr[Sql.Executable[?]]` so a single static-render entry point can serve all
      * executable subtypes (`Query[A]`, `Insert[T, F]`, `Update[T, F]`, `Delete[T, F]`, `Fragment[A]`).
      *
      * Each subtype `<: Sql.Executable[?]` by construction (verified at `Sql.scala`: `Query[A] extends Executable[A]`,
      * `Action[A] extends Executable[A]`, `Fragment[A] extends Executable[A]`). The widening is a safe upcast: `asExprOf` is the macro-API
      * analogue of an unchecked Scala upcast, and the subtype relationship makes it well-typed. Factored here so the cast is justified once
      * rather than inline at each call site.
      */
    private def widenStatement[E](e: Expr[E])(using Quotes): Expr[Sql.Executable[?]] =
        // Safe upcast: every caller passes Expr[Query[A]] / Expr[Insert[T, F]] / Expr[Update[T, F]] /
        // Expr[Delete[T, F]], all of which are subtypes of Sql.Executable[?].
        e.asExprOf[Sql.Executable[?]]

    // --- Query[A] ---

    /** Emits the row-returning dispatch: take this client's SQL out of the render, then decode each row through `schemaExpr`. The
      * decode is positional by construction (the renderer emitted the columns in field order), so no run-site naming is threaded.
      */
    private def emitQuery[A: Type](
        rendered: Expr[Sql.Rendered],
        schemaExpr: Expr[SqlSchema[A]]
    )(using Quotes): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        '{
            DB.state.map { state =>
                val r = $rendered
                r.sqlForOrFail(state.client.dialect.id).map(sql =>
                    state.client.internalExecuteQuery[A](sql, r.params, state.config)(using summon[Frame], $schemaExpr)
                )
            }
        }

    /** Emits the affected-row-count dispatch, shared by `Update` and `Delete`, which differ only in the SQL they render. */
    private def emitUpdate(rendered: Expr[Sql.Rendered])(using Quotes): Expr[Long < (Abort[SqlException] & DB)] =
        '{
            DB.state.map { state =>
                val r = $rendered
                r.sqlForOrFail(state.client.dialect.id).map(sql => state.client.internalExecuteUpdate(sql, r.params, state.config))
            }
        }

    /** Emits the insert dispatch, the one statement kind whose result carries a generated key. */
    private def emitInsert(rendered: Expr[Sql.Rendered])(using
        Quotes
    ): Expr[SqlClient.InsertOutcome < (Abort[SqlException] & DB)] =
        '{
            DB.state.map { state =>
                val r = $rendered
                r.sqlForOrFail(state.client.dialect.id).map(sql => state.client.internalExecuteInsert(sql, r.params, state.config))
            }
        }

    /** The [[kyo.SqlSchema]] evidence that proves `A` is SQL-storable, or a compile error at the call site telling the caller how to make it
      * one. The evidence is what admits `A` to a run; its carried schema (`.schema`) is the codec the decode runs through.
      */
    private def summonEvidence[A: Type](q: Expr[?])(using Quotes): Expr[SqlSchema[A]] =
        import quotes.reflect.*
        Expr.summon[SqlSchema[A]].getOrElse(
            report.errorAndAbort(
                s"${Type.show[A]} is not a SQL-storable result type. Add `derives SqlSchema` to ${Type.show[A]} (its fields must all be " +
                    s"single-column SQL types), or install a `given SqlSchema.Column[${Type.show[A]}]` (Sql.jsonColumn, Sql.enumText, " +
                    s"SqlSchema.of) for a custom single-column codec.",
                q.asTerm.pos
            )
        )
    end summonEvidence

end SqlRunMacro
