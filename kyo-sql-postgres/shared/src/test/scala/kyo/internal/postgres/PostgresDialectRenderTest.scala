package kyo.internal.postgres

import kyo.*
import kyo.Test

/** Verifies the SQL [[PostgresDialect]] renders for the core statement shapes: bare tables, projections, predicates, joins, grouping, and
  * inserts.
  *
  * Byte-exact assertions, because the text is the contract with the server: identifier quoting, placeholder syntax, and clause order are
  * all observable to it. The last group compares this dialect's compile-time render against its run-time render for the same statement,
  * which is the property that keeps `.run` and `.runDynamic` returning the same results.
  */
class PostgresDialectRenderTest extends Test:

    // `Sql.BoundValue`'s existential `?#A` value/codec fields can't satisfy strict-equality CanEqual derivation,
    // so the tests compare via `.equals` after widening to `Any`. The given below restores `==` between Any values
    // for tests only.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Person(id: Long, name: String, age: Int, deptId: Long)
    case class Dept(id: Long, budget: BigDecimal)
    case class Order(id: Long, userId: Long)
    case class CamelRow(userId: Long, firstName: String)
    case class RenamedRow(@column("uid") userId: Long, name: String)

    // Fixtures carrying SQL bind-value types (java.time / opaque). These exercise the natural
    // `Sql.from[<case class>].where(c => c.col == <value>)` path for type-specific round-trip leaves.
    // No `LocalDateTime` fixture: see the `LocalDateTime` bind leaf's comment for why a `LocalDateTime` *column* is not used.
    case class Appointment(id: Long, day: java.time.LocalDate)
    case class Alarm(id: Long, time: java.time.LocalTime)
    case class Blob(id: Long, data: kyo.Span[Byte])
    case class Stamped(id: Long, moment: kyo.Instant)

    "bare table from Sql.from" in {
        val q = Sql.from[Person]("p")
        assert(q.render(PostgresDialect).onlySql.get == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p"""")
        assert(q.render(PostgresDialect).params.isEmpty)
    }

    "select single column via Sql.from(...).select" in {
        val q = Sql.from[Person]("p").select(c => c.p.name)
        assert(q.render(PostgresDialect).onlySql.get == """SELECT "p"."name" FROM "person" "p"""")
    }

    "where + select with comparison" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)
        val rp = q.render(PostgresDialect)
        assert(rp.onlySql.get == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" >= $1)""")
        assert(rp.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    "compound predicate" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "")
        val rp = q.render(PostgresDialect)
        assert(
            rp.onlySql.get == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE (("p"."age" >= $1) AND ("p"."name" <> $2))"""
        )
        assert(rp.params.size == 2)
        val bv0: kyo.Sql.BoundValue[?] = rp.params(0)
        val bv1: kyo.Sql.BoundValue[?] = rp.params(1)
        assert((bv0.value: Any) == 18)
        assert(bv0.schema eq SqlSchema.int)
        assert((bv1.value: Any) == "")
        assert(bv1.schema eq SqlSchema.string)
    }

    "pure case-class construction, no DSL builders / lambdas at all" in {
        import kyo.Sql.*
        // `Select` carries the source's column record, so the hand-built node takes it from the source rather
        // than inventing one. `B` appears only in `extends Query[B]` and so cannot be inferred from any
        // argument; the ascription supplies it, while `A` comes from `src` and `F` from `src.columns`.
        val src = Sql.from[Person]("p")
        val q: Query[String] = Select(
            src,
            src.columns,
            Projection.Resolved(Chunk(Column["name", String]("p", "name", "name"))),
            false
        )
        assert(q.render(PostgresDialect).onlySql.get == """SELECT "p"."name" FROM "person" "p"""")
    }

    "tuple projection" in {
        val q = Sql.from[Person]("p").select(c => (c.p.name, c.p.age))
        assert(q.render(PostgresDialect).onlySql.get == """SELECT "p"."name", "p"."age" FROM "person" "p"""")
    }

    "where + orderBy + limit (SELECT *)" in {
        val q = Sql.from[Person]("p")
            .where(c => c.p.age >= 18)
            .orderBy(c => c.p.age.desc)
            .limit(10)
        val rp = q.render(PostgresDialect)
        assert(
            rp.onlySql.get == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" >= $1) ORDER BY "p"."age" DESC LIMIT 10"""
        )
        assert(rp.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    "join + select" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.from[Dept]("d"))
            .on(j => j.p.deptId == j.d.id)
            .select(j => (j.p.name, j.d.budget))
        assert(
            q.render(
                PostgresDialect
            ).onlySql.get == """SELECT "p"."name", "d"."budget" FROM "person" "p" INNER JOIN "dept" "d" ON ("p"."deptId" = "d"."id")"""
        )
    }

    // --- Codec-carrying `Rendered` leaves ---

    "a single Int bind carries the Int column codec" in {
        val rp = Sql.from[Person]("p").where(c => c.p.age >= 18).render(PostgresDialect)
        assert(rp.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    "a single String bind carries the String column codec" in {
        val rp = Sql.from[Person]("p").where(c => c.p.name == "alice").render(PostgresDialect)
        assert(rp.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == "alice")
        assert(bv.schema eq SqlSchema.string)
    }

    "bind-free query renders an empty Chunk" in {
        val rp = Sql.from[Person]("p").render(PostgresDialect)
        assert(rp.params.isEmpty)
        // type assertion: Rendered.params is Chunk[Sql.BoundValue[?]], not List[Any]
        val typed: Chunk[Sql.BoundValue[?]] = rp.params
        assert(typed.isEmpty)
    }

    "two literals of different types preserve schema declaration order" in {
        val rp = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "").render(PostgresDialect)
        assert(rp.params.size == 2)
        val bv0: kyo.Sql.BoundValue[?] = rp.params(0)
        val bv1: kyo.Sql.BoundValue[?] = rp.params(1)
        assert((bv0.value: Any) == 18)
        assert(bv0.schema eq SqlSchema.int)
        assert((bv1.value: Any) == "")
        assert(bv1.schema eq SqlSchema.string)
    }

    "the bind carries the same column instance summon returns" in {
        val rp                        = Sql.from[Person]("p").where(c => c.p.age >= 18).render(PostgresDialect)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        val expected                  = summon[SqlSchema.Column[Int]]
        assert(bv.schema eq expected)
    }

    "Chunk round-trip, value equality and schema identity for each Sql.BoundValue" in {
        val rp                               = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "").render(PostgresDialect)
        val params: Chunk[Sql.BoundValue[?]] = Chunk.from(rp.params)
        assert(params.size == 2)
        val bv0 = params(0)
        val bv1 = params(1)
        // value equality
        assert((bv0.value: Any) == 18)
        assert((bv1.value: Any) == "")
        // schema identity
        assert(bv0.schema eq SqlSchema.int)
        assert(bv1.schema eq SqlSchema.string)
    }

    // --- Dual-string rendering leaves ---

    "one literal parameter uses $1 on PG and ? on MySQL" in {
        val q  = Sql.from[Person]("p").where(c => c.p.name == "alice")
        val rp = q.render(PostgresDialect)
        assert(rp.onlySql.get.contains("$1"))
        assert(!rp.onlySql.get.contains("?"))
        assert(rp.params.size == 1)
        assert((rp.params.head.value: Any) == "alice")
    }

    "three literal parameters number as $1, $2, $3 in declaration order on PG" in {
        val q = Sql.from[Person]("p").where(c =>
            c.p.age >= 18 && c.p.name != "" && c.p.deptId == 7L
        )
        val rp = q.render(PostgresDialect)
        assert(
            rp.onlySql.get == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ((("p"."age" >= $1) AND ("p"."name" <> $2)) AND ("p"."deptId" = $3))"""
        )
        assert(rp.params.size == 3)
    }

    "identifier-quote escape doubles the double quote on PG" in {
        assert(PostgresDialect.quoteIdent("a\"b") == "\"a\"\"b\"")
    }

    // --- where age == 30 + select name ---

    "where(age == 30).select(name) emits correct SQL and one Int Sql.BoundValue" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age == 30).select(c => c.p.name)
        val rp = q.render(PostgresDialect)
        assert(rp.onlySql.get == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" = $1)""")
        assert(rp.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 30)
        assert(bv.schema eq SqlSchema.int)
    }

    // --- where name == "Alice" + select id ---

    "where(name == \"Alice\").select(id) emits correct SQL and one String Sql.BoundValue" in {
        val q  = Sql.from[Person]("p").where(c => c.p.name == "Alice").select(c => c.p.id)
        val rp = q.render(PostgresDialect)
        assert(rp.onlySql.get == """SELECT "p"."id" FROM "person" "p" WHERE ("p"."name" = $1)""")
        assert(rp.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == "Alice")
        assert(bv.schema eq SqlSchema.string)
    }

    // --- where age.between(18, 65) + select name ---

    "where(age.between(18, 65)).select(name) emits BETWEEN SQL with two Int params" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age.between(18, 65)).select(c => c.p.name)
        val rp = q.render(PostgresDialect)
        assert(rp.onlySql.get == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" BETWEEN $1 AND $2)""")
        assert(rp.params.size == 2)
        val bv0: kyo.Sql.BoundValue[?] = rp.params(0)
        val bv1: kyo.Sql.BoundValue[?] = rp.params(1)
        assert((bv0.value: Any) == 18)
        assert(bv0.schema eq SqlSchema.int)
        assert((bv1.value: Any) == 65)
        assert(bv1.schema eq SqlSchema.int)
    }

    // --- Joins ---

    "CrossJoin.columns (merged Record[F1 & F2]) renders CROSS JOIN SQL" in {
        val q = Sql.from[Person]("p").crossJoin(Sql.from[Order]("o")).select(c => (c.p.id, c.o.userId))
        assert(q.render(PostgresDialect).onlySql.get == """SELECT "p"."id", "o"."userId" FROM "person" "p" CROSS JOIN "order" "o"""")
    }

    "Join.columns (merged Record via on) renders INNER JOIN SQL" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.from[Order]("o"))
            .on(c => c.p.id == c.o.userId)
            .select(c => c.p.name)
        assert(q.render(
            PostgresDialect
        ).onlySql.get == """SELECT "p"."name" FROM "person" "p" INNER JOIN "order" "o" ON ("p"."id" = "o"."userId")""")
    }

    // --- GroupBy ---

    "GroupBy with aggregate projection renders COUNT + GROUP BY inline" in {
        // A `SELECT` over a `Where`-sourced GroupBy is rendered flat by the dialect, the `WHERE` is reused inline,
        // not subquery-wrapped.
        val q = Sql.from[Person]("p").where(c => c.p.age > 18)
            .groupBy(c => c.p.age).select(view => view.age.count)
        val rp = q.render(PostgresDialect)
        assert(rp.onlySql.get ==
            """SELECT COUNT("p"."age") FROM "person" "p" WHERE ("p"."age" > $1) GROUP BY "p"."age"""")
        assert(rp.params.size == 1)
    }

    // --- bind-value round-trip leaves ---

    "Int bind param produces params(0).value == 42 plus the Int column codec" in {
        val rp                        = Sql.from[Person]("p").where(c => c.p.age == 42).render(PostgresDialect)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert(rp.params.size == 1)
        assert((bv.value: Any) == 42)
        assert(bv.schema eq SqlSchema.int)
    }

    "String bind param produces params(0).value == \"Alice\" plus the String column codec" in {
        val rp                        = Sql.from[Person]("p").where(c => c.p.name == "Alice").render(PostgresDialect)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert(rp.params.size == 1)
        assert((bv.value: Any) == "Alice")
        assert(bv.schema eq SqlSchema.string)
    }

    "Long bind param produces params(0).value == 99L plus the Long column codec" in {
        val rp                        = Sql.from[Person]("p").where(c => c.p.id == 99L).render(PostgresDialect)
        val bv: kyo.Sql.BoundValue[?] = rp.params.head
        assert(rp.params.size == 1)
        assert((bv.value: Any) == 99L)
        assert(bv.schema eq SqlSchema.long)
    }

    // SQL bind-value-type round-trips through the encoder chain. Each leaf is a `Sql.from[<case class>]`
    // query whose `where` predicate compares a `java.time` / opaque-typed column against a literal; the
    // literal becomes a `Literal[T]` bind in the AST that the render surfaces as `params(0).value`.

    "LocalDate bind param round-trips" in {
        val rp = Sql.from[Appointment]("a")
            .where(c => c.a.day == java.time.LocalDate.of(2024, 1, 15))
            .render(PostgresDialect)
        assert(rp.params.size == 1)
        assert((rp.params(0).value: Any) == java.time.LocalDate.of(2024, 1, 15))
    }

    // The bind is supplied via `Sql.literal[LocalDateTime](...)` projected from a `Sql.from[Person]`
    // query, NOT a `LocalDateTime`-typed *column* on the source case class. A case class with a
    // `java.time.LocalDateTime` field cannot be passed to `Sql.from[T]`: building its columns calls
    // `summonInline[Tag[LocalDateTime]]`, and `kyo.Tag` derivation for `LocalDateTime` crashes the
    // dotty macro with `java.lang.AssertionError: TypeBounds(Nothing, FromJavaObject)` in
    // `TagMacro.deriveImpl`. `LocalDate`, `LocalTime`, `Span[Byte]`, and `kyo.Instant` columns all
    // compile fine. `LocalDateTime`'s parent interface `ChronoLocalDateTime<LocalDate>` carries a type
    // argument the Tag macro's parent walk mishandles. `Sql.literal` only needs `SqlSchema[LocalDateTime]`,
    // not `Tag[LocalDateTime]`, so it sidesteps the Tag bug while still exercising a real `Sql.from`
    // query carrying a `LocalDateTime` `Literal` bind.
    "LocalDateTime bind param round-trips" in {
        val rp = Sql.from[Person]("p")
            .select(_ => Sql.literal(java.time.LocalDateTime.of(2024, 1, 15, 9, 30, 45, 123000000)))
            .render(PostgresDialect)
        assert(rp.params.size == 1)
        assert((rp.params(0).value: Any) == java.time.LocalDateTime.of(2024, 1, 15, 9, 30, 45, 123000000))
    }

    "LocalTime bind param round-trips" in {
        val rp = Sql.from[Alarm]("a")
            .where(c => c.a.time == java.time.LocalTime.of(13, 45, 30, 500000000))
            .render(PostgresDialect)
        assert(rp.params.size == 1)
        assert((rp.params(0).value: Any) == java.time.LocalTime.of(13, 45, 30, 500000000))
    }

    "Span[Byte] bind param round-trips" in {
        val rp = Sql.from[Blob]("b")
            .where(c => c.b.data == kyo.Span.from(Array[Byte](1, 2, 3, 4)))
            .render(PostgresDialect)
        assert(rp.params.size == 1)
        // `Span[Byte]` is an opaque alias of `Array[Byte]`; the runtime value is the backing array.
        val got: Any = rp.params(0).value
        got match
            case arr: Array[Byte] => assert(arr.sameElements(Array[Byte](1, 2, 3, 4)))
            case other            => fail(s"expected Array[Byte]-backed Span, got $other")
    }

    "kyo.Instant bind param round-trips" in {
        val rp = Sql.from[Stamped]("s")
            .where(c => c.s.moment == kyo.Instant.fromJava(java.time.Instant.ofEpochMilli(1705312245123L)))
            .render(PostgresDialect)
        assert(rp.params.size == 1)
        // `kyo.Instant` is an opaque alias of `java.time.Instant`; the runtime value is the Java instant.
        val got: Any = rp.params(0).value
        got match
            case i: java.time.Instant => assert(i.toEpochMilli == 1705312245123L)
            case other                => fail(s"expected java.time.Instant-backed kyo.Instant, got $other")
    }

    "multi-bind query produces params.size == 3 in declaration order" in {
        val rp = Sql.from[Person]("p")
            .where(c => c.p.age >= 18 && c.p.name != "" && c.p.deptId == 7L)
            .render(PostgresDialect)
        assert(rp.params.size == 3)
        assert((rp.params(0).value: Any) == 18)
        assert((rp.params(1).value: Any) == "")
        assert((rp.params(2).value: Any) == 7L)
    }

    // --- INSERT rendering ---

    // `Insert.Values[T, F]` stores rows as decomposed pure data,
    // `Chunk[Chunk[Sql.BoundValue[?]]]` (outer = rows, inner = one `Sql.BoundValue` per column in declaration
    // order). The `Sql.insert[Person].values(Person(...))` builder eagerly decomposes each row `T` into
    // its per-field `Sql.BoundValue`s via `SqlMacros.rowValues[T]`. The renderer
    // (`Idiom.insertValues` → `decomposedRows`) binds each cell, so the statement text carries a
    // placeholder per column and every value travels in the param list.
    "INSERT statement renders correct INSERT SQL" in {
        val q  = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L))
        val rp = q.render(PostgresDialect)
        assert(rp.onlySql.get == """INSERT INTO "person" ("id", "name", "age", "deptId") VALUES ($1, $2, $3, $4) RETURNING "id"""")
        assert(rp.params.toSeq.map(_.value: Any) == Seq(0L, "Alice", 30, 1L))
    }

    // Cast node renders CAST(...)
    "Cast node renders CAST(\"p\".\"id\" AS TEXT)" in {
        val rp = Sql.from[Person]("p").select(c => c.p.id.cast[String]).render(PostgresDialect)
        assert(rp.onlySql.get.contains("""CAST("p"."id" AS TEXT)"""))
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // `SqlStaticProbe.render` invokes the compile-time renderer used by `.run` / `.runStatic`; the same query
    // fed to `ast.render(dialect)` drives the runtime renderer used by `.runDynamic`. Drift
    // between the two would surface as `.run` and `.runDynamic` returning different execution results on the
    // same query, so the invariant is that both emit byte-identical SQL for every AST shape.
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (`q.value` cannot reduce
    // through a `val` reference), so the same query expression is duplicated between the runtime and probe calls.
    // Keep both copies identical when editing.

    "runtime renderer schema matches static renderer schema for the same AST" in {
        val runtimeR = Sql.from[Person]("p").where(c => c.p.age >= 18).render(PostgresDialect)
        val staticR  = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18))
        assert(staticR.params.size == runtimeR.params.size)
        val sBv: kyo.Sql.BoundValue[?] = staticR.params.head
        val rBv: kyo.Sql.BoundValue[?] = runtimeR.params.head
        val schemasEq                  = sBv.schema.asInstanceOf[AnyRef] eq rBv.schema.asInstanceOf[AnyRef]
        assert(schemasEq)
        assert((sBv.value: Any) == (rBv.value: Any))
    }

    "static PG SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    "GroupBy with aggregate projection compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.from[Person]("p").where(c => c.p.age > 18)
            .groupBy(c => c.p.age).select(view => view.age.count)
            .render(PostgresDialect)
        val rs = SqlStaticProbe.render(
            Sql.from[Person]("p").where(c => c.p.age > 18)
                .groupBy(c => c.p.age).select(view => view.age.count)
        )
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    "regression, static SQL is byte-identical to the runtime render for the same AST" in {
        val rp = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(rs.sqlFor(PostgresDialect.id).get == rp.onlySql.get)
    }

    "INSERT compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L)).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.insert[Person].values(Person(0L, "Alice", 30, 1L)))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    "Cast node compile-time render matches runtime render byte-for-byte" in {
        // `.cast[String]` targets the fieldless `Type.Text` and would lift even if the `SqlType` givens were plain
        // rather than inline. The field-carrying cases are the load-bearing pins: `.cast[BigDecimal]` carries
        // `Type.Numeric(Absent, Absent)` and `.cast[Chunk[Int]]` carries `Type.Array(Type.Int)`, which the generic
        // static lift can read only when the given inlines the enum value as a literal at the `.cast` site. A plain
        // given would leave the target a reference to a module val, the lift would answer None, and the static render
        // would diverge from the runtime one below.
        val rtS = Sql.from[Person]("p").select(c => c.p.id.cast[String]).render(PostgresDialect)
        val rsS = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.id.cast[String]))
        assert(rsS.sqlFor(PostgresDialect.id).get == rtS.onlySql.get)

        val rtN = Sql.from[Person]("p").select(c => c.p.id.cast[BigDecimal]).render(PostgresDialect)
        val rsN = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.id.cast[BigDecimal]))
        assert(rsN.sqlFor(PostgresDialect.id).get == rtN.onlySql.get)
        assert(rtN.onlySql.get.contains("AS NUMERIC"), s"expected the Numeric spelling, got: ${rtN.onlySql.get}")

        val rtA = Sql.from[Person]("p").select(c => c.p.id.cast[Chunk[Int]]).render(PostgresDialect)
        val rsA = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.id.cast[Chunk[Int]]))
        assert(rsA.sqlFor(PostgresDialect.id).get == rtA.onlySql.get)
        assert(rtA.onlySql.get.contains("AS INTEGER[]"), s"expected the Array spelling, got: ${rtA.onlySql.get}")
    }

    "an explicit table-name parameter overrides the T-derived default (EP2 166a)" in {
        val q = Sql.from[Person]("p", "people").select(c => c.p.name)
        assert(q.render(PostgresDialect).onlySql.get == """SELECT "p"."name" FROM "people" "p"""")
    }

    "without the table-name parameter the name stays the lowercased type label" in {
        val q = Sql.from[Person]("p").select(c => c.p.name)
        assert(q.render(PostgresDialect).onlySql.get == """SELECT "p"."name" FROM "person" "p"""")
    }

    "INSERT and DELETE take an explicit table name" in {
        val ins = Sql.insert[Person]("people").values(Person(0L, "Alice", 30, 1L)).render(PostgresDialect)
        assert(ins.onlySql.get.contains("""INSERT INTO "people""""), s"expected the explicit table, got: ${ins.onlySql.get}")
        val del = Sql.delete[Person]("people").where(_.id == 1L).render(PostgresDialect)
        assert(del.onlySql.get.contains("""DELETE FROM "people""""), s"expected the explicit table, got: ${del.onlySql.get}")
    }

    "the table-name parameter folds under a static render" in {
        val rt = Sql.from[Person]("p", "people").select(c => c.p.name).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.from[Person]("p", "people").select(c => c.p.name))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
        assert(rt.onlySql.get.contains("""FROM "people""""), s"expected the explicit table, got: ${rt.onlySql.get}")
    }

    "an in-scope SqlNaming.SnakeCase casts column names (EP2 166b)" in {
        given SqlNaming = SqlNaming.SnakeCase
        val sql         = Sql.from[CamelRow]("r").select(c => c.r.userId).render(PostgresDialect).onlySql.get
        assert(sql.contains(""""r"."user_id""""), s"expected snake_case column, got: $sql")
    }

    "without an SqlNaming given, column names stay verbatim" in {
        val sql = Sql.from[CamelRow]("r").select(c => c.r.userId).render(PostgresDialect).onlySql.get
        assert(sql.contains(""""r"."userId""""), s"expected verbatim column, got: $sql")
    }

    "a @column rename casts a column to its wire name" in {
        val sql = Sql.from[RenamedRow]("r").select(c => c.r.userId).render(PostgresDialect).onlySql.get
        assert(sql.contains(""""r"."uid""""), s"expected the @column wire name, got: $sql")
    }

end PostgresDialectRenderTest
