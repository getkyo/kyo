package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Test
import kyo.internal.postgres.marshaller.SSLRequestMarshaller
import kyo.internal.postgres.marshaller.StartupMessageMarshaller

/** Tests for startup-phase Frontend messages: SSLRequest and StartupMessage.
  *
  * Expected bytes manually constructed per PostgreSQL §55.7 "Message Formats".
  */
class StartupMessagesTest extends Test:

    // SSLRequest encodes to 8 bytes
    // Expected wire: [0,0,0,8, 4,210,22,47] (length=8, code=80877103=0x04D2162F)
    "SSLRequest encodes to 8 bytes" in {
        val buf = new PostgresBufferWriter
        SSLRequestMarshaller.write(SSLRequest, buf)
        val span = buf.toSpan
        assert(span.size == 8)
        // length = 8 big-endian
        assert(span(0) == 0.toByte)
        assert(span(1) == 0.toByte)
        assert(span(2) == 0.toByte)
        assert(span(3) == 8.toByte)
        // code = 80877103 = 0x04D2162F
        assert((span(4) & 0xff) == 0x04)
        assert((span(5) & 0xff) == 0xd2)
        assert((span(6) & 0xff) == 0x16)
        assert((span(7) & 0xff) == 0x2f)
    }

    // StartupMessage encodes protocol version 196608 at bytes 4-7
    // Protocol version 196608 = 0x00030000 = [0,3,0,0]
    "StartupMessage encodes protocol version 196608" in {
        val msg = StartupMessage(Chunk("user" -> "alice"))
        val buf = new PostgresBufferWriter
        StartupMessageMarshaller.write(msg, buf)
        val span = buf.toSpan
        // bytes 0-3 = total length; bytes 4-7 = protocol version
        assert(span(4) == 0.toByte)
        assert(span(5) == 3.toByte)
        assert(span(6) == 0.toByte)
        assert(span(7) == 0.toByte)
    }

    // StartupMessage encodes user key-value pair as NUL-terminated strings
    // After the 8-byte header: "user" NUL "alice" NUL NUL
    "StartupMessage encodes user key-value pair" in {
        val msg = StartupMessage(Chunk("user" -> "alice"))
        val buf = new PostgresBufferWriter
        StartupMessageMarshaller.write(msg, buf)
        val bytes      = buf.toSpan.toArray
        val userBytes  = "user".getBytes(StandardCharsets.UTF_8)
        val aliceBytes = "alice".getBytes(StandardCharsets.UTF_8)
        val pos        = 8 // after 4-byte length + 4-byte protocol version
        // "user" bytes
        assert(userBytes.indices.forall(i => bytes(pos + i) == userBytes(i)))
        assert(bytes(pos + userBytes.length) == 0.toByte) // NUL after "user"
        // "alice" bytes
        val alicePos = pos + userBytes.length + 1
        assert(aliceBytes.indices.forall(i => bytes(alicePos + i) == aliceBytes(i)))
        assert(bytes(alicePos + aliceBytes.length) == 0.toByte)     // NUL after "alice"
        assert(bytes(alicePos + aliceBytes.length + 1) == 0.toByte) // trailing NUL list terminator
    }

end StartupMessagesTest
