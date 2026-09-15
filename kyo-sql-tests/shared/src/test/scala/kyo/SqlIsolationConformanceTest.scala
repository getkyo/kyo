package kyo

import kyo.internal.SqlTestBackend

/** Cross-backend conformance for `SqlClient.transaction`'s isolation vocabulary: each of the four [[SqlClient.IsolationLevel]]s, plus a
  * read-only transaction, run against every available backend.
  *
  * [[SqlClientTransactionTest]] covers the isolation vocabulary's shape (the four levels, exhaustive matching) and each behavior in depth
  * on a single engine (a level reported back through an engine-specific introspection query, a read-only rejection). This suite is the
  * cross-backend half: every leaf runs through [[SqlBackendTest.forEachBackend]] against whatever backend descriptors are discovered, so a
  * third backend that registers a descriptor gets these leaves for free with no change here.
  *
  * The property under test is behavioral rather than textual: a level's transaction runs and commits a normal write, proving the server
  * accepted the level rather than rejecting the BEGIN/START TRANSACTION outright, and a read-only transaction refuses a write with a
  * typed server error and writes nothing.
  *
  * It also asserts the level the server ACTUALLY applied, which for a long time nothing did. That the SQL to introspect it differs per
  * engine is a mechanism difference, which is what a descriptor absorbs, and it was previously the stated reason for leaving the property
  * out of scope entirely. The gap it left was total: `SqlClient.IsolationLevel.sqlKeyword` had no test reference anywhere in the repository,
  * so a caller could ask for one level, silently get another, and lose updates with nothing red.
  */
class SqlIsolationConformanceTest extends SqlBackendTest:

    /** The name each level is expected to answer to, owned by this suite rather than read from the production mapping.
      *
      * The duplication is the point. Comparing the server's answer against `level.sqlKeyword` would agree with itself: a wrong keyword sends
      * a wrong level, the server reports that wrong level, and the two match. An independent expectation breaks the loop, so a keyword that
      * drifts is caught by the server disagreeing with what the caller asked for.
      */
    private def expectedName(level: SqlClient.IsolationLevel): String =
        level match
            case SqlClient.IsolationLevel.ReadUncommitted => "READ UNCOMMITTED"
            case SqlClient.IsolationLevel.ReadCommitted   => "READ COMMITTED"
            case SqlClient.IsolationLevel.RepeatableRead  => "REPEATABLE READ"
            case SqlClient.IsolationLevel.Serializable    => "SERIALIZABLE"

    for level <- SqlClient.IsolationLevel.values.toList do
        s"a transaction under $level runs at the level it asked for" - {
            forEachBackend() { (backend, client, _) =>
                client.transaction(Present(level), readOnly = false) {
                    client.query(backend.isolationIntrospectionSql).flatMap { rows =>
                        rows(0).decode[String](0).map { answer =>
                            val applied = SqlTestBackend.canonicalIsolationName(answer)
                            assert(
                                applied == expectedName(level),
                                s"${backend.label}: asked for $level, server applied $applied (raw answer $answer)"
                            )
                        }
                    }
                }
            }
        }
    end for

    for level <- SqlClient.IsolationLevel.values.toList do
        s"a transaction under $level commits a normal write" - {
            forEachBackend() { (_, client, _) =>
                client.executeRaw("CREATE TABLE iso_check (id INT)").andThen {
                    client.transaction(Present(level), readOnly = false) {
                        client.executeRaw("INSERT INTO iso_check VALUES (1)")
                    }.andThen {
                        client.query("SELECT count(*) FROM iso_check").flatMap { rows =>
                            rows(0).decode[Long](0).map { count =>
                                assert(count == 1L, s"a $level transaction must commit its write, count was $count")
                            }
                        }
                    }
                }
            }
        }
    end for

    "a readOnly transaction rejects a write with the typed server error and writes nothing" - {
        forEachBackend() { (_, client, _) =>
            client.executeRaw("CREATE TABLE iso_check (id INT)").andThen {
                Abort.run[SqlException](
                    client.transaction(Absent, readOnly = true) {
                        client.executeRaw("INSERT INTO iso_check VALUES (1)")
                    }
                ).flatMap { outcome =>
                    client.query("SELECT count(*) FROM iso_check").flatMap { rows =>
                        rows(0).decode[Long](0).map { count =>
                            assert(count == 0L, s"a read-only transaction must write nothing, count was $count")
                            outcome match
                                case Result.Failure(e: SqlServerException) =>
                                    assert(
                                        e.serverMessage.toLowerCase.contains("read only") ||
                                            e.serverMessage.toLowerCase.contains("read-only"),
                                        s"expected the server to name the read-only transaction, got '${e.serverMessage}'"
                                    )
                                case other =>
                                    fail(s"expected the write to be refused by a typed server error, got $other")
                            end match
                        }
                    }
                }
            }
        }
    }

end SqlIsolationConformanceTest
