package kyo.internal.mysql

import kyo.Chunk
import kyo.Maybe
import kyo.Span

// --- Frontend Messages (client → server) ---
//
// MySQL 8.x wire protocol frontend messages.
// Each message starts with a 1-byte command code (except HandshakeResponse41 and
// SslRequest, which are sent during the connection phase with no command byte).
//
// Reference: MySQL Internals Manual — Connection Phase Packets + Text Protocol

/** Base trait for all messages sent from the client to the server. */
sealed trait FrontendMessage derives CanEqual

/** Handshake response sent by the client after receiving [[HandshakeV10]].
  *
  * Wire (protocol 4.1): LE uint32(capabilities) | LE uint32(maxPacketSize) | uint8(charset) | filler[23]
  *   | NUL-string(username) | lenenc-bytes(authResponse) | NUL-string(database)? | NUL-string(authPlugin)?
  *
  * Reference: MySQL Internals — Protocol::HandshakeResponse41
  *
  * @param capabilities
  *   combined capability flags (see [[Capabilities]])
  * @param maxPacket
  *   maximum packet size the client can receive (typically 16MB)
  * @param charset
  *   client charset number (e.g. 33 = utf8mb3, 255 = utf8mb4)
  * @param username
  *   the connecting user name
  * @param authResponse
  *   authentication plugin response bytes (e.g. scrambled password)
  * @param database
  *   optional initial schema name
  * @param authPlugin
  *   optional authentication plugin name (e.g. "caching_sha2_password")
  */
final case class HandshakeResponse41(
    capabilities: Long,
    maxPacket: Long,
    charset: Int,
    username: String,
    authResponse: Span[Byte],
    database: Maybe[String],
    authPlugin: Maybe[String]
) extends FrontendMessage

/** SSL/TLS upgrade request — a short HandshakeResponse41 without auth fields.
  *
  * Wire: LE uint32(capabilities) | LE uint32(maxPacketSize) | uint8(charset) | filler[23]
  *
  * After this is sent the client wraps the socket in TLS and then sends a full [[HandshakeResponse41]].
  *
  * Reference: MySQL Internals — Protocol::SSLRequest
  *
  * @param capabilities
  *   capability flags (must include CLIENT_SSL)
  * @param maxPacket
  *   maximum packet size
  * @param charset
  *   client charset number
  */
final case class SslRequest(
    capabilities: Long,
    maxPacket: Long,
    charset: Int
) extends FrontendMessage

/** Auth-switch response — raw bytes sent by the client during an auth plugin switch round.
  *
  * Wire: raw bytes (the auth plugin response)
  *
  * Reference: MySQL Internals — Protocol::AuthSwitchResponse
  *
  * @param data
  *   the plugin response bytes
  */
final case class AuthSwitchResponse(data: Span[Byte]) extends FrontendMessage

/** Auth-more-data response (client side) — raw bytes sent during caching_sha2_password full-auth.
  *
  * Used when the server sends AuthMoreData with status 0x04 (full auth required), indicating the client should send its password RSA-OAEP
  * encrypted (or plaintext over TLS).
  *
  * Wire: raw bytes (encrypted or plaintext password)
  *
  * Reference: MySQL Internals — caching_sha2_password Authentication
  *
  * @param data
  *   the auth continuation bytes
  */
final case class AuthMoreDataResponse(data: Span[Byte]) extends FrontendMessage

/** Simple (text-protocol) query.
  *
  * Wire: 0x03 | UTF-8 SQL bytes
  *
  * Reference: MySQL Internals — COM_QUERY
  *
  * @param sql
  *   the SQL text to execute
  */
final case class ComQuery(sql: String) extends FrontendMessage

/** Prepare a SQL statement for repeated execution.
  *
  * Wire: 0x16 | UTF-8 SQL bytes
  *
  * Reference: MySQL Internals — COM_STMT_PREPARE
  *
  * @param sql
  *   the parameterized SQL text (using `?` placeholders)
  */
final case class ComStmtPrepare(sql: String) extends FrontendMessage

