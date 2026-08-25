package kyo

import kyo.Sql.*
import kyo.internal.postgres.PostgresDialect
import scala.compiletime.testing.typeCheckErrors

/** Verifies that `.runStatic` folds a statement at compile time, and that `.run` falls back when it cannot.
  *
  * The fold is done by [[kyo.internal.SqlStaticMacro]], which reads every backend on the compile classpath out of
  * `META-INF/services/kyo.db.Backend`, reaches each one's dialect, renders the lifted AST through each during expansion, and splices the
  * resulting SQL strings as constants.
  *
  * The `.runStatic` scenarios assert the observable half of that by compiling: the macro aborts when it cannot fold, so a call site that
  * compiles is a call site that folded, and a call site that must NOT fold is asserted with `typeCheckErrors` against the macro's own
  * diagnostic.
  *
  * The `.run` scenarios cannot work that way and must not pretend to. `.run` takes the opportunistic entry, which answers `Absent` and
  * falls back to the runtime renderer silently, so a `.run` call site compiles whether or not the fold happened. Those scenarios therefore
  * carry a [[SqlStaticProbe]] render of the same statement and assert the SQL it produces. Leaf C carries the reasoning for why that is
  * evidence about `.run` and where its limit is.
  *
  * This module's compile classpath carries ONE dialect, so every scenario here asserts a property that holds for any single dialect: the
  * fold succeeds, or it fails with the macro's own diagnostic. The scenarios that assert what the fold does across SEVERAL dialects at once
  * cannot run here, because the assertion is about the SET of dialects the classpath carries; they live in `kyo-sql-tests` as
  * `SqlStaticMacroMultiDialectTest`, which is the only module that sees both.
  *
  * The SQL those splices contain is asserted separately, per dialect, by the parity scenarios in each dialect's render suite: they compare
  * [[SqlStaticProbe]]'s compile-time render against the same statement rendered at run time, which is the property that keeps `.run` and
  * `.runDynamic` returning the same results.
  */
class SqlRunStaticTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)
    case class User(id: Long, email: String)

    // ── Leaf H, a row whose field resolves through a PARAMETERIZED column given ─────────────────
    //
    // Most `SqlSchema.Column` givens are parameterless values; `maybe[A](using Column[A])` and
    // `option[A](using Column[A])` take an argument. That distinction is invisible in the DSL and decisive in
    // the lift, because `FromExprDerived.resolveStableGiven` recovers a given by reflecting on its owner
    // module and looks the member up by name AND argument count, so it reaches the parameterized givens as
    // well as the parameterless ones. A `Maybe` field resolves through a parameterized given, which a lookup
    // by name alone could not reach, so it would fail to lift and `.run` would fall back to the runtime
    // renderer in silence.
    enum RunStaticStatus derives SqlSchema.Column:
        case Pending, Paid

    case class HasEnum(id: Long, status: RunStaticStatus)

    case class HasMaybe(id: Long, note: Maybe[String])
    case class HasCount(id: Long, count: Maybe[Int])
    case class HasTuple(id: Long, pair: (Int, String))

    // The enum's Column given is derived in this very compilation unit, so it has no class file when the macro
    // expands; the fold must succeed anyway, through the render stand-in rather than instance resolution.
    "an enum bind folds at compile time, with its derivation in the module being compiled" in {
        def shape(using Frame): Chunk[HasEnum] < (Abort[SqlException] & DB) =
            Sql.from[HasEnum]("h").where(c => c.h.status == RunStaticStatus.Paid).runStatic
        val rendered = SqlStaticProbe.render(Sql.from[HasEnum]("h").where(c => c.h.status == RunStaticStatus.Paid))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "h"."id", "h"."status" FROM "hasenum" "h" WHERE ("h"."status" = $1)""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
        assert(rendered.params.size == 1, s"the enum bind must be spliced; got ${rendered.params}")
        assert(
            (rendered.params(0).value: Any).equals(RunStaticStatus.Paid),
            s"the spliced bind must carry the call site's own value; got ${rendered.params(0).value}"
        )
    }

    "the derived-alias spelling folds statically" in {
        def shape(using Frame): Chunk[Person] < (Abort[SqlException] & DB) =
            Sql.from[Person].where(c => c.person.age >= 18).runStatic
        val rendered = SqlStaticProbe.render(Sql.from[Person].where(c => c.person.age >= 18))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "person"."id", "person"."name", "person"."age", "person"."deptId" FROM "person" "person" WHERE ("person"."age" >= $1)""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
    }

    "a row with a Maybe field folds at compile time, through the parameterized `maybe` given" in {
        def shape(using Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
            Sql.insert[HasMaybe].values(HasMaybe(0L, Maybe("n"))).run
        val rendered = SqlStaticProbe.render(Sql.insert[HasMaybe].values(HasMaybe(0L, Maybe("n"))))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """INSERT INTO "hasmaybe" ("id", "note") VALUES ($1, $2) RETURNING "id"""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
        assert(rendered.params.size == 2, s"both columns must be bound; got ${rendered.params}")
    }

    // The BIND shape of the same seam. A `Maybe` FIELD reaches `maybe` through the row derivation, which
    // resolves the column givens at the derivation site; a `Maybe` BIND reaches it through the static lift,
    // which recovers the given by reflection at the query site instead. The two use different code, so a
    // regression can take either one alone, and only the bind path decides whether `.runStatic` compiles.
    "a Maybe bind folds at compile time, through the parameterized `maybe` given" in {
        def shape(using Frame): Chunk[HasCount] < (Abort[SqlException] & DB) =
            Sql.from[HasCount]("h").where(c => c.h.count == Maybe(1)).runStatic
        val rendered = SqlStaticProbe.render(Sql.from[HasCount]("h").where(c => c.h.count == Maybe(1)))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "h"."id", "h"."count" FROM "hascount" "h" WHERE ("h"."count" = $1)""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
        assert(rendered.params.size == 1, s"the Maybe bind must be spliced; got ${rendered.params}")
        assert(
            (rendered.params(0).value: Any).equals(Maybe(1)),
            s"the spliced bind must carry the call site's own value; got ${rendered.params(0).value}"
        )
    }

    // A tuple field is REFUSED, and which layer refuses it is the whole point of this leaf.
    //
    // A tuple occupies two SQL columns and an INSERT cell holds exactly one, so the refusal is now the
    // MACRO's, at the cell that cannot be bound, rather than the renderer's or the lift's. The diagnostic
    // names the offending field and the tier its type would have to reach, which is what makes the error
    // actionable without reading the derivation.
    //
    // The leaf asserts that diagnostic AND asserts the absence of the fold diagnostic. The second half is
    // what keeps it a guard on WHERE the refusal happens: a refusal reported as "cannot be folded" would send
    // a reader looking for a lift defect in a statement that is simply not bindable.
    "a tuple field is refused at the INSERT cell, naming the field, not reported as a lift failure" in {
        val errors  = typeCheckErrors("""kyo.SqlStaticProbe.render(kyo.Sql.insert[HasTuple].values(HasTuple(0L, (1, "a"))))""")
        val message = errors.map(_.message).mkString(" | ")
        assert(
            message.contains("'pair'") && message.contains("is not a single-column SQL type"),
            s"expected the cell's own refusal naming the field; got: $message"
        )
        assert(
            !message.contains("cannot be folded"),
            s"a non-bindable cell must be refused as such, not reported as a lift failure; got: $message"
        )
    }

    // ── Leaf A, runStatic on a fully-static select folds into a spliced Sql.Rendered ──

    "Query.runStatic on a static select compiles (the fold produced a Sql.Rendered splice)" in {
        // The macro renders once per classpath dialect at expansion time and emits an Expr[Sql.Rendered] whose
        // per-dialect SQL strings are constants. Compiling this call site is the assertion.
        def shape(using Frame): Chunk[String] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p").select(c => c.p.name).runStatic
        succeed
    }

    "Query.runStatic on a compound where+orderBy query compiles" in {
        def shape(using Frame): Chunk[Person] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p")
                .where(c => c.p.age >= 18)
                .orderBy(c => c.p.name.asc)
                .runStatic
        succeed
    }

    // ── Leaf B, runStatic on a window function compiles ──
    //
    // WindowSpec.Builder.partitionBy uses explicit `new WindowSpec.Builder(...)` constructors,
    // so the inlined call-site trees are plain constructor Apply nodes that the generic `FromExpr.derived`
    // product walker lifts at compile time. WindowSpec, WindowFrame, and FrameBound all derive that way
    // through the column-projection givens the lift site imports, so the macro folds window expressions with
    // no window-specific FromExpr given. Leaf B2 pins the framed case that exercises WindowFrame and FrameBound.

    "Query.runStatic on a window function compiles statically" in {
        def shape(using Frame): Chunk[Long] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p").select(c =>
                WindowFunction.RowNumber.over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.age.asc), Maybe.empty))
            ).runStatic
        succeed
    }

    // ── Leaf B2, the static fold lifts a window function WITH an explicit frame ───
    //
    // Leaf B uses `Maybe.empty` for the frame, which lifts as the empty case without ever deriving a
    // `WindowFrame`. This leaf pins the framed case: an explicit `WindowFrame` carrying `FrameBound`s forces
    // the compile-time lift through `WindowFrame` and `FrameBound`, so a regression in their static lifting
    // shows here rather than passing silently. `SqlStaticProbe.render` folds through the same aborting macro
    // entry `.runStatic` uses, so a frame that cannot lift fails this compile; the SQL assertion pins what it
    // folds to.

    "the static fold lifts a window function with an explicit frame, at compile time" in {
        val rendered = SqlStaticProbe.render(
            Sql.from[Person]("p").select(c =>
                c.p.age.sum.over(
                    WindowSpec(
                        Chunk(c.p.deptId),
                        Chunk(c.p.id.asc),
                        Maybe(WindowFrame(WindowFrame.Kind.Rows, FrameBound.UnboundedPreceding, Maybe(FrameBound.CurrentRow)))
                    )
                )
            )
        )
        assert(
            rendered.sqlFor(PostgresDialect.id).get.contains("ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"),
            s"the window frame did not fold to the expected SQL; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
    }

    // ── Leaf C, .run on a static query: the statement folds, and this is the SQL it folds to ─────
    //
    // WHY A `.run` LEAF NEEDS MORE THAN COMPILING, unlike the `.runStatic` leaves above. `.runStatic` aborts
    // the compile when it cannot fold, so compiling IS the assertion. `.run` takes the opportunistic entry,
    // which answers `Absent` and falls back to the runtime path without a word, so a `.run` call site
    // compiles whether the fold happened or not. A leaf that ended in a bare `succeed` would stay green even
    // with a completely broken fast path, so these assert on the SQL the fold produces instead.
    //
    // WHAT THE PROBE PROVES ABOUT `.run`, which is more than its two entry points suggest.
    // `SqlStaticProbe.render` calls `SqlStaticMacro.impl` (the aborting entry) while `.run` calls `tryImpl`
    // (the opportunistic one). They consult `opportunistic` in exactly three places, `renderOne`,
    // `renderLifted`'s empty-dialect branch, and `bindsAgree`, and each one runs the SAME predicate and lets
    // the flag choose only between aborting and answering `Absent`. The LIFT cannot differ either: both
    // entries call the same `liftAst(q)`, which takes no flag at all. So the probe rendering and `.run` folding
    // hold under identical conditions, in both directions: if a probe leaf compiles then the statement folds
    // for `.run` too, and if the probe cannot render then `.run` falls back. That is what makes the Update
    // leaf below able to assert a fallback positively rather than by omission.
    //
    // WHAT IT DOES NOT PROVE is that the `.run` call beside it is still the same statement. The probe reads
    // the expression written inside its own call, so the two copies are tied together by nothing except this
    // comment. It is the STATIC-SQL-INLINE-ONLY convention the dialect render suites already carry: keep both
    // copies identical when editing, or the leaf quietly stops being evidence about `.run`.

    "Query.run's statement folds at compile time, to this SQL and no binds" in {
        def shape(using Frame): Chunk[Person] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p").run
        val rendered = SqlStaticProbe.render(Sql.from[Person]("p"))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p"""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
        assert(rendered.params.isEmpty, s"a query with no bind must emit no params; got ${rendered.params}")
    }

    // ── Leaf D, Insert.runStatic compiles for a static AST ───────────────────────

    "Insert.runStatic compiles for a static AST" in {
        def shape(using Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
            Sql.insert[User].values(User(0L, "ada@example.com")).runStatic
        succeed
    }

    // ── Leaf E, Update.runStatic fails at compile time (sets lambda not liftable) ─
    //
    // `Update.Builder.set(inline specs: ...)` applies each spec lambda via `specs.map(_(columns))`
    // which produces a runtime `Chunk`, not reducible by `FromExpr.derived`. `runStatic` calls
    // report.errorAndAbort when the AST is not liftable.

    "Update.runStatic fails at compile time (set lambda is not statically liftable)" in {
        val errors = typeCheckErrors(
            """def shape(using kyo.Frame): Long < (kyo.Async & kyo.Abort[kyo.SqlException] & kyo.DB) =
  kyo.Sql.update[User].set(_.email := "new@example.com").where(_.id == 1L).runStatic"""
        )
        // The assertion names the diagnostic rather than counting errors, because the macro must carry the
        // `.runStatic` / `cannot be folded` message from SqlStaticMacro.impl's report.errorAndAbort and not
        // silently fail with an unrelated one. The exact wording is in SqlStaticMacro.scala's .impl error
        // branches (".runStatic: query cannot be folded at compile time ..."). An empty error list makes
        // mkString "" and "".contains false, so this fails cleanly when nothing was rejected at all.
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains(".runStatic") || message.contains("cannot be folded"),
            s"expected the SqlStaticMacro.impl diagnostic in the error message; got: $message"
        )
    }

    // ── The emitted dispatch, at run time ────────────────────────────────────────
    //
    // Compiling a `.runStatic` call site proves the fold happened; this proves the tree it emitted runs. The emitted
    // dispatch reads the client from the DB effect the run supplies, so a statement with no client is a compile error
    // rather than a run-time failure (DBTest pins that); here the client's dialect is found in the spliced set, so the
    // statement is handed to the execute entry and fails on the dead port rather than on a missing dialect.

    "runStatic hands the statement for the client's own dialect to the execute path" in {
        val poolConfig: SqlConfig = SqlConfig.default.copy(minConnections = 0)
        Abort.run[SqlException](Scope.run {
            SqlClient.init("postgres://alice:secret@localhost:9999/mydb", poolConfig).flatMap { client =>
                DB.run(client)(Sql.from[Person]("p").select(c => c.p.name).runStatic)
            }
        }).map {
            case Result.Failure(_: SqlConnectionException) => succeed
            case Result.Failure(e: SqlStaticRenderMissingDialectException) =>
                fail(s"the client's dialect must be in the spliced set, got: ${e.getMessage}")
            case other => fail(s"expected the statement to reach the connection, got $other")
        }
    }

    // ── Leaf F, Delete.runStatic compiles for a static AST ───────────────────────

    "Delete.runStatic compiles for a static AST" in {
        def shape(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.delete[User].where(_.id == 1L).runStatic
        succeed
    }

    // ── Leaf G, .run on Insert, Update and Delete ────────────────────────────────
    // Insert and Delete fold; Update does not, because `set` applies its spec lambdas through
    // `specs.map(_(columns))` and produces a runtime Chunk. Each is asserted rather than assumed, by the
    // probe reasoning in Leaf C above. All three keep the STATIC-SQL-INLINE-ONLY duplication constraint.

    "Insert.run's statement folds at compile time, binding every column including the auto-key" in {
        def shape(using Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
            Sql.insert[User].values(User(0L, "x")).run
        val rendered = SqlStaticProbe.render(Sql.insert[User].values(User(0L, "x")))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """INSERT INTO "user" ("id", "email") VALUES ($1, $2) RETURNING "id"""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
        // Two, not one: `values(row)` sends the detected auto-key as well, per Sql.scala's insert scaladoc.
        assert(rendered.params.size == 2, s"both columns must be bound; got ${rendered.params}")
    }

    "Update.run falls back to runtime, and the set lambda is why" in {
        def shape(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.update[User].set(_.email := "y").where(_.id == 2L).run
        // A POSITIVE assertion of the fallback rather than the absence of one. The probe aborts on exactly
        // the predicates that make `.run` answer `Absent`, so a refusal here IS the evidence that `.run`
        // takes the runtime path. Asserting the diagnostic rather than an error count, as Leaf E does, so a
        // rejection for some unrelated reason cannot pass as this one.
        val errors = typeCheckErrors(
            """kyo.SqlStaticProbe.render(kyo.Sql.update[User].set(_.email := "y").where(_.id == 2L))"""
        )
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains(".runStatic") || message.contains("cannot be folded"),
            s"expected the SqlStaticMacro.impl diagnostic for a statement that cannot fold; got: $message"
        )
    }

    "Delete.run's statement folds at compile time, binding the predicate value" in {
        def shape(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.delete[User].where(_.id == 3L).run
        val rendered = SqlStaticProbe.render(Sql.delete[User].where(_.id == 3L))
        assert(
            rendered.sqlFor(PostgresDialect.id).get == """DELETE FROM "user" WHERE ("id" = $1)""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
        assert(rendered.params.size == 1, s"the predicate value must be bound; got ${rendered.params}")
    }

    // ── Leaf J, a bind sitting under a type-equality retype ──────────────────────
    //
    // `Term`'s boolean and string operators are gated by an `A =:= Boolean` / `A =:= String` evidence and retype
    // their receiver through `ev.substituteCo`, at ten sites in `Sql.scala`. When the receiver is a BIND rather
    // than a column, the term the lift receives for that position is the evidence application, and
    // `BindFromExpr.Walk.unwrap`'s `substituteCo` / `substituteContra` arm is what sees through it to the
    // `Literal(value, schema)` construction underneath. Without that arm the wrapper IS the term, `bindCarrier`
    // matches none of its three constructor spellings, no `BindHole` is built, and the lift answers `None` for the
    // whole statement. `ColumnFromExpr` and `RecordFromExpr` carry the same arm for the column and record shapes,
    // and both are exercised by the leaves above; this is the bind shape.
    //
    // WHY THE PROBE AND NOT THE `.run` CALL, for the reason Leaf C sets out at length: answering `None` makes
    // `.run` fall back to the runtime renderer without a word, so the `.run` line below compiles either way. The
    // probe calls the aborting entry, so a lift that stops seeing through the evidence stops this file compiling
    // with SqlStaticMacro's own `cannot be folded` diagnostic rather than leaving a green test over a dead fast
    // path. Same STATIC-SQL-INLINE-ONLY constraint as the leaves above: the two copies of the statement are tied
    // together by nothing but this comment, so edit them together.
    //
    // `&&` is the site chosen because it is `inline`, which is what puts the `Logical(...)` construction (and the
    // evidence application inside it) in the tree the lift walks. The `isTrue` / `isFalse` family apply the same
    // `substituteCo` but are plain methods, so their call sites never carry the construction at all.
    "a bind retyped through a type-equality evidence still folds, and this is the SQL it folds to" in {
        def shape(using Frame): Chunk[Person] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p").where(c => Sql.literal(true) && (c.p.age >= 18)).run
        val rendered = SqlStaticProbe.render(Sql.from[Person]("p").where(c => Sql.literal(true) && (c.p.age >= 18)))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ($1 AND ("p"."age" >= $2))""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
        assert(rendered.params.size == 2, s"both binds must be spliced; got ${rendered.params}")
        // The retyped bind is the one at placeholder 1, so this pins that the hole `unwrap` had to reach carried
        // the call site's own value into the params chunk, in the renderer's order. The `: Any` ascription is
        // `IdiomRenderTest`'s idiom for reading a `BoundValue[?]`, whose element type is erased at this point.
        assert(
            (rendered.params(0).value: Any).equals(true),
            s"the retyped bind must be the first spliced value; got ${rendered.params(0).value}"
        )
    }

    // ── Leaf I, .runStatic folds the casing, the table-name parameter and a rename to the resolved identifiers ──
    //
    // The three name channels reach the static render by different routes. The table name is a literal
    // parameter that lifts directly. A `@column` rename is read off the case-class field symbols at
    // macro time. The casing is an in-scope `SqlNaming` given, which the macro folds only when it can recover
    // it BY REFERENCE (`FromExprDerived.resolveStableGiven` reflects on the given's owner module), so the
    // casing leaves below declare it `inline`: the reference the macro then sees is `SqlNaming.SnakeCase`
    // itself, an enum case of an already-compiled module, rather than a given defined in this same
    // compilation unit, whose class does not exist yet when the macro runs.
    //
    // `SqlStaticProbe.render` calls the SAME aborting entry `.runStatic` does, which is what makes these
    // leaves a strict regression guard rather than a value assertion that could quietly start passing for the
    // wrong reason: if a channel ever stops folding, what is left in the tree is a runtime call the AST lift
    // cannot lift, so this file STOPS COMPILING with SqlStaticMacro's "cannot be folded ... for resolving the
    // table name / column name" diagnostic. A passing compile that also asserts the exact resolved
    // identifiers is therefore proof of the fold, not merely of its absence of failure.

    case class NamedThing(id: Long, firstName: String)

    // The casing governs the table name exactly as it governs the columns, so `NamedThing` folds to
    // `named_thing` under the snake casing. `PostgresDialectNamingRenderTest` pins the same rule on the
    // runtime render path.
    "runStatic folds an in-scope casing to the cased column names, at compile time" in {
        import kyo.SqlTestNaming.snake
        def shape(using Frame): Chunk[NamedThing] < (Abort[SqlException] & DB) =
            Sql.from[NamedThing]("n").runStatic
        val rendered = SqlStaticProbe.render(Sql.from[NamedThing]("n"))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "n"."id", "n"."first_name" FROM "named_thing" "n"""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
    }

    case class RenamedTable(id: Long, sku: String)

    "runStatic folds the table-name parameter to that table name, at compile time" in {
        def shape(using Frame): Chunk[RenamedTable] < (Abort[SqlException] & DB) =
            Sql.from[RenamedTable]("r", "items").runStatic
        val rendered = SqlStaticProbe.render(Sql.from[RenamedTable]("r", "items"))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "r"."id", "r"."sku" FROM "items" "r"""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
    }

    case class RenamedField(id: Long, @column("dept_code") deptId: Long)

    "runStatic folds a @column rename to the renamed column, at compile time" in {
        def shape(using Frame): Chunk[RenamedField] < (Abort[SqlException] & DB) =
            Sql.from[RenamedField]("f").runStatic
        val rendered = SqlStaticProbe.render(Sql.from[RenamedField]("f"))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "f"."id", "f"."dept_code" FROM "renamedfield" "f"""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
    }

    case class Catalog(id: Long, @column("name") productName: String, listPrice: BigDecimal)

    "runStatic folds the casing, the table-name parameter and a rename composed together, at compile time" in {
        import kyo.SqlTestNaming.snake
        def shape(using Frame): Chunk[Catalog] < (Abort[SqlException] & DB) =
            Sql.from[Catalog]("c", "catalog_items").runStatic
        val rendered = SqlStaticProbe.render(Sql.from[Catalog]("c", "catalog_items"))
        assert(
            rendered.sqlFor(PostgresDialect.id).get ==
                """SELECT "c"."id", "c"."name", "c"."list_price" FROM "catalog_items" "c"""",
            s"the compile-time render changed; got ${rendered.sqlFor(PostgresDialect.id)}"
        )
    }

    // The guard on a missing custom-lift import, hosted here rather than in kyo-sql's ColumnFromExprNegativeTest.
    //
    // A missing `kyo.internal.macros.BindFromExpr.given` import is invisible to an ordinary assertion: the derivation
    // answers `None` and `matched` reports `false`, the same answer it gives when the derivation correctly refuses a
    // tree, so the two are indistinguishable at the assertion level. The harness aborts on the custom-lift arm,
    // matching its production twin `deriveSqlCustomLift`, so this leaf fails to COMPILE without the import rather
    // than passing silently.
    //
    // WHY THIS MODULE. The leaf needs a scope where the given is genuinely absent rather than simulated, and
    // no kyo-sql-postgres test imports it. What this module adds is that `SqlLiftHarness` arrives over the
    // `test->test` edge as a CLASSPATH macro. Called
    // from inside a `typeCheckErrors` string in its own module it is a SAME-RUN macro, which suspends the
    // compilation unit under a forged inlining phase, makes the retry skip `ExtractAPI`, and leaves Zinc with
    // classfiles whose API was never registered. `SqlStaticProbe` above is the control: it lives beside
    // SqlLiftHarness in kyo-sql's test sources and is already called from inside a string here without
    // trouble.
    //
    // The title says "a custom-lift given" rather than naming `BindFromExpr`, and that is deliberate.
    // `Term[X]` is walked as a SUM, so the derivation asks for a `FromExpr` for every custom-lift child and
    // aborts on whichever one it reaches first without a given. Measured, that is `Sql.Column`, not
    // `Literal`. A title promising `BindFromExpr` specifically would claim a precision the assertion does not
    // have. The message names both imports by their full path, so the assertion below checks both exact
    // paths rather than the bare class name. A bare-name check would still pass even if the advice text
    // silently drifted to a stale package, which is exactly the risk `ColumnFromExpr` and `BindFromExpr`
    // moving to `kyo.internal.macros` creates: only a path-exact assertion forces the advice text to move
    // with them.
    "a missing custom-lift given fails the harness compile and names the imports" in {
        val errors = typeCheckErrors(
            """
            kyo.SqlLiftHarness.matched[kyo.Sql.Term[Int]](kyo.Sql.literal[Int](42))
            """
        )
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains("kyo.internal.macros.ColumnFromExpr.given") &&
                message.contains("kyo.internal.macros.BindFromExpr.given"),
            s"the compile error must name the exact import paths the user needs; got: $message"
        )
        // The negative half, and it is the assertion that makes this a regression guard rather than a
        // restatement. A derivation that answers `None` lets this block COMPILE with no errors at all, which an
        // assertion on the message alone cannot see. Requiring a non-empty error list is what pins refusal
        // rather than silence.
        assert(
            errors.nonEmpty,
            "a missing given must fail the compile; answering None is what made a real regression unreadable"
        )
    }

end SqlRunStaticTest
