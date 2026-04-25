package kyo.internal

import kyo.*
import kyo.internal.ContainerBackend.parseInstant
import kyo.internal.ContainerBackend.parseState
import kyo.internal.ResourceContext

/** HTTP-based container backend that talks to Docker/Podman API via Unix domain socket.
  *
  * Uses `http+unix://` URLs supported by kyo-http to communicate with the Docker Engine API or Podman-compatible API. The socket path is
  * URL-encoded in the authority portion of the URL. All API calls are prefixed with the API version (default `v1.43`).
  *
  * @param socketPath
  *   Absolute path to the Unix domain socket (e.g. `/var/run/docker.sock`)
  * @param apiVersion
  *   Docker Engine API version prefix (e.g. `v1.43`)
  *
  * @see
  *   [[ContainerBackend]] the abstract contract this implements
  * @see
  *   [[HttpContainerBackend.detect]] auto-detection logic
  */
final private[kyo] class HttpContainerBackend(
    socketPath: String,
    private[internal] val apiVersion: String = HttpContainerBackend.defaultApiVersion,
    meter: Meter = Meter.Noop
) extends ContainerBackend(meter):

    import Container.*

    /** Runtime family derived from the socket path — "podman" if the path contains "podman", else "docker". Diagnostic only. */
    private val runtimeName: String =
        if socketPath.contains("podman") then "podman" else "docker"

    /** Builds an `http+unix://` URL for the Docker API.
      *
      * @param path
      *   API path (e.g. `/containers/json`)
      * @param params
      *   Query parameters as key-value pairs
      * @return
      *   Full URL string like `http+unix://%2Fvar%2Frun%2Fdocker.sock/v1.43/containers/json?all=true`
      */
    private[internal] def url(path: String, params: (String, String)*): String =
        val encoded = socketPath.replace("/", "%2F")
        // Java interop boundary: URL-encoding query parameters for Docker API
        val query = if params.isEmpty then "" else "?" + params.map((k, v) => s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}").mkString("&")
        s"http+unix://$encoded/$apiVersion$path$query"
    end url

    // --- Error mapping ---

    /** Maps an HttpException to the appropriate ContainerException subtype based on HTTP status code and resource context. */
    private def mapHttpError(
        httpEx: HttpException,
        ctx: ResourceContext
    )(using Frame): Nothing < Abort[ContainerException] =
        httpEx match
            case e: HttpStatusException =>
                e.status.code match
                    case 304 =>
                        ctx match
                            case ResourceContext.Container(id) => Abort.fail(ContainerAlreadyStoppedException(id))
                            case _ => Abort.fail(ContainerBackendException(s"Unexpected HTTP 304 for ${ctx.describe}", e))
                    case 404 =>
                        ctx match
                            case ResourceContext.Container(id) => Abort.fail(ContainerMissingException(id))
                            case ResourceContext.Image(ref) =>
                                Abort.fail(ContainerImageMissingException(ContainerImage.parse(ref).getOrElse(ContainerImage(ref))))
                            case ResourceContext.Network(id) => Abort.fail(ContainerNetworkMissingException(id))
                            case ResourceContext.Volume(id)  => Abort.fail(ContainerVolumeMissingException(id))
                            case ResourceContext.Op(name) => Abort.fail(ContainerOperationException(s"Resource not found during $name", e))
                    case 409 =>
                        ctx match
                            case ResourceContext.Container(id) => Abort.fail(ContainerAlreadyExistsException(id.value))
                            case ResourceContext.Op(name)      => Abort.fail(ContainerAlreadyExistsException(name))
                            case other                         => Abort.fail(ContainerAlreadyExistsException(other.describe))
                    case _ => Abort.fail(ContainerOperationException(
                            s"Daemon returned HTTP ${e.status.code}${e.body.map(b => s": $b").getOrElse("")} for ${ctx.describe}",
                            e
                        ))
            case other =>
                Abort.fail(ContainerBackendException(s"HTTP transport failed for ${ctx.describe}", other))
        end match
    end mapHttpError

    /** Runs an HTTP call, catching HttpException and mapping to ContainerException. */
    private def withErrorMapping[A](ctx: ResourceContext)(
        v: A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[ContainerException]) =
        Abort.runWith[Closed](meter.run {
            Abort.runWith[HttpException](v) {
                case Result.Success(a) => a
                case Result.Failure(e) => mapHttpError(e, ctx)
                case Result.Panic(e) =>
                    Abort.fail(ContainerBackendException(s"Unexpected error during ${ctx.describe}", e))
            }
        }) {
            case Result.Success(a) => a
            case Result.Failure(_) =>
                Abort.fail(ContainerBackendException(s"Concurrency meter closed during ${ctx.describe}", "meter was closed"))
            case Result.Panic(ex) => Abort.fail(ContainerBackendException(s"Concurrency meter panicked during ${ctx.describe}", ex))
        }

    /** POST with empty body, accepting 200/201/204 as success. */
    private def postUnit(endpoint: String, ctx: ResourceContext)(using Frame): Unit < (Async & Abort[ContainerException]) =
        withErrorMapping(ctx)(HttpClient.postText(url(endpoint), "").unit)

    /** POST with empty body, accepting 200/201/204/304 as success. 304 means "already in this state" — not an error for idempotent
      * operations.
      */
    private def postUnitAccept304(endpoint: String, ctx: ResourceContext)(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.runWith[Closed](meter.run {
            Abort.run[HttpException](HttpClient.postText(url(endpoint), "")).map {
                case Result.Success(_)                                              => ()
                case Result.Failure(e: HttpStatusException) if e.status.code == 304 => ()
                case Result.Failure(e)                                              => mapHttpError(e, ctx)
                case Result.Panic(e) => Abort.fail(ContainerBackendException(s"Unexpected error during ${ctx.describe}", e))
            }
        }) {
            case Result.Success(a) => a
            case Result.Failure(_) =>
                Abort.fail(ContainerBackendException(s"Concurrency meter closed during ${ctx.describe}", "meter was closed"))
            case Result.Panic(ex) => Abort.fail(ContainerBackendException(s"Concurrency meter panicked during ${ctx.describe}", ex))
        }

    /** Build auth headers for registry operations. */
    private def authHeaders(auth: Maybe[ContainerImage.RegistryAuth]): Chunk[(String, String)] =
        auth match
            case Present(ra) =>
                // Encode the first auth entry as X-Registry-Auth header
                Maybe.fromOption(ra.auths.toMap.headOption) match
                    case Present((_, encoded)) => Chunk("X-Registry-Auth" -> encoded)
                    case Absent                => Chunk.empty
            case Absent => Chunk.empty

    // --- Container Lifecycle ---

    def create(config: Config)(using Frame): Container.Id < (Async & Abort[ContainerException]) =
        val envVars = (config.env.toMap ++ config.command.map(_.env).getOrElse(Map.empty)).map { case (k, v) => s"$k=$v" }.toSeq

        val portBindings: Map[String, Seq[PortBindingEntry]] = config.ports.toSeq.groupBy { pb =>
            s"${pb.containerPort}/${pb.protocol.cliName}"
        }.map { case (key, bindings) =>
            key -> bindings.map(pb => PortBindingEntry(pb.hostIp, if pb.hostPort != 0 then pb.hostPort.toString else ""))
        }

        val binds = config.mounts.toSeq.collect {
            case Config.Mount.Bind(source, target, readOnly) =>
                val ro = if readOnly then ":ro" else ""
                s"$source:$target$ro"
            case Config.Mount.Volume(name, target, readOnly) =>
                val ro = if readOnly then ":ro" else ""
                s"${name.value}:$target$ro"
        }

        val tmpfs: Map[String, String] = config.mounts.toSeq.collect {
            case Config.Mount.Tmpfs(target, sizeBytes) =>
                target.toString -> sizeBytes.map(s => s"size=$s").getOrElse("")
        }.toMap

        val networkModeStr: Maybe[String] = config.networkMode.map {
            case Config.NetworkMode.Bridge          => "bridge"
            case Config.NetworkMode.Host            => "host"
            case Config.NetworkMode.None            => "none"
            case Config.NetworkMode.Shared(cid)     => s"container:${cid.value}"
            case Config.NetworkMode.Custom(name, _) => name
        }

        val networkingConfig: Option[NetworkingConfig] = config.networkMode.toOption.flatMap {
            case Config.NetworkMode.Custom(name, aliases) if aliases.nonEmpty =>
                Some(NetworkingConfig(
                    EndpointsConfig = Map(name -> EndpointConfig(Aliases = aliases.toSeq))
                ))
            case _ => None
        }

        val restartPol = config.restartPolicy match
            case Config.RestartPolicy.OnFailure(retries) => RestartPolicyEntry(config.restartPolicy.cliName, retries)
            case _                                       => RestartPolicyEntry(config.restartPolicy.cliName, 0)

        val hostConfig = HostConfig(
            Binds = if binds.nonEmpty then binds else Seq.empty,
            PortBindings = if portBindings.nonEmpty then portBindings else Map.empty,
            NetworkMode = networkModeStr.getOrElse(""),
            Dns = config.dns.toSeq,
            ExtraHosts = config.extraHosts.toSeq.map(eh => s"${eh.hostname}:${eh.ip}"),
            Memory = config.memory.getOrElse(0L),
            MemorySwap = config.memorySwap.getOrElse(0L),
            NanoCPUs = config.cpuLimit.map(c => (c * 1e9).toLong).getOrElse(0L),
            CpusetCpus = config.cpuAffinity.getOrElse(""),
            PidsLimit = config.maxProcesses.getOrElse(0L),
            Privileged = config.privileged,
            CapAdd = config.addCapabilities.toSeq.map(_.cliName),
            CapDrop = config.dropCapabilities.toSeq.map(_.cliName),
            ReadonlyRootfs = config.readOnlyFilesystem,
            AutoRemove = config.autoRemove,
            RestartPolicy = restartPol,
            Mounts = Seq.empty,
            Tmpfs = tmpfs
        )

        val exposedPorts: Option[Map[String, Map[String, String]]] =
            if portBindings.isEmpty then None
            else Some(portBindings.keys.map(_ -> Map.empty[String, String]).toMap)

        val body = CreateContainerRequest(
            Image = config.image.reference,
            Cmd = config.command.map(_.args.toSeq).toOption,
            Env = if envVars.nonEmpty then envVars else Seq.empty,
            Hostname = config.hostname.getOrElse(""),
            User = config.user.getOrElse(""),
            Labels = config.labels.toMap,
            Tty = config.allocateTty,
            OpenStdin = config.interactive,
            StopSignal = config.stopSignal.map(_.name).getOrElse(""),
            WorkingDir = config.command.flatMap(_.workDir).map(_.toString).getOrElse(""),
            ExposedPorts = exposedPorts,
            HostConfig = hostConfig,
            NetworkingConfig = networkingConfig
        )

        val params        = config.name.map(n => Seq("name" -> n)).getOrElse(Seq.empty)
        val nameForErrors = config.name.getOrElse("new-container")

        Abort.runWith[HttpException](
            HttpClient.postJson[CreateContainerResponse](url("/containers/create", params*), body)
        ) {
            case Result.Success(resp) => Container.Id(resp.Id)
            case Result.Failure(e: HttpStatusException) =>
                e.status.code match
                    case 404 => Abort.fail(ContainerImageMissingException(config.image))
                    case 409 => Abort.fail(ContainerAlreadyExistsException(nameForErrors))
                    case _ => Abort.fail(ContainerOperationException(
                            s"Daemon returned HTTP ${e.status.code}${e.body.map(b => s": $b").getOrElse("")} creating container $nameForErrors",
                            e
                        ))
                end match
            case Result.Failure(e) =>
                Abort.fail(ContainerOperationException(s"Failed to create container $nameForErrors", e))
            case Result.Panic(e) =>
                Abort.fail(ContainerBackendException(s"Unexpected error creating container $nameForErrors", e))
        }
    end create

    private def ctxContainer(id: Container.Id): ResourceContext = ResourceContext.Container(id)

    def start(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnitAccept304(s"/containers/${id.value}/start", ctxContainer(id))

    def stop(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val seconds = timeout.toMillis / 1000
        postUnitAccept304(s"/containers/${id.value}/stop?t=$seconds", ctxContainer(id)).andThen {
            // Docker HTTP API returns before the container fully transitions to "exited".
            // Wait for the container to complete (consistent with ShellBackend's blocking `docker stop`).
            Abort.run[ContainerException](waitForExit(id)).unit
        }
    end stop

    def kill(id: Container.Id, signal: Container.Signal)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/kill?signal=${signal.name}", ctxContainer(id))

    def restart(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val seconds = timeout.toMillis / 1000
        postUnit(s"/containers/${id.value}/restart?t=$seconds", ctxContainer(id))

    def pause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/pause", ctxContainer(id))

    def unpause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/unpause", ctxContainer(id))

    def remove(id: Container.Id, force: Boolean, removeVolumes: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        withErrorMapping(ctxContainer(id)) {
            HttpClient.deleteText(
                url(s"/containers/${id.value}", "force" -> force.toString, "v" -> removeVolumes.toString)
            ).unit
        }

    def rename(id: Container.Id, newName: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/rename?name=${java.net.URLEncoder.encode(newName, "UTF-8")}", ctxContainer(id))

    def waitForExit(id: Container.Id)(using Frame): ExitCode < (Async & Abort[ContainerException]) =
        Abort.run[ContainerException](
            withErrorMapping(ctxContainer(id)) {
                HttpClient.postJson[WaitResponse](url(s"/containers/${id.value}/wait"), "")
            }.map(resp => ExitCode(resp.StatusCode))
        ).map {
            case Result.Success(code)                         => code
            case Result.Failure(_: ContainerMissingException) =>
                // Container was auto-removed before wait completed — exit code lost
                ExitCode.Success
            case Result.Failure(other) => Abort.fail(other)
            case Result.Panic(ex)      => Abort.fail(ContainerBackendException(s"waitForExit panicked for ${id.value}", ex))
        }

    // --- Checkpoint/Restore ---

    def checkpoint(id: Container.Id, name: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val body     = CheckpointCreateRequest(CheckpointID = name)
        val jsonBody = Json.encode(body)
        withErrorMapping(ctxContainer(id)) {
            HttpClient.postText(
                url(s"/containers/${id.value}/checkpoints"),
                jsonBody,
                headers = Seq("Content-Type" -> "application/json")
            )
        }.unit
    end checkpoint

    def restore(id: Container.Id, checkpoint: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/start?checkpoint=${java.net.URLEncoder.encode(checkpoint, "UTF-8")}", ctxContainer(id))

    // --- Health ---

    def isHealthy(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException]) =
        state(id).map(_ == Container.State.Running)

    // --- Inspection ---

    // Docker/Podman returns `null` for unexposed-but-not-bound ports, e.g. `"5432/tcp": null`.
    // zio-schema's JSON codec fails to decode null as Option[Seq[T]] inside a Map, so we
    // normalise null port-binding values to `[]` in the raw JSON before decode.

    private def normalizePortsNull(json: String): String =
        json.replaceAll("""("[\d]+/\w+")\s*:\s*null""", "$1: []")

    private def fetchInspect(id: Container.Id)(using Frame): InspectResponse < (Async & Abort[HttpException]) =
        val inspectUrl = url(s"/containers/${id.value}/json")
        HttpClient.getText(inspectUrl).map { raw =>
            Json.decode[InspectResponse](normalizePortsNull(raw)) match
                case Result.Success(r) => r
                case Result.Failure(e) => Abort.get(Result.fail(HttpJsonDecodeException(e.getMessage, "GET", inspectUrl)))
                case Result.Panic(e)   => Abort.get(Result.panic(e))
        }
    end fetchInspect

    def inspect(id: Container.Id)(using Frame): Container.Info < (Async & Abort[ContainerException]) =
        withErrorMapping(ctxContainer(id)) {
            fetchInspect(id)
        }.map(mapInspectToInfo)

    def state(id: Container.Id)(using Frame): Container.State < (Async & Abort[ContainerException]) =
        withErrorMapping(ctxContainer(id)) {
            fetchInspect(id)
        }.map(resp => parseState(resp.State.Status))

    def stats(id: Container.Id)(using Frame): Container.Stats < (Async & Abort[ContainerException]) =
        // Check container state first — Docker returns zero-valued stats for stopped
        // containers, so we need to detect and fail explicitly (consistent with ShellBackend).
        Clock.now.map { now =>
            state(id).map { s =>
                if s == Container.State.Stopped || s == Container.State.Dead then
                    Abort.fail[ContainerException](ContainerAlreadyStoppedException(id))
                else
                    withErrorMapping(ctxContainer(id)) {
                        HttpClient.getJson[StatsResponse](url(s"/containers/${id.value}/stats", "stream" -> "false"))
                    }.map(dto => mapStatsResponse(dto, now))
            }
        }

    def statsStream(id: Container.Id)(using Frame): Stream[Container.Stats, Async & Abort[ContainerException]] =
        statsStream(id, 200.millis)

    def statsStream(id: Container.Id, interval: Duration)(using Frame): Stream[Container.Stats, Async & Abort[ContainerException]] =
        // Docker stats streaming ignores interval, so we poll instead for consistent behavior
        Stream {
            Loop(()) { _ =>
                stats(id).map { s =>
                    Emit.value(Chunk(s)).andThen {
                        Async.sleep(interval).andThen(Loop.continue(()))
                    }
                }
            }
        }

    def top(id: Container.Id, psArgs: String)(using Frame): Container.TopResult < (Async & Abort[ContainerException]) =
        withErrorMapping(ctxContainer(id)) {
            HttpClient.getJson[TopResponse](url(s"/containers/${id.value}/top", "ps_args" -> psArgs))
        }.map { resp =>
            TopResult(
                titles = Chunk.from(resp.Titles),
                processes = Chunk.from(resp.Processes.map(p => Chunk.from(p)))
            )
        }

    def changes(id: Container.Id)(using Frame): Chunk[Container.FilesystemChange] < (Async & Abort[ContainerException]) =
        withErrorMapping(ctxContainer(id)) {
            HttpClient.getJson[Seq[ChangeEntryDto]](url(s"/containers/${id.value}/changes"))
        }.map { entries =>
            Chunk.from(entries.map { e =>
                val kind = e.Kind match
                    case 0 => FilesystemChange.Kind.Modified
                    case 1 => FilesystemChange.Kind.Added
                    case 2 => FilesystemChange.Kind.Deleted
                    case _ => FilesystemChange.Kind.Modified
                FilesystemChange(e.Path, kind)
            })
        }

    // --- Exec ---

    private def execCreateBody(command: Command): ExecCreateRequest =
        val envVars = command.env.map { case (k, v) => s"$k=$v" }.toSeq
        ExecCreateRequest(
            Cmd = command.args.toSeq,
            Env = envVars,
            WorkingDir = command.workDir.map(_.toString).getOrElse("")
        )
    end execCreateBody

    def exec(id: Container.Id, command: Command)(using Frame): Container.ExecResult < (Async & Abort[ContainerException]) =
        // Create exec instance
        val createBody = execCreateBody(command)
        withErrorMapping(ctxContainer(id)) {
            HttpClient.postJson[ExecCreateResponse](url(s"/containers/${id.value}/exec"), createBody)
        }
            .map { createResp =>
                val execId = createResp.Id
                // Start exec and collect output
                val startBody = ExecStartRequest(Detach = false)
                withErrorMapping(ctxContainer(id)) {
                    HttpClient.postBinary(
                        url(s"/exec/$execId/start"),
                        Span.from(Json.encode(startBody).getBytes),
                        headers = Seq("Content-Type" -> "application/json")
                    )
                }.map { outputBytes =>
                    // Inspect exec for exit code
                    val entries = demuxStream(outputBytes)
                    val stdout  = entries.filter(_.source == LogEntry.Source.Stdout).map(_.content).toSeq.mkString("\n")
                    val stderr  = entries.filter(_.source == LogEntry.Source.Stderr).map(_.content).toSeq.mkString("\n")
                    withErrorMapping(ctxContainer(id)) {
                        HttpClient.getJson[ExecInspectResponse](url(s"/exec/$execId/json"))
                    }
                        .map { inspectResp =>
                            val exitCode = if inspectResp.ExitCode == 0 then ExitCode.Success else ExitCode.Failure(inspectResp.ExitCode)
                            val result   = ExecResult(exitCode, stdout, stderr)
                            exitCode match
                                case ExitCode.Failure(code) if code == 125 || code == 126 || code == 127 =>
                                    Abort.fail[ContainerException](
                                        ContainerExecFailedException(
                                            id,
                                            command.args,
                                            exitCode,
                                            stderr
                                        )
                                    )
                                case _ => result
                            end match
                        }
                }
            }
    end exec

    def execStream(id: Container.Id, command: Command)(
        using Frame
    ): Stream[Container.LogEntry, Async & Abort[ContainerException]] =
        // Native HTTP: create exec instance, upgrade the connection at /exec/{id}/start, and
        // demux the multiplexed stdout/stderr frames into LogEntries via the shared assembler.
        // Ignores stdin — exec is strictly output-only here; use execInteractive for a writable session.
        Stream {
            val createBody = execCreateBody(command)
            withErrorMapping(ctxContainer(id)) {
                HttpClient.postJson[ExecCreateResponse](url(s"/containers/${id.value}/exec"), createBody)
            }.map { resp =>
                val execId    = resp.Id
                val startBody = ExecStartRequest(Detach = false)
                val bodyBytes = Span.from(Json.encode(startBody).getBytes("UTF-8"))
                Scope.run {
                    Abort.runWith[HttpException](
                        HttpClient.connectRaw(
                            url(s"/exec/$execId/start"),
                            body = bodyBytes,
                            headers = Seq(
                                "Content-Type" -> "application/json",
                                "Connection"   -> "Upgrade",
                                "Upgrade"      -> "tcp"
                            )
                        )
                    ) {
                        case Result.Success(conn) =>
                            val stdoutAsm = new LineAssembler
                            val stderrAsm = new LineAssembler
                            conn.read.foreachChunk { chunk =>
                                Kyo.foreachDiscard(chunk) { span =>
                                    val entries = demuxStreamAssembled(span, stdoutAsm, stderrAsm, timestamps = false)
                                    Emit.value(entries)
                                }
                            }
                        case Result.Failure(e) => mapHttpError(e, ctxContainer(id)).unit
                        case Result.Panic(e) =>
                            Abort.fail(ContainerBackendException(s"execStream panicked in ${id.value}", e))
                    }
                }
            }
        }

    def execInteractive(id: Container.Id, command: Command)(
        using Frame
    ): Container.AttachSession < (Async & Abort[ContainerException] & Scope) =
        val createBody = execCreateBody(command).copy(AttachStdin = true)
        withErrorMapping(ctxContainer(id)) {
            HttpClient.postJson[ExecCreateResponse](url(s"/containers/${id.value}/exec"), createBody)
        }.map { resp =>
            val execId    = resp.Id
            val startBody = ExecStartRequest(Detach = false)
            val bodyBytes = Span.from(Json.encode(startBody).getBytes("UTF-8"))
            Abort.runWith[HttpException](
                HttpClient.connectRaw(
                    url(s"/exec/$execId/start"),
                    body = bodyBytes,
                    headers = Seq(
                        "Content-Type" -> "application/json",
                        "Connection"   -> "Upgrade",
                        "Upgrade"      -> "tcp"
                    )
                )
            ) {
                case Result.Success(conn) => buildAttachSession(conn, isTty = false)
                case Result.Failure(e)    => mapHttpError(e, ctxContainer(id))
                case Result.Panic(e) =>
                    Abort.fail(ContainerBackendException(s"Unexpected error for ${id.value}", e))
            }
        }
    end execInteractive

    // --- Attach ---

    def attach(id: Container.Id, stdin: Boolean, stdout: Boolean, stderr: Boolean)(
        using Frame
    ): Container.AttachSession < (Async & Abort[ContainerException] & Scope) =
        isContainerTty(id).map { isTty =>
            Abort.runWith[HttpException](
                HttpClient.connectRaw(
                    url(
                        s"/containers/${id.value}/attach",
                        "stream" -> "true",
                        "logs"   -> "true",
                        "stdin"  -> stdin.toString,
                        "stdout" -> stdout.toString,
                        "stderr" -> stderr.toString
                    ),
                    headers = Seq(
                        "Content-Type" -> "application/vnd.docker.raw-stream",
                        "Connection"   -> "Upgrade",
                        "Upgrade"      -> "tcp"
                    )
                )
            ) {
                case Result.Success(conn) => buildAttachSession(conn, isTty)
                case Result.Failure(e)    => mapHttpError(e, ctxContainer(id))
                case Result.Panic(e) =>
                    Abort.fail(ContainerBackendException(s"Unexpected error for ${id.value}", e))
            }
        }
    end attach

    /** Build an AttachSession from an HttpRawConnection.
      *
      * Wraps the bidirectional raw byte connection as an AttachSession with proper demultiplexing. In TTY mode, raw bytes are treated as
      * stdout text. In non-TTY mode, Docker's 8-byte multiplexed stream headers are parsed to separate stdout/stderr.
      */
    private def buildAttachSession(conn: HttpRawConnection, isTty: Boolean): AttachSession =
        new AttachSession:
            def write(data: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
                conn.write(Span.from(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

            def write(data: Chunk[Byte])(using Frame): Unit < (Async & Abort[ContainerException]) =
                conn.write(Span.from(data.toArray))

            def read(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
                if isTty then
                    conn.read.mapChunk { spans =>
                        spans.flatMap { bytes =>
                            val text  = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            val lines = text.split("\n").filter(_.nonEmpty)
                            Chunk.from(lines.map(line => LogEntry(LogEntry.Source.Stdout, line)))
                        }
                    }
                else
                    conn.read.mapChunk { spans =>
                        spans.flatMap(demuxStream(_))
                    }

            def resize(width: Int, height: Int)(using Frame): Unit < (Async & Abort[ContainerException]) =
                () // Resize can be added later via POST to resize endpoint
        end new
    end buildAttachSession

    // --- Logs ---

    def logs(
        id: Container.Id,
        stdout: Boolean,
        stderr: Boolean,
        since: Instant,
        until: Instant,
        timestamps: Boolean,
        tail: Int
    )(using Frame): Chunk[Container.LogEntry] < (Async & Abort[ContainerException]) =
        val params = Seq(
            "stdout"     -> stdout.toString,
            "stderr"     -> stderr.toString,
            "timestamps" -> timestamps.toString
        ) ++
            (if tail != Int.MaxValue then Seq("tail" -> tail.toString) else Seq.empty) ++
            (if since != Instant.Min then Seq("since" -> since.toJava.getEpochSecond.toString) else Seq.empty) ++
            (if until != Instant.Max then Seq("until" -> until.toJava.getEpochSecond.toString) else Seq.empty)
        // Check if container uses TTY — TTY mode returns raw output without multiplexing headers
        isContainerTty(id).map { isTty =>
            withErrorMapping(ctxContainer(id)) {
                HttpClient.getBinary(url(s"/containers/${id.value}/logs", params*))
            }.map { bytes =>
                if isTty then parseRawLogStream(bytes, timestamps)
                else demuxStream(bytes, timestamps)
            }
        }
    end logs

    def logStream(
        id: Container.Id,
        stdout: Boolean,
        stderr: Boolean,
        since: Instant,
        timestamps: Boolean,
        tail: Int
    )(using Frame): Stream[Container.LogEntry, Async & Abort[ContainerException]] =
        val params = Seq(
            "stdout"     -> stdout.toString,
            "stderr"     -> stderr.toString,
            "timestamps" -> timestamps.toString,
            "follow"     -> "true"
        ) ++
            (if tail != Int.MaxValue then Seq("tail" -> tail.toString) else Seq.empty) ++
            (if since != Instant.Min then Seq("since" -> since.toJava.getEpochSecond.toString) else Seq.empty)
        // Use streaming HTTP to avoid buffering the entire (potentially infinite) response.
        // getStreamBytes returns chunks incrementally as they arrive from Docker.
        val byteStream: Stream[Span[Byte], Async & Abort[HttpException]] =
            HttpClient.getStreamBytes(url(s"/containers/${id.value}/logs", params*))
        // Demux each byte span into log entries, forwarding them via Emit.
        // Check TTY mode — TTY returns raw output without multiplexing headers.
        // Wrap in Abort.runWith to map HttpException to ContainerException.
        Stream {
            isContainerTty(id).map { isTty =>
                // Per-stream state: lines spanning chunk boundaries are re-assembled.
                // Demux frames maintain separate assemblers per source since stdout/stderr
                // frames interleave at arbitrary boundaries.
                val stdoutAsm = new LineAssembler
                val stderrAsm = new LineAssembler
                Abort.runWith[HttpException](
                    byteStream.foreachChunk { chunk =>
                        Kyo.foreachDiscard(chunk) { span =>
                            val entries =
                                if isTty then parseRawLogStreamAssembled(span, stdoutAsm, timestamps)
                                else demuxStreamAssembled(span, stdoutAsm, stderrAsm, timestamps)
                            Emit.value(entries)
                        }
                    }
                ) {
                    case Result.Success(_) => ()
                    case Result.Failure(e) => mapHttpError(e, ctxContainer(id)).unit
                    case Result.Panic(e) =>
                        Abort.fail(ContainerBackendException(s"Log stream failed for ${id.value}", e))
                }
            }
        }
    end logStream

    // --- File ops ---

    def copyTo(id: Container.Id, source: Path, containerPath: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        // Create tar archive of the source file, then PUT to /containers/{id}/archive
        // Docker API extracts the tar at `path`, so we use the parent directory of containerPath
        // and rename the file in the tar to match the desired container filename.
        val sourceDir     = source.parent.map(_.toString).getOrElse(".")
        val sourceFile    = source.name.getOrElse(source.toString)
        val containerDir  = containerPath.parent.map(_.toString).getOrElse("/")
        val containerFile = containerPath.name.getOrElse(containerPath.toString)
        // BSD tar -s for portable rename when filenames differ
        val tarCmd =
            if sourceFile == containerFile then
                Command("tar", "-c", "-f", "-", "-C", sourceDir, sourceFile)
            else
                Command(
                    "tar",
                    "-c",
                    "-f",
                    "-",
                    "-s",
                    s"/$sourceFile/$containerFile/",
                    "-C",
                    sourceDir,
                    sourceFile
                )
        Scope.run {
            Abort.runWith[CommandException](tarCmd.spawn) {
                case Result.Success(proc) =>
                    proc.stdout.run.map { tarBytes =>
                        val tarSpan = Span.from(tarBytes.toArray)
                        withErrorMapping(ctxContainer(id)) {
                            HttpClient.putBinary(
                                url(
                                    s"/containers/${id.value}/archive",
                                    "path" -> containerDir
                                ),
                                tarSpan,
                                headers = Seq("Content-Type" -> "application/x-tar")
                            )
                        }.map(_ => ())
                    }
                case Result.Failure(cmdEx) =>
                    Abort.fail(ContainerOperationException(
                        s"Copy operation failed for ${id.value}: failed to create tar for copyTo",
                        cmdEx
                    ))
                case Result.Panic(ex) =>
                    Abort.fail(ContainerBackendException(
                        s"Unexpected error creating tar for copyTo on ${id.value}",
                        ex
                    ))
            }
        }
    end copyTo

    def copyFrom(id: Container.Id, containerPath: Path, destination: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        // GET tar archive from container, pipe to tar -x via stdin
        // Docker returns the file with its container-side name inside the tar,
        // so we extract to a temp dir and move the file to the desired destination.
        withErrorMapping(ctxContainer(id)) {
            HttpClient.getBinary(url(s"/containers/${id.value}/archive", "path" -> containerPath.toString))
        }.map { tarBytes =>
            val containerFileName = containerPath.name.getOrElse(containerPath.toString)
            val destDir           = destination.parent.map(_.toString).getOrElse(".")
            val destFileName      = destination.name.getOrElse(destination.toString)
            // Extract to destDir, then rename if needed
            val extractCmd = Command("tar", "-x", "-C", destDir).stdin(tarBytes)
            Abort.runWith[CommandException](extractCmd.text) {
                case Result.Success(_) =>
                    // If the container filename differs from the destination filename, rename
                    if containerFileName != destFileName then
                        val extractedPath = Path(destDir + "/" + containerFileName)
                        Abort.runWith[CommandException](
                            Command("mv", extractedPath.toString, destination.toString).text
                        ) {
                            case Result.Success(_) => ()
                            case Result.Failure(cmdEx) =>
                                Abort.fail(ContainerOperationException(
                                    s"Copy operation failed for ${id.value}: failed to rename extracted file for copyFrom",
                                    cmdEx
                                ))
                            case Result.Panic(ex) =>
                                Abort.fail(ContainerBackendException(
                                    s"Unexpected error renaming file for copyFrom on ${id.value}",
                                    ex
                                ))
                        }
                    else ()
                case Result.Failure(cmdEx) =>
                    Abort.fail(ContainerOperationException(s"Failed to extract tar for copyFrom on ${id.value}", cmdEx))
                case Result.Panic(ex) =>
                    Abort.fail(ContainerBackendException(s"Unexpected error extracting tar for copyFrom on ${id.value}", ex))
            }
        }

    def stat(id: Container.Id, containerPath: Path)(using Frame): Container.FileStat < (Async & Abort[ContainerException]) =
        // HEAD /containers/{id}/archive?path=... returns X-Docker-Container-Path-Stat header
        Abort.runWith[HttpException](
            HttpClient.head(url(s"/containers/${id.value}/archive", "path" -> containerPath.toString))
        ) {
            case Result.Success(resp) =>
                resp.headers.get("X-Docker-Container-Path-Stat") match
                    case Present(encoded) =>
                        val decoded = new String(java.util.Base64.getDecoder.decode(encoded), java.nio.charset.StandardCharsets.UTF_8)
                        Json.decode[FileStatDto](decoded) match
                            case Result.Success(dto) =>
                                val modifiedAt = parseInstant(Option(dto.mtime).filter(_.nonEmpty)).getOrElse(Instant.Epoch)
                                FileStat(
                                    name = dto.name,
                                    size = dto.size,
                                    mode = dto.mode,
                                    modifiedAt = modifiedAt,
                                    linkTarget = if dto.linkTarget.nonEmpty then Present(dto.linkTarget) else Absent
                                )
                            case Result.Failure(_) =>
                                Abort.fail(ContainerDecodeException(
                                    "Parse error in stat",
                                    s"Failed to parse file stat JSON for container ${id.value}"
                                ))
                            case Result.Panic(t) => Abort.panic(t)
                        end match
                    case Absent =>
                        Abort.fail(ContainerDecodeException(
                            "Parse error in stat",
                            s"Missing X-Docker-Container-Path-Stat header for container ${id.value}"
                        ))
            case Result.Failure(e) => mapHttpError(e, ctxContainer(id))
            case Result.Panic(e) =>
                Abort.fail(ContainerBackendException(s"Unexpected error during stat on container ${id.value}", e))
        }

    def exportFs(id: Container.Id)(using Frame): Stream[Byte, Async & Abort[ContainerException]] =
        // GET /containers/{id}/export returns raw tar stream — use streaming to avoid loading entire FS
        val byteStream: Stream[Span[Byte], Async & Abort[HttpException]] =
            HttpClient.getStreamBytes(url(s"/containers/${id.value}/export"))
        Stream {
            Abort.runWith[HttpException](
                byteStream.foreachChunk { chunk =>
                    Kyo.foreachDiscard(chunk) { span =>
                        Emit.value(Chunk.from(span.toArray))
                    }
                }
            ) {
                case Result.Success(_) => ()
                case Result.Failure(e) => mapHttpError(e, ctxContainer(id)).unit
                case Result.Panic(e) =>
                    Abort.fail(ContainerBackendException(s"Unexpected error during export of ${id.value}", e))
            }
        }
    end exportFs

    // --- Resource updates ---

    def update(
        id: Container.Id,
        memory: Maybe[Long],
        memorySwap: Maybe[Long],
        cpuLimit: Maybe[Double],
        cpuAffinity: Maybe[String],
        maxProcesses: Maybe[Long],
        restartPolicy: Maybe[Container.Config.RestartPolicy]
    )(using Frame): Unit < (Async & Abort[ContainerException]) =
        val restartPol = restartPolicy.map { rp =>
            rp match
                case Config.RestartPolicy.OnFailure(retries) => RestartPolicyEntry(rp.cliName, retries)
                case _                                       => RestartPolicyEntry(rp.cliName, 0)
        }
        val body = UpdateRequest(
            Memory = memory.getOrElse(0L),
            MemorySwap = memorySwap.getOrElse(0L),
            NanoCPUs = cpuLimit.map(c => (c * 1e9).toLong).getOrElse(0L),
            CpusetCpus = cpuAffinity.getOrElse(""),
            PidsLimit = maxProcesses.getOrElse(0L),
            RestartPolicy = restartPol.getOrElse(RestartPolicyEntry("", 0))
        )
        val jsonBody = Json.encode(body)
        withErrorMapping(ctxContainer(id)) {
            HttpClient.postText(
                url(s"/containers/${id.value}/update"),
                jsonBody,
                headers = Seq("Content-Type" -> "application/json")
            )
        }.unit
    end update

    // --- Network on container ---

    def connectToNetwork(id: Container.Id, networkId: Container.Network.Id, aliases: Chunk[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        networkConnect(networkId, id, aliases)

    def disconnectFromNetwork(id: Container.Id, networkId: Container.Network.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        networkDisconnect(networkId, id, force)

    // --- Container listing ---

    def list(all: Boolean, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Summary] < (Async & Abort[ContainerException]) =
        val params = Seq("all" -> all.toString) ++
            (if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
             else Seq.empty)
        withErrorMapping(ResourceContext.Op("list")) {
            HttpClient.getJson[Seq[ListContainerEntry]](url("/containers/json", params*))
        }.map { entries =>
            Chunk.from(entries.map { e =>
                Container.Summary(
                    id = Container.Id(e.Id),
                    names = Chunk.from(e.Names.map(_.stripPrefix("/"))),
                    image = ContainerImage.parse(e.Image).getOrElse(ContainerImage(e.Image)),
                    imageId = ContainerImage.Id(e.ImageID),
                    command = e.Command,
                    state = parseState(e.State),
                    status = e.Status,
                    ports = Chunk.from(e.Ports.map { p =>
                        val protocol = p.Type match
                            case "udp"  => Config.Protocol.UDP
                            case "sctp" => Config.Protocol.SCTP
                            case _      => Config.Protocol.TCP
                        Config.PortBinding(
                            containerPort = p.PrivatePort,
                            hostPort = p.PublicPort,
                            hostIp = p.IP,
                            protocol = protocol
                        )
                    }),
                    labels = Dict.from(e.Labels),
                    mounts = Chunk.from(e.Mounts.map(_.Name)),
                    createdAt = Instant.fromJava(java.time.Instant.ofEpochSecond(e.Created))
                )
            })
        }
    end list

    def prune(filters: Dict[String, Chunk[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
            else Seq.empty
        withErrorMapping(ResourceContext.Op("prune")) {
            HttpClient.postJson[PruneContainersResponse](url("/containers/prune", params*), "")
        }.map { resp =>
            Container.PruneResult(
                deleted = Chunk.from(resp.ContainersDeleted.getOrElse(Seq.empty)),
                spaceReclaimed = resp.SpaceReclaimed
            )
        }
    end prune

    def attachById(idOrName: Container.Id)(using Frame): (Container.Id, Container.Config) < (Async & Abort[ContainerException]) =
        inspect(idOrName).map { info =>
            val config0 = Config.default.copy(
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
    ): Unit < (Async & Abort[ContainerException]) =
        nativePull(image, platform, auth).foreach(_ => Kyo.unit)

    def imageEnsure(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val ref = image.reference
        Abort.recover[ContainerException](
            (_: ContainerException) => imagePull(image, platform, auth),
            (_: Throwable) => imagePull(image, platform, auth)
        ) {
            withErrorMapping(ResourceContext.Image(ref)) {
                HttpClient.getJson[ImageInspectDto](url(s"/images/$ref/json"))
            }.unit
        }
    end imageEnsure

    def imagePullWithProgress(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Stream[ContainerImage.PullProgress, Async & Abort[ContainerException]] =
        val ref = image.reference
        Stream {
            Abort.recover[ContainerException] {
                (_: ContainerException) =>
                    nativePull(image, platform, auth).foreachChunk(chunk => Emit.value(chunk))
            } {
                withErrorMapping(ResourceContext.Image(ref)) {
                    HttpClient.getJson[ImageInspectDto](url(s"/images/$ref/json"))
                }.andThen {
                    Emit.value(Chunk(ContainerImage.PullProgress(
                        id = Absent,
                        status = s"$ref: image is up to date",
                        progress = Absent,
                        error = Absent
                    )))
                }
            }
        }
    end imagePullWithProgress

    /** Base path of an image reference with registry and namespace but no tag or digest. */
    private def imageBase(image: ContainerImage): String =
        (image.registry, image.namespace) match
            case (Present(r), Present(ns)) => s"${r.value}/$ns/${image.name}"
            case (Present(r), _)           => s"${r.value}/${image.name}"
            case (_, Present(ns))          => s"$ns/${image.name}"
            case _                         => image.name

    /** Encode a RegistryAuth entry as the `X-Registry-Auth` header value expected by Docker's API.
      *
      * Looks up the image's registry in `auth.auths` (falls back to Docker Hub). The stored credential is assumed to be a Base64-encoded
      * `username:password`, which is wrapped in a JSON object and re-encoded per the Docker engine's `X-Registry-Auth` contract.
      */
    private def registryAuthHeader(image: ContainerImage, auth: ContainerImage.RegistryAuth): Maybe[String] =
        val key = image.registry.getOrElse(ContainerImage.Registry.DockerHub)
        auth.auths.get(key).map { encodedCreds =>
            val json = s"""{"auth":"$encodedCreds"}"""
            java.util.Base64.getEncoder.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }
    end registryAuthHeader

    /** Native streaming pull via `POST /images/create` with NDJSON progress events.
      *
      * Emits one [[ContainerImage.PullProgress]] per daemon progress line. Error events are translated to
      * [[ContainerImageMissingException]] (when the message indicates a missing manifest) or [[ContainerOperationException]] otherwise.
      */
    private def nativePull(
        image: ContainerImage,
        platform: Maybe[Container.Platform],
        auth: Maybe[ContainerImage.RegistryAuth]
    )(using Frame): Stream[ContainerImage.PullProgress, Async & Abort[ContainerException]] =
        Stream {
            val fromImage     = imageBase(image)
            val tagOrDigest   = image.digest.map(_.value).orElse(image.tag)
            val platformParam = platform.fold(Seq.empty[(String, String)])(p => Seq("platform" -> p.reference))
            val params =
                Seq("fromImage" -> fromImage) ++ tagOrDigest.fold(Seq.empty[(String, String)])(t => Seq("tag" -> t)) ++ platformParam
            val authHeader =
                auth.flatMap(a => registryAuthHeader(image, a)).fold(Seq.empty[(String, String)])(h => Seq("X-Registry-Auth" -> h))
            val headers    = Seq("Content-Type" -> "application/json") ++ authHeader
            val byteStream = HttpClient.postStreamBytes(url("/images/create", params*), Span.empty[Byte], headers)
            val asm        = new LineAssembler
            Abort.runWith[HttpException](
                byteStream.foreachChunk { chunk =>
                    Kyo.foreachDiscard(chunk) { span =>
                        val text  = new String(span.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        val lines = asm.feed(text)
                        Kyo.foreachDiscard(lines.toSeq.filter(_.trim.nonEmpty))(processPullLine(_, image))
                    }
                }
            ) {
                case Result.Success(_) => ()
                case Result.Failure(e) => mapHttpError(e, ResourceContext.Image(image.reference)).unit
                case Result.Panic(e) =>
                    Abort.fail(ContainerBackendException(s"Unexpected error pulling ${image.reference}", e))
            }
        }

    private def processPullLine(
        line: String,
        image: ContainerImage
    )(using Frame): Unit < (Abort[ContainerException] & Emit[Chunk[ContainerImage.PullProgress]]) =
        Json.decode[PullProgressDto](line) match
            case Result.Success(dto) =>
                val errMsg = dto.error.orElse(dto.errorDetail.map(_.message))
                errMsg match
                    case Some(err) if err.contains("not found") || err.contains("manifest unknown") =>
                        Abort.fail(ContainerImageMissingException(image))
                    case Some(err) =>
                        Abort.fail(ContainerOperationException(
                            s"Pull failed for ${image.reference}: $err",
                            new RuntimeException(err)
                        ))
                    case None =>
                        Emit.value(Chunk(ContainerImage.PullProgress(
                            id = Maybe.fromOption(dto.id),
                            status = dto.status.getOrElse(""),
                            progress = Maybe.fromOption(dto.progress),
                            error = Absent
                        )))
                end match
            case Result.Failure(_) => Kyo.unit
            case Result.Panic(t)   => Abort.panic(t)

    def imageList(all: Boolean, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[ContainerImage.Summary] < (Async & Abort[ContainerException]) =
        val params = Seq("all" -> all.toString) ++
            (if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
             else Seq.empty)
        withErrorMapping(ResourceContext.Op("imageList")) {
            HttpClient.getJson[Seq[ImageListEntryDto]](url("/images/json", params*))
        }.map { entries =>
            Chunk.from(entries.map { e =>
                val repoTags = Chunk.from(
                    e.RepoTags.getOrElse(Seq.empty).filter(_ != "<none>:<none>")
                        .map(s => ContainerImage.parse(s).getOrElse(ContainerImage(s)))
                )
                val repoDigests = Chunk.from(
                    e.RepoDigests.getOrElse(Seq.empty).filter(_ != "<none>@<none>")
                        .map(s => ContainerImage.parse(s).getOrElse(ContainerImage(s)))
                )
                ContainerImage.Summary(
                    id = ContainerImage.Id(e.Id),
                    repoTags = repoTags,
                    repoDigests = repoDigests,
                    createdAt = if e.Created > 0 then Instant.fromJava(java.time.Instant.ofEpochSecond(e.Created))
                    else Instant.Epoch,
                    size = e.Size,
                    labels = Dict.from(e.Labels.getOrElse(Map.empty))
                )
            })
        }
    end imageList

    def imageInspect(image: ContainerImage)(using Frame): ContainerImage.Info < (Async & Abort[ContainerException]) =
        val ref = image.reference
        withErrorMapping(ResourceContext.Image(ref)) {
            HttpClient.getJson[ImageInspectDto](url(s"/images/$ref/json"))
        }.map { dto =>
            ContainerImage.Info(
                id = ContainerImage.Id(dto.Id),
                repoTags = Chunk.from(dto.RepoTags.getOrElse(Seq.empty)
                    .map(s => ContainerImage.parse(s).getOrElse(ContainerImage(s)))),
                repoDigests = Chunk.from(dto.RepoDigests.getOrElse(Seq.empty)
                    .map(s => ContainerImage.parse(s).getOrElse(ContainerImage(s)))),
                createdAt = parseInstant(Option(dto.Created).filter(_.nonEmpty)).getOrElse(Instant.Epoch),
                size = dto.Size,
                labels = Dict.from(dto.Config.Labels.getOrElse(Map.empty)),
                architecture = dto.Architecture,
                os = dto.Os
            )
        }
    end imageInspect

    def imageRemove(image: ContainerImage, force: Boolean, noPrune: Boolean)(
        using Frame
    ): Chunk[ContainerImage.DeleteResponse] < (Async & Abort[ContainerException]) =
        val ref = image.reference
        withErrorMapping(ResourceContext.Image(ref)) {
            HttpClient.deleteJson[Seq[ImageDeleteEntryDto]](
                url(s"/images/$ref", "force" -> force.toString, "noprune" -> noPrune.toString)
            )
        }.map { entries =>
            Chunk.from(entries.map { e =>
                ContainerImage.DeleteResponse(
                    untagged = Maybe.fromOption(e.Untagged),
                    deleted = Maybe.fromOption(e.Deleted)
                )
            })
        }
    end imageRemove

    def imageTag(source: ContainerImage, repo: String, tag: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val ref = source.reference
        withErrorMapping(ResourceContext.Image(ref)) {
            HttpClient.postText(url(s"/images/$ref/tag", "repo" -> repo, "tag" -> tag), "").unit
        }
    end imageTag

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
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]] =
        // Stream-based context not supported, use imageBuildFromPath instead
        Stream {
            Abort.fail[ContainerException](
                ContainerNotSupportedException("imageBuild with stream context", "Use imageBuildFromPath instead")
            ).unit
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
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]] =
        Stream {
            // Archive the build context via `tar -c`, POST the bytes to /build, and parse the
            // NDJSON build-progress response. The tar is buffered in memory — for the typical
            // integration-test / CI context sizes this is fine; very large contexts (GB-scale)
            // would need a streaming request body which kyo-http does not yet expose as a helper.
            val params =
                tags.toSeq.map("t" -> _) ++
                    (if dockerfile != "Dockerfile" then Seq("dockerfile" -> dockerfile) else Seq.empty) ++
                    (if noCache then Seq("nocache" -> "true") else Seq.empty) ++
                    (if pull then Seq("pull" -> "true") else Seq.empty) ++
                    target.fold(Seq.empty[(String, String)])(t => Seq("target" -> t)) ++
                    platform.fold(Seq.empty[(String, String)])(p => Seq("platform" -> p.reference)) ++
                    (if buildArgs.nonEmpty then
                         Seq("buildargs" -> Json.encode(buildArgs.toMap))
                     else Seq.empty) ++
                    (if labels.nonEmpty then
                         Seq("labels" -> Json.encode(labels.toMap))
                     else Seq.empty)
            val authHeader = auth.fold(Seq.empty[(String, String)]) { a =>
                // Docker's /build takes X-Registry-Config, a base64-encoded JSON of {registry: {auth: ...}} pairs.
                val entries = a.auths.toMap.map { case (reg, encoded) =>
                    s""""${reg.value}":{"auth":"$encoded"}"""
                }.mkString(",")
                val json = s"{$entries}"
                Seq("X-Registry-Config" -> java.util.Base64.getEncoder.encodeToString(json.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                )))
            }
            val headers = Seq("Content-Type" -> "application/x-tar") ++ authHeader
            val tarCmd  = Command("tar", "-c", "-C", path.toString, ".")
            Scope.run {
                Abort.runWith[CommandException](tarCmd.spawn) {
                    case Result.Success(proc) =>
                        proc.stdout.run.map { tarBytes =>
                            val byteStream = HttpClient.postStreamBytes(
                                url("/build", params*),
                                Span.from(tarBytes.toArray),
                                headers
                            )
                            val asm = new LineAssembler
                            Abort.runWith[HttpException](
                                byteStream.foreachChunk { chunk =>
                                    Kyo.foreachDiscard(chunk) { span =>
                                        val text  = new String(span.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                        val lines = asm.feed(text)
                                        Kyo.foreachDiscard(lines.toSeq.filter(_.trim.nonEmpty))(processBuildLine(_, path.toString))
                                    }
                                }
                            ) {
                                case Result.Success(_) => verifyBuildProduced(tags)
                                case Result.Failure(e) => mapHttpError(e, ResourceContext.Op("imageBuildFromPath")).unit
                                case Result.Panic(e) =>
                                    Abort.fail(ContainerBuildFailedException(path.toString, "unexpected error", e))
                            }
                        }
                    case Result.Failure(e) =>
                        Abort.fail[ContainerException](ContainerBuildFailedException(path.toString, "tar failed", e)).unit
                    case Result.Panic(e) =>
                        Abort.fail[ContainerException](ContainerBuildFailedException(path.toString, "tar panicked", e)).unit
                }
            }
        }

    private def processBuildLine(
        line: String,
        buildContext: String
    )(using Frame): Unit < (Abort[ContainerException] & Emit[Chunk[ContainerImage.BuildProgress]]) =
        Json.decode[BuildProgressDto](line) match
            case Result.Success(dto) =>
                val errMsg = dto.error.orElse(dto.errorDetail.map(_.message))
                errMsg match
                    case Some(err) =>
                        Abort.fail(ContainerBuildFailedException(buildContext, err, new RuntimeException(err)))
                    case None =>
                        Emit.value(Chunk(ContainerImage.BuildProgress(
                            stream = Maybe.fromOption(dto.stream),
                            status = Maybe.fromOption(dto.status),
                            progress = Maybe.fromOption(dto.progress),
                            error = Absent,
                            aux = Maybe.fromOption(dto.aux.map(_.ID))
                        )))
                end match
            case Result.Failure(_) => Kyo.unit
            case Result.Panic(t)   => Abort.panic(t)

    /** After a build stream completes without raising, confirm the daemon actually produced an image for the first tag.
      *
      * Docker's documented contract is that JSONMessage.Error is populated on build failure, which [[processBuildLine]] checks. Some
      * builder pipelines (notably Docker Desktop's buildx → BuildKit path) deviate and emit error text only in stream events, leaving the
      * structured error field absent. A 404 on `GET /images/{tag}/json` is then the authoritative API-level signal that the build didn't
      * succeed.
      *
      * When no tag was requested, nothing is verified — the caller implicitly opted into trusting the event stream.
      */
    private def verifyBuildProduced(tags: Chunk[String])(using Frame): Unit < (Async & Abort[ContainerException]) =
        tags.headOption match
            case None => Kyo.unit
            case Some(tag) =>
                Abort.runWith[HttpException](HttpClient.getJson[ImageInspectDto](url(s"/images/$tag/json"))) {
                    case Result.Success(_) => ()
                    case Result.Failure(e: HttpStatusException) if e.status.code == 404 =>
                        Abort.fail(ContainerBuildFailedException(
                            tag,
                            "image tag not found after build — possibly silent failure",
                            new RuntimeException(s"tagged image '$tag' absent after build")
                        ))
                    case Result.Failure(e) => mapHttpError(e, ResourceContext.Image(tag)).unit
                    case Result.Panic(e) =>
                        Abort.fail(ContainerBuildFailedException(tag, "unexpected error during verification", e))
                }

    def imagePush(image: ContainerImage, auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val ref     = image.reference
        val headers = authHeaders(auth)
        withErrorMapping(ResourceContext.Image(ref)) {
            HttpClient.postText(url(s"/images/$ref/push"), "", headers = headers).unit
        }
    end imagePush

    def imageSearch(term: String, limit: Int, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[ContainerImage.SearchResult] < (Async & Abort[ContainerException]) =
        val params = Seq("term" -> term) ++
            (if limit != Int.MaxValue then Seq("limit" -> limit.toString) else Seq.empty) ++
            (if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
             else Seq.empty)
        withErrorMapping(ResourceContext.Op("imageSearch")) {
            HttpClient.getJson[Seq[ImageSearchEntryDto]](url("/images/search", params*))
        }.map { entries =>
            Chunk.from(entries.map { e =>
                ContainerImage.SearchResult(
                    name = e.name,
                    description = e.description,
                    stars = e.star_count,
                    isOfficial = e.is_official,
                    isAutomated = e.is_automated
                )
            })
        }
    end imageSearch

    def imageHistory(image: ContainerImage)(using Frame): Chunk[ContainerImage.HistoryEntry] < (Async & Abort[ContainerException]) =
        val ref = image.reference
        withErrorMapping(ResourceContext.Image(ref)) {
            HttpClient.getJson[Seq[ImageHistoryEntryDto]](url(s"/images/$ref/history"))
        }.map { entries =>
            Chunk.from(entries.map { e =>
                ContainerImage.HistoryEntry(
                    id = e.Id,
                    createdAt = if e.Created > 0 then Instant.fromJava(java.time.Instant.ofEpochSecond(e.Created))
                    else Instant.Epoch,
                    createdBy = e.CreatedBy,
                    size = e.Size,
                    tags = Chunk.from(e.Tags.getOrElse(Seq.empty)),
                    comment = e.Comment
                )
            })
        }
    end imageHistory

    def imagePrune(filters: Dict[String, Chunk[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
            else Seq.empty
        withErrorMapping(ResourceContext.Op("imagePrune")) {
            HttpClient.postJson[ImagePruneResponseDto](url("/images/prune", params*), "")
        }.map { resp =>
            val deleted = Chunk.from(resp.ImagesDeleted.getOrElse(Seq.empty).flatMap { e =>
                e.Deleted.toSeq ++ e.Untagged.toSeq
            })
            Container.PruneResult(deleted, resp.SpaceReclaimed)
        }
    end imagePrune

    def imageCommit(
        container: Container.Id,
        repo: String,
        tag: String,
        comment: String,
        author: String,
        pause: Boolean
    )(using Frame): String < (Async & Abort[ContainerException]) =
        val params = Seq("container" -> container.value, "pause" -> pause.toString) ++
            (if repo.nonEmpty then Seq("repo" -> repo) else Seq.empty) ++
            (if tag.nonEmpty then Seq("tag" -> tag) else Seq.empty) ++
            (if comment.nonEmpty then Seq("comment" -> comment) else Seq.empty) ++
            (if author.nonEmpty then Seq("author" -> author) else Seq.empty)
        Abort.runWith[HttpException](
            HttpClient.postText(
                url("/commit", params*),
                "{}",
                headers = Seq("Content-Type" -> "application/json")
            )
        ) {
            case Result.Success(body) =>
                Json.decode[ImageCommitResponseDto](body) match
                    case Result.Success(dto) => dto.Id
                    case Result.Failure(_) =>
                        Abort.fail(ContainerDecodeException("Parse error in imageCommit", "Failed to parse commit response"))
                    case Result.Panic(t) => Abort.panic(t)
            case Result.Failure(e) => mapHttpError(e, ctxContainer(container))
            case Result.Panic(e) =>
                Abort.fail(ContainerBackendException(s"Unexpected error committing container ${container.value}", e))
        }
    end imageCommit

    // --- Network operations ---

    def networkCreate(config: Container.Network.Config)(using Frame): Container.Network.Id < (Async & Abort[ContainerException]) =
        val ipamConfig = config.ipam.map { ipam =>
            IpamDto(
                Driver = ipam.driver,
                Config = ipam.config.toSeq.map { pool =>
                    IpamPoolDto(
                        Subnet = pool.subnet.getOrElse(""),
                        Gateway = pool.gateway.getOrElse(""),
                        IPRange = pool.ipRange.getOrElse("")
                    )
                }
            )
        }.getOrElse(IpamDto())
        val body = NetworkCreateRequest(
            Name = config.name,
            Driver = config.driver.cliName,
            Internal = config.internal,
            Attachable = config.attachable,
            EnableIPv6 = config.enableIPv6,
            Labels = config.labels.toMap,
            Options = config.options.toMap,
            IPAM = ipamConfig
        )
        withErrorMapping(ResourceContext.Op("networkCreate")) {
            HttpClient.postJson[NetworkCreateResponse](url("/networks/create"), body)
        }.map(resp => Container.Network.Id(resp.Id))
    end networkCreate

    def networkList(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Network.Info] < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
            else Seq.empty
        withErrorMapping(ResourceContext.Op("networkList")) {
            HttpClient.getJson[Seq[NetworkInfoDto]](url("/networks", params*))
        }.map(entries => Chunk.from(entries.map(mapNetworkInfo)))
    end networkList

    def networkInspect(id: Container.Network.Id)(using Frame): Container.Network.Info < (Async & Abort[ContainerException]) =
        withErrorMapping(ResourceContext.Network(id)) {
            HttpClient.getJson[NetworkInfoDto](url(s"/networks/${id.value}"))
        }.map(mapNetworkInfo)

    def networkRemove(id: Container.Network.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        withErrorMapping(ResourceContext.Network(id)) {
            HttpClient.deleteText(url(s"/networks/${id.value}")).unit
        }

    def networkConnect(network: Container.Network.Id, container: Container.Id, aliases: Chunk[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val endpointConfig =
            if aliases.nonEmpty then Present(EndpointConfigDto(Aliases = aliases.toSeq))
            else Absent
        val body = NetworkConnectRequest(
            Container = container.value,
            EndpointConfig = endpointConfig.getOrElse(EndpointConfigDto())
        )
        val jsonBody = Json.encode(body)
        // Docker returns HTTP 403 when the container is already attached to the network.
        // Treat that as an idempotent no-op so repeated connect calls are safe.
        Abort.runWith[Closed](meter.run {
            Abort.run[HttpException](
                HttpClient.postText(
                    url(s"/networks/${network.value}/connect"),
                    jsonBody,
                    headers = Seq("Content-Type" -> "application/json")
                )
            ).map {
                case Result.Success(_)                                              => ()
                case Result.Failure(e: HttpStatusException) if e.status.code == 403 => ()
                case Result.Failure(e)                                              => mapHttpError(e, ResourceContext.Network(network))
                case Result.Panic(e) =>
                    Abort.fail(ContainerBackendException(s"Unexpected error for network ${network.value}", e))
            }
        }) {
            case Result.Success(a) => a
            case Result.Failure(_) =>
                Abort.fail(ContainerBackendException(s"Concurrency meter closed during network operation", "meter was closed"))
            case Result.Panic(ex) => Abort.fail(ContainerBackendException(s"Concurrency meter panicked during network operation", ex))
        }
    end networkConnect

    def networkDisconnect(network: Container.Network.Id, container: Container.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val body     = NetworkDisconnectRequest(Container = container.value, Force = force)
        val jsonBody = Json.encode(body)
        withErrorMapping(ResourceContext.Network(network)) {
            HttpClient.postText(
                url(s"/networks/${network.value}/disconnect"),
                jsonBody,
                headers = Seq("Content-Type" -> "application/json")
            )
        }.unit
    end networkDisconnect

    def networkPrune(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Network.Id] < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
            else Seq.empty
        withErrorMapping(ResourceContext.Op("networkPrune")) {
            HttpClient.postJson[NetworkPruneResponse](url("/networks/prune", params*), "")
        }.map { resp =>
            Chunk.from(resp.NetworksDeleted.getOrElse(Seq.empty).map(Container.Network.Id(_)))
        }
    end networkPrune

    // --- Volume operations ---

    def volumeCreate(config: Container.Volume.Config)(using Frame): Container.Volume.Info < (Async & Abort[ContainerException]) =
        val body = VolumeCreateRequest(
            Name = config.name.map(_.value).getOrElse(""),
            Driver = config.driver,
            DriverOpts = config.driverOpts.toMap,
            Labels = config.labels.toMap
        )
        withErrorMapping(ResourceContext.Op("volumeCreate")) {
            HttpClient.postJson[VolumeInfoDto](url("/volumes/create"), body)
        }.map(mapVolumeInfo)
    end volumeCreate

    def volumeList(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Container.Volume.Info] < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
            else Seq.empty
        withErrorMapping(ResourceContext.Op("volumeList")) {
            HttpClient.getJson[VolumeListResponse](url("/volumes", params*))
        }.map { resp =>
            Chunk.from(resp.Volumes.getOrElse(Seq.empty).map(mapVolumeInfo))
        }
    end volumeList

    def volumeInspect(id: Container.Volume.Id)(using Frame): Container.Volume.Info < (Async & Abort[ContainerException]) =
        withErrorMapping(ResourceContext.Volume(id)) {
            HttpClient.getJson[VolumeInfoDto](url(s"/volumes/${id.value}"))
        }.map(mapVolumeInfo)

    def volumeRemove(id: Container.Volume.Id, force: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        withErrorMapping(ResourceContext.Volume(id)) {
            HttpClient.deleteText(url(s"/volumes/${id.value}", "force" -> force.toString)).unit
        }

    def volumePrune(filters: Dict[String, Chunk[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json.encode(filters.toMap.view.mapValues(_.toSeq).toMap))
            else Seq.empty
        withErrorMapping(ResourceContext.Op("volumePrune")) {
            HttpClient.postJson[VolumePruneResponse](url("/volumes/prune", params*), "")
        }.map { resp =>
            Container.PruneResult(
                deleted = Chunk.from(resp.VolumesDeleted.getOrElse(Seq.empty)),
                spaceReclaimed = resp.SpaceReclaimed
            )
        }
    end volumePrune

    // --- Backend detection ---

    def describe: String =
        s"HttpContainerBackend(socket=$socketPath, apiVersion=$apiVersion, runtime=$runtimeName)"

    def detect()(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.runWith[HttpException](HttpClient.getText(url("/_ping"))) {
            case Result.Success(response) if response.trim == "OK" => ()
            case Result.Success(response) =>
                Abort.fail(ContainerBackendUnavailableException(
                    "http",
                    s"Unexpected ping response from $socketPath: $response"
                ))
            case Result.Failure(e: HttpException) =>
                Abort.fail(ContainerBackendUnavailableException(
                    "http",
                    s"Failed to ping container API at $socketPath: ${e.getMessage}"
                ))
            case Result.Panic(e) =>
                Abort.fail(ContainerBackendUnavailableException(
                    "http",
                    s"Failed to ping container API at $socketPath: ${e.getMessage}"
                ))
        }

    /** Check if a container was created with TTY mode enabled. */
    private def isContainerTty(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException]) =
        withErrorMapping(ctxContainer(id)) {
            fetchInspect(id)
        }.map(_.Config.Tty)

    /** Parse raw (non-multiplexed) log output as Stdout entries.
      *
      * Used for TTY containers where Docker returns raw text without 8-byte frame headers.
      */
    private def parseRawLogStream(bytes: Span[Byte], timestamps: Boolean = false): Chunk[LogEntry] =
        val text   = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
        val result = Chunk.newBuilder[LogEntry]
        text.split("\n").filter(_.nonEmpty).foreach { line =>
            if timestamps then
                val (maybeTs, rest) = parseTimestampLine(line)
                result.addOne(LogEntry(LogEntry.Source.Stdout, rest, maybeTs))
            else
                result.addOne(LogEntry(LogEntry.Source.Stdout, line))
        }
        result.result()
    end parseRawLogStream

    // --- Multiplexed stream demuxing ---

    /** Demultiplex Docker's multiplexed stdout/stderr stream format.
      *
      * Each frame: byte[0]=stream type (1=stdout,2=stderr), bytes[1-3]=padding, bytes[4-7]=uint32 big-endian size, then `size` bytes
      * payload.
      *
      * @param timestamps
      *   When true, attempt to parse Docker timestamp prefix from each line (format: `2024-01-01T00:00:00.000000000Z content`)
      */
    private def demuxStream(bytes: Span[Byte], timestamps: Boolean = false): Chunk[LogEntry] =
        val arr = bytes.toArray
        val len = arr.length

        def parseLines(content: String, source: LogEntry.Source): Chunk[LogEntry] =
            Chunk.from(content.split("\n").iterator.filter(_.nonEmpty).map { line =>
                if timestamps then
                    val (maybeTs, rest) = parseTimestampLine(line)
                    LogEntry(source, rest, maybeTs)
                else
                    LogEntry(source, line)
            }.toSeq)

        @scala.annotation.tailrec
        def parse(offset: Int, acc: Chunk[LogEntry]): Chunk[LogEntry] =
            if offset + 8 > len then acc
            else
                val streamType = arr(offset) & 0xff
                val size = ((arr(offset + 4) & 0xff) << 24) |
                    ((arr(offset + 5) & 0xff) << 16) |
                    ((arr(offset + 6) & 0xff) << 8) |
                    (arr(offset + 7) & 0xff)
                if offset + 8 + size > len then acc
                else
                    val content = new String(arr, offset + 8, size, java.nio.charset.StandardCharsets.UTF_8)
                    val source  = if streamType == 2 then LogEntry.Source.Stderr else LogEntry.Source.Stdout
                    parse(offset + 8 + size, acc ++ parseLines(content, source))
                end if
        end parse

        parse(0, Chunk.empty)
    end demuxStream

    /** Streaming variant of [[parseRawLogStream]] that threads a [[LineAssembler]] across byte spans so lines straddling chunk boundaries
      * are emitted as a single entry.
      */
    private def parseRawLogStreamAssembled(
        bytes: Span[Byte],
        assembler: LineAssembler,
        timestamps: Boolean
    ): Chunk[LogEntry] =
        val text   = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
        val lines  = assembler.feed(text)
        val result = Chunk.newBuilder[LogEntry]
        lines.foreach { line =>
            if line.nonEmpty then
                if timestamps then
                    val (maybeTs, rest) = parseTimestampLine(line)
                    result.addOne(LogEntry(LogEntry.Source.Stdout, rest, maybeTs))
                else
                    result.addOne(LogEntry(LogEntry.Source.Stdout, line))
        }
        result.result()
    end parseRawLogStreamAssembled

    /** Streaming variant of [[demuxStream]] that maintains per-source [[LineAssembler]]s. Frame payloads are demultiplexed as in the
      * non-streaming version, but each payload is fed into its source's assembler so multi-frame / multi-chunk lines remain intact.
      */
    private def demuxStreamAssembled(
        bytes: Span[Byte],
        stdoutAsm: LineAssembler,
        stderrAsm: LineAssembler,
        timestamps: Boolean
    ): Chunk[LogEntry] =
        val arr    = bytes.toArray
        val len    = arr.length
        val result = Chunk.newBuilder[LogEntry]

        def emitFromPayload(content: String, source: LogEntry.Source): Unit =
            val asm   = if source == LogEntry.Source.Stderr then stderrAsm else stdoutAsm
            val lines = asm.feed(content)
            lines.foreach { line =>
                if line.nonEmpty then
                    if timestamps then
                        val (maybeTs, rest) = parseTimestampLine(line)
                        result.addOne(LogEntry(source, rest, maybeTs))
                    else
                        result.addOne(LogEntry(source, line))
            }
        end emitFromPayload

        @scala.annotation.tailrec
        def parse(offset: Int): Unit =
            if offset + 8 > len then ()
            else
                val streamType = arr(offset) & 0xff
                val size = ((arr(offset + 4) & 0xff) << 24) |
                    ((arr(offset + 5) & 0xff) << 16) |
                    ((arr(offset + 6) & 0xff) << 8) |
                    (arr(offset + 7) & 0xff)
                if offset + 8 + size > len then ()
                else
                    val content = new String(arr, offset + 8, size, java.nio.charset.StandardCharsets.UTF_8)
                    val source  = if streamType == 2 then LogEntry.Source.Stderr else LogEntry.Source.Stdout
                    emitFromPayload(content, source)
                    parse(offset + 8 + size)
                end if
        end parse

        parse(0)
        result.result()
    end demuxStreamAssembled

    /** Parse a Docker timestamp prefix from a log line.
      *
      * Docker timestamp format: `2024-01-01T00:00:00.000000000Z content` The timestamp is followed by a space, then the content.
      */
    private def parseTimestampLine(line: String): (Maybe[Instant], String) =
        val spaceIdx = line.indexOf(' ')
        if spaceIdx >= HttpContainerBackend.minDockerTimestampLength then
            val tsStr = line.substring(0, spaceIdx)
            parseInstant(Some(tsStr)) match
                case Present(ts) => (Present(ts), line.substring(spaceIdx + 1))
                case Absent      => (Absent, line)
        else
            (Absent, line)
        end if
    end parseTimestampLine

    // --- Stats mapping ---

    private def mapStatsResponse(dto: StatsResponse, now: Instant): Container.Stats =
        val cpuDelta    = dto.cpu_stats.cpu_usage.total_usage - dto.precpu_stats.cpu_usage.total_usage
        val systemDelta = dto.cpu_stats.system_cpu_usage - dto.precpu_stats.system_cpu_usage
        val onlineCpus  = if dto.cpu_stats.online_cpus > 0 then dto.cpu_stats.online_cpus else 1
        val cpuPercent  = if systemDelta > 0 then (cpuDelta.toDouble / systemDelta.toDouble) * onlineCpus * 100.0 else 0.0

        val memLimit   = dto.memory_stats.limit
        val memUsage   = dto.memory_stats.usage
        val memPercent = if memLimit > 0 then (memUsage.toDouble / memLimit.toDouble) * 100.0 else 0.0

        val networkMap = Dict.from(dto.networks.getOrElse(Map.empty).map { case (ifName, net) =>
            ifName -> Stats.Net(
                rxBytes = net.rx_bytes,
                rxPackets = Present(net.rx_packets),
                rxErrors = Present(net.rx_errors),
                rxDropped = Present(net.rx_dropped),
                txBytes = net.tx_bytes,
                txPackets = Present(net.tx_packets),
                txErrors = Present(net.tx_errors),
                txDropped = Present(net.tx_dropped)
            )
        })

        val blkioEntries = dto.blkio_stats.io_service_bytes_recursive.getOrElse(Seq.empty)
        val readBytes    = blkioEntries.filter(_.op.equalsIgnoreCase("read")).map(_.value).sum
        val writeBytes   = blkioEntries.filter(_.op.equalsIgnoreCase("write")).map(_.value).sum

        val readAt = parseInstant(Option(dto.read).filter(_.nonEmpty)).getOrElse(now)

        Stats(
            readAt = readAt,
            cpu = Stats.Cpu(
                totalUsage = if dto.cpu_stats.cpu_usage.total_usage > 0 then Present(dto.cpu_stats.cpu_usage.total_usage) else Absent,
                systemUsage = if dto.cpu_stats.system_cpu_usage > 0 then Present(dto.cpu_stats.system_cpu_usage) else Absent,
                onlineCpus = onlineCpus,
                usagePercent = cpuPercent
            ),
            memory = Stats.Memory(
                usage = memUsage,
                maxUsage = if dto.memory_stats.max_usage > 0 then Present(dto.memory_stats.max_usage) else Absent,
                limit = if memLimit > 0 then Present(memLimit) else Absent,
                usagePercent = memPercent
            ),
            network = networkMap,
            blockIo = Stats.BlockIo(readBytes = readBytes, writeBytes = writeBytes),
            pids = Stats.Pids(
                current = dto.pids_stats.current,
                limit = Maybe.fromOption(dto.pids_stats.limit.filter(_ > 0).filter(_ < Long.MaxValue.toDouble).map(_.toLong))
            )
        )
    end mapStatsResponse

    // --- Inspect mapping ---

    private def mapInspectToInfo(dto: InspectResponse): Container.Info =
        val healthStatusStr = Maybe.fromOption(dto.State.Health)
            .flatMap(h => if h.Status.nonEmpty then Present(h.Status) else Absent)
        val healthStatus = healthStatusStr.map(HealthStatus.parse).getOrElse(HealthStatus.NoHealthcheck)

        val portsSeq = dto.NetworkSettings.Ports.getOrElse(Map.empty).flatMap { case (portProto, maybeMappings) =>
            val parts         = portProto.split("/")
            val containerPort = parts(0).toIntOption.getOrElse(0)
            val protocol = if parts.length > 1 then
                parts(1) match
                    case "udp"  => Config.Protocol.UDP
                    case "sctp" => Config.Protocol.SCTP
                    case _      => Config.Protocol.TCP
            else Config.Protocol.TCP
            val mappings = maybeMappings.getOrElse(Seq.empty)
            if mappings.nonEmpty then
                mappings.map { m =>
                    Config.PortBinding(
                        containerPort = containerPort,
                        hostPort = if m.HostPort.nonEmpty then
                            Result.catching[NumberFormatException](m.HostPort.toInt).getOrElse(0)
                        else 0,
                        hostIp = if m.HostIp.nonEmpty then m.HostIp else "",
                        protocol = protocol
                    )
                }
            else
                Seq(Config.PortBinding(
                    containerPort = containerPort,
                    hostPort = 0,
                    hostIp = "",
                    protocol = protocol
                ))
            end if
        }

        val networks = Dict.from(dto.NetworkSettings.Networks.getOrElse(Map.empty).map { case (name, ep) =>
            // Use the actual network ID as key (not the name) so lookups by Network.Id work
            val key = if ep.NetworkID.nonEmpty then Network.Id(ep.NetworkID) else Network.Id(name)
            key -> Info.NetworkEndpoint(
                networkId = Network.Id(ep.NetworkID),
                endpointId = ep.EndpointID,
                gateway = ep.Gateway,
                ipAddress = ep.IPAddress,
                ipPrefixLen = ep.IPPrefixLen,
                macAddress = ep.MacAddress
            )
        })

        val mounts = Chunk.from(dto.Mounts.getOrElse(Seq.empty).map { m =>
            m.Type.toLowerCase match
                case "volume" =>
                    val volName = if m.Name.nonEmpty then m.Name else m.Source
                    Config.Mount.Volume(Container.Volume.Id(volName), Path(m.Destination), !m.RW): Config.Mount
                case "tmpfs" =>
                    Config.Mount.Tmpfs(Path(m.Destination), Absent): Config.Mount
                case _ =>
                    Config.Mount.Bind(Path(m.Source), Path(m.Destination), !m.RW): Config.Mount
        })

        val envMap: Dict[String, String] = Dict.from(dto.Config.Env.getOrElse(Seq.empty).flatMap { entry =>
            val idx = entry.indexOf('=')
            if idx >= 0 then Seq(entry.substring(0, idx) -> entry.substring(idx + 1))
            else Seq.empty
        }.toMap)

        val imageName = if dto.Config.Image.nonEmpty then dto.Config.Image else dto.Image
        val imageRef  = ContainerImage.parse(imageName).getOrElse(ContainerImage(imageName))
        val platform  = Container.Platform.parse(dto.Platform).getOrElse(Container.Platform("", ""))

        Info(
            id = Container.Id(dto.Id),
            name = dto.Name.stripPrefix("/"),
            image = imageRef,
            imageId = ContainerImage.Id(dto.Image),
            state = parseState(dto.State.Status),
            exitCode = if dto.State.ExitCode == 0 then ExitCode.Success else ExitCode.Failure(dto.State.ExitCode),
            pid = dto.State.Pid,
            startedAt = parseInstant(Option(dto.State.StartedAt).filter(_.nonEmpty)),
            finishedAt = parseInstant(Option(dto.State.FinishedAt).filter(_.nonEmpty)),
            healthStatus = healthStatus,
            ports = Chunk.from(portsSeq),
            mounts = mounts,
            labels = Dict.from(dto.Config.Labels.getOrElse(Map.empty)),
            env = envMap,
            command = dto.Config.Cmd.getOrElse(Seq.empty).mkString(" "),
            createdAt = parseInstant(Option(dto.Created).filter(_.nonEmpty)).getOrElse(Instant.Epoch),
            restartCount = dto.RestartCount,
            driver = dto.Driver,
            platform = platform,
            networkSettings = Info.NetworkSettings(
                ipAddress = if dto.NetworkSettings.IPAddress.nonEmpty then Present(dto.NetworkSettings.IPAddress) else Absent,
                gateway = if dto.NetworkSettings.Gateway.nonEmpty then Present(dto.NetworkSettings.Gateway) else Absent,
                macAddress = if dto.NetworkSettings.MacAddress.nonEmpty then Present(dto.NetworkSettings.MacAddress) else Absent,
                networks = networks
            )
        )
    end mapInspectToInfo

    // --- Network mapping ---

    private def mapNetworkInfo(dto: NetworkInfoDto): Container.Network.Info =
        val containers = Dict.from(dto.Containers.getOrElse(Map.empty).map { case (cid, ep) =>
            Container.Id(cid) -> Info.NetworkEndpoint(
                networkId = Network.Id(dto.Id),
                endpointId = ep.EndpointID,
                gateway = "",
                ipAddress = ep.IPv4Address,
                ipPrefixLen = 0,
                macAddress = ep.MacAddress
            )
        })
        Container.Network.Info(
            id = Network.Id(dto.Id),
            name = dto.Name,
            driver = Container.NetworkDriver.parse(dto.Driver),
            scope = dto.Scope,
            internal = dto.Internal,
            attachable = dto.Attachable,
            enableIPv6 = dto.EnableIPv6,
            labels = Dict.from(dto.Labels.getOrElse(Map.empty)),
            options = Dict.from(dto.Options.getOrElse(Map.empty)),
            containers = containers,
            createdAt = parseInstant(Option(dto.Created).filter(_.nonEmpty)).getOrElse(Instant.Epoch)
        )
    end mapNetworkInfo

    // --- Volume mapping ---

    private def mapVolumeInfo(dto: VolumeInfoDto): Container.Volume.Info =
        Container.Volume.Info(
            name = Container.Volume.Id(dto.Name),
            driver = dto.Driver,
            mountpoint = dto.Mountpoint,
            labels = Dict.from(dto.Labels.getOrElse(Map.empty)),
            options = Dict.from(dto.Options.getOrElse(Map.empty)),
            createdAt = parseInstant(Option(dto.CreatedAt).filter(_.nonEmpty)).getOrElse(Instant.Epoch),
            scope = dto.Scope
        )

    // --- DTO case classes for Docker API ---

    private case class CreateContainerRequest(
        Image: String,
        Cmd: Option[Seq[String]] = None,
        Env: Seq[String] = Seq.empty,
        Hostname: String = "",
        User: String = "",
        Labels: Map[String, String] = Map.empty,
        Tty: Boolean = false,
        OpenStdin: Boolean = false,
        StopSignal: String = "",
        WorkingDir: String = "",
        ExposedPorts: Option[Map[String, Map[String, String]]] = None,
        HostConfig: HostConfig = HostConfig(),
        NetworkingConfig: Option[NetworkingConfig] = None
    ) derives Schema

    private case class NetworkingConfig(
        EndpointsConfig: Map[String, EndpointConfig] = Map.empty
    ) derives Schema

    private case class EndpointConfig(
        Aliases: Seq[String] = Seq.empty
    ) derives Schema

    private case class HostConfig(
        Binds: Seq[String] = Seq.empty,
        PortBindings: Map[String, Seq[PortBindingEntry]] = Map.empty,
        NetworkMode: String = "",
        Dns: Seq[String] = Seq.empty,
        ExtraHosts: Seq[String] = Seq.empty,
        Memory: Long = 0,
        MemorySwap: Long = 0,
        NanoCPUs: Long = 0,
        CpusetCpus: String = "",
        PidsLimit: Long = 0,
        Privileged: Boolean = false,
        CapAdd: Seq[String] = Seq.empty,
        CapDrop: Seq[String] = Seq.empty,
        ReadonlyRootfs: Boolean = false,
        AutoRemove: Boolean = false,
        RestartPolicy: RestartPolicyEntry = RestartPolicyEntry(),
        Mounts: Seq[MountEntry] = Seq.empty,
        Tmpfs: Map[String, String] = Map.empty
    ) derives Schema

    private case class PortBindingEntry(
        HostIp: String = "",
        HostPort: String = ""
    ) derives Schema

    private case class RestartPolicyEntry(
        Name: String = "",
        MaximumRetryCount: Int = 0
    ) derives Schema

    private case class MountEntry(
        Type: String = "",
        Source: String = "",
        Target: String = ""
    ) derives Schema

    private case class CreateContainerResponse(
        Id: String = "",
        Warnings: Seq[String] = Seq.empty
    ) derives Schema

    private case class WaitResponse(
        StatusCode: Int = 0
    ) derives Schema

    private case class UpdateRequest(
        Memory: Long = 0,
        MemorySwap: Long = 0,
        NanoCPUs: Long = 0,
        CpusetCpus: String = "",
        PidsLimit: Long = 0,
        RestartPolicy: RestartPolicyEntry = RestartPolicyEntry()
    ) derives Schema

    private case class ListContainerEntry(
        Id: String = "",
        Names: Seq[String] = Seq.empty,
        Image: String = "",
        ImageID: String = "",
        Command: String = "",
        State: String = "",
        Status: String = "",
        Created: Long = 0,
        Ports: Seq[ListPortEntry] = Seq.empty,
        Labels: Map[String, String] = Map.empty,
        Mounts: Seq[ListMountEntry] = Seq.empty
    ) derives Schema

    private case class ListPortEntry(
        IP: String = "",
        PrivatePort: Int = 0,
        PublicPort: Int = 0,
        Type: String = "tcp"
    ) derives Schema

    private case class ListMountEntry(
        Type: String = "",
        Name: String = "",
        Source: String = "",
        Destination: String = ""
    ) derives Schema

    private case class PruneContainersResponse(
        ContainersDeleted: Option[Seq[String]] = None,
        SpaceReclaimed: Long = 0
    ) derives Schema

    // --- DTOs for container inspect ---

    private case class InspectResponse(
        Id: String = "",
        Name: String = "",
        Image: String = "",
        Created: String = "",
        State: InspectStateDto = InspectStateDto(),
        Config: InspectConfigDto = InspectConfigDto(),
        NetworkSettings: InspectNetworkSettingsDto = InspectNetworkSettingsDto(),
        RestartCount: Int = 0,
        Driver: String = "",
        Mounts: Option[Seq[InspectMountDto]] = None,
        Platform: String = ""
    ) derives Schema

    private case class InspectStateDto(
        Status: String = "",
        Running: Boolean = false,
        Pid: Int = 0,
        ExitCode: Int = 0,
        StartedAt: String = "",
        FinishedAt: String = "",
        Health: Option[InspectHealthDto] = None
    ) derives Schema

    private case class InspectHealthDto(
        Status: String = ""
    ) derives Schema

    private case class InspectConfigDto(
        Image: String = "",
        Cmd: Option[Seq[String]] = None,
        Env: Option[Seq[String]] = None,
        Labels: Option[Map[String, String]] = None,
        Tty: Boolean = false
    ) derives Schema

    private case class InspectPortMappingDto(
        HostIp: String = "",
        HostPort: String = ""
    ) derives Schema

    private case class InspectNetworkSettingsDto(
        IPAddress: String = "",
        Gateway: String = "",
        MacAddress: String = "",
        Ports: Option[Map[String, Option[Seq[InspectPortMappingDto]]]] = None,
        Networks: Option[Map[String, InspectNetworkEndpointDto]] = None
    ) derives Schema

    private case class InspectNetworkEndpointDto(
        NetworkID: String = "",
        EndpointID: String = "",
        Gateway: String = "",
        IPAddress: String = "",
        IPPrefixLen: Int = 0,
        MacAddress: String = ""
    ) derives Schema

    private case class InspectMountDto(
        Type: String = "",
        Name: String = "",
        Source: String = "",
        Destination: String = "",
        RW: Boolean = true
    ) derives Schema

    // --- DTOs for container stats ---

    private case class StatsResponse(
        read: String = "",
        cpu_stats: CpuStatsDto = CpuStatsDto(),
        precpu_stats: CpuStatsDto = CpuStatsDto(),
        memory_stats: MemoryStatsDto = MemoryStatsDto(),
        networks: Option[Map[String, NetworkStatsDto]] = None,
        blkio_stats: BlkioStatsDto = BlkioStatsDto(),
        pids_stats: PidsStatsDto = PidsStatsDto()
    ) derives Schema

    private case class CpuStatsDto(
        cpu_usage: CpuUsageDto = CpuUsageDto(),
        system_cpu_usage: Long = 0,
        online_cpus: Int = 0
    ) derives Schema

    private case class CpuUsageDto(
        total_usage: Long = 0
    ) derives Schema

    private case class MemoryStatsDto(
        usage: Long = 0,
        max_usage: Long = 0,
        limit: Long = 0
    ) derives Schema

    private case class NetworkStatsDto(
        rx_bytes: Long = 0,
        rx_packets: Long = 0,
        rx_errors: Long = 0,
        rx_dropped: Long = 0,
        tx_bytes: Long = 0,
        tx_packets: Long = 0,
        tx_errors: Long = 0,
        tx_dropped: Long = 0
    ) derives Schema

    private case class BlkioStatsDto(
        io_service_bytes_recursive: Option[Seq[BlkioEntryDto]] = None
    ) derives Schema

    private case class BlkioEntryDto(
        op: String = "",
        value: Long = 0
    ) derives Schema

    private case class PidsStatsDto(
        current: Long = 0,
        limit: Option[Double] = None
    ) derives Schema

    // --- DTOs for top, changes, exec ---

    private case class TopResponse(
        Titles: Seq[String] = Seq.empty,
        Processes: Seq[Seq[String]] = Seq.empty
    ) derives Schema

    private case class ChangeEntryDto(
        Path: String = "",
        Kind: Int = 0
    ) derives Schema

    private case class ExecCreateRequest(
        Cmd: Seq[String] = Seq.empty,
        AttachStdin: Boolean = false,
        AttachStdout: Boolean = true,
        AttachStderr: Boolean = true,
        Env: Seq[String] = Seq.empty,
        WorkingDir: String = ""
    ) derives Schema

    private case class ExecCreateResponse(
        Id: String = ""
    ) derives Schema

    private case class ExecStartRequest(
        Detach: Boolean = false
    ) derives Schema

    private case class ExecInspectResponse(
        ExitCode: Int = 0
    ) derives Schema

    // --- DTOs for file stat ---

    private case class FileStatDto(
        name: String = "",
        size: Long = 0,
        mode: Int = 0,
        mtime: String = "",
        linkTarget: String = ""
    ) derives Schema

    // --- DTOs for image operations ---

    private case class ImageInspectDto(
        Id: String = "",
        RepoTags: Option[Seq[String]] = None,
        RepoDigests: Option[Seq[String]] = None,
        Created: String = "",
        Size: Long = 0,
        Architecture: String = "",
        Os: String = "",
        Config: ImageConfigDto = ImageConfigDto()
    ) derives Schema

    private case class ImageConfigDto(
        Labels: Option[Map[String, String]] = None
    ) derives Schema

    private case class ImageListEntryDto(
        Id: String = "",
        RepoTags: Option[Seq[String]] = None,
        RepoDigests: Option[Seq[String]] = None,
        Created: Long = 0,
        Size: Long = 0,
        Labels: Option[Map[String, String]] = None
    ) derives Schema

    private case class ImageDeleteEntryDto(
        Untagged: Option[String] = None,
        Deleted: Option[String] = None
    ) derives Schema

    private case class ImageSearchEntryDto(
        name: String = "",
        description: String = "",
        star_count: Int = 0,
        is_official: Boolean = false,
        is_automated: Boolean = false
    ) derives Schema

    private case class ImageHistoryEntryDto(
        Id: String = "",
        Created: Long = 0,
        CreatedBy: String = "",
        Size: Long = 0,
        Tags: Option[Seq[String]] = None,
        Comment: String = ""
    ) derives Schema

    private case class ImagePruneResponseDto(
        ImagesDeleted: Option[Seq[ImageDeleteEntryDto]] = None,
        SpaceReclaimed: Long = 0
    ) derives Schema

    private case class ImageCommitResponseDto(
        Id: String = ""
    ) derives Schema

    private case class PullProgressErrorDto(
        message: String = ""
    ) derives Schema

    private case class PullProgressDto(
        status: Option[String] = None,
        id: Option[String] = None,
        progress: Option[String] = None,
        error: Option[String] = None,
        errorDetail: Option[PullProgressErrorDto] = None
    ) derives Schema

    private case class BuildProgressErrorDto(
        message: String = ""
    ) derives Schema

    private case class BuildAuxIdDto(
        ID: String = ""
    ) derives Schema

    private case class BuildProgressDto(
        stream: Option[String] = None,
        status: Option[String] = None,
        progress: Option[String] = None,
        error: Option[String] = None,
        errorDetail: Option[BuildProgressErrorDto] = None,
        aux: Option[BuildAuxIdDto] = None
    ) derives Schema

    // --- DTOs for checkpoint ---

    private case class CheckpointCreateRequest(
        CheckpointID: String = ""
    ) derives Schema

    // --- DTOs for network operations ---

    private case class IpamPoolDto(
        Subnet: String = "",
        Gateway: String = "",
        IPRange: String = ""
    ) derives Schema

    private case class IpamDto(
        Driver: String = "default",
        Config: Seq[IpamPoolDto] = Seq.empty
    ) derives Schema

    private case class NetworkCreateRequest(
        Name: String = "",
        Driver: String = "bridge",
        Internal: Boolean = false,
        Attachable: Boolean = true,
        EnableIPv6: Boolean = false,
        Labels: Map[String, String] = Map.empty,
        Options: Map[String, String] = Map.empty,
        IPAM: IpamDto = IpamDto()
    ) derives Schema

    private case class NetworkCreateResponse(
        Id: String = ""
    ) derives Schema

    private case class NetworkContainerDto(
        EndpointID: String = "",
        MacAddress: String = "",
        IPv4Address: String = "",
        IPv6Address: String = ""
    ) derives Schema

    private case class NetworkInfoDto(
        Id: String = "",
        Name: String = "",
        Driver: String = "",
        Scope: String = "",
        Internal: Boolean = false,
        Attachable: Boolean = false,
        EnableIPv6: Boolean = false,
        Labels: Option[Map[String, String]] = None,
        Options: Option[Map[String, String]] = None,
        Containers: Option[Map[String, NetworkContainerDto]] = None,
        Created: String = ""
    ) derives Schema

    private case class EndpointConfigDto(
        Aliases: Seq[String] = Seq.empty
    ) derives Schema

    private case class NetworkConnectRequest(
        Container: String = "",
        EndpointConfig: EndpointConfigDto = EndpointConfigDto()
    ) derives Schema

    private case class NetworkDisconnectRequest(
        Container: String = "",
        Force: Boolean = false
    ) derives Schema

    private case class NetworkPruneResponse(
        NetworksDeleted: Option[Seq[String]] = None
    ) derives Schema

    // --- DTOs for volume operations ---

    private case class VolumeCreateRequest(
        Name: String = "",
        Driver: String = "local",
        DriverOpts: Map[String, String] = Map.empty,
        Labels: Map[String, String] = Map.empty
    ) derives Schema

    private case class VolumeInfoDto(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Labels: Option[Map[String, String]] = None,
        Options: Option[Map[String, String]] = None,
        CreatedAt: String = "",
        Scope: String = ""
    ) derives Schema

    private case class VolumeListResponse(
        Volumes: Option[Seq[VolumeInfoDto]] = None,
        Warnings: Option[Seq[String]] = None
    ) derives Schema

    private case class VolumePruneResponse(
        VolumesDeleted: Option[Seq[String]] = None,
        SpaceReclaimed: Long = 0
    ) derives Schema

end HttpContainerBackend

private[kyo] object HttpContainerBackend:

    /** Default Docker/Podman Engine API version targeted by the HTTP backend. Override via
      * `BackendConfig.UnixSocket(path, apiVersion = ...)` if you need to pin a different revision.
      */
    val defaultApiVersion: String = "v1.43"

    /** Minimum length of a Docker-formatted timestamp prefix (`2024-01-01T00:00:00Z` = 20 chars). A space index below this means the line
      * has no timestamp prefix.
      */
    private val minDockerTimestampLength: Int = 20

    /** Detect available container runtime by checking Unix socket paths.
      *
      * Resolution order:
      *   1. Explicit env vars (Podman convention: `CONTAINER_HOST` first, then Docker's `DOCKER_HOST`). When set, the value MUST be a
      *      `unix://...` URI or a bare absolute path; any other transport (`tcp://`, `ssh://`, `npipe://`, `fd://`) or unrecognized format
      *      fails with [[ContainerBackendUnavailableException]] rather than silently falling through. When set, the user-supplied path is
      *      used as-is — no merging with defaults, no existence check.
      *   2. If neither env var is set, probe common socket paths:
      *      - Podman: `$XDG_RUNTIME_DIR/podman/podman.sock`
      *      - Docker: `/var/run/docker.sock`
      *      - Docker rootless: `$HOME/.docker/run/docker.sock` Existing-on-disk filter applied; CLI fallback (`docker context`,
      *        `podman info`) if nothing matches.
      *
      * Tries `_ping` on each candidate, returns first that responds with `OK`.
      */
    def detect(meter: Meter = Meter.Noop, apiVersion: String = defaultApiVersion)(using
        Frame
    ): HttpContainerBackend < (Async & Abort[ContainerException]) =
        candidateSocketPaths.map { candidates =>
            def tryNext(remaining: Seq[String]): HttpContainerBackend < (Async & Abort[ContainerException]) =
                if remaining.isEmpty then
                    Abort.fail(ContainerBackendUnavailableException(
                        "http",
                        "No container runtime socket found. Checked: CONTAINER_HOST, DOCKER_HOST, " +
                            "$XDG_RUNTIME_DIR/podman/podman.sock, /var/run/docker.sock, $HOME/.docker/run/docker.sock"
                    ))
                else
                    val path    = remaining.head
                    val backend = new HttpContainerBackend(path, apiVersion, meter)
                    Abort.run[ContainerException](backend.detect()).map {
                        case Result.Success(_) =>
                            Log.info(s"kyo-pod: using HTTP backend at $path (apiVersion=${backend.apiVersion})").andThen(backend)
                        case Result.Failure(_) => tryNext(remaining.tail)
                        case Result.Panic(ex) =>
                            Log.warn(s"Unexpected error pinging socket $path", ex).andThen(
                                tryNext(remaining.tail)
                            )
                    }
                end if
            end tryNext
            tryNext(candidates)
        }

    /** Parse a `CONTAINER_HOST` / `DOCKER_HOST` env var value into a Unix socket path.
      *
      * Accepts:
      *   - `unix:///path/to/sock` → `/path/to/sock`
      *   - `/absolute/path` (legacy bare-path form) → `/absolute/path`
      *
      * Rejects (with [[ContainerBackendUnavailableException]]):
      *   - `tcp://host:port` — TCP transport not supported by the HTTP/Unix-socket backend
      *   - `ssh://user@host` — SSH transport not supported
      *   - `npipe://...` — Windows named pipes not supported
      *   - `fd://N` — systemd file-descriptor handoff not supported
      *   - anything else — unrecognized URI format
      *
      * Exposed at `private[kyo]` for unit testing of pure parsing logic.
      */
    private[kyo] def parseHostUri(envName: String, value: String)(using Frame): String < Abort[ContainerException] =
        def fail(transport: String, suggestion: String): Nothing < Abort[ContainerException] =
            Abort.fail(ContainerBackendUnavailableException(
                "http",
                s"$envName=$value uses $transport transport, which is not supported by the HTTP backend " +
                    s"(only Unix domain sockets). $suggestion"
            ))
        val unixPrefix = "unix://"
        if value.startsWith(unixPrefix) then
            val path = value.stripPrefix(unixPrefix)
            if path.isEmpty then
                Abort.fail(ContainerBackendUnavailableException(
                    "http",
                    s"$envName=$value has an empty socket path after 'unix://'. " +
                        s"Set $envName to 'unix:///path/to/socket' or unset it to fall back to auto-detect."
                ))
            else path
            end if
        else if value.startsWith("/") then value
        else if value.startsWith("tcp://") then
            fail("TCP", s"Use 'unix:///path/to/socket' or unset $envName to fall back to auto-detect.")
        else if value.startsWith("ssh://") then
            fail("SSH", s"Use 'unix:///path/to/socket' or unset $envName to fall back to auto-detect.")
        else if value.startsWith("npipe://") then
            fail("named-pipe (npipe)", s"Use 'unix:///path/to/socket' or unset $envName to fall back to auto-detect.")
        else if value.startsWith("fd://") then
            fail("fd", s"Use 'unix:///path/to/socket' or unset $envName to fall back to auto-detect.")
        else
            Abort.fail(ContainerBackendUnavailableException(
                "http",
                s"$envName=$value has an unrecognized URI format. " +
                    s"Set $envName to 'unix:///path/to/socket' or an absolute path, or unset it to fall back to auto-detect."
            ))
        end if
    end parseHostUri

    /** Resolve an explicit socket path from `CONTAINER_HOST` (preferred, Podman convention) or `DOCKER_HOST`.
      *
      * Returns `Present(path)` when either env var is set to a non-empty value (the value is parsed via [[parseHostUri]] and may fail with
      * [[ContainerBackendUnavailableException]] on unsupported transports). Returns `Absent` when both are unset or empty.
      */
    private def explicitSocketPath(using Frame): Maybe[String] < (Sync & Abort[ContainerException]) =
        def nonEmpty(name: String): Maybe[String] < Sync =
            kyo.System.env[String](name).map {
                case Present(v) if v.trim.nonEmpty => Present(v.trim)
                case _                             => Absent
            }
        nonEmpty("CONTAINER_HOST").map {
            case Present(v) => parseHostUri("CONTAINER_HOST", v).map(Present(_))
            case Absent =>
                nonEmpty("DOCKER_HOST").map {
                    case Present(v) => parseHostUri("DOCKER_HOST", v).map(Present(_))
                    case Absent     => Absent
                }
        }
    end explicitSocketPath

    /** Collect candidate socket paths.
      *
      * If `CONTAINER_HOST` or `DOCKER_HOST` is set, returns just that single path (no defaults, no existence filter — let the daemon ping
      * surface a clear connection error if the user's path is bad). Otherwise probes the default candidates per OS, filters to existing
      * paths on disk, and falls back to CLI discovery if none exist.
      */
    private def candidateSocketPaths(using Frame): Seq[String] < (Async & Abort[ContainerException]) =
        explicitSocketPath.map {
            case Present(path) => Seq(path)
            case Absent        => defaultCandidateSocketPaths
        }

    /** Default candidate probe — used only when neither `CONTAINER_HOST` nor `DOCKER_HOST` is set. */
    private def defaultCandidateSocketPaths(using Frame): Seq[String] < (Async & Abort[ContainerException]) =
        val xdgPodman: Maybe[String] < Sync =
            kyo.System.env[String]("XDG_RUNTIME_DIR").map {
                case Present(dir) => Present(s"$dir/podman/podman.sock")
                case Absent       => Absent
            }

        val homeDocker: Maybe[String] < Sync =
            kyo.System.env[String]("HOME").map {
                case Present(home) => Present(s"$home/.docker/run/docker.sock")
                case Absent        => Absent
            }

        val defaultSocket = "/var/run/docker.sock"

        xdgPodman.map { xp =>
            homeDocker.map { hd =>
                kyo.System.operatingSystem.map { os =>
                    // On macOS, prefer ~/.docker/run/docker.sock over /var/run/docker.sock
                    // because Docker Desktop on macOS uses the former, and /var/run/docker.sock
                    // may be symlinked to Podman Machine by Podman's installer — causing confusion
                    // when the user pulls images via `docker pull` (which hits Docker Desktop's socket).
                    val candidates =
                        if os == kyo.System.OS.MacOS then
                            xp.toList ++ hd.toList ++ Seq(defaultSocket)
                        else
                            xp.toList ++ Seq(defaultSocket) ++ hd.toList
                    // Filter to paths that exist on disk
                    Kyo.filter(candidates)(path => Path(path).exists).map { staticPaths =>
                        if staticPaths.nonEmpty then staticPaths
                        else discoverSocketsViaCli()
                    }
                }
            }
        }
    end defaultCandidateSocketPaths

    /** Try to discover socket paths by running CLI commands.
      *
      * Tries:
      *   - `docker context inspect --format '{{.Endpoints.docker.Host}}'` (returns `unix:///path`)
      *   - `podman info --format '{{.Host.RemoteSocket.Path}}'` (returns socket path)
      */
    private def discoverSocketsViaCli()(using Frame): Seq[String] < (Async & Abort[ContainerException]) =
        val dockerSocket = discoverViaCli(
            Command("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
        )
        val podmanSocket = discoverViaCli(
            Command("podman", "info", "--format", "{{.Host.RemoteSocket.Path}}")
        )
        dockerSocket.map { ds =>
            podmanSocket.map { ps =>
                ps.toList ++ ds.toList
            }
        }
    end discoverSocketsViaCli

    /** Run a CLI command and extract a Unix socket path from its output. */
    private def discoverViaCli(command: Command)(using Frame): Maybe[String] < Async =
        Abort.run[CommandException](command.redirectErrorStream(true).textWithExitCode).map {
            case Result.Success((output, exitCode)) if exitCode == ExitCode.Success =>
                val trimmed = output.trim
                if trimmed.startsWith("unix://") then
                    val path = trimmed.stripPrefix("unix://")
                    if path.nonEmpty then Present(path) else Absent
                else if trimmed.startsWith("/") then
                    Present(trimmed)
                else Absent
                end if
            case Result.Success(_) => Absent
            case Result.Failure(_) => Absent
            case Result.Panic(ex) =>
                Log.debug(s"CLI socket discovery failed: ${ex.getMessage}").andThen(
                    Absent: Maybe[String]
                )
        }
    end discoverViaCli

end HttpContainerBackend
