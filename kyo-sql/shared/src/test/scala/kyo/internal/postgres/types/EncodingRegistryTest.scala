package kyo.internal.postgres.types

import kyo.*

/** Tests for EncodingRegistry — lookup by OID + format. */
class EncodingRegistryTest extends kyo.Test:

    val registry = EncodingRegistry.builtin

    // ── Encoder lookups ───────────────────────────────────────────────────────

    "encoderByOid returns bool text encoder for OID 16 + String" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_BOOL, Format.Text)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_BOOL)
        assert(enc.get.format == Format.Text)
    }

    "encoderByOid returns bool binary encoder for OID 16 + Binary" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_BOOL, Format.Binary)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_BOOL)
        assert(enc.get.format == Format.Binary)
    }

    "encoderByOid returns int4 binary encoder for OID 23 + Binary" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_INT4, Format.Binary)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_INT4)
    }

    "encoderByOid returns int8 binary encoder for OID 20 + Binary" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_INT8, Format.Binary)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_INT8)
    }

    "encoderByOid returns Absent for unknown OID" in {
        val enc = registry.encoderByOid(99999, Format.Binary)
        assert(enc.isEmpty)
    }

    "encoderByOid returns text encoder for numeric OID 1700" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_NUMERIC, Format.Text)
        assert(enc.isDefined)
    }

    "encoderByOid returns binary encoder for numeric OID 1700 + Binary" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_NUMERIC, Format.Binary)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_NUMERIC)
        assert(enc.get.format == Format.Binary)
    }

    // ── Decoder lookups ───────────────────────────────────────────────────────

    "decoderByOid returns bool decoder for OID 16 + String" in {
        val dec = registry.decoderByOid(PostgresEncoder.OID_BOOL, Format.Text)
        assert(dec.isDefined)
    }

    "decoderByOid returns bool decoder for OID 16 + Binary" in {
        val dec = registry.decoderByOid(PostgresEncoder.OID_BOOL, Format.Binary)
        assert(dec.isDefined)
    }

    "decoderByOid returns text decoder for varchar OID 1043" in {
        val dec = registry.decoderByOid(1043, Format.Text)
        assert(dec.isDefined)
    }

    "decoderByOid returns binary decoder for numeric OID 1700 + Binary" in {
        val dec = registry.decoderByOid(PostgresEncoder.OID_NUMERIC, Format.Binary)
        assert(dec.isDefined)
    }

    "decoderByOid returns Absent for completely unknown OID" in {
        val dec = registry.decoderByOid(99999, Format.Binary)
        assert(dec.isEmpty)
    }

    "decoderByOid returns timestamptz decoder for OID 1184" in {
        val dec = registry.decoderByOid(PostgresEncoder.OID_TIMESTAMPTZ, Format.Binary)
        assert(dec.isDefined)
    }

end EncodingRegistryTest
