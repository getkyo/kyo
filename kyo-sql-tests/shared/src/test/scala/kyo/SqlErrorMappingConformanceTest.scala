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

    /** A violated CHECK constraint carries the same integrity marker a duplicate key does.
      *
      * Measured, and this one escapes the dispatcher entirely: PostgreSQL reports a CHECK violation under SQLSTATE `23514` while MySQL
      * reports errno 3819 under `HY000`. Dispatch is by SQLSTATE prefix, so the `23` family catches the PostgreSQL case and the `HY` one
      * falls through to the generic server error. A caller recovering on [[SqlIntegrityViolation]], which the scaladoc invites, handles a
      * constraint failure on one engine and misses the identical failure on the other.
      *
      * Not a capability difference: both engines enforce CHECK constraints, and MySQL relays its own errno in `extra("code")`, which the
      * dispatcher does not consult. Its unique and not-null violations both carry `23000`, so prefix dispatch already works for those two
      * and CHECK is the specific escapee, which is why the duplicate-key leaf above passes today and this one does not.
      *
      * The marker is what is asserted rather than the SQLSTATE. Which code each engine reports is the engine's business; that a caller can
      * recover the FAILURE CLASS is the contract this module owes.
      */
    "a violated CHECK constraint maps to the integrity-violation family" - {
        forEachBackend(SqlConfig(maxConnections = 2)) { (backend, client, _) =>
            val table = backend.quoteIdent("err_map_check")
            for
                _      <- client.executeRaw(s"CREATE TABLE $table (v INT CHECK (v > 0))")
                result <- Abort.run[SqlException](client.executeRaw(s"INSERT INTO $table (v) VALUES (-1)")(using Frame.derive))
            yield result match
                case Result.Failure(e) =>
                    assert(
                        e.isInstanceOf[SqlIntegrityViolation],
                        s"${backend.label}: expected a CHECK violation to carry the SqlIntegrityViolation marker, got ${e.getClass.getName}"
                    )
                case Result.Success(_) =>
                    fail(s"${backend.label}: expected the CHECK violation to fail the insert, but it succeeded")
                case Result.Panic(t) =>
                    fail(s"${backend.label}: unexpected panic: ${t.getMessage}")
            end for
        }
    }

    /** A statement with no SQL in it is refused by the client, identically on every backend.
      *
      * The engines disagreed here and neither answer was useful: one has a protocol response for an empty statement and reported success with
      * no affected rows, the other rejected it as a syntax error. So the same call was silence on one deployment and a failure on the other,
      * for what is a caller mistake on both.
      *
      * Since no driver change makes the second engine accept it, the conformable answer is the narrower one, and it is given before a
      * connection is reached: [[kyo.SqlRequestEmptyStatementException]] rather than either server's. The answer is pinned rather than merely
      * compared, because two engines agreeing on a SERVER error would satisfy agreement too, and that is the outcome the client guard exists
      * to replace.
      */
    "a statement with no SQL in it is refused by the client" in {
        agreeAcrossBackends(expected = Present("refused with SqlRequestEmptyStatementException")) { (_, client, _) =>
            client.executeRaw("")(using Frame.derive).map(affected => s"the empty statement affected $affected rows")
        }
    }

    /** A statement of nothing but whitespace is refused the same way as an empty one.
      *
      * Beside the leaf above because it is the form a caller actually reaches: SQL assembled from a template or read from a file arrives with
      * a newline in it, and a guard that tested emptiness alone would let it through to the divergence.
      */
    "a statement of only whitespace is refused by the client" in {
        agreeAcrossBackends(expected = Present("refused with SqlRequestEmptyStatementException")) { (_, client, _) =>
            client.executeRaw("  \n\t ")(using Frame.derive).map(affected => s"the blank statement affected $affected rows")
        }
    }

    /** An empty FRAGMENT is refused too, which is the lane the raw-String guard cannot see.
      *
      * `sql""` renders an empty statement and reaches the wire through the executable path rather than through the raw-String entry points,
      * so guarding only those left the divergence open on the form a caller is most likely to produce by accident: a fragment interpolated
      * from a value that turned out to be empty.
      */
    "an empty fragment is refused by the client" in {
        agreeAcrossBackends(expected = Present("refused with SqlRequestEmptyStatementException")) { (_, client, _) =>
            client.query(sql"")(using Frame.derive).map(rows => s"the empty fragment answered ${rows.size} rows")
        }
    }

end SqlErrorMappingConformanceTest
