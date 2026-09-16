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
  * Honouring all four levels is a capability, not a given. An engine that runs everything at one level is required to REFUSE the others
  * rather than substitute, since asking for one level and quietly getting a weaker one is the lost-update bug this suite exists to catch.
  * So the leaves split on `honouredIsolationLevels`, and the refusal loop claims the other side; every backend meets every level through
  * exactly one of the two. Reporting the APPLIED level splits again, on whether the engine can be asked at all.
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

    /** One leaf per level, on every backend, with no engine skipped.
      *
      * Three arms, each a real and checkable property, and every engine takes exactly one of them for every level:
      *
      *   - it runs and the server reports the level, which is the strongest arm and the one the introspecting engines take;
      *   - it runs and the engine has no statement that reports a level, so what is checkable is that it ran at all;
      *   - it is refused with a typed error, which an engine that cannot honour the level owes its caller.
      *
      * Skipping the engines that cannot be asked would drop the third arm entirely, and that arm carries the property this suite exists for:
      * an engine which cannot honour a level has to SAY so rather than quietly run at another one. Each arm also cross-checks the
      * descriptor's own declaration against what the engine just did, so a descriptor claiming a level it refuses, or refusing one it claims,
      * fails here.
      */
    for level <- SqlClient.IsolationLevel.values.toList do
        s"a transaction under $level runs at that level or is refused outright" - {
            forEachBackend() { (backend, client, _) =>
                val honoured = backend.honouredIsolationLevels.contains(level)
                Abort.run[SqlException] {
                    client.transaction(Present(level), readOnly = false) {
                        backend.isolationIntrospectionSql match
                            case Present(sql) =>
                                client.query(sql).flatMap(rows => rows(0).decode[String](0).map(Maybe(_)))
                            case Absent =>
                                client.executeRaw("SELECT 1").andThen(Maybe.empty[String])
                    }
                }.map {
                    case Result.Success(Present(answer)) =>
                        val applied = SqlTestBackend.canonicalIsolationName(answer)
                        assert(
                            applied == expectedName(level),
                            s"${backend.label}: asked for $level, server applied $applied (raw answer $answer)"
                        )
                        assert(honoured, s"${backend.label} ran at $level, so its descriptor must declare it honoured")
                    case Result.Success(Absent) =>
                        assert(
                            honoured,
                            s"${backend.label} ran a $level transaction, so its descriptor must declare it honoured rather than refused"
                        )
                    case Result.Failure(_) =>
                        assert(
                            !honoured,
                            s"${backend.label} declares it honours $level, so the transaction must run rather than be refused"
                        )
                    case Result.Panic(t) =>
                        fail(s"${backend.label}: a $level transaction panicked with $t")
                }
            }
        }
    end for

    for level <- SqlClient.IsolationLevel.values.toList do
        s"a transaction under $level commits a normal write" - {
            forEachBackend(where = _.honouredIsolationLevels.contains(level)) { (_, client, _) =>
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

    // Bounded because the way this one fails is a WEDGE: a refusal that leaks the session leaves the leaf waiting on a statement that will
    // never run, and at the suite default that stalls the whole battery. A backend that refuses correctly answers in milliseconds.
    "a readOnly transaction rejects a write with the typed server error and writes nothing" - {
        forEachBackend(timeout = Present(30.seconds)) { (_, client, _) =>
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
                                    // Three spellings of one word. Engines word errors differently and the assertion is that the server
                                    // NAMES the condition, not that they agree on punctuation; requiring one spelling would report a
                                    // divergence that is not one.
                                    val worded = e.serverMessage.toLowerCase
                                    assert(
                                        worded.contains("read only") || worded.contains("read-only") || worded.contains("readonly"),
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