/** Execute a previously-prepared statement with binary-protocol parameters.
  *
  * Wire: 0x17 | LE uint32(stmtId) | uint8(flags) | LE uint32(iterationCount=1)
  *         | null-bitmap[(numParams+7)/8 bytes] | uint8(newParamsBound)
  *         | [uint16(paramType) uint8(unsigned)]* (when newParamsBound=1)
  *         | param-values*
  *
  * Reference: MySQL Internals — COM_STMT_EXECUTE
  *
  * @param stmtId
  *   the statement ID returned by [[StmtPrepareOk]]
  * @param flags
  *   cursor type flags (0 = no cursor)
  * @param params
  *   parameter values; [[Maybe.Absent]] encodes as NULL
  * @param paramTypes
  *   parallel array of (mysqlType, isUnsigned) pairs for each param (only sent when newParamsBound=1)
  * @param newParamsBound
  *   1 on first execute (or when types change); 0 on re-execute with same types
  */
final case class ComStmtExecute(
    stmtId: Int,
    flags: Int,
    params: Chunk[Maybe[Span[Byte]]],
    paramTypes: Chunk[(Int, Int)],
    newParamsBound: Int
) extends FrontendMessage

/** Close (deallocate) a prepared statement on the server.
  *
  * Wire: 0x19 | LE uint32(stmtId)
  *
  * No response is expected.
  *
  * Reference: MySQL Internals — COM_STMT_CLOSE
  *
  * @param stmtId
  *   the statement ID to close
  */
final case class ComStmtClose(stmtId: Int) extends FrontendMessage

/** Reset a prepared statement's cursor and pending data.
  *
  * Wire: 0x1A | LE uint32(stmtId)
  *
  * Server responds with OK.
  *
  * Reference: MySQL Internals — COM_STMT_RESET
  *
  * @param stmtId
  *   the statement ID to reset
  */
final case class ComStmtReset(stmtId: Int) extends FrontendMessage

/** Reset the connection to its initial state (clear session state without re-handshaking).
  *
  * Wire: 0x1F
  *
  * Server responds with OK.
  *
  * Reference: MySQL Internals — COM_RESET_CONNECTION
  */
case object ComResetConnection extends FrontendMessage

/** Ping the server to keep the connection alive.
  *
  * Wire: 0x0E
  *
  * Server responds with OK.
  *
  * Reference: MySQL Internals — COM_PING
  */
case object ComPing extends FrontendMessage

/** Gracefully terminate the connection.
  *
  * Wire: 0x01
  *
  * No response is expected; the server closes the TCP connection.
  *
  * Reference: MySQL Internals — COM_QUIT
  */
case object ComQuit extends FrontendMessage

// --- Backend Messages (server → client) ---
//
// MySQL 8.x wire protocol backend messages.
// The first byte of the payload discriminates the message type in most contexts:
//   0x00 → OK packet (or first byte of StmtPrepareOk)
//   0xFF → ERR packet
//   0xFE → EOF packet (len<9) or OK packet (len>=7) or AuthSwitchRequest in auth context
//   0x01 → AuthMoreData (server side)
//   Other → various (column count, row data, etc.)
//
// When CLIENT_DEPRECATE_EOF is negotiated (kyo-sql default), EOF packets are replaced
// by OK packets everywhere (first byte 0x00 or 0xFE with len>=7).
//
// Reference: MySQL Internals Manual — Generic Response Packets + Result Set Packets

/** Base trait for all messages sent from the server to the client. */
sealed trait BackendMessage derives CanEqual

/** Initial server greeting sent immediately after TCP connection.
  *
  * Wire: uint8(protocolVersion=10) | NUL-string(serverVersion) | LE uint32(threadId)
  *         | bytes[8](authPluginDataPart1) | 0x00 | LE uint16(capFlagsLow)
  *         | uint8(charset) | LE uint16(statusFlags) | LE uint16(capFlagsHigh)
  *         | uint8(authPluginDataLen or 0) | filler[10]
  *         | bytes[max(13, authPluginDataLen-8)](authPluginDataPart2)
  *         | NUL-string(authPluginName)?
  *
  * The full 4-byte capability flags are assembled as: capFlagsLow | (capFlagsHigh << 16)
  *
  * Reference: MySQL Internals — Protocol::Handshake
  *
  * @param protocolVersion
  *   always 10 for MySQL 8.x
  * @param serverVersion
  *   human-readable server version string (e.g. "8.0.34")
  * @param threadId
  *   connection ID
  * @param authPluginData
  *   combined auth data (20 bytes for caching_sha2_password: 8 from part1 + 12 from part2)
  * @param capabilityFlags
  *   combined 32-bit capability flags
  * @param charset
  *   server default charset number
  * @param statusFlags
  *   server status flags
  * @param authPluginName
  *   authentication plugin name (e.g. "caching_sha2_password")
  */
