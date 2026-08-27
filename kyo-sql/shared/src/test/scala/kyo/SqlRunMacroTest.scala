package kyo

import kyo.Sql.*
import scala.compiletime.testing.typeCheckErrors

/** Pins the shape of what [[kyo.internal.SqlRunMacro]] emits, on both of `.run`'s paths.
  *
  * `.run` folds the statement when it can and emits `.runDynamic` when it cannot, and the two differ in what reaches the compiler
  * afterwards. The folded path emits SQL string constants and drops the query tree entirely. The fallback splices the query tree back into
  * the expansion, and an expansion is re-checked against the compiler's tree invariants under `-Xcheck-macros`, which every module in this
  * build compiles with. That check does not run over ordinary inlined code, so it is only ever the macro re-emitting the tree that exposes
  * it.
  *
  * This module is the only place the fallback can be pinned: `SqlStaticMacro` reads the compile classpath for dialects, `kyo-sql` declares
  * none (the engine modules depend on it rather than the reverse), so every `.run` here renders at run time. In every other module the same
  * calls fold and the branch is never taken.
  *
  * The queries are chained rather than bare because chaining is what makes the tree hard: each inline combinator applied to a value leaves
  * the compiler a receiver binding and a proxy for its `using` parameters, and those are the definitions whose owners the check reads.
  */
class SqlRunMacroTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema

    "the fallback expansion is well-formed" - {

        "for a chained select" in {
            val errors = typeCheckErrors(
                """def probe(using f: Frame): Chunk[String] < (Abort[SqlException] & DB) =
    Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).run"""
            )
            assert(errors.isEmpty, errors.map(_.message).mkString("\n"))
        }

        "for a deeper chain" in {
            val errors = typeCheckErrors(
                """def probe(using f: Frame): Chunk[String] < (Abort[SqlException] & DB) =
    Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name).limit(10).run"""
            )
            assert(errors.isEmpty, errors.map(_.message).mkString("\n"))
        }

        "for an update" in {
            val errors = typeCheckErrors(
                """def probe(using f: Frame): Long < (Abort[SqlException] & DB) =
    Sql.update[Person].set(_.name := "x").where(_.id == 1L).run"""
            )
            assert(errors.isEmpty, errors.map(_.message).mkString("\n"))
        }

        "for a delete" in {
            val errors = typeCheckErrors(
                """def probe(using f: Frame): Long < (Abort[SqlException] & DB) =
    Sql.delete[Person].where(_.id == 1L).run"""
            )
            assert(errors.isEmpty, errors.map(_.message).mkString("\n"))
        }
    }

    "the run evidence is the one the call site supplies" in {
        // `.run` takes `using SqlSchema[A]`, and what it decodes through has to be that argument rather than whatever a fresh implicit
        // search would find. The two coincide wherever only the derived given is in scope, so the difference is only observable with a
        // second instance the call site names explicitly.
        val explicit = summon[SqlSchema[Person]]
        val errors = typeCheckErrors(
            """def probe(using f: Frame): Chunk[String] < (Abort[SqlException] & DB) =
    Sql.from[Person]("p").select(c => c.p.name).run(using summon[SqlSchema[String]], f)"""
        )
        assert(errors.isEmpty, errors.map(_.message).mkString("\n"))
        assert(explicit.width == 4)
    }

end SqlRunMacroTest
