package kyo.internal.mysql.exchange

import kyo.*
import kyo.internal.mysql.ErrPacket
import kyo.internal.mysql.auth.CachingSha2Shared
import kyo.internal.mysql.auth.ClearPassword
import kyo.internal.mysql.auth.NativePasswordShared
import kyo.internal.mysql.auth.Sha256Password

/** Unit tests for the auth response [[HandshakeExchange]] puts in the initial `HandshakeResponse41`.
  *
  * These bytes are the hardest part of the handshake to cover from a connection that succeeds. The server names one plugin in its
  * `HandshakeV10` and switches to another when the account uses a different one, so a wrong initial response for plugin X is simply never
  * reached unless X is the server's own default. `Sha256PasswordIntegrationTest` reaches `sha256_password` through an `AuthSwitchRequest`
  * on a server whose default is `caching_sha2_password`, which exercises the switch path and not this one; the initial-response bytes for
  * each plugin are pinned here directly.
  */
class HandshakeExchangeTest extends kyo.Test:

    private val scramble = Span.from(Array.tabulate(20)(i => (i + 1).toByte))
    private val password = "secret"

    private def initialResponse(plugin: String, password: Maybe[String], tlsActive: Boolean)(using
        Frame
    ): Span[Byte] < Abort[SqlException] =
        HandshakeExchange.computeAuthResponse(plugin, password, scramble, tlsActive)

    // ── sha256_password ───────────────────────────────────────────────────────

    "sha256_password over plaintext asks for the server's RSA public key with the single byte 0x01" in {
        // The server's plugin sends the PEM public key only for exactly this response. An empty payload is read as an
        // empty password, so the RSA round never begins and every real password is rejected.
        Abort.run[SqlException](initialResponse("sha256_password", Present(password), tlsActive = false)).map {
            case Result.Success(bytes) =>
                assert(bytes.toArray.sameElements(Array[Byte](0x01)), s"got ${bytes.toArray.mkString("[", ",", "]")}")
                assert(bytes.toArray.sameElements(Sha256Password.RequestPublicKey.toArray))
            case other => fail(s"expected the request-public-key byte, got: $other")
        }
    }

    "sha256_password over plaintext with no password sends the single NUL, not a key request" in {
        // Nothing to protect means no key to fetch. 0x01 here would start an RSA round the server has no ciphertext for.
        Abort.run[SqlException](initialResponse("sha256_password", Absent, tlsActive = false)).map { absent =>
            Abort.run[SqlException](initialResponse("sha256_password", Present(""), tlsActive = false)).map { empty =>
                assert(absent.map(_.toArray.toSeq) == Result.Success(Seq[Byte](0x00)), s"absent password: $absent")
                assert(empty.map(_.toArray.toSeq) == Result.Success(Seq[Byte](0x00)), s"empty password: $empty")
            }
        }
    }

    "sha256_password over TLS sends the NUL-terminated cleartext password" in {
        Abort.run[SqlException](initialResponse("sha256_password", Present(password), tlsActive = true)).map {
            case Result.Success(bytes) =>
                assert(bytes.toArray.sameElements("secret".getBytes("UTF-8") :+ 0.toByte), s"got ${bytes.toArray.mkString("[", ",", "]")}")
            case other => fail(s"expected the cleartext password, got: $other")
        }
    }

    // ── mysql_clear_password ──────────────────────────────────────────────────

    "mysql_clear_password over TLS sends the NUL-terminated cleartext password" in {
        // The plugin has no hashing and no encryption, so the payload IS the secret. Pinning the bytes is the
        // only way to see that, since a server that accepts the login would accept several wrong payloads too.
        Abort.run[SqlException](initialResponse("mysql_clear_password", Present(password), tlsActive = true)).map {
            case Result.Success(bytes) =>
                assert(
                    bytes.toArray.sameElements("secret".getBytes("UTF-8") :+ 0.toByte),
                    s"got ${bytes.toArray.mkString("[", ",", "]")}"
                )
                assert(bytes.toArray.sameElements(ClearPassword.encode(Present(password)).toArray))
            case other => fail(s"expected the NUL-terminated cleartext password, got: $other")
        }
    }

    "mysql_clear_password over plaintext refuses rather than sending the password" in {
        // The refusal is the security property: over an unencrypted channel this plugin would put the password
        // on the wire in the clear. The assertion is on the exception type, not merely on failure, because a
        // failure for any other reason would leave the guard unexercised.
        Abort.run[SqlException](initialResponse("mysql_clear_password", Present(password), tlsActive = false)).map {
            case Result.Failure(_: SqlConnectionClearPasswordRequiresTlsException) => succeed
            case other =>
                fail(s"expected SqlConnectionClearPasswordRequiresTlsException over plaintext, got: $other")
        }
    }

    "mysql_clear_password refuses over plaintext even with no password to protect" in {
        // Absent and empty are one credential on MySQL (see the passwordless leaf below), and the guard must not
        // read "nothing to leak" as "safe to send": the plugin name itself is what makes the channel wrong.
        Abort.run[SqlException](initialResponse("mysql_clear_password", Absent, tlsActive = false)).map {
            case Result.Failure(_: SqlConnectionClearPasswordRequiresTlsException) => succeed
            case other => fail(s"expected the refusal for an absent credential too, got: $other")
        }
    }

    "the clear-password guard is one predicate, so the switch path cannot drift from the initial response" in {
        // HandshakeExchange has two places that can send this plugin's payload: the initial response above and
        // the AuthSwitchRequest arm, which needs a scripted channel and so cannot be reached from here. Asserting
        // the predicate directly is what keeps the unreachable arm covered: both arms consult this one function,
        // so a change here is visible to both, and an edit that re-inlines either has to delete a tested function
        // to do it.
        Abort.run[SqlException](HandshakeExchange.requireTlsForClearPassword(tlsActive = false)).map { refused =>
            Abort.run[SqlException](HandshakeExchange.requireTlsForClearPassword(tlsActive = true)).map { allowed =>
                refused match
                    case Result.Failure(_: SqlConnectionClearPasswordRequiresTlsException) => ()
                    case other => fail(s"expected the guard to refuse an unencrypted channel, got: $other")
                assert(allowed == Result.Success(()), s"expected the guard to permit an encrypted channel, got: $allowed")
            }
        }
    }

    // ── The two request-public-key bytes are per plugin ───────────────────────

    "caching_sha2_password sends its fast-path response, so the two plugins' key requests cannot be confused" in {
        // caching_sha2_password requests the key with 0x02 and only after the server answers 0x04, never in the initial
        // response. sha256_password requests it with 0x01 in the initial response. Sending either plugin's byte on the
        // other's path authenticates nothing.
        Abort.run[SqlException](initialResponse("caching_sha2_password", Present(password), tlsActive = false)).map {
            case Result.Success(bytes) =>
                assert(bytes.size == 32, s"the fast-path response is a 32-byte digest, got ${bytes.size}")
                assert(bytes.toArray.sameElements(CachingSha2Shared.computeFastResponse(password, scramble).toArray))
                // The property the title claims, asserted without going through the same implementation: neither
                // plugin's key-request byte can appear here.
                assert(!bytes.toArray.sameElements(Sha256Password.RequestPublicKey.toArray))
                assert(!bytes.toArray.sameElements(Array[Byte](0x02)))
            case other => fail(s"expected the caching_sha2 fast-path response, got: $other")
        }
    }

    "mysql_native_password sends the scrambled SHA-1 response on both transports" in {
        val expected = NativePasswordShared.computeResponse(password, scramble).toArray
        Abort.run[SqlException](initialResponse("mysql_native_password", Present(password), tlsActive = false)).map { plaintext =>
            Abort.run[SqlException](initialResponse("mysql_native_password", Present(password), tlsActive = true)).map { overTls =>
                assert(plaintext.map(_.toArray.toSeq) == Result.Success(expected.toSeq))
                assert(overTls.map(_.toArray.toSeq) == Result.Success(expected.toSeq))
            }
        }
    }

    // ── Absent and empty are one credential on MySQL, and it is a real one ────

    "a passwordless account sends the protocol's empty response rather than being refused" in {
        // MySQL defines a zero-length or single-NUL response for accounts with no password, so an absent credential is a
        // valid thing to send here, not a missing input. Every plugin's payload is pinned so "absent means passwordless"
        // stays a decision this file can see rather than an accident of defaulting.
        val nul   = Seq[Byte](0x00)
        val empty = Seq.empty[Byte]
        Abort.run[SqlException](initialResponse("mysql_native_password", Absent, tlsActive = false)).map { native =>
            Abort.run[SqlException](initialResponse("caching_sha2_password", Absent, tlsActive = false)).map { caching =>
                Abort.run[SqlException](initialResponse("sha256_password", Absent, tlsActive = false)).map { sha256 =>
                    Abort.run[SqlException](initialResponse("sha256_password", Absent, tlsActive = true)).map { sha256Tls =>
                        assert(native.map(_.toArray.toSeq) == Result.Success(empty), s"mysql_native_password: $native")
                        assert(caching.map(_.toArray.toSeq) == Result.Success(empty), s"caching_sha2_password: $caching")
                        assert(sha256.map(_.toArray.toSeq) == Result.Success(nul), s"sha256_password plaintext: $sha256")
                        assert(sha256Tls.map(_.toArray.toSeq) == Result.Success(nul), s"sha256_password over TLS: $sha256Tls")
                    }
                }
            }
        }
    }

    "an unknown plugin sends an empty response and lets the server drive the switch" in {
        Abort.run[SqlException](initialResponse("authentication_ldap_sasl", Present(password), tlsActive = false)).map {
            case Result.Success(bytes) => assert(bytes.size == 0, s"got ${bytes.size} byte(s)")
            case other                 => fail(s"expected an empty response, got: $other")
        }
    }

    // Connection-phase ErrPacket classification. Only a SQLSTATE class-28 ErrPacket is an authentication failure
    // carrying the SqlAuthenticationFailure marker, the same rule StartupExchange applies on PostgreSQL. A
    // credentials-retry caller must not read "too many connections", "unknown database" or "host not privileged"
    // as a wrong password, so the leaves assert the classification, not merely that the connect failed.

    "an access-denied ErrPacket is an authentication failure and carries the marker" in {
        // ER_ACCESS_DENIED_ERROR, the one condition a credentials retry is right to react to.
        val e = HandshakeExchange.mkAuthError(ErrPacket(1045, "28000", "Access denied for user 'u'@'h' (using password: YES)"))
        e match
            case a: SqlConnectionAuthenticationFailedException =>
                assert(a.sqlState == "28000", s"expected the server's SQLSTATE carried through, got ${a.sqlState}")
                assert(a.errorCode == 1045, s"expected the server's error code carried through, got ${a.errorCode}")
            case other => fail(s"expected SqlConnectionAuthenticationFailedException, got $other")
        end match
        assert(e.isInstanceOf[SqlAuthenticationFailure], "an access-denied error must carry the marker callers recover on")
    }

    "a passwordless access-denied ErrPacket is an authentication failure too" in {
        // ER_ACCESS_DENIED_NO_PASSWORD_ERROR reports the same SQLSTATE class, so one rule covers both.
        val e = HandshakeExchange.mkAuthError(ErrPacket(1698, "28000", "Access denied for user 'u'@'h'"))
        assert(e.isInstanceOf[SqlAuthenticationFailure], s"expected the marker on 1698, got $e")
    }

    "a too-many-connections ErrPacket is a server error, not a wrong password" in {
        // ER_CON_COUNT_ERROR reports 08004. Classified as authentication, a credentials-retry loop spins on it
        // forever, because no credential change can make the server accept another connection.
        val e = HandshakeExchange.mkAuthError(ErrPacket(1040, "08004", "Too many connections"))
        assert(!e.isInstanceOf[SqlAuthenticationFailure], s"1040 is not an authentication failure, got $e")
        e match
            case s: SqlServerException =>
                assert(s.sqlState == "08004", s"expected 08004 preserved, got ${s.sqlState}")
                // The 08 class is what Connection.isProtocolFatal reads to decide the connection is gone.
                assert(kyo.db.Connection.isProtocolFatal(s), "an 08-class server error must stay protocol-fatal")
            case other => fail(s"expected SqlServerException, got $other")
        end match
    }

    "an unknown-database ErrPacket is a server error, not a wrong password" in {
        // ER_BAD_DB_ERROR reports 42000, a statement-level class that leaves the session usable.
        val e = HandshakeExchange.mkAuthError(ErrPacket(1049, "42000", "Unknown database 'nope'"))
        assert(!e.isInstanceOf[SqlAuthenticationFailure], s"1049 is not an authentication failure, got $e")
        e match
            case s: SqlServerException => assert(s.sqlState == "42000", s"expected 42000 preserved, got ${s.sqlState}")
            case other                 => fail(s"expected SqlServerException, got $other")
    }

    "a host-not-privileged ErrPacket is a server error, not a wrong password" in {
        // ER_HOST_NOT_PRIVILEGED reports HY000: the host is refused before any credential is considered.
        val e = HandshakeExchange.mkAuthError(ErrPacket(1130, "HY000", "Host 'h' is not allowed to connect to this MySQL server"))
        assert(!e.isInstanceOf[SqlAuthenticationFailure], s"1130 is not an authentication failure, got $e")
        e match
            case s: SqlServerException => assert(s.sqlState == "HY000", s"expected HY000 preserved, got ${s.sqlState}")
            case other                 => fail(s"expected SqlServerException, got $other")
    }

end HandshakeExchangeTest
