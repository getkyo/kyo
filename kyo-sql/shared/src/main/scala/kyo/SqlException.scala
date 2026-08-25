package kyo

import kyo.Chunk
import kyo.Frame
import kyo.KyoException
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.db.Idiom

/** Base class for every error raised by kyo-sql, organized into five sealed sub-categories by failure mode.
  *
  * The five sub-categories map to distinct failure modes:
  *   - [[kyo.SqlConnectionException]], transport-level and connection-pool failures (connect refused, pool exhausted, protocol decode)
  *   - [[kyo.SqlRequestException]], client-side request-preparation failures (bad argument shape, encoding overflow)
  *   - [[kyo.SqlServerException]], error responses received from the database server (carries the wire ErrorResponse fields)
  *   - [[kyo.SqlDecodeException]], row-level decoding failures after the server has returned data
  *   - [[kyo.SqlUnsupportedException]], operations the current backend does not implement
  *
  * Every leaf is a top-level type prefixed with its sub-category; the message string is authored inside the leaf from its typed fields.
  * Three cross-cutting marker traits carry properties callers recover on: [[kyo.SqlRetryable]], [[kyo.SqlIntegrityViolation]], and
  * [[kyo.SqlAuthenticationFailure]].
  *
  * All kyo-sql operations fail with `Abort[SqlException]` as their error channel. Match on the sub-category to distinguish recovery
  * strategies, on the marker trait to recover by property, or on the concrete leaf when the typed fields are needed:
  *
  * {{{
  * query.run.pipe(Abort.recover[SqlException] {
  *   case _: SqlRetryable                          => // transient, safe to retry
  *   case e: SqlServerConstraintViolationException => // unique / foreign-key / check violation
  *   case e: SqlDecodeException                    => // schema mismatch, check derivation
  *   case e: SqlConnectionException                => // transport failure
  *   case other                                    => throw other // rethrow other categories
  * })
  * }}}
  */
sealed abstract class SqlException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends KyoException(msg, cause):

    /** The message string authored by the leaf's typed fields. Delegates to [[getMessage]] on the underlying [[Throwable]]. */
    def message: String = getMessage()
end SqlException

object SqlException:
    given Render[SqlException] = Render.from(_.getMessage)

// --- Marker traits ---

/** Property marker: the failure is transient and the caller may safely retry the operation.
  *
  * Unsealed: matched as a typecase, never scrutinized exhaustively, so an out-of-tree backend leaf can declare itself retryable.
  */
trait SqlRetryable

/** Property marker: the failure is a database integrity violation (unique, foreign-key, check, or exclusion constraint). Unsealed, as
  * [[SqlRetryable]].
  */
trait SqlIntegrityViolation

/** Property marker: the failure is an authentication rejection (bad credentials, missing TLS, unsupported mechanism). Unsealed, as
  * [[SqlRetryable]].
  */
trait SqlAuthenticationFailure

// =============================================================================
// SqlConnectionException family
// =============================================================================

/** Transport-level and connection-pool failures. */
sealed abstract class SqlConnectionException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlException(msg, cause)

/** Cannot parse a database URL into scheme, host, port, database, and options.
  *
  * `url` is stored with any password already redacted (the constructor redacts), so a structured logger that serialises exception
  * fields cannot leak the credential either.
  */
final case class SqlConnectionUrlParseException private (url: String, scheme: String)(using Frame)
    extends SqlConnectionException(
        s"Cannot parse database URL for scheme '$scheme'. Input: $url"
    )

object SqlConnectionUrlParseException:
    /** The one door in: redacts before the field is stored. */
    def apply(rawUrl: String, scheme: String)(using Frame): SqlConnectionUrlParseException =
        new SqlConnectionUrlParseException(SqlConnectionUrlParseException.redactUserInfo(rawUrl), scheme)

    /** Redacts the password of a URL's userinfo, `scheme://user:pass@host` becoming `scheme://user:***@host`.
      *
      * Malformed input is exactly where this runs (a parse failure's message is its main consumer), so it must not rely on the URL
      * being well formed: with no `://` the scan starts at the beginning of the string, and the userinfo is taken to end at the LAST
      * `@` (RFC 3986), so a password containing `@` is covered whole. On the aggressive paths this can mask more than the password;
      * over-masking a diagnostic beats leaking a credential.
      */
    private[kyo] def redactUserInfo(url: String): String =
        val schemeSep = url.indexOf("://")
        val start     = if schemeSep < 0 then 0 else schemeSep + 3
        val authorityEnd =
            if schemeSep < 0 then url.length
            else
                url.indexOf('/', start) match
                    case -1 => url.length
                    case i  => i
        val atIndex = url.lastIndexOf('@', authorityEnd - 1)
        if atIndex < start then url
        else
            val userInfo = url.substring(start, atIndex)
            val colon    = userInfo.indexOf(':')
            if colon < 0 then url
            else
                val user = userInfo.substring(0, colon)
                s"${url.substring(0, start)}$user:***${url.substring(atIndex)}"
            end if
        end if
    end redactUserInfo
end SqlConnectionUrlParseException

/** A database URL query-string option carries a value [[kyo.SqlConfig.Url.Options]] cannot represent.
  *
  * Raised instead of dropping the pair, so a mistyped `sslmode` is reported rather than silently leaving the connection plaintext.
  *
  * @param key
  *   the query-string key whose value was rejected
  * @param value
  *   the value as it appeared in the URL
  * @param expected
  *   what the key accepts, phrased for the message
  */
final case class SqlConnectionUrlOptionException(key: String, value: String, expected: String)(using Frame)
    extends SqlConnectionException(
        s"URL option '$key' has unsupported value '$value'; expected $expected"
    )

/** The database URL's scheme has no registered backend on the classpath.
  *
  * `available` lists the schemes the registered backends do claim, so the message names the missing dependency for the caller. It covers
  * both tiers of [[kyo.db.Backend.Registry]], the factories the call site compiled against and the ones the running program discovered, so
  * it answers what this program can actually open rather than only what the compiler saw.
  */
final case class SqlConnectionUnsupportedSchemeException(scheme: String, available: Chunk[String])(using Frame)
    extends SqlConnectionException(
        s"No backend registered for URL scheme '$scheme'. Registered schemes: ${available.mkString(", ")}"
    )

/** TCP connect to the database server failed. */
final case class SqlConnectionConnectFailedException(host: String, port: Int, cause: Throwable)(using Frame)
    extends SqlConnectionException(
        s"Connect to $host:$port failed",
        cause
    ) with SqlRetryable

/** Server rejected the SSLRequest with a byte other than 'S' or 'N'. */
final case class SqlConnectionSslRequestFailedException(host: String, port: Int, responseByte: Byte)(using Frame)
    extends SqlConnectionException(
        s"SSLRequest to $host:$port received unexpected response byte 0x${(responseByte & 0xff).toHexString.toUpperCase}"
    )

