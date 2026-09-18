package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlCodec.Format
import kyo.internal.SqlJsonArray

/** That MySQL's reader binds `SqlJsonArray`'s failure callback to a typed decode failure.
  *
  * `SqlJsonArray` lives in core and throws nothing of its own: it reports through a callback each reader binds. The pure leaves in
  * `SqlJsonArrayTest` read that callback through a sentinel, which says the parser detects a malformed document and not what a caller sees.
  * This is the other half, and it is per backend because the binding is.
  */
class MysqlJsonArrayReaderTest extends Test:

    "a malformed array column reaches the caller as a typed decode failure, not an untyped throw" in {
        val row = new SqlRow(
            Chunk(Maybe.Present(Span.from("""not an array""".getBytes(StandardCharsets.UTF_8)))),
            Chunk(SqlRow.Column("arr", 0)),
            MysqlRowCodec(Format.Binary)
        )
        val ex = intercept[SqlDecodeJsonException] {
            val _ = new MysqlRowReader(row, Format.Binary).nextArrayOfInt()
        }
        assert(ex.jsonPreview.contains("expected a JSON array"), ex.jsonPreview)
    }

end MysqlJsonArrayReaderTest
