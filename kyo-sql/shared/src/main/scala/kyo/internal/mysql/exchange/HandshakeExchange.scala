package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.*
import kyo.internal.mysql.auth.CachingSha2
import kyo.internal.mysql.auth.ClearPassword
import kyo.internal.mysql.auth.NativePassword
import kyo.internal.mysql.auth.Sha256Password
import kyo.internal.mysql.unmarshaller.HandshakeV10Unmarshaller
import kyo.net.NetTlsConfig

/** Result of a successful MySQL handshake.
  *
  * @param connectionId
  *   the server-assigned connection / thread ID
  * @param serverVersion
  *   server version string (e.g. "8.0.34")
  * @param capabilities
  *   negotiated client capability flags
  * @param charset
  *   negotiated charset number
  * @param statusFlags
  *   last-seen server status flags
  * @param channel
  *   the [[MysqlChannel]] to use for subsequent communication (may be TLS-wrapped if TLS was negotiated)
  */
final case class HandshakeResult(
    connectionId: Long,
    serverVersion: String,
    capabilities: Long,
    charset: Int,
    statusFlags: Int,
    channel: MysqlChannel
)

/** Executes the MySQL connection-phase handshake on a newly-connected [[MysqlChannel]].
  *
  * Protocol sequence (plaintext or TLS, mysql_native_password / caching_sha2_password / sha256_password / mysql_clear_password):
  *   1. Server sends [[HandshakeV10]].
  *   2. If `tls` is [[Maybe.Present]] AND server advertises CLIENT_SSL:
  *      a. Send short [[SslRequest]] (CLIENT_SSL + maxPacket + charset, NO auth fields) via [[InitTlsExchange]].
  *      b. Perform TLS handshake on the same socket.
  *      c. Continue on the returned TLS-wrapped channel.
  *   3. Client selects capabilities, computes auth response, sends [[HandshakeResponse41]] (over TLS if step 2 ran).
  *   4. Server responds with [[OkPacket]] (success), [[ErrPacket]] (auth failure), or [[AuthSwitchRequest]] (plugin switch).
  *   5. If [[AuthSwitchRequest]]: `mysql_native_password`, `caching_sha2_password`, `sha256_password`, and `mysql_clear_password` switches
  *      are handled.
  *   6. For `caching_sha2_password`: [[AuthMoreData]] with 0x03 → fast-path success; 0x04 → full-auth.
  *   7. Full-auth: over TLS sends cleartext password; over plaintext requests RSA key then OAEP-encrypts.
  *   8. `mysql_clear_password`: sends NUL-terminated cleartext password; refuses to authenticate without TLS.
  *   9. Returns [[HandshakeResult]] on success.
  *
  * Sequence IDs during the handshake are sequential (server=0, client=1, server=2, ...) — do NOT call `resetSeq` between steps.
  */
