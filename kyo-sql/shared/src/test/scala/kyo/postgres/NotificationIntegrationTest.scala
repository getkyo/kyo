package kyo.postgres

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for LISTEN/NOTIFY via SqlClient.notifications stream.
  *
  * Tests:
  *   1. LISTEN on a channel, NOTIFY from a second connection, notification arrives in stream.
  *   2. Notification with a non-empty payload is delivered correctly.
  *   3. Notification with empty payload delivered (NOTIFY without payload clause).
  *   4. Stream delivers multiple notifications in order.
  *   5. Notifications stream on separate connection does not interfere with query connection.
  */
class NotificationIntegrationTest extends kyo.Test:

    "NOTIFY delivers a notification to the LISTEN stream" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                SqlClient.init(url).flatMap { client =>
                    SqlClient.let(client) {
                        // Open the notification stream (dedicated connection).
                        val stream = client.notifications("test_channel")
                        // Start taking from the stream in a background fiber.
                        Fiber.init(Scope.run(stream.take(1).run)).flatMap { notifFiber =>
                            // Give the listener time to register.
                            Async.sleep(300.millis).andThen {
                                // NOTIFY from the same client (different pool connection).
                                client.executeRaw("NOTIFY test_channel, 'hello'").andThen {
                                    notifFiber.get.map { notifications =>
                                        assert(notifications.size == 1, s"Expected 1 notification, got ${notifications.size}")
                                        val n = notifications(0)
                                        assert(n.channel == "test_channel", s"Expected channel 'test_channel', got '${n.channel}'")
                                        assert(n.payload == "hello", s"Expected payload 'hello', got '${n.payload}'")
                                        assert(n.processId > 0, s"Expected positive processId, got ${n.processId}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Notification with non-empty payload carries the payload string" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                SqlClient.init(url).flatMap { client =>
                    SqlClient.let(client) {
                        val stream = client.notifications("payload_channel")
                        Fiber.init(Scope.run(stream.take(1).run)).flatMap { notifFiber =>
                            Async.sleep(300.millis).andThen {
                                client.executeRaw("NOTIFY payload_channel, 'my_payload_123'").andThen {
                                    notifFiber.get.map { notifications =>
                                        assert(notifications.size == 1)
                                        assert(notifications(0).payload == "my_payload_123")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Notification with empty payload delivers empty string" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                SqlClient.init(url).flatMap { client =>
                    SqlClient.let(client) {
                        val stream = client.notifications("empty_payload_ch")
                        Fiber.init(Scope.run(stream.take(1).run)).flatMap { notifFiber =>
                            Async.sleep(300.millis).andThen {
                                // NOTIFY without payload sends an empty string payload.
                                client.executeRaw("NOTIFY empty_payload_ch").andThen {
                                    notifFiber.get.map { notifications =>
                                        assert(notifications.size == 1)
                                        assert(
                                            notifications(0).payload == "",
                                            s"Expected empty payload, got '${notifications(0).payload}'"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Multiple NOTIFYs are delivered in order" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                SqlClient.init(url).flatMap { client =>
                    SqlClient.let(client) {
                        val stream = client.notifications("order_channel")
                        Fiber.init(Scope.run(stream.take(3).run)).flatMap { notifFiber =>
                            Async.sleep(300.millis).andThen {
                                // Send 3 notifications in sequence.
                                client.executeRaw("NOTIFY order_channel, 'first'").andThen {
                                    client.executeRaw("NOTIFY order_channel, 'second'").andThen {
                                        client.executeRaw("NOTIFY order_channel, 'third'").andThen {
                                            notifFiber.get.map { notifications =>
                                                assert(notifications.size == 3, s"Expected 3 notifications, got ${notifications.size}")
                                                assert(notifications(0).payload == "first")
                                                assert(notifications(1).payload == "second")
                                                assert(notifications(2).payload == "third")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Notifications stream on dedicated connection does not interfere with query connection" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                SqlClient.init(url).flatMap { client =>
                    SqlClient.let(client) {
                        val stream = client.notifications("isolated_channel")
                        // Start listening in background.
                        Fiber.init(Scope.run(stream.take(1).run)).flatMap { notifFiber =>
                            Async.sleep(300.millis).andThen {
                                // Run a normal query on the SAME client (uses a different pool connection).
                                client.query("SELECT generate_series(1, 5)").flatMap { rows =>
                                    assert(rows.size == 5, s"Expected 5 rows from query, got ${rows.size}")
                                    // NOTIFY to trigger the stream.
                                    client.executeRaw("NOTIFY isolated_channel, 'ping'").andThen {
                                        notifFiber.get.map { notifications =>
                                            assert(notifications.size == 1)
                                            assert(notifications(0).payload == "ping")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end NotificationIntegrationTest
