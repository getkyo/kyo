package kyo

import kyo.*
import kyo.Sql.*
import kyo.Test
import scala.compiletime.testing.typeChecks

/** Leaf coverage for the eager `groupBy` view: its materialization, its static lift, and its type-level boundary.
  *
  * Covers three invariants:
  *   - the AST `GroupBy` carries an eagerly built `view: Record[F]` (structural check)
  *   - `FromExpr.derived[GroupBy]` lifts a statically-known `groupBy(...)` value
  *   - an unprojected `GroupBy` is a `SelectSource`, not a `Query`, so no query terminal accepts it (compile-time rejection)
  *
  * The rendered SQL of grouped queries is asserted per dialect, in `PostgresDialectGroupedViewRenderTest` and the dialect DSL render
  * suites.
  */
class SqlGroupedViewTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema

    val people = Sql.from[Person]("p")

    "AST GroupBy carries view: Record[F] after groupBy" in {
        val g = people.groupBy(_.p.deptId)
        assert(g.productElementNames.contains("view"))
        g.productElement(g.productElementNames.indexOf("view")) match
            case v: Record[?] =>
                assert(v ne null)
                val dictMap = v.dict.toMap
                assert(dictMap.contains("deptId"))
                assert(dictMap("deptId").isInstanceOf[GroupedColumn[?, ?]])
            case other =>
                fail(s"Expected Record[?] for view field, got: ${other.getClass.getName}")
        end match
    }

    // --- FromExpr.derived[GroupBy] unapply ---
    //
    // The derived `FromExpr[GroupBy].unapply` reconstructs a statically-known `groupBy(...)` value
    // by walking its inline-expanded TASTy: the `GroupBy` product, its `source: Query` (via the
    // recursion guard), `keys: Chunk[Term]`, `view: Record[F]` (via `RecordFromExpr`'s
    // `buildGroupedView` arm) and `havingTerm: Maybe`.

    "FromExpr.derived[GroupBy] lifts a statically-known GroupBy" in {
        // `ColumnFromExpr.given` is required so the projection-shaped grouping key (`_.p.deptId`) lifts
        // to a real `Column`, without it the `selectDynamic` projection chain does not reconstruct.
        import kyo.internal.macros.ColumnFromExpr.given
        import kyo.internal.macros.RecordFromExpr.given
        assert(SqlLiftHarness.matched[GroupBy[Person, ?]](Sql.from[Person]("p").groupBy(_.p.deptId)))
    }

    // --- Unprojected GroupBy is not a Query ---
    //
    // A grouping becomes a statement only through `select`, so every query terminal must reject an unprojected
    // `GroupBy` at compile time. Each rejected expression below would otherwise produce invalid SQL
    // (`SELECT * ... GROUP BY ...`) or a wrong row type.

    "unprojected GroupBy is unconstructible as a query" in {
        // Positive control: the same grouping with a projection is a Query and reaches the terminals.
        assert(typeChecks("""Sql.from[Person]("p").groupBy(c => c.p.deptId).select(view => view.deptId).count"""))
        // Not a Sql node, so it cannot render.
        assert(!typeChecks("""Sql.from[Person]("p").groupBy(c => c.p.deptId).render(IdiomRenderStub)"""))
        // Not a Query, so no query terminal accepts it.
        assert(!typeChecks("""Sql.from[Person]("p").groupBy(c => c.p.deptId).count"""))
        assert(!typeChecks("""Sql.from[Person]("p").groupBy(c => c.p.deptId).exists"""))
        assert(!typeChecks("""Sql.from[Person]("p").groupBy(c => c.p.deptId).forUpdate"""))
        assert(!typeChecks("""{ val g = Sql.from[Person]("p").groupBy(c => c.p.deptId); g.union(g) }"""))
        // Ordering and limiting live on the Select the projection produces, not on the grouping.
        assert(!typeChecks("""Sql.from[Person]("p").groupBy(c => c.p.deptId).orderBy(view => view.deptId.desc)"""))
        assert(!typeChecks("""Sql.from[Person]("p").groupBy(c => c.p.deptId).limit(10)"""))
    }

end SqlGroupedViewTest
