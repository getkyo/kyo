package kyo

/** How string comparison behaves on every registered backend: the same predicate over the same rows selects the same rows.
  *
  * This divergence returns WRONG ROWS rather than failing. One engine's default collation is case- and accent-insensitive, so
  * `where(_.name == "alice")` matches a stored `Alice` there and nothing on the other, and a unique index rejects the pair as duplicates.
  *
  * The engines are made to agree at the COLUMN, the only place reaching every operation below: a connection-level pin fixes
  * literal-against-literal comparison only, and a per-query `COLLATE` cannot reach a unique index.
  */
class SqlCollationConformanceTest extends SqlBackendTest:

    case class Name(v: String) derives SqlSchema, CanEqual

    private def seed(backend: kyo.internal.SqlTestBackend, client: SqlClient)(using
        Frame
    ): Unit < (Async & Abort[SqlException] & DB) =
        client.executeRaw(s"CREATE TABLE name (v ${backend.textColumnType} NOT NULL)").andThen(
            Sql.insert[Name].values(Name("Alice"), Name("alice"), Name("ALICE"), Name("resume"), Name("résumé")).run
        ).unit

    "equality on a text column distinguishes case" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- seed(backend, client)
                rows <- Sql.from[Name]("n").where(_.n.v == "alice").run
            yield assert(
                rows.map(_.v) == Chunk("alice"),
                s"${backend.label}: expected only the exact match, got ${rows.map(_.v)}"
            )
        }
    }

    /** Asserted apart from equality: `IN` is a different node with its own lowering. */
    "membership on a text column distinguishes case" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- seed(backend, client)
                rows <- Sql.from[Name]("n").where(_.n.v.in("alice", "ALICE")).run
            yield assert(
                rows.map(_.v).sorted == Chunk("ALICE", "alice").sorted,
                s"${backend.label}: expected the two exact matches, got ${rows.map(_.v)}"
            )
        }
    }

    /** `like` is the case-sensitive form on both engines by contract; `ilike` is the insensitive one, below. Under a case-insensitive
      * collation the two spellings mean the same thing and the distinction the DSL offers is not real.
      */
    "a like pattern distinguishes case" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- seed(backend, client)
                rows <- Sql.from[Name]("n").where(_.n.v.like("alic%")).run
            yield assert(
                rows.map(_.v) == Chunk("alice"),
                s"${backend.label}: expected only the lower-case row, got ${rows.map(_.v)}"
            )
        }
    }

    /** The complement: `ilike` must match case-insensitively on an engine whose collation is case-SENSITIVE. One engine has a native operator
      * and the other lowers to `LOWER(x) LIKE LOWER(p)`; each dialect's translation suite pins the spelling, and what belongs here is that
      * both select the same rows.
      */
    "an ilike pattern ignores case" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- seed(backend, client)
                rows <- Sql.from[Name]("n").where(_.n.v.ilike("alic%")).run
            // Sorted here rather than by the server, so the leaf pins which rows matched and not how a collation orders them.
            yield assert(
                rows.map(_.v).toSeq.sorted == Seq("ALICE", "Alice", "alice"),
                s"${backend.label}: expected every case variant, got ${rows.map(_.v)}"
            )
        }
    }

    /** The accent half is a separate property: one engine's default collation is accent-insensitive too, so `resume` and `résumé` collapse
      * into one group there and a leaf testing only case would pass.
      */
    "distinctness separates case variants and accented letters" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- seed(backend, client)
                rows <- Sql.from[Name]("n").select(_.n.v).distinct.run
            yield assert(
                rows.size == 5,
                s"${backend.label}: five distinct values were stored, got ${rows.size}: ${rows.sorted}"
            )
        }
    }

end SqlCollationConformanceTest
