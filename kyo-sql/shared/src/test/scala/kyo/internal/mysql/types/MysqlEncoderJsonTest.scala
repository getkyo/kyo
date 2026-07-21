package kyo.internal.mysql.types

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.mysql.MysqlBufferWriter

/** Codec-layer unit tests for MySQL JSON wire encoder and decoder (Phase 17, G-Parity-12).
  *
  *   - Wire type byte: `TYPE_JSON = 0xf5`.
  *   - Encoder writes a lenenc-int length prefix followed by raw UTF-8 JSON text (`writeLenencString`).
  *   - Decoder consumes raw UTF-8 bytes (the lenenc prefix is stripped by `BinaryResultsetRowUnmarshaller`).
  *
  * These are pure codec tests on in-memory byte buffers, no schema layer, no database container. Schema-layer JSON tests (the
  * [[kyo.Structure.Value]] / [[kyo.SqlSchema]] surface) live in `kyo/SqlSchemaStructureValueTest.scala`.
  */
class MysqlEncoderJsonTest extends kyo.Test:

    private def mysqlEncode(value: String): Span[Byte] =
        val buf = new MysqlBufferWriter
        MysqlEncoder.jsonEncoder.write(value, buf)
        buf.toSpan
    end mysqlEncode

    private def mysqlDecode(bytes: Span[Byte]): String < Abort[SqlException.Decode] =
        MysqlDecoder.jsonDecoder.decode(bytes)

    "MySQL JSON encodes Json as UTF-8 text (TYPE_JSON)" in {
        val json            = """{"mysql":true}"""
        val bytes           = mysqlEncode(json)
        val expectedPayload = json.getBytes(StandardCharsets.UTF_8)
        // lenenc-int prefix (1 byte for length < 251) + payload bytes
        assert(bytes.size == 1 + expectedPayload.length)
        assert((bytes(0) & 0xff) == expectedPayload.length)
        val payload = new String(bytes.slice(1, bytes.size).toArray, StandardCharsets.UTF_8)
        assert(payload == json)
    }

    "MySQL JSON encoder has mysqlType = TYPE_JSON (0xf5)" in {
        assert(MysqlEncoder.jsonEncoder.mysqlType == MysqlEncoder.TYPE_JSON)
        assert(MysqlEncoder.jsonEncoder.mysqlType == 0xf5)
    }

    "MySQL JSON decodes Json from UTF-8 text" in {
        val json    = """{"decoded":true}"""
        val payload = Span.from(json.getBytes(StandardCharsets.UTF_8))
        val result  = Abort.run(mysqlDecode(payload)).eval
        result match
            case Result.Success(decoded) => assert(decoded == json)
            case Result.Failure(e)       => fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[MysqlEncoderJsonTest] unexpected panic: ${t.getMessage}")
                fail(s"Unexpected panic: $t")
        end match
    }

    "Json round-trips through MySQL JSON column" in {
        val original = """{"round":"trip","n":99}"""
        val encoded  = mysqlEncode(original)
        // The MySQL encoder writes a lenenc-int prefix; the decoder receives the raw UTF-8 bytes (after
        // the length prefix has been consumed by BinaryResultsetRowUnmarshaller). Simulate: skip the
        // lenenc-int prefix (1 byte for length < 251) and decode the rest.
        val payloadBytes = encoded.slice(1, encoded.size)
        val result       = Abort.run(mysqlDecode(payloadBytes)).eval
        result match
            case Result.Success(decoded) => assert(decoded == original)
            case Result.Failure(e)       => fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[MysqlEncoderJsonTest] unexpected panic: ${t.getMessage}")
                fail(s"Unexpected panic: $t")
        end match
    }

end MysqlEncoderJsonTest
