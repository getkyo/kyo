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

    def runQueryImpl[A: Type](q: Expr[Query[A]], ev: Expr[SqlSchema[A]], frame: Expr[Frame])(using
        Quotes
    ): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement(q)) match
            case Present(rendered) => emitQuery[A](rendered, ev, frame)
            // The using args are supplied explicitly rather than summoned in the quote: `A` is abstract in this macro, so the quote
            // cannot resolve the evidence itself, and re-summoning the `Frame` would discard the one the caller already threaded and
            // derive a fresh one at the splice site. No naming is threaded: the decode is positional by construction on both the
            // static path and this fallback.
            case Absent => '{ $q.runDynamic(using $frame, $ev) }
        end match
    end runQueryImpl

    def runQueryStaticImpl[A: Type](q: Expr[Query[A]], ev: Expr[SqlSchema[A]], frame: Expr[Frame])(using
        Quotes
    ): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        // SqlStaticMacro.impl reports the precise position and message when the AST cannot be folded.
        emitQuery[A](SqlStaticMacro.impl(widenStatement(q)), ev, frame)
    end runQueryStaticImpl

    // --- Insert[T, F] ---

    def runInsertImpl[T: Type, F: Type](ins: Expr[Insert[T, F]], frame: Expr[Frame])(using
        Quotes
    ): Expr[SqlClient.InsertOutcome < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement(ins)) match
            case Present(rendered) => emitInsert(rendered)
            case Absent            => '{ $ins.runDynamic(using $frame) }

    def runInsertStaticImpl[T: Type, F: Type](ins: Expr[Insert[T, F]])(using
        Quotes
    ): Expr[SqlClient.InsertOutcome < (Abort[SqlException] & DB)] =
        emitInsert(SqlStaticMacro.impl(widenStatement(ins)))

    // --- Update[T, F] ---

    def runUpdateImpl[T: Type, F: Type](upd: Expr[Update[T, F]], frame: Expr[Frame])(using
        Quotes
    ): Expr[Long < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement(upd)) match
            case Present(rendered) => emitUpdate(rendered)
            case Absent            => '{ $upd.runDynamic(using $frame) }

    def runUpdateStaticImpl[T: Type, F: Type](upd: Expr[Update[T, F]])(using
        Quotes
    ): Expr[Long < (Abort[SqlException] & DB)] =
        emitUpdate(SqlStaticMacro.impl(widenStatement(upd)))

    // --- Update.Returning[T, F, A] / Delete.Returning[T, F, A] ---
    //
    // A returning write answers rows, so it emits the row-returning dispatch rather than the count one. The
    // statement it renders is the ordinary write it wraps, whose RETURNING clause the renderers already emit;
    // what the wrapper adds is the type those rows decode at.

    def runReturningImpl[T: Type, F: Type, A: Type](ret: Expr[Update.Returning[T, F, A]], ev: Expr[SqlSchema[A]], frame: Expr[Frame])(using
        Quotes
    ): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement('{ $ret.statement })) match
            case Present(rendered) => emitQuery[A](rendered, ev, frame)
            case Absent            => '{ $ret.runDynamic(using $frame, $ev) }
    end runReturningImpl

    def runReturningStaticImpl[T: Type, F: Type, A: Type](
        ret: Expr[Update.Returning[T, F, A]],
        ev: Expr[SqlSchema[A]],
        frame: Expr[Frame]
    )(using
        Quotes
    ): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        emitQuery[A](SqlStaticMacro.impl(widenStatement('{ $ret.statement })), ev, frame)

    def runDeleteReturningImpl[T: Type, F: Type, A: Type](ret: Expr[Delete.Returning[T, F, A]], ev: Expr[SqlSchema[A]], frame: Expr[Frame])(
        using Quotes
    ): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement('{ $ret.statement })) match
            case Present(rendered) => emitQuery[A](rendered, ev, frame)
            case Absent            => '{ $ret.runDynamic(using $frame, $ev) }
    end runDeleteReturningImpl

    def runDeleteReturningStaticImpl[T: Type, F: Type, A: Type](
        ret: Expr[Delete.Returning[T, F, A]],
        ev: Expr[SqlSchema[A]],
        frame: Expr[Frame]
    )(using
        Quotes
    ): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        emitQuery[A](SqlStaticMacro.impl(widenStatement('{ $ret.statement })), ev, frame)

    // --- Insert.Returning[T, F, A] ---
    //
    // An INSERT that names its returning columns answers rows, so it emits the row-returning dispatch rather than the
    // insert-outcome one. The renderer already prefers an explicit RETURNING over the auto-key clause it appends, so
    // there is one clause either way and the rows this decodes are the ones the caller asked for.

    def runInsertReturningImpl[T: Type, F: Type, A: Type](ret: Expr[Insert.Returning[T, F, A]], ev: Expr[SqlSchema[A]], frame: Expr[Frame])(
        using Quotes
    ): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement('{ $ret.statement })) match
            case Present(rendered) => emitQuery[A](rendered, ev, frame)
            case Absent            => '{ $ret.runDynamic(using $frame, $ev) }
    end runInsertReturningImpl

    def runInsertReturningStaticImpl[T: Type, F: Type, A: Type](
        ret: Expr[Insert.Returning[T, F, A]],
        ev: Expr[SqlSchema[A]],
        frame: Expr[Frame]
    )(using
        Quotes
    ): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        emitQuery[A](SqlStaticMacro.impl(widenStatement('{ $ret.statement })), ev, frame)

    // --- Delete[T, F] ---

    def runDeleteImpl[T: Type, F: Type](del: Expr[Delete[T, F]], frame: Expr[Frame])(using
        Quotes
    ): Expr[Long < (Abort[SqlException] & DB)] =
        SqlStaticMacro.tryImpl(widenStatement(del)) match
            case Present(rendered) => emitUpdate(rendered)
            case Absent            => '{ $del.runDynamic(using $frame) }

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
        schemaExpr: Expr[SqlSchema[A]],
        frame: Expr[Frame]
    )(using Quotes): Expr[Chunk[A] < (Abort[SqlException] & DB)] =
        '{
            DB.state.map { state =>
                val r = $rendered
                r.sqlForOrFail(state.client.dialect.id).map(sql =>
                    state.client.internalExecuteQuery[A](sql, r.params, state.config)(using $frame, $schemaExpr)
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

end SqlRunMacro
