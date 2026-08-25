package kyo

/** Conformance battery for the mapping from a server's SQLSTATE to kyo-sql's typed [[SqlServerException]] family, run against every
  * discovered backend through [[SqlBackendTest.forEachBackend]].
  *
  * The typed family is the cross-engine contract: [[SqlServerException.apply]] dispatches on the SQLSTATE class (class 23 to
  * [[SqlServerConstraintViolationException]], class 42 to [[SqlServerSyntaxException]]), and every engine follows the ANSI class
  * conventions for these conditions, so a leaf asserts the family by matching the typed leaf and needs no engine-specific value. Where a
  * leaf pins the server's exact SQLSTATE (to prove the backend relays the server's own code rather than fabricating one), it reads the
  * expected code from the backend descriptor's capability, so the suite names no engine and carries no SQLSTATE literal.
  *
  * Coverage:
  *   - an undefined relation surfaces as the syntax/access family, carrying the server's own SQLSTATE, the failing SQL, a zero bind
  *     count, the server session id, and a frame pointing at the call site, and leaves the pooled connection reusable;
  *   - a malformed statement surfaces as the same syntax/access family under a different SQLSTATE within the class;
  *   - a duplicate key surfaces as the integrity-violation family, carrying the [[SqlIntegrityViolation]] marker callers recover on and
  *     the server's own class-23 SQLSTATE.
  */
class SqlErrorMappingConformanceTest extends SqlBackendTest:

    "an undefined relation maps to the syntax/access family, carrying the server's own SQLSTATE and enriched context" - {
        forEachBackend(SqlConfig(maxConnections = 2)) { (backend, client, _) =>
            val failSql = "SELECT 1 FROM no_such_table"
            // Frame.derive pins the call site to this suite so the enriched frame assertion below is meaningful.
            Abort.run[SqlException](client.executeRaw(failSql)(using Frame.derive)).flatMap {
                case Result.Failure(e: SqlServerSyntaxException) =>
                    // Typed family: both engines report an undefined relation under SQLSTATE class 42, which the dispatcher maps to
                    // SqlServerSyntaxException regardless of the exact code, so matching the typed leaf is the cross-engine assertion.
                    assert(
                        e.sqlState == backend.tableNotFoundSqlState,
                        s"expected the backend to relay the server's own undefined-relation SQLSTATE ${backend.tableNotFoundSqlState}, got ${e.sqlState}"
                    )
                    assert(
                        e.sqlText.exists(_.contains("no_such_table")),
                        s"expected the failing SQL to be carried on the error, got ${e.sqlText}"
                    )
                    assert(
                        e.paramCount == 0,
                        s"expected no bound parameters on a simple statement, got ${e.paramCount}"
                    )
                    assert(
                        e.connectionId.isDefined,
                        s"expected the server session id to be present on the error, got ${e.connectionId}"
                    )
                    val fileName = e.frame.position.fileName
                    assert(
                        fileName.contains("SqlErrorMappingConformanceTest"),
                        s"expected the error frame to point at this suite, got $fileName"
                    )
                    // The session must return to the pool healthy: a connection left desynchronised by the error round can answer with
                    // the wrong row, so the probe asserts the value, not mere arrival.
                    client.query("SELECT 1").flatMap { rows =>
                        assert(rows.size == 1, s"the probe query after the error must return exactly one row, got ${rows.size}")
                        rows(0).decode[Int](0).map(v => assert(v == 1, s"the probe query must return 1, got $v"))
                    }
                case Result.Failure(other) =>
                    fail(s"expected a class-42 SqlServerSyntaxException for an undefined relation, got: $other")
                case Result.Success(_) =>
                    fail("expected the query against a missing relation to fail, but it succeeded")
                case Result.Panic(t) =>
                    fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "a malformed statement maps to the same syntax/access family under a different SQLSTATE" - {
        forEachBackend(SqlConfig(maxConnections = 2)) { (backend, client, _) =>
            val badSql = "SLECT 1"
            Abort.run[SqlException](client.executeRaw(badSql)(using Frame.derive)).map {
                case Result.Failure(e: SqlServerSyntaxException) =>
                    // A parse error is a distinct condition from an undefined relation, yet both land in SQLSTATE class 42 and therefore
                    // in the same typed family: the family is keyed on the class, not the exact code. The class is read from the
                    // undefined-relation capability so this leaf carries no SQLSTATE literal of its own.
                    assert(
                        e.sqlState.take(2) == backend.tableNotFoundSqlState.take(2),
                        s"expected the malformed statement to share the syntax/access SQLSTATE class ${backend.tableNotFoundSqlState.take(2)}, got ${e.sqlState}"
                    )
                case Result.Failure(other) =>
                    fail(s"expected a class-42 SqlServerSyntaxException for a malformed statement, got: $other")
                case Result.Success(_) =>
                    fail("expected the malformed statement to fail, but it succeeded")
                case Result.Panic(t) =>
                    fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "a duplicate key maps to the integrity-violation family, carrying the server's own SQLSTATE" - {
        forEachBackend(SqlConfig(maxConnections = 2)) { (backend, client, _) =>
            val table     = backend.quoteIdent("err_map_dup")
            val createSql = s"CREATE TABLE $table (k INT PRIMARY KEY)"
            val insertSql = s"INSERT INTO $table (k) VALUES (1)"
            for
                _      <- client.executeRaw(createSql)
                _      <- client.executeRaw(insertSql)
                result <- Abort.run[SqlException](client.executeRaw(insertSql)(using Frame.derive))
            yield result match
                case Result.Failure(e) =>
                    // Both engines report a duplicate key under SQLSTATE class 23, which the dispatcher maps to the constraint-violation
                    // leaf; that leaf is the SqlIntegrityViolation marker callers recover on. The marker is asserted on the untyped error
                    // so it is a runtime check, and the server's own class-23 code is then read from the leaf.
                    assert(
                        e.isInstanceOf[SqlIntegrityViolation],
                        s"expected a duplicate key to carry the SqlIntegrityViolation marker, got ${e.getClass.getName}"
                    )
                    e match
                        case sv: SqlServerConstraintViolationException =>
                            assert(
                                sv.sqlState == backend.uniqueViolationSqlState,
                                s"expected the backend to relay the server's own unique-violation SQLSTATE ${backend.uniqueViolationSqlState}, got ${sv.sqlState}"
                            )
                        case other =>
                            fail(s"expected a SqlServerConstraintViolationException for a duplicate key, got: ${other.getClass.getName}")
                    end match
                case Result.Success(_) =>
                    fail("expected the duplicate key insert to fail, but it succeeded")
                case Result.Panic(t) =>
                    fail(s"unexpected panic: ${t.getMessage}")
            end for
        }
    }

end SqlErrorMappingConformanceTest
