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
        assert(url.address.driver == "postgres")
        assert(url.address.host == "localhost")
        assert(url.address.port == 5432)
        assert(url.address.database == "mydb")
        assert(url.address.user == "alice")
        assert(url.password == "secret")
    }
    "missing scheme returns Result.Failure with correct message" in {
        val raw    = "localhost:5432/mydb"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
                assert(e.scheme == "", s"expected empty scheme, got: ${e.scheme}")
            case other =>
                fail(s"Expected Result.Failure(SqlConnectionUrlParseException) but got: $other")
        end match
    }
    "unsupported scheme returns Result.Failure with correct message" in {
        val raw    = "ftp://user:pw@host:5432/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
                assert(e.scheme == "ftp", s"expected scheme 'ftp', got: ${e.scheme}")
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
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
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
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
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
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
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
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
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
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
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
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
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
                assert(e.rawUrl == raw, s"expected rawUrl '$raw', got: ${e.rawUrl}")
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

end SqlConfigUrlTest
