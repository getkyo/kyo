package kyo.internal.postgres

import kyo.Chunk
import kyo.Maybe
import kyo.Span

// --- Frontend Messages ---
//
// All frontend messages that have a type byte use the framing:
//   Byte1(type) | Int32(length including itself) | payload
//
// StartupMessage, SSLRequest, and CancelRequest are exceptions: they have no
// leading type byte and use their own fixed framing.
//
// Reference: PostgreSQL documentation §55.7 "Message Formats"

/** Base trait for all messages sent from the client (frontend) to the server (backend). */
sealed trait FrontendMessage derives CanEqual

/** Startup message sent as the very first message on a new connection (no type byte).
  *
  * Wire: Int32(length) Int32(196608 = protocol v3.0) [cstring(key) cstring(value)]* 0x00
  *
  * @param parameters
  *   key-value pairs such as `("user", "alice")`, `("database", "mydb")`, etc.
  */
final case class StartupMessage(parameters: Chunk[(String, String)]) extends FrontendMessage

/** SSL upgrade request (no type byte).
  *
  * Wire: Int32(8) Int32(80877103) Total: exactly 8 bytes.
  */
case object SSLRequest extends FrontendMessage

/** Cancel request, sent on a new TCP connection (no type byte).
  *
  * Wire: Int32(16) Int32(80877102) Int32(processId) Int32(secretKey)
  *
  * @param processId
  *   the backend process id from [[BackendKeyData]]
  * @param secretKey
  *   the secret key from [[BackendKeyData]]
  */
final case class CancelRequest(processId: Int, secretKey: Int) extends FrontendMessage

/** Password authentication response.
  *
  * Wire: 'p' | Int32(len) | cstring(password)
  *
  * @param password
  *   cleartext or MD5-hashed password (caller decides which form)
  */
final case class PasswordMessage(password: String) extends FrontendMessage

/** First message in a SASL authentication exchange (e.g. SCRAM-SHA-256).
  *
  * Wire: 'p' | Int32(len) | cstring(mechanism) | Int32(clientFirstMessageLen) | bytes
  *
  * @param mechanism
  *   the chosen SASL mechanism name, e.g. `"SCRAM-SHA-256"`
  * @param clientFirstMessage
  *   raw bytes of the SASL client-first-message
  */
final case class SASLInitialResponse(mechanism: String, clientFirstMessage: Span[Byte]) extends FrontendMessage

/** Subsequent SASL response message (client-final-message in SCRAM).
  *
  * Wire: 'p' | Int32(len) | bytes
  *
  * @param data
  *   raw bytes of the SASL client-final-message
  */
final case class SASLResponse(data: Span[Byte]) extends FrontendMessage

/** Simple query.
  *
  * Wire: 'Q' | Int32(len) | cstring(sql)
  *
  * @param sql
  *   the SQL text to execute (may contain multiple semicolon-separated statements)
  */
final case class Query(sql: String) extends FrontendMessage

/** Parse a named or anonymous prepared statement.
  *
  * Wire: 'P' | Int32(len) | cstring(stmtName) | cstring(sql) | Int16(numParams) | Int32(OID)*
  *
  * @param stmtName
  *   empty string for anonymous statement
  * @param sql
  *   the parameterised SQL text
  * @param paramTypes
  *   OIDs of parameter types; 0 means "infer"
  */
final case class Parse(stmtName: String, sql: String, paramTypes: Chunk[Int]) extends FrontendMessage

/** Bind parameters to a portal.
  *
  * Wire: 'B' | Int32(len) | cstring(portal) | cstring(stmt) | Int16(numFmts) | Int16*
  *       | Int16(numParams) | [Int32(len) | bytes | Int32(-1)]*
  *       | Int16(numResultFmts) | Int16*
  *
  * @param portalName
  *   empty string for anonymous portal
  * @param stmtName
  *   name of the prepared statement to bind
  * @param paramFormats
  *   format codes for each parameter (0 = text, 1 = binary)
  * @param paramValues
  *   encoded parameter values; [[Maybe.Absent]] encodes as SQL NULL
  * @param resultFormats
  *   format codes for each result column (0 = text, 1 = binary)
  */
