package kyo

import kyo.internal.macros.BindFromExpr.given
// Load-bearing: `matched[Sql.Term[X]]` walks `Term` as a SUM, so the derivation asks for a `FromExpr` for every
// custom-lift child including `Sql.Column`, not only the `Literal` the leaf is about. Without this import the
// `Column` branch has no custom lift and the custom-lift arm aborts.
import kyo.internal.macros.ColumnFromExpr.given
import kyo.internal.macros.RecordFromExpr.given

/** FromExpr derivation coverage for the [[SqlLiftHarness]] entry points.
  *
  * Each leaf drives one lift path through the harness (`matched` / `recordFieldNames`) and asserts the derived `FromExpr[A]` successfully
  * unapplies the supplied inline expression. Covers the `Literal[T]` bind-carrier path, including the column-given resolution it depends
  * on, and the `Table[T, F]` record-lift path used by the runtime and static renderers.
  *
  * Both `kyo.internal.macros` given imports are load-bearing rather than decorative. The derivation intercepts these types instead of
  * walking them generically, and it resolves the replacement instance against the implicit scope of the macro's USE site, which is this
  * file.
  *
  * The two imports fail DIFFERENTLY when dropped, and the difference is deliberate rather than an inconsistency. Dropping
  * `BindFromExpr.given` fails the COMPILE with a message naming the import, because `Literal` and the other bind carriers take the
  * custom-lift arm, which aborts exactly as its production counterpart `deriveSqlCustomLift` always has. Dropping `RecordFromExpr.given`
  * still answers `None` and reports a false `matched`, because the record arm matches `deriveRecord`, which deliberately tolerates a
  * context with no kyo-sql given on its path.
  *
  * The asymmetry is worth stating: a silent `None` is indistinguishable from the derivation correctly refusing a tree, so a missing import
  * can masquerade as an ordinary assertion failure. `ColumnFromExprNegativeTest` holds the guard for the aborting half.
  */
/** Probe enum for the same-module bind-schema leaves: its `SqlSchema.Column` given is derived in the compilation unit under test, so
  * no class file exists when the lift macro expands, which is exactly the condition the render stand-in exists for.
  */
enum SqlLiftHarnessTestStatus derives SqlSchema.Column:
    case Pending, Paid

class SqlLiftHarnessTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema

    "a Literal over a same-module derives enum lifts to Some" in {
        // The schema given has no loadable class at expansion time; the lift must not depend on resolving it.
        assert(SqlLiftHarness.matched[Sql.Term[SqlLiftHarnessTestStatus]](
            Sql.literal[SqlLiftHarnessTestStatus](SqlLiftHarnessTestStatus.Paid)
        ))
    }

    "a where over a same-module enum bind lifts to Some" in {
        assert(SqlLiftHarness.matched(
            Sql.from[Person]("p").where(c => Sql.literal(SqlLiftHarnessTestStatus.Paid) == Sql.literal(SqlLiftHarnessTestStatus.Paid))
        ))
    }

    "FromExpr.derived[Literal[Int]] lifts a lifted Int to Some" in {
        assert(SqlLiftHarness.matched[Sql.Term[Int]](Sql.literal[Int](42)))
    }

    "FromExpr.derived[Literal[String]] lifts a lifted String to Some" in {
        assert(SqlLiftHarness.matched[Sql.Term[String]](Sql.literal[String]("hello")))
    }

    // `matched` only reports isDefined, so nothing above would catch a Literal that lifted its value
    // eagerly as a constant. This pins the hole: the value field must stay a runtime bind.
    "FromExpr-lifted Literal keeps its value as a runtime bind hole, not an eager constant" in {
        val r = SqlLiftHarness.repr[Sql.Term[Int]](Sql.literal[Int](42))
        assert(r.startsWith("Some(Literal("))
        assert(r.contains("BindHole"))
    }

    // The bind carrier holds the column codec itself, and the lift resolves that reference through
    // `resolveStableGiven`. A named top-level given is the shape that has to resolve: `localDate` is one, so a lift
    // that only handled anonymous givens would answer None here and silently cost every temporal query its fold.
    "a Literal over a named top-level column given lifts to Some" in {
        assert(SqlLiftHarness.matched[Sql.Term[java.time.LocalDate]](Sql.literal(java.time.LocalDate.of(2026, 5, 5))))
    }

    // The parameterised half of the same resolution: `maybe[A](using inner: Column[A])` takes an argument, so the
    // lift has to carry the argument through rather than resolve a bare reference.
    "a Literal over a parameterised column given lifts to Some" in {
        assert(SqlLiftHarness.matched[Sql.Term[Maybe[Int]]](Sql.literal(Maybe(42))))
    }

    "FromExpr-lifted Table reconstructs to Some" in {
        assert(SqlLiftHarness.matched[Sql.Table[Person, ?]](Sql.from[Person]("p")))
    }

    "FromExpr-lifted Table reconstructs columns Record with the expected field names" in {
        val names = SqlLiftHarness.recordFieldNames[Sql.Table[Person, ?]](Sql.from[Person]("p"))
        assert(names == "p;age,deptId,id,name")
    }

    // `CrossJoin` carries the merged two-table record, the shape a join produces.
    case class Dept(id: Long, budget: Long) derives SqlSchema

    "FromExpr-lifted CrossJoin reconstructs to Some, merged columns and all" in {
        assert(SqlLiftHarness.matched[Sql.CrossJoin[(Person, Dept), ?]](Sql.from[Person]("p").crossJoin(Sql.from[Dept]("d"))))
    }

end SqlLiftHarnessTest
