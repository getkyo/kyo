package kyo

import kyo.SqlDecodeException

case class SqlSchemaTestPair(a: Int, b: String) derives SqlSchema, CanEqual

/** Sealed trait with two case objects, stored as its variant label through [[Sql.enumText]]. */
sealed trait TestColor derives CanEqual

object TestColor:
    case object Red  extends TestColor
    case object Blue extends TestColor

    given SqlSchema.Column[TestColor] = Sql.enumText
end TestColor

/** Sealed trait with a mix of case-class and case-object variants. A data-carrying variant has no label encoding, so the whole value is stored
  * as one JSON document through [[Sql.jsonColumn]], with kyo-schema supplying the document text.
  */
sealed trait TestEvent derives CanEqual, Schema

object TestEvent:
    case class Login(userId: Long, ipAddress: String) extends TestEvent
    case class Click(targetId: Long)                  extends TestEvent
    case object Heartbeat                             extends TestEvent

    given SqlSchema.Column[TestEvent] =
        Sql.jsonColumn[TestEvent](v => Json.encode(v))(text => Json.decode[TestEvent](text).getOrThrow)
end TestEvent

/** Scala 3 enum with case-object-style variants, stored as its label like [[TestColor]], its column DERIVED rather than installed:
  * `derives SqlSchema.Column` is the spelling a user writes, and every leaf below that touches [[TestStatus]] runs through it.
  */
enum TestStatus derives CanEqual, SqlSchema.Column:
    case Pending, Active, Cancelled

/** A row whose field is not a single SQL column, for the positioned derivation error. */
case class SqlSchemaTestUnsupportedField(id: Long, thread: java.lang.Thread)

/** Unit tests for [[SqlSchema]]: which instances exist, how many columns each occupies, and the columns each one writes and reads back.
  *
  * All tests are pure and backend-blind: they drive a recording writer and a replaying reader, so each assertion is about which SQL type the
  * codec chose for each column and in what order, which is what an `SqlSchema` decides. The bytes a backend then produces for those calls are
  * pinned by the backend writers' and readers' own suites, and container-based tests live in the integration suite.
  */
