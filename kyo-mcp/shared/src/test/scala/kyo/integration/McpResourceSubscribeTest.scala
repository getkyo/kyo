package kyo.integration

import kyo.*

/** Tests for resources/subscribe and resources/unsubscribe (§3.4). */
class McpResourceSubscribeTest extends Test:

    private val uri1 = McpResourceUri.parse("file:///res1").get
    private val uri2 = McpResourceUri.parse("file:///res2").get

    // AllowUnsafe: AtomicInt.Unsafe.init for thread-safe notification counter.
    private def makeCounter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

    case class ResUpdated(uri: String) derives Schema, CanEqual

    "subscribed URI receives notifyResourceUpdated; non-subscribed URI does not" in {
        val subscribedCounter    = makeCounter
        val notSubscribedCounter = makeCounter

        // Route that counts notifications/resources/updated for uri1.
        val updatedRoute = McpClientHandler.onNotification[ResUpdated]("notifications/resources/updated") { msg =>
            Sync.defer {
                if msg.uri == uri1.asString then
                    discard(subscribedCounter.incrementAndGet()(using AllowUnsafe.embrace.danger))
                else
                    discard(notSubscribedCounter.incrementAndGet()(using AllowUnsafe.embrace.danger))
            }
        }

        val resourceRoute =
            McpHandler.resource(uri1, "res1", subscribe = true)(Chunk(McpHandler.ResourceBody.text("content")))

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resourceRoute),
                McpClient.initUnscoped(tc, McpInfo("sub-test"), McpCapabilities.Client(), updatedRoute)
            ).flatMap { (srv, client) =>
                for
                    _ <- client.subscribeResource(uri1)
                    _ <- Abort.run[McpConnectionClosedException](srv.notifyResourceUpdated(uri1))
                    _ <- Async.sleep(50.millis)
                    c1 = subscribedCounter.get()(using AllowUnsafe.embrace.danger)
                    _ <- Abort.run[McpConnectionClosedException](srv.notifyResourceUpdated(uri2))
                    _ <- Async.sleep(50.millis)
                    c2 = notSubscribedCounter.get()(using AllowUnsafe.embrace.danger)
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield
                    assert(c1 >= 1, s"expected at least 1 notification for subscribed URI, got $c1")
                    assert(c2 == 0, s"expected 0 notifications for non-subscribed URI, got $c2")
                end for
            }
        }
    }

    "after unsubscribeResource, no further notifications for the URI" in {
        val counter = makeCounter

        val updatedRoute = McpClientHandler.onNotification[ResUpdated]("notifications/resources/updated") { _ =>
            Sync.defer(discard(counter.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }

        val resourceRoute =
            McpHandler.resource(uri1, "res1", subscribe = true)(Chunk(McpHandler.ResourceBody.text("content")))

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resourceRoute),
                McpClient.initUnscoped(tc, McpInfo("unsub-test"), McpCapabilities.Client(), updatedRoute)
            ).flatMap { (srv, client) =>
                for
                    _ <- client.subscribeResource(uri1)
                    _ <- Abort.run[McpConnectionClosedException](srv.notifyResourceUpdated(uri1))
                    _ <- Async.sleep(50.millis)
                    c1 = counter.get()(using AllowUnsafe.embrace.danger)
                    _ <- client.unsubscribeResource(uri1)
                    _ <- Abort.run[McpConnectionClosedException](srv.notifyResourceUpdated(uri1))
                    _ <- Async.sleep(50.millis)
                    c2 = counter.get()(using AllowUnsafe.embrace.danger)
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield
                    assert(c1 >= 1, s"expected at least 1 notification before unsubscribe, got $c1")
                    assert(c2 == c1, s"expected no new notifications after unsubscribe, got $c2 (was $c1)")
                end for
            }
        }
    }

    "subscribe=false resource produces no notifyResourceUpdated emit" in {
        val counter = makeCounter

        val updatedRoute = McpClientHandler.onNotification[ResUpdated]("notifications/resources/updated") { _ =>
            Sync.defer(discard(counter.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }

        // subscribe=false (default) means the resource capability does not expose subscription;
        // notifyResourceUpdated is a no-op for such resources.
        val resourceRoute = McpHandler.resource(uri1, "res1")(Chunk(McpHandler.ResourceBody.text("content")))

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resourceRoute),
                McpClient.initUnscoped(tc, McpInfo("nosub-test"), McpCapabilities.Client(), updatedRoute)
            ).flatMap { (srv, client) =>
                for
                    _ <- srv.notifyResourceUpdated(uri1)
                    _ <- Async.sleep(50.millis)
                    c = counter.get()(using AllowUnsafe.embrace.danger)
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield assert(c == 0, s"expected no notification for subscribe=false resource, got $c")
                end for
            }
        }
    }

end McpResourceSubscribeTest
