package kyo.internal

import kyo.*
import kyo.internal.lsp.LspContentSchemas

class LspHandlerFactoryTest extends kyo.Test:

    // MARK: -- textDocument namespace factory tests

    "textDocument namespace factories produce valid handlers" - {
        "completion factory returns non-null handler with correct name and kind" in {
            val h = LspHandler.TextDocument.completion(_ => LspHandler.CompletionResult.Items(Chunk.empty))
            assert(h.name == "textDocument/completion")
            assert(h.kind == LspHandler.Kind.Completion)
            assert(h.direction == LspHandler.Direction.ServerHandled)
            assert(h.errorMappings.isEmpty)
            succeed
        }

        "completionItemResolve factory" in {
            val h = LspHandler.TextDocument.completionItemResolve(item => item)
            assert(h.name == "completionItem/resolve")
            assert(h.kind == LspHandler.Kind.CompletionItemResolve)
            succeed
        }

        "hover factory" in {
            val h = LspHandler.TextDocument.hover(_ => Absent)
            assert(h.name == "textDocument/hover")
            assert(h.kind == LspHandler.Kind.Hover)
            succeed
        }

        "signatureHelp factory" in {
            val h = LspHandler.TextDocument.signatureHelp(_ => Absent)
            assert(h.name == "textDocument/signatureHelp")
            assert(h.kind == LspHandler.Kind.SignatureHelp)
            succeed
        }

        "declaration factory returns DeclarationResult directly (A4 fix)" in {
            val h = LspHandler.TextDocument.declaration(_ => LspHandler.DeclarationResult.Many(Chunk.empty))
            assert(h.name == "textDocument/declaration")
            assert(h.kind == LspHandler.Kind.Declaration)
            succeed
        }

        "definition factory returns DefinitionResult directly (A4 fix)" in {
            val h = LspHandler.TextDocument.definition(_ => LspHandler.DefinitionResult.Many(Chunk.empty))
            assert(h.name == "textDocument/definition")
            assert(h.kind == LspHandler.Kind.Definition)
            succeed
        }

        "typeDefinition factory returns DefinitionResult directly (A4 fix)" in {
            val h = LspHandler.TextDocument.typeDefinition(_ => LspHandler.DefinitionResult.Many(Chunk.empty))
            assert(h.name == "textDocument/typeDefinition")
            assert(h.kind == LspHandler.Kind.TypeDefinition)
            succeed
        }

        "implementation factory returns DefinitionResult directly (A4 fix)" in {
            val h = LspHandler.TextDocument.implementation(_ => LspHandler.DefinitionResult.Many(Chunk.empty))
            assert(h.name == "textDocument/implementation")
            assert(h.kind == LspHandler.Kind.Implementation)
            succeed
        }

        "references factory" in {
            val h = LspHandler.TextDocument.references(_ => Chunk.empty)
            assert(h.name == "textDocument/references")
            assert(h.kind == LspHandler.Kind.References)
            succeed
        }

        "documentHighlight factory" in {
            val h = LspHandler.TextDocument.documentHighlight(_ => Chunk.empty)
            assert(h.name == "textDocument/documentHighlight")
            assert(h.kind == LspHandler.Kind.DocumentHighlight)
            succeed
        }

        "documentSymbol factory" in {
            val h = LspHandler.TextDocument.documentSymbol(_ => LspHandler.DocumentSymbolResult.Symbols(Chunk.empty))
            assert(h.name == "textDocument/documentSymbol")
            assert(h.kind == LspHandler.Kind.DocumentSymbol)
            succeed
        }

        "codeAction factory" in {
            val h = LspHandler.TextDocument.codeAction(_ => Chunk.empty)
            assert(h.name == "textDocument/codeAction")
            assert(h.kind == LspHandler.Kind.CodeAction)
            succeed
        }

        "codeActionResolve factory" in {
            val h = LspHandler.TextDocument.codeActionResolve(ca => ca)
            assert(h.name == "codeAction/resolve")
            assert(h.kind == LspHandler.Kind.CodeActionResolve)
            succeed
        }

        "codeLens factory" in {
            val h = LspHandler.TextDocument.codeLens(_ => Chunk.empty)
            assert(h.name == "textDocument/codeLens")
            assert(h.kind == LspHandler.Kind.CodeLens)
            succeed
        }

        "codeLensResolve factory" in {
            val h = LspHandler.TextDocument.codeLensResolve(cl => cl)
            assert(h.name == "codeLens/resolve")
            assert(h.kind == LspHandler.Kind.CodeLensResolve)
            succeed
        }

        "documentLink factory" in {
            val h = LspHandler.TextDocument.documentLink(_ => Chunk.empty)
            assert(h.name == "textDocument/documentLink")
            assert(h.kind == LspHandler.Kind.DocumentLink)
            succeed
        }

        "documentLinkResolve factory" in {
            val h = LspHandler.TextDocument.documentLinkResolve(dl => dl)
            assert(h.name == "documentLink/resolve")
            assert(h.kind == LspHandler.Kind.DocumentLinkResolve)
            succeed
        }

        "documentColor factory" in {
            val h = LspHandler.TextDocument.documentColor(_ => Chunk.empty)
            assert(h.name == "textDocument/documentColor")
            assert(h.kind == LspHandler.Kind.DocumentColor)
            succeed
        }

        "colorPresentation factory" in {
            val h = LspHandler.TextDocument.colorPresentation(_ => Chunk.empty)
            assert(h.name == "textDocument/colorPresentation")
            assert(h.kind == LspHandler.Kind.ColorPresentation)
            succeed
        }

        "formatting factory" in {
            val h = LspHandler.TextDocument.formatting(_ => Chunk.empty)
            assert(h.name == "textDocument/formatting")
            assert(h.kind == LspHandler.Kind.Formatting)
            succeed
        }

        "rangeFormatting factory" in {
            val h = LspHandler.TextDocument.rangeFormatting(_ => Chunk.empty)
            assert(h.name == "textDocument/rangeFormatting")
            assert(h.kind == LspHandler.Kind.RangeFormatting)
            succeed
        }

        "onTypeFormatting factory" in {
            val h = LspHandler.TextDocument.onTypeFormatting(_ => Chunk.empty)
            assert(h.name == "textDocument/onTypeFormatting")
            assert(h.kind == LspHandler.Kind.OnTypeFormatting)
            succeed
        }

        "rename factory" in {
            val h = LspHandler.TextDocument.rename(_ => Absent)
            assert(h.name == "textDocument/rename")
            assert(h.kind == LspHandler.Kind.Rename)
            succeed
        }

        "prepareRename factory" in {
            val h = LspHandler.TextDocument.prepareRename(_ => Absent)
            assert(h.name == "textDocument/prepareRename")
            assert(h.kind == LspHandler.Kind.PrepareRename)
            succeed
        }

        "foldingRange factory" in {
            val h = LspHandler.TextDocument.foldingRange(_ => Chunk.empty)
            assert(h.name == "textDocument/foldingRange")
            assert(h.kind == LspHandler.Kind.FoldingRange)
            succeed
        }

        "selectionRange factory" in {
            val h = LspHandler.TextDocument.selectionRange(_ => Chunk.empty)
            assert(h.name == "textDocument/selectionRange")
            assert(h.kind == LspHandler.Kind.SelectionRange)
            succeed
        }

        "linkedEditingRange factory" in {
            val h = LspHandler.TextDocument.linkedEditingRange(_ => Absent)
            assert(h.name == "textDocument/linkedEditingRange")
            assert(h.kind == LspHandler.Kind.LinkedEditingRange)
            succeed
        }

        "prepareCallHierarchy factory" in {
            val h = LspHandler.TextDocument.prepareCallHierarchy(_ => Chunk.empty)
            assert(h.name == "textDocument/prepareCallHierarchy")
            assert(h.kind == LspHandler.Kind.PrepareCallHierarchy)
            succeed
        }

        "callHierarchyIncomingCalls factory" in {
            val h = LspHandler.TextDocument.callHierarchyIncomingCalls(_ => Chunk.empty)
            assert(h.name == "callHierarchy/incomingCalls")
            assert(h.kind == LspHandler.Kind.CallHierarchyIncomingCalls)
            succeed
        }

        "callHierarchyOutgoingCalls factory" in {
            val h = LspHandler.TextDocument.callHierarchyOutgoingCalls(_ => Chunk.empty)
            assert(h.name == "callHierarchy/outgoingCalls")
            assert(h.kind == LspHandler.Kind.CallHierarchyOutgoingCalls)
            succeed
        }

        "prepareTypeHierarchy factory" in {
            val h = LspHandler.TextDocument.prepareTypeHierarchy(_ => Chunk.empty)
            assert(h.name == "textDocument/prepareTypeHierarchy")
            assert(h.kind == LspHandler.Kind.PrepareTypeHierarchy)
            succeed
        }

        "typeHierarchySupertypes factory uses renamed params type" in {
            val params = LspHandler.TypeHierarchySupertypesParams(
                item = LspHandler.TypeHierarchyItem(
                    "SomeName",
                    LspHandler.SymbolKind.Class,
                    Chunk.empty,
                    Absent,
                    LspHandler.LspDocument.Uri.fromWire("file:///a.scala"),
                    LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(1, 0)),
                    LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 4))
                )
            )
            val h = LspHandler.TextDocument.typeHierarchySupertypes(_ => Chunk.empty)
            assert(h.name == "typeHierarchy/supertypes")
            assert(h.kind == LspHandler.Kind.TypeHierarchySupertypes)
            succeed
        }

        "typeHierarchySubtypes factory uses renamed params type" in {
            val h = LspHandler.TextDocument.typeHierarchySubtypes(_ => Chunk.empty)
            assert(h.name == "typeHierarchy/subtypes")
            assert(h.kind == LspHandler.Kind.TypeHierarchySubtypes)
            succeed
        }

        "semanticTokensFull factory" in {
            val h = LspHandler.TextDocument.semanticTokensFull(_ => Absent)
            assert(h.name == "textDocument/semanticTokens/full")
            assert(h.kind == LspHandler.Kind.SemanticTokensFull)
            succeed
        }

        "semanticTokensFullDelta factory" in {
            val h = LspHandler.TextDocument.semanticTokensFullDelta(_ => Absent)
            assert(h.name == "textDocument/semanticTokens/full/delta")
            assert(h.kind == LspHandler.Kind.SemanticTokensFullDelta)
            succeed
        }

        "semanticTokensRange factory" in {
            val h = LspHandler.TextDocument.semanticTokensRange(_ => Absent)
            assert(h.name == "textDocument/semanticTokens/range")
            assert(h.kind == LspHandler.Kind.SemanticTokensRange)
            succeed
        }

        "moniker factory" in {
            val h = LspHandler.TextDocument.moniker(_ => Chunk.empty)
            assert(h.name == "textDocument/moniker")
            assert(h.kind == LspHandler.Kind.Moniker)
            succeed
        }

        "inlayHint factory" in {
            val h = LspHandler.TextDocument.inlayHint(_ => Chunk.empty)
            assert(h.name == "textDocument/inlayHint")
            assert(h.kind == LspHandler.Kind.InlayHint)
            succeed
        }

        "inlayHintResolve factory" in {
            val h = LspHandler.TextDocument.inlayHintResolve(ih => ih)
            assert(h.name == "inlayHint/resolve")
            assert(h.kind == LspHandler.Kind.InlayHintResolve)
            succeed
        }

        "inlineValue factory" in {
            val h = LspHandler.TextDocument.inlineValue(_ => Chunk.empty)
            assert(h.name == "textDocument/inlineValue")
            assert(h.kind == LspHandler.Kind.InlineValue)
            succeed
        }

        "diagnostic factory" in {
            val h = LspHandler.TextDocument.diagnostic(_ =>
                LspHandler.DocumentDiagnosticReport.Full(items = Chunk.empty)
            )
            assert(h.name == "textDocument/diagnostic")
            assert(h.kind == LspHandler.Kind.DocumentDiagnostic)
            succeed
        }

        "willSaveWaitUntil factory" in {
            val h = LspHandler.TextDocument.willSaveWaitUntil(_ => Chunk.empty)
            assert(h.name == "textDocument/willSaveWaitUntil")
            assert(h.kind == LspHandler.Kind.TextDocumentWillSaveWaitUntil)
            succeed
        }

        "didOpen factory is NotificationHandler" in {
            val h = LspHandler.TextDocument.didOpen(_ => ())
            assert(h.name == "textDocument/didOpen")
            assert(h.kind == LspHandler.Kind.TextDocumentDidOpen)
            assert(h.direction == LspHandler.Direction.ServerHandled)
            succeed
        }

        "didChange factory" in {
            val h = LspHandler.TextDocument.didChange(_ => ())
            assert(h.name == "textDocument/didChange")
            assert(h.kind == LspHandler.Kind.TextDocumentDidChange)
            succeed
        }

        "didSave factory" in {
            val h = LspHandler.TextDocument.didSave(_ => ())
            assert(h.name == "textDocument/didSave")
            assert(h.kind == LspHandler.Kind.TextDocumentDidSave)
            succeed
        }

        "didClose factory" in {
            val h = LspHandler.TextDocument.didClose(_ => ())
            assert(h.name == "textDocument/didClose")
            assert(h.kind == LspHandler.Kind.TextDocumentDidClose)
            succeed
        }

        "willSave factory" in {
            val h = LspHandler.TextDocument.willSave(_ => ())
            assert(h.name == "textDocument/willSave")
            assert(h.kind == LspHandler.Kind.TextDocumentWillSave)
            succeed
        }
    }

    // MARK: -- workspace namespace factory tests

    "workspace namespace factories produce valid handlers" - {
        "symbol factory" in {
            val h = LspHandler.Workspace.symbol(_ => Chunk.empty)
            assert(h.name == "workspace/symbol")
            assert(h.kind == LspHandler.Kind.WorkspaceSymbol)
            succeed
        }

        "symbolResolve factory" in {
            val h = LspHandler.Workspace.symbolResolve(ws => ws)
            assert(h.name == "workspaceSymbol/resolve")
            assert(h.kind == LspHandler.Kind.WorkspaceSymbolResolve)
            succeed
        }

        "executeCommand factory" in {
            val h = LspHandler.Workspace.executeCommand[String](_ => Absent)
            assert(h.name == "workspace/executeCommand")
            assert(h.kind == LspHandler.Kind.ExecuteCommand)
            succeed
        }

        "didChangeConfiguration factory" in {
            val h = LspHandler.Workspace.didChangeConfiguration[String](_ => ())
            assert(h.name == "workspace/didChangeConfiguration")
            assert(h.kind == LspHandler.Kind.DidChangeConfiguration)
            succeed
        }

        "didChangeWatchedFiles factory" in {
            val h = LspHandler.Workspace.didChangeWatchedFiles(_ => ())
            assert(h.name == "workspace/didChangeWatchedFiles")
            assert(h.kind == LspHandler.Kind.DidChangeWatchedFiles)
            succeed
        }

        "didChangeWorkspaceFolders factory" in {
            val h = LspHandler.Workspace.didChangeWorkspaceFolders(_ => ())
            assert(h.name == "workspace/didChangeWorkspaceFolders")
            assert(h.kind == LspHandler.Kind.DidChangeWorkspaceFolders)
            succeed
        }

        "willCreateFiles factory" in {
            val h = LspHandler.Workspace.willCreateFiles(_ => Absent)
            assert(h.name == "workspace/willCreateFiles")
            assert(h.kind == LspHandler.Kind.WillCreateFiles)
            succeed
        }

        "didCreateFiles factory" in {
            val h = LspHandler.Workspace.didCreateFiles(_ => ())
            assert(h.name == "workspace/didCreateFiles")
            assert(h.kind == LspHandler.Kind.DidCreateFiles)
            succeed
        }

        "willRenameFiles factory" in {
            val h = LspHandler.Workspace.willRenameFiles(_ => Absent)
            assert(h.name == "workspace/willRenameFiles")
            assert(h.kind == LspHandler.Kind.WillRenameFiles)
            succeed
        }

        "didRenameFiles factory" in {
            val h = LspHandler.Workspace.didRenameFiles(_ => ())
            assert(h.name == "workspace/didRenameFiles")
            assert(h.kind == LspHandler.Kind.DidRenameFiles)
            succeed
        }

        "willDeleteFiles factory" in {
            val h = LspHandler.Workspace.willDeleteFiles(_ => Absent)
            assert(h.name == "workspace/willDeleteFiles")
            assert(h.kind == LspHandler.Kind.WillDeleteFiles)
            succeed
        }

        "didDeleteFiles factory" in {
            val h = LspHandler.Workspace.didDeleteFiles(_ => ())
            assert(h.name == "workspace/didDeleteFiles")
            assert(h.kind == LspHandler.Kind.DidDeleteFiles)
            succeed
        }

        "diagnostic factory" in {
            val h = LspHandler.Workspace.diagnostic(_ => LspHandler.WorkspaceDiagnosticReport(Chunk.empty))
            assert(h.name == "workspace/diagnostic")
            assert(h.kind == LspHandler.Kind.WorkspaceDiagnostic)
            succeed
        }

        "applyEdit factory is ClientHandled" in {
            val h = LspHandler.Workspace.applyEdit(_ => LspHandler.ApplyWorkspaceEditResult(applied = true))
            assert(h.name == "workspace/applyEdit")
            assert(h.kind == LspHandler.Kind.ApplyEdit)
            assert(h.direction == LspHandler.Direction.ClientHandled)
            succeed
        }

        "configuration factory" in {
            val h = LspHandler.Workspace.configuration[String](_ => Chunk.empty)
            assert(h.name == "workspace/configuration")
            assert(h.kind == LspHandler.Kind.Configuration)
            succeed
        }

        "workspaceFolders factory" in {
            val h = LspHandler.Workspace.workspaceFolders(_ => Absent)
            assert(h.name == "workspace/workspaceFolders")
            assert(h.kind == LspHandler.Kind.WorkspaceFolders)
            succeed
        }

        "refreshSemanticTokens factory" in {
            val h = LspHandler.Workspace.refreshSemanticTokens
            assert(h.name == "workspace/semanticTokens/refresh")
            assert(h.kind == LspHandler.Kind.RefreshSemanticTokens)
            assert(h.direction == LspHandler.Direction.ClientHandled)
            succeed
        }

        "refreshInlineValue factory" in {
            val h = LspHandler.Workspace.refreshInlineValue
            assert(h.name == "workspace/inlineValue/refresh")
            assert(h.kind == LspHandler.Kind.RefreshInlineValue)
            succeed
        }

        "refreshInlayHint factory" in {
            val h = LspHandler.Workspace.refreshInlayHint
            assert(h.name == "workspace/inlayHint/refresh")
            assert(h.kind == LspHandler.Kind.RefreshInlayHint)
            succeed
        }

        "refreshDiagnostic factory" in {
            val h = LspHandler.Workspace.refreshDiagnostic
            assert(h.name == "workspace/diagnostic/refresh")
            assert(h.kind == LspHandler.Kind.RefreshDiagnostic)
            succeed
        }

        "refreshCodeLens factory" in {
            val h = LspHandler.Workspace.refreshCodeLens
            assert(h.name == "workspace/codeLens/refresh")
            assert(h.kind == LspHandler.Kind.RefreshCodeLens)
            succeed
        }
    }

    // MARK: -- notebookDocument namespace factory tests

    "notebookDocument namespace factories produce valid handlers" - {
        "didOpen factory" in {
            val h = LspHandler.NotebookDocument.didOpen(_ => ())
            assert(h.name == "notebookDocument/didOpen")
            assert(h.kind == LspHandler.Kind.NotebookDidOpen)
            succeed
        }

        "didChange factory" in {
            val h = LspHandler.NotebookDocument.didChange(_ => ())
            assert(h.name == "notebookDocument/didChange")
            assert(h.kind == LspHandler.Kind.NotebookDidChange)
            succeed
        }

        "didSave factory" in {
            val h = LspHandler.NotebookDocument.didSave(_ => ())
            assert(h.name == "notebookDocument/didSave")
            assert(h.kind == LspHandler.Kind.NotebookDidSave)
            succeed
        }

        "didClose factory" in {
            val h = LspHandler.NotebookDocument.didClose(_ => ())
            assert(h.name == "notebookDocument/didClose")
            assert(h.kind == LspHandler.Kind.NotebookDidClose)
            succeed
        }
    }

    // MARK: -- window namespace factory tests

    "window namespace factories produce valid handlers" - {
        "showMessage factory is ClientHandled" in {
            val h = LspHandler.Window.showMessage(_ => ())
            assert(h.name == "window/showMessage")
            assert(h.kind == LspHandler.Kind.ShowMessage)
            assert(h.direction == LspHandler.Direction.ClientHandled)
            succeed
        }

        "showMessageRequest factory" in {
            val h = LspHandler.Window.showMessageRequest(_ => Absent)
            assert(h.name == "window/showMessageRequest")
            assert(h.kind == LspHandler.Kind.ShowMessageRequest)
            succeed
        }

        "showDocument factory" in {
            val h = LspHandler.Window.showDocument(_ => LspHandler.ShowDocumentResult(success = true))
            assert(h.name == "window/showDocument")
            assert(h.kind == LspHandler.Kind.ShowDocument)
            succeed
        }

        "logMessage factory" in {
            val h = LspHandler.Window.logMessage(_ => ())
            assert(h.name == "window/logMessage")
            assert(h.kind == LspHandler.Kind.LogMessage)
            succeed
        }

        "createWorkDoneProgress factory" in {
            val h = LspHandler.Window.createWorkDoneProgress(_ => ())
            assert(h.name == "window/workDoneProgress/create")
            assert(h.kind == LspHandler.Kind.WorkDoneProgressCreate)
            succeed
        }

        "workDoneProgressCancel factory" in {
            val h = LspHandler.Window.workDoneProgressCancel(_ => ())
            assert(h.name == "window/workDoneProgress/cancel")
            assert(h.kind == LspHandler.Kind.WorkDoneProgressCancel)
            succeed
        }

        "telemetry factory" in {
            val h = LspHandler.Window.telemetry[String](_ => ())
            assert(h.name == "telemetry/event")
            assert(h.kind == LspHandler.Kind.Telemetry)
            succeed
        }
    }

    // MARK: -- client namespace factory tests

    "client namespace factories produce valid handlers" - {
        "registerCapability factory is ClientHandled" in {
            val h = LspHandler.Client.registerCapability(_ => ())
            assert(h.name == "client/registerCapability")
            assert(h.kind == LspHandler.Kind.RegisterCapability)
            assert(h.direction == LspHandler.Direction.ClientHandled)
            succeed
        }

        "unregisterCapability factory" in {
            val h = LspHandler.Client.unregisterCapability(_ => ())
            assert(h.name == "client/unregisterCapability")
            assert(h.kind == LspHandler.Kind.UnregisterCapability)
            succeed
        }
    }

    // MARK: -- custom escape hatch tests

    "custom escape hatch factories" - {
        "custom creates ServerHandled handler with given method" in {
            val h = LspHandler.custom[String, String]("vendor/myMethod")(s => s)
            assert(h.name == "vendor/myMethod")
            assert(h.kind == LspHandler.Kind.Custom)
            assert(h.direction == LspHandler.Direction.ServerHandled)
            succeed
        }

        "customClient creates ClientHandled handler" in {
            val h = LspHandler.customClient[String, String]("vendor/clientMethod")(s => s)
            assert(h.name == "vendor/clientMethod")
            assert(h.kind == LspHandler.Kind.Custom)
            assert(h.direction == LspHandler.Direction.ClientHandled)
            succeed
        }
    }

    // MARK: -- .error[E2] extension tests

    "error extension method" - {
        "adds error mapping to RequestHandler" in {
            case class MyError(msg: String) derives Schema, CanEqual
            val h0 = LspHandler.TextDocument.hover(_ => Absent)
            assert(h0.errorMappings.isEmpty)

            val h1 = h0.error[MyError](code = -32001, message = "my error")
            assert(h1.errorMappings.size == 1)
            assert(h1.errorMappings(0).code == -32001)
            assert(h1.errorMappings(0).message == "my error")
            assert(h1.name == "textDocument/hover")
            assert(h1.kind == LspHandler.Kind.Hover)
            succeed
        }

        "adds error mapping to NotificationHandler" in {
            case class NotifError(code: Int) derives Schema, CanEqual
            val h0 = LspHandler.TextDocument.didOpen(_ => ())
            assert(h0.errorMappings.isEmpty)

            val h1 = h0.error[NotifError](code = -32002, message = "notif error")
            assert(h1.errorMappings.size == 1)
            assert(h1.errorMappings(0).code == -32002)
            succeed
        }

        "chains multiple error mappings" in {
            case class Err1(a: String) derives Schema, CanEqual
            case class Err2(b: Int) derives Schema, CanEqual
            val h0 = LspHandler.TextDocument.completion(_ => LspHandler.CompletionResult.Items(Chunk.empty))
            val h1 = h0.error[Err1](code = 1, message = "err1")
            val h2 = h1.error[Err2](code = 2, message = "err2")
            assert(h2.errorMappings.size == 2)
            assert(h2.errorMappings(0).code == 1)
            assert(h2.errorMappings(1).code == 2)
            succeed
        }

        "adds error mapping to CustomHandler" in {
            case class CustomErr(reason: String) derives Schema, CanEqual
            val h0 = LspHandler.custom[String, String]("vendor/x")(s => s)
            val h1 = h0.error[CustomErr](code = -32003, message = "custom err")
            assert(h1.errorMappings.size == 1)
            assert(h1.errorMappings(0).code == -32003)
            succeed
        }
    }

    // MARK: -- Schema bug fix round-trip tests (A1-A3)

    "CommandOrCodeAction schema decode fixes" - {
        "decodes a Command with string command field" in {
            val cmd: LspHandler.CommandOrCodeAction = LspHandler.CommandOrCodeAction.Cmd(
                LspHandler.Command("Run Tests", "test.run", Chunk("arg1", "arg2"))
            )
            val json    = Json.encode(cmd)
            val decoded = Json.decode[LspHandler.CommandOrCodeAction](json)
            decoded match
                case Result.Success(LspHandler.CommandOrCodeAction.Cmd(LspHandler.Command(title, command, args))) =>
                    assert(title == "Run Tests")
                    assert(command == "test.run")
                    // arguments contain string elements (string args round-trip)
                    assert(args == Chunk("arg1", "arg2"))
                case other =>
                    fail(s"Expected Cmd, got: $other (json=$json)")
            end match
            succeed
        }

        "decodes a CodeAction with nested command object" in {
            val cmd                                     = LspHandler.Command("Apply Fix", "fix.apply", Chunk.empty)
            val ca                                      = LspHandler.CodeAction(title = "Fix all", command = Present(cmd))
            val cmdOrCa: LspHandler.CommandOrCodeAction = LspHandler.CommandOrCodeAction.Action(ca)
            val json                                    = Json.encode(cmdOrCa)
            val decoded                                 = Json.decode[LspHandler.CommandOrCodeAction](json)
            decoded match
                case Result.Success(LspHandler.CommandOrCodeAction.Action(action)) =>
                    assert(action.title == "Fix all")
                    action.command match
                        case Present(c) => assert(c.command == "fix.apply")
                        case Absent     => fail("Expected command to be present")
                case other =>
                    fail(s"Expected Action with nested command, got: $other")
            end match
            succeed
        }

        "decodes a CodeAction with edit field (not confused with Command)" in {
            val edit                                    = LspHandler.WorkspaceEdit()
            val ca                                      = LspHandler.CodeAction(title = "Rename", edit = Present(edit))
            val cmdOrCa: LspHandler.CommandOrCodeAction = LspHandler.CommandOrCodeAction.Action(ca)
            val json                                    = Json.encode(cmdOrCa)
            val decoded                                 = Json.decode[LspHandler.CommandOrCodeAction](json)
            decoded match
                case Result.Success(LspHandler.CommandOrCodeAction.Action(action)) =>
                    assert(action.title == "Rename")
                    assert(action.edit.isDefined)
                case other =>
                    fail(s"Expected Action, got: $other")
            end match
            succeed
        }
    }

    "WorkspaceEditDocumentChange schema includes options for file operations (A3 fix)" - {
        "decodes CreateFile with options" in {
            val create: LspHandler.WorkspaceEditDocumentChange = LspHandler.WorkspaceEditDocumentChange.Create(
                LspHandler.CreateFile(
                    uri = "file:///new.txt",
                    options = Present(LspHandler.CreateFileOptions(overwrite = Present(true), ignoreIfExists = Present(false)))
                )
            )
            val json    = Json.encode(create)
            val decoded = Json.decode[LspHandler.WorkspaceEditDocumentChange](json)
            decoded match
                case Result.Success(LspHandler.WorkspaceEditDocumentChange.Create(cf)) =>
                    assert(cf.uri == "file:///new.txt")
                    cf.options match
                        case Present(opts) =>
                            assert(opts.overwrite == Present(true))
                            assert(opts.ignoreIfExists == Present(false))
                        case Absent => fail("Expected options to be present")
                    end match
                case other =>
                    fail(s"Expected Create with options, got: $other")
            end match
            succeed
        }

        "decodes RenameFile with options" in {
            val rename: LspHandler.WorkspaceEditDocumentChange = LspHandler.WorkspaceEditDocumentChange.Rename(
                LspHandler.RenameFile(
                    oldUri = "file:///old.txt",
                    newUri = "file:///new.txt",
                    options = Present(LspHandler.RenameFileOptions(overwrite = Present(false), ignoreIfExists = Absent))
                )
            )
            val json    = Json.encode(rename)
            val decoded = Json.decode[LspHandler.WorkspaceEditDocumentChange](json)
            decoded match
                case Result.Success(LspHandler.WorkspaceEditDocumentChange.Rename(rf)) =>
                    assert(rf.oldUri == "file:///old.txt")
                    assert(rf.newUri == "file:///new.txt")
                    rf.options match
                        case Present(opts) => assert(opts.overwrite == Present(false))
                        case Absent        => fail("Expected options to be present")
                case other =>
                    fail(s"Expected Rename with options, got: $other")
            end match
            succeed
        }

        "decodes DeleteFile with options" in {
            val delete: LspHandler.WorkspaceEditDocumentChange = LspHandler.WorkspaceEditDocumentChange.Delete(
                LspHandler.DeleteFile(
                    uri = "file:///old.txt",
                    options = Present(LspHandler.DeleteFileOptions(recursive = Present(true), ignoreIfNotExists = Present(false)))
                )
            )
            val json    = Json.encode(delete)
            val decoded = Json.decode[LspHandler.WorkspaceEditDocumentChange](json)
            decoded match
                case Result.Success(LspHandler.WorkspaceEditDocumentChange.Delete(df)) =>
                    assert(df.uri == "file:///old.txt")
                    df.options match
                        case Present(opts) =>
                            assert(opts.recursive == Present(true))
                            assert(opts.ignoreIfNotExists == Present(false))
                        case Absent => fail("Expected options to be present")
                    end match
                case other =>
                    fail(s"Expected Delete with options, got: $other")
            end match
            succeed
        }
    }

    "TypeHierarchy params use corrected capitalization" - {
        "TypeHierarchySupertypesParams round-trips" in {
            val item = LspHandler.TypeHierarchyItem(
                "MyClass",
                LspHandler.SymbolKind.Class,
                Chunk.empty,
                Absent,
                LspHandler.LspDocument.Uri.fromWire("file:///test.scala"),
                LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(10, 0)),
                LspHandler.Range(LspHandler.Position(0, 6), LspHandler.Position(0, 13))
            )
            val params  = LspHandler.TypeHierarchySupertypesParams(item = item)
            val json    = Json.encode(params)
            val decoded = Json.decode[LspHandler.TypeHierarchySupertypesParams](json)
            decoded match
                case Result.Success(p) =>
                    assert(p.item.name == "MyClass")
                case other =>
                    fail(s"Expected success, got: $other")
            end match
            succeed
        }

        "TypeHierarchySubtypesParams round-trips" in {
            val item = LspHandler.TypeHierarchyItem(
                "SubClass",
                LspHandler.SymbolKind.Class,
                Chunk.empty,
                Absent,
                LspHandler.LspDocument.Uri.fromWire("file:///sub.scala"),
                LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(5, 0)),
                LspHandler.Range(LspHandler.Position(0, 6), LspHandler.Position(0, 14))
            )
            val params  = LspHandler.TypeHierarchySubtypesParams(item = item)
            val json    = Json.encode(params)
            val decoded = Json.decode[LspHandler.TypeHierarchySubtypesParams](json)
            decoded match
                case Result.Success(p) =>
                    assert(p.item.name == "SubClass")
                case other =>
                    fail(s"Expected success, got: $other")
            end match
            succeed
        }
    }

    // MARK: -- general namespace factory tests (A1 fix)

    "general namespace factories produce valid handlers" - {
        "cancelRequest factory has correct name, kind, and direction" in {
            val h = LspHandler.General.cancelRequest(_ => ())
            assert(h.name == "$/cancelRequest")
            assert(h.kind == LspHandler.Kind.CancelRequest)
            assert(h.direction == LspHandler.Direction.Either)
            succeed
        }

        "progress factory has correct name, kind, and direction" in {
            val h = LspHandler.General.progress[String](_ => ())
            assert(h.name == "$/progress")
            assert(h.kind == LspHandler.Kind.Progress)
            assert(h.direction == LspHandler.Direction.Either)
            succeed
        }

        "setTrace factory has correct name, kind, and direction" in {
            val h = LspHandler.General.setTrace(_ => ())
            assert(h.name == "$/setTrace")
            assert(h.kind == LspHandler.Kind.SetTrace)
            assert(h.direction == LspHandler.Direction.ServerHandled)
            succeed
        }

        "logTrace factory has correct name, kind, and direction" in {
            val h = LspHandler.General.logTrace(_ => ())
            assert(h.name == "$/logTrace")
            assert(h.kind == LspHandler.Kind.LogTrace)
            assert(h.direction == LspHandler.Direction.ClientHandled)
            succeed
        }
    }

    "completion factory returns CompletionResult directly (A6 fix)" - {
        "completion handler type is CompletionResult not Maybe[CompletionResult]" in {
            // The factory signature should compile with a non-Maybe handler
            val h = LspHandler.TextDocument.completion(_ =>
                LspHandler.CompletionResult.Items(Chunk(LspHandler.CompletionItem(label = "foo")))
            )
            assert(h.name == "textDocument/completion")
            assert(h.kind == LspHandler.Kind.Completion)
            succeed
        }

        "documentSymbol handler type is DocumentSymbolResult not Maybe[DocumentSymbolResult]" in {
            val h = LspHandler.TextDocument.documentSymbol(_ =>
                LspHandler.DocumentSymbolResult.Symbols(Chunk.empty)
            )
            assert(h.name == "textDocument/documentSymbol")
            assert(h.kind == LspHandler.Kind.DocumentSymbol)
            succeed
        }
    }

end LspHandlerFactoryTest
