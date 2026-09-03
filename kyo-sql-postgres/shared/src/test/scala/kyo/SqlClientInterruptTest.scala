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

    /** A caller interrupted out of a connect must not strand the socket that connect owns.
      *
      * `transport.connect` returns a fiber owning a descriptor from the instant it is called, so the finalizer that closes it has to be
      * registered BEFORE the launch. While it was registered after, an interrupt landing in that window left nobody owning the socket:
      * the connect still succeeded, handed its connection to a promise the interrupted computation never read, and the descriptor stayed
      * ESTABLISHED for the life of the process along with the server-side peer it was connected to.
      *
      * The window is narrow, so ONE interrupt proves nothing: eight of 192 connects took it. The scenario is repeated until the failure
      * is reliable rather than probabilistic, which is what makes this a guard instead of a coin flip.
      *
      * Each cycle runs in its OWN `Scope.run`. Without that, the fake server and the client accumulate for the whole leaf and the
      * harness's descriptor check reports the fixture's own growth rather than anything about the interrupt path. Asserting that every
      * interrupt actually landed is what keeps the loop honest, since a cycle whose statement was never in flight exercises nothing;
      * the descriptor check is what catches the leak itself.
      */
    "an interrupted connect strands no descriptor" in {
        Kyo.foreachDiscard(Chunk.from(1 to 100)) { _ =>
            Scope.run {
                withSilentClient { client =>
                    Latch.initWith(1) { started =>
                        Fiber.initUnscoped(
                            started.release.andThen(Abort.run[SqlException](client.query("SELECT 1")))
                        ).flatMap { queryFiber =>
                            started.await.andThen {
                                queryFiber.interrupt.map { interrupted =>
                                    assert(interrupted, "each cycle must genuinely interrupt an in-flight statement")
                                }
                            }
                        }
                    }
                }
            }
        }.andThen(succeed)
    }

end SqlClientInterruptTest
