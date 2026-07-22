package kyo

import kyo.internal.mysql.MysqlBufferWriter
import kyo.internal.mysql.types.MysqlEncoder
import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresEncoder

// Top-level case class with explicit derivation (derives SqlSchema is unsupported on opaque types).
// `SqlSchema[A] = Schema[A]` opaquely; `SqlSchema.derived` delegates to `Schema.derived`.
case class SqlSchemaTestPair(a: Int, b: String)

object SqlSchemaTestPair:
    given SqlSchema[SqlSchemaTestPair] = SqlSchema.derived

end SqlSchemaTestPair

/** Sealed trait for SqlSchemaTest test 11. Two case objects used to verify sum-type decode via the string-discriminator strategy. */
sealed trait TestColor derives CanEqual

object TestColor:
    case object Red  extends TestColor
    case object Blue extends TestColor
    given Schema[TestColor]    = Schema.derived
    given SqlSchema[TestColor] = SqlSchema.derived
end TestColor

/** Sealed trait with a mix of case-class and case-object variants. Exercises the JSON-encoded sum-type derivation path (case-class variants
  * disqualify the string-discriminator optimisation).
  */
sealed trait TestEvent derives CanEqual

object TestEvent:
    case class Login(userId: Long, ipAddress: String) extends TestEvent
    case class Click(targetId: Long)                  extends TestEvent
    case object Heartbeat                             extends TestEvent
    given Schema[TestEvent]    = Schema.derived
    given SqlSchema[TestEvent] = SqlSchema.derived
end TestEvent

/** Scala 3 enum with case-object-style variants. Exercises the same path as a sealed-trait-of-objects. */
enum TestStatus derives CanEqual:
    case Pending, Active, Cancelled

object TestStatus:
    given Schema[TestStatus]    = Schema.derived
    given SqlSchema[TestStatus] = SqlSchema.derived
end TestStatus

/** Unit tests for [[SqlSchema]], typeclass existence, field metadata, write, and read round-trips.
  *
  * All tests are pure (no container needed): they exercise [[SqlSchema]] extensions directly using in-memory byte buffers and mock
  * [[SqlRow]] instances. Container-based tests live in the integration test suite.
  */
