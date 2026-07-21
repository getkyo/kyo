package kyo

import kyo.SqlAst.*

/** User-facing run surface for the redesigned DSL.
  *
  * Three extension methods are added to every `Query[A]` / `Insert[T, F]` / `Update[T, F]` / `Delete[T, F]`:
  *
  *   - `.run` — try the static-emission path first; fall back to the runtime renderer if the AST cannot be reduced at compile time.
  *   - `.runStatic` — require the static-emission path; produce a compile error if the AST cannot be reduced at compile time.
  *   - `.runDynamic` — skip the static path entirely; always use the runtime renderer. Non-inline.
  *
  * The static path is implemented in [[kyo.SqlStatic]]; the `FromExpr`-based AST reduction lives in [[kyo.internal.SqlRunMacro]]. `.run`
  * falls back to the runtime renderer when the AST cannot be fully reduced at compile time. `.runStatic` requires full compile-time
  * reduction and produces a compile error when the AST is not reducible.
  *
  * Each shape returns:
  *   - `Query[A]` → `Chunk[A]`
  *   - `Insert[T, F]` → `InsertResult` (affected rows + generated key)
  *   - `Update[T, F]` / `Delete[T, F]` → `Long` (affected rows)
  *
  * The effect row is `Async & Abort[SqlException] & Scope`. `Scope` is intentionally over-declared so the signature stays stable when
  * cursor-mode (large-result streaming) is added.
  */