/** Server responded 'N' to SSLRequest: it does not advertise TLS support. */
final case class SqlConnectionTlsNotAdvertisedException(host: String, port: Int)(using Frame)
    extends SqlConnectionException(
        s"Server at $host:$port does not support TLS (responded 'N' to SSLRequest)"
    )

/** The configured TLS mode cannot be built into a usable configuration (a verifying mode naming no CA certificate). */
final case class SqlConnectionTlsConfigException(tlsMode: SqlConfig.TlsMode)(using Frame)
    extends SqlConnectionException(
        s"Unsupported TLS configuration: sslMode='${tlsMode.sslMode}'"
    )

/** A [[SqlConfig.TlsMode]] that demands encryption reached the point of opening a connection with no TLS settings to open it with.
  *
  * Sits between two siblings and is neither. [[SqlConnectionTlsConfigException]] reports a config that could not be BUILT: `verify-ca` or
  * `verify-full` naming no CA certificate. [[SqlConnectionTlsNotAdvertisedException]] reports the SERVER declining TLS. This one reports the
  * client's own state: a well-formed mode arriving at the point of USE with `SqlConfig.tls` absent, so nothing was ever offered to the server to
  * decline. The three must stay separate because a caller diagnosing a failed connection acts differently on each, and only this one is reachable
  * without touching a URL, a factory, or a server.
  *
  * `kyo.internal.tls.TlsContext.build` is what pairs a mode with its settings, and `SqlConfig.Url.toConfig` is its only caller on the way to a
  * client, so the pairing is established once at construction. Any later route to a [[SqlConfig]] can break it: `withConfig(_.copy(tlsMode =
  * VerifyFull))` raises the demand and leaves `tls` as it was. Both engines' connect paths read `tls` rather than the mode, so an absent one
  * would open in the clear and run the statement unencrypted for a caller who asked for a verified channel; this exception is raised at connect
  * time to prevent that.
  *
  * Raised where the connection is opened rather than where the config is assembled, so it does not matter which route produced the config.
  * Repairing it at the mutation sites instead would leave the state representable and rely on every future one remembering.
  */
final case class SqlConnectionTlsNotConfiguredException(tlsMode: SqlConfig.TlsMode)(using Frame)
    extends SqlConnectionException(
        s"TLS mode '${tlsMode.sslMode}' requires an encrypted connection, but no TLS settings are configured"
    )

/** The connection pool has been closed and cannot serve further requests. */
final case class SqlConnectionPoolClosedException()(using Frame)
    extends SqlConnectionException(
        "Connection pool is closed"
    )

/** Acquiring a connection from the pool exceeded the configured acquire timeout. */
final case class SqlConnectionAcquireTimeoutException(acquireTimeout: Duration)(using Frame)
    extends SqlConnectionException(
        s"Acquiring a connection from the pool timed out after ${acquireTimeout.show}"
    ) with SqlRetryable

/** Establishing a new physical connection exceeded the configured connect timeout. */
final case class SqlConnectionEstablishTimeoutException(timeout: Duration, host: String, port: Int)(using Frame)
    extends SqlConnectionException(
        s"Establishing a connection to $host:$port timed out after ${timeout.show}"
    ) with SqlRetryable

/** A query exceeded the configured per-query timeout. */
final case class SqlConnectionQueryTimeoutException(queryTimeout: Duration)(using Frame)
    extends SqlConnectionException(
        s"Query exceeded the configured timeout of ${queryTimeout.show}"
    ) with SqlRetryable

/** A single read on an established connection waited longer than the URL's `socketTimeout`.
  *
  * Distinct from [[SqlConnectionQueryTimeoutException]], which bounds a whole statement: this bounds one wait for bytes, so it fires when the
  * server stops answering mid-response rather than when a statement takes too long overall. The connection is left desynchronised by
  * definition, part of a response having been read and the rest not, so it is destroyed rather than pooled.
  */
final case class SqlConnectionSocketTimeoutException(socketTimeout: Duration)(using Frame)
    extends SqlConnectionException(
        s"A read on an established connection exceeded the configured socketTimeout of ${socketTimeout.show}"
    ) with SqlRetryable

/** A client-side budget for stopping or closing a session expired before the work finished.
  *
  * Both uses are the same shape, which is why they share one leaf: the reclaim chain's `cancelTimeout`, covering the wire cancel, the drain and
  * the rollback together, and MySQL's close grace period bounding `COM_QUIT`. The carried [[kyo.Duration]] is whichever budget expired.
  *
  * This is a [[SqlConnectionException]] and not a server error: nothing here was reported by the server, the client gave up. Modelling it as a
  * [[SqlServerException]] with a fabricated SQLSTATE would make a client-side timeout indistinguishable from a real server-reported cancellation
  * for callers matching on SQLSTATE, so the connection-lifecycle family is the honest one and [[SqlRetryable]] carries the retry advice.
  */
final case class SqlConnectionCancelTimeoutException(cancelTimeout: Duration)(using Frame)
    extends SqlConnectionException(
        s"A client-side budget of ${cancelTimeout.show} for stopping or closing the session expired"
    ) with SqlRetryable

/** The pool warmup task panicked with an unexpected error. */
final case class SqlConnectionWarmupPanicException(cause: Throwable)(using Frame)
    extends SqlConnectionException(
        "Pool warmup panicked",
        cause
    )

/** The connection was closed by the peer or the driver in the middle of an operation. */
final case class SqlConnectionClosedException(phase: String)(using Frame)
    extends SqlConnectionException(
        s"Connection closed during $phase"
    )

/** A write to the underlying transport panicked. */
final case class SqlConnectionWritePanicException(cause: Throwable)(using Frame)
    extends SqlConnectionException(
        "Transport write panicked",
        cause
    )

/** The wire protocol framing for a bulk-transfer operation (COPY / LOAD DATA) is corrupted. */
final case class SqlConnectionProtocolCorruptedException(operation: String)(using Frame)
    extends SqlConnectionException(
        s"Protocol framing corrupted during $operation"
    )

/** Failed to decode a wire packet from the server. */
final case class SqlConnectionProtocolDecodeException(packetType: String, cause: String | Throwable)(using Frame)
    extends SqlConnectionException(
        s"Failed to decode $packetType packet",
        cause
    )

/** Received a message that does not match the protocol's expected next message for this phase. */
final case class SqlConnectionUnexpectedMessageException(phase: String, expected: String, actual: String)(using Frame)
    extends SqlConnectionException(
        s"Unexpected message during $phase: expected $expected, received $actual"
    )

/** The server requested an authentication mechanism the driver does not implement.
  *
  * `mechanism` is whatever the backend's handshake names: a SASL mechanism on PostgreSQL, an authentication plugin on MySQL.
  */