final case class Bind(
    portalName: String,
    stmtName: String,
    paramFormats: Chunk[Short],
    paramValues: Chunk[Maybe[Span[Byte]]],
    resultFormats: Chunk[Short]
) extends FrontendMessage

/** Describe a prepared statement or portal.
  *
  * Wire: 'D' | Int32(len) | Byte1(target) | cstring(name)
  *
  * @param target
  *   `'S'` for a prepared statement, `'P'` for a portal
  * @param name
  *   the name (empty string for anonymous)
  */
final case class Describe(target: Byte, name: String) extends FrontendMessage

/** Execute a portal.
  *
  * Wire: 'E' | Int32(len) | cstring(portal) | Int32(maxRows)
  *
  * @param portalName
  *   empty string for the unnamed portal
  * @param maxRows
  *   maximum rows to return; 0 means unlimited
  */
final case class Execute(portalName: String, maxRows: Int) extends FrontendMessage

/** Sync, marks end of an extended-query message group; server responds with [[ReadyForQuery]].
  *
  * Wire: 'S' | Int32(4)
  */
case object Sync extends FrontendMessage

/** Flush, forces the server to emit any pending output without ending the implicit transaction.
  *
  * Wire: 'H' | Int32(4)
  */
case object Flush extends FrontendMessage

/** Close a prepared statement or portal.
  *
  * Wire: 'C' | Int32(len) | Byte1(target) | cstring(name)
  *
  * @param target
  *   `'S'` for a prepared statement, `'P'` for a portal
  * @param name
  *   the name to close
  */
final case class Close(target: Byte, name: String) extends FrontendMessage

/** Terminate the connection gracefully.
  *
  * Wire: 'X' | Int32(4)
  */
case object Terminate extends FrontendMessage

// --- Backend Messages ---
//
// All backend messages use the framing:
//   Byte1(type) | Int32(length including itself) | payload
//
// Reference: PostgreSQL documentation §55.7 "Message Formats"

/** Base trait for all messages sent from the server (backend) to the client (frontend). */
sealed trait BackendMessage derives CanEqual

// --- Authentication ---

/** Discriminator for the `Authentication` message family.
  *
  * All variants share the type byte `'R'` and are distinguished by an Int32 sub-type code at bytes 5-8.
  */
sealed trait AuthenticationKind derives CanEqual

object AuthenticationKind:
    /** Auth sub-type 0: authentication succeeded. */
    case object Ok extends AuthenticationKind

    /** Auth sub-type 2: Kerberos V5 (historic; rarely seen). */
    case object KerberosV5 extends AuthenticationKind

    /** Auth sub-type 3: cleartext password required. */
    case object CleartextPassword extends AuthenticationKind

    /** Auth sub-type 5: MD5 password required.
      *
      * @param salt
      *   4-byte salt to incorporate in the MD5 hash
      */
    final case class MD5Password(salt: Span[Byte]) extends AuthenticationKind

    /** Auth sub-type 6: SCM credential required. */
    case object SCMCredential extends AuthenticationKind

    /** Auth sub-type 7: GSSAPI required. */
    case object GSS extends AuthenticationKind

    /** Auth sub-type 8: GSSAPI continuation data.
      *
      * @param data
      *   raw GSSAPI/SSPI data
      */
    final case class GSSContinue(data: Span[Byte]) extends AuthenticationKind

    /** Auth sub-type 9: SSPI required. */
    case object SSPI extends AuthenticationKind

    /** Auth sub-type 10: SASL authentication required.
      *
      * @param mechanisms
      *   list of supported mechanism names (e.g. `["SCRAM-SHA-256", "SCRAM-SHA-256-PLUS"]`)
      */
    final case class SASL(mechanisms: Chunk[String]) extends AuthenticationKind

    /** Auth sub-type 11: SASL challenge (server-first-message).
      *
      * @param data
      *   raw server-first-message bytes
      */
    final case class SASLContinue(data: Span[Byte]) extends AuthenticationKind

    /** Auth sub-type 12: SASL final message (server-final-message).
      *
      * @param data
      *   raw server-final-message bytes
      */
    final case class SASLFinal(data: Span[Byte]) extends AuthenticationKind
