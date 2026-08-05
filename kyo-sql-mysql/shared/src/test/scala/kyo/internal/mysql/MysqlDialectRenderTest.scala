package kyo.internal.mysql

import kyo.*
import kyo.Test

/** Verifies the SQL [[MysqlDialect]] renders for the core statement shapes: bare tables, projections, predicates, joins, grouping, and
  * inserts.
  *
  * Byte-exact assertions, because the text is the contract with the server: identifier quoting, placeholder syntax, and clause order are all
  * observable to it. The last group compares this dialect's compile-time render against its run-time render for the same statement, which is
  * the property that keeps `.run` and `.runDynamic` returning the same results.
  */
class MysqlDialectRenderTest extends Test:

    // `Sql.BoundValue`'s existential `?#A` value/codec fields can't satisfy strict-equality CanEqual derivation,
    // so the tests compare via `.equals` after widening to `Any`. The given below restores `==` between Any values
    // for tests only.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Person(id: Long, name: String, age: Int, deptId: Long)
    case class Dept(id: Long, budget: BigDecimal)
    case class Order(id: Long, userId: Long)

    // Fixtures carrying SQL bind-value types (java.time / opaque). These exercise the natural
    // `Sql.from[<case class>].where(c => c.col == <value>)` path for type-specific round-trip leaves.
    // No `LocalDateTime` fixture: see the `LocalDateTime` bind leaf's comment for why a `LocalDateTime` *column* is not used.
    case class Appointment(id: Long, day: java.time.LocalDate)
    case class Alarm(id: Long, time: java.time.LocalTime)
    case class Blob(id: Long, data: kyo.Span[Byte])
    case class Stamped(id: Long, moment: kyo.Instant)

    "bare table from Sql.from" in {
        val q = Sql.from[Person]("p")
        assert(q.render(MysqlDialect).onlySql.get == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p`")
        assert(q.render(MysqlDialect).params.isEmpty)
    }

    "select single column via Sql.from(...).select" in {
        val q = Sql.from[Person]("p").select(c => c.p.name)
        assert(q.render(MysqlDialect).onlySql.get == "SELECT `p`.`name` FROM `person` `p`")
    }

    "where + select with comparison" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` >= ?)")
        assert(rm.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    "compound predicate" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "")
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE ((`p`.`age` >= ?) AND (`p`.`name` <> ?))"
        )
        assert(rm.params.size == 2)
        val bv0: kyo.Sql.BoundValue[?] = rm.params(0)
        val bv1: kyo.Sql.BoundValue[?] = rm.params(1)
        assert((bv0.value: Any) == 18)
        assert(bv0.schema eq SqlSchema.int)
        assert((bv1.value: Any) == "")
        assert(bv1.schema eq SqlSchema.string)
    }

    "pure case-class construction, no DSL builders / lambdas at all" in {
        import kyo.Sql.*
        // `Select` carries the source's column record now, so the hand-built node takes it from the source
        // rather than inventing one. `B` appears only in `extends Query[B]` and so cannot be inferred from any
        // argument; the ascription supplies it, while `A` comes from `src` and `F` from `src.columns`.
        val src = Sql.from[Person]("p")
        val q: Query[String] = Select(
            src,
            src.columns,
            Projection.Resolved(Chunk(Column["name", String]("p", "name", "name"))),
            false
        )
        assert(q.render(MysqlDialect).onlySql.get == "SELECT `p`.`name` FROM `person` `p`")
    }

    "tuple projection" in {
        val q = Sql.from[Person]("p").select(c => (c.p.name, c.p.age))
        assert(q.render(MysqlDialect).onlySql.get == "SELECT `p`.`name`, `p`.`age` FROM `person` `p`")
    }

    "where + orderBy + limit (SELECT *)" in {
        val q = Sql.from[Person]("p")
            .where(c => c.p.age >= 18)
            .orderBy(c => c.p.age.desc)
            .limit(10)
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (`p`.`age` >= ?) ORDER BY `p`.`age` DESC LIMIT 10"
        )
        assert(rm.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    "join + select" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.from[Dept]("d"))
            .on(j => j.p.deptId == j.d.id)
            .select(j => (j.p.name, j.d.budget))
        assert(q.render(
            MysqlDialect
        ).onlySql.get == "SELECT `p`.`name`, `d`.`budget` FROM `person` `p` INNER JOIN `dept` `d` ON (`p`.`deptId` = `d`.`id`)")
    }

    // --- Codec-carrying `Rendered` leaves ---

    "a single Int bind carries the Int column codec" in {
        val rm = Sql.from[Person]("p").where(c => c.p.age >= 18).render(MysqlDialect)
        assert(rm.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    "a single String bind carries the String column codec" in {
        val rm = Sql.from[Person]("p").where(c => c.p.name == "alice").render(MysqlDialect)
        assert(rm.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert((bv.value: Any) == "alice")
        assert(bv.schema eq SqlSchema.string)
    }

    "bind-free query renders an empty Chunk" in {
        val rm = Sql.from[Person]("p").render(MysqlDialect)
        assert(rm.params.isEmpty)
        // type assertion: Rendered.params is Chunk[Sql.BoundValue[?]], not List[Any]
        val typed: Chunk[Sql.BoundValue[?]] = rm.params
        assert(typed.isEmpty)
    }

    "two literals of different types preserve schema declaration order" in {
        val rm = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "").render(MysqlDialect)
        assert(rm.params.size == 2)
        val bv0: kyo.Sql.BoundValue[?] = rm.params(0)
        val bv1: kyo.Sql.BoundValue[?] = rm.params(1)
        assert((bv0.value: Any) == 18)
        assert(bv0.schema eq SqlSchema.int)
        assert((bv1.value: Any) == "")
        assert(bv1.schema eq SqlSchema.string)
    }

    "the bind carries the same column instance summon returns" in {
        val rm                        = Sql.from[Person]("p").where(c => c.p.age >= 18).render(MysqlDialect)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        val expected                  = summon[SqlSchema.Column[Int]]
        assert(bv.schema eq expected)
    }

    "Chunk round-trip, value equality and schema identity for each Sql.BoundValue" in {
        val rm                               = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "").render(MysqlDialect)
        val params: Chunk[Sql.BoundValue[?]] = Chunk.from(rm.params)
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
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get.contains("?"))
        assert(!rm.onlySql.get.contains("$1"))
        assert(rm.params.size == 1)
        assert((rm.params.head.value: Any) == "alice")
    }

    "three literal parameters render as three unnumbered ?s on MySQL" in {
        val q = Sql.from[Person]("p").where(c =>
            c.p.age >= 18 && c.p.name != "" && c.p.deptId == 7L
        )
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (((`p`.`age` >= ?) AND (`p`.`name` <> ?)) AND (`p`.`deptId` = ?))"
        )
        assert(rm.params.size == 3)
    }

    "identifier-quote escape doubles the backtick on MySQL" in {
        assert(MysqlDialect.quoteIdent("a`b") == "`a``b`")
    }

    // --- where age == 30 + select name ---

    "where(age == 30).select(name) emits correct SQL and one Int Sql.BoundValue" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age == 30).select(c => c.p.name)
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` = ?)")
        assert(rm.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert((bv.value: Any) == 30)
        assert(bv.schema eq SqlSchema.int)
    }

    // --- where name == "Alice" + select id ---

    "where(name == \"Alice\").select(id) emits correct SQL and one String Sql.BoundValue" in {
        val q  = Sql.from[Person]("p").where(c => c.p.name == "Alice").select(c => c.p.id)
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "SELECT `p`.`id` FROM `person` `p` WHERE (`p`.`name` = ?)")
        assert(rm.params.size == 1)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert((bv.value: Any) == "Alice")
        assert(bv.schema eq SqlSchema.string)
    }

    // --- where age.between(18, 65) + select name ---

    "where(age.between(18, 65)).select(name) emits BETWEEN SQL with two Int params" in {
        val q  = Sql.from[Person]("p").where(c => c.p.age.between(18, 65)).select(c => c.p.name)
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` BETWEEN ? AND ?)")
        assert(rm.params.size == 2)
        val bv0: kyo.Sql.BoundValue[?] = rm.params(0)
        val bv1: kyo.Sql.BoundValue[?] = rm.params(1)
        assert((bv0.value: Any) == 18)
        assert(bv0.schema eq SqlSchema.int)
        assert((bv1.value: Any) == 65)
        assert(bv1.schema eq SqlSchema.int)
    }

    // --- Joins ---

    "CrossJoin.columns (merged Record[F1 & F2]) renders CROSS JOIN SQL" in {
        val q = Sql.from[Person]("p").crossJoin(Sql.from[Order]("o")).select(c => (c.p.id, c.o.userId))
        assert(q.render(MysqlDialect).onlySql.get == "SELECT `p`.`id`, `o`.`userId` FROM `person` `p` CROSS JOIN `order` `o`")
    }

    "Join.columns (merged Record via on) renders INNER JOIN SQL" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.from[Order]("o"))
            .on(c => c.p.id == c.o.userId)
            .select(c => c.p.name)
        assert(
            q.render(MysqlDialect).onlySql.get == "SELECT `p`.`name` FROM `person` `p` INNER JOIN `order` `o` ON (`p`.`id` = `o`.`userId`)"
        )
    }

    // --- GroupBy ---

    "GroupBy with aggregate projection renders COUNT + GROUP BY inline" in {
        // A `SELECT` over a `Where`-sourced GroupBy is rendered flat by the dialect, the `WHERE` is reused inline,
        // not subquery-wrapped.
        val q = Sql.from[Person]("p").where(c => c.p.age > 18)
            .groupBy(c => c.p.age).select(view => view.age.count)
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get ==
            "SELECT COUNT(`p`.`age`) FROM `person` `p` WHERE (`p`.`age` > ?) GROUP BY `p`.`age`")
        assert(rm.params.size == 1)
    }

    // --- bind-value round-trip leaves ---

    "Int bind param produces params(0).value == 42 plus the Int column codec" in {
        val rm                        = Sql.from[Person]("p").where(c => c.p.age == 42).render(MysqlDialect)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert(rm.params.size == 1)
        assert((bv.value: Any) == 42)
        assert(bv.schema eq SqlSchema.int)
    }

    "String bind param produces params(0).value == \"Alice\" plus the String column codec" in {
        val rm                        = Sql.from[Person]("p").where(c => c.p.name == "Alice").render(MysqlDialect)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert(rm.params.size == 1)
        assert((bv.value: Any) == "Alice")
        assert(bv.schema eq SqlSchema.string)
    }

    "Long bind param produces params(0).value == 99L plus the Long column codec" in {
        val rm                        = Sql.from[Person]("p").where(c => c.p.id == 99L).render(MysqlDialect)
        val bv: kyo.Sql.BoundValue[?] = rm.params.head
        assert(rm.params.size == 1)
        assert((bv.value: Any) == 99L)
        assert(bv.schema eq SqlSchema.long)
    }

    // SQL bind-value-type round-trips through the encoder chain. Each leaf is a `Sql.from[<case class>]`
    // query whose `where` predicate compares a `java.time` / opaque-typed column against a literal; the
    // literal becomes a `Literal[T]` bind in the AST that the render surfaces as `params(0).value`.

    "LocalDate bind param round-trips" in {
        val rm = Sql.from[Appointment]("a")
            .where(c => c.a.day == java.time.LocalDate.of(2024, 1, 15))
            .render(MysqlDialect)
        assert(rm.params.size == 1)
        assert((rm.params(0).value: Any) == java.time.LocalDate.of(2024, 1, 15))
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
        val rm = Sql.from[Person]("p")
            .select(_ => Sql.literal(java.time.LocalDateTime.of(2024, 1, 15, 9, 30, 45, 123000000)))
            .render(MysqlDialect)
        assert(rm.params.size == 1)
        assert((rm.params(0).value: Any) == java.time.LocalDateTime.of(2024, 1, 15, 9, 30, 45, 123000000))
    }

    "LocalTime bind param round-trips" in {
        val rm = Sql.from[Alarm]("a")
            .where(c => c.a.time == java.time.LocalTime.of(13, 45, 30, 500000000))
            .render(MysqlDialect)
        assert(rm.params.size == 1)
        assert((rm.params(0).value: Any) == java.time.LocalTime.of(13, 45, 30, 500000000))
    }

    "Span[Byte] bind param round-trips" in {
        val rm = Sql.from[Blob]("b")
            .where(c => c.b.data == kyo.Span.from(Array[Byte](1, 2, 3, 4)))
            .render(MysqlDialect)
        assert(rm.params.size == 1)
        // `Span[Byte]` is an opaque alias of `Array[Byte]`; the runtime value is the backing array.
        val got: Any = rm.params(0).value
        got match
            case arr: Array[Byte] => assert(arr.sameElements(Array[Byte](1, 2, 3, 4)))
            case other            => fail(s"expected Array[Byte]-backed Span, got $other")
    }

    "kyo.Instant bind param round-trips" in {
        val rm = Sql.from[Stamped]("s")
            .where(c => c.s.moment == kyo.Instant.fromJava(java.time.Instant.ofEpochMilli(1705312245123L)))
            .render(MysqlDialect)
        assert(rm.params.size == 1)
        // `kyo.Instant` is an opaque alias of `java.time.Instant`; the runtime value is the Java instant.
        val got: Any = rm.params(0).value
        got match
            case i: java.time.Instant => assert(i.toEpochMilli == 1705312245123L)
            case other                => fail(s"expected java.time.Instant-backed kyo.Instant, got $other")
    }

    "multi-bind query produces params.size == 3 in declaration order" in {
        val rm = Sql.from[Person]("p")
            .where(c => c.p.age >= 18 && c.p.name != "" && c.p.deptId == 7L)
            .render(MysqlDialect)
        assert(rm.params.size == 3)
        assert((rm.params(0).value: Any) == 18)
        assert((rm.params(1).value: Any) == "")
        assert((rm.params(2).value: Any) == 7L)
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
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "INSERT INTO `person` (`id`, `name`, `age`, `deptId`) VALUES (?, ?, ?, ?)")
        assert(rm.params.toSeq.map(_.value: Any) == Seq(0L, "Alice", 30, 1L))
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // `SqlStaticProbe.render` invokes the compile-time renderer used by `.run` / `.runStatic`; the same query
    // fed to `ast.render(dialect)` drives the runtime renderer used by `.runDynamic`. Drift
    // between the two would surface as `.run` and `.runDynamic` returning different execution results on the
    // same query, so the invariant is that both emit byte-identical SQL for every AST shape.
    //
    // `SqlStaticProbe.render` requires a fully-inline expression: `q.value` cannot reduce through a `val` reference.
    // So the same query expression is duplicated between the runtime and probe calls; keep both copies identical
    // when editing.

    "static MySQL SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).render(MysqlDialect)
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(rs.sqlFor(MysqlDialect.id).get == rt.onlySql.get)
    }

    "regression, static SQL is byte-identical to the runtime render for the same AST" in {
        val rm = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).render(MysqlDialect)
        val rs = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(rs.sqlFor(MysqlDialect.id).get == rm.onlySql.get)
    }

end MysqlDialectRenderTest
