package kyo

class LspCapabilitiesTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def roundtrip[A: Schema](value: A): A = Json.decode[A](Json.encode(value)).getOrThrow

    // =========================================================================
    // Type alias correctness (A7 fix)
    // =========================================================================

    "type aliases" - {
        "LspCapabilities.Server resolves to the inner case class" in {
            val s: LspCapabilities.Server = LspCapabilities.Server.empty
            assert(s == LspCapabilities.Server.Server())
        }
        "LspCapabilities.Client resolves to the inner case class" in {
            val c: LspCapabilities.Client = LspCapabilities.Client.empty
            assert(c == LspCapabilities.Client.Client())
        }
        "alias and inner class are the same type" in {
            val byAlias: LspCapabilities.Server       = LspCapabilities.Server.empty
            val byFull: LspCapabilities.Server.Server = LspCapabilities.Server.empty
            assert(byAlias == byFull)
        }
    }

    // =========================================================================
    // Server capabilities
    // =========================================================================

    "Server.empty" - {
        "all fields Absent" in {
            val s = LspCapabilities.Server.empty
            assert(s.positionEncoding.isEmpty)
            assert(s.textDocumentSync.isEmpty)
            assert(s.completionProvider.isEmpty)
            assert(s.hoverProvider.isEmpty)
            assert(s.signatureHelpProvider.isEmpty)
            assert(s.declarationProvider.isEmpty)
            assert(s.definitionProvider.isEmpty)
            assert(s.typeDefinitionProvider.isEmpty)
            assert(s.implementationProvider.isEmpty)
            assert(s.referencesProvider.isEmpty)
            assert(s.documentHighlightProvider.isEmpty)
            assert(s.documentSymbolProvider.isEmpty)
            assert(s.codeActionProvider.isEmpty)
            assert(s.codeLensProvider.isEmpty)
            assert(s.documentLinkProvider.isEmpty)
            assert(s.colorProvider.isEmpty)
            assert(s.documentFormattingProvider.isEmpty)
            assert(s.documentRangeFormattingProvider.isEmpty)
            assert(s.documentOnTypeFormattingProvider.isEmpty)
            assert(s.renameProvider.isEmpty)
            assert(s.foldingRangeProvider.isEmpty)
            assert(s.executeCommandProvider.isEmpty)
            assert(s.selectionRangeProvider.isEmpty)
            assert(s.linkedEditingRangeProvider.isEmpty)
            assert(s.callHierarchyProvider.isEmpty)
            assert(s.semanticTokensProvider.isEmpty)
            assert(s.monikerProvider.isEmpty)
            assert(s.typeHierarchyProvider.isEmpty)
            assert(s.inlineValueProvider.isEmpty)
            assert(s.inlayHintProvider.isEmpty)
            assert(s.diagnosticProvider.isEmpty)
            assert(s.workspaceSymbolProvider.isEmpty)
            assert(s.notebookDocumentSync.isEmpty)
            assert(s.workspace.isEmpty)
        }
    }

    "Schema[Server.Server] round-trip empty" in {
        val s = LspCapabilities.Server.empty
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] round-trip with populated fields" in {
        val s = LspCapabilities.Server.empty.copy(
            positionEncoding = Present(LspHandler.PositionEncodingKind.UTF16),
            hoverProvider = Present(LspHandler.BooleanOr.Bool(true)),
            completionProvider = Present(LspHandler.CompletionOptions(
                triggerCharacters = Chunk(".", ":"),
                resolveProvider = Present(true)
            )),
            renameProvider = Present(LspHandler.BooleanOr.Options(
                LspHandler.RenameOptions(prepareProvider = Present(true))
            ))
        )
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] BooleanOr.Bool(true) round-trips for hoverProvider" in {
        val s = LspCapabilities.Server.empty.copy(
            hoverProvider = Present(LspHandler.BooleanOr.Bool(true))
        )
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] BooleanOr.Bool(false) round-trips for hoverProvider" in {
        val s = LspCapabilities.Server.empty.copy(
            hoverProvider = Present(LspHandler.BooleanOr.Bool(false))
        )
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] BooleanOr.Options round-trips for hoverProvider" in {
        val opts = LspHandler.HoverOptions(workDoneProgress = Present(true))
        val s = LspCapabilities.Server.empty.copy(
            hoverProvider = Present(LspHandler.BooleanOr.Options(opts))
        )
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] with workspace field" in {
        val ws = LspCapabilities.Server.WorkspaceServerCapabilities(
            workspaceFolders = Present(
                LspCapabilities.Server.WorkspaceFoldersServerCapabilities(supported = Present(true))
            )
        )
        val s = LspCapabilities.Server.empty.copy(workspace = Present(ws))
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] with diagnosticProvider" in {
        val diag = LspCapabilities.Server.DiagnosticOptions(
            interFileDependencies = true,
            workspaceDiagnostics = false
        )
        val s = LspCapabilities.Server.empty.copy(diagnosticProvider = Present(diag))
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] textDocumentSync with Kind" in {
        val s = LspCapabilities.Server.empty.copy(
            textDocumentSync = Present(LspHandler.TextDocumentSyncValue.Kind(LspHandler.TextDocumentSyncKind.Incremental))
        )
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] textDocumentSync with Options" in {
        val s = LspCapabilities.Server.empty.copy(
            textDocumentSync = Present(LspHandler.TextDocumentSyncValue.Options(
                LspHandler.TextDocumentSyncOptions(openClose = Present(true))
            ))
        )
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] with notebookDocumentSync" in {
        val notebookSync = LspCapabilities.Server.NotebookDocumentSyncOptions(
            notebookSelector = Chunk(
                LspCapabilities.Server.NotebookSelector(
                    cells = Chunk(LspCapabilities.Server.CellSelectorItem("scala"))
                )
            )
        )
        val s = LspCapabilities.Server.empty.copy(notebookDocumentSync = Present(notebookSync))
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] with fileOperations" in {
        val fileOps = LspCapabilities.Server.FileOperationsServerCapabilities(
            didCreate = Present(LspHandler.FileOperationRegistrationOptions(filters = Chunk.empty))
        )
        val ws = LspCapabilities.Server.WorkspaceServerCapabilities(fileOperations = Present(fileOps))
        val s  = LspCapabilities.Server.empty.copy(workspace = Present(ws))
        assert(roundtrip(s) == s)
    }

    "Schema[Server.Server] with semanticTokensProvider" in {
        val stOpts = LspHandler.SemanticTokensOptions(
            legend = LspHandler.SemanticTokensLegend(
                tokenTypes = Chunk(LspHandler.SemanticTokenTypes("type")),
                tokenModifiers = Chunk(LspHandler.SemanticTokenModifiers("readonly"))
            ),
            full = Present(LspHandler.BooleanOr.Bool(true))
        )
        val s = LspCapabilities.Server.empty.copy(semanticTokensProvider = Present(stOpts))
        assert(roundtrip(s) == s)
    }

    // =========================================================================
    // Client capabilities
    // =========================================================================

    "Client.empty" - {
        "all group fields Absent" in {
            val c = LspCapabilities.Client.empty
            assert(c.workspace.isEmpty)
            assert(c.textDocument.isEmpty)
            assert(c.notebookDocument.isEmpty)
            assert(c.window.isEmpty)
            assert(c.general.isEmpty)
        }
    }

    "Schema[Client.Client] round-trip empty" in {
        val c = LspCapabilities.Client.empty
        assert(roundtrip(c) == c)
    }

    "Schema[Client.Client] round-trip with workspace capabilities" in {
        val c = LspCapabilities.Client.empty.copy(
            workspace = Present(LspCapabilities.Client.WorkspaceClientCapabilities(
                applyEdit = Present(true),
                workspaceFolders = Present(true),
                configuration = Present(true)
            ))
        )
        assert(roundtrip(c) == c)
    }

    "Schema[Client.Client] round-trip with general positionEncodings" in {
        val c = LspCapabilities.Client.empty.copy(
            general = Present(LspCapabilities.Client.GeneralClientCapabilities(
                positionEncodings = Chunk(LspHandler.PositionEncodingKind.UTF16, LspHandler.PositionEncodingKind.UTF8)
            ))
        )
        assert(roundtrip(c) == c)
    }

    "Schema[Client.Client] round-trip with window capabilities" in {
        val c = LspCapabilities.Client.empty.copy(
            window = Present(LspCapabilities.Client.WindowClientCapabilities(
                workDoneProgress = Present(true),
                showDocument = Present(LspCapabilities.Client.ShowDocumentClientCapabilities(support = true))
            ))
        )
        assert(roundtrip(c) == c)
    }

    "Schema[Client.Client] round-trip with textDocument completion" in {
        val c = LspCapabilities.Client.empty.copy(
            textDocument = Present(LspCapabilities.Client.TextDocumentClientCapabilities(
                completion = Present(LspCapabilities.Client.CompletionClientCapabilities(
                    dynamicRegistration = Present(true),
                    contextSupport = Present(true)
                ))
            ))
        )
        assert(roundtrip(c) == c)
    }

    "Schema[Client.Client] round-trip with notebookDocument" in {
        val c = LspCapabilities.Client.empty.copy(
            notebookDocument = Present(LspCapabilities.Client.NotebookDocumentClientCapabilities(
                synchronization = Present(LspCapabilities.Client.NotebookDocumentSyncClientCapabilities(
                    dynamicRegistration = Present(true),
                    executionSummarySupport = Present(true)
                ))
            ))
        )
        assert(roundtrip(c) == c)
    }

    "Schema[Client.Client] with semanticTokens client capabilities" in {
        val c = LspCapabilities.Client.empty.copy(
            textDocument = Present(LspCapabilities.Client.TextDocumentClientCapabilities(
                semanticTokens = Present(LspCapabilities.Client.SemanticTokensClientCapabilities(
                    requests = LspCapabilities.Client.SemanticTokensRequestsCapabilities(
                        range = Present(true),
                        full = Present(true)
                    ),
                    tokenTypes = Chunk(LspHandler.SemanticTokenTypes("keyword")),
                    tokenModifiers = Chunk(LspHandler.SemanticTokenModifiers("declaration")),
                    formats = Chunk("relative"),
                    multilineTokenSupport = Present(true)
                ))
            ))
        )
        assert(roundtrip(c) == c)
    }

    "Schema[Client.Client] with codeAction capabilities" in {
        val c = LspCapabilities.Client.empty.copy(
            textDocument = Present(LspCapabilities.Client.TextDocumentClientCapabilities(
                codeAction = Present(LspCapabilities.Client.CodeActionClientCapabilities(
                    dynamicRegistration = Present(true),
                    isPreferredSupport = Present(true),
                    disabledSupport = Present(true),
                    dataSupport = Present(true),
                    codeActionLiteralSupport = Present(
                        LspCapabilities.Client.CodeActionLiteralSupportOptions(
                            codeActionKind = LspCapabilities.Client.CodeActionKindOptions(
                                valueSet = Chunk(LspHandler.CodeActionKind("quickfix"))
                            )
                        )
                    )
                ))
            ))
        )
        assert(roundtrip(c) == c)
    }

    // =========================================================================
    // LspCapabilities.Name enum
    // =========================================================================

    "Name Schema individual cases" - {
        "Completion encodes to completion" in {
            assert(Json.encode(LspCapabilities.Name.Completion) == """"completion"""")
        }
        "Hover encodes to hover" in {
            assert(Json.encode(LspCapabilities.Name.Hover) == """"hover"""")
        }
        "SignatureHelp encodes to signatureHelp" in {
            assert(Json.encode(LspCapabilities.Name.SignatureHelp) == """"signatureHelp"""")
        }
        "Declaration encodes to declaration" in {
            assert(Json.encode(LspCapabilities.Name.Declaration) == """"declaration"""")
        }
        "Definition encodes to definition" in {
            assert(Json.encode(LspCapabilities.Name.Definition) == """"definition"""")
        }
        "TypeDefinition encodes to typeDefinition" in {
            assert(Json.encode(LspCapabilities.Name.TypeDefinition) == """"typeDefinition"""")
        }
        "Implementation encodes to implementation" in {
            assert(Json.encode(LspCapabilities.Name.Implementation) == """"implementation"""")
        }
        "References encodes to references" in {
            assert(Json.encode(LspCapabilities.Name.References) == """"references"""")
        }
        "DocumentHighlight encodes to documentHighlight" in {
            assert(Json.encode(LspCapabilities.Name.DocumentHighlight) == """"documentHighlight"""")
        }
        "DocumentSymbol encodes to documentSymbol" in {
            assert(Json.encode(LspCapabilities.Name.DocumentSymbol) == """"documentSymbol"""")
        }
        "CodeAction encodes to codeAction" in {
            assert(Json.encode(LspCapabilities.Name.CodeAction) == """"codeAction"""")
        }
        "CodeLens encodes to codeLens" in {
            assert(Json.encode(LspCapabilities.Name.CodeLens) == """"codeLens"""")
        }
        "DocumentLink encodes to documentLink" in {
            assert(Json.encode(LspCapabilities.Name.DocumentLink) == """"documentLink"""")
        }
        "DocumentColor encodes to colorProvider" in {
            assert(Json.encode(LspCapabilities.Name.DocumentColor) == """"colorProvider"""")
        }
        "Formatting encodes to documentFormattingProvider" in {
            assert(Json.encode(LspCapabilities.Name.Formatting) == """"documentFormattingProvider"""")
        }
        "RangeFormatting encodes to documentRangeFormattingProvider" in {
            assert(Json.encode(LspCapabilities.Name.RangeFormatting) == """"documentRangeFormattingProvider"""")
        }
        "OnTypeFormatting encodes to documentOnTypeFormattingProvider" in {
            assert(Json.encode(LspCapabilities.Name.OnTypeFormatting) == """"documentOnTypeFormattingProvider"""")
        }
        "Rename encodes to rename" in {
            assert(Json.encode(LspCapabilities.Name.Rename) == """"rename"""")
        }
        "FoldingRange encodes to foldingRange" in {
            assert(Json.encode(LspCapabilities.Name.FoldingRange) == """"foldingRange"""")
        }
        "SelectionRange encodes to selectionRange" in {
            assert(Json.encode(LspCapabilities.Name.SelectionRange) == """"selectionRange"""")
        }
        "CallHierarchy encodes to callHierarchy" in {
            assert(Json.encode(LspCapabilities.Name.CallHierarchy) == """"callHierarchy"""")
        }
        "TypeHierarchy encodes to typeHierarchy" in {
            assert(Json.encode(LspCapabilities.Name.TypeHierarchy) == """"typeHierarchy"""")
        }
        "SemanticTokens encodes to semanticTokens" in {
            assert(Json.encode(LspCapabilities.Name.SemanticTokens) == """"semanticTokens"""")
        }
        "Moniker encodes to moniker" in {
            assert(Json.encode(LspCapabilities.Name.Moniker) == """"moniker"""")
        }
        "LinkedEditingRange encodes to linkedEditingRange" in {
            assert(Json.encode(LspCapabilities.Name.LinkedEditingRange) == """"linkedEditingRange"""")
        }
        "InlayHint encodes to inlayHint" in {
            assert(Json.encode(LspCapabilities.Name.InlayHint) == """"inlayHint"""")
        }
        "InlineValue encodes to inlineValue" in {
            assert(Json.encode(LspCapabilities.Name.InlineValue) == """"inlineValue"""")
        }
        "Diagnostic encodes to diagnostic" in {
            assert(Json.encode(LspCapabilities.Name.Diagnostic) == """"diagnostic"""")
        }
        "NotebookDocumentSync encodes to notebookDocumentSync" in {
            assert(Json.encode(LspCapabilities.Name.NotebookDocumentSync) == """"notebookDocumentSync"""")
        }
        "ExecuteCommand encodes to executeCommand" in {
            assert(Json.encode(LspCapabilities.Name.ExecuteCommand) == """"executeCommand"""")
        }
        "WorkspaceSymbol encodes to workspaceSymbol" in {
            assert(Json.encode(LspCapabilities.Name.WorkspaceSymbol) == """"workspaceSymbol"""")
        }
        "WorkspaceFolders encodes to workspaceFolders" in {
            assert(Json.encode(LspCapabilities.Name.WorkspaceFolders) == """"workspaceFolders"""")
        }
        "FileOperations encodes to fileOperations" in {
            assert(Json.encode(LspCapabilities.Name.FileOperations) == """"fileOperations"""")
        }
    }

    "Name Schema covers all 33 cases round-trip" in {
        val allCases = LspCapabilities.Name.values.toSeq
        assert(allCases.size == 33)
        val allRoundTrip = allCases.forall(n => roundtrip(n) == n)
        assert(allRoundTrip)
    }

    "Name Schema decodes case-insensitively fails on wrong string" in {
        val result = Json.decode[LspCapabilities.Name](""""COMPLETION"""")
        assert(result.isFailure)
    }

    // =========================================================================
    // BooleanOr / StringOr at capability use sites
    // =========================================================================

    "BooleanOr aliases" - {
        "LspCapabilities.BooleanOr is same as LspHandler.BooleanOr" in {
            val v: LspCapabilities.BooleanOr[LspHandler.HoverOptions] =
                LspHandler.BooleanOr.Bool(true)
            assert(v == LspHandler.BooleanOr.Bool(true))
        }
        "StringOr alias works at call site" in {
            val v: LspCapabilities.StringOr[LspHandler.FoldingRangeOptions] =
                LspHandler.StringOr.Str("custom")
            assert(v == LspHandler.StringOr.Str("custom"))
        }
    }

    // =========================================================================
    // Server nested records
    // =========================================================================

    "Server nested records" - {
        "WorkspaceColorProviderOptions round-trips" in {
            val opts = LspCapabilities.Server.WorkspaceColorProviderOptions(workDoneProgress = Present(true))
            assert(roundtrip(opts) == opts)
        }
        "DocumentFormattingOptions round-trips" in {
            val opts = LspCapabilities.Server.DocumentFormattingOptions(workDoneProgress = Present(false))
            assert(roundtrip(opts) == opts)
        }
        "DocumentRangeFormattingOptions round-trips" in {
            val opts = LspCapabilities.Server.DocumentRangeFormattingOptions(workDoneProgress = Present(true))
            assert(roundtrip(opts) == opts)
        }
        "DiagnosticOptions round-trips" in {
            val opts = LspCapabilities.Server.DiagnosticOptions(
                identifier = Present("myDiagnostics"),
                interFileDependencies = true,
                workspaceDiagnostics = true
            )
            assert(roundtrip(opts) == opts)
        }
        "WorkspaceServerCapabilities round-trips" in {
            val ws = LspCapabilities.Server.WorkspaceServerCapabilities(
                workspaceFolders = Present(
                    LspCapabilities.Server.WorkspaceFoldersServerCapabilities(
                        supported = Present(true),
                        changeNotifications = Present(true)
                    )
                )
            )
            assert(roundtrip(ws) == ws)
        }
        "FileOperationsServerCapabilities round-trips" in {
            val fileOps = LspCapabilities.Server.FileOperationsServerCapabilities(
                didCreate = Present(LspHandler.FileOperationRegistrationOptions(filters = Chunk.empty)),
                willDelete = Present(LspHandler.FileOperationRegistrationOptions(filters = Chunk.empty))
            )
            assert(roundtrip(fileOps) == fileOps)
        }
    }

    // =========================================================================
    // Client nested records
    // =========================================================================

    "Client nested records" - {
        "WorkspaceEditClientCapabilities round-trips" in {
            val caps = LspCapabilities.Client.WorkspaceEditClientCapabilities(
                documentChanges = Present(true),
                resourceOperations = Chunk("create", "rename", "delete"),
                normalizesLineEndings = Present(true)
            )
            assert(roundtrip(caps) == caps)
        }
        "SemanticTokensClientCapabilities round-trips" in {
            val caps = LspCapabilities.Client.SemanticTokensClientCapabilities(
                requests = LspCapabilities.Client.SemanticTokensRequestsCapabilities(
                    range = Present(true),
                    full = Present(false)
                ),
                tokenTypes = Chunk(LspHandler.SemanticTokenTypes("class")),
                tokenModifiers = Chunk(LspHandler.SemanticTokenModifiers("static")),
                formats = Chunk("relative"),
                overlappingTokenSupport = Present(false),
                augmentsSyntaxTokens = Present(true)
            )
            assert(roundtrip(caps) == caps)
        }
        "FoldingRangeClientCapabilities round-trips" in {
            val caps = LspCapabilities.Client.FoldingRangeClientCapabilities(
                dynamicRegistration = Present(true),
                rangeLimit = Present(5000),
                lineFoldingOnly = Present(false),
                foldingRangeKind = Present(
                    LspCapabilities.Client.FoldingRangeKindOptions(
                        valueSet = Chunk(LspHandler.FoldingRangeKind("comment"))
                    )
                )
            )
            assert(roundtrip(caps) == caps)
        }
        "StaleRequestSupportOptions round-trips" in {
            val opts = LspCapabilities.Client.StaleRequestSupportOptions(
                cancel = true,
                retryOnContentModified = Chunk("textDocument/completion")
            )
            assert(roundtrip(opts) == opts)
        }
        "PublishDiagnosticsClientCapabilities round-trips" in {
            val caps = LspCapabilities.Client.PublishDiagnosticsClientCapabilities(
                relatedInformation = Present(true),
                tagSupport = Present(LspCapabilities.Client.DiagnosticTagSupportOptions(
                    valueSet = Chunk(LspHandler.DiagnosticTag.Unnecessary)
                )),
                versionSupport = Present(true)
            )
            assert(roundtrip(caps) == caps)
        }
    }

end LspCapabilitiesTest
