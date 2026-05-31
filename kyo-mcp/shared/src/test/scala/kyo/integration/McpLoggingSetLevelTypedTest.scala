package kyo.integration

import kyo.*

/** Tests for typed logging/setLevel and server-side log level filtering (§3.9). */
class McpLoggingSetLevelTypedTest extends Test:

    // AllowUnsafe: AtomicInt.Unsafe.init for thread-safe notification counter across fibers.
    private def makeCounter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

    case class LogMsg(level: String, data: Structure.Value, logger: Maybe[String] = Absent)
        derives Schema,
          CanEqual

    "setLogLevel(Warning) drops subsequent notifyLog(Info) messages" in run {
        val counter = makeCounter
        // Register a client-side notification handler for notifications/message to count arrivals.
        val logNotifRoute = McpRoute.custom[LogMsg]("notifications/message") { (_, _) =>
            Sync.defer(discard(counter.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }

        val declaredCaps = McpCapabilities.Server(logging = Present(McpCapabilities.LoggingCapability()))
        val config       = McpConfig.default.declaredCapabilities(declaredCaps)

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, config)(),
                McpClient.initUnscoped(tc, McpInfo("log-test"), McpCapabilities.Client(), logNotifRoute)
            ).flatMap { (srv, client) =>
                for
                    _ <- client.setLogLevel(McpLogLevel.Warning)
                    _ <- Abort.run[Closed](srv.notifyLog(McpLogLevel.Info, "dropped-message"))
                    _ <- Abort.run[Closed](srv.notifyLog(McpLogLevel.Debug, "also-dropped"))
                    c1 = counter.get()(using AllowUnsafe.embrace.danger)
                    _ <- Abort.run[Closed](srv.notifyLog(McpLogLevel.Error, "received-message"))
                    _ <- Async.sleep(50.millis)
                    c2 = counter.get()(using AllowUnsafe.embrace.danger)
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield
                    assert(c1 == 0, s"expected 0 notifications for Info log after Warning threshold, got $c1")
                    assert(c2 == 1, s"expected 1 notification for Error log after Warning threshold, got $c2")
                end for
            }
        }
    }

    "notifyLog before setLogLevel uses Info default threshold" in run {
        val counter = makeCounter
        val logNotifRoute = McpRoute.custom[LogMsg]("notifications/message") { (_, _) =>
            Sync.defer(discard(counter.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }

        val declaredCaps = McpCapabilities.Server(logging = Present(McpCapabilities.LoggingCapability()))
        val config       = McpConfig.default.declaredCapabilities(declaredCaps)

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, config)(),
                McpClient.initUnscoped(tc, McpInfo("log-test2"), McpCapabilities.Client(), logNotifRoute)
            ).flatMap { (srv, client) =>
                for
                    // Info >= Info (default threshold), so Info messages are forwarded.
                    _ <- Abort.run[Closed](srv.notifyLog(McpLogLevel.Info, "info-message"))
                    // Debug < Info, so Debug messages are dropped.
                    _ <- Abort.run[Closed](srv.notifyLog(McpLogLevel.Debug, "debug-dropped"))
                    _ <- Async.sleep(50.millis)
                    c = counter.get()(using AllowUnsafe.embrace.danger)
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield assert(c == 1, s"expected 1 notification for Info log at Info threshold, got $c")
                end for
            }
        }
    }

end McpLoggingSetLevelTypedTest
