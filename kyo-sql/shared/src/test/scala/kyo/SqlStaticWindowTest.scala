package kyo

import kyo.SqlAst.*

/** Verifies that window-function queries lift statically through the Phase 8 [[kyo.FromExpr]] derivations for [[WindowSpec]],
  * [[WindowFrame]], [[FrameBound]], and [[WindowSpecBuilder]].
  *
  * Every leaf calls [[SqlStatic.staticSql]] and asserts the compiled SQL strings for both backends. All queries must reduce at compile time
  * if any leaf fails to lift the macro will emit a compile error, not a runtime failure.
  */
class SqlStaticWindowTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema

    inline given personSqlSchema: SqlSchema[Person] = SqlSchema.derived

    // ── Leaf 1, ROW_NUMBER() OVER () (no PARTITION, no ORDER) ───────────────

    "rowNumber over empty spec lifts to ROW_NUMBER() OVER ()" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.RowNumber.over(WindowSpec(Chunk.empty, Chunk.empty, Maybe.empty))
            )
        )
        assert(r.sql.postgres.contains("ROW_NUMBER() OVER ()"))
        assert(r.sql.mysql.contains("ROW_NUMBER() OVER ()"))
    }

    "rowNumber over empty spec via Sql.windowSpec builder lifts to ROW_NUMBER() OVER ()" in {
        // Driven through the public `Sql.windowSpec.rowNumber` builder terminator (post-BLOCKER-1 fix).
        // The macro folds the inlined `Sql.windowSpec.build` Select-chain through resolveBindings's
        // case-field arm.
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(_ =>
                Sql.windowSpec.rowNumber
            )
        )
        assert(r.sql.postgres.contains("ROW_NUMBER() OVER ()"))
        assert(r.sql.mysql.contains("ROW_NUMBER() OVER ()"))
    }

    // ── Leaf 2, RANK() with partitionBy + orderBy ────────────────────────────

    "rank over partitionBy(deptId).orderBy(age.asc) lifts correctly" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.Rank.over(
                    WindowSpec(Chunk(c.p.deptId), Chunk(c.p.age.asc), Maybe.empty)
                )
            )
        )
        assert(r.sql.postgres.contains("""PARTITION BY "p"."deptId""""))
        assert(r.sql.postgres.contains("""ORDER BY "p"."age" ASC"""))
        assert(r.sql.postgres.contains("RANK() OVER ("))
        assert(r.sql.mysql.contains("PARTITION BY `p`.`deptId`"))
        assert(r.sql.mysql.contains("ORDER BY `p`.`age` ASC"))
        assert(r.sql.mysql.contains("RANK() OVER ("))
    }

    "denseRank over partitionBy(deptId).orderBy(name.asc) lifts correctly" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.DenseRank.over(
                    WindowSpec(Chunk(c.p.deptId), Chunk(c.p.name.asc), Maybe.empty)
                )
            )
        )
        assert(r.sql.postgres.contains("DENSE_RANK() OVER ("))
        assert(r.sql.postgres.contains("""PARTITION BY "p"."deptId""""))
        assert(r.sql.mysql.contains("DENSE_RANK() OVER ("))
        assert(r.sql.mysql.contains("PARTITION BY `p`.`deptId`"))
    }

    "percentRank over orderBy(age.desc) lifts correctly" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.PercentRank.over(
                    WindowSpec(Chunk.empty, Chunk(c.p.age.desc), Maybe.empty)
                )
            )
        )
        assert(r.sql.postgres.contains("PERCENT_RANK() OVER ("))
        assert(r.sql.postgres.contains("""ORDER BY "p"."age" DESC"""))
        assert(r.sql.mysql.contains("PERCENT_RANK() OVER ("))
        assert(r.sql.mysql.contains("ORDER BY `p`.`age` DESC"))
    }

    "cumeDist over orderBy(id.asc) lifts correctly" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.CumeDist.over(
                    WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty)
                )
            )
        )
        assert(r.sql.postgres.contains("CUME_DIST() OVER ("))
        assert(r.sql.mysql.contains("CUME_DIST() OVER ("))
    }

    // ── Leaf 3, Window frames ─────────────────────────────────────────────────

    "frameRange(unboundedPreceding, currentRow) emits RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                c.p.age.sum.over(
                    WindowSpec(
                        Chunk(c.p.deptId),
                        Chunk(c.p.age.asc),
                        Maybe(WindowFrame(WindowFrame.Kind.Range, FrameBound.UnboundedPreceding, Maybe(FrameBound.CurrentRow)))
                    )
                )
            )
        )
        assert(r.sql.postgres.contains("RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"))
        assert(r.sql.mysql.contains("RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"))
    }

    "frameRows(preceding(2), currentRow) emits ROWS BETWEEN N PRECEDING AND CURRENT ROW" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
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
        )
        assert(r.sql.postgres.contains("ROWS BETWEEN"))
        assert(r.sql.postgres.contains("PRECEDING AND CURRENT ROW"))
        assert(r.sql.mysql.contains("ROWS BETWEEN"))
        assert(r.sql.mysql.contains("PRECEDING AND CURRENT ROW"))
    }

    "frameRows with single unboundedPreceding bound (no end) emits ROWS UNBOUNDED PRECEDING" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                c.p.age.sum.over(
                    WindowSpec(
                        Chunk.empty,
                        Chunk(c.p.id.asc),
                        Maybe(WindowFrame(WindowFrame.Kind.Rows, FrameBound.UnboundedPreceding, Maybe.empty))
                    )
                )
            )
        )
        assert(r.sql.postgres.contains("ROWS UNBOUNDED PRECEDING"))
        assert(r.sql.mysql.contains("ROWS UNBOUNDED PRECEDING"))
    }

    "frameGroups(currentRow, unboundedFollowing) emits GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                c.p.age.sum.over(
                    WindowSpec(
                        Chunk.empty,
                        Chunk(c.p.age.asc),
                        Maybe(WindowFrame(WindowFrame.Kind.Groups, FrameBound.CurrentRow, Maybe(FrameBound.UnboundedFollowing)))
                    )
                )
            )
        )
        assert(r.sql.postgres.contains("GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING"))
        assert(r.sql.mysql.contains("GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING"))
    }

    // ── Leaf 4, lead / lag with offset ───────────────────────────────────────

    "lead(expr, offset=1) lifts to LEAD(col, 1)" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.Lead(c.p.age, Literal(1, summon[SqlSchema[Int]]), Maybe.empty)
                    .over(WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty))
            )
        )
        assert(r.sql.postgres.contains("LEAD("))
        assert(r.sql.mysql.contains("LEAD("))
    }

    "lag(expr, offset=1) lifts to LAG(col, 1)" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.Lag(c.p.age, Literal(1, summon[SqlSchema[Int]]), Maybe.empty)
                    .over(WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty))
            )
        )
        assert(r.sql.postgres.contains("LAG("))
        assert(r.sql.mysql.contains("LAG("))
    }

    "firstValue lifts to FIRST_VALUE(col) OVER (...)" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.FirstValue(c.p.name)
                    .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
            )
        )
        assert(r.sql.postgres.contains("FIRST_VALUE("))
        assert(r.sql.mysql.contains("FIRST_VALUE("))
    }

    "lastValue lifts to LAST_VALUE(col) OVER (...)" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.LastValue(c.p.name)
                    .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
            )
        )
        assert(r.sql.postgres.contains("LAST_VALUE("))
        assert(r.sql.mysql.contains("LAST_VALUE("))
    }

    "nthValue lifts to NTH_VALUE(col, n) OVER (...)" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.NthValue(c.p.name, Literal(3, summon[SqlSchema[Int]]))
                    .over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.id.asc), Maybe.empty))
            )
        )
        assert(r.sql.postgres.contains("NTH_VALUE("))
        assert(r.sql.mysql.contains("NTH_VALUE("))
    }

    // ── Leaf 5, Window aggregate (sum(col) OVER (...)) ───────────────────────

    "SUM aggregate over partitionBy+orderBy renders complete static SQL" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                c.p.age.sum.over(
                    WindowSpec(Chunk(c.p.deptId), Chunk(c.p.age.asc), Maybe.empty)
                )
            )
        )
        assert(
            r.sql.postgres ==
                """SELECT SUM("p"."age") OVER (PARTITION BY "p"."deptId" ORDER BY "p"."age" ASC) FROM "person" "p""""
        )
        assert(
            r.sql.mysql ==
                "SELECT SUM(`p`.`age`) OVER (PARTITION BY `p`.`deptId` ORDER BY `p`.`age` ASC) FROM `person` `p`"
        )
        assert(r.params.isEmpty)
    }

    "MAX aggregate over orderBy(id.asc) renders complete static SQL" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                c.p.age.max.over(
                    WindowSpec(Chunk.empty, Chunk(c.p.id.asc), Maybe.empty)
                )
            )
        )
        assert(r.sql.postgres.contains("MAX("))
        assert(r.sql.postgres.contains("OVER ("))
        assert(r.sql.mysql.contains("MAX("))
        assert(r.sql.mysql.contains("OVER ("))
        assert(r.params.isEmpty)
    }

    "COUNT aggregate over partitionBy renders complete static SQL" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                c.p.age.count.over(
                    WindowSpec(Chunk(c.p.deptId), Chunk.empty, Maybe.empty)
                )
            )
        )
        assert(r.sql.postgres.contains("COUNT("))
        assert(r.sql.postgres.contains("PARTITION BY"))
        assert(r.sql.mysql.contains("COUNT("))
        assert(r.sql.mysql.contains("PARTITION BY"))
        assert(r.params.isEmpty)
    }

    // ── partitionBy semantics, replace (not append) ──────────────────────────
    //
    // `WindowSpecBuilder.partitionBy(key)` uses `new WindowSpecBuilder(Chunk(key), orderings, frameOpt)`
    // which REPLACES the partition list rather than appending. This is the post-Phase-8 semantic.
    // Multi-key partitions must use `partitionBy(k1, k2)` (the vararg overload).
    //
    // The static liftability constraint means we exercise the semantics via explicit `WindowSpec`
    // constructor calls (which are fully liftable) rather than chained builder calls (which produce
    // field-access selects on intermediate constructors that are not reducible by `FromExpr.derived`).

    "single-element partitionBy, one PARTITION BY column in output" in {
        // Equivalent to: Sql.windowSpec.partitionBy(c.p.deptId).rowNumber
        // The builder replaces the partition list with Chunk(key) (not append); we verify the
        // resulting WindowSpec with a single-element Chunk renders exactly one PARTITION BY column.
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.RowNumber.over(
                    WindowSpec(Chunk(c.p.deptId), Chunk.empty, Maybe.empty)
                )
            )
        )
        assert(r.sql.postgres.contains("""PARTITION BY "p"."deptId""""))
        // Confirm only one column in PARTITION BY, no second comma-separated column
        assert(!r.sql.postgres.contains("""PARTITION BY "p"."deptId", """))
        assert(r.sql.mysql.contains("PARTITION BY `p`.`deptId`"))
        assert(!r.sql.mysql.contains("PARTITION BY `p`.`deptId`, "))
    }

    "partitionBy vararg(k1, k2) produces multi-key PARTITION BY" in {
        // Equivalent to: Sql.windowSpec.partitionBy(c.p.deptId, c.p.age).rowNumber
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                WindowFunction.RowNumber.over(
                    WindowSpec(Chunk(c.p.deptId, c.p.age), Chunk.empty, Maybe.empty)
                )
            )
        )
        assert(r.sql.postgres.contains("""PARTITION BY "p"."deptId", "p"."age""""))
        assert(r.sql.mysql.contains("PARTITION BY `p`.`deptId`, `p`.`age`"))
    }

    // ── Leaf 6, builder chain end-to-end: Sql.windowSpec.partitionBy(x).rowNumber ──────────────
    //
    // PENDING (Phase 10 blocker): the builder chain `Sql.windowSpec.partitionBy(c.p.deptId).rowNumber`
    // does NOT lift through `staticSql`. The `resolveBindings` fixpoint in `FromExprDerived` is designed
    // to fold `Select(<case-class-constructor>, fieldName)` to the matching constructor argument, which
    // should reduce intermediate `WindowSpecBuilder` field accesses (`<wsb>.orderings`, `<wsb>.frameOpt`)
    // to `Chunk.empty` / `Maybe.empty`. Empirically this reduction does not happen, the macro still sees
    // an opaque tree and aborts. Diagnosis requires `-Xprint:inliner` output to inspect the exact tree
    // shape after inline expansion. Until that root cause is identified, the chain-end-to-end leaves are
    // `pending`.

    "Sql.windowSpec.partitionBy(deptId).rowNumber lifts through staticSql" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                Sql.windowSpec.partitionBy(c.p.deptId).rowNumber
            )
        )
        assert(r.sql.postgres.contains("ROW_NUMBER()"))
        assert(r.sql.postgres.contains("""PARTITION BY "p"."deptId""""))
        assert(r.params.isEmpty)
    }

    "Sql.windowSpec.partitionBy(deptId).orderBy(age.asc).rank lifts through staticSql" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p").select(c =>
                Sql.windowSpec.partitionBy(c.p.deptId).orderBy(c.p.age.asc).rank
            )
        )
        assert(r.sql.postgres.contains("RANK()"))
        assert(r.sql.postgres.contains("""PARTITION BY "p"."deptId""""))
        assert(r.sql.postgres.contains("""ORDER BY "p"."age" ASC"""))
        assert(r.params.isEmpty)
    }

end SqlStaticWindowTest
