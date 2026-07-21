package kyo

import kyo.Sql.render
import kyo.internal.RecordFromExpr.given

class SqlStaticTest extends Test:

    // `BoundValue`'s existential `?#A` value/schema fields can't satisfy strict-equality CanEqual derivation,
    // so the tests compare via `.equals` after widening to `Any`. The given below restores `==` between Any values
    // for tests only.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema
    case class Dept(id: Long, budget: BigDecimal) derives Schema
    case class Order(id: Long, userId: Long) derives Schema

    // Fixtures carrying SQL bind-value types (java.time / opaque). These exercise the natural
    // `Sql.from[<case class>].where(c => c.col == <value>)` path for type-specific round-trip leaves.
    // No `LocalDateTime` fixture: see leaf 6's comment for why a `LocalDateTime` *column* is not used.
    case class Appointment(id: Long, day: java.time.LocalDate) derives Schema
    case class Alarm(id: Long, time: java.time.LocalTime) derives Schema
    case class Blob(id: Long, data: kyo.Span[Byte]) derives Schema
    case class Stamped(id: Long, moment: kyo.Instant) derives Schema

    "bare table from production Sql.from" in {
        val r = SqlStatic.staticSql(Sql.from[Person]("p"))
        assert(r.sql.postgres == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p"""")
        assert(r.sql.mysql == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p`")
        assert(r.params.isEmpty)
    }

    "select single column via Sql.from(...).select" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c => c.p.name)
        )
        assert(r.sql.postgres == """SELECT "p"."name" FROM "person" "p"""")
        assert(r.sql.mysql == "SELECT `p`.`name` FROM `person` `p`")
    }

    "production select (now inline)" in {
        val r = SqlStatic.staticSql(Sql.from[Person]("p").select(c => c.p.name))
        assert(r.sql.postgres == """SELECT "p"."name" FROM "person" "p"""")
        assert(r.sql.mysql == "SELECT `p`.`name` FROM `person` `p`")
    }

    "production where + select with comparison" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)
        )
        assert(r.sql.postgres == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" >= $1)""")
        assert(r.sql.mysql == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` >= ?)")
        assert(r.params.size == 1)
        val bv: kyo.BoundValue[?] = r.params.head
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "production compound predicate" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "")
        )
        assert(
            r.sql.postgres == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE (("p"."age" >= $1) AND ("p"."name" <> $2))"""
        )
        assert(
            r.sql.mysql == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE ((`p`.`age` >= ?) AND (`p`.`name` <> ?))"
        )
        assert(r.params.size == 2)
        val bv0: kyo.BoundValue[?] = r.params(0)
        val bv1: kyo.BoundValue[?] = r.params(1)
        assert((bv0.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv0, summon[SqlSchema[Int]]); assert(ok) }
        assert((bv1.value: Any) == "")
        { val ok = SqlSchema.boundSchemaEqRef(bv1, summon[SqlSchema[String]]); assert(ok) }
    }

    "pure case-class construction, no DSL builders / lambdas at all" in {
        import kyo.SqlAst.*
        val r = SqlStatic.staticSql(
            Select[Person, String](
                Sql.from[Person]("p"),
                Projection.Resolved(Chunk(Column["name", String]("p", "name", "name"))),
                false
            )
        )
        assert(r.sql.postgres == """SELECT "p"."name" FROM "person" "p"""")
        assert(r.sql.mysql == "SELECT `p`.`name` FROM `person` `p`")
    }

    "production tuple projection" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c => (c.p.name, c.p.age))
        )
        assert(r.sql.postgres == """SELECT "p"."name", "p"."age" FROM "person" "p"""")
        assert(r.sql.mysql == "SELECT `p`.`name`, `p`.`age` FROM `person` `p`")
    }

    "production where + orderBy + limit (SELECT *)" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p")
                .where(c => c.p.age >= 18)
                .orderBy(c => c.p.age.desc)
                .limit(10)
        )
        assert(
            r.sql.postgres == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" >= $1) ORDER BY "p"."age" DESC LIMIT 10"""
        )
        assert(
            r.sql.mysql == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (`p`.`age` >= ?) ORDER BY `p`.`age` DESC LIMIT 10"
        )
        assert(r.params.size == 1)
        val bv: kyo.BoundValue[?] = r.params.head
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "production join + select" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p")
                .innerJoin(Sql.from[Dept]("d"))
                .on(j => j.p.deptId == j.d.id)
                .select(j => (j.p.name, j.d.budget))
        )
        assert(r.sql.postgres == """SELECT "p"."name", "d"."budget" FROM "person" "p" INNER JOIN "dept" "d" ON ("p"."deptId" = "d"."id")""")
        assert(r.sql.mysql == "SELECT `p`.`name`, `d`.`budget` FROM `person` `p` INNER JOIN `dept` `d` ON (`p`.`deptId` = `d`.`id`)")
    }

    // --- Schema-carrying `Rendered` leaves ---

    "single Int literal carries summon[SqlSchema[Int]]" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age >= 18)
        )
        assert(r.params.size == 1)
        val bv: kyo.BoundValue[?] = r.params.head
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "single String literal carries summon[SqlSchema[String]]" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.name == "alice")
        )
        assert(r.params.size == 1)
        val bv: kyo.BoundValue[?] = r.params.head
        assert((bv.value: Any) == "alice")
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[String]]); assert(ok) }
    }

    "bind-free query renders an empty Chunk" in {
        val r = SqlStatic.staticSql(Sql.from[Person]("p"))
        assert(r.params.isEmpty)
        // type assertion: Rendered.params is Chunk[BoundValue[?]], not List[Any]
        val typed: Chunk[BoundValue[?]] = r.params
        assert(typed.isEmpty)
    }

    "two literals of different types preserve schema declaration order" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "")
        )
        assert(r.params.size == 2)
        val bv0: kyo.BoundValue[?] = r.params(0)
        val bv1: kyo.BoundValue[?] = r.params(1)
        assert((bv0.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv0, summon[SqlSchema[Int]]); assert(ok) }
        assert((bv1.value: Any) == "")
        { val ok = SqlSchema.boundSchemaEqRef(bv1, summon[SqlSchema[String]]); assert(ok) }
    }

    "runtime renderer schema matches static renderer schema for the same AST" in {
        import kyo.SqlAst.*
        val staticR = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age >= 18)
        )
        val runtimeR = Sql.from[Person]("p").where(c => c.p.age >= 18).render(SqlBackend.Postgres)
        assert(staticR.params.size == runtimeR.params.size)
        val sBv: kyo.BoundValue[?] = staticR.params.head
        val rBv: kyo.BoundValue[?] = runtimeR.params.head
        val schemasEq              = sBv.schema.asInstanceOf[AnyRef] eq rBv.schema.asInstanceOf[AnyRef]
        assert(schemasEq)
        assert((sBv.value: Any) == (rBv.value: Any))
    }

    "schema is the same instance returned by summon[SqlSchema[Int]]" in {
        val r                     = SqlStatic.staticSql(Sql.from[Person]("p").where(c => c.p.age >= 18))
        val bv: kyo.BoundValue[?] = r.params.head
        val expected              = summon[SqlSchema[Int]]
        { val ok = SqlSchema.boundSchemaEqRef(bv, expected); assert(ok) }
    }

    "Chunk round-trip, value equality and schema identity for each BoundValue" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "")
        )
        val params: Chunk[BoundValue[?]] = Chunk.from(r.params)
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

    "bare table + projection renders correct PG quoting" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c => c.p.name)
        )
        assert(r.sql.postgres == """SELECT "p"."name" FROM "person" "p"""")
    }

    "bare table + projection renders correct MySQL quoting" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c => c.p.name)
        )
        assert(r.sql.mysql == "SELECT `p`.`name` FROM `person` `p`")
    }

    "one literal parameter uses $1 on PG and ? on MySQL" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.name == "alice")
        )
        assert(r.sql.postgres.contains("$1"))
        assert(!r.sql.postgres.contains("?"))
        assert(r.sql.mysql.contains("?"))
        assert(!r.sql.mysql.contains("$1"))
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "alice")
    }

    "three literal parameters use $1,$2,$3 on PG and three ?s on MySQL" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c =>
                c.p.age >= 18 && c.p.name != "" && c.p.deptId == 7L
            )
        )
        // Strict positional check on PG: $1, $2, $3 in declaration order.
        assert(r.sql.postgres.contains("$1"))
        assert(r.sql.postgres.contains("$2"))
        assert(r.sql.postgres.contains("$3"))
        assert(!r.sql.postgres.contains("$4"))
        // MySQL: three `?` placeholders, zero `$N`.
        assert(r.sql.mysql.count(_ == '?') == 3)
        assert(!r.sql.mysql.contains("$"))
        assert(r.params.size == 3)
    }

    "identifier-quote escape matches runtime renderer (PG `\"\"`, MySQL ``)" in {
        // PG: a double-quote in an alias must be doubled. Use SqlRender.quoteIdent on a raw
        // string with an embedded `"` to pin parity; the static macro emits the same byte
        // sequence via its `q(...)` helper. Static path does not currently accept raw aliases
        // with embedded `"`, so this leaf tests the runtime helper that the macro mirrors.
        assert(kyo.internal.SqlRender.quoteIdent("a\"b", SqlBackend.Postgres) == "\"a\"\"b\"")
        assert(kyo.internal.SqlRender.quoteIdent("a`b", SqlBackend.Mysql) == "`a``b`")
    }

    "BackendSql.forBackend(Postgres) returns the postgres string" in {
        val bs = BackendSql("PG_SQL", "MY_SQL")
        assert(bs.forBackend(SqlBackend.Postgres) == "PG_SQL")
    }

    "BackendSql.forBackend(Mysql) returns the mysql string" in {
        val bs = BackendSql("PG_SQL", "MY_SQL")
        assert(bs.forBackend(SqlBackend.Mysql) == "MY_SQL")
    }

    "static PG SQL matches runtime SqlRender(Postgres) byte-for-byte" in {
        val staticR = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)
        )
        val runtimeR = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).render(SqlBackend.Postgres)
        assert(staticR.sql.postgres == runtimeR.sql)
    }

    "static MySQL SQL matches runtime SqlRender(Mysql) byte-for-byte" in {
        val staticR = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)
        )
        val runtimeR = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).render(SqlBackend.Mysql)
        assert(staticR.sql.mysql == runtimeR.sql)
    }

    "BackendSql.toString round-trips both strings without truncation" in {
        val bs = BackendSql("""SELECT "a"."b" FROM "t"""", "SELECT `a`.`b` FROM `t`")
        val s  = bs.toString
        assert(s.contains("""SELECT "a"."b" FROM "t""""))
        assert(s.contains("SELECT `a`.`b` FROM `t`"))
    }

    "staticSql signature has no `using SqlBackend`" in {
        // Compile-time check: invoking staticSql without any using-clause must compile. Any
        // future regression that re-adds `using SqlBackend` to the signature would fail this.
        val _ = SqlStatic.staticSql(Sql.from[Person]("p"))
        succeed
    }

    // --- where age == 30 + select name ---

    "where(age == 30).select(name) emits correct SQL and one Int BoundValue" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age == 30).select(c => c.p.name)
        )
        assert(r.sql.postgres == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" = $1)""")
        assert(r.sql.mysql == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` = ?)")
        assert(r.params.size == 1)
        val bv: kyo.BoundValue[?] = r.params.head
        assert((bv.value: Any) == 30)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    // --- where name == "Alice" + select id ---

    "where(name == \"Alice\").select(id) emits correct SQL and one String BoundValue" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.name == "Alice").select(c => c.p.id)
        )
        assert(r.sql.postgres == """SELECT "p"."id" FROM "person" "p" WHERE ("p"."name" = $1)""")
        assert(r.sql.mysql == "SELECT `p`.`id` FROM `person` `p` WHERE (`p`.`name` = ?)")
        assert(r.params.size == 1)
        val bv: kyo.BoundValue[?] = r.params.head
        assert((bv.value: Any) == "Alice")
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[String]]); assert(ok) }
    }

    // --- where age.between(18, 65) + select name ---

    "where(age.between(18, 65)).select(name) emits BETWEEN SQL with two Int params" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age.between(18, 65)).select(c => c.p.name)
        )
        assert(r.sql.postgres == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" BETWEEN $1 AND $2)""")
        assert(r.sql.mysql == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` BETWEEN ? AND ?)")
        assert(r.params.size == 2)
        val bv0: kyo.BoundValue[?] = r.params(0)
        val bv1: kyo.BoundValue[?] = r.params(1)
        assert((bv0.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv0, summon[SqlSchema[Int]]); assert(ok) }
        assert((bv1.value: Any) == 65)
        { val ok = SqlSchema.boundSchemaEqRef(bv1, summon[SqlSchema[Int]]); assert(ok) }
    }

    // --- FromExpr.derived[Literal[Int]] lifts Literal(42, ...) ---

    "FromExpr.derived[Literal[Int]] lifts Literal(42, SqlSchema[Int]) to Some with value 42" in {
        // SqlLiftHarness uses applyMatchedImpl which calls buildDirect[Term[Int]] at macro expansion time.
        // buildDirect takes the buildProduct path on the Literal child of Term; each field is lifted:
        //   - value: Int  → summonPrimitiveFromExpr[Int]
        //   - schema: SqlSchema[Int] → buildSqlSchema[SqlSchema[Int]] → Expr.summon[FromExpr[SqlSchema[Int]]]
        // Driven through the public `Sql.literal[Int](42)` entry point so the LHS exercises the same
        // path a user would invoke (Sql.literal returns the Literal Term).
        assert(SqlLiftHarness.matched[SqlAst.Term[Int]](Sql.literal[Int](42)))
    }

    // --- FromExpr.derived[Literal[String]] lifts "hello" ---

    "FromExpr.derived[Literal[String]] lifts Literal(\"hello\", SqlSchema[String]) to Some" in {
        assert(SqlLiftHarness.matched[SqlAst.Term[String]](Sql.literal[String]("hello")))
    }

    // --- fromExprSqlSchema[Int] finds given for Int ---

    "fromExprSqlSchema[Int].unapply of a summoned SqlSchema[Int] returns Some (non-None)" in {
        // SqlLiftHarness.matched[SqlSchema[Int]] uses buildDirect → buildSqlSchema → Expr.summon[FromExpr[SqlSchema[Int]]]
        // which finds fromExprSqlSchema → Expr.summon[SqlSchema[Int]].flatMap(_.value) → Some.
        assert(SqlLiftHarness.matched[SqlSchema[Int]](summon[SqlSchema[Int]]))
    }

    // --- fromExprSqlSchema[LocalDate] finds given for LocalDate ---

    "fromExprSqlSchema[LocalDate].unapply returns Some, named top-level given is found" in {
        assert(SqlLiftHarness.matched[SqlSchema[java.time.LocalDate]](summon[SqlSchema[java.time.LocalDate]]))
    }

    // --- RecordFromExpr infrastructure ---

    "bare Table via Sql.from emits SELECT * SQL (Record[F] columns field lifts)" in {
        // Verifies that FromExpr.derived[Table[Person, F]] works with RecordFromExpr.fromExprRecord in scope.
        // The static macro renders the bare Table correctly, regression guard.
        val r = SqlStatic.staticSql(Sql.from[Person]("p"))
        assert(r.sql.postgres == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p"""")
        assert(r.sql.mysql == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p`")
        assert(r.params.isEmpty)
    }

    "CrossJoin.columns (merged Record[F1 & F2]) renders CROSS JOIN SQL" in {
        // Verifies that CrossJoin.columns = left.columns & right.columns lifts via fromExprRecord.
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").crossJoin(Sql.from[Order]("o")).select(c => (c.p.id, c.o.userId))
        )
        assert(r.sql.postgres == """SELECT "p"."id", "o"."userId" FROM "person" "p" CROSS JOIN "order" "o"""")
        assert(r.sql.mysql == "SELECT `p`.`id`, `o`.`userId` FROM `person` `p` CROSS JOIN `order` `o`")
    }

    "Join.columns (merged Record via on) renders INNER JOIN SQL" in {
        // Verifies that Join.columns = left.columns & right.columns lifts via fromExprRecord.
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p")
                .innerJoin(Sql.from[Order]("o"))
                .on(c => c.p.id == c.o.userId)
                .select(c => c.p.name)
        )
        assert(r.sql.postgres == """SELECT "p"."name" FROM "person" "p" INNER JOIN "order" "o" ON ("p"."id" = "o"."userId")""")
        assert(r.sql.mysql == "SELECT `p`.`name` FROM `person` `p` INNER JOIN `order` `o` ON (`p`.`id` = `o`.`userId`)")
    }

    "GroupBy (view from buildGroupedView) renders GROUP BY SQL via static macro" in {
        // Verifies that GroupBy nodes render correctly in the static macro. The macro
        // calls `SqlRender.render` directly, so the static SQL is byte-identical to the runtime
        // renderer. A `Where`-sourced GroupBy is subquery-wrapped by `SqlRender` (the canonical
        // renderer). The expected strings below are the canonical `SqlRender` output, asserted
        // byte-equal to the runtime path immediately afterwards.
        val r = SqlStatic.staticSql(Sql.from[Person]("p").where(c => c.p.age > 18).groupBy(c => c.p.age))
        assert(
            r.sql.postgres == """SELECT * FROM (SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" > $1)) AS sub GROUP BY "p"."age""""
        )
        assert(
            r.sql.mysql == "SELECT * FROM (SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (`p`.`age` > ?)) AS sub GROUP BY `p`.`age`"
        )
        assert(r.params.size == 1)
        val bv: kyo.BoundValue[?] = r.params.head
        assert((bv.value: Any) == 18)
        // Static-path lockstep: the static macro and the runtime renderer emit byte-identical SQL.
        val rt = Sql.from[Person]("p").where(c => c.p.age > 18).groupBy(c => c.p.age).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

    // --- FromExpr.derived[Table].unapply runtime verification ---

    "FromExpr-lifted Table reconstructs to Some" in {
        // Direct end-to-end exercise of the derived `FromExpr[Table[Person, ?]].unapply` against a
        // real inline `Sql.from[Person]("p")`.
        assert(SqlLiftHarness.matched[SqlAst.Table[Person, ?]](Sql.from[Person]("p")))
    }

    "FromExpr-lifted Table reconstructs columns Record with the expected field names" in {
        // The reconstructed `Table`'s `columns` Record (lifted via `RecordFromExpr.fromExprRecord`)
        // is `buildColumns`-shaped: an alias-keyed wrapper (`"p"`) around the inner column record
        // carrying exactly Person's fields. This proves the `buildColumns` TASTy walk + per-column
        // closure beta-reduction reconstruct a structurally-correct Record, not a non-None placeholder.
        val names = SqlLiftHarness.recordFieldNames[SqlAst.Table[Person, ?]](Sql.from[Person]("p"))
        assert(names == "p;age,deptId,id,name")
    }

    // --- GroupBy aggregate-projection static render ---

    "static macro renders GroupBy with aggregate projection" in {
        // The `q.value`-based macro lifts `view.age.count` (an
        // `Aggregate.Count` whose argument is the `GroupTerm.inline$underlying` accessor on a
        // projected `GroupedColumn`) via `ColumnFromExpr`. A `SELECT` over a `Where`-sourced GroupBy
        // is rendered flat by `SqlRender` (the canonical renderer the macro delegates to), the
        // `WHERE` is reused inline, not subquery-wrapped.
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c => c.p.age > 18)
                .groupBy(c => c.p.age).select(view => view.age.count)
        )
        assert(r.sql.postgres ==
            """SELECT COUNT("p"."age") FROM "person" "p" WHERE ("p"."age" > $1) GROUP BY "p"."age"""")
        assert(r.sql.mysql ==
            "SELECT COUNT(`p`.`age`) FROM `person` `p` WHERE (`p`.`age` > ?) GROUP BY `p`.`age`")
        assert(r.params.size == 1)
        val rt = Sql.from[Person]("p").where(c => c.p.age > 18)
            .groupBy(c => c.p.age).select(view => view.age.count)
            .render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

    // --- Static bind-value round-trip leaves ---

    // Full regression: every static query renders byte-identical to SqlRender.render.
    "regression, static SQL is byte-identical to SqlRender.render for the same AST" in {
        val r  = SqlStatic.staticSql(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        val rp = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).render(SqlBackend.Postgres)
        val rm = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).render(SqlBackend.Mysql)
        assert(r.sql.postgres == rp.sql)
        assert(r.sql.mysql == rm.sql)
    }

    // Int bind param.
    "Int bind param produces params(0).value == 42 + SqlSchema[Int]" in {
        val r                     = SqlStatic.staticSql(Sql.from[Person]("p").where(c => c.p.age == 42))
        val bv: kyo.BoundValue[?] = r.params.head
        assert(r.params.size == 1)
        assert((bv.value: Any) == 42)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    // String bind param.
    "String bind param produces params(0).value == \"Alice\" + SqlSchema[String]" in {
        val r                     = SqlStatic.staticSql(Sql.from[Person]("p").where(c => c.p.name == "Alice"))
        val bv: kyo.BoundValue[?] = r.params.head
        assert(r.params.size == 1)
        assert((bv.value: Any) == "Alice")
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[String]]); assert(ok) }
    }

    // Long bind param.
    "Long bind param produces params(0).value == 99L + SqlSchema[Long]" in {
        val r                     = SqlStatic.staticSql(Sql.from[Person]("p").where(c => c.p.id == 99L))
        val bv: kyo.BoundValue[?] = r.params.head
        assert(r.params.size == 1)
        assert((bv.value: Any) == 99L)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Long]]); assert(ok) }
    }

    // SQL bind-value-type round-trips through the custom `ToExpr` instances.
    //
    // Each leaf is the plan's natural form: `SqlStatic.staticSql` of a real `Sql.from[<case class>]`
    // query whose `where` predicate compares a `java.time` / opaque-typed column against a literal
    // value. The literal becomes a `Literal[T]` bind in the AST; the static macro lifts it via
    // `FromExpr.derived` and re-lifts the bind value through the corresponding `toExpr*` instance
    // in `SqlStaticMacro.liftOne`. Asserting `params(0).value` round-trips the whole `toExpr*` chain.

    // LocalDate bind param (toExprLocalDate round-trip).
    "LocalDate bind param round-trips through toExprLocalDate" in {
        val r = SqlStatic.staticSql(
            Sql.from[Appointment]("a").where(c => c.a.day == java.time.LocalDate.of(2024, 1, 15))
        )
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == java.time.LocalDate.of(2024, 1, 15))
    }

    // LocalDateTime bind param (toExprLocalDateTime round-trip).
    //
    // The bind is supplied via `Sql.literal[LocalDateTime](...)` projected from a `Sql.from[Person]`
    // query, NOT a `LocalDateTime`-typed *column* on the source case class. A case class with a
    // `java.time.LocalDateTime` field cannot be passed to `Sql.from[T]`: building its columns calls
    // `summonInline[Tag[LocalDateTime]]`, and `kyo.Tag` derivation for `LocalDateTime` crashes the
    // dotty macro with `java.lang.AssertionError: TypeBounds(Nothing, FromJavaObject)` in
    // `TagMacro.deriveImpl` (reproduced: `case class Reminder(id: Long, at: LocalDateTime) derives
    // Schema` + `Sql.from[Reminder]` → that AssertionError, with no `staticSql` involved). This is a
    // genuine pre-existing `kyo.Tag` bug specific to `LocalDateTime`, `LocalDate`, `LocalTime`,
    // `Span[Byte]`, and `kyo.Instant` columns (leaves 5, 7, 8, 9) all compile fine. `LocalDateTime`'s
    // parent interface `ChronoLocalDateTime<LocalDate>` carries a type argument the Tag macro's
    // parent walk mishandles. `Sql.literal` only needs `SqlSchema[LocalDateTime]` (which exists),
    // not `Tag[LocalDateTime]`, so it sidesteps the Tag bug while still exercising the natural DSL
    // path: a real `Sql.from` query carrying a `LocalDateTime` `Literal` bind round-tripped through
    // `FromExpr.derived` + `toExprLocalDateTime`.
    "LocalDateTime bind param round-trips through toExprLocalDateTime" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(_ => Sql.literal(java.time.LocalDateTime.of(2024, 1, 15, 9, 30, 45, 123000000)))
        )
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == java.time.LocalDateTime.of(2024, 1, 15, 9, 30, 45, 123000000))
    }

    // LocalTime bind param (toExprLocalTime round-trip).
    "LocalTime bind param round-trips through toExprLocalTime" in {
        val r = SqlStatic.staticSql(
            Sql.from[Alarm]("a").where(c => c.a.time == java.time.LocalTime.of(13, 45, 30, 500000000))
        )
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == java.time.LocalTime.of(13, 45, 30, 500000000))
    }

    // Span[Byte] bind param (toExprSpanByte round-trip).
    "Span[Byte] bind param round-trips through toExprSpanByte" in {
        val r = SqlStatic.staticSql(
            Sql.from[Blob]("b").where(c => c.b.data == kyo.Span.from(Array[Byte](1, 2, 3, 4)))
        )
        assert(r.params.size == 1)
        // `Span[Byte]` is an opaque alias of `Array[Byte]`; the runtime value is the backing array.
        val got: Any = r.params(0).value
        got match
            case arr: Array[Byte] => assert(arr.sameElements(Array[Byte](1, 2, 3, 4)))
            case other            => fail(s"expected Array[Byte]-backed Span, got $other")
    }

    // kyo.Instant bind param (toExprKyoInstant round-trip).
    "kyo.Instant bind param round-trips through toExprKyoInstant" in {
        val r = SqlStatic.staticSql(
            Sql.from[Stamped]("s").where(c =>
                c.s.moment == kyo.Instant.fromJava(java.time.Instant.ofEpochMilli(1705312245123L))
            )
        )
        assert(r.params.size == 1)
        // `kyo.Instant` is an opaque alias of `java.time.Instant`; the runtime value is the Java instant.
        val got: Any = r.params(0).value
        got match
            case i: java.time.Instant => assert(i.toEpochMilli == 1705312245123L)
            case other                => fail(s"expected java.time.Instant-backed kyo.Instant, got $other")
    }

    // Multi-bind query (Int + String + Long) in declaration order.
    "multi-bind query produces params.size == 3 in declaration order" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").where(c =>
                c.p.age >= 18 && c.p.name != "" && c.p.deptId == 7L
            )
        )
        assert(r.params.size == 3)
        assert((r.params(0).value: Any) == 18)
        assert((r.params(1).value: Any) == "")
        assert((r.params(2).value: Any) == 7L)
    }

    // INSERT static rendering.
    //
    // `Insert.Values[T, F]` stores rows as decomposed pure data,
    // `Chunk[Chunk[BoundValue[?]]]` (outer = rows, inner = one `BoundValue` per column in declaration
    // order). The `Sql.insert[Person].values(Person(...))` builder eagerly decomposes each row `T` into
    // its per-field `BoundValue`s via the `SqlMacros.rowValues[T]` macro (field value + summoned
    // `SqlSchema`). The AST node now carries only `Chunk` / `BoundValue` / `SqlSchema`, all pure data
    // that `FromExpr.derived` lifts with zero reflection, so `staticSql` of an INSERT works without ever
    // reflectively reconstructing the (co-compiled) `Person` row class.
    //
    // The renderer (`SqlRender.insertValues` → `renderDecomposedRows`) emits each cell's `BoundValue.value`
    // as an inline literal, byte-identical to the prior `productIterator` walk, so the static SQL matches
    // the runtime renderer exactly. Bind params stay empty for a literal-VALUES INSERT (same as before).
    "staticSql of an INSERT statement renders correct INSERT SQL" in {
        val r = SqlStatic.staticSql(Sql.insert[Person].values(Person(0L, "Alice", 30, 1L)))
        assert(r.sql.postgres == """INSERT INTO "person" ("id", "name", "age", "deptId") VALUES (0, 'Alice', 30, 1) RETURNING "id"""")
        assert(r.sql.mysql == "INSERT INTO `person` (`id`, `name`, `age`, `deptId`) VALUES (0, 'Alice', 30, 1)")
        assert(r.params.isEmpty)
        val rt = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L)).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

    // Cast node renders CAST(...) byte-identical to SqlRender.render.
    "Cast node renders CAST(\"p\".\"id\" AS TEXT) byte-identical to SqlRender" in {
        val r  = SqlStatic.staticSql(Sql.from[Person]("p").select(c => c.p.id.cast[String]))
        val rt = Sql.from[Person]("p").select(c => c.p.id.cast[String]).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
        assert(r.sql.postgres.contains("""CAST("p"."id" AS TEXT)"""))
    }

    // --- SqlStaticMacro moved to kyo.internal ---

    "staticSql still expands correctly after SqlStaticMacro moved to kyo.internal" in {
        // Regression guard: SqlStaticMacro is now in kyo.internal; the macro splice in
        // SqlStatic.staticSql references it as kyo.internal.SqlStaticMacro.impl.
        // A bare-table query verifies the splice resolves and produces correct SQL.
        val r = SqlStatic.staticSql(Sql.from[Person]("p"))
        assert(r.sql.postgres == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p"""")
        assert(r.sql.mysql == "SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p`")
        assert(r.params.isEmpty)
    }

end SqlStaticTest
