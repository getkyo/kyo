package kyo.internal

import kyo.*

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
    apiVersion: String = "v1.43",
    meter: Meter = Meter.Noop
) extends ContainerBackend(meter):

    import Container.*

    /** CLI command name derived from socket path — "podman" if the path contains "podman", else "docker". */
    private val cliCommand: String =
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

    /** Maps an HttpException to the appropriate ContainerException subtype based on HTTP status code.
      *
      * @param mapNotFound
      *   Function to create the appropriate NotFound exception subtype for the resource being accessed. Defaults to
      *   `ContainerException.NotFound` for container operations.
      */
    private def mapHttpError(
        httpEx: HttpException,
        id: String,
        mapNotFound: Maybe[String => ContainerException] = Absent
    )(using Frame): Nothing < Abort[ContainerException] =
        val notFoundMapper: String => ContainerException =
            mapNotFound.getOrElse((s: String) => ContainerException.NotFound(Container.Id(s)))
        httpEx match
            case e: HttpStatusException =>
                e.status.code match
                    case 304 => Abort.fail(ContainerException.AlreadyStopped(Container.Id(id)))
                    case 404 => Abort.fail(notFoundMapper(id))
                    case 409 => Abort.fail(ContainerException.AlreadyExists(id))
                    case _   => Abort.fail(ContainerException.General(s"HTTP ${e.status.code} for $id", e))
            case other =>
                Abort.fail(ContainerException.General(s"HTTP request failed for $id", other))
        end match
    end mapHttpError

    /** Runs an HTTP call, catching HttpException and mapping to ContainerException.
      *
      * @param mapNotFound
      *   Function to create the appropriate NotFound exception subtype for the resource being accessed. Defaults to
      *   `ContainerException.NotFound` for container operations.
      */
    private def withErrorMapping[A](
        id: String,
        mapNotFound: Maybe[String => ContainerException] = Absent
    )(
        v: A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[ContainerException]) =
        Abort.runWith[Closed](meter.run {
            Abort.runWith[HttpException](v) {
                case Result.Success(a) => a
                case Result.Failure(e) => mapHttpError(e, id, mapNotFound)
                case Result.Panic(e)   => Abort.fail(ContainerException.General(s"Unexpected error for $id", e))
            }
        }) {
            case Result.Success(a) => a
            case Result.Failure(_) => Abort.fail(ContainerException.General("Meter closed", "concurrency meter was closed"))
            case Result.Panic(ex)  => Abort.fail(ContainerException.General("Meter panicked", ex))
        }

    /** POST with empty body, accepting 200/201/204 as success. */
    private def postUnit(endpoint: String, id: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        withErrorMapping(id)(HttpClient.postText(url(endpoint), "").unit)

    /** POST with empty body, accepting 200/201/204/304 as success. 304 means "already in this state" — not an error for idempotent
      * operations.
      */
    private def postUnitAccept304(endpoint: String, id: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.runWith[Closed](meter.run {
            Abort.run[HttpException](HttpClient.postText(url(endpoint), "")).map {
                case Result.Success(_)                                              => ()
                case Result.Failure(e: HttpStatusException) if e.status.code == 304 => ()
                case Result.Failure(e)                                              => mapHttpError(e, id)
                case Result.Panic(e) => Abort.fail(ContainerException.General(s"Unexpected error for container $id", e))
            }
        }) {
            case Result.Success(a) => a
            case Result.Failure(_) => Abort.fail(ContainerException.General("Meter closed", "concurrency meter was closed"))
            case Result.Panic(ex)  => Abort.fail(ContainerException.General("Meter panicked", ex))
        }

    /** Build auth headers for registry operations. */
    private def authHeaders(auth: Maybe[ContainerImage.RegistryAuth]): Chunk[(String, String)] =
        auth match
            case Present(ra) =>
                // Encode the first auth entry as X-Registry-Auth header
                Maybe.fromOption(ra.auths.headOption) match
                    case Present((_, encoded)) => Chunk("X-Registry-Auth" -> encoded)
                    case Absent                => Chunk.empty
            case Absent => Chunk.empty

    // --- Container Lifecycle ---

    def create(config: Config)(using Frame): Container.Id < (Async & Abort[ContainerException]) =
        val envVars = config.command.env.map { case (k, v) => s"$k=$v" }.toSeq

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
            case Config.NetworkMode.Bridge       => "bridge"
            case Config.NetworkMode.Host         => "host"
            case Config.NetworkMode.None         => "none"
            case Config.NetworkMode.Shared(cid)  => s"container:${cid.value}"
            case Config.NetworkMode.Custom(name) => name
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
            Cmd = config.command.args.toSeq,
            Env = if envVars.nonEmpty then envVars else Seq.empty,
            Hostname = config.hostname.getOrElse(""),
            User = config.user.getOrElse(""),
            Labels = config.labels,
            Tty = config.allocateTty,
            OpenStdin = config.interactive,
            StopSignal = config.stopSignal.map(_.name).getOrElse(""),
            WorkingDir = config.command.workDir.map(_.toString).getOrElse(""),
            ExposedPorts = exposedPorts,
            HostConfig = hostConfig
        )

        val params        = config.name.map(n => Seq("name" -> n)).getOrElse(Seq.empty)
        val nameForErrors = config.name.getOrElse("new-container")

        Abort.runWith[HttpException](
            HttpClient.postJson[CreateContainerResponse](url("/containers/create", params*), body)
        ) {
            case Result.Success(resp) => Container.Id(resp.Id)
            case Result.Failure(e: HttpStatusException) =>
                e.status.code match
                    case 404 => Abort.fail(ContainerException.ImageNotFound(config.image))
                    case 409 => Abort.fail(ContainerException.AlreadyExists(nameForErrors))
                    case _   => Abort.fail(ContainerException.General(s"HTTP ${e.status.code} creating container", e))
            case Result.Failure(e) =>
                Abort.fail(ContainerException.General(s"Failed creating container $nameForErrors", e))
            case Result.Panic(e) =>
                Abort.fail(ContainerException.General(s"Unexpected error creating container $nameForErrors", e))
        }
    end create

    def start(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnitAccept304(s"/containers/${id.value}/start", id.value)

    def stop(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val seconds = timeout.toMillis / 1000
        postUnitAccept304(s"/containers/${id.value}/stop?t=$seconds", id.value).andThen {
            // Docker HTTP API returns before the container fully transitions to "exited".
            // Wait for the container to complete (consistent with ShellBackend's blocking `docker stop`).
            Abort.run[ContainerException](waitForExit(id)).unit
        }
    end stop

    def kill(id: Container.Id, signal: Container.Signal)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/kill?signal=${signal.name}", id.value)

    def restart(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val seconds = timeout.toMillis / 1000
        postUnit(s"/containers/${id.value}/restart?t=$seconds", id.value)

    def pause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/pause", id.value)

    def unpause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/unpause", id.value)

    def remove(id: Container.Id, force: Boolean, removeVolumes: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value) {
            HttpClient.deleteText(
                url(s"/containers/${id.value}", "force" -> force.toString, "v" -> removeVolumes.toString)
            ).unit
        }

    def rename(id: Container.Id, newName: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/rename?name=${java.net.URLEncoder.encode(newName, "UTF-8")}", id.value)

    def waitForExit(id: Container.Id)(using Frame): ExitCode < (Async & Abort[ContainerException]) =
        Abort.run[ContainerException](
            withErrorMapping(id.value) {
                HttpClient.postJson[WaitResponse](url(s"/containers/${id.value}/wait"), "")
            }.map(resp => ExitCode(resp.StatusCode))
        ).map {
            case Result.Success(code)                           => code
            case Result.Failure(_: ContainerException.NotFound) =>
                // Container was auto-removed before wait completed — exit code lost
                ExitCode.Success
            case Result.Failure(other) => Abort.fail(other)
            case Result.Panic(ex)      => Abort.fail(ContainerException.General(s"waitForExit panicked for ${id.value}", ex))
        }

    // --- Checkpoint/Restore ---

    def checkpoint(id: Container.Id, name: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val body     = CheckpointCreateRequest(CheckpointID = name)
        val jsonBody = Json[CheckpointCreateRequest].encode(body)
        withErrorMapping(id.value) {
            HttpClient.postText(
                url(s"/containers/${id.value}/checkpoints"),
                jsonBody,
                headers = Seq("Content-Type" -> "application/json")
            )
        }.unit
    end checkpoint

    def restore(id: Container.Id, checkpoint: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        postUnit(s"/containers/${id.value}/start?checkpoint=${java.net.URLEncoder.encode(checkpoint, "UTF-8")}", id.value)

    // --- Health ---

    def isHealthy(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException]) =
        state(id).map(_ == Container.State.Running)

    // --- Inspection ---

    def inspect(id: Container.Id)(using Frame): Container.Info < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value) {
            HttpClient.getJson[InspectResponse](url(s"/containers/${id.value}/json"))
        }.map(mapInspectToInfo)

    def state(id: Container.Id)(using Frame): Container.State < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value) {
            HttpClient.getJson[InspectResponse](url(s"/containers/${id.value}/json"))
        }.map(resp => parseState(resp.State.Status))

    def stats(id: Container.Id)(using Frame): Container.Stats < (Async & Abort[ContainerException]) =
        // Check container state first — Docker returns zero-valued stats for stopped
        // containers, so we need to detect and fail explicitly (consistent with ShellBackend).
        state(id).map { s =>
            if s == Container.State.Stopped || s == Container.State.Dead then
                Abort.fail[ContainerException](ContainerException.AlreadyStopped(id))
            else
                withErrorMapping(id.value) {
                    HttpClient.getJson[StatsResponse](url(s"/containers/${id.value}/stats", "stream" -> "false"))
                }.map(mapStatsResponse)
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
        withErrorMapping(id.value) {
            HttpClient.getJson[TopResponse](url(s"/containers/${id.value}/top", "ps_args" -> psArgs))
        }.map { resp =>
            TopResult(
                titles = Chunk.from(resp.Titles),
                processes = Chunk.from(resp.Processes.map(p => Chunk.from(p)))
            )
        }

    def changes(id: Container.Id)(using Frame): Chunk[Container.FilesystemChange] < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value) {
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
        // Phase 1: Create exec instance
        val createBody = execCreateBody(command)
        withErrorMapping(id.value) {
            HttpClient.postJson[ExecCreateResponse](url(s"/containers/${id.value}/exec"), createBody)
        }
            .map { createResp =>
                val execId = createResp.Id
                // Phase 2: Start exec and collect output
                val startBody = ExecStartRequest(Detach = false)
                withErrorMapping(id.value) {
                    HttpClient.postBinary(
                        url(s"/exec/$execId/start"),
                        Span.from(Json[ExecStartRequest].encode(startBody).getBytes),
                        headers = Seq("Content-Type" -> "application/json")
                    )
                }.map { outputBytes =>
                    // Phase 3: Inspect exec for exit code
                    val entries = demuxStream(outputBytes)
                    val stdout  = entries.filter(_.source == LogEntry.Source.Stdout).map(_.content).toSeq.mkString("\n")
                    val stderr  = entries.filter(_.source == LogEntry.Source.Stderr).map(_.content).toSeq.mkString("\n")
                    withErrorMapping(id.value) {
                        HttpClient.getJson[ExecInspectResponse](url(s"/exec/$execId/json"))
                    }
                        .map { inspectResp =>
                            val exitCode = if inspectResp.ExitCode == 0 then ExitCode.Success else ExitCode.Failure(inspectResp.ExitCode)
                            val result   = ExecResult(exitCode, stdout, stderr)
                            exitCode match
                                case ExitCode.Failure(code) if code == 125 || code == 126 || code == 127 =>
                                    Abort.fail[ContainerException](
                                        ContainerException.ExecFailed(
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
        // Use CLI subprocess with redirectErrorStream to merge stderr into stdout,
        // consistent with ShellBackend behavior
        Stream {
            val baseArgs = Chunk("exec") ++
                command.env.flatMap { case (k, v) => Chunk("-e", s"$k=$v") } ++
                command.workDir.map(w => Chunk("-w", w.toString)).getOrElse(Chunk.empty) ++
                Chunk(id.value) ++
                Chunk.from(command.args)
            val execCmd = Command((cliCommand +: baseArgs.toSeq)*).redirectErrorStream(true)
            Scope.run {
                Abort.runWith[CommandException](execCmd.spawn) {
                    case Result.Success(proc) =>
                        proc.stdout.mapChunk { bytes =>
                            val text  = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            val lines = text.split("\n").filter(_.trim.nonEmpty)
                            Chunk.from(lines.map(line => LogEntry(LogEntry.Source.Stdout, line)))
                        }.emit
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"execStream failed in ${id.value}", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"execStream panicked in ${id.value}", ex)
                        ).unit
                }
            }
        }

    def execInteractive(id: Container.Id, command: Command)(
        using Frame
    ): Container.AttachSession < (Async & Abort[ContainerException] & Scope) =
        val createBody = execCreateBody(command).copy(AttachStdin = true)
        withErrorMapping(id.value) {
            HttpClient.postJson[ExecCreateResponse](url(s"/containers/${id.value}/exec"), createBody)
        }.map { resp =>
            val execId    = resp.Id
            val startBody = ExecStartRequest(Detach = false)
            val bodyBytes = Span.from(Json[ExecStartRequest].encode(startBody).getBytes("UTF-8"))
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
                case Result.Failure(e)    => mapHttpError(e, id.value)
                case Result.Panic(e) =>
                    Abort.fail(ContainerException.General(s"Unexpected error for ${id.value}", e))
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
                case Result.Failure(e)    => mapHttpError(e, id.value)
                case Result.Panic(e) =>
                    Abort.fail(ContainerException.General(s"Unexpected error for ${id.value}", e))
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
        tail: Int,
        timestamps: Boolean
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
            withErrorMapping(id.value) {
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
        tail: Int,
        timestamps: Boolean
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
                Abort.runWith[HttpException](
                    byteStream.foreachChunk { chunk =>
                        Kyo.foreachDiscard(chunk) { span =>
                            val entries =
                                if isTty then parseRawLogStream(span, timestamps)
                                else demuxStream(span, timestamps)
                            Emit.value(entries)
                        }
                    }
                ) {
                    case Result.Success(_) => ()
                    case Result.Failure(e) => mapHttpError(e, id.value).unit
                    case Result.Panic(e) =>
                        Abort.fail(ContainerException.General(s"Unexpected error for log stream ${id.value}", e))
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
                        withErrorMapping(id.value) {
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
                    Abort.fail(ContainerException.General(
                        s"Failed to create tar for copyTo: ${id.value}",
                        cmdEx
                    ))
                case Result.Panic(ex) =>
                    Abort.fail(ContainerException.General(
                        s"Unexpected error creating tar for copyTo: ${id.value}",
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
        withErrorMapping(id.value) {
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
                                Abort.fail(ContainerException.General(
                                    s"Failed to rename extracted file for copyFrom: ${id.value}",
                                    cmdEx
                                ))
                            case Result.Panic(ex) =>
                                Abort.fail(ContainerException.General(
                                    s"Unexpected error renaming file for copyFrom: ${id.value}",
                                    ex
                                ))
                        }
                    else ()
                case Result.Failure(cmdEx) =>
                    Abort.fail(ContainerException.General(s"Failed to extract tar for copyFrom: ${id.value}", cmdEx))
                case Result.Panic(ex) =>
                    Abort.fail(ContainerException.General(s"Unexpected error extracting tar for copyFrom: ${id.value}", ex))
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
                        Json[FileStatDto].decode(decoded) match
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
                                Abort.fail(ContainerException.ParseError(
                                    "stat",
                                    s"Failed to parse file stat JSON for container ${id.value}"
                                ))
                        end match
                    case Absent =>
                        Abort.fail(ContainerException.ParseError(
                            "stat",
                            s"Missing X-Docker-Container-Path-Stat header for container ${id.value}"
                        ))
            case Result.Failure(e) => mapHttpError(e, id.value)
            case Result.Panic(e) =>
                Abort.fail(ContainerException.General(s"Unexpected error for stat on container ${id.value}", e))
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
                case Result.Failure(e) => mapHttpError(e, id.value).unit
                case Result.Panic(e) =>
                    Abort.fail(ContainerException.General(s"Unexpected error for export ${id.value}", e))
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
        val jsonBody = Json[UpdateRequest].encode(body)
        withErrorMapping(id.value) {
            HttpClient.postText(
                url(s"/containers/${id.value}/update"),
                jsonBody,
                headers = Seq("Content-Type" -> "application/json")
            )
        }.unit
    end update

    // --- Network on container ---

    def connectToNetwork(id: Container.Id, networkId: Container.Network.Id, aliases: Seq[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        networkConnect(networkId, id, aliases)

    def disconnectFromNetwork(id: Container.Id, networkId: Container.Network.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        networkDisconnect(networkId, id, force)

    // --- Container listing ---

    def list(all: Boolean, filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Container.Summary] < (Async & Abort[ContainerException]) =
        val params = Seq("all" -> all.toString) ++
            (if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
             else Seq.empty)
        withErrorMapping("list") {
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
                    labels = e.Labels,
                    mounts = Chunk.from(e.Mounts.map(_.Name)),
                    createdAt = Instant.fromJava(java.time.Instant.ofEpochSecond(e.Created))
                )
            })
        }
    end list

    def prune(filters: Map[String, Seq[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
            else Seq.empty
        withErrorMapping("prune") {
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
            val config = new Config(
                image = info.image,
                name = if info.name.nonEmpty then Present(info.name) else Absent
            )
            (info.id, config)
        }

    // --- Image operations ---

    private def imageNotFound(using Frame): String => ContainerException =
        s => ContainerException.ImageNotFound(ContainerImage.parse(s).getOrElse(ContainerImage(s)))

    def imagePull(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val ref = image.reference
        val args = Chunk(cliCommand, "pull") ++
            platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
            Chunk(ref)
        val pullCmd = Command(args.toSeq*).redirectErrorStream(true)
        Abort.runWith[CommandException](pullCmd.textWithExitCode) {
            case Result.Success((output, exitCode)) =>
                if exitCode.isSuccess then ()
                else if output.contains("not found") || output.contains("manifest unknown") then
                    Abort.fail[ContainerException](imageNotFound(ref))
                else
                    Abort.fail[ContainerException](ContainerException.General(
                        s"Pull failed for $ref: $output",
                        new RuntimeException(output)
                    ))
            case Result.Failure(cmdEx) =>
                Abort.fail[ContainerException](ContainerException.General(s"Pull failed for $ref", cmdEx))
            case Result.Panic(ex) =>
                Abort.fail[ContainerException](ContainerException.General(s"Pull panicked for $ref", ex))
        }
    end imagePull

    def imageEnsure(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        // Check if image exists locally first
        val ref = image.reference
        Abort.run[ContainerException](
            withErrorMapping(ref, Present(imageNotFound)) {
                HttpClient.getJson[ImageInspectDto](url(s"/images/$ref/json"))
            }
        ).map {
            case Result.Success(_) => () // Image exists locally
            case _                 => imagePull(image, platform, auth)
        }
    end imageEnsure

    def imagePullWithProgress(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Stream[ContainerImage.PullProgress, Async & Abort[ContainerException]] =
        Stream {
            val ref = image.reference
            // Check locally first via HTTP API
            Abort.run[ContainerException](
                withErrorMapping(ref, Present(imageNotFound)) {
                    HttpClient.getJson[ImageInspectDto](url(s"/images/$ref/json"))
                }
            ).map {
                case Result.Success(_) =>
                    // Image exists locally
                    Emit.value(Chunk(ContainerImage.PullProgress(
                        id = Absent,
                        status = s"$ref: image is up to date",
                        progress = Absent,
                        error = Absent
                    )))
                case _ =>
                    // Use CLI subprocess for streaming pull progress
                    val args = Chunk("pull") ++
                        platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
                        Chunk(ref)
                    val pullCmd = Command((cliCommand +: args.toSeq)*).redirectErrorStream(true)
                    Scope.run {
                        Abort.runWith[CommandException](pullCmd.spawn) {
                            case Result.Success(proc) =>
                                proc.stdout.mapChunk { bytes =>
                                    val text = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    Chunk.from(text.split("\n").filter(_.trim.nonEmpty).map { line =>
                                        ContainerImage.PullProgress(
                                            id = Absent,
                                            status = line,
                                            progress = Absent,
                                            error = Absent
                                        )
                                    })
                                }.emit
                            case Result.Failure(cmdEx) =>
                                Abort.fail[ContainerException](
                                    ContainerException.General(s"pull failed for $ref", cmdEx)
                                ).unit
                            case Result.Panic(ex) =>
                                Abort.fail[ContainerException](
                                    ContainerException.General(s"pull panicked for $ref", ex)
                                ).unit
                        }
                    }
            }
        }

    def imageList(all: Boolean, filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[ContainerImage.Summary] < (Async & Abort[ContainerException]) =
        val params = Seq("all" -> all.toString) ++
            (if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
             else Seq.empty)
        withErrorMapping("imageList") {
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
                    labels = e.Labels.getOrElse(Map.empty)
                )
            })
        }
    end imageList

    def imageInspect(image: ContainerImage)(using Frame): ContainerImage.Info < (Async & Abort[ContainerException]) =
        val ref = image.reference
        withErrorMapping(ref, Present(imageNotFound)) {
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
                labels = dto.Config.Labels.getOrElse(Map.empty),
                architecture = dto.Architecture,
                os = dto.Os
            )
        }
    end imageInspect

    def imageRemove(image: ContainerImage, force: Boolean, noPrune: Boolean)(
        using Frame
    ): Chunk[ContainerImage.DeleteResponse] < (Async & Abort[ContainerException]) =
        val ref = image.reference
        withErrorMapping(ref, Present(imageNotFound)) {
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
        withErrorMapping(ref, Present(imageNotFound)) {
            HttpClient.postText(url(s"/images/$ref/tag", "repo" -> repo, "tag" -> tag), "").unit
        }
    end imageTag

    def imageBuild(
        context: Stream[Byte, Sync],
        dockerfile: String,
        tags: Seq[String],
        buildArgs: Map[String, String],
        labels: Map[String, String],
        noCache: Boolean,
        pull: Boolean,
        target: Maybe[String],
        platform: Maybe[Container.Platform],
        auth: Maybe[ContainerImage.RegistryAuth]
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]] =
        // Stream-based context not supported, use imageBuildFromPath instead
        Stream {
            Abort.fail[ContainerException](
                ContainerException.NotSupported("imageBuild with stream context", "Use imageBuildFromPath instead")
            ).unit
        }

    def imageBuildFromPath(
        path: Path,
        dockerfile: String,
        tags: Seq[String],
        buildArgs: Map[String, String],
        labels: Map[String, String],
        noCache: Boolean,
        pull: Boolean,
        target: Maybe[String],
        platform: Maybe[Container.Platform],
        auth: Maybe[ContainerImage.RegistryAuth]
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]] =
        Stream {
            val args = Chunk("build") ++
                tags.flatMap(t => Chunk("-t", t)) ++
                (if dockerfile != "Dockerfile" then Chunk("-f", dockerfile) else Chunk.empty) ++
                Chunk.from(buildArgs.toSeq.flatMap { case (k, v) => Seq("--build-arg", s"$k=$v") }) ++
                Chunk.from(labels.toSeq.flatMap { case (k, v) => Seq("--label", s"$k=$v") }) ++
                (if noCache then Chunk("--no-cache") else Chunk.empty) ++
                (if pull then Chunk("--pull") else Chunk.empty) ++
                target.map(t => Chunk("--target", t)).getOrElse(Chunk.empty) ++
                platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
                Chunk(path.toString)
            val buildCmd = Command((cliCommand +: args.toSeq)*).redirectErrorStream(true)
            Scope.run {
                Abort.runWith[CommandException](buildCmd.spawn) {
                    case Result.Success(proc) =>
                        proc.stdout.mapChunk { bytes =>
                            val text = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            Chunk.from(text.split("\n").filter(_.trim.nonEmpty).map { line =>
                                ContainerImage.BuildProgress(
                                    stream = Present(line),
                                    status = Absent,
                                    progress = Absent,
                                    error = Absent,
                                    aux = Absent
                                )
                            })
                        }.emit
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"build failed for $path", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"build panicked for $path", ex)
                        ).unit
                }
            }
        }

    def imagePush(image: ContainerImage, auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val ref     = image.reference
        val headers = authHeaders(auth)
        withErrorMapping(ref, Present(imageNotFound)) {
            HttpClient.postText(url(s"/images/$ref/push"), "", headers = headers).unit
        }
    end imagePush

    def imageSearch(term: String, limit: Int, filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[ContainerImage.SearchResult] < (Async & Abort[ContainerException]) =
        val params = Seq("term" -> term) ++
            (if limit != Int.MaxValue then Seq("limit" -> limit.toString) else Seq.empty) ++
            (if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
             else Seq.empty)
        withErrorMapping("imageSearch") {
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
        withErrorMapping(ref, Present(imageNotFound)) {
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

    def imagePrune(filters: Map[String, Seq[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
            else Seq.empty
        withErrorMapping("imagePrune") {
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
                Json[ImageCommitResponseDto].decode(body) match
                    case Result.Success(dto) => dto.Id
                    case Result.Failure(_) =>
                        Abort.fail(ContainerException.ParseError("imageCommit", s"Failed to parse commit response"))
            case Result.Failure(e) => mapHttpError(e, container.value)
            case Result.Panic(e) =>
                Abort.fail(ContainerException.General(s"Unexpected error committing container ${container.value}", e))
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
            Labels = config.labels,
            Options = config.options,
            IPAM = ipamConfig
        )
        withErrorMapping(config.name) {
            HttpClient.postJson[NetworkCreateResponse](url("/networks/create"), body)
        }.map(resp => Container.Network.Id(resp.Id))
    end networkCreate

    def networkList(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Container.Network.Info] < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
            else Seq.empty
        withErrorMapping("networkList") {
            HttpClient.getJson[Seq[NetworkInfoDto]](url("/networks", params*))
        }.map(entries => Chunk.from(entries.map(mapNetworkInfo)))
    end networkList

    private def networkNotFound(using Frame): String => ContainerException =
        s => ContainerException.NetworkNotFound(Container.Network.Id(s))

    def networkInspect(id: Container.Network.Id)(using Frame): Container.Network.Info < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value, Present(networkNotFound)) {
            HttpClient.getJson[NetworkInfoDto](url(s"/networks/${id.value}"))
        }.map(mapNetworkInfo)

    def networkRemove(id: Container.Network.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value, Present(networkNotFound)) {
            HttpClient.deleteText(url(s"/networks/${id.value}")).unit
        }

    def networkConnect(network: Container.Network.Id, container: Container.Id, aliases: Seq[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val endpointConfig =
            if aliases.nonEmpty then Present(EndpointConfigDto(Aliases = aliases.toSeq))
            else Absent
        val body = NetworkConnectRequest(
            Container = container.value,
            EndpointConfig = endpointConfig.getOrElse(EndpointConfigDto())
        )
        val jsonBody = Json[NetworkConnectRequest].encode(body)
        withErrorMapping(network.value, Present(networkNotFound)) {
            HttpClient.postText(
                url(s"/networks/${network.value}/connect"),
                jsonBody,
                headers = Seq("Content-Type" -> "application/json")
            )
        }.unit
    end networkConnect

    def networkDisconnect(network: Container.Network.Id, container: Container.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val body     = NetworkDisconnectRequest(Container = container.value, Force = force)
        val jsonBody = Json[NetworkDisconnectRequest].encode(body)
        withErrorMapping(network.value, Present(networkNotFound)) {
            HttpClient.postText(
                url(s"/networks/${network.value}/disconnect"),
                jsonBody,
                headers = Seq("Content-Type" -> "application/json")
            )
        }.unit
    end networkDisconnect

    def networkPrune(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Container.Network.Id] < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
            else Seq.empty
        withErrorMapping("networkPrune") {
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
            DriverOpts = config.driverOpts,
            Labels = config.labels
        )
        withErrorMapping("volumeCreate") {
            HttpClient.postJson[VolumeInfoDto](url("/volumes/create"), body)
        }.map(mapVolumeInfo)
    end volumeCreate

    def volumeList(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Container.Volume.Info] < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
            else Seq.empty
        withErrorMapping("volumeList") {
            HttpClient.getJson[VolumeListResponse](url("/volumes", params*))
        }.map { resp =>
            Chunk.from(resp.Volumes.getOrElse(Seq.empty).map(mapVolumeInfo))
        }
    end volumeList

    private def volumeNotFound(using Frame): String => ContainerException = s => ContainerException.VolumeNotFound(Container.Volume.Id(s))

    def volumeInspect(id: Container.Volume.Id)(using Frame): Container.Volume.Info < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value, Present(volumeNotFound)) {
            HttpClient.getJson[VolumeInfoDto](url(s"/volumes/${id.value}"))
        }.map(mapVolumeInfo)

    def volumeRemove(id: Container.Volume.Id, force: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value, Present(volumeNotFound)) {
            HttpClient.deleteText(url(s"/volumes/${id.value}", "force" -> force.toString)).unit
        }

    def volumePrune(filters: Map[String, Seq[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException]) =
        val params =
            if filters.nonEmpty then Seq("filters" -> Json[Map[String, Seq[String]]].encode(filters.map((k, v) => k -> v.toSeq)))
            else Seq.empty
        withErrorMapping("volumePrune") {
            HttpClient.postJson[VolumePruneResponse](url("/volumes/prune", params*), "")
        }.map { resp =>
            Container.PruneResult(
                deleted = Chunk.from(resp.VolumesDeleted.getOrElse(Seq.empty)),
                spaceReclaimed = resp.SpaceReclaimed
            )
        }
    end volumePrune

    // --- Backend detection ---

    def detect()(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.runWith[HttpException](HttpClient.getText(url("/_ping"))) {
            case Result.Success(response) if response.trim == "OK" => ()
            case Result.Success(response) =>
                Abort.fail(ContainerException.BackendUnavailable(
                    "http",
                    s"Unexpected ping response from $socketPath: $response"
                ))
            case Result.Failure(e: HttpException) =>
                Abort.fail(ContainerException.BackendUnavailable(
                    "http",
                    s"Failed to ping container API at $socketPath: ${e.getMessage}"
                ))
            case Result.Panic(e) =>
                Abort.fail(ContainerException.BackendUnavailable(
                    "http",
                    s"Failed to ping container API at $socketPath: ${e.getMessage}"
                ))
        }

    /** Check if a container was created with TTY mode enabled. */
    private def isContainerTty(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException]) =
        withErrorMapping(id.value) {
            HttpClient.getJson[InspectResponse](url(s"/containers/${id.value}/json"))
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

    /** Parse a Docker timestamp prefix from a log line.
      *
      * Docker timestamp format: `2024-01-01T00:00:00.000000000Z content` The timestamp is followed by a space, then the content.
      */
    private def parseTimestampLine(line: String): (Maybe[Instant], String) =
        // Docker timestamps are like: 2024-01-01T00:00:00.000000000Z
        // Minimum length is ~20 chars for the timestamp + space
        val spaceIdx = line.indexOf(' ')
        if spaceIdx >= 20 then
            val tsStr = line.substring(0, spaceIdx)
            parseInstant(Some(tsStr)) match
                case Present(ts) => (Present(ts), line.substring(spaceIdx + 1))
                case Absent      => (Absent, line)
        else
            (Absent, line)
        end if
    end parseTimestampLine

    // --- Stats mapping ---

    private def mapStatsResponse(dto: StatsResponse): Container.Stats =
        val cpuDelta    = dto.cpu_stats.cpu_usage.total_usage - dto.precpu_stats.cpu_usage.total_usage
        val systemDelta = dto.cpu_stats.system_cpu_usage - dto.precpu_stats.system_cpu_usage
        val onlineCpus  = if dto.cpu_stats.online_cpus > 0 then dto.cpu_stats.online_cpus else 1
        val cpuPercent  = if systemDelta > 0 then (cpuDelta.toDouble / systemDelta.toDouble) * onlineCpus * 100.0 else 0.0

        val memLimit   = dto.memory_stats.limit
        val memUsage   = dto.memory_stats.usage
        val memPercent = if memLimit > 0 then (memUsage.toDouble / memLimit.toDouble) * 100.0 else 0.0

        val networkMap = dto.networks.getOrElse(Map.empty).map { case (ifName, net) =>
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
        }

        val blkioEntries = dto.blkio_stats.io_service_bytes_recursive.getOrElse(Seq.empty)
        val readBytes    = blkioEntries.filter(_.op.equalsIgnoreCase("read")).map(_.value).sum
        val writeBytes   = blkioEntries.filter(_.op.equalsIgnoreCase("write")).map(_.value).sum

        // Non-effectful Java interop boundary: fallback timestamp for stats
        val readAt = parseInstant(Option(dto.read).filter(_.nonEmpty)).getOrElse(Instant.fromJava(java.time.Instant.now()))

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

        val networks = dto.NetworkSettings.Networks.getOrElse(Map.empty).map { case (name, ep) =>
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
        }

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

        val envMap: Map[String, String] = dto.Config.Env.getOrElse(Seq.empty).flatMap { entry =>
            val idx = entry.indexOf('=')
            if idx >= 0 then Seq(entry.substring(0, idx) -> entry.substring(idx + 1))
            else Seq.empty
        }.toMap

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
            labels = dto.Config.Labels.getOrElse(Map.empty),
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
        val containers = dto.Containers.getOrElse(Map.empty).map { case (cid, ep) =>
            Container.Id(cid) -> Info.NetworkEndpoint(
                networkId = Network.Id(dto.Id),
                endpointId = ep.EndpointID,
                gateway = "",
                ipAddress = ep.IPv4Address,
                ipPrefixLen = 0,
                macAddress = ep.MacAddress
            )
        }
        Container.Network.Info(
            id = Network.Id(dto.Id),
            name = dto.Name,
            driver = Container.NetworkDriver.parse(dto.Driver),
            scope = dto.Scope,
            internal = dto.Internal,
            attachable = dto.Attachable,
            enableIPv6 = dto.EnableIPv6,
            labels = dto.Labels.getOrElse(Map.empty),
            options = dto.Options.getOrElse(Map.empty),
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
            labels = dto.Labels.getOrElse(Map.empty),
            options = dto.Options.getOrElse(Map.empty),
            createdAt = parseInstant(Option(dto.CreatedAt).filter(_.nonEmpty)).getOrElse(Instant.Epoch),
            scope = dto.Scope
        )

    // --- DTO case classes for Docker API ---

    private case class CreateContainerRequest(
        Image: String,
        Cmd: Seq[String] = Seq.empty,
        Env: Seq[String] = Seq.empty,
        Hostname: String = "",
        User: String = "",
        Labels: Map[String, String] = Map.empty,
        Tty: Boolean = false,
        OpenStdin: Boolean = false,
        StopSignal: String = "",
        WorkingDir: String = "",
        ExposedPorts: Option[Map[String, Map[String, String]]] = None,
        HostConfig: HostConfig = HostConfig()
    ) derives Json

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
    ) derives Json

    private case class PortBindingEntry(
        HostIp: String = "",
        HostPort: String = ""
    ) derives Json

    private case class RestartPolicyEntry(
        Name: String = "",
        MaximumRetryCount: Int = 0
    ) derives Json

    private case class MountEntry(
        Type: String = "",
        Source: String = "",
        Target: String = ""
    ) derives Json

    private case class CreateContainerResponse(
        Id: String = "",
        Warnings: Seq[String] = Seq.empty
    ) derives Json

    private case class WaitResponse(
        StatusCode: Int = 0
    ) derives Json

    private case class UpdateRequest(
        Memory: Long = 0,
        MemorySwap: Long = 0,
        NanoCPUs: Long = 0,
        CpusetCpus: String = "",
        PidsLimit: Long = 0,
        RestartPolicy: RestartPolicyEntry = RestartPolicyEntry()
    ) derives Json

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
    ) derives Json

    private case class ListPortEntry(
        IP: String = "",
        PrivatePort: Int = 0,
        PublicPort: Int = 0,
        Type: String = "tcp"
    ) derives Json

    private case class ListMountEntry(
        Type: String = "",
        Name: String = "",
        Source: String = "",
        Destination: String = ""
    ) derives Json

    private case class PruneContainersResponse(
        ContainersDeleted: Option[Seq[String]] = None,
        SpaceReclaimed: Long = 0
    ) derives Json

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
    ) derives Json

    private case class InspectStateDto(
        Status: String = "",
        Running: Boolean = false,
        Pid: Int = 0,
        ExitCode: Int = 0,
        StartedAt: String = "",
        FinishedAt: String = "",
        Health: Option[InspectHealthDto] = None
    ) derives Json

    private case class InspectHealthDto(
        Status: String = ""
    ) derives Json

    private case class InspectConfigDto(
        Image: String = "",
        Cmd: Option[Seq[String]] = None,
        Env: Option[Seq[String]] = None,
        Labels: Option[Map[String, String]] = None,
        Tty: Boolean = false
    ) derives Json

    private case class InspectNetworkSettingsDto(
        IPAddress: String = "",
        Gateway: String = "",
        MacAddress: String = "",
        Ports: Option[Map[String, Option[Seq[InspectPortMappingDto]]]] = None,
        Networks: Option[Map[String, InspectNetworkEndpointDto]] = None
    ) derives Json

    private case class InspectPortMappingDto(
        HostIp: String = "",
        HostPort: String = ""
    ) derives Json

    private case class InspectNetworkEndpointDto(
        NetworkID: String = "",
        EndpointID: String = "",
        Gateway: String = "",
        IPAddress: String = "",
        IPPrefixLen: Int = 0,
        MacAddress: String = ""
    ) derives Json

    private case class InspectMountDto(
        Type: String = "",
        Name: String = "",
        Source: String = "",
        Destination: String = "",
        RW: Boolean = true
    ) derives Json

    // --- DTOs for container stats ---

    private case class StatsResponse(
        read: String = "",
        cpu_stats: CpuStatsDto = CpuStatsDto(),
        precpu_stats: CpuStatsDto = CpuStatsDto(),
        memory_stats: MemoryStatsDto = MemoryStatsDto(),
        networks: Option[Map[String, NetworkStatsDto]] = None,
        blkio_stats: BlkioStatsDto = BlkioStatsDto(),
        pids_stats: PidsStatsDto = PidsStatsDto()
    ) derives Json

    private case class CpuStatsDto(
        cpu_usage: CpuUsageDto = CpuUsageDto(),
        system_cpu_usage: Long = 0,
        online_cpus: Int = 0
    ) derives Json

    private case class CpuUsageDto(
        total_usage: Long = 0
    ) derives Json

    private case class MemoryStatsDto(
        usage: Long = 0,
        max_usage: Long = 0,
        limit: Long = 0
    ) derives Json

    private case class NetworkStatsDto(
        rx_bytes: Long = 0,
        rx_packets: Long = 0,
        rx_errors: Long = 0,
        rx_dropped: Long = 0,
        tx_bytes: Long = 0,
        tx_packets: Long = 0,
        tx_errors: Long = 0,
        tx_dropped: Long = 0
    ) derives Json

    private case class BlkioStatsDto(
        io_service_bytes_recursive: Option[Seq[BlkioEntryDto]] = None
    ) derives Json

    private case class BlkioEntryDto(
        op: String = "",
        value: Long = 0
    ) derives Json

    private case class PidsStatsDto(
        current: Long = 0,
        limit: Option[Double] = None
    ) derives Json

    // --- DTOs for top, changes, exec ---

    private case class TopResponse(
        Titles: Seq[String] = Seq.empty,
        Processes: Seq[Seq[String]] = Seq.empty
    ) derives Json

    private case class ChangeEntryDto(
        Path: String = "",
        Kind: Int = 0
    ) derives Json

    private case class ExecCreateRequest(
        Cmd: Seq[String] = Seq.empty,
        AttachStdin: Boolean = false,
        AttachStdout: Boolean = true,
        AttachStderr: Boolean = true,
        Env: Seq[String] = Seq.empty,
        WorkingDir: String = ""
    ) derives Json

    private case class ExecCreateResponse(
        Id: String = ""
    ) derives Json

    private case class ExecStartRequest(
        Detach: Boolean = false
    ) derives Json

    private case class ExecInspectResponse(
        ExitCode: Int = 0
    ) derives Json

    // --- DTOs for file stat ---

    private case class FileStatDto(
        name: String = "",
        size: Long = 0,
        mode: Int = 0,
        mtime: String = "",
        linkTarget: String = ""
    ) derives Json

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
    ) derives Json

    private case class ImageConfigDto(
        Labels: Option[Map[String, String]] = None
    ) derives Json

    private case class ImageListEntryDto(
        Id: String = "",
        RepoTags: Option[Seq[String]] = None,
        RepoDigests: Option[Seq[String]] = None,
        Created: Long = 0,
        Size: Long = 0,
        Labels: Option[Map[String, String]] = None
    ) derives Json

    private case class ImageDeleteEntryDto(
        Untagged: Option[String] = None,
        Deleted: Option[String] = None
    ) derives Json

    private case class ImageSearchEntryDto(
        name: String = "",
        description: String = "",
        star_count: Int = 0,
        is_official: Boolean = false,
        is_automated: Boolean = false
    ) derives Json

    private case class ImageHistoryEntryDto(
        Id: String = "",
        Created: Long = 0,
        CreatedBy: String = "",
        Size: Long = 0,
        Tags: Option[Seq[String]] = None,
        Comment: String = ""
    ) derives Json

    private case class ImagePruneResponseDto(
        ImagesDeleted: Option[Seq[ImageDeleteEntryDto]] = None,
        SpaceReclaimed: Long = 0
    ) derives Json

    private case class ImageCommitResponseDto(
        Id: String = ""
    ) derives Json

    // --- DTOs for checkpoint ---

    private case class CheckpointCreateRequest(
        CheckpointID: String = ""
    ) derives Json

    // --- DTOs for network operations ---

    private case class IpamPoolDto(
        Subnet: String = "",
        Gateway: String = "",
        IPRange: String = ""
    ) derives Json

    private case class IpamDto(
        Driver: String = "default",
        Config: Seq[IpamPoolDto] = Seq.empty
    ) derives Json

    private case class NetworkCreateRequest(
        Name: String = "",
        Driver: String = "bridge",
        Internal: Boolean = false,
        Attachable: Boolean = true,
        EnableIPv6: Boolean = false,
        Labels: Map[String, String] = Map.empty,
        Options: Map[String, String] = Map.empty,
        IPAM: IpamDto = IpamDto()
    ) derives Json

    private case class NetworkCreateResponse(
        Id: String = ""
    ) derives Json

    private case class NetworkContainerDto(
        EndpointID: String = "",
        MacAddress: String = "",
        IPv4Address: String = "",
        IPv6Address: String = ""
    ) derives Json

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
    ) derives Json

    private case class EndpointConfigDto(
        Aliases: Seq[String] = Seq.empty
    ) derives Json

    private case class NetworkConnectRequest(
        Container: String = "",
        EndpointConfig: EndpointConfigDto = EndpointConfigDto()
    ) derives Json

    private case class NetworkDisconnectRequest(
        Container: String = "",
        Force: Boolean = false
    ) derives Json

    private case class NetworkPruneResponse(
        NetworksDeleted: Option[Seq[String]] = None
    ) derives Json

    // --- DTOs for volume operations ---

    private case class VolumeCreateRequest(
        Name: String = "",
        Driver: String = "local",
        DriverOpts: Map[String, String] = Map.empty,
        Labels: Map[String, String] = Map.empty
    ) derives Json

    private case class VolumeInfoDto(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Labels: Option[Map[String, String]] = None,
        Options: Option[Map[String, String]] = None,
        CreatedAt: String = "",
        Scope: String = ""
    ) derives Json

    private case class VolumeListResponse(
        Volumes: Option[Seq[VolumeInfoDto]] = None,
        Warnings: Option[Seq[String]] = None
    ) derives Json

    private case class VolumePruneResponse(
        VolumesDeleted: Option[Seq[String]] = None,
        SpaceReclaimed: Long = 0
    ) derives Json

end HttpContainerBackend

private[kyo] object HttpContainerBackend:

    /** Detect available container runtime by checking Unix socket paths.
      *
      * Checks common socket paths in order:
      *   1. `DOCKER_HOST` env var (parse `unix://` prefix)
      *   2. Podman: `$XDG_RUNTIME_DIR/podman/podman.sock`
      *   3. Docker: `/var/run/docker.sock`
      *   4. Docker rootless: `$HOME/.docker/run/docker.sock`
      *
      * Tries `_ping` on each, returns first that responds with `OK`.
      */
    def detect(meter: Meter = Meter.Noop)(using Frame): HttpContainerBackend < (Async & Abort[ContainerException]) =
        candidateSocketPaths.map { candidates =>
            def tryNext(remaining: Seq[String]): HttpContainerBackend < (Async & Abort[ContainerException]) =
                if remaining.isEmpty then
                    Abort.fail(ContainerException.BackendUnavailable(
                        "http",
                        "No container runtime socket found. Checked: DOCKER_HOST, " +
                            "$XDG_RUNTIME_DIR/podman/podman.sock, /var/run/docker.sock, $HOME/.docker/run/docker.sock"
                    ))
                else
                    val path    = remaining.head
                    val backend = new HttpContainerBackend(path, meter = meter)
                    Abort.run[ContainerException](backend.detect()).map {
                        case Result.Success(_) => backend
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

    /** Collect candidate socket paths from environment, filtering to those that exist on disk. */
    private def candidateSocketPaths(using Frame): Seq[String] < (Async & Abort[ContainerException]) =
        // Build the ordered list of candidate paths
        val dockerHost: Maybe[String] < Sync =
            kyo.System.env[String]("DOCKER_HOST").map {
                case Present(host) if host.startsWith("unix://") =>
                    Present(host.stripPrefix("unix://"))
                case Present(host) if host.startsWith("/") =>
                    Present(host)
                case _ => Absent
            }

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

        dockerHost.map { dh =>
            xdgPodman.map { xp =>
                homeDocker.map { hd =>
                    // Build ordered candidate list
                    val candidates =
                        dh.toList ++ xp.toList ++ Seq(defaultSocket) ++ hd.toList
                    // Filter to paths that exist on disk
                    Kyo.filter(candidates)(path => Path(path).exists).map { staticPaths =>
                        if staticPaths.nonEmpty then staticPaths
                        else discoverSocketsViaCli()
                    }
                }
            }
        }
    end candidateSocketPaths

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
