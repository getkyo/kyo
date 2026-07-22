package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.Span
import kyo.SqlException
import kyo.Test
import kyo.internal.postgres.Authentication
import kyo.internal.postgres.AuthenticationKind
import kyo.internal.postgres.PostgresBufferReader

/** Wire-format unit test for [[AuthenticationUnmarshaller]].
  *
  * Verifies that the unmarshaller correctly decodes the AuthenticationOk variant from a 4-byte body (Int32(0)). The reader receives the
  * message body only, the type byte ('R') and 4-byte length field have already been consumed by the protocol dispatcher.
  */
class AuthenticationUnmarshallerTest extends kyo.Test:

    "PostgresChannel receive decodes AuthenticationOk from bytes, mock channel" in {
        // Wire bytes for AuthenticationOk: 'R' | Int32(8) | Int32(0)
        // The unmarshaller receives only the body, after the type byte and 4-byte length field.
        // Body = Int32(0) = the auth type code for Ok.
        val body   = Array[Byte](0, 0, 0, 0)
        val reader = new PostgresBufferReader(Span.from(body))
        val um     = Unmarshallers.default
        Abort.run[SqlDecodeException](um.authentication.read(reader)).map { r =>
            r match
                case Result.Success(Authentication(AuthenticationKind.Ok)) => succeed
                case other                                                 => fail(s"Expected AuthenticationOk, got $other")
        }
    }

end AuthenticationUnmarshallerTest