final case class HandshakeV10(
    protocolVersion: Int,
    serverVersion: String,
    threadId: Long,
    authPluginData: Span[Byte],
    capabilityFlags: Long,
    charset: Int,
    statusFlags: Int,
    authPluginName: String
) extends BackendMessage

/** Success response from the server.
  *
  * First byte is 0x00 (or 0xFE if CLIENT_DEPRECATE_EOF and length >= 7).
  *
  * Wire: 0x00 | lenenc-int(affectedRows) | lenenc-int(lastInsertId) | LE uint16(statusFlags)
  *         | LE uint16(warnings) | string<EOF>(info)?
  *
  * Reference: MySQL Internals — Protocol::OK_Packet
  *
  * @param affectedRows
  *   number of rows affected by the command
  * @param lastInsertId
  *   the auto-increment ID of the last inserted row (0 if not applicable)
  * @param statusFlags
  *   server status flags
  * @param warnings
  *   number of warnings generated
  * @param info
  *   optional human-readable information string
  * @param sessionStateInfo
  *   optional session-state-change data (present if CLIENT_SESSION_TRACK and SERVER_SESSION_STATE_CHANGED)
  */
final case class OkPacket(
    affectedRows: Long,
    lastInsertId: Long,
    statusFlags: Short,
    warnings: Short,
    info: Maybe[String],
    sessionStateInfo: Maybe[String]
) extends BackendMessage

/** Error response from the server.
  *
  * First byte 0xFF.
  *
  * Wire: 0xFF | LE uint16(errorCode) | '#' | bytes[5](sqlState) | string<EOF>(errorMessage)
  *
  * Reference: MySQL Internals — Protocol::ERR_Packet
  *
  * @param errorCode
  *   MySQL error code (e.g. 1045 = ER_ACCESS_DENIED_ERROR)
  * @param sqlState
  *   5-character SQLSTATE (e.g. "42000")
  * @param errorMessage
  *   human-readable error message
  */
final case class ErrPacket(
    errorCode: Int,
    sqlState: String,
    errorMessage: String
) extends BackendMessage

/** EOF (end-of-data) marker.
  *
  * First byte 0xFE, total payload length < 9 bytes.
  *
  * Only sent when CLIENT_DEPRECATE_EOF is NOT negotiated. kyo-sql always negotiates CLIENT_DEPRECATE_EOF, so EOF packets are replaced by OK
  * packets. This type exists for completeness and to handle older MySQL servers that do not advertise the capability.
  *
  * Wire: 0xFE | LE uint16(warnings) | LE uint16(statusFlags)
  *
  * Reference: MySQL Internals — Protocol::EOF_Packet
  *
  * @param warnings
  *   number of warnings
  * @param statusFlags
  *   server status flags
  */
final case class EofPacket(warnings: Short, statusFlags: Short) extends BackendMessage

/** Auth-switch request from the server — switch to a different auth plugin.
  *
  * First byte 0xFE in an auth context, followed by a NUL-terminated plugin name and raw auth data.
  *
  * Disambiguation from EOF: check first byte is 0xFE AND payload length >= 2 AND the auth context flag is set.
  *
  * Wire: 0xFE | NUL-string(pluginName) | bytes<EOF>(pluginData)
  *
  * Reference: MySQL Internals — Protocol::AuthSwitchRequest
  *
  * @param pluginName
  *   the requested plugin (e.g. "caching_sha2_password")
  * @param pluginData
  *   the initial auth challenge data for the new plugin
  */
final case class AuthSwitchRequest(pluginName: String, pluginData: Span[Byte]) extends BackendMessage

/** Additional auth data from the server (server side of caching_sha2_password multi-round auth).
  *
  * First byte 0x01.
  *
  * Notable sub-values of the data byte:
  *   - 0x03 = fast-path success (caching_sha2_password: cached credentials accepted)
  *   - 0x04 = full auth required (client must send plaintext password or RSA-OAEP encrypted)
  *
  * Wire: 0x01 | bytes<EOF>(data)
  *
  * Reference: MySQL Internals — caching_sha2_password Authentication
  *
  * @param data
  *   the auth continuation bytes from the server
  */
