package kyo.internal.postgres

import kyo.*
import kyo.SqlCodec.Format
import kyo.Test
import kyo.internal.postgres.types.PostgresDecoder

/** Verifies the equality [[kyo.SqlRow]] gets from carrying a [[PostgresRowCodec]], and how `columnDecoded` classifies what a decoder throws.
  *
  * A row holds its column payloads as `Span`s, whose own equality is by reference, so two rows read from the same result set would compare
  * unequal unless the row compares its columns by content. Callers do compare rows (a test asserting a query returned exactly the rows it
  * expected is the common case), which is what makes this a property of the row rather than an incidental detail.
  *
  * The decode leaves drive `columnDecoded` with a decoder that throws, which is the only way to reach its classification: a real decoder
  * fails on bytes, and the point here is what happens to the throwable rather than which bytes produced it.
  */
class PostgresRowCodecTest extends Test:

    // `java.time.LocalDate` is a JDK type with no `CanEqual` instance in scope; its `equals` compares the date
    // fields, which is the comparison the guarded-read assertions below rely on.
    given CanEqual[java.time.LocalDate, java.time.LocalDate] = CanEqual.canEqualAny

    private val column = SqlRow.Column("id", 23)
    private val codec  = PostgresRowCodec(Format.Text)

    private def throwing(t: => Throwable): PostgresDecoder[Int] =
        new PostgresDecoder[Int]:
            def oids: Set[Int]                                                            = Set(23)
            def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Int = throw t

    private val oneColumnRow =
        new SqlRow(Chunk(Maybe.Present(Span.from(Array[Byte](1)))), Chunk(column), codec)

    "two rows whose single column is NULL compare equal" in {
        val first  = new SqlRow(Chunk(Maybe.Absent), Chunk(column), codec)
        val second = new SqlRow(Chunk(Maybe.Absent), Chunk(column), codec)
        assert(first == second)
    }

    // Distinct Span instances over identical bytes: reference equality would call these different rows.
    "two rows whose column payloads carry identical bytes compare equal" in {
        val first  = new SqlRow(Chunk(Maybe.Present(Span.from(Array[Byte](1, 2, 3)))), Chunk(column), codec)
        val second = new SqlRow(Chunk(Maybe.Present(Span.from(Array[Byte](1, 2, 3)))), Chunk(column), codec)
        assert(first == second)
    }

    "two rows whose column payloads differ by one byte compare unequal" in {
        val first  = new SqlRow(Chunk(Maybe.Present(Span.from(Array[Byte](1, 2, 3)))), Chunk(column), codec)
        val second = new SqlRow(Chunk(Maybe.Present(Span.from(Array[Byte](1, 2, 4)))), Chunk(column), codec)
        assert(first != second)
    }

    // An `Error` is what a catch of `Exception` cannot see, so catching `Exception` alone would let it leave the
    // method as a raw throw, past the effect system and past the `Abort[SqlDecodeException]` the scaladoc promises.
    // `AssertionError` is the discriminating case: it is an `Error`, invisible to a catch of `Exception`, yet
    // `NonFatal` accepts it, so it is a failure a caller can act on rather than one the runtime must not survive.
    "a decoder that throws an Error the runtime can recover from arrives on the declared channel" in {
        val raised                 = new AssertionError("boom")
        given PostgresDecoder[Int] = throwing(raised)
        Abort.run[SqlDecodeException](PostgresRowCodec.columnDecoded[Int](oneColumnRow, 0)).eval match
            case Result.Failure(e: SqlDecodeColumnDecodeException) =>
                assert(e.columnIndex == Maybe.Present(0))
                e.cause match
                    case t: Throwable => assert(t eq raised)
                    case s: String    => fail(s"expected the raised error as the cause, got the string '$s'")
            case other => fail(s"expected a column-decode failure naming column 0, got $other")
        end match
    }

    // The other side of the same classification: an `InterruptedException` IS an `Exception`, so catching `Exception`
    // would wrap it as a `SqlDecodeColumnDecodeException` and let a caller handling `SqlDecodeException` by type
    // recover from a cancellation. `NonFatal` excludes it, and kyo's own policy for a throwable `NonFatal` excludes is
    // that it is never carried as a value (`Result.Panic.apply` rethrows it), so it propagates and no
    // `Abort.run[SqlDecodeException]` can absorb it.
    "an interrupt raised by a decoder propagates instead of being reported as a decode failure" in {
        val interrupt              = new InterruptedException()
        given PostgresDecoder[Int] = throwing(interrupt)
        val thrown = intercept[InterruptedException] {
            val _ = Abort.run[SqlDecodeException](PostgresRowCodec.columnDecoded[Int](oneColumnRow, 0)).eval
        }
        assert(thrown eq interrupt)
    }

    // The index is the whole reason this entry point exists separately from the schema-driven path, so routing it
    // through the shared helper must not trade it away.
    "a decoder that throws an ordinary exception names the column it failed on" in {
        val raised                 = new IllegalStateException("boom")
        given PostgresDecoder[Int] = throwing(raised)
        Abort.run[SqlDecodeException](PostgresRowCodec.columnDecoded[Int](oneColumnRow, 0)).eval match
            case Result.Failure(e: SqlDecodeColumnDecodeException) =>
                assert(e.columnIndex == Maybe.Present(0))
                e.cause match
                    case t: Throwable => assert(t eq raised)
                    case s: String    => fail(s"expected the raised exception as the cause, got the string '$s'")
            case other => fail(s"expected a column-decode failure naming column 0, got $other")
        end match
    }

    // An INTERVAL is the one column kind no Scala type spans: `java.time.Duration` carries the time part and
    // `java.time.Period` the calendar part, and a value is free to carry both. Rendering it at either type refused
    // ordinary values, so `interval '3 days'` and `interval '1 mon'` aborted a call documented to render whatever the
    // column holds, and `interval '1 day 3 hours'` had no reading at all. The rendering is over the wire fields, so
    // there is nothing to drop and nothing to refuse.
    "an interval renders as text whatever mix of calendar and time components it carries" - {
        // Wire: Int64 microseconds, Int32 days, Int32 months, all big-endian.
        def intervalRow(months: Int, days: Int, micros: Long): SqlRow =
            val out = new Array[Byte](16)
            var i   = 0
            while i < 8 do
                out(i) = ((micros >>> ((7 - i) * 8)) & 0xffL).toByte
                i += 1
            while i < 12 do
                out(i) = ((days >>> ((11 - i) * 8)) & 0xff).toByte
                i += 1
            while i < 16 do
                out(i) = ((months >>> ((15 - i) * 8)) & 0xff).toByte
                i += 1
            new SqlRow(
                Chunk(Maybe.Present(Span.from(out))),
                Chunk(SqlRow.Column("span", 1186)),
                PostgresRowCodec(Format.Binary)
            )
        end intervalRow

        def rendered(months: Int, days: Int, micros: Long)(using kyo.test.AssertScope): Maybe[String] =
            Abort.run[SqlDecodeException](intervalRow(months, days, micros).text(0)).eval match
                case Result.Success(v) => v
                case other             => fail(s"expected a rendering, got $other")

        "a time-only interval" in {
            assert(rendered(0, 0, 3_600_000_000L) == Present("PT1H"))
            assert(rendered(0, 0, 14_706_000_000L) == Present("PT4H5M6S"))
        }

        "a calendar-only interval, which a Duration cannot hold" in {
            assert(rendered(0, 3, 0L) == Present("P3D"))
            assert(rendered(1, 0, 0L) == Present("P1M"))
            assert(rendered(14, 0, 0L) == Present("P1Y2M"))
        }

        "an interval carrying both, which neither type holds" in {
            assert(rendered(14, 3, 14_706_000_000L) == Present("P1Y2M3DT4H5M6S"))
            assert(rendered(0, 1, 10_800_000_000L) == Present("P1DT3H"))
        }

        "the zero interval" in {
            assert(rendered(0, 0, 0L) == Present("PT0S"))
        }

        "a negative interval carries the sign on every component, as the server's own rendering does" in {
            assert(rendered(-14, -3, -14_706_000_000L) == Present("P-1Y-2M-3DT-4H-5M-6S"))
        }

        "fractional seconds, and only when there are any" in {
            assert(rendered(0, 0, 1_500_000L) == Present("PT1.5S"))
            assert(rendered(0, 0, 500_000L) == Present("PT0.5S"))
            assert(rendered(0, 0, -500_000L) == Present("PT-0.5S"))
            assert(rendered(0, 0, 2_000_000L) == Present("PT2S"))
            assert(rendered(0, 0, 1_000_001L) == Present("PT1.000001S"))
        }
    }

    // An `inet` is a binary struct on the wire, so rendering it as UTF-8 answers the struct's bytes as mojibake. The
    // column is one this backend names, and `decode[String]` refuses it precisely because those bytes are not text,
    // which leaves `text` as the route a caller with no row type has: it has to render what the column holds.
    "an inet renders as the address it holds, not as its wire struct" - {
        // Wire: family (2 = IPv4, 3 = IPv6), netmask bits, is_cidr, address length, then the address bytes.
        def inetRow(family: Int, bits: Int, addr: Array[Int]): SqlRow =
            val out = new Array[Byte](4 + addr.length)
            out(0) = family.toByte
            out(1) = bits.toByte
            out(2) = 0
            out(3) = addr.length.toByte
            var i = 0
            while i < addr.length do
                out(4 + i) = (addr(i) & 0xff).toByte
                i += 1
            new SqlRow(
                Chunk(Maybe.Present(Span.from(out))),
                Chunk(SqlRow.Column("addr", 869)),
                PostgresRowCodec(Format.Binary)
            )
        end inetRow

        def rendered(family: Int, bits: Int, addr: Array[Int])(using kyo.test.AssertScope): Maybe[String] =
            Abort.run[SqlDecodeException](inetRow(family, bits, addr).text(0)).eval match
                case Result.Success(v) => v
                case other             => fail(s"expected a rendering, got $other")

        def v6(groups: Int*): Array[Int] =
            groups.flatMap(g => Seq((g >> 8) & 0xff, g & 0xff)).toArray

        "an IPv4 host address carries no mask" in {
            assert(rendered(2, 32, Array(192, 168, 1, 1)) == Present("192.168.1.1"))
        }

        "an IPv4 network keeps the mask it was given" in {
            assert(rendered(2, 24, Array(192, 168, 1, 0)) == Present("192.168.1.0/24"))
            assert(rendered(2, 0, Array(0, 0, 0, 0)) == Present("0.0.0.0/0"))
        }

        "an IPv6 address is compressed at its longest run of zero groups" in {
            assert(rendered(3, 128, v6(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1)) == Present("2001:db8::1"))
            assert(rendered(3, 128, v6(0, 0, 0, 0, 0, 0, 0, 1)) == Present("::1"))
            assert(rendered(3, 128, v6(0, 0, 0, 0, 0, 0, 0, 0)) == Present("::"))
            assert(rendered(3, 64, v6(0x2001, 0x0db8, 0, 0, 0, 0, 0, 0)) == Present("2001:db8::/64"))
        }

        // A single zero group is written out: `::` for one group is not shorter, and RFC 5952 forbids it, so a
        // renderer that compressed every run would disagree with the server's own text for the same value.
        "a single zero group is not compressed, and the leftmost longest run wins" in {
            assert(rendered(3, 128, v6(0x2001, 0, 0x2001, 1, 1, 1, 1, 1)) == Present("2001:0:2001:1:1:1:1:1"))
            assert(rendered(3, 128, v6(1, 0, 0, 1, 0, 0, 0, 1)) == Present("1:0:0:1::1"))
        }
    }

    "a decoder that throws a decode leaf surfaces the leaf, not a wrapper" in {
        val leaf                   = SqlDecodeNumericException("boom", SqlDecodeNumericException.Subtype.Parse)
        given PostgresDecoder[Int] = throwing(leaf)
        Abort.run[SqlDecodeException](PostgresRowCodec.columnDecoded[Int](oneColumnRow, 0)).eval match
            case Result.Failure(e) => assert(e eq leaf, s"expected the leaf itself, got $e")
            case other             => fail(s"expected the decode leaf unwrapped, got $other")
    }

    // ---- The struct-typed built-ins the refusal list did not name ------------------------------------
    //
    // The refusal was curated from the types this module can encode, which is not the set a SELECT can
    // return. Every OID below is a fixed built-in whose binary form is a struct, so a String read of one
    // answered the protocol buffer as UTF-8. `cidr` is the sharpest of them: it shares its wire struct with
    // `inet`, the one type this change went out of its way to render field by field.

    "a String read of a struct-typed built-in is refused, naming the type" - {
        val cases = Seq(
            650  -> "cidr",
            829  -> "macaddr",
            790  -> "money",
            1560 -> "bit",
            3904 -> "int4range",
            600  -> "point",
            3614 -> "tsvector"
        )
        cases.foreach { (oid, name) =>
            s"$name (OID $oid)" in {
                val ex = intercept[SqlDecodeColumnTypeMismatchException](
                    PostgresDecoder.requireTextColumn("String", oid)
                )
                assert(ex.columnType == name, s"expected the failure to name $name, got ${ex.columnType}")
                assert(ex.typeToken == oid.toString, s"expected the token $oid, got ${ex.typeToken}")
            }
        }
    }

    "a String read of any array column is refused, not only the three this module encodes" - {
        // Every array shares one binary layout, a header then the elements, so the element type cannot make
        // one readable. text[] was refused and varchar[] was not, which is the same struct either way.
        val cases = Seq(1016 -> "int8[]", 1015 -> "varchar[]", 1000 -> "bool[]", 2951 -> "uuid[]", 1185 -> "timestamptz[]")
        cases.foreach { (oid, name) =>
            s"$name (OID $oid)" in {
                val ex = intercept[SqlDecodeColumnTypeMismatchException](
                    PostgresDecoder.requireTextColumn("String", oid)
                )
                assert(ex.columnType == name, s"expected the failure to name $name, got ${ex.columnType}")
            }
        }
    }

    "the text-shaped built-ins are still read as text" in {
        // The other half: `name`, `"char"` and `xml` carry their characters on the wire, so naming them in the
        // table must not refuse them. A catalog query selecting relname is the ordinary case.
        Seq(19, 18, 142).foreach { oid =>
            PostgresDecoder.requireTextColumn("String", oid)
        }
        succeed
    }

    "an OID the backend cannot name is still not refused" in {
        // The dynamic range: citext, an enum type, a domain. A token with no known meaning is not evidence of
        // a mismatch, and refusing it would break every extension type.
        PostgresDecoder.requireTextColumn("String", 16384)
        PostgresDecoder.requireTextColumn("String", 999999)
        succeed
    }

    // ---- A typed read of the wrong column, which cannot fail on its own -------------------------------
    //
    // The text guard closed one direction: a String field over an int4. The other direction is the same
    // defect and answers something worse than mojibake. `date` binary is an int4 day count from 2000-01-01,
    // so a LocalDate field over an int4 column holding 42 read the four bytes at the layout it expected and
    // answered 2000-02-12, a well-formed date with nothing to notice. `time` and `timestamptz` are int64s
    // and do the same. These decoders resolve their layout from the target type, never from the column, so
    // the column has to be checked against what the decoder claims.

    private def readerOn(oid: Int, bytes: Array[Byte], format: Format): PostgresRowReader =
        val row = new SqlRow(Chunk(Maybe.Present(Span.from(bytes))), Chunk(SqlRow.Column("c", oid)), PostgresRowCodec(format))
        new PostgresRowReader(row, format, Maybe.empty)
    end readerOn

    /** 42 as PostgreSQL sends an int4: four bytes, big-endian. */
    private val int4Of42 = Array[Byte](0, 0, 0, 42)

    "a LocalDate read of an int4 column is refused rather than answering a day count" in {
        val ex = intercept[SqlDecodeColumnTypeMismatchException](readerOn(23, int4Of42, Format.Binary).nextDate())
        assert(ex.columnType == "int4", s"expected the failure to name int4, got ${ex.columnType}")
        assert(ex.scalaType == "LocalDate", s"expected the failure to name LocalDate, got ${ex.scalaType}")
    }

    "a LocalTime and an Instant read of an int8 column are refused" in {
        val int8 = Array[Byte](0, 0, 0, 0, 0, 0, 0, 1)
        assert(intercept[SqlDecodeColumnTypeMismatchException](readerOn(20, int8, Format.Binary).nextTime()).columnType == "int8")
        assert(intercept[SqlDecodeColumnTypeMismatchException](readerOn(20, int8, Format.Binary).instant()).columnType == "int8")
    }

    "a UUID read of a bytea column is refused, and bytes of a uuid column" in {
        val sixteen = Array.fill[Byte](16)(0)
        assert(intercept[SqlDecodeColumnTypeMismatchException](readerOn(17, sixteen, Format.Binary).nextUuid()).columnType == "bytea")
        assert(intercept[SqlDecodeColumnTypeMismatchException](readerOn(2950, sixteen, Format.Binary).bytes()).columnType == "uuid")
    }

    "each guarded read still accepts the column it is for" in {
        // The negative control. A guard that refuses everything would pass every leaf above.
        assert(readerOn(1082, int4Of42, Format.Binary).nextDate() == java.time.LocalDate.of(2000, 2, 12), "a date column must still decode")
        assert(readerOn(17, Array[Byte](1, 2), Format.Binary).bytes().size == 2, "a bytea column must still decode")
        succeed
    }

    "a nested value carries no column OID and is not refused" in {
        // An array element or a range bound reaches a decoder with OID_UNSPECIFIED, because the column's own
        // OID describes the container. Refusing on it would break every array read.
        assert(readerOn(0, int4Of42, Format.Binary).nextDate() == java.time.LocalDate.of(2000, 2, 12), "an unspecified OID must not refuse")
    }

    "an OID the backend cannot name is not refused by the typed guard either" in {
        // A domain or an extension type, which this connection cannot resolve. Same rule as the text guard.
        assert(readerOn(16384, int4Of42, Format.Binary).nextDate() == java.time.LocalDate.of(2000, 2, 12), "an unnamed OID must not refuse")
    }

end PostgresRowCodecTest
