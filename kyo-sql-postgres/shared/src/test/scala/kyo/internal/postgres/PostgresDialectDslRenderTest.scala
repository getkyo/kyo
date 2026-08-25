package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[PostgresDialect]] renders across the DSL surface, one statement shape at a time, asserting the rendered text and the
  * bind count.
  *
  * The breadth is the point: this is where a change to any clause's rendering shows up. The type-level properties of the same DSL, which
  * clauses compose and which combinations the compiler rejects, are asserted in core, where the DSL lives.
  */
class PostgresDialectDslRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)
    case class Department(id: Long, name: String, budget: BigDecimal)
    case class Order(id: Long, userId: Long, total: BigDecimal, createdAt: Instant)
    case class NameAge(name: String, age: Int)
    case class Customer(id: Long, name: String, email: Maybe[String], suspended: Boolean)

    val people      = Sql.from[Person]("p")
    val departments = Sql.from[Department]("d")
    val orders      = Sql.from[Order]("o")
    val customers   = Sql.from[Customer]("c")

    def sqlOf(q: Query[?]): String      = q.render(PostgresDialect).onlySql.get
    def sqlOf(s: Executable[?]): String = s.render(PostgresDialect).onlySql.get
    def paramsOf(q: Query[?])           = q.render(PostgresDialect).params.size
    def paramsOf(s: Executable[?])      = s.render(PostgresDialect).params.size

    // --- SELECT ---

    "whole-table select" in {
        assert(sqlOf(people) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p"""")
        assert(paramsOf(people) == 0)
    }

    "tuple select projects two columns" in {
        val q = people.select(c => (c.p.name, c.p.age))
        assert(sqlOf(q) == """SELECT "p"."name", "p"."age" FROM "person" "p"""")
    }

    "single-column select" in {
        val q = people.select(c => c.p.name)
        assert(sqlOf(q) == """SELECT "p"."name" FROM "person" "p"""")
    }

    "select.to[CaseClass] preserves the projection" in {
        val q = people.select(c => (c.p.name, c.p.age)).to[NameAge]
        assert(sqlOf(q) == """SELECT "p"."name", "p"."age" FROM "person" "p"""")
    }

    // --- WHERE ---

    "where with single comparison emits one bind parameter" in {
        val q = people.where(c => c.p.age >= 18)
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" >= $1)""")
        assert(paramsOf(q) == 1)
    }

    "where with compound AND / != emits two binds" in {
        val q = people.where(c => c.p.age >= 18 && c.p.name != "")
        assert(sqlOf(
            q
        ) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE (("p"."age" >= $1) AND ("p"."name" <> $2))""")
        assert(paramsOf(q) == 2)
    }

    "where with LIKE patterns" in {
        val q = people.where(c => c.p.name.like("A%") || c.p.name.like("B%"))
        assert(
            sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE (("p"."name" LIKE $1) OR ("p"."name" LIKE $2))"""
        )
    }

    "where IN with subquery" in {
        val q = people.where(c => c.p.id.in(orders.select(o => o.o.userId)))
        assert(
            sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."id" IN (SELECT "o"."userId" FROM "order" "o"))"""
        )
    }

    "where NOT IN with raw values" in {
        val q = people.where(c => c.p.id.notIn(1L, 2L, 3L))
        assert(
            sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."id" NOT IN ($1, $2, $3))"""
        )
        assert(paramsOf(q) == 3)
    }

    // --- EXISTS ---

    "where exists with correlated subquery" in {
        val q = people.where(c => orders.where(o => o.o.userId == c.p.id).exists)
        assert(
            sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE EXISTS (SELECT "o"."id", "o"."userId", "o"."total", "o"."createdAt" FROM "order" "o" WHERE ("o"."userId" = "p"."id"))"""
        )
    }

    "where notExists" in {
        val q = people.where(c => orders.where(o => o.o.userId == c.p.id).notExists)
        assert(
            sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE NOT EXISTS (SELECT "o"."id", "o"."userId", "o"."total", "o"."createdAt" FROM "order" "o" WHERE ("o"."userId" = "p"."id"))"""
        )
    }

    // --- JOIN ---

    "inner join with on predicate and tuple select" in {
        val q = people.innerJoin(orders).on(j => j.p.id == j.o.userId).select(j => (j.p.name, j.o.total))
        assert(
            sqlOf(q) == """SELECT "p"."name", "o"."total" FROM "person" "p" INNER JOIN "order" "o" ON ("p"."id" = "o"."userId")"""
        )
    }

    // A `leftJoin` NULL-extends the right side, so `o.total` is `Maybe[BigDecimal]` in the joined view and a comparison against a raw
    // literal no longer type-checks. `Maybe(...)` is the same spelling an already-nullable case-class field requires, as the `coalesce`
    // leaf below shows.
    "left join + where + select" in {
        val q = people.leftJoin(orders).on(j => j.p.id == j.o.userId)
            .where(j => j.o.total > Maybe(BigDecimal(100)))
            .select(j => (j.p.name, j.o.total))
        assert(
            sqlOf(q) == """SELECT "p"."name", "o"."total" FROM "person" "p" LEFT JOIN "order" "o" ON ("p"."id" = "o"."userId") WHERE ("o"."total" > $1)"""
        )
    }

    "right / full-outer joins" in {
        val r = people.rightJoin(orders).on(j => j.p.id == j.o.userId)
        val f = people.fullOuterJoin(orders).on(j => j.p.id == j.o.userId)
        assert(sqlOf(r) == """SELECT * FROM "person" "p" RIGHT JOIN "order" "o" ON ("p"."id" = "o"."userId")""")
        assert(sqlOf(f) == """SELECT * FROM "person" "p" FULL OUTER JOIN "order" "o" ON ("p"."id" = "o"."userId")""")
    }

    "cross join + where" in {
        val q = people.crossJoin(departments).where(j => j.p.deptId == j.d.id)
        assert(
            sqlOf(q) == """SELECT * FROM "person" "p" CROSS JOIN "department" "d" WHERE ("p"."deptId" = "d"."id")"""
        )
    }

    // --- GROUP BY ---

    "group by single column with having + select" in {
        val q = people.groupBy(c => c.p.deptId)
            .having(view => view.deptId.count > 5)
            .select(view => (view.deptId, view.age.sum))
        assert(
            sqlOf(q) == """SELECT "p"."deptId", SUM("p"."age") FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
    }

    // --- Eager groupBy view materialization ---

    "groupBy select renders byte-identical SQL" in {
        val q = people.groupBy(_.p.deptId).select(view => view.deptId)
        assert(sqlOf(q) == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId"""")
    }

    "groupBy having select renders byte-identical HAVING" in {
        val q = people.groupBy(_.p.deptId).having(view => view.deptId.count > 5).select(view => view.deptId)
        assert(
            sqlOf(q) == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
    }

    "groupBy select orderBy renders byte-identical ORDER BY" in {
        val q = people.groupBy(_.p.deptId).select(view => view.deptId).orderBy(view => view.deptId.desc)
        assert(
            sqlOf(q) == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId" ORDER BY "p"."deptId" DESC"""
        )
    }

    "groupBy select aggregate SUM renders byte-identical SQL" in {
        val q = people.groupBy(_.p.deptId).select(view => view.age.sum)
        assert(
            sqlOf(q) == """SELECT SUM("p"."age") FROM "person" "p" GROUP BY "p"."deptId""""
        )
    }

    "end-to-end groupBy + having + tuple select renders correctly" in {
        val q = people.groupBy(_.p.deptId)
            .having(view => view.deptId.count > 5)
            .select(view => (view.deptId, view.age.avg))
        assert(
            sqlOf(q) == """SELECT "p"."deptId", AVG("p"."age") FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
    }

    "grouped-view sumDistinct / avgDistinct emit DISTINCT inside the aggregate" in {
        val summed = people.groupBy(_.p.deptId).select(view => (view.deptId, view.age.sumDistinct))
        assert(
            sqlOf(summed) == """SELECT "p"."deptId", SUM(DISTINCT "p"."age") FROM "person" "p" GROUP BY "p"."deptId""""
        )
        val averaged = people.groupBy(_.p.deptId).select(view => (view.deptId, view.age.avgDistinct))
        assert(
            sqlOf(averaged) == """SELECT "p"."deptId", AVG(DISTINCT "p"."age") FROM "person" "p" GROUP BY "p"."deptId""""
        )
    }

    // `limit(n, offset)` after a grouping goes through `Select.limit`, a different declaration from the `From.limit`
    // the LIMIT section below asserts, and it is the one a grouped query reaches: a `GroupBy` is not a `Query`, so
    // the projection is what it has to pass through before a limit can follow. The `OFFSET` half is emitted only
    // when the offset is above zero (`Idiom.limit`), so an offset of 0 renders no `OFFSET` at all and a
    // leaf using one would not exercise the clause.
    "groupBy select limit with offset appends LIMIT and OFFSET after the GROUP BY" in {
        val q = people.groupBy(_.p.deptId).select(view => view.deptId).limit(5, offset = 10)
        assert(
            sqlOf(q) == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId" LIMIT 5 OFFSET 10"""
        )
        val noOffset = people.groupBy(_.p.deptId).select(view => view.deptId).limit(5)
        assert(
            sqlOf(noOffset) == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId" LIMIT 5"""
        )
    }

    "all existing groupBy/having SQL strings remain byte-identical (regression)" in {
        // Asserts the rendered strings for the groupBy/having scenarios.
        // If this fails, byte-for-byte compatibility was broken.
        val q1 = people.groupBy(c => c.p.deptId)
            .having(view => view.deptId.count > 5)
            .select(view => (view.deptId, view.age.sum))
        assert(
            sqlOf(q1) == """SELECT "p"."deptId", SUM("p"."age") FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
    }

    // --- ORDER BY ---

    "order by single desc" in {
        val q = people.orderBy(c => c.p.age.desc)
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" ORDER BY "p"."age" DESC""")
    }

    "order by tuple-lambda (asc, desc)" in {
        val q = people.orderBy(c => (c.p.deptId.asc, c.p.age.desc))
        assert(sqlOf(
            q
        ) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" ORDER BY "p"."deptId" ASC, "p"."age" DESC""")
    }

    "order by with nulls last / first" in {
        assert(sqlOf(people.orderBy(c =>
            c.p.name.ascAbsentLast
        )) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" ORDER BY "p"."name" ASC NULLS LAST""")
        assert(sqlOf(people.orderBy(c =>
            c.p.name.descAbsentFirst
        )) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" ORDER BY "p"."name" DESC NULLS FIRST""")
    }

    // --- LIMIT / DISTINCT ---

    "limit and limit-with-offset" in {
        assert(sqlOf(people.limit(10)) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" LIMIT 10""")
        assert(sqlOf(people.limit(
            10,
            offset = 20
        )) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" LIMIT 10 OFFSET 20""")
    }

    "select distinct" in {
        val q = people.select(c => c.p.name).distinct
        assert(sqlOf(q) == """SELECT DISTINCT "p"."name" FROM "person" "p"""")
    }

    // --- SET OPS ---

    "union / unionAll / intersect / except" in {
        val left  = people.select(c => c.p.name)
        val right = departments.select(c => c.d.name)
        assert(sqlOf(left `union` right) == """SELECT "p"."name" FROM "person" "p" UNION SELECT "d"."name" FROM "department" "d"""")
        assert(sqlOf(left `unionAll` right) == """SELECT "p"."name" FROM "person" "p" UNION ALL SELECT "d"."name" FROM "department" "d"""")
        assert(sqlOf(left `intersect` right) == """SELECT "p"."name" FROM "person" "p" INTERSECT SELECT "d"."name" FROM "department" "d"""")
        assert(sqlOf(
            left `intersectAll` right
        ) == """SELECT "p"."name" FROM "person" "p" INTERSECT ALL SELECT "d"."name" FROM "department" "d"""")
        assert(sqlOf(left `except` right) == """SELECT "p"."name" FROM "person" "p" EXCEPT SELECT "d"."name" FROM "department" "d"""")
        assert(
            sqlOf(left `exceptAll` right) == """SELECT "p"."name" FROM "person" "p" EXCEPT ALL SELECT "d"."name" FROM "department" "d""""
        )
    }

    // --- CTE ---

    "with single CTE" in {
        val cte = Sql.commonTable("active_people", people.where(c => c.p.age >= 18))
        val q   = Sql.commonTables(cte)(people)
        assert(
            sqlOf(q) == """WITH "active_people" AS (SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" >= $1)) SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p""""
        )
    }

    "with recursive CTE" in {
        val cte = Sql.commonTable("active_people", people)
        val q   = Sql.commonTablesRecursive(cte)(people)
        assert(
            sqlOf(q) == """WITH RECURSIVE "active_people" AS (SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p") SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p""""
        )
    }

    // --- Aggregates ---

    "From-level count / sum / avg / min / max" in {
        assert(sqlOf(people.count) == """SELECT COUNT(*) FROM "person" "p"""")
        assert(sqlOf(people.count(_.p.deptId)) == """SELECT COUNT("p"."deptId") FROM "person" "p"""")
        assert(sqlOf(people.countDistinct(_.p.deptId)) == """SELECT COUNT(DISTINCT "p"."deptId") FROM "person" "p"""")
        assert(sqlOf(people.sum(_.p.age)) == """SELECT SUM("p"."age") FROM "person" "p"""")
        assert(sqlOf(people.avg(_.p.age)) == """SELECT AVG("p"."age") FROM "person" "p"""")
        assert(sqlOf(people.min(_.p.age)) == """SELECT MIN("p"."age") FROM "person" "p"""")
        assert(sqlOf(people.max(_.p.age)) == """SELECT MAX("p"."age") FROM "person" "p"""")
    }

    "Where-level count" in {
        val q = people.where(c => c.p.age >= 18).count
        assert(sqlOf(q) == """SELECT COUNT(*) FROM "person" "p" WHERE ("p"."age" >= $1)""")
    }

    // --- DISTINCT aggregates ---
    //
    // `countDistinct` is asserted above; these are its two siblings `sumDistinct` and `avgDistinct`. The
    // `distinct = true` flag is a field of the same `Aggregate.Sum` / `Aggregate.Avg` nodes their plain forms build,
    // so a rendering that dropped it would answer the plain aggregate's number for a query that asked for the
    // distinct one, which is a wrong result and not an error. `From`, `Where` and the post-groupBy view declare the
    // same two method names separately, so each is asserted rather than assumed; the post-groupBy pair sits with the
    // other grouping leaves above.

    "From-level sumDistinct / avgDistinct emit DISTINCT inside the aggregate" in {
        assert(sqlOf(people.sumDistinct(_.p.age)) == """SELECT SUM(DISTINCT "p"."age") FROM "person" "p"""")
        assert(sqlOf(people.avgDistinct(_.p.age)) == """SELECT AVG(DISTINCT "p"."age") FROM "person" "p"""")
        assert(paramsOf(people.sumDistinct(_.p.age)) == 0)
    }

    "Where-level sumDistinct keeps both the predicate bind and the DISTINCT" in {
        val q = people.where(c => c.p.age >= 18).sumDistinct(_.p.age)
        assert(sqlOf(q) == """SELECT SUM(DISTINCT "p"."age") FROM "person" "p" WHERE ("p"."age" >= $1)""")
        assert(paramsOf(q) == 1)
    }

    // --- Window functions ---

    "windowed SUM with partitionBy + orderBy" in {
        val q = people.select(c =>
            (c.p.name, c.p.age.sum.over(Sql.windowSpec.partitionBy(c.p.deptId).orderBy(c.p.age.asc)))
        )
        assert(
            sqlOf(q) == """SELECT "p"."name", SUM("p"."age") OVER (PARTITION BY "p"."deptId" ORDER BY "p"."age" ASC) FROM "person" "p""""
        )
    }

    "standalone rowNumber + rank window functions" in {
        val rn = people.select(c => (c.p.name, Sql.windowSpec.partitionBy(c.p.deptId).orderBy(c.p.age.desc).rowNumber))
        assert(
            sqlOf(rn) == """SELECT "p"."name", ROW_NUMBER() OVER (PARTITION BY "p"."deptId" ORDER BY "p"."age" DESC) FROM "person" "p""""
        )
        val rk = people.select(c => (c.p.name, Sql.windowSpec.orderBy(c.p.age.desc).rank))
        assert(sqlOf(rk) == """SELECT "p"."name", RANK() OVER (ORDER BY "p"."age" DESC) FROM "person" "p"""")
    }

    "window LAG with default" in {
        val q = people.select(c => (c.p.name, c.p.age.lag(1, default = 0).over(Sql.windowSpec.orderBy(c.p.id.asc))))
        assert(
            sqlOf(q) == """SELECT "p"."name", LAG("p"."age", $1, $2) OVER (ORDER BY "p"."id" ASC) FROM "person" "p""""
        )
        assert(paramsOf(q) == 2)
    }

    "window frame ROWS BETWEEN preceding(2) AND currentRow" in {
        val q = people.select(c =>
            c.p.age.sum.over(
                Sql.windowSpec.partitionBy(c.p.deptId).orderBy(c.p.id.asc).frameRows(FrameBound.preceding(2), FrameBound.currentRow)
            )
        )
        assert(
            sqlOf(q) == """SELECT SUM("p"."age") OVER (PARTITION BY "p"."deptId" ORDER BY "p"."id" ASC ROWS BETWEEN $1 PRECEDING AND CURRENT ROW) FROM "person" "p""""
        )
    }

    // The window-spec builder lives in the `WindowSpec` companion (`Sql.WindowSpec.Builder`), so a
    // `Sql.windowSpec.partitionBy(...).orderBy(...).rank` chain reaches the renderer through it. This leaf pins the
    // byte-exact PG SQL that chain produces.
    "Sql.windowSpec builder renders byte-exact PG SQL" in {
        val q = people.select(c =>
            (c.p.name, Sql.windowSpec.partitionBy(c.p.deptId).orderBy(c.p.age.asc).rank)
        )
        assert(
            sqlOf(q) == """SELECT "p"."name", RANK() OVER (PARTITION BY "p"."deptId" ORDER BY "p"."age" ASC) FROM "person" "p""""
        )
    }

    // --- Expression DSL ---

    "raw and column comparisons" in {
        assert(sqlOf(people.where(c =>
            c.p.age == 30
        )) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" = $1)""")
        assert(sqlOf(people.where(c =>
            c.p.id == c.p.deptId
        )) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."id" = "p"."deptId")""")
    }

    "between (raw)" in {
        val q = people.where(c => c.p.age.between(18, 65))
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" BETWEEN $1 AND $2)""")
    }

    // `/` is the one arithmetic operator PostgreSQL cannot render as the bare symbol: `int4 / int4` truncates and returns an
    // int4, while the DSL types the quotient BigDecimal so it means the same thing on MySQL. Casting the dividend to NUMERIC
    // is what makes PostgreSQL resolve the expression to exact division. The other three operators are unchanged.
    "arithmetic select with mixed raw values, and an integral division cast to NUMERIC" in {
        val q = people.select(c => (c.p.name, c.p.age + 10, c.p.age * 2, c.p.age / 4))
        assert(
            sqlOf(
                q
            ) == """SELECT "p"."name", ("p"."age" + $1), ("p"."age" * $2), (CAST("p"."age" AS NUMERIC) / $3) FROM "person" "p""""
        )
    }

    "an integral division between two columns casts the dividend on PG" in {
        val q = people.select(c => c.p.age / c.p.age)
        assert(sqlOf(q) == """SELECT (CAST("p"."age" AS NUMERIC) / "p"."age") FROM "person" "p"""")
    }

    // The truncating spelling is the plain operator here, because PostgreSQL's integer `/` already truncates toward zero.
    // MySQL renders the same node as `DIV`; see MysqlDialectDslRenderTest.
    "divideTruncating renders the plain operator on PG" in {
        val q = people.select(c => c.p.age.divideTruncating(c.p.age))
        assert(sqlOf(q) == """SELECT ("p"."age" / "p"."age") FROM "person" "p"""")
    }

    // The cast is keyed on the operand being INTEGRAL, not merely exact: PostgreSQL's `numeric / numeric` is already the
    // fractional quotient, so a BigDecimal division renders the bare operator.
    "a division on non-integral operands needs no cast on PG" in {
        val q = orders.select(c => c.o.total / c.o.total)
        assert(sqlOf(q) == """SELECT ("o"."total" / "o"."total") FROM "order" "o"""")
    }

    "string functions and concat" in {
        val q = people.select(c => (c.p.name.upper, c.p.name.lower, c.p.name.length, c.p.name.trim))
        assert(
            sqlOf(q) == """SELECT UPPER("p"."name"), LOWER("p"."name"), LENGTH("p"."name"), TRIM("p"."name") FROM "person" "p""""
        )
        val concat = people.select(c => c.p.name ++ " (adult)")
        assert(sqlOf(concat) == """SELECT ("p"."name" || $1) FROM "person" "p"""")
    }

    "substring with two raw bounds" in {
        val q = people.select(c => c.p.name.substring(1, 3))
        assert(sqlOf(q) == """SELECT SUBSTRING("p"."name" FROM $1 FOR $2) FROM "person" "p"""")
    }

    "isNull / isNotNull on Maybe column" in {
        assert(sqlOf(customers.where(c =>
            c.c.email == Absent
        )) == """SELECT "c"."id", "c"."name", "c"."email", "c"."suspended" FROM "customer" "c" WHERE ("c"."email" IS NULL)""")
        assert(sqlOf(customers.where(c =>
            c.c.email != Absent
        )) == """SELECT "c"."id", "c"."name", "c"."email", "c"."suspended" FROM "customer" "c" WHERE ("c"."email" IS NOT NULL)""")
    }

    "coalesce + nullIf" in {
        val q = customers.select(c => c.c.email.coalesce(Maybe("(none)")))
        assert(sqlOf(q) == """SELECT COALESCE("c"."email", $1) FROM "customer" "c"""")
        val n = people.select(c => c.p.name.absentIf(""))
        assert(sqlOf(n) == """SELECT NULLIF("p"."name", $1) FROM "person" "p"""")
    }

    "boolean predicates on Boolean column" in {
        assert(sqlOf(customers.where(c =>
            c.c.suspended.isTrue
        )) == """SELECT "c"."id", "c"."name", "c"."email", "c"."suspended" FROM "customer" "c" WHERE ("c"."suspended" IS TRUE)""")
        assert(
            sqlOf(customers.where(c =>
                c.c.suspended.isNotFalse
            )) == """SELECT "c"."id", "c"."name", "c"."email", "c"."suspended" FROM "customer" "c" WHERE ("c"."suspended" IS NOT FALSE)"""
        )
    }

    "unary NOT" in {
        val q = people.where(c => !(c.p.age >= 18))
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE (NOT ("p"."age" >= $1))""")
    }

    // --- CASE WHEN ---

    "case-when with otherwise" in {
        val q = people.select(c =>
            Sql.when(c.p.age >= 65).to("senior").when(c.p.age >= 18).to("adult").otherwise("minor")
        )
        assert(
            sqlOf(q) == """SELECT CASE WHEN ("p"."age" >= $1) THEN $2 WHEN ("p"."age" >= $3) THEN $4 ELSE $5 END FROM "person" "p""""
        )
    }

    "case-when with otherwiseAbsent" in {
        val q = people.select(c => Sql.when(c.p.age >= 18).to("adult").otherwiseAbsent)
        assert(sqlOf(q) == """SELECT CASE WHEN ("p"."age" >= $1) THEN $2 END FROM "person" "p"""")
    }

    // --- Cast / call / raw ---

    "cast emits CAST" in {
        val q = people.select(c => c.p.age.cast[String])
        assert(sqlOf(q) == """SELECT CAST("p"."age" AS TEXT) FROM "person" "p"""")
    }

    "cast[String] renders TEXT for id (Long)" in {
        val q = Sql.from[Person]("p").select(c => c.p.id.cast[String])
        assert(sqlOf(q) == """SELECT CAST("p"."id" AS TEXT) FROM "person" "p"""")
        assert(paramsOf(q) == 0)
    }

    "cast[Int] renders INTEGER for name (String)" in {
        val q = Sql.from[Person]("p").select(c => c.p.name.cast[Int])
        assert(sqlOf(q) == """SELECT CAST("p"."name" AS INTEGER) FROM "person" "p"""")
        assert(paramsOf(q) == 0)
    }

    "Sql.call emits a function call" in {
        val q = people.select(c => Sql.call[kyo.Instant]("DATE_TRUNC", Sql.literal("day"), c.p.deptId))
        assert(sqlOf(q) == """SELECT DATE_TRUNC($1, "p"."deptId") FROM "person" "p"""")
    }

    "Sql.call compiles without using SqlSchema and renders a bound param" in {
        val q = people.select(_ => Sql.call[Long]("nextval", Sql.literal("my_seq")))
        assert(sqlOf(q) == """SELECT nextval($1) FROM "person" "p"""")
    }

    "Sql.call inside WHERE compiles without using SqlSchema" in {
        val q = people.where(c => c.p.id == Sql.call[Long]("nextval", Sql.raw("'my_seq'")))
        assert(
            sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."id" = nextval('my_seq'))"""
        )
    }

    "Sql.raw emits as-is" in {
        val q = people.select(c => Sql.raw[Int]("NOW()"))
        assert(sqlOf(q) == """SELECT NOW() FROM "person" "p"""")
    }

    // --- Fragments (sql"..." interpolator) ---

    "frag with no args is a literal fragment" in {
        val f = sql"is_active = TRUE"
        val q = people.where(_ => f.as[Boolean])
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE is_active = TRUE""")
    }

    "frag with a bind parameter" in {
        val cutoff = 18
        val f      = sql"age > $cutoff"
        val q      = people.where(_ => f.as[Boolean])
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE age > $1""")
        assert(paramsOf(q) == 1)
    }

    "frag with an embedded Column reference" in {
        val q = people.where(c => sql"length(${c.p.name}) > 0".as[Boolean])
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE length("p"."name") > 0""")
    }

    "frag composes via ++" in {
        val cutoff = 21
        val base   = sql"age >= $cutoff"
        val extra  = sql" AND status = 'ACTIVE'"
        val q      = people.where(_ => (base ++ extra).as[Boolean])
        assert(
            sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE age >= $1 AND status = 'ACTIVE'"""
        )
        assert(paramsOf(q) == 1)
    }

    "frag mixing embed + bind in the projection" in {
        val suffix = " (adult)"
        val q      = people.select(c => sql"${c.p.name} || $suffix")
        assert(sqlOf(q) == """SELECT "p"."name" || $1 FROM "person" "p"""")
        assert(paramsOf(q) == 1)
    }

    "frag with multiple binds preserves placeholder order" in {
        val a = 1
        val b = 2
        val q = people.where(_ => sql"x BETWEEN $a AND $b".as[Boolean])
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE x BETWEEN $1 AND $2""")
    }

    "frag with a sub-query reference" in {
        val sub = orders.select(o => o.o.userId)
        val q   = people.where(c => sql"${c.p.id} IN ${sub}".as[Boolean])
        assert(
            sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE "p"."id" IN (SELECT "o"."userId" FROM "order" "o")"""
        )
    }

    // --- INSERT ---

    "insert single row" in {
        val s = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L))
        assert(sqlOf(s) == """INSERT INTO "person" ("id", "name", "age", "deptId") VALUES ($1, $2, $3, $4) RETURNING "id"""")
    }

    "insert many rows" in {
        val s = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L), Person(0L, "Bob", 25, 2L))
        assert(sqlOf(
            s
        ) == """INSERT INTO "person" ("id", "name", "age", "deptId") VALUES ($1, $2, $3, $4), ($5, $6, $7, $8) RETURNING "id"""")
    }

    "insert partialValues" in {
        val s = Sql.insert[Person].partialValues(_.name := "Alice", _.age := 30)
        assert(sqlOf(s) == """INSERT INTO "person" ("name", "age") VALUES ($1, $2) RETURNING "id"""")
    }

    "insert fromSelect" in {
        val s = Sql.insert[Person].fromSelect(_.name, _.age)(departments.select(d => (d.d.name, Sql.literal(0))))
        assert(sqlOf(s) == """INSERT INTO "person" ("name", "age") SELECT "d"."name", $1 FROM "department" "d" RETURNING "id"""")
    }

    "insert on conflict do nothing" in {
        val s = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L)).onConflictDoNothing(_.name)
        assert(
            sqlOf(
                s
            ) == """INSERT INTO "person" ("id", "name", "age", "deptId") VALUES ($1, $2, $3, $4) ON CONFLICT ("name") DO NOTHING RETURNING "id""""
        )
    }

    "insert on conflict do update with where" in {
        val s = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L))
            .onConflictDoUpdate(_.name).where(_.id != 1L)(_.age := 30)
        assert(
            sqlOf(s) == """INSERT INTO "person" ("id", "name", "age", "deptId") VALUES ($1, $2, $3, $4) ON CONFLICT ("name") DO UPDATE SET "age" = $5 WHERE ("id" <> $6) RETURNING "id""""
        )
    }

    "insert auto-renders RETURNING pk on PG when first column is Long" in {
        val s = Sql.insert[Person].values(Person(0L, "Alice", 30, 1L))
        assert(
            sqlOf(s) == """INSERT INTO "person" ("id", "name", "age", "deptId") VALUES ($1, $2, $3, $4) RETURNING "id""""
        )
    }

    // --- UPDATE ---

    "update single column with where" in {
        val s = Sql.update[Person].set(_.name := "Alice").where(c => c.id == 1L)
        assert(sqlOf(s) == """UPDATE "person" SET "name" = $1 WHERE ("id" = $2)""")
    }

    "update with computed value" in {
        val s = Sql.update[Person].set(c => c.age := c.age + 1).where(c => c.id == 1L)
        assert(sqlOf(s) == """UPDATE "person" SET "age" = ("age" + $1) WHERE ("id" = $2)""")
    }

    "update without where (build)" in {
        val s = Sql.update[Person].set(_.age := 0).build
        assert(sqlOf(s) == """UPDATE "person" SET "age" = $1""")
    }

    // --- DELETE ---

    "delete with where" in {
        val s = Sql.delete[Person].where(c => c.age < 18)
        assert(sqlOf(s) == """DELETE FROM "person" WHERE ("age" < $1)""")
    }

    "delete all" in {
        assert(sqlOf(Sql.delete[Person].build) == """DELETE FROM "person"""")
    }

    // --- Locks ---

    "lock for update" in {
        val q = people.where(c => c.p.id == 1L).forUpdate
        assert(sqlOf(q) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."id" = $1) FOR UPDATE""")
    }

    "lock for update of … skip locked" in {
        val q = people.where(c => c.p.id == 1L).forUpdateSkipLocked
        assert(sqlOf(
            q
        ) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."id" = $1) FOR UPDATE SKIP LOCKED""")
    }

    "lock for share with of-tables and nowait" in {
        val q1 = people.where(c => c.p.id == 1L).forShare("person")
        val q2 = people.where(c => c.p.id == 1L).forShareNoWait
        assert(sqlOf(
            q1
        ) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."id" = $1) FOR SHARE OF "person"""")
        assert(
            sqlOf(q2) == """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."id" = $1) FOR SHARE NOWAIT"""
        )
    }

end PostgresDialectDslRenderTest
