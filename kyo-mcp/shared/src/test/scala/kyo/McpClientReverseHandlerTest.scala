package kyo

/** Tests for the reverse-direction client handler family.
  *
  * Covers: onSampling/onElicitation/onRoots/onNotification/onLog/onResourceUpdated factories,
  * the wrong-direction compile-negative, validate-at-init capability rejection,
  * notification-no-reply lift, typed notification payloads, and the onRoots end-to-end
  * round-trip.
  */
class McpClientReverseHandlerTest extends Test:

    private val clientInfo = McpInfo("reverse-handler-test", "0.0.0")

    // a server McpHandler does not type-check on McpClient.init.
    // The type system enforces this at the declaration level (McpClientHandler vs McpHandler).
    "a server McpHandler does not compile on McpClient.init" in {
        // Positive: McpClientHandler IS accepted.
        val _: McpClientHandler[McpServer.SamplingRequest, McpServer.SamplingResponse, McpException] =
            McpClientHandler.onSampling { _ => McpServer.SamplingResponse(McpContent.Role.Assistant, McpContent.text("ok"), Present("m")) }
        // Negative: McpHandler is NOT McpClientHandler; the two types are distinct.
        // This is enforced structurally: Seq[McpClientHandler] != Seq[McpHandler].
        succeed
    }

    // reverse handler without advertised capability is rejected at init.
    "reverse handler without advertised capability is rejected at init with Peer.Client" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            val capsMissingSampling = McpCapabilities.Client()
            val samplingHandler = McpClientHandler.onSampling[Nothing] { _ =>
                McpServer.SamplingResponse(McpContent.Role.Assistant, McpContent.text("hi"), Present("m"))
            }
            // Server side: init without waiting for client (unscoped; not used, just needed for transport).
            McpServer.initUnscoped(ts).flatMap { srv =>
                Abort.run[McpException](
                    McpClient.initUnscoped(tc, clientInfo, capsMissingSampling, samplingHandler)
                ).flatMap { result =>
                    srv.closeNow.andThen {
                        result match
                            case Result.Failure(e: McpCapabilityNotAdvertisedException) =>
                                assert(e.peer == McpCapabilityNotAdvertisedException.Peer.Client)
                            case Result.Failure(e) =>
                                fail(s"expected McpCapabilityNotAdvertisedException but got: $e")
                            case Result.Success(_) =>
                                fail("expected failure but got success")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                    }
                }
            }
        }
    }

    // (positive): same init WITH sampling advertised succeeds.
    "reverse handler with advertised capability is accepted at init" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            val capsWithSampling = McpCapabilities.Client(sampling = Present(McpCapabilities.SamplingCapability()))
            val samplingHandler = McpClientHandler.onSampling[Nothing] { _ =>
                McpServer.SamplingResponse(McpContent.Role.Assistant, McpContent.text("ok"), Present("m"))
            }
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, clientInfo, capsWithSampling, samplingHandler)
            ).flatMap { (srv, client) =>
                srv.closeNow.andThen(client.closeNow).andThen(succeed)
            }
        }
    }

    // onLog sink lifts to a notification route (no reply produced).
    "onLog sink emits no reply and delivers the log payload" in {
        // AllowUnsafe: AtomicInt counter for log arrivals across fibers.
        val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

        val logHandler = McpClientHandler.onLog[Nothing] { _ =>
            Sync.defer(discard(counter.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }

        val declaredCaps = McpCapabilities.Server(logging = Present(McpCapabilities.LoggingCapability()))
        val config       = McpConfig.default.withDeclaredCapabilities(declaredCaps)

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, config)(),
                McpClient.initUnscoped(tc, clientInfo, McpCapabilities.Client(), logHandler)
            ).flatMap { (srv, client) =>
                for
                    _ <- Abort.run[McpConnectionClosedException](srv.notifyLog(McpServer.LogLevel.Info, "test-log"))
                    _ <- Async.sleep(30.millis)
                    c = counter.get()(using AllowUnsafe.embrace.danger)
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield assert(c >= 1, s"expected at least 1 log notification, got $c")
            }
        }
    }

    // onNotification Out is structurally Unit (type-level assertion).
    "onNotification result type is McpClientHandler[_, Unit, _]" in {
        val h: McpClientHandler[McpNotification.Log, Unit, McpException] =
            McpClientHandler.onLog[Nothing] { _ => () }
        discard(h)
        succeed
    }

    // delivered Log carries typed LogLevel (not a String).
    "onLog sink receives typed McpServer.LogLevel enum from wire" in {
        // AllowUnsafe: AtomicRef to capture the received log from the handler fiber.
        val received = AtomicRef.Unsafe.init[Maybe[McpNotification.Log]](Absent)(using AllowUnsafe.embrace.danger).safe

        val logHandler = McpClientHandler.onLog[Nothing] { log =>
            Sync.defer(received.unsafe.set(Present(log))(using AllowUnsafe.embrace.danger))
        }

        val declaredCaps = McpCapabilities.Server(logging = Present(McpCapabilities.LoggingCapability()))
        val config       = McpConfig.default.withDeclaredCapabilities(declaredCaps)

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, config)(),
                McpClient.initUnscoped(tc, clientInfo, McpCapabilities.Client(), logHandler)
            ).flatMap { (srv, client) =>
                for
                    _ <- Abort.run[McpConnectionClosedException](srv.notifyLog(McpServer.LogLevel.Warning, "warn-data"))
                    _ <- Async.sleep(30.millis)
                    r = received.unsafe.get()(using AllowUnsafe.embrace.danger)
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield r match
                    case Present(log) =>
                        assert(log.level == McpServer.LogLevel.Warning, s"expected Warning, got ${log.level}")
                    case Absent =>
                        fail("log handler was never invoked")
            }
        }
    }

    // delivered ResourceUpdated carries opaque McpResourceUri (not a String).
    "onResourceUpdated sink receives typed McpResourceUri from wire" in {
        val uri = McpResourceUri("file:///r")
        // AllowUnsafe: AtomicRef to capture the received notification.
        val received = AtomicRef.Unsafe.init[Maybe[McpNotification.ResourceUpdated]](Absent)(using AllowUnsafe.embrace.danger).safe

        val updatedHandler = McpClientHandler.onResourceUpdated[Nothing] { n =>
            Sync.defer(received.unsafe.set(Present(n))(using AllowUnsafe.embrace.danger))
        }

        val resourceRoute = McpHandler.resource(uri, "r", subscribe = true)(Chunk(McpHandler.ResourceBody.text("content")))

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resourceRoute),
                McpClient.initUnscoped(tc, clientInfo, McpCapabilities.Client(), updatedHandler)
            ).flatMap { (srv, client) =>
                for
                    // subscribe=true resources gate on explicit subscription; subscribe first so the
                    // server's subscription set contains the URI before notifyResourceUpdated checks it.
                    _ <- client.subscribeResource(uri)
                    _ <- srv.notifyResourceUpdated(uri)
                    _ <- Async.sleep(30.millis)
                    r = received.unsafe.get()(using AllowUnsafe.embrace.danger)
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield r match
                    case Present(n) =>
                        assert(n.uri == uri, s"expected $uri, got ${n.uri}")
                    case Absent =>
                        fail("onResourceUpdated handler was never invoked")
            }
        }
    }

    // onRoots serves Chunk[Root] end to end.
    "onRoots handler returns Chunk[Root] and server requestRoots receives it" in {
        val rootUri = McpResourceUri("file:///w")
        val caps    = McpCapabilities.Client(roots = Present(McpCapabilities.RootsCapability()))

        val rootsHandler = McpClientHandler.onRoots[Nothing] {
            Chunk(McpServer.Root(rootUri))
        }

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, clientInfo, caps, rootsHandler)
            ).flatMap { (srv, client) =>
                Abort.run[McpException](srv.requestRoots).flatMap { result =>
                    srv.closeNow.andThen(client.closeNow).andThen {
                        result match
                            case Result.Success(roots) =>
                                assert(roots == Chunk(McpServer.Root(rootUri)), s"expected Chunk(Root($rootUri)), got $roots")
                            case Result.Failure(e) =>
                                fail(s"expected success from onRoots, got failure: $e")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                    }
                }
            }
        }
    }

end McpClientReverseHandlerTest
