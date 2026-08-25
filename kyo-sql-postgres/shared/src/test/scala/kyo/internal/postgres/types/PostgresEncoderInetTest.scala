package kyo.internal.postgres.types
import kyo.*
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeInsufficientBytesException
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowCodec

/** Unit tests for the wire form a PostgreSQL `inet` column carries: family(1) + prefix_bits(1) + is_cidr(1) + addr_len(1) + addr_bytes(N).
  * family: 2 = IPv4, 3 = IPv6.
  *
  * kyo-sql ships no dedicated address type; a caller reaching for a native `inet` column uses [[kyo.PostgresTypes.custom]] with a plain
  * `String` and its own wire encoding, which is what [[inetColumn]] below builds. These tests pin the bytes that encoding has to produce
  * and consume; the address parsing itself (`::` compression, canonical rendering) is the caller's job, not this file's subject.
  */
class PostgresEncoderInetTest extends kyo.Test:

    private def encodeIPv4(dotted: String): Span[Byte] =
        val buf    = new PostgresBufferWriter
        val octets = dotted.split('.').map(s => s.toInt.toByte)
        buf.writeByte(2)  // family: IPv4
        buf.writeByte(32) // prefix_bits: full host
        buf.writeByte(0)  // is_cidr
        buf.writeByte(4)  // addr_len
        buf.writeBytes(Span.from(octets))
        buf.toSpan
    end encodeIPv4

    // No `::` compression: the groups here are always fully expanded, so splitting on `:` needs no run-length handling.
    private def encodeIPv6(expanded: String): Span[Byte] =
        val buf    = new PostgresBufferWriter
        val groups = expanded.split(':').map(g => Integer.parseInt(g, 16))
        buf.writeByte(3)          // family: IPv6
        buf.writeByte(128.toByte) // prefix_bits: full host, 128 does not fit a signed Byte
        buf.writeByte(0)          // is_cidr
        buf.writeByte(16)         // addr_len
        groups.foreach(g => buf.writeBytes(Span.from(Array[Byte](((g >>> 8) & 0xff).toByte, (g & 0xff).toByte))))
        buf.toSpan
    end encodeIPv6

    private def decodeBinary(bytes: Span[Byte])(using Frame): String =
        if bytes.size < 4 then throw SqlDecodeInsufficientBytesException("inet header", 4, bytes.size, 0)
        val family     = bytes(0).toInt & 0xff
        val prefixBits = bytes(1).toInt & 0xff
        val addrLen    = bytes(3).toInt & 0xff
        if bytes.size < 4 + addrLen then throw SqlDecodeInsufficientBytesException("inet address", addrLen, bytes.size - 4, 4)
        val hostWidth = addrLen * 8
        val address = family match
            case 2 =>
                val a = bytes(4) & 0xff
                val b = bytes(5) & 0xff
                val c = bytes(6) & 0xff
                val d = bytes(7) & 0xff
                s"$a.$b.$c.$d"
            case 3 =>
                (0 until 8).map { i =>
                    val hi = bytes(4 + i * 2) & 0xff
                    val lo = bytes(5 + i * 2) & 0xff
                    Integer.toHexString((hi << 8) | lo)
                }.mkString(":")
            case other =>
                throw new IllegalArgumentException(s"unknown inet address family: $other")
        // Dropping a narrower-than-host prefix is a silent value change rather than a lossy rendering:
        // `10.0.0.0/8` names 16 million addresses and its address part alone names one. A String carries the
        // suffix, so the codec keeps it rather than refusing the value.
        if prefixBits < hostWidth then s"$address/$prefixBits" else address
    end decodeBinary

    /** The custom single-column codec a caller writes for a native address column.
      *
      * The read branches on the format the carrier reports, which is what an `inet` column really needs: the same value arrives as these
      * bytes through the extended protocol and as `192.168.1.1` through a simple query.
      */
    private def inetColumn: SqlSchema.Column[String] =
        kyo.PostgresTypes.custom[String] { (addr, w) =>
            val bytes = if addr.indexOf(':') >= 0 then encodeIPv6(addr) else encodeIPv4(addr)
            w.extension(SqlCodec.Writer.Payload(PostgresEncoder.dialectId, "inet", Format.Binary, bytes))
        } { r =>
            val ext = r.nextExtension(PostgresEncoder.dialectId, "inet")
            ext.format match
                case Format.Binary => decodeBinary(ext.bytes)(using r.frame)
                case Format.Text   => new String(ext.bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
        }

    /** The bytes the real PostgreSQL writer puts on the wire for `addr`, under the builtin `inet` OID. */
    private def writeBytes(addr: String)(using kyo.test.AssertScope): Span[Byte] =
        val params = PostgresParamWriter.write(inetColumn, addr)
        assert(params.size == 1, s"expected one inet param, got ${params.size}")
        assert(params(0).encoder.oid == PostgresEncoder.OID_INET, s"expected the builtin inet OID, got ${params(0).encoder.oid}")
        params(0).encoded match
            case Maybe.Present(bytes) => bytes
            case Maybe.Absent         => fail("expected the inet param to carry bytes")
        end match
    end writeBytes

    /** Reads `bytes` back as an `inet` column arriving in `format`, through the real PostgreSQL row reader. */
    private def readAddr(bytes: Span[Byte], format: Format)(using kyo.test.AssertScope): String =
        val row = new SqlRow(
            Chunk(Maybe.Present(bytes)),
            Chunk(SqlRow.Column("addr", PostgresEncoder.OID_INET)),
            PostgresRowCodec(format)
        )
        Abort.run(row.decode[String](using summon[Frame], inetColumn)).eval match
            case Result.Success(addr) => addr
            case other                => fail(s"expected the inet column to decode, got $other")
        end match
    end readAddr

    // ── Encoder ──────────────────────────────────────────────────────────────

    "inet encodes IPv4 address as 8 bytes binary" in {
        val bytes = writeBytes("192.168.1.1")
        assert(bytes.size == 8)
        assert(bytes(0) == 2.toByte)  // family = IPv4
        assert(bytes(1) == 32.toByte) // prefix_bits = full host
        assert(bytes(2) == 0.toByte)  // is_cidr
        assert(bytes(3) == 4.toByte)  // addr_len
        assert(bytes.slice(4, 8).toArray.toSeq == Seq[Byte](192.toByte, 168.toByte, 1, 1))
    }

    "inet encodes IPv6 address as 20 bytes binary" in {
        val bytes = writeBytes("2001:0db8:0000:0000:0000:0000:0000:0001")
        assert(bytes.size == 20)
        assert(bytes(0) == 3.toByte)   // family = IPv6
        assert(bytes(1) == 128.toByte) // prefix_bits = full host
        assert(bytes(2) == 0.toByte)   // is_cidr
        assert(bytes(3) == 16.toByte)  // addr_len
        val expected = Seq[Byte](0x20, 0x01, 0x0d, 0xb8.toByte, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        assert(bytes.slice(4, 20).toArray.toSeq == expected)
    }

    // ── Decoder ──────────────────────────────────────────────────────────────

    "inet decodes IPv4 from binary" in {
        assert(decodeBinary(encodeIPv4("10.0.0.1")) == "10.0.0.1")
    }

    "inet decodes IPv6 from binary" in {
        assert(decodeBinary(encodeIPv6("fe80:0000:0000:0000:0000:0000:0000:0001")) == "fe80:0:0:0:0:0:0:1")
    }

    "inet round-trips through the custom column" in {
        val addr = "172.16.254.1"
        assert(readAddr(writeBytes(addr), Format.Binary) == addr)
    }

    "a text-format inet column reaches the text arm of the same codec" in {
        // Every column of a simple query arrives in text, and `192.168.1.1` read as the binary header would take
        // '1' as address family 49. Branching on the carrier's format is what a custom type does with it.
        val text = Span.from("192.168.1.1".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        assert(readAddr(text, Format.Text) == "192.168.1.1")
    }

    "inet decodes a narrower-than-host prefix as a suffix, rather than silently dropping it" in {
        // family=2 (IPv4), prefix_bits=24, is_cidr=0, addr_len=4: a `192.168.1.5/24` value, which only a
        // representation carrying the prefix can hold.
        val bytes = Span.from(Array[Byte](2.toByte, 24.toByte, 0.toByte, 4.toByte, 192.toByte, 168.toByte, 1.toByte, 5.toByte))
        assert(decodeBinary(bytes) == "192.168.1.5/24")
    }

    "inet decode with an unknown address family raises, naming the family" in {
        val badBytes = Span.from(Array[Byte](99.toByte, 32.toByte, 0.toByte, 4.toByte, 192.toByte, 168.toByte, 1.toByte, 1.toByte))
        val ex = intercept[IllegalArgumentException] {
            val _ = decodeBinary(badBytes)
        }
        assert(ex.getMessage.contains("99"), s"expected the family named, got: ${ex.getMessage}")
    }

    "inet binary shorter than its header raises insufficient bytes" in {
        val bytes = Span.from(Array[Byte](2.toByte, 32.toByte))
        val ex = intercept[SqlDecodeInsufficientBytesException] {
            val _ = decodeBinary(bytes)
        }
        assert((ex.expected, ex.actual, ex.position) == (4, 2, 0), s"got ${(ex.expected, ex.actual, ex.position)}")
    }

end PostgresEncoderInetTest
