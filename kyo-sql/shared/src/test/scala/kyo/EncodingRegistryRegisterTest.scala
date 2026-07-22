package kyo

import kyo.EncodingRegistry
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Span
import kyo.Test
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Unit tests for [[EncodingRegistry.register]] and [[EncodingRegistry.registerCustom]].
  *
  * All tests are pure in-memory, no database container required.
  */
class EncodingRegistryRegisterTest extends Test:

    // Custom OID outside the standard range, avoids colliding with builtins.
    private val CUSTOM_OID = 99001

    // A minimal custom encoder for String that uses String format.
    private val customPgEncoder: PostgresEncoder[String] = new PostgresEncoder[String]:
        val oid: Int       = CUSTOM_OID
        val format: Format = Format.Text
        def write(value: String, buf: PostgresBufferWriter): Unit =
            val bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            buf.writeBytes(bytes)

    // A minimal custom decoder for String.
    private val customPgDecoder: PostgresDecoder[String] = new PostgresDecoder[String]:
        def oids: Set[Int] = Set(CUSTOM_OID)
        def read(format: Format, bytes: Span[Byte])(using Frame): String =
            new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)

    "register adds a custom PG encoder and lookup returns it" in {
        val registry = EncodingRegistry.builtin.register(CUSTOM_OID, customPgEncoder, customPgDecoder)
        val enc      = registry.encoderByOid(CUSTOM_OID, Format.Text)
        enc match
            case Present(e) => assert(e eq customPgEncoder)
            case Absent     => fail("Expected Present(customPgEncoder) but got Absent")
    }

    "register adds a custom PG decoder and lookup returns it" in {
        val registry = EncodingRegistry.builtin.register(CUSTOM_OID, customPgEncoder, customPgDecoder)
        val decBin   = registry.decoderByOid(CUSTOM_OID, Format.Binary)
        val decText  = registry.decoderByOid(CUSTOM_OID, Format.Text)
        decBin match
            case Present(d) => assert(d eq customPgDecoder)
            case Absent     => fail("Expected Present(customPgDecoder) for Binary lookup but got Absent")
        decText match
            case Present(d) => assert(d eq customPgDecoder)
            case Absent     => fail("Expected Present(customPgDecoder) for String lookup but got Absent")
    }

    "register chained calls accumulate entries (later registration wins for same OID)" in {
        val SECOND_OID = 99002
        val secondEncoder: PostgresEncoder[String] = new PostgresEncoder[String]:
            val oid: Int                                              = SECOND_OID
            val format: Format                                        = Format.Text
            def write(value: String, buf: PostgresBufferWriter): Unit = ()
        val secondDecoder: PostgresDecoder[String] = new PostgresDecoder[String]:
            def oids: Set[Int]                                               = Set(SECOND_OID)
            def read(format: Format, bytes: Span[Byte])(using Frame): String = ""

        val registry = EncodingRegistry.builtin
            .register(CUSTOM_OID, customPgEncoder, customPgDecoder)
            .register(SECOND_OID, secondEncoder, secondDecoder)

        // Both OIDs must be reachable.
        val enc1 = registry.encoderByOid(CUSTOM_OID, Format.Text)
        val enc2 = registry.encoderByOid(SECOND_OID, Format.Text)
        enc1 match
            case Present(e) => assert(e eq customPgEncoder)
            case Absent     => fail("CUSTOM_OID encoder missing after chained register")
        enc2 match
            case Present(e) => assert(e eq secondEncoder)
            case Absent     => fail("SECOND_OID encoder missing after chained register")

        // Re-registering CUSTOM_OID must replace the earlier entry.
        val replacementEncoder: PostgresEncoder[String] = new PostgresEncoder[String]:
            val oid: Int                                              = CUSTOM_OID
            val format: Format                                        = Format.Text
            def write(value: String, buf: PostgresBufferWriter): Unit = ()
        val replacementDecoder: PostgresDecoder[String] = new PostgresDecoder[String]:
            def oids: Set[Int]                                               = Set(CUSTOM_OID)
            def read(format: Format, bytes: Span[Byte])(using Frame): String = ""

        val reregistered = registry.register(CUSTOM_OID, replacementEncoder, replacementDecoder)
        val enc1b        = reregistered.encoderByOid(CUSTOM_OID, Format.Text)
        enc1b match
            case Present(e) => assert(e eq replacementEncoder)
            case Absent     => fail("Expected replacement encoder after re-registration")
    }

    "lookup falls back to builtin when no custom registration exists" in {
        val registry = EncodingRegistry.builtin.register(CUSTOM_OID, customPgEncoder, customPgDecoder)

        // OID_BOOL = 16 is a builtin OID, must still be reachable via the builtin fallback.
        val boolEnc = registry.encoderByOid(PostgresEncoder.OID_BOOL, Format.Binary)
        boolEnc match
            case Present(_) => succeed
            case Absent     => fail("Builtin bool encoder should be reachable after register()")

        val boolDec = registry.decoderByOid(PostgresEncoder.OID_BOOL, Format.Binary)
        boolDec match
            case Present(_) => succeed
            case Absent     => fail("Builtin bool decoder should be reachable after register()")

        // An unregistered custom OID must return Absent.
        val missing = registry.encoderByOid(99999, Format.Binary)
        assert(missing == Absent)
    }

end EncodingRegistryRegisterTest
