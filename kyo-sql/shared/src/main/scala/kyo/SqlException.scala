package kyo

import kyo.Frame
import kyo.KyoException
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import scala.annotation.nowarn

/** Sealed hierarchy of errors produced by kyo-sql operations.
  *
  * Five variants cover every failure category:
  *   - [[SqlException.Connection]], transport/pool-level failures (connect refused, pool exhausted, timeout acquiring a connection)
  *   - [[SqlException.Request]], client-side request preparation errors (bad SQL, missing encoder, serialization failure)
  *   - [[SqlException.Server]], error response received from the database server (carries full PG ErrorResponse / MySQL ERR fields)
  *   - [[SqlException.Decode]], row-level decoding failure after data was received successfully
  *   - [[SqlException.Unsupported]], operation not supported by this backend's SqlReader/SqlWriter implementation
  *
  * ==Decode vs Unsupported, the key distinction==
  *
  * These two variants are easy to confuse. The rule of thumb:
  *
  *   - `Decode` is thrown by the '''driver''' at row-decoding time, ''after'' the server has already returned data. It means a specific
  *     column's wire value could not be converted to the requested Scala type (wrong OID, unexpected null, truncated bytes, unparseable
  *     text representation, etc.). The server did nothing wrong; the type mapping is the issue. Recovery options: check the [[SqlSchema]]
  *     derivation for the query result type, add a custom decoder via [[SqlSchema.withDecoder]], or widen the result type to `String` /
  *     `Span[Byte]` to capture the raw wire value for diagnosis.
  *   - `Unsupported` is thrown by the '''driver''' when a structural operation (array element read, map entry read, nested object
  *     traversal) is called on a backend implementation that does not implement that operation. This is a type-system / schema gap, not a
  *     data error. Recovery: re-derive the schema without the unsupported structural type, or use [[SqlSchema.withDecoder]] to supply a
  *     fully custom decoder that handles the wire format directly.
  *
  * Both `Decode` and `Unsupported` propagate as `Abort[SqlException]` at the `Query.run` call site. Handle them by pattern matching on the
  * `SqlException` subtypes inside an `Abort.recover` or at the `Async.runAndBlock` boundary:
  *
  * {{{
  * query.run.map(rows => ???).pipe(Abort.recover[SqlException] {
  *   case e: SqlException.Decode      => // schema mismatch, check SqlSchema derivation
  *   case e: SqlException.Unsupported => // missing structural support, widen or customise schema
  *   case e: SqlException.Server      => // database rejected the query
  *   case e: SqlException.Connection  => // pool/transport failure, retry or surface to the caller
  *   case e: SqlException.Request     => // bad SQL or missing encoder, programming error
  * })
  * }}}
  */
sealed abstract class SqlException(message: => String, cause: String | Throwable = "")(using Frame) extends KyoException(message, cause)
    derives CanEqual

