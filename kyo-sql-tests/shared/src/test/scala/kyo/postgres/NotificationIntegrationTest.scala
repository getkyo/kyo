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
class NotificationIntegrationTest extends SqlContainerTest:

    // The delivery leaf's dedicated LISTEN connection read can be slow under CI's cross-module churn, so widen the
    // default 2m per-leaf limit (the suite runs sequentially via SqlContainerTest).
    override def timeout: Duration = 5.minutes

    /** Waits until the dedicated notification connection has registered its LISTEN on `channel` server-side, so a
      * following NOTIFY cannot be lost to a listener that has not subscribed yet. Polls `pg_stat_activity` for the
      * LISTEN session the same way the death-channel leaf asserts on it, bounded by the per-test timeout via
      * `assertEventually`. This replaces a fixed pre-NOTIFY sleep that could fire before the subscription existed.
      */
    private def awaitListening(client: PostgresClient, channel: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[SqlException]) =
        assertEventually(
            client.query(s"SELECT pid FROM pg_stat_activity WHERE query LIKE 'LISTEN%$channel%'").map(_.nonEmpty)
        )

    "NOTIFY delivers a notification to the LISTEN stream" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                PostgresClient.init(url).flatMap { client =>
                    DB.run(client) {
                        // Open the notification stream (dedicated connection).
                        val stream = client.notifications("test_channel")
                        // Start taking from the stream in a background fiber.
                        Fiber.init(Scope.run(stream.take(1).run)).flatMap { notifFiber =>
                            // Wait until the LISTEN is registered server-side before issuing NOTIFY.
                            awaitListening(client, "test_channel").andThen {
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
                PostgresClient.init(url).flatMap { client =>
                    DB.run(client) {
                        val stream = client.notifications("payload_channel")
                        Fiber.init(Scope.run(stream.take(1).run)).flatMap { notifFiber =>
                            awaitListening(client, "payload_channel").andThen {
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
                PostgresClient.init(url).flatMap { client =>
                    DB.run(client) {
                        val stream = client.notifications("empty_payload_ch")
                        Fiber.init(Scope.run(stream.take(1).run)).flatMap { notifFiber =>
                            awaitListening(client, "empty_payload_ch").andThen {
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
                PostgresClient.init(url).flatMap { client =>
                    DB.run(client) {
                        val stream = client.notifications("order_channel")
                        Fiber.init(Scope.run(stream.take(3).run)).flatMap { notifFiber =>
                            awaitListening(client, "order_channel").andThen {
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
                PostgresClient.init(url).flatMap { client =>
                    DB.run(client) {
                        val stream = client.notifications("isolated_channel")
                        // Start listening in background.
                        Fiber.init(Scope.run(stream.take(1).run)).flatMap { notifFiber =>
                            awaitListening(client, "isolated_channel").andThen {
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

    /** Polls `seen` until it holds at least `n` elements, bounded so a silent stream fails the leaf fast instead of hanging it. */
    private def awaitCount[A](seen: AtomicRef[Chunk[A]], n: Int)(using Frame, kyo.test.AssertScope): Unit < Async =
        Loop(0) { i =>
            seen.get.flatMap { c =>
                if c.size >= n then Loop.done
                else if i >= 400 then fail(s"expected $n notifications, saw ${c.size} after the wait budget")
                else Async.sleep(50.millis).andThen(Loop.continue(i + 1))
            }
        }

    "a dead notification connection fails the stream instead of stalling it" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                PostgresClient.init(url).flatMap { client =>
                    DB.run(client) {
                        AtomicRef.init(Chunk.empty[PostgresClient.Notification]).flatMap { seen =>
                            // Consume on a background fiber whose completion carries the stream's outcome: a
                            // first delivery proves the subscription is live, and once the listener's backend
                            // dies the stream must FAIL, not fall silent, because silence is indistinguishable
                            // from a quiet channel.
                            val consume =
                                Scope.run {
                                    Abort.run[SqlException] {
                                        client.notifications("death_channel").foreach { n =>
                                            seen.getAndUpdate(_.appended(n)).unit
                                        }
                                    }
                                }
                            Fiber.init(consume).flatMap { consumer =>
                                awaitListening(client, "death_channel").andThen {
                                    client.executeRaw("NOTIFY death_channel, 'alive'").andThen {
                                        awaitCount(seen, 1).andThen {
                                            client.query(
                                                "SELECT pid FROM pg_stat_activity WHERE query LIKE 'LISTEN%death_channel%'"
                                            ).flatMap { rows =>
                                                assert(rows.size == 1, s"exactly one listener session must exist, found ${rows.size}")
                                                rows(0).decode[Int](0).flatMap { pid =>
                                                    client.query(s"SELECT pg_terminate_backend($pid)").andThen {
                                                        Abort.run[Timeout](Async.timeout(10.seconds)(consumer.get)).map {
                                                            case Result.Success(Result.Failure(e)) =>
                                                                assert(
                                                                    e.isInstanceOf[SqlConnectionClosedException],
                                                                    s"the stream must fail with the pump's connection error, got $e"
                                                                )
                                                            case Result.Success(other) =>
                                                                fail(s"the stream ended without a failure: $other")
                                                            case other =>
                                                                fail(s"the stream stayed silent after its connection died: $other")
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
            }
        }
    }

    "a NOTIFY burst larger than the delivery buffer loses nothing" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                PostgresClient.init(url).flatMap { client =>
                    DB.run(client) {
                        // The internal delivery buffer holds 128, so 300 exercises the overflow: the pump must
                        // suspend and let the unread socket backpressure the server, never drop a delivery.
                        val total = 300
                        AtomicRef.init(Chunk.empty[String]).flatMap { seen =>
                            Latch.init(1).flatMap { gate =>
                                // The gate holds the consumer while the whole burst lands, so delivery must
                                // survive a consumer slower than the arrival rate.
                                val consume =
                                    Scope.run {
                                        Abort.run[SqlException] {
                                            client.notifications("burst_channel").foreach { n =>
                                                gate.await.andThen(seen.getAndUpdate(_.appended(n.payload)).unit)
                                            }
                                        }
                                    }
                                Fiber.init(consume).flatMap { _ =>
                                    awaitListening(client, "burst_channel").andThen {
                                        Kyo.foreachDiscard(Chunk.from(1 to total)) { i =>
                                            client.executeRaw(s"NOTIFY burst_channel, 'p$i'").unit
                                        }.andThen {
                                            gate.release.andThen {
                                                awaitCount(seen, total).andThen {
                                                    seen.get.map { payloads =>
                                                        assert(
                                                            payloads == Chunk.from((1 to total).map(i => s"p$i")),
                                                            s"every NOTIFY must arrive once and in order; got ${payloads.size} of $total"
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
                }
            }
        }
    }

end NotificationIntegrationTest
