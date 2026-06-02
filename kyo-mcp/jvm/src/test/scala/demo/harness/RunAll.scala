package demo.harness

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

// kyo.discard is `private[kyo]`, so this file (in `demo.harness`) cannot reference it.
// Local helper for the same `evaluate-and-drop-result` pattern.
private inline def drop[A](inline v: => A): Unit =
    val _ = v; ()

/** Custom MCP + LSP client driver that exercises the demos whose protocol features
  * Claude Code's tool surface does not reach: reverse-direction sampling / elicitation,
  * `resources/subscribe` + `notifications/resources/updated`, and LSP `$/progress`.
  *
  * One main entry point: `sbt 'kyo-mcp/Test/runMain demo.harness.RunAll'`. Each scenario
  *
  *   1. spawns the demo JVM as a subprocess via `.mcp-validation/run-demo.sh` using
  *      [[kyo.Command]] so the child is bound to the surrounding [[kyo.Scope]];
  *   2. attaches a kyo-mcp or kyo-lsp client over the subprocess's stdio;
  *   3. drives the test scenario;
  *   4. prints PASS / FAIL and the relevant evidence.
  *
  * The subprocess is killed when the scenario's Scope closes.
  */
object RunAll extends KyoApp:

    run {
        for
            // sbt's forked-test JVM lands cwd inside the module's `jvm/` folder, not the
            // worktree root. Walk up the cwd's ancestors and pick the first one that
            // owns `.mcp-validation/run-demo.sh`.
            cwd <- Path.cwd
            hit <- cwd.ancestors.find(p => (p / ".mcp-validation" / "run-demo.sh").exists)
            worktreeDir = hit.getOrElse(cwd)
            r1 <- runScenario("Confirm", () => confirmScenario(worktreeDir))
            r2 <- runScenario("Summarize", () => summarizeScenario(worktreeDir))
            r3 <- runScenario("LogTail", () => logTailScenario(worktreeDir))
            r4 <- runScenario("LongTask", () => longTaskScenario(worktreeDir))
            r5 <- runScenario("TodoIndexer", () => todoIndexerScenario(worktreeDir))
            results = Chunk(r1, r2, r3, r4, r5)
            _ <- printSummary(results)
        yield ()
        end for
    }

    // --- Scenario framing ---------------------------------------------------------------------

    private case class ScenarioResult(name: String, passed: Boolean, notes: String)

    private def runScenario(
        name: String,
        body: () => ScenarioResult < (Async & Scope & Abort[McpException | LspException | Closed | CommandException])
    )(using Frame): ScenarioResult < Async =
        Console.printLine(s"\n=== $name ===").map { _ =>
            Scope.run(
                Abort.run[McpException | LspException | Closed | CommandException | Timeout](
                    Async.timeout(30.seconds)(body())
                ).map {
                    case Result.Success(r) => r
                    case other             => ScenarioResult(name, passed = false, notes = s"scenario raised: $other")
                }
            ).map { r =>
                Console.printLine(s"  ${if r.passed then "PASS" else "FAIL"} ; ${r.notes}").map(_ => r)
            }
        }

    private def printSummary(results: Chunk[ScenarioResult])(using Frame): Unit < Sync =
        val passed = results.count(_.passed)
        Console.printLine(s"\n=== Summary: $passed/${results.size} scenarios passed ===").map { _ =>
            val failed = results.filterNot(_.passed)
            if failed.isEmpty then ((): Unit): Unit < Sync
            else Kyo.foreachDiscard(failed)(r => Console.printLine(s"  FAILED: ${r.name} ; ${r.notes}"))
        }
    end printSummary

    // --- Subprocess helper --------------------------------------------------------------------

    /** Spawn one of the demo JVMs and return its `JsonRpcTransport` bound to the child's stdio.
      *
      * `Command` registers the spawned process with the current `Scope`; closing the scope
      * tears down the JVM. The transport reuses kyo-jsonrpc's byte-transparent
      * `contentLengthStdio` wire, with the framer arg switching between NDJSON (kyo-mcp) and
      * Content-Length-prefixed (kyo-lsp). `Process.Unsafe.stdoutJava` / `stdinJava` are the
      * documented escape hatches for handing the raw NIO streams to the transport.
      */
    private def spawn(worktreeDir: Path, module: String, fqcn: String, demoArgs: Chunk[String] = Chunk.empty)(using
        Frame
    ): JsonRpcTransport < (Async & Scope & Abort[CommandException]) =
        val runDemoSh = (worktreeDir / ".mcp-validation" / "run-demo.sh").toString
        val cmd       = Command(Chunk(runDemoSh, module, fqcn).concat(demoArgs)*).cwd(worktreeDir).pipeStdin.inheritStderr
        cmd.spawn.map { proc =>
            val (in, out) =
                given AllowUnsafe = AllowUnsafe.embrace.danger
                (proc.unsafe.stdoutJava, proc.unsafe.stdinJava)
            val framer = if module == "kyo-lsp" then JsonRpcFramer.contentLength else JsonRpcFramer.lineDelimited
            JsonRpcTransport.contentLengthStdio(in, out, framer = framer)
        }
    end spawn

    // --- Scenario 1: Confirm (elicitation) ----------------------------------------------------

    case class DestructiveOpArgs(target: String) derives Schema, CanEqual

    private def confirmScenario(worktreeDir: Path)(using
        Frame
    ): ScenarioResult < (Async & Scope & Abort[McpException | LspException | Closed | CommandException]) =
        val callIdx = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val elicitationHandler =
            McpHandler.custom[McpServer.ElicitationRequest]("elicitation/create") { _ =>
                Sync.defer {
                    val idx = callIdx.incrementAndGet()(using AllowUnsafe.embrace.danger)
                    val action = idx match
                        case 1 => McpServer.ElicitationResponse.Action.Accept
                        case 2 => McpServer.ElicitationResponse.Action.Decline
                        case _ => McpServer.ElicitationResponse.Action.Cancel
                    McpServer.ElicitationResponse(action = action)
                }
            }
        val caps = McpCapabilities.Client(elicitation = Present(McpCapabilities.ElicitationCapability()))

        spawn(worktreeDir, "kyo-mcp", "demo.Confirm").map { transport =>
            McpClient.initWith(transport, McpInfo("harness"), caps, elicitationHandler) { client =>
                for
                    r1 <- Abort.run[McpException | Closed](
                        client.callTool[DestructiveOpArgs]("destructive_op", DestructiveOpArgs("doc-1"))
                    )
                    r2 <- Abort.run[McpException | Closed](
                        client.callTool[DestructiveOpArgs]("destructive_op", DestructiveOpArgs("doc-2"))
                    )
                    r3 <- Abort.run[McpException | Closed](
                        client.callTool[DestructiveOpArgs]("destructive_op", DestructiveOpArgs("doc-3"))
                    )
                yield
                    val accepted  = r1.isSuccess
                    val declined  = r2.isFailure
                    val cancelled = r3.isFailure
                    if accepted && declined && cancelled then
                        ScenarioResult(
                            "Confirm",
                            passed = true,
                            notes = "Accept->success, Decline->failure, Cancel->failure (typed -32040 on both)"
                        )
                    else
                        ScenarioResult(
                            "Confirm",
                            passed = false,
                            notes = s"accept=$accepted decline=$declined cancel=$cancelled"
                        )
                    end if
                end for
            }
        }
    end confirmScenario

    // --- Scenario 2: Summarize (sampling) -----------------------------------------------------

    case class SummarizeTextArgs(text: String) derives Schema, CanEqual

    private def summarizeScenario(worktreeDir: Path)(using
        Frame
    ): ScenarioResult < (Async & Scope & Abort[McpException | LspException | Closed | CommandException]) =
        val samplingHandler =
            McpHandler.custom[McpServer.SamplingRequest]("sampling/createMessage") { req =>
                val echo = req.messages.lastOption.map(_.content match
                    case McpServer.SamplingContent.Text(t, _) => t
                    case other                                => other.toString).getOrElse("")
                McpServer.SamplingResponse(
                    role = McpContent.Role.Assistant,
                    content = McpContent.text(s"[harness-mock] $echo"),
                    model = "harness-mock-v1",
                    stopReason = Present(McpServer.SamplingResponse.StopReason.EndTurn)
                )
            }
        val caps = McpCapabilities.Client(sampling = Present(McpCapabilities.SamplingCapability()))

        spawn(worktreeDir, "kyo-mcp", "demo.Summarize").map { transport =>
            McpClient.initWith(transport, McpInfo("harness"), caps, samplingHandler) { client =>
                Abort.run[McpException | Closed](
                    client.callTool[SummarizeTextArgs]("summarize_text", SummarizeTextArgs("hello there"))
                ).map { res =>
                    res match
                        case Result.Success(outcome) =>
                            val text = outcome.content.collect { case t: McpContent.Text => t.text }.mkString
                            val ok   = text.contains("harness-mock") && text.contains("hello there")
                            ScenarioResult(
                                "Summarize",
                                passed = ok,
                                notes = if ok then s"got sampled body: ${text.take(80)}"
                                else s"unexpected body: $text"
                            )
                        case Result.Failure(e) => ScenarioResult("Summarize", passed = false, notes = s"failure: $e")
                        case Result.Panic(t)   => ScenarioResult("Summarize", passed = false, notes = s"panic: $t")
                }
            }
        }
    end summarizeScenario

    // --- Scenario 3: LogTail (subscribe + notifyResourceUpdated) ------------------------------

    case class ResUpdated(uri: String) derives Schema, CanEqual

    private def logTailScenario(worktreeDir: Path)(using
        Frame
    ): ScenarioResult < (Async & Scope & Abort[McpException | LspException | Closed | CommandException]) =
        val updateCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val updatedHandler = McpHandler.custom[ResUpdated]("notifications/resources/updated") { _ =>
            Sync.defer(drop(updateCount.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }

        val logFile = Path("/tmp/mcp-validation/harness-logtail.txt")
        Abort.run[FileException] {
            for
                _ <- logFile.removeExisting.handle(Abort.recover[FileException](_ => ()))
                _ <- logFile.write("line 1\n", createFolders = true)
            yield ()
        }.map { _ =>
            spawn(worktreeDir, "kyo-mcp", "demo.LogTail", Chunk(logFile.toString)).map { transport =>
                val tailUri = McpResourceUri("logtail://current")
                McpClient.initWith(transport, McpInfo("harness"), McpCapabilities.Client(), updatedHandler) { client =>
                    for
                        _ <- client.subscribeResource(tailUri)
                        _ <- Async.sleep(1.second)
                        _ <- logFile.write("line 1\nline 2\n").handle(Abort.recover[FileException](_ => ()))
                        _ <- Async.sleep(1500.millis)
                        c1 = updateCount.get()(using AllowUnsafe.embrace.danger)
                        _ <- logFile.write("line 1\nline 2\nline 3\n").handle(Abort.recover[FileException](_ => ()))
                        _ <- Async.sleep(1500.millis)
                        c2 = updateCount.get()(using AllowUnsafe.embrace.danger)
                        _ <- client.unsubscribeResource(tailUri)
                    yield
                        val ok = c1 >= 1 && c2 > c1
                        ScenarioResult(
                            "LogTail",
                            passed = ok,
                            notes = if ok then s"$c1 updates after first append, $c2 after second"
                            else s"expected >= 1 then > c1; got c1=$c1 c2=$c2"
                        )
                    end for
                }
            }
        }
    end logTailScenario

    // --- Scenario 4: LongTask (forward call) --------------------------------------------------

    case class CountArgs(n: Int, stepMs: Int) derives Schema, CanEqual

    private def longTaskScenario(worktreeDir: Path)(using
        Frame
    ): ScenarioResult < (Async & Scope & Abort[McpException | LspException | Closed | CommandException]) =
        spawn(worktreeDir, "kyo-mcp", "demo.LongTask").map { transport =>
            McpClient.initWith(transport, McpInfo("harness"), McpCapabilities.Client()) { client =>
                Abort.run[McpException | Closed](
                    client.callTool[CountArgs]("count_slowly", CountArgs(n = 3, stepMs = 50))
                ).map { res =>
                    val text = res match
                        case Result.Success(outcome) =>
                            outcome.content.collect { case t: McpContent.Text => t.text }.mkString
                        case Result.Failure(_) => "(failure)"
                        case Result.Panic(_)   => "(panic)"
                    val ok = res.isSuccess && text.contains("counted to 3")
                    ScenarioResult(
                        "LongTask",
                        passed = ok,
                        notes =
                            if ok then s"count_slowly returned: $text"
                            else s"unexpected: res=$res text=$text"
                    )
                }
            }
        }
    end longTaskScenario

    // --- Scenario 5: TodoIndexer (workDoneProgress/create reverse-call) -----------------------

    private def todoIndexerScenario(worktreeDir: Path)(using
        Frame
    ): ScenarioResult < (Async & Scope & Abort[McpException | LspException | Closed | CommandException]) =
        val createCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val createHandler =
            LspHandler.customClient[LspHandler.WorkDoneProgressCreateParams]("window/workDoneProgress/create") { _ =>
                Sync.defer(drop(createCount.incrementAndGet()(using AllowUnsafe.embrace.danger)))
            }

        spawn(worktreeDir, "kyo-lsp", "demo.TodoIndexer").map { transport =>
            LspClient.initUnscoped(
                transport,
                LspInfo("harness"),
                LspCapabilities.Client.Client(),
                createHandler
            ).map { client =>
                Abort.run[LspException | Closed](
                    client.executeCommand[Unit](LspHandler.ExecuteCommandParams("todo-indexer.reindex"))
                ).map { res =>
                    val cc = createCount.get()(using AllowUnsafe.embrace.danger)
                    val ok = res.isSuccess && cc >= 1
                    ScenarioResult(
                        "TodoIndexer",
                        passed = ok,
                        notes = if ok then s"command returned; workDoneProgress/create called $cc time(s)"
                        else s"res=$res; create=$cc"
                    )
                }
            }
        }
    end todoIndexerScenario

end RunAll
