package kyo

import kyo.internal.RecordFromExpr.given

/** FromExpr derivation coverage for the [[SqlLiftHarness]] entry points.
  *
  * Each leaf drives one lift path through the harness (`matched` / `recordFieldNames`) and asserts the derived `FromExpr[A]` successfully
  * unapplies the supplied inline expression. Covers the `Literal[T]` product path, the `SqlSchema[T]` summon path, and the
  * `Table[T, F]` record-lift path used by the runtime and static renderers.
  */
class SqlLiftHarnessTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema

    "FromExpr.derived[Literal[Int]] lifts Literal(42, SqlSchema[Int]) to Some with value 42" in {
        assert(SqlLiftHarness.matched[SqlAst.Term[Int]](Sql.literal[Int](42)))
    }

    "FromExpr.derived[Literal[String]] lifts Literal(\"hello\", SqlSchema[String]) to Some" in {
        assert(SqlLiftHarness.matched[SqlAst.Term[String]](Sql.literal[String]("hello")))
    }

    "fromExprSqlSchema[Int].unapply of a summoned SqlSchema[Int] returns Some (non-None)" in {
        assert(SqlLiftHarness.matched[SqlSchema[Int]](summon[SqlSchema[Int]]))
    }

    "fromExprSqlSchema[LocalDate].unapply returns Some, named top-level given is found" in {
        assert(SqlLiftHarness.matched[SqlSchema[java.time.LocalDate]](summon[SqlSchema[java.time.LocalDate]]))
    }

    "FromExpr-lifted Table reconstructs to Some" in {
        assert(SqlLiftHarness.matched[SqlAst.Table[Person, ?]](Sql.from[Person]("p")))
    }

    "FromExpr-lifted Table reconstructs columns Record with the expected field names" in {
        val names = SqlLiftHarness.recordFieldNames[SqlAst.Table[Person, ?]](Sql.from[Person]("p"))
        assert(names == "p;age,deptId,id,name")
    }

end SqlLiftHarnessTest