final case class AuthMoreData(data: Span[Byte]) extends BackendMessage

/** Full description of a result-set column.
  *
  * Sent as part of a result-set preamble before any row data.
  *
  * Wire (protocol 4.1): lenenc-string(catalog) | lenenc-string(schema) | lenenc-string(table) | lenenc-string(orgTable)
  *   | lenenc-string(name) | lenenc-string(orgName) | 0x0C(fixed)
  *   | LE uint16(charset) | LE uint32(columnLength) | uint8(columnType) | LE uint16(flags)
  *   | uint8(decimals) | 0x00 0x00 (filler)
  *
  * Reference: MySQL Internals — Protocol::ColumnDefinition41
  *
  * @param catalog
  *   catalog name (always "def" in MySQL 8.x)
  * @param schema
  *   schema (database) name
  * @param table
  *   alias table name
  * @param orgTable
  *   original table name
  * @param name
  *   alias column name
  * @param orgName
  *   original column name
  * @param charset
  *   column charset/collation number
  * @param columnLength
  *   maximum display width
  * @param columnType
  *   MySQL type code (e.g. 0x03 = LONG, 0xFC = BLOB)
  * @param flags
  *   column flags (e.g. NOT_NULL, UNSIGNED, AUTO_INCREMENT)
  * @param decimals
  *   number of decimal places (for numeric types)
  */
final case class ColumnDefinition41(
    catalog: String,
    schema: String,
    table: String,
    orgTable: String,
    name: String,
    orgName: String,
    charset: Int,
    columnLength: Long,
    columnType: Int,
    flags: Int,
    decimals: Int
) extends BackendMessage

/** A single row in text-protocol result sets.
  *
  * Wire: [lenenc-string(value) | 0xFB(NULL)]* one per column
  *
  * Each column value is either a length-encoded string (even for numeric types, which are ASCII-rendered) or 0xFB for NULL.
  *
  * Reference: MySQL Internals — Text Resultset Row
  *
  * @param values
  *   one entry per column; [[Maybe.Absent]] represents SQL NULL (0xFB marker)
  */
final case class ResultsetRow(values: Chunk[Maybe[Span[Byte]]]) extends BackendMessage

/** A single row in binary-protocol result sets (from COM_STMT_EXECUTE).
  *
  * Wire: 0x00 (packet header) | null-bitmap[ceil((numCols+7+2)/8) bytes] | [column-values]*
  *
  * The null-bitmap has a 2-bit offset: bit position for column N is bit (N+2) of the bitmap. The bitmap is followed by the binary-encoded
  * column values for non-null columns in column order.
  *
  * Reference: MySQL Internals — Binary Resultset Row
  *
  * @param nullBitmap
  *   the raw null-bitmap bytes (2-bit offset already accounted for; caller checks bit (N+2))
  * @param values
  *   one entry per column; [[Maybe.Absent]] for NULL columns (matching the null-bitmap); [[Maybe.Present]] holds the raw binary bytes for
  *   non-null columns (type-specific decoding is done by the type layer)
  */
final case class BinaryResultsetRow(
    nullBitmap: Span[Byte],
    values: Chunk[Maybe[Span[Byte]]]
) extends BackendMessage

/** Response to COM_STMT_PREPARE indicating successful preparation.
  *
  * Wire: 0x00 | LE uint32(stmtId) | LE uint16(numColumns) | LE uint16(numParams) | 0x00(reserved) | LE uint16(warningCount)
  *
  * After this packet, the server sends `numParams` ColumnDefinition41 packets (if numParams > 0), then an EOF/OK, then `numColumns`
  * ColumnDefinition41 packets (if numColumns > 0), then another EOF/OK.
  *
  * Reference: MySQL Internals — COM_STMT_PREPARE_OK
  *
  * @param stmtId
  *   the server-assigned statement ID
  * @param numColumns
  *   number of result columns
  * @param numParams
  *   number of `?` parameters
  * @param warnings
  *   number of warnings
  */
final case class StmtPrepareOk(
    stmtId: Int,
    numColumns: Short,
    numParams: Short,
    warnings: Short
) extends BackendMessage
