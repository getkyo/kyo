package kyo.internal.postgres.exchange

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlConnectionPasswordRequiredException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlConnectionUnsupportedAuthMethodException
import kyo.SqlException
import kyo.internal.postgres.*
import kyo.internal.postgres.auth.ChannelBinding
import kyo.internal.postgres.auth.Md5PasswordShared
import kyo.internal.postgres.auth.PlainPassword
import kyo.internal.postgres.auth.ScramSha256Shared

/** Result of a successful startup / authentication handshake. */
final case class StartupResult(
    parameters: Map[String, String],
    processId: Int,
    secretKey: Int
)

/** Runs the PostgreSQL startup handshake on the given channel.
  *
  * Protocol sequence:
  *   1. Send [[StartupMessage]] with user / database / application_name.
  *   2. Read authentication request and dispatch:
  *      - [[AuthenticationKind.Ok]] → trust; proceed to step 3.
  *      - [[AuthenticationKind.CleartextPassword]] → send [[PasswordMessage]], read [[AuthenticationKind.Ok]].
  *      - [[AuthenticationKind.MD5Password]] → hash via MD5, send [[PasswordMessage]], read [[AuthenticationKind.Ok]].
  *      - [[AuthenticationKind.SASL]] with "SCRAM-SHA-256" or "SCRAM-SHA-256-PLUS" → full SCRAM exchange, with the mechanism and the GS2
  *        channel-binding flag chosen by [[selectMechanism]].
  *      - Other kinds fail with [[SqlConnectionUnsupportedAuthMethodException]] ("auth method not supported").
  *   3. Drain [[ParameterStatus]] and [[BackendKeyData]] messages until [[ReadyForQuery]].
  *   4. Return [[StartupResult]] with the accumulated parameters and backend key data.
  */
