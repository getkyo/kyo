package kyo.internal.sqlite

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Pins which STORAGE CLASS every kind of bound value lands in.
  *
  * A declared column's affinity rewrites what it is given, so a value bound in the wrong class is converted rather than refused, and
  * silently: binding a decimal as a double loses its scale before the column ever sees it, and binding text that looks integral makes a
  * later division truncate. Each leaf therefore asserts the exact `Param` variant, never that a bind merely happened.
  */
class SqliteParamWriterTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private def one(write: SqliteParamWriter => Unit)(using kyo.test.AssertScope): SqliteParamWriter.Param =
        val w = new SqliteParamWriter(summon[Frame])
        write(w)
        val ps = w.params
        assert(ps.size == 1, s"expected exactly one bound parameter, got ${ps.size}")
        ps.head
    end one

    private def textOf(p: SqliteParamWriter.Param)(using kyo.test.AssertScope): String =
        p match
            case SqliteParamWriter.Param.Text(v) => v
            case other                           => fail(s"expected a text bind, got $other")

    "every integral width binds as an integer, widened to the one SQLite stores" in {
        assert(one(_.int(7)) == SqliteParamWriter.Param.Integer(7L))
        assert(one(_.long(7L)) == SqliteParamWriter.Param.Integer(7L))
        assert(one(_.short(7.toShort)) == SqliteParamWriter.Param.Integer(7L))
        assert(one(_.byte(7.toByte)) == SqliteParamWriter.Param.Integer(7L))
    }

    // Widening a float to a double is lossless, and the narrowing on the way back recovers the bit pattern.
    "a float is widened to the double SQLite stores" in {
        assert(one(_.float(0.5f)) == SqliteParamWriter.Param.Real(0.5))
        assert(one(_.double(0.5)) == SqliteParamWriter.Param.Real(0.5))
    }

    // A BOOLEAN declaration takes NUMERIC affinity, so the value binds as the integer it will be stored as.
    "a boolean binds as the integer it is stored as, not as the word" in {
        assert(one(_.boolean(true)) == SqliteParamWriter.Param.Integer(1L))
        assert(one(_.boolean(false)) == SqliteParamWriter.Param.Integer(0L))
    }

    "bytes bind as a blob, and null binds as null" in {
        val bytes = Span.from(Array[Byte](1, 2, 3))
        assert(one(_.bytes(bytes)) == SqliteParamWriter.Param.Blob(bytes))
        assert(one(_.nil()) == SqliteParamWriter.Param.Null)
    }

    // sqlite3_bind_double stores NaN as NULL and reports success, so binding it would turn a value into an absent one
    // with nothing red.
    "NaN is refused rather than bound, because the C call would store NULL and report success" in {
        val w = new SqliteParamWriter(summon[Frame])
        intercept[SqliteNaNNotStorableException](w.double(Double.NaN))
        assert(w.params.isEmpty, "a refused bind must leave nothing behind")
        // The infinities are storable and are NOT refused: SQLite round-trips them.
        assert(one(_.double(Double.PositiveInfinity)) == SqliteParamWriter.Param.Real(Double.PositiveInfinity))
        assert(one(_.double(Double.NegativeInfinity)) == SqliteParamWriter.Param.Real(Double.NegativeInfinity))
    }

    // Text is coerced by what it LOOKS like: `4` parses as an integer, and a column whose stored text also looks
    // integral then divides by it integrally, so a stored 10 over a bound 4 answers 2 rather than 2.5.
    "a decimal binds as text and always carries a decimal point" in {
        assert(textOf(one(_.bigDecimal(BigDecimal("4")))) == "4.0")
        assert(textOf(one(_.bigDecimal(BigDecimal("4.25")))) == "4.25")
        assert(textOf(one(_.bigDecimal(BigDecimal("0.12345678901234567890")))) == "0.12345678901234567890")
    }

    "a big integer binds as an integer while it fits, and as text once it does not" in {
        assert(one(_.bigInt(BigInt(7))) == SqliteParamWriter.Param.Integer(7L))
        assert(one(_.bigInt(BigInt(Long.MaxValue))) == SqliteParamWriter.Param.Integer(Long.MaxValue))
        // Past 64 bits an integer bind would silently become a float, so it goes as text instead.
        val huge = BigInt(Long.MaxValue) + 1
        assert(textOf(one(_.bigInt(huge))) == huge.toString)
    }

    "strings, chars, json and uuids all bind as text" in {
        assert(textOf(one(_.string("abc"))) == "abc")
        assert(textOf(one(_.char('x'))) == "x")
        assert(textOf(one(_.json("""{"a":1}"""))) == """{"a":1}""")
        val id = java.util.UUID.fromString("00000000-0000-4000-8000-000000000001")
        assert(textOf(one(_.uuid(id))) == "00000000-0000-4000-8000-000000000001")
    }

    // The rendering counts a BC year WITHIN its era, so 44 BC is written 0044 with an era rather than the proleptic -43.
    "a BC date is rendered within its era rather than as a non-positive year" in {
        val bc = textOf(one(_.date(java.time.LocalDate.of(-43, 3, 15))))
        assert(bc.startsWith("0044"), bc)
        assert(bc.endsWith(" BC"), bc)
        val ad = textOf(one(_.date(java.time.LocalDate.of(2024, 3, 15))))
        assert(ad == "2024-03-15", ad)
    }

    "arrays bind as the JSON the reader parses back" in {
        assert(textOf(one(_.arrayOfInt(Chunk(1, 2, 3)))) == "[1,2,3]")
        assert(textOf(one(_.arrayOfString(Chunk("a", "b")))) == """["a","b"]""")
        assert(textOf(one(_.arrayOfJson(Chunk("""{"a":1}""", "2")))) == """[{"a":1},2]""")
    }

    "an extension payload for this dialect binds as a blob carrying its bytes unchanged" in {
        val payload = SqlCodec.Writer.Payload(SqliteParamWriter.DialectId, "geometry", SqlCodec.Format.Binary, Span.from(Array[Byte](9, 9)))
        // Compared by content: Span equality is by reference, so two spans over equal arrays are never ==.
        one(_.extension(payload)) match
            case SqliteParamWriter.Param.Blob(span) => assert(span.toArray.toSeq == Seq[Byte](9, 9))
            case other                              => fail(s"an extension payload must bind as a blob, got $other")
    }

    "an extension payload built for another engine is refused, naming both dialects" in {
        val w       = new SqliteParamWriter(summon[Frame])
        val payload = SqlCodec.Writer.Payload(Idiom.Id("postgres"), "geometry", SqlCodec.Format.Binary, Span.from(Array[Byte](9)))
        val ex      = intercept[SqlUnsupportedTypeOnBackendException](w.extension(payload))
        assert(ex.getMessage.nn.contains("postgres"), ex.getMessage.nn)
        assert(ex.getMessage.nn.contains("sqlite"), ex.getMessage.nn)
        assert(w.params.isEmpty)
    }

    // There is one wire format here, so a param's bytes are its text unless it is a blob. Null is the only variant
    // with no bytes at all, which is what distinguishes an absent value from an empty one.
    "bytesOf renders each class, and only null has no bytes" in {
        def utf8(s: String) = s.getBytes(StandardCharsets.UTF_8).nn.toSeq
        assert(SqliteParamWriter.bytesOf(SqliteParamWriter.Param.Null).isEmpty)
        assert(SqliteParamWriter.bytesOf(SqliteParamWriter.Param.Integer(7L)).get.toArray.toSeq == utf8("7"))
        assert(SqliteParamWriter.bytesOf(SqliteParamWriter.Param.Text("hi")).get.toArray.toSeq == utf8("hi"))
        val blob = Span.from(Array[Byte](0, 1, 2))
        assert(SqliteParamWriter.bytesOf(SqliteParamWriter.Param.Blob(blob)).get.toArray.toSeq == Seq[Byte](0, 1, 2))
        // A real goes through the shared float rendering rather than toString, so it agrees with every other backend.
        assert(SqliteParamWriter.bytesOf(SqliteParamWriter.Param.Real(0.5)).get.toArray.toSeq == utf8("0.5"))
    }

    "an empty blob is bytes of length zero rather than an absent value" in {
        val empty = SqliteParamWriter.bytesOf(SqliteParamWriter.Param.Blob(Span.empty))
        assert(empty.isDefined, "an empty blob is present")
        assert(empty.get.toArray.isEmpty)
    }

    "parameters are collected in bind order" in {
        val w = new SqliteParamWriter(summon[Frame])
        w.int(1)
        w.string("two")
        w.nil()
        val ps = w.params
        assert(ps.size == 3)
        assert(ps(0) == SqliteParamWriter.Param.Integer(1L))
        assert(ps(1) == SqliteParamWriter.Param.Text("two"))
        assert(ps(2) == SqliteParamWriter.Param.Null)
    }

end SqliteParamWriterTest
