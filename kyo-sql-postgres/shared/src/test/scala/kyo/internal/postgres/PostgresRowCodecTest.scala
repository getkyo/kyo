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

    "a decoder that throws a decode leaf surfaces the leaf, not a wrapper" in {
        val leaf                   = SqlDecodeNumericException("boom", SqlDecodeNumericException.Subtype.Parse)
        given PostgresDecoder[Int] = throwing(leaf)
        Abort.run[SqlDecodeException](PostgresRowCodec.columnDecoded[Int](oneColumnRow, 0)).eval match
            case Result.Failure(e) => assert(e eq leaf, s"expected the leaf itself, got $e")
            case other             => fail(s"expected the decode leaf unwrapped, got $other")
    }

end PostgresRowCodecTest
