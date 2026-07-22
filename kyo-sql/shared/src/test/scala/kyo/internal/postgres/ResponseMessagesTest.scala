package kyo.internal.postgres

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.unmarshaller.*

/** Tests for Backend message unmarshallers.
  *
  * All expected byte sequences are manually constructed per PostgreSQL §55.7 "Message Formats". The reader is fed only the message body
  * (type byte and length field already consumed by a dispatcher).
  */
class ResponseMessagesTest extends Test:

    // Helper: unwrap an Abort[SqlDecodeException] result to the success value, failing the test on error
    private def decode[A](result: A < Abort[SqlDecodeException])(using kyo.test.AssertScope): A =
        Abort.run[SqlDecodeException](result).eval match
            case Result.Success(a) => a
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t

    // ─── Authentication ───────────────────────────────────────────────────────

    "AuthenticationUnmarshaller decodes AuthenticationOk" in {
        val body   = Array[Byte](0, 0, 0, 0) // sub-type = 0 (Ok)
        val reader = PostgresBufferReader(body)
        val msg    = decode(AuthenticationUnmarshaller.read(reader))
        assert(msg.kind.isInstanceOf[AuthenticationKind.Ok.type])
    }

    "AuthenticationUnmarshaller decodes AuthenticationMD5Password" in {
        val salt   = Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)
        val body   = Array[Byte](0, 0, 0, 5) ++ salt
        val reader = PostgresBufferReader(body)
        val msg    = decode(AuthenticationUnmarshaller.read(reader))
        msg match
            case Authentication(AuthenticationKind.MD5Password(s)) =>
                val saltArr = s.toArray
                assert(saltArr(0) == 0xde.toByte)
                assert(saltArr(1) == 0xad.toByte)
                assert(saltArr(2) == 0xbe.toByte)
                assert(saltArr(3) == 0xef.toByte)
            case other => fail(s"Expected MD5Password, got $other")
        end match

    }

    "AuthenticationUnmarshaller decodes AuthenticationSASL with mechanism list" in {
        val mechBytes = "SCRAM-SHA-256".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val body      = Array[Byte](0, 0, 0, 10) ++ mechBytes ++ Array[Byte](0, 0)
        val reader    = PostgresBufferReader(body)
        val msg       = decode(AuthenticationUnmarshaller.read(reader))
        msg match
            case Authentication(AuthenticationKind.SASL(mechs)) =>
                assert(mechs.size == 1)
                assert(mechs(0) == "SCRAM-SHA-256")
            case other => fail(s"Expected SASL, got $other")
        end match

    }

    "AuthenticationUnmarshaller decodes AuthenticationSASLContinue" in {
        val data   = Array[Byte](1, 2, 3, 4, 5)
        val body   = Array[Byte](0, 0, 0, 11) ++ data
        val reader = PostgresBufferReader(body)
        val msg    = decode(AuthenticationUnmarshaller.read(reader))
        msg match
            case Authentication(AuthenticationKind.SASLContinue(d)) =>
                assert(d.toArray sameElements data)
            case other => fail(s"Expected SASLContinue, got $other")
        end match

    }

    // ─── ParameterStatus ──────────────────────────────────────────────────────

    "ParameterStatusUnmarshaller decodes key and value" in {
        val name  = "client_encoding"
        val value = "UTF8"
        val body = name.getBytes(java.nio.charset.StandardCharsets.UTF_8) ++
            Array[Byte](0) ++
            value.getBytes(java.nio.charset.StandardCharsets.UTF_8) ++
            Array[Byte](0)
        val reader = PostgresBufferReader(body)
        val msg    = decode(ParameterStatusUnmarshaller.read(reader))
        assert(msg.name == "client_encoding")
        assert(msg.value == "UTF8")
    }

    // ─── BackendKeyData ───────────────────────────────────────────────────────

    "BackendKeyDataUnmarshaller decodes pid and secret" in {
        // pid=1234=0x000004D2, secret=5678=0x0000162E in big-endian
        val body = Array[Byte](
            0,
            0,
            4,
            0xd2.toByte,
            0,
            0,
            0x16.toByte,
            0x2e.toByte
        )
        val reader = PostgresBufferReader(body)
        val msg    = decode(BackendKeyDataUnmarshaller.read(reader))
        assert(msg.processId == 1234)
        assert(msg.secretKey == 5678)
    }

    // ─── ReadyForQuery ────────────────────────────────────────────────────────

    "ReadyForQueryUnmarshaller decodes idle status 'I'" in {
        val body   = Array[Byte]('I'.toByte)
        val reader = PostgresBufferReader(body)
        val msg    = decode(ReadyForQueryUnmarshaller.read(reader))
        assert(msg.status == 'I'.toByte)
    }

    "ReadyForQueryUnmarshaller decodes transaction status 'T'" in {
        val body   = Array[Byte]('T'.toByte)
        val reader = PostgresBufferReader(body)
        val msg    = decode(ReadyForQueryUnmarshaller.read(reader))
        assert(msg.status == 'T'.toByte)
    }

    "ReadyForQueryUnmarshaller decodes error status 'E'" in {
        val body   = Array[Byte]('E'.toByte)
        val reader = PostgresBufferReader(body)
        val msg    = decode(ReadyForQueryUnmarshaller.read(reader))
        assert(msg.status == 'E'.toByte)
    }

    // ─── RowDescription ───────────────────────────────────────────────────────

    "RowDescriptionUnmarshaller decodes single column" in {
        val writer = new PostgresBufferWriter
        writer.writeInt16(1.toShort) // numFields = 1
        writer.writeString("id")
        writer.writeInt32(0)         // tableOid
        writer.writeInt16(0.toShort) // colAttr
        writer.writeInt32(23)        // int4 OID
        writer.writeInt16(4.toShort) // dataTypeSize
        writer.writeInt32(-1)        // typeModifier
        writer.writeInt16(0.toShort) // text format

        val reader = new PostgresBufferReader(writer.toSpan)
        val msg    = decode(RowDescriptionUnmarshaller.read(reader))
        assert(msg.fields.size == 1)
        val f = msg.fields(0)
        assert(f.name == "id")
        assert(f.dataType == 23)
        assert(f.formatCode == 0.toShort)
    }

    // ─── DataRow ──────────────────────────────────────────────────────────────

    "DataRowUnmarshaller decodes single text column" in {
        val writer = new PostgresBufferWriter
        writer.writeInt16(1.toShort)
        val valBytes = "42".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        writer.writeInt32(valBytes.length)
        writer.writeBytes(Span.from(valBytes))

        val reader = new PostgresBufferReader(writer.toSpan)
        val msg    = decode(DataRowUnmarshaller.read(reader))
        assert(msg.values.size == 1)
        msg.values(0) match
            case Maybe.Present(span) => assert(new String(span.toArray, java.nio.charset.StandardCharsets.UTF_8) == "42")
            case Maybe.Absent        => fail("Expected Present, got Absent")

    }

    "DataRowUnmarshaller decodes NULL column as Absent" in {
        val writer = new PostgresBufferWriter
        writer.writeInt16(1.toShort)
        writer.writeInt32(-1) // NULL marker

        val reader = new PostgresBufferReader(writer.toSpan)
        val msg    = decode(DataRowUnmarshaller.read(reader))
        assert(msg.values.size == 1)
        assert(msg.values(0).isEmpty)
    }

    // ─── ErrorResponse ────────────────────────────────────────────────────────

    "ErrorResponseUnmarshaller extracts SQLSTATE code" in {
        val writer = new PostgresBufferWriter
        writer.writeByte('C'.toByte)
        writer.writeString("23505")
        writer.writeByte('M'.toByte)
        writer.writeString("duplicate key value violates unique constraint")
        writer.writeByte(0.toByte) // terminator

        val reader   = new PostgresBufferReader(writer.toSpan)
        val msg      = decode(ErrorResponseUnmarshaller.read(reader))
        val sqlState = msg.fields.find(_._1 == 'C'.toByte).map(_._2)
        assert(sqlState == Some("23505"))
    }

    // ─── CommandComplete ──────────────────────────────────────────────────────

    "CommandCompleteUnmarshaller decodes SELECT tag" in {
        val writer = new PostgresBufferWriter
        writer.writeString("SELECT 5")

        val reader = new PostgresBufferReader(writer.toSpan)
        val msg    = decode(CommandCompleteUnmarshaller.read(reader))
        assert(msg.tag == "SELECT 5")
    }

    // ─── NotificationResponse ─────────────────────────────────────────────────

    "NotificationResponseUnmarshaller decodes pid, channel, payload" in {
        val writer = new PostgresBufferWriter
        writer.writeInt32(42)
        writer.writeString("test")
        writer.writeString("hello")

        val reader = new PostgresBufferReader(writer.toSpan)
        val msg    = decode(NotificationResponseUnmarshaller.read(reader))
        assert(msg.processId == 42)
        assert(msg.channel == "test")
        assert(msg.payload == "hello")
    }

    // ─── PortalSuspended ──────────────────────────────────────────────────────

    "PortalSuspendedUnmarshaller decodes" in {
        val body   = Array.empty[Byte]
        val reader = PostgresBufferReader(body)
        val msg    = decode(PortalSuspendedUnmarshaller.read(reader))
        assert(msg eq PortalSuspended)
    }

    // ─── NoData ───────────────────────────────────────────────────────────────

    "NoDataUnmarshaller decodes" in {
        val body   = Array.empty[Byte]
        val reader = PostgresBufferReader(body)
        val msg    = decode(NoDataUnmarshaller.read(reader))
        assert(msg eq NoData)
    }

    "two structurally-equal FieldDescription values compare ==" in {
        val fd1 = FieldDescription("id", 0, 0.toShort, 23, 4.toShort, -1, 0.toShort)
        val fd2 = FieldDescription("id", 0, 0.toShort, 23, 4.toShort, -1, 0.toShort)
        assert(fd1 == fd2)
    }

end ResponseMessagesTest