final case class SqlConnectionUnsupportedAuthMethodException(mechanism: String)(using Frame)
    extends SqlConnectionException(
        s"Unsupported authentication mechanism: $mechanism"
    ) with SqlAuthenticationFailure

/** SCRAM authentication exchange failed. */
final case class SqlConnectionScramFailedException(reason: String)(using Frame)
    extends SqlConnectionException(
        s"SCRAM authentication failed: $reason"
    ) with SqlAuthenticationFailure

/** The server asked for more PBKDF2 iterations than the driver will perform for one SCRAM exchange.
  *
  * RFC 5802 carries the iteration count in the server's first message, so the peer chooses it before the client has authenticated the peer,
  * and under every `SqlConfig.TlsMode` short of `verify-ca` the peer is unauthenticated when it names the number. The derivation that consumes it is a
  * straight-line loop with no suspension point, so it occupies the fiber's carrier for its whole duration and neither an `Async.timeout`
  * around the connect nor interrupting the connecting fiber can cut it short. `limit` is the ceiling that bounds that work before it starts.
  *
  * `iterations` is a `Long` so a count above `Int.MaxValue` is reported as what it is rather than as a malformed field.
  */
final case class SqlConnectionScramIterationsTooHighException(iterations: Long, limit: Int)(using Frame)
    extends SqlConnectionException(
        s"Server asked for $iterations SCRAM iterations, above the driver's ceiling of $limit"
    ) with SqlAuthenticationFailure

/** The server rejected authentication with an ErrorResponse. */
final case class SqlConnectionAuthenticationFailedException(sqlState: String, errorCode: Int, serverMessage: String)(using Frame)
    extends SqlConnectionException(
        s"Authentication rejected by server [$sqlState] (errorCode=$errorCode): $serverMessage"
    ) with SqlAuthenticationFailure

/** The server asked for a credential and none was configured.
  *
  * Raised instead of authenticating with the empty string, because "no password was supplied" and "the password is empty" are different facts
  * and only the first one is actionable: an empty-password attempt comes back as the server rejecting the credential, which sends the caller
  * looking for a wrong password rather than a missing one. PostgreSQL has no usable empty password for any of the methods that reach here, so
  * the attempt could not succeed either.
  *
  * `method` names what the server asked for, so the message says which authentication the connection would have needed a password for.
  *
  * MySQL is deliberately not covered by this leaf: its plugins define a zero-length auth response for genuinely passwordless accounts, so an
  * absent credential there is a valid thing to send rather than a missing input.
  */
final case class SqlConnectionPasswordRequiredException(method: String)(using Frame)
    extends SqlConnectionException(
        s"Server requested $method authentication but no password was configured for this connection"
    ) with SqlAuthenticationFailure

/** A backend whose handshake carries a user name reached the point of opening a connection with [[SqlConfig.Address.user]] absent.
  *
  * The password sibling above reports a credential the SERVER asked for; this one reports an input the PROTOCOL requires unconditionally, so it
  * is raised before any socket is opened rather than partway through an exchange. Both PostgreSQL's startup packet and MySQL's handshake
  * response carry the user name as a mandatory field, and neither has anything to send in its place.
  *
  * Raised instead of sending an empty user name: a URL naming no user would otherwise reach the wire as an empty user name, and the server
  * answers that no such role exists, which reads as a wrong user rather than a missing one. A declared empty user
  * ([[Present]] with an empty string, spelled `scheme://@host:port/db`) is a different fact and is NOT refused here: what an empty user name
  * means is the server's to decide, and MySQL's anonymous accounts are a real use for it.
  *
  * `scheme` is the URL scheme that selected the backend, which is what says whose handshake demanded the field. The refusal belongs to the
  * backend rather than to [[SqlConfig]]: an engine authenticating by peer credentials or a Unix socket peer name would need no user, and core
  * cannot rule for it. What keeps a backend from forgetting the check is that it must obtain a user name to put on the wire at all, so the type
  * forces the question at every engine.
  */
final case class SqlConnectionUserRequiredException(scheme: String)(using Frame)
    extends SqlConnectionException(
        s"Connecting to a '$scheme' database requires a user name, but none was configured"
    ) with SqlAuthenticationFailure

/** MySQL asked for a clear-text password over a non-TLS connection, which the driver refuses to send. */
final case class SqlConnectionClearPasswordRequiresTlsException()(using Frame)
    extends SqlConnectionException(
        "Clear-text password authentication requires TLS; refusing to send credentials over an unencrypted connection"
    ) with SqlAuthenticationFailure

/** The notification consumer task panicked. */
final case class SqlConnectionNotificationPanicException(cause: Throwable)(using Frame)
    extends SqlConnectionException(
        "Notification consumer panicked",
        cause
    )

/** The type-lookup step could not resolve one or more type names to OIDs. */
final case class SqlConnectionTypeLookupMissingException(missingTypes: Chunk[String])(using Frame)
    extends SqlConnectionException(
        s"Type lookup missing OIDs for: ${missingTypes.mkString(", ")}"
    )

/** One or more type names supplied for pre-registration are not valid identifiers. */
final case class SqlConnectionInvalidTypeNameException(typeNames: Chunk[String])(using Frame)
    extends SqlConnectionException(
        s"Invalid type name(s) for pre-registration: ${typeNames.mkString(", ")}"
    )

/** Resetting a pooled connection failed with a server ErrorResponse. */
final case class SqlConnectionResetFailedException(errorCode: Int, errorMessage: String)(using Frame)
    extends SqlConnectionException(
        s"Connection reset failed (errorCode=$errorCode): $errorMessage"
    )

/** An operation was asked of the wrong backend (e.g. narrowing to a PostgresClient over a MySQL client).
  *
  * `requested` names what the caller asked for, verbatim: the client type at a [[kyo.DB.clientAs]] narrow, or the dialect an
  * engine-only operation belongs to. `activeDriver` is the dialect the client actually speaks. `requested` is a plain string rather
  * than an [[Idiom.Id]] because the thing asked for is not always a dialect.
  */
final case class SqlConnectionBackendMismatchException(
    requested: String,
    activeDriver: Idiom.Id,
    operation: String
)(using Frame)
    extends SqlConnectionException(
        s"Operation '$operation' requires '$requested', but the active driver is '${activeDriver.value}'"
    )

// =============================================================================
// SqlRequestException family
// =============================================================================

/** Client-side request-preparation failures. */
sealed abstract class SqlRequestException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlException(msg, cause)

/** A `.runStatic` call site rendered SQL for a set of dialects that does not include the connected client's dialect.
  *
  * `available` lists the dialects the splice did render, so the message tells the caller which backend module was missing from the compile
  * classpath.
  */
