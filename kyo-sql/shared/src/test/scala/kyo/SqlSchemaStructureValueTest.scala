package kyo

import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresEncoder

/** Schema-level tests for the JSON-typed column surface: [[kyo.SqlSchema]][[[kyo.Structure.Value]]] and
  * [[kyo.SqlSchema]][[[kyo.Chunk]][[[kyo.Structure.Value]]]].
  *
  * The schema encodes a [[kyo.Structure.Value]] to JSON text via [[kyo.Json.encode]] and hands the payload to PG `jsonb` / MySQL `JSON`.
  * On read it parses the column payload back into a [[kyo.Structure.Value]]. There is no [[kyo.SqlSchema]][String] collision because
  * [[Structure.Value]] is a distinct type; the default [[kyo.SqlSchema]][String] still targets PG `text` / MySQL `VARCHAR`.
  *
  * Codec-layer JSON tests live in:
  *   - `kyo/internal/postgres/types/PostgresEncoderJsonTest.scala`, PG JSONB binary + JSON text encoders/decoders
  *   - `kyo/internal/mysql/types/MysqlEncoderJsonTest.scala`, MySQL JSON encoder + decoder
  */
class SqlSchemaStructureValueTest extends Test:

    "summon SqlSchema[Structure.Value] compiles" in {
        val s: SqlSchema[Structure.Value] = summon[SqlSchema[Structure.Value]]
        assert(s.fieldCount == 1)
        succeed
    }

    "Structure.Value writePostgres emits a single jsonb-OID Binary param" in {
        val original = Structure.Value.Record(Chunk(
            "x" -> Structure.Value.Integer(1),
            "y" -> Structure.Value.Bool(true),
            "z" -> Structure.Value.Null
        ))
        val params = summon[SqlSchema[Structure.Value]].writePostgres(original)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_JSONB)
        assert(params(0).format == Format.Binary)
        succeed
    }

    "Structure.Value writeMysql emits a single param" in {
        val original = Structure.Value.Record(Chunk(
            "mysql" -> Structure.Value.Bool(true),
            "n"     -> Structure.Value.Integer(42)
        ))
        val params = summon[SqlSchema[Structure.Value]].writeMysql(original)
        assert(params.size == 1)
        succeed
    }

    "summon SqlSchema[Chunk[Structure.Value]] compiles" in {
        val s: SqlSchema[Chunk[Structure.Value]] = summon[SqlSchema[Chunk[Structure.Value]]]
        assert(s.fieldCount == 1)
        succeed
    }

    "Chunk[Structure.Value] writePostgres emits one _jsonb param (OID 3807)" in {
        val input = Chunk(
            Structure.Value.Record(Chunk("a" -> Structure.Value.Integer(1))),
            Structure.Value.Record(Chunk("b" -> Structure.Value.Integer(2)))
        )
        val params = summon[SqlSchema[Chunk[Structure.Value]]].writePostgres(input)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_JSONB_ARRAY)
        assert(params(0).format == Format.Binary)
        succeed
    }

    "Chunk[Structure.Value] writeMysql emits one param" in {
        val input = Chunk(
            Structure.Value.Record(Chunk("a" -> Structure.Value.Integer(1))),
            Structure.Value.Null
        )
        val params = summon[SqlSchema[Chunk[Structure.Value]]].writeMysql(input)
        assert(params.size == 1)
        succeed
    }

end SqlSchemaStructureValueTest
