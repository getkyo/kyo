package kyo.internal

import kyo.*
import kyo.SqlAst.*
import scala.quoted.*

/** Macro entry points for the `.run` / `.runStatic` extension methods on `Query[A]`, `Action[A]` (Insert/Update/Delete), and `Fragment[A]`.
  *
  * Static-fast-path wiring (G1.1, G4.2, G4.3):
  *
  * Every `run*Impl` entry now attempts compile-time AST reduction via [[SqlStaticMacro.impl]] before falling back to the runtime renderer.
  * The `runStatic*Impl` entries delegate to [[SqlStaticMacro.impl]] and surface the compile-time error directly when the AST is not
  * reducible (no runtime fallback).
  *
  * The splice flow inside each entry:
  *   1. Try to lift the AST `Expr[Executable[?]]` to a compile-time constant by probing `q.value` with the `FromExpr` givens brought into
  *      scope by [[ColumnFromExpr]] / [[RecordFromExpr]].
  *   2. On success (`.run`) or unconditionally (`.runStatic`): call [[SqlStaticMacro.impl]] which renders both backends at macro-expansion
  *      time and returns an `Expr[SqlRendered]`. Splice the result into a `SqlClient.use` block.
  *   3. On failure (`.run` only): emit the runtime-render fallback (existing behaviour).
  */