extension [A](inline q: Query[A])

    /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time.
      *
      * Falls back to the runtime renderer when the AST cannot be fully reduced at compile time.
      *
      * ==Error types==
      * Aborts with [[SqlException]] (any subtype). The two most common decode-time variants:
      *   - [[SqlException.Decode]] — a column value could not be converted to the target Scala type. Check the [[SqlSchema]] derivation for
      *     `A`, ensure nullable columns use `Maybe[T]`, and confirm the database schema matches the query result.
      *   - [[SqlException.Unsupported]] — the [[SqlSchema]] decoder called a structural read operation (array element, map entry) that the
      *     backend does not yet implement. Re-derive the schema without the unsupported structural type, or supply a custom decoder via
      *     [[SqlSchema.withDecoder]].
      *
      * @tparam A
      *   the decoded row element type
      */
    // inline is required: the macro splice `${ ... }` cannot appear in a non-inline def.
    inline def run(using SqlSchema[A], Frame): Chunk[A] < (Async & Abort[SqlException] & Scope) =
        ${ kyo.internal.SqlRunMacro.runQueryImpl[A]('q) }

    /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible.
      *
      * @tparam A
      *   the decoded row element type
      */
    // inline is required: the macro splice `${ ... }` cannot appear in a non-inline def.
    inline def runStatic(using SqlSchema[A], Frame): Chunk[A] < (Async & Abort[SqlException] & Scope) =
        ${ kyo.internal.SqlRunMacro.runQueryStaticImpl[A]('q) }

end extension

extension [A](q: Query[A])

    /** Skip the static path entirely and always use the runtime renderer.
      *
      * Non-inline so it can be invoked with a `val` reference (which would resolve to an `Ident` and miss the inliner's view of the
      * construction tree).
      *
      * ==Error types==
      * Same as [[run]]: aborts with [[SqlException]]. Decode-time variants:
      *   - [[SqlException.Decode]] — column value could not be converted to the target Scala type.
      *   - [[SqlException.Unsupported]] — structural read operation not implemented by this backend.
      *
      * @tparam A
      *   the decoded row element type
      */
    def runDynamic(using SqlSchema[A], Frame): Chunk[A] < (Async & Abort[SqlException] & Scope) =
        SqlClient.use { client =>
            val r = kyo.internal.SqlRender.render(q, client.sqlBackend)
            client.executeBoundQuery[A](r.sql, r.params)
        }

end extension

extension [T, F](inline ins: Insert[T, F])

    /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time.
      *
      * Falls back to the runtime renderer when the AST cannot be fully reduced at compile time.
      *
      * @tparam T
      *   the case class type being inserted
      * @tparam F
      *   the field record type
      */
    // inline is required: the macro splice `${ ... }` cannot appear in a non-inline def.
    inline def run(using Frame): InsertResult < (Async & Abort[SqlException] & Scope) =
        ${ kyo.internal.SqlRunMacro.runInsertImpl[T, F]('ins) }

    /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible.
      *
      * @tparam T
      *   the case class type being inserted
      * @tparam F
      *   the field record type
      */
    // inline is required: the macro splice `${ ... }` cannot appear in a non-inline def.
    inline def runStatic(using Frame): InsertResult < (Async & Abort[SqlException] & Scope) =
        ${ kyo.internal.SqlRunMacro.runInsertStaticImpl[T, F]('ins) }

end extension

extension [T, F](ins: Insert[T, F])

    /** Skip the static path entirely and always use the runtime renderer.
      *
      * Non-inline so it can be invoked with a `val` reference.
      *
      * @tparam T
      *   the case class type being inserted
      * @tparam F
      *   the field record type
      */
    def runDynamic(using frame: Frame): InsertResult < (Async & Abort[SqlException] & Scope) =
        SqlClient.use { client =>
            val r = kyo.internal.SqlRender.render(ins, client.sqlBackend, frame)
            client.executeBoundInsert(r.sql, r.params)
        }

end extension

extension [T, F](inline upd: Update[T, F])

    /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time.
      *
      * Falls back to the runtime renderer when the AST cannot be fully reduced at compile time.
      *
      * @tparam T
      *   the case class type being updated
      * @tparam F
      *   the field record type
      */
    // inline is required: the macro splice `${ ... }` cannot appear in a non-inline def.
    inline def run(using Frame): Long < (Async & Abort[SqlException] & Scope) =
        ${ kyo.internal.SqlRunMacro.runUpdateImpl[T, F]('upd) }

    /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible.
      *
      * @tparam T
      *   the case class type being updated
      * @tparam F
      *   the field record type
      */
    // inline is required: the macro splice `${ ... }` cannot appear in a non-inline def.
    inline def runStatic(using Frame): Long < (Async & Abort[SqlException] & Scope) =
        ${ kyo.internal.SqlRunMacro.runUpdateStaticImpl[T, F]('upd) }

end extension

extension [T, F](upd: Update[T, F])

    /** Skip the static path entirely and always use the runtime renderer.
      *
      * Non-inline so it can be invoked with a `val` reference.
      *
      * @tparam T
      *   the case class type being updated
      * @tparam F
      *   the field record type
      */
    def runDynamic(using Frame): Long < (Async & Abort[SqlException] & Scope) =
        SqlClient.use { client =>
            val r = kyo.internal.SqlRender.render(upd, client.sqlBackend)
            client.executeBoundUpdate(r.sql, r.params)
        }

end extension

extension [T, F](inline del: Delete[T, F])

    /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time.
      *
      * Falls back to the runtime renderer when the AST cannot be fully reduced at compile time.
      *
      * @tparam T
      *   the case class type being deleted
      * @tparam F
      *   the field record type
      */
    // inline is required: the macro splice `${ ... }` cannot appear in a non-inline def.
    inline def run(using Frame): Long < (Async & Abort[SqlException] & Scope) =
        ${ kyo.internal.SqlRunMacro.runDeleteImpl[T, F]('del) }

    /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible.
      *
      * @tparam T
      *   the case class type being deleted
      * @tparam F
      *   the field record type
      */
    // inline is required: the macro splice `${ ... }` cannot appear in a non-inline def.
    inline def runStatic(using Frame): Long < (Async & Abort[SqlException] & Scope) =
        ${ kyo.internal.SqlRunMacro.runDeleteStaticImpl[T, F]('del) }

end extension

extension [T, F](del: Delete[T, F])

    /** Skip the static path entirely and always use the runtime renderer.
      *
      * Non-inline so it can be invoked with a `val` reference.
      *
      * @tparam T
      *   the case class type being deleted
      * @tparam F
      *   the field record type
      */
    def runDynamic(using Frame): Long < (Async & Abort[SqlException] & Scope) =
        SqlClient.use { client =>
            val r = kyo.internal.SqlRender.render(del, client.sqlBackend)
            client.executeBoundUpdate(r.sql, r.params)
        }

end extension
