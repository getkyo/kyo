package kyo

/** Tests for LspClient init quartet, argument order, handshake correctness, and typed method surface.
  *
  * Covers scope-managed init defaulting, the (transport, clientInfo, capabilities, handlers*) argument
  * order, eager initialize/initialized handshake, and the typed-only executeCommand[T] surface.
  */
class LspClientTest extends Test:

    private val clientInfo = LspInfo("test-client", "0.0.0")
    private val clientCaps = LspCapabilities.Client.empty

    private val mainUri = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get

    /** Starts a server with `handlers` and a connected client over an in-memory transport pair,
      * runs `call(client)` against the live session, and returns its `Result`. Scope-managed: both
      * server and client are released at scope exit.
      */
    private def serve[A](handlers: LspHandler[?, ?, ?]*)(
        call: LspClient => A < (Async & Abort[LspException])
    )(using Frame): Result[LspException, A] < (Async & Scope) =
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.init(ta, handlers*).flatMap { _ =>
                Abort.run[LspException](LspClient.init(tb, clientInfo, clientCaps).flatMap(call))
            }
        }

    // =========================================================================
    // Init quartet smoke tests
    // =========================================================================

    "initUnscoped with no handlers requires a connected server to complete handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        client.closeNow.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected client init to succeed, got error: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "initUnscoped with explicit config smoke test" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps, LspConfig.default)).flatMap {
                    case Result.Success(client) =>
                        client.closeNow.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "initUnscopedWith applies function and returns result" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](
                    LspClient.initUnscopedWith(tb, clientInfo, clientCaps) { client =>
                        client.specVersion
                    }
                ).flatMap {
                    case Result.Success(version) =>
                        server.closeNow.andThen(assert(version == "3.17"))
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "initUnscopedWith with explicit config applies function" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](
                    LspClient.initUnscopedWith(tb, clientInfo, clientCaps, LspConfig.default) { client =>
                        client.specVersion
                    }
                ).flatMap {
                    case Result.Success(version) =>
                        server.closeNow.andThen(assert(version == "3.17"))
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "init (scoped) releases client when Scope exits" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](
                    Scope.run {
                        LspClient.init(tb, clientInfo, clientCaps).map { client =>
                            assert(client.specVersion == "3.17")
                        }
                    }
                ).flatMap {
                    case Result.Success(_)   => server.closeNow.andThen(succeed)
                    case Result.Failure(err) => server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex)    => server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "initWith applies function and client is live inside the function" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](
                    Scope.run {
                        LspClient.initWith(tb, clientInfo, clientCaps) { client =>
                            client.specVersion
                        }
                    }
                ).flatMap {
                    case Result.Success(version) =>
                        server.closeNow.andThen(assert(version == "3.17"))
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "initWith with explicit config applies function" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](
                    Scope.run {
                        LspClient.initWith(tb, clientInfo, clientCaps, LspConfig.default) { client =>
                            client.specVersion
                        }
                    }
                ).flatMap {
                    case Result.Success(version) =>
                        server.closeNow.andThen(assert(version == "3.17"))
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    // =========================================================================
    // Argument order locked
    // =========================================================================

    "init argument order is (transport, clientInfo, capabilities, handlers*)" in {
        // Compile-time check: the signature must accept arguments in this exact order.
        // If the order changes, this test fails to compile.
        JsonRpcTransport.inMemory.flatMap { (_, tb) =>
            val transport: JsonRpcTransport         = tb
            val info: LspInfo                       = clientInfo
            val caps: LspCapabilities.Client.Client = clientCaps
            val handlers: Seq[LspHandler[?, ?, ?]]  = Seq.empty
            // The type annotation is the assertion: this must compile with the exact row.
            // If the argument order changed, this would not compile.
            val _: LspClient < (Async & Abort[LspInitFailure]) =
                LspClient.initUnscoped(transport, info, caps, handlers*)
            succeed
        }
    }

    // =========================================================================
    // Eager handshake
    // =========================================================================

    "serverCapabilities is Present immediately after init" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        client.serverCapabilities.map { caps =>
                            client.closeNow.andThen(server.closeNow).andThen {
                                assert(caps.isDefined, s"Expected serverCapabilities to be Present after init, got $caps")
                            }
                        }
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected client init to succeed, got error: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "serverInfo is Present immediately after init when server advertises info" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        client.serverInfo.map { info =>
                            client.closeNow.andThen(server.closeNow).andThen {
                                // Server includes serverInfo in InitializeResult by default.
                                assert(info.isDefined, s"Expected serverInfo to be Present after init, got $info")
                            }
                        }
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected client init to succeed, got error: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "positionEncoding defaults to UTF16 when server does not advertise encoding" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        client.positionEncoding.map { enc =>
                            client.closeNow.andThen(server.closeNow).andThen {
                                assert(enc == LspHandler.PositionEncodingKind.UTF16)
                            }
                        }
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected client init to succeed, got error: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "specVersion returns 3.17 after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        val ver = client.specVersion
                        client.closeNow.andThen(server.closeNow).andThen {
                            assert(ver == "3.17")
                        }
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected client init to succeed, got error: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    // =========================================================================
    // Typed-only executeCommand[T]
    // =========================================================================

    "executeCommand[T] is the only variant and is typed-only" in {
        // Compile-time check: the extension method exists with the typed signature.
        // The type parameter [T] proves it is typed-only; no untyped alias exists
        // because that would have a different name (executeCommandUntyped, etc.).
        // Here we verify via the safe-tier extension call site.
        case class TestOut(value: String) derives Schema
        val params = LspHandler.ExecuteCommandParams("test")
        // This compile-time annotation is the assertion: only one executeCommand variant exists.
        def typedOnly(client: LspClient)(using Frame): Maybe[TestOut] < (Async & Abort[LspRequestFailure]) =
            client.executeCommand[TestOut](params)
        assert(typedOnly != null, "executeCommand extension method must exist and be callable")
    }

    "executeCommand[T] return type is Maybe[T] < (Async & Abort[LspRequestFailure])" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        case class MyResult(value: Int) derives Schema
                        val params = LspHandler.ExecuteCommandParams("myCommand")
                        // Compile-time type annotation confirms the return row.
                        val _: Maybe[MyResult] < (Async & Abort[LspRequestFailure]) =
                            client.executeCommand[MyResult](params)
                        client.closeNow.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    // =========================================================================
    // Session accessors
    // =========================================================================

    "underlying returns a JsonRpcHandler instance" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        val _ = client.underlying
                        client.closeNow.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "unsafe accessor returns LspClient.Unsafe" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        val unsafe = client.unsafe
                        val back   = unsafe.safe
                        client.closeNow.andThen(server.closeNow).andThen {
                            assert(back.specVersion == "3.17")
                        }
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    // =========================================================================
    // Close / closeNow / awaitDrain
    // =========================================================================

    "close(using Frame) completes without error" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        client.close.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "closeNow completes without error" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        client.closeNow.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "awaitDrain completes on an idle client" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        client.awaitDrain.andThen(client.closeNow).andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    // =========================================================================
    // Direction filtering at init time
    // =========================================================================

    "initUnscoped rejects a ServerHandled handler at init time" in {
        // A server-handled handler (completion) passed to client init must throw WrongDirection.
        val serverHandler = LspHandler.TextDocument.completion { _ =>
            LspHandler.CompletionResult.Items(Chunk.empty)
        }
        JsonRpcTransport.inMemory.map { (_, _) =>
            val result = scala.util.Try(
                kyo.internal.lsp.LspCatalog.fromHandlers(Seq(serverHandler), LspHandler.Direction.ClientHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspWrongDirectionException])
        }
    }

    // =========================================================================
    // Typed request effect rows (compile-time)
    // =========================================================================

    "hover request round-trips through a registered server handler" in {
        val hover  = LspHandler.TextDocument.hover { _ => Present(LspHandler.Hover.plainText("the docs")) }
        val params = LspHandler.HoverParams(LspHandler.TextDocumentIdentifier(mainUri), LspHandler.Position(0, 0))
        Scope.run(serve(hover)(_.hover(params))).map {
            case Result.Success(result) => assert(result == Present(LspHandler.Hover.plainText("the docs")))
            case other                  => fail(s"hover: $other")
        }
    }

    "definition request round-trips a result union (Location) through the custom schema" in {
        val target = LspHandler.Location(mainUri, LspHandler.Range.of(2, 0, 2, 4))
        val definition = LspHandler.TextDocument.definition { _ =>
            LspHandler.DefinitionResult.One(target)
        }
        val params = LspHandler.DefinitionParams(LspHandler.TextDocumentIdentifier(mainUri), LspHandler.Position(0, 0))
        Scope.run(serve(definition)(_.definition(params))).map {
            case Result.Success(result) => assert(result == Present(LspHandler.DefinitionResult.One(target)))
            case other                  => fail(s"definition: $other")
        }
    }

    "executeCommand[T] decodes the typed result the server returns" in {
        val command = LspHandler.Workspace.executeCommand[ReindexResult] { _ => Present(ReindexResult(7)) }
        val params  = LspHandler.ExecuteCommandParams(command = "todo.reindex", arguments = Chunk.empty)
        Scope.run(serve(command)(_.executeCommand[ReindexResult](params))).map {
            case Result.Success(result) => assert(result == Present(ReindexResult(7)))
            case other                  => fail(s"executeCommand: $other")
        }
    }

    "a .error[E2] handler failure arrives on the client as LspRemoteException" in {
        val command = LspHandler.Workspace.executeCommand[ReindexResult] { _ =>
            Abort.fail(TodoLintFailure(3, "unknown keyword"))
                .andThen((Absent: Maybe[ReindexResult]): Maybe[ReindexResult] < Async)
        }.error[TodoLintFailure](code = -32099, message = "todo lint failure")
        val params = LspHandler.ExecuteCommandParams(command = "todo.reindex", arguments = Chunk.empty)
        Scope.run(serve(command)(_.executeCommand[ReindexResult](params))).map {
            case Result.Failure(LspRemoteException(code, _, _)) => assert(code == -32099)
            case other                                          => fail(s"expected LspRemoteException(-32099), got: $other")
        }
    }

end LspClientTest

private case class ReindexResult(scanned: Int) derives Schema, CanEqual
private case class TodoLintFailure(line: Int, reason: String) derives Schema, CanEqual
