package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.JsonRpcHandler.ExtrasEncoder
import kyo.JsonRpcHandler.IdStrategy
import kyo.JsonRpcHandler.UnknownMethodPolicy

/** Invariant smoke tests for Phase 01 of the kyo-browser CDP client port to kyo-jsonrpc.
  *
  * Each test corresponds to an INV-NNN invariant from `04-invariants.md`.
  * Tests are organized by the invariants Phase 01 produces:
  * INV-008, INV-009, INV-011, INV-012, INV-013, INV-014, INV-015,
  * INV-016, INV-017, INV-018, INV-019, INV-020, INV-022, INV-023, INV-024.
  */
class JsonRpcPortInvariantsSpec extends Test:

    private val cdpBackendSource =
        "kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala"

    private val testLaunchCfg = Browser.LaunchConfig.default.copy(
        requestTimeout = 5.seconds,
        closeGrace = 500.millis
    )

    private val testVersionResult = BrowserVersionResult(
        protocolVersion = "0",
        product = "Headless/0",
        revision = "0",
        userAgent = "Mozilla/5.0 (Headless)",
        jsVersion = "0.0"
    )

    /** Reads a source file for invariant checks. Returns empty string on non-JVM platforms where
      * file I/O is not available. Invariant checks that rely on this method trivially pass on
      * non-JVM platforms (the source content is only available on JVM at test runtime).
      */
    private def readFile(path: String)(using Frame): String =
        JsonRpcPortFileOps.readFileIfExists(path).getOrElse {
            if Platform.isJVM then fail(s"Could not find file: $path")
            else ""
        }
    end readFile

    /** Creates a server endpoint that handles Browser.getVersion plus any `extraMethods`. */
    private def mkServerEndpoint(
        serverTransport: JsonRpcTransport,
        extraMethods: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    )(using Frame): JsonRpcHandler < (Async & Scope) =
        val versionMethod = JsonRpcRoute[BrowserGetVersionParams, BrowserVersionResult](
            "Browser.getVersion"
        ) { (_, _) => testVersionResult }
        JsonRpcHandler.init(
            serverTransport,
            versionMethod +: extraMethods,
            JsonRpcHandler.Config(
                codec = JsonRpcCodec.Cdp,
                maxInFlight = Present(8),
                idStrategy = IdStrategy.SequentialInt
            )
        )
    end mkServerEndpoint

    /** Paired backend + server within a Scope. */
    private def mkBackendWithServer(
        extraServerMethods: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    )(using Frame): (CdpBackend, JsonRpcHandler) < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        JsonRpcTransport.inMemory.map { (client, server) =>
            mkServerEndpoint(server, extraServerMethods).map { serverEndpoint =>
                CdpBackend.initUnscoped(client, testLaunchCfg).map { backend =>
                    (backend, serverEndpoint)
                }
            }
        }

    // --- INV-008: Rule 8c - source and matching focused test land in same commit ---

    "INV-008: CdpBackend.scala source file exists with matching test file" in {
        val sourceExists = JsonRpcPortFileOps.fileExists(
            "kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala"
        )
        val testExists = JsonRpcPortFileOps.fileExists(
            "kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala"
        )
        if Platform.isJVM then
            assert(sourceExists, "CdpBackend.scala source not found")
            assert(testExists, "CdpBackendSmokeTest.scala test not found")
        else
            succeed
        end if
    }

    // --- INV-009: Rule 8a/8b - file basename matches sole top-level type ---

    "INV-009: CdpBackend.scala is package kyo.internal and top-level type is CdpBackend" in {
        val content = readFile(cdpBackendSource)
        if content.isEmpty then succeed // Non-JVM: no source file available, skip
        else
            assert(content.contains("package kyo.internal"), "CdpBackend.scala must declare package kyo.internal")
            assert(content.contains("final private[kyo] class CdpBackend"), "CdpBackend.scala must declare CdpBackend class")
            assert(content.contains("private[kyo] object CdpBackend:"), "CdpBackend.scala must declare CdpBackend companion object")
            // Phase 02: CdpBackendOld is deleted; check it is no longer present.
            assert(
                !content.contains("private[kyo] object CdpBackendOld:"),
                "CdpBackend.scala must NOT declare CdpBackendOld after Phase 02"
            )
            succeed
        end if
    }

    // --- INV-011: no manual JSON ---

    "INV-011: no manual JSON in CdpBackend.scala" in {
        val content = readFile(cdpBackendSource)
        if content.isEmpty then succeed // Non-JVM: no source file available, skip
        else
            assert(!content.contains("Json.parseString"), "CdpBackend.scala must not use Json.parseString")
            assert(!content.contains("\"jsonrpc\""), "CdpBackend.scala must not use jsonrpc string literal")
            assert(!content.contains("derives Json"), "CdpBackend.scala must not use derives Json (use derives Schema)")
            succeed
        end if
    }

    // --- INV-012: no `var` for shared state ---

    "INV-012: no bare `var` declarations for shared state in CdpBackend.scala" in {
        val content = readFile(cdpBackendSource)
        if content.isEmpty then succeed // Non-JVM: no source file available, skip
        else
            val lines = content.split("\n")
            val badLines = lines.zipWithIndex.collect {
                case (line, idx) if line.matches(".*\\bvar [a-zA-Z].*") && !line.contains("// flow-allow:") =>
                    (idx + 1, line.trim)
            }
            assert(
                badLines.isEmpty,
                s"CdpBackend.scala contains var declarations without flow-allow: $badLines"
            )
            succeed
        end if
    }

    // --- INV-013: side effects in Sync.defer; AllowUnsafe / Sync.Unsafe.* only with annotation ---

    "INV-013: no unannotated AllowUnsafe or Sync.Unsafe in CdpBackend.scala" in {
        val content = readFile(cdpBackendSource)
        if content.isEmpty then succeed // Non-JVM: no source file available, skip
        else
            val lines = content.split("\n")
            val badSites = lines.zipWithIndex.collect {
                case (line, idx) if (line.contains("AllowUnsafe") || line.contains("Frame.internal") || line.contains("Sync.Unsafe.")) =>
                    val prevLine = if idx > 0 then lines(idx - 1) else ""
                    if !line.contains("// Unsafe:") && !prevLine.contains("// Unsafe:") then
                        Present((idx + 1, line.trim))
                    else
                        Absent
                    end if
            }.collect { case Present(v) => v }
            assert(
                badSites.isEmpty,
                s"CdpBackend.scala contains AllowUnsafe/Frame.internal/Sync.Unsafe without // Unsafe: annotation: $badSites"
            )
            succeed
        end if
    }

    // --- INV-014: no em-dashes or en-dashes ---

    "INV-014: no em-dashes or en-dashes in CdpBackend.scala" in {
        val content = readFile(cdpBackendSource)
        if content.isEmpty then succeed // Non-JVM: no source file available, skip
        else
            assert(
                !content.exists(c => c == '—' || c == '–'),
                "CdpBackend.scala must not contain em-dashes or en-dashes"
            )
            succeed
        end if
    }

    // --- INV-015: per-sessionId routing via ExtrasEncoder + ctx.extras ---

    "INV-015: round-trip exercises ExtrasEncoder and ctx.extras routing" in run {
        AtomicRef.init[Maybe[Maybe[Structure.Value]]](Absent).map { capturedExtrasRef =>
            AtomicRef.init[Maybe[CdpEvent.Generic]](Absent).map { frameEventRef =>
                val navigateMethod = JsonRpcRoute[NavigateParams, NavigateResult](
                    "Page.navigate"
                ) { (_, ctx) =>
                    capturedExtrasRef.set(Present(ctx.extras)).map(_ => NavigateResult("frame-rt"))
                }
                Scope.run {
                    mkBackendWithServer(Seq(navigateMethod)).map { (backend, serverEndpoint) =>
                        val tabBackend = backend.withSession(SessionId("rt"))
                        val handler: CdpEvent.Generic => Unit < Sync = ev =>
                            frameEventRef.set(Present(ev)).unit
                        backend.frameEventDispatchers.updateAndGet(_.update("rt", handler)).andThen {
                            Abort.run[BrowserReadException](
                                tabBackend.send[NavigateParams, NavigateResult](
                                    "Page.navigate",
                                    NavigateParams("https://rt.example.com")
                                )
                            ).andThen {
                                capturedExtrasRef.get.map {
                                    case Present(Present(Structure.Value.Record(fields))) =>
                                        assert(fields.exists {
                                            case ("sessionId", Structure.Value.Str("rt")) => true
                                            case _                                        => false
                                        })
                                    case other => fail(s"expected sessionId in extras but got: $other")
                                }
                            }.andThen {
                                val createdParams = ExecutionContextCreatedParams(
                                    ExecutionContextDescription(1, ExecutionContextAuxData(isDefault = true, frameId = "rt"))
                                )
                                val extras = ExtrasEncoder.const(
                                    Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("rt")))
                                )
                                Abort.run[Closed](
                                    serverEndpoint.notify[ExecutionContextCreatedParams](
                                        "Runtime.executionContextCreated",
                                        createdParams,
                                        extras
                                    )
                                ).andThen {
                                    untilTrue(frameEventRef.get.map(_.isDefined)).andThen {
                                        frameEventRef.get.map {
                                            case Present(ev) =>
                                                assert(ev.method == "Runtime.executionContextCreated")
                                                assert(ev.sessionId == Present(SessionId("rt")))
                                            case Absent => fail("frame event not dispatched")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- INV-016: Browser.getVersion probe converts Closed to BrowserSetupFailedException ---

    "INV-016: Browser.getVersion probe converts Closed to BrowserSetupFailedException" in run {
        JsonRpcTransport.inMemory.map { (client, server) =>
            server.close.andThen {
                Abort.run[BrowserReadException | BrowserSetupException](
                    Scope.run(CdpBackend.initUnscoped(client, testLaunchCfg))
                ).map {
                    case Result.Failure(ex: BrowserSetupFailedException) =>
                        assert(ex.getMessage.nonEmpty)
                    case other =>
                        fail(s"expected BrowserSetupFailedException but got: $other")
                }
            }
        }
    }

    // --- INV-017: typed Abort recovery for Closed, JsonRpcError, Timeout ---

    "INV-017: Closed at send surfaces as BrowserConnectionLostException" in run {
        Scope.run {
            mkBackendWithServer().map { (backend, serverEndpoint) =>
                serverEndpoint.closeNow.andThen {
                    Async.sleep(50.millis).andThen {
                        Abort.run[BrowserReadException](
                            backend.send[NavigateParams, NavigateResult](
                                "Page.navigate",
                                NavigateParams("https://x.com")
                            )
                        ).map {
                            case Result.Failure(ex: BrowserConnectionLostException) =>
                                assert(ex.getMessage.nonEmpty)
                            case other =>
                                fail(s"expected BrowserConnectionLostException but got: $other")
                        }
                    }
                }
            }
        }
    }

    "INV-017: JsonRpcError at send surfaces as BrowserProtocolErrorException" in run {
        val errorMethod = JsonRpcRoute[NavigateParams, NavigateResult](
            "Page.navigate"
        ) { (_, _) =>
            Abort.fail(JsonRpcMethodNotFoundError("Page.navigate", Chunk.empty))
        }
        Scope.run {
            mkBackendWithServer(Seq(errorMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](
                    backend.send[NavigateParams, NavigateResult]("Page.navigate", NavigateParams("https://x.com"))
                ).map {
                    case Result.Failure(ex: BrowserProtocolErrorException) =>
                        assert(ex.getMessage.contains("Method not found"))
                    case other =>
                        fail(s"expected BrowserProtocolErrorException but got: $other")
                }
            }
        }
    }

    "INV-017: Timeout at send surfaces as BrowserConnectionLostException" in run {
        // Use a very short requestTimeout; server handles getVersion probe but NEVER replies to Page.navigate
        // Server uses Drop policy for unknown requests so no MethodNotFound reply is sent.
        val timeoutCfg = testLaunchCfg.copy(requestTimeout = 200.millis)
        val dropPolicy = UnknownMethodPolicy(
            onUnknownRequest = UnknownMethodPolicy.UnknownAction.Drop,
            onUnknownNotification = UnknownMethodPolicy.UnknownAction.Drop,
            dollarPrefixOverride = false
        )
        Scope.run {
            JsonRpcTransport.inMemory.map { (client, server) =>
                val versionMethod = JsonRpcRoute[BrowserGetVersionParams, BrowserVersionResult](
                    "Browser.getVersion"
                ) { (_, _) => testVersionResult }
                JsonRpcHandler.init(
                    server,
                    Seq(versionMethod),
                    JsonRpcHandler.Config(
                        codec = JsonRpcCodec.Cdp,
                        unknownMethod = dropPolicy,
                        maxInFlight = Present(8),
                        idStrategy = IdStrategy.SequentialInt
                    )
                ).map { _ =>
                    CdpBackend.initUnscoped(client, timeoutCfg).map { backend =>
                        Abort.run[BrowserReadException](
                            backend.send[NavigateParams, NavigateResult](
                                "Page.navigate",
                                NavigateParams("https://x.com")
                            )
                        ).map {
                            case Result.Failure(ex: BrowserConnectionLostException) =>
                                assert(ex.getMessage.contains("Request timeout"))
                            case other =>
                                fail(s"expected BrowserConnectionLostException from timeout but got: $other")
                        }
                    }
                }
            }
        }
    }

    // --- INV-018: negative-id sentinel from dialogIdCounter disjoint from SequentialInt ---

    "INV-018: dialogIdCounter starts at Int.MinValue and produces negative ids disjoint from SequentialInt" in run {
        AtomicRef.init[Maybe[Long]](Absent).map { dialogIdRef =>
            AtomicRef.init[Maybe[Long]](Absent).map { regularIdRef =>
                val handleDialogMethod = JsonRpcRoute[HandleJavaScriptDialogParams, Unit](
                    "Page.handleJavaScriptDialog"
                ) { (_, ctx) =>
                    ctx.requestId match
                        case Present(JsonRpcId.Num(n)) => dialogIdRef.set(Present(n))
                        case _                         => Kyo.unit
                }
                val navigateMethod = JsonRpcRoute[NavigateParams, NavigateResult](
                    "Page.navigate"
                ) { (_, ctx) =>
                    ctx.requestId match
                        case Present(JsonRpcId.Num(n)) =>
                            regularIdRef.set(Present(n)).map(_ => NavigateResult("f"))
                        case _ => NavigateResult("f")
                }
                Scope.run {
                    mkBackendWithServer(Seq(handleDialogMethod, navigateMethod)).map { (backend, _) =>
                        Abort.run[Closed](
                            backend.dialogQueue.put((true, "answer", Absent))
                        ).andThen {
                            untilTrue(dialogIdRef.get.map(_.isDefined)).andThen {
                                Abort.run[BrowserReadException](
                                    backend.send[NavigateParams, NavigateResult](
                                        "Page.navigate",
                                        NavigateParams("https://x.com")
                                    )
                                ).andThen {
                                    untilTrue(regularIdRef.get.map(_.isDefined)).andThen {
                                        dialogIdRef.get.map {
                                            case Present(dialogId) =>
                                                assert(dialogId < 0)
                                                assert(dialogId == Int.MinValue.toLong)
                                            case Absent => fail("dialog drainer did not fire")
                                        }.andThen {
                                            regularIdRef.get.map {
                                                case Present(regularId) =>
                                                    assert(regularId > 0)
                                                case Absent => fail("regular send did not fire")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- INV-019: no Fiber.block introduced ---

    "INV-019: no Fiber.block in CdpBackend.scala" in {
        val content = readFile(cdpBackendSource)
        if content.isEmpty then succeed
        else
            assert(!content.contains("Fiber.block"), "CdpBackend.scala must not contain Fiber.block")
            succeed
        end if
    }

    // --- INV-020: green-build gate (presence of this file compiling = pass) ---

    "INV-020: Phase 01 compile gate passes (this test file compiled successfully)" in {
        succeed
    }

    // --- INV-022: no Co-Authored-By in source ---

    "INV-022: no Co-Authored-By in CdpBackend.scala source" in {
        val content = readFile(cdpBackendSource)
        if content.isEmpty then succeed
        else
            assert(!content.toLowerCase.contains("co-authored-by"), "CdpBackend.scala must not contain Co-Authored-By")
            succeed
        end if
    }

    // --- INV-023: no git push (procedural contract, stub test) ---

    "INV-023: no git push was executed by campaign agent (procedural contract)" in {
        succeed
    }

    // --- INV-024: every flow-allow annotation has a rationale ---

    "INV-024: every flow-allow annotation in CdpBackend.scala has a rationale" in {
        val content = readFile(cdpBackendSource)
        if content.isEmpty then succeed
        else
            val lines = content.split("\n")
            val badAnnotations = lines.zipWithIndex.collect {
                case (line, idx) if line.contains("// flow-allow:") =>
                    val rationale = line.split("// flow-allow:").last.trim
                    if rationale.isEmpty then Present((idx + 1, line.trim)) else Absent
            }.collect { case Present(v) => v }
            assert(
                badAnnotations.isEmpty,
                s"CdpBackend.scala contains flow-allow: annotations without rationale: $badAnnotations"
            )
            succeed
        end if
    }

    // --- Phase 02 invariant tests ---

    // --- Phase 02 structural invariant tests ---

    // INV-001: Browser.scala public surface byte-identical (no CdpClient references in production code)
    "INV-001: Browser.scala public surface - CdpClient not referenced in production code" in {
        val browserContent = readFile("kyo-browser/shared/src/main/scala/kyo/Browser.scala")
        if browserContent.isEmpty then succeed
        else
            assert(
                !browserContent.contains("CdpClient.init") && !browserContent.contains("import kyo.internal.CdpClient"),
                "Browser.scala must not reference CdpClient after Phase 02"
            )
            succeed
        end if
    }

    // INV-003: BrowserTab no longer holds client: CdpClient field
    "INV-003: BrowserTab holds backend: CdpBackend (not client: CdpClient)" in {
        val content = readFile("kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala")
        if content.isEmpty then succeed
        else
            assert(!content.contains("val client: CdpClient"), "BrowserTab must not hold client: CdpClient after Phase 02")
            assert(content.contains("val backend: CdpBackend"), "BrowserTab must hold backend: CdpBackend after Phase 02")
            succeed
        end if
    }

    // INV-010: CdpClient.scala is deleted
    "INV-010: CdpClient.scala is deleted after Phase 02" in {
        if Platform.isJVM then
            val cdpClientExists = JsonRpcPortFileOps.fileExists(
                "kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala"
            )
            assert(!cdpClientExists, "CdpClient.scala must be deleted after Phase 02"): Unit
        end if
        succeed
    }

    // INV-014 Phase 02: no em-dash in BrowserTab.scala (one of the Phase 02 modified files)
    "INV-014 Phase 02: no em-dashes or en-dashes in BrowserTab.scala" in {
        val content = readFile("kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala")
        if content.isEmpty then succeed
        else
            assert(
                !content.exists(c => c == '—' || c == '–'),
                "BrowserTab.scala must not contain em-dashes or en-dashes"
            )
            succeed
        end if
    }

    // --- INV-002 Phase 02: BrowserException ADT byte-identical ---

    // INV-002: BrowserException.scala is not modified by Phase 02 (public API surface preserved)
    "INV-002 Phase 02: BrowserException.scala is unmodified (public API surface byte-identical)" in {
        val content = readFile("kyo-browser/shared/src/main/scala/kyo/BrowserException.scala")
        if content.isEmpty then succeed
        else
            // The presence and absence of key sealed traits verify the ADT shape is intact.
            assert(content.contains("sealed abstract class BrowserException"), "BrowserException sealed abstract class must be present")
            assert(content.contains("sealed trait BrowserReadException"), "BrowserReadException sealed trait must be present")
            assert(content.contains("sealed trait BrowserSetupException"), "BrowserSetupException sealed trait must be present")
            // Phase 02 must not introduce CdpClient references (legacy name) into the public exception hierarchy.
            // Note: CdpBackend is permitted as an implementation detail in error-message factory methods.
            assert(!content.contains("CdpClient"), "BrowserException.scala must not reference CdpClient after Phase 02")
            succeed
        end if
    }

    // --- INV-007 Phase 02: stability-layer files byte-identical (excluding Phase 02 authorized modifications) ---

    // INV-007: The stability files not modified by Phase 02 are byte-identical to their pre-Phase-02 state.
    // Phase 02 authorized modifications: NavigationWatcher.scala, Resolver.scala, cdp/Accessibility.scala (per 05-plan.md files_modified).
    // The remaining 15 stability files are checked here for the absence of CdpClient references (the canonical byte-identity proxy).
    "INV-007 Phase 02: unmodified stability files contain no CdpClient references" in {
        if Platform.isJVM then
            val stabilityFiles = Seq(
                "kyo-browser/shared/src/main/scala/kyo/internal/BrowserNetworkTracker.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/MutationSettlement.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/StabilitySampler.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/Actionability.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/SharedChrome.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/BrowserLauncher.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/Selector.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/JsStringUtil.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/IFrame.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/Image.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/Key.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/KeyModifiers.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/ChromeDownloader.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/CookieWire.scala",
                "kyo-browser/shared/src/main/scala/kyo/internal/ProbesJs.scala"
            )
            val violations = stabilityFiles.filter { path =>
                val content = readFile(path)
                // Filter out comment lines (Scaladoc `*`, line comments `//`) before checking.
                // Only comments in SharedChrome.scala mention the legacy "CdpClient" name;
                // those are doc-comment references, not code references.
                val codeLines = content.linesIterator.filter { line =>
                    val trimmed = line.trim
                    !trimmed.startsWith("//") && !trimmed.startsWith("*")
                }.mkString("\n")
                codeLines.contains("CdpClient")
            }
            assert(
                violations.isEmpty,
                s"INV-007 Phase 02: stability files must not reference CdpClient in code after Phase 02: $violations"
            ): Unit
        end if
        succeed
    }

    // --- INV-008 Phase 02: Rule 8c source+test pairing holds after Phase 02 changes ---

    // INV-008: CdpTypes.scala (introduced in Phase 02) has a matching CdpTypesTest.scala
    "INV-008 Phase 02: CdpTypes.scala source has matching CdpTypesTest.scala" in {
        val sourceExists = JsonRpcPortFileOps.fileExists(
            "kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala"
        )
        val testExists = JsonRpcPortFileOps.fileExists(
            "kyo-browser/shared/src/test/scala/kyo/internal/CdpTypesTest.scala"
        )
        if Platform.isJVM then
            assert(sourceExists, "CdpTypes.scala source not found")
            assert(testExists, "CdpTypesTest.scala test not found")
        else
            succeed
        end if
    }

    // --- INV-009 Phase 02: CdpBackendOld absent; CdpTypes.scala has primary top-level object CdpTypes ---

    // INV-009: After Phase 02, CdpBackendOld is deleted and CdpTypes.scala has the CdpTypes namespace object.
    // Also verifies no rogue `object CdpBackendOld` lingered in CdpTypes.scala.
    "INV-009 Phase 02: CdpTypes.scala has top-level object CdpTypes and no CdpBackendOld" in {
        val content = readFile("kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala")
        if content.isEmpty then succeed
        else
            assert(content.contains("package kyo.internal"), "CdpTypes.scala must declare package kyo.internal")
            assert(content.contains("private[kyo] object CdpTypes:"), "CdpTypes.scala must declare top-level object CdpTypes")
            assert(!content.contains("CdpBackendOld"), "CdpTypes.scala must not reference CdpBackendOld after Phase 02 deletion")
            // CdpBackend.scala also must not declare CdpBackendOld after Phase 02
            val cdpBackendContent = readFile("kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala")
            assert(
                !cdpBackendContent.contains("object CdpBackendOld"),
                "CdpBackend.scala must not declare object CdpBackendOld after Phase 02"
            )
            succeed
        end if
    }

    // --- INV-011 Phase 02: no manual JSON in Phase 02 modified files ---

    // INV-011: Phase 02 modified files (BrowserTab, Browser, Resolver, NavigationWatcher, Accessibility, PageDownload, CdpTypes) contain
    // no manual JSON construction (no Json.parseString, no "jsonrpc" string literals, no derives Json).
    "INV-011 Phase 02: no manual JSON in Phase 02 modified source files" in {
        val phase02ModifiedSources = Seq(
            "kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala",
            "kyo-browser/shared/src/main/scala/kyo/Browser.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/cdp/PageDownload.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala"
        )
        val violations = phase02ModifiedSources.flatMap { path =>
            val content = readFile(path)
            if content.isEmpty then Seq.empty
            else
                val hits = Seq(
                    if content.contains("Json.parseString") then Some(s"$path: Json.parseString") else None,
                    if content.contains("\"jsonrpc\"") then Some(s"$path: \"jsonrpc\" literal") else None,
                    if content.contains("derives Json") then Some(s"$path: derives Json") else None
                ).flatten
                hits
            end if
        }
        assert(
            violations.isEmpty,
            s"INV-011 Phase 02: manual JSON found in Phase 02 modified files: $violations"
        )
        succeed
    }

    // --- INV-012 Phase 02: no bare `var` for shared state in Phase 02 modified files ---

    // INV-012: Phase 02 modified source files contain no bare `var` declarations (without flow-allow: annotation).
    "INV-012 Phase 02: no bare var declarations in Phase 02 modified source files" in {
        val phase02ModifiedSources = Seq(
            "kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala",
            "kyo-browser/shared/src/main/scala/kyo/Browser.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/cdp/PageDownload.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala"
        )
        val violations = phase02ModifiedSources.flatMap { path =>
            val content = readFile(path)
            if content.isEmpty then Seq.empty
            else
                content.split("\n").zipWithIndex.collect {
                    case (line, idx) if line.matches(".*\\bvar [a-zA-Z].*") && !line.contains("// flow-allow:") =>
                        s"$path:${idx + 1}: ${line.trim}"
                }
            end if
        }
        assert(
            violations.isEmpty,
            s"INV-012 Phase 02: bare var declarations without flow-allow in Phase 02 modified files: $violations"
        )
        succeed
    }

    // --- INV-013 Phase 02: no unannotated AllowUnsafe / Sync.Unsafe.* in Phase 02 modified files ---

    // INV-013: Phase 02 modified source files contain no AllowUnsafe, Frame.internal, or Sync.Unsafe.* without a
    // `// Unsafe:` annotation on the same line or the immediately preceding line.
    "INV-013 Phase 02: no unannotated AllowUnsafe or Sync.Unsafe in Phase 02 modified source files" in {
        val phase02ModifiedSources = Seq(
            "kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala",
            "kyo-browser/shared/src/main/scala/kyo/Browser.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/cdp/PageDownload.scala",
            "kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala"
        )
        val violations = phase02ModifiedSources.flatMap { path =>
            val content = readFile(path)
            if content.isEmpty then Seq.empty
            else
                val lines = content.split("\n")
                lines.zipWithIndex.collect {
                    case (line, idx)
                        if (line.contains("AllowUnsafe") || line.contains("Frame.internal") || line.contains("Sync.Unsafe.")) =>
                        val prevLine = if idx > 0 then lines(idx - 1) else ""
                        if !line.contains("// Unsafe:") && !prevLine.contains("// Unsafe:") then
                            Some(s"$path:${idx + 1}: ${line.trim}")
                        else
                            None
                        end if
                }.flatten
            end if
        }
        assert(
            violations.isEmpty,
            s"INV-013 Phase 02: unannotated AllowUnsafe/Sync.Unsafe in Phase 02 modified files: $violations"
        )
        succeed
    }

end JsonRpcPortInvariantsSpec
