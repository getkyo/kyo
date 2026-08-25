package kyo.internal

import kyo.*
import kyo.Test
import kyo.db.Connection

/** Unit tests for the [[kyo.db.Connection]] companion members that decide what an ended exchange left behind.
  *
  * `isProtocolFatal` classifies an exception by whether it left the wire usable, and `leftSessionIdle` turns an exit edge into a yes-or-no
  * about the session. Both have two consumers that must agree: a connection lowers its in-flight flag on the same answer the pool uses to
  * decide release-or-destroy, so a discrepancy between them would either leak a cancel on a healthy connection or pool a desynced one.
  *
  * `closingOnFailure` is here for the same reason, being the third member of that companion, but it is the one leaf that needs a real
  * socket: the property is whether a descriptor is shut, which a stub cannot answer. So this suite is not container-free: it binds a
  * loopback listener and uses [[kyo.Scope]]. Still no database.
  *
  * Everything asserted here is `private[kyo]`, which is why the leaves live in `kyo.internal`.
  */
class SqlConnectionTest extends SqlContainerTest:

    "a decode failure is protocol-fatal, because the framing no longer agrees with the server" in {
        // SqlDecodeException means the reader and the server disagree about where a message ends.
        // Handing such a connection to the next borrower corrupts its first read.
        val decodeError = SqlDecodeColumnDecodeException(0, new Exception("expected Int32 but got NUL"))
        assert(
            Connection.isProtocolFatal(decodeError),
            "isProtocolFatal(Decode) must be true, Decode means wire desync, connection is poisoned"
        )
    }

    "an unsupported operation is not protocol-fatal, because it never reached the wire" in {
        val unsupportedError = SqlUnsupportedCustomTypeException("geometry")
        assert(
            !Connection.isProtocolFatal(unsupportedError),
            "isProtocolFatal(Unsupported) must be false, Unsupported does not poison the connection"
        )
    }

    "a server error in SQLSTATE class 08 is protocol-fatal, because the server invalidated the connection" in {
        // SQLSTATE class 08 is `connection exception` per ISO SQL; 08006 is connection_failure,
        // the server having terminated the connection.
        val serverError = SqlServerException(
            sqlState = "08006",
            severity = "FATAL",
            message = "connection_failure",
            detail = Absent,
            hint = Absent,
            position = Absent,
            extra = Map.empty,
            sqlText = Absent,
            paramCount = 0,
            connectionId = Absent
        )
        assert(
            Connection.isProtocolFatal(serverError),
            "isProtocolFatal(Server sqlState=08006) must be true, SQLSTATE class 08 = connection exception"
        )
    }

    "closingOnFailure closes the socket when the body fails typed and the failure is handled in-fiber" in {
        // The mechanism, pinned where it lives rather than through any of its five call sites. The edge that
        // matters is a typed SqlException handled in the SAME fiber, which is what every handshake failure is:
        // auth rejected, SCRAM failed, TLS not advertised, secure transport required. `Sync.ensure` does not fire
        // there, so without this bracket the raw socket stays open until the fiber ends, and MySQL's
        // `SqlConfig.TlsMode.Allow` path handles exactly such a failure in-module and then opens a second socket
        // for the secure retry.
        //
        // A live socket rather than a stub, because "was close() called" is not the property; "is the descriptor
        // shut" is. FakeServer's handler hands us the server side of a real accepted connection.
        Scope.run {
            Latch.initWith(1) { observed =>
                AtomicRef.init(Maybe.empty[Boolean]).flatMap { openAfter =>
                    FakeServer.listenPort { conn =>
                        Abort.run[SqlException](
                            Connection.closingOnFailure(conn) {
                                Abort.fail(SqlConnectionClosedException("handshake refused by the server"))
                            }
                        ).andThen {
                            // Unsafe: reading a socket's open flag outside fiber suspension.
                            Sync.Unsafe.defer(conn.isOpen).flatMap { stillOpen =>
                                openAfter.set(Present(stillOpen)).andThen(observed.release)
                            }
                        }
                    }.flatMap { listener =>
                        // Provoke one accept. `minConnections = 1` is what makes init actually dial: without a
                        // warm-up the client opens nothing and the handler below would never run. The client's own
                        // outcome is irrelevant, since this handler shuts the socket rather than answering; all it
                        // has to do is get the server side of a real connection into the bracket.
                        val url     = s"postgres://u:p@127.0.0.1:${listener.port}/db"
                        val warming = SqlConfig(minConnections = 1, maxConnections = 1, acquireTimeout = 2.seconds)
                        Abort.run[Any](SqlClient.initUnscoped(url, warming)).andThen {
                            Abort.run[Timeout] {
                                observed.await
                            }.flatMap {
                                case Result.Success(_) =>
                                    openAfter.get.map { result =>
                                        assert(
                                            result == Present(false),
                                            s"the socket must be shut once the typed failure was handled, observed open=$result"
                                        )
                                    }
                                case other =>
                                    fail(s"the bracket never resolved, so no accept reached it: $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "a class-25 error is not protocol-fatal, because the exchange completed and the wire is idle" in {
        // 25P02, in_failed_sql_transaction: a statement issued inside a transaction that has already failed. The
        // server reports it through an ordinary ErrorResponse followed by the trailing ReadyForQuery that the
        // exchange's own drain consumes, so the round trip finished and the wire owes nothing.
        //
        // Classifying it fatal costs more than a wrong label. The raised in-flight flag makes the lease exit
        // quarantine the connection: a cancel goes to a server with nothing to cancel, the drain then blocks
        // reading an idle wire for the whole cancelTimeout, and a healthy connection is destroyed at the end of it
        // with cancels_timed_out recording a cancellation that never occurred. SQLSTATE class 25 is a
        // transaction-state problem, which the reclaim chain's rollback step resolves.
        val failedTxError = SqlServerException(
            sqlState = "25P02",
            severity = "ERROR",
            message = "current transaction is aborted, commands ignored until end of transaction block",
            detail = Absent,
            hint = Absent,
            position = Absent,
            extra = Map.empty,
            sqlText = Absent,
            paramCount = 0,
            connectionId = Absent
        )
        assert(
            !Connection.isProtocolFatal(failedTxError),
            "isProtocolFatal(Server sqlState=25P02) must be false: the barrier was consumed, so the session is idle"
        )
        assert(
            Connection.leftSessionIdle(Present(Result.Failure(failedTxError))),
            "a class-25 failure must leave the session idle, or the exit path quarantines a healthy connection"
        )
    }

    "a routine server error is not protocol-fatal, so the connection stays poolable" in {
        // 23505 is a unique violation: the server answered, drained, and is ready for the next statement.
        val serverError = SqlServerException(
            sqlState = "23505",
            severity = "ERROR",
            message = "duplicate key value violates unique constraint",
            detail = Absent,
            hint = Absent,
            position = Absent,
            extra = Map.empty,
            sqlText = Absent,
            paramCount = 0,
            connectionId = Absent
        )
        assert(
            !Connection.isProtocolFatal(serverError),
            "isProtocolFatal(Server sqlState=23505) must be false, a query-level error leaves the wire intact"
        )
    }

    "an exchange that ended without error left the session idle" in {
        assert(
            Connection.leftSessionIdle(Absent),
            "a normal exit must release the connection back to the pool"
        )
    }

    "an exchange that ended in a routine server error left the session idle" in {
        val e = SqlServerException("23505", "ERROR", "duplicate key value violates unique constraint")
        assert(
            Connection.leftSessionIdle(Present(Result.Failure(e))),
            "a non-fatal SqlException must release: the wire is idle and the session is reusable"
        )
    }

    "an exchange that ended in a transport failure did not leave the session idle" in {
        val e = SqlConnectionClosedException("read")
        assert(
            !Connection.leftSessionIdle(Present(Result.Failure(e))),
            "a protocol-fatal SqlException must destroy: the socket is already unusable"
        )
    }

    "an exchange that ended in a panic did not leave the session idle, because where the reader stopped is unknown" in {
        assert(
            !Connection.leftSessionIdle(Present(Result.Panic(new RuntimeException("boom")))),
            "a panic must destroy: the wire position cannot be established from the exit path"
        )
    }

    "an exchange that ended in a failure of some other type did not leave the session idle" in {
        // Not every failure that can reach the exit path is an SqlException: a caller's own typed error can,
        // and nothing about it says the wire survived.
        assert(
            !Connection.leftSessionIdle(Present(Result.Failure("some caller-owned error"))),
            "a non-SqlException failure must destroy, since it carries no claim about the wire"
        )
    }

end SqlConnectionTest
