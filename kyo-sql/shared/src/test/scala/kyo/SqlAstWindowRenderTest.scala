package kyo

import kyo.SqlAst.*

/** Runtime-render coverage for window-function queries.
  *
  * Every leaf calls `.renderPostgres` / `.renderMysql` and asserts the compiled SQL strings for both backends.
  */
class SqlAstWindowRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema

    inline given personSqlSchema: SqlSchema[Person] = SqlSchema.derived

    // ROW_NUMBER() OVER () (no PARTITION, no ORDER)

    "rowNumber over empty spec renders ROW_NUMBER() OVER ()" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.RowNumber.over(WindowSpec(Chunk.empty, Chunk.empty, Maybe.empty))
        )
        assert(q.renderPostgres.sql.contains("ROW_NUMBER() OVER ()"))
        assert(q.renderMysql.sql.contains("ROW_NUMBER() OVER ()"))
    }

    "rowNumber via Sql.windowSpec builder renders ROW_NUMBER() OVER ()" in {
        val q = Sql.from[Person]("p").select(_ =>
            Sql.windowSpec.rowNumber
        )
        assert(q.renderPostgres.sql.contains("ROW_NUMBER() OVER ()"))
        assert(q.renderMysql.sql.contains("ROW_NUMBER() OVER ()"))
    }

    // RANK() with partitionBy + orderBy

    "rank over partitionBy(deptId).orderBy(age.asc) renders correctly" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.Rank.over(
                WindowSpec(Chunk(c.p.deptId), Chunk(c.p.age.asc), Maybe.empty)
            )
        )
        val rp = q.renderPostgres.sql
        val rm = q.renderMysql.sql
        assert(rp.contains("""PARTITION BY "p"."deptId""""))
        assert(rp.contains("""ORDER BY "p"."age" ASC"""))
        assert(rp.contains("RANK() OVER ("))
        assert(rm.contains("PARTITION BY `p`.`deptId`"))
        assert(rm.contains("ORDER BY `p`.`age` ASC"))
        assert(rm.contains("RANK() OVER ("))
    }

    "denseRank over partitionBy(deptId).orderBy(name.asc) renders correctly" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.DenseRank.over(
                WindowSpec(Chunk(c.p.deptId), Chunk(c.p.name.asc), Maybe.empty)
            )
        )
        val rp = q.renderPostgres.sql
        val rm = q.renderMysql.sql
        assert(rp.contains("DENSE_RANK() OVER ("))
        assert(rp.contains("""PARTITION BY "p"."deptId""""))
        assert(rm.contains("DENSE_RANK() OVER ("))
        assert(rm.contains("PARTITION BY `p`.`deptId`"))
    }

    "percentRank over orderBy(age.desc) renders correctly" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.PercentRank.over(
                WindowSpec(Chunk.empty, Chunk(c.p.age.desc), Maybe.empty)
            )
        )
        val rp = q.renderPostgres.sql
        val rm = q.renderMysql.sql
        assert(rp.contains("PERCENT_RANK() OVER ("))
        assert(rp.contains("""ORDER BY "p"."age" DESC"""))
        assert(rm.contains("PERCENT_RANK() OVER ("))
        assert(rm.contains("ORDER BY `p`.`age` DESC"))
    }

    "cumeDist over orderBy(id.asc) renders correctly" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.CumeDist.over(
                WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty)
            )
        )
        assert(q.renderPostgres.sql.contains("CUME_DIST() OVER ("))
        assert(q.renderMysql.sql.contains("CUME_DIST() OVER ("))
    }

    // Window frames

    "frameRange(unboundedPreceding, currentRow) emits RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.sum.over(
                WindowSpec(
                    Chunk(c.p.deptId),
                    Chunk(c.p.age.asc),
                    Maybe(WindowFrame(WindowFrame.Kind.Range, FrameBound.UnboundedPreceding, Maybe(FrameBound.CurrentRow)))
                )
            )
        )
        assert(q.renderPostgres.sql.contains("RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"))
        assert(q.renderMysql.sql.contains("RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"))
    }

    "frameRows(preceding(2), currentRow) emits ROWS BETWEEN N PRECEDING AND CURRENT ROW" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.sum.over(
                WindowSpec(
                    Chunk(c.p.deptId),
                    Chunk(c.p.id.asc),
                    Maybe(WindowFrame(
                        WindowFrame.Kind.Rows,
                        FrameBound.Preceding(Literal(2, summon[SqlSchema[Int]])),
                        Maybe(FrameBound.CurrentRow)
                    ))
                )
            )
        )
        val rp = q.renderPostgres.sql
        val rm = q.renderMysql.sql
        assert(rp.contains("ROWS BETWEEN"))
        assert(rp.contains("PRECEDING AND CURRENT ROW"))
        assert(rm.contains("ROWS BETWEEN"))
        assert(rm.contains("PRECEDING AND CURRENT ROW"))
    }

    "frameRows with single unboundedPreceding bound (no end) emits ROWS UNBOUNDED PRECEDING" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.sum.over(
                WindowSpec(
                    Chunk.empty,
                    Chunk(c.p.id.asc),
                    Maybe(WindowFrame(WindowFrame.Kind.Rows, FrameBound.UnboundedPreceding, Maybe.empty))
                )
            )
        )
        assert(q.renderPostgres.sql.contains("ROWS UNBOUNDED PRECEDING"))
        assert(q.renderMysql.sql.contains("ROWS UNBOUNDED PRECEDING"))
    }

    "frameGroups(currentRow, unboundedFollowing) emits GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.sum.over(
                WindowSpec(
                    Chunk.empty,
                    Chunk(c.p.age.asc),
                    Maybe(WindowFrame(WindowFrame.Kind.Groups, FrameBound.CurrentRow, Maybe(FrameBound.UnboundedFollowing)))
                )
            )
        )
        assert(q.renderPostgres.sql.contains("GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING"))
        assert(q.renderMysql.sql.contains("GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING"))
    }

    // lead / lag with offset

    "lead(expr, offset=1) renders LEAD(col, 1)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.Lead(c.p.age, Literal(1, summon[SqlSchema[Int]]), Maybe.empty)
                .over(WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty))
        )
        assert(q.renderPostgres.sql.contains("LEAD("))
        assert(q.renderMysql.sql.contains("LEAD("))
    }

    "lag(expr, offset=1) renders LAG(col, 1)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.Lag(c.p.age, Literal(1, summon[SqlSchema[Int]]), Maybe.empty)
                .over(WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty))
        )
        assert(q.renderPostgres.sql.contains("LAG("))
        assert(q.renderMysql.sql.contains("LAG("))
    }

    "firstValue renders FIRST_VALUE(col) OVER (...)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.FirstValue(c.p.name)
                .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
        )
        assert(q.renderPostgres.sql.contains("FIRST_VALUE("))
        assert(q.renderMysql.sql.contains("FIRST_VALUE("))
    }

    "lastValue renders LAST_VALUE(col) OVER (...)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.LastValue(c.p.name)
                .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
        )
        assert(q.renderPostgres.sql.contains("LAST_VALUE("))
        assert(q.renderMysql.sql.contains("LAST_VALUE("))
    }

    "nthValue renders NTH_VALUE(col, n) OVER (...)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.NthValue(c.p.name, Literal(3, summon[SqlSchema[Int]]))
                .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
        )
        assert(q.renderPostgres.sql.contains("NTH_VALUE("))
        assert(q.renderMysql.sql.contains("NTH_VALUE("))
    }

    // Window aggregate (sum(col) OVER (...))

    "SUM aggregate over partitionBy+orderBy renders complete SQL" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.sum.over(
                WindowSpec(Chunk(c.p.deptId), Chunk(c.p.age.asc), Maybe.empty)
            )
        )
        assert(
            q.renderPostgres.sql ==
                """SELECT SUM("p"."age") OVER (PARTITION BY "p"."deptId" ORDER BY "p"."age" ASC) FROM "person" "p""""
        )
        assert(
            q.renderMysql.sql ==
                "SELECT SUM(`p`.`age`) OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`age` ASC) FROM `person` `p`"
        )
        assert(q.renderPostgres.params.isEmpty)
    }

    "MAX aggregate over orderBy(id.asc) renders complete SQL" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.max.over(
                WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty)
            )
        )
        val rp = q.renderPostgres.sql
        val rm = q.renderMysql.sql
        assert(rp.contains("MAX("))
        assert(rp.contains("OVER ("))
        assert(rm.contains("MAX("))
        assert(rm.contains("OVER ("))
        assert(q.renderPostgres.params.isEmpty)
    }

    "COUNT aggregate over partitionBy renders complete SQL" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.count.over(
                WindowSpec(Chunk(c.p.deptId), Chunk.empty, Maybe.empty)
            )
        )
        val rp = q.renderPostgres.sql
        val rm = q.renderMysql.sql
        assert(rp.contains("COUNT("))
        assert(rp.contains("PARTITION BY"))
        assert(rm.contains("COUNT("))
        assert(rm.contains("PARTITION BY"))
        assert(q.renderPostgres.params.isEmpty)
    }

    // partitionBy semantics, replace (not append)

    "single-element partitionBy renders one PARTITION BY column in output" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.RowNumber.over(
                WindowSpec(Chunk(c.p.deptId), Chunk.empty, Maybe.empty)
            )
        )
        val rp = q.renderPostgres.sql
        val rm = q.renderMysql.sql
        assert(rp.contains("""PARTITION BY "p"."deptId""""))
        assert(!rp.contains("""PARTITION BY "p"."deptId", """))
        assert(rm.contains("PARTITION BY `p`.`deptId`"))
        assert(!rm.contains("PARTITION BY `p`.`deptId`, "))
    }

    "partitionBy vararg(k1, k2) produces multi-key PARTITION BY" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.RowNumber.over(
                WindowSpec(Chunk(c.p.deptId, c.p.age), Chunk.empty, Maybe.empty)
            )
        )
        assert(q.renderPostgres.sql.contains("""PARTITION BY "p"."deptId", "p"."age""""))
        assert(q.renderMysql.sql.contains("PARTITION BY `p`.`deptId`, `p`.`age`"))
    }

    // builder chain end-to-end: Sql.windowSpec.partitionBy(x).rowNumber

    "Sql.windowSpec.partitionBy(deptId).rowNumber renders ROW_NUMBER OVER (PARTITION BY deptId)" in {
        val q = Sql.from[Person]("p").select(c =>
            Sql.windowSpec.partitionBy(c.p.deptId).rowNumber
        )
        assert(q.renderPostgres.sql.contains("ROW_NUMBER()"))
        assert(q.renderPostgres.sql.contains("""PARTITION BY "p"."deptId""""))
        assert(q.renderPostgres.params.isEmpty)
    }

    "Sql.windowSpec.partitionBy(deptId).orderBy(age.asc).rank renders RANK OVER (PARTITION BY deptId ORDER BY age ASC)" in {
        val q = Sql.from[Person]("p").select(c =>
            Sql.windowSpec.partitionBy(c.p.deptId).orderBy(c.p.age.asc).rank
        )
        val rp = q.renderPostgres.sql
        assert(rp.contains("RANK()"))
        assert(rp.contains("""PARTITION BY "p"."deptId""""))
        assert(rp.contains("""ORDER BY "p"."age" ASC"""))
        assert(q.renderPostgres.params.isEmpty)
    }

end SqlAstWindowRenderTest