class SqlSchemaTest extends Test:

    given CanEqual[java.time.LocalDate, java.time.LocalDate]         = CanEqual.canEqualAny
    given CanEqual[java.time.LocalDateTime, java.time.LocalDateTime] = CanEqual.canEqualAny

    // --- Helpers ---

    /** The calls `value` made on the writer, one per column the codec occupies. */
    private def written[A](value: A)(using s: SqlSchema[A]): Chunk[SqlSchemaWriterMock.Call] =
        recording(value).calls

    /** Writes `value` into a fresh recording writer and returns it. */
    private def recording[A](value: A)(using s: SqlSchema[A]): SqlSchemaWriterMock =
        val writer = SqlSchemaWriterMock.postgresMock
        s.write(value, writer)
        writer
    end recording

    /** Writes `value`, then reads it back from the columns it wrote. */
    private def roundTrip[A](value: A)(using s: SqlSchema[A]): A =
        s.read(SqlSchemaReaderMock.replaying(recording(value)))

    /** Reads an `A` from `calls`, through the same catch the row codec applies in production. */
    private def decoded[A](calls: SqlSchemaWriterMock.Call*)(using s: SqlSchema[A], f: Frame): Result[SqlDecodeException, A] =
        Abort.run(SqlRow.Codec.catching(s.read(SqlSchemaReaderMock.postgresMock(Chunk.from(calls))))).eval

    // --- 1. Primitive summon compiles ---

    "summon SqlSchema[Long] compiles" in {
        val s: SqlSchema[Long] = summon[SqlSchema[Long]]
        assert(s.width == 1)
        succeed
    }

    "summon SqlSchema[String] compiles" in {
        val s: SqlSchema[String] = summon[SqlSchema[String]]
        assert(s.width == 1)
        succeed
    }

    "summon SqlSchema[Span[Byte]] compiles" in {
        val s: SqlSchema[Span[Byte]] = summon[SqlSchema[Span[Byte]]]
        assert(s.width == 1)
        succeed
    }

    // --- 2. A scalar resolves at the Column tier, which is what a bind position requires ---

    "a primitive resolves as a Column, the tier a bind position requires" in {
        assert(summon[SqlSchema.Column[Long]].width == 1)
        assert(summon[SqlSchema.Column[String]].width == 1)
    }

    // --- 3. Nullable summon compiles ---

    "summon SqlSchema[Maybe[Int]] compiles" in {
        val s: SqlSchema[Maybe[Int]] = summon[SqlSchema[Maybe[Int]]]
        assert(s.width == 1)
        succeed
    }

    // --- 4. The product derivation ---

    "width is 2 for SqlSchemaTestPair (two-field case class)" in {
        assert(summon[SqlSchema[SqlSchemaTestPair]].width == 2)
        succeed
    }

    "fieldNames is Chunk(a, b) for SqlSchemaTestPair" in {
        assert(summon[SqlSchema[SqlSchemaTestPair]].fieldNames == Chunk("a", "b"))
        succeed
    }

    // --- 5. Unsupported types produce compile errors ---

    "a type with no SqlSchema is a compile error at the summon" in {
        typeCheckFailure("summon[SqlSchema[java.lang.Thread]]")("is not a SQL-storable type")
    }

    // The derivation reports the FIELD, not the row: a row is rejected because one of its columns does not
    // exist, and naming the row would leave the reader to find out which field that is.
    "a row field that is not a single column is a compile error naming the field" in {
        typeCheckFailure("summon[SqlSchema[SqlSchemaTestUnsupportedField]]")("Field 'thread' is not a single-column SQL type")
    }

    // A row is not a bind value: `Column` is the tier a bind position requires, and a case class has none.
    "a multi-column row does not resolve at the Column tier" in {
        typeCheckFailure("summon[SqlSchema.Column[SqlSchemaTestPair]]")("cannot occupy a single SQL column")
    }

    // --- 6. SqlSchema.of constructs a single-column codec ---

    "SqlSchema.of constructs a scalar column of width 1" in {
        val custom = SqlSchema.of[Double](
            write = (v, w) => w.double(v),
            read = r => r.double()
        )
        assert(custom.width == 1)
        succeed
    }

    // --- 7. what each codec writes ---

    "SqlSchema[Long] writes one long column" in {
        assert(written(42L) == Chunk(SqlSchemaWriterMock.Call.Long(42L)))
    }

    "SqlSchema[SqlSchemaTestPair] writes its two fields in declaration order" in {
        assert(
            written(SqlSchemaTestPair(1, "hello")) ==
                Chunk(SqlSchemaWriterMock.Call.Int(1), SqlSchemaWriterMock.Call.Str("hello"))
        )
    }

    // --- 8. round-trips through the columns each codec wrote ---

    "SqlSchema[Long] round-trips" in {
        assert(roundTrip(42L) == 42L)
    }

    "SqlSchema[SqlSchemaTestPair] round-trips both fields" in {
        val decoded = roundTrip(SqlSchemaTestPair(7, "alice"))
        assert(decoded.a == 7)
        assert(decoded.b == "alice")
    }

    // --- 9. a read that does not match the column surfaces as Abort[SqlDecodeException] ---

    "a decode failure surfaces as Abort[SqlDecodeException]" in {
        // A Long codec reading a column the writer recorded as text cannot coerce; the row codec's
        // catch turns the throw into a typed decode failure. The byte-level equivalent (a 3-byte
        // payload where Long needs 8) lives in the backend row-reader suites.
        decoded[Long](SqlSchemaWriterMock.Call.Str("not a long")) match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

    // --- 10. nullable ---

    "nullable writes a NULL column for Maybe.Absent" in {
        assert(written[Maybe[Long]](Maybe.Absent) == Chunk(SqlSchemaWriterMock.Call.Nil))
    }

    "nullable writes the value for Maybe.Present(42L)" in {
        assert(written[Maybe[Long]](Maybe(42L)) == Chunk(SqlSchemaWriterMock.Call.Long(42L)))
    }

    // --- 11. Maybe of a row is the row's own column count, one NULL per column ---
    //
    // A `Maybe` of a scalar is one nullable column; a `Maybe` of a whole row is that row's columns, all
    // absent together. The two are different givens and the count is what tells them apart.

    "Maybe of a scalar occupies one column" in {
        assert(summon[SqlSchema[Maybe[Int]]].width == 1)
        assert(summon[SqlSchema[Maybe[String]]].width == 1)
    }

    "Maybe of a multi-column row occupies that row's columns" in {
        val inner = summon[SqlSchema[SqlSchemaTestPair]]
        assert(inner.width == 2, s"fixture must be multi-column for this leaf to mean anything; got ${inner.width}")
        assert(summon[SqlSchema[Maybe[SqlSchemaTestPair]]].width == 2)
    }

    "an absent row writes one NULL per column and reads back absent" in {
        assert(
            written[Maybe[SqlSchemaTestPair]](Maybe.Absent) ==
                Chunk(SqlSchemaWriterMock.Call.Nil, SqlSchemaWriterMock.Call.Nil)
        )
        assert(roundTrip[Maybe[SqlSchemaTestPair]](Maybe.Absent) == Maybe.Absent)
    }

    "a present row round-trips through the embedded columns" in {
        val value = Maybe(SqlSchemaTestPair(3, "carol"))
        assert(
            written[Maybe[SqlSchemaTestPair]](value) ==
                Chunk(SqlSchemaWriterMock.Call.Int(3), SqlSchemaWriterMock.Call.Str("carol"))
        )
        assert(roundTrip[Maybe[SqlSchemaTestPair]](value) == value)
    }

    // --- 12. Tuple arity coverage: summon for arities 2..22 ---
    // Verifies that SqlSchema is available for multi-column result tuples of any SQL-relevant arity.

    "summon SqlSchema[(Long, String)] (arity 2)" in {
        val s = summon[SqlSchema[(Long, String)]]
        assert(s.width == 2)
        succeed
    }

    "summon SqlSchema[(Long, String, Int)] (arity 3)" in {
        val s = summon[SqlSchema[(Long, String, Int)]]
        assert(s.width == 3)
        succeed
    }

    "summon SqlSchema[(Long, String, Int, Boolean, Float, Double)] (arity 6)" in {
        val s = summon[SqlSchema[(Long, String, Int, Boolean, Float, Double)]]
        assert(s.width == 6)
        succeed
    }

    "summon SqlSchema[(Long, String, Int, Boolean, Float, Double, Long, Long)] (arity 8)" in {
        val s = summon[SqlSchema[(Long, String, Int, Boolean, Float, Double, Long, Long)]]
        assert(s.width == 8)
        succeed
    }

    "summon SqlSchema[12-tuple] (arity 12)" in {
        val s = summon[SqlSchema[(Long, String, Int, Boolean, Float, Double, Long, Long, Long, Long, Long, Long)]]
        assert(s.width == 12)
        succeed
    }

    "summon SqlSchema[16-tuple] (arity 16)" in {
        val s = summon[SqlSchema[(Long, String, Int, Boolean, Float, Double, Long, Long, Long, Long, Long, Long, Long, Long, Long, Long)]]
        assert(s.width == 16)
        succeed
    }

    "summon SqlSchema[20-tuple] (arity 20)" in {
        val s = summon[SqlSchema[(
            Long,
            String,
            Int,
            Boolean,
            Float,
            Double,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long
        )]]
        assert(s.width == 20)
        succeed
    }

    "summon SqlSchema[22-tuple] (arity 22)" in {
        val s = summon[SqlSchema[(
            Long,
            String,
            Int,
            Boolean,
            Float,
            Double,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long,
            Long
        )]]
        assert(s.width == 22)
        succeed
    }

    // --- 13. temporal givens write one column each, naming the SQL type ---
    //
    // Which column type each backend then uses is pinned in PostgresParamWriterTest (OID 1082 / 1114)
    // and MysqlParamWriterTest (TYPE_DATE / TYPE_DATETIME).

    "SqlSchema[LocalDate] writes one date column" in {
        val date = java.time.LocalDate.of(2026, 5, 5)
        assert(written(date) == Chunk(SqlSchemaWriterMock.Call.Date(date)))
        assert(roundTrip(date) == date)
    }

    "SqlSchema[LocalDateTime] writes one date-and-time column" in {
        val dt = java.time.LocalDateTime.of(2026, 5, 5, 12, 30, 0)
        assert(written(dt) == Chunk(SqlSchemaWriterMock.Call.DateTime(dt)))
        assert(roundTrip(dt) == dt)
    }

    // LocalTime must write a `time` column, not a text column: if it travelled as text, the write would land a
    // `text` parameter against a `time` column and the read would hand the column's binary microseconds to
    // `LocalTime.parse`.
    "SqlSchema[LocalTime] writes one time-of-day column, not a text column" in {
        val t = java.time.LocalTime.of(13, 45, 30, 123_000_000)
        assert(written(t) == Chunk(SqlSchemaWriterMock.Call.Time(t)))
        assert(roundTrip(t).equals(t))
    }

    // --- Error-remapping: a Decode failure propagates as a SqlException ---

    "a decode failure is a SqlException (the internalExecuteQuery / decodeStream widening contract)" in {
        // Verifies the behavioral contract shared by every Abort.recover rewrite on the decode path:
        // SqlDecodeException IS-A SqlException, so the widening Abort.fail(e: SqlException) succeeds
        // and the result at the outer Abort[SqlException] boundary is a Failure.
        // The widening is a compile-time property, so it is pinned with a subtype witness. A runtime
        // isInstanceOf here could not fail: Abort.run[SqlDecodeException] already types the failure.
        summon[SqlDecodeException <:< SqlException]
        decoded[Long](SqlSchemaWriterMock.Call.Str("not a long")) match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected decode failure but got $other")
        end match
    }

    // --- 14. Maybe[A] round-trips, Present and Absent ---

    "Maybe[Int] round-trips Present(42) and Absent" in {
        assert(roundTrip[Maybe[Int]](Maybe(42)) == Maybe(42))
        assert(roundTrip[Maybe[Int]](Maybe.Absent) == Maybe.Absent)
    }

    "Maybe[String] round-trips Present(\"x\") and Absent" in {
        assert(roundTrip[Maybe[String]](Maybe("x")) == Maybe("x"))
        assert(roundTrip[Maybe[String]](Maybe.Absent) == Maybe.Absent)
    }

    // --- 15. Sql.enumText over a sum of singletons: one TEXT column holding the label ---

    "the enumText column for TestColor occupies one column" in {
        assert(summon[SqlSchema[TestColor]].width == 1)
    }

    "the enumText column for TestColor round-trips both arms" in {
        assert(roundTrip[TestColor](TestColor.Red) == TestColor.Red)
        assert(roundTrip[TestColor](TestColor.Blue) == TestColor.Blue)
    }

    "the enumText column writes the variant label as text" in {
        assert(written[TestColor](TestColor.Red) == Chunk(SqlSchemaWriterMock.Call.Str("Red")))
    }

    "the enumText column raises a typed Decode on an unknown label" in {
        decoded[TestColor](SqlSchemaWriterMock.Call.Str("Green")) match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected typed Decode failure, got $other")
    }

    "derives SqlSchema.Column resolves the enum's column with no explicit given" in {
        // TestStatus carries `derives SqlSchema.Column` and no companion given, so this summon IS the derivation,
        // and the width pins that it derived a single column rather than a row.
        assert(summon[SqlSchema.Column[TestStatus]].width == 1)
    }

    "the enumText column round-trips all three cases of a Scala 3 enum" in {
        Seq(TestStatus.Pending, TestStatus.Active, TestStatus.Cancelled).foreach { v =>
            assert(written[TestStatus](v) == Chunk(SqlSchemaWriterMock.Call.Str(v.toString)))
            assert(roundTrip[TestStatus](v) == v)
        }
        succeed
    }

    // --- 16. Sql.jsonColumn over a sum with data-carrying variants: one JSON column ---

    "the jsonColumn for TestEvent occupies one column" in {
        assert(summon[SqlSchema[TestEvent]].width == 1)
        // The codec is installed; a cast target is a separate declaration, and this type declares none, so
        // `.cast[TestEvent]` is a compile error rather than a render-time surprise.
        typeCheckFailure("summon[SqlType[TestEvent]]")
    }

    "the jsonColumn writes one JSON column holding the encoded variant" in {
        val calls = written[TestEvent](TestEvent.Login(42L, "10.0.0.1"))
        assert(calls.size == 1)
        calls.head match
            case SqlSchemaWriterMock.Call.Json(text) =>
                assert(text.contains("Login"), s"the document should name the variant: $text")
                assert(text.contains("10.0.0.1"), s"the document should carry the field values: $text")
            case other => fail(s"expected one json column, got $other")
        end match
    }

    "the jsonColumn round-trips every variant through the JSON column" in {
        val cases = Seq[TestEvent](
            TestEvent.Login(7L, "127.0.0.1"),
            TestEvent.Click(99L),
            TestEvent.Heartbeat
        )
        cases.foreach(original => assert(roundTrip[TestEvent](original) == original, s"mismatch for $original"))
        succeed
    }

    "the jsonColumn surfaces a malformed document as a typed decode failure" in {
        decoded[TestEvent](SqlSchemaWriterMock.Call.Json("{not json at all")) match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected typed Decode failure, got $other")
    }

    // --- 17. WindowSpec.Builder.partitionBy replace semantic ---
    //
    // Driven through the user-facing `Sql.windowSpec` DSL, Terms come from `select(c => ...)`
    // accessors, so the LHS is pure DSL. The byte-exact SQL on the RHS proves the replace semantic
    // (second `partitionBy` discards the first): a "wrong" append semantic would emit
    // `PARTITION BY [p].[deptId], [p].[age]` instead of just `PARTITION BY [p].[age]`. Rendered through
    // the core-owned stub dialect, because the subject is which keys survive and not any flavor's syntax.

    case class WSBPerson(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema
    private def renderStub(q: Sql.Executable[?]): String = q.render(IdiomRenderStub).onlySql.get

    "Sql.windowSpec.partitionBy single-Column replace: second partitionBy replaces first" in {
        val q = Sql.from[WSBPerson]("p").select(c =>
            Sql.windowSpec.partitionBy(c.p.deptId).partitionBy(c.p.age).rowNumber
        )
        assert(
            renderStub(q) == "SELECT ROW_NUMBER() OVER (PARTITION BY [p].[age]) FROM [wsbperson] [p]"
        )
    }

    // The vararg overload `partitionBy(keys: Term[?]*)` has the same replace semantic.
    "Sql.windowSpec.partitionBy vararg replace: partitionBy(a, b).partitionBy(c) keeps only c" in {
        val q = Sql.from[WSBPerson]("p").select(c =>
            Sql.windowSpec.partitionBy(c.p.deptId, c.p.age).partitionBy(c.p.id).rowNumber
        )
        assert(
            renderStub(q) == "SELECT ROW_NUMBER() OVER (PARTITION BY [p].[id]) FROM [wsbperson] [p]"
        )
    }

end SqlSchemaTest