end AuthenticationKind

/** Authentication request from the server.
  *
  * Wire: 'R' | Int32(len) | Int32(subType) | [variant-specific data]
  *
  * @param kind
  *   the authentication variant requested
  */
final case class Authentication(kind: AuthenticationKind) extends BackendMessage

/** Server parameter setting (sent during startup and on `SET` commands).
  *
  * Wire: 'S' | Int32(len) | cstring(name) | cstring(value)
  *
  * @param name
  *   parameter name (e.g. `"server_version"`, `"client_encoding"`)
  * @param value
  *   parameter value
  */
final case class ParameterStatus(name: String, value: String) extends BackendMessage

/** Backend process ID and secret key (used for [[CancelRequest]]).
  *
  * Wire: 'K' | Int32(12) | Int32(processId) | Int32(secretKey)
  *
  * @param processId
  *   backend process ID
  * @param secretKey
  *   secret key for this connection
  */
final case class BackendKeyData(processId: Int, secretKey: Int) extends BackendMessage

/** Server is ready to accept a new command.
  *
  * Wire: 'Z' | Int32(5) | Byte1(status)
  *
  * @param status
  *   `'I'` = idle, `'T'` = in transaction block, `'E'` = in failed transaction
  */
final case class ReadyForQuery(status: Byte) extends BackendMessage

/** Describes the columns of a query result.
  *
  * Wire: 'T' | Int32(len) | Int16(numFields) | [FieldDescription]*
  *
  * @param fields
  *   one [[FieldDescription]] per column
  */
final case class RowDescription(fields: Chunk[FieldDescription]) extends BackendMessage

/** Metadata for a single result column.
  *
  * @param name
  *   column name
  * @param tableOid
  *   OID of the table the column belongs to (0 if not a table column)
  * @param columnAttr
  *   attribute number within the table (0 if not a table column)
  * @param dataType
  *   OID of the column data type
  * @param dataTypeSize
  *   size of the type in bytes (negative if variable-length)
  * @param typeModifier
  *   type-specific modifier (e.g. `varchar(n)` length); -1 if none
  * @param formatCode
  *   0 = text, 1 = binary
  */
final case class FieldDescription(
    name: String,
    tableOid: Int,
    columnAttr: Short,
    dataType: Int,
    dataTypeSize: Short,
    typeModifier: Int,
    formatCode: Short
) derives CanEqual

/** A single row of query results.
  *
  * Wire: 'D' | Int32(len) | Int16(numCols) | [Int32(colLen) | bytes | Int32(-1)]*
  *
  * @param values
  *   one entry per column; [[Maybe.Absent]] represents a SQL NULL
  */
final case class DataRow(values: Chunk[Maybe[Span[Byte]]]) extends BackendMessage

/** Command execution complete.
  *
  * Wire: 'C' | Int32(len) | cstring(tag)
  *
  * @param tag
  *   command tag, e.g. `"SELECT 5"`, `"INSERT 0 1"`, `"UPDATE 3"`
  */
final case class CommandComplete(tag: String) extends BackendMessage

/** Empty query response (server received an empty query string).
  *
  * Wire: 'I' | Int32(4)
  */
case object EmptyQueryResponse extends BackendMessage

/** Error response from the server.
  *
  * Wire: 'E' | Int32(len) | [Byte1(fieldType) | cstring(value)]* | 0x00
  *
  * @param fields
  *   list of `(fieldType, value)` pairs; common field types include `'S'` severity, `'C'` SQLSTATE, `'M'` message, etc.
  */