object SqlRunMacro:

    /** Widens a typed `Expr[Executable subtype]` to `Expr[SqlAst.Executable[?]]` so a single static-render entry point can serve all
      * executable subtypes (`Query[A]`, `Insert[T, F]`, `Update[T, F]`, `Delete[T, F]`, `Fragment[A]`).
      *
      * Each subtype `<: SqlAst.Executable[?]` by construction (verified at `SqlAst.scala`: `Query[A] extends Executable[A]`,
      * `Action[A] extends Executable[A]`, `Fragment[A] extends Executable[A]`). The widening is a safe upcast: `asExprOf` is the macro-API
      * analogue of an unchecked Scala upcast, and the subtype relationship makes it well-typed. Factored here so the cast is justified once
      * rather than inline at each call site (PHASE-7 audit W-2).
      */
    private def widenStatement[E](e: Expr[E])(using Quotes): Expr[SqlAst.Executable[?]] =
        // Safe upcast: every caller passes Expr[Query[A]] / Expr[Insert[T, F]] / Expr[Update[T, F]] /
        // Expr[Delete[T, F]], all of which are subtypes of SqlAst.Executable[?].
        e.asExprOf[SqlAst.Executable[?]]

    // --- Query[A] ---

    def runQueryImpl[A: Type](q: Expr[Query[A]])(using Quotes): Expr[Chunk[A] < (Async & Abort[SqlException] & Scope)] =
        import quotes.reflect.*
        val schemaExpr: Expr[SqlSchema[A]] = Expr.summon[SqlSchema[A]].getOrElse(
            report.errorAndAbort(
                s"Cannot find a SqlSchema[${Type.show[A]}] in scope. Derive one with `given SqlSchema[${Type.show[A]}] = SqlSchema.derived`.",
                q.asTerm.pos
            )
        )
        // Static fast-path: lift + render in a single FromExpr traversal (W-3). On success, splice the
        // compile-time-rendered constant; on failure, fall back to the runtime renderer.
        SqlStaticMacro.tryImpl(widenStatement(q)) match
            case Some(rendered) =>
                '{
                    SqlClient.use { client =>
                        val r = $rendered
                        client.executeBoundQuery[A](r.sql.forBackend(client.sqlBackend), r.params)(using $schemaExpr, summon[Frame])
                    }
                }
            case None =>
                '{
                    SqlClient.use { client =>
                        val r = SqlRender.render($q, client.sqlBackend)
                        client.executeBoundQuery[A](r.sql, r.params)(using $schemaExpr, summon[Frame])
                    }
                }
        end match
    end runQueryImpl

    def runQueryStaticImpl[A: Type](q: Expr[Query[A]])(using Quotes): Expr[Chunk[A] < (Async & Abort[SqlException] & Scope)] =
        import quotes.reflect.*
        val schemaExpr: Expr[SqlSchema[A]] = Expr.summon[SqlSchema[A]].getOrElse(
            report.errorAndAbort(
                s"Cannot find a SqlSchema[${Type.show[A]}] in scope. Derive one with `given SqlSchema[${Type.show[A]}] = SqlSchema.derived`.",
                q.asTerm.pos
            )
        )
        // Delegate to SqlStaticMacro.impl, it calls report.errorAndAbort with the precise position+message when the AST is not liftable.
        val rendered: Expr[SqlRendered] = SqlStaticMacro.impl(widenStatement(q))
        '{
            SqlClient.use { client =>
                val r = $rendered
                client.executeBoundQuery[A](r.sql.forBackend(client.sqlBackend), r.params)(using $schemaExpr, summon[Frame])
            }
        }
    end runQueryStaticImpl

    // --- Insert[T, F] ---

    def runInsertImpl[T: Type, F: Type](ins: Expr[Insert[T, F]])(using
        Quotes
    )
        : Expr[SqlClient.InsertOutcome < (Async & Abort[SqlException] & Scope)] =
        SqlStaticMacro.tryImpl(widenStatement(ins)) match
            case Some(rendered) =>
                '{
                    SqlClient.use { client =>
                        val r = $rendered
                        client.executeBoundInsert(r.sql.forBackend(client.sqlBackend), r.params)
                    }
                }
            case None =>
                '{
                    SqlClient.use { client =>
                        val r = SqlRender.render($ins, client.sqlBackend, summon[Frame])
                        client.executeBoundInsert(r.sql, r.params)
                    }
                }
        end match
    end runInsertImpl

    def runInsertStaticImpl[T: Type, F: Type](ins: Expr[Insert[T, F]])(using
        Quotes
    )
        : Expr[SqlClient.InsertOutcome < (Async & Abort[SqlException] & Scope)] =
        val rendered: Expr[SqlRendered] = SqlStaticMacro.impl(widenStatement(ins))
        '{
            SqlClient.use { client =>
                val r = $rendered
                client.executeBoundInsert(r.sql.forBackend(client.sqlBackend), r.params)
            }
        }
    end runInsertStaticImpl

    // --- Update[T, F] ---

    def runUpdateImpl[T: Type, F: Type](upd: Expr[Update[T, F]])(using
        Quotes
    )
        : Expr[Long < (Async & Abort[SqlException] & Scope)] =
        SqlStaticMacro.tryImpl(widenStatement(upd)) match
            case Some(rendered) =>
                '{
                    SqlClient.use { client =>
                        val r = $rendered
                        client.executeBoundUpdate(r.sql.forBackend(client.sqlBackend), r.params)
                    }
                }
            case None =>
                '{
                    SqlClient.use { client =>
                        val r = SqlRender.render($upd, client.sqlBackend)
                        client.executeBoundUpdate(r.sql, r.params)
                    }
                }
        end match
    end runUpdateImpl

    def runUpdateStaticImpl[T: Type, F: Type](upd: Expr[Update[T, F]])(using
        Quotes
    )
        : Expr[Long < (Async & Abort[SqlException] & Scope)] =
        val rendered: Expr[SqlRendered] = SqlStaticMacro.impl(widenStatement(upd))
        '{
            SqlClient.use { client =>
                val r = $rendered
                client.executeBoundUpdate(r.sql.forBackend(client.sqlBackend), r.params)
            }
        }
    end runUpdateStaticImpl

    // --- Delete[T, F] ---

    def runDeleteImpl[T: Type, F: Type](del: Expr[Delete[T, F]])(using
        Quotes
    )
        : Expr[Long < (Async & Abort[SqlException] & Scope)] =
        SqlStaticMacro.tryImpl(widenStatement(del)) match
            case Some(rendered) =>
                '{
                    SqlClient.use { client =>
                        val r = $rendered
                        client.executeBoundUpdate(r.sql.forBackend(client.sqlBackend), r.params)
                    }
                }
            case None =>
                '{
                    SqlClient.use { client =>
                        val r = SqlRender.render($del, client.sqlBackend)
                        client.executeBoundUpdate(r.sql, r.params)
                    }
                }
        end match
    end runDeleteImpl

    def runDeleteStaticImpl[T: Type, F: Type](del: Expr[Delete[T, F]])(using
        Quotes
    )
        : Expr[Long < (Async & Abort[SqlException] & Scope)] =
        val rendered: Expr[SqlRendered] = SqlStaticMacro.impl(widenStatement(del))
        '{
            SqlClient.use { client =>
                val r = $rendered
                client.executeBoundUpdate(r.sql.forBackend(client.sqlBackend), r.params)
            }
        }
    end runDeleteStaticImpl

end SqlRunMacro
