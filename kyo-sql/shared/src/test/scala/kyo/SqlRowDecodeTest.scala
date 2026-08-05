package kyo

/** Unit tests for [[SqlRow]]'s decode surface.
  *
  * A row carries its producing backend's [[SqlRow.Codec]], so `decode` never inspects wire bytes itself: it locates the starting column and hands
  * the row to that codec. These tests drive a mock codec so the routing (by position, by name, the offset a multi-column row starts at) is
  * asserted independently of any backend, and pin the typed failures a caller can see.
  */
class SqlRowDecodeTest extends Test:

    private def row(names: String*)(recorded: SqlSchemaWriterMock.Call*): SqlRow =
        SqlRowCodecMock.decodableRow(Chunk.from(names), Chunk.from(recorded))

    "decode with no index reads from the first column" in {
        val r = row("n")(SqlSchemaWriterMock.Call.Long(42L))
        assert(Abort.run(r.decode[Long]).eval == Result.Success(42L))
    }

    "decode by index reads from that column" in {
        val r = row("a", "b")(SqlSchemaWriterMock.Call.Long(1L), SqlSchemaWriterMock.Call.Str("two"))
        assert(Abort.run(r.decode[String](1)).eval == Result.Success("two"))
    }

    "decode by name resolves the index from the column names" in {
        val r = row("a", "b")(SqlSchemaWriterMock.Call.Long(1L), SqlSchemaWriterMock.Call.Str("two"))
        assert(Abort.run(r.decode[String]("b")).eval == Result.Success("two"))
    }

    "decode by an unknown name aborts with SqlDecodeColumnNotFoundException" in {
        val r = row("a")(SqlSchemaWriterMock.Call.Long(1L))
        Abort.run[SqlDecodeException](r.decode[Long]("missing")).eval match
            case Result.Failure(e: SqlDecodeColumnNotFoundException) => assert(e.getMessage.contains("missing"))
            case other                                               => fail(s"expected a column-not-found failure, got $other")
    }

    "decode by an out-of-bounds index aborts with SqlDecodeColumnOutOfBoundsException" in {
        val r = row("a")(SqlSchemaWriterMock.Call.Long(1L))
        Abort.run[SqlDecodeException](r.decode[Long](3)).eval match
            case Result.Failure(e: SqlDecodeColumnOutOfBoundsException) => assert(e.getMessage.contains("3"))
            case other                                                  => fail(s"expected an out-of-bounds failure, got $other")
    }

    "decode on an empty row aborts rather than returning a value" in {
        val r = row()()
        Abort.run[SqlDecodeException](r.decode[Long]).eval match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"expected a decode failure, got $other")
    }

    "a multi-column row decodes from its starting column onward" in {
        given SqlSchema[SqlRowDecodePair] = SqlSchema.ofMulti[SqlRowDecodePair](Seq("id", "name"))(
            write = (v, w) =>
                w.long(v.id)
                w.string(v.name)
        )(
            read = r => SqlRowDecodePair(r.long(), r.string())
        )
        val r = row("skip", "id", "name")(
            SqlSchemaWriterMock.Call.Str("ignored"),
            SqlSchemaWriterMock.Call.Long(7L),
            SqlSchemaWriterMock.Call.Str("ada")
        )
        assert(Abort.run(r.decode[SqlRowDecodePair](1)).eval == Result.Success(SqlRowDecodePair(7L, "ada")))
    }

    "a decode failure inside the codec surfaces as Abort[SqlDecodeException], not a throw" in {
        val r = row("n")(SqlSchemaWriterMock.Call.Str("not a long"))
        Abort.run[SqlDecodeException](r.decode[Long]).eval match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"expected a decode failure, got $other")
    }

    // `Result.catching[Throwable]` admits every throwable into `Result.Failure`, so without the classification arm
    // an interrupt would come back as a `SqlDecodeColumnDecodeException`: a caller handling `SqlDecodeException`
    // by type would recover from it and carry on, having swallowed a cancellation. The same holds for a `LinkageError`
    // and an `OutOfMemoryError`; `InterruptedException` is the one that needs no VM damage to raise.
    //
    // It propagates rather than arriving as a `Result.Panic`, because kyo's policy is that a throwable `NonFatal`
    // excludes is never carried as a value: `Result.Panic.apply` rethrows it. No `Abort.run[SqlDecodeException]`
    // can absorb it.
    "an interrupt raised by a decoder propagates instead of being reported as a decode failure" in {
        val interrupt = new InterruptedException()
        val thrown = intercept[InterruptedException] {
            val _ = Abort.run[SqlDecodeException](SqlRow.Codec.catching[Long](throw interrupt)).eval
        }
        assert(thrown eq interrupt)
    }

    // An `Error` that `NonFatal` accepts is a decode failure like any other, and must stay on the declared channel
    // rather than following the interrupt out. This is the pair to the leaf above: one classification, two outcomes,
    // and asserting only one of them would leave the arm that routes the other untested.
    "an Error the runtime can recover from stays a typed decode failure" in {
        val raised = new AssertionError("boom")
        Abort.run[SqlDecodeException](SqlRow.Codec.catching[Long](throw raised)).eval match
            case Result.Failure(e: SqlDecodeColumnDecodeException) =>
                assert(e.columnIndex == Maybe.Absent)
                e.cause match
                    case t: Throwable => assert(t eq raised)
                    case s: String    => fail(s"expected the raised error as the cause, got the string '$s'")
            case other => fail(s"expected a whole-row column-decode failure, got $other")
        end match
    }

    "size, columnNames, and column report the row's shape" in {
        val bytes = Span.from("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val r     = SqlRowCodecMock.row(Chunk("a", "b"), Chunk(Maybe.Present(bytes), Maybe.Absent), Chunk.empty)
        assert(r.size == 2)
        assert(r.columnNames == Chunk("a", "b"))
        assert(r.column(0).map(_.toArray.toSeq) == Maybe(Seq[Byte]('h', 'e', 'l', 'l', 'o')))
        assert(r.column(1) == Maybe.Absent, "a NULL column reads as Absent")
        assert(r.column(2) == Maybe.Absent, "an out-of-bounds index reads as Absent")
        assert(r.column("b") == Maybe.Absent)
        assert(r.column("nope") == Maybe.Absent)
    }

    "slice narrows the columns and carries the codec over" in {
        val r = row("a", "b", "c")(SqlSchemaWriterMock.Call.Long(1L), SqlSchemaWriterMock.Call.Long(2L), SqlSchemaWriterMock.Call.Long(3L))
        val sliced = r.slice(1, 3)
        assert(sliced.size == 2)
        assert(sliced.columnNames == Chunk("b", "c"))
        assert(sliced.codec == r.codec)
    }

    "two rows with the same columns, bytes, and codec are equal" in {
        val bytes1 = Span.from(Array[Byte](1, 2, 3))
        val bytes2 = Span.from(Array[Byte](1, 2, 3)) // a distinct instance with the same content
        val first  = SqlRowCodecMock.row(Chunk("a"), Chunk(Maybe.Present(bytes1)), Chunk.empty)
        val second = SqlRowCodecMock.row(Chunk("a"), Chunk(Maybe.Present(bytes2)), Chunk.empty)
        assert(first == second)
        assert(first.hashCode == second.hashCode)
        val different = SqlRowCodecMock.row(Chunk("a"), Chunk(Maybe.Present(Span.from(Array[Byte](1, 2, 4)))), Chunk.empty)
        assert(first != different)
    }

    // A NULL in a `Maybe` field is where the reader's null contract is load-bearing. The nullable column reads as
    // `if r.isNil() then Maybe.empty else Present(read(...))`, so a reader whose `isNil` answered true WITHOUT
    // consuming the column would leave that null column in front of the next field's read and shift every value
    // after it by one. A top-level `Maybe` cannot show that, because it has no following field.
    "a NULL in a Maybe field decodes as Absent and consumes its own column" in {
        val r = row("id", "nickname")(SqlSchemaWriterMock.Call.Long(7L), SqlSchemaWriterMock.Call.Nil)
        assert(Abort.run(r.decode[SqlRowDecodeNullable]).eval == Result.Success(SqlRowDecodeNullable(7L, Absent)))
    }

    "a present value in a Maybe field still decodes as Present" in {
        val r = row("id", "nickname")(SqlSchemaWriterMock.Call.Long(7L), SqlSchemaWriterMock.Call.Str("ada"))
        assert(Abort.run(r.decode[SqlRowDecodeNullable]).eval == Result.Success(SqlRowDecodeNullable(7L, Present("ada"))))
    }

    "two NULL Maybe fields in a row each consume their own column" in {
        val r = row("id", "nickname", "note")(SqlSchemaWriterMock.Call.Long(7L), SqlSchemaWriterMock.Call.Nil, SqlSchemaWriterMock.Call.Nil)
        assert(Abort.run(r.decode[SqlRowDecodeTwoNullable]).eval == Result.Success(SqlRowDecodeTwoNullable(7L, Absent, Absent)))
    }

    "a NULL Maybe field followed by a non-null field leaves that field readable" in {
        val r =
            row("id", "nickname", "age")(SqlSchemaWriterMock.Call.Long(7L), SqlSchemaWriterMock.Call.Nil, SqlSchemaWriterMock.Call.Int(31))
        assert(Abort.run(r.decode[SqlRowDecodeTrailing]).eval == Result.Success(SqlRowDecodeTrailing(7L, Absent, 31)))
    }

    // Naming is a read-side concern too. A row codec's field names are the Scala names (post `@column`), while
    // a cased query's server columns carry the resolved ones, so the casing has to reach the decode or a snake-cased
    // field is never matched and the read fails while the write direction works. Casing is query-scoped, so it
    // arrives through the run path's `decodeRow` rather than being carried by the codec: the public `decode` API has
    // no query scope and matches verbatim, which the following leaf pins.
    "a snake-cased query decodes a row whose columns carry the resolved names" in {
        val r = row("id", "first_name", "created_at")(
            SqlSchemaWriterMock.Call.Long(7L),
            SqlSchemaWriterMock.Call.Str("ada"),
            SqlSchemaWriterMock.Call.Long(1700000000L)
        )
        val decoded = r.decodeRow(summon[SqlSchema[SqlRowDecodeSnake]], Maybe(SqlNaming.SnakeCase), SqlRow.FieldMatch.ByName)
        assert(Abort.run(decoded).eval == Result.Success(SqlRowDecodeSnake(7L, "ada", 1700000000L)))
    }

    // Without the query scope there is no casing to apply, so the row's cased columns share no name with the codec's
    // fields and the read falls back to position. It lands the same values here because the query listed the columns
    // in field order, which is the honest reading: verbatim matching found nothing to match on.
    "the public decode API applies no casing, and falls back to position" in {
        val r = row("first_name", "id", "created_at")(
            SqlSchemaWriterMock.Call.Str("ada"),
            SqlSchemaWriterMock.Call.Long(7L),
            SqlSchemaWriterMock.Call.Long(1700000000L)
        )
        Abort.run[SqlDecodeException](r.decode[SqlRowDecodeSnake]).eval match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"expected the positional read to reject the swapped columns, got $other")
    }

    "a renamed field decodes from the column it was renamed to" in {
        val r = row("id", "nick")(SqlSchemaWriterMock.Call.Long(7L), SqlSchemaWriterMock.Call.Str("ada"))
        assert(Abort.run(r.decode[SqlRowDecodeRenamed]).eval == Result.Success(SqlRowDecodeRenamed(7L, "ada")))
    }

    // A projection is the case that has no names to match on: the columns are named after the source table, or after
    // whatever the flavor calls a computed expression, while the target's fields are named for the caller's domain.
    // Matching falls back to position there, which is what `Select.to`'s own Mirror constraint already proves about
    // the types, so a caller writes neither `.as` labels nor a column-shaped target.
    "a target whose field names differ from every column decodes positionally" in {
        val r = row("name", "age")(SqlSchemaWriterMock.Call.Str("ada"), SqlSchemaWriterMock.Call.Int(31))
        assert(Abort.run(r.decode[SqlRowDecodeSummary]).eval == Result.Success(SqlRowDecodeSummary("ada", 31)))
    }

    // Positional is the fallback, not the rule: when the row does carry the schema's names, they decide, so a column
    // order the schema did not choose still lands each value in its own field. That is the raw-SQL case, where the
    // caller wrote the SELECT and the order is theirs.
    "a row whose columns carry the schema's names decodes by name, not by position" in {
        val r = row("nickname", "id")(SqlSchemaWriterMock.Call.Str("ada"), SqlSchemaWriterMock.Call.Long(7L))
        assert(Abort.run(r.decode[SqlRowDecodePlain]).eval == Result.Success(SqlRowDecodePlain(7L, "ada")))
    }

end SqlRowDecodeTest

// The multi-column fixture carries no derived instance: its leaf installs a hand-written `SqlSchema.ofMulti` codec.
case class SqlRowDecodePair(id: Long, name: String) derives CanEqual

case class SqlRowDecodeSnake(id: Long, firstName: String, createdAt: Long) derives SqlSchema, CanEqual
case class SqlRowDecodeRenamed(id: Long, @column("nick") nickname: String) derives SqlSchema, CanEqual
case class SqlRowDecodePlain(id: Long, nickname: String) derives SqlSchema, CanEqual
case class SqlRowDecodeSummary(fullName: String, years: Int) derives SqlSchema, CanEqual
case class SqlRowDecodeNullable(id: Long, nickname: Maybe[String]) derives SqlSchema, CanEqual
case class SqlRowDecodeTwoNullable(id: Long, nickname: Maybe[String], note: Maybe[String]) derives SqlSchema, CanEqual
case class SqlRowDecodeTrailing(id: Long, nickname: Maybe[String], age: Int) derives SqlSchema, CanEqual
