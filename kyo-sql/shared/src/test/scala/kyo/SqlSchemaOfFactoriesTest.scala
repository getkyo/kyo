package kyo

/** Single-column value type for the [[SqlSchema.of]] round-trip. Stored as one BIGINT column. */
case class SqlSchemaOfFactoriesCents(amount: Long) derives CanEqual

/** Two-column value type for the [[SqlSchema.ofMulti]] round-trip. Stored as an `id` BIGINT plus a `name` TEXT column. */
case class SqlSchemaOfFactoriesPair(id: Long, name: String) derives CanEqual

/** Unit tests for the [[SqlSchema.of]] and [[SqlSchema.ofMulti]] escape hatches: the tier each one produces, the field-name metadata
  * `ofMulti` reports, and a write-then-read round-trip through each factory's lambdas.
  *
  * The tier is the load-bearing difference. `of` returns a [[SqlSchema.Column]], so a value it describes is legal at a bind position;
  * `ofMulti` returns row evidence, which a bind position does not accept, and that refusal is a compile error rather than a render-time
  * failure. The round-trips drive a recording writer and a replaying reader, so the assertions cover which SQL type each lambda wrote and in
  * what order. The wire bytes each backend then produces for those calls are pinned by the backend writers' own suites.
  */
class SqlSchemaOfFactoriesTest extends Test:

    // --- Helpers ---

    /** Records what `value` wrote through its codec. */
    private def written[A](value: A)(using s: SqlSchema[A]): Chunk[SqlSchemaWriterMock.Call] =
        recording(value).calls

    /** Writes `value` into a fresh recording writer and returns it. */
    private def recording[A](value: A)(using s: SqlSchema[A]): SqlSchemaWriterMock =
        val writer = SqlSchemaWriterMock.postgresMock
        s.write(value, writer)
        writer
    end recording

    /** Writes `value` through its codec, then reads it back from the recorded calls. */
    private def roundTrip[A](value: A)(using s: SqlSchema[A]): A =
        s.read(SqlSchemaReaderMock.replaying(recording(value)))

    // --- the tier each factory produces ---

    "of produces a Column, the tier a bind position requires" in {
        val column: SqlSchema.Column[SqlSchemaOfFactoriesCents] = SqlSchema.of[SqlSchemaOfFactoriesCents](
            write = (v, w) => w.long(v.amount),
            read = r => SqlSchemaOfFactoriesCents(r.long())
        )
        assert(column.width == 1)
        assert(column.fieldNames == Chunk.empty[String])
    }

    // A multi-column encoding has no single placeholder to bind into, so `ofMulti` deliberately answers with row
    // evidence only. The refusal is what keeps a two-column value out of a comparison or an INSERT cell.
    "ofMulti produces row evidence a bind position does not accept" in {
        typeCheckFailure(
            """val row: SqlSchema.Column[SqlSchemaOfFactoriesPair] =
                   SqlSchema.ofMulti[SqlSchemaOfFactoriesPair](Seq("id", "name"))(
                       write = (v, w) =>
                           w.long(v.id)
                           w.string(v.name)
                   )(
                       read = r => SqlSchemaOfFactoriesPair(r.long(), r.string())
                   )"""
        )
    }

    // Neither factory reflects on `A`, so an abstract type parameter is enough: the write and read lambdas are the
    // whole codec.
    "of installs a codec for an abstract type parameter" in {
        typeCheck(
            """def anyType[X]: SqlSchema.Column[X] =
                   SqlSchema.of[X](write = (v, w) => w.string(v.toString), read = r => throw new Exception(r.string()))"""
        )
    }

    // --- of: single column ---

    "of invokes write, emitting exactly the column the write lambda wrote" in {
        given schema: SqlSchema[SqlSchemaOfFactoriesCents] = SqlSchema.of[SqlSchemaOfFactoriesCents](
            write = (v, w) => w.long(v.amount),
            read = r => SqlSchemaOfFactoriesCents(r.long())
        )
        assert(written(SqlSchemaOfFactoriesCents(4200L)) == Chunk(SqlSchemaWriterMock.Call.Long(4200L)))
    }

    "of round-trips a value through write then read" in {
        given schema: SqlSchema[SqlSchemaOfFactoriesCents] = SqlSchema.of[SqlSchemaOfFactoriesCents](
            write = (v, w) => w.long(v.amount),
            read = r => SqlSchemaOfFactoriesCents(r.long())
        )
        val original = SqlSchemaOfFactoriesCents(-99L)
        assert(roundTrip(original) == original)
    }

    // The cast target is a separate declaration: `of` installs the codec, `SqlType.of` says what `.cast[A]` renders,
    // and a type can have either without the other.
    "a custom column's cast target is declared separately, through SqlType" in {
        given SqlType[SqlSchemaOfFactoriesCents] = SqlType.of(SqlType.Type.BigInt)
        assert(summon[SqlType[SqlSchemaOfFactoriesCents]].columnType == SqlType.Type.BigInt)
    }

    // --- ofMulti: field-name propagation across the three argument lists ---

    "ofMulti propagates the field names it was given" in {
        val schema = SqlSchema.ofMulti[SqlSchemaOfFactoriesPair](Seq("id", "name"))(
            write = (v, w) =>
                w.long(v.id)
                w.string(v.name)
        )(
            read = r => SqlSchemaOfFactoriesPair(r.long(), r.string())
        )
        assert(schema.fieldNames == Chunk("id", "name"))
        assert(schema.width == 2)
    }

    "ofMulti invokes write once per column, in declaration order" in {
        given schema: SqlSchema[SqlSchemaOfFactoriesPair] = SqlSchema.ofMulti[SqlSchemaOfFactoriesPair](Seq("id", "name"))(
            write = (v, w) =>
                w.long(v.id)
                w.string(v.name)
        )(
            read = r => SqlSchemaOfFactoriesPair(r.long(), r.string())
        )
        assert(
            written(SqlSchemaOfFactoriesPair(7L, "ada")) ==
                Chunk(SqlSchemaWriterMock.Call.Long(7L), SqlSchemaWriterMock.Call.Str("ada"))
        )
    }

    "ofMulti round-trips a value through write then read" in {
        given schema: SqlSchema[SqlSchemaOfFactoriesPair] = SqlSchema.ofMulti[SqlSchemaOfFactoriesPair](Seq("id", "name"))(
            write = (v, w) =>
                w.long(v.id)
                w.string(v.name)
        )(
            read = r => SqlSchemaOfFactoriesPair(r.long(), r.string())
        )
        val original = SqlSchemaOfFactoriesPair(7L, "ada")
        assert(roundTrip(original) == original)
    }

end SqlSchemaOfFactoriesTest
