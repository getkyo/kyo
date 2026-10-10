package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.Test
import kyo.internal.mysql.AuthSwitchRequest
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.MysqlBufferWriter

/** Unit tests for [[AuthSwitchRequestUnmarshaller]].
  *
  * A MySQL 8.0 server writes the switch nonce as 20 bytes followed by a NUL, the same terminator the HandshakeV10 nonce carries. The
  * scramble every plugin hashes is the 20 bytes, so the decoder must hand those over without the terminator.
  */
class AuthSwitchRequestUnmarshallerTest extends Test:

    private def decode(payload: Span[Byte]): AuthSwitchRequest =
        import AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(Abort.run(AuthSwitchRequestUnmarshaller.read(MysqlBufferReader(payload))).map(_.getOrThrow))

    private def request(plugin: String, data: Array[Byte]): Span[Byte] =
        val buf = new MysqlBufferWriter
        buf.writeNulTerminatedString(plugin)
        buf.writeBytes(data)
        buf.toSpan
    end request

    private val nonce: Array[Byte] = Array.tabulate(20)(i => (i + 1).toByte)

    "a nonce sent with its NUL terminator decodes to the 20 nonce bytes" in {
        val decoded = decode(request("mysql_native_password", nonce :+ 0.toByte))
        assert(decoded.pluginName == "mysql_native_password")
        assert(decoded.pluginData.size == 20, s"expected the 20-byte nonce, got ${decoded.pluginData.size} bytes")
        assert(decoded.pluginData.toArray.toSeq == nonce.toSeq)
    }

    "a nonce sent without a terminator decodes unchanged" in {
        val decoded = decode(request("caching_sha2_password", nonce))
        assert(decoded.pluginData.toArray.toSeq == nonce.toSeq)
    }

    "a switch with no plugin data decodes to an empty nonce" in {
        val decoded = decode(request("mysql_clear_password", Array.empty))
        assert(decoded.pluginName == "mysql_clear_password")
        assert(decoded.pluginData.isEmpty)
    }

end AuthSwitchRequestUnmarshallerTest
