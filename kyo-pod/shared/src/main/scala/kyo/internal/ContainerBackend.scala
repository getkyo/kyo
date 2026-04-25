package kyo.internal

import kyo.*

/** Abstract contract that container runtime backends implement.
  *
  * Users don't interact with this directly. Container and ContainerImage delegate all operations to the active backend. Auto-detected via
  * `ContainerBackend.detect` which probes Unix domain sockets. Override with `Container.withBackendConfig` to force a specific runtime.
  *
  * @see
  *   [[HttpContainerBackend]] HTTP-based implementation via Unix domain socket
  * @see
  *   [[kyo.Container.BackendConfig]] Configuration for backend selection
  */
abstract private[kyo] class ContainerBackend(val meter: Meter):

    // --- Container Lifecycle ---

    def create(config: Container.Config)(using Frame): Container.Id < (Async & Abort[ContainerException])

    def start(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException])

    def stop(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException])

    def kill(id: Container.Id, signal: Container.Signal)(using Frame): Unit < (Async & Abort[ContainerException])

    def restart(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException])

    def pause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException])

    def unpause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException])

    def remove(id: Container.Id, force: Boolean, removeVolumes: Boolean)(using Frame): Unit < (Async & Abort[ContainerException])

    def rename(id: Container.Id, newName: String)(using Frame): Unit < (Async & Abort[ContainerException])

    def waitForExit(id: Container.Id)(using Frame): ExitCode < (Async & Abort[ContainerException])

    // --- Checkpoint/Restore ---

    def checkpoint(id: Container.Id, name: String)(using Frame): Unit < (Async & Abort[ContainerException])

    def restore(id: Container.Id, checkpoint: String)(using Frame): Unit < (Async & Abort[ContainerException])

    // --- Health ---

    def isHealthy(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException])

    // --- Inspection ---

    def inspect(id: Container.Id)(using Frame): Container.Info < (Async & Abort[ContainerException])

    def state(id: Container.Id)(using Frame): Container.State < (Async & Abort[ContainerException])

    def stats(id: Container.Id)(using Frame): Container.Stats < (Async & Abort[ContainerException])

    def statsStream(id: Container.Id)(using Frame): Stream[Container.Stats, Async & Abort[ContainerException]]

    def statsStream(id: Container.Id, interval: Duration)(using Frame): Stream[Container.Stats, Async & Abort[ContainerException]]

    def top(id: Container.Id, psArgs: String)(using Frame): Container.TopResult < (Async & Abort[ContainerException])

    def changes(id: Container.Id)(using Frame): Chunk[Container.FilesystemChange] < (Async & Abort[ContainerException])

    // --- Exec ---

    def exec(id: Container.Id, command: Command)(using Frame): Container.ExecResult < (Async & Abort[ContainerException])

    def execStream(id: Container.Id, command: Command)(
        using Frame
    ): Stream[Container.LogEntry, Async & Abort[ContainerException]]

    def execInteractive(id: Container.Id, command: Command)(
        using Frame
    ): Container.AttachSession < (Async & Abort[ContainerException] & Scope)

    // --- Attach ---

    def attach(id: Container.Id, stdin: Boolean, stdout: Boolean, stderr: Boolean)(
        using Frame
    ): Container.AttachSession < (Async & Abort[ContainerException] & Scope)

    // --- Logs ---

    def logs(
        id: Container.Id,
        stdout: Boolean,
        stderr: Boolean,
        since: Instant,
        until: Instant,
        timestamps: Boolean,
        tail: Int
    )(using Frame): Chunk[Container.LogEntry] < (Async & Abort[ContainerException])

    def logStream(
        id: Container.Id,
        stdout: Boolean,
        stderr: Boolean,
        since: Instant,
        timestamps: Boolean,
        tail: Int
    )(using Frame): Stream[Container.LogEntry, Async & Abort[ContainerException]]

    // --- File ops ---

    def copyTo(id: Container.Id, source: Path, containerPath: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    def copyFrom(id: Container.Id, containerPath: Path, destination: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    def stat(id: Container.Id, containerPath: Path)(using Frame): Container.FileStat < (Async & Abort[ContainerException])

    def exportFs(id: Container.Id)(using Frame): Stream[Byte, Async & Abort[ContainerException]]

    // --- Resource updates ---

    def update(
        id: Container.Id,
        memory: Maybe[Long],
        memorySwap: Maybe[Long],
        cpuLimit: Maybe[Double],
        cpuAffinity: Maybe[String],
        maxProcesses: Maybe[Long],
        restartPolicy: Maybe[Container.Config.RestartPolicy]
    )(using Frame): Unit < (Async & Abort[ContainerException])

    // --- Network on container ---

    def connectToNetwork(id: Container.Id, networkId: Container.Network.Id, aliases: Chunk[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    def disconnectFromNetwork(id: Container.Id, networkId: Container.Network.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    // --- Container listing ---

    def list(all: Boolean, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Summary] < (Async & Abort[ContainerException])

    def prune(filters: Dict[String, Chunk[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException])

    def attachById(idOrName: Container.Id)(using Frame): (Container.Id, Container.Config) < (Async & Abort[ContainerException])

    // --- Image operations ---

    def imagePull(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    def imageEnsure(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    def imagePullWithProgress(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Stream[ContainerImage.PullProgress, Async & Abort[ContainerException]]

    def imageList(all: Boolean, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[ContainerImage.Summary] < (Async & Abort[ContainerException])

    def imageInspect(image: ContainerImage)(using Frame): ContainerImage.Info < (Async & Abort[ContainerException])

    def imageRemove(image: ContainerImage, force: Boolean, noPrune: Boolean)(
        using Frame
    ): Chunk[ContainerImage.DeleteResponse] < (Async & Abort[ContainerException])

    def imageTag(source: ContainerImage, repo: String, tag: String)(using Frame): Unit < (Async & Abort[ContainerException])

    def imageBuild(
        context: Stream[Byte, Sync],
        dockerfile: String,
        tags: Chunk[String],
        buildArgs: Dict[String, String],
        labels: Dict[String, String],
        noCache: Boolean,
        pull: Boolean,
        target: Maybe[String],
        platform: Maybe[Container.Platform],
        auth: Maybe[ContainerImage.RegistryAuth]
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]]

    def imageBuildFromPath(
        path: Path,
        dockerfile: String,
        tags: Chunk[String],
        buildArgs: Dict[String, String],
        labels: Dict[String, String],
        noCache: Boolean,
        pull: Boolean,
        target: Maybe[String],
        platform: Maybe[Container.Platform],
        auth: Maybe[ContainerImage.RegistryAuth]
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]]

    def imagePush(image: ContainerImage, auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    def imageSearch(term: String, limit: Int, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[ContainerImage.SearchResult] < (Async & Abort[ContainerException])

    def imageHistory(image: ContainerImage)(using Frame): Chunk[ContainerImage.HistoryEntry] < (Async & Abort[ContainerException])

    def imagePrune(filters: Dict[String, Chunk[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException])

    def imageCommit(
        container: Container.Id,
        repo: String,
        tag: String,
        comment: String,
        author: String,
        pause: Boolean
    )(using Frame): String < (Async & Abort[ContainerException])

    // --- Network operations ---

    def networkCreate(config: Container.Network.Config)(using Frame): Container.Network.Id < (Async & Abort[ContainerException])

    def networkList(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Network.Info] < (Async & Abort[ContainerException])

    def networkInspect(id: Container.Network.Id)(using Frame): Container.Network.Info < (Async & Abort[ContainerException])

    def networkRemove(id: Container.Network.Id)(using Frame): Unit < (Async & Abort[ContainerException])

    def networkConnect(network: Container.Network.Id, container: Container.Id, aliases: Chunk[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    def networkDisconnect(network: Container.Network.Id, container: Container.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    def networkPrune(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Network.Id] < (Async & Abort[ContainerException])

    // --- Volume operations ---

    def volumeCreate(config: Container.Volume.Config)(using Frame): Container.Volume.Info < (Async & Abort[ContainerException])

    def volumeList(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Volume.Info] < (Async & Abort[ContainerException])

    def volumeInspect(id: Container.Volume.Id)(using Frame): Container.Volume.Info < (Async & Abort[ContainerException])

    def volumeRemove(id: Container.Volume.Id, force: Boolean)(using Frame): Unit < (Async & Abort[ContainerException])

    def volumePrune(filters: Dict[String, Chunk[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException])

    // --- RegistryAuth ---

    final def registryAuthFromConfig(using Frame): ContainerImage.RegistryAuth < (Async & Abort[ContainerException]) =
        // Try docker config first, then podman
        System.property[String]("user.home").map { userHome =>
            System.env[String]("XDG_RUNTIME_DIR").map { xdg =>
                System.env[String]("DOCKER_CONFIG").map { dockerCfg =>
                    val configPaths = Seq(
                        userHome.map(_ + "/.docker/config.json"),
                        xdg.map(_ + "/containers/auth.json"),
                        dockerCfg.map(_ + "/config.json")
                    ).flatMap(m => m.fold(Seq.empty[String])(Seq(_)))

                    def findExisting(paths: Seq[String]): Maybe[String] < Sync =
                        if paths.isEmpty then Absent
                        else
                            val head = paths.head
                            Path(head).exists.map { exists =>
                                if exists then Present(head)
                                else findExisting(paths.tail)
                            }

                    findExisting(configPaths).map {
                        case Present(configPath) =>
                            Abort.run[FileReadException](Path(configPath).read).map {
                                case Result.Success(content) =>
                                    Json.decode[ContainerBackend.AuthConfigJson](content) match
                                        case Result.Success(dto) =>
                                            ContainerImage.RegistryAuth(Dict.from(dto.auths.getOrElse(Map.empty).map { case (k, v) =>
                                                ContainerImage.Registry(k) -> v
                                            }))
                                        case _ => ContainerImage.RegistryAuth(Dict.empty)
                                    end match
                                case _ =>
                                    ContainerImage.RegistryAuth(Dict.empty)
                            }
                        case Absent =>
                            ContainerImage.RegistryAuth(Dict.empty)
                    }
                }
            }
        }
    end registryAuthFromConfig

    // --- Backend detection ---

    def detect()(using Frame): Unit < (Async & Abort[ContainerException])

    /** Returns a human-readable description of this backend — includes transport details (socket path, API version, or CLI binary). */
    def describe: String
end ContainerBackend

private[kyo] object ContainerBackend:

    /** Parse a container state string (from Docker/Podman API) to the State enum. */
    def parseState(s: String): Container.State =
        s.toLowerCase match
            case "created"            => Container.State.Created
            case "running"            => Container.State.Running
            case "paused"             => Container.State.Paused
            case "restarting"         => Container.State.Restarting
            case "removing"           => Container.State.Removing
            case "exited" | "stopped" => Container.State.Stopped
            case "dead"               => Container.State.Dead
            case _                    => Container.State.Stopped

    /** Parse an ISO-8601 timestamp string to an Instant. Returns Absent for None, empty, or zero-value timestamps. Handles both Docker
      * format (Z suffix) and Podman format (timezone offset like -07:00).
      */
    def parseInstant(s: Option[String]): Maybe[Instant] =
        s match
            case None | Some("") | Some("0001-01-01T00:00:00Z") => Absent
            case Some(v) =>
                Instant.parse(v).toMaybe.orElse {
                    Result.catching[java.time.format.DateTimeParseException](
                        Instant.fromJava(java.time.OffsetDateTime.parse(v).toInstant)
                    ).toMaybe
                }

    /** Auto-detect an available container runtime.
      *
      * Tries HTTP backend via Unix domain socket first, falls back to ShellBackend via CLI.
      */
    def detect(
        meter: Meter = Meter.Noop,
        apiVersion: String = HttpContainerBackend.defaultApiVersion,
        streamBufferSize: Int = ShellBackend.defaultStreamBufferSize
    )(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        Abort.run[ContainerException](HttpContainerBackend.detect(meter, apiVersion)).map {
            case Result.Success(backend) => backend: ContainerBackend
            case Result.Failure(_)       => detectShell(meter, streamBufferSize)
            case Result.Panic(ex) =>
                Log.warn("Unexpected error during HTTP backend detection", ex).andThen(
                    detectShell(meter, streamBufferSize)
                )
        }

    private def detectShell(meter: Meter, streamBufferSize: Int)(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        ShellBackend.detect(meter, streamBufferSize).map(b => b: ContainerBackend)

    private[internal] case class AuthConfigJson(
        auths: Option[Map[String, String]] = None
    ) derives Schema

end ContainerBackend

private[kyo] case class ContainerHealthState(healthy: Boolean, check: Maybe[Container.HealthCheck]) derives CanEqual
