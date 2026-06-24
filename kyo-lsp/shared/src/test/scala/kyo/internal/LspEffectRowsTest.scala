package kyo.internal

import kyo.*

/** Compile-time effect-row assertions for LspServer methods.
  *
  * Notification methods return `Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]]`.
  * Request methods return `Fiber.Unsafe[T, Abort[LspRequestFailure]]`.
  * Lifecycle methods (awaitDrain, close) return `Unit < Async` on the safe tier.
  *
  * These compile-time evidence tests confirm the return rows at the safe-extension level.
  */
class LspEffectRowsTest extends Test:

    // Safe-tier notify methods: Unit < (Async & Abort[LspConnectionClosedException]).
    "showMessage safe row is Unit < (Async & Abort[LspConnectionClosedException])" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ShowMessageParams(LspHandler.MessageType.Info, "hello")
                val e: Unit < (Async & Abort[LspConnectionClosedException]) = server.showMessage(params)
                Abort.run[Closed](e).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "logMessage safe row is Unit < (Async & Abort[LspConnectionClosedException])" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params                                                  = LspHandler.LogMessageParams(LspHandler.MessageType.Log, "log")
                val e: Unit < (Async & Abort[LspConnectionClosedException]) = server.logMessage(params)
                Abort.run[Closed](e).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "publishDiagnostics safe row is Unit < (Async & Abort[LspConnectionClosedException])" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val uri                                                     = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get
                val params                                                  = LspHandler.PublishDiagnosticsParams(uri, Absent, Chunk.empty)
                val e: Unit < (Async & Abort[LspConnectionClosedException]) = server.publishDiagnostics(params)
                Abort.run[Closed](e).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "telemetry safe row is Unit < (Async & Abort[LspConnectionClosedException])" in {
        case class Event(name: String) derives Schema
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val e: Unit < (Async & Abort[LspConnectionClosedException]) = server.telemetry(Event("test"))
                Abort.run[Closed](e).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "logTrace safe row is Unit < (Async & Abort[LspConnectionClosedException])" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val e: Unit < (Async & Abort[LspConnectionClosedException]) = server.logTrace("msg")
                Abort.run[Closed](e).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "workDoneProgress safe row is Unit < (Async & Abort[LspConnectionClosedException])" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val token                                                   = LspHandler.ProgressToken.StringToken("t")
                val value                                                   = LspHandler.WorkDoneProgressValue.Begin(title = "Working")
                val e: Unit < (Async & Abort[LspConnectionClosedException]) = server.workDoneProgress(token, value)
                Abort.run[Closed](e).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "cancel safe row is Unit < (Async & Abort[LspConnectionClosedException])" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val e: Unit < (Async & Abort[LspConnectionClosedException]) = server.cancel(JsonRpcId(1L))
                Abort.run[Closed](e).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    // Safe-tier request methods: T < (Async & Abort[LspRequestFailure]).
    // Type-check only; not awaited (reverse-direction requests need a connected client peer).
    "showMessageRequest safe row is Maybe[MessageActionItem] < (Async & Abort[LspRequestFailure]) (type check only)" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ShowMessageRequestParams(LspHandler.MessageType.Info, "choose", Chunk.empty)
                val _: Maybe[LspHandler.MessageActionItem] < (Async & Abort[LspRequestFailure]) =
                    server.showMessageRequest(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "applyEdit safe row is ApplyWorkspaceEditResult < (Async & Abort[LspRequestFailure]) (type check only)" in {
        // Compile-time type check; not awaited (reverse-direction request needs a connected client).
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ApplyWorkspaceEditParams(edit = LspHandler.WorkspaceEdit())
                val _: LspHandler.ApplyWorkspaceEditResult < (Async & Abort[LspRequestFailure]) = server.applyEdit(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "getWorkspaceFolders safe row is Maybe[Chunk[WorkspaceFolder]] < (Async & Abort[LspRequestFailure]) (type check only)" in {
        // Compile-time type check; not awaited (reverse-direction request needs a connected client).
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Maybe[Chunk[LspHandler.WorkspaceFolder]] < (Async & Abort[LspRequestFailure]) =
                    server.getWorkspaceFolders
                server.closeNow.andThen(succeed)
            }
        }
    }

    "refreshSemanticTokens safe row is Unit < (Async & Abort[LspRequestFailure]) (type check only)" in {
        // Compile-time type check; not awaited (reverse-direction request needs a connected client).
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _: Unit < (Async & Abort[LspRequestFailure]) = server.refreshSemanticTokens
                server.closeNow.andThen(succeed)
            }
        }
    }

    // Lifecycle rows.
    "awaitDrain safe row is Unit < Async" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val e: Unit < Async = server.awaitDrain
                e.andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "close safe row is Unit < Async" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val e: Unit < Async = server.close
                e.andThen(succeed)
            }
        }
    }

    "closeNow safe row is Unit < Async" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val e: Unit < Async = server.closeNow
                e.andThen(succeed)
            }
        }
    }

end LspEffectRowsTest
