package kyo

import kyo.internal.ContainerBackend
import kyo.internal.ContainerHealthState
import kyo.internal.HttpContainerBackend
import kyo.internal.ShellBackend

/** A handle to a running or stopped OS container managed by Docker or Podman.
  *
  * Create containers via `Container.init` (scoped lifecycle) or `Container.use` (bracket semantics). On scope close the container receives
  * SIGTERM for graceful shutdown, then is force-removed. The backend is auto-detected at first use (Podman preferred, then Docker) and can
  * be overridden per-scope with `Container.withBackend`.
  *
  * Typical use cases: integration testing, sidecar services, job runners, batch processing, CI/CD pipelines, and dynamic scaling scenarios
  * where containers are created and torn down programmatically.
  *
  * Health checks run in a background fiber with configurable retry schedules. Use `HealthCheck.exec`, `HealthCheck.port`,
  * `HealthCheck.httpGet`, or `HealthCheck.log` for common patterns, or `HealthCheck.init` for custom logic.
  *
  * '''Warning''': The container is force-removed on scope exit. Use `Container.initUnscoped` for containers that must outlive a scope, and
  * manage their lifecycle manually via `stop` and `remove`.
  *
  * @see
  *   [[Container.init]] Scoped container creation with automatic cleanup
  * @see
  *   [[Container.use]] Bracket-style create/use/cleanup without Scope in effect set
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

    /** Start a created container. */
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
                    // No health check configured, check running state
                    backend.isHealthy(id)
                case Present(hc) =>
                    // Re-run the health check using the check's own schedule for retries
                    Abort.recover[ContainerException] { (_: ContainerException) =>
                        healthState.set(ContainerHealthState(false, Present(hc))).andThen(false)
                    } {
                        Retry[ContainerException](hc.schedule) {
                            hc.check(this)
                        }.andThen {
                            healthState.set(ContainerHealthState(true, Present(hc))).andThen(true)
                        }
                    }
            end match
        }
    end isHealthy

    // --- Inspection ---

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

    /** WebSocket-based attach for bidirectional I/O.
      *
      * Note: Not supported by the shell backend. Returns a failure immediately. Requires an HTTP backend implementation.
      */
    def attachWebSocket(using Frame): AttachSession < (Async & Abort[ContainerException] & Scope) =
        attachWebSocket(stdin = true, stdout = true, stderr = true)

    /** WebSocket-based attach selecting which streams to connect. Not yet implemented; always fails with NotSupported. */
    def attachWebSocket(
        stdin: Boolean,
        stdout: Boolean,
        stderr: Boolean
    )(using Frame): AttachSession < (Async & Abort[ContainerException] & Scope) =
        Abort.fail(ContainerException.NotSupported("WebSocket attach", "Not supported by the shell backend"))

    // --- Logs ---

    /** Structured log entries with source (stdout/stderr) detection. */
    def logs(using Frame): Chunk[LogEntry] < (Async & Abort[ContainerException]) =
        logs(stdout = true, stderr = true, since = Instant.Min, until = Instant.Max, tail = Int.MaxValue, timestamps = false)

    /** Filter logs by stream, time range, and tail count. When `timestamps` is true, each entry is prefixed with an ISO timestamp. */
    def logs(
        stdout: Boolean,
        stderr: Boolean = true,
        since: Instant = Instant.Min,
        until: Instant = Instant.Max,
        tail: Int = Int.MaxValue,
        timestamps: Boolean = false
    )(using Frame): Chunk[LogEntry] < (Async & Abort[ContainerException]) =
        backend.logs(id, stdout, stderr, since, until, tail, timestamps)

    /** Returns logs as raw text content for backward compatibility. */
    def logsText(using Frame): String < (Async & Abort[ContainerException]) =
        logsText(stdout = true, stderr = true, since = Instant.Min, until = Instant.Max, tail = Int.MaxValue, timestamps = false)

    /** Same filters as `logs`, but returns raw text instead of structured LogEntry values. */
    def logsText(
        stdout: Boolean,
        stderr: Boolean = true,
        since: Instant = Instant.Min,
        until: Instant = Instant.Max,
        tail: Int = Int.MaxValue,
        timestamps: Boolean = false
    )(using Frame): String < (Async & Abort[ContainerException]) =
        logs(stdout, stderr, since, until, tail, timestamps).map { entries =>
            entries.map(_.content).toSeq.mkString("\n")
        }

    /** Continuous log stream that emits new entries as they are produced. */
    def logStream(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
        logStream(stdout = true, stderr = true, since = Instant.Min, tail = Int.MaxValue, timestamps = false)

    /** Continuous log tail with filtering. When `follow` is implicit via the stream, new entries are emitted as they arrive. */
    def logStream(
        stdout: Boolean,
        stderr: Boolean = true,
        since: Instant = Instant.Min,
        tail: Int = Int.MaxValue,
        timestamps: Boolean = false
    )(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
        backend.logStream(id, stdout, stderr, since, tail, timestamps)

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

    /** Update resource limits on a running container without restarting it. No-arg overload is a no-op. */
    def update(using Frame): Unit < (Async & Abort[ContainerException]) =
        update(Absent)

    /** Update resource limits on a running container without restarting it. Only provided fields are changed. */
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
    def connectToNetwork(networkId: Network.Id, aliases: Seq[String] = Seq.empty)(using
        Frame
    ): Unit < (Async & Abort[ContainerException]) =
        backend.connectToNetwork(id, networkId, aliases)

    /** Detach this container from a network. */
    def disconnectFromNetwork(networkId: Network.Id, force: Boolean = false)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.disconnectFromNetwork(id, networkId, force)

    // --- Checkpoint/Restore ---

    /** CRIU-based checkpoint: freeze container state to disk. Requires CRIU installed on the host. */
    def checkpoint(name: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        backend.checkpoint(id, name)

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

    // --- Entry points ---

    /** Create and start a scoped container. Automatically stopped and removed on scope exit. */
    def init(config: Config)(using Frame): Container < (Async & Abort[ContainerException] & Scope) =
        currentBackend.map { b =>
            backendLocal.let(Present(b)) {
                b.create(config).map { cid =>
                    b.start(cid).map { _ =>
                        AtomicRef.init(ContainerHealthState(false, Absent)).map { healthRef =>
                            val container = new Container(cid, config, b, healthRef)
                            Scope.ensure {
                                val shutdown = config.stopSignal match
                                    case Present(signal) =>
                                        Abort.run[ContainerException](b.kill(cid, signal)).unit
                                    case Absent =>
                                        Abort.run[ContainerException](b.stop(cid, config.stopTimeout)).unit
                                shutdown.andThen(Abort.run[ContainerException](b.remove(cid, force = true, removeVolumes = false)).unit)
                            }.andThen {
                                runHealthCheck(container, config.healthCheck).andThen(container)
                            }
                        }
                    }
                }
            }
        }

    /** Convenience overload: create and start a scoped container from an image with optional ports. */
    def init(
        image: ContainerImage,
        command: Command = Command("sh", "-c", "trap 'exit 0' TERM; sleep infinity"),
        name: Maybe[String] = Absent,
        ports: Chunk[(Int, Int)] = Chunk.empty,
        healthCheck: HealthCheck = HealthCheck.default
    )(using Frame): Container < (Async & Abort[ContainerException] & Scope) =
        val portBindings = Chunk.from(ports.map { case (container, host) => Config.PortBinding(container, host) })
        val config = Config(
            image = image,
            command = command,
            name = name,
            ports = portBindings,
            healthCheck = healthCheck
        )
        init(config)
    end init

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
                b.create(config).map { cid =>
                    b.start(cid).andThen {
                        val container = new Container(cid, config, b, healthRef)
                        runHealthCheck(container, config.healthCheck).andThen(container)
                    }
                }
            }
        }

    /** Bracket: create a container, apply `f`, then stop and remove. No Scope needed. */
    def use[A, S](config: Config)(f: Container => A < S)(using Frame): A < (Async & Abort[ContainerException] & S) =
        Scope.run {
            init(config).map(f)
        }

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
        list(all = false, filters = Map.empty)

    /** List containers. When `all` is true, includes stopped containers. */
    def list(
        all: Boolean,
        filters: Map[String, Seq[String]] = Map.empty
    )(using Frame): Chunk[Summary] < (Async & Abort[ContainerException]) =
        currentBackend.map(_.list(all, filters))

    /** Remove all stopped containers. */
    def prune(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        prune(filters = Map.empty)

    /** Remove all stopped containers matching the given filters. */
    def prune(
        filters: Map[String, Seq[String]]
    )(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        currentBackend.map(_.prune(filters))

    /** Override backend detection (Docker/Podman/socket) for all container operations in the enclosed block. */
    def withBackend[A, S](config: BackendConfig)(v: => A < S)(using Frame): A < (Async & Abort[ContainerException] & S) =
        resolveBackend(config).map(b => backendLocal.let(Present(b))(v))

    /** Override backend detection (Docker/Podman/socket) for all container operations in the enclosed block. */
    def withBackend[A, S](f: BackendConfig.type => BackendConfig)(v: => A < S)(using Frame): A < (Async & Abort[ContainerException] & S) =
        withBackend(f(BackendConfig))(v)

    given CanEqual[Container, Container] = CanEqual.derived

    // --- Typed Primitives ---

    opaque type Id = String
    object Id:
        given CanEqual[Id, Id]                 = CanEqual.derived
        given Render[Id]                       = Render.from(_.value)
        def apply(value: String): Id           = value
        extension (self: Id) def value: String = self
    end Id

    // --- Config ---

    case class Config(
        image: ContainerImage,
        command: Command = Command("sh", "-c", "trap 'exit 0' TERM; sleep infinity"),
        name: Maybe[String] = Absent,
        hostname: Maybe[String] = Absent,
        user: Maybe[String] = Absent,
        labels: Map[String, String] = Map.empty,
        ports: Chunk[Config.PortBinding] = Chunk.empty,
        mounts: Chunk[Config.Mount] = Chunk.empty,
        networkMode: Maybe[Config.NetworkMode] = Absent,
        dns: Seq[String] = Seq.empty,
        extraHosts: Chunk[Config.ExtraHost] = Chunk.empty,
        // Resource limits
        memory: Maybe[Long] = Absent,
        memorySwap: Maybe[Long] = Absent,
        cpuLimit: Maybe[Double] = Absent,
        cpuAffinity: Maybe[String] = Absent,
        maxProcesses: Maybe[Long] = Absent,
        // Security
        privileged: Boolean = false,
        addCapabilities: Chunk[Capability] = Chunk.empty,
        dropCapabilities: Chunk[Capability] = Chunk.empty,
        readOnlyFilesystem: Boolean = false,
        // Behavior
        interactive: Boolean = false,
        allocateTty: Boolean = false,
        autoRemove: Boolean = false,
        restartPolicy: Config.RestartPolicy = Config.RestartPolicy.No,
        stopSignal: Maybe[Signal] = Absent,
        stopTimeout: Duration = 3.seconds,
        healthCheck: HealthCheck = HealthCheck.default
    ) derives CanEqual:
        // --- Builder methods ---

        def command(cmd: Command): Config             = copy(command = cmd)
        def command(cmd: String*): Config             = copy(command = Command(cmd*))
        def name(n: String): Config                   = copy(name = Present(n))
        def hostname(h: String): Config               = copy(hostname = Present(h))
        def user(u: String): Config                   = copy(user = Present(u))
        def labels(l: Map[String, String]): Config    = copy(labels = labels ++ l)
        def label(key: String, value: String): Config = copy(labels = labels + (key -> value))

        // Ports
        def port(container: Int, host: Int): Config =
            copy(ports = ports.append(Config.PortBinding(container, host)))
        def port(container: Int): Config =
            copy(ports = ports.append(Config.PortBinding(container)))
        def port(binding: Config.PortBinding): Config =
            copy(ports = ports.append(binding))

        // Mounts
        def mount(m: Config.Mount): Config                      = copy(mounts = mounts.append(m))
        def mount(f: Config.Mount.type => Config.Mount): Config = copy(mounts = mounts.append(f(Config.Mount)))
        def bind(source: Path, target: Path, readOnly: Boolean = false): Config =
            copy(mounts = mounts.append(Config.Mount.Bind(source, target, readOnly)))
        def volume(name: Volume.Id, target: Path, readOnly: Boolean = false): Config =
            copy(mounts = mounts.append(Config.Mount.Volume(name, target, readOnly)))
        def tmpfs(target: Path): Config =
            copy(mounts = mounts.append(Config.Mount.Tmpfs(target)))

        // Network
        def networkMode(mode: Config.NetworkMode): Config = copy(networkMode = Present(mode))
        def networkMode(f: Config.NetworkMode.type => Config.NetworkMode): Config =
            copy(networkMode = Present(f(Config.NetworkMode)))
        def dns(servers: String*): Config = copy(dns = dns.concat(Chunk.from(servers)))
        def extraHost(hostname: String, ip: String): Config =
            copy(extraHosts = extraHosts.append(Config.ExtraHost(hostname, ip)))

        // Resource limits
        def memory(bytes: Long): Config           = copy(memory = Present(bytes))
        def memorySwap(bytes: Long): Config       = copy(memorySwap = Present(bytes))
        def cpuLimit(cpus: Double): Config        = copy(cpuLimit = Present(cpus))
        def cpuAffinity(affinity: String): Config = copy(cpuAffinity = Present(affinity))
        def maxProcesses(limit: Long): Config     = copy(maxProcesses = Present(limit))

        // Security
        def privileged(value: Boolean): Config          = copy(privileged = value)
        def addCapabilities(caps: Capability*): Config  = copy(addCapabilities = addCapabilities.concat(Chunk.from(caps)))
        def dropCapabilities(caps: Capability*): Config = copy(dropCapabilities = dropCapabilities.concat(Chunk.from(caps)))
        def readOnlyFilesystem(value: Boolean): Config  = copy(readOnlyFilesystem = value)

        // Behavior
        def interactive(value: Boolean): Config                 = copy(interactive = value)
        def allocateTty(value: Boolean): Config                 = copy(allocateTty = value)
        def autoRemove(value: Boolean): Config                  = copy(autoRemove = value)
        def restartPolicy(policy: Config.RestartPolicy): Config = copy(restartPolicy = policy)
        def restartPolicy(f: Config.RestartPolicy.type => Config.RestartPolicy): Config =
            copy(restartPolicy = f(Config.RestartPolicy))
        def stopSignal(signal: Signal): Config     = copy(stopSignal = Present(signal))
        def stopTimeout(timeout: Duration): Config = copy(stopTimeout = timeout)
        def healthCheck(hc: HealthCheck): Config   = copy(healthCheck = hc)

    end Config

    object Config:

        def apply(image: ContainerImage): Config = new Config(image = image)
        def apply(image: String): Config         = new Config(image = ContainerImage.parse(image).getOrElse(ContainerImage(image)))

        case class PortBinding(
            containerPort: Int,
            hostPort: Int = 0,
            hostIp: String = "",
            protocol: Protocol = Protocol.TCP
        ) derives CanEqual

        sealed trait Mount
        object Mount:
            case class Bind(source: Path, target: Path, readOnly: Boolean = false)                extends Mount derives CanEqual
            case class Volume(name: Container.Volume.Id, target: Path, readOnly: Boolean = false) extends Mount derives CanEqual
            case class Tmpfs(target: Path, sizeBytes: Maybe[Long] = Absent)                       extends Mount derives CanEqual
        end Mount

        enum Protocol derives CanEqual:
            case TCP, UDP, SCTP

        enum NetworkMode derives CanEqual:
            case Bridge, Host, None
            case Shared(containerId: Container.Id)
            case Custom(name: String)
        end NetworkMode

        enum RestartPolicy derives CanEqual:
            case No
            case Always
            case UnlessStopped
            case OnFailure(maxRetries: Int)
        end RestartPolicy

        case class ExtraHost(hostname: String, ip: String) derives CanEqual

    end Config

    // --- HealthCheck ---

    abstract class HealthCheck private[kyo]:
        def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException])
        def schedule: Schedule
    end HealthCheck

    object HealthCheck:

        val defaultSchedule: Schedule = Schedule.fixed(500.millis).take(30)

        /** Create a custom health check from a function and retry schedule. */
        def init(
            check: Container => Unit < (Async & Abort[ContainerException]),
            schedule: Schedule = defaultSchedule
        ): HealthCheck =
            val fn  = check
            val sch = schedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    fn(container)
                def schedule: Schedule = sch
            end new
        end init

        lazy val default: HealthCheck = exec(Command("echo", "ok"))

        /** Health check that runs a command inside the container. Passes if exit code is 0 and output matches `expected` (when provided).
          */
        def exec(
            command: Command = Command("echo", "ok"),
            expected: Maybe[String] = Present("ok"),
            schedule: Schedule = defaultSchedule
        ): HealthCheck =
            val cmd = command
            val exp = expected
            val sch = schedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    container.exec(cmd).map { result =>
                        if !result.isSuccess then
                            Abort.fail(ContainerException.General(
                                s"Health check failed for container ${container.id.value}: exec exit code ${result.exitCode}",
                                s"exit code: ${result.exitCode}"
                            ))
                        else
                            exp match
                                case Present(e) =>
                                    if !result.stdout.trim.contains(e) then
                                        Abort.fail(ContainerException.General(
                                            s"Health check failed for container ${container.id.value}: expected '$e' in output",
                                            s"expected '$e' in output"
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
            exec(Command(command*), Absent, defaultSchedule)

        /** Health check that searches container logs for the given message. Fails if the message is not found. */
        def log(
            message: String,
            schedule: Schedule = defaultSchedule
        ): HealthCheck =
            val msg = message
            val sch = schedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    container.logsText.map { output =>
                        if !output.contains(msg) then
                            Abort.fail(ContainerException.General(
                                s"Health check failed for container ${container.id.value}: expected '$msg' in logs",
                                s"expected '$msg' in logs"
                            ))
                        else ()
                    }
                def schedule: Schedule = sch
            end new
        end log

        /** Health check that verifies a TCP port is reachable inside the container (via `nc -z`). */
        def port(
            port: Int,
            schedule: Schedule = defaultSchedule
        ): HealthCheck =
            val p   = port
            val sch = schedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    container.exec("nc", "-z", "localhost", p.toString).map { result =>
                        if !result.isSuccess then
                            Abort.fail(ContainerException.General(
                                s"Health check failed for container ${container.id.value}: port $p not reachable",
                                s"port $p not reachable"
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
            schedule: Schedule = defaultSchedule
        ): HealthCheck =
            val p   = port
            val pth = path
            val sch = schedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    container.exec("wget", "-q", "-O", "/dev/null", "-S", s"http://localhost:$p$pth").map { result =>
                        if !result.isSuccess then
                            Abort.fail(ContainerException.General(
                                s"Health check failed for container ${container.id.value}: HTTP GET http://localhost:$p$pth failed",
                                s"HTTP GET http://localhost:$p$pth failed"
                            ))
                        else ()
                    }
                def schedule: Schedule = sch
            end new
        end httpGet

        /** Composite health check: all checks must pass. Uses the schedule of the first check. */
        def all(checks: HealthCheck*): HealthCheck =
            val chs = checks
            val sch = if chs.isEmpty then defaultSchedule else chs.head.schedule
            new HealthCheck:
                def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                    Kyo.foreach(chs)(_.check(container)).unit
                def schedule: Schedule = sch
            end new
        end all

        def apply(schedule: Schedule)(f: Container => Unit < (Async & Abort[ContainerException])): HealthCheck =
            init(f, schedule)

    end HealthCheck

    // --- State ---

    enum State derives CanEqual:
        case Created, Running, Paused, Restarting, Removing, Stopped, Dead

    object State:
        given Render[State] = Render.from(_.toString)
    end State

    // --- TopResult ---

    case class TopResult(titles: Seq[String], processes: Seq[Seq[String]]) derives CanEqual

    // --- PruneResult ---

    case class PruneResult(deleted: Seq[String], spaceReclaimed: Long) derives CanEqual

    // --- ExecResult ---

    case class ExecResult(exitCode: ExitCode, stdout: String, stderr: String) derives CanEqual:
        def isSuccess: Boolean = exitCode.isSuccess
    end ExecResult

    // --- LogEntry ---

    case class LogEntry(source: LogEntry.Source, content: String, timestamp: Maybe[Instant] = Absent) derives CanEqual

    object LogEntry:
        enum Source derives CanEqual:
            case Stdout, Stderr
    end LogEntry

    // --- AttachSession ---

    // CanEqual not derived — contains function-like methods (write, read, resize)
    abstract class AttachSession:
        def write(data: String)(using Frame): Unit < (Async & Abort[ContainerException])
        def write(data: Chunk[Byte])(using Frame): Unit < (Async & Abort[ContainerException])
        def read(using Frame): Stream[LogEntry, Async & Abort[ContainerException]]
        def resize(width: Int, height: Int)(using Frame): Unit < (Async & Abort[ContainerException])
    end AttachSession

    // --- Info ---

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
        labels: Map[String, String],
        env: Map[String, String],
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
            networks: Map[Network.Id, NetworkEndpoint]
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

    case class Stats(
        readAt: Instant,
        cpu: Stats.Cpu,
        memory: Stats.Memory,
        network: Map[String, Stats.Net],
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

    case class FilesystemChange(path: String, kind: FilesystemChange.Kind) derives CanEqual

    object FilesystemChange:
        enum Kind derives CanEqual:
            case Modified, Added, Deleted
    end FilesystemChange

    // --- FileStat ---

    case class FileStat(
        name: String,
        size: Long,
        mode: Int,
        modifiedAt: Instant,
        linkTarget: Maybe[String]
    ) derives CanEqual

    // --- Summary ---

    case class Summary(
        id: Id,
        names: Seq[String],
        image: ContainerImage,
        imageId: ContainerImage.Id,
        command: String,
        state: State,
        status: String,
        ports: Chunk[Config.PortBinding],
        labels: Map[String, String],
        mounts: Seq[String],
        createdAt: Instant
    ) derives CanEqual

    // --- Backend selection ---
    // Default is auto-detect. Users can override with `Container.withBackend`.

    enum BackendConfig derives CanEqual:
        case AutoDetect
        case UnixSocket(path: Path)
        case Shell(command: String)
    end BackendConfig

    // --- Network ---

    object Network:

        opaque type Id = String
        object Id:
            given CanEqual[Id, Id]                 = CanEqual.derived
            given Render[Id]                       = Render.from(_.value)
            def apply(value: String): Id           = value
            extension (self: Id) def value: String = self
        end Id

        case class Config(
            name: String,
            driver: NetworkDriver = NetworkDriver.Bridge,
            internal: Boolean = false,
            attachable: Boolean = true,
            enableIPv6: Boolean = false,
            labels: Map[String, String] = Map.empty,
            options: Map[String, String] = Map.empty,
            ipam: Maybe[IpamConfig] = Absent
        ) derives CanEqual

        case class IpamConfig(
            driver: String = "default",
            config: Chunk[IpamPool] = Chunk.empty
        ) derives CanEqual

        case class IpamPool(
            subnet: Maybe[String] = Absent,
            gateway: Maybe[String] = Absent,
            ipRange: Maybe[String] = Absent
        ) derives CanEqual

        case class Info(
            id: Id,
            name: String,
            driver: NetworkDriver,
            scope: String,
            internal: Boolean,
            attachable: Boolean,
            enableIPv6: Boolean,
            labels: Map[String, String],
            options: Map[String, String],
            containers: Map[Container.Id, Container.Info.NetworkEndpoint],
            createdAt: Instant
        ) derives CanEqual

        /** Create a new Docker/Podman network. Returns the network ID. */
        def create(config: Config)(using Frame): Id < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkCreate(config))

        /** List all networks. */
        def list(using Frame): Chunk[Info] < (Async & Abort[ContainerException]) =
            list(filters = Map.empty)

        /** List networks matching the given filters. */
        def list(
            filters: Map[String, Seq[String]]
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
            aliases: Seq[String] = Seq.empty
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
            prune(filters = Map.empty)

        /** Remove unused networks matching the given filters. */
        def prune(
            filters: Map[String, Seq[String]]
        )(using Frame): Chunk[Id] < (Async & Abort[ContainerException]) =
            currentBackend.map(_.networkPrune(filters))

    end Network

    // --- Volume ---

    object Volume:

        opaque type Id = String
        object Id:
            given CanEqual[Id, Id]                 = CanEqual.derived
            given Render[Id]                       = Render.from(_.value)
            def apply(value: String): Id           = value
            extension (self: Id) def value: String = self
        end Id

        case class Config(
            name: Maybe[Volume.Id] = Absent,
            driver: String = "local",
            driverOpts: Map[String, String] = Map.empty,
            labels: Map[String, String] = Map.empty
        ) derives CanEqual

        case class Info(
            name: Volume.Id,
            driver: String,
            mountpoint: String,
            labels: Map[String, String],
            options: Map[String, String],
            createdAt: Instant,
            scope: String
        ) derives CanEqual

        /** Create a new volume. Returns full volume info including the generated name. */
        def create(config: Config)(using Frame): Info < (Async & Abort[ContainerException]) =
            currentBackend.map(_.volumeCreate(config))

        /** List all volumes. */
        def list(using Frame): Chunk[Info] < (Async & Abort[ContainerException]) =
            list(filters = Map.empty)

        /** List volumes matching the given filters. */
        def list(
            filters: Map[String, Seq[String]]
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
            prune(filters = Map.empty)

        /** Remove unused volumes matching the given filters. */
        def prune(
            filters: Map[String, Seq[String]]
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
    case class Platform(os: String, arch: String, variant: Maybe[String] = Absent) derives CanEqual:
        def reference: String = variant.map(v => s"$os/$arch/$v").getOrElse(s"$os/$arch")
    end Platform

    object Platform:
        given Render[Platform] = Render.from(_.reference)

        def parse(ref: String): Result[String, Platform] =
            val parts = ref.split("/")
            parts.length match
                case 2 => Result.succeed(Platform(parts(0), parts(1)))
                case 3 => Result.succeed(Platform(parts(0), parts(1), Present(parts(2))))
                case _ => Result.fail(s"Invalid platform: $ref")
            end match
        end parse

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

    // --- Private internals ---

    private val backendLocal: Local[Maybe[ContainerBackend]] = Local.init(Absent)

    private def currentBackend(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        backendLocal.get.map {
            case Present(b) => b
            case Absent     => ContainerBackend.detect()
        }

    private def resolveBackend(config: BackendConfig)(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        config match
            case BackendConfig.AutoDetect =>
                ContainerBackend.detect()
            case BackendConfig.UnixSocket(path) =>
                val backend = new HttpContainerBackend(path.toString)
                backend.detect().andThen(backend)
            case BackendConfig.Shell(cmd) =>
                val backend = new ShellBackend(cmd)
                backend.detect().andThen(backend)

    private def runHealthCheck(container: Container, hc: HealthCheck)(using Frame): Unit < (Async & Abort[ContainerException]) =
        // If the container already exited (e.g. command("true")), skip health check
        Abort.runWith[ContainerException](container.backend.state(container.id)) {
            case Result.Success(st) if st != State.Running && st != State.Created =>
                () // Container not running, skip health check
            case Result.Failure(_: ContainerException.NotFound) =>
                () // Container was auto-removed
            case _ =>
                // Container is running, run health check with retry.
                // Accumulate errors for diagnostic reporting on final failure.
                Clock.now.map { startTime =>
                    def loop(
                        schedule: Schedule,
                        attempts: Int,
                        recentErrors: Seq[String]
                    ): Unit < (Async & Abort[ContainerException]) =
                        Abort.runWith[ContainerException](hc.check(container)) {
                            case Result.Success(_) =>
                                container.healthState.set(ContainerHealthState(true, Present(hc)))
                            case failure =>
                                val errorMsg = failure match
                                    case Result.Failure(e) => e.getMessage.take(100)
                                    case _                 => "unknown error"
                                // Keep last 5 errors for diagnostics
                                val updatedErrors =
                                    if recentErrors.length >= 5 then recentErrors.tail :+ errorMsg
                                    else recentErrors :+ errorMsg
                                val nextAttempts = attempts + 1
                                // Check if container is still running before retrying
                                Abort.runWith[ContainerException](container.backend.state(container.id)) {
                                    case Result.Success(st) if st != State.Running && st != State.Created =>
                                        () // Container exited during health check, not a failure
                                    case _ =>
                                        Clock.now.map { now =>
                                            schedule.next(now) match
                                                case Present((delay, nextSchedule)) =>
                                                    Async.sleep(delay).andThen(loop(nextSchedule, nextAttempts, updatedErrors))
                                                case Absent =>
                                                    val elapsed = now.toJava.toEpochMilli - startTime.toJava.toEpochMilli
                                                    val elapsedStr =
                                                        if elapsed >= 1000 then s"${elapsed / 1000}s"
                                                        else s"${elapsed}ms"
                                                    val errorsStr = updatedErrors.map(e => s"[$e]").mkString(" ")
                                                    val msg = s"Health check failed for container ${container.id.value} " +
                                                        s"after $nextAttempts attempts ($elapsedStr). Last errors: $errorsStr"
                                                    Abort.fail(ContainerException.General(msg, "health check exhausted"))
                                        }
                                }
                        }
                    loop(hc.schedule, 0, Seq.empty)
                }
        }

    private[kyo] def currentBackendForImage(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        currentBackend

end Container
