package kyo

import kyo.Chunk
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.SqlServerException
import kyo.Test
import kyo.db.Idiom

class SqlExceptionTest extends Test:

    "SqlServerException factory dispatches 23xxx to constraint violation" in {
        SqlServerException("23505", "ERROR", "duplicate key") match
            case leaf: SqlServerConstraintViolationException =>
                assert(leaf.sqlState == "23505")
            case other => fail(s"expected SqlServerConstraintViolationException, got $other")
    }

    "SqlServerException factory dispatches 40001 to deadlock" in {
        SqlServerException("40001", "ERROR", "serialization failure") match
            case leaf: SqlServerDeadlockException => assert(leaf.sqlState == "40001")
            case other                            => fail(s"expected SqlServerDeadlockException, got $other")
    }

    "SqlServerException factory dispatches 40P01 to deadlock" in {
        SqlServerException("40P01", "ERROR", "deadlock detected") match
            case leaf: SqlServerDeadlockException => assert(leaf.sqlState == "40P01")
            case other                            => fail(s"expected SqlServerDeadlockException, got $other")
    }

    "SqlServerException factory dispatches 42xxx to syntax" in {
        SqlServerException("42601", "ERROR", "syntax error") match
            case leaf: SqlServerSyntaxException => assert(leaf.sqlState == "42601")
            case other                          => fail(s"expected SqlServerSyntaxException, got $other")
    }

    "SqlServerException factory dispatches 08xxx to connection" in {
        SqlServerException("08006", "FATAL", "connection failure") match
            case leaf: SqlServerConnectionException => assert(leaf.sqlState == "08006")
            case other                              => fail(s"expected SqlServerConnectionException, got $other")
    }

    "SqlServerException factory falls back to error for uncategorised sqlState" in {
        SqlServerException("99999", "ERROR", "unknown") match
            case leaf: SqlServerErrorException => assert(leaf.sqlState == "99999")
            case other                         => fail(s"expected SqlServerErrorException, got $other")
    }

    "SqlServerException carries the full field set through the factory" in {
        val ex = SqlServerException(
            sqlState = "23505",
            severity = "ERROR",
            message = "duplicate key value violates unique constraint",
            detail = Present("Key (id)=(42) already exists."),
            hint = Present("Change the id value."),
            position = Absent,
            extra = Map("table" -> "widgets", "constraint" -> "widgets_pkey"),
            sqlText = Present("INSERT INTO t VALUES ($1)"),
            paramCount = 1,
            connectionId = Present(99L)
        )
        val leaf = ex match
            case leaf: SqlServerConstraintViolationException => leaf
            case other                                       => fail(s"expected SqlServerConstraintViolationException, got $other")
        assert(leaf.sqlState == "23505")
        assert(leaf.severity == "ERROR")
        assert(leaf.serverMessage == "duplicate key value violates unique constraint")
        assert(leaf.detail == Present("Key (id)=(42) already exists."))
        assert(leaf.hint == Present("Change the id value."))
        assert(leaf.position == Absent)
        assert(leaf.extra("table") == "widgets")
        assert(leaf.extra("constraint") == "widgets_pkey")
        assert(leaf.paramCount == 1)
        assert(leaf.connectionId == Present(99L))
        assert(ex.getMessage.contains("[23505] ERROR: duplicate key value violates unique constraint"))
        assert(ex.getMessage.contains("Detail: Key (id)=(42) already exists."))
        assert(ex.getMessage.contains("Hint: Change the id value."))
        assert(ex.getMessage.contains("SQL: INSERT INTO t VALUES ($1)"))
        assert(ex.getMessage.contains("Params: 1"))
        assert(ex.getMessage.contains("ConnectionId: 99"))
    }

    "SqlConnectionPoolClosedException reports the closed pool" in {
        val ex = SqlConnectionPoolClosedException()
        assert(ex.getMessage.contains("pool is closed"))
    }

    "SqlConnectionAcquireTimeoutException carries the acquire timeout" in {
        val ex = SqlConnectionAcquireTimeoutException(5.seconds)
        assert(ex.acquireTimeout == 5.seconds)
        assert(ex.getMessage.contains("5"))
    }

    "SqlConnectionUrlParseException carries the raw URL and scheme" in {
        val ex = SqlConnectionUrlParseException("bogus://x", "bogus")
        assert(ex.url == "bogus://x")
        assert(ex.scheme == "bogus")
        assert(ex.getMessage.contains("bogus"))
    }

    "SqlDecodeColumnAbsentException reports column index" in {
        val ex = SqlDecodeColumnAbsentException(3)
        assert(ex.columnIndex == Present(3))
        assert(ex.columnName == Absent)
        assert(ex.getMessage.contains("Column at index 3 held no value; its target type is not Maybe"))
    }

    // A caller that looked the column up by name has no index, and the field says so rather than standing a
    // number in for it. Passing -1 instead would make the message logic test `columnIndex < 0` to decide whether
    // to print it, which is the type reading its own sentinel back.
    "SqlDecodeColumnAbsentException(columnName) reports the index as absent, not as a number" in {
        val ex = SqlDecodeColumnAbsentException("email")
        assert(ex.columnIndex == Absent)
        assert(ex.columnName == Present("email"))
        assert(ex.getMessage.contains("Column 'email' held no value; its target type is not Maybe"))
        assert(!ex.getMessage.contains("-1"))
    }

    "SqlDecodeColumnAbsentException(columnIndex, columnName) reports both" in {
        val ex = SqlDecodeColumnAbsentException(2, "email")
        assert(ex.columnIndex == Present(2))
        assert(ex.columnName == Present("email"))
        assert(ex.getMessage.contains("Column 'email' (index 2) held no value; its target type is not Maybe"))
    }

    // The other three leaves whose column index can be absent. Each renders without an index clause rather than
    // showing a sentinel like `-1`.
    "SqlDecodeColumnDecodeException without a column renders no index" in {
        val ex = SqlDecodeColumnDecodeException(Absent, new Exception("boom"))
        assert(ex.columnIndex == Absent)
        assert(ex.getMessage.contains("Column decode failed"))
        assert(!ex.getMessage.contains("-1"))
    }

    "SqlDecodeColumnDecodeException with a column names it" in {
        val ex = SqlDecodeColumnDecodeException(4, new Exception("boom"))
        assert(ex.columnIndex == Present(4))
        assert(ex.getMessage.contains("Column decode failed at index 4"))
    }

    "SqlDecodeEmptyStringForCharException without a column renders no index" in {
        val ex = SqlDecodeEmptyStringForCharException(Absent)
        assert(ex.columnIndex == Absent)
        assert(ex.getMessage.contains("An empty value cannot decode as Char"))
        assert(!ex.getMessage.contains("-1"))
    }

    "SqlDecodeEmptyStringForCharException with a column names it" in {
        val ex = SqlDecodeEmptyStringForCharException(Present(1))
        assert(ex.columnIndex == Present(1))
        assert(ex.getMessage.contains("Column at index 1 is empty; cannot decode as Char"))
    }

    "SqlDecodeMapAbsentValueException names the Scala value type the empty entry could not decode as" in {
        val ex = SqlDecodeMapAbsentValueException("String")
        assert(ex.scalaType == "String")
        assert(ex.getMessage.contains("Map entry held no value and is not decodable as String"))
    }

    // Asserting the WHOLE sentence, not a fragment plus a `!contains("postgres")`, and the reason is a property of `getMessage` rather
    // than a style choice. In development mode `KyoException.getMessage` returns the frame render, which embeds the construction site's
    // enclosing class and the surrounding source lines, so a negative over it tests the test file's own text: the same assertion inside
    // `PostgresParamWriterTest` fails on the `kyo.internal.postgres` in its own frame while the message is clean. A full-sentence
    // containment cannot be satisfied by a message that names a dialect, so it carries the property without reading the instrument.
    "SqlDecodeEmptyRangeException blames the decode target rather than the server that sent a well-formed value" in {
        val ex = SqlDecodeEmptyRangeException("Range[Int]")
        assert(ex.scalaType == "Range[Int]")
        assert(
            ex.getMessage.contains(
                "The empty range is not decodable as Range[Int]: a pair of bounds cannot express a range that holds no values"
            ),
            ex.getMessage
        )
    }

    "SqlConnectionConnectFailedException propagates the underlying cause" in {
        val cause                 = new RuntimeException("boom")
        val ex                    = SqlConnectionConnectFailedException("db.example.com", 5432, cause)
        val propagated: Throwable = ex.getCause
        assert(propagated eq cause)
    }

    "SqlDecodeColumnNotFoundException reports column name" in {
        val ex = SqlDecodeColumnNotFoundException("id")
        assert(ex.columnName == "id")
        assert(ex.getMessage.contains("id"))
    }

    "SqlDecodeColumnNotFoundException names the row's own columns" in {
        val ex = SqlDecodeColumnNotFoundException("executionId", Chunk("execution_id", "flow_id", "status"))
        assert(ex.columnName == "executionId")
        assert(ex.availableColumns == Chunk("execution_id", "flow_id", "status"))
        assert(ex.message.contains("execution_id, flow_id, status"), ex.message)
    }

    /** A field name that matches a column up to casing is the shape a SqlNaming that did not reach the query leaves behind.
      *
      * The given is resolved at the call site of the run, so one declared in an object the run is not inside of (a companion, another
      * method) is not applied, and every field then looks for its verbatim Scala name against snake_case columns. The failure is a
      * column-not-found at the first decode, which said only that the column was missing.
      */
    "SqlDecodeColumnNotFoundException points at the casing when only the casing differs" in {
        val ex = SqlDecodeColumnNotFoundException("executionId", Chunk("execution_id", "flow_id"))
        assert(ex.message.contains("differs from 'executionId' only in casing"), ex.message)
        assert(ex.message.contains("SqlNaming"), ex.message)
        assert(ex.message.contains("call site of the run"), ex.message)
    }

    "SqlDecodeColumnNotFoundException says nothing about casing when nothing matches" in {
        val ex = SqlDecodeColumnNotFoundException("total", Chunk("execution_id", "flow_id"))
        // The rendered message carries the raising frame's source context, so the absence is asserted on the hint's own
        // words rather than on "casing", which the enclosing leaf name would put in the frame regardless.
        assert(!ex.message.contains("only in casing"), ex.message)
        assert(ex.message.contains("the row has execution_id, flow_id"), ex.message)
    }

    "SqlDecodeColumnNotFoundException with no row to report keeps its original message" in {
        val ex = SqlDecodeColumnNotFoundException("id")
        assert(ex.message.contains("Column 'id' not found in row"), ex.message)
        assert(!ex.message.contains("the row has"), ex.message)
    }

    "SqlRequestMysqlLocalInfileRequiresLoadApiException names the dedicated API" in {
        val ex = SqlRequestMysqlLocalInfileRequiresLoadApiException()
        assert(ex.getMessage.contains("LOAD DATA LOCAL INFILE"))
        assert(ex.getMessage.contains("loadLocalInfile"))
    }

    "SqlRequestRsaOaepException resolves and carries position, tag, and cause" in {
        val cause = new RuntimeException("cipher init failed")
        val ex    = SqlRequestRsaOaepException("encrypt", "sha256", cause)
        assert(ex.position == "encrypt")
        assert(ex.tag == "sha256")
        assert(ex.getMessage.contains("RSA-OAEP failed at encrypt (tag=sha256)"))
        assert(ex.getCause eq cause)
    }

    "SqlConnectionBackendMismatchException names the request and the active dialect" in {
        val ex = SqlConnectionBackendMismatchException("postgres", Idiom.Id("mysql"), "copyIn")
        assert(ex.requested == "postgres")
        assert(ex.activeDriver == Idiom.Id("mysql"))
        assert(ex.operation == "copyIn")
        assert(ex.getMessage.contains("Operation 'copyIn' requires 'postgres', but the active driver is 'mysql'"))
    }

    "SqlConnectionUrlOptionException names the key, the value, and what the key accepts" in {
        val ex = SqlConnectionUrlOptionException("sslmode", "banana", "one of disable, allow, prefer, require, verify-ca, verify-full")
        assert(ex.key == "sslmode")
        assert(ex.value == "banana")
        assert(
            ex.getMessage.contains(
                "URL option 'sslmode' has unsupported value 'banana'; expected one of disable, allow, prefer, require, verify-ca, verify-full"
            )
        )
    }

    "SqlConnectionUnsupportedSchemeException lists the registered schemes" in {
        val ex = SqlConnectionUnsupportedSchemeException("oracle", Chunk("postgres", "mysql"))
        assert(ex.scheme == "oracle")
        assert(ex.getMessage.contains("No backend registered for URL scheme 'oracle'. Registered schemes: postgres, mysql"))
    }

    "SqlStaticRenderMissingDialectException lists the rendered dialects" in {
        val ex = SqlStaticRenderMissingDialectException(Idiom.Id("mysql"), Chunk(Idiom.Id("postgres")))
        assert(ex.dialect == Idiom.Id("mysql"))
        assert(ex.available == Chunk(Idiom.Id("postgres")))
        assert(ex.getMessage.contains("No statically rendered SQL for dialect 'mysql'. Rendered dialects: postgres"))
    }

    "SqlUnsupportedTypeOnBackendException names the owning and the active dialect" in {
        val ex = SqlUnsupportedTypeOnBackendException(Idiom.Id("postgres"), "HSTORE", Idiom.Id("mysql"))
        assert(ex.dialect == Idiom.Id("postgres"))
        assert(ex.activeDialect == Idiom.Id("mysql"))
        assert(ex.getMessage.contains("Type 'HSTORE' belongs to dialect 'postgres' and is not supported on the active 'mysql' backend"))
    }

    "a client-side cancel budget expiry is a connection fault carrying the budget, not a server error" in {
        // A reclaim timeout must not carry PostgreSQL's 57014. That SQLSTATE is a statement about what the server
        // reported, and the only construction site here is the client's own budget expiring, so carrying it would
        // leave a caller matching on sqlState unable to tell a real server-side cancellation from a client-side
        // give-up.
        val ex = SqlConnectionCancelTimeoutException(2.seconds)
        assert(ex.cancelTimeout == 2.seconds, "the failure must name which budget expired")
        assert(
            ex.getMessage.contains("A client-side budget of 2.seconds for stopping or closing the session expired"),
            s"message must describe a client-side budget rather than a server report, was ${ex.getMessage}"
        )
        // Retry advice is the one thing a SQLSTATE would have carried that a caller acts on, so the marker states
        // it directly. Pinned at compile time because the mixin is part of the type: a runtime test for it cannot
        // fail, which the compiler says out loud (the fallback case is unreachable), whereas this fails the day
        // someone drops `with SqlRetryable` and silently stops retry applying to cancel timeouts.
        assert(
            scala.compiletime.testing.typeChecks(
                "summon[kyo.SqlConnectionCancelTimeoutException <:< kyo.SqlRetryable]"
            ),
            "the surviving leaf must still be marked SqlRetryable"
        )
    }

    "there is no server-family cancel-timeout leaf to construct" in {
        // A client-fabricated SqlServerException is unconstructible by design, and `SqlServerException.apply`
        // taking ten decoded wire fields is what makes that structural rather than a convention: a leaf with an
        // empty parameter list cannot have come off the wire.
        assert(!scala.compiletime.testing.typeChecks("kyo.SqlServerCancelTimeoutException()"))
    }

    "SqlDecodeUnknownTypeException names the dialect and the unresolved type token" in {
        val ex = SqlDecodeUnknownTypeException(Idiom.Id("postgres"), "3802")
        assert(ex.dialect == Idiom.Id("postgres"))
        assert(ex.typeToken == "3802")
        assert(ex.getMessage.contains("No codec registered on dialect 'postgres' for type token '3802'"))
    }

    "SqlDecodeCodecMismatchException reports the decoded and the expected class" in {
        val ex = SqlDecodeCodecMismatchException(Idiom.Id("postgres"), "1114", "java.lang.String", "java.time.Instant")
        assert(ex.dialect == Idiom.Id("postgres"))
        assert(ex.decodedClass == "java.lang.String")
        assert(ex.expectedClass == "java.time.Instant")
        assert(
            ex.getMessage.contains(
                "Codec on dialect 'postgres' for type token '1114' produced a java.lang.String, expected java.time.Instant"
            )
        )
    }

    "SqlRequestAdvisoryLockException with a timeout reports the key and the wait budget" in {
        val ex = SqlRequestAdvisoryLockException(42L, Present(5.seconds))
        assert(ex.key == 42L)
        assert(ex.timeout == Present(5.seconds))
        assert(ex.getMessage.contains(s"Advisory lock 42 could not be acquired within ${5.seconds.show}"))
    }

    "SqlRequestAdvisoryLockException without a timeout omits the wait budget" in {
        val ex = SqlRequestAdvisoryLockException(7L, Absent)
        assert(ex.key == 7L)
        assert(ex.timeout == Absent)
        assert(ex.getMessage.contains("Advisory lock 7 could not be acquired"))
    }

    "SqlRequestNotificationChannelNulException locates the NUL without echoing the name" in {
        val ex = SqlRequestNotificationChannelNulException(3)
        assert(ex.index == 3)
        assert(ex.getMessage.contains("cannot contain a NUL character; found one at index 3"))
        // The message must not carry the byte itself. A NUL reaching a log or a terminal is invisible there, and
        // it is what turns a text file binary to every ordinary grep.
        assert(!ex.getMessage.exists(_ == 0.toChar), "the message must not contain a NUL byte")
    }

    "SqlConnectionUserRequiredException names the scheme whose handshake demanded the user" in {
        val ex = SqlConnectionUserRequiredException("mysql")
        assert(ex.scheme == "mysql")
        assert(ex.getMessage.contains("Connecting to a 'mysql' database requires a user name, but none was configured"))
        // Carries the same marker as its password sibling, so one `Abort.recover[SqlAuthenticationFailure]` covers a
        // missing user and a missing password rather than only the one the caller happened to think of. Pinned at
        // compile time for the reason the cancel-timeout leaf above gives: the mixin is part of the type, so a runtime
        // test for it cannot fail, while this fails the day someone drops `with SqlAuthenticationFailure`.
        assert(
            scala.compiletime.testing.typeChecks(
                "summon[kyo.SqlConnectionUserRequiredException <:< kyo.SqlAuthenticationFailure]"
            ),
            "a missing user is a credential failure and must stay marked SqlAuthenticationFailure"
        )
    }

    "SqlUnsupportedDialectFeatureException reports a version-gated feature" in {
        val ex = SqlUnsupportedDialectFeatureException(
            "INTERSECT / EXCEPT",
            Idiom.Id("mysql"),
            Present(Idiom.ServerVersion(8, 0, 31)),
            Present(Idiom.ServerVersion(5, 7, 44))
        )
        assert(ex.feature == "INTERSECT / EXCEPT")
        assert(ex.dialect == Idiom.Id("mysql"))
        assert(ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 31)))
        assert(ex.serverVersion == Present(Idiom.ServerVersion(5, 7, 44)))
        assert(ex.getMessage.contains("Feature 'INTERSECT / EXCEPT' requires mysql server version 8.0.31; connected server is 5.7.44"))
    }

    "SqlUnsupportedDialectFeatureException reports RETURNING as unsupported at every version" in {
        val ex = SqlUnsupportedDialectFeatureException(
            "RETURNING",
            Idiom.Id("mysql"),
            Absent,
            Present(Idiom.ServerVersion(8, 4, 0))
        )
        assert(ex.requiredVersion == Absent)
        assert(ex.getMessage.contains("Feature 'RETURNING' is not supported on mysql (server version 8.4.0)"))
    }

    "SqlUnsupportedDialectFeatureException reports the upsert WHERE clause as unsupported at every version" in {
        val ex = SqlUnsupportedDialectFeatureException(
            "ON CONFLICT ... WHERE",
            Idiom.Id("mysql"),
            Absent,
            Present(Idiom.ServerVersion(8, 4, 0))
        )
        assert(ex.feature == "ON CONFLICT ... WHERE")
        assert(ex.getMessage.contains("Feature 'ON CONFLICT ... WHERE' is not supported on mysql (server version 8.4.0)"))
    }

    "SqlUnsupportedDialectFeatureException omits an unspecified server version" in {
        val ex = SqlUnsupportedDialectFeatureException(
            "LATERAL",
            Idiom.Id("mysql"),
            Present(Idiom.ServerVersion(8, 0, 14)),
            Absent
        )
        assert(ex.serverVersion == Absent)
        assert(ex.getMessage.contains("Feature 'LATERAL' requires mysql server version 8.0.14"))
    }

    // The two composite-element leaves. Both exist because the dialect-feature leaf above formats every message as
    // "Feature 'X' is not supported on <dialect>", and neither of these refusals belongs to a dialect: the guards sit in both engines'
    // writers and refuse the same two things for the same reason. Each leaf's whole sentence is asserted here, which is where "names no
    // dialect" is pinned; see the note above SqlDecodeEmptyRangeException for why the two engines' own suites cannot assert that half.

    "SqlUnsupportedMultiColumnElementException names the column count and no dialect" in {
        val ex = SqlUnsupportedMultiColumnElementException("Tuple2", 2)
        assert((ex.scalaType, ex.columnCount) == ("Tuple2", 2))
        assert(
            ex.getMessage.contains(
                "Value of type 'Tuple2' occupies 2 SQL columns, and one element of a composite value holds exactly one. " +
                    "Multi-column schemas (SqlSchema.ofMulti, and a derived case class) can be read as a whole result row, " +
                    "but cannot be an element of a composite value."
            ),
            ex.getMessage
        )
    }

    "SqlUnsupportedAbsentElementException says absence has no spelling inside a composite, naming no dialect" in {
        val ex = SqlUnsupportedAbsentElementException("Maybe")
        assert(ex.scalaType == "Maybe")
        assert(
            ex.getMessage.contains(
                "A value of type 'Maybe' is absent, and an element of a composite value cannot be. " +
                    "A composite payload addresses each element by its byte length and carries no absence flag, " +
                    "so an absent element has no spelling."
            ),
            ex.getMessage
        )
    }

    "SqlConnectionUnsupportedAuthMethodException covers MySQL authentication plugin names" in {
        val ex = SqlConnectionUnsupportedAuthMethodException("authentication_windows_client")
        assert(ex.mechanism == "authentication_windows_client")
        assert(ex.getMessage.contains("Unsupported authentication mechanism: authentication_windows_client"))
    }

    "the consolidated and deleted leaf names no longer resolve" in {
        // The no-active-client leaf sits here rather than above because the state it would name is unrepresentable:
        // the client a statement runs on is the `DB` effect, so a statement with none supplied does not compile and
        // there is no run-time moment left for such a failure to be raised at. `DBTest` pins that compile error.
        typeCheckFailure("""SqlConnectionNoActiveClientException()""")
        typeCheckFailure("""SqlRequestMysqlTxRequiresConnectionApiException("query")""")
        typeCheckFailure("""SqlConnectionCachingSha2EmptyPayloadException()""")
        typeCheckFailure("""SqlRequestMysqlGetLockException("42", 5, "42")""")
        typeCheckFailure("""SqlConnectionUnsupportedAuthPluginException("caching_sha2_password")""")
        typeCheckFailure("""SqlUnsupportedMysqlVersionFeatureException("LATERAL", "8.0.14", "5.7.44")""")
        typeCheckFailure("""SqlUnsupportedReturningOnMysqlException()""")
        typeCheckFailure("""SqlUnsupportedUpsertWhereClauseOnMysqlException()""")
    }

    "Render[SqlException] renders a SqlConnectionException to a human-readable string" in {
        val ex       = SqlConnectionPoolClosedException()
        val rendered = Render[SqlException].asString(ex)
        assert(rendered.contains("pool is closed"))
    }

end SqlExceptionTest
