package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[MysqlDialect]] renders for window functions and their frames: `OVER`, `PARTITION BY`, `ORDER BY` inside a
  * window, and the `ROWS` / `RANGE` / `GROUPS` frame modes with each bound.
  *
  * Every scenario pins the whole statement rather than a keyword inside it, because a window clause is assembled from parts whose order and
  * spacing are as much a part of the contract as the keywords: `PARTITION BY` before `ORDER BY` before the frame, one space between them, no
  * trailing space when a part is absent. A substring check passes on all of those being wrong.
  */
class MysqlDialectWindowRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)

    // ROW_NUMBER() OVER () (no PARTITION, no ORDER)

    "rowNumber over empty spec renders ROW_NUMBER() OVER ()" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.RowNumber.over(WindowSpec(Chunk.empty, Chunk.empty, Maybe.empty))
        )
        assert(q.render(MysqlDialect).onlySql.get == "SELECT ROW_NUMBER() OVER () FROM `person` `p`")
        assert(q.render(MysqlDialect).params.isEmpty)
    }

    "rowNumber via Sql.windowSpec builder renders ROW_NUMBER() OVER ()" in {
        val q = Sql.from[Person]("p").select(_ =>
            Sql.windowSpec.rowNumber
        )
        assert(q.render(MysqlDialect).onlySql.get == "SELECT ROW_NUMBER() OVER () FROM `person` `p`")
    }

    // RANK() with partitionBy + orderBy

    "rank over partitionBy(deptId).orderBy(age.asc) renders correctly" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.Rank.over(
                WindowSpec(Chunk(c.p.deptId), Chunk(c.p.age.asc), Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT RANK() OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`age` ASC) FROM `person` `p`"
        )
    }

    "denseRank over partitionBy(deptId).orderBy(name.asc) renders correctly" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.DenseRank.over(
                WindowSpec(Chunk(c.p.deptId), Chunk(c.p.name.asc), Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT DENSE_RANK() OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`name` ASC) FROM `person` `p`"
        )
    }

    "percentRank over orderBy(age.desc) renders correctly" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.PercentRank.over(
                WindowSpec(Chunk.empty, Chunk(c.p.age.desc), Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT PERCENT_RANK() OVER (ORDER BY `p`.`age` DESC) FROM `person` `p`"
        )
    }

    "cumeDist over orderBy(id.asc) renders correctly" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.CumeDist.over(
                WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT CUME_DIST() OVER (ORDER BY `p`.`id` ASC) FROM `person` `p`"
        )
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
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT SUM(`p`.`age`) OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`age` ASC RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM `person` `p`"
        )
    }

    // A numeric frame bound is a bind, not an inlined number, so this scenario pins the placeholder in the frame
    // and the value behind it.
    "frameRows(preceding(2), currentRow) emits ROWS BETWEEN N PRECEDING AND CURRENT ROW" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.sum.over(
                WindowSpec(
                    Chunk(c.p.deptId),
                    Chunk(c.p.id.asc),
                    Maybe(WindowFrame(
                        WindowFrame.Kind.Rows,
                        FrameBound.Preceding(Sql.lit(2, SqlSchema.int)),
                        Maybe(FrameBound.CurrentRow)
                    ))
                )
            )
        )
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get ==
                "SELECT SUM(`p`.`age`) OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`id` ASC ROWS BETWEEN ? PRECEDING AND CURRENT ROW) FROM `person` `p`"
        )
        assert(rm.params.size == 1)
        rm.params.head.value match
            case value: Int => assert(value == 2)
            case other      => fail(s"expected the Int bind 2 for the frame bound, got $other")
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
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT SUM(`p`.`age`) OVER (ORDER BY `p`.`id` ASC ROWS UNBOUNDED PRECEDING) FROM `person` `p`"
        )
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
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT SUM(`p`.`age`) OVER (ORDER BY `p`.`age` ASC GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) FROM `person` `p`"
        )
    }

    // lead / lag with offset

    "lead(expr, offset=1) renders LEAD(col, 1)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.Lead(c.p.age, Sql.lit(1, SqlSchema.int), Maybe.empty)
                .over(WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty))
        )
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "SELECT LEAD(`p`.`age`, ?) OVER (ORDER BY `p`.`id` ASC) FROM `person` `p`")
        assert(rm.params.size == 1)
    }

    "lag(expr, offset=1) renders LAG(col, 1)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.Lag(c.p.age, Sql.lit(1, SqlSchema.int), Maybe.empty)
                .over(WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty))
        )
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "SELECT LAG(`p`.`age`, ?) OVER (ORDER BY `p`.`id` ASC) FROM `person` `p`")
        assert(rm.params.size == 1)
    }

    "firstValue renders FIRST_VALUE(col) OVER (...)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.FirstValue(c.p.name)
                .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT FIRST_VALUE(`p`.`name`) OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`id` ASC) FROM `person` `p`"
        )
    }

    "lastValue renders LAST_VALUE(col) OVER (...)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.LastValue(c.p.name)
                .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT LAST_VALUE(`p`.`name`) OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`id` ASC) FROM `person` `p`"
        )
    }

    "nthValue renders NTH_VALUE(col, n) OVER (...)" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.NthValue(c.p.name, Sql.lit(3, SqlSchema.int))
                .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
        )
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get ==
                "SELECT NTH_VALUE(`p`.`name`, ?) OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`id` ASC) FROM `person` `p`"
        )
        rm.params.head.value match
            case value: Int => assert(value == 3)
            case other      => fail(s"expected the Int bind 3 for the nth position, got $other")
    }

    // Window aggregate (sum(col) OVER (...))

    "SUM aggregate over partitionBy+orderBy renders complete SQL" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.sum.over(
                WindowSpec(Chunk(c.p.deptId), Chunk(c.p.age.asc), Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT SUM(`p`.`age`) OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`age` ASC) FROM `person` `p`"
        )
    }

    "MAX aggregate over orderBy(id.asc) renders complete SQL" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.max.over(
                WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT MAX(`p`.`age`) OVER (ORDER BY `p`.`id` ASC) FROM `person` `p`"
        )
        assert(q.render(MysqlDialect).params.isEmpty)
    }

    "COUNT aggregate over partitionBy renders complete SQL" in {
        val q = Sql.from[Person]("p").select(c =>
            c.p.age.count.over(
                WindowSpec(Chunk(c.p.deptId), Chunk.empty, Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT COUNT(`p`.`age`) OVER (PARTITION BY `p`.`deptId`) FROM `person` `p`"
        )
        assert(q.render(MysqlDialect).params.isEmpty)
    }

    // partitionBy semantics, replace (not append)

    "single-element partitionBy renders one PARTITION BY column in output" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.RowNumber.over(
                WindowSpec(Chunk(c.p.deptId), Chunk.empty, Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT ROW_NUMBER() OVER (PARTITION BY `p`.`deptId`) FROM `person` `p`"
        )
    }

    "partitionBy vararg(k1, k2) produces multi-key PARTITION BY" in {
        val q = Sql.from[Person]("p").select(c =>
            WindowFunction.RowNumber.over(
                WindowSpec(Chunk(c.p.deptId, c.p.age), Chunk.empty, Maybe.empty)
            )
        )
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT ROW_NUMBER() OVER (PARTITION BY `p`.`deptId`, `p`.`age`) FROM `person` `p`"
        )
    }

end MysqlDialectWindowRenderTest
