package kyo

import kyo.internal.SqlTestBackend.ColumnType

/** What a write REPORTS on every registered backend: the same statement answers the same count and the same generated key.
  *
  * The suites that pin what a write STORES check the half the engines already agree on. This checks the number and the key handed back, which
  * a caller branches on without ever reading a row. Written through the typed API, so the renderer is under test alongside the behaviour.
  */
class SqlWriteCountConformanceTest extends SqlBackendTest:

    case class Noop(id: Int, v: Int) derives SqlSchema, CanEqual
    case class ManyKeys(id: Long, label: String) derives SqlSchema, CanEqual

    /** One engine reports rows CHANGED and the other rows MATCHED, so an idempotent write answers 0 on one and 1 on the other. Matched is the
      * portable answer, since the engine reporting changed rows can be asked for matched ones and not the reverse.
      */
    "an update that matches a row but changes nothing reports one row" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE noop (id ${backend.columnType(ColumnType.Int)}, v ${backend.columnType(ColumnType.Int)})"
                )
                _         <- Sql.insert[Noop].values(Noop(1, 5)).run
                unchanged <- client.execute(Sql.update[Noop].set(_.v := 5).where(_.id == 1))
                changed   <- client.execute(Sql.update[Noop].set(_.v := 6).where(_.id == 1))
                missing   <- client.execute(Sql.update[Noop].set(_.v := 7).where(_.id == 99))
            yield
                // The two uncontested cases are asserted alongside so a fix that reported 1 for everything would fail here.
                assert(changed == 1L, s"${backend.label}: an update that changes a row reports 1, got $changed")
                assert(missing == 0L, s"${backend.label}: an update matching nothing reports 0, got $missing")
                assert(
                    unchanged == 1L,
                    s"${backend.label}: an update matching a row it does not change still reports 1, got $unchanged"
                )
        }
    }

    /** The first key is the only value both can answer: an OK packet carries one field defined as the first row's id and cannot recover the
      * rest, while RETURNING has every key and can report the first.
      */
    "a multi-row insert reports the first inserted key" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE manykeys (id ${backend.autoIncrementPrimaryKey}, label ${backend.textColumnType} NOT NULL)"
                )
                outcome <- Sql.insert[ManyKeys]
                    .values(ManyKeys(0L, "a"), ManyKeys(0L, "b"), ManyKeys(0L, "c"))
                    .overriding(_.id := Sql.default)
                    .run
                // The key is compared against the row the server actually gave it to, rather than against a literal,
                // so the leaf does not assume the engine starts its sequence at one.
                firstId <- Sql.from[ManyKeys]("k").where(_.k.label == "a").select(_.k.id).run
            yield
                assert(outcome.affectedRows == 3L, s"${backend.label}: three rows inserted, reported ${outcome.affectedRows}")
                assert(
                    outcome.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(firstId.head),
                    s"${backend.label}: expected the first row's key ${firstId.head}, got ${outcome.generatedKey}"
                )
        }
    }

    /** One engine counts an upsert that updated a row as TWO, the matched row plus the changed one. [[Sql.Insert.capAffected]] holds the count
      * to what the statement could have affected, which is a property of the statement rather than of either engine.
      */
    "an upsert reports one row whether it inserted or updated" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE noop (id ${backend.columnType(ColumnType.Int)} PRIMARY KEY, v ${backend.columnType(ColumnType.Int)})"
                )
                inserted <- Sql.insert[Noop].values(Noop(1, 5))
                    .onConflictDoUpdate(_.id)(c => c.v := Sql.Excluded(c.v)).run
                updated <- Sql.insert[Noop].values(Noop(1, 9))
                    .onConflictDoUpdate(_.id)(c => c.v := Sql.Excluded(c.v)).run
                rows <- Sql.from[Noop]("n").run
            yield
                assert(inserted.affectedRows == 1L, s"${backend.label}: a fresh insert reports 1, got ${inserted.affectedRows}")
                assert(
                    updated.affectedRows == 1L,
                    s"${backend.label}: an upsert that updated one row reports 1, got ${updated.affectedRows}"
                )
                assert(rows.size == 1 && rows.head.v == 9, s"${backend.label}: the row must hold the updated value, got $rows")
        }
    }

end SqlWriteCountConformanceTest
