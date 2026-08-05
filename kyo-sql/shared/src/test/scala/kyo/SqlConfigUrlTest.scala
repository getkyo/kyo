package kyo

import kyo.Frame
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.SqlConnectionException
import kyo.SqlConnectionUrlParseException

class SqlConfigUrlTest extends Test:
    "valid URL returns Result.Success" in {
        val raw    = "postgres://alice:secret@localhost:5432/mydb"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isSuccess)
        val url = result.getOrElse(???)
        assert(url.address.scheme == "postgres")
        assert(url.address.host == "localhost")
        assert(url.address.port == 5432)
        assert(url.address.database == "mydb")
        assert(url.address.user == Present("alice"))
        assert(url.password == Present("secret"))
    }
    "missing scheme returns Result.Failure with correct message" in {
        val raw    = "localhost:5432/mydb"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "", s"expected empty scheme, got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    // Parsing does not judge the scheme: which schemes are openable is decided by the backends on the classpath,
    // so a scheme this build has no backend for still parses, and SqlClient.init is what rejects it. Keeping the
    // judgment there is what lets a backend declare an alias like `postgresql` and be found under it.
    "a scheme no backend claims still parses, and carries through verbatim" in {
        val raw    = "ftp://user:pw@host:5432/db"
        val result = SqlConfig.Url.parse(raw)
        result match
            case Result.Success(url) =>
                assert(url.address.scheme == "ftp", s"expected scheme 'ftp', got: ${url.address.scheme}")
                assert(url.address.host == "host")
                assert(url.address.database == "db")
            case other =>
                fail(s"Expected Result.Success for an unclaimed scheme but got: $other")
        end match
    }

    "a declared alias parses as its own scheme, so the registry can resolve it" in {
        val result = SqlConfig.Url.parse("postgresql://alice:secret@localhost:5432/mydb")
        result match
            case Result.Success(url) =>
                assert(url.address.scheme == "postgresql", s"expected scheme 'postgresql', got: ${url.address.scheme}")
                assert(url.address.port == 5432)
            case other =>
                fail(s"Expected Result.Success for the postgresql alias but got: $other")
        end match
    }

    "an empty scheme is still a parse failure" in {
        val raw    = "://user:pw@host:5432/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "", s"expected empty scheme, got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "missing database name returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@localhost:5432"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "postgres", s"expected scheme 'postgres', got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "malformed IPv6 host returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@[::1:5432/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "postgres", s"expected scheme 'postgres', got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "IPv6 host missing colon before port returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@[::1]5432/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "postgres", s"expected scheme 'postgres', got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "non-IPv6 host without colon returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@localhost/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "postgres", s"expected scheme 'postgres', got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "non-IPv6 host with empty port string returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@localhost:/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "postgres", s"expected scheme 'postgres', got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "non-numeric port returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@localhost:notaport/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "postgres", s"expected scheme 'postgres', got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "non-numeric IPv6 port returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@[::1]:notaport/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(
                    e.url == SqlConnectionUrlParseException.redactUserInfo(raw),
                    s"the stored url must be the redacted input, got: ${e.url}"
                )
                assert(e.scheme == "postgres", s"expected scheme 'postgres', got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "parseOptions returns Present for a present option key" in {
        val raw    = "postgres://alice:pw@localhost:5432/mydb?application_name=myapp"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isSuccess)
        val url = result.getOrElse(???)
        assert(url.options.applicationName == Present("myapp"))
    }
    "parseOptions returns Absent for an absent option key" in {
        val raw    = "postgres://alice:pw@localhost:5432/mydb"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isSuccess)
        val url = result.getOrElse(???)
        assert(url.options.applicationName == Absent)
    }

    "a URL with no colon in the userinfo declares no password" in {
        val url = SqlConfig.Url.parse("postgres://alice@localhost:5432/mydb").getOrThrow
        assert(url.address.user == Present("alice"))
        assert(url.password == Absent)
    }
    "a URL with a colon and nothing after it declares an empty password" in {
        val url = SqlConfig.Url.parse("postgres://alice:@localhost:5432/mydb").getOrThrow
        assert(url.address.user == Present("alice"))
        assert(url.password == Present(""))
    }
    "a URL with no userinfo at all declares neither user nor password" in {
        val url = SqlConfig.Url.parse("postgres://localhost:5432/mydb").getOrThrow
        assert(url.address.user == Absent)
        assert(url.password == Absent)
    }
    // The distinction the user field's `Maybe` type exists for, and the one a plain `String` cannot express: an
    // `@` with nothing before it names an empty user, while no `@` names no user at all. The `@` declares the
    // field exactly as the `:` declares the password above.
    "an @ with nothing before it declares an empty user, which is not the same as naming none" in {
        val declaredEmpty = SqlConfig.Url.parse("postgres://@localhost:5432/mydb").getOrThrow
        val named         = SqlConfig.Url.parse("postgres://localhost:5432/mydb").getOrThrow
        assert(declaredEmpty.address.user == Present(""))
        assert(named.address.user == Absent)
        assert(declaredEmpty.address.user != named.address.user)
        assert(declaredEmpty.password == Absent)
    }
    // The colon still governs the password inside a declared-empty userinfo, so `:secret@` names an empty
    // user with a real credential. This is the one spelling where both delimiters are present and the user
    // half is empty, and it has to keep the password rather than losing it to the empty-user branch.
    "a userinfo of just :password declares an empty user and a real password" in {
        val url = SqlConfig.Url.parse("postgres://:secret@localhost:5432/mydb").getOrThrow
        assert(url.address.user == Present(""))
        assert(url.password == Present("secret"))
    }

    "toString redacts the password" in {
        val url = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb").getOrThrow
        val out = url.toString
        assert(!out.contains("secret"), s"password leaked into toString: $out")
        assert(out.contains("***"))
    }
    // The third case of a three-case render, and the one this type change introduces. A declared credential is
    // redacted whatever its length, so an empty one prints `***` like any other: what it was is the secret, and
    // that it was supplied is what the slot records. Asserted positionally for the same reason as the leaf below:
    // `!out.contains("Absent")` would be the obvious second check and it is unsatisfiable, because `Options`
    // renders five `Absent`s of its own into this very string.
    "toString redacts a declared empty password rather than reporting it as absent" in {
        val url = SqlConfig.Url.parse("postgres://alice:@localhost:5432/mydb").getOrThrow
        val out = url.toString
        assert(out == s"Url(${url.address},***,${url.options})", s"a declared empty password is still redacted: $out")
    }
    // The expectation is built from the two neighbouring fields rather than written out, because `Options`
    // renders five `Absent`s of its own: a bare `out.contains("Absent")` is satisfied by those and holds for
    // a present password too, so it would assert nothing. Pinning the slot is what discriminates.
    "toString reports an absent password as absent rather than redacting one that is not there" in {
        val url = SqlConfig.Url.parse("postgres://alice@localhost:5432/mydb").getOrThrow
        val out = url.toString
        assert(out == s"Url(${url.address},Absent,${url.options})", s"an absent password should be reported as absent: $out")
        assert(!out.contains("***"), s"nothing was supplied, so there is nothing to redact: $out")
    }

    // --- Redaction regression guards ---

    "a parse failure on a URL with a malformed scheme separator still redacts the password" in {
        // No `://` at all, which was exactly the input the redaction used to skip.
        val raw = "postgres:/user:hunter2@host:5432/db"
        SqlConfig.Url.parse(raw) match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                // Only the crafted first line: in development mode getMessage appends the raise site's source
                // snippet, which here is this test's own fixture line and legitimately contains the password.
                val crafted = e.getMessage.linesIterator.next()
                assert(!crafted.contains("hunter2"), s"the password must not reach the message: $crafted")
                assert(!e.url.contains("hunter2"), s"the stored url must not carry the password: ${e.url}")
                assert(e.url.contains(":***@"), s"the mask must be visible where the password was: ${e.url}")
            case other =>
                fail(s"expected a parse failure, got: $other")
        end match
    }

    "a password containing an at-sign parses whole" in {
        // RFC 3986: the LAST at-sign ends the userinfo. Truncating at the first one would misread the host and
        // silently hand the server a wrong credential.
        val url = SqlConfig.Url.parse("postgres://alice:se@cret@localhost:5432/mydb").getOrThrow
        assert(url.password == Present("se@cret"), s"the whole password must survive, got: ${url.password}")
        assert(url.address.host == "localhost", s"the host must start after the last at-sign, got: ${url.address.host}")
    }

    "toString masks unclaimed option values" in {
        // `extra` holds every pair no field claims; `sslpassword` is the libpq client-key passphrase.
        val url = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb?sslpassword=hunter2").getOrThrow
        assert(!url.toString.contains("hunter2"), s"unclaimed option values must not render: $url")
        assert(!url.toString.contains("secret"), s"the password must not render: $url")
    }

end SqlConfigUrlTest
