package kyo

import kyo.Sql.*
import kyo.db.Idiom
import kyo.internal.SqlColumns

/** Verifies what [[kyo.db.Idiom]] does regardless of SQL flavor: which binds it collects, in what order, and where it puts their
  * placeholders.
  *
  * Every scenario renders through [[IdiomRenderStub]], the core-owned dialect, for two reasons. Naming a real one would tie core's own renderer
  * test to a backend, and the properties under test are not per-flavor: a dialect chooses how a placeholder is spelled, never how many there
  * are or which value each one stands for. The text a specific server accepts is pinned beside that server's dialect instead.
  */
/** A two-column row, for the bind-arity leaves at the bottom of [[IdiomRenderTest]]. Every field is a single column, so it derives as a row,
  * which is exactly what makes it illegal as one case-class field's cell.
  */
case class IdiomRenderTestAmount(value: Long, currency: String) derives SqlSchema

/** A row whose `amount` field is itself a row. It carries no `SqlSchema`: a field must be a single column, and this one is two, which is the
  * compile error the bind-arity leaves pin.
  */
case class IdiomRenderTestPayment(id: Long, amount: IdiomRenderTestAmount)

class IdiomRenderTest extends Test:

    // Sql.BoundValue's existential value field cannot satisfy CanEqual derivation, so bind values are compared
    // after widening to Any.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema
    case class HTTPServer(id: Long, port: Int) derives SqlSchema
    case class Appointment(id: Long, day: java.time.LocalDate) derives SqlSchema
    case class Blob(id: Long, data: Span[Byte]) derives SqlSchema
    case class Stamped(id: Long, moment: Instant) derives SqlSchema
    case class Reading(id: Long, ratio: Float, scale: Double) derives SqlSchema
    case class Priced(id: Long, amount: BigDecimal) derives SqlSchema
    case class Optional(id: Long, note: Maybe[String]) derives SqlSchema

    private def rendered(ast: Sql[?]): Sql.Rendered = ast.render(IdiomRenderStub)

    "a bind-free query renders no params" in {
        val r = rendered(Sql.from[Person]("p"))
        assert(r.params.isEmpty)
        assert(r.onlySql == Present("SELECT [p].[id], [p].[name], [p].[age], [p].[deptId] FROM [person] [p]"))
    }

    "bare from keys the record on the decapitalized type name" in {
        val r = rendered(Sql.from[Person].where(c => c.person.age >= 18).select(c => c.person.name))
        assert(r.onlySql == Present("SELECT [person].[name] FROM [person] [person] WHERE ([person].[age] >= ?1)"))
    }

    "bare from keeps an acronym-leading type name verbatim" in {
        val r = rendered(Sql.from[HTTPServer].where(c => c.HTTPServer.port >= 1))
        assert(r.onlySql == Present(
            "SELECT [HTTPServer].[id], [HTTPServer].[port] FROM [httpserver] [HTTPServer] WHERE ([HTTPServer].[port] >= ?1)"
        ))
    }

    "a self-join aliases one side explicitly beside the derived key" in {
        val q = Sql.from[Person].innerJoin(Sql.from[Person]("m")).on(x => x.person.deptId == x.m.id)
        val r = rendered(q)
        assert(r.onlySql == Present(
            "SELECT * FROM [person] [person] INNER JOIN [person] [m] ON ([person].[deptId] = [m].[id])"
        ))
    }

    "one bind renders one placeholder carrying its value and schema" in {
        val r = rendered(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(r.onlySql == Present("SELECT [p].[name] FROM [person] [p] WHERE ([p].[age] >= ?1)"))
        assert(r.params.size == 1)
        val bound: Sql.BoundValue[?] = r.params.head
        assert((bound.value: Any) == 18)
        assert(SqlColumns.eqRef(bound.schema, summon[SqlSchema.Column[Int]]))
    }

    // The placeholder number and the params index have to agree, or a statement binds the right values into the
    // wrong slots. Two binds of different types make a swap visible.
    "binds are collected in placeholder order, each with its own schema" in {
        val r = rendered(Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != ""))
        assert(r.onlySql.get.contains("([p].[age] >= ?1)"))
        assert(r.onlySql.get.contains("([p].[name] <> ?2)"))
        assert(r.params.size == 2)
        assert((r.params(0).value: Any) == 18)
        assert(SqlColumns.eqRef(r.params(0).schema, summon[SqlSchema.Column[Int]]))
        assert((r.params(1).value: Any) == "")
        assert(SqlColumns.eqRef(r.params(1).schema, summon[SqlSchema.Column[String]]))
    }

    "placeholder numbering continues across every clause that binds" in {
        val r = rendered(
            Sql.from[Person]("p")
                .where(c => c.p.age >= 18 && c.p.name != "" && c.p.deptId == 7L)
        )
        assert(r.params.size == 3)
        assert(r.onlySql.get.contains("?1"))
        assert(r.onlySql.get.contains("?2"))
        assert(r.onlySql.get.contains("?3"))
        assert(!r.onlySql.get.contains("?4"))
        assert((r.params(0).value: Any) == 18)
        assert((r.params(1).value: Any) == "")
        assert((r.params(2).value: Any) == 7L)
    }

    // A BETWEEN binds twice from one clause, so it is the shape most likely to lose a position.
    "a two-bind clause renders both placeholders and both values" in {
        val r = rendered(Sql.from[Person]("p").where(c => c.p.age.between(18, 65)).select(c => c.p.name))
        assert(r.onlySql == Present("SELECT [p].[name] FROM [person] [p] WHERE ([p].[age] BETWEEN ?1 AND ?2)"))
        assert(r.params.size == 2)
        assert((r.params(0).value: Any) == 18)
        assert((r.params(1).value: Any) == 65)
    }

    // Bind values reach the wire through their schema rather than through the SQL text, so a value whose Scala
    // type is opaque has to arrive as the type the encoder expects.
    "a bind of an opaque type carries the value the encoder will see" in {
        val bytes = rendered(Sql.from[Blob]("b").where(c => c.b.data == Span.from(Array[Byte](1, 2, 3, 4))))
        assert(bytes.params.size == 1)
        (bytes.params(0).value: Any) match
            case value: Array[Byte] => assert(value.sameElements(Array[Byte](1, 2, 3, 4)))
            case other              => fail(s"expected the Array[Byte] backing a Span, got $other")

        val stamped = rendered(Sql.from[Stamped]("s").where(c =>
            c.s.moment == Instant.fromJava(java.time.Instant.ofEpochMilli(1705312245123L))
        ))
        assert(stamped.params.size == 1)
        (stamped.params(0).value: Any) match
            case value: java.time.Instant => assert(value.toEpochMilli == 1705312245123L)
            case other                    => fail(s"expected the java.time.Instant backing a kyo.Instant, got $other")
    }

    "a temporal bind round-trips as itself" in {
        val r = rendered(Sql.from[Appointment]("a").where(c => c.a.day == java.time.LocalDate.of(2024, 1, 15)))
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == java.time.LocalDate.of(2024, 1, 15))
    }

    // An INSERT binds every cell of every row, so its statement text depends on the row shape alone and its
    // values all travel as parameters. A change here would silently move values into the statement text, which
    // is where they were escaped by hand and where a String could close its own literal.
    "an INSERT of rows binds every cell in row-then-column order" in {
        val r = rendered(Sql.insert[Person].values(Person(0L, "Alice", 30, 1L)))
        assert(r.onlySql == Present("INSERT INTO [person] ([id], [name], [age], [deptId]) VALUES (?1, ?2, ?3, ?4) RETURNING [id]"))
        assert(r.params.size == 4)
        assert((r.params(0).value: Any) == 0L)
        assert((r.params(1).value: Any) == "Alice")
        assert((r.params(2).value: Any) == 30)
        assert((r.params(3).value: Any) == 1L)
        assert(SqlColumns.eqRef(r.params(1).schema, summon[SqlSchema.Column[String]]))
    }

    "a multi-row INSERT numbers placeholders across rows without restarting" in {
        val r = rendered(Sql.insert[Person].values(Person(0L, "Alice", 30, 1L), Person(0L, "Bob", 25, 2L)))
        assert(
            r.onlySql == Present(
                "INSERT INTO [person] ([id], [name], [age], [deptId]) VALUES (?1, ?2, ?3, ?4), (?5, ?6, ?7, ?8) RETURNING [id]"
            )
        )
        assert(r.params.size == 8)
        assert((r.params(1).value: Any) == "Alice")
        assert((r.params(5).value: Any) == "Bob")
    }

    // Regression guard against SQL injection on this path. The payload is the one that escapes
    // MySQL's default sql_mode: a backslash before the quote makes quote-doubling produce `\''`, where the
    // backslash escapes the first quote and the second closes the literal, so everything after it is statement
    // text. Asserting the value is absent from the SQL and present in the binds is what makes the escape
    // impossible, rather than asserting that one particular escaping got it right.
    "a String cell containing a backslash and a quote never reaches the statement text" in {
        val payload = """x\'), (2, 0x70776e6564) -- """
        val r       = rendered(Sql.insert[Person].values(Person(1L, payload, 30, 1L)))
        val sql     = r.onlySql.get
        assert(sql == "INSERT INTO [person] ([id], [name], [age], [deptId]) VALUES (?1, ?2, ?3, ?4) RETURNING [id]")
        assert(!sql.contains("\\"))
        assert(!sql.contains("'"))
        assert(!sql.contains("0x70776e6564"))
        assert(!sql.contains("--"))
        assert((r.params(1).value: Any) == payload)
    }

    // Float and Double have no literal spelling for their non-finite values: `toString` gives NaN and Infinity,
    // which neither dialect parses as a number. Binding is what makes them expressible at all.
    "non-finite Float and Double cells bind rather than render their toString" in {
        val r   = rendered(Sql.insert[Reading].values(Reading(1L, Float.NaN, Double.PositiveInfinity)))
        val sql = r.onlySql.get
        assert(sql == "INSERT INTO [reading] ([id], [ratio], [scale]) VALUES (?1, ?2, ?3) RETURNING [id]")
        assert(!sql.contains("NaN"))
        assert(!sql.contains("Infinity"))
        assert(r.params.size == 3)
        (r.params(1).value: Any) match
            case f: Float => assert(java.lang.Float.isNaN(f))
            case other    => fail(s"expected the Float cell to bind as a Float, got $other")
        assert((r.params(2).value: Any) == Double.PositiveInfinity)
    }

    // BigDecimal.toString reaches scientific notation past a certain scale, and MySQL reads an exponent as an
    // approximate DOUBLE literal, so an exact decimal inlined that way round-trips through a float.
    "a BigDecimal cell binds rather than rendering its scientific-notation form" in {
        val small = BigDecimal("1E-10")
        val r     = rendered(Sql.insert[Priced].values(Priced(1L, small)))
        val sql   = r.onlySql.get
        assert(sql == "INSERT INTO [priced] ([id], [amount]) VALUES (?1, ?2) RETURNING [id]")
        assert(!sql.contains("E-10"))
        assert((r.params(1).value: Any) == small)
    }

    // The statement text depends on the row's SHAPE and not on which cells hold a value. An absent cell binds like
    // any other, so the two inserts below are the same statement with different parameters rather than two
    // statements, which is what lets one prepared statement serve both. Translating absence to whatever the flavor
    // calls it belongs to the backend's parameter writer, not here.
    "an Absent cell binds, and does not change the statement text" in {
        val absent = rendered(Sql.insert[Optional].values(Optional(1L, Absent)))
        assert(absent.onlySql == Present("INSERT INTO [optional] ([id], [note]) VALUES (?1, ?2) RETURNING [id]"))
        assert(absent.params.size == 2)
        assert((absent.params(0).value: Any) == 1L)
        assert((absent.params(1).value: Any) == Absent)

        val present = rendered(Sql.insert[Optional].values(Optional(1L, Present("hi"))))
        assert(present.onlySql == absent.onlySql)
        assert(present.params.size == 2)
        assert((present.params(1).value: Any) == Present("hi"))
    }

    // Sql.values reaches the same cell renderer through a SELECT's FROM clause, so it inherits the same
    // guarantee: nothing a caller supplies lands in the statement text. The alias column list is the other
    // property this shape carries: a VALUES list names its own columns (`column1` on one flavor, `column_0` on
    // the other), so the projection above it resolves only against an alias that renames them. The row
    // constructor is the dialect's, and this stub spells it the bare way. The derived-table alias is a space and the quoted name with no
    // AS, the ruled portable spelling of a derived-table alias.
    "a VALUES source in a FROM clause binds its cells and names its columns" in {
        val r = rendered(Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)))
        assert(
            r.onlySql == Present(
                "SELECT [pv].[id], [pv].[name], [pv].[age], [pv].[deptId] FROM (VALUES (?1, ?2, ?3, ?4)) [pv]([id], [name], [age], [deptId])"
            )
        )
        assert(r.params.size == 4)
        assert((r.params(1).value: Any) == "Alice")
    }

    // An empty value list has no SQL spelling, `IN ()` being a syntax error on both flavors, and its meaning is
    // not in doubt: nothing is a member of the empty set. So the empty IN is constant false and the empty NOT IN
    // is constant true, which is what the two leaves below pin, each with a non-empty control beside it so a
    // change that lost the list entirely could not pass as the empty case.
    "an empty in renders constant false, and a non-empty one still renders its list" in {
        val none: Seq[Int] = Seq.empty
        val empty          = rendered(Sql.from[Person]("p").where(c => c.p.age.in(none*)))
        assert(empty.onlySql == Present("SELECT [p].[id], [p].[name], [p].[age], [p].[deptId] FROM [person] [p] WHERE (FALSE)"))
        assert(empty.params.isEmpty)

        val filled = rendered(Sql.from[Person]("p").where(c => c.p.age.in(Seq(18, 21)*)))
        assert(
            filled.onlySql == Present(
                "SELECT [p].[id], [p].[name], [p].[age], [p].[deptId] FROM [person] [p] WHERE ([p].[age] IN (?1, ?2))"
            )
        )
        assert(filled.params.toSeq.map(_.value: Any) == Seq(18, 21))
    }

    "an empty notIn renders constant true, and a non-empty one still renders its list" in {
        val none: Seq[Int] = Seq.empty
        val empty          = rendered(Sql.from[Person]("p").where(c => c.p.age.notIn(none*)))
        assert(empty.onlySql == Present("SELECT [p].[id], [p].[name], [p].[age], [p].[deptId] FROM [person] [p] WHERE (TRUE)"))
        assert(empty.params.isEmpty)

        val filled = rendered(Sql.from[Person]("p").where(c => c.p.age.notIn(Seq(18, 21)*)))
        assert(
            filled.onlySql == Present(
                "SELECT [p].[id], [p].[name], [p].[age], [p].[deptId] FROM [person] [p] WHERE ([p].[age] NOT IN (?1, ?2))"
            )
        )
        assert(filled.params.toSeq.map(_.value: Any) == Seq(18, 21))
    }

    // A projected query is orderable and limitable in SQL, and the ordering may name a column the projection
    // leaves out, which is why the ordering lambda sees the source's columns rather than the projected terms.
    "a projected query orders and limits without a nested wrapper" in {
        val r = rendered(
            Sql.from[Person]("p")
                .where(c => c.p.age >= 18)
                .select(c => c.p.name)
                .orderBy(c => c.p.age.desc)
                .limit(10)
        )
        assert(
            r.onlySql == Present(
                "SELECT [p].[name] FROM [person] [p] WHERE ([p].[age] >= ?1) ORDER BY [p].[age] DESC LIMIT 10"
            )
        )
        assert(r.params.toSeq.map(_.value: Any) == Seq(18))
    }

    // Sql.default is the one assignable value that is not an expression, and DEFAULT is legal in exactly the
    // positions that accept an assignment. The INSERT override is the case it exists for: the row carries a value
    // for the key and the override replaces that one cell, so the server assigns the key instead.
    "Sql.default renders DEFAULT in an insert override and in an UPDATE SET" in {
        val overridden = rendered(Sql.insert[Person].values(Person(7L, "Alice", 30, 1L)).overriding(_.id := Sql.default))
        assert(
            overridden.onlySql == Present(
                "INSERT INTO [person] ([id], [name], [age], [deptId]) VALUES (DEFAULT, ?1, ?2, ?3) RETURNING [id]"
            )
        )
        // The row's own 7 never travels: three binds for four columns, and the key's cell is the keyword.
        assert(overridden.params.size == 3)
        assert(overridden.params.toSeq.map(_.value: Any) == Seq("Alice", 30, 1L))

        val updated = rendered(Sql.update[Person].set(_.deptId := Sql.default).build)
        assert(updated.onlySql == Present("UPDATE [person] SET [deptId] = DEFAULT"))
        assert(updated.params.isEmpty)
    }

    // The render is asked for one dialect, so it answers for exactly that one and records the version it
    // resolved. Naming no version means the dialect's floor.
    "a render covers the dialect it was asked for, at the version it resolved" in {
        val r = rendered(Sql.from[Person]("p"))
        assert(r.perDialect.keySet == Set(Idiom.Id("stub")))
        assert(r.perDialect(Idiom.Id("stub")).version == IdiomRenderStub.capabilityFloor)
        assert(r.sqlFor(Idiom.Id("stub")) == r.onlySql)
    }

    "a render at a named version records that version instead of the floor" in {
        val version = Idiom.ServerVersion(2, 3, 4)
        val r       = Sql.from[Person]("p").render(IdiomRenderStub, Present(version))
        assert(r.perDialect(Idiom.Id("stub")).version == version)
    }

    // --- An aggregate over a wrapped query needs a derived table ---
    //
    // `.count` is defined on every `Query`, so its source can be any node, and the aggregate's own `SELECT` owns the statement's
    // top-level clauses. A source that already carries `LIMIT`, `GROUP BY` or `ORDER BY` therefore cannot be flattened into the FROM
    // clause: the clause lands on the COUNT rather than on the rows being counted, and the statement either answers the wrong number or
    // fails to parse. Only a `From` (a real FROM item) and a `Where` (which contributes the aggregate's own WHERE) flatten. A wrapped
    // source becomes `(...) [sub]`, the derived-table alias quoted (through the stub's brackets) and carrying no `AS`, the ruled portable spelling.

    "count over a limited query counts the limited rows, not the table" in {
        // Flattened this reads `SELECT COUNT(*) FROM [person] [p] LIMIT 10`, which is valid SQL that counts the WHOLE table and then
        // returns its single row, so the limit silently does nothing.
        val r = rendered(Sql.from[Person]("p").limit(10).count)
        assert(
            r.onlySql == Present(
                "SELECT COUNT(*) FROM (SELECT [p].[id], [p].[name], [p].[age], [p].[deptId] FROM [person] [p] LIMIT 10) [sub]"
            )
        )
    }

    "count over a limited-with-offset query keeps the offset inside the derived table" in {
        val r = rendered(Sql.from[Person]("p").limit(10, 5).count)
        assert(
            r.onlySql == Present(
                "SELECT COUNT(*) FROM (SELECT [p].[id], [p].[name], [p].[age], [p].[deptId] FROM [person] [p] LIMIT 10 OFFSET 5) [sub]"
            )
        )
    }

    "count over a grouped query counts the groups, not the rows of each group" in {
        // Flattened this reads `SELECT COUNT(*) FROM [person] [p] GROUP BY [p].[deptId]`, which returns ONE ROW PER GROUP carrying that
        // group's size, where `.count` promises a single number. A grouping is reachable only through its projection, so the grouped
        // source arrives as a `Select` and stays a derived table.
        val r = rendered(Sql.from[Person]("p").groupBy(c => c.p.deptId).select(view => view.deptId).count)
        assert(
            r.onlySql == Present(
                "SELECT COUNT(*) FROM (SELECT [p].[deptId] FROM [person] [p] GROUP BY [p].[deptId]) [sub]"
            )
        )
    }

    "count over an ordered query keeps the ordering inside the derived table" in {
        // Flattened this reads `SELECT COUNT(*) FROM [person] [p] ORDER BY [p].[age] ASC`, which PostgreSQL rejects outright: the
        // ordering column is neither grouped nor aggregated.
        val r = rendered(Sql.from[Person]("p").orderBy(c => c.p.age.asc).count)
        assert(
            r.onlySql == Present(
                "SELECT COUNT(*) FROM (SELECT [p].[id], [p].[name], [p].[age], [p].[deptId] FROM [person] [p] ORDER BY [p].[age] ASC) [sub]"
            )
        )
    }

    // The two sources that legitimately flatten, kept beside the four above so a fix that wraps everything is caught here rather than by
    // a reviewer.
    "count over a table and over a filtered table stay flat" in {
        assert(rendered(Sql.from[Person]("p").count).onlySql == Present("SELECT COUNT(*) FROM [person] [p]"))
        val filtered = rendered(Sql.from[Person]("p").where(c => c.p.age >= 18).count)
        assert(filtered.onlySql == Present("SELECT COUNT(*) FROM [person] [p] WHERE ([p].[age] >= ?1)"))
        assert(filtered.params.toSeq.map(_.value: Any) == Seq(18))
    }

    // --- A bind position holds one column ---
    //
    // A placeholder stands for one parameter, and the parameter writer expands a bind through its codec, so a codec occupying N columns
    // would put N parameters behind one placeholder and the statement would reach the server with a parameter count the caller never
    // wrote. Every bind position therefore takes `SqlSchema.Column`, which is one column by construction, so a row at a bind is a compile
    // error at the call site rather than a render-time refusal. The leaves below pin the refusals, each with the running render beside it
    // so a change that admitted the row could not pass as the compile error.

    "a row field that is itself a row cannot be an INSERT cell" in {
        // Admitted, this would render `INSERT INTO [idiomrendertestpayment] ([id], [amount]) VALUES (?1, ?2)`: two columns, two
        // placeholders, and THREE values on the writer, because the `amount` cell writes its own two.
        typeCheckFailure(
            """Sql.insert[IdiomRenderTestPayment].values(IdiomRenderTestPayment(1L, IdiomRenderTestAmount(250L, "EUR")))"""
        )("is not a single-column SQL type")
    }

    "a row is not an sql interpolation argument" in {
        typeCheckFailure(
            """val amount = IdiomRenderTestAmount(250L, "EUR")
               sql"SELECT 1 WHERE amount = $amount""""
        )("is not a single-column SQL type")
    }

    // A `Maybe` of a multi-column value is the wrapper form of the same arity problem: `Maybe[A]` is a column only when `A` is one, so the
    // wrapper does not smuggle a row into a bind either. Absent and Present are the same static shape, which is why one leaf covers both.
    "a Maybe of a row is not an sql interpolation argument either" in {
        typeCheckFailure(
            """val amount = Maybe(IdiomRenderTestAmount(250L, "EUR"))
               sql"SELECT 1 WHERE amount = $amount""""
        )("is not a single-column SQL type")
    }

    // The control for all three: a single-column value of a type that occupies one column binds normally through the same seam, so the
    // refusals above are about the column count and not about the interpolation.
    "a single-column value binds through the same seam" in {
        val at = java.time.OffsetDateTime.parse("2026-05-22T10:30:00+05:30")
        val r  = rendered(sql"SELECT 1 WHERE at = $at")
        assert(r.onlySql == Present("SELECT 1 WHERE at = ?1"))
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == at)
    }

    // The `Maybe` control: a `Maybe` of a single column is a single column, so both arms bind and neither changes the statement text.
    "a Maybe of a single column binds in both arms" in {
        val present: Maybe[String] = Maybe("hi")
        val absent: Maybe[String]  = Absent
        val withValue              = rendered(sql"SELECT 1 WHERE note = $present")
        val withNone               = rendered(sql"SELECT 1 WHERE note = $absent")
        assert(withValue.onlySql == Present("SELECT 1 WHERE note = ?1"))
        assert(withNone.onlySql == withValue.onlySql)
        assert((withValue.params(0).value: Any) == Maybe("hi"))
        assert((withNone.params(0).value: Any) == Absent)
    }

end IdiomRenderTest
