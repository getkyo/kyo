package kyo

import kyo.internal.SqlTestBackend.ColumnType

/** What a conflict clause suppresses, run against every registered backend: `onConflictDoNothing` skips a conflicting row and NOTHING else,
  * whichever engine ran it.
  *
  * A conflict clause is a narrow instruction, and one engine implements it with a blunt one. `ON CONFLICT DO NOTHING` suppresses unique and
  * exclusion conflicts; `INSERT IGNORE`, which the other flavor renders for the same node, downgrades EVERY error in the statement to a
  * warning: a foreign-key violation, a null into a NOT NULL column, an out-of-range number, a truncated string. So the same call silently
  * discards a row on one engine that the other refuses outright, or stores a mangled default where the other raises.
  *
  * The suites that already cover this node use a duplicate primary key, which is the one input where the two implementations coincide. These
  * leaves use the inputs where they do not.
  *
  * Written through the typed API, since the conflict clause is exactly the node whose lowering differs per flavor.
  */
class SqlConflictConformanceTest extends SqlBackendTest:

    case class Parent(id: Int) derives SqlSchema, CanEqual
    case class Child(id: Int, parentId: Int) derives SqlSchema, CanEqual
    case class Required(id: Int, note: String) derives SqlSchema, CanEqual

    /** A conflict clause does not swallow a foreign-key violation.
      *
      * Measured: the row referencing a missing parent is silently dropped on one engine, affected count 0 with a warning, and refused with a
      * typed constraint error on the other. A caller inserting under a conflict clause has no way to learn the row never landed.
      *
      * Both engines can suppress duplicates without suppressing this, so it is a driver obligation rather than a capability difference: the
      * OK packet carries a non-zero warning count, and the warning's own error number is identical to the one the hard failure reports.
      */
    "a conflict clause does not swallow a foreign-key violation" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(s"CREATE TABLE parent (id ${backend.columnType(ColumnType.Int)} PRIMARY KEY)")
                // The mixed-case column is QUOTED here. Unquoted DDL folds to lower case on one engine and is
                // preserved on the other, while the DSL always quotes, so an unquoted `parentId` becomes
                // `parentid` in the table and the generated SELECT then asks for a column that does not exist.
                _ <- client.executeRaw(
                    s"""CREATE TABLE child (
                       |  id ${backend.columnType(ColumnType.Int)} PRIMARY KEY,
                       |  ${backend.quoteIdent("parentId")} ${backend.columnType(ColumnType.Int)},
                       |  FOREIGN KEY (${backend.quoteIdent("parentId")}) REFERENCES parent(id)
                       |)""".stripMargin
                )
                _ <- Sql.insert[Parent].values(Parent(1)).run
                // Parent 99 does not exist, so this violates the foreign key. The conflict clause is about
                // duplicates and has no business suppressing it.
                outcome <- Abort.run[SqlException](
                    Sql.insert[Child].values(Child(1, 99)).onConflictDoNothing(_.id).run
                )
                rows <- Sql.from[Child]("c").run
            yield
                assert(
                    outcome.isFailure,
                    s"${backend.label}: a foreign-key violation under a conflict clause must fail, got $outcome"
                )
                assert(rows.isEmpty, s"${backend.label}: no child row should exist, got ${rows.map(_.id)}")
        }
    }

    /** A conflict clause still skips the duplicate it is for, which is the control for the leaf above.
      *
      * Without this, a driver that made every conflicting insert fail would satisfy the foreign-key leaf and destroy the feature.
      */
    "a conflict clause skips a duplicate row without failing" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE required (id ${backend.columnType(ColumnType.Int)} PRIMARY KEY, note ${backend.textColumnType} NOT NULL)"
                )
                _    <- Sql.insert[Required].values(Required(1, "first")).run
                _    <- Sql.insert[Required].values(Required(1, "second")).onConflictDoNothing(_.id).run
                rows <- Sql.from[Required]("r").run
            yield
                assert(rows.size == 1, s"${backend.label}: the duplicate must be skipped, got ${rows.size} rows")
                assert(
                    rows.head.note == "first",
                    s"${backend.label}: the original row must survive untouched, got ${rows.head.note}"
                )
        }
    }

end SqlConflictConformanceTest
