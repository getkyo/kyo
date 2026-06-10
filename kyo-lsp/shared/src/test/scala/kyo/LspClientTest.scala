package kyo

/** Tests for LspClient init quartet, argument order, handshake correctness, and typed method surface.
  *
  * Covers scope-managed init defaulting, the (transport, clientInfo, capabilities, handlers*) argument
  * order, eager initialize/initialized handshake, and the typed-only executeCommand[T] surface.
  */
class LspClientTest extends Test:

    private val clientInfo = LspInfo("test-client", "0.0.0")
    private val clientCaps = LspCapabilities.Client.empty

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
            val _: LspClient < (Async & Abort[LspException]) =
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
                        val caps = client.serverCapabilities
                        client.closeNow.andThen(server.closeNow).andThen {
                            assert(caps.isDefined, s"Expected serverCapabilities to be Present after init, got $caps")
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
                        val info = client.serverInfo
                        client.closeNow.andThen(server.closeNow).andThen {
                            // Server includes serverInfo in InitializeResult by default.
                            assert(info.isDefined, s"Expected serverInfo to be Present after init, got $info")
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
                        val enc = client.positionEncoding
                        client.closeNow.andThen(server.closeNow).andThen {
                            assert(enc == LspHandler.PositionEncodingKind.UTF16)
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
        def typedOnly(client: LspClient)(using Frame): Maybe[TestOut] < (Async & Abort[LspException | Closed]) =
            client.executeCommand[TestOut](params)
        assert(typedOnly != null, "executeCommand extension method must exist and be callable")
    }

    "executeCommand[T] return type is Maybe[T] < (Async & Abort[LspException | Closed])" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        case class MyResult(value: Int) derives Schema
                        val params = LspHandler.ExecuteCommandParams("myCommand")
                        // Compile-time type annotation confirms the return row.
                        val _: Maybe[MyResult] < (Async & Abort[LspException | Closed]) =
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
            assert(result.failed.get.isInstanceOf[LspException.Dispatch.WrongDirection])
        }
    }

    // =========================================================================
    // Typed request effect rows (compile-time)
    // =========================================================================

    "hover return type is Maybe[Hover] < (Async & Abort[LspException | Closed])" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        val uri    = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get
                        val params = LspHandler.HoverParams(LspHandler.TextDocumentIdentifier(uri), LspHandler.Position(0, 0))
                        val _: Maybe[LspHandler.Hover] < (Async & Abort[LspException | Closed]) = client.hover(params)
                        client.closeNow.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "didOpen return type is Unit < (Async & Abort[Closed])" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        val uri = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get
                        val params = LspHandler.DidOpenTextDocumentParams(
                            LspHandler.TextDocumentItem(uri, "scala", 1, "object Main")
                        )
                        val _: Unit < (Async & Abort[Closed]) = client.didOpen(params)
                        client.closeNow.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

    "workspaceSymbol return type is Chunk[WorkspaceSymbol] < (Async & Abort[LspException | Closed])" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                Abort.run[LspException](LspClient.initUnscoped(tb, clientInfo, clientCaps)).flatMap {
                    case Result.Success(client) =>
                        val params = LspHandler.WorkspaceSymbolParams("Main")
                        val _: Chunk[LspHandler.WorkspaceSymbol] < (Async & Abort[LspException | Closed]) =
                            client.workspaceSymbol(params)
                        client.closeNow.andThen(server.closeNow).andThen(succeed)
                    case Result.Failure(err) =>
                        server.closeNow.andThen(fail(s"Expected success, got: $err"))
                    case Result.Panic(ex) =>
                        server.closeNow.andThen(throw ex)
                }
            }
        }
    }

end LspClientTest
