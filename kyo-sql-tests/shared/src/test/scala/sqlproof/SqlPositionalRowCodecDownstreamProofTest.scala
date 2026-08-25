package sqlproof

import com.example.stub.StubReader
import kyo.*
import kyo.internal.SqlPositionalRowCodec

/** Proves the row-decode half of the backend SPI is implementable outside the `kyo` package.
  *
  * A backend's row codec is [[kyo.internal.SqlPositionalRowCodec]] plus one hook: `newReader` supplies the engine's
  * [[kyo.SqlCodec.Reader]], and the slicing, field matching, and abort handling are inherited. The stub below compiles from the public
  * surface alone, so a member creeping into the contract that a downstream engine cannot reach would fail this compile. The decode
  * behavior itself is exercised through the real backends' suites; the stub's reader refuses every read.
  */
class SqlPositionalRowCodecDownstreamProofTest extends kyo.Test:

    final class StubRowCodec extends SqlPositionalRowCodec:
        def format: SqlCodec.Format = SqlCodec.Format.Text
        def newReader(sliced: SqlRow, matchesFieldAt: Maybe[(Int, String) => Boolean])(using frame: Frame): SqlCodec.Reader =
            new StubReader(frame)
    end StubRowCodec

    case class ProofRow(id: Long, name: String) derives SqlSchema

    "a downstream row codec is SqlPositionalRowCodec plus one reader hook" in {
        val codec = new StubRowCodec
        val row = new SqlRow(
            Chunk(Maybe.empty, Maybe.empty),
            Chunk(SqlRow.Column("id", 0), SqlRow.Column("name", 0)),
            codec
        )
        // The read runs the inherited machinery and reaches the stub reader, whose refusal comes back as the
        // typed decode failure carrying the reader's own exception: the wiring is complete from public surface
        // alone, and the cause pins that it was the stub that answered.
        Abort.run[SqlDecodeException](codec.read(summon[SqlSchema[ProofRow]], row, 0, Maybe.empty, SqlRow.FieldMatch.Verbatim)).map {
            case Result.Failure(e) =>
                assert(
                    e.getCause != null && String.valueOf(e.getCause.getMessage).contains("StubReader"),
                    s"the stub reader's refusal must surface as the decode failure's cause, got $e"
                )
            case other => fail(s"expected a typed decode failure from the stub reader, got $other")
        }
    }

end SqlPositionalRowCodecDownstreamProofTest
