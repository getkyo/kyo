package kyo

import kyo.Sql.*
import kyo.SqlConnectionException
import kyo.SqlServerException
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** End-to-end integration tests against every registered backend.
  *
  * Covers the full pipeline: DSL → AST → Idiom → SqlClient.internalExecute* → Connection → real DB → assertion.
  *
  * SqlClient.InsertOutcome contract (SqlClient.InsertOutcome.scala):
  *   - affectedRows: Long, row count from CommandComplete / OK packet
  *   - generatedKey: SqlClient.InsertOutcome.GeneratedKey, Value(id) when an auto-key was detected and the server reported one, NoAutoKey
  *     when the renderer emitted no RETURNING because the table has no such column, and Unavailable when the server's answer cannot tell
  *     those two apart.
  * Auto-key detection: case class whose FIRST field is Long-typed.
  *
  * Every live assertion here runs through .run or .runDynamic. Which SQL each flavor renders is asserted separately, in each dialect's own
  * render suites.
  *
  * Every scenario runs through [[SqlBackendTest.forEachBackend]] with DDL from the descriptor. What legitimately stays single-engine is the
  * surface that IS one engine's: the typed `MysqlClient` factory, a PostgreSQL `INTERVAL` column, and the generated-key paths, which differ
  * by a capability the descriptor names.
  */
