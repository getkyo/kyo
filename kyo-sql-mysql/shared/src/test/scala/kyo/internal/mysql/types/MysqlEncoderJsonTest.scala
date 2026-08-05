package kyo.internal.mysql.types

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlCodec.Format
import kyo.internal.mysql.MysqlBufferWriter
import kyo.internal.mysql.MysqlParamWriter
import kyo.internal.mysql.MysqlRowCodec
import kyo.internal.mysql.MysqlRowReader

/** Codec-layer unit tests for the MySQL JSON wire encoder and decoder.
  *
  *   - Wire type byte: `TYPE_JSON = 0xf5`.
  *   - Encoder writes a lenenc-int length prefix followed by raw UTF-8 JSON text (`writeLenencString`).
  *   - Decoder consumes raw UTF-8 bytes (the lenenc prefix is stripped by `BinaryResultsetRowUnmarshaller`).
  *
  * Mostly pure codec tests on in-memory byte buffers. The column-level leaves at the end pin which MySQL type byte [[kyo.JsonText]] reaches
  * the wire with, including a document produced by a JSON library rather than by a fixed schema, which is what a runtime-shaped value looks
  * like from kyo-sql's side.
  */
class MysqlEncoderJsonTest extends kyo.Test:

    private def mysqlEncode(value: String): Span[Byte] =
        val buf = new MysqlBufferWriter
        MysqlEncoder.jsonEncoder.write(value, buf)
        buf.toSpan
    end mysqlEncode

    /** Reads `bytes` back as a JSON column, through the reader a JSON field decodes through.
      *
      * `MysqlRowReader.nextJson` is what a JSON column really meets, and the claim it carries is that a JSON column surfaces as raw UTF-8
      * with no further parsing.
      */
    private def mysqlDecode(bytes: Span[Byte]): String < Abort[SqlDecodeException] =
        val row = new SqlRow(
            Chunk(Maybe.Present(bytes)),
            Chunk(SqlRow.Column("doc", 0)),
            MysqlRowCodec(Format.Binary)
        )
        new MysqlRowReader(row, Format.Binary)(using kyo.Frame.derive).nextJson()
    end mysqlDecode

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

    // These column-level cases need a concrete backend writer, so they live in the MySQL module: they assert what
    // JsonText puts on the MySQL wire.

    "JsonText emits a single TYPE_JSON param carrying the document verbatim" in {
        val document = """{"mysql":true,"n":42}"""
        val params   = MysqlParamWriter.write(summon[SqlSchema.Column[JsonText]], JsonText(document))
        assert(params.size == 1)
        assert(params(0).encoder.mysqlType == MysqlEncoder.TYPE_JSON)
        params(0).encoded match
            case Maybe.Present(bytes) =>
                // A JSON param is a lenenc string: one length byte for a payload under 251 bytes.
                val payload = new String(bytes.slice(1, bytes.size).toArray, StandardCharsets.UTF_8)
                assert(bytes(0) == payload.length.toByte, "the lenenc prefix states the payload length")
                assert(payload == document)
            case Maybe.Absent => fail("expected encoded JSON bytes")
        end match
    }

    "a runtime-shaped document encoded with Json reaches the JSON wire as its text" in {
        // kyo-sql names no document library: a caller produces the text with whatever encoder they have and
        // wraps it in JsonText. Driving this leaf with a Structure.Value, whose shape exists only at runtime,
        // is what shows the column carries a document rather than a fixed row type.
        val value = Structure.Value.Record(Chunk(
            "mysql" -> Structure.Value.Bool(true),
            "n"     -> Structure.Value.Integer(42)
        ))
        val params = MysqlParamWriter.write(summon[SqlSchema.Column[JsonText]], JsonText(Json.encode(value)))
        assert(params.size == 1)
        assert(params(0).encoder.mysqlType == MysqlEncoder.TYPE_JSON)
        params(0).encoded match
            case Maybe.Present(bytes) =>
                assert(new String(bytes.slice(1, bytes.size).toArray, StandardCharsets.UTF_8) == """{"mysql":true,"n":42}""")
            case Maybe.Absent => fail("expected encoded JSON bytes")
        end match
    }

    "Chunk[JsonText] emits one VAR_STRING param holding the JSON array" in {
        val input  = Chunk(JsonText("""{"a":1}"""), JsonText("null"))
        val params = MysqlParamWriter.write(summon[SqlSchema.Column[Chunk[JsonText]]], input)
        assert(params.size == 1)
        assert(params(0).encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        params(0).value match
            case Maybe.Present(text: String) => assert(text == """[{"a":1},null]""")
            case other                       => fail(s"expected the JSON array text, got $other")
        end match
    }

end MysqlEncoderJsonTest
