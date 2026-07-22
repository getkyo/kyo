package kyo.internal.postgres.exchange

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlConnectionUnsupportedAuthMethodException
import kyo.SqlException
import kyo.internal.postgres.*
import kyo.internal.postgres.auth.Md5Password
import kyo.internal.postgres.auth.PlainPassword
import kyo.internal.postgres.auth.ScramSha256

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
  *      - [[AuthenticationKind.SASL]] with "SCRAM-SHA-256" or "SCRAM-SHA-256-PLUS" → full SCRAM exchange. When the server offers
  *        SCRAM-SHA-256-PLUS AND the connection has a known TLS cert hash, uses PLUS (channel binding). Otherwise uses SCRAM-SHA-256.
  *      - Other kinds fail with [[SqlConnectionUnsupportedAuthMethodException]] ("auth method not supported").
  *   3. Drain [[ParameterStatus]] and [[BackendKeyData]] messages until [[ReadyForQuery]].
  *   4. Return [[StartupResult]] with the accumulated parameters and backend key data.
  */
object StartupExchange:

    def run(
        channel: PostgresChannel,
        user: String,
        db: String,
        password: Maybe[String],
        certHashOverride: Maybe[Maybe[Span[Byte]]],
        mechanismCapture: Maybe[AtomicRef[String]]
    )(using Frame): StartupResult < (Async & Abort[SqlException]) =
        val params = Chunk(
            ("user", user),
            ("database", db),
            ("application_name", "kyo-sql"),
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
                val pw           = password.getOrElse("")
                val pwBytes      = PlainPassword.encode(pw)
                val pwStr        = new String(pwBytes, StandardCharsets.UTF_8)
                val pwMarshaller = channel.marshallers.passwordMessage
                channel.send(PasswordMessage(pwStr))(using pwMarshaller).andThen {
                    expectAuthOk(channel)
                }

            case Authentication(AuthenticationKind.MD5Password(salt)) =>
                val pw  = password.getOrElse("")
                val md5 = Md5Password.encode(pw, user, salt.toArray)
                channel.send(PasswordMessage(md5))(using channel.marshallers.passwordMessage).andThen {
                    expectAuthOk(channel)
                }

            case Authentication(AuthenticationKind.SASL(mechanisms)) =>
                // Select SCRAM-SHA-256-PLUS when the server offers it AND the connection has a known TLS cert hash.
                // Use the SAFE `serverCertificateHash` (returns Maybe[Span[Byte]] < Sync), no AllowUnsafe.
                // certHashOverride (if Present) takes precedence over the real cert hash; used in tests to inject a synthetic hash.
                if mechanisms.contains("SCRAM-SHA-256") || mechanisms.contains("SCRAM-SHA-256-PLUS") then
                    // certHashOverride semantics:
                    //   Absent               = no override; use the real connection cert hash
                    //   Present(Absent)      = override to Absent (force non-PLUS even on TLS)
                    //   Present(Present(h))  = override to Present(h) (inject a synthetic hash)
                    val certHashEffect: Maybe[Span[Byte]] < Sync = certHashOverride match
                        case Present(overrideValue) => overrideValue
                        case Absent                 => channel.conn.serverCertificateHash
                    certHashEffect.flatMap { certHash =>
                        val (mechanism, binding) = (mechanisms.contains("SCRAM-SHA-256-PLUS"), certHash) match
                            case (true, Present(hash)) => ("SCRAM-SHA-256-PLUS", Present(hash))
                            case _                     => ("SCRAM-SHA-256", Absent)
                        authenticateSCRAM(channel, user, password.getOrElse(""), mechanism, binding, mechanismCapture)
                    }
                else
                    val mechs = mechanisms.mkString(", ")
                    Abort.fail(SqlConnectionUnsupportedAuthMethodException(s"SASL with mechanisms [$mechs]"))

            case ErrorResponse(fields) =>
                Abort.fail(mkAuthError(fields))

            case Authentication(other) =>
                Abort.fail(SqlConnectionUnsupportedAuthMethodException(other.getClass.getSimpleName))

            case other =>
                Abort.fail(SqlConnectionUnexpectedMessageException("authentication", "Authentication / ErrorResponse", other.toString))
        }

    private def authenticateSCRAM(
        channel: PostgresChannel,
        user: String,
        password: String,
        mechanism: String,
        channelBinding: Maybe[Span[Byte]],
        mechanismCapture: Maybe[AtomicRef[String]]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        // Generate client nonce using kyo.Random.secure, 24 random bytes, base64-encoded.
        Random.secure.nextBytes(24).flatMap { nonceBytes =>
            val clientNonce = ScramSha256.encodeNonce(Span.from(nonceBytes.toArray))
            val scram       = new ScramSha256(user, clientNonce, channelBinding)
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
            QueryResultExchange.mkServerError(fields, Maybe.Absent, 0, Maybe.Absent)
        end if
    end mkAuthError

end StartupExchange