object SqlException:

    given Render[SqlException] = Render.from(_.getMessage)

    // --- Connection, transport or connection-pool failure ---

    /** Transport or connection-pool failure.
      *
      * Examples: TCP connection refused, pool exhausted, acquire timeout, TLS handshake failure.
      *
      * @param message
      *   human-readable description
      * @param frame
      *   call-site frame captured at the raise site
      */
    final case class Connection(
        message: String,
        @nowarn("msg=cannot override val parameter given instance frame") override val frame: Frame
    ) extends SqlException(message)(using frame) derives CanEqual

    object Connection:

        /** Server responded 'N' to SSLRequest, it does not support TLS. */
        def TlsNotSupported(host: String, port: Int)(using frame: Frame): Connection =
            Connection(s"Server at $host:$port does not support TLS (responded 'N' to SSLRequest)", frame)

        /** TLS handshake failed after the server accepted the SSLRequest. */
        def TlsHandshakeFailed(host: String, port: Int, cause: Throwable)(using frame: Frame): Connection =
            Connection(s"TLS handshake with $host:$port failed: ${cause.getMessage}", frame)

    end Connection

    // --- Request, client-side request failure ---

    /** Client-side request failure.
      *
      * Examples: missing encoder for a custom type, parameter serialization error, protocol state mismatch.
      *
      * @param message
      *   human-readable description
      * @param sqlText
      *   the SQL query text (truncated to 4096 chars if longer), if available at the raise site
      * @param frame
      *   call-site frame captured at the raise site
      */
    final case class Request(
        message: String,
        sqlText: Maybe[String],
        @nowarn("msg=cannot override val parameter given instance frame") override val frame: Frame
    ) extends SqlException(
            message +
                sqlText.fold("")(s => s"\n  SQL: $s")
        )(using frame) derives CanEqual

    // --- Server, error response from the database ---

    /** Error response received from the database server.
      *
      * Fields mirror the PostgreSQL ErrorResponse / NoticeResponse message format (§52.2 of the PG docs). MySQL maps its own error fields
      * into the same structure: sqlState from the ERR packet, severity inferred from error-class, message from the error message,
      * detail/hint/position absent (MySQL doesn't send them), extra carries MySQL-specific error code as `"code" -> "1062"`.
      *
      * @param sqlState
      *   five-character SQLSTATE code (e.g. "23505" for unique-violation)
      * @param severity
      *   localised severity string from the server (e.g. "ERROR", "FATAL", "WARNING")
      * @param message
      *   primary human-readable error message
      * @param detail
      *   optional secondary detail message
      * @param hint
      *   optional hint for resolving the error
      * @param position
      *   optional cursor position (1-based character index) into the query string
      * @param extra
      *   unmodelled ErrorResponse fields keyed by PG field identifier name: "schema", "table", "column", "datatype", "constraint", "file",
      *   "line", "routine", and any future additions
      * @param sqlText
      *   the SQL query text (truncated to 4096 chars if longer), if available at the raise site
      * @param paramCount
      *   number of bound parameters in the query (0 if none or not applicable)
      * @param connectionId
      *   server-assigned connection/thread ID (Present for MySQL; Present with processId for Postgres; Absent if unavailable)
      * @param frame
      *   call-site frame captured at the raise site
      */
    final case class Server(
        sqlState: String,
        severity: String,
        message: String,
        detail: Maybe[String],
        hint: Maybe[String],
        position: Maybe[Int],
        extra: Map[String, String],
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long],
        @nowarn("msg=cannot override val parameter given instance frame") override val frame: Frame
    ) extends SqlException(
            s"[$sqlState] $severity: $message" +
                detail.fold("")(d => s"\n  Detail: $d") +
                hint.fold("")(h => s"\n  Hint: $h") +
                sqlText.fold("")(s => s"\n  SQL: $s") +
                (if paramCount > 0 then s"\n  Params: $paramCount" else "") +
                connectionId.fold("")(id => s"\n  ConnectionId: $id")
        )(using frame) derives CanEqual

    object Server:
        /** Convenience constructor for tests and simple cases with no optional fields. */
        def apply(sqlState: String, severity: String, message: String)(using frame: Frame): Server =
            Server(sqlState, severity, message, Absent, Absent, Absent, Map.empty, Absent, 0, Absent, frame)
    end Server

    // --- Decode, row-decoding failure ---

    /** Row-level decoding failure: the server returned data, but the driver could not convert a column's wire value to the expected Scala
      * type.
      *
      * ==Who throws it==
      * The kyo-sql '''driver''' (not the database server). Raised inside `SqlSchema#readPostgres` / `SqlSchema#readMysql` while iterating
      * over the result rows returned by `executeBoundQuery` or `streamPgQuery`.
      *
      * ==When it is thrown==
      * Any of the following column-level conditions:
      *   - The PostgreSQL OID or MySQL column type does not match the Scala type expected by the [[SqlSchema]] decoder.
      *   - A non-nullable column contains a SQL `NULL` but the target type does not wrap `Maybe`.
      *   - The text/binary representation of the value cannot be parsed (e.g. `"not-a-number"` decoded as `Int`).
      *   - A custom decoder supplied via [[SqlSchema.withDecoder]] threw an exception.
      *
      * ==What the user should do==
      *   1. Inspect `message`, it includes the column name or zero-based column index when available.
      *   2. Verify that the [[SqlSchema]] derivation for the result type matches the actual database schema.
      *   3. For nullable columns, wrap the field type in `Maybe[T]` (or `Option[T]`, both are supported).
      *   4. For non-standard types, supply a custom decoder via [[SqlSchema.withDecoder]] or widen the field to `String` / `Span[Byte]` to
      *      capture the raw wire value for further diagnosis.
      *
      * Contrast with [[SqlException.Unsupported]]: `Decode` means the value arrived but could not be converted; `Unsupported` means the
      * structural operation (e.g. array traversal, map entry read) is not implemented by this backend.
      *
      * @param message
      *   human-readable description including column name or position when available
      * @param sqlText
      *   the SQL query text (truncated to 4096 chars if longer), if available at the raise site
      * @param frame
      *   call-site frame captured at the raise site
      */
    final case class Decode(
        message: String,
        sqlText: Maybe[String],
        @nowarn("msg=cannot override val parameter given instance frame") override val frame: Frame
    ) extends SqlException(
            message +
                sqlText.fold("")(s => s"\n  SQL: $s")
        )(using frame) derives CanEqual

    // --- Unsupported, operation not supported by this backend ---

    /** A structural read or write operation is not implemented by this backend's [[kyo.internal.SqlReader]] / [[kyo.internal.SqlWriter]].
      *
      * ==Who throws it==
      * The kyo-sql '''driver'''. Raised by the synchronous `SqlReader` / `SqlWriter` implementations (e.g. `PostgresRowReader`,
      * `MysqlRowReader`) when a schema-derived decoder calls a structural method, `arrayStart`, `arrayElement`, `mapStart`, `mapEntry`,
      * that the backend does not yet implement for a particular wire format or type combination.
      *
      * ==When it is thrown==
      * Encountered during row decoding when the [[SqlSchema]] for the result type includes a structurally complex field (array of custom
      * types, nested record, hstore, JSONB object) and the backend does not provide a matching codec path.
      *
      * ==What the user should do==
      *   1. Inspect `message`, it identifies the operation and, where possible, the column type.
      *   2. Simplify the result type: flatten nested structures or decode complex columns as `String` / `Span[Byte]` and parse them
      *      manually.
      *   3. Supply a fully custom column decoder via [[SqlSchema.withDecoder]] that handles the wire format directly, bypassing the
      *      structural codec path.
      *   4. If the backend should support this operation, file a kyo-sql issue, `Unsupported` on a standard type is a driver bug.
      *
      * Contrast with [[SqlException.Decode]]: `Unsupported` means the structural operation itself is missing; `Decode` means the operation
      * exists but the wire value could not be converted to the target type.
      *
      * @param message
      *   human-readable description of the unsupported operation
      * @param frame
      *   call-site frame captured at the raise site
      */
    final case class Unsupported(
        message: String,
        @nowarn("msg=cannot override val parameter given instance frame") override val frame: Frame
    ) extends SqlException(message)(using frame) derives CanEqual

end SqlException
