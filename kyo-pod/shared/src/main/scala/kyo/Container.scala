package kyo

import kyo.internal.ContainerBackend
import kyo.internal.ContainerHealthState
import kyo.internal.HttpContainerBackend
import kyo.internal.ShellBackend

/** A handle to a running or stopped OS container managed by Docker or Podman.
  *
  * Create containers via `Container.init` (scoped lifecycle) or `Container.initWith` (bracket semantics with explicit `Scope.run`). On
  * scope close the container receives SIGTERM for graceful shutdown, then is force-removed. The backend is auto-detected at first use
  * (Podman preferred, then Docker) and can be overridden per-scope with `Container.withBackendConfig`.
  *
  * Typical use cases: integration testing, sidecar services, job runners, batch processing, CI/CD pipelines, and dynamic scaling scenarios
  * where containers are created and torn down programmatically.
  *
  * Health checks run in a background fiber with configurable retry schedules. Use `HealthCheck.exec`, `HealthCheck.port`,
  * `HealthCheck.httpGet`, or `HealthCheck.log` for common patterns, `HealthCheck.init` for custom logic, or `HealthCheck.noop` to skip
  * health checking entirely.
  *
  * WARNING: The container is force-removed on scope exit. Use `Container.initUnscoped` for containers that must outlive a scope, and manage
  * their lifecycle manually via `stop` and `remove`.
  *
  * @see
  *   [[Container.init]] Scoped container creation with automatic cleanup
  * @see
  *   [[Container.initWith]] Bracket-style create/use/cleanup — wrap in `Scope.run` to eliminate Scope from effect set
  * @see
  *   [[Container.initUnscoped]] Unscoped creation — caller manages lifecycle
  * @see
  *   [[Container.Config]] Container configuration (image, ports, mounts, resources, security)
  * @see
  *   [[ContainerImage]] Structured image reference (registry/namespace/name:tag@digest)
  * @see
  *   [[ContainerException]] Typed error hierarchy for container operations
  */