final case class SqlStaticRenderMissingDialectException(dialect: Idiom.Id, available: Chunk[Idiom.Id])(using Frame)
    extends SqlRequestException(
        s"No statically rendered SQL for dialect '${dialect.value}'. Rendered dialects: ${available.map(_.value).mkString(", ")}"
    )

/** The MySQL server responded with a LOAD DATA LOCAL INFILE request during a regular query. Callers must use the dedicated
  * `loadLocalInfile` API to run LOAD DATA LOCAL INFILE statements, not `query` / `execute`.
  */
final case class SqlRequestMysqlLocalInfileRequiresLoadApiException()(using Frame)
    extends SqlRequestException(
        "MySQL server responded with LOAD DATA LOCAL INFILE for a regular query. Use the MySQL client's loadLocalInfile instead."
    )

/** RSA-OAEP encryption of the MySQL sha256_password payload failed. */
final case class SqlRequestRsaOaepException(position: String, tag: String, cause: String | Throwable)(using Frame)
    extends SqlRequestException(
        s"RSA-OAEP failed at $position (tag=$tag)",
        cause
    )

/** The MySQL server offered an RSA public key component larger than the driver will use.
  *
  * The key arrives from the peer in an `AuthMoreData` packet on a connection that is neither encrypted nor authenticated yet, and modular
  * exponentiation costs grow with the bit length of both the modulus and the exponent, so an unbounded key lets the peer choose how long the
  * encryption step runs on the fiber's carrier. MySQL's own auto-generated keys are 2048-bit with exponent 65537.
  */
final case class SqlRequestRsaKeyTooLargeException(
    component: SqlRequestRsaKeyTooLargeException.Component,
    bits: Int,
    limit: Int
)(using Frame)
    extends SqlRequestException(
        component match
            case SqlRequestRsaKeyTooLargeException.Component.Modulus =>
                s"RSA modulus is $bits bits, above the driver's ceiling of $limit"
            case SqlRequestRsaKeyTooLargeException.Component.Exponent =>
                s"RSA public exponent is $bits bits, above the driver's ceiling of $limit"
    )

object SqlRequestRsaKeyTooLargeException:
    /** Which of the two key components exceeded its ceiling. */
    enum Component derives CanEqual:
        case Modulus
        case Exponent
    end Component
end SqlRequestRsaKeyTooLargeException

/** An advisory lock on `key` could not be acquired. `timeout` is the wait budget the caller asked for, absent when the caller asked to wait
  * indefinitely.
  */
final case class SqlRequestAdvisoryLockException(key: Long, timeout: Maybe[Duration])(using Frame)
    extends SqlRequestException(
        timeout.fold(s"Advisory lock $key could not be acquired")(t => s"Advisory lock $key could not be acquired within ${t.show}")
    )

/** A Duration exceeded the range the target backend can represent.
  *
  * `limit` names the bound that was actually crossed, supplied by the backend that raised this. It is a
  * parameter rather than a fixed string because the two backends enforce DIFFERENT bounds for different
  * reasons: PostgreSQL overflows the microsecond count an interval is stored in, and MySQL overflows the day
  * count its TIME encoding carries, so no single hardcoded bound is right for both.
  */
final case class SqlRequestDurationOverflowException(totalDays: Long, limit: String)(using Frame)
    extends SqlRequestException(
        s"Duration overflow: $totalDays total days exceeds $limit"
    )

/** A `Period`'s year-and-month total exceeded the range `Period.normalized()` can carry.
  *
  * `Period.normalized()` computes `years * 12L + months` as a `Long`, then narrows the quotient of that total divided by twelve back into an
  * `Int` for the normalised years field, via `Math.toIntExact`, an unchecked `ArithmeticException` when the quotient does not fit. A `Period`
  * reaching this from a bind parameter is built directly (`Period.of`), not derived from `LocalDate.until` or `Period.between`, whose own
  * `LocalDate` year range keeps the quotient well inside `Int`; a directly constructed `Period` carries no such bound, so its years field can
  * be large enough that the quotient overflows. This is the calendar-interval sibling of [[kyo.SqlRequestDurationOverflowException]]: the
  * guard runs before normalisation so the caller receives a typed leaf instead of the JDK's unchecked exception.
  *
  * `totalMonths` is `years * 12L + months`, computed the same way `Period.normalized()` computes it, before the narrowing that overflows.
  */
final case class SqlRequestPeriodOverflowException(totalMonths: Long, limit: String)(using Frame)
    extends SqlRequestException(
        s"Period overflow: $totalMonths total months exceeds $limit"
    )

/** A notification channel name contains a NUL character.
  *
  * `LISTEN` travels as a NUL-terminated cstring, so an embedded NUL ends the statement at the server instead of appearing inside the quoted
  * identifier. Doubling the quotes, which is what makes every other character in a channel name safe, cannot reach it: the byte terminates
  * the message rather than living in it, so the server parses a prefix and answers a syntax error naming a statement the caller never wrote.
  * Refused before the statement is built, and before a connection is opened for it.
  *
  * `index` locates the NUL. The name is deliberately not echoed: putting it in this message would carry the NUL into the message, and from
  * there into logs and terminals where it is invisible.
  */
final case class SqlRequestNotificationChannelNulException(index: Int)(using Frame)
    extends SqlRequestException(
        s"A notification channel name cannot contain a NUL character; found one at index $index"
    )

// =============================================================================
// SqlServerException family
// =============================================================================

/** Error responses received from the database server.
  *
  * Fields mirror the PostgreSQL ErrorResponse / NoticeResponse message format (§52.2 of the PG docs). MySQL maps its ERR fields into the
  * same structure: sqlState from the ERR packet, severity inferred from error-class, message from the error message, detail/hint/position
  * absent (MySQL does not send them), extra carries the MySQL-specific error code as `"code" -> "1062"`.
  *
  * The server's text passes through verbatim, and PostgreSQL's `detail` conventionally echoes the offending row values (`Key
  * (email)=(alice@example.com) already exists.`), so error logs inherit whatever data the database puts in its own errors. The fields
  * are structured exactly so a reporter that must redact can drop or mask `detail` while keeping `sqlState` and `message`.
  */
sealed abstract class SqlServerException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlException(msg, cause):
    def sqlState: String
    def severity: String
    def serverMessage: String
    def detail: Maybe[String]
    def hint: Maybe[String]
    def position: Maybe[Int]
    def extra: Map[String, String]
    def sqlText: Maybe[String]
    def paramCount: Int
    def connectionId: Maybe[Long]
end SqlServerException

