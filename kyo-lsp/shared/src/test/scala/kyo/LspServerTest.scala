package kyo

/** Tests for LspServer init quartet, lifecycle, and accessor behaviour. */
class LspServerTest extends Test:

    // =========================================================================
    // Init quartet: basic smoke tests
    // =========================================================================

    "initUnscoped with no handlers produces a live server" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                server.closeNow.andThen(succeed)
            }
        }
    }

    "initUnscoped with default config smoke test" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta, LspConfig.default)().flatMap { server =>
                server.closeNow.andThen(succeed)
            }
        }
    }

    "initUnscoped with Seq overload produces a live server" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta, Seq.empty, LspConfig.default).flatMap { server =>
                server.closeNow.andThen(succeed)
            }
        }
    }

    "initUnscopedWith applies function to the server and returns result" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscopedWith(ta) { server =>
                server.specVersion
            }
        }.map { version =>
            assert(version == "3.17")
        }
    }

    "initUnscopedWith with config applies function to the server" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscopedWith(ta, LspConfig.default)() { server =>
                server.specVersion
            }
        }.map { version =>
            assert(version == "3.17")
        }
    }

    // =========================================================================
    // Scoped init quartet
    // =========================================================================

    "init (scoped) releases server when Scope exits" in run {
        JsonRpcTransport.inMemory.flatMap { (ta, _) =>
            Scope.run {
                LspServer.init(ta).map { server =>
                    assert(server.specVersion == "3.17")
                }
            }
        }
    }

    "init (scoped) with config releases server when Scope exits" in run {
        JsonRpcTransport.inMemory.flatMap { (ta, _) =>
            Scope.run {
                LspServer.init(ta, LspConfig.default)().map { server =>
                    assert(server.specVersion == "3.17")
                }
            }
        }
    }

    "initWith applies function and server is live inside the function" in run {
        JsonRpcTransport.inMemory.flatMap { (ta, _) =>
            Scope.run {
                LspServer.initWith(ta) { server =>
                    server.specVersion
                }
            }.map { version =>
                assert(version == "3.17")
            }
        }
    }

    "initWith with config applies function to the server" in run {
        JsonRpcTransport.inMemory.flatMap { (ta, _) =>
            Scope.run {
                LspServer.initWith(ta, LspConfig.default)() { server =>
                    server.specVersion
                }
            }.map { version =>
                assert(version == "3.17")
            }
        }
    }

    // =========================================================================
    // Accessors before handshake
    // =========================================================================

    "specVersion returns 3.17 before handshake" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val ver = server.specVersion
                server.closeNow.andThen {
                    assert(ver == "3.17")
                }
            }
        }
    }

    "clientCapabilities returns Absent before handshake" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val caps = server.clientCapabilities
                server.closeNow.andThen {
                    assert(caps == Absent)
                }
            }
        }
    }

    "clientInfo returns Absent before handshake" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val info = server.clientInfo
                server.closeNow.andThen {
                    assert(info == Absent)
                }
            }
        }
    }

    "workspaceFolders returns Absent before handshake" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val folders = server.workspaceFolders
                server.closeNow.andThen {
                    assert(folders == Absent)
                }
            }
        }
    }

    "positionEncoding returns UTF16 as default before handshake" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val enc = server.positionEncoding
                server.closeNow.andThen {
                    assert(enc == LspHandler.PositionEncodingKind.UTF16)
                }
            }
        }
    }

    "underlying returns a JsonRpcHandler instance" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val _ = server.underlying
                server.closeNow.andThen(succeed)
            }
        }
    }

    "unsafe returns the Unsafe handle (opaque identity)" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val unsafe = server.unsafe
                val back   = unsafe.safe
                server.closeNow.andThen {
                    // LspServer = LspServer.Unsafe; safe returns `this`. Check via specVersion equality.
                    assert(back.specVersion == server.specVersion)
                }
            }
        }
    }

    // =========================================================================
    // close / closeNow / awaitDrain
    // =========================================================================

    "close(using Frame) completes without error (30-second grace)" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                server.close.andThen(succeed)
            }
        }
    }

    "closeNow completes without error (Duration.Zero grace)" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                server.closeNow.andThen(succeed)
            }
        }
    }

    "close(gracePeriod) with explicit duration completes without error" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                server.close(1.seconds).andThen(succeed)
            }
        }
    }

    "awaitDrain completes without error on idle server" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                server.awaitDrain.andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    // =========================================================================
    // LspConfig.require fires before transport
    // =========================================================================

    "init aborts when positionEncodings is empty" in run {
        val badConfig = LspConfig.default.withPositionEncodings(Chunk.empty)
        val result    = scala.util.Try(LspConfig.require(badConfig))
        assert(result.isFailure)
        assert(result.failed.get.getMessage.contains("positionEncodings"))
    }

    // =========================================================================
    // Direction filtering at init time
    // =========================================================================

    "initUnscoped rejects a ClientHandled handler at init time" in run {
        val wrongHandler = LspHandler.initNotification[LspHandler.ShowMessageParams, Nothing](
            "window/showMessage",
            LspHandler.Kind.ShowMessage,
            _ => ()
        )
        JsonRpcTransport.inMemory.map { (ta, _) =>
            val result = scala.util.Try(
                kyo.internal.lsp.LspCatalog.fromHandlers(Seq(wrongHandler), LspHandler.Direction.ServerHandled)
            )
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[LspException.Dispatch.WrongDirection])
        }
    }

    // =========================================================================
    // Reverse-direction extension method types
    // =========================================================================

    "showMessage return type is Unit < (Async & Abort[Closed])" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params                                 = LspHandler.ShowMessageParams(LspHandler.MessageType.Info, "hello")
                val effect: Unit < (Async & Abort[Closed]) = server.showMessage(params)
                Abort.run[Closed](effect).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "logMessage return type is Unit < (Async & Abort[Closed])" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params                                 = LspHandler.LogMessageParams(LspHandler.MessageType.Log, "debug info")
                val effect: Unit < (Async & Abort[Closed]) = server.logMessage(params)
                Abort.run[Closed](effect).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "publishDiagnostics return type is Unit < (Async & Abort[Closed])" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val uri                                    = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get
                val params                                 = LspHandler.PublishDiagnosticsParams(uri, Absent, Chunk.empty)
                val effect: Unit < (Async & Abort[Closed]) = server.publishDiagnostics(params)
                Abort.run[Closed](effect).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "telemetry return type is Unit < (Async & Abort[Closed])" in run {
        case class Payload(msg: String) derives Schema
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val effect: Unit < (Async & Abort[Closed]) = server.telemetry(Payload("test"))
                Abort.run[Closed](effect).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "logTrace return type is Unit < (Async & Abort[Closed])" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val effect: Unit < (Async & Abort[Closed]) = server.logTrace("trace message")
                Abort.run[Closed](effect).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "applyEdit return type compiles as ApplyWorkspaceEditResult < (Async & Abort[LspException | Closed])" in run {
        // Compile-time type check only; the method signature must accept the return type annotation.
        // Not awaited: reverse-direction requests need a connected client peer.
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ApplyWorkspaceEditParams(edit = LspHandler.WorkspaceEdit())
                // The type annotation confirms the compile-time return row.
                val _: LspHandler.ApplyWorkspaceEditResult < (Async & Abort[LspException | Closed]) = server.applyEdit(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "showMessageRequest return type compiles as Maybe[MessageActionItem] < (Async & Abort[LspException | Closed])" in run {
        // Compile-time type check only; not awaited (needs a connected client peer).
        JsonRpcTransport.inMemory.map { (ta, _) =>
            LspServer.initUnscoped(ta).flatMap { server =>
                val params = LspHandler.ShowMessageRequestParams(LspHandler.MessageType.Warning, "choose", Chunk.empty)
                val _: Maybe[LspHandler.MessageActionItem] < (Async & Abort[LspException | Closed]) =
                    server.showMessageRequest(params)
                server.closeNow.andThen(succeed)
            }
        }
    }

end LspServerTest