final class Container private[kyo] (
    val id: Container.Id,
    val config: Container.Config,
    private[kyo] val backend: ContainerBackend,
    private[kyo] val healthState: AtomicRef[ContainerHealthState]
):

    import Container.*

    // --- Lifecycle ---

    /** Start the container if it is stopped or newly created. No-op if already running. */
    def start(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.start(id)

    /** Send SIGTERM and wait 3 seconds for graceful shutdown. */
    def stop(using Frame): Unit < (Async & Abort[ContainerException]) =
        stop(3.seconds)

    /** Send SIGTERM and wait up to `timeout` for graceful shutdown, then SIGKILL. */
    def stop(timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.stop(id, timeout)

    /** Send SIGTERM to the container process. */
    def kill(using Frame): Unit < (Async & Abort[ContainerException]) =
        kill(Signal.SIGTERM)

    /** Send a signal to the container process immediately without waiting. */
    def kill(signal: Signal)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.kill(id, signal)

    /** Restart with a 3-second stop timeout. */
    def restart(using Frame): Unit < (Async & Abort[ContainerException]) =
        restart(3.seconds)

    /** Stop then start the container. Uses `timeout` for the stop phase. */
    def restart(timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.restart(id, timeout)

    /** Freeze all container processes via SIGSTOP. The container remains in memory. */
    def pause(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.pause(id)

    /** Resume a paused container via SIGCONT. */
    def unpause(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.unpause(id)

    /** Remove this container (must be stopped). */
    def remove(using Frame): Unit < (Async & Abort[ContainerException]) =
        remove(force = false, removeVolumes = false)

    /** Delete this container. If `force` is true, kills the container first if running. */
    def remove(force: Boolean, removeVolumes: Boolean = false)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.remove(id, force, removeVolumes)

    /** Change the container's name. */
    def rename(newName: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.rename(id, newName)

    /** Block until the container exits and return its exit code. */
    def waitForExit(using Frame): ExitCode < (Async & Abort[ContainerException]) =
        backend.waitForExit(id)

    // --- Health ---

    /** Check container health. Re-runs the configured health check (or falls back to runtime state if none). */
    def isHealthy(using Frame): Boolean < (Async & Abort[ContainerException]) =
        healthState.get.map { state =>
            state.check match
                case Absent =>
                    backend.isHealthy(id)
                case Present(hc) =>
                    Abort.recover[ContainerException] { (_: ContainerException) =>
                        healthState.set(ContainerHealthState(false, Present(hc))).andThen(false)
                    } {
                        hc.check(this).andThen {
                            healthState.set(ContainerHealthState(true, Present(hc))).andThen(true)
                        }
                    }
            end match
        }
    end isHealthy

    /** Block until the container's health check passes, retrying per its configured schedule.
      *
      * For a single-shot check use [[isHealthy]] instead.
      */
    def awaitHealthy(using Frame): Unit < (Async & Abort[ContainerException]) =
        healthState.get.map { state =>
            state.check match
                case Absent => ()
                case Present(hc) =>
                    Retry[ContainerException](hc.schedule)(hc.check(this)).andThen {
                        healthState.set(ContainerHealthState(true, Present(hc))).unit
                    }
            end match
        }
    end awaitHealthy

    // --- Inspection ---

    /** The hostname clients use to reach a mapped port. Currently always `"127.0.0.1"` (local Docker); reserved for future remote-Docker
      * support via `DOCKER_HOST`.
      */
    val host: String = "127.0.0.1"

    /** Returns the host port that Docker bound to `containerPort`. Looks up the matching `containerPort/tcp` entry in this container's
      * inspect response and returns its bound host port. Useful when binding to host port 0 (auto-allocate).
      *
      * Fails with `ContainerOperationException` if no binding exists for `containerPort` (common cause: the port wasn't included in the
      * `Container.Config.ports` list).
      */
    def mappedPort(containerPort: Int)(using Frame): Int < (Async & Abort[ContainerException]) =
        inspect.map { info =>
            info.ports.find(b => b.containerPort == containerPort && b.protocol == Config.Protocol.TCP) match
                case Some(binding) if binding.hostPort > 0 => binding.hostPort
                case _ =>
                    Abort.fail(ContainerOperationException(s"Port $containerPort is not bound on container ${id.value}"))
        }

    /** Full container metadata snapshot (state, ports, mounts, network, etc.). */
    def inspect(using Frame): Info < (Async & Abort[ContainerException]) =
        backend.inspect(id)

    /** Current lifecycle state (Running, Stopped, Paused, etc.). */
    def state(using Frame): State < (Async & Abort[ContainerException]) =
        backend.state(id)

    /** Single point-in-time resource usage snapshot (CPU, memory, network, I/O). */
    def stats(using Frame): Stats < (Async & Abort[ContainerException]) =
        backend.stats(id)

    /** Continuous resource usage polling at 200ms intervals. */
    def statsStream(using Frame): Stream[Stats, Async & Abort[ContainerException]] =
        statsStream(200.millis)

    /** Continuous resource usage polling at the given interval. */
    def statsStream(interval: Duration)(using Frame): Stream[Stats, Async & Abort[ContainerException]] =
        backend.statsStream(id, interval)

    /** List running processes with default ps arguments. */
    def top(using Frame): TopResult < (Async & Abort[ContainerException]) =
        top("aux")

    /** List running processes inside the container, like `ps`. Passes `psArgs` to ps. */
    def top(psArgs: String)(using Frame): TopResult < (Async & Abort[ContainerException]) =
        backend.top(id, psArgs)

    /** Filesystem diff since container creation (added, modified, deleted files). */
    def changes(using Frame): Chunk[FilesystemChange] < (Async & Abort[ContainerException]) =
        backend.changes(id)

    // --- Exec ---

    /** Run a command inside the container and wait for the result (exit code + stdout/stderr). */
    def exec(command: String*)(using Frame): ExecResult < (Async & Abort[ContainerException]) =
        backend.exec(id, Command(command*))

    /** Run a command inside the container and wait for the result (exit code + stdout/stderr). */
    def exec(command: Command)(using Frame): ExecResult < (Async & Abort[ContainerException]) =
        backend.exec(id, command)
    end exec

    /** Run a command and stream output as log entries as it arrives. */
    def execStream(command: Command)(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
        backend.execStream(id, command)

    /** Run a command and stream output as log entries as it arrives. */
    def execStream(command: String*)(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
        backend.execStream(id, Command(command*))

    /** Interactive exec with bidirectional stdin/stdout and terminal resize support. */
    def execInteractive(command: Command)(using Frame): AttachSession < (Async & Abort[ContainerException] & Scope) =
        backend.execInteractive(id, command)

    /** Interactive exec with bidirectional stdin/stdout and terminal resize support. */
    def execInteractive(command: String*)(using Frame): AttachSession < (Async & Abort[ContainerException] & Scope) =
        backend.execInteractive(id, Command(command*))

    // --- Attach ---

    /** Attach to the container's main process for bidirectional I/O. */
    def attach(using Frame): AttachSession < (Async & Abort[ContainerException] & Scope) =
        attach(stdin = true, stdout = true, stderr = true)

    /** Attach to the container's main process, selecting which streams to connect. */
    def attach(
        stdin: Boolean,
        stdout: Boolean,
        stderr: Boolean
    )(using Frame): AttachSession < (Async & Abort[ContainerException] & Scope) =
        backend.attach(id, stdin, stdout, stderr)

    // --- Logs ---

    /** Returns up to [[Container.defaultLogTail]] of the most recent log entries. To request the full history pass an explicit
      * `tail = Int.MaxValue`; for very long-running containers prefer [[logStream]] to avoid materializing megabytes in memory.
      */
    def logs(using Frame): Chunk[LogEntry] < (Async & Abort[ContainerException]) =
        logs(stdout = true, stderr = true, since = Instant.Min, until = Instant.Max, timestamps = false, tail = Container.defaultLogTail)

    /** Filter logs by stream, time range, and tail count. When `timestamps` is true, each entry is prefixed with an ISO timestamp.
      *
      * `tail` bounds the number of lines returned. The default is [[Container.defaultLogTail]] (1000) — a memory-safe ceiling for the
      * buffered API. Pass `Int.MaxValue` to get the full history (use with care on long-running containers); for unbounded but lazy
      * consumption, prefer [[logStream]].
      */
    def logs(
        stdout: Boolean = true,
        stderr: Boolean = true,
        since: Instant = Instant.Min,
        until: Instant = Instant.Max,
        timestamps: Boolean = false,
        tail: Int = Container.defaultLogTail
    )(using Frame): Chunk[LogEntry] < (Async & Abort[ContainerException]) =
        backend.logs(id, stdout, stderr, since, until, timestamps, tail)

    /** Returns up to [[Container.defaultLogTail]] of the most recent log lines as raw text. Same memory tradeoff as [[logs]] — see that
      * method for guidance on when to override `tail` or switch to [[logStream]].
      */
    def logsText(using Frame): String < (Async & Abort[ContainerException]) =
        logsText(
            stdout = true,
            stderr = true,
            since = Instant.Min,
            until = Instant.Max,
            timestamps = false,
            tail = Container.defaultLogTail
        )

    /** Same filters as [[logs]], but returns raw text instead of structured LogEntry values. `tail` defaults to
      * [[Container.defaultLogTail]] (1000); pass `Int.MaxValue` for full history.
      */
    def logsText(
        stdout: Boolean = true,
        stderr: Boolean = true,
        since: Instant = Instant.Min,
        until: Instant = Instant.Max,
        timestamps: Boolean = false,
        tail: Int = Container.defaultLogTail
    )(using Frame): String < (Async & Abort[ContainerException]) =
        logs(stdout, stderr, since, until, timestamps, tail).map { entries =>
            entries.map(_.content).toSeq.mkString("\n")
        }

    /** Continuous log stream that emits the last [[Container.defaultLogTail]] lines, then follows new entries as they are produced. The
      * stream is lazy — the bound only affects the initial backfill, not steady-state consumption.
      */
    def logStream(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
        logStream(stdout = true, stderr = true, since = Instant.Min, timestamps = false, tail = Container.defaultLogTail)

    /** Continuous log tail with filtering. New entries are emitted as they arrive.
      *
      * `tail` bounds the initial backfill emitted before the stream catches up to live. The default is [[Container.defaultLogTail]] (1000);
      * pass `Int.MaxValue` to backfill the full history (rare — usually you want recent context plus future events).
      */
    def logStream(
        stdout: Boolean = true,
        stderr: Boolean = true,
        since: Instant = Instant.Min,
        timestamps: Boolean = false,
        tail: Int = Container.defaultLogTail
    )(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
        backend.logStream(id, stdout, stderr, since, timestamps, tail)

    // --- File operations ---

    /** Copy a file or directory from the host into the container. */
    def copyTo(source: Path, containerPath: Path)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.copyTo(id, source, containerPath)

    /** Copy a file or directory from the container to the host. */
    def copyFrom(containerPath: Path, destination: Path)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.copyFrom(id, containerPath, destination)

    /** File metadata (size, mode, mtime) for a path inside the container. */
    def stat(containerPath: Path)(using Frame): FileStat < (Async & Abort[ContainerException]) =
        backend.stat(id, containerPath)

    /** Export the entire container filesystem as a tar byte stream. */
    def exportFs(using Frame): Stream[Byte, Async & Abort[ContainerException]] =
        backend.exportFs(id)

    // --- Resource updates ---

    /** Update resource limits on a running container without restarting it. Only provided fields are changed. Pass at least one non-Absent
      * field; an all-Absent call is a noop.
      */
    def update(
        memory: Maybe[Long],
        memorySwap: Maybe[Long] = Absent,
        cpuLimit: Maybe[Double] = Absent,
        cpuAffinity: Maybe[String] = Absent,
        maxProcesses: Maybe[Long] = Absent,
        restartPolicy: Maybe[Config.RestartPolicy] = Absent
    )(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.update(id, memory, memorySwap, cpuLimit, cpuAffinity, maxProcesses, restartPolicy)

    // --- Network ---

    /** Attach this container to a network, optionally with DNS aliases. */
    def connectToNetwork(networkId: Network.Id, aliases: Chunk[String] = Chunk.empty)(using
        Frame
    ): Unit < (Async & Abort[ContainerException]) =
        backend.connectToNetwork(id, networkId, aliases)

    /** Detach this container from a network. */
    def disconnectFromNetwork(networkId: Network.Id, force: Boolean = false)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.disconnectFromNetwork(id, networkId, force)

    // --- Checkpoint/Restore ---

    /** CRIU-based checkpoint: freeze container state to disk. Requires CRIU installed on the host.
      *
      * Returns the checkpoint name so callers can feed it directly into [[restore]].
      */
    def checkpoint(name: String)(using Frame): String < (Async & Abort[ContainerException]) =
        backend.checkpoint(id, name).andThen(name)

    /** Restore a previously checkpointed container state. Requires CRIU installed on the host. */
    def restore(checkpoint: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.restore(id, checkpoint)

    // --- Infrastructure ---

    override def toString: String = s"Container(${id.value})"

    override def equals(that: Any): Boolean = that match
        case c: Container => id == c.id
        case _            => false

    override def hashCode: Int = id.hashCode

end Container

object Container:

    /** Command that traps SIGTERM and sleeps indefinitely. Use as `Container.Config(image, command = Present(Container.sleepForever))` when
      * you need a long-lived container whose image has no useful default CMD (e.g. an empty alpine).
      */
    val sleepForever: Command = Command("sh", "-c", "trap 'exit 0' TERM; sleep infinity")

    // --- Entry points ---

    /** Create and start a scoped container. Automatically stopped and removed on scope exit.
      *
      * `imageEnsure` runs first — a no-op when the image is already local, otherwise an `imagePull`. This makes init symmetric across
      * backends (the shell backend's `docker create` / `podman create` auto-pull as a side effect; the HTTP backend's
      * `POST /containers/create` returns 404 instead). Aligns with Testcontainers behavior.
      */
    def init(config: Config)(using Frame): Container < (Async & Abort[ContainerException] & Scope) =
        currentBackend.map { b =>
            b.imageEnsure(config.image, Absent, Absent).andThen(b.create(config)).map { cid =>
                // Register cleanup IMMEDIATELY after create, before start
                Scope.ensure {
                    def logFailure(op: String)(r: Result[Throwable, Unit])(using Frame): Unit < Sync = r match
                        case Result.Success(_) => ()
                        // The container being already absent is the desired end state — nothing to warn about.
                        // Happens when the caller (or autoRemove) removed it before the Scope finalizer ran.
                        case Result.Failure(_: ContainerMissingException) => ()
                        case Result.Failure(e)                            => Log.warn(s"Container ${cid.value} $op failed: ${e.getMessage}")
                        case Result.Panic(e) => Log.warn(s"Container ${cid.value} $op panicked: ${e.getMessage}")

                    val shutdown: Unit < (Async & Abort[Nothing]) = config.stopSignal match
                        case Present(signal) =>
                            Abort.run[ContainerException](b.kill(cid, signal)).map(logFailure("kill")).andThen(
                                Abort.run[ContainerException | Timeout](
                                    Async.timeout(config.stopTimeout)(b.waitForExit(cid))
                                ).map(r => logFailure("waitForExit")(r.map(_ => ())))
                            )
                        case Absent =>
                            Abort.run[ContainerException](b.stop(cid, config.stopTimeout)).map(logFailure("stop"))

                    shutdown.andThen(
                        Abort.run[ContainerException](b.remove(cid, force = true, removeVolumes = false)).map(logFailure("remove"))
                    )
                }.andThen {
                    b.start(cid).andThen {
                        AtomicRef.init(ContainerHealthState(false, Absent)).map { healthRef =>
                            val container = new Container(cid, config, b, healthRef)
                            runHealthCheck(container, config.healthCheck).andThen(container)
                        }
                    }
                }
            }
        }

    /** Convenience overload: create and start a scoped container from an image with optional ports. */
    def init(
        image: ContainerImage,
        command: Maybe[Command] = Absent,
        name: Maybe[String] = Absent,
        ports: Chunk[(Int, Int)] = Chunk.empty,
        healthCheck: HealthCheck = HealthCheck.noop
    )(using Frame): Container < (Async & Abort[ContainerException] & Scope) =
        val portBindings = Chunk.from(ports.map { case (container, host) => Config.PortBinding(container, host) })
        val config = Config.default.copy(
            image = image,
            command = command,
            name = name,
            ports = portBindings,
            healthCheck = healthCheck
        )
        init(config)
    end init

    /** Creates a container and runs `f` with it, keeping `Scope` in the effect row.
      *
      * The container is cleaned up when the enclosing `Scope.run` completes — callers wrap in `Scope.run` when they want bracket semantics.
      */
    def initWith[A, S](config: Config)(f: Container => A < S)(using Frame): A < (S & Async & Abort[ContainerException] & Scope) =
        init(config).map(f)

    /** Creates a container without scope cleanup — caller manages lifecycle manually.
      *
      * Unlike `init`, the container is NOT automatically stopped or removed when the scope closes. The caller is responsible for calling
      * `stop` and `remove`.
      *
      * IMPORTANT: Use this for long-lived containers that must outlive any scope.
      */
    def initUnscoped(config: Config)(using Frame): Container < (Async & Abort[ContainerException]) =
        currentBackend.map { b =>
            AtomicRef.init(ContainerHealthState(false, Absent)).map { healthRef =>
                b.imageEnsure(config.image, Absent, Absent).andThen(b.create(config)).map { cid =>
                    val container = new Container(cid, config, b, healthRef)
                    Abort.run[ContainerException] {
                        b.start(cid).andThen(runHealthCheck(container, config.healthCheck))
                    }.map {
                        case Result.Success(_)   => container
                        case Result.Failure(err) =>
                            // Cleanup on failure — best effort
                            Abort.run[ContainerException](b.remove(cid, force = true, removeVolumes = false))
                                .andThen(Abort.fail[ContainerException](err))
                        case Result.Panic(ex) =>
                            // Cleanup on panic — best effort
                            Abort.run[ContainerException](b.remove(cid, force = true, removeVolumes = false))
                                .andThen(Abort.panic[ContainerException](ex))
                    }
                }
            }
        }

    /** Creates a container without scope cleanup and runs `f` with it. Caller manages `stop`/`remove`. */
    def initUnscopedWith[A, S](config: Config)(f: Container => A < S)(using Frame): A < (S & Async & Abort[ContainerException]) =
        initUnscoped(config).map(f)

    /** Start multiple containers sequentially, awaiting each's health before starting the next. Returns all containers as `Chunk`. All are
      * scope-managed: closing the enclosing Scope tears them down.
      *
      * Use when a stack has ordered dependencies — e.g. database must be healthy before app container starts.
      *
      * @param awaitHealthy
      *   when true (default), each container's `awaitHealthy` must succeed before the next starts
      */
    def initAll(configs: Chunk[Config], awaitHealthy: Boolean = true)(using
        Frame
    )
        : Chunk[Container] < (Async & Abort[ContainerException] & Scope) =
        Kyo.foreach(configs) { cfg =>
            init(cfg).map { c =>
                (if awaitHealthy then c.awaitHealthy else Kyo.unit).andThen(c)
            }
        }

    /** Run `command` in a fresh container, wait for it to exit (or timeout), collect logs, and clean up.
      *
      * Equivalent to wrapping `Container.init` + `c.waitForExit` + `c.logs` in a `Scope.run`, but packaged as a single call for the common
      * "fire-and-forget batch job" pattern.
      *
      * On timeout the container is still torn down by `Scope`, and the result carries `ExitCode.Signaled(15)` with a sentinel message
      * appended to stderr.
      *
      * For containers that need security options not exposed here (e.g. `readOnlyFilesystem`, `dropCapabilities`, tmpfs mounts), use the
      * manual `Container.init` + `waitForExit` + `logs` pipeline directly.
      */
    def runOnce(
        image: ContainerImage,
        command: Command,
        timeout: Duration = 30.seconds,
        env: Dict[String, String] = Dict.empty,
        memory: Maybe[Long] = Absent,
        cpuLimit: Maybe[Double] = Absent,
        networkMode: Config.NetworkMode = Config.NetworkMode.Bridge
    )(using Frame): ExecResult < (Async & Abort[ContainerException]) =
        Scope.run {
            init(Container.Config.default.copy(
                image = image,
                command = Present(command),
                env = env,
                memory = memory,
                cpuLimit = cpuLimit,
                networkMode = networkMode,
                healthCheck = HealthCheck.noop
            )).map { c =>
                def collect: ExecResult < (Async & Abort[ContainerException]) =
                    c.logs(stdout = true, stderr = true).map { entries =>
                        val stdout = entries.filter(_.source == LogEntry.Source.Stdout).map(_.content).toSeq.mkString("\n")
                        val stderr = entries.filter(_.source == LogEntry.Source.Stderr).map(_.content).toSeq.mkString("\n")
                        ExecResult(ExitCode.Success, stdout, stderr)
                    }
                Abort.recover[Timeout] { (_: Timeout) =>
                    collect.map(r => r.copy(exitCode = ExitCode.Signaled(15), stderr = r.stderr + "\n[kyo-pod] runOnce timed out"))
                } {
                    Async.timeout(timeout)(c.waitForExit).map { exit =>
                        c.logs(stdout = true, stderr = true).map { entries =>
                            val stdout = entries.filter(_.source == LogEntry.Source.Stdout).map(_.content).toSeq.mkString("\n")
                            val stderr = entries.filter(_.source == LogEntry.Source.Stderr).map(_.content).toSeq.mkString("\n")
                            ExecResult(exit, stdout, stderr)
                        }
                    }
                }
            }
        }
    end runOnce

    /** Attach to an existing container by ID or name. Does not manage its lifecycle. */
    def attach(idOrName: Id)(using Frame): Container < (Async & Abort[ContainerException]) =
        currentBackend.map { b =>
            b.attachById(idOrName).map { case (resolvedId, config) =>
                AtomicRef.init(ContainerHealthState(false, Absent)).map { healthRef =>
                    new Container(resolvedId, config, b, healthRef)
                }
            }
        }

    /** List running containers. */
    def list(using Frame): Chunk[Summary] < (Async & Abort[ContainerException]) =
        list(all = false, filters = Dict.empty)

    /** List containers. When `all` is true, includes stopped containers. */
    def list(
        all: Boolean,
        filters: Filters = Dict.empty
    )(using Frame): Chunk[Summary] < (Async & Abort[ContainerException]) =
        currentBackend.map(_.list(all, filters))

    /** Returns a human-readable description of the currently-active backend (socket path, API version, CLI name).
      *
      * Useful for diagnostics — if a command seems to hit the wrong daemon (e.g. `docker pull` put an image somewhere kyo-pod isn't
      * looking), this tells you which backend kyo-pod has selected.
      */
    def currentBackendDescription(using Frame): String < (Async & Abort[ContainerException]) =
        currentBackend.map(_.describe)

    /** Remove all stopped containers. */
    def prune(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        prune(filters = Dict.empty)

    /** Remove all stopped containers matching the given filters. */
    def prune(
        filters: Filters
    )(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        currentBackend.map(_.prune(filters))

    /** Override backend detection (Docker/Podman/socket) for all container operations in the enclosed block. */
    def withBackendConfig[A, S](config: BackendConfig)(v: => A < S)(using Frame): A < (Async & Abort[ContainerException] & S) =
        resolveBackend(config).map(b => backendLocal.let(Present(b))(v))

    /** Override backend detection (Docker/Podman/socket) for all container operations in the enclosed block. */
    def withBackendConfig[A, S](f: BackendConfig.type => BackendConfig)(v: => A < S)(using
        Frame
    ): A < (Async & Abort[ContainerException] & S) =
        withBackendConfig(f(BackendConfig))(v)

    // --- Typed Primitives ---

    /** Unique container identifier assigned by the runtime. */
    opaque type Id = String
    object Id:
        given CanEqual[Id, Id] = CanEqual.derived

        given Render[Id] = Render.from(_.value)

        def apply(value: String): Id           = value
        extension (self: Id) def value: String = self
    end Id

    /** Filter map passed through to the Docker/Podman daemon. Keys and values are forwarded verbatim. Common keys by operation:
      *   - container list: `label`, `name`, `status`, `network`, `ancestor`, `exited`
      *   - container prune: `label`, `until`
      *   - image list: `label`, `dangling`, `reference`, `before`, `since`
      *   - image prune: `label`, `dangling`, `until`
      *   - network list/prune: `driver`, `id`, `label`, `name`, `scope`, `type`
      *   - volume list/prune: `dangling`, `driver`, `label`, `name`
      *
      * See Docker's filter documentation for the complete key set and value syntax.
      */
    type Filters = Dict[String, Chunk[String]]

    // --- Config ---

    /** Creation-time configuration for a container.
      *
      * Bundles everything the backend needs to `create` and `start` a container: the image, command, environment, port publishing, mounts,
      * networking mode, resource limits (memory/CPU/processes), security flags (capabilities, privileged mode, read-only filesystem),
      * behavioral flags (interactive, tty, auto-remove), and the lifecycle stop signal and timeout.
      *
      * Build a config from a starting point — either the empty default (`Config.default`) or the image-only shortcut (`Config(image)`) —
      * then chain fluent builder methods: `.name("api").port(8080).env("KEY", "value").memory(512 * 1024 * 1024)`. Every builder returns a
      * new `Config`; the underlying case class is immutable.
      *
      * Nested types in the companion (`PortBinding`, `Mount`, `NetworkMode`, `Protocol`, `RestartPolicy`, `ExtraHost`) model specific
      * fields. `HealthCheck` is separate — `Config.healthCheck` accepts factory-built checks like `HealthCheck.exec`, `HealthCheck.port`,
      * or `HealthCheck.noop` for containers where readiness cannot be probed.
      *
      * IMPORTANT: Not every field is honored by every backend. Resource limits like `cpuAffinity` and `maxProcesses` require kernel support
      * (cgroups v2 for cpuAffinity, pids controller for maxProcesses). Unsupported settings are silently ignored by the runtime.
      *
      * @see
      *   [[Container.init]] scoped creation that accepts a `Config`
      * @see
      *   [[Container.initUnscoped]] unscoped creation — caller manages lifecycle
      * @see
      *   [[Config.default]] the all-defaults starting point
      * @see
      *   [[HealthCheck]] readiness probes wired via `.healthCheck(...)`
      * @see
      *   [[BackendConfig]] select Docker/Podman/socket backend per enclosing block
      */
    case class Config(
        image: ContainerImage,
        command: Maybe[Command],
        name: Maybe[String],
        hostname: Maybe[String],
        user: Maybe[String],
        labels: Dict[String, String],
        env: Dict[String, String],
        ports: Chunk[Config.PortBinding],
        mounts: Chunk[Config.Mount],
        networkMode: Config.NetworkMode,
        dns: Chunk[String],
        extraHosts: Chunk[Config.ExtraHost],
        // Resource limits
        memory: Maybe[Long],
        memorySwap: Maybe[Long],
        cpuLimit: Maybe[Double],
        cpuAffinity: Maybe[String],
        maxProcesses: Maybe[Long],
        // Security
        privileged: Boolean,
        addCapabilities: Chunk[Capability],
        dropCapabilities: Chunk[Capability],
        readOnlyFilesystem: Boolean,
        // Behavior
        interactive: Boolean,
        allocateTty: Boolean,
        autoRemove: Boolean,
        restartPolicy: Config.RestartPolicy,
        stopSignal: Maybe[Signal],
        stopTimeout: Duration,
        healthCheck: HealthCheck
    ) derives CanEqual:
        // --- Builder methods ---

        def command(cmd: Command): Config = copy(command = Present(cmd))

        def command(cmd: String*): Config = copy(command = Present(Command(cmd*)))

        def name(n: String): Config = copy(name = Present(n))

        def hostname(h: String): Config = copy(hostname = Present(h))

        def user(u: String): Config = copy(user = Present(u))

        def labels(l: Dict[String, String]): Config = copy(labels = labels ++ l)

        def label(key: String, value: String): Config = copy(labels = labels.update(key, value))

        def env(key: String, value: String): Config = copy(env = env.update(key, value))

        def envAll(vars: Dict[String, String]): Config = copy(env = env ++ vars)

        // Ports

        def port(container: Int, host: Int): Config =
            copy(ports = ports.append(Config.PortBinding(container, host)))

        def port(container: Int): Config =
            copy(ports = ports.append(Config.PortBinding(container)))

        def port(binding: Config.PortBinding): Config =
            copy(ports = ports.append(binding))

        // Mounts

        def mount(m: Config.Mount): Config = copy(mounts = mounts.append(m))

        def mount(f: Config.Mount.type => Config.Mount): Config = copy(mounts = mounts.append(f(Config.Mount)))

        def bind(source: Path, target: Path, readOnly: Boolean = false): Config =
            copy(mounts = mounts.append(Config.Mount.Bind(source, target, readOnly)))

        def volume(name: Volume.Id, target: Path, readOnly: Boolean = false): Config =
            copy(mounts = mounts.append(Config.Mount.Volume(name, target, readOnly)))

        def tmpfs(target: Path): Config =
            copy(mounts = mounts.append(Config.Mount.Tmpfs(target)))

        // Network

        def networkMode(mode: Config.NetworkMode): Config = copy(networkMode = mode)

        def networkMode(f: Config.NetworkMode.type => Config.NetworkMode): Config =
            copy(networkMode = f(Config.NetworkMode))

        def dns(servers: String*): Config = copy(dns = dns.concat(Chunk.from(servers)))

        def extraHost(hostname: String, ip: String): Config =
            copy(extraHosts = extraHosts.append(Config.ExtraHost(hostname, ip)))

        // Resource limits

        def memory(bytes: Long): Config = copy(memory = Present(bytes))

        def memorySwap(bytes: Long): Config = copy(memorySwap = Present(bytes))

        def cpuLimit(cpus: Double): Config = copy(cpuLimit = Present(cpus))

        def cpuAffinity(affinity: String): Config = copy(cpuAffinity = Present(affinity))

        def maxProcesses(limit: Long): Config = copy(maxProcesses = Present(limit))

        // Security

        def privileged(value: Boolean): Config = copy(privileged = value)

        def addCapabilities(caps: Capability*): Config = copy(addCapabilities = addCapabilities.concat(Chunk.from(caps)))

        def dropCapabilities(caps: Capability*): Config = copy(dropCapabilities = dropCapabilities.concat(Chunk.from(caps)))

        def readOnlyFilesystem(value: Boolean): Config = copy(readOnlyFilesystem = value)

        // Behavior

        def interactive(value: Boolean): Config = copy(interactive = value)

        def allocateTty(value: Boolean): Config = copy(allocateTty = value)

        def autoRemove(value: Boolean): Config = copy(autoRemove = value)

        def restartPolicy(policy: Config.RestartPolicy): Config = copy(restartPolicy = policy)

        def restartPolicy(f: Config.RestartPolicy.type => Config.RestartPolicy): Config =
            copy(restartPolicy = f(Config.RestartPolicy))

        def stopSignal(signal: Signal): Config = copy(stopSignal = Present(signal))

        def stopTimeout(timeout: Duration): Config = copy(stopTimeout = timeout)

        def healthCheck(hc: HealthCheck): Config = copy(healthCheck = hc)

    end Config

    object Config:

        /** Default Config value — arbitrary placeholder image (`scratch`), all other fields at their natural defaults. */
        val default: Config = new Config(
            image = ContainerImage(
                name = "scratch",
                namespace = Absent,
                registry = Absent,
                tag = Absent,
                digest = Absent
            ),
            command = Absent,
            name = Absent,
            hostname = Absent,
            user = Absent,
            labels = Dict.empty,
            env = Dict.empty,
            ports = Chunk.empty,
            mounts = Chunk.empty,
            networkMode = Config.NetworkMode.Bridge,
            dns = Chunk.empty,
            extraHosts = Chunk.empty,
            memory = Absent,
            memorySwap = Absent,
            cpuLimit = Absent,
            cpuAffinity = Absent,
            maxProcesses = Absent,
            privileged = false,
            addCapabilities = Chunk.empty,
            dropCapabilities = Chunk.empty,
            readOnlyFilesystem = false,
            interactive = false,
            allocateTty = false,
            autoRemove = false,
            restartPolicy = Config.RestartPolicy.No,
            stopSignal = Absent,
            stopTimeout = 3.seconds,
            healthCheck = HealthCheck.noop
        )

        def apply(image: ContainerImage): Config = default.copy(image = image)

        def apply(image: String): Config = default.copy(image = ContainerImage.parse(image).getOrElse(ContainerImage(image)))

        case class PortBinding(
            containerPort: Int,
            hostPort: Int,
            hostIp: String,
            protocol: Protocol
        ) derives CanEqual

        object PortBinding:
            /** Default PortBinding — arbitrary placeholder `containerPort = 0`. */
            val default: PortBinding = new PortBinding(
                containerPort = 0,
                hostPort = 0,
                hostIp = "",
                protocol = Protocol.TCP
            )

            def apply(containerPort: Int): PortBinding =
                default.copy(containerPort = containerPort)

            def apply(containerPort: Int, hostPort: Int): PortBinding =
                default.copy(containerPort = containerPort, hostPort = hostPort)
        end PortBinding

        sealed trait Mount
        object Mount:
            case class Bind(source: Path, target: Path, readOnly: Boolean)                extends Mount derives CanEqual
            case class Volume(name: Container.Volume.Id, target: Path, readOnly: Boolean) extends Mount derives CanEqual
            case class Tmpfs(target: Path, sizeBytes: Maybe[Long])                        extends Mount derives CanEqual

            object Bind:
                /** Default Bind — arbitrary placeholder paths. */
                val default: Bind = new Bind(source = Path(""), target = Path(""), readOnly = false)

                def apply(source: Path, target: Path): Bind =
                    default.copy(source = source, target = target)
            end Bind

            object Volume:
                /** Default Volume mount — arbitrary placeholder name and target. */
                val default: Volume = new Volume(name = Container.Volume.Id(""), target = Path(""), readOnly = false)

                def apply(name: Container.Volume.Id, target: Path): Volume =
                    default.copy(name = name, target = target)
            end Volume

            object Tmpfs:
                /** Default Tmpfs — arbitrary placeholder target path. */
                val default: Tmpfs = new Tmpfs(target = Path(""), sizeBytes = Absent)

                def apply(target: Path): Tmpfs =
                    default.copy(target = target)
            end Tmpfs
        end Mount

        enum Protocol derives CanEqual:
            case TCP, UDP, SCTP

            def cliName: String = this match
                case TCP  => "tcp"
                case UDP  => "udp"
                case SCTP => "sctp"
        end Protocol

        enum NetworkMode derives CanEqual:
            case Bridge, Host, None
            case Shared(containerId: Container.Id)
            case Custom(name: String, aliases: Chunk[String] = Chunk.empty)
        end NetworkMode

        enum RestartPolicy derives CanEqual:
            case No
            case Always
            case UnlessStopped
            case OnFailure(maxRetries: Int)

            def cliName: String = this match
                case No            => "no"
                case Always        => "always"
                case UnlessStopped => "unless-stopped"
                case OnFailure(_)  => "on-failure"
        end RestartPolicy

        case class ExtraHost(hostname: String, ip: String) derives CanEqual

    end Config

    // --- HealthCheck ---

    /** Readiness probe configuration for a container.
      *
      * A `HealthCheck` pairs a single-attempt `check` — a computation that must succeed when the container is healthy — with a retry
      * `schedule` controlling cadence and exhaustion. On container start, kyo-pod runs the check on the container's background fiber: each
      * failure is retried per `schedule`; success flips the container's health state and unblocks `Container.awaitHealthy`. The full retry
      * history (capped) is surfaced in the final failure message.
      *
      * Use the factories in the companion for common patterns: [[HealthCheck.exec]] for in-container commands, [[HealthCheck.port]] for TCP
      * reachability via `/dev/tcp` or `nc`, [[HealthCheck.httpGet]] for HTTP probes, [[HealthCheck.log]] to grep container stdout.
      * [[HealthCheck.init]] builds one from a bare function + schedule. [[HealthCheck.noop]] disables health checking — use for one-shot
      * containers whose readiness can't be probed.
      *
      * Note: the schedule is a retry policy, not a periodic reschedule. Once the check succeeds, the container is considered healthy and
      * the schedule stops. `Container.isHealthy` re-runs the check once on demand without the schedule.
      *
      * @see
      *   [[HealthCheck.exec]] run a command inside the container and check its exit code / output
      * @see
      *   [[HealthCheck.port]] TCP port-reachability probe
      * @see
      *   [[HealthCheck.httpGet]] HTTP status-code probe
      * @see
      *   [[HealthCheck.log]] substring match on container logs
      * @see
      *   [[HealthCheck.init]] custom check from a function
      * @see
      *   [[HealthCheck.noop]] no-op check for one-shot containers
      */
    abstract class HealthCheck private[kyo]:
        def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException])

        def schedule: Schedule
    end HealthCheck

    object HealthCheck:

        val defaultRetrySchedule: Schedule = Schedule.fixed(500.millis).take(30)

        /** Create a custom health check using the default retry schedule. */
        def init(check: Container => Unit < (Async & Abort[ContainerException])): HealthCheck =
            init(defaultRetrySchedule)(check)

        /** Create a custom health check with an explicit retry schedule. */
        def init(retrySchedule: Schedule)(check: Container => Unit < (Async & Abort[ContainerException])): HealthCheck =
            val fn  = check
            val sch = retrySchedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    fn(container)

                def schedule: Schedule = sch
            end new
        end init

        lazy val default: HealthCheck = exec(Command("echo", "ok"))

        /** No-op health check — `awaitHealthy` returns immediately. Use for short-lived one-shot containers. */
        val noop: HealthCheck = init(_ => Kyo.unit)

        /** Health check that runs a command inside the container. Passes if exit code is 0 and output matches `expected` (when provided).
          */
        def exec(
            command: Command = Command("echo", "ok"),
            expected: Maybe[String] = Present("ok"),
            retrySchedule: Schedule = defaultRetrySchedule
        ): HealthCheck =
            val cmd = command
            val exp = expected
            val sch = retrySchedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    container.exec(cmd).map { result =>
                        if !result.isSuccess then
                            Abort.fail(ContainerHealthCheckException(
                                container.id,
                                s"exec exited ${result.exitCode}",
                                attempts = 1,
                                lastError = s"exit code: ${result.exitCode}"
                            ))
                        else
                            exp match
                                case Present(e) =>
                                    if result.stdout.trim != e then
                                        Abort.fail(ContainerHealthCheckException(
                                            container.id,
                                            s"expected '$e' in exec output, got '${result.stdout.trim}'",
                                            attempts = 1
                                        ))
                                    else ()
                                case Absent => ()
                    }
                end check

                def schedule: Schedule = sch
            end new
        end exec

        /** Shorthand exec health check with default schedule and no output matching. */
        def exec(command: String*): HealthCheck =
            exec(Command(command*), Absent, defaultRetrySchedule)

        /** Health check that searches container logs for the given message. Fails if the message is not found. Reads the last 500 lines of
          * logs on each retry, to bound memory on long-running containers.
          */
        def log(
            message: String,
            retrySchedule: Schedule = defaultRetrySchedule
        ): HealthCheck =
            val msg = message
            val sch = retrySchedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    container.logsText(tail = 500).map { output =>
                        if !output.contains(msg) then
                            Abort.fail(ContainerHealthCheckException(
                                container.id,
                                s"expected '$msg' in last 500 lines of logs",
                                attempts = 1
                            ))
                        else ()
                    }

                def schedule: Schedule = sch
            end new
        end log

        /** Health check that verifies a TCP port is reachable inside the container. Uses Bash's `/dev/tcp` when available, falling back to
          * `nc -z`.
          *
          * Requires `/bin/sh` in the image. Works with busybox (alpine) and any Bash-compatible shell. Not compatible with scratch /
          * distroless-static images that lack both `sh` and `nc`.
          */
        def port(
            port: Int,
            retrySchedule: Schedule = defaultRetrySchedule
        ): HealthCheck =
            val p   = port
            val sch = retrySchedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    container
                        .exec(Command(
                            "sh",
                            "-c",
                            s"""(bash -c 'exec 3<>/dev/tcp/127.0.0.1/$p' 2>/dev/null) || nc -z 127.0.0.1 $p 2>/dev/null"""
                        ))
                        .map { result =>
                            if !result.isSuccess then
                                Abort.fail(ContainerHealthCheckException(
                                    container.id,
                                    s"port $p not reachable",
                                    attempts = 1
                                ))
                            else ()
                        }

                def schedule: Schedule = sch
            end new
        end port

        /** Health check via HTTP GET inside the container (using `wget`). Fails if the request does not succeed. */
        def httpGet(
            port: Int,
            path: String = "/",
            expectedStatus: Int = 200,
            retrySchedule: Schedule = defaultRetrySchedule
        ): HealthCheck =
            val p   = port
            val pth = path
            val sch = retrySchedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    container.exec("wget", "-q", "-O", "/dev/null", "-S", s"http://localhost:$p$pth").map { result =>
                        if !result.isSuccess then
                            Abort.fail(ContainerHealthCheckException(
                                container.id,
                                s"HTTP GET http://localhost:$p$pth failed",
                                attempts = 1
                            ))
                        else ()
                    }

                def schedule: Schedule = sch
            end new
        end httpGet

        /** Composite health check: all checks must pass. Uses the schedule of the first check. */
        def all(checks: HealthCheck*): HealthCheck =
            val chs = checks
            val sch = if chs.isEmpty then defaultRetrySchedule else chs.head.schedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    Kyo.foreach(chs)(_.check(container)).unit

                def schedule: Schedule = sch
            end new
        end all

    end HealthCheck

    // --- State ---

    /** Runtime state of a container. */
    enum State derives CanEqual:
        case Created, Running, Paused, Restarting, Removing, Stopped, Dead

    object State:
        given Render[State] = Render.from(_.toString)
    end State

    // --- TopResult ---

    /** Process listing inside a running container. */
    case class TopResult(titles: Chunk[String], processes: Chunk[Chunk[String]]) derives CanEqual

    // --- PruneResult ---

    /** Result of pruning stopped containers or unused resources. */
    case class PruneResult(deleted: Chunk[String], spaceReclaimed: Long) derives CanEqual

    // --- ExecResult ---

    /** Result of executing a command inside a container. */
    case class ExecResult(exitCode: ExitCode, stdout: String, stderr: String) derives CanEqual:
        def isSuccess: Boolean = exitCode.isSuccess
    end ExecResult

    // --- LogEntry ---

    /** A single log line with source (stdout/stderr) and optional timestamp. */
    case class LogEntry(source: LogEntry.Source, content: String, timestamp: Maybe[Instant]) derives CanEqual

    object LogEntry:
        enum Source derives CanEqual:
            case Stdout, Stderr

        /** Default LogEntry — arbitrary placeholder source/content, no timestamp. */
        val default: LogEntry = new LogEntry(source = Source.Stdout, content = "", timestamp = Absent)

        def apply(source: Source, content: String): LogEntry =
            default.copy(source = source, content = content)
    end LogEntry

    // --- AttachSession ---

    /** Bidirectional connection to a container's stdin/stdout/stderr. */
    // CanEqual not derived — contains function-like methods (write, read, resize)
    abstract class AttachSession:
        def write(data: String)(using Frame): Unit < (Async & Abort[ContainerException])

        def write(data: Chunk[Byte])(using Frame): Unit < (Async & Abort[ContainerException])

        def read(using Frame): Stream[LogEntry, Async & Abort[ContainerException]]

        def resize(width: Int, height: Int)(using Frame): Unit < (Async & Abort[ContainerException])
    end AttachSession

    // --- Info ---

    /** Detailed inspection result for a running or stopped container. */
    case class Info(
        id: Id,
        name: String,
        image: ContainerImage,
        imageId: ContainerImage.Id,
        state: State,
        exitCode: ExitCode,
        pid: Int,
        startedAt: Maybe[Instant],
        finishedAt: Maybe[Instant],
        healthStatus: HealthStatus,
        ports: Chunk[Config.PortBinding],
        mounts: Chunk[Config.Mount],
        labels: Dict[String, String],
        env: Dict[String, String],
        command: String,
        createdAt: Instant,
        restartCount: Int,
        driver: String,
        platform: Platform,
        networkSettings: Info.NetworkSettings
    ) derives CanEqual

    object Info:
        case class NetworkSettings(
            ipAddress: Maybe[String],
            gateway: Maybe[String],
            macAddress: Maybe[String],
            networks: Dict[Network.Id, NetworkEndpoint]
        ) derives CanEqual

        case class NetworkEndpoint(
            networkId: Network.Id,
            endpointId: String,
            gateway: String,
            ipAddress: String,
            ipPrefixLen: Int,
            macAddress: String
        ) derives CanEqual
    end Info

    // --- Stats ---

    /** Point-in-time resource usage snapshot. */
    case class Stats(
        readAt: Instant,
        cpu: Stats.Cpu,
        memory: Stats.Memory,
        network: Dict[String, Stats.Net],
        blockIo: Stats.BlockIo,
        pids: Stats.Pids
    ) derives CanEqual

    object Stats:
        case class Cpu(totalUsage: Maybe[Long], systemUsage: Maybe[Long], onlineCpus: Int, usagePercent: Double) derives CanEqual
        case class Memory(usage: Long, maxUsage: Maybe[Long], limit: Maybe[Long], usagePercent: Double) derives CanEqual
        case class Net(
            rxBytes: Long,
            rxPackets: Maybe[Long],
            rxErrors: Maybe[Long],
            rxDropped: Maybe[Long],
            txBytes: Long,
            txPackets: Maybe[Long],
            txErrors: Maybe[Long],
            txDropped: Maybe[Long]
        ) derives CanEqual
        case class BlockIo(readBytes: Long, writeBytes: Long) derives CanEqual
        case class Pids(current: Long, limit: Maybe[Long]) derives CanEqual
    end Stats

    // --- FilesystemChange ---

    /** Filesystem diff entry showing what changed in the container layer. */
    case class FilesystemChange(path: String, kind: FilesystemChange.Kind) derives CanEqual

    object FilesystemChange:
        enum Kind derives CanEqual:
            case Modified, Added, Deleted
    end FilesystemChange

    // --- FileStat ---

    /** Filesystem metadata for a file inside a container. */
    case class FileStat(
        name: String,
        size: Long,
        mode: Int,
        modifiedAt: Instant,
        linkTarget: Maybe[String]
    ) derives CanEqual

    // --- Summary ---

    /** Lightweight container listing entry. */
    case class Summary(
        id: Id,
        names: Chunk[String],
        image: ContainerImage,
        imageId: ContainerImage.Id,
        command: String,
        state: State,
        status: String,
        ports: Chunk[Config.PortBinding],
        labels: Dict[String, String],
        mounts: Chunk[String],
        createdAt: Instant
    ) derives CanEqual:
        /** Attach to this container's full handle for further operations (exec, logs, stats, ...). */
        def attach(using Frame): Container < (Async & Abort[ContainerException]) =
            Container.attach(id)
    end Summary

    // --- Backend selection ---

    /** Controls which container runtime backend to use.
      *
      *   - [[BackendConfig.AutoDetect]]: probe Unix domain sockets (Podman first, then Docker), fall back to shell CLI.
      *   - [[BackendConfig.UnixSocket]]: force the HTTP-over-Unix-socket backend at the given path.
      *   - [[BackendConfig.Shell]]: force the shell CLI backend (binary name like "docker" or "podman").
      *
      * The HTTP (UnixSocket) backend speaks the Docker Engine API directly — no `docker` / `podman` binary required on `PATH`. It still
      * requires `tar` on `PATH` for [[Container.copyTo]] / [[Container.copyFrom]] (to pack/unpack the tar archives the Docker API expects)
      * and for [[ContainerImage.buildFromPath]] (to archive the build context before POSTing to `/build`).
      */
    enum BackendConfig derives CanEqual:
        case AutoDetect(
            meter: Meter = Meter.Noop,
            apiVersion: String = internal.HttpContainerBackend.defaultApiVersion,
            streamBufferSize: Int = internal.ShellBackend.defaultStreamBufferSize
        )
        case UnixSocket(
            path: Path,
            meter: Meter = Meter.Noop,
            apiVersion: String = internal.HttpContainerBackend.defaultApiVersion
        )
        case Shell(
            command: String,
            meter: Meter = Meter.Noop,
            streamBufferSize: Int = internal.ShellBackend.defaultStreamBufferSize
        )
    end BackendConfig

    object BackendConfig:
        /** Alias for `UnixSocket(kyo.Path(path))` — accepts a bare String for convenience. */
        def UnixSocket(path: String): BackendConfig = BackendConfig.UnixSocket(kyo.Path(path))
    end BackendConfig

    // --- Network ---

    /** Container network operations — create, inspect, connect, disconnect, remove. */
    object Network:

        /** Unique network identifier. */
        opaque type Id = String
        object Id:
            given CanEqual[Id, Id] = CanEqual.derived

            given Render[Id] = Render.from(_.value)

            def apply(value: String): Id           = value
            extension (self: Id) def value: String = self
        end Id

        /** Network creation configuration. */
        case class Config(
            name: String,
            driver: NetworkDriver,
            internal: Boolean,
            attachable: Boolean,
            enableIPv6: Boolean,
            labels: Dict[String, String],
            options: Dict[String, String],
            ipam: Maybe[IpamConfig]
        ) derives CanEqual:
            def driver(d: NetworkDriver): Config = copy(driver = d)

            def internal(v: Boolean): Config = copy(internal = v)

            def attachable(v: Boolean): Config = copy(attachable = v)

            def enableIPv6(v: Boolean): Config = copy(enableIPv6 = v)

            def label(key: String, value: String): Config = copy(labels = labels.update(key, value))

            def labels(l: Dict[String, String]): Config = copy(labels = labels ++ l)

            def option(key: String, value: String): Config = copy(options = options.update(key, value))

            def options(o: Dict[String, String]): Config = copy(options = options ++ o)

            def ipam(i: IpamConfig): Config = copy(ipam = Present(i))
        end Config

        object Config:
            /** Default Network Config — arbitrary placeholder name. */
            val default: Config = new Config(
                name = "",
                driver = NetworkDriver.Bridge,
                internal = false,
                attachable = true,
                enableIPv6 = false,
                labels = Dict.empty,
                options = Dict.empty,
                ipam = Absent
            )
        end Config

        case class IpamConfig(
            driver: String,
            config: Chunk[IpamPool]
        ) derives CanEqual

        object IpamConfig:
            /** Default IpamConfig. */
            val default: IpamConfig = new IpamConfig(driver = "default", config = Chunk.empty)
        end IpamConfig

        case class IpamPool(
            subnet: Maybe[String],
            gateway: Maybe[String],
            ipRange: Maybe[String]
        ) derives CanEqual

        object IpamPool:
            /** Default IpamPool — all fields `Absent`. */
            val default: IpamPool = new IpamPool(subnet = Absent, gateway = Absent, ipRange = Absent)
        end IpamPool

        /** Network inspection result. */
        case class Info(
            id: Id,
            name: String,
            driver: NetworkDriver,
            scope: String,
            internal: Boolean,
            attachable: Boolean,
            enableIPv6: Boolean,
            labels: Dict[String, String],
            options: Dict[String, String],
            containers: Dict[Container.Id, Container.Info.NetworkEndpoint],
            createdAt: Instant
        ) derives CanEqual

        /** Create a Docker/Podman network whose lifecycle is tied to the enclosing `Scope`. When the scope closes, the network is removed
          * (best-effort — logs a warning if remove fails).
          */
        def init(config: Config)(using Frame): Id < (Async & Abort[ContainerException] & Scope) =
            currentBackend.map { b =>
                b.networkCreate(config).map { id =>
                    Scope.ensure {
                        Abort.run[ContainerException](b.networkRemove(id)).unit
                    }.andThen(id)
                }
            }

        /** Create a scope-managed network and run `f` with its id. The network is removed when the enclosing scope closes. */
        def initWith[A, S](config: Config)(f: Id => A < S)(using Frame): A < (S & Async & Abort[ContainerException] & Scope) =
            init(config).map(f)

        /** Create a network without scope cleanup — caller must call `remove` manually.
          *
          * Use for long-lived networks shared across multiple scopes or tests. Prefer [[init]] when the network's lifetime should match the
          * enclosing scope.
          */
        def initUnscoped(config: Config)(using Frame): Id < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkCreate(config))

        /** Create an unscoped network and run `f` with its id. Caller manages cleanup via `remove`. */
        def initUnscopedWith[A, S](config: Config)(f: Id => A < S)(using Frame): A < (S & Async & Abort[ContainerException]) =
            initUnscoped(config).map(f)

        /** List all networks. */
        def list(using Frame): Chunk[Info] < (Async & Abort[ContainerException]) =
            list(filters = Dict.empty)

        /** List networks matching the given filters. */
        def list(
            filters: Filters
        )(using Frame): Chunk[Info] < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkList(filters))

        /** Full network metadata. */
        def inspect(id: Id)(using Frame): Info < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkInspect(id))

        /** Remove a network by ID. */
        def remove(id: Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkRemove(id))

        /** Attach a container to a network, optionally with DNS aliases. */
        def connect(
            network: Id,
            container: Container.Id,
            aliases: Chunk[String] = Chunk.empty
        )(using Frame): Unit < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkConnect(network, container, aliases))

        /** Detach a container from a network. */
        def disconnect(
            network: Id,
            container: Container.Id,
            force: Boolean = false
        )(using Frame): Unit < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkDisconnect(network, container, force))

        /** Remove all unused networks. */
        def prune(using Frame): Chunk[Id] < (Async & Abort[ContainerException]) =
            prune(filters = Dict.empty)

        /** Remove unused networks matching the given filters. */
        def prune(
            filters: Filters
        )(using Frame): Chunk[Id] < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkPrune(filters))

    end Network

    // --- Volume ---

    /** Container volume operations — create, inspect, remove. */
    object Volume:

        /** Unique volume identifier. */
        opaque type Id = String
        object Id:
            given CanEqual[Id, Id] = CanEqual.derived

            given Render[Id] = Render.from(_.value)

            def apply(value: String): Id           = value
            extension (self: Id) def value: String = self
        end Id

        /** Volume creation configuration. */
        case class Config(
            name: Maybe[Volume.Id],
            driver: String,
            driverOpts: Dict[String, String],
            labels: Dict[String, String]
        ) derives CanEqual

        object Config:
            /** Default Volume Config — no name, driver `local`. */
            val default: Config = new Config(
                name = Absent,
                driver = "local",
                driverOpts = Dict.empty,
                labels = Dict.empty
            )
        end Config

        /** Volume inspection result. */
        case class Info(
            name: Volume.Id,
            driver: String,
            mountpoint: String,
            labels: Dict[String, String],
            options: Dict[String, String],
            createdAt: Instant,
            scope: String
        ) derives CanEqual

        /** Create a Docker/Podman volume whose lifecycle is tied to the enclosing `Scope`. When the scope closes, the volume is removed
          * (best-effort — logs a warning if remove fails).
          */
        def init(config: Config)(using Frame): Id < (Async & Abort[ContainerException] & Scope) =
            currentBackend.map { b =>
                b.volumeCreate(config).map { info =>
                    Scope.ensure {
                        Abort.run[ContainerException](b.volumeRemove(info.name, force = false)).unit
                    }.andThen(info.name)
                }
            }

        /** Create a scope-managed volume and run `f` with its id. The volume is removed when the enclosing scope closes. */
        def initWith[A, S](config: Config)(f: Id => A < S)(using Frame): A < (S & Async & Abort[ContainerException] & Scope) =
            init(config).map(f)

        /** Create a volume without scope cleanup — caller must call `remove` manually.
          *
          * Returns full [[Info]] (including the daemon-assigned name, mountpoint, and driver options). Use for long-lived volumes shared
          * across multiple scopes or tests. Prefer [[init]] when the volume's lifetime should match the enclosing scope.
          */
        def initUnscoped(config: Config)(using Frame): Info < (Async & Abort[ContainerException]) =
            currentBackend.map(_.volumeCreate(config))

        /** Create an unscoped volume and run `f` with its full info. Caller manages cleanup via `remove`. */
        def initUnscopedWith[A, S](config: Config)(f: Info => A < S)(using Frame): A < (S & Async & Abort[ContainerException]) =
            initUnscoped(config).map(f)

        /** List all volumes. */
        def list(using Frame): Chunk[Info] < (Async & Abort[ContainerException]) =
            list(filters = Dict.empty)

        /** List volumes matching the given filters. */
        def list(
            filters: Filters
        )(using Frame): Chunk[Info] < (Async & Abort[ContainerException]) =
            currentBackend.map(_.volumeList(filters))

        /** Full volume metadata (driver, mountpoint, labels, etc.). */
        def inspect(id: Volume.Id)(using Frame): Info < (Async & Abort[ContainerException]) =
            currentBackend.map(_.volumeInspect(id))

        /** Remove a volume. If `force` is true, removes even if in use. */
        def remove(id: Volume.Id, force: Boolean = false)(using Frame): Unit < (Async & Abort[ContainerException]) =
            currentBackend.map(_.volumeRemove(id, force))

        /** Remove all unused volumes. */
        def prune(using Frame): PruneResult < (Async & Abort[ContainerException]) =
            prune(filters = Dict.empty)

        /** Remove unused volumes matching the given filters. */
        def prune(
            filters: Filters
        )(using Frame): PruneResult < (Async & Abort[ContainerException]) =
            currentBackend.map(_.volumePrune(filters))

    end Volume

    // --- Signal enum ---

    /** Signals that can be sent to a container process. */
    enum Signal derives CanEqual:
        case SIGTERM, SIGKILL, SIGINT, SIGHUP, SIGUSR1, SIGUSR2, SIGQUIT, SIGSTOP
        case Custom(signalName: String)
    end Signal

    object Signal:
        given Render[Signal] = Render.from(_.name)
        extension (self: Signal)

            def name: String = self match
                case Custom(n) => n
                case other     => other.toString
        end extension
    end Signal

    // --- Capability enum ---

    /** Linux capabilities for container security configuration. */
    enum Capability derives CanEqual:
        /** Change file ownership */
        case Chown

        /** Control kernel auditing */
        case AuditControl

        /** Read audit log */
        case AuditRead

        /** Write audit records */
        case AuditWrite

        /** Block system suspend */
        case BlockSuspend

        /** BPF operations */
        case Bpf

        /** Checkpoint/restore operations */
        case CheckpointRestore

        /** Bypass file permission checks */
        case DacOverride

        /** Bypass read/search permission on files */
        case DacReadSearch

        /** Bypass ownership checks on file operations */
        case Fowner

        /** Don't clear set-user/group-ID bits on file modification */
        case Fsetid

        /** Lock memory (mlock, mlockall) */
        case IpcLock

        /** Bypass IPC permission checks */
        case IpcOwner

        /** Send signals to any process */
        case Kill

        /** Set file leases */
        case Lease

        /** Set immutable and append-only file attributes */
        case LinuxImmutable

        /** MAC configuration changes */
        case MacAdmin

        /** Override MAC policy */
        case MacOverride

        /** Create special files (mknod) */
        case Mknod

        /** Network administration and configuration */
        case NetAdmin

        /** Bind to privileged ports (below 1024) */
        case NetBindService

        /** Network broadcasting */
        case NetBroadcast

        /** Use raw and packet sockets */
        case NetRaw

        /** Performance monitoring */
        case Perfmon

        /** Set file capabilities */
        case Setfcap

        /** Set group ID for process */
        case Setgid

        /** Transfer capabilities between processes */
        case Setpcap

        /** Set user ID for process */
        case Setuid

        /** Broad system administration (mount, sethostname, etc.) */
        case SysAdmin

        /** Reboot the system */
        case SysBoot

        /** Use chroot */
        case SysChroot

        /** Load and unload kernel modules */
        case SysModule

        /** Set process scheduling priority (nice, setpriority) */
        case SysNice

        /** Configure process accounting */
        case SysPacct

        /** Trace and debug processes (ptrace) */
        case SysPtrace

        /** Perform raw I/O port operations (iopl, ioperm) */
        case SysRawio

        /** Override resource limits (ulimit) */
        case SysResource

        /** Set the system clock */
        case SysTime

        /** Configure TTY devices */
        case SysTtyConfig

        /** Perform syslog operations */
        case Syslog

        /** Set wake alarms */
        case WakeAlarm

        /** Custom capability not in this enum */
        case Custom(capName: String)
    end Capability

    object Capability:
        given Render[Capability] = Render.from(_.cliName)
        extension (self: Capability)

            def cliName: String = self match
                case Custom(n) => n
                case other     =>
                    // CamelCase -> UPPER_SNAKE: NetAdmin -> NET_ADMIN
                    other.toString.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase
        end extension
    end Capability

    // --- HealthStatus enum ---

    /** Health status reported by container runtime health checks. */
    enum HealthStatus derives CanEqual:
        case Healthy, Unhealthy, Starting, NoHealthcheck
    end HealthStatus

    object HealthStatus:
        given Render[HealthStatus] = Render.from(_.toString)

        def parse(s: String): HealthStatus = s.toLowerCase match
            case "healthy"   => Healthy
            case "unhealthy" => Unhealthy
            case "starting"  => Starting
            case _           => NoHealthcheck
    end HealthStatus

    // --- Platform type ---

    /** Target platform for container images (OS/architecture/variant). */
    case class Platform(os: String, arch: String, variant: Maybe[String]) derives CanEqual:
        def reference: String = variant.map(v => s"$os/$arch/$v").getOrElse(s"$os/$arch")
    end Platform

    object Platform:
        given Render[Platform] = Render.from(_.reference)

        /** Default Platform — arbitrary placeholder os/arch, no variant. */
        val default: Platform = new Platform(os = "", arch = "", variant = Absent)

        def parse(ref: String): Result[String, Platform] =
            val parts = ref.split("/")
            parts.length match
                case 2 => Result.succeed(Platform(parts(0), parts(1), Absent))
                case 3 => Result.succeed(Platform(parts(0), parts(1), Present(parts(2))))
                case _ => Result.fail(s"Invalid platform: $ref")
            end match
        end parse

        def apply(os: String, arch: String): Platform =
            default.copy(os = os, arch = arch)

        // Linux
        val LinuxAmd64    = Platform("linux", "amd64")
        val LinuxArm64    = Platform("linux", "arm64")
        val LinuxArmV7    = Platform("linux", "arm", Present("v7"))
        val LinuxArmV6    = Platform("linux", "arm", Present("v6"))
        val Linux386      = Platform("linux", "386")
        val LinuxPpc64le  = Platform("linux", "ppc64le")
        val LinuxS390x    = Platform("linux", "s390x")
        val LinuxRiscv64  = Platform("linux", "riscv64")
        val LinuxMips64le = Platform("linux", "mips64le")
        // Windows
        val WindowsAmd64 = Platform("windows", "amd64")
        // WebAssembly
        val WasiWasm = Platform("wasi", "wasm")
    end Platform

    // --- NetworkDriver enum ---

    /** Network driver types for container networking. */
    enum NetworkDriver derives CanEqual:
        /** Default bridge network */
        case Bridge

        /** Host networking (no isolation) */
        case Host

        /** Multi-host overlay network (Swarm) */
        case Overlay

        /** MAC VLAN -- assigns MAC addresses to containers */
        case Macvlan

        /** IP VLAN -- shares host's MAC but assigns IPs */
        case IPvlan

        /** No networking */
        case None

        /** Custom or plugin driver */
        case Custom(driverName: String)
    end NetworkDriver

    object NetworkDriver:
        given Render[NetworkDriver] = Render.from(_.cliName)

        def parse(s: String): NetworkDriver = s.toLowerCase match
            case "bridge"  => Bridge
            case "host"    => Host
            case "overlay" => Overlay
            case "macvlan" => Macvlan
            case "ipvlan"  => IPvlan
            case "none"    => None
            case other     => Custom(other)
        extension (self: NetworkDriver)

            def cliName: String = self match
                case Custom(n) => n
                case None      => "none"
                case other     => other.toString.toLowerCase
        end extension
    end NetworkDriver

    given CanEqual[Container, Container] = CanEqual.derived

    // --- Private internals ---

    /** Cap on retained per-health-check error messages — keeps the diagnostic buffer bounded under long retry loops. */
    private val healthCheckRecentErrorsCapacity: Int = 5

    /** Cap on each health-check error message length before it enters the diagnostic buffer. Prevents a single huge exception from
      * dominating the final failure report.
      */
    private val healthCheckErrorMessageMaxLength: Int = 500

    /** Threshold (milliseconds) above which elapsed-time strings switch from "Nms" to "Ns" formatting. */
    private val elapsedMillisFormatThreshold: Long = 1000L

    /** Default upper bound on lines returned by the buffered log APIs (`logs`, `logsText`, `logStream`). Prevents accidental OOM when
      * called on long-running containers with megabytes of history. Pass an explicit `tail` to override; pass `Int.MaxValue` for unbounded.
      */
    val defaultLogTail: Int = 1000

    private val backendLocal: Local[Maybe[ContainerBackend]] = Local.init(Absent)

    private[kyo] def currentBackend(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        backendLocal.get.map {
            case Present(b) => b
            case Absent     => ContainerBackend.detect()
        }

    private def resolveBackend(config: BackendConfig)(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        config match
            case BackendConfig.AutoDetect(meter, apiVersion, streamBufferSize) =>
                ContainerBackend.detect(meter, apiVersion, streamBufferSize)
            case BackendConfig.UnixSocket(path, meter, apiVersion) =>
                val backend = new HttpContainerBackend(path.toString, apiVersion, meter)
                backend.detect().andThen(backend)
            case BackendConfig.Shell(cmd, meter, streamBufferSize) =>
                val backend = new ShellBackend(cmd, meter, streamBufferSize)
                backend.detect().andThen(backend)

    private def runHealthCheck(container: Container, hc: HealthCheck)(using Frame): Unit < (Async & Abort[ContainerException]) =
        // If the container already exited (e.g. command("true")), skip health check
        Abort.runWith[ContainerException](container.backend.state(container.id)) {
            case Result.Success(st) if st != State.Running && st != State.Created =>
                () // Container not running, skip health check
            case Result.Failure(_: ContainerMissingException) =>
                () // Container was auto-removed
            case _ =>
                // Container is running, run health check with retry.
                // Accumulate errors for diagnostic reporting on final failure.
                Clock.now.map { startTime =>
                    def loop(
                        retrySchedule: Schedule,
                        attempts: Int,
                        recentErrors: Seq[String]
                    ): Unit < (Async & Abort[ContainerException]) =
                        Abort.runWith[ContainerException](hc.check(container)) {
                            case Result.Success(_) =>
                                container.healthState.set(ContainerHealthState(true, Present(hc)))
                            case failure =>
                                val errorMsg = failure match
                                    case Result.Failure(e) => e.getMessage.take(healthCheckErrorMessageMaxLength)
                                    case _                 => "unknown error"
                                val updatedErrors =
                                    if recentErrors.length >= healthCheckRecentErrorsCapacity then recentErrors.tail :+ errorMsg
                                    else recentErrors :+ errorMsg
                                val nextAttempts = attempts + 1
                                // Check if container is still running before retrying
                                Abort.runWith[ContainerException](container.backend.state(container.id)) {
                                    case Result.Success(st) if st != State.Running && st != State.Created =>
                                        () // Container exited during health check, not a failure
                                    case Result.Failure(_: ContainerMissingException) =>
                                        () // Container was auto-removed during health check, not a failure
                                    case _ =>
                                        Clock.now.map { now =>
                                            retrySchedule.next(now) match
                                                case Present((delay, nextSchedule)) =>
                                                    Async.sleep(delay).andThen(loop(nextSchedule, nextAttempts, updatedErrors))
                                                case Absent =>
                                                    val elapsed = now.toJava.toEpochMilli - startTime.toJava.toEpochMilli
                                                    val elapsedStr =
                                                        if elapsed >= elapsedMillisFormatThreshold then s"${elapsed / 1000}s"
                                                        else s"${elapsed}ms"
                                                    val errorsStr = updatedErrors.map(e => s"[$e]").mkString(" ")
                                                    Abort.fail(ContainerHealthCheckException(
                                                        container.id,
                                                        s"retry schedule exhausted in $elapsedStr",
                                                        attempts = nextAttempts,
                                                        lastError = errorsStr
                                                    ))
                                        }
                                }
                        }
                    loop(hc.schedule, 0, Seq.empty)
                }
        }

end Container