object SqlServerException:
    /** Dispatch a server ErrorResponse to the correct leaf by SQLSTATE prefix. */
    def apply(
        sqlState: String,
        severity: String,
        message: String,
        detail: Maybe[String],
        hint: Maybe[String],
        position: Maybe[Int],
        extra: Map[String, String],
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    )(using Frame): SqlServerException =
        if sqlState == "40001" || sqlState == "40P01" then
            SqlServerDeadlockException(sqlState, severity, message, detail, hint, position, extra, sqlText, paramCount, connectionId)
        else if sqlState.startsWith("23") then
            SqlServerConstraintViolationException(
                sqlState,
                severity,
                message,
                detail,
                hint,
                position,
                extra,
                sqlText,
                paramCount,
                connectionId
            )
        else if sqlState.startsWith("42") then
            SqlServerSyntaxException(sqlState, severity, message, detail, hint, position, extra, sqlText, paramCount, connectionId)
        else if sqlState.startsWith("08") then
            SqlServerConnectionException(sqlState, severity, message, detail, hint, position, extra, sqlText, paramCount, connectionId)
        else
            SqlServerErrorException(sqlState, severity, message, detail, hint, position, extra, sqlText, paramCount, connectionId)
    end apply

    /** Convenience for tests and simple construction with no optional fields. */
    private[kyo] def apply(sqlState: String, severity: String, message: String)(using Frame): SqlServerException =
        apply(sqlState, severity, message, Absent, Absent, Absent, Map.empty, Absent, 0, Absent)

    private[kyo] def format(
        sqlState: String,
        severity: String,
        serverMessage: String,
        detail: Maybe[String],
        hint: Maybe[String],
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    ): String =
        s"[$sqlState] $severity: $serverMessage" +
            detail.fold("")(d => s"\n  Detail: $d") +
            hint.fold("")(h => s"\n  Hint: $h") +
            sqlText.fold("")(s => s"\n  SQL: $s") +
            (if paramCount > 0 then s"\n  Params: $paramCount" else "") +
            connectionId.fold("")(id => s"\n  ConnectionId: $id")
end SqlServerException

/** Integrity-constraint violation (SQLSTATE class 23: unique, foreign-key, check, exclusion). */
final case class SqlServerConstraintViolationException(
    sqlState: String,
    severity: String,
    serverMessage: String,
    detail: Maybe[String],
    hint: Maybe[String],
    position: Maybe[Int],
    extra: Map[String, String],
    sqlText: Maybe[String],
    paramCount: Int,
    connectionId: Maybe[Long]
)(using Frame)
    extends SqlServerException(
        SqlServerException.format(sqlState, severity, serverMessage, detail, hint, sqlText, paramCount, connectionId)
    ) with SqlIntegrityViolation

/** Serialization or deadlock failure (SQLSTATE 40001 / 40P01), safe to retry. */
final case class SqlServerDeadlockException(
    sqlState: String,
    severity: String,
    serverMessage: String,
    detail: Maybe[String],
    hint: Maybe[String],
    position: Maybe[Int],
    extra: Map[String, String],
    sqlText: Maybe[String],
    paramCount: Int,
    connectionId: Maybe[Long]
)(using Frame)
    extends SqlServerException(
        SqlServerException.format(sqlState, severity, serverMessage, detail, hint, sqlText, paramCount, connectionId)
    ) with SqlRetryable

/** Syntax or access-rule error (SQLSTATE class 42). */
final case class SqlServerSyntaxException(
    sqlState: String,
    severity: String,
    serverMessage: String,
    detail: Maybe[String],
    hint: Maybe[String],
    position: Maybe[Int],
    extra: Map[String, String],
    sqlText: Maybe[String],
    paramCount: Int,
    connectionId: Maybe[Long]
)(using Frame)
    extends SqlServerException(
        SqlServerException.format(sqlState, severity, serverMessage, detail, hint, sqlText, paramCount, connectionId)
    )

/** Connection exception reported by the server after login (SQLSTATE class 08). */
final case class SqlServerConnectionException(
    sqlState: String,
    severity: String,
    serverMessage: String,
    detail: Maybe[String],
    hint: Maybe[String],
    position: Maybe[Int],
    extra: Map[String, String],
    sqlText: Maybe[String],
    paramCount: Int,
    connectionId: Maybe[Long]
)(using Frame)
    extends SqlServerException(
        SqlServerException.format(sqlState, severity, serverMessage, detail, hint, sqlText, paramCount, connectionId)
    )

/** Fallback for any server error not covered by the categorised leaves. */
final case class SqlServerErrorException(
    sqlState: String,
    severity: String,
    serverMessage: String,
    detail: Maybe[String],
    hint: Maybe[String],
    position: Maybe[Int],
    extra: Map[String, String],
    sqlText: Maybe[String],
    paramCount: Int,
    connectionId: Maybe[Long]
)(using Frame)
    extends SqlServerException(
        SqlServerException.format(sqlState, severity, serverMessage, detail, hint, sqlText, paramCount, connectionId)
    )

// =============================================================================
// SqlDecodeException family
// =============================================================================

/** Row-level decoding failures raised after the server has returned data. */
sealed abstract class SqlDecodeException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlException(msg, cause)

/** Not enough bytes remaining in the buffer to decode a value of the target type. */
final case class SqlDecodeInsufficientBytesException(typeName: String, expected: Int, actual: Int, position: Int)(using Frame)
    extends SqlDecodeException(
        s"Insufficient bytes for $typeName at position $position: expected $expected, actual $actual"
    )

/** A column carried no value and the target Scala type is not a [[kyo.Maybe]].
  *
  * A caller identifies the column by index, by name, or by both, so each field is present exactly when the caller had it. The primary
  * constructor is private because a value carrying neither identifies no column at all, leaving the three companion overloads as the whole
  * public surface.
  *
  * @param columnIndex
  *   the column's position in the row, absent where the caller looked the column up by name
  * @param columnName
  *   the column name the server reported, absent where the caller read the column positionally
  */
final case class SqlDecodeColumnAbsentException private (columnIndex: Maybe[Int], columnName: Maybe[String])(using Frame)
    extends SqlDecodeException(
        columnName.fold(
            columnIndex.fold("A column held no value; its target type is not Maybe")(i =>
                s"Column at index $i held no value; its target type is not Maybe"
            )
        )(name =>
            columnIndex.fold(s"Column '$name' held no value; its target type is not Maybe")(i =>
                s"Column '$name' (index $i) held no value; its target type is not Maybe"
            )
        )
    )

object SqlDecodeColumnAbsentException:
    def apply(columnIndex: Int)(using Frame): SqlDecodeColumnAbsentException =
        new SqlDecodeColumnAbsentException(Present(columnIndex), Absent)
    def apply(columnName: String)(using Frame): SqlDecodeColumnAbsentException =
        new SqlDecodeColumnAbsentException(Absent, Present(columnName))
    def apply(columnIndex: Int, columnName: String)(using Frame): SqlDecodeColumnAbsentException =
        new SqlDecodeColumnAbsentException(Present(columnIndex), Present(columnName))