private[mysql] object HandshakeExchange:

    // --- Constants ---

    private val MaxPacketSize = 16777215L // 2^24 - 1
    private val Charset       = 255       // utf8mb4

    /** Auth-more-data marker sent by client to request the server's RSA public key. */
    private val RequestPublicKey: Span[Byte] = Span.from(Array(0x02.toByte))

    /** Server fast-path success marker (cache hit). */
    private val FastPathOk: Byte = 0x03.toByte

    /** Server full-auth required marker (cache miss). */
    private val FullAuthRequired: Byte = 0x04.toByte

    // --- Handshake ---

    /** Runs the complete MySQL connection-phase handshake.
      *
      * @param channel
      *   the plaintext [[MysqlChannel]] (freshly connected, no bytes exchanged yet)
      * @param user
      *   database username
      * @param password
      *   optional password
      * @param db
      *   optional initial database
      * @param host
      *   server hostname (for error messages and TLS SNI)
      * @param port
      *   server port (for error messages)
      * @param tls
      *   optional TLS configuration; if [[Maybe.Present]] and server advertises CLIENT_SSL, performs mid-handshake TLS upgrade
      * @param preferFallback
      *   if `true`, enables `sslmode=prefer` behaviour: attempt TLS upgrade if server advertises CLIENT_SSL, but fall back to plaintext if
      *   it does not (instead of failing). When `false` (the default), TLS is required if `tls` is [[Maybe.Present]] and the server does
      *   not advertise CLIENT_SSL — [[InitTlsExchange]] will fail with [[SqlException.Connection]].
      */
    def run(
        channel: MysqlChannel,
        user: String,
        password: Maybe[String],
        db: Maybe[String],
        host: String,
        port: Int,
        tls: Maybe[NetTlsConfig],
        preferFallback: Boolean
    )(using Frame): HandshakeResult < (Async & Abort[SqlException]) =
        // Step 1: Read HandshakeV10 from server.
        channel.receiveHandshake.flatMap { handshake =>
            val serverCaps   = handshake.capabilityFlags
            val serverHasSsl = (serverCaps & Capabilities.CLIENT_SSL) != 0L
            // Pre-compute the final client capabilities now so that the SslRequest and HandshakeResponse41
            // advertise IDENTICAL capability sets. MySQL 8.0 reads capabilities from the SslRequest to
            // configure its auth state machine BEFORE the TLS handshake; if SslRequest omits flags like
            // CLIENT_PLUGIN_AUTH that HandshakeResponse41 later includes, MySQL raises error 1251.
            //
            // For sslmode=prefer with preferFallback=true: attempt TLS only when server advertises CLIENT_SSL;
            // if not, proceed plaintext without calling InitTlsExchange.
            val tlsActive  = tls.isDefined && serverHasSsl
            val clientCaps = buildClientCaps(serverCaps, db, tlsActive)
            // Step 2: optionally upgrade to TLS mid-handshake.
            val tlsUpgradeEffect: MysqlChannel < (Async & Abort[SqlException]) = tls match
                case Maybe.Present(tlsConfig) if serverHasSsl =>
                    InitTlsExchange.run(channel, serverCaps, clientCaps, host, port, tlsConfig)
                case Maybe.Present(_) if preferFallback =>
                    // sslmode=prefer: server doesn't advertise CLIENT_SSL — fall back to plaintext.
                    channel
                case Maybe.Present(_) =>
                    // sslmode=require/verify-*: TLS requested but server doesn't advertise CLIENT_SSL — fail.
                    InitTlsExchange.run(channel, serverCaps, clientCaps, host, port, NetTlsConfig())
                case Maybe.Absent =>
                    channel
            tlsUpgradeEffect.flatMap { activeChannel =>
                // Compute auth response based on plugin.
                val scramble   = handshake.authPluginData
                val pluginName = handshake.authPluginName
                computeAuthResponse(pluginName, password, scramble, tlsActive).flatMap { authResponse =>

                    // Step 3: Send full HandshakeResponse41 (over TLS if upgraded).
                    val response = HandshakeResponse41(
                        capabilities = clientCaps,
                        maxPacket = MaxPacketSize,
                        charset = Charset,
                        username = user,
                        authResponse = authResponse,
                        database = db,
                        authPlugin = Maybe.Present(pluginName)
                    )
                    activeChannel.send(response)(using activeChannel.marshallers.handshakeResponse41).flatMap { _ =>
                        // Step 4: Read response.
                        activeChannel.receive(inAuthContext = true).flatMap {
                            case ok: OkPacket =>
                                HandshakeResult(
                                    handshake.threadId,
                                    handshake.serverVersion,
                                    clientCaps,
                                    handshake.charset,
                                    handshake.statusFlags,
                                    activeChannel
                                )

                            case err: ErrPacket =>
                                Abort.fail(mkServerError(err))

                            case AuthMoreData(data) if pluginName == "caching_sha2_password" =>
                                // caching_sha2_password multi-round auth.
                                handleCachingSha2MoreData(activeChannel, data, password, scramble, handshake, clientCaps, tlsActive)

                            case AuthMoreData(data) if pluginName == "sha256_password" =>
                                // sha256_password: server sent AuthMoreData (PEM public key) — XOR with scramble, encrypt, send.
                                handleSha256MoreData(activeChannel, data, password, scramble, handshake, clientCaps)

                            case AuthSwitchRequest(newPlugin, newScramble) =>
                                // Step 5: Auth plugin switch.
                                handleAuthSwitch(activeChannel, newPlugin, newScramble, password, handshake, clientCaps, tlsActive)

                            case other =>
                                Abort.fail(SqlException.Connection(s"Unexpected message after HandshakeResponse41: $other", summon[Frame]))
                        }
                    }
                } // end computeAuthResponse.flatMap
            }
        }
    end run

    // --- caching_sha2_password ---

    /** Handles the caching_sha2_password AuthMoreData round received after HandshakeResponse41.
      *
      * @param data
      *   the raw AuthMoreData payload (first byte is the status byte)
      */
    private def handleCachingSha2MoreData(
        channel: MysqlChannel,
        data: Span[Byte],
        password: Maybe[String],
        scramble: Span[Byte],
        handshake: HandshakeV10,
        clientCaps: Long,
        tlsActive: Boolean
    )(using Frame): HandshakeResult < (Async & Abort[SqlException]) =
        if data.size < 1 then
            Abort.fail(SqlException.Connection("Empty AuthMoreData payload during caching_sha2_password", summon[Frame]))
        else
            val statusByte = data(0)
            if statusByte == FastPathOk then
                // Fast-path success: server cache hit, read the final OK.
                channel.receive(inAuthContext = true).flatMap {
                    case ok: OkPacket =>
                        HandshakeResult(
                            handshake.threadId,
                            handshake.serverVersion,
                            clientCaps,
                            handshake.charset,
                            handshake.statusFlags,
                            channel
                        )
                    case err: ErrPacket =>
                        Abort.fail(mkServerError(err))
                    case other =>
                        Abort.fail(SqlException.Connection(s"Expected OK after caching_sha2 fast-path, got: $other", summon[Frame]))
                }
            else if statusByte == FullAuthRequired then
                // Full-auth required: cache miss.
                performFullAuth(channel, password, scramble, handshake, clientCaps, tlsActive)
            else
                Abort.fail(SqlException.Connection(
                    s"Unknown caching_sha2_password AuthMoreData status: 0x${(statusByte & 0xff).toHexString}",
                    summon[Frame]
                ))
            end if
        end if
    end handleCachingSha2MoreData

    /** Performs full-auth (cache miss) for caching_sha2_password.
      *
      * Over TLS: sends cleartext password (NUL-terminated) directly. Over plaintext: requests RSA public key (0x02), receives it in
      * AuthMoreData, encrypts password XOR'd with scramble using RSA-OAEP, sends ciphertext.
      */
    private def performFullAuth(
        channel: MysqlChannel,
        password: Maybe[String],
        scramble: Span[Byte],
        handshake: HandshakeV10,
        clientCaps: Long,
        tlsActive: Boolean
    )(using Frame): HandshakeResult < (Async & Abort[SqlException]) =
        if tlsActive then
            // Over TLS: send cleartext password NUL-terminated.
            val authPayload: Span[Byte] = password match
                case Maybe.Present(pw) =>
                    val pwBytes = pw.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    val buf     = new Array[Byte](pwBytes.length + 1)
                    java.lang.System.arraycopy(pwBytes, 0, buf, 0, pwBytes.length)
                    buf(pwBytes.length) = 0.toByte
                    Span.from(buf)
                case Maybe.Absent =>
                    // Empty password: single NUL byte.
                    Span.from(Array(0.toByte))
            channel.send(AuthMoreDataResponse(authPayload))(using channel.marshallers.authMoreData).flatMap { _ =>
                readFinalOk(channel, handshake, clientCaps)
            }
        else
            // Non-TLS: request RSA public key.
            channel.send(AuthMoreDataResponse(RequestPublicKey))(using channel.marshallers.authMoreData).flatMap { _ =>
                // Server sends AuthMoreData containing the PEM-encoded RSA public key.
                channel.receive(inAuthContext = true).flatMap {
                    case AuthMoreData(pemData) =>
                        val encryptedEffect: Span[Byte] < (Sync & Abort[SqlException]) = password match
                            case Maybe.Present(pw) => CachingSha2.computeFullAuthResponse(pw, scramble, pemData)
                            case Maybe.Absent      => Span.from(Array(0.toByte))
                        encryptedEffect.flatMap { encrypted =>
                            channel.send(AuthMoreDataResponse(encrypted))(using channel.marshallers.authMoreData).flatMap { _ =>
                                readFinalOk(channel, handshake, clientCaps)
                            }
                        }
                    case err: ErrPacket =>
                        Abort.fail(mkServerError(err))
                    case other =>
                        Abort.fail(SqlException.Connection(
                            s"Expected AuthMoreData (RSA PEM) during caching_sha2 full-auth, got: $other",
                            summon[Frame]
                        ))
                }
            }
        end if
    end performFullAuth

    /** Reads the final OkPacket after completing full-auth. */
    private def readFinalOk(
        channel: MysqlChannel,
        handshake: HandshakeV10,
        clientCaps: Long
    )(using Frame): HandshakeResult < (Async & Abort[SqlException]) =
        channel.receive(inAuthContext = true).flatMap {
            case _: OkPacket =>
                HandshakeResult(
                    handshake.threadId,
                    handshake.serverVersion,
                    clientCaps,
                    handshake.charset,
                    handshake.statusFlags,
                    channel
                )
            case err: ErrPacket =>
                Abort.fail(mkServerError(err))
            case other =>
                Abort.fail(SqlException.Connection(s"Expected OK after caching_sha2 full-auth, got: $other", summon[Frame]))
        }
    end readFinalOk

    // --- sha256_password ---

    /** Handles sha256_password authentication.
      *
      * sha256_password has no fast-path cache: every connection either:
      *   - sends cleartext NUL-terminated password over TLS, or
      *   - receives the server's RSA public key in an AuthMoreData packet, XORs `(password + NUL)` with the scramble (cycling), RSA-OAEP
      *     encrypts the result, and sends the ciphertext.
      *
      * This method is called when the server sends an [[AuthMoreData]] packet after the initial HandshakeResponse41. The `data` payload is
      * the PEM-encoded RSA public key.
      */
    private def handleSha256MoreData(
        channel: MysqlChannel,
        data: Span[Byte],
        password: Maybe[String],
        scramble: Span[Byte],
        handshake: HandshakeV10,
        clientCaps: Long
    )(using Frame): HandshakeResult < (Async & Abort[SqlException]) =
        // Server sent the PEM public key in AuthMoreData — XOR with scramble, encrypt, and reply.
        val encryptedEffect: Span[Byte] < (Sync & Abort[SqlException]) = password match
            case Maybe.Present(pw) => Sha256Password.computeEncryptedResponse(pw, scramble, data)
            case Maybe.Absent      => Span.from(Array(0.toByte))
        encryptedEffect.flatMap { encrypted =>
            channel.send(AuthMoreDataResponse(encrypted))(using channel.marshallers.authMoreData).flatMap { _ =>
                readFinalOk(channel, handshake, clientCaps)
            }
        }
    end handleSha256MoreData

    /** Performs sha256_password full authentication for a switched plugin.
      *
      * Over TLS: sends cleartext NUL-terminated password. Over plaintext: sends `\x01` (request public key), receives PEM key in
      * AuthMoreData, XORs password with the scramble, RSA-OAEP encrypts, sends ciphertext.
      */
    private def performSha256Auth(
        channel: MysqlChannel,
        password: Maybe[String],
        scramble: Span[Byte],
        handshake: HandshakeV10,
        clientCaps: Long,
        tlsActive: Boolean
    )(using Frame): HandshakeResult < (Async & Abort[SqlException]) =
        if tlsActive then
            // Over TLS: send cleartext password NUL-terminated (same shape as caching_sha2 TLS path).
            val authPayload: Span[Byte] = password match
                case Maybe.Present(pw) =>
                    val pwBytes = pw.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    val buf     = new Array[Byte](pwBytes.length + 1)
                    java.lang.System.arraycopy(pwBytes, 0, buf, 0, pwBytes.length)
                    buf(pwBytes.length) = 0.toByte
                    Span.from(buf)
                case Maybe.Absent =>
                    Span.from(Array(0.toByte))
            channel.send(AuthSwitchResponse(authPayload))(using channel.marshallers.authSwitchResponse).flatMap { _ =>
                readFinalOk(channel, handshake, clientCaps)
            }
        else
            // Non-TLS: send \x01 to request RSA public key.
            channel.send(AuthSwitchResponse(Sha256Password.RequestPublicKey))(using channel.marshallers.authSwitchResponse).flatMap { _ =>
                channel.receive(inAuthContext = true).flatMap {
                    case AuthMoreData(pemData) =>
                        val encryptedEffect: Span[Byte] < (Sync & Abort[SqlException]) = password match
                            case Maybe.Present(pw) => Sha256Password.computeEncryptedResponse(pw, scramble, pemData)
                            case Maybe.Absent      => Span.from(Array(0.toByte))
                        encryptedEffect.flatMap { encrypted =>
                            channel.send(AuthMoreDataResponse(encrypted))(using channel.marshallers.authMoreData).flatMap { _ =>
                                readFinalOk(channel, handshake, clientCaps)
                            }
                        }
                    case err: ErrPacket =>
                        Abort.fail(mkServerError(err))
                    case other =>
                        Abort.fail(SqlException.Connection(
                            s"Expected AuthMoreData (RSA PEM) during sha256_password auth, got: $other",
                            summon[Frame]
                        ))
                }
            }
        end if
    end performSha256Auth

    // --- Auth switch ---

    private def handleAuthSwitch(
        channel: MysqlChannel,
        plugin: String,
        newScramble: Span[Byte],
        password: Maybe[String],
        handshake: HandshakeV10,
        clientCaps: Long,
        tlsActive: Boolean
    )(using Frame): HandshakeResult < (Async & Abort[SqlException]) =
        plugin match
            case "mysql_native_password" =>
                val authResp = computeNativePassword(password, newScramble)
                channel.send(AuthSwitchResponse(authResp))(using channel.marshallers.authSwitchResponse).flatMap { _ =>
                    channel.receive(inAuthContext = true).flatMap {
                        case ok: OkPacket =>
                            HandshakeResult(
                                handshake.threadId,
                                handshake.serverVersion,
                                clientCaps,
                                handshake.charset,
                                handshake.statusFlags,
                                channel
                            )
                        case err: ErrPacket =>
                            Abort.fail(mkServerError(err))
                        case other =>
                            Abort.fail(SqlException.Connection(s"Unexpected message after AuthSwitchResponse: $other", summon[Frame]))
                    }
                }
            case "caching_sha2_password" =>
                // Re-run fast-path with the new scramble from the AuthSwitchRequest.
                val fastResp = password match
                    case Maybe.Present(pw) => CachingSha2.computeFastResponse(pw, newScramble)
                    case Maybe.Absent      => Span.empty[Byte]
                channel.send(AuthSwitchResponse(fastResp))(using channel.marshallers.authSwitchResponse).flatMap { _ =>
                    channel.receive(inAuthContext = true).flatMap {
                        case ok: OkPacket =>
                            HandshakeResult(
                                handshake.threadId,
                                handshake.serverVersion,
                                clientCaps,
                                handshake.charset,
                                handshake.statusFlags,
                                channel
                            )
                        case err: ErrPacket =>
                            Abort.fail(mkServerError(err))
                        case AuthMoreData(data) =>
                            // Fast-path result after AuthSwitchRequest re-auth.
                            handleCachingSha2MoreData(channel, data, password, newScramble, handshake, clientCaps, tlsActive)
                        case other =>
                            Abort.fail(SqlException.Connection(
                                s"Unexpected message after caching_sha2 AuthSwitchResponse: $other",
                                summon[Frame]
                            ))
                    }
                }
            case "sha256_password" =>
                // sha256_password: proceed to full auth using the new scramble from AuthSwitchRequest.
                performSha256Auth(channel, password, newScramble, handshake, clientCaps, tlsActive)

            case "mysql_clear_password" =>
                // mysql_clear_password MUST NOT be used without TLS — password would be sent in plaintext.
                if !tlsActive then
                    Abort.fail(SqlException.Connection(
                        "mysql_clear_password requires TLS to avoid sending the password in plaintext",
                        summon[Frame]
                    ))
                else
                    channel.send(AuthSwitchResponse(ClearPassword.encode(password)))(using channel.marshallers.authSwitchResponse).flatMap {
                        _ =>
                            readFinalOk(channel, handshake, clientCaps)
                    }

            case other =>
                Abort.fail(SqlException.Connection(s"Auth switch to unsupported plugin: $other", summon[Frame]))

    // --- Utilities ---

    private def computeAuthResponse(
        plugin: String,
        password: Maybe[String],
        scramble: Span[Byte],
        tlsActive: Boolean
    )(using Frame): Span[Byte] < Abort[SqlException] =
        plugin match
            case "mysql_native_password" =>
                computeNativePassword(password, scramble)
            case "caching_sha2_password" =>
                // Compute fast-path response; server will send AuthMoreData with 0x03 or 0x04.
                password match
                    case Maybe.Present(pw) => CachingSha2.computeFastResponse(pw, scramble)
                    case Maybe.Absent      => Span.empty[Byte]
            case "sha256_password" if tlsActive =>
                // Over TLS: send NUL-terminated cleartext password as the initial auth data (MySQL 5.7/8.0 TLS requirement).
                password match
                    case Maybe.Present(pw) =>
                        val pwBytes = pw.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        val buf     = new Array[Byte](pwBytes.length + 1)
                        java.lang.System.arraycopy(pwBytes, 0, buf, 0, pwBytes.length)
                        buf(pwBytes.length) = 0.toByte
                        Span.from(buf)
                    case Maybe.Absent =>
                        Span.from(Array(0.toByte))
            case "sha256_password" =>
                // Non-TLS: send empty initial auth data; server will send AuthMoreData with the RSA public key.
                // Client then RSA-OAEP encrypts the NUL-terminated password and sends the ciphertext.
                Span.empty[Byte]
            case "mysql_clear_password" =>
                // mysql_clear_password MUST NOT be used without TLS — password would be sent in plaintext.
                if !tlsActive then
                    Abort.fail(SqlException.Connection(
                        "mysql_clear_password requires TLS to avoid sending the password in plaintext",
                        summon[Frame]
                    ))
                else
                    ClearPassword.encode(password)
            case _ =>
                Span.empty[Byte]

    private def computeNativePassword(password: Maybe[String], scramble: Span[Byte]): Span[Byte] =
        password match
            case Maybe.Present(pw) => NativePassword.computeResponse(pw, scramble)
            case Maybe.Absent      => Span.empty[Byte]

    private def buildClientCaps(serverCaps: Long, db: Maybe[String], tlsActive: Boolean): Long =
        import Capabilities.*
        var caps = Default
        // Add CLIENT_CONNECT_WITH_DB only if a database is requested
        db match
            case Maybe.Present(_) => caps = caps | CLIENT_CONNECT_WITH_DB
            case Maybe.Absent     => caps = caps & ~CLIENT_CONNECT_WITH_DB
        // When TLS is active, include CLIENT_SSL so the full HandshakeResponse41 carries it.
        if tlsActive then caps = caps | CLIENT_SSL
        // CLIENT_LOCAL_FILES is already in Capabilities.Default; no separate OR needed here.
        // Intersect with server's advertised capabilities
        caps & serverCaps
    end buildClientCaps

    private def mkServerError(err: ErrPacket)(using frame: Frame): SqlException.Connection =
        SqlException.Connection(
            s"Authentication failed: [${err.sqlState}] ${err.errorMessage} (code=${err.errorCode})",
            frame
        )

end HandshakeExchange
