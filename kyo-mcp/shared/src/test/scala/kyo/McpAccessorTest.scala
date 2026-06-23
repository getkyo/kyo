package kyo

/** Tests for the Mcp accessor surface (Mcp.serverMaybe, Mcp.server, etc.).
  *
  * The accessors read the per-request Local bound by the engine. Outside a handler invocation the
  * Local is Absent; inside it carries the RequestContext the engine set.
  */
class McpAccessorTest extends Test:

    // serverMaybe returns Absent when called outside a handler invocation.
    "serverMaybe returns Absent outside a handler invocation" in {
        Mcp.serverMaybe.map { result =>
            assert(result == Absent, s"expected Absent outside handler, got $result")
        }
    }

    // server panics (IllegalStateException) outside a handler invocation.
    // Abort.catching captures the thrown exception as a Failure.
    "server fails with Throwable outside a handler invocation" in {
        Abort.run[Throwable](
            Abort.catching[Throwable](Mcp.server)
        ).map { result =>
            result match
                case Result.Failure(t) =>
                    assert(t.isInstanceOf[IllegalStateException], s"expected IllegalStateException, got $t")
                case Result.Panic(t) =>
                    // Panic also acceptable: the exception propagates as a panic from Sync.defer(throw).
                    assert(t.isInstanceOf[IllegalStateException], s"expected IllegalStateException panic, got $t")
                case Result.Success(s) =>
                    fail(s"expected failure, got success: $s")
        }
    }

    // Mcp.extras[T] decodes a conforming _meta payload to Present(t), and aborts
    // McpDecodeException when the payload does not conform to T.
    // The Local is seeded directly via Mcp.local.let to simulate an in-handler invocation.
    "extras[T] decodes conforming _meta to Present(t) and aborts on non-conforming payload" in {
        case class MetaPayload(token: String) derives Schema, CanEqual

        // Conforming path: _meta encodes a MetaPayload, extras[T] returns Present.
        val conformingSv = Structure.encode[MetaPayload](MetaPayload("abc"))

        // Non-conforming path: _meta holds a Record that does not decode to MetaPayload.
        // Structure.Value.Integer is the integer scalar constructor (Long).
        val nonConformingSv = Structure.Value.Record(Chunk("wrong" -> Structure.Value.Integer(99L)))

        buildCtx(Present(conformingSv)).flatMap { conformingCtx =>
            Mcp.local.let(Present(conformingCtx)) {
                Abort.run[McpDecodeException](Mcp.extras[MetaPayload]).map { conformingResult =>
                    assert(
                        conformingResult == Result.Success(Present(MetaPayload("abc"))),
                        s"expected Present(MetaPayload(abc)), got $conformingResult"
                    )
                }
            }
        }.andThen {
            buildCtx(Present(nonConformingSv)).flatMap { nonConformingCtx =>
                Mcp.local.let(Present(nonConformingCtx)) {
                    Abort.run[McpDecodeException](Mcp.extras[MetaPayload]).map { nonConformingResult =>
                        nonConformingResult match
                            case Result.Failure(_: McpDecodeException) => succeed
                            case other                                 => fail(s"expected Failure(McpDecodeException), got $other")
                    }
                }
            }
        }
    }

    // Builds a Mcp.RequestContext with the given extras value and a stub McpServer.
    // The stub server is never called; it exists only to satisfy the RequestContext constructor.
    private def buildCtx(extras: Maybe[Structure.Value]): Mcp.RequestContext < Sync =
        Fiber.Promise.init[Unit, Sync].map { promise =>
            val jrCtx = JsonRpcRoute.Context.forTest(promise, Absent, extras, Absent)
            Mcp.RequestContext(jrCtx, StubServer.safe)
        }

    // Minimal stub McpServer.Unsafe that satisfies the RequestContext requirement.
    // None of its methods are called during the extras[T] test.
    private object StubServer extends McpServer.Unsafe:

        private def notImpl[A, E](m: String)(using Frame): A < (Async & Abort[E]) =
            Abort.panic(new UnsupportedOperationException(s"StubServer.$m not implemented"))

        def requestSampling(req: McpServer.SamplingRequest)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[McpServer.SamplingResponse, Abort[McpRequestSamplingFailure]] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notImpl[
                McpServer.SamplingResponse,
                McpRequestSamplingFailure
            ]("requestSampling"))).unsafe

        def requestRoots(using AllowUnsafe, Frame): Fiber.Unsafe[Chunk[McpServer.Root], Abort[McpRequestRootsFailure]] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notImpl[Chunk[McpServer.Root], McpRequestRootsFailure]("requestRoots"))).unsafe

        def requestElicitation(req: McpServer.ElicitationRequest)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[McpServer.ElicitationResponse, Abort[McpRequestElicitationFailure]] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notImpl[
                McpServer.ElicitationResponse,
                McpRequestElicitationFailure
            ]("requestElicitation"))).unsafe

        def notifyToolsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

        def notifyResourcesListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

        def notifyResourceUpdated(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

        def notifyPromptsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

        def notifyLog[T](level: McpServer.LogLevel, data: T, logger: Maybe[String])(using
            AllowUnsafe,
            Frame,
            Schema[T]
        ): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

        def protocolVersion: Maybe[McpConfig.ProtocolVersion] = Absent
        def clientCapabilities: Maybe[McpCapabilities.Client] = Absent
        def clientInfo: Maybe[McpInfo]                        = Absent

        def underlying: JsonRpcHandler =
            throw new UnsupportedOperationException("StubServer.underlying not implemented")

        def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer(()))).unsafe

        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer(()))).unsafe

        private[kyo] def closeDirect(using Frame): Unit < Async = Sync.defer(())

    end StubServer

end McpAccessorTest