end SqlDecodeColumnAbsentException

/** A column index is out of bounds for the current row. */
final case class SqlDecodeColumnOutOfBoundsException(columnIndex: Int, columnCount: Int)(using Frame)
    extends SqlDecodeException(
        s"Column index $columnIndex out of bounds (row has $columnCount columns)"
    )

/** A column's text rendering does not parse as the type the codec expected (`typeName` names it, `text` is the offending value). */
final case class SqlDecodeInvalidTextException(typeName: String, text: String)(using Frame)
    extends SqlDecodeException(s"invalid $typeName text '$text'")

/** A column looked up by name is not present in the row. */
final case class SqlDecodeColumnNotFoundException(columnName: String)(using Frame)
    extends SqlDecodeException(
        s"Column '$columnName' not found in row"
    )

/** No codec on the active backend covers the column's wire type token. */
final case class SqlDecodeUnknownTypeException(dialect: Idiom.Id, typeToken: String)(using Frame)
    extends SqlDecodeException(
        s"No codec registered on dialect '${dialect.value}' for type token '$typeToken'"
    )

/** A backend codec decoded the column to a runtime type the target schema does not accept. */
final case class SqlDecodeCodecMismatchException(
    dialect: Idiom.Id,
    typeToken: String,
    decodedClass: String,
    expectedClass: String
)(using Frame)
    extends SqlDecodeException(
        s"Codec on dialect '${dialect.value}' for type token '$typeToken' produced a $decodedClass, expected $expectedClass"
    )

/** A custom column decoder raised an exception while converting the wire value.
  *
  * `columnIndex` is present where the decode was driven one column at a time and the caller held the index, which is what
  * [[kyo.SqlRow.Codec.catchingColumn]] carries. It is absent where the failure was caught around a whole row rather than at one column, which
  * is what [[kyo.SqlRow.Codec.catching]] does for anything a reader threw that is not itself a decode leaf.
  *
  * @param columnIndex
  *   the column the decoder was reading, absent for a failure caught around the whole row
  * @param cause
  *   what the decoder raised
  */
final case class SqlDecodeColumnDecodeException(columnIndex: Maybe[Int], cause: String | Throwable)(using Frame)
    extends SqlDecodeException(
        columnIndex.fold("Column decode failed")(i => s"Column decode failed at index $i"),
        cause
    )

object SqlDecodeColumnDecodeException:
    def apply(columnIndex: Int, cause: String | Throwable)(using Frame): SqlDecodeColumnDecodeException =
        SqlDecodeColumnDecodeException(Present(columnIndex), cause)
end SqlDecodeColumnDecodeException

/** An array element carried no value and the target Scala element type is not a [[kyo.Maybe]].
  *
  * `arrayIndex` is the element's own position in the array, which every array reader tracks, so it names which element was empty rather
  * than the column the array came from. A `Chunk[Maybe[A]]` decodes that element as [[kyo.Maybe.Absent]] and raises nothing.
  *
  * @param scalaType
  *   the Scala element type the schema asked for
  * @param arrayIndex
  *   the empty element's zero-based position in the array
  */
final case class SqlDecodeArrayAbsentElementException(scalaType: String, arrayIndex: Int)(using Frame)
    extends SqlDecodeException(
        s"Array element at index $arrayIndex held no value and is not decodable as $scalaType"
    )

/** A map-shaped column held an entry with no value and the target Scala value type is not a [[kyo.Maybe]].
  *
  * The map sibling of [[kyo.SqlDecodeArrayAbsentElementException]], raised by a PostgreSQL `hstore` whose entry carries no value. A
  * `Map[String, Maybe[A]]` decodes that entry as [[kyo.Maybe.Absent]] and raises nothing.
  *
  * @param scalaType
  *   the Scala value type the schema asked for
  */
final case class SqlDecodeMapAbsentValueException(scalaType: String)(using Frame)
    extends SqlDecodeException(
        s"Map entry held no value and is not decodable as $scalaType"
    )

/** A range column carried the empty range, and the target Scala type spells a range as a pair of bounds.
  *
  * The empty range holds no values, which two bounds cannot say: an unbounded pair reads back as the range of every value, the exact
  * opposite. No dialect is named because none is at fault: the value the server sent is well formed, and it is the decode target that
  * has no representation for it.
  *
  * @param scalaType
  *   the Scala type the schema asked for
  */
final case class SqlDecodeEmptyRangeException(scalaType: String)(using Frame)
    extends SqlDecodeException(
        s"The empty range is not decodable as $scalaType: a pair of bounds cannot express a range that holds no values"
    )

/** A PostgreSQL array wire representation is malformed. */
final case class SqlDecodeArrayFormatException(ndim: Int, length: Int, offset: Int)(using Frame)
    extends SqlDecodeException(
        s"Malformed array wire format: ndim=$ndim, length=$length, offset=$offset"
    )

/** A PostgreSQL hstore wire representation is malformed. */
final case class SqlDecodeHstoreFormatException(count: Int, keyLength: Int, valueLength: Int, offset: Int)(using Frame)
    extends SqlDecodeException(
        s"Malformed hstore wire format: count=$count, keyLength=$keyLength, valueLength=$valueLength, offset=$offset"
    )

/** A JSON payload could not be decoded to the target Scala type. */
final case class SqlDecodeJsonException(jsonPreview: String, cause: String | Throwable)(using Frame)
    extends SqlDecodeException(
        s"JSON decode failed. Preview: $jsonPreview",
        cause
    )

/** A sum-type discriminator label does not match any known case. */
final case class SqlDecodeSumTypeUnknownLabelException(label: String, validLabels: Chunk[String])(using Frame)
    extends SqlDecodeException(
        s"Unknown sum-type label '$label'. Valid labels: ${validLabels.mkString(", ")}"
    )

/** A CHAR column was empty but the target Scala type is Char (which cannot represent absence).
  *
  * `columnIndex` is absent when the value was buffered away from its row and no index is available;
  * [[kyo.SqlDecodeMultiCharacterForCharException]] is the too-many-characters half of the same contract and spells absence the same way.
  *
  * @param columnIndex
  *   the column the value came from, absent for a buffered column
  */
final case class SqlDecodeEmptyStringForCharException(columnIndex: Maybe[Int])(using Frame)
    extends SqlDecodeException(
        columnIndex.fold("An empty value cannot decode as Char")(i => s"Column at index $i is empty; cannot decode as Char")
    )

