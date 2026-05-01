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

    final def isHealthy(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException]) =
        state(id).map(_ == Container.State.Running)

    // --- Inspection ---

    def inspect(id: Container.Id)(using Frame): Container.Info < (Async & Abort[ContainerException])

    def state(id: Container.Id)(using Frame): Container.State < (Async & Abort[ContainerException])

    def stats(id: Container.Id)(using Frame): Container.Stats < (Async & Abort[ContainerException])

    final def statsStream(id: Container.Id)(using Frame): Stream[Container.Stats, Async & Abort[ContainerException]] =
        statsStream(id, 200.millis)

    final def statsStream(id: Container.Id, interval: Duration)(using Frame): Stream[Container.Stats, Async & Abort[ContainerException]] =
        Stream {
            Loop(()) { _ =>
                stats(id).map { s =>
                    Emit.value(Chunk(s)).andThen {
                        Async.sleep(interval).andThen(Loop.continue(()))
                    }
                }
            }
        }

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

    final def connectToNetwork(id: Container.Id, networkId: Container.Network.Id, aliases: Chunk[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        networkConnect(networkId, id, aliases)

    final def disconnectFromNetwork(id: Container.Id, networkId: Container.Network.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        networkDisconnect(networkId, id, force)

    // --- Container listing ---

    def list(all: Boolean, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Summary] < (Async & Abort[ContainerException])

    def prune(filters: Dict[String, Chunk[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException])

    final def attachById(idOrName: Container.Id)(using Frame): (Container.Id, Container.Config) < (Async & Abort[ContainerException]) =
        inspect(idOrName).map { info =>
            val config0 = Container.Config.default.copy(
                image = info.image,
                name = if info.name.nonEmpty then Present(info.name) else Absent,
                labels = info.labels,
                ports = info.ports,
                mounts = info.mounts
            )
            val config =
                if info.command.isEmpty then config0
                else
                    val args = Chunk.from(info.command.split(" ").iterator.filter(_.nonEmpty).toSeq)
                    val cmd  = if info.env.isEmpty then Command(args*) else Command(args*).envAppend(info.env.toMap)
                    config0.command(cmd)
            (info.id, config)
        }

    // --- Image operations ---

    def imagePull(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])

    final def imageEnsure(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        Abort.recover[ContainerException](
            (_: ContainerException) => imagePull(image, platform, auth),
            (_: Throwable) => imagePull(image, platform, auth)
        ) {
            imageInspect(image).unit
        }

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

    final def imageBuild(
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
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]] =
        Stream {
            Abort.fail[ContainerException](
                ContainerNotSupportedException("imageBuild with stream context", "Use imageBuildFromPath instead")
            ).unit
        }

    /** After a build stream completes, confirm the daemon produced an image for the first tag.
      *
      * Inspecting the tagged image post-build gives an API-level ground truth that works uniformly across builder variants. When no tag was
      * requested, nothing is verified — the caller implicitly opted into trusting the event stream.
      */
    final private[kyo] def verifyBuildProduced(tags: Chunk[String])(using Frame): Unit < (Async & Abort[ContainerException]) =
        tags.headOption match
            case None => Kyo.unit
            case Some(tag) =>
                Abort.run[ContainerException](imageInspect(ContainerImage(tag))).map {
                    case Result.Success(_) => ()
                    case Result.Failure(_: ContainerImageMissingException) =>
                        Abort.fail(ContainerBuildFailedException(
                            tag,
                            "image tag not found after build — possibly silent failure",
                            new RuntimeException(s"tagged image '$tag' absent after build")
                        ))
                    case Result.Failure(e) => Abort.fail(e)
                    case Result.Panic(e) =>
                        Abort.fail(ContainerBuildFailedException(tag, "unexpected error verifying build", e))
                }

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
        ContainerBackend.registryAuthFromConfig

    // --- Backend detection ---

    def detect()(using Frame): Unit < (Async & Abort[ContainerException])

    /** Returns a human-readable description of this backend — includes transport details (socket path, API version, or CLI binary). */
    def describe: String
end ContainerBackend

private[kyo] object ContainerBackend:

    /** Parse a container state string (from Docker/Podman API) to the State enum.
      *
      * `configured` and `initialized` are podman-specific pre-start states that map to `Created`. Unknown states default to `Stopped` since
      * that is the safest assumption — the alternative (treating unknowns as `Running`) would mask real failures.
      */
    def parseState(s: String): Container.State =
        s.toLowerCase match
            case "created"                    => Container.State.Created
            case "configured" | "initialized" => Container.State.Created
            case "running"                    => Container.State.Running
            case "paused"                     => Container.State.Paused
            case "restarting"                 => Container.State.Restarting
            case "removing"                   => Container.State.Removing
            case "exited" | "stopped"         => Container.State.Stopped
            case "dead"                       => Container.State.Dead
            case _                            => Container.State.Stopped

    /** Returns `Absent` for states where the container hasn't exited yet (Created, Running, Paused, Restarting, Removing), and
      * `Present(ExitCode)` for terminal states (Stopped, Dead). This makes the type truthful — a running container doesn't have an exit
      * code.
      */
    def exitCodeForState(state: Container.State, rawCode: Int): Maybe[ExitCode] =
        state match
            case Container.State.Created | Container.State.Running |
                Container.State.Paused | Container.State.Restarting |
                Container.State.Removing =>
                Absent
            case Container.State.Stopped | Container.State.Dead =>
                Present(ExitCode(rawCode))

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

    /** Parse an ISO-8601 timestamp string to an Instant, returning Instant.Epoch for empty or unparseable strings.
      *
      * Convenience wrapper around [[parseInstant]] that unwraps the Maybe with Epoch as the fallback.
      */
    def parseInstantOrEpoch(s: String): Instant =
        parseInstant(Option(s).filter(_.nonEmpty)).getOrElse(Instant.Epoch)

    /** Convert a Unix epoch-second Long to an Instant, returning Instant.Epoch for non-positive values.
      *
      * Applies a `> 0` guard so that zero (unset) and negative (invalid) timestamps are treated as Epoch rather than mapping them to the
      * literal 1970 epoch or before. Instant.Epoch == Instant.fromJava(java.time.Instant.EPOCH) — confirmed by definition in kyo-data.
      */
    def fromEpochSecond(s: Long): Instant =
        if s > 0 then Instant.fromJava(java.time.Instant.ofEpochSecond(s)) else Instant.Epoch

    /** Load registry credentials from the local Docker/Podman config.
      *
      * Checks (in order) `~/.docker/config.json`, `$XDG_RUNTIME_DIR/containers/auth.json`, and `$DOCKER_CONFIG/config.json`. Returns an
      * empty [[ContainerImage.RegistryAuth]] if none of the paths exist or if parsing fails.
      */
    private[kyo] def registryAuthFromConfig(using Frame): ContainerImage.RegistryAuth < (Async & Abort[ContainerException]) =
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
                                        case Result.Failure(_) =>
                                            ContainerImage.RegistryAuth(Dict.empty)
                                        case Result.Panic(t) =>
                                            Log.warn(s"unexpected panic decoding auth config JSON: $t").andThen(
                                                ContainerImage.RegistryAuth(Dict.empty)
                                            )
                                    end match
                                case Result.Failure(_) =>
                                    ContainerImage.RegistryAuth(Dict.empty)
                                case Result.Panic(t) =>
                                    Log.warn(s"unexpected error reading auth config: $t").andThen(
                                        ContainerImage.RegistryAuth(Dict.empty)
                                    )
                            }
                        case Absent =>
                            ContainerImage.RegistryAuth(Dict.empty)
                    }
                }
            }
        }
    end registryAuthFromConfig

    /** Run a CLI command, apply a transform to its stdout, and return the result.
      *
      * Redirects stderr to stdout so that error noise does not contaminate the output captured for parsing. Returns `Absent` when:
      *   - the command exits with a non-zero code, or
      *   - the command throws a `CommandException` (binary not found, permission denied, …), or
      *   - the `transform` returns `Absent`.
      *
      * `Result.Panic` (unexpected JVM-level errors) is logged at DEBUG level and also returns `Absent` so callers never see raw exceptions.
      *
      * @param cmd
      *   Command to run; stderr is merged into stdout via `redirectErrorStream(true)`.
      * @param transform
      *   Function applied to `stdout.trim` on exit-code-success; returns the parsed value or `Absent` if the output is not usable.
      */
    private[kyo] def runCliQuery[A](cmd: Command, transform: String => Maybe[A])(using Frame): Maybe[A] < Async =
        Abort.run[CommandException](cmd.redirectErrorStream(true).textWithExitCode).map {
            case Result.Success((output, ExitCode.Success)) => transform(output.trim)
            case Result.Success((_, _))                     => Absent
            case Result.Failure(_: CommandException)        => Absent
            case Result.Panic(ex) =>
                Log.debug(s"CLI command failed unexpectedly: ${ex.getMessage}").andThen(
                    Absent: Maybe[A]
                )
        }
    end runCliQuery

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

    final private[internal] case class AuthConfigJson(
        auths: Option[Map[String, String]] = None
    ) derives Schema

end ContainerBackend

final private[kyo] case class ContainerHealthState(check: Maybe[Container.HealthCheck]) derives CanEqual
