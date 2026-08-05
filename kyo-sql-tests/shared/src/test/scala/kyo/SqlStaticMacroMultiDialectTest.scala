package kyo
import kyo.Sql.*
import kyo.db.Idiom

/** Verifies what `.runStatic`'s compile-time fold does across SEVERAL dialects at once.
  *
  * [[kyo.internal.SqlStaticMacro]] reads every backend on the compile classpath out of `META-INF/services/kyo.db.Backend`, reaches each
  * one's dialect, renders the lifted AST through each during expansion, and splices the resulting SQL strings as constants. Every scenario
  * below asserts a property of the resulting SET: that it covers each dialect the classpath carries, that each entry records its own
  * capability floor, that the bind list is reconstructed once and shared, and that a construct the two engines spell differently gets each
  * engine's spelling.
  *
  * These live here rather than beside the single-dialect scenarios in `kyo-sql-postgres` because the assertion IS about the set. A module
  * carrying one backend sees one entry, so `perDialect.keySet == Set(postgres, mysql)` is unsatisfiable there for a reason that has nothing
  * to do with the macro. `kyo-sql-tests` is the only module whose compile classpath carries both backends' services entries, so it is the
  * only place the multi-dialect half of Mechanism A is observable at all.
  *
  * The counterpart single-dialect scenarios are `kyo.SqlRunStaticTest` in `kyo-sql-postgres`; each assertion lives in the module where its
  * premise holds.
  *
  * Named for [[kyo.internal.SqlStaticMacro]] rather than for `.runStatic`, because the multi-dialect fold is that macro's behavior and the
  * naming convention keys a test file to the source it covers.
  */
class SqlStaticMacroMultiDialectTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema

    // Mechanism A's whole point: the emitted splice covers every backend the compile classpath carries, so the
    // same call site runs against either engine without re-rendering. Both dialects ship in this build, so a
    // probe of the same statement carries both entries.
    "a static fold covers every dialect on the compile classpath" in {
        val rendered = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.name))
        assert(rendered.perDialect.keySet == Set(Idiom.Id("postgres"), Idiom.Id("mysql")))
        assert(rendered.onlySql == Absent, "a multi-dialect render has no single answer")
    }

    // A compile-time render knows no server version, so each entry records the dialect's capability floor. That
    // is what makes a spliced statement safe to send to any supported server of that flavor.
    "each spliced entry records its dialect's capability floor" in {
        val rendered = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.name))
        assert(rendered.perDialect(Idiom.Id("postgres")).version == Idiom.ServerVersion(11, 0, 0))
        assert(rendered.perDialect(Idiom.Id("mysql")).version == Idiom.ServerVersion(8, 0, 31))
    }

    // The bind list is reconstructed once and shared by every dialect, so the splice carries one chunk however
    // many flavors it rendered for.
    "a static fold reconstructs the bind list once for every dialect" in {
        val rendered = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18))
        assert(rendered.params.size == 1)
        val bound: Sql.BoundValue[?] = rendered.params.head
        bound.value match
            case value: Int => assert(value == 18)
            case other      => fail(s"expected the Int bind 18, got $other")
        assert(kyo.internal.SqlColumns.eqRef(bound.schema, summon[SqlSchema.Column[Int]]))
        assert(rendered.sqlFor(Idiom.Id("postgres")).get.contains("$1"))
        assert(rendered.sqlFor(Idiom.Id("mysql")).get.contains("?"))
    }

    // A division picks its AST node by a compile-time `summonFrom` on `SqlIntegral`, since PostgreSQL has to cast an
    // integral quotient and MySQL has to spell a truncated one `DIV`, and neither operand's Scala type survives to render
    // time. That reduction happens during inlining, so the spliced tree is an ordinary constructor call the lift can fold:
    // asserting the per-dialect SQL is what proves it, because a tree the macro could not fold would fall back to the
    // runtime renderer and produce no constants at all.
    "a static fold lifts both divisions and gives each dialect its own operator" in {
        val quotient = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.age / c.p.age))
        assert(quotient.sqlFor(Idiom.Id("postgres")).get.contains("""(CAST("p"."age" AS NUMERIC) / "p"."age")"""))
        assert(quotient.sqlFor(Idiom.Id("mysql")).get.contains("(`p`.`age` / `p`.`age`)"))
        val truncated = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.age.divideTruncating(c.p.age)))
        assert(truncated.sqlFor(Idiom.Id("postgres")).get.contains("""("p"."age" / "p"."age")"""))
        assert(truncated.sqlFor(Idiom.Id("mysql")).get.contains("(`p`.`age` DIV `p`.`age`)"))
    }

    "a static fold lifts the aggregate nodes, whose result type is no longer their operand's" in {
        val total = SqlStaticProbe.render(Sql.from[Person]("p").sum(_.p.age))
        assert(total.sqlFor(Idiom.Id("postgres")).get == """SELECT SUM("p"."age") FROM "person" "p"""")
        assert(total.sqlFor(Idiom.Id("mysql")).get == "SELECT SUM(`p`.`age`) FROM `person` `p`")
        val rolledUp = SqlStaticProbe.render(
            Sql.from[Person]("p").groupByRollup(c => c.p.deptId).select(v => (v.deptId, v.age.sum))
        )
        assert(rolledUp.sqlFor(Idiom.Id("postgres")).get.contains("""GROUP BY ROLLUP ("p"."deptId")"""))
        assert(rolledUp.sqlFor(Idiom.Id("mysql")).get.contains("GROUP BY `p`.`deptId` WITH ROLLUP"))
    }

    // --- Runtime binds fold. This is the design's worked example, and the acceptance test for it. ---
    //
    // A bind's value is not part of the SQL text, only of the parameter list, so a statement whose bind is a
    // runtime value has exactly as much statically-known text as one whose bind is a constant. The lift keeps the
    // call site's own expression in the value position instead of trying to reconstruct the value, which is what
    // makes these fold: a lift that had to reconstruct the value would refuse every non-constant bind.

    case class Contact(id: Long, name: String, email: Maybe[String]) derives SqlSchema

    "a bind that is a runtime val folds, and the val reaches the emitted params" in {
        val minAge = 18 + scala.util.Random.nextInt(1)
        val rendered = SqlStaticProbe.render(
            Sql.from[Person]("p").where(c => c.p.age >= minAge).select(c => c.p.id)
        )
        // Folded: both dialects produced constant SQL, each with its own placeholder spelling.
        assert(rendered.sqlFor(Idiom.Id("postgres")).get == """SELECT "p"."id" FROM "person" "p" WHERE ("p"."age" >= $1)""")
        assert(rendered.sqlFor(Idiom.Id("mysql")).get == "SELECT `p`.`id` FROM `person` `p` WHERE (`p`.`age` >= ?)")
        // The bind is the val's own value, evaluated at run time rather than reconstructed at splice time.
        assert(rendered.params.size == 1)
        rendered.params.head.value match
            case value: Int => assert(value == minAge)
            case other      => fail(s"expected the Int bind $minAge, got $other")
        end match
    }

    "a runtime bind of an arbitrary type folds too" in {
        // The point of the hole: the emitted code splices the caller's expression, so the bind's type needs no
        // lifting machinery at all. A `String` computed at run time could never have been re-lifted as a constant.
        val name = "ada".toUpperCase
        val rendered = SqlStaticProbe.render(
            Sql.from[Person]("p").where(c => c.p.name == name)
        )
        assert(rendered.sqlFor(Idiom.Id("postgres")).get.contains("""("p"."name" = $1)"""))
        rendered.params.head.value match
            case value: String => assert(value == "ADA")
            case other         => fail(s"expected the String bind ADA, got $other")
        end match
    }

    "an absence test folds and carries no bind" in {
        // `== Absent` builds a node that holds no value, so it renders identically whatever the row contains and
        // contributes nothing to the params chunk.
        val rendered = SqlStaticProbe.render(Sql.from[Contact]("c").where(x => x.c.email == Absent))
        assert(rendered.sqlFor(Idiom.Id("postgres")).get.endsWith("""WHERE ("c"."email" IS NULL)"""))
        assert(rendered.sqlFor(Idiom.Id("mysql")).get.endsWith("WHERE (`c`.`email` IS NULL)"))
        assert(rendered.params.isEmpty)
    }

    "a presence test folds to the complementary predicate" in {
        val rendered = SqlStaticProbe.render(Sql.from[Contact]("c").where(x => x.c.email != Absent))
        assert(rendered.sqlFor(Idiom.Id("postgres")).get.endsWith("""WHERE ("c"."email" IS NOT NULL)"""))
        assert(rendered.params.isEmpty)
    }

    "an INSERT of a runtime row folds, with every cell bound" in {
        val row      = Person(1L, "ada".toUpperCase, 34, 7L)
        val rendered = SqlStaticProbe.render(Sql.insert[Person].values(row))
        assert(rendered.params.size == 4)
        rendered.params(1).value match
            case value: String => assert(value == "ADA")
            case other         => fail(s"expected the String cell ADA, got $other")
        end match
    }

    // --- The bind's evidence is resolved at splice time, including the parameterised givens ---
    //
    // A bind carries its own `SqlSchema.Column`, and the lift resolves that reference by reflection. Every leaf
    // above binds through a NAMED top-level given (`int`, `string`), which resolves as a bare reference. A
    // `Maybe[A]` bind does not: it resolves through `maybe[A](using inner: Column[A])`, a given that takes an
    // argument, so the reconstruction has to carry the argument too.
    //
    // The failure this pins is silent by construction. A lift that could not resolve the given answers `None`,
    // which sends the call site to the runtime renderer, and the query still returns the right rows: every
    // result-only assertion in this suite would still pass while the fold was gone. The probe below renders at
    // COMPILE time, so a lost fold is a compile error here rather than a slower query in production.

    "a Maybe bind folds, through the parameterised column given" in {
        val limit: Maybe[Int] = Maybe(18)
        val rendered          = SqlStaticProbe.render(sql"SELECT n FROM cte WHERE n < $limit")
        assert(rendered.sqlFor(Idiom.Id("postgres")).get == "SELECT n FROM cte WHERE n < $1")
        assert(rendered.sqlFor(Idiom.Id("mysql")).get == "SELECT n FROM cte WHERE n < ?")
        assert(rendered.params.size == 1)
        given CanEqual[Any, Maybe[Int]] = CanEqual.derived
        assert(
            (rendered.params.head.value: Any) == Maybe(18),
            s"the hole must carry the Maybe itself, got ${rendered.params.head.value}"
        )
    }

    // The same evidence at the call site a user writes. `.runStatic` requires the fold, so it is a compile error
    // when the AST does not reduce, and this definition existing is the assertion.
    "a Maybe bind folds under .runStatic" in {
        val wanted: Maybe[String] = Maybe("ada@example.com")
        def shape(using Frame): Chunk[Long] < (Abort[SqlException] & DB) =
            Sql.from[Contact]("c").where(x => x.c.email == wanted).select(x => x.c.id).runStatic
        succeed
    }

    // The same statement through the probe, so the `.runStatic` leaf above is known to have folded rather than
    // merely compiled: the SQL below is a compile-time constant, and a `Maybe` the lift could not resolve would
    // have produced no constant at all.
    "the same Maybe bind folds to one placeholder in a Query" in {
        val wanted: Maybe[String] = Maybe("ada@example.com")
        val rendered = SqlStaticProbe.render(
            Sql.from[Contact]("c").where(x => x.c.email == wanted).select(x => x.c.id)
        )
        assert(rendered.sqlFor(Idiom.Id("postgres")).get == """SELECT "c"."id" FROM "contact" "c" WHERE ("c"."email" = $1)""")
        assert(rendered.params.size == 1)
        given CanEqual[Any, Maybe[String]] = CanEqual.derived
        assert((rendered.params.head.value: Any) == Maybe("ada@example.com"))
    }

end SqlStaticMacroMultiDialectTest