class SqlSchemaTest extends Test:

    // --- Helpers ---

    /** Encodes a value via [[SqlSchema.writePostgres]] and extracts the wire bytes from the single resulting [[BoundParam]] using its
      * public [[BoundParam.encoded]] accessor, no test-side reach into internal encoders.
      */
    private def encode[A](value: A)(using s: SqlSchema[A], as: kyo.test.AssertScope): Span[Byte] =
        val params = s.writePostgres(value)
        assert(params.size == 1, s"encode helper requires a single-column schema; got ${params.size} params")
        params.head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("schema.writePostgres produced a NULL param")
    end encode

    private def field(name: String): FieldDescription =
        FieldDescription(name, 0, 0, 0, 0, 0, 1) // formatCode=1 for Binary

    private def binaryRow(columns: (String, Span[Byte])*): SqlRow =
        val values = Chunk.from(columns.map { case (_, bytes) => Maybe.Present(bytes) })
        val fields = Chunk.from(columns.map { case (name, _) => field(name) })
        new SqlRow(values, fields, Format.Binary)
    end binaryRow

    /** Encodes a value via [[SqlSchema.writeMysql]] and extracts the wire bytes from the single resulting [[BoundMysqlParam]] using its
      * public [[BoundMysqlParam.encoded]] accessor, no test-side reach into internal encoders.
      */
    private def encodeMySQL[A](value: A)(using s: SqlSchema[A], as: kyo.test.AssertScope): Span[Byte] =
        val params = s.writeMysql(value)
        assert(params.size == 1, s"encodeMySQL helper requires a single-column schema; got ${params.size} params")
        params.head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("schema.writeMysql produced a NULL param")
    end encodeMySQL

    private def mysqlBinaryRow(columns: (String, Span[Byte])*): SqlRow =
        val values = Chunk.from(columns.map { case (_, bytes) => Maybe.Present(bytes) })
        val fields = Chunk.from(columns.map { case (name, _) => field(name) })
        new SqlRow(values, fields, Format.Binary)
    end mysqlBinaryRow

    // --- 1. Primitive summon compiles ---

    "summon SqlSchema[Long] compiles" in {
        val s: SqlSchema[Long] = summon[SqlSchema[Long]]
        assert(s.fieldCount == 1)
        succeed
    }

    "summon SqlSchema[String] compiles" in {
        val s: SqlSchema[String] = summon[SqlSchema[String]]
        assert(s.fieldCount == 1)
        succeed
    }

    "summon SqlSchema[Span[Byte]] compiles" in {
        val s: SqlSchema[Span[Byte]] = summon[SqlSchema[Span[Byte]]]
        assert(s.fieldCount == 1)
        succeed
    }

    // --- 2. Nullable summon compiles ---

    "summon SqlSchema[Maybe[Int]] compiles" in {
        val s: SqlSchema[Maybe[Int]] = summon[SqlSchema[Maybe[Int]]]
        assert(s.fieldCount == 1)
        succeed
    }

    // --- 3. fieldCount for primitive is 1 ---

    "fieldCount is 1 for Long" in {
        assert(summon[SqlSchema[Long]].fieldCount == 1)
        succeed
    }

    "fieldCount is 1 for String" in {
        assert(summon[SqlSchema[String]].fieldCount == 1)
        succeed
    }

    // --- 4. SqlSchema.derived on a case class ---

    "fieldCount is 2 for SqlSchemaTestPair (two-field case class via SqlSchema.derived)" in {
        assert(summon[SqlSchema[SqlSchemaTestPair]].fieldCount == 2)
        succeed
    }

    "fieldNames is Chunk(a, b) for SqlSchemaTestPair" in {
        assert(summon[SqlSchema[SqlSchemaTestPair]].fieldNames == Chunk("a", "b"))
        succeed
    }

    // --- 5. Unsupported field type produces compile error ---

    "SqlSchema.derived on a class with unsupported field type fails to compile" in {
        // typeCheckErrors' declared `Seq[Error]` return does not match the underlying `Array[Error]`
        // the compiler produces under the current stdlib. Route through java.lang.reflect.Array.
        // so no Scala collection dispatch or Array-vs-Seq cast is involved.
        val errors = compiletime.testing.typeCheckErrors("SqlSchema.derived[java.lang.Thread]").asInstanceOf[AnyRef]
        val n =
            if errors.getClass.isArray then java.lang.reflect.Array.getLength(errors)
            else errors.asInstanceOf[Seq[?]].size
        assert(n > 0)
    }

    // --- 6. SqlSchema.of constructs a single-column schema ---

    "SqlSchema.of constructs a scalar schema with fieldCount 1" in {
        val custom = SqlSchema.of[Double](
            write = (v, w) => w.double(v),
            read = r => r.double()
        )
        assert(custom.fieldCount == 1)
        succeed
    }

    // --- 7. writePostgres for Long ---

    "writePostgres for Long produces one BoundParam" in {
        val params = summon[SqlSchema[Long]].writePostgres(42L)
        assert(params.size == 1)
        // OID and format must match the int8Binary encoder
        assert(params(0).oid == PostgresEncoder.OID_INT8)
        assert(params(0).format == Format.Binary)
        succeed
    }

    // --- 8. writeMysql for Long ---

    "writeMysql for Long produces one BoundMysqlParam" in {
        val params = summon[SqlSchema[Long]].writeMysql(42L)
        assert(params.size == 1)
        succeed
    }

    // --- 9. writePostgres for SqlSchemaTestPair produces two BoundParams ---

    "writePostgres for SqlSchemaTestPair produces two BoundParams" in {
        val params = summon[SqlSchema[SqlSchemaTestPair]].writePostgres(SqlSchemaTestPair(1, "hello"))
        assert(params.size == 2)
        // First param: Int → int4Binary (OID 23)
        assert(params(0).oid == PostgresEncoder.OID_INT4)
        // Second param: String → textText (OID 25)
        assert(params(1).oid == PostgresEncoder.OID_TEXT)
        succeed
    }

    // --- 10. readPostgres round-trip for Long ---

    "readPostgres round-trip for Long" in {
        val bytes  = encode(42L)
        val row    = binaryRow("val" -> bytes)
        val result = Abort.run(summon[SqlSchema[Long]].readPostgres(row)).eval
        result match
            case Result.Success(v) => assert(v == 42L)
            case other             => fail(s"Expected Success(42L) but got $other")
    }

    // --- 11. readPostgres round-trip for SqlSchemaTestPair ---

    "readPostgres round-trip for SqlSchemaTestPair" in {
        val intBytes  = encode(7)
        val textBytes = encode("alice")
        val row       = binaryRow("a" -> intBytes, "b" -> textBytes)
        val result    = Abort.run(summon[SqlSchema[SqlSchemaTestPair]].readPostgres(row)).eval
        result match
            case Result.Success(p) =>
                assert(p.a == 7)
                assert(p.b == "alice")
            case other => fail(s"Expected Success(SqlSchemaTestPair(7, alice)) but got $other")
        end match
    }

    // --- 12. readPostgres failure surfaces as Abort[SqlException.Decode] ---

    "readPostgres failure surfaces as Abort[SqlException.Decode]" in {
        // Feed a 3-byte payload where Long expects exactly 8 bytes, will throw inside serializeRead.
        val badBytes = Span.from(Array[Byte](0x01, 0x02, 0x03))
        val row      = binaryRow("val" -> badBytes)
        val result   = Abort.run[SqlException.Decode](summon[SqlSchema[Long]].readPostgres(row)).eval
        result match
            case Result.Failure(_: SqlException.Decode) => succeed
            case other                                  => fail(s"Expected Failure(SqlException.Decode) but got $other")
    }

    // --- 13. nullable writePostgres for Maybe.Absent produces null param ---

    "nullable writePostgres for Maybe.Absent produces a null BoundParam" in {
        val params = summon[SqlSchema[Maybe[Long]]].writePostgres(Maybe.Absent)
        assert(params.size == 1)
        // Absent encodes as null, the value inside the BoundParam is Absent.
        assert(params(0).value == Maybe.Absent)
        succeed
    }

    // --- 14. nullable writePostgres for Maybe.Present round-trips correctly ---

    "nullable writePostgres for Maybe.Present(42L) encodes the value" in {
        val params = summon[SqlSchema[Maybe[Long]]].writePostgres(Maybe(42L))
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_INT8)
        succeed
    }

    // --- 15. fieldCountOf compile-time constant ---

    "fieldCountOf[Long] is 1 (scalar, no mirror)" in {
        assert(SqlSchema.fieldCountOf[Long] == 1)
        succeed
    }

    "fieldCountOf[String] is 1 (scalar, no mirror)" in {
        assert(SqlSchema.fieldCountOf[String] == 1)
        succeed
    }

    "fieldCountOf[SqlSchemaTestPair] is 2 (2-field case class)" in {
        assert(SqlSchema.fieldCountOf[SqlSchemaTestPair] == 2)
        succeed
    }

    "fieldCountOf[(Int, String)] is 2 (2-tuple)" in {
        assert(SqlSchema.fieldCountOf[(Int, String)] == 2)
        succeed
    }

    // --- 16. readMysql round-trip for Long ---

    "readMysql round-trip for Long" in {
        val bytes  = encodeMySQL(42L)
        val row    = mysqlBinaryRow("val" -> bytes)
        val result = Abort.run(summon[SqlSchema[Long]].readMysql(row)).eval
        result match
            case Result.Success(v) => assert(v == 42L)
            case other             => fail(s"Expected Success(42L) but got $other")
    }

    // --- 17. Tuple arity coverage: summon for arities 2..22 ---
    // Verifies that SqlSchema is available for multi-column result tuples of any SQL-relevant arity.

    "summon SqlSchema[(Long, String)] (arity 2)" in {
        val s = summon[SqlSchema[(Long, String)]]
        assert(s.fieldCount == 2)
        succeed
    }

    "summon SqlSchema[(Long, String, Int)] (arity 3)" in {
        val s = summon[SqlSchema[(Long, String, Int)]]
        assert(s.fieldCount == 3)
        succeed
    }

    "summon SqlSchema[(Long, String, Int, Boolean, Float, Double)] (arity 6)" in {
        val s = summon[SqlSchema[(Long, String, Int, Boolean, Float, Double)]]
        assert(s.fieldCount == 6)
        succeed
    }

    "summon SqlSchema[(Long, String, Int, Boolean, Float, Double, Long, Long)] (arity 8)" in {
        val s = summon[SqlSchema[(Long, String, Int, Boolean, Float, Double, Long, Long)]]
        assert(s.fieldCount == 8)
        succeed
    }

    "summon SqlSchema[12-tuple] (arity 12)" in {
        val s = summon[SqlSchema[(Long, String, Int, Boolean, Float, Double, Long, Long, Long, Long, Long, Long)]]
        assert(s.fieldCount == 12)
        succeed
    }

    "summon SqlSchema[16-tuple] (arity 16)" in {
        val s = summon[SqlSchema[(Long, String, Int, Boolean, Float, Double, Long, Long, Long, Long, Long, Long, Long, Long, Long, Long)]]
        assert(s.fieldCount == 16)
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
        assert(s.fieldCount == 20)
        succeed
    }

    // --- 18. LocalDate writePostgres emits OID=1082, binary format ---

    "writePostgres for LocalDate emits OID=1082 with binary format" in {
        val date   = java.time.LocalDate.of(2026, 5, 5)
        val params = summon[SqlSchema[java.time.LocalDate]].writePostgres(date)
        assert(params.size == 1)
        val p = params(0)
        assert(p.oid == PostgresEncoder.OID_DATE, s"expected OID=${PostgresEncoder.OID_DATE} but got ${p.oid}")
        assert(p.format == Format.Binary, s"expected Binary format but got ${p.format}")
        // Verify 4-byte payload (days-since-2000 as big-endian Int32)
        p.encoded match
            case Maybe.Absent         => fail("expected encoded bytes for LocalDate")
            case Maybe.Present(bytes) => assert(bytes.size == 4, s"expected 4 bytes, got ${bytes.size}")
        end match
        succeed
    }

    // --- 19. LocalDateTime writePostgres emits OID=1114, binary format ---

    "writePostgres for LocalDateTime emits OID=1114 with binary format" in {
        val dt     = java.time.LocalDateTime.of(2026, 5, 5, 12, 30, 0)
        val params = summon[SqlSchema[java.time.LocalDateTime]].writePostgres(dt)
        assert(params.size == 1)
        val p = params(0)
        assert(p.oid == PostgresEncoder.OID_TIMESTAMP, s"expected OID=${PostgresEncoder.OID_TIMESTAMP} but got ${p.oid}")
        assert(p.format == Format.Binary, s"expected Binary format but got ${p.format}")
        // Verify 8-byte payload (microseconds-since-2000 as big-endian Int64)
        p.encoded match
            case Maybe.Absent         => fail("expected encoded bytes for LocalDateTime")
            case Maybe.Present(bytes) => assert(bytes.size == 8, s"expected 8 bytes, got ${bytes.size}")
        end match
        succeed
    }

    // --- 20. LocalDate writeMysql emits TYPE_DATE (0x0a) ---

    "writeMysql for LocalDate emits TYPE_DATE (0x0a)" in {
        val date   = java.time.LocalDate.of(2026, 5, 5)
        val params = summon[SqlSchema[java.time.LocalDate]].writeMysql(date)
        assert(params.size == 1)
        val p = params(0)
        assert(
            p.encoder.mysqlType == MysqlEncoder.TYPE_DATE,
            s"expected TYPE_DATE (${MysqlEncoder.TYPE_DATE}) but got ${p.encoder.mysqlType}"
        )
        succeed
    }

    // --- 21. LocalDateTime writeMysql emits TYPE_DATETIME (0x0c) ---

    "writeMysql for LocalDateTime emits TYPE_DATETIME (0x0c)" in {
        val dt     = java.time.LocalDateTime.of(2026, 5, 5, 12, 30, 0)
        val params = summon[SqlSchema[java.time.LocalDateTime]].writeMysql(dt)
        assert(params.size == 1)
        val p = params(0)
        assert(
            p.encoder.mysqlType == MysqlEncoder.TYPE_DATETIME,
            s"expected TYPE_DATETIME (${MysqlEncoder.TYPE_DATETIME}) but got ${p.encoder.mysqlType}"
        )
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
        assert(s.fieldCount == 22)
        succeed
    }

    // --- sqlTypeName, SQL-canonical type names for primitive schemas ---

    "sqlTypeName for Int is INTEGER" in {
        assert(summon[SqlSchema[Int]].sqlTypeName == "INTEGER")
    }

    "sqlTypeName for String is TEXT" in {
        assert(summon[SqlSchema[String]].sqlTypeName == "TEXT")
    }

    // --- Single-clause using on SqlRow.columnAs and SqlRow.columnDecoded ---

    "SqlRow.columnAs[String] compiles and decodes with single-clause (using Frame, SqlDecoder[String])" in {
        // Compile-time proof: the merged `using` clause resolves without a named binder.
        // `columnAs` is called with no explicit `using`, Frame and SqlDecoder[String] are both summonable.
        val bytes  = Span.from("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val row    = new SqlRow(Chunk(Maybe.Present(bytes)), Chunk.empty, kyo.internal.postgres.types.Format.Text)
        val result = Abort.run[SqlException.Decode](row.columnAs[String](0)).eval
        result match
            case Result.Success(s) => assert(s == "hello")
            case other             => fail(s"Expected Success(hello) but got $other")
    }

    // --- Error-remapping: Decode failure propagates as SqlException ---

    "readPostgres decode failure is a SqlException (executeBoundQuery error-remapping widening contract)" in {
        // Verifies the behavioral contract shared by executeBoundQuery's Abort.recover rewrite:
        // SqlException.Decode IS-A SqlException, so the widening Abort.fail(e: SqlException) succeeds
        // and the result at the outer Abort[SqlException] boundary is a Failure.
        val badBytes = Span.from(Array[Byte](0x01, 0x02, 0x03)) // too short for Long
        val row      = binaryRow("val" -> badBytes)
        val result   = Abort.run[SqlException.Decode](summon[SqlSchema[Long]].readPostgres(row)).eval
        result match
            case Result.Failure(e) =>
                // SqlException.Decode extends SqlException, verify the is-a relationship
                // that makes the Abort.recover widening correct.
                assert(e.isInstanceOf[SqlException])
                succeed
            case other => fail(s"Expected decode failure but got $other")
        end match
    }

    "readMysql decode failure is a SqlException (decodeStream error-remapping widening contract)" in {
        // Same contract via readMysql, covers the decodeStream path.
        val badBytes = Span.from(Array[Byte](0x01, 0x02, 0x03))
        val row      = mysqlBinaryRow("val" -> badBytes)
        val result   = Abort.run[SqlException.Decode](summon[SqlSchema[Long]].readMysql(row)).eval
        result match
            case Result.Failure(e) =>
                assert(e.isInstanceOf[SqlException])
                succeed
            case other => fail(s"Expected decode failure but got $other")
        end match
    }

    "readPostgres decode failure surfaces as Abort[SqlException.Decode] (error-remapping source unchanged)" in {
        // Regression: the SqlSchema error-remapping site must continue to produce SqlException.Decode
        // so the Abort.recover in executeBoundQuery/decodeStream can catch and widen it.
        val badBytes = Span.from(Array[Byte](0x01))
        val row      = binaryRow("val" -> badBytes)
        val result   = Abort.run[SqlException.Decode](summon[SqlSchema[Long]].readPostgres(row)).eval
        result match
            case Result.Failure(_: SqlException.Decode) => succeed
            case other                                  => fail(s"Expected Failure(SqlException.Decode) but got $other")
    }

    // --- 22. Schema.derived[Maybe[Int]] round-trips (test 9 / G-Codec-2) ---

    "Schema.derived[Maybe[Int]] Present(42) round-trips via readPostgres" in {
        val intBytes = encode(42)
        val row      = binaryRow("val" -> intBytes)
        val schema   = summon[SqlSchema[Maybe[Int]]]
        val result   = Abort.run(schema.readPostgres(row)).eval
        result match
            case Result.Success(Maybe.Present(v)) => assert(v == 42)
            case other                            => fail(s"Expected Present(42), got $other")
    }

    "Schema.derived[Maybe[Int]] Absent round-trips via readPostgres" in {
        val values = Chunk(Maybe.empty[Span[Byte]])
        val fields = Chunk(field("val"))
        val row    = new SqlRow(values, fields, Format.Binary)
        val schema = summon[SqlSchema[Maybe[Int]]]
        val result = Abort.run(schema.readPostgres(row)).eval
        result match
            case Result.Success(Maybe.Absent) => succeed
            case other                        => fail(s"Expected Absent, got $other")
    }

    // --- 23. Schema.derived[Maybe[String]] round-trips (test 10 / G-Codec-2) ---

    "Schema.derived[Maybe[String]] Present(\"x\") round-trips via readPostgres" in {
        val strBytes = Span.from("x".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val row      = binaryRow("val" -> strBytes)
        val schema   = summon[SqlSchema[Maybe[String]]]
        val result   = Abort.run(schema.readPostgres(row)).eval
        result match
            case Result.Success(Maybe.Present(s)) => assert(s == "x")
            case other                            => fail(s"Expected Present(x), got $other")
    }

    "Schema.derived[Maybe[String]] Absent round-trips via readPostgres" in {
        val values = Chunk(Maybe.empty[Span[Byte]])
        val fields = Chunk(field("val"))
        val row    = new SqlRow(values, fields, Format.Binary)
        val schema = summon[SqlSchema[Maybe[String]]]
        val result = Abort.run(schema.readPostgres(row)).eval
        result match
            case Result.Success(Maybe.Absent) => succeed
            case other                        => fail(s"Expected Absent, got $other")
    }

    // --- 24. SqlSchema.derived[TestColor] both arms round-trip (test 11 / G-Codec-2) ---
    //
    // Auto-selected strategy: string discriminator (both variants are case objects → ValueOf evidence).
    // Single TEXT column holds the variant label.

    "SqlSchema.derived[TestColor] reports fieldCount 1 (single discriminator column)" in {
        assert(summon[SqlSchema[TestColor]].fieldCount == 1)
    }

    "SqlSchema.derived[TestColor].writePostgres(Red) emits a single TEXT-OID param" in {
        val params = summon[SqlSchema[TestColor]].writePostgres(TestColor.Red)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_TEXT)
    }

    "SqlSchema.derived[TestColor] both arms round-trip via readPostgres" in {
        val s = summon[SqlSchema[TestColor]]
        // Round-trip Red via the string-discriminator wire format (TEXT column holding 'Red').
        val redBytes  = Span.from("Red".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val blueBytes = Span.from("Blue".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val redResult = Abort.run(s.readPostgres(
            new SqlRow(Chunk(Maybe.Present(redBytes)), Chunk(field("color")), Format.Text)
        )).eval
        redResult match
            case Result.Success(TestColor.Red) => succeed
            case other                         => fail(s"Expected Success(Red) got $other")
        end match
        val blueResult = Abort.run(s.readPostgres(
            new SqlRow(Chunk(Maybe.Present(blueBytes)), Chunk(field("color")), Format.Text)
        )).eval
        blueResult match
            case Result.Success(TestColor.Blue) => succeed
            case other                          => fail(s"Expected Success(Blue) got $other")
        end match
    }

    "SqlSchema.derived[TestColor] decode raises typed Decode on unknown label" in {
        val unknown = Span.from("Green".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val result = Abort.run[SqlException.Decode](summon[SqlSchema[TestColor]].readPostgres(
            new SqlRow(Chunk(Maybe.Present(unknown)), Chunk(field("color")), Format.Text)
        )).eval
        result match
            case Result.Failure(_: SqlException.Decode) => succeed
            case other                                  => fail(s"Expected typed Decode failure, got $other")
        end match
    }

    "SqlSchema.derived[TestColor] round-trips via readMysql" in {
        val s        = summon[SqlSchema[TestColor]]
        val redBytes = Span.from("Red".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val result = Abort.run(s.readMysql(
            new SqlRow(Chunk(Maybe.Present(redBytes)), Chunk(field("color")), Format.Binary)
        )).eval
        result match
            case Result.Success(TestColor.Red) => succeed
            case other                         => fail(s"Expected Success(Red) got $other")
        end match
    }

    // --- 24b. Scala 3 enum (TestStatus), same string-discriminator path ---

    "SqlSchema.derived[TestStatus] round-trips all three enum cases (write→read via public BoundParam.encoded)" in {
        val s = summon[SqlSchema[TestStatus]]
        Seq(TestStatus.Pending, TestStatus.Active, TestStatus.Cancelled).foreach { v =>
            val params = s.writePostgres(v)
            assert(params.size == 1)
            // Drive bytes through the public BoundParam.encoded surface, no test-side reach into typed value internals.
            val bytes = params(0).encoded match
                case Maybe.Present(b) => b
                case Maybe.Absent     => fail(s"writePostgres($v) produced a NULL param")
            val row    = new SqlRow(Chunk(Maybe.Present(bytes)), Chunk(field("status")), Format.Text)
            val result = Abort.run(s.readMysql(row)).eval
            result match
                case Result.Success(decoded) => assert(decoded == v)
                case other                   => fail(s"Expected Success($v) got $other")
            end match
        }
        succeed
    }

    // --- 24c. Sealed trait with case-class variants (TestEvent), JSON-encoded path ---

    "SqlSchema.derived[TestEvent] reports fieldCount 1 (single jsonb column)" in {
        assert(summon[SqlSchema[TestEvent]].fieldCount == 1)
    }

    "SqlSchema.derived[TestEvent].writePostgres emits a single JSONB-OID param" in {
        val params = summon[SqlSchema[TestEvent]].writePostgres(TestEvent.Login(42L, "10.0.0.1"))
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_JSONB)
    }

    private def pgBytesFromParam(p: kyo.internal.postgres.BoundParam[?], label: String)(using kyo.test.AssertScope): Span[Byte] =
        p.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail(s"$label produced a NULL param")

    private def myBytesFromParam(p: kyo.internal.mysql.BoundMysqlParam[?], label: String)(using kyo.test.AssertScope): Span[Byte] =
        p.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail(s"$label produced a NULL param")

    "SqlSchema.derived[TestEvent] case-class variant round-trips via PG jsonb (write→read same schema)" in {
        val s         = summon[SqlSchema[TestEvent]]
        val original  = TestEvent.Login(42L, "10.0.0.1")
        val params    = s.writePostgres(original)
        val wireBytes = pgBytesFromParam(params(0), s"writePostgres($original)")
        val row       = new SqlRow(Chunk(Maybe.Present(wireBytes)), Chunk(field("event")), Format.Binary)
        val result    = Abort.run(s.readPostgres(row)).eval
        result match
            case Result.Success(decoded) => assert(decoded == original)
            case other                   => fail(s"Expected Success($original) got $other")
        end match
    }

    "SqlSchema.derived[TestEvent] case-object variant round-trips via PG jsonb (write→read same schema)" in {
        val s         = summon[SqlSchema[TestEvent]]
        val params    = s.writePostgres(TestEvent.Heartbeat)
        val wireBytes = pgBytesFromParam(params(0), "writePostgres(Heartbeat)")
        val row       = new SqlRow(Chunk(Maybe.Present(wireBytes)), Chunk(field("event")), Format.Binary)
        val result    = Abort.run(s.readPostgres(row)).eval
        result match
            case Result.Success(TestEvent.Heartbeat) => succeed
            case other                               => fail(s"Expected Success(Heartbeat) got $other")
        end match
    }

    "SqlSchema.derived[TestEvent] all three variants round-trip via MySQL JSON (write→read same schema)" in {
        val s = summon[SqlSchema[TestEvent]]
        val cases = Seq[TestEvent](
            TestEvent.Login(7L, "127.0.0.1"),
            TestEvent.Click(99L),
            TestEvent.Heartbeat
        )
        cases.foreach { original =>
            val params = s.writeMysql(original)
            assert(params.size == 1)
            val wireBytes = myBytesFromParam(params(0), s"writeMysql($original)")
            val row       = new SqlRow(Chunk(Maybe.Present(wireBytes)), Chunk(field("event")), Format.Binary)
            val result    = Abort.run(s.readMysql(row)).eval
            result match
                case Result.Success(decoded) => assert(decoded == original, s"mismatch for $original: got $decoded")
                case other                   => fail(s"Expected Success($original) got $other")
            end match
        }
        succeed
    }

    // --- 25. WindowSpec.Builder.partitionBy replace semantic (Phase 10 / G4.1) ---

    // ---- Replace-semantic tests for Sql.windowSpec.partitionBy (Phase 10 + audit W-5) ----
    //
    // Driven through the user-facing `Sql.windowSpec` DSL, Terms come from `select(c => ...)`
    // accessors, so the LHS is pure DSL. The byte-exact PG SQL on the RHS proves the replace
    // semantic (second `partitionBy` discards the first): a "wrong" append semantic would emit
    // `PARTITION BY "p"."deptId", "p"."age"` instead of just `PARTITION BY "p"."age"`.

    case class WSBPerson(id: Long, name: String, age: Int, deptId: Long) derives Schema
    given SqlSchema[WSBPerson]                            = SqlSchema.derived
    private def renderPg(q: SqlAst.Executable[?]): String = q.renderPostgres.sql

    "Sql.windowSpec.partitionBy single-Column replace: second partitionBy replaces first" in {
        val q = Sql.from[WSBPerson]("p").select(c =>
            Sql.windowSpec.partitionBy(c.p.deptId).partitionBy(c.p.age).rowNumber
        )
        assert(
            renderPg(q) == """SELECT ROW_NUMBER() OVER (PARTITION BY "p"."age") FROM "wsbperson" "p""""
        )
    }

    // PHASE-10-AUDIT W-5: vararg overload `partitionBy(keys: Term[?]*)` has the same replace semantic.
    "Sql.windowSpec.partitionBy vararg replace: partitionBy(a, b).partitionBy(c) keeps only c" in {
        val q = Sql.from[WSBPerson]("p").select(c =>
            Sql.windowSpec.partitionBy(c.p.deptId, c.p.age).partitionBy(c.p.id).rowNumber
        )
        assert(
            renderPg(q) == """SELECT ROW_NUMBER() OVER (PARTITION BY "p"."id") FROM "wsbperson" "p""""
        )
    }

end SqlSchemaTest
