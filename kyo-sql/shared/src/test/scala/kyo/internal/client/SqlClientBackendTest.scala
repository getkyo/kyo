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

end SqlClientBackendTest