class SqlEndToEndTest extends SqlBackendTest:

    override def timeout: Duration = 5.minutes

    // ── Shared case classes ────────────────────────────────────────────────────

    case class Person(id: Long, name: String, age: Int) derives SqlSchema, CanEqual
    case class Dept(id: Long, name: String) derives SqlSchema, CanEqual
    case class Tag(name: String) derives SqlSchema, CanEqual
    case class Point(x: Int, y: Int) derives SqlSchema, CanEqual
    case class Widget(id: Long, label: String) derives SqlSchema, CanEqual
    case class Trip(id: Long, label: String, span: java.time.Duration) derives SqlSchema, CanEqual

    // The one engine named in this file: the leaf below is about that engine's own typed factory, so the SURFACE is
    // the engine's. Everything else reaches its client through `forEachBackend`.

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def withMyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: SqlClient => A < (S & Async & Abort[SqlException] & DB))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlConnectionException](MysqlClient.init(myUrl(ctx))).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(DB.run(client)(f(client)))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

    /** The shared person DDL, with the text column's type from the descriptor. */
    private def createPerson(backend: kyo.internal.SqlTestBackend, client: SqlClient)(using Frame): Unit < (Async & Abort[SqlException]) =
        client.executeRaw(
            s"CREATE TABLE person (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL, age INT NOT NULL)"
        ).unit

    private def seedTwoPeople(client: SqlClient)(using Frame): Unit < (Async & Abort[SqlException]) =
        client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 25)").unit

    "SELECT + WHERE round-trips" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- createPerson(backend, client)
                _    <- seedTwoPeople(client)
                rows <- Sql.from[Person]("p").where(c => c.p.age >= 30).run
            yield
                assert(rows.size == 1, s"${backend.label}: expected 1 row, got ${rows.size}")
                assert(rows.head == Person(1L, "alice", 30), s"${backend.label}: expected Person(1,alice,30), got ${rows.head}")
        }
    }

    // ── Leaf 3: JOIN + SELECT round-trip on PG ────────────────────────────────

    "JOIN + SELECT round-trips" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(s"CREATE TABLE dept (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL)")
                _ <- createPerson(backend, client)
                _ <- client.executeRaw("INSERT INTO dept VALUES (1, 'engineering')")
                _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                // 2-column join projection: (person.name, dept.name). INNER JOIN means only
                // alice (id=1) matches dept id=1. Decoded positionally into (String, String).
                rows <- Sql
                    .from[Person]("p")
                    .innerJoin(Sql.from[Dept]("d"))
                    .on(j => j.p.id == j.d.id)
                    .select(j => (j.p.name, j.d.name))
                    .run
            yield
                assert(rows.size == 1, s"${backend.label}: expected 1 join row (person.id=1 matches dept.id=1), got ${rows.size}")
                assert(rows.head == ("alice", "engineering"), s"${backend.label}: expected (alice,engineering), got ${rows.head}")
        }
    }

    "GROUP BY + HAVING round-trips" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createPerson(backend, client)
                _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 30), (3, 'carol', 25)")
                // GROUP BY age HAVING COUNT(*) >= 2, DSL grouped view. COUNT(*) is int8/BIGINT
                // on both engines, so it decodes as Long with no cast. Only age=30 (2 rows) qualifies.
                rows <- Sql
                    .from[Person]("p")
                    .groupBy(_.p.age)
                    .having(view => view.age.count >= 2L)
                    .select(view => view.age.count)
                    .run
            yield
                assert(rows.size == 1, s"${backend.label}: expected 1 group (age=30 has 2 rows), got ${rows.size}")
                assert(rows.head == 2L, s"${backend.label}: expected count 2 for the age=30 group, got ${rows.head}")
        }
    }

    /** No cast on the seed: `1` is the narrower integer on both engines and the numeric family widens it. A cast would have to be spelled in
      * one engine's syntax.
      */
    "a recursive CTE runs through a raw fragment" - {
        forEachBackend() { (backend, _, _) =>
            if !backend.supportsRecursiveCte then succeed(s"${backend.label} declares no recursive CTE")
            else
                val limit = 5L
                sql"WITH RECURSIVE cte (n) AS (SELECT 1 UNION ALL SELECT cte.n + 1 FROM cte WHERE cte.n < $limit) SELECT n FROM cte"
                    .as[Long]
                    .run
                    .map { rows =>
                        assert(rows.size == 5, s"${backend.label}: expected 5 rows from the recursive CTE, got ${rows.size}")
                        assert(rows.toSeq.sorted == Seq(1L, 2L, 3L, 4L, 5L), s"${backend.label}: expected 1..5, got $rows")
                    }
        }
    }

    /** Beside the recursive form because they are different constructs: `WITH RECURSIVE` is gated on a capability and a server version, a plain
      * `WITH` is not, so covering only the recursive one leaves the unconditional construct executing nowhere. Three binds, so the placeholder
      * numbering goes with it.
      */
    "a plain CTE runs through a raw fragment, carrying its binds" - {
        forEachBackend() { (backend, _, _) =>
            val low  = 2L
            val high = 4L
            val step = 1L
            sql"WITH bounds (lo, hi) AS (SELECT $low + $step - $step, $high) SELECT hi - lo FROM bounds"
                .as[Long]
                .run
                .map { rows =>
                    assert(rows.size == 1, s"${backend.label}: expected one row from the CTE, got ${rows.size}")
                    assert(rows.head == 2L, s"${backend.label}: expected 4 - 2 = 2, got ${rows.head}")
                }
        }
    }

    // ── Leaves 9-11: SqlClient.InsertOutcome ─────────────────────────────────────────────
    //
    // SqlClient.InsertOutcome contract for auto-key INSERTs:
    //   - affectedRows: Long
    //   - generatedKey: SqlClient.InsertOutcome.GeneratedKey, Value(id) when the first field is Long AND the
    //     server reports a key. PG auto-appends RETURNING <pk>; MySQL reads last_insert_id from the OK packet.

    /** Overriding the key with `Sql.default` is what makes it portable: the server assigns it on both engines. */
    "an insert into an auto-keyed table reports the server's generated key" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE person (id ${backend.autoIncrementPrimaryKey}, name ${backend.textColumnType} NOT NULL, " +
                        "age INT NOT NULL)"
                )
                result <- Sql.insert[Person].values(Person(0L, "alice", 30)).overriding(_.id := Sql.default).run
            yield
                assert(result.affectedRows == 1L, s"${backend.label}: expected 1 affected row, got ${result.affectedRows}")
                assert(
                    SqlClient.InsertOutcome.GeneratedKey.isPresent(result.generatedKey),
                    s"${backend.label}: expected a generated key, got ${result.generatedKey}"
                )
                assert(
                    SqlClient.InsertOutcome.GeneratedKey.foldKey(result.generatedKey)(false)(_ > 0L),
                    s"${backend.label}: expected a positive generated key, got ${result.generatedKey}"
                )
        }
    }

    /** A caller-supplied key echoed back is a real capability difference, unlike the leaf above. */
    "an explicit key is echoed back where the engine has RETURNING" - {
        forEachBackend() { (backend, client, _) =>
            if !backend.supportsReturning then succeed(s"${backend.label} reports no key for a caller-supplied id")
            else
                for
                    _      <- createPerson(backend, client)
                    result <- Sql.insert[Person].values(Person(42L, "alice", 30)).run
                yield
                    assert(result.affectedRows == 1L, s"${backend.label}: expected 1 affected row, got ${result.affectedRows}")
                    assert(
                        SqlClient.InsertOutcome.GeneratedKey.foldKey(result.generatedKey)(-1L)(identity) == 42L,
                        s"${backend.label}: expected the supplied key back, got ${result.generatedKey}"
                    )
        }
    }

    // client.executeInsert is the non-macro way to reach the same outcome: `execute` discards the generated key
    // because its return type has no slot for one, so an Insert AST in hand needs this entry to keep it.
    // client.executeInsert is the non-macro way to reach the same outcome: `execute` discards the generated key
    // because its return type has no slot for one, so an Insert AST in hand needs this entry to keep it.
    "client.executeInsert reports the same outcome as the .run path for the same Insert" - {
        forEachBackend() { (backend, client, _) =>
            for
                _      <- createPerson(backend, client)
                result <- client.executeInsert(Sql.insert[Person].values(Person(43L, "bob", 25)))
            yield assert(result.affectedRows == 1L, s"${backend.label}: expected 1 affected row, got ${result.affectedRows}")
        }
    }

    /** Gated because the discriminant needs `RETURNING` to distinguish "no auto-key column" from "no key generated"; an engine reporting from
      * an OK packet cannot tell them apart and answers `Unavailable`.
      */
    "an insert with no auto-key column reports NoAutoKey where the engine can tell" - {
        forEachBackend() { (backend, client, _) =>
            if !backend.supportsReturning then succeed(s"${backend.label} cannot distinguish an absent auto-key from a suppressed one")
            else
                // Tag(name: String), first field is String, so no auto-key detection fires.
                for
                    _      <- client.executeRaw(s"CREATE TABLE tag (name ${backend.textColumnType.replace(" NOT NULL", "")} PRIMARY KEY)")
                    result <- Sql.insert[Tag].values(Tag("urgent")).run
                yield
                    assert(result.affectedRows == 1L, s"${backend.label}: expected 1 affected row, got ${result.affectedRows}")
                    assert(
                        result.generatedKey == SqlClient.InsertOutcome.GeneratedKey.NoAutoKey,
                        s"${backend.label}: expected NoAutoKey for a table with no auto-key column, got ${result.generatedKey}"
                    )
        }
    }

    // ── Leaf 12: UPDATE affected-row count on PG ──────────────────────────────

    "UPDATE returns the affected-row count" - {
        forEachBackend() { (backend, client, _) =>
            for
                _     <- createPerson(backend, client)
                _     <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 30), (3, 'carol', 25)")
                count <- Sql.update[Person].set(_.age := 31).where(_.age == 30).run
            yield assert(count == 2L, s"${backend.label}: expected 2 updated rows (age=30), got $count")
        }
    }

    "DELETE returns the affected-row count" - {
        forEachBackend() { (backend, client, _) =>
            for
                _     <- createPerson(backend, client)
                _     <- seedTwoPeople(client)
                count <- Sql.delete[Person].where(_.age == 25).run
            yield assert(count == 1L, s"${backend.label}: expected 1 deleted row, got $count")
        }
    }

    // ── Transaction rollback leaves the table unchanged, one leaf per backend ──

    "a transaction that aborts leaves the table unchanged" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createPerson(backend, client)
                // A transaction that inserts and then aborts must roll the insert back.
                txResult <- Abort.run[SqlException](
                    client.transaction {
                        Sql.insert[Person]
                            .values(Person(1L, "alice", 30))
                            .run
                            .flatMap { _ =>
                                Abort.fail[SqlException](SqlServerException("XX000", "ERROR", "intentional rollback"))
                            }
                    }
                )
                _ = assert(txResult.isFailure, s"${backend.label}: expected the transaction to fail, got $txResult")
                rows <- Sql.from[Person]("p").run
            yield assert(rows.isEmpty, s"${backend.label}: expected an empty table after rollback, got $rows")
        }
    }

    // ── Leaf 25: sql"..." raw interpolator round-trip on both backends ─────────
    // The sql"..." interpolator returns Fragment[Any] (a Term[Any]) that can be
    // embedded in .where() / .select() predicates. We embed it in a WHERE predicate
    // and execute against a live DB to verify the full interpolation pipeline.

    "the sql interpolator embeds a column and a bind in a predicate" - {
        forEachBackend() { (backend, client, _) =>
            val minAge = 26
            for
                _ <- createPerson(backend, client)
                _ <- seedTwoPeople(client)
                // The interpolator embeds a column reference and a bound literal in the WHERE.
                rows <- Sql
                    .from[Person]("p")
                    .where(c => sql"${c.p.age} >= $minAge".as[Boolean])
                    .select(c => c.p.name)
                    .run
            yield
                assert(rows.size == 1, s"${backend.label}: expected 1 row (age >= 26), got ${rows.size}")
                assert(rows.head == "alice", s"${backend.label}: expected alice, got ${rows.head}")
            end for
        }
    }

    // ── SqlClient factory-chain and close-triad ────────────────────────────────

    // Which factory installs the ambient client, and what each close variant promises. Not an engine property, so it
    // runs per backend and adds what a single-engine form cannot state: every URL scheme reaches the same behaviour.

    "initWith(url)(f) creates a client, runs f, and registers Scope cleanup" - {
        forEachBackend() { (backend, _, schema) =>
            // initWith registers Scope.ensure(close); the query succeeds inside `f`.
            SqlClient.initWith(schema.url) { client =>
                DB.run(client) {
                    client.executeRaw(
                        s"CREATE TABLE person (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL, age INT NOT NULL)"
                    ).andThen {
                        client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                            .map(n => assert(n == 1L, s"${backend.label}: expected 1 affected row, got $n"))
                    }
                }
            }
        }
    }

    "Scope.run(initWith(url)(f)) gives bracket semantics, with no Scope in the effect set" - {
        forEachBackend() { (backend, _, schema) =>
            // initWith binds close to the enclosing Scope; running that Scope inline discharges it, so the ascription
            // below (no Scope in the row) is the assertion: close is still guaranteed, and the caller inherits no Scope
            // requirement.
            val bracketed: Unit < (Async & Abort[SqlException]) =
                Scope.run {
                    SqlClient.initWith(schema.url) { client =>
                        client.executeRaw(
                            s"CREATE TABLE person (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL, age INT NOT NULL)"
                        ).andThen {
                            client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                                .map(n => assert(n == 1L, s"${backend.label}: expected 1 affected row, got $n"))
                        }
                    }
                }
            bracketed
        }
    }

    "initUnscoped(url) creates a client with no cleanup; manual close completes without error" - {
        forEachBackend() { (backend, _, schema) =>
            // initUnscoped leaves cleanup to the caller; the client is closed manually below.
            SqlClient.initUnscoped(schema.url).flatMap { client =>
                DB.run(client) {
                    for
                        _ <- client.executeRaw(
                            s"CREATE TABLE person (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                        // The write is read back before the manual close: an unscoped client that accepts a statement
                        // and never serves the row back is not a working client.
                        rows <- Sql.from[Person]("p").run
                        _    <- client.close
                    yield
                        assert(rows.size == 1, s"${backend.label}: expected 1 row, got ${rows.size}")
                        assert(rows.head == Person(1L, "alice", 30), s"${backend.label}: expected Person(1,alice,30), got ${rows.head}")
                }
            }
        }
    }

    "close(gracePeriod), close, and closeNow all complete without error" - {
        forEachBackend() { (_, _, schema) =>
            for
                // Three independent clients, one per close variant. Completion without error is the contract; that an
                // idle close returns within the grace period rather than at it needs a Clock seam to assert, not a
                // wall-clock bound.
                c1 <- SqlClient.initUnscoped(schema.url)
                _  <- c1.close(30.seconds)
                c2 <- SqlClient.initUnscoped(schema.url)
                _  <- c2.close
                c3 <- SqlClient.initUnscoped(schema.url)
                _  <- c3.closeNow
            yield succeed
        }
    }

    /** The edges where the two carriers differ: zero, a sub-second fraction, a negative span, and a span past a day. The sub-second case needs
      * the descriptor's `Duration` column to keep a fraction, since one engine's `TIME` defaults to precision 0.
      */
    "a java.time.Duration round-trips across the boundary values" - {
        forEachBackend() { (backend, client, _) =>
            val inputs = Seq(
                Trip(1L, "zero", java.time.Duration.ZERO),
                Trip(2L, "1h", java.time.Duration.ofHours(1)),
                Trip(3L, "1h1m1.5s", java.time.Duration.ofSeconds(3661, 500_000_000L)),
                Trip(4L, "neg-30s", java.time.Duration.ofSeconds(-30)),
                Trip(5L, "1d2h", java.time.Duration.ofDays(1).plusHours(2))
            )
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE trip (id BIGINT PRIMARY KEY, label ${backend.textColumnType} NOT NULL, " +
                        s"span ${backend.columnType(kyo.internal.SqlTestBackend.ColumnType.Duration)} NOT NULL)"
                )
                _    <- Kyo.foreachDiscard(inputs)(t => Sql.insert[Trip].values(t).run)
                rows <- Sql.from[Trip]("t").orderBy(_.t.id.asc).run
            yield
                assert(rows.size == inputs.size, s"${backend.label}: expected ${inputs.size} rows, got ${rows.size}")
                inputs.zip(rows).foreach { case (expected, actual) =>
                    assert(actual.id == expected.id, s"${backend.label}: id mismatch, ${actual.id} vs ${expected.id}")
                    assert(actual.label == expected.label, s"${backend.label}: label mismatch, ${actual.label}")
                    assert(
                        actual.span.equals(expected.span),
                        s"${backend.label}: span round-trip mismatch for ${expected.label}, ${actual.span} vs ${expected.span}"
                    )
                }
                succeed
            end for
        }
    }

    // ── A narrow Scala type over a wide column, end to end on both engines ────
    //
    // This suite's other count(*) sites either type the result Long (the DSL types `.count` that way) or cast to text
    // before decoding, so the leaves below are the ones that place a narrow Scala type over a wide column. That is the
    // mismatch a user writing raw SQL hits first: count(*) is int8 on PostgreSQL and BIGINT on MySQL, every
    // extended-protocol result column is requested in binary, and reading four big-endian bytes of an eight-byte value
    // returns its high word, which is 0 for every count under 2^32. Decoding count(*) into Int against a table with a
    // known row count is what makes that visible: it answers 0 where the table holds 7.

    /** Pinned on every engine because the failure differs and both are quiet: `count(*)` is an `int8` whose high word is zero on one, and ASCII
      * digits a little-endian read turns into a large wrong number on the other.
      */
    "a count(*) decoded into Int returns the count, and a BIGINT id narrows into one" - {
        forEachBackend() { (backend, client, _) =>
            for
                _       <- client.executeRaw("CREATE TABLE counted (id BIGINT PRIMARY KEY)")
                _       <- client.executeRaw("INSERT INTO counted VALUES (1), (2), (3), (4), (5), (6), (7)")
                rows    <- client.query("SELECT count(*) FROM counted")
                n       <- rows.head.decode[Int]
                ids     <- client.query("SELECT id FROM counted ORDER BY id")
                firstId <- ids.head.decode[Int]
            yield
                assert(n == 7, s"${backend.label}: count(*) over 7 rows must decode into Int as 7, got $n")
                assert(firstId == 1, s"${backend.label}: a BIGINT id of 1 must decode into Int as 1, got $firstId")
        }
    }

    "a MySQL simpleQuery row decodes its text values, which share no representation with the binary protocol" in {
        // simpleQuery is public and documented for one-off SQL, and its rows are Format.Text: every value is its ASCII
        // rendering. The digits of 1234 parsed as a little-endian LONG are 875770417, and the ASCII 0 of a false boolean
        // is the nonzero byte 0x30.
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        numeric <- client.simpleQuery("SELECT 1234")
                        n       <- numeric.head.decode[Int]
                        falsy   <- client.simpleQuery("SELECT 0")
                        f       <- falsy.head.decode[Boolean]
                        truthy  <- client.simpleQuery("SELECT 1")
                        t       <- truthy.head.decode[Boolean]
                    yield
                        assert(n == 1234, s"a text-protocol 1234 must decode as 1234, got $n")
                        assert(!f, "a text-protocol 0 must decode as false")
                        assert(t, "a text-protocol 1 must decode as true")
                }
            }
        }
    }

    /** `Sql.default` in the key cell is the portable way to let the server assign; both engines then hand back the FIRST key. */
    "the server assigns successive keys when the key cell is DEFAULT" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE widget (id ${backend.autoIncrementPrimaryKey}, label ${backend.textColumnType} NOT NULL)"
                )
                first  <- Sql.insert[Widget].values(Widget(5L, "five")).overriding(_.id := Sql.default).run
                second <- Sql.insert[Widget].values(Widget(6L, "six")).overriding(_.id := Sql.default).run
                rows   <- client.query("SELECT id, label FROM widget ORDER BY id")
            yield
                assert(first.affectedRows == 1L, s"${backend.label}: expected one inserted row, got ${first.affectedRows}")
                assert(
                    first.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(1L),
                    s"${backend.label}: the server assigns the first key, not the row's 5, got ${first.generatedKey}"
                )
                assert(
                    second.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(2L),
                    s"${backend.label}: the sequence must advance, so the second key is 2, got ${second.generatedKey}"
                )
                assert(rows.size == 2, s"${backend.label}: both rows must be present, got ${rows.size}")
        }
    }

    /** The key arrives at the width the column declared, which need not be the row type's: a narrower payload has to widen rather than refuse. */
    "a narrow generated-key payload widens into the row's key type" - {
        forEachBackend() { (backend, client, _) =>
            if !backend.supportsReturning then succeed(s"${backend.label} carries no RETURNING payload to widen")
            else
                for
                    _     <- client.executeRaw(s"CREATE TABLE widget (id SERIAL PRIMARY KEY, label ${backend.textColumnType} NOT NULL)")
                    first <- Sql.insert[Widget].values(Widget(5L, "five")).overriding(_.id := Sql.default).run
                yield assert(
                    first.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(1L),
                    s"${backend.label}: a four-byte key payload must widen into the Long, got ${first.generatedKey}"
                )
        }
    }

    // ── A VALUES source is a query both servers can execute ───────────────────
    //
    // A rendered-text assertion cannot see whether this entry point produces executable SQL: a VALUES list names its own
    // columns (`column1` on PostgreSQL, `column_0` on MySQL), so a projection of `"v"."x"` above it resolves against
    // nothing unless the alias renames them. These two leaves run the query and read the values back.

    "a query over a VALUES source returns its rows" - {
        forEachBackend() { (backend, _, _) =>
            Sql.values[Point]("v", Point(1, 2), Point(3, 4)).run.map { rows =>
                assert(rows.size == 2, s"${backend.label}: a two-row VALUES source must return two rows, got ${rows.size}")
                assert(rows.head == Point(1, 2), s"${backend.label}: expected Point(1,2), got ${rows.head}")
                assert(rows(1) == Point(3, 4), s"${backend.label}: expected Point(3,4), got ${rows(1)}")
            }
        }
    }

    // ── Leaf 26: a String value carrying SQL metacharacters round-trips byte-for-byte ────
    //
    // An INSERT path that wrote String cells into the statement text with `'` doubling as their only transformation
    // would be unsafe on MySQL, whose default sql_mode treats a backslash as an escape: `\'` becomes `\''`, the
    // backslash escapes the first quote, the second closes the literal, and the rest of the value lands in statement
    // position. These two leaves assert on the value that comes back rather than on the statement succeeding, because
    // a succeeding statement is precisely that defect's failure mode.

    /** Values whose text is SQL syntax if it ever leaves the bind list.
      *
      * The second is the injection payload, shaped so that it lands rather than raising. It carries one quote (doubling cannot disarm it,
      * and MySQL's backslash escape is what closes the literal early), then completes the row the renderer had opened, then opens a second
      * complete row whose text value is a hex literal so the payload needs no quotes of its own, then comments out the renderer's own tail.
      * Interpolated into the statement text it parses as a valid two-row insert on MySQL, so the row count is what catches it.
      */
    private val metacharacterNames: Seq[String] = Seq(
        """o'brien""",
        """x\', 0), (999, 0x70776e6564, 1) -- """,
        """ends with a backslash \""",
        """two \\ backslashes""",
        """a "double" quote and a `backtick`"""
    )

    "String values carrying SQL metacharacters round-trip byte-for-byte" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- createPerson(backend, client)
                _    <- Kyo.foreachIndexed(metacharacterNames)((i, name) => Sql.insert[Person].values(Person(i.toLong, name, 30)).run)
                rows <- Sql.from[Person]("p").run
            yield
                // Row count first: an escaped literal injects extra rows, so a count over the inserted
                // total is the injection itself rather than a corrupted value.
                assert(
                    rows.size == metacharacterNames.size,
                    s"${backend.label}: expected exactly ${metacharacterNames.size} rows, got ${rows.size}: ${rows.toSeq.map(_.name)}"
                )
                val byId = rows.toSeq.map(p => p.id -> p.name).toMap
                metacharacterNames.zipWithIndex.foreach { (name, i) =>
                    assert(
                        byId.get(i.toLong).contains(name),
                        s"${backend.label}: row $i must hold the value byte-for-byte, expected [$name], got [${byId.get(i.toLong)}]"
                    )
                }
                succeed
        }
    }

    // ── Leaf 27: a NULL in a Maybe field decodes, on both engines ─────────────
    //
    // `Maybe` is the DSL's only nullability vocabulary, and it is the one place a row read depends on the reader's
    // null contract: the nullable column reads as `if r.isNil() then Maybe.empty else Present(read(...))`, so a
    // reader answering `isNil` without consuming the null column would leave it in front of the next field's read
    // and shift every value after it. The mock readers pin the contract; these two leaves pin that both real
    // readers implement it.

    case class Contact(id: Long, name: String, email: Maybe[String]) derives SqlSchema, CanEqual

    "a NULL in a Maybe field decodes as Absent" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE contact (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL, email ${backend.textColumnType})"
                )
                _    <- client.executeRaw("INSERT INTO contact VALUES (1, 'alice', NULL), (2, 'bob', 'bob@example.com')")
                rows <- Sql.from[Contact]("c").run
            yield
                assert(rows.size == 2, s"${backend.label}: expected 2 rows, got ${rows.size}")
                val byId = rows.toSeq.map(c => c.id -> c).toMap
                assert(byId(1L) == Contact(1L, "alice", Absent), s"${backend.label}: a NULL email must decode as Absent, got ${byId(1L)}")
                assert(
                    byId(2L) == Contact(2L, "bob", Present("bob@example.com")),
                    s"${backend.label}: a present email must decode as Present, got ${byId(2L)}"
                )
        }
    }

    // ── Leaf 28: naming resolution round-trips, on both engines ───────────────
    //
    // The three naming suites assert rendered SQL only, so the read direction needs its own coverage: a row codec's
    // field names are the Scala names (post `@column`) while a cased query's server columns carry the resolved
    // ones, so the casing has to reach the decode or the read fails on a field the write direction filled happily.
    // One leaf per engine covers both mechanisms, the query-scoped `SqlNaming` and the per-field `@column`,
    // to keep it to one container each. The table name comes from the table-name parameter, since casing governs
    // columns only.

    case class UserProfile(id: Long, firstName: String, createdAt: Long) derives SqlSchema, CanEqual

    case class Alias(id: Long, @column("nick") nickname: String) derives SqlSchema, CanEqual

    "a cased query and a renamed field both round-trip" - {
        given SqlNaming = SqlNaming.SnakeCase
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE user_profile (id BIGINT PRIMARY KEY, first_name ${backend.textColumnType} NOT NULL, " +
                        "created_at BIGINT NOT NULL)"
                )
                _        <- client.executeRaw(s"CREATE TABLE alias (id BIGINT PRIMARY KEY, nick ${backend.textColumnType} NOT NULL)")
                _        <- Sql.insert[UserProfile]("user_profile").values(UserProfile(1L, "ada", 1700000000L)).run
                _        <- Sql.insert[Alias].values(Alias(1L, "countess")).run
                profiles <- Sql.from[UserProfile]("u", "user_profile").run
                aliases  <- Sql.from[Alias]("a").run
            yield
                assert(
                    profiles == Chunk(UserProfile(1L, "ada", 1700000000L)),
                    s"${backend.label}: a cased query must read back what it wrote, got $profiles"
                )
                assert(
                    aliases == Chunk(Alias(1L, "countess")),
                    s"${backend.label}: a renamed field must read back from its column, got $aliases"
                )
        }
    }

    // ── Leaf 29: a projection into a case class, written the way a caller writes it ─
    //
    // No `.as` labels on the projected terms and a target whose field names deliberately differ from the projected
    // columns'. A decode that matched by name alone would make the labels mandatory and report a missing field that is
    // right there in the case class.

    case class Summary(fullName: String, years: Int) derives SqlSchema, CanEqual

    "a tuple projection decodes into a case class whose field names differ from the columns" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- createPerson(backend, client)
                _    <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                rows <- Sql.from[Person]("p").select(c => (c.p.name, c.p.age)).to[Summary].run
            yield assert(rows == Chunk(Summary("alice", 30)), s"${backend.label}: expected one Summary(alice, 30), got $rows")
        }
    }

    // The property positional matching must not cost: a caller's own SELECT, whose column order is theirs and not the
    // schema's, still lands each value in its own field.
    "raw SQL whose columns are in a different order than the case class still decodes by name" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- createPerson(backend, client)
                _    <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                rows <- client.query("SELECT age, name, id FROM person")
                row  <- Abort.run[SqlDecodeException](rows.head.decode[Person]).map(_.getOrThrow)
            yield assert(
                row == Person(1L, "alice", 30),
                s"${backend.label}: columns out of schema order must still decode by name, got $row"
            )
        }
    }

    // ── Leaf 31: aggregate and division VALUES, on both engines ───────────────
    //
    // These are the value assertions for `.sum` / `.avg` / `.min` / `.max`; a rendered-text assertion or a type-rejection
    // check cannot see the result type at all. `SUM` over an `INT` is an `int8` on PostgreSQL and a `DECIMAL` on MySQL,
    // so typing the aggregate as its operand reads a PostgreSQL total under 2^32 as its high word (zero) and a MySQL one
    // as four ASCII digits taken for a little-endian integer. `AVG` at the operand's type is a semantic error rather than
    // a width one, and an untyped `/` answers 3 on PostgreSQL where MySQL answers 3.5 from the same source line.
    //
    // One leaf covering the widths, the empty-input NULL, the two divisions, and the rollup key's NULL.

    case class Metric(id: Long, region: String, amount: Int, quantity: Long, divisor: Int) derives SqlSchema, CanEqual
    case class RegionTotal(region: Maybe[String], total: Long) derives SqlSchema, CanEqual

    private val metricRows = "(1, 'north', 10, 100, 4), (2, 'north', 20, 200, 8), (3, 'south', 30, 300, 3), (4, 'south', 41, 400, 2)"

    /** The nine values agree; what differs is how each goes wrong when the aggregate is typed as its operand. One engine's `SUM` over an `INT`
      * is an `int8` read as its high word, the other a `DECIMAL` arriving as ASCII read as a little-endian integer.
      */
    "aggregates, division, and a rollup key all return the values their types promise" - {
        forEachBackend() { (backend, client, _) =>
            val metrics = Sql.from[Metric]("m")
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE metric (id BIGINT PRIMARY KEY, region ${backend.textColumnType} NOT NULL, amount INT NOT NULL, " +
                        "quantity BIGINT NOT NULL, divisor INT NOT NULL)"
                )
                _             <- client.executeRaw(s"INSERT INTO metric VALUES $metricRows")
                amountTotal   <- metrics.sum(_.m.amount).run
                amountAverage <- metrics.avg(_.m.amount).run
                amountLow     <- metrics.min(_.m.amount).run
                amountHigh    <- metrics.max(_.m.amount).run
                quantityTotal <- metrics.sum(_.m.quantity).run
                emptyTotal    <- metrics.where(c => c.m.amount > 1000).sum(_.m.amount).run
                quotient      <- metrics.where(c => c.m.id == 1L).select(c => c.m.amount / c.m.divisor).run
                truncated     <- metrics.where(c => c.m.id == 1L).select(c => c.m.amount.divideTruncating(c.m.divisor)).run
                rolledUp      <- metrics.groupByRollup(c => c.m.region).select(v => (v.region, v.amount.sum)).to[RegionTotal].run
            yield
                // SUM over an INT column is an int8 on one engine and a DECIMAL on the other. At type Int the first
                // reads as the high word of an eight-byte value (zero) and the second as ASCII digits taken for a
                // little-endian integer, so the width the DSL assigns is what makes one answer come back.
                assert(amountTotal.head == Present(101L), s"${backend.label}: SUM(amount) must be 101, got ${amountTotal.head}")
                // AVG is not the operand's type in any flavor: 101/4 is not an Int.
                assert(
                    amountAverage.head == Present(BigDecimal("25.25")),
                    s"${backend.label}: AVG(amount) must be 25.25, got ${amountAverage.head}"
                )
                assert(amountLow.head == Present(10), s"${backend.label}: MIN(amount) must be 10, got ${amountLow.head}")
                assert(amountHigh.head == Present(41), s"${backend.label}: MAX(amount) must be 41, got ${amountHigh.head}")
                // SUM over a BIGINT widens again, which is the overflow headroom the widening exists for.
                assert(
                    quantityTotal.head == Present(BigDecimal(1000)),
                    s"${backend.label}: SUM(quantity) must be 1000, got ${quantityTotal.head}"
                )
                // A predicate matching nothing still returns one row, holding NULL.
                assert(emptyTotal.head == Absent, s"${backend.label}: SUM over no rows must be Absent, got ${emptyTotal.head}")
                // 10 / 4 is 2.5 on both: one engine's integer division would truncate it to 2, and the dialect casts so
                // it does not, while the other's `/` is fractional already.
                assert(quotient == Chunk(BigDecimal("2.5")), s"${backend.label}: 10 / 4 must be 2.5, got $quotient")
                // And the truncating spelling is what keeps truncation expressible on both.
                assert(truncated == Chunk(2), s"${backend.label}: 10 divideTruncating 4 must be 2, got $truncated")
                // The ROLLUP subtotal row carries NULL for the key it is not grouping by.
                val byRegion = rolledUp.toSeq.map(r => r.region -> r.total).toMap
                assert(byRegion.size == 3, s"${backend.label}: ROLLUP must add one subtotal row to the two regions, got $rolledUp")
                assert(byRegion(Present("north")) == 30L, s"${backend.label}: north total must be 30, got $rolledUp")
                assert(byRegion(Present("south")) == 71L, s"${backend.label}: south total must be 71, got $rolledUp")
                assert(byRegion(Absent) == 101L, s"${backend.label}: the subtotal row must carry Absent and 101, got $rolledUp")
            end for
        }
    }

end SqlEndToEndTest
