package com.example.stub

import kyo.*
import kyo.Sql.BoundValue
import kyo.db.Connection
import kyo.db.Idiom

/** A [[kyo.db.Connection]] built from the public SPI alone.
  *
  * The stub engine opens no socket and executes no statement, so every data member is inert: it exists to prove the whole `Connection`
  * surface is implementable outside the `kyo` package, not to talk to a server. The lifecycle members answer as a closed, idle session so the
  * pool machinery can hold one without a live wire.
  */
final class StubConnection extends Connection:

    def id: Long = 1L

    // Data members: the stub runs nothing, so each refuses rather than pretends.
    private def inert[A](member: String)(using Frame): A < (Async & Abort[SqlException]) =
        Abort.panic(new UnsupportedOperationException(s"StubConnection.$member: the stub engine executes no statements"))

    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) = Idiom.ServerVersion(1, 0, 0)

    def extendedQuery(sql: String, params: Chunk[BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        inert("extendedQuery")
    def extendedExecute(sql: String, params: Chunk[BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
        inert("extendedExecute")
    def extendedExecuteInsert(sql: String, params: Chunk[BoundValue[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) = inert("extendedExecuteInsert")
    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) = inert("simpleQuery")
    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException])        = inert("simpleExecute")
    def streamQuery(sql: String, params: Chunk[BoundValue[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](inert("streamQuery"))
    def pipelined(stmts: Chunk[(String, Chunk[BoundValue[?]])])(using
        Frame
    ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) = inert("pipelined")

    def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) = inert("beginTransaction")
    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException])                 = inert("commitTransaction")
    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException])               = inert("rollbackTransaction")
    def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])           = inert("savepoint")
    def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])    = inert("releaseSavepoint")
    def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) = inert("rollbackToSavepoint")
    def ping(using Frame): Unit < (Async & Abort[SqlException])                              = inert("ping")
    def resetSession(using Frame): Unit < (Async & Abort[SqlException])                      = inert("resetSession")
    def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
        inert("acquireAdvisoryLock")
    def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) = inert("releaseAdvisoryLock")
    def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException])                 = inert("cancelInFlight")
    def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException])      = inert("rollbackIfOpenTransaction")
    def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException])                 = inert("drainToIdle")

    // Lifecycle: a closed, idle session, so the pool can retire one without a wire.
    def inFlight(using AllowUnsafe): Boolean          = false
    def inOpenTransaction(using AllowUnsafe): Boolean = false
    def isOpen(using Frame): Boolean < Sync           = false
    def close(using Frame): Unit < Async              = ()
    def closeNow(using Frame, AllowUnsafe): Unit      = ()

end StubConnection

object StubConnection:

    /** The stub's [[kyo.db.Connection.Factory]]: it opens no socket, so it refuses. The stub backend assembles with `minConnections = 0`, so
      * warm-up never calls this, and no test leases a connection.
      */
    def factory(options: SqlConfig.Url.Options): Connection.Factory[StubConnection] =
        new Connection.Factory[StubConnection]:
            def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
                Frame
            ): StubConnection < (Async & Abort[SqlException]) =
                Abort.panic(new UnsupportedOperationException("StubConnection.factory: the stub engine opens no connections"))

end StubConnection
