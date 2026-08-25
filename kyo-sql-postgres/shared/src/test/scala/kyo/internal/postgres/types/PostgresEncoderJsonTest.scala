package kyo.internal.postgres.types

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlCodec.Format
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowCodec

/** Codec-layer unit tests for PostgreSQL JSON / JSONB wire encoders and decoders.
  *
  *   - JSONB binary: OID 3802, version byte 0x01 + UTF-8 JSON text.
  *   - JSON text: OID 114, raw UTF-8 JSON text.
  *
  * Mostly pure codec tests on in-memory byte buffers. The column-level leaves at the end pin which OID and format [[kyo.JsonText]] reaches
  * the wire with, including a document produced by a JSON library rather than by a fixed schema, which is what a runtime-shaped value looks
  * like from kyo-sql's side.
  */
class PostgresEncoderJsonTest extends kyo.Test:

    private def pgEncode[A](value: A, enc: PostgresEncoder[A]): Span[Byte] =
        val buf = new PostgresBufferWriter
        enc.write(value, buf)
        buf.toSpan
    end pgEncode

    private def pgDecode[A](format: Format, bytes: Span[Byte], dec: PostgresDecoder[A]): A =
        dec.read(format, bytes)

    // ── JSONB binary encoder (OID 3802) ───────────────────────────────────────

    "JSONB binary encodes Json as 0x01 + UTF-8 text" in {
        val json  = """{"key":"value"}"""
        val bytes = pgEncode(json, PostgresEncoder.jsonbBinary)
        assert(bytes.size > 0)
        assert(bytes(0) == 0x01.toByte)
        val payload = new String(bytes.slice(1, bytes.size).toArray, StandardCharsets.UTF_8)
        assert(payload == json)
    }

    "JSONB binary encodes empty object {}" in {
        val json  = "{}"
        val bytes = pgEncode(json, PostgresEncoder.jsonbBinary)
        assert(bytes.size == 3)
        assert(bytes(0) == 0x01.toByte)
        val payload = new String(bytes.slice(1, bytes.size).toArray, StandardCharsets.UTF_8)
        assert(payload == json)
    }

    "JSONB binary encoder has OID 3802" in {
        assert(PostgresEncoder.jsonbBinary.oid == 3802)
        assert(PostgresEncoder.jsonbBinary.oid == PostgresEncoder.OID_JSONB)
    }

    "JSONB binary encoder uses Binary format" in {
        assert(PostgresEncoder.jsonbBinary.format == Format.Binary)
    }

    // ── JSONB binary decoder (OID 3802) ───────────────────────────────────────

    "JSONB binary decodes Json from version-prefixed bytes" in {
        val json      = """{"answer":42}"""
        val jsonBytes = json.getBytes(StandardCharsets.UTF_8)
        val payload   = Span.from(Array(0x01.toByte) ++ jsonBytes)
        val decoded   = pgDecode(Format.Binary, payload, PostgresDecoder.jsonDecoder)
        assert(decoded == json)
    }

    "JSONB binary decodes without version byte when not present (text fallback)" in {
        val json    = """[1,2,3]"""
        val payload = Span.from(json.getBytes(StandardCharsets.UTF_8))
        val decoded = pgDecode(Format.Binary, payload, PostgresDecoder.jsonDecoder)
        assert(decoded == json)
    }

    // ── JSON text decoder (OID 114) ───────────────────────────────────────────

    "JSON text decodes Json from raw UTF-8" in {
        val json    = """{"status":"ok"}"""
        val payload = Span.from(json.getBytes(StandardCharsets.UTF_8))
        val decoded = pgDecode(Format.Text, payload, PostgresDecoder.jsonDecoder)
        assert(decoded == json)
    }

    "json decoder handles OID 114 and OID 3802" in {
        assert(PostgresEncoder.OID_JSON == 114)
        assert(PostgresDecoder.jsonDecoder.oids.contains(114))
        assert(PostgresDecoder.jsonDecoder.oids.contains(3802))
        assert(PostgresDecoder.jsonDecoder.oids.contains(PostgresEncoder.OID_JSON))
        assert(PostgresDecoder.jsonDecoder.oids.contains(PostgresEncoder.OID_JSONB))
    }

    // ── Round-trips ───────────────────────────────────────────────────────────

    "Json round-trips through PG jsonb (binary)" in {
        val original = """{"x":1,"y":true,"z":null}"""
        val bytes    = pgEncode(original, PostgresEncoder.jsonbBinary)
        val decoded  = pgDecode(Format.Binary, bytes, PostgresDecoder.jsonDecoder)
        assert(decoded == original)
    }

    // ── Declared OID and format ───────────────────────────────────────────────

    "jsonbBinary declares OID 3802 in binary format" in {
        assert(PostgresEncoder.jsonbBinary.oid == PostgresEncoder.OID_JSONB)
        assert(PostgresEncoder.OID_JSONB == 3802)
        assert(PostgresEncoder.jsonbBinary.format == Format.Binary)
    }

    // ── Column-level: what JsonText puts on the PG wire ──────────────────────
    //
    // This lives with the Postgres encoder because it needs a backend writer.

    "JsonText emits a single jsonb-OID Binary param carrying the document verbatim" in {
        val document = """{"x":1,"y":true,"z":null}"""
        val params   = PostgresParamWriter.write(summon[SqlSchema.Column[JsonText]], JsonText(document))
        assert(params.size == 1)
        assert(params(0).encoder.oid == PostgresEncoder.OID_JSONB)
        assert(params(0).encoder.format == Format.Binary)
        // The payload is the jsonb binary form: version byte 0x01 followed by the JSON text.
        params(0).encoded match
            case Maybe.Present(bytes) =>
                assert(bytes(0) == 0x01.toByte, "jsonb binary starts with the version byte")
                assert(new String(bytes.slice(1, bytes.size).toArray, StandardCharsets.UTF_8) == document)
            case Maybe.Absent => fail("expected encoded jsonb bytes")
        end match
    }

    "a runtime-shaped document encoded with Json reaches the jsonb wire as its text" in {
        // kyo-sql names no document library: a caller produces the text with whatever encoder they have and
        // wraps it in JsonText. Driving this leaf with a Structure.Value, whose shape exists only at runtime,
        // is what shows the column carries a document rather than a fixed row type.
        val value = Structure.Value.Record(Chunk(
            "x" -> Structure.Value.Integer(1),
            "y" -> Structure.Value.Bool(true),
            "z" -> Structure.Value.Null
        ))
        val params = PostgresParamWriter.write(summon[SqlSchema.Column[JsonText]], JsonText(Json.encode(value)))
        assert(params.size == 1)
        assert(params(0).encoder.oid == PostgresEncoder.OID_JSONB)
        params(0).encoded match
            case Maybe.Present(bytes) =>
                assert(new String(bytes.slice(1, bytes.size).toArray, StandardCharsets.UTF_8) == """{"x":1,"y":true,"z":null}""")
            case Maybe.Absent => fail("expected encoded jsonb bytes")
        end match
    }

    "Chunk[JsonText] emits one _jsonb param (OID 3807)" in {
        val input = Chunk(
            JsonText("""{"a":1}"""),
            JsonText("""{"b":2}""")
        )
        val params = PostgresParamWriter.write(summon[SqlSchema.Column[Chunk[JsonText]]], input)
        assert(params.size == 1)
        assert(params(0).encoder.oid == PostgresEncoder.OID_JSONB_ARRAY)
        assert(params(0).encoder.format == Format.Binary)
    }

    "a jsonColumn round-trips a sum type through the real jsonb wire" in {
        // Sql.jsonColumn is the installed single-column codec for a type richer than one SQL column: the
        // document text is the caller's JSON encoder's, and this leaf proves those values survive the actual
        // jsonb bytes rather than only the vocabulary call.
        given SqlSchema.Column[PostgresEncoderJsonPayload] =
            Sql.jsonColumn[PostgresEncoderJsonPayload](v => Json.encode(v))(text =>
                Json.decode[PostgresEncoderJsonPayload](text).getOrThrow
            )
        val column = summon[SqlSchema.Column[PostgresEncoderJsonPayload]]
        val cases = Seq[PostgresEncoderJsonPayload](
            PostgresEncoderJsonSuccess("ok"),
            PostgresEncoderJsonFailure(500, "boom")
        )
        cases.foreach { original =>
            val params = PostgresParamWriter.write(column, original)
            assert(params.size == 1, s"expected one jsonb param for $original")
            assert(params(0).encoder.oid == PostgresEncoder.OID_JSONB)
            val payload = params(0).encoded match
                case Maybe.Present(bytes) => bytes
                case Maybe.Absent         => fail(s"expected encoded jsonb bytes for $original")
            val row = new SqlRow(
                Chunk(Maybe.Present(payload)),
                Chunk(SqlRow.Column("doc", PostgresEncoder.OID_JSONB)),
                PostgresRowCodec(Format.Binary)
            )
            Abort.run(row.decode[PostgresEncoderJsonPayload]).eval match
                case Result.Success(decoded) => assert(decoded == original, s"round-trip mismatch for $original")
                case other                   => fail(s"expected Success($original), got $other")
            end match
        }
        succeed
    }

end PostgresEncoderJsonTest

sealed trait PostgresEncoderJsonPayload derives CanEqual
case class PostgresEncoderJsonSuccess(msg: String)               extends PostgresEncoderJsonPayload
case class PostgresEncoderJsonFailure(code: Int, reason: String) extends PostgresEncoderJsonPayload

object PostgresEncoderJsonPayload:
    given Schema[PostgresEncoderJsonPayload] = Schema.derived
end PostgresEncoderJsonPayload