final case class ErrorResponse(fields: Chunk[(Byte, String)]) extends BackendMessage

/** Informational notice from the server (same wire shape as [[ErrorResponse]] but non-fatal).
  *
  * Wire: 'N' | Int32(len) | [Byte1(fieldType) | cstring(value)]* | 0x00
  *
  * @param fields
  *   same structure as [[ErrorResponse.fields]]
  */
final case class NoticeResponse(fields: Chunk[(Byte, String)]) extends BackendMessage

/** Asynchronous notification (from LISTEN/NOTIFY).
  *
  * Wire: 'A' | Int32(len) | Int32(pid) | cstring(channel) | cstring(payload)
  *
  * @param processId
  *   PID of the notifying backend
  * @param channel
  *   channel name
  * @param payload
  *   notification payload string
  */
final case class NotificationResponse(processId: Int, channel: String, payload: String) extends BackendMessage

/** Prepared statement parsed successfully.
  *
  * Wire: '1' | Int32(4)
  */
case object ParseComplete extends BackendMessage

/** Portal bound successfully.
  *
  * Wire: '2' | Int32(4)
  */
case object BindComplete extends BackendMessage

/** Named statement or portal closed successfully.
  *
  * Wire: '3' | Int32(4)
  */
case object CloseComplete extends BackendMessage

/** Describes the parameter types of a prepared statement.
  *
  * Wire: 't' | Int32(len) | Int16(numParams) | Int32(OID)*
  *
  * @param types
  *   OIDs of the parameter types
  */
final case class ParameterDescription(types: Chunk[Int]) extends BackendMessage

/** The query returned no data columns (e.g. a non-SELECT statement was described).
  *
  * Wire: 'n' | Int32(4)
  */
case object NoData extends BackendMessage

/** The portal was suspended because [[Execute]] reached its `maxRows` limit.
  *
  * Wire: 's' | Int32(4)
  */
case object PortalSuspended extends BackendMessage

// --- COPY protocol messages ---
//
// The COPY sub-protocol is initiated by a simple-query `COPY ... FROM STDIN` or
// `COPY ... TO STDOUT`. The direction determines which CopyResponse the server sends
// first. All wire formats are documented in §55.7 "Message Formats".

/** Indicates that the server is ready to receive COPY data from the client (COPY FROM STDIN).
  *
  * Wire: 'G' | Int32(len) | Int8(overallFormat) | Int16(numCols) | Int16(colFormat)*
  *
  * @param overallFormat
  *   0 = text, 1 = binary
  * @param columnFormats
  *   per-column format codes (0 = text, 1 = binary); may be empty for text-format COPY
  */
final case class CopyInResponse(overallFormat: Byte, columnFormats: Chunk[Short]) extends BackendMessage

/** Indicates that the server will send COPY data to the client (COPY TO STDOUT).
  *
  * Wire: 'H' | Int32(len) | Int8(overallFormat) | Int16(numCols) | Int16(colFormat)*
  *
  * @param overallFormat
  *   0 = text, 1 = binary
  * @param columnFormats
  *   per-column format codes (0 = text, 1 = binary)
  */
final case class CopyOutResponse(overallFormat: Byte, columnFormats: Chunk[Short]) extends BackendMessage

/** A chunk of data in a COPY data stream.
  *
  * Wire (backend → client): 'd' | Int32(len) | data Wire (client → backend): 'd' | Int32(len) | data
  *
  * @param data
  *   the raw COPY data payload (one or more rows in text/binary format)
  */
final case class CopyData(data: Span[Byte]) extends BackendMessage

/** Signals that the COPY data stream is complete (sent by either side).
  *
  * Wire: 'c' | Int32(4)
  */
case object CopyDone extends BackendMessage

/** Sent by the client to abort a COPY FROM STDIN operation.
  *
  * Wire: 'f' | Int32(len) | cstring(errorMessage)
  *
  * @param errorMessage
  *   human-readable reason for the failure
  */
final case class CopyFail(errorMessage: String) extends FrontendMessage
