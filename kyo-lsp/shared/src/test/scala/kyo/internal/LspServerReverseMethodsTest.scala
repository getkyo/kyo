package kyo.internal

import kyo.*

/** Verifies that LspServer extension surface covers the ClientHandled Kind set.
  *
  * Reflects the method names on the LspServer opaque type's extension block and asserts
  * the expected reverse-direction methods are present. A missing method fails this gate.
  */
class LspServerReverseMethodsTest extends Test:

    private val clientInfo = LspInfo("rev-client", "0.0.0")
    private val clientCaps = LspCapabilities.Client.empty

    /** Starts a server and a client (carrying `clientHandlers` for the reverse-direction requests the
      * server issues) over an in-memory pair, then runs `call(server)` so the server can drive a
      * request back to the client and observe its handler's response. Scope-managed.
      */
    private def reverse[A](clientHandlers: LspHandler[?, ?, ?]*)(
        call: LspServer => A < (Async & Abort[LspException])
    )(using Frame): Result[LspException, A] < (Async & Scope) =
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            LspServer.init(ta).flatMap { server =>
                Abort.run[LspException](
                    LspClient.init(tb, clientInfo, clientCaps, clientHandlers*).andThen(call(server))
                )
            }
        }

    "showMessage is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                // Verify the method is callable with the correct param type.
                val params = LspHandler.ShowMessageParams(LspHandler.MessageType.Info, "test")
                val _: Unit < (Async & Abort[LspConnectionClosedException]) = server.showMessage(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "showMessageRequest round-trips: server asks, client handler answers" in {
        val item    = LspHandler.MessageActionItem("Retry")
        val clientH = LspHandler.Window.showMessageRequest { _ => Present(item) }
        val params  = LspHandler.ShowMessageRequestParams(LspHandler.MessageType.Info, "pick one", Chunk(item))
        Scope.run(reverse(clientH)(_.showMessageRequest(params))).map {
            case Result.Success(result) => assert(result == Present(item))
            case other                  => fail(s"showMessageRequest: $other")
        }
    }

    "showDocument is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ShowDocumentParams(uri = "file:///Main.scala")
                val _: LspHandler.ShowDocumentResult < (Async & Abort[LspRequestFailure]) = server.showDocument(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "logMessage is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.LogMessageParams(LspHandler.MessageType.Log, "debug")
                val _: Unit < (Async & Abort[LspConnectionClosedException]) = server.logMessage(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "createWorkDoneProgress is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val token                                        = LspHandler.ProgressToken.StringToken("test-token")
                val params                                       = LspHandler.WorkDoneProgressCreateParams(token)
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.createWorkDoneProgress(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "telemetry is defined on LspServer" in {
        case class TelemetryData(event: String) derives Schema
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspConnectionClosedException]) = server.telemetry(TelemetryData("startup"))
                server.closeNow.andThen(succeed)
            }
        }
    }

    "applyEdit round-trips: server requests an edit, client handler reports the result" in {
        val clientH = LspHandler.Workspace.applyEdit { _ =>
            LspHandler.ApplyWorkspaceEditResult(applied = true)
        }
        val params = LspHandler.ApplyWorkspaceEditParams(edit = LspHandler.WorkspaceEdit())
        Scope.run(reverse(clientH)(_.applyEdit(params))).map {
            case Result.Success(result) => assert(result.applied)
            case other                  => fail(s"applyEdit: $other")
        }
    }

    "getConfiguration is defined on LspServer" in {
        case class MyConfig(value: String) derives Schema
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val item                                                    = LspHandler.ConfigurationItem()
                val params                                                  = LspHandler.ConfigurationParams(Chunk(item))
                val _: Chunk[MyConfig] < (Async & Abort[LspRequestFailure]) = server.getConfiguration[MyConfig](params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "getWorkspaceFolders is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Maybe[Chunk[LspHandler.WorkspaceFolder]] < (Async & Abort[LspRequestFailure]) =
                    server.getWorkspaceFolders
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshSemanticTokens is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.refreshSemanticTokens
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshInlineValue is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.refreshInlineValue
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshInlayHint is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.refreshInlayHint
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshDiagnostic is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.refreshDiagnostic
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshCodeLens is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.refreshCodeLens
                server.closeNow.andThen(succeed)
            }
        }
    }

    "registerCapability is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params                                       = LspHandler.RegistrationParams(Chunk.empty)
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.registerCapability(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "unregisterCapability is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params                                       = LspHandler.UnregistrationParams(Chunk.empty)
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.unregisterCapability(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "publishDiagnostics is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val uri                                                     = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get
                val params                                                  = LspHandler.PublishDiagnosticsParams(uri, Absent, Chunk.empty)
                val _: Unit < (Async & Abort[LspConnectionClosedException]) = server.publishDiagnostics(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "logTrace is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspConnectionClosedException]) = server.logTrace("trace msg")
                server.closeNow.andThen(succeed)
            }
        }
    }

    "workDoneProgress is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val token                                                   = LspHandler.ProgressToken.StringToken("wdp-token")
                val value                                                   = LspHandler.WorkDoneProgressValue.Begin(title = "Working")
                val _: Unit < (Async & Abort[LspConnectionClosedException]) = server.workDoneProgress(token, value)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "cancel is defined on LspServer" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspConnectionClosedException]) = server.cancel(JsonRpcId(1L))
                server.closeNow.andThen(succeed)
            }
        }
    }

end LspServerReverseMethodsTest
