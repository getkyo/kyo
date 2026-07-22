package kyo.internal.client

import kyo.*
import kyo.Test

/** Unit tests for [[SqlClientBackend.isProtocolFatal]].
  *
  * Verifies that the predicate correctly classifies each protocol-fatal / non-fatal exception category. These leaves exercise an internal
  * method (`private[kyo]`) and therefore live in `kyo.internal.client` rather than the public test package.
  *
  * All leaves are unit tests, no container, no network, no Scope required.
  */
class SqlClientBackendTest extends kyo.Test:

    "stream raising SqlDecodeException discards connection (does not return to pool)" in {
        // SqlDecodeException signals wire-format desynchronisation: the framing is out of
        // sync with the server. Returning such a connection to the pool would corrupt the
        // next borrower. isProtocolFatal must return true for Decode.
        val decodeError = SqlDecodeColumnDecodeException(0, new Exception("expected Int32 but got NUL"))
        assert(
            SqlClientBackend.isProtocolFatal(decodeError),
            "isProtocolFatal(Decode) must be true, Decode means wire desync, connection is poisoned"
        )
    }

    "stream raising user-level Unsupported returns connection to pool" in {
        // SqlUnsupportedException signals that an operation is not supported by the
        // backend implementation, the TCP wire is perfectly intact. The connection must
        // be returned to the pool, not discarded.
        val unsupportedError = SqlUnsupportedStructuralReadException("arrayStart")
        assert(
            !SqlClientBackend.isProtocolFatal(unsupportedError),
            "isProtocolFatal(Unsupported) must be false, Unsupported does not poison the connection"
        )
    }

    "stream raising SqlServerException with sqlState 08006 discards connection" in {
        // SQLSTATE class 08 = connection exception (RFC 5789 / ISO SQL). The server has
        // indicated that the connection itself is invalid. The connection must be discarded.
        // 08006 = connection_failure (the server terminated the connection).
        val serverError = SqlServerException(
            sqlState = "08006",
            severity = "FATAL",
            message = "connection_failure",
            detail = Absent,
            hint = Absent,
            position = Absent,
            extra = Map.empty,
            sqlText = Absent,
            paramCount = 0,
            connectionId = Absent
        )
        assert(
            SqlClientBackend.isProtocolFatal(serverError),
            "isProtocolFatal(Server sqlState=08006) must be true, SQLSTATE class 08 = connection exception"
        )
    }

    "mysqlRowToRow preserves the source row's wire Format (regression: extended-protocol binary rows were being wrapped as Format.Text on the tx / cancel / pipeline paths, causing MysqlRowReader to decode binary bytes as text)" in {
        import kyo.internal.mysql.ColumnDefinition41
        import kyo.internal.mysql.MysqlRow
        import kyo.internal.postgres.types.Format

        val column = ColumnDefinition41(
            catalog = "",
            schema = "",
            table = "",
            orgTable = "",
            name = "n",
            orgName = "n",
            charset = 0,
            columnLength = 0L,
            columnType = 0,
            flags = 0,
            decimals = 0
        )
        val values = Chunk(Maybe(Span[Byte](0.toByte)))

        val binaryRow = new MysqlRow(values, Chunk(column), Format.Binary)
        assert(
            SqlClientBackend.mysqlRowToRow(binaryRow).format == Format.Binary,
            "mysqlRowToRow must forward the Binary format from an extended-protocol MysqlRow"
        )

        val textRow = new MysqlRow(values, Chunk(column), Format.Text)
        assert(
            SqlClientBackend.mysqlRowToRow(textRow).format == Format.Text,
            "mysqlRowToRow must forward the Text format from a simple-query MysqlRow"
        )
    }

end SqlClientBackendTest
