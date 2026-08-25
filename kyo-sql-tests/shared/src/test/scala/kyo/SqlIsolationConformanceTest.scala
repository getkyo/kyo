package kyo

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
  * typed server error and writes nothing. Reading the level back off the server through a `SHOW`-style introspection query stays
  * engine-specific and out of scope here: the SQL to introspect it differs per engine, and this suite branches on capability, never on a
  * hardcoded engine name.
  */
class SqlIsolationConformanceTest extends SqlBackendTest:

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
