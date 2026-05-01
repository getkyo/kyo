package demo

import kyo.*

/** Locked-down runner for user-submitted code.
  *
  * Each submission runs in a fresh container with hard memory + CPU + PID limits, no network, a read-only root filesystem (with a small
  * tmpfs at `/tmp`), no privileged syscalls, and a wall-clock timeout. Output is captured and returned alongside the exit code.
  *
  * Demonstrates: `Config.memory`/`cpuLimit`/`maxProcesses`/`readOnlyFilesystem`/`privileged`, `NetworkMode.None`, tmpfs mount,
  * `Container.init` scope-managed lifecycle, `Async.timeout` wrapping `waitForExit`, stdout/stderr capture via `logs`.
  *
  * Not a real production sandbox — seccomp, user namespaces, and image hardening are out of scope. Illustrates only the kyo-pod API surface
  * needed to approximate a sandbox.
  */
object CodeSandbox extends KyoApp:

    final case class SandboxResult(exitCode: Int, stdout: String, stderr: String, timedOut: Boolean)

    val defaultTimeout: Duration = 5.seconds

    /** Execute `source` in an isolated Python container. Returns stdout+stderr+exit, or `timedOut = true` if the wall-clock limit hit.
      *
      * Uses the manual `Container.init` + `waitForExit` + `logs` pipeline rather than `Container.runOnce` because the sandbox requires
      * security options (`readOnlyFilesystem`, `dropCapabilities`, tmpfs mount) that `runOnce` does not expose.
      */
    def runPython(
        source: String,
        timeout: Duration = defaultTimeout,
        memoryBytes: Long = 64L * 1024 * 1024,
        cpus: Double = 0.5,
        pids: Long = 64
    )(using Frame): SandboxResult < (Async & Abort[ContainerException] & Scope) =
        val config = Container.Config.default.copy(
            image = ContainerImage("python:3.12-alpine"),
            memory = Present(memoryBytes),
            cpuLimit = Present(cpus),
            maxProcesses = Present(pids),
            readOnlyFilesystem = true,
            mounts = Chunk(Container.Config.Mount.Tmpfs(Path("/tmp"))),
            networkMode = Container.Config.NetworkMode.None,
            privileged = false,
            dropCapabilities = Chunk(Container.Capability.Custom("ALL")),
            healthCheck = Container.HealthCheck.noop
        ).command(Command("python3", "-u", "-c", source))

        Container.init(config).map { c =>
            def collectLogs: (String, String) < (Async & Abort[ContainerException]) =
                c.logs(stdout = true, stderr = true).map { entries =>
                    val stdout = entries.filter(_.source == Container.LogEntry.Source.Stdout).map(_.content).toSeq.mkString("\n")
                    val stderr = entries.filter(_.source == Container.LogEntry.Source.Stderr).map(_.content).toSeq.mkString("\n")
                    (stdout, stderr)
                }

            Abort.recover[Timeout] { (_: Timeout) =>
                // Timeout hit — container will be torn down by Scope; collect what logs we have.
                Abort.recover[ContainerException] { (_: ContainerException) =>
                    SandboxResult(exitCode = -1, stdout = "", stderr = "[sandbox] could not collect logs after timeout", timedOut = true)
                } {
                    collectLogs.map { case (out, err) =>
                        SandboxResult(exitCode = -1, stdout = out, stderr = err, timedOut = true)
                    }
                }
            } {
                Async.timeout(timeout)(c.waitForExit).map { exit =>
                    collectLogs.map { case (out, err) =>
                        SandboxResult(exitCode = exit.toInt, stdout = out, stderr = err, timedOut = false)
                    }
                }
            }
        }
    end runPython

    /** Examples demonstrating happy path, stderr capture, memory blow, infinite loop timeout, network blocked. */
    val examples = Chunk(
        "hello" ->
            """print("hello from sandbox")""",
        "stderr-capture" ->
            """import sys
              |print("to stdout")
              |print("to stderr", file=sys.stderr)
              |""".stripMargin,
        "memory-blow" ->
            """x = bytearray(200 * 1024 * 1024)
              |print("allocated 200MB — should NOT reach here under 64MB limit")
              |""".stripMargin,
        "infinite-loop" ->
            """while True: pass""",
        "no-network" ->
            """import socket
              |try:
              |    s = socket.create_connection(("1.1.1.1", 80), timeout=1)
              |    print("network OPEN — should not happen")
              |except Exception as e:
              |    print(f"network blocked: {type(e).__name__}")
              |""".stripMargin
    )

    run {
        val pythonImage = ContainerImage("python:3.12-alpine")
        ContainerImage.ensure(pythonImage).andThen {
            Kyo.foreachDiscard(examples) { case (name, source) =>
                Scope.run {
                    Console.printLine(s"=== $name ===").andThen {
                        runPython(source, timeout = 3.seconds).map { r =>
                            Console.printLine(s"  exit=${r.exitCode} timedOut=${r.timedOut}").andThen {
                                val printOut =
                                    if r.stdout.nonEmpty then Console.printLine(s"  stdout: ${r.stdout.replace("\n", " | ")}")
                                    else Kyo.unit
                                val printErr =
                                    if r.stderr.nonEmpty then Console.printLine(s"  stderr: ${r.stderr.replace("\n", " | ")}")
                                    else Kyo.unit
                                printOut.andThen(printErr)
                            }
                        }
                    }
                }
            }
        }
    }
end CodeSandbox
