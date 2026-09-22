package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlException
import kyo.Test
import kyo.net.StubConnection

/** Unit tests for [[PostgresConnection]]'s wire-level bookkeeping.
  *
  * Driven by a stub [[kyo.net.Connection]] fed hand-built frames, because the conditions under test are ones the server produces and the
  * public API cannot ask for: a `Close 'S'` is only ever sent for a name this driver itself registered, so a server that refuses one is not
  * reachable through a query.
  */
class PostgresConnectionTest extends Test:

    private def frame(tpe: Char, body: Array[Byte]): Span[Byte] =
        val len = body.length + 4
        Span.from(Array[Byte](
            tpe.toByte,
            ((len >> 24) & 0xff).toByte,
            ((len >> 16) & 0xff).toByte,
            ((len >> 8) & 0xff).toByte,
            (len & 0xff).toByte
        ) ++ body)
    end frame

    private def cstr(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8) :+ 0.toByte

    private val errorFrame =
        frame(
            'E',
            Array('S'.toByte) ++ cstr("ERROR") ++
                Array('C'.toByte) ++ cstr("26000") ++
                Array('M'.toByte) ++ cstr("no such statement") ++
                Array(0.toByte)
        )

    private val readyFrame = frame('Z', Array('I'.toByte))

    /** A `ParameterStatus`, standing in for whatever the next exchange's first message would be. */
    private val nextFrame = frame('S', cstr("sentinel") ++ cstr("ok"))

    /** An unrecognised type byte, which fails the drain while leaving the socket open and the connection poolable. */
    private val undecodableFrame = frame('!', Array.empty[Byte])

    private def connect(conn: kyo.net.Connection)(using Frame): PostgresConnection < Sync =
        for
            channel     <- PostgresChannel(conn)
            params      <- AtomicRef.init(Map.empty[String, String])
            notifChan   <- Channel.initUnscoped[NotificationResponse](8)
            closesRef   <- AtomicRef.init(Chunk.empty[String])
            stmtCounter <- AtomicLong.init(0L)
            stmtCache   <- PostgresConnection.mkStmtCache(closesRef, 8, Duration.Zero)
            stmtRef     <- AtomicRef.init(stmtCache)
        yield new PostgresConnection(channel, params, 1, 2, notifChan, stmtRef, 8, Duration.Zero, closesRef, stmtCounter)

    "a Close refused by the server still consumes the ReadyForQuery its Sync produces" in {
        // The trailing `Sync` is answered with a `ReadyForQuery` whether or not the Closes before it
        // errored. Failing on the ErrorResponse alone would leave that ReadyForQuery unread, and the next
        // exchange would take it for the answer to its own Sync, running one message behind from then on.
        val conn = StubConnection()
        connect(conn).flatMap { connection =>
            connection.pendingCloses.set(Chunk("s_gone")).andThen {
                Abort.run[Closed](conn.inbound.safe.put(errorFrame))
                    .andThen(Abort.run[Closed](conn.inbound.safe.put(readyFrame)))
                    .andThen(Abort.run[Closed](conn.inbound.safe.put(nextFrame)))
                    .andThen(Abort.run[SqlException](connection.drainPendingCloses))
                    .flatMap { outcome =>
                        assert(outcome.isFailure, s"the refused Close must be reported, got: $outcome")
                        Abort.run[SqlException](connection.channel.receive).map { next =>
                            assert(next == Result.Success(ParameterStatus("sentinel", "ok")))
                        }
                    }
            }
        }
    }

    "a drain that fails after a refused Close surfaces its own failure, not the server's" in {
        // The server error is non-fatal, so surfacing it tells the pool this session is idle and fit to
        // lend. If the drain failed, the ReadyForQuery is still coming and the next borrower reads it as
        // its own: the desync this drain exists to prevent, on a connection whose socket is still open.
        val conn = StubConnection()
        connect(conn).flatMap { connection =>
            connection.pendingCloses.set(Chunk("s_gone")).andThen {
                Abort.run[Closed](conn.inbound.safe.put(errorFrame))
                    .andThen(Abort.run[Closed](conn.inbound.safe.put(undecodableFrame)))
                    .andThen(Abort.run[SqlException](connection.drainPendingCloses))
                    .map {
                        case Result.Failure(e) =>
                            assert(
                                kyo.db.Connection.isProtocolFatal(e),
                                s"a connection owed a ReadyForQuery nobody read must not be poolable, and this error lets it be: $e"
                            )
                        case other => fail(s"Expected a failure, got: $other")
                    }
            }
        }
    }

    "the error a refused Close reports is the server's, once the drain has succeeded" in {
        // The drain runs for the wire's sake; what the caller needs to read is why the Close was refused.
        val conn = StubConnection()
        connect(conn).flatMap { connection =>
            connection.pendingCloses.set(Chunk("s_gone")).andThen {
                Abort.run[Closed](conn.inbound.safe.put(errorFrame))
                    .andThen(Abort.run[Closed](conn.inbound.safe.put(readyFrame)))
                    .andThen(Abort.run[SqlException](connection.drainPendingCloses))
                    .map {
                        case Result.Failure(e: SqlServerException) =>
                            assert(e.sqlState == "26000")
                        case other =>
                            fail(s"Expected the server's 26000, got: $other")
                    }
            }
        }
    }

end PostgresConnectionTest
