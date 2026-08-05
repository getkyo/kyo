package kyo

import kyo.*
import kyo.Sql.*
import scala.compiletime.testing.typeChecks

/** Verifies the `SqlClient.InsertOutcome` surface: the value type, the DSL shape around `.returning`, and the live-server behaviours that
  * only a running engine can settle, run once per available backend.
  *
  * Backends report a generated key by different mechanisms and therefore disagree about which key a multi-row insert reports, and about how
  * a table with no auto-key column is described. Both divergences turn on whether the engine returns keys through a RETURNING clause, so
  * the conformance bodies branch on `dialect.supportsReturning`: an engine with RETURNING returns one row per inserted row (the batch
  * reports its LAST key) and lets the renderer omit the clause when no key column exists (reported as `NoAutoKey`); an engine without it
  * reads the key off the OK packet (the batch reports its FIRST key, and a keyless insert reports `Unavailable`). Neither is observable
  * from a renderer test.
  *
  * What is NOT here, deliberately: the single-row generated-key path. [[SqlEndToEndTest]] covers it against every container, and a second
  * copy would be a duplicate rather than coverage.
  */
class SqlClientInsertOutcomeTest extends SqlBackendTest:

    case class Account(id: Long, name: String) derives SqlSchema
    case class Tag(name: String) derives SqlSchema

    // ── A table with no auto-key column, where the two engines describe it differently ──

    "Insert.run on a table with no auto-key column reports the engine's own no-key outcome" - forEachBackend() { (_, client, _) =>
        // The key comes from RETURNING or from the OK packet, and the two describe a keyless table differently. An
        // engine with RETURNING lets the renderer OMIT the clause once it sees there is no key column, and that
        // omission is itself the evidence, reported as NoAutoKey. An engine without RETURNING reads last_insert_id off
        // the OK packet, where a keyless table reports 0; that cannot be told apart from "column present, generation
        // suppressed", so it is Unavailable rather than a Value(0L) the caller would read as a real key. Branching on
        // `supportsReturning` is what folds the two halves into one body.
        val expectedKey =
            if client.dialect.supportsReturning then SqlClient.InsertOutcome.GeneratedKey.NoAutoKey
            else SqlClient.InsertOutcome.GeneratedKey.Unavailable
        for
            _      <- client.executeRaw("CREATE TABLE tag (name VARCHAR(255) PRIMARY KEY)")
            result <- Sql.insert[Tag].values(Tag("urgent")).run
        yield
            assert(result.affectedRows == 1L, s"expected one inserted row, got ${result.affectedRows}")
            assert(
                result.generatedKey == expectedKey,
                s"a keyless insert must report the engine's own no-key outcome, got ${result.generatedKey}"
            )
        end for
    }

    // ── A batch insert, where the two engines report opposite ends of the range ──

    "a batch INSERT's generatedKey is the id its wire protocol reports" - forEachBackend() { (backend, client, _) =>
        // The two engines disagree here by protocol, not by choice. An engine with RETURNING returns one row per
        // inserted row, so a 3-row insert produces three keys and the outcome carries the LAST. An engine without it
        // reads last_insert_id off the OK packet, which is the FIRST id of a multi-row insert. A caller that assumed
        // one answer would be off by (rowCount - 1) on the other. The auto-increment key spelling also differs by
        // engine, so the DDL fragment comes from the descriptor's `autoIncrementPrimaryKey` rather than a literal;
        // `supportsReturning` selects only which end of the range the outcome reports.
        //
        // `.overriding(_.id := Sql.default)` is required rather than decoration: `values(row)` sends every column, and
        // an engine that accepts an explicit value in an identity column without advancing its sequence would
        // otherwise report the ids supplied here.
        val autoIncPk = backend.autoIncrementPrimaryKey
        val expectedKey =
            if client.dialect.supportsReturning then SqlClient.InsertOutcome.GeneratedKey.Value(3L)
            else SqlClient.InsertOutcome.GeneratedKey.Value(1L)
        for
            _ <- client.executeRaw(s"CREATE TABLE account (id $autoIncPk, name VARCHAR(255) NOT NULL)")
            result <- Sql
                .insert[Account]
                .values(Account(0L, "ada"), Account(0L, "bob"), Account(0L, "cid"))
                .overriding(_.id := Sql.default)
                .run
            ids <- Sql.from[Account]("a").select(c => c.a.id).orderBy(c => c.a.id.asc).run
        yield
            assert(result.affectedRows == 3L, s"expected three inserted rows, got ${result.affectedRows}")
            assert(
                result.generatedKey == expectedKey,
                s"the batch's generatedKey must be the end its wire protocol reports, got ${result.generatedKey}"
            )
            assert(ids == Chunk(1L, 2L, 3L), s"a fresh table must generate ids 1, 2, 3 for the three rows, got $ids")
        end for
    }

    // ── .returning on Insert, Update and Delete ────────────────────────────

    "Insert .returning method compiles" in {
        assert(typeChecks("""Sql.insert[Account].values(Account(0L, "Ada")).returning(_.id)"""))
    }

    "Update .returning DSL chain (after .where) is intermediate-builder-driven, not direct" in {
        // `.returning` lives on Update.ReturningBuilder / Delete.ReturningBuilder rather than on
        // Update and Delete themselves, so a chain ending in `.where` cannot reach it: `.where`
        // returns Update, which declares no `.returning`. The order the API takes is
        // `Sql.update[T].set(...).returning(...).where(...)`.
        assert(!typeChecks("""Sql.update[Account].set(_.name := "x").where(_.id == 1L).returning(_.id)"""))
    }

    "Delete .returning DSL chain (after .where) is intermediate-builder-driven, not direct" in {
        assert(!typeChecks("""Sql.delete[Account].where(_.id == 1L).returning(_.id)"""))
    }

    // ── there is no Sql.Returning type ──────────────────────────────────────

    "Sql.Returning type is gone (compile error)" in {
        assert(!typeChecks("kyo.Sql.Returning"))
    }

    // ── SqlClient.InsertOutcome value type assertions ─────────────────────────────────────

    "SqlClient.InsertOutcome fields are Long + SqlClient.InsertOutcome.GeneratedKey.Value" in {
        val r = SqlClient.InsertOutcome(2L, SqlClient.InsertOutcome.GeneratedKey.Value(99L))
        assert(r.affectedRows == 2L)
        assert(r.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(99L))
        assert(SqlClient.InsertOutcome.GeneratedKey.isPresent(r.generatedKey))
        assert(SqlClient.InsertOutcome.GeneratedKey.foldKey(r.generatedKey)(0L)(identity) == 99L)
    }

    "SqlClient.InsertOutcome.generatedKey can be NoAutoKey" in {
        val r = SqlClient.InsertOutcome(1L, SqlClient.InsertOutcome.GeneratedKey.NoAutoKey)
        assert(r.generatedKey == SqlClient.InsertOutcome.GeneratedKey.NoAutoKey)
        assert(!SqlClient.InsertOutcome.GeneratedKey.isPresent(r.generatedKey))
        assert(SqlClient.InsertOutcome.GeneratedKey.foldKey(r.generatedKey)(-1L)(identity) == -1L)
    }

    "SqlClient.InsertOutcome.generatedKey can be Unavailable" in {
        val r = SqlClient.InsertOutcome(1L, SqlClient.InsertOutcome.GeneratedKey.Unavailable)
        assert(r.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Unavailable)
        assert(!SqlClient.InsertOutcome.GeneratedKey.isPresent(r.generatedKey))
    }

    "SqlClient.InsertOutcome.GeneratedKey.NoAutoKey and Unavailable are distinguishable" in {
        assert(SqlClient.InsertOutcome.GeneratedKey.NoAutoKey != SqlClient.InsertOutcome.GeneratedKey.Unavailable)
        // The split is the reason this enum exists: collapsing both into one absent value hides from
        // the caller which of the two conditions the server actually reported.
        succeed
    }

end SqlClientInsertOutcomeTest