/** A numeric wire value could not be decoded (NaN, infinity, or unparseable text). */
final case class SqlDecodeNumericException(text: String, subtype: SqlDecodeNumericException.Subtype)(using Frame)
    extends SqlDecodeException(
        subtype match
            case SqlDecodeNumericException.Subtype.NaN    => s"Numeric value is NaN: '$text'"
            case SqlDecodeNumericException.Subtype.PosInf => s"Numeric value is +Infinity: '$text'"
            case SqlDecodeNumericException.Subtype.NegInf => s"Numeric value is -Infinity: '$text'"
            case SqlDecodeNumericException.Subtype.Parse  => s"Cannot parse numeric value: '$text'"
    )

object SqlDecodeNumericException:
    /** Distinguishes the four numeric-decode failure modes. */
    enum Subtype derives CanEqual:
        case NaN
        case PosInf
        case NegInf
        case Parse
    end Subtype
end SqlDecodeNumericException

/** A PostgreSQL `bytea` text-format payload is malformed.
  *
  * Raised by the two `bytea_output` renderings a text-format result can carry. Under `hex` the payload is two hex digits per byte, so an odd
  * digit count and a non-hex digit are both malformed. Under `escape` a backslash introduces either a doubled backslash or a three-digit
  * octal value, so a backslash followed by anything else is malformed.
  *
  * @param payloadLength
  *   the length of the payload the value came in, in characters for `hex` and in bytes for `escape`
  * @param subtype
  *   which of the three malformed shapes the payload has
  */
final case class SqlDecodeByteaException(payloadLength: Int, subtype: SqlDecodeByteaException.Subtype)(using Frame)
    extends SqlDecodeException(
        subtype match
            case SqlDecodeByteaException.Subtype.OddHexLength =>
                s"bytea hex payload of $payloadLength characters has an odd digit count"
            case SqlDecodeByteaException.Subtype.HexDigit =>
                s"bytea hex payload of $payloadLength characters holds a character that is not a hex digit"
            case SqlDecodeByteaException.Subtype.EscapeSequence =>
                s"bytea escape payload of $payloadLength bytes holds a backslash that starts no escape sequence"
    )

object SqlDecodeByteaException:
    /** Distinguishes the three malformed `bytea` text payloads. */
    enum Subtype derives CanEqual:
        case OddHexLength
        case HexDigit
        case EscapeSequence
    end Subtype
end SqlDecodeByteaException

/** A column's wire value cannot be carried by the Scala type the schema asked for.
  *
  * Raised where widening is impossible rather than merely lossy: an integer whose magnitude exceeds the target type's range, a fractional
  * value read into an integral type, or a wire width for which the target type has no exact reading. The alternative at each of those points
  * is a silent wrap or a truncation, which is the failure this leaf exists to replace.
  *
  * The approximate targets (`Float`, `Double`) do not raise this: rounding is what those types are for, so a value that does not land on a
  * representable float is rounded rather than rejected.
  *
  * @param scalaType
  *   the Scala type the schema asked for
  * @param wireValue
  *   the value the column carried, rendered
  * @param wireDescription
  *   the wire representation it was read from
  */
final case class SqlDecodeValueRangeException(scalaType: String, wireValue: String, wireDescription: String)(using Frame)
    extends SqlDecodeException(
        s"Wire value $wireValue ($wireDescription) does not fit $scalaType"
    )

/** A calendar-interval field could not be decoded, on either backend: a PostgreSQL `INTERVAL` column or MySQL's ISO-8601 text encoding of
  * one.
  */
final case class SqlDecodeIntervalException(field: String, value: String)(using Frame)
    extends SqlDecodeException(
        s"Cannot decode interval field '$field' from value '$value'"
    )

/** A column value read as a `Char` holds more than one character.
  *
  * A `Char` carries exactly one, so taking the first character of a longer value would drop the rest silently. `columnIndex` is absent when
  * the value was buffered away from its row and no index is available; [[kyo.SqlDecodeEmptyStringForCharException]] is the empty-value half
  * of the same contract.
  *
  * @param columnIndex
  *   the column the value came from, absent for a buffered column
  * @param characterCount
  *   the number of characters the value holds
  */
final case class SqlDecodeMultiCharacterForCharException(columnIndex: Maybe[Int], characterCount: Int)(using Frame)
    extends SqlDecodeException(
        columnIndex.fold(s"A $characterCount-character value cannot decode as Char")(i =>
            s"Column at index $i holds $characterCount characters; cannot decode as Char"
        )
    )

/** A UUID wire value is not 16 bytes. */
final case class SqlDecodeUuidException(byteSize: Int)(using Frame)
    extends SqlDecodeException(
        s"UUID must be exactly 16 bytes, received $byteSize"
    )

/** An Instant wire value could not be decoded. */
final case class SqlDecodeInstantException(text: String, cause: Throwable)(using Frame)
    extends SqlDecodeException(
        s"Cannot decode Instant from '$text'",
        cause
    )

/** A Duration wire value could not be decoded. */
final case class SqlDecodeDurationException(text: String, cause: Throwable)(using Frame)
    extends SqlDecodeException(
        s"Cannot decode Duration from '$text'",
        cause
    )

/** A MySQL temporal struct (DATE, DATETIME, TIME) is malformed. */
final case class SqlDecodeTemporalException(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    structLength: Int
)(using Frame)
    extends SqlDecodeException(
        s"Cannot decode temporal value: year=$year, month=$month, day=$day, hour=$hour, minute=$minute, second=$second, structLength=$structLength"
    )

/** A SCRAM authentication field could not be parsed. */
final case class SqlDecodeScramFormatException(field: String, text: String)(using Frame)
    extends SqlDecodeException(
        s"Malformed SCRAM field '$field': $text"
    )

/** An authentication-response sub-type byte is not recognised. */
final case class SqlDecodeUnknownAuthTypeException(subType: Int)(using Frame)
    extends SqlDecodeException(
        s"Unknown Authentication subType $subType"
    )

/** A backend-message type byte is not recognised. */
final case class SqlDecodeUnknownBackendMessageException(messageByte: Byte)(using Frame)
    extends SqlDecodeException(
        s"Unknown backend message byte 0x${(messageByte & 0xff).toHexString.toUpperCase} ('${messageByte.toChar}')"
    )

/** A wire message's declared length or shape does not match the protocol. */
final case class SqlDecodeProtocolFormatException(messageByte: Byte, position: Int)(using Frame)
    extends SqlDecodeException(
        s"Malformed message 0x${(messageByte & 0xff).toHexString.toUpperCase} at position $position"
    )

// =============================================================================
// SqlUnsupportedException family
// =============================================================================

/** Operations the current backend does not implement. */
sealed abstract class SqlUnsupportedException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlException(msg, cause)

/** A DSL feature is not available on the dialect the statement is being rendered for.
  *
  * Covers both shapes of unavailability. `Present(requiredVersion)` means the dialect implements `feature` from that version onwards and
  * the connected server predates it; `Absent` means the dialect does not implement `feature` at any version. `serverVersion` is `Absent`
  * when the render target carries no captured handshake version, in which case the message omits it rather than reporting a fabricated one.
  */
