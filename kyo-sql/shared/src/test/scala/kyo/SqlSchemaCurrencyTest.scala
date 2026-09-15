package kyo

import kyo.SqlDecodeException

/** Unit tests for [[SqlSchema]]'s [[Currency]] column: one text column holding the ISO 4217 code.
  *
  * The leaves run on every platform with no locale data on the classpath, which is the property the column exists for: the currency table
  * is part of [[Currency]], so a decode never depends on what the application links. The wire legs live in
  * `kyo/internal/postgres/types/PostgresEncoderCurrencyTest.scala` and `kyo/internal/mysql/types/MysqlEncoderCurrencyTest.scala`.
  */
class SqlSchemaCurrencyTest extends Test:

    private def currency(code: String): Currency = Currency.parse(code).getOrThrow

    /** The calls `value` made on the writer. */
    private def written[A](value: A)(using s: SqlSchema[A]): Chunk[SqlSchemaWriterMock.Call] =
        recording(value).calls

    /** Writes `value` into a fresh recording writer and returns it. */
    private def recording[A](value: A)(using s: SqlSchema[A]): SqlSchemaWriterMock =
        val writer = SqlSchemaWriterMock.postgresMock
        s.write(value, writer)
        writer
    end recording

    /** Reads an `A` back from a single text column, through the same catch the row codec applies in production. */
    private def readText[A](text: String)(using s: SqlSchema[A], f: Frame): Result[SqlDecodeException, A] =
        val reader = SqlSchemaReaderMock.postgresMock(Chunk(SqlSchemaWriterMock.Call.Str(text)))
        Abort.run(SqlRow.Codec.catching(s.read(reader))).eval
    end readText

    /** Writes `value`, then reads it back from what it wrote. */
    private def roundTrip[A](value: A)(using s: SqlSchema[A]): A =
        s.read(SqlSchemaReaderMock.replaying(recording(value)))

    "Currency is a single text column" in {
        val s: SqlSchema.Column[Currency] = summon[SqlSchema.Column[Currency]]
        assert(s.width == 1)
        assert(summon[SqlType[Currency]].columnType == SqlType.Type.Text)
    }

    "Currency writes one text column holding its ISO 4217 code" in {
        assert(written(currency("USD")) == Chunk(SqlSchemaWriterMock.Call.Str("USD")))
    }

    "Currency round-trips for USD, BRL, JPY, and a code with no minor unit" in {
        Seq("USD", "BRL", "JPY", "XAU").foreach(code => assert(roundTrip(currency(code)) == currency(code)))
        succeed
    }

    "Currency decode from text that is not an ISO 4217 code raises Abort[SqlDecodeException]" in {
        Seq("NOTACURRENCY", "usd", "").foreach { text =>
            readText[Currency](text) match
                case Result.Failure(_: SqlDecodeException) => ()
                case other                                 => fail(s"Expected Failure(SqlDecodeException) for '$text' but got $other")
        }
        succeed
    }

end SqlSchemaCurrencyTest
