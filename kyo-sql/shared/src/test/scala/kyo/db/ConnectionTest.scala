package kyo.db

import kyo.*

/** Contract tests for [[Connection]]'s companion: the shared lifecycle policy ([[Connection.leftSessionIdle]],
  * [[Connection.isProtocolFatal]]), the handshake bracket ([[Connection.closingOnFailure]]), the user-name refusal
  * ([[Connection.requireUser]]), and the exit-edge view ([[Connection.errorOf]]).
  */
class ConnectionTest extends Test:

    private def serverError(sqlState: String)(using Frame): SqlServerException =
        SqlServerException(sqlState, "ERROR", "test error")

    // ── leftSessionIdle ───────────────────────────────────────────────────────

    "leftSessionIdle reports a normal exit as idle".timeout(10.seconds) in {
        assert(Connection.leftSessionIdle(Absent))
    }

    "leftSessionIdle reports a routine server error as idle".timeout(10.seconds) in {
        assert(Connection.leftSessionIdle(Present(Result.Failure(serverError("23505")))))
    }

    "leftSessionIdle reports a client-side request error as idle".timeout(10.seconds) in {
        assert(Connection.leftSessionIdle(Present(Result.Failure(SqlRequestAdvisoryLockException(7L, Absent)))))
    }

    "leftSessionIdle reports an unsupported operation as idle".timeout(10.seconds) in {
        assert(Connection.leftSessionIdle(Present(Result.Failure(SqlUnsupportedCustomTypeException("hstore")))))
    }

    // SQLSTATE class 25 is deliberately not protocol-fatal: the exchange completed, so the wire is idle.
    "leftSessionIdle reports a failed transaction state as idle".timeout(10.seconds) in {
        assert(Connection.leftSessionIdle(Present(Result.Failure(serverError("25P02")))))
    }

    "leftSessionIdle reports a protocol-fatal failure as not idle".timeout(10.seconds) in {
        assert(!Connection.leftSessionIdle(Present(Result.Failure(SqlConnectionClosedException("read")))))
        assert(!Connection.leftSessionIdle(Present(Result.Failure(SqlDecodeUuidException(4)))))
        assert(!Connection.leftSessionIdle(Present(Result.Failure(serverError("08006")))))
    }

    "leftSessionIdle reports a foreign typed failure as not idle".timeout(10.seconds) in {
        assert(!Connection.leftSessionIdle(Present(Result.Failure("boom"))))
        assert(!Connection.leftSessionIdle(Present(Result.Failure(new IllegalStateException("boom")))))
    }

    "leftSessionIdle reports a panic as not idle".timeout(10.seconds) in {
        assert(!Connection.leftSessionIdle(Present(Result.Panic(new RuntimeException("crash")))))
    }

    // ── isProtocolFatal ───────────────────────────────────────────────────────

    "isProtocolFatal reports connection failures as fatal".timeout(10.seconds) in {
        assert(Connection.isProtocolFatal(SqlConnectionClosedException("read")))
        assert(Connection.isProtocolFatal(SqlConnectionConnectFailedException("localhost", 5432, new Exception("refused"))))
    }

    "isProtocolFatal reports decode failures as fatal".timeout(10.seconds) in {
        assert(Connection.isProtocolFatal(SqlDecodeUuidException(4)))
        assert(Connection.isProtocolFatal(SqlDecodeColumnNotFoundException("name")))
    }

    "isProtocolFatal reports a server error in class 08 as fatal".timeout(10.seconds) in {
        assert(Connection.isProtocolFatal(serverError("08006")))
        assert(Connection.isProtocolFatal(serverError("08S01")))
    }

    "isProtocolFatal reports unsupported operations as not fatal".timeout(10.seconds) in {
        assert(!Connection.isProtocolFatal(SqlUnsupportedCustomTypeException("hstore")))
    }

    "isProtocolFatal reports request errors as not fatal".timeout(10.seconds) in {
        assert(!Connection.isProtocolFatal(SqlRequestAdvisoryLockException(7L, Absent)))
    }

    "isProtocolFatal reports other server classes as not fatal".timeout(10.seconds) in {
        assert(!Connection.isProtocolFatal(serverError("23505")))
        assert(!Connection.isProtocolFatal(serverError("42601")))
        assert(!Connection.isProtocolFatal(serverError("HY000")))
    }

    // SQLSTATE class 25 is a transaction-state problem the reclaim chain's rollback resolves, not a framing problem.
    "isProtocolFatal reports an invalid transaction state as not fatal".timeout(10.seconds) in {
        assert(!Connection.isProtocolFatal(serverError("25P02")))
        assert(!Connection.isProtocolFatal(serverError("25001")))
    }

    // ── closingOnFailure ──────────────────────────────────────────────────────

    "closingOnFailure leaves the socket open on success".timeout(10.seconds) in {
        val socket = kyo.net.StubConnection()
        Connection.closingOnFailure(socket)(42).map { value =>
            Sync.Unsafe.defer(socket.isOpen).map { open =>
                assert(value == 42)
                assert(open)
            }
        }
    }

    // The typed failure is handled in the same fiber, the edge the contract singles out: the socket must already be closed by the time
    // the handler sees the failure, not at fiber completion.
    "closingOnFailure closes the socket on a typed failure handled in the same fiber".timeout(10.seconds) in {
        val socket = kyo.net.StubConnection()
        val error  = SqlConnectionClosedException("handshake")
        Abort.run[SqlException](Connection.closingOnFailure(socket)(Abort.fail[SqlException](error))).map { outcome =>
            Sync.Unsafe.defer(socket.isOpen).map { open =>
                outcome match
                    case Result.Failure(e) => assert(e eq error)
                    case other             => fail("expected the typed failure to propagate, got: " + other)
                assert(!open)
            }
        }
    }

    "closingOnFailure closes the socket on a panic".timeout(10.seconds) in {
        val socket = kyo.net.StubConnection()
        val boom   = new IllegalStateException("boom")
        Abort.run[SqlException](Connection.closingOnFailure(socket)(Abort.panic[SqlException](boom))).map { outcome =>
            Sync.Unsafe.defer(socket.isOpen).map { open =>
                outcome match
                    case Result.Panic(t) => assert(t eq boom)
                    case other           => fail("expected the panic to propagate, got: " + other)
                assert(!open)
            }
        }
    }

    // ── requireUser ───────────────────────────────────────────────────────────

    "requireUser returns the declared user".timeout(10.seconds) in {
        val address = SqlConfig.Address("stub", "localhost", 5432, "app", Present("alice"))
        Connection.requireUser(address).map { user =>
            assert(user == "alice")
        }
    }

    // A declared empty user is what the URL said; whether it names an account is the server's judgment.
    "requireUser returns a declared empty user as it stands".timeout(10.seconds) in {
        val address = SqlConfig.Address("stub", "localhost", 5432, "app", Present(""))
        Connection.requireUser(address).map { user =>
            assert(user == "")
        }
    }

    "requireUser refuses an absent user".timeout(10.seconds) in {
        val address = SqlConfig.Address("stub", "localhost", 5432, "app", Absent)
        Abort.run[SqlException](Connection.requireUser(address)).map { outcome =>
            outcome match
                case Result.Failure(e: SqlConnectionUserRequiredException) => assert(e.scheme == "stub")
                case other => fail("expected SqlConnectionUserRequiredException, got: " + other)
        }
    }

    // ── errorOf ───────────────────────────────────────────────────────────────

    "errorOf reports a success as Absent".timeout(10.seconds) in {
        assert(Connection.errorOf[Int](Result.Success(42)).isEmpty)
    }

    "errorOf reports a failure as itself".timeout(10.seconds) in {
        val error = SqlConnectionClosedException("read")
        Connection.errorOf[Int](Result.Failure(error)) match
            case Present(Result.Failure(e: SqlException)) => assert(e eq error)
            case other                                    => fail("expected Present(Result.Failure), got: " + other)
    }

    "errorOf reports a panic as itself".timeout(10.seconds) in {
        val boom = new IllegalStateException("boom")
        Connection.errorOf[Int](Result.Panic(boom)) match
            case Present(Result.Panic(t)) => assert(t eq boom)
            case other                    => fail("expected Present(Result.Panic), got: " + other)
    }

    // ── parseServerVersion ────────────────────────────────────────────────────

    "parseServerVersion reads a plain three-component version".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("8.0.35")).map {
            case Result.Success(v) => assert(v == Idiom.ServerVersion(8, 0, 35))
            case other             => fail(s"Expected 8.0.35, got $other")
        }
    }

    // MySQL appends a build suffix to the version it announces in its handshake packet.
    "parseServerVersion ignores the MySQL build suffix".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("8.0.34-log")).map {
            case Result.Success(v) => assert(v == Idiom.ServerVersion(8, 0, 34))
            case other             => fail(s"Expected 8.0.34, got $other")
        }
    }

    "parseServerVersion ignores a distribution-qualified MySQL suffix".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("5.7.44-0ubuntu0.18.04.1")).map {
            case Result.Success(v) => assert(v == Idiom.ServerVersion(5, 7, 44))
            case other             => fail(s"Expected 5.7.44, got $other")
        }
    }

    // Postgres reports two components plus a packaging note in its server_version parameter.
    "parseServerVersion reads a Postgres two-component version as patch zero".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("16.2")).map {
            case Result.Success(v) => assert(v == Idiom.ServerVersion(16, 2, 0))
            case other             => fail(s"Expected 16.2.0, got $other")
        }
    }

    "parseServerVersion ignores the Postgres packaging note".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("15.6 (Debian 15.6-1.pgdg120+2)")).map {
            case Result.Success(v) => assert(v == Idiom.ServerVersion(15, 6, 0))
            case other             => fail(s"Expected 15.6.0, got $other")
        }
    }

    "parseServerVersion ignores a pre-release letter suffix".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("17beta1")).map {
            case Result.Success(v) => assert(v == Idiom.ServerVersion(17, 0, 0))
            case other             => fail(s"Expected 17.0.0, got $other")
        }
    }

    "parseServerVersion aborts when the string carries no leading version".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("unknown")).map {
            case Result.Failure(e: SqlConnectionProtocolDecodeException) =>
                assert(e.packetType == "server version")
                assert(e.getMessage.contains("server version"))
            case other => fail(s"Expected a protocol decode failure, got $other")
        }
    }

    "parseServerVersion aborts on an empty version string".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("")).map {
            case Result.Failure(_: SqlConnectionProtocolDecodeException) => succeed
            case other                                                   => fail(s"Expected a protocol decode failure, got $other")
        }
    }

    "parseServerVersion aborts when only separators are present".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("...")).map {
            case Result.Failure(_: SqlConnectionProtocolDecodeException) => succeed
            case other                                                   => fail(s"Expected a protocol decode failure, got $other")
        }
    }

    // A hostile or malformed announcement must not overflow a version component, which each holds in 20 bits.
    "parseServerVersion clamps an absurdly long component instead of overflowing".timeout(10.seconds) in {
        Abort.run[SqlConnectionProtocolDecodeException](Connection.parseServerVersion("123456789012345.1.2")).map {
            case Result.Success(v) => assert(v == Idiom.ServerVersion(123456, 1, 2))
            case other             => fail(s"Expected a clamped major component, got $other")
        }
    }

    // openSocket used to replace whatever the transport reported with `new Exception("connect refused")`, so a DNS failure, a refused
    // connect, an unavailable I/O backend and a connect timeout all surfaced as the same four words with no cause attached. A validation
    // report built on that message concluded the platform's socket layer was refusing an open port, when the message could not have said
    // anything about the socket at all. The cause has to survive.
    "openSocket carries the transport's own failure as the cause".timeout(30.seconds) in {
        // Port 1 on the loopback: privileged, never bound by a test, so the connect fails for a reason the transport names.
        // `ownerClose` only runs on the success path, which a connect to port 1 never reaches.
        val open = Connection.openSocket("127.0.0.1", 1, _ => SqlConnectionClosedException("probe"), (_: Unit) => ())(_ => ())
        Abort.run[SqlException](open).map {
            case Result.Failure(e: SqlConnectionConnectFailedException) =>
                assert(e.cause.isInstanceOf[kyo.net.NetException], s"expected the transport's NetException as the cause, got ${e.cause}")
                assert(e.cause.getMessage != "connect refused", "the cause must be the transport's own failure, not a placeholder")
            case other => fail(s"Expected a connect failure, got $other")
        }
    }

end ConnectionTest
