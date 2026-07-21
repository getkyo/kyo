package kyo.internal.postgres.types

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.postgres.PostgresBufferWriter

/** Codec-layer unit tests for PostgreSQL JSON / JSONB wire encoders and decoders (Phase 17, G-Parity-12).
  *
  *   - JSONB binary: OID 3802, version byte 0x01 + UTF-8 JSON text.
  *   - JSON text: OID 114, raw UTF-8 JSON text.
  *
  * These are pure codec tests on in-memory byte buffers — no schema layer, no database container. Schema-layer JSON tests (the
  * [[kyo.JsonText]] / [[kyo.SqlSchema]] surface) live in `kyo/SqlSchemaJsonTest.scala`.
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

    // ── JSON text encoder (OID 114) ───────────────────────────────────────────

    "JSON text encodes Json as raw UTF-8" in {
        val json  = """{"name":"kyo"}"""
        val bytes = pgEncode(json, PostgresEncoder.jsonText)
        val s     = new String(bytes.toArray, StandardCharsets.UTF_8)
        assert(s == json)
    }

    "JSON text encoder has OID 114" in {
        assert(PostgresEncoder.jsonText.oid == 114)
        assert(PostgresEncoder.jsonText.oid == PostgresEncoder.OID_JSON)
    }

    "JSON text encoder uses String format" in {
        assert(PostgresEncoder.jsonText.format == Format.Text)
    }

    // ── JSON text decoder (OID 114) ───────────────────────────────────────────

    "JSON text decodes Json from raw UTF-8" in {
        val json    = """{"status":"ok"}"""
        val payload = Span.from(json.getBytes(StandardCharsets.UTF_8))
        val decoded = pgDecode(Format.Text, payload, PostgresDecoder.jsonDecoder)
        assert(decoded == json)
    }

    "json decoder handles OID 114 and OID 3802" in {
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

    "Json round-trips through PG json (text)" in {
        val original = """[{"a":1},{"b":"two"}]"""
        val bytes    = pgEncode(original, PostgresEncoder.jsonText)
        val decoded  = pgDecode(Format.Text, bytes, PostgresDecoder.jsonDecoder)
        assert(decoded == original)
    }

    // ── EncodingRegistry integration (PG side) ────────────────────────────────

    "EncodingRegistry.builtin has JSONB binary encoder at OID 3802" in {
        val enc = EncodingRegistry.builtin.encoderByOid(3802, Format.Binary)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_JSONB)
    }

    "EncodingRegistry.builtin has JSON text encoder at OID 114" in {
        val enc = EncodingRegistry.builtin.encoderByOid(114, Format.Text)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_JSON)
    }

    "EncodingRegistry.builtin has json decoder for OID 114 (text)" in {
        val dec = EncodingRegistry.builtin.decoderByOid(114, Format.Text)
        assert(dec.isDefined)
    }

    "EncodingRegistry.builtin has json decoder for OID 3802 (binary)" in {
        val dec = EncodingRegistry.builtin.decoderByOid(3802, Format.Binary)
        assert(dec.isDefined)
    }

end PostgresEncoderJsonTest
