package kyo.internal

import kyo.*

/** Verifies that LspServer extension surface covers the ClientHandled Kind set.
  *
  * Reflects the method names on the LspServer opaque type's extension block and asserts
  * the expected reverse-direction methods are present. A missing method fails this gate.
  */
class LspServerReverseMethodsTest extends Test:

    "showMessage is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                // Verify the method is callable with the correct param type.
                val params                            = LspHandler.ShowMessageParams(LspHandler.MessageType.Info, "test")
                val _: Unit < (Async & Abort[Closed]) = server.showMessage(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "showMessageRequest is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ShowMessageRequestParams(LspHandler.MessageType.Info, "test", Chunk.empty)
                val _: Maybe[LspHandler.MessageActionItem] < (Async & Abort[LspException | Closed]) =
                    server.showMessageRequest(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "showDocument is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ShowDocumentParams(uri = "file:///Main.scala")
                val _: LspHandler.ShowDocumentResult < (Async & Abort[LspException | Closed]) = server.showDocument(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "logMessage is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params                            = LspHandler.LogMessageParams(LspHandler.MessageType.Log, "debug")
                val _: Unit < (Async & Abort[Closed]) = server.logMessage(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "createWorkDoneProgress is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val token                                            = LspHandler.ProgressToken.StringToken("test-token")
                val params                                           = LspHandler.WorkDoneProgressCreateParams(token)
                val _: Unit < (Async & Abort[LspException | Closed]) = server.createWorkDoneProgress(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "telemetry is defined on LspServer" in run {
        case class TelemetryData(event: String) derives Schema
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[Closed]) = server.telemetry(TelemetryData("startup"))
                server.closeNow.andThen(succeed)
            }
        }
    }

    "applyEdit is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ApplyWorkspaceEditParams(edit = LspHandler.WorkspaceEdit())
                val _: LspHandler.ApplyWorkspaceEditResult < (Async & Abort[LspException | Closed]) = server.applyEdit(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "getConfiguration is defined on LspServer" in run {
        case class MyConfig(value: String) derives Schema
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val item                                                        = LspHandler.ConfigurationItem()
                val params                                                      = LspHandler.ConfigurationParams(Chunk(item))
                val _: Chunk[MyConfig] < (Async & Abort[LspException | Closed]) = server.getConfiguration[MyConfig](params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "getWorkspaceFolders is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Maybe[Chunk[LspHandler.WorkspaceFolder]] < (Async & Abort[LspException | Closed]) =
                    server.getWorkspaceFolders
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshSemanticTokens is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspException | Closed]) = server.refreshSemanticTokens
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshInlineValue is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspException | Closed]) = server.refreshInlineValue
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshInlayHint is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspException | Closed]) = server.refreshInlayHint
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshDiagnostic is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspException | Closed]) = server.refreshDiagnostic
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshCodeLens is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspException | Closed]) = server.refreshCodeLens
                server.closeNow.andThen(succeed)
            }
        }
    }

    "registerCapability is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params                                           = LspHandler.RegistrationParams(Chunk.empty)
                val _: Unit < (Async & Abort[LspException | Closed]) = server.registerCapability(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "unregisterCapability is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params                                           = LspHandler.UnregistrationParams(Chunk.empty)
                val _: Unit < (Async & Abort[LspException | Closed]) = server.unregisterCapability(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "publishDiagnostics is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val uri                               = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get
                val params                            = LspHandler.PublishDiagnosticsParams(uri, Absent, Chunk.empty)
                val _: Unit < (Async & Abort[Closed]) = server.publishDiagnostics(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "logTrace is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[Closed]) = server.logTrace("trace msg")
                server.closeNow.andThen(succeed)
            }
        }
    }

    "workDoneProgress is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val token                             = LspHandler.ProgressToken.StringToken("wdp-token")
                val value                             = LspHandler.WorkDoneProgressValue.Begin(title = "Working")
                val _: Unit < (Async & Abort[Closed]) = server.workDoneProgress(token, value)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "cancel is defined on LspServer" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[Closed]) = server.cancel(JsonRpcId(1L))
                server.closeNow.andThen(succeed)
            }
        }
    }

end LspServerReverseMethodsTest
