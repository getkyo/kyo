package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection

/** Pins the two ways a caller stops a statement that is taking too long.
  *
  * There is no cancel handle to hold and no `cancel(handle)` to call: the fiber running the statement IS the handle. Wrapping it in
  * [[kyo.Async.timeout]] bounds it, and interrupting its [[kyo.Fiber]] stops it, and both work because every method on a client suspends
  * rather than blocking a thread. These scenarios drive a fake server that accepts the TCP connection and then never answers, so the statement
  * is genuinely in flight when the timeout fires and when the interrupt lands.
  */
class SqlClientInterruptTest extends SqlContainerTest:

    /** Accepts the startup packet and answers nothing, leaving the handshake suspended for as long as the test needs. */
    private def silentHandler(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).unit

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    /** No warm-up, so opening the client touches no socket and only the statement under test does. */
    private val config: SqlConfig =
        SqlConfig(maxConnections = 2, minConnections = 0, acquireTimeout = 30.seconds, queryTimeout = 30.seconds)

    private def withSilentClient[A](f: SqlClient => A < (Async & Abort[SqlException] & Scope))(using
        Frame
    ): A < (Async & Abort[SqlException] & Abort[kyo.net.NetException] & Scope) =
        kyo.internal.FakeServer.listenPort(silentHandler).flatMap { listener =>
            SqlClient.initUnscoped(fakeUrl(listener.port), config).flatMap { client =>
                Scope.ensure(Abort.run(client.close).unit).andThen(f(client))
            }
        }

    "Async.timeout bounds a statement that never completes" in {
        withSilentClient { client =>
            Abort.run[Timeout](
                Async.timeout(200.millis)(Abort.run[SqlException](client.query("SELECT 1")))
            ).map {
                case Result.Failure(_: Timeout) => succeed
                case other                      => fail(s"Expected the query to be bounded by Async.timeout, got $other")
            }
        }
    }

    "interrupting the statement's fiber stops it" in {
        withSilentClient { client =>
            Latch.initWith(1) { started =>
                Fiber.initUnscoped(
                    started.release.andThen(Abort.run[SqlException](client.query("SELECT 1")))
                ).flatMap { queryFiber =>
                    started.await.andThen {
                        queryFiber.interrupt.map { interrupted =>
                            assert(interrupted, "interrupting the fiber running a statement must stop it")
                        }
                    }
                }
            }
        }
    }

end SqlClientInterruptTest
