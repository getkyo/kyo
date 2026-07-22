package kyo

import kyo.Chunk
import kyo.Frame
import kyo.KyoException
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.internal.SqlBackend

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

/** Property marker: the failure is transient and the caller may safely retry the operation. */
sealed trait SqlRetryable

/** Property marker: the failure is a database integrity violation (unique, foreign-key, check, or exclusion constraint). */
sealed trait SqlIntegrityViolation

/** Property marker: the failure is an authentication rejection (bad credentials, missing TLS, unsupported mechanism). */
sealed trait SqlAuthenticationFailure

// =============================================================================
// SqlConnectionException family
// =============================================================================

/** Transport-level and connection-pool failures. */
sealed abstract class SqlConnectionException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlException(msg, cause)

/** Cannot parse a database URL into scheme, host, port, database, and options. */
final case class SqlConnectionUrlParseException(rawUrl: String, scheme: String)(using Frame)
    extends SqlConnectionException(
        s"Cannot parse database URL for scheme '$scheme'. Input: $rawUrl"
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

/** The configured sslMode value is not supported by kyo-sql. */
final case class SqlConnectionTlsConfigException(sslMode: String)(using Frame)
    extends SqlConnectionException(
        s"Unsupported TLS configuration: sslMode='$sslMode'"
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

/** Cancelling an in-flight query exceeded the configured cancel timeout. */
final case class SqlConnectionCancelTimeoutException(cancelTimeout: Duration)(using Frame)
    extends SqlConnectionException(
        s"Cancel handshake did not complete within ${cancelTimeout.show}"
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

/** The server requested an authentication mechanism the driver does not implement. */
final case class SqlConnectionUnsupportedAuthMethodException(mechanism: String)(using Frame)
    extends SqlConnectionException(
        s"Unsupported authentication mechanism: $mechanism"
    ) with SqlAuthenticationFailure

/** The MySQL server requested an authentication plugin the driver does not implement. */
final case class SqlConnectionUnsupportedAuthPluginException(pluginName: String)(using Frame)
    extends SqlConnectionException(
        s"Unsupported MySQL authentication plugin: $pluginName"
    ) with SqlAuthenticationFailure

/** SCRAM authentication exchange failed. */
final case class SqlConnectionScramFailedException(reason: String)(using Frame)
    extends SqlConnectionException(
        s"SCRAM authentication failed: $reason"
    ) with SqlAuthenticationFailure

/** The server rejected authentication with an ErrorResponse. */
final case class SqlConnectionAuthenticationFailedException(sqlState: String, errorCode: Int, serverMessage: String)(using Frame)
    extends SqlConnectionException(
        s"Authentication rejected by server [$sqlState] (errorCode=$errorCode): $serverMessage"
    ) with SqlAuthenticationFailure

/** MySQL asked for a clear-text password over a non-TLS connection, which the driver refuses to send. */
final case class SqlConnectionClearPasswordRequiresTlsException()(using Frame)
    extends SqlConnectionException(
        "Clear-text password authentication requires TLS; refusing to send credentials over an unencrypted connection"
    ) with SqlAuthenticationFailure

/** MySQL caching_sha2_password returned an empty auth-more-data payload, which the driver cannot handle. */
final case class SqlConnectionCachingSha2EmptyPayloadException()(using Frame)
    extends SqlConnectionException(
        "MySQL caching_sha2_password returned an empty auth-more-data payload"
    ) with SqlAuthenticationFailure

/** An internal sentinel value escaped its intended scope. */
final case class SqlConnectionUnexpectedSentinelException(context: String)(using Frame)
    extends SqlConnectionException(
        s"Unexpected sentinel value in $context"
    )

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

/** An operation was invoked on the wrong backend (e.g. a Postgres-only call on a MySQL client). */
final case class SqlConnectionBackendMismatchException(requiredDriver: SqlBackend, activeDriver: SqlBackend, operation: String)(using Frame)
    extends SqlConnectionException(
        s"Operation '$operation' requires ${requiredDriver} driver, but active driver is ${activeDriver}"
    )

/** No active kyo-sql client is available in the current scope. */
final case class SqlConnectionNoActiveClientException()(using Frame)
    extends SqlConnectionException(
        "No active SqlClient in scope"
    )

// =============================================================================
// SqlRequestException family
// =============================================================================

/** Client-side request-preparation failures. */
sealed abstract class SqlRequestException(msg: => String, cause: String | Throwable = "")(using Frame)
    extends SqlException(msg, cause)

/** A MySQL transaction operation was invoked outside of the connection-scoped API required for MySQL. */
final case class SqlRequestMysqlTxRequiresConnectionApiException(operation: String)(using Frame)
    extends SqlRequestException(
        s"MySQL '$operation' requires the connection-scoped transaction API (SqlClient.Mysql.withConnection)"
    )

/** The MySQL server responded with a LOAD DATA LOCAL INFILE request during a regular query. Callers must use the dedicated
  * `loadLocalInfile` API to run LOAD DATA LOCAL INFILE statements, not `query` / `execute`.
  */
final case class SqlRequestMysqlLocalInfileRequiresLoadApiException()(using Frame)
    extends SqlRequestException(
        "MySQL server responded with LOAD DATA LOCAL INFILE for a regular query. Use SqlClient.Mysql.loadLocalInfile instead."
    )

/** RSA-OAEP encryption of the MySQL sha256_password payload failed. */
final case class SqlRequestRsaOaepException(position: String, tag: String, cause: String | Throwable)(using Frame)
    extends SqlRequestException(
        s"RSA-OAEP failed at $position (tag=$tag)",
        cause
    )

/** MySQL GET_LOCK returned NULL or 0, indicating the advisory lock could not be acquired within the timeout. */
final case class SqlRequestMysqlGetLockException(name: String, timeoutSeconds: Int, lockKey: String)(using Frame)
    extends SqlRequestException(
        s"MySQL GET_LOCK('$lockKey', $timeoutSeconds) failed for advisory lock '$name'"
    )

/** A Duration exceeded the day range MySQL's TIME type can represent. */
final case class SqlRequestDurationOverflowException(totalDays: Long)(using Frame)
    extends SqlRequestException(
        s"Duration overflow: $totalDays total days exceeds MySQL TIME range"
    )

// =============================================================================
// SqlServerException family
// =============================================================================

/** Error responses received from the database server.
  *
  * Fields mirror the PostgreSQL ErrorResponse / NoticeResponse message format (§52.2 of the PG docs). MySQL maps its ERR fields into the
  * same structure: sqlState from the ERR packet, severity inferred from error-class, message from the error message, detail/hint/position
  * absent (MySQL does not send them), extra carries the MySQL-specific error code as `"code" -> "1062"`.
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
    def apply(sqlState: String, severity: String, message: String)(using Frame): SqlServerException =
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

/** A non-nullable column contained SQL NULL. */
final case class SqlDecodeColumnNullException(columnIndex: Int, columnName: Maybe[String])(using Frame)
    extends SqlDecodeException(
        columnName.fold(s"Non-nullable column at index $columnIndex was SQL NULL")(name =>
            s"Non-nullable column '$name' (index $columnIndex) was SQL NULL"
        )
    )

object SqlDecodeColumnNullException:
    def apply(columnIndex: Int)(using Frame): SqlDecodeColumnNullException =
        SqlDecodeColumnNullException(columnIndex, Absent)
    def apply(columnName: String)(using Frame): SqlDecodeColumnNullException =
        SqlDecodeColumnNullException(-1, Present(columnName))
    def apply(columnIndex: Int, columnName: String)(using Frame): SqlDecodeColumnNullException =
        SqlDecodeColumnNullException(columnIndex, Present(columnName))
end SqlDecodeColumnNullException

/** A column index is out of bounds for the current row. */
final case class SqlDecodeColumnOutOfBoundsException(columnIndex: Int, columnCount: Int)(using Frame)
    extends SqlDecodeException(
        s"Column index $columnIndex out of bounds (row has $columnCount columns)"
    )

/** A column looked up by name is not present in the row. */
final case class SqlDecodeColumnNotFoundException(columnName: String)(using Frame)
    extends SqlDecodeException(
        s"Column '$columnName' not found in row"
    )

/** A custom column decoder raised an exception while converting the wire value. */
final case class SqlDecodeColumnDecodeException(columnIndex: Int, cause: String | Throwable)(using Frame)
    extends SqlDecodeException(
        s"Column decode failed at index $columnIndex",
        cause
    )

/** A SQL array element was NULL and the target Scala element type is non-nullable. */
final case class SqlDecodeArrayNullElementException(scalaType: String, arrayIndex: Int)(using Frame)
    extends SqlDecodeException(
        s"NULL array element at index $arrayIndex is not decodable as $scalaType"
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

/** A CHAR column was empty but the target Scala type is Char (which cannot represent absence). */
final case class SqlDecodeEmptyStringForCharException(columnIndex: Int)(using Frame)
    extends SqlDecodeException(
        s"Column at index $columnIndex is empty; cannot decode as Char"
    )

/** Distinguishes the four numeric-decode failure modes. */
enum NumericSubtype derives CanEqual:
    case NaN
    case PosInf
    case NegInf
    case Parse
end NumericSubtype

/** A numeric wire value could not be decoded (NaN, infinity, or unparseable text). */
final case class SqlDecodeNumericException(text: String, subtype: NumericSubtype)(using Frame)
    extends SqlDecodeException(
        subtype match
            case NumericSubtype.NaN    => s"Numeric value is NaN: '$text'"
            case NumericSubtype.PosInf => s"Numeric value is +Infinity: '$text'"
            case NumericSubtype.NegInf => s"Numeric value is -Infinity: '$text'"
            case NumericSubtype.Parse  => s"Cannot parse numeric value: '$text'"
    )

/** A PostgreSQL interval field could not be decoded. */
final case class SqlDecodeIntervalException(field: String, value: String)(using Frame)
    extends SqlDecodeException(
        s"Cannot decode interval field '$field' from value '$value'"
    )

/** A PostgreSQL inet / cidr wire value has an unexpected family or address length. */
final case class SqlDecodeInetException(typeName: String, family: Int, addressLength: Int, byteSize: Int)(using Frame)
    extends SqlDecodeException(
        s"Cannot decode $typeName: family=$family, addressLength=$addressLength, byteSize=$byteSize"
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

/** A DSL feature requires a newer MySQL server version than the connected server reports. */
final case class SqlUnsupportedMysqlVersionFeatureException(feature: String, requiredVersion: String, serverVersion: String)(using Frame)
    extends SqlUnsupportedException(
        s"MySQL feature '$feature' requires server version $requiredVersion; connected server is $serverVersion"
    )

/** RETURNING is a Postgres-only clause; MySQL does not implement it. */
final case class SqlUnsupportedReturningOnMysqlException()(using Frame)
    extends SqlUnsupportedException(
        "RETURNING clause is not supported on MySQL"
    )

/** ON CONFLICT ... WHERE is a Postgres-only clause; MySQL's ON DUPLICATE KEY UPDATE has no WHERE. */
final case class SqlUnsupportedUpsertWhereClauseOnMysqlException()(using Frame)
    extends SqlUnsupportedException(
        "ON CONFLICT ... WHERE is not supported on MySQL"
    )

/** A structural SqlReader operation is not implemented by the buffered reader. */
final case class SqlUnsupportedStructuralReadException(methodName: String)(using Frame)
    extends SqlUnsupportedException(
        s"Structural read operation '$methodName' is not supported by the buffered SqlReader"
    )

/** A user-declared custom type name is not registered in the built-in map or in [[kyo.SqlClientConfig.typeNames]]. */
final case class SqlUnsupportedCustomTypeException(typeName: String)(using Frame)
    extends SqlUnsupportedException(
        s"Custom type '$typeName' is not registered. Add '$typeName' to SqlClientConfig.typeNames before pool initialization."
    )
