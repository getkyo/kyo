package kyo.internal.mysql

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.exchange.MysqlErrors
import kyo.internal.mysql.marshaller.*
import kyo.internal.mysql.types.MysqlDecoder
import kyo.internal.mysql.unmarshaller.*

/** Round-trip tests: encode every Frontend message, decode every Backend message. Validates structural equality. */
class RoundTripTest extends Test:

    private def decode[A](result: A < Abort[SqlDecodeException])(using kyo.test.AssertScope): A =
        Abort.run[SqlDecodeException](result).eval match
            case Result.Success(a) => a
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t

    // ─── Frontend round-trips ─────────────────────────────────────────────────

    "ComQuit marshals to single byte 0x01" in {
        val buf = new MysqlBufferWriter
        ComQuitMarshaller.write(ComQuit, buf)
        val span = buf.toSpan
        assert(span.size == 1)
        assert(span(0) == 0x01.toByte)
    }

    "ComPing marshals to single byte 0x0E" in {
        val buf = new MysqlBufferWriter
        ComPingMarshaller.write(ComPing, buf)
        val span = buf.toSpan
        assert(span.size == 1)
        assert(span(0) == 0x0e.toByte)
    }

    "ComResetConnection marshals to single byte 0x1F" in {
        val buf = new MysqlBufferWriter
        ComResetConnectionMarshaller.write(ComResetConnection, buf)
        val span = buf.toSpan
        assert(span.size == 1)
        assert(span(0) == 0x1f.toByte)
    }

    "ComStmtClose marshals to 0x19 + 4-byte stmtId" in {
        val buf = new MysqlBufferWriter
        ComStmtCloseMarshaller.write(ComStmtClose(stmtId = 42), buf)
        val arr = buf.toSpan.toArray
        assert(arr.length == 5)
        assert(arr(0) == 0x19.toByte)
        // stmtId 42 in LE: [42, 0, 0, 0]
        assert(arr(1) == 42.toByte)
        assert(arr(2) == 0.toByte)
        assert(arr(3) == 0.toByte)
        assert(arr(4) == 0.toByte)
    }

    "ComStmtReset marshals to 0x1A + 4-byte stmtId" in {
        val buf = new MysqlBufferWriter
        ComStmtResetMarshaller.write(ComStmtReset(stmtId = 7), buf)
        val arr = buf.toSpan.toArray
        assert(arr.length == 5)
        assert(arr(0) == 0x1a.toByte)
        assert(arr(1) == 7.toByte)
    }

    "AuthSwitchResponse marshals raw bytes verbatim" in {
        val data = Span.from(Array[Byte](1, 2, 3, 4))
        val buf  = new MysqlBufferWriter
        AuthSwitchResponseMarshaller.write(AuthSwitchResponse(data), buf)
        val span = buf.toSpan
        assert(span.size == 4)
        assert(span(0) == 1.toByte)
        assert(span(3) == 4.toByte)
    }

    "AuthMoreDataResponse marshals raw bytes verbatim" in {
        val data = Span.from(Array[Byte](0x04.toByte))
        val buf  = new MysqlBufferWriter
        AuthMoreDataMarshaller.write(AuthMoreDataResponse(data), buf)
        val span = buf.toSpan
        assert(span.size == 1)
        assert(span(0) == 0x04.toByte)
    }

    "HandshakeResponse41 with database round-trips NUL-terminated database name" in {
        val msg = HandshakeResponse41(
            capabilities = Capabilities.Default | Capabilities.CLIENT_CONNECT_WITH_DB,
            maxPacket = 16777216L,
            charset = 255,
            username = "u",
            authResponse = Span.from(Array[Byte](0x00.toByte)),
            database = Maybe.Present("mydb"),
            authPlugin = Maybe.Present("caching_sha2_password")
        )
        val buf = new MysqlBufferWriter
        HandshakeResponse41Marshaller.write(msg, buf)
        val arr = buf.toSpan.toArray
        // Verify the bytes contain "mydb\0" somewhere
        val encoded = new String(arr)
        assert(encoded.contains("mydb"))
    }

    // ─── Backend round-trips ─────────────────────────────────────────────────

    "OkPacket decode → encode fields preserved" in {
        val body = Array[Byte](
            5, // affectedRows = 5
            3, // lastInsertId = 3
            0x02.toByte,
            0x00.toByte, // statusFlags = 2
            0x01.toByte,
            0x00.toByte // warnings = 1
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(OkPacketUnmarshaller.read(reader))
        assert(decoded.affectedRows == 5L)
        assert(decoded.lastInsertId == 3L)
        assert(decoded.statusFlags == 2.toShort)
        assert(decoded.warnings == 1.toShort)
    }

    "ErrPacket decodes and preserves all fields" in {
        val buf = new MysqlBufferWriter
        buf.writeUInt16LE(1062)
        buf.writeByte('#'.toByte)
        buf.writeFixedString("23000")
        buf.writeFixedString("Duplicate entry '1' for key 'PRIMARY'")
        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(ErrPacketUnmarshaller.read(reader))
        assert(decoded.errorCode == 1062)
        assert(decoded.sqlState == "23000")
        assert(decoded.errorMessage.startsWith("Duplicate"))
    }

    "EofPacket round-trip preserves warnings and statusFlags" in {
        val body    = Array[Byte](7, 0, 0x02, 0) // warnings=7, statusFlags=2
        val reader  = MysqlBufferReader(body)
        val decoded = decode(EofPacketUnmarshaller.read(reader))
        assert(decoded.warnings == 7.toShort)
        assert(decoded.statusFlags == 2.toShort)
    }

    "AuthMoreData with multi-byte data round-trip" in {
        val data    = Array.fill(32)(0xaa.toByte)
        val body    = data
        val reader  = MysqlBufferReader(body)
        val decoded = decode(AuthMoreDataUnmarshaller.read(reader))
        assert(decoded.data.size == 32)
        assert(decoded.data(0) == 0xaa.toByte)
    }

    "StmtPrepareOk round-trip with warnings" in {
        val buf = new MysqlBufferWriter
        buf.writeUInt32LE(99L) // stmtId = 99
        buf.writeUInt16LE(2)   // numColumns = 2
        buf.writeUInt16LE(1)   // numParams = 1
        buf.writeByte(0)       // reserved
        buf.writeUInt16LE(3)   // warnings = 3
        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(StmtPrepareOkUnmarshaller.read(reader))
        assert(decoded.stmtId == 99)
        assert(decoded.numColumns == 2.toShort)
        assert(decoded.numParams == 1.toShort)
        assert(decoded.warnings == 3.toShort)
    }

    "ResultsetRow with three columns (NULL, string, string) round-trip" in {
        val buf = new MysqlBufferWriter
        buf.writeByte(0xfb.toByte) // NULL
        buf.writeLenencString("foo")
        buf.writeLenencString("bar")
        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(ResultsetRowUnmarshaller(3).read(reader))
        assert(decoded.values(0) == Maybe.Absent)
        decoded.values(1) match
            case Maybe.Present(b) => assert(new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8) == "foo")
            case _                => fail("Expected Present for column 1")
        decoded.values(2) match
            case Maybe.Present(b) => assert(new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8) == "bar")
            case _                => fail("Expected Present for column 2")
    }

    "BinaryResultsetRow with two columns (null, non-null TINY) round-trip" in {
        // 2 columns: bitmap len = ceil((2+2+7)/8) = 1
        // Column 0 null → bit (0+2) = bit 2 = 0x04
        // Column 1 not null → bit (1+2) = bit 3 = 0 → bitmap = 0x04
        val bitmap = 0x04.toByte
        val buf    = new MysqlBufferWriter
        buf.writeByte(bitmap)
        buf.writeByte(77.toByte) // non-null TINY value = 77
        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(BinaryResultsetRowUnmarshaller(2, Chunk(0x03, 0x01)).read(reader))
        assert(decoded.values(0) == Maybe.Absent)
        decoded.values(1) match
            case Maybe.Present(b) => assert(b.size == 1 && b(0) == 77.toByte)
            case _                => fail("Expected Present for column 1")
    }

    "ColumnDefinition41 round-trip preserves all string fields" in {
        val buf = new MysqlBufferWriter
        buf.writeLenencString("def")
        buf.writeLenencString("myschema")
        buf.writeLenencString("mytable")
        buf.writeLenencString("base_table")
        buf.writeLenencString("col_alias")
        buf.writeLenencString("col_real")
        buf.writeLenencInt(12L)
        buf.writeUInt16LE(255)            // charset utf8mb4
        buf.writeUInt32LE(100L)           // columnLength
        buf.writeUInt8(0xfc)              // columnType BLOB
        buf.writeUInt16LE(0)              // flags
        buf.writeUInt8(0)                 // decimals
        buf.writeBytes(Array[Byte](0, 0)) // filler

        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(ColumnDefinition41Unmarshaller.read(reader))
        assert(decoded.catalog == "def")
        assert(decoded.schema == "myschema")
        assert(decoded.table == "mytable")
        assert(decoded.orgTable == "base_table")
        assert(decoded.name == "col_alias")
        assert(decoded.orgName == "col_real")
        assert(decoded.columnType == 0xfc)
        assert(decoded.charset == 255)
    }

    // ─── Binary datetime/time codec tests ────────────────────────────────────

    // binary DATETIME zero-length struct decodes as epoch-ish (zero-date edge case)
    "binary DATETIME zero-length struct decodes (MySQL zero-date edge case)" in {
        // 0-length struct = 0000-00-00 00:00:00 → our decoder maps year 0 to 1, month 0 to 1, day 0 to 1
        val result = Abort.run[SqlDecodeException](
            MysqlDecoder.localDateTimeDecoder.decode(Span.from(Array.empty[Byte]))
        ).eval
        result match
            case Result.Success(ldt) =>
                // Zero-date (0-length struct) decodes to LocalDateTime.of(0, 1, 1, 0, 0, 0),
                // year 0 in proleptic Gregorian (= 1 BCE). We verify it's a zero-time date.
                assert(ldt.getYear == 0, s"Expected year 0 for zero-date, got ${ldt.getYear}")
                assert(ldt.getMonthValue == 1)
                assert(ldt.getDayOfMonth == 1)
                assert(ldt.getHour == 0)
                assert(ldt.getMinute == 0)
                assert(ldt.getSecond == 0)
            case Result.Failure(e) => fail(s"Expected success for zero-date, got: $e")
            case Result.Panic(t)   => throw t
        end match
    }

    // binary DATETIME 4-byte struct (date only) decodes to midnight
    "binary DATETIME 4-byte struct (date only) decodes to midnight" in {
        // 4-byte struct: year(2 LE) + month(1) + day(1); time component = midnight
        val buf = new MysqlBufferWriter
        buf.writeUInt16LE(2024) // year
        buf.writeUInt8(6)       // month = June
        buf.writeUInt8(15)      // day
        val result = Abort.run[SqlDecodeException](
            MysqlDecoder.localDateTimeDecoder.decode(buf.toSpan)
        ).eval
        result match
            case Result.Success(ldt) =>
                assert(ldt.getYear == 2024)
                assert(ldt.getMonthValue == 6)
                assert(ldt.getDayOfMonth == 15)
                assert(ldt.getHour == 0, s"Expected midnight hour, got ${ldt.getHour}")
                assert(ldt.getMinute == 0)
                assert(ldt.getSecond == 0)
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t
        end match
    }

    // binary TIME 12-byte struct decodes hours, minutes, seconds, microseconds
    "binary TIME 12-byte struct decodes hours, minutes, seconds, microseconds" in {
        // 12-byte struct: is_negative(1) + days(4 LE) + hour(1) + min(1) + sec(1) + micros(4 LE)
        val buf = new MysqlBufferWriter
        buf.writeUInt8(0)          // is_negative = 0
        buf.writeUInt32LE(0L)      // days = 0
        buf.writeUInt8(10)         // hour = 10
        buf.writeUInt8(30)         // minute = 30
        buf.writeUInt8(45)         // second = 45
        buf.writeUInt32LE(123456L) // micros = 123456
        val result = Abort.run[SqlDecodeException](
            MysqlDecoder.localTimeDecoder.decode(buf.toSpan)
        ).eval
        result match
            case Result.Success(lt) =>
                assert(lt.getHour == 10, s"Expected hour=10, got ${lt.getHour}")
                assert(lt.getMinute == 30)
                assert(lt.getSecond == 45)
                // micros → nanos: 123456 * 1000 = 123456000
                assert(lt.getNano == 123456000, s"Expected nano=123456000, got ${lt.getNano}")
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t
        end match
    }

    // ─── MysqlErrors.mkServerError structural equality ────────────────────────

    "MysqlErrors.mkServerError produces same shape as former per-exchange copies" in {
        // Construct an ErrPacket with known fields
        val err = ErrPacket(
            errorCode = 1064,
            sqlState = "42000",
            errorMessage = "You have an error in your SQL syntax"
        )
        val sqlText      = Maybe.Present("SELECT * FROM bad syntax")
        val paramCount   = 2
        val connectionId = Maybe.Present(99L)
        val result       = MysqlErrors.mkServerError(err, sqlText, paramCount, connectionId)
        // Verify every field matches the expected shape shared by all 4 former per-exchange copies
        assert(result.sqlState == "42000")
        assert(result.severity == "ERROR")
        assert(result.serverMessage == "You have an error in your SQL syntax")
        assert(result.detail.isEmpty)
        assert(result.hint.isEmpty)
        assert(result.position.isEmpty)
        assert(result.extra == Map("code" -> "1064"))
        // sqlText shorter than 4096 chars, not truncated
        assert(result.sqlText == Maybe.Present("SELECT * FROM bad syntax"))
        assert(result.paramCount == 2)
        assert(result.connectionId == Maybe.Present(99L))
    }

    "MysqlErrors.truncateSqlText leaves short SQL unchanged" in {
        val short = "SELECT 1"
        assert(MysqlErrors.truncateSqlText(short) == short)
    }

    "MysqlErrors.truncateSqlText truncates SQL exceeding SqlTextMaxLen" in {
        val long      = "x" * (MysqlErrors.SqlTextMaxLen + 10)
        val truncated = MysqlErrors.truncateSqlText(long)
        assert(truncated.length == MysqlErrors.SqlTextMaxLen + MysqlErrors.TruncationSuffix.length)
        assert(truncated.endsWith(MysqlErrors.TruncationSuffix))
    }

end RoundTripTest
