package kyo

class SqlAstRenderTest extends Test:

    // `SqlSchema.BoundValue`'s existential `?#A` value/schema fields can't satisfy strict-equality CanEqual derivation,
    // so the tests compare via `.equals` after widening to `Any`. The given below restores `==` between Any values
    // for tests only.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema
    case class Dept(id: Long, budget: BigDecimal) derives Schema
    case class Order(id: Long, userId: Long) derives Schema

    // Fixtures carrying SQL bind-value types (java.time / opaque). These exercise the natural
    // `Sql.from[<case class>].where(c => c.col == <value>)` path for type-specific round-trip leaves.
    // No `LocalDateTime` fixture: see the `LocalDateTime` bind leaf's comment for why a `LocalDateTime` *column* is not used.
    case class Appointment(id: Long, day: java.time.LocalDate) derives Schema
    case class Alarm(id: Long, time: java.time.LocalTime) derives Schema
    case class Blob(id: Long, data: kyo.Span[Byte]) derives Schema
    case class Stamped(id: Long, moment: kyo.Instant) derives Schema

    "bare table from Sql.from" in {
        val q = Sql.from[Person]("p")
        assert(q.renderPostgres.sql == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p"""")
        assert(q.renderMysql.sql == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p`")
        assert(q.renderPostgres.params.isEmpty)
    }

    "select single column via Sql.from(...).select" in {
        val q = Sql.from[Person]("p").select(c => c.p.name)
        assert(q.renderPostgres.sql == """SELECT "p"."name" FROM "person" "p"""")
        assert(q.renderMysql.sql == "SELECT `p`.`name` FROM `person` `p`")
    }

    "where + select with comparison" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" >= $1)""")
        assert(rm.sql == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` >= ?)")
        assert(rp.params.size == 1)
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "compound predicate" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "")
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(
            rp.sql == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE (("p"."age" >= $1) AND ("p"."name" <> $2))"""
        )
        assert(
            rm.sql == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE ((`p`.`age` >= ?) AND (`p`.`name` <> ?))"
        )
        assert(rp.params.size == 2)
        val bv0: kyo.SqlSchema.BoundValue[?] = rp.params(0)
        val bv1: kyo.SqlSchema.BoundValue[?] = rp.params(1)
        assert((bv0.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv0, summon[SqlSchema[Int]]); assert(ok) }
        assert((bv1.value: Any) == "")
        { val ok = SqlSchema.boundSchemaEqRef(bv1, summon[SqlSchema[String]]); assert(ok) }
    }

    "pure case-class construction, no DSL builders / lambdas at all" in {
        import kyo.SqlAst.*
        val q = Select[Person, String](
            Sql.from[Person]("p"),
            Projection.Resolved(Chunk(Column["name", String]("p", "name", "name"))),
            false
        )
        assert(q.renderPostgres.sql == """SELECT "p"."name" FROM "person" "p"""")
        assert(q.renderMysql.sql == "SELECT `p`.`name` FROM `person` `p`")
    }

    "tuple projection" in {
        val q = Sql.from[Person]("p").select(c => (c.p.name, c.p.age))
        assert(q.renderPostgres.sql == """SELECT "p"."name", "p"."age" FROM "person" "p"""")
        assert(q.renderMysql.sql == "SELECT `p`.`name`, `p`.`age` FROM `person` `p`")
    }

    "where + orderBy + limit (SELECT *)" in {
        val q = Sql.from[Person]("p")
            .where(c => c.p.age >= 18)
            .orderBy(c => c.p.age.desc)
            .limit(10)
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(
            rp.sql == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" >= $1) ORDER BY "p"."age" DESC LIMIT 10"""
        )
        assert(
            rm.sql == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (`p`.`age` >= ?) ORDER BY `p`.`age` DESC LIMIT 10"
        )
        assert(rp.params.size == 1)
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "join + select" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.from[Dept]("d"))
            .on(j => j.p.deptId == j.d.id)
            .select(j => (j.p.name, j.d.budget))
        assert(
            q.renderPostgres.sql == """SELECT "p"."name", "d"."budget" FROM "person" "p" INNER JOIN "dept" "d" ON ("p"."deptId" = "d"."id")"""
        )
        assert(q.renderMysql.sql == "SELECT `p`.`name`, `d`.`budget` FROM `person` `p` INNER JOIN `dept` `d` ON (`p`.`deptId` = `d`.`id`)")
    }

    // --- Schema-carrying `Rendered` leaves ---

    "single Int literal carries summon[SqlSchema[Int]]" in {
        val rp = Sql.from[Person]("p").where(c => c.p.age >= 18).renderPostgres
        assert(rp.params.size == 1)
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "single String literal carries summon[SqlSchema[String]]" in {
        val rp = Sql.from[Person]("p").where(c => c.p.name == "alice").renderPostgres
        assert(rp.params.size == 1)
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == "alice")
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[String]]); assert(ok) }
    }

    "bind-free query renders an empty Chunk" in {
        val rp = Sql.from[Person]("p").renderPostgres
        assert(rp.params.isEmpty)
        // type assertion: Rendered.params is Chunk[SqlSchema.BoundValue[?]], not List[Any]
        val typed: Chunk[SqlSchema.BoundValue[?]] = rp.params
        assert(typed.isEmpty)
    }

    "two literals of different types preserve schema declaration order" in {
        val rp = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "").renderPostgres
        assert(rp.params.size == 2)
        val bv0: kyo.SqlSchema.BoundValue[?] = rp.params(0)
        val bv1: kyo.SqlSchema.BoundValue[?] = rp.params(1)
        assert((bv0.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv0, summon[SqlSchema[Int]]); assert(ok) }
        assert((bv1.value: Any) == "")
        { val ok = SqlSchema.boundSchemaEqRef(bv1, summon[SqlSchema[String]]); assert(ok) }
    }

    "schema is the same instance returned by summon[SqlSchema[Int]]" in {
        val rp                              = Sql.from[Person]("p").where(c => c.p.age >= 18).renderPostgres
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        val expected                        = summon[SqlSchema[Int]]
        { val ok = SqlSchema.boundSchemaEqRef(bv, expected); assert(ok) }
    }

    "Chunk round-trip, value equality and schema identity for each SqlSchema.BoundValue" in {
        val rp                                     = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "").renderPostgres
        val params: Chunk[SqlSchema.BoundValue[?]] = Chunk.from(rp.params)
        assert(params.size == 2)
        val bv0 = params(0)
        val bv1 = params(1)
        // value equality
        assert((bv0.value: Any) == 18)
        assert((bv1.value: Any) == "")
        // schema identity
        { val ok = SqlSchema.boundSchemaEqRef(bv0, summon[SqlSchema[Int]]); assert(ok) }
        { val ok = SqlSchema.boundSchemaEqRef(bv1, summon[SqlSchema[String]]); assert(ok) }
    }

    // --- Dual-string rendering leaves ---

    "one literal parameter uses $1 on PG and ? on MySQL" in {
        val q  = Sql.from[Person]("p").where(c => c.p.name == "alice")
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql.contains("$1"))
        assert(!rp.sql.contains("?"))
        assert(rm.sql.contains("?"))
        assert(!rm.sql.contains("$1"))
        assert(rp.params.size == 1)
        assert((rp.params.head.value: Any) == "alice")
    }

    "three literal parameters use $1,$2,$3 on PG and three ?s on MySQL" in {
        val q = Sql.from[Person]("p").where(c =>
            c.p.age >= 18 && c.p.name != "" && c.p.deptId == 7L
        )
        val rp = q.renderPostgres
        val rm = q.renderMysql
        // Strict positional check on PG: $1, $2, $3 in declaration order.
        assert(rp.sql.contains("$1"))
        assert(rp.sql.contains("$2"))
        assert(rp.sql.contains("$3"))
        assert(!rp.sql.contains("$4"))
        // MySQL: three `?` placeholders, zero `$N`.
        assert(rm.sql.count(_ == '?') == 3)
        assert(!rm.sql.contains("$"))
        assert(rp.params.size == 3)
    }

    "identifier-quote escape (PG doubles \", MySQL doubles `)" in {
        assert(kyo.internal.SqlRender.quoteIdent("a\"b", kyo.internal.SqlBackend.Postgres) == "\"a\"\"b\"")
        assert(kyo.internal.SqlRender.quoteIdent("a`b", kyo.internal.SqlBackend.Mysql) == "`a``b`")
    }

    // --- where age == 30 + select name ---

    "where(age == 30).select(name) emits correct SQL and one Int SqlSchema.BoundValue" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age == 30).select(c => c.p.name)
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" = $1)""")
        assert(rm.sql == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` = ?)")
        assert(rp.params.size == 1)
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 30)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    // --- where name == "Alice" + select id ---

    "where(name == \"Alice\").select(id) emits correct SQL and one String SqlSchema.BoundValue" in {
        val q  = Sql.from[Person]("p").where(c => c.p.name == "Alice").select(c => c.p.id)
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql == """SELECT "p"."id" FROM "person" "p" WHERE ("p"."name" = $1)""")
        assert(rm.sql == "SELECT `p`.`id` FROM `person` `p` WHERE (`p`.`name` = ?)")
        assert(rp.params.size == 1)
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == "Alice")
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[String]]); assert(ok) }
    }

    // --- where age.between(18, 65) + select name ---

    "where(age.between(18, 65)).select(name) emits BETWEEN SQL with two Int params" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age.between(18, 65)).select(c => c.p.name)
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" BETWEEN $1 AND $2)""")
        assert(rm.sql == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` BETWEEN ? AND ?)")
        assert(rp.params.size == 2)
        val bv0: kyo.SqlSchema.BoundValue[?] = rp.params(0)
        val bv1: kyo.SqlSchema.BoundValue[?] = rp.params(1)
        assert((bv0.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv0, summon[SqlSchema[Int]]); assert(ok) }
        assert((bv1.value: Any) == 65)
        { val ok = SqlSchema.boundSchemaEqRef(bv1, summon[SqlSchema[Int]]); assert(ok) }
    }

    // --- Joins ---

    "CrossJoin.columns (merged Record[F1 & F2]) renders CROSS JOIN SQL" in {
        val q = Sql.from[Person]("p").crossJoin(Sql.from[Order]("o")).select(c => (c.p.id, c.o.userId))
        assert(q.renderPostgres.sql == """SELECT "p"."id", "o"."userId" FROM "person" "p" CROSS JOIN "order" "o"""")
        assert(q.renderMysql.sql == "SELECT `p`.`id`, `o`.`userId` FROM `person` `p` CROSS JOIN `order` `o`")
    }

    "Join.columns (merged Record via on) renders INNER JOIN SQL" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.from[Order]("o"))
            .on(c => c.p.id == c.o.userId)
            .select(c => c.p.name)
        assert(q.renderPostgres.sql == """SELECT "p"."name" FROM "person" "p" INNER JOIN "order" "o" ON ("p"."id" = "o"."userId")""")
        assert(q.renderMysql.sql == "SELECT `p`.`name` FROM `person` `p` INNER JOIN `order` `o` ON (`p`.`id` = `o`.`userId`)")
    }

    // --- GroupBy ---

    "GroupBy renders GROUP BY SQL" in {
        // A `Where`-sourced GroupBy is subquery-wrapped by `SqlRender` (the canonical renderer).
        val q  = Sql.from[Person]("p").where(c => c.p.age > 18).groupBy(c => c.p.age)
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(
            rp.sql == """SELECT * FROM (SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" > $1)) AS sub GROUP BY "p"."age""""
        )
        assert(
            rm.sql == "SELECT * FROM (SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (`p`.`age` > ?)) AS sub GROUP BY `p`.`age`"
        )
        assert(rp.params.size == 1)
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert((bv.value: Any) == 18)
    }

    "GroupBy with aggregate projection renders COUNT + GROUP BY inline" in {
        // A `SELECT` over a `Where`-sourced GroupBy is rendered flat by `SqlRender`, the `WHERE` is reused inline,
        // not subquery-wrapped.
        val q = Sql.from[Person]("p").where(c => c.p.age > 18)
            .groupBy(c => c.p.age).select(view => view.age.count)
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql ==
            """SELECT COUNT("p"."age") FROM "person" "p" WHERE ("p"."age" > $1) GROUP BY "p"."age"""")
        assert(rm.sql ==
            "SELECT COUNT(`p`.`age`) FROM `person` `p` WHERE (`p`.`age` > ?) GROUP BY `p`.`age`")
        assert(rp.params.size == 1)
    }

    // --- bind-value round-trip leaves ---

    "Int bind param produces params(0).value == 42 + SqlSchema[Int]" in {
        val rp                              = Sql.from[Person]("p").where(c => c.p.age == 42).renderPostgres
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert(rp.params.size == 1)
        assert((bv.value: Any) == 42)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "String bind param produces params(0).value == \"Alice\" + SqlSchema[String]" in {
        val rp                              = Sql.from[Person]("p").where(c => c.p.name == "Alice").renderPostgres
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert(rp.params.size == 1)
        assert((bv.value: Any) == "Alice")
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[String]]); assert(ok) }
    }

    "Long bind param produces params(0).value == 99L + SqlSchema[Long]" in {
        val rp                              = Sql.from[Person]("p").where(c => c.p.id == 99L).renderPostgres
        val bv: kyo.SqlSchema.BoundValue[?] = rp.params.head
        assert(rp.params.size == 1)
        assert((bv.value: Any) == 99L)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Long]]); assert(ok) }
    }

    // SQL bind-value-type round-trips through the encoder chain. Each leaf is a `Sql.from[<case class>]`
    // query whose `where` predicate compares a `java.time` / opaque-typed column against a literal; the
    // literal becomes a `Literal[T]` bind in the AST that `SqlRender` surfaces as `params(0).value`.

    "LocalDate bind param round-trips" in {
        val rp = Sql.from[Appointment]("a")
            .where(c => c.a.day == java.time.LocalDate.of(2024, 1, 15))
            .renderPostgres
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
            .renderPostgres
        assert(rp.params.size == 1)
        assert((rp.params(0).value: Any) == java.time.LocalDateTime.of(2024, 1, 15, 9, 30, 45, 123000000))
    }

    "LocalTime bind param round-trips" in {
        val rp = Sql.from[Alarm]("a")
            .where(c => c.a.time == java.time.LocalTime.of(13, 45, 30, 500000000))
            .renderPostgres
        assert(rp.params.size == 1)
        assert((rp.params(0).value: Any) == java.time.LocalTime.of(13, 45, 30, 500000000))
    }

    "Span[Byte] bind param round-trips" in {
        val rp = Sql.from[Blob]("b")
            .where(c => c.b.data == kyo.Span.from(Array[Byte](1, 2, 3, 4)))
            .renderPostgres
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
            .renderPostgres
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
            .renderPostgres
        assert(rp.params.size == 3)
        assert((rp.params(0).value: Any) == 18)
        assert((rp.params(1).value: Any) == "")
        assert((rp.params(2).value: Any) == 7L)
    }

    // --- INSERT rendering ---

    // `Insert.Values[T, F]` stores rows as decomposed pure data,
    // `Chunk[Chunk[SqlSchema.BoundValue[?]]]` (outer = rows, inner = one `SqlSchema.BoundValue` per column in declaration
    // order). The `Sql.insert[Person].values(Person(...))` builder eagerly decomposes each row `T` into
    // its per-field `SqlSchema.BoundValue`s via `SqlMacros.rowValues[T]`. The renderer
    // (`SqlRender.insertValues` → `renderDecomposedRows`) emits each cell's `SqlSchema.BoundValue.value`
    // as an inline literal, so bind params stay empty for a literal-VALUES INSERT.
    "INSERT statement renders correct INSERT SQL" in {
        val q  = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L))
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql == """INSERT INTO "person" ("id", "name", "age", "deptId") VALUES (0, 'Alice', 30, 1) RETURNING "id"""")
        assert(rm.sql == "INSERT INTO `person` (`id`, `name`, `age`, `deptId`) VALUES (0, 'Alice', 30, 1)")
        assert(rp.params.isEmpty)
    }

    // Cast node renders CAST(...)
    "Cast node renders CAST(\"p\".\"id\" AS TEXT)" in {
        val rp = Sql.from[Person]("p").select(c => c.p.id.cast[String]).renderPostgres
        assert(rp.sql.contains("""CAST("p"."id" AS TEXT)"""))
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // `SqlStaticProbe.render` invokes the compile-time renderer used by `.run` / `.runStatic`; the same query
    // fed to `.renderPostgres` / `.renderMysql` drives the runtime renderer used by `.runDynamic`. Drift
    // between the two would surface as `.run` and `.runDynamic` returning different execution results on the
    // same query, so the invariant is that both emit byte-identical SQL for every AST shape.
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (same constraint the
    // old `SqlStatic.staticSql` had, `q.value` cannot reduce through a `val` reference), so the same query
    // expression is duplicated between the runtime and probe calls. Keep both copies identical when editing.

    "runtime renderer schema matches static renderer schema for the same AST" in {
        val runtimeR = Sql.from[Person]("p").where(c => c.p.age >= 18).renderPostgres
        val staticR  = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18))
        assert(staticR.params.size == runtimeR.params.size)
        val sBv: kyo.SqlSchema.BoundValue[?] = staticR.params.head
        val rBv: kyo.SqlSchema.BoundValue[?] = runtimeR.params.head
        val schemasEq                        = sBv.schema.asInstanceOf[AnyRef] eq rBv.schema.asInstanceOf[AnyRef]
        assert(schemasEq)
        assert((sBv.value: Any) == (rBv.value: Any))
    }

    "static PG SQL matches runtime SqlRender(Postgres) byte-for-byte" in {
        val rt = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).renderPostgres
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(rs.sql.postgres == rt.sql)
    }

    "static MySQL SQL matches runtime SqlRender(Mysql) byte-for-byte" in {
        val rt = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).renderMysql
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(rs.sql.mysql == rt.sql)
    }

    "GroupBy compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.from[Person]("p").where(c => c.p.age > 18).groupBy(c => c.p.age).renderPostgres
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age > 18).groupBy(c => c.p.age))
        assert(rs.sql.postgres == rt.sql)
    }

    "GroupBy with aggregate projection compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.from[Person]("p").where(c => c.p.age > 18)
            .groupBy(c => c.p.age).select(view => view.age.count)
            .renderPostgres
        val rs = SqlStaticProbe.render(
            Sql.from[Person]("p").where(c => c.p.age > 18)
                .groupBy(c => c.p.age).select(view => view.age.count)
        )
        assert(rs.sql.postgres == rt.sql)
    }

    "regression, static SQL is byte-identical to SqlRender.render for the same AST" in {
        val rp = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).renderPostgres
        val rm = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).renderMysql
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(rs.sql.postgres == rp.sql)
        assert(rs.sql.mysql == rm.sql)
    }

    "INSERT compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L)).renderPostgres
        val rs = SqlStaticProbe.render(Sql.insert[Person].values(Person(0L, "Alice", 30, 1L)))
        assert(rs.sql.postgres == rt.sql)
    }

    "Cast node compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.from[Person]("p").select(c => c.p.id.cast[String]).renderPostgres
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.id.cast[String]))
        assert(rs.sql.postgres == rt.sql)
    }

end SqlAstRenderTest
