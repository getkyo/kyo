package kyo

import kyo.db.Idiom
import kyo.internal.SqlColumns

/** Verifies [[Sql.Rendered]], the shape every render produces.
  *
  * The values here are built directly rather than rendered, because the subject is the container and not the SQL: a single-dialect render
  * fills one entry, the static-render macro fills one per backend on the compile classpath, and the readings have to behave for both. The
  * dialect ids and versions are written as literals for the same reason, so this suite names no backend.
  */
class IdiomRenderedTest extends Test:

    private val postgres = Idiom.Id("postgres")
    private val mysql    = Idiom.Id("mysql")
    private val sqlite   = Idiom.Id("sqlite")

    private val pgVersion = Idiom.ServerVersion(11, 0, 0)
    private val myVersion = Idiom.ServerVersion(8, 0, 31)

    private val pgEntry = Sql.Rendered.Dialected("""SELECT "id" FROM "person" WHERE ("age" >= $1)""", pgVersion)
    private val myEntry = Sql.Rendered.Dialected("SELECT `id` FROM `person` WHERE (`age` >= ?)", myVersion)

    private val bind: Chunk[Sql.BoundValue[?]] = Chunk(Sql.BoundValue(18, summon[SqlSchema.Column[Int]], "Int"))

    private val singleDialect = Sql.Rendered(Map(postgres -> pgEntry), bind)
    private val bothDialects  = Sql.Rendered(Map(postgres -> pgEntry, mysql -> myEntry), bind)

    "sqlFor returns the text rendered for a dialect the render covered" in {
        assert(bothDialects.sqlFor(postgres) == Present("""SELECT "id" FROM "person" WHERE ("age" >= $1)"""))
        assert(bothDialects.sqlFor(mysql) == Present("SELECT `id` FROM `person` WHERE (`age` >= ?)"))
    }

    "sqlFor is Absent for a dialect the render did not cover" in {
        assert(bothDialects.sqlFor(sqlite) == Absent)
        assert(singleDialect.sqlFor(mysql) == Absent)
    }

    "onlySql reads the text of a single-dialect render without naming its dialect" in {
        assert(singleDialect.onlySql == Present("""SELECT "id" FROM "person" WHERE ("age" >= $1)"""))
    }

    // A multi-dialect render has no single answer, and returning either entry would be picking one arbitrarily.
    "onlySql is Absent when the render covers more than one dialect" in {
        assert(bothDialects.onlySql == Absent)
    }

    "onlySql is Absent when the render covers no dialect at all" in {
        assert(Sql.Rendered(Map.empty, Chunk.empty).onlySql == Absent)
    }

    // The bind list does not vary by flavor, so it sits beside the map rather than once per entry: the two
    // dialects above disagree on placeholder syntax and quoting, and agree on binding 18 at position 1.
    "the bind list is shared across every dialect the render covers" in {
        assert(bothDialects.params.size == 1)
        val bound: Sql.BoundValue[?] = bothDialects.params.head
        bound.value match
            case v: Int => assert(v == 18)
            case other  => fail(s"expected the Int bind 18, got $other")
        assert(SqlColumns.eqRef(bound.schema, summon[SqlSchema.Column[Int]]))
    }

    // The version an entry records is what distinguishes two renders of the same statement in the same flavor:
    // a static render targets the dialect's capability floor, a client render targets the server it handshook with.
    "each entry records the server version its text was rendered against" in {
        assert(bothDialects.perDialect(postgres).version == pgVersion)
        assert(bothDialects.perDialect(mysql).version == myVersion)
    }

    "sqlForOrFail hands back the text when the dialect is covered" in {
        assert(Abort.run(bothDialects.sqlForOrFail(mysql)).eval == Result.Success("SELECT `id` FROM `person` WHERE (`age` >= ?)"))
    }

    // The static path fixes its rendered set when the call site compiles, so a client speaking anything else is a
    // deployment mismatch. It names both sides rather than falling back to another flavor's SQL.
    "sqlForOrFail fails naming the dialect asked for and the ones covered" in {
        Abort.run[SqlException](singleDialect.sqlForOrFail(mysql)).eval match
            case Result.Failure(e: SqlStaticRenderMissingDialectException) =>
                assert(e.dialect == mysql)
                assert(e.available == Chunk(postgres))
            case other => fail(s"expected a missing-dialect failure, got $other")
    }

    // A per-backend render spelling would have to name the concrete backend dialects to delegate to, which is
    // exactly the coupling core is free of, so neither spelling exists.
    "the two per-backend render spellings no longer exist" in {
        typeCheckFailure("Sql.from[IdiomRenderedPerson](\"p\").renderPostgres")
        typeCheckFailure("Sql.from[IdiomRenderedPerson](\"p\").renderMysql")
    }

end IdiomRenderedTest

/** Row type for the per-backend-spelling scenario, top-level so the `typeCheckFailure` snippets can name it. */
case class IdiomRenderedPerson(id: Long, age: Int) derives SqlSchema
