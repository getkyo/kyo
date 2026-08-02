package kyo.internal

import kyo.*
import kyo.Test
import kyo.db.Connection

/** Pins that `Connection.requireUser` refuses an address that named no user, on every backend, before a socket is opened.
  *
  * The property is not "an absent user fails". It is that it fails HERE, with a leaf naming the missing input, rather than reaching the wire as
  * an empty user name and coming back as the server reporting that no such role exists. Both PostgreSQL's startup packet and MySQL's handshake
  * response carry the user name as a mandatory field, so `SqlConfig.Address.user` becoming a `Maybe` raised the question of what an [[Absent]]
  * one does at connect time, and this suite is the answer.
  *
  * Every leaf points at a loopback port nothing listens on, which is the technique `PostgresClientSchemeTest` documents and it carries the whole
  * before-the-socket claim: a connect to a dead port cannot produce `SqlConnectionUserRequiredException`, so getting that leaf proves the refusal
  * ran first. The two negative leaves are what make the positive one mean something. One proves the port really is dead, so the refusal came from
  * the user check rather than from the address; the other proves the check keys on ABSENCE rather than on emptiness, which is the design decision
  * the `Maybe` exists to express and the one a `.isEmpty` guard would quietly undo.
  *
  * One body per scheme rather than a hand-written pair, so a backend cannot drift out of coverage by having its leaf forgotten.
  */
class SqlConnectionUserRequiredTest extends Test:

    /** Loopback with a port nothing listens on. */
    private val deadPort = 45918

    /** `minConnections` is what makes `init` open a connection at all: at the default 0 the pool connects lazily, so the refusal would not be
      * observable until the first statement and the leaves would be asserting a different path than the one they name.
      */
    private val eager: SqlConfig = SqlConfig(minConnections = 1, maxConnections = 1)

    private def outcome(rawUrl: String)(using Frame): Result[SqlException, Any] < Async =
        Abort.run[SqlException](Scope.run(SqlClient.init(rawUrl, eager)))

    private def refusedForNoUser(result: Result[SqlException, Any]): Boolean =
        result match
            case Result.Failure(_: SqlConnectionUserRequiredException) => true
            case _                                                     => false

    /** Registers one leaf per backend scheme from a single body, the shape `BasePodTest.runRuntimes` uses. */
    private def runSchemes(f: String => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        List("postgres", "mysql").foreach { scheme =>
            s"[$scheme]" in f(scheme)
        }

    "a URL that names no user is refused before a socket is opened" - runSchemes { scheme =>
        outcome(s"$scheme://127.0.0.1:$deadPort/probe").map {
            case Result.Failure(e: SqlConnectionUserRequiredException) =>
                assert(
                    e.scheme == scheme,
                    s"the refusal must name the scheme whose handshake demanded the user, got '${e.scheme}'"
                )
                assert(
                    e.getMessage().contains("user name"),
                    s"the message must say what is missing: ${e.getMessage()}"
                )
            case other =>
                fail(
                    s"a $scheme URL naming no user must be refused with SqlConnectionUserRequiredException before " +
                        s"any socket is opened; the port is dead, so any connect failure here means the check ran too late: $other"
                )
        }
    }

    // Without this leaf the one above passes over a URL that was going to fail anyway, and nothing says the dead port
    // is dead. A named user must get PAST the check and fail on the connection instead. Both properties are asserted
    // rather than one of them being an empty match arm: an arm that returns unit asserts nothing, and a leaf that
    // reaches it has proven nothing about the outcome it landed on.
    "a URL that names a user gets past the check and fails on the connection" - runSchemes { scheme =>
        outcome(s"$scheme://alice@127.0.0.1:$deadPort/probe").map { result =>
            assert(
                !refusedForNoUser(result),
                s"a $scheme URL naming 'alice' declared a user and must not be refused for having none: $result"
            )
            assert(
                !result.isSuccess,
                s"nothing listens on $deadPort, so a $scheme connect there must not succeed: $result"
            )
        }
    }

    // The design decision, asserted rather than described: `scheme://@host` DECLARED an empty user, which is a different
    // fact from naming none, and whether an empty user name identifies an account is the server's to answer. Refusing it
    // here would collapse the two spellings the type change exists to separate.
    "a URL declaring an empty user is not refused for having none" - runSchemes { scheme =>
        outcome(s"$scheme://@127.0.0.1:$deadPort/probe").map { result =>
            assert(
                !refusedForNoUser(result),
                s"a $scheme URL spelled '@' declared an empty user, so the refusal must not fire: an empty user name " +
                    s"is the server's judgment, and MySQL's anonymous accounts are a real use for one. Got: $result"
            )
            assert(
                !result.isSuccess,
                s"nothing listens on $deadPort, so a $scheme connect there must not succeed: $result"
            )
        }
    }

end SqlConnectionUserRequiredTest
