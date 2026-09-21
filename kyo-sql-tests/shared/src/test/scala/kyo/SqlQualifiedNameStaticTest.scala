package kyo

import kyo.Sql.*
import scala.compiletime.testing.Error
import scala.compiletime.testing.typeCheckErrors

/** A schema-qualified statement must stay on the compile-time render path.
  *
  * A schema the macro cannot read while compiling has two ways to go wrong, and the second is the dangerous one: every qualified statement
  * drops off the static path, or the fold emits the unqualified name while the runtime fallback emits the qualified one, sending the two
  * terminals to different tables.
  *
  * Here rather than beside a dialect because folding is not per flavor: the macro renders once per backend on the compile classpath, and
  * this module is the one that sees them all. [[SqlStaticProbe]] hands back what that render produced for each of them, so the leaves
  * assert the folded TEXT rather than only that a fold happened; compiling alone would leave the second failure above undetected.
  *
  * The leaves that assert a REFUSAL compile a snippet instead, since the thing under test is a compile error. `Frame` cannot be derived
  * inside package `kyo` and those snippets compile in this file's package, so that diagnostic is filtered as a harness artifact rather than
  * a property under test.
  */
class SqlQualifiedNameStaticTest extends Test:

    private def folded(errs: List[Error]): Boolean =
        !errs.exists(_.message.contains("cannot be folded"))

    private def unexpected(errs: List[Error]): List[String] =
        errs.map(_.message)
            .filterNot(_.contains("Frame cannot be derived"))
            .filterNot(_.contains("cannot be folded"))

    /** `app`, one quote character, a period, the SAME quote character, `invoice`.
      *
      * The back-reference is what makes this flavor-agnostic: every dialect on the classpath quotes with a single character, and requiring
      * the two occurrences to match accepts `"app"."invoice"` and `` `app`.`invoice` `` while rejecting a period that merely sits between
      * the names.
      */
    private val qualifiedPair = """app(.)\.\1invoice""".r

    /** The dialects whose folded text does not carry the two names as separate identifiers, each with the text it produced.
      *
      * The fused spelling is checked as well as the split one, because `"app.invoice"` names a relation whose own name contains a period
      * and is what a render quoting the pair as a unit emits.
      */
    private def notQualified(rendered: Sql.Rendered): Seq[String] =
        rendered.perDialect.toSeq
            .filter((_, d) => qualifiedPair.findFirstIn(d.sql).isEmpty || d.sql.contains("app.invoice"))
            .map((id, d) => s"$id: ${d.sql}")

    /** Both halves of the property in one place: something folded, and what it folded to is qualified everywhere. */
    private def assertQualified(rendered: Sql.Rendered)(using Frame, kyo.test.AssertScope) =
        assert(rendered.perDialect.nonEmpty, "the fold must cover at least one dialect")
        assert(notQualified(rendered).isEmpty, notQualified(rendered).mkString(" | "))
    end assertQualified

    "a schema-qualified select folds to two identifiers in every flavor" in {
        assertQualified(SqlStaticProbe.render(
            Sql.from[SqlQualifiedNameStaticTest.Invoice](alias = "i", schemaName = "app", tableName = "invoice")
        ))
    }

    "a schema-qualified select folds through a where clause" in {
        assertQualified(SqlStaticProbe.render(
            Sql.from[SqlQualifiedNameStaticTest.Invoice](alias = "i", schemaName = "app", tableName = "invoice")
                .where(r => r.i.id > 1L)
        ))
    }

    "a schema-qualified insert folds to two identifiers in every flavor" in {
        assertQualified(SqlStaticProbe.render(
            Sql.insert[SqlQualifiedNameStaticTest.Invoice](schemaName = "app", tableName = "invoice")
                .values(SqlQualifiedNameStaticTest.Invoice(1L, BigDecimal(2)))
        ))
    }

    "a schema-qualified update folds to two identifiers in every flavor" in {
        assertQualified(SqlStaticProbe.render(
            Sql.update[SqlQualifiedNameStaticTest.Invoice](schemaName = "app", tableName = "invoice")
                .set(_.total := BigDecimal(1)).build
        ))
    }

    "a schema-qualified delete folds to two identifiers in every flavor" in {
        assertQualified(SqlStaticProbe.render(
            Sql.delete[SqlQualifiedNameStaticTest.Invoice](schemaName = "app", tableName = "invoice").build
        ))
    }

    "an inline-def source carrying a schema folds" in {
        // The documented say-it-once idiom. If this stops folding, writing the schema once costs static queries,
        // and if the schema does not survive the indirection the statement silently reaches another table.
        assertQualified(SqlStaticProbe.render(SqlQualifiedNameStaticTest.invoices))
    }

    "arguments named out of order still fold, and land in the right slots" in {
        // The three names are transposable when written positionally, which is why the API asks for them by name.
        // A fold reading them by position would render `"invoice"."app"`, still qualified and still a statement.
        assertQualified(SqlStaticProbe.render(
            Sql.from[SqlQualifiedNameStaticTest.Invoice](tableName = "invoice", schemaName = "app", alias = "i")
        ))
    }

    // The README tells callers a schema may be held in a `final val` or an `inline def` and still fold, which is
    // what lets one name serve a whole codebase. Both spellings are constants to the compiler; nothing else is.
    "a schema held in a final val folds" in {
        assertQualified(SqlStaticProbe.render(
            Sql.from[SqlQualifiedNameStaticTest.Invoice](
                alias = "i",
                schemaName = SqlQualifiedNameStaticTest.appSchema,
                tableName = "invoice"
            )
        ))
    }

    "a schema behind an inline def folds" in {
        assertQualified(SqlStaticProbe.render(
            Sql.from[SqlQualifiedNameStaticTest.Invoice](
                alias = "i",
                schemaName = SqlQualifiedNameStaticTest.inlineSchema,
                tableName = "invoice"
            )
        ))
    }

    "a runtime schema name is refused by runStatic rather than silently falling back" in {
        // Naming the schema at run time is legal on `.run` and `.runDynamic`; what must not happen is
        // `.runStatic` accepting it and folding something other than what runs.
        val errs = typeCheckErrors(
            """val s = scala.util.Random.nextString(3)
               kyo.Sql.from[kyo.SqlQualifiedNameStaticTest.Invoice](
                 alias = "i", schemaName = s, tableName = "invoice").runStatic"""
        )
        assert(!folded(errs))
    }

end SqlQualifiedNameStaticTest

object SqlQualifiedNameStaticTest:
    case class Invoice(id: Long, total: BigDecimal) derives SqlSchema

    /** The say-it-once idiom the README gives: the schema and table named at one place, reused at every call site. */
    transparent inline def invoices = Sql.from[Invoice](alias = "i", schemaName = "app", tableName = "invoice")

    final val appSchema     = "app"
    inline def inlineSchema = "app"
end SqlQualifiedNameStaticTest
