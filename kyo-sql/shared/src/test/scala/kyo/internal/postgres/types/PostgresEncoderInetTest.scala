package kyo.internal.postgres.types

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kyo.*
import kyo.SqlException
import kyo.internal.postgres.PostgresBufferWriter

/** Unit tests for the PG inet binary codec (OID 869).
  *
  * Wire format: family(1) + prefix_bits(1) + is_cidr(1) + addr_len(1) + addr_bytes(N). family: 2 = IPv4 (PGSQL_AF_INET), 3 = IPv6
  * (PGSQL_AF_INET6).
  */
class PostgresEncoderInetTest extends kyo.Test:

    given CanEqual[InetAddress, InetAddress] = CanEqual.canEqualAny

    // Helper: encode an InetAddress to bytes using inetBinary.
    private def encode(value: InetAddress): Span[Byte] =
        val buf = new PostgresBufferWriter
        PostgresEncoder.inetBinary.write(value, buf)
        buf.toSpan
    end encode

    // Helper: decode bytes as InetAddress in the given format.
    private def decode(format: Format, bytes: Span[Byte]): InetAddress =
        PostgresDecoder.inet.read(format, bytes)

    // ── Encoder ──────────────────────────────────────────────────────────────

    "inet encodes IPv4 address as 8 bytes binary" in {
        val addr  = InetAddress.getByName("192.168.1.1")
        val bytes = encode(addr)
        // Total: 4 header bytes + 4 address bytes = 8 bytes
        assert(bytes.size == 8)
        // family byte = 2 (PGSQL_AF_INET)
        assert(bytes(0) == 2.toByte)
        // prefix bits = 32 (full IPv4 host)
        assert(bytes(1) == 32.toByte)
        // is_cidr = 0 (inet, not cidr)
        assert(bytes(2) == 0.toByte)
        // addr_len = 4 (IPv4 is 4 bytes)
        assert(bytes(3) == 4.toByte)
        // address bytes match
        val rawAddr = addr.getAddress
        assert(bytes(4) == rawAddr(0))
        assert(bytes(5) == rawAddr(1))
        assert(bytes(6) == rawAddr(2))
        assert(bytes(7) == rawAddr(3))
    }

    "inet encodes IPv6 address as 20 bytes binary" in {
        val addr  = InetAddress.getByName("2001:db8::1")
        val bytes = encode(addr)
        // Total: 4 header bytes + 16 address bytes = 20 bytes
        assert(bytes.size == 20)
        // family byte = 3 (PGSQL_AF_INET6)
        assert(bytes(0) == 3.toByte)
        // prefix bits = 128 (full IPv6 host)
        assert(bytes(1) == 128.toByte)
        // is_cidr = 0
        assert(bytes(2) == 0.toByte)
        // addr_len = 16 (IPv6 is 16 bytes)
        assert(bytes(3) == 16.toByte)
        // address bytes match
        val rawAddr = addr.getAddress
        var i       = 0
        while i < 16 do
            assert(bytes(4 + i) == rawAddr(i))
            i += 1
        end while
        assert(i == 16) // all 16 address bytes verified
    }

    // ── Decoder ──────────────────────────────────────────────────────────────

    "inet decodes IPv4 from binary" in {
        val addr    = InetAddress.getByName("10.0.0.1")
        val bytes   = encode(addr)
        val decoded = decode(Format.Binary, bytes)
        assert(java.util.Arrays.equals(decoded.getAddress, addr.getAddress))
    }

    "inet decodes IPv6 from binary" in {
        val addr    = InetAddress.getByName("fe80::1")
        val bytes   = encode(addr)
        val decoded = decode(Format.Binary, bytes)
        assert(java.util.Arrays.equals(decoded.getAddress, addr.getAddress))
    }

    "inet round-trips through encode + decode" in {
        val ipv4     = InetAddress.getByName("172.16.254.1")
        val bytes4   = encode(ipv4)
        val decoded4 = decode(Format.Binary, bytes4)
        assert(java.util.Arrays.equals(decoded4.getAddress, ipv4.getAddress))

        val ipv6     = InetAddress.getByName("::1")
        val bytes6   = encode(ipv6)
        val decoded6 = decode(Format.Binary, bytes6)
        assert(java.util.Arrays.equals(decoded6.getAddress, ipv6.getAddress))
    }

    "inet decodes from text representation" in {
        val ipv4Bytes = Span.from("192.168.0.1".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val decoded4  = decode(Format.Text, ipv4Bytes)
        assert(java.util.Arrays.equals(decoded4.getAddress, InetAddress.getByName("192.168.0.1").getAddress))

        val ipv6Bytes = Span.from("::1".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val decoded6  = decode(Format.Text, ipv6Bytes)
        assert(java.util.Arrays.equals(decoded6.getAddress, InetAddress.getByName("::1").getAddress))
    }

    "inet decode with unknown address family raises Decode" in {
        // Construct a hand-crafted binary payload with family = 99 (unknown).
        val badBytes = Span.from(Array[Byte](
            99.toByte, // unknown family
            32.toByte, // prefix_bits
            0.toByte,  // is_cidr
            4.toByte,  // addr_len
            192.toByte,
            168.toByte,
            1.toByte,
            1.toByte // addr bytes
        ))
        try
            val _ = decode(Format.Binary, badBytes)
            assert(false, "Expected SqlException.Decode to be thrown")
        catch
            case ex: SqlException.Decode =>
                assert(ex.getMessage.contains("unknown address family"))
        end try
    }

    // ── Phase 15 audit W-1 + W-2: SqlSchema[InetAddress] usable as a case-class field ───
    //
    // Before the Phase 9b SerializationMacro change, `Schema.derived[Row]` for a case class with an
    // `InetAddress` field failed because `isPrimitiveType` did not list InetAddress. The macro now
    // dispatches InetAddress to the `writer.string(v.getHostAddress)` / `InetAddress.getByName(reader.string())`
    // primitive arms, parallel to UUID. The MySQL path is also exercised here — the string fallback
    // (`mw.string(v.getHostAddress)`) is what `SqlSchema[InetAddress]` writes against a MysqlParamWriter.

    "Schema.derived[CaseClassWithInet] writes the InetAddress field via the string primitive (PG)" in {
        // Wire a Schema[InetAddress] locally via the string primitive so `derives kyo.Schema` can find it
        // when synthesising the Host schema — mirrors what SqlSchema[InetAddress] does under the hood.
        given kyo.Schema[java.net.InetAddress] =
            kyo.Schema.stringSchema.transform(java.net.InetAddress.getByName)(_.getHostAddress)
        case class Host(name: String, addr: java.net.InetAddress) derives kyo.Schema
        given kyo.SqlSchema[Host] = kyo.SqlSchema.derived

        val schema = kyo.SqlSchema[Host]
        val value  = Host("api", InetAddress.getByName("192.168.1.42"))
        val params = schema.writePostgres(value)
        assert(params.size == 2, s"expected 2 BoundParams (name + addr), got ${params.size}")
        // The derived path emits the InetAddress field as a string param (PG's textText OID 25), NOT
        // as the binary `inetBinary` (OID 869). This pins the SerializationMacro primitive routing.
        assert(params(0).oid == PostgresEncoder.OID_TEXT, "first param (name) routes to textText")
        assert(params(1).oid == PostgresEncoder.OID_TEXT, "addr field routes through string primitive")
        params(1).value match
            case kyo.Maybe.Present(s: String) =>
                assert(s == "192.168.1.42", s"expected addr's hostAddress string, got '$s'")
            case other => fail(s"expected Present string for InetAddress field, got $other")
        end match
    }

    "Schema.derived[CaseClassWithInet] writes the InetAddress field via mw.string (MySQL VARCHAR(45) path)" in {
        given kyo.Schema[java.net.InetAddress] =
            kyo.Schema.stringSchema.transform(java.net.InetAddress.getByName)(_.getHostAddress)
        case class Host(name: String, addr: java.net.InetAddress) derives kyo.Schema
        given kyo.SqlSchema[Host] = kyo.SqlSchema.derived

        val schema = kyo.SqlSchema[Host]
        val value  = Host("dual", InetAddress.getByName("2001:db8::42"))
        val params = schema.writeMysql(value)
        assert(params.size == 2)
        params(1).value match
            case kyo.Maybe.Present(s: String) =>
                // IPv6 hostAddress for "2001:db8::42" is "2001:db8:0:0:0:0:0:42" — fits in VARCHAR(45).
                assert(s.length <= 45, s"VARCHAR(45) constraint: address text must fit, got ${s.length} chars: $s")
                assert(InetAddress.getByName(s).equals(value.addr), s"VARCHAR(45) string must round-trip to the same InetAddress")
            case other => fail(s"expected Present string for InetAddress field, got $other")
        end match
    }

    // ── Phase 15 audit W-3: PG cidr (OID 650) wire codec ────────────────────────────────
    //
    // cidr shares inet's binary wire format with `is_cidr = 1`. java.net.InetAddress has no prefix
    // representation, so we emit the host bits (/32 or /128). The decoder rejects text values with
    // a non-host prefix mask (e.g. "192.168.1.0/24") via SqlException.Decode.

    "cidrBinary encodes IPv4 as 8 bytes with is_cidr=1" in {
        val addr = InetAddress.getByName("10.0.0.5")
        val buf  = new kyo.internal.postgres.PostgresBufferWriter
        PostgresEncoder.cidrBinary.write(addr, buf)
        val bytes = buf.toSpan
        assert(bytes.size == 8, s"expected 8 bytes (4 header + 4 addr), got ${bytes.size}")
        assert(bytes(0) == 2.toByte, "family == PGSQL_AF_INET (2)")
        assert(bytes(1) == 32.toByte, "prefix == 32 (full IPv4 host)")
        assert(bytes(2) == 1.toByte, "is_cidr == 1")
        assert(bytes(3) == 4.toByte, "addr_len == 4")
        assert(java.util.Arrays.equals(bytes.slice(4, 8).toArray, addr.getAddress))
    }

    "cidr binary round-trips through encode + decode" in {
        val addr = InetAddress.getByName("172.20.0.1")
        val buf  = new kyo.internal.postgres.PostgresBufferWriter
        PostgresEncoder.cidrBinary.write(addr, buf)
        val decoded = PostgresDecoder.cidr.read(Format.Binary, buf.toSpan)
        assert(java.util.Arrays.equals(decoded.getAddress, addr.getAddress))
    }

    "cidr text decodes a /32 host route" in {
        val bytes   = Span.from("10.1.2.3/32".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val decoded = PostgresDecoder.cidr.read(Format.Text, bytes)
        assert(java.util.Arrays.equals(decoded.getAddress, InetAddress.getByName("10.1.2.3").getAddress))
    }

    "cidr text decodes a bare address (no /prefix)" in {
        val bytes   = Span.from("10.1.2.3".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val decoded = PostgresDecoder.cidr.read(Format.Text, bytes)
        assert(java.util.Arrays.equals(decoded.getAddress, InetAddress.getByName("10.1.2.3").getAddress))
    }

    "cidr text raises SqlException.Decode for a non-host network prefix" in {
        val bytes = Span.from("192.168.1.0/24".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        try
            val _ = PostgresDecoder.cidr.read(Format.Text, bytes)
            assert(false, "Expected SqlException.Decode for non-host CIDR prefix")
        catch
            case ex: SqlException.Decode =>
                assert(ex.getMessage.contains("non-host prefix"))
        end try
    }

end PostgresEncoderInetTest
