package kyo.internal

import kyo.*
import kyo.SqlBackend
import kyo.SqlClient
import kyo.SqlException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.internal.mysql.BoundMysqlParam
import kyo.internal.postgres.BoundParam

/** Typeclass that captures all backend-specific bound-parameter operations.
  *
  * The two given instances ([[SqlBackendOps.postgres]] and [[SqlBackendOps.mysql]]) encode the dispatch on the active backend. Plain `def`
  * methods in `SqlClient` summon `SqlBackendOps` at their own call site (parameterised by the backend tag), so the dispatch is resolved
  * once by the compiler.
  *
  * The `Param` abstract type carries the backend-specific bound-parameter type. Each given instance fixes it to a concrete type, so the
  * implementations of [[runQuery]], [[runExecute]], and [[runStream]] are fully typed at their call sites with no casts.
  *
  * @tparam B
  *   the backend discriminator; must be a subtype of [[SqlBackend]]. The compiler selects between [[SqlBackendOps.postgres]] and
  *   [[SqlBackendOps.mysql]] based on `B` at each call site.
  *
  * @see
  *   [[SqlBackend]] for the discriminator ADT.
  * @see
  *   [[SqlBackendOps.postgres]] and [[SqlBackendOps.mysql]] for the two given instances.
  */
sealed trait SqlBackendOps[B <: SqlBackend]:
    type Param

    /** Selects the appropriate backend render mode for SQL placeholder generation. */
    def renderBackend: SqlBackend

    /** Runs a query and returns all rows as [[SqlRow]]. */
    def runQuery(client: SqlClient, sql: String, params: Chunk[Param])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException])

    /** Runs a DML statement and returns the affected-row count. */
    def runExecute(client: SqlClient, sql: String, params: Chunk[Param])(using Frame): Long < (Async & Abort[SqlException])

    /** Streams rows from the backend. */
    def runStream(
        client: SqlClient,
        sql: String,
        params: Chunk[Param],
        batchSize: Int
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope]

    /** Decodes a single [[SqlRow]] into `A` using this backend's wire format.
      *
      * @tparam A
      *   the Scala type to decode into; must have a [[kyo.SqlSchema]] instance
      */
    def readRow[A](schema: SqlSchema[A], row: SqlRow)(using Frame): A < Abort[SqlException.Decode]

end SqlBackendOps

object SqlBackendOps:

    /** Postgres backend ops: uses Postgres extended protocol and binary wire format. */
    given postgres: SqlBackendOps[SqlBackend.Postgres] with
        type Param = BoundParam[?]

        def renderBackend: SqlBackend = SqlBackend.Postgres

        def runQuery(client: SqlClient, sql: String, params: Chunk[BoundParam[?]])(using
            Frame
        ): Chunk[SqlRow] < (Async & Abort[SqlException]) =
            // Use the backend directly with pre-converted BoundParam; bypasses the public BoundValue surface.
            client.executePgQuery(sql, params)

        def runExecute(client: SqlClient, sql: String, params: Chunk[BoundParam[?]])(using
            Frame
        ): Long < (Async & Abort[SqlException]) =
            // Use the backend directly with pre-converted BoundParam; bypasses the public BoundValue surface.
            client.executePgUpdate(sql, params)

        def runStream(
            client: SqlClient,
            sql: String,
            params: Chunk[BoundParam[?]],
            batchSize: Int
        )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
            client.streamPgQuery(sql, params, batchSize)

        def readRow[A](schema: SqlSchema[A], row: SqlRow)(using Frame): A < Abort[SqlException.Decode] =
            schema.readPostgres(row)
    end postgres

    /** MySQL backend ops: uses MySQL binary prepared-statement protocol. */
    given mysql: SqlBackendOps[SqlBackend.Mysql] with
        type Param = BoundMysqlParam[?]

        def renderBackend: SqlBackend = SqlBackend.Mysql

        def runQuery(client: SqlClient, sql: String, params: Chunk[BoundMysqlParam[?]])(using
            Frame
        ): Chunk[SqlRow] < (Async & Abort[SqlException]) =
            client.queryMysqlFrag(sql, params)

        def runExecute(client: SqlClient, sql: String, params: Chunk[BoundMysqlParam[?]])(using
            Frame
        ): Long < (Async & Abort[SqlException]) =
            client.executeMysqlFrag(sql, params)

        def runStream(
            client: SqlClient,
            sql: String,
            params: Chunk[BoundMysqlParam[?]],
            batchSize: Int
        )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
            client.streamQueryMysqlFrag(sql, params, batchSize)

        def readRow[A](schema: SqlSchema[A], row: SqlRow)(using Frame): A < Abort[SqlException.Decode] =
            schema.readMysql(row)
    end mysql

end SqlBackendOps
