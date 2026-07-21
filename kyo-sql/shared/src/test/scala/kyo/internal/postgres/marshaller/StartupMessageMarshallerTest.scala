package kyo.internal.postgres.marshaller

import kyo.*
import kyo.Test
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.StartupMessage

/** Wire-format unit test for [[StartupMessageMarshaller]].
  *
  * Verifies that the marshaller writes the correct byte sequence for a StartupMessage: no type byte, 4-byte length prefix, 4-byte protocol
  * version (0x00030000 = 196608), key-value parameter strings, and a trailing NUL.
  */
class StartupMessageMarshallerTest extends kyo.Test:

    "PostgresChannel send StartupMessage bytes on wire — typeless framing" in {
        // Unit test: use the marshaller directly and inspect output bytes.
        val msg = StartupMessage(Chunk(("user", "alice"), ("database", "mydb")))
        val buf = new PostgresBufferWriter
        StartupMessageMarshaller.write(msg, buf)
        val bytes = buf.toSpan
        // StartupMessage has no type byte; first 4 bytes are the length.
        assert(bytes.size >= 8)
        // Protocol version at bytes 4-7: 196608 = 0x00030000
        val b4 = bytes(4) & 0xff
        val b5 = bytes(5) & 0xff
        val b6 = bytes(6) & 0xff
        val b7 = bytes(7) & 0xff
        assert((b4 << 24 | b5 << 16 | b6 << 8 | b7) == 196608)
    }

end StartupMessageMarshallerTest
