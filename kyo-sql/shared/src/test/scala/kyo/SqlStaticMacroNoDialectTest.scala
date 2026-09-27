package kyo

import kyo.Sql.*
import scala.compiletime.testing.typeCheckErrors

/** Pins what [[kyo.internal.SqlStaticMacro]] reports when the compile classpath carries no dialect at all.
  *
  * `.runStatic` renders during expansion, once per dialect the classpath declares, so a build with no backend has nothing to render and the
  * call is a compile error rather than a silent fallback. Falling back is `.run`'s behavior, not `.runStatic`'s, which is the distinction this
  * message has to make actionable. `kyo-sql` declares no dialect and the engine modules depend on it rather than the reverse, so this module's
  * own test classpath is the only one with zero `META-INF/services/kyo.SqlDialect` entries and the only place the branch occurs. The
  * multi-dialect counterpart is `SqlStaticMacroMultiDialectTest` in `kyo-sql-tests`, the one module whose classpath carries both.
  *
  * The probe query is deliberately foldable. `SqlStaticMacro.impl` checks the lift BEFORE it reads the classpath, so a statement that cannot
  * be folded reports the fold failure and never reaches the dialect check; asserting on the empty-classpath message would then pass or fail
  * for reasons that have nothing to do with the classpath.
  */
class SqlStaticMacroNoDialectTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema

    "a foldable .runStatic does not compile when the classpath carries no dialect" in {
        val errors = typeCheckErrors(
            """def probe(using Frame): Chunk[String] < (Abort[SqlException] & DB) =
    Sql.from[Person]("p").select(c => c.p.name).runStatic"""
        )
        val message = errors.map(_.message).mkString("\n")
        assert(
            message.contains("no kyo-sql backend on the compile classpath"),
            s"the error must report the empty classpath rather than a fold failure, got: $message"
        )
        assert(
            message.contains("Add the kyo-sql backend artifact for the engine you are targeting"),
            s"the error must say what to add, got: $message"
        )
        assert(
            !message.contains("postgres") && !message.contains("mysql"),
            s"a core diagnostic must name no engine, got: $message"
        )
    }

    // With no dialect the fold never succeeds, so `.run` splices the caller's own query tree into its `.runDynamic`
    // fallback. That tree has to pass -Xcheck-macros for every operator a query can carry, including the text
    // operators whose bodies inline a cast.
    case class Scored(id: Long, v: Maybe[Int]) derives SqlSchema

    "a .run falling back to .runDynamic compiles for the operators that inline a cast" - {
        "like" in typeCheck("""def probe(using Frame) = Sql.from[Person]("p").where(_.p.name.like("a%")).run""")
        "like a term" in typeCheck("""def probe(using Frame) = Sql.from[Person]("p").where(c => c.p.name.like(c.p.name)).run""")
        "notLike" in typeCheck("""def probe(using Frame) = Sql.from[Person]("p").where(_.p.name.notLike("a%")).run""")
        "ilike" in typeCheck("""def probe(using Frame) = Sql.from[Person]("p").where(_.p.name.ilike("a%")).run""")
        "notIlike" in typeCheck("""def probe(using Frame) = Sql.from[Person]("p").where(_.p.name.notIlike("a%")).run""")
        "++ a value" in typeCheck("""def probe(using Frame) = Sql.from[Person]("p").select(c => c.p.name ++ "!").run""")
        "++ a column" in typeCheck("""def probe(using Frame) = Sql.from[Person]("p").select(c => c.p.name ++ c.p.name).run""")
        "substring" in typeCheck("""def probe(using Frame) = Sql.from[Person]("p").select(c => c.p.name.substring(2, 3)).run""")
        "+ a value on a nullable column" in typeCheck("""def probe(using Frame) = Sql.from[Scored]("s").select(c => c.s.v + 1).run""")
        "- a value on a nullable column" in typeCheck("""def probe(using Frame) = Sql.from[Scored]("s").select(c => c.s.v - 1).run""")
        "* a value on a nullable column" in typeCheck("""def probe(using Frame) = Sql.from[Scored]("s").select(c => c.s.v * 2).run""")
    }

end SqlStaticMacroNoDialectTest