final case class SqlUnsupportedDialectFeatureException(
    feature: String,
    dialect: Idiom.Id,
    requiredVersion: Maybe[Idiom.ServerVersion],
    serverVersion: Maybe[Idiom.ServerVersion]
)(using Frame)
    extends SqlUnsupportedException(
        SqlUnsupportedDialectFeatureException.format(feature, dialect, requiredVersion, serverVersion)
    )

object SqlUnsupportedDialectFeatureException:
    private[kyo] def format(
        feature: String,
        dialect: Idiom.Id,
        requiredVersion: Maybe[Idiom.ServerVersion],
        serverVersion: Maybe[Idiom.ServerVersion]
    ): String =
        requiredVersion match
            case Absent =>
                val connected = serverVersion match
                    case Present(v) => s" (server version ${v.show})"
                    case Absent     => ""
                s"Feature '$feature' is not supported on ${dialect.value}$connected"
            case Present(required) =>
                val connected = serverVersion match
                    case Present(v) => s"; connected server is ${v.show}"
                    case Absent     => ""
                s"Feature '$feature' requires ${dialect.value} server version ${required.show}$connected"
end SqlUnsupportedDialectFeatureException

/** A dialect-only type was used against a backend that does not own it.
  *
  * `dialect` is the dialect the type belongs to, `activeDialect` the one the statement ran against.
  */
final case class SqlUnsupportedTypeOnBackendException(dialect: Idiom.Id, typeName: String, activeDialect: Idiom.Id)(using Frame)
    extends SqlUnsupportedException(
        s"Type '$typeName' belongs to dialect '${dialect.value}' and is not supported on the active '${activeDialect.value}' backend"
    )

/** A value whose [[SqlSchema]] spans more than one SQL column was used as one element of a composite value.
  *
  * A composite payload addresses each of its elements by byte length: a range carries `int32 length + element bytes` per bound, an array
  * carries the same per element. One element is therefore one column's worth of bytes, and a schema built by [[SqlSchema.ofMulti]] (or
  * derived for a case class) writes `columnCount` of them.
  *
  * Not a dialect limitation: no flavor gives one composite element room for more than one column, so this is refused rather than reported
  * against whichever backend the write was asked for. The bind-position case has no runtime sibling: a bind takes a
  * [[kyo.SqlSchema.Column]], so a multi-column value there is a compile error.
  *
  * @param scalaType
  *   the value's Scala type, as its schema's tag reports it
  * @param columnCount
  *   how many columns the schema wrote
  */
final case class SqlUnsupportedMultiColumnElementException(scalaType: String, columnCount: Int)(using Frame)
    extends SqlUnsupportedException(
        s"Value of type '$scalaType' occupies $columnCount SQL columns, and one element of a composite value holds exactly one. " +
            s"Multi-column schemas (SqlSchema.ofMulti, and a derived case class) can be read as a whole result row, " +
            s"but cannot be an element of a composite value."
    )

/** An absent value was used as one element of a composite value.
  *
  * A composite payload addresses each element by byte length and carries no per-element absence flag, so no byte sequence in it means "this
  * element is not there". An absent range bound is spelled as unbounded instead, which is a property of the range rather than of a bound's
  * value.
  *
  * Not a dialect limitation: no flavor's composite wire form has an absent element, so this is refused rather than reported against
  * whichever backend the write was asked for. The read-side counterpart is [[kyo.SqlDecodeArrayAbsentElementException]].
  *
  * @param scalaType
  *   the value's Scala type, as its schema's tag reports it
  */
final case class SqlUnsupportedAbsentElementException(scalaType: String)(using Frame)
    extends SqlUnsupportedException(
        s"A value of type '$scalaType' is absent, and an element of a composite value cannot be. " +
            s"A composite payload addresses each element by its byte length and carries no absence flag, " +
            s"so an absent element has no spelling."
    )

/** A composite element was requested in a wire format the backend cannot produce.
  *
  * A composite payload's byte layout fixes its elements' format: a binary range holds binary elements. A backend whose encoder for
  * `scalaType` produces the other format refuses here rather than emitting bytes the payload's reader will misparse.
  *
  * Unlike the other two element leaves, this one names a dialect, because it genuinely is a backend limitation rather than a property every
  * flavor shares.
  *
  * @param scalaType
  *   the value's Scala type, as its schema's tag reports it
  * @param format
  *   the wire format the composite demanded
  * @param dialect
  *   the backend that cannot produce it
  */
final case class SqlUnsupportedElementFormatException(scalaType: String, format: SqlCodec.Format, dialect: Idiom.Id)(using Frame)
    extends SqlUnsupportedException(
        s"A value of type '$scalaType' cannot be encoded in the $format wire format on the " +
            s"'${dialect.value}' backend, and the composite payload it is an element of requires that format."
    )

/** A user-declared custom type name is not registered in the built-in map or in the backend configuration's custom type names. */
final case class SqlUnsupportedCustomTypeException(typeName: String)(using Frame)
    extends SqlUnsupportedException(
        s"Custom type '$typeName' is not registered. Declare '$typeName' in the backend configuration's custom type names before pool initialization."
    )

// =============================================================================
// Open bridges for out-of-tree backends
// =============================================================================
//
// The five category classes are `sealed`, so direct subclassing is restricted to this file. These open abstract
// bridges are the lawful extension point below each: an out-of-tree backend leaf extends the bridge for its failure
// mode, and category matching stays exhaustive because every bridge is an instance of exactly one category. They are
// classes rather than traits because a leaf passes constructor arguments through them to the category.

/** The open bridge below [[SqlConnectionException]] for a backend defined outside this file: an out-of-tree connection-fault leaf extends
  * this instead of the sealed category. It is itself a [[SqlConnectionException]], so exhaustive category matching still holds.
  */
abstract class SqlConnectionBackendException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlConnectionException(msg, cause)

/** The open bridge below [[SqlRequestException]] for an out-of-tree backend. See [[SqlConnectionBackendException]]. */
abstract class SqlRequestBackendException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlRequestException(msg, cause)

/** The open bridge below [[SqlServerException]] for an out-of-tree backend. The server-error field definitions stay abstract, so a leaf
  * supplies `sqlState` and the rest. See [[SqlConnectionBackendException]].
  */
abstract class SqlServerBackendException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlServerException(msg, cause)

/** The open bridge below [[SqlDecodeException]] for an out-of-tree backend. See [[SqlConnectionBackendException]]. */
abstract class SqlDecodeBackendException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlDecodeException(msg, cause)

/** The open bridge below [[SqlUnsupportedException]] for an out-of-tree backend. See [[SqlConnectionBackendException]]. */
abstract class SqlUnsupportedBackendException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlUnsupportedException(msg, cause)
