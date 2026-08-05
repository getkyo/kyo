package kyo

import kyo.Sql.*
import kyo.Test

/** Structural equality tests for Sql AST node types, plus the outer-join column lifting `leftJoin` / `rightJoin` / `fullOuterJoin` /
  * `innerJoin` apply to the unmatched side's columns.
  *
  * Covers: Limit, Lock, OrderBy, GroupedColumn equality.
  */
class SqlNodeTest extends Test:

    // Minimal table type used to build real Query values for wrapper-node tests.
    case class Row(id: Long) derives SqlSchema, CanEqual

    "two equal Limit nodes compare ==" in {
        val q1     = Sql.from[Row]("r")
        val q2     = Sql.from[Row]("r")
        val limit1 = Limit(q1, 10, 0)
        val limit2 = Limit(q2, 10, 0)
        assert(limit1 == limit2)
    }

    "two unequal Limit nodes compare !=" in {
        val q      = Sql.from[Row]("r")
        val limit1 = Limit(q, 10, 0)
        val limit2 = Limit(q, 20, 0)
        assert(limit1 != limit2)
    }

    "two equal Lock nodes compare ==" in {
        val q     = Sql.from[Row]("r")
        val lock1 = Lock(q, Lock.Mode.Update, Lock.Behavior.Wait, Chunk.empty)
        val lock2 = Lock(q, Lock.Mode.Update, Lock.Behavior.Wait, Chunk.empty)
        assert(lock1 == lock2)
    }

    "two equal OrderBy nodes compare ==" in {
        val col    = Column["id", Long]("r", "id", "id")
        val spec   = OrderSpec(col, OrderSpec.Direction.Asc, OrderSpec.AbsentPlacement.Default)
        val q      = Sql.from[Row]("r")
        val order1 = OrderBy(q, OrderingSpecs.Resolved(Chunk(spec)))
        val order2 = OrderBy(q, OrderingSpecs.Resolved(Chunk(spec)))
        assert(order1 == order2)
    }

    "two equal GroupedColumn nodes compare ==" in {
        val col1 = Column["id", Long]("r", "id", "id")
        val col2 = Column["id", Long]("r", "id", "id")
        val gc1  = GroupedColumn(col1)
        val gc2  = GroupedColumn(col2)
        assert(gc1 == gc2)
    }

    // --- Outer joins make the unmatched side optional ---
    //
    // An outer join emits rows in which the unmatched side is entirely absent, so the DSL has to type that side's columns as `Maybe`
    // or the first unmatched row raises `SqlDecodeColumnAbsentException` at a column the user was told is total. Each leaf below asserts a
    // concrete AST value; the leaf compiling at all is half the assertion, because `Absent` conforms to a column's value type only when
    // that type is a `Maybe`, so a leaf that tests a lifted column against `Absent` cannot be written against a total one.

    case class JoinLeft(id: Long, name: String) derives SqlSchema, CanEqual
    case class JoinRight(id: Long, budget: Long, note: Maybe[String]) derives SqlSchema, CanEqual
    case class JoinThird(id: Long, tag: String) derives SqlSchema, CanEqual

    val jl = Sql.from[JoinLeft]("l")
    val jr = Sql.from[JoinRight]("r2")
    val jt = Sql.from[JoinThird]("t")

    /** `(alias, name, sqlName)` of the column an absence test reads, `Absent` for any other node.
      *
      * `Term` carries no `CanEqual`, so the node cannot be compared to a constructed one; matching it out and reading the column's own
      * strings asserts the same thing without widening either side. `Absent` rather than `fail` because `fail` needs the per-leaf
      * `AssertScope` that only an `in { … }` body supplies.
      */
    private def absenceTestTarget(t: Term[Boolean]): Maybe[(String, String, String)] =
        t match
            case IsAbsent(c: Column[?, ?]) => Present((c.alias, c.name, c.sqlName))
            case _                         => Absent

    "leftJoin lifts the right side and leaves the left side alone" in {
        val j = jl.leftJoin(jr).on(x => x.l.id == x.r2.id)
        // Ascribing `Maybe[Long]` is the property: before the lift this column was `Column["budget", Long]`.
        val budget: Column["budget", Maybe[Long]] = j.columns.r2.budget
        assert(budget == Column["budget", Maybe[Long]]("r2", "budget", "budget"))
        // `Absent` conforms to this column's value type only because the lift made it a `Maybe`; on a total column it does not typecheck.
        assert(absenceTestTarget(budget == Absent) == Present(("r2", "budget", "budget")))
        // The left side is untouched.
        val leftId: Column["id", Long] = j.columns.l.id
        assert(leftId == Column["id", Long]("l", "id", "id"))
    }

    "rightJoin lifts the left side and leaves the right side alone" in {
        val j                                       = jl.rightJoin(jr).on(x => x.l.id == x.r2.id)
        val leftName: Column["name", Maybe[String]] = j.columns.l.name
        assert(absenceTestTarget(leftName == Absent) == Present(("l", "name", "name")))
        val rightBudget: Column["budget", Long] = j.columns.r2.budget
        assert(rightBudget == Column["budget", Long]("r2", "budget", "budget"))
    }

    "fullOuterJoin lifts both sides" in {
        val j                                       = jl.fullOuterJoin(jr).on(x => x.l.id == x.r2.id)
        val leftName: Column["name", Maybe[String]] = j.columns.l.name
        val budget: Column["budget", Maybe[Long]]   = j.columns.r2.budget
        assert(absenceTestTarget(leftName == Absent) == Present(("l", "name", "name")))
        assert(absenceTestTarget(budget == Absent) == Present(("r2", "budget", "budget")))
    }

    "innerJoin lifts neither side" in {
        val j                                   = jl.innerJoin(jr).on(x => x.l.id == x.r2.id)
        val leftName: Column["name", String]    = j.columns.l.name
        val rightBudget: Column["budget", Long] = j.columns.r2.budget
        assert((leftName.sqlName, rightBudget.sqlName) == ("name", "budget"))
    }

    "an already-optional column does not nest under the lift" in {
        val j = jl.leftJoin(jr).on(x => x.l.id == x.r2.id)
        // `Maybe[Maybe[String]]` is undecodable (the outer read consumes the column and the inner has none left), so
        // `AsMaybe.alreadyMaybe` has to win here. Ascribing the single-level type is what pins it.
        val note: Column["note", Maybe[String]] = j.columns.r2.note
        assert(note == Column["note", Maybe[String]]("r2", "note", "note"))
    }

    "the lift reaches every alias when the unmatched side is itself a join" in {
        val inner                                 = jr.innerJoin(jt).on(x => x.r2.id == x.t.id)
        val outer                                 = jl.leftJoin(inner).on(x => x.l.id == x.r2.id)
        val budget: Column["budget", Maybe[Long]] = outer.columns.r2.budget
        val tag: Column["tag", Maybe[String]]     = outer.columns.t.tag
        assert(absenceTestTarget(budget == Absent) == Present(("r2", "budget", "budget")))
        assert(absenceTestTarget(tag == Absent) == Present(("t", "tag", "tag")))
    }

    "the ON predicate still sees both sides at their declared types" in {
        // ON is evaluated before the lift, so a `fullOuterJoin` still compares two total `Long` columns here. If the
        // predicate saw the lifted view this would not compile, and every existing `.on` in the tree would have had to be respelled.
        val j = jl.fullOuterJoin(jr).on(x => x.l.id == x.r2.id)
        j.predicate match
            case Comparison(l: Column[?, ?], op, r: Column[?, ?]) =>
                assert((l.alias, l.sqlName, op, r.alias, r.sqlName) == ("l", "id", Comparison.Op.Eq, "r2", "id"))
            case other => fail(s"expected a column-to-column comparison, got $other")
        end match
    }

end SqlNodeTest
