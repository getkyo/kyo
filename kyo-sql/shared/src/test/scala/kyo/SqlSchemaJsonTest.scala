package kyo

import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresEncoder

/** Schema-level tests for the JSON-typed column surface: [[kyo.JsonText]] and [[kyo.SqlSchema]][[[kyo.Chunk]][[[kyo.JsonText]]]].
  *
  * `kyo.Json` is a serializer singleton (not a value type), so [[kyo.JsonText]] is the user-facing tagged-string wrapper for binding
  * pre-encoded JSON text against PostgreSQL `jsonb` / `JSON` and MySQL `JSON` columns without colliding with the default
  * [[kyo.SqlSchema]][String] mapping (which targets PG `text` / MySQL `VARCHAR`).
  *
  * Codec-layer JSON tests live in:
  *   - `kyo/internal/postgres/types/PostgresEncoderJsonTest.scala`, PG JSONB binary + JSON text encoders/decoders
  *   - `kyo/internal/mysql/types/MysqlEncoderJsonTest.scala`, MySQL JSON encoder + decoder
  */
class SqlSchemaJsonTest extends Test:

    "summon SqlSchema[JsonText] compiles" in {
        val s: SqlSchema[JsonText] = summon[SqlSchema[JsonText]]
        assert(s.fieldCount == 1)
        succeed
    }

    "JsonText writePostgres emits a single jsonb-OID Binary param" in {
        val original = JsonText("""{"x":1,"y":true,"z":null}""")
        val params   = summon[SqlSchema[JsonText]].writePostgres(original)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_JSONB)
        assert(params(0).format == Format.Binary)
        succeed
    }

    "JsonText writeMysql emits a single param" in {
        val original = JsonText("""{"mysql":true,"n":42}""")
        val params   = summon[SqlSchema[JsonText]].writeMysql(original)
        assert(params.size == 1)
        succeed
    }

    "summon SqlSchema[Chunk[JsonText]] compiles" in {
        val s: SqlSchema[Chunk[JsonText]] = summon[SqlSchema[Chunk[JsonText]]]
        assert(s.fieldCount == 1)
        succeed
    }

    "Chunk[JsonText] writePostgres emits one _jsonb param (OID 3807)" in {
        val input  = Chunk(JsonText("""{"a":1}"""), JsonText("""{"b":2}"""))
        val params = summon[SqlSchema[Chunk[JsonText]]].writePostgres(input)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_JSONB_ARRAY)
        assert(params(0).format == Format.Binary)
        succeed
    }

    "Chunk[JsonText] writeMysql emits one param" in {
        val input  = Chunk(JsonText("""{"a":1}"""), JsonText("null"))
        val params = summon[SqlSchema[Chunk[JsonText]]].writeMysql(input)
        assert(params.size == 1)
        succeed
    }

    "JsonText.value extracts wrapped JSON text" in {
        val j = JsonText("""[1,2,3]""")
        assert(j.value == """[1,2,3]""")
    }

end SqlSchemaJsonTest