object StartupExchange:

    /** What the session calls itself when the caller named nothing.
      *
      * PostgreSQL reports this back through `pg_stat_activity.application_name`, so whoever is reading server-side diagnostics sees which
      * driver a connection came from rather than an empty column.
      */
    val defaultApplicationName: String = "kyo-sql"

    /** @param applicationName
      *   what the session should call itself, or [[Absent]] for [[defaultApplicationName]]. Reaches
      *   `pg_stat_activity.application_name`, which is what makes a connection identifiable in server-side diagnostics, so a value the caller
      *   asked for has to arrive here rather than being replaced by the driver's own name.
      */
    def run(
        channel: PostgresChannel,
        user: String,
        db: String,
        password: Maybe[String],
        certHashOverride: Maybe[Maybe[Span[Byte]]],
        mechanismCapture: Maybe[AtomicRef[String]],
        applicationName: Maybe[String] = Absent
    )(using Frame): StartupResult < (Async & Abort[SqlException]) =
        val params = Chunk(
            ("user", user),
            ("database", db),
            ("application_name", applicationName.getOrElse(defaultApplicationName)),
            ("client_encoding", "UTF8")
        )
        val startupMarshaller = channel.marshallers.startupMessage
        channel.send(StartupMessage(params))(using startupMarshaller).andThen {
            authenticate(channel, user, password, certHashOverride, mechanismCapture).flatMap { _ =>
                collectStartupMessages(channel, Map.empty, Absent)
            }
        }
    end run

    private def authenticate(
        channel: PostgresChannel,
        user: String,
        password: Maybe[String],
        certHashOverride: Maybe[Maybe[Span[Byte]]],
        mechanismCapture: Maybe[AtomicRef[String]]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case Authentication(AuthenticationKind.Ok) =>
                ()

            case Authentication(AuthenticationKind.CleartextPassword) =>
                requirePassword(password, "cleartext password").flatMap { pw =>
                    val pwBytes      = PlainPassword.encode(pw)
                    val pwStr        = new String(pwBytes, StandardCharsets.UTF_8)
                    val pwMarshaller = channel.marshallers.passwordMessage
                    channel.send(PasswordMessage(pwStr))(using pwMarshaller).andThen {
                        expectAuthOk(channel)
                    }
                }

            case Authentication(AuthenticationKind.MD5Password(salt)) =>
                requirePassword(password, "MD5 password").flatMap { pw =>
                    val md5 = Md5PasswordShared.encode(pw, user, salt)
                    channel.send(PasswordMessage(md5))(using channel.marshallers.passwordMessage).andThen {
                        expectAuthOk(channel)
                    }
                }

            case Authentication(AuthenticationKind.SASL(mechanisms)) =>
                // Use the SAFE `serverCertificateHash` (returns Maybe[Span[Byte]] < Sync), no AllowUnsafe.
                // certHashOverride semantics:
                //   Absent               = no override; use the real connection cert hash
                //   Present(Absent)      = override to Absent (force non-PLUS even on TLS)
                //   Present(Present(h))  = override to Present(h) (inject a synthetic hash)
                val certHashEffect: Maybe[Span[Byte]] < Sync = certHashOverride match
                    case Present(overrideValue) => overrideValue
                    case Absent                 => channel.conn.serverCertificateHash
                certHashEffect.flatMap { certHash =>
                    selectMechanism(mechanisms, certHash) match
                        case Present((mechanism, binding)) =>
                            requirePassword(password, mechanism).flatMap { pw =>
                                authenticateSCRAM(channel, user, pw, mechanism, binding, mechanismCapture)
                            }
                        case Absent =>
                            val mechs = mechanisms.mkString(", ")
                            Abort.fail(SqlConnectionUnsupportedAuthMethodException(s"SASL with mechanisms [$mechs]"))
                    end match
                }

            case ErrorResponse(fields) =>
                Abort.fail(mkAuthError(fields))

            case Authentication(other) =>
                Abort.fail(SqlConnectionUnsupportedAuthMethodException(other.toString))

            case other =>
                Abort.fail(SqlConnectionUnexpectedMessageException("authentication", "Authentication / ErrorResponse", other.toString))
        }

    /** The configured password, or a typed refusal naming the method the server asked for.
      *
      * Every PostgreSQL method that reaches here needs a credential, and none of them has a usable empty one: `ALTER USER ... PASSWORD ''`
      * leaves an unusable verifier, so an empty-password attempt cannot succeed and comes back as the server rejecting the credential. That
      * sends the caller looking for a wrong password when the truth is that none was supplied. Refusing before the first byte says the
      * actionable thing instead, and it also keeps a client proof from being computed over an empty password.
      *
      * An empty string is refused alongside an absent one, since it is the same missing input spelled differently and neither can authenticate.
      */
    private def requirePassword(password: Maybe[String], method: String)(using Frame): String < Abort[SqlException] =
        password match
            case Present(pw) if pw.nonEmpty => pw
            case _                          => Abort.fail(SqlConnectionPasswordRequiredException(method))

    /** The SASL mechanism name for SCRAM without channel binding. */
    private val scramSha256: String = "SCRAM-SHA-256"

    /** The SASL mechanism name for SCRAM with channel binding. */
    private val scramSha256Plus: String = "SCRAM-SHA-256-PLUS"

    /** Picks the mechanism to advertise and the GS2 channel-binding flag to send, or [[Absent]] when the server named nothing this client can
      * run.
      *
      * The flag is the part that carries a security property rather than a preference. RFC 5802 §6 requires a channel-binding-capable server
      * to fail an exchange whose flag is `y`, because a client that can bind sends `y` only when the advertised list held no `-PLUS`
      * mechanism, and against a server that supports binding that can only be an intermediary having edited the list. Reporting `n` in that
      * situation makes a stripped `-PLUS` indistinguishable from a server that never offered it, so the exchange completes and neither end
      * learns anything: SCRAM-SHA-256-PLUS then protects the password, which plain SCRAM already does, and detects none of the
      * man-in-the-middle it exists for. It matters most under `require` and `prefer`, which build a `trustAll` TLS context and so validate
      * neither chain nor hostname.
      *
      * `-PLUS` alone with no certificate hash yields [[Absent]] rather than a downgrade to plain SCRAM: a server that offers only the binding
      * mechanism is demanding binding, and answering with a mechanism it did not advertise would be the client stripping the requirement.
      *
      * @param certHash
      *   the RFC 5929 `tls-server-end-point` hash for this connection, [[Absent]] when there is no TLS to bind to
      */
    private[postgres] def selectMechanism(
        mechanisms: Chunk[String],
        certHash: Maybe[Span[Byte]]
    ): Maybe[(String, ChannelBinding)] =
        val plusOffered  = mechanisms.contains(scramSha256Plus)
        val plainOffered = mechanisms.contains(scramSha256)
        certHash match
            case Present(hash) if plusOffered =>
                Present((scramSha256Plus, ChannelBinding.Bound(hash)))
            case Present(_) if plainOffered =>
                Present((scramSha256, ChannelBinding.SupportedButNotOffered))
            case Absent if plainOffered =>
                Present((scramSha256, ChannelBinding.NotSupported))
            case _ =>
                Absent
        end match
    end selectMechanism

    private def authenticateSCRAM(
        channel: PostgresChannel,
        user: String,
        password: String,
        mechanism: String,
        channelBinding: ChannelBinding,
        mechanismCapture: Maybe[AtomicRef[String]]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        // Generate client nonce using the ambient kyo.SecureRandom, 24 random bytes, base64-encoded.
        SecureRandom.nextBytes(24).flatMap { nonceBytes =>
            val clientNonce = ScramSha256Shared.encodeNonce(nonceBytes)
            val scram       = ScramSha256Shared(user, clientNonce, channelBinding)
            val cfmBytes    = scram.clientFirstMessage.getBytes(StandardCharsets.UTF_8)

            // Record the selected mechanism name, if a capture ref was provided.
            val captureEffect: Unit < Sync = mechanismCapture match
                case Present(ref) => ref.set(mechanism)
                case Absent       => ()

            // Step 1: Send SASLInitialResponse with the chosen mechanism and client-first-message.
            captureEffect.andThen {
                channel.send(SASLInitialResponse(mechanism, Span.from(cfmBytes)))(using
                    channel.marshallers.saslInitialResponse
                ).andThen {
                    // Step 2: Receive AuthenticationSASLContinue (server-first-message).
                    channel.receive.flatMap {
                        case Authentication(AuthenticationKind.SASLContinue(data)) =>
                            val serverFirst = new String(data.toArray, StandardCharsets.UTF_8)
                            scram.clientFinalMessage(serverFirst, password) match
                                case Result.Failure(err) => Abort.fail(err)
                                case Result.Success((clientFinal, expectedServerSig)) =>
                                    val cfBytes = clientFinal.getBytes(StandardCharsets.UTF_8)
                                    // Step 3: Send SASLResponse (client-final-message).
                                    channel.send(SASLResponse(Span.from(cfBytes)))(using
                                        channel.marshallers.saslResponse
                                    ).andThen {
                                        // Step 4: Receive AuthenticationSASLFinal (server-final-message).
                                        channel.receive.flatMap {
                                            case Authentication(AuthenticationKind.SASLFinal(data)) =>
                                                val serverFinal = new String(data.toArray, StandardCharsets.UTF_8)
                                                scram.verifyServerSignature(serverFinal, expectedServerSig) match
                                                    case Result.Failure(err) => Abort.fail(err)
                                                    case Result.Success(_)   =>
                                                        // Step 5: Expect AuthenticationOk.
                                                        expectAuthOk(channel)
                                                end match
                                            case ErrorResponse(fields) =>
                                                Abort.fail(mkAuthError(fields))
                                            case other =>
                                                Abort.fail(SqlConnectionUnexpectedMessageException(
                                                    "SCRAM final",
                                                    "AuthenticationSASLFinal / ErrorResponse",
                                                    other.toString
                                                ))
                                        }
                                    }
                            end match
                        case ErrorResponse(fields) =>
                            Abort.fail(mkAuthError(fields))
                        case other =>
                            Abort.fail(SqlConnectionUnexpectedMessageException(
                                "SCRAM continue",
                                "AuthenticationSASLContinue / ErrorResponse",
                                other.toString
                            ))
                    }
                }
            }
        }
    end authenticateSCRAM

    private def expectAuthOk(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case Authentication(AuthenticationKind.Ok) => ()
            case ErrorResponse(fields) =>
                Abort.fail(mkAuthError(fields))
            case other =>
                Abort.fail(SqlConnectionUnexpectedMessageException("after password", "AuthenticationOk / ErrorResponse", other.toString))
        }

    private def collectStartupMessages(
        channel: PostgresChannel,
        params: Map[String, String],
        keyData: Maybe[(Int, Int)]
    )(using Frame): StartupResult < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case ParameterStatus(name, value) =>
                collectStartupMessages(channel, params + (name -> value), keyData)

            case BackendKeyData(pid, secret) =>
                collectStartupMessages(channel, params, Present((pid, secret)))

            case ReadyForQuery(_) =>
                val (pid, secret) = keyData.getOrElse((0, 0))
                StartupResult(params, pid, secret)

            case NoticeResponse(_) =>
                // Notices during startup are informational; ignore and continue.
                collectStartupMessages(channel, params, keyData)

            case ErrorResponse(fields) =>
                Abort.fail(mkAuthError(fields))

            case other =>
                Abort.fail(SqlConnectionUnexpectedMessageException(
                    "startup",
                    "ParameterStatus / BackendKeyData / ReadyForQuery / NoticeResponse / ErrorResponse",
                    other.toString
                ))
        }

    /** Converts an authentication ErrorResponse into a marker-carrying [[SqlException]].
      *
      * PG auth failures carry sqlState "28P01" (invalid_password), "28000" (invalid_authorization_specification), etc. Any sqlState in the
      * "28" class routes to [[SqlConnectionAuthenticationFailedException]] so callers can recover via `Abort.recover[SqlAuthenticationFailure]`.
      * Other sqlStates fall back to the generic [[SqlServerException]] classification.
      */
    private def mkAuthError(fields: Chunk[(Byte, String)])(using Frame): SqlException =
        var sqlState: String     = "00000"
        var message: String      = "Authentication failed"
        var errorCodeStr: String = ""
        val it                   = fields.iterator
        while it.hasNext do
            val (tag, value) = it.next()
            if tag == 'C'.toByte then sqlState = value
            else if tag == 'M'.toByte then message = value
            else if tag == 'c'.toByte then errorCodeStr = value
            else ()
            end if
        end while
        if sqlState.startsWith("28") then
            val errorCode = errorCodeStr.toIntOption.getOrElse(0)
            SqlConnectionAuthenticationFailedException(sqlState, errorCode, message)
        else
            ServerErrors.mkServerError(fields, Maybe.Absent, 0, Maybe.Absent)
        end if
    end mkAuthError

end StartupExchange
