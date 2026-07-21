package kyo

import kyo.Frame
import kyo.Maybe.Absent
import kyo.Maybe.Present

class SqlConfigUrlTest extends Test:
    "valid URL returns Result.Success" in {
        val raw    = "postgres://alice:secret@localhost:5432/mydb"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isSuccess)
        val url = result.getOrElse(???)
        assert(url.address.driver == "postgres")
        assert(url.address.host == "localhost")
        assert(url.address.port == 5432)
        assert(url.address.db == "mydb")
        assert(url.address.user == "alice")
        assert(url.password == "secret")
    }
    "missing scheme returns Result.Failure with correct message" in {
        val raw    = "localhost:5432/mydb"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == s"Missing scheme in URL: $raw")
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
        end match
    }
    "unsupported scheme returns Result.Failure with correct message" in {
        val raw    = "ftp://user:pw@host:5432/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == "Unsupported scheme 'ftp'; expected 'postgres' or 'mysql'")
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
        end match
    }
    "missing database name returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@localhost:5432"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == s"Missing database name in URL: $raw")
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
        end match
    }
    "malformed IPv6 host returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@[::1:5432/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == s"Malformed IPv6 host in URL: $raw")
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
        end match
    }
    "IPv6 host missing colon before port returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@[::1]5432/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == s"Port is required in URL: $raw")
                // Discriminator: this trigger is the IPv6-bracket-closed-but-no-colon branch
                assert(e.message.contains("[::1]5432"), s"message should contain the IPv6+digits trigger: ${e.message}")
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
        end match
    }
    "non-IPv6 host without colon returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@localhost/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == s"Port is required in URL: $raw")
                // Discriminator: this trigger is a plain hostname with no colon at all
                assert(e.message.contains("localhost/db"), s"message should contain the no-colon host trigger: ${e.message}")
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
        end match
    }
    "non-IPv6 host with empty port string returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@localhost:/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == s"Port is required in URL: $raw")
                // Discriminator: this trigger is a colon-present but empty port-string branch
                assert(e.message.contains("localhost:/db"), s"message should contain the empty-port trigger: ${e.message}")
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
        end match
    }
    "non-numeric port returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@localhost:notaport/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == s"Port is required in URL: $raw")
                // Discriminator: this trigger is a non-numeric port string on a plain host
                assert(e.message.contains("notaport"), s"message should contain the non-numeric port trigger: ${e.message}")
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
        end match
    }
    "non-numeric IPv6 port returns Result.Failure with correct message" in {
        val raw    = "postgres://user:pw@[::1]:notaport/db"
        val result = SqlConfig.Url.parse(raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message == s"Port is required in URL: $raw")
                // Discriminator: this trigger is a non-numeric port on an IPv6 host
                assert(
                    e.message.contains("[::1]:notaport"),
                    s"message should contain the IPv6+non-numeric-port trigger: ${e.message}"
                )
            case other =>
                fail(s"Expected Result.Failure(SqlException.Connection) but got: $other")
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
