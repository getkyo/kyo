package kyo.internal

import kyo.*
import kyo.internal.ContainerBackend.parseInstant
import kyo.internal.ContainerBackend.parseState

/** Container backend that delegates to Docker or Podman CLI commands.
  *
  * Each operation builds a [[kyo.Command]], executes it via the shell, and parses stdout/stderr. Docker and Podman produce different JSON
  * formats for inspect, stats, and list operations — separate DTO case classes handle the divergence with a decode-fallback pattern.
  *
  * IMPORTANT: Requires `docker` or `podman` CLI on PATH. Detection tries Podman first, then Docker.
  *
  * @see
  *   [[ContainerBackend]] the abstract contract this implements
  * @see
  *   [[ContainerBackend.detect]] auto-detection logic
  */
final private[kyo] class ShellBackend(
    cmd: String,
    meter: Meter = Meter.Noop,
    streamBufferSize: Int = ShellBackend.defaultStreamBufferSize
) extends ContainerBackend(meter):

    import Container.*
    import ShellBackend.*

    // --- Container Lifecycle ---

    def create(config: Config)(using Frame): Container.Id < (Async & Abort[ContainerException]) =
        val restartStr = config.restartPolicy match
            case p @ Config.RestartPolicy.OnFailure(retries) => s"${p.cliName}:$retries"
            case p                                           => p.cliName

        // Pre-resolve bind mount paths (effectful due to symlink resolution)
        Kyo.foreach(config.mounts) {
            case Config.Mount.Bind(source, target, readOnly) =>
                resolveHostPath(source).map { resolvedSrc =>
                    val ro = if readOnly then ":ro" else ""
                    Chunk("-v", s"$resolvedSrc:${target}$ro")
                }
            case Config.Mount.Volume(name, target, readOnly) =>
                val ro = if readOnly then ":ro" else ""
                Chunk("-v", s"${name.value}:${target}$ro"): Seq[String] < (Async & Abort[ContainerException])
            case Config.Mount.Tmpfs(target, sizeBytes) =>
                Chunk(
                    "--tmpfs",
                    sizeBytes.map(s => s"${target}:size=$s").getOrElse(target.toString)
                ): Seq[String] < (Async & Abort[ContainerException])
        }.map { mountArgs =>
            // Build base args
            val args = Chunk("create") ++
                // Config flags
                config.name.map(n => Chunk("--name", n)).getOrElse(Chunk.empty) ++
                // Hostname
                config.hostname.map(h => Chunk("--hostname", h)).getOrElse(Chunk.empty) ++
                // User
                config.user.map(u => Chunk("--user", u)).getOrElse(Chunk.empty) ++
                // Labels
                Chunk.from(config.labels.toMap.toSeq.flatMap { case (k, v) => Seq("--label", s"$k=$v") }) ++
                // Ports
                config.ports.flatMap { pb =>
                    val proto = pb.protocol.cliName
                    val binding = (pb.hostIp, pb.hostPort) match
                        case (ip, hp) if ip.nonEmpty && hp != 0 => s"$ip:$hp:${pb.containerPort}/$proto"
                        case ("", hp) if hp != 0                => s"$hp:${pb.containerPort}/$proto"
                        case (ip, _) if ip.nonEmpty             => s"$ip::${pb.containerPort}/$proto"
                        case _                                  => s"${pb.containerPort}/$proto"
                    Chunk("-p", binding)
                } ++
                // Mounts (pre-resolved)
                mountArgs.flatten ++
                // Network mode
                config.networkMode.map { mode =>
                    val modeStr = mode match
                        case Config.NetworkMode.Bridge          => "bridge"
                        case Config.NetworkMode.Host            => "host"
                        case Config.NetworkMode.None            => "none"
                        case Config.NetworkMode.Shared(cid)     => s"container:${cid.value}"
                        case Config.NetworkMode.Custom(name, _) => name
                    val aliasArgs = mode match
                        case Config.NetworkMode.Custom(_, aliases) if aliases.nonEmpty =>
                            aliases.flatMap(a => Chunk("--network-alias", a))
                        case _ => Chunk.empty
                    Chunk("--network", modeStr) ++ aliasArgs
                }.getOrElse(Chunk.empty) ++
                // DNS
                config.dns.flatMap(d => Chunk("--dns", d)) ++
                // Extra hosts
                config.extraHosts.flatMap(eh => Chunk("--add-host", s"${eh.hostname}:${eh.ip}")) ++
                // Resource limits
                config.memory.map(m => Chunk("--memory", m.toString)).getOrElse(Chunk.empty) ++
                config.memorySwap.map(m => Chunk("--memory-swap", m.toString)).getOrElse(Chunk.empty) ++
                config.cpuLimit.map(c => Chunk("--cpus", c.toString)).getOrElse(Chunk.empty) ++
                config.cpuAffinity.map(a => Chunk("--cpuset-cpus", a)).getOrElse(Chunk.empty) ++
                config.maxProcesses.map(p => Chunk("--pids-limit", p.toString)).getOrElse(Chunk.empty) ++
                // Security
                (if config.privileged then Chunk("--privileged") else Chunk.empty) ++
                config.addCapabilities.flatMap(c => Chunk("--cap-add", c.cliName)) ++
                config.dropCapabilities.flatMap(c => Chunk("--cap-drop", c.cliName)) ++
                (if config.readOnlyFilesystem then Chunk("--read-only") else Chunk.empty) ++
                // Behavior
                (if config.interactive then Chunk("-i") else Chunk.empty) ++
                (if config.allocateTty then Chunk("-t") else Chunk.empty) ++
                (if config.autoRemove then Chunk("--rm") else Chunk.empty) ++
                Chunk("--restart", restartStr) ++
                config.stopSignal.map(s => Chunk("--stop-signal", s.name)).getOrElse(Chunk.empty) ++
                // Environment variables from Config.env merged with Command.env (command overrides on conflict)
                (config.env.toMap ++ config.command.map(_.env).getOrElse(Map.empty)).flatMap { case (k, v) => Chunk("-e", s"$k=$v") } ++
                // Working directory from Command
                config.command.flatMap(_.workDir).map(w => Chunk("-w", w.toString)).getOrElse(Chunk.empty) ++
                // Image
                Chunk(config.image.reference) ++
                // Command args (only when command is Present)
                config.command.map(cmd => Chunk.from(cmd.args)).getOrElse(Chunk.empty)

            // Run. Use Image context so mapError classifies image-not-found as ImageNotFound.
            run(ResourceContext.Image(config.image.reference), args.toSeq*).map(output => Container.Id(output.trim))
        }
    end create

    def start(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Container(id), "start", id.value)

    def stop(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Container(id), "stop", "-t", parseDurationSeconds(timeout).toString, id.value)

    def kill(id: Container.Id, signal: Container.Signal)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Container(id), "kill", "-s", signal.name, id.value)

    def restart(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Container(id), "restart", "-t", parseDurationSeconds(timeout).toString, id.value)

    def pause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Container(id), "pause", id.value)

    def unpause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Container(id), "unpause", id.value)

    def remove(id: Container.Id, force: Boolean, removeVolumes: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("rm") ++
            (if force then Chunk("-f") else Chunk.empty) ++
            (if removeVolumes then Chunk("-v") else Chunk.empty) ++
            Chunk(id.value)
        runUnit(ResourceContext.Container(id), args.toSeq*)
    end remove

    def rename(id: Container.Id, newName: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Container(id), "rename", id.value, newName)

    def waitForExit(id: Container.Id)(using Frame): ExitCode < (Async & Abort[ContainerException]) =
        val waitCmd = Command(cmd, "wait", id.value)
        Abort.runWith[CommandException](waitCmd.textWithExitCode) {
            case Result.Success((output, _)) =>
                // docker wait prints the container's exit code to stdout
                parseExitCode(output.trim) match
                    case Result.Success(code) => code
                    case _                    =>
                        // Couldn't parse output; try inspect as fallback
                        inspectExitCode(id)
            case Result.Failure(_) =>
                // docker wait failed (e.g. container already removed); try inspect
                inspectExitCode(id)
            case Result.Panic(ex) =>
                Abort.fail[ContainerException](
                    ContainerBackendException(s"waitForExit panicked for ${id.value}", ex)
                )
        }
    end waitForExit

    private def parseExitCode(s: String): Result[NumberFormatException, ExitCode] =
        Result.catching[NumberFormatException](s.toInt).map { code =>
            if code == 0 then ExitCode.Success
            else ExitCode.Failure(code)
        }

    private def inspectExitCode(id: Container.Id)(using Frame): ExitCode < (Async & Abort[ContainerException]) =
        Abort.runWith[ContainerException](
            run(ResourceContext.Container(id), "inspect", "--format", "{{.State.ExitCode}}", id.value)
        ) {
            case Result.Success(output) =>
                parseExitCode(output.trim) match
                    case Result.Success(code) => code
                    case _                    => ExitCode.Success
            case _ =>
                // Both wait and inspect failed — container is gone
                ExitCode.Success
        }

    // --- Checkpoint/Restore ---

    def checkpoint(id: Container.Id, name: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        if cmd == "podman" then
            runUnit(ResourceContext.Container(id), "container", "checkpoint", id.value, "--export", s"/tmp/$name.tar")
        else
            runUnit(ResourceContext.Container(id), "checkpoint", "create", id.value, name)

    def restore(id: Container.Id, checkpoint: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        if cmd == "podman" then
            runUnit(ResourceContext.Container(id), "container", "restore", "--import", s"/tmp/$checkpoint.tar")
        else
            runUnit(ResourceContext.Container(id), "start", "--checkpoint", checkpoint, id.value)

    // --- Health ---

    def isHealthy(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException]) =
        state(id).map(_ == State.Running)

    // --- Inspection ---

    // Docker/Podman CLI returns `null` for ports that are exposed but not bound to a host port
    // (e.g. `"5432/tcp": null`). zio-schema cannot decode null as Seq[T], so we normalise
    // null port-binding values to `[]` in the raw JSON before decode.

    private def normalizePortsNull(json: String): String =
        json.replaceAll("""("[\d]+/\w+")\s*:\s*null""", "$1: []")

    def inspect(id: Container.Id)(using Frame): Info < (Async & Abort[ContainerException]) =
        run(ResourceContext.Container(id), "inspect", "--format", "{{json .}}", id.value).map { raw =>
            decodeJson[InspectJson](normalizePortsNull(raw)).map(mapInspectToInfo(id, _))
        }

    private def mapInspectToInfo(id: Container.Id, dto: InspectJson)(using Frame): Info =
        // Parse basic fields
        val imageName =
            if dto.ImageName.nonEmpty then dto.ImageName
            else if dto.Config.Image.nonEmpty then dto.Config.Image
            else dto.Image

        val healthStatusStr = Maybe.fromOption(dto.State.Health).flatMap(h => if h.Status.nonEmpty then Present(h.Status) else Absent)
        val healthStatus    = healthStatusStr.map(HealthStatus.parse).getOrElse(HealthStatus.NoHealthcheck)

        // Parse config
        val portsSeq = dto.NetworkSettings.Ports.getOrElse(Map.empty).flatMap { case (portProto, mappings) =>
            val parts         = portProto.split("/")
            val containerPort = parts(0).toIntOption.getOrElse(0)
            val protocol = if parts.length > 1 then
                parts(1) match
                    case "udp"  => Config.Protocol.UDP
                    case "sctp" => Config.Protocol.SCTP
                    case _      => Config.Protocol.TCP
            else Config.Protocol.TCP
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
        }
        val portsChunk = Chunk.from(portsSeq)

        // Parse network
        val networks = Dict.from(dto.NetworkSettings.Networks.getOrElse(Map.empty).map { case (name, ep) =>
            Network.Id(name) -> Info.NetworkEndpoint(
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

        // Parse env "KEY=VALUE" strings into Dict
        val envMap: Dict[String, String] = Dict.from(dto.Config.Env.getOrElse(Seq.empty).flatMap { entry =>
            val idx = entry.indexOf('=')
            if idx >= 0 then Seq(entry.substring(0, idx) -> entry.substring(idx + 1))
            else Seq.empty
        }.toMap)

        // Parse image reference
        val imageRef = ContainerImage.parse(imageName).getOrElse(ContainerImage(imageName))

        // Parse platform
        val platform = Container.Platform.parse(dto.Platform).getOrElse(Container.Platform("", ""))

        // Parse state
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
            ports = portsChunk,
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

    def state(id: Container.Id)(using Frame): State < (Async & Abort[ContainerException]) =
        run(ResourceContext.Container(id), "inspect", "--format", "{{.State.Status}}", id.value).map(parseState)

    def stats(id: Container.Id)(using Frame): Stats < (Async & Abort[ContainerException]) =
        // Check container state first — Docker returns zero-valued stats for stopped
        // containers with exit code 0, so we need to detect and fail explicitly.
        Clock.now.map { now =>
            kyo.System.availableProcessors.map { cpus =>
                val cpuCount = Math.max(1, cpus)
                state(id).map { s =>
                    if s == State.Stopped || s == State.Dead then
                        Abort.fail[ContainerException](
                            ContainerAlreadyStoppedException(id)
                        )
                    else
                        run(ResourceContext.Container(id), "stats", "--no-stream", "--format", "{{json .}}", id.value).map { raw =>
                            val trimmed = raw.trim
                            // Try Docker format (string fields) first, then Podman format (numeric fields)
                            Json.decode[DockerStatsJson](trimmed) match
                                case Result.Success(dto) if dto.CPUPerc.nonEmpty => mapDockerStats(dto, now, cpuCount)
                                case _ =>
                                    Json.decode[PodmanStatsJson](trimmed) match
                                        case Result.Success(dto) => mapPodmanStats(dto, now, cpuCount)
                                        case Result.Failure(err) =>
                                            Abort.fail(ContainerDecodeException("Parse error in stats JSON", err))
                                        case Result.Panic(t) => Abort.panic(t)
                            end match
                        }
                    end if
                }
            }
        }

    private def mapDockerStats(dto: DockerStatsJson, now: Instant, cpuCount: Int)(using Frame): Stats =
        val cpuPerc                 = parsePercent(dto.CPUPerc)
        val memPerc                 = parsePercent(dto.MemPerc)
        val (memUsage, memLimit)    = parseSlashPair(dto.MemUsage)
        val (blockRead, blockWrite) = parseSlashPair(dto.BlockIO)
        val (netRx, netTx)          = parseSlashPair(dto.NetIO)
        val pidsVal =
            Result.catching[NumberFormatException](dto.PIDs.trim.toLong).getOrElse(0L)

        Stats(
            readAt = now,
            cpu = Stats.Cpu(
                totalUsage = Absent,
                systemUsage = Absent,
                onlineCpus = cpuCount,
                usagePercent = cpuPerc
            ),
            memory = Stats.Memory(
                usage = memUsage,
                maxUsage = Absent,
                limit = if memLimit > 0 then Present(memLimit) else Absent,
                usagePercent = memPerc
            ),
            network = Dict("eth0" -> Stats.Net(
                rxBytes = netRx,
                rxPackets = Absent,
                rxErrors = Absent,
                rxDropped = Absent,
                txBytes = netTx,
                txPackets = Absent,
                txErrors = Absent,
                txDropped = Absent
            )),
            blockIo = Stats.BlockIo(readBytes = blockRead, writeBytes = blockWrite),
            pids = Stats.Pids(current = pidsVal, limit = Absent)
        )
    end mapDockerStats

    private def mapPodmanStats(dto: PodmanStatsJson, now: Instant, cpuCount: Int)(using Frame): Stats =
        val systemNano = if dto.CPUSystemNano != 0L then dto.CPUSystemNano else dto.SystemNano
        val networkMap = Dict.from(dto.Network.getOrElse(Map.empty).map { case (ifName, netDto) =>
            ifName -> Stats.Net(
                rxBytes = netDto.RxBytes,
                rxPackets = Present(netDto.RxPackets),
                rxErrors = Present(netDto.RxErrors),
                rxDropped = Present(netDto.RxDropped),
                txBytes = netDto.TxBytes,
                txPackets = Present(netDto.TxPackets),
                txErrors = Present(netDto.TxErrors),
                txDropped = Present(netDto.TxDropped)
            )
        })
        val finalNetwork = if networkMap.nonEmpty then networkMap
        else Dict("eth0" -> Stats.Net(0, Absent, Absent, Absent, 0, Absent, Absent, Absent))

        val onlineCpus = if systemNano > 0 && dto.CPUNano > 0 then cpuCount
        else 0

        Stats(
            readAt = now,
            cpu = Stats.Cpu(
                totalUsage = if dto.CPUNano > 0 then Present(dto.CPUNano) else Absent,
                systemUsage = if systemNano > 0 then Present(systemNano) else Absent,
                onlineCpus = onlineCpus,
                usagePercent = dto.CPU
            ),
            memory = Stats.Memory(
                usage = dto.MemUsage,
                maxUsage = Absent,
                limit = if dto.MemLimit > 0 then Present(dto.MemLimit) else Absent,
                usagePercent = dto.MemPerc
            ),
            network = finalNetwork,
            blockIo = Stats.BlockIo(readBytes = dto.BlockInput, writeBytes = dto.BlockOutput),
            pids = Stats.Pids(current = dto.PIDs, limit = Absent)
        )
    end mapPodmanStats

    /** Parse a percentage string like "0.50%" to a Double. */
    private def parsePercent(s: String): Double =
        Result.catching[NumberFormatException](s.replace("%", "").trim.toDouble).getOrElse(0.0)

    /** Parse a "value / value" string like "100MiB / 1GiB" or "1.5kB / 2.3MB" to (Long, Long). */
    private def parseSlashPair(s: String): (Long, Long) =
        val parts = s.split("/").map(_.trim)
        if parts.length == 2 then (parseSizeString(parts(0)), parseSizeString(parts(1)))
        else (0L, 0L)
    end parseSlashPair

    /** Parse a docker human-readable size string to bytes. */
    private def parseSizeString(s: String): Long =
        val trimmed = s.trim
        Result.catching[NumberFormatException] {
            if trimmed.endsWith("GiB") then (trimmed.stripSuffix("GiB").trim.toDouble * 1024 * 1024 * 1024).toLong
            else if trimmed.endsWith("MiB") then (trimmed.stripSuffix("MiB").trim.toDouble * 1024 * 1024).toLong
            else if trimmed.endsWith("KiB") then (trimmed.stripSuffix("KiB").trim.toDouble * 1024).toLong
            else if trimmed.endsWith("GB") then (trimmed.stripSuffix("GB").trim.toDouble * 1000 * 1000 * 1000).toLong
            else if trimmed.endsWith("MB") then (trimmed.stripSuffix("MB").trim.toDouble * 1000 * 1000).toLong
            else if trimmed.endsWith("kB") then (trimmed.stripSuffix("kB").trim.toDouble * 1000).toLong
            else if trimmed.endsWith("B") then trimmed.stripSuffix("B").trim.toDouble.toLong
            else trimmed.toDouble.toLong
        }.getOrElse(0L)
    end parseSizeString

    def statsStream(id: Container.Id)(using Frame): Stream[Stats, Async & Abort[ContainerException]] =
        statsStream(id, 200.millis)

    def statsStream(id: Container.Id, interval: Duration)(using Frame): Stream[Stats, Async & Abort[ContainerException]] =
        // Poll stats repeatedly since true streaming requires Scope
        Stream {
            Loop(()) { _ =>
                stats(id).map { s =>
                    Emit.value(Chunk(s)).andThen {
                        Async.sleep(interval).andThen(Loop.continue(()))
                    }
                }
            }
        }

    def top(id: Container.Id, psArgs: String)(using Frame): TopResult < (Async & Abort[ContainerException]) =
        run(ResourceContext.Container(id), "top", id.value, psArgs).map { output =>
            val lines = output.split("\n")
            if lines.isEmpty then TopResult(Chunk.empty, Chunk.empty)
            else
                val titles    = Chunk.from(lines.head.split("\\s+"))
                val processes = Chunk.from(lines.tail.map(l => Chunk.from(l.split("\\s+", titles.length))))
                TopResult(titles, processes)
            end if
        }

    def changes(id: Container.Id)(using Frame): Chunk[FilesystemChange] < (Async & Abort[ContainerException]) =
        run(ResourceContext.Container(id), "diff", id.value).map { output =>
            if output.trim.isEmpty then Chunk.empty
            else
                Chunk.from(output.split("\n").flatMap { line =>
                    val trimmed = line.trim
                    if trimmed.length >= 2 then
                        val kindChar = trimmed.charAt(0)
                        val path     = trimmed.substring(2)
                        val kind = kindChar match
                            case 'A' => FilesystemChange.Kind.Added
                            case 'C' => FilesystemChange.Kind.Modified
                            case 'D' => FilesystemChange.Kind.Deleted
                            case _   => FilesystemChange.Kind.Modified
                        Seq(FilesystemChange(path, kind))
                    else Seq.empty
                    end if
                })
        }

    // --- Exec ---

    def exec(id: Container.Id, command: Command)(using Frame): ExecResult < (Async & Abort[ContainerException]) =
        Abort.recover[Closed] { (_: Closed) =>
            Abort.fail(ContainerBackendException(
                s"Concurrency meter closed during exec in ${id.value}",
                "execution meter rejected the command"
            ))
        }(meter.run(execOnce(id, command)))
    end exec

    /** Execute a command inside the container, mapping exit codes to typed errors.
      *
      * Exit code semantics (Docker & Podman):
      *   - 0: Success
      *   - 1: Generic error — Docker uses this for daemon errors too (requires string matching on stderr)
      *   - 125: Podman infrastructure error (container not found, not running, paused, etc.)
      *   - 126: Command cannot be invoked (permission denied or not an executable)
      *   - 127: Command not found inside the container
      *   - 128+N: Process terminated by signal N (e.g. 137 = SIGKILL, 143 = SIGTERM)
      */
    private def execOnce(id: Container.Id, command: Command)(using Frame): ExecResult < (Async & Abort[ContainerException]) =
        // Build command
        val baseArgs = Chunk("exec") ++
            command.env.flatMap { case (k, v) => Chunk("-e", s"$k=$v") } ++
            command.workDir.map(w => Chunk("-w", w.toString)).getOrElse(Chunk.empty) ++
            Chunk(id.value) ++
            Chunk.from(command.args)

        // Spawn process
        val execCmd = Command((cmd +: baseArgs.toSeq)*)
        Scope.run {
            Abort.runWith[CommandException](execCmd.spawn) {
                case Result.Success(proc) =>
                    // Collect output
                    for
                        outFib   <- Fiber.init(Scope.run(proc.stdout.run))
                        errFib   <- Fiber.init(Scope.run(proc.stderr.run))
                        rawExit  <- proc.waitFor
                        outBytes <- outFib.get
                        errBytes <- errFib.get
                    yield
                        val stdoutStr = new String(outBytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        val stderrStr = new String(errBytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        // Parse exit code and classify result
                        if (rawExit == ExitCode.Failure(125) || isDockerDaemonError(stderrStr)) && stderrStr.nonEmpty then
                            Abort.fail[ContainerException](mapError(stderrStr.trim, ResourceContext.Container(id), Seq("exec", id.value)))
                        // Exit code 126: command exists but cannot be invoked (permissions, not executable)
                        else if rawExit == ExitCode.Failure(126) then
                            Abort.fail[ContainerException](
                                ContainerExecFailedException(
                                    id,
                                    Chunk.from(command.args),
                                    ExitCode.Failure(126),
                                    "Command cannot be invoked (permission denied or not executable)"
                                )
                            )
                        // Exit code 127: command not found in the container's PATH
                        else if rawExit == ExitCode.Failure(127) then
                            Abort.fail[ContainerException](
                                ContainerExecFailedException(
                                    id,
                                    Chunk.from(command.args),
                                    ExitCode.Failure(127),
                                    "Command not found"
                                )
                            )
                        else
                            val exitCode = rawExit match
                                // Signal-terminated: map to 128+signal convention
                                case ExitCode.Signaled(sig) => ExitCode.Failure(128 + sig)
                                case other                  => other
                            ExecResult(exitCode, stdoutStr, stderrStr)
                        end if
                case Result.Failure(cmdEx) =>
                    Abort.fail[ContainerException](
                        ContainerOperationException(s"exec failed in ${id.value}", cmdEx)
                    )
                case Result.Panic(ex) =>
                    Abort.fail[ContainerException](
                        ContainerBackendException(s"exec panicked in ${id.value}", ex)
                    )
            }
        }
    end execOnce

    def execStream(id: Container.Id, command: Command)(
        using Frame
    ): Stream[LogEntry, Async & Abort[ContainerException]] =
        Stream {
            val baseArgs = Chunk("exec") ++
                command.env.flatMap { case (k, v) => Chunk("-e", s"$k=$v") } ++
                command.workDir.map(w => Chunk("-w", w.toString)).getOrElse(Chunk.empty) ++
                Chunk(id.value) ++
                Chunk.from(command.args)

            // Keep stdout and stderr separate so each LogEntry carries its true source tag,
            // matching the HTTP backend's multiplexed-stream behavior. Drain both subprocess
            // pipes into a shared channel from a forked fiber; the main stream consumes the
            // channel and emits entries in arrival order.
            val execCmd   = Command((cmd +: baseArgs.toSeq)*)
            val stdoutAsm = new LineAssembler
            val stderrAsm = new LineAssembler
            Scope.run {
                Abort.runWith[CommandException](execCmd.spawn) {
                    case Result.Success(proc) =>
                        Channel.initUnscoped[LogEntry](streamBufferSize).map { channel =>
                            def drain(
                                byteStream: Stream[Byte, Sync & Scope],
                                asm: LineAssembler,
                                source: LogEntry.Source
                            ): Unit < (Async & Abort[Closed]) =
                                Scope.run(byteStream.foreachChunk { bytes =>
                                    val text  = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    val lines = asm.feed(text)
                                    Kyo.foreachDiscard(lines.toSeq.filter(_.trim.nonEmpty)) { line =>
                                        channel.put(LogEntry(source, line))
                                    }
                                })
                            val drainBoth: Unit < (Async & Abort[Closed]) =
                                Async.zip(
                                    drain(proc.stdout, stdoutAsm, LogEntry.Source.Stdout),
                                    drain(proc.stderr, stderrAsm, LogEntry.Source.Stderr)
                                ).unit
                            Fiber.init(Abort.run[Closed](drainBoth).andThen(channel.close.unit)).andThen {
                                channel.streamUntilClosed().emit
                            }
                        }
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](
                            ContainerOperationException(s"execStream failed in ${id.value}", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerBackendException(s"execStream panicked in ${id.value}", ex)
                        ).unit
                }
            }
        }

    def execInteractive(id: Container.Id, command: Command)(
        using Frame
    ): AttachSession < (Async & Abort[ContainerException] & Scope) =
        val baseArgs = Chunk("exec", "-i") ++
            command.env.flatMap { case (k, v) => Chunk("-e", s"$k=$v") } ++
            command.workDir.map(w => Chunk("-w", w.toString)).getOrElse(Chunk.empty) ++
            Chunk(id.value) ++
            Chunk.from(command.args)

        createAttachSession(baseArgs.toSeq, s"exec interactive failed for ${id.value}")
    end execInteractive

    // --- Attach ---

    def attach(id: Container.Id, stdin: Boolean, stdout: Boolean, stderr: Boolean)(
        using Frame
    ): AttachSession < (Async & Abort[ContainerException] & Scope) =
        val args = Chunk("attach") ++
            (if !stdin then Chunk("--no-stdin") else Chunk.empty) ++
            (if !stdout then Chunk("--no-stdout") else Chunk.empty) ++
            (if !stderr then Chunk("--no-stderr") else Chunk.empty) ++
            Chunk(id.value)

        createAttachSession(args.toSeq, s"attach failed for ${id.value}")
    end attach

    private def createAttachSession(args: Seq[String], errorMsg: String)(
        using Frame
    ): AttachSession < (Async & Abort[ContainerException] & Scope) =
        // Use an empty stdin stream — interactive write requires HTTP backend
        val emptyIn   = new java.io.ByteArrayInputStream(Array.empty[Byte])
        val dockerCmd = Command((cmd +: args)*).stdin(Process.Input.FromStream(emptyIn))
        Abort.runWith[CommandException](dockerCmd.spawn) {
            case Result.Failure(cmdEx) =>
                Abort.fail[ContainerException](ContainerOperationException(errorMsg, cmdEx))
            case Result.Panic(ex) =>
                Abort.fail[ContainerException](ContainerBackendException(errorMsg, ex))
            case Result.Success(proc) =>
                new AttachSession:
                    def write(data: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
                        Abort.fail(ContainerNotSupportedException(
                            "attach write",
                            "Shell backend does not support interactive stdin — use HTTP backend"
                        ))

                    def write(data: Chunk[Byte])(using Frame): Unit < (Async & Abort[ContainerException]) =
                        Abort.fail(ContainerNotSupportedException(
                            "attach write",
                            "Shell backend does not support interactive stdin — use HTTP backend"
                        ))

                    def read(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
                        Stream {
                            Scope.run {
                                proc.stdout.mapChunk { bytes =>
                                    val text  = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    val lines = text.split("\n").filter(_.trim.nonEmpty)
                                    Chunk.from(lines.map(line => LogEntry(LogEntry.Source.Stdout, line)))
                                }.emit
                            }
                        }

                    def resize(width: Int, height: Int)(using Frame): Unit < (Async & Abort[ContainerException]) =
                        () // Not supported via CLI
                end new
        }
    end createAttachSession

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
        val needMerge           = stdout && stderr
        val effectiveTimestamps = timestamps || needMerge
        val args = Chunk("logs") ++
            (if tail != Int.MaxValue then Chunk("--tail", tail.toString) else Chunk.empty) ++
            (if since != Instant.Min then Chunk("--since", since.toString) else Chunk.empty) ++
            (if until != Instant.Max then Chunk("--until", until.toString) else Chunk.empty) ++
            (if effectiveTimestamps then Chunk("-t") else Chunk.empty) ++
            Chunk(id.value)

        // Container's stdout goes to command's stdout, container's stderr goes to command's stderr
        // We need to collect them separately based on the stdout/stderr flags
        val logsCmd = Command((cmd +: args.toSeq)*)
        Scope.run {
            Abort.runWith[CommandException](logsCmd.spawn) {
                case Result.Success(proc) =>
                    for
                        outFib   <- Fiber.init(Scope.run(proc.stdout.run))
                        errFib   <- Fiber.init(Scope.run(proc.stderr.run))
                        exitCode <- proc.waitFor
                        outBytes <- outFib.get
                        errBytes <- errFib.get
                    yield
                        val outStr = new String(outBytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        val errStr = new String(errBytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        if !exitCode.isSuccess then
                            Abort.fail[ContainerException](mapError((outStr + errStr).trim, ResourceContext.Container(id), args.toSeq))
                        else
                            val outEntries =
                                if stdout then parseLogLines(outStr, LogEntry.Source.Stdout, effectiveTimestamps)
                                else Chunk.empty[LogEntry]
                            val errEntries =
                                if stderr then parseLogLines(errStr, LogEntry.Source.Stderr, effectiveTimestamps)
                                else Chunk.empty[LogEntry]
                            val merged: Chunk[LogEntry] =
                                if needMerge then Chunk.from(mergeByTimestamp(outEntries.toSeq, errEntries.toSeq))
                                else outEntries.concat(errEntries)
                            if !timestamps && effectiveTimestamps then
                                merged.map(_.copy(timestamp = Absent))
                            else merged
                        end if
                case Result.Failure(cmdEx) =>
                    Abort.fail[ContainerException](
                        ContainerOperationException(s"logs failed for ${id.value}", cmdEx)
                    )
                case Result.Panic(ex) =>
                    Abort.fail[ContainerException](
                        ContainerBackendException(s"logs panicked for ${id.value}", ex)
                    )
            }
        }
    end logs

    /** Parse log output lines into LogEntry, handling optional timestamps. */

    /** Parse a timestamp string from log output. Docker uses "2024-01-01T00:00:00.000000000Z" (Instant format). Podman uses
      * "2024-01-01T00:00:00-07:00" (OffsetDateTime format).
      */
    private def parseLogTimestamp(tsStr: String): Maybe[Instant] =
        Instant.parse(tsStr).toMaybe.orElse {
            Result.catching[java.time.format.DateTimeParseException](
                Instant.fromJava(java.time.OffsetDateTime.parse(tsStr).toInstant)
            ).toMaybe
        }

    private def parseLogLines(raw: String, source: LogEntry.Source, hasTimestamps: Boolean): Chunk[LogEntry] =
        if raw.trim.isEmpty then Chunk.empty
        else
            Chunk.from(raw.split("\n").filter(_.nonEmpty).map { line =>
                if hasTimestamps then
                    val spaceIdx = line.indexOf(' ')
                    if spaceIdx > 0 then
                        val tsStr   = line.substring(0, spaceIdx)
                        val content = line.substring(spaceIdx + 1)
                        val ts      = parseLogTimestamp(tsStr)
                        LogEntry(source, content, ts)
                    else LogEntry(source, line)
                    end if
                else LogEntry(source, line)
            })
    end parseLogLines

    private def parseLogLine(line: String, source: LogEntry.Source, hasTimestamps: Boolean): LogEntry =
        if hasTimestamps then
            val spaceIdx = line.indexOf(' ')
            if spaceIdx > 0 then
                val tsStr   = line.substring(0, spaceIdx)
                val content = line.substring(spaceIdx + 1)
                val ts      = parseLogTimestamp(tsStr)
                LogEntry(source, content, ts)
            else LogEntry(source, line)
            end if
        else LogEntry(source, line)

    /** Merge two per-source log sequences into a single emission-order sequence.
      *
      * Entries are ordered primarily by timestamp. When timestamps tie (including when both are `Absent`), the two streams are interleaved
      * in round-robin from the last-emitted source, so consecutive ties alternate between stdout and stderr. This heuristic recovers
      * emission order when the CLI's timestamp resolution is coarser than the inter-event spacing (e.g., Podman emits second-precision
      * timestamps).
      */
    private def mergeByTimestamp(out: Seq[LogEntry], err: Seq[LogEntry]): Seq[LogEntry] =
        def key(e: LogEntry): Long             = e.timestamp.map(_.toJava.toEpochMilli).getOrElse(0L)
        val outQ                               = out.toBuffer
        val errQ                               = err.toBuffer
        val result                             = Seq.newBuilder[LogEntry]
        var lastSource: Maybe[LogEntry.Source] = Absent
        while outQ.nonEmpty && errQ.nonEmpty do
            val o = outQ.head
            val e = errQ.head
            val pickOut =
                if key(o) < key(e) then true
                else if key(e) < key(o) then false
                else
                    lastSource match
                        case Present(LogEntry.Source.Stdout) => false // alternate away from stdout
                        case Present(LogEntry.Source.Stderr) => true  // alternate away from stderr
                        case _                               => true  // default: stdout first
            if pickOut then
                result += o
                discard(outQ.remove(0))
                lastSource = Present(LogEntry.Source.Stdout)
            else
                result += e
                discard(errQ.remove(0))
                lastSource = Present(LogEntry.Source.Stderr)
            end if
        end while
        result ++= outQ
        result ++= errQ
        result.result()
    end mergeByTimestamp

    def logStream(
        id: Container.Id,
        stdout: Boolean,
        stderr: Boolean,
        since: Instant,
        timestamps: Boolean,
        tail: Int
    )(using Frame): Stream[LogEntry, Async & Abort[ContainerException]] =
        Stream {
            val args = Chunk("logs", "--follow") ++
                (if tail != Int.MaxValue then Chunk("--tail", tail.toString) else Chunk.empty) ++
                (if since != Instant.Min then Chunk("--since", since.toString) else Chunk.empty) ++
                (if timestamps then Chunk("-t") else Chunk.empty) ++
                Chunk(id.value)

            // Select source tag and whether to merge streams
            val source       = if stderr && !stdout then LogEntry.Source.Stderr else LogEntry.Source.Stdout
            val mergeStreams = stdout && stderr

            val logsCmd =
                if mergeStreams then Command((cmd +: args.toSeq)*).redirectErrorStream(true)
                else Command((cmd +: args.toSeq)*)

            // Per-stream state: lines spanning chunk boundaries are re-assembled. No flush on
            // termination — matches prior behavior of dropping trailing partial lines.
            val assembler = new LineAssembler
            Scope.run {
                Abort.runWith[CommandException](logsCmd.spawn) {
                    case Result.Success(proc) =>
                        val byteStream = if source == LogEntry.Source.Stderr then proc.stderr else proc.stdout
                        byteStream.mapChunk { bytes =>
                            val text  = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            val lines = assembler.feed(text)
                            Chunk.from(lines.toSeq.filter(_.nonEmpty).map(line => parseLogLine(line, source, timestamps)))
                        }.emit
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](
                            ContainerOperationException(s"logStream failed for ${id.value}", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerBackendException(s"logStream panicked for ${id.value}", ex)
                        ).unit
                }
            }
        }

    // --- File Ops ---

    /** Resolve a host path to its real/canonical path, following symlinks (e.g. /tmp -> /private/tmp on macOS).
      *
      * Uses kyo Command to run readlink, falling back to the original path on failure.
      */
    private def resolveHostPath(path: Path)(using Frame): String < (Async & Abort[ContainerException]) =
        val pathStr = path.toString
        Abort.recover[ContainerException](
            (_: ContainerException) => pathStr,
            (_: Throwable) => pathStr
        ) {
            Abort.runWith[CommandException](Command("readlink", "-f", pathStr).textWithExitCode) {
                case Result.Success((output, exitCode)) if exitCode == ExitCode.Success =>
                    val resolved = output.trim
                    if resolved.nonEmpty then resolved else pathStr
                case _ => pathStr
            }
        }
    end resolveHostPath

    def copyTo(id: Container.Id, source: Path, containerPath: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        resolveHostPath(source).map { resolved =>
            runUnit(ResourceContext.Container(id), "cp", resolved, s"${id.value}:${containerPath}")
        }

    def copyFrom(id: Container.Id, containerPath: Path, destination: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        // Resolve the parent directory for the destination to handle macOS symlinks (e.g. /tmp -> /private/tmp)
        val destStr = destination.toString
        destination.parent match
            case Present(parentPath) =>
                destination.name match
                    case Present(fileName) =>
                        resolveHostPath(parentPath).map { resolvedParent =>
                            runUnit(ResourceContext.Container(id), "cp", s"${id.value}:${containerPath}", s"$resolvedParent/$fileName")
                        }
                    case Absent =>
                        runUnit(ResourceContext.Container(id), "cp", s"${id.value}:${containerPath}", destStr)
            case Absent =>
                runUnit(ResourceContext.Container(id), "cp", s"${id.value}:${containerPath}", destStr)
        end match
    end copyFrom

    def stat(id: Container.Id, containerPath: Path)(using Frame): FileStat < (Async & Abort[ContainerException]) =
        // %N gives quoted name + link target for symlinks, %s=size, %f=hex mode, %Y=mtime epoch
        run(ResourceContext.Container(id), "exec", id.value, "stat", "-c", "%N|%s|%f|%Y", containerPath.toString).map { output =>
            val parts = output.trim.split("\\|")
            if parts.length >= 4 then
                // %N returns 'name' for regular files, or 'name' -> 'target' for symlinks
                val rawName = parts(0)
                val (name, linkTarget) =
                    val arrowIdx = rawName.indexOf(" -> ")
                    if arrowIdx >= 0 then
                        val n = rawName.substring(0, arrowIdx).replaceAll("^['\"]|['\"]$", "")
                        val t = rawName.substring(arrowIdx + 4).replaceAll("^['\"]|['\"]$", "")
                        (n, Present(t))
                    else
                        (rawName.replaceAll("^['\"]|['\"]$", ""), Absent)
                    end if
                end val
                // Extract just the filename from the full path
                val baseName = Maybe.fromOption(name.split("/").lastOption).getOrElse(name)
                Result.catching[NumberFormatException] {
                    FileStat(
                        name = baseName,
                        size = parts(1).toLong,
                        mode = java.lang.Integer.parseInt(parts(2), 16),
                        modifiedAt = Instant.fromJava(java.time.Instant.ofEpochSecond(parts(3).toLong)),
                        linkTarget = linkTarget
                    )
                } match
                    case Result.Success(fs) => fs
                    case _ =>
                        Abort.fail(ContainerDecodeException(
                            "Parse error in stat",
                            s"Invalid stat output for container ${id.value}: ${output.trim}"
                        ))
                end match
            else
                FileStat(
                    name = Maybe.fromOption(containerPath.toString.split("/").lastOption).getOrElse(containerPath.toString),
                    size = 0,
                    mode = 0,
                    modifiedAt = Instant.Epoch,
                    linkTarget = Absent
                )
            end if
        }

    def exportFs(id: Container.Id)(using Frame): Stream[Byte, Async & Abort[ContainerException]] =
        Stream {
            val dockerCmd = Command(cmd, "export", id.value).redirectErrorStream(true)
            Scope.run {
                Abort.runWith[CommandException](dockerCmd.spawn) {
                    case Result.Success(proc) =>
                        proc.stdout.emit
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](
                            ContainerOperationException(s"export failed for ${id.value}", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerBackendException(s"export panicked for ${id.value}", ex)
                        ).unit
                }
            }
        }

    // --- Resource Updates ---

    def update(
        id: Container.Id,
        memory: Maybe[Long],
        memorySwap: Maybe[Long],
        cpuLimit: Maybe[Double],
        cpuAffinity: Maybe[String],
        maxProcesses: Maybe[Long],
        restartPolicy: Maybe[Config.RestartPolicy]
    )(using Frame): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("update") ++
            memory.map(m => Chunk("--memory", m.toString)).getOrElse(Chunk.empty) ++
            memorySwap.map(m => Chunk("--memory-swap", m.toString)).getOrElse(Chunk.empty) ++
            cpuLimit.map(c => Chunk("--cpus", c.toString)).getOrElse(Chunk.empty) ++
            cpuAffinity.map(a => Chunk("--cpuset-cpus", a)).getOrElse(Chunk.empty) ++
            maxProcesses.map(p => Chunk("--pids-limit", p.toString)).getOrElse(Chunk.empty) ++
            restartPolicy.map { rp =>
                val rpStr = rp match
                    case Config.RestartPolicy.No                 => "no"
                    case Config.RestartPolicy.Always             => "always"
                    case Config.RestartPolicy.UnlessStopped      => "unless-stopped"
                    case Config.RestartPolicy.OnFailure(retries) => s"on-failure:$retries"
                Chunk("--restart", rpStr)
            }.getOrElse(Chunk.empty)
        // Docker requires at least one flag; skip the command for no-op updates
        if args.length > 1 then
            runUnit(ResourceContext.Container(id), (args ++ Chunk(id.value)).toSeq*)
        else ()
        end if
    end update

    // --- Network on Container ---

    def connectToNetwork(id: Container.Id, networkId: Network.Id, aliases: Chunk[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "connect") ++
            aliases.flatMap(a => Chunk("--alias", a)) ++
            Chunk(networkId.value) ++
            Chunk(id.value)
        // Use Network context so network-not-found classifies as NetworkNotFound.
        Abort.run[ContainerException] {
            runUnit(ResourceContext.Network(networkId), args.toSeq*)
        }.map {
            case Result.Success(_)                             => ()
            case Result.Failure(_: ContainerConflictException) => ()
            case Result.Failure(e)                             => Abort.fail(e)
            case Result.Panic(e)                               => Abort.panic(e)
        }
    end connectToNetwork

    def disconnectFromNetwork(id: Container.Id, networkId: Network.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "disconnect") ++
            (if force then Chunk("-f") else Chunk.empty) ++
            Chunk(networkId.value) ++
            Chunk(id.value)
        runUnit(ResourceContext.Network(networkId), args.toSeq*)
    end disconnectFromNetwork

    // --- Container Listing ---

    def list(all: Boolean, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Summary] < (Async & Abort[ContainerException]) =
        val args = Chunk("ps") ++
            (if all then Chunk("--all") else Chunk.empty) ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--no-trunc", "--format", "{{json .}}")
        run(ResourceContext.Op("list"), args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[Summary]
            else
                // Parse each line independently — skip lines that fail to decode
                // so one malformed container entry doesn't abort the entire list
                Chunk.from(output.trim.split("\n")).flatMap { line =>
                    val trimmed = line.trim
                    Json.decode[PsJsonDocker](trimmed) match
                        case Result.Success(dto) if dto.ID.nonEmpty =>
                            Chunk(mapDockerPsToSummary(dto))
                        case _ =>
                            Json.decode[PsJsonPodman](trimmed) match
                                case Result.Success(dto) if dto.Id.nonEmpty =>
                                    Chunk(mapPodmanPsToSummary(dto))
                                case _ => Chunk.empty
                    end match
                }
        }
    end list

    /** Parse a single Docker port entry like "0.0.0.0:8080->80/tcp", ":::8080->80/tcp", or "80/tcp". */
    private def parseDockerPortEntry(entry: String): Maybe[Config.PortBinding] =
        // Split protocol: "0.0.0.0:8080->80/tcp" -> ("0.0.0.0:8080->80", "tcp")
        val slashIdx      = entry.lastIndexOf('/')
        val (main, proto) = if slashIdx >= 0 then (entry.substring(0, slashIdx), entry.substring(slashIdx + 1)) else (entry, "tcp")
        val protocol = proto match
            case "udp"  => Config.Protocol.UDP
            case "sctp" => Config.Protocol.SCTP
            case _      => Config.Protocol.TCP
        val arrowIdx = main.indexOf("->")
        if arrowIdx >= 0 then
            // Has host mapping: "host:hostPort->containerPort" or "hostIp:hostPort->containerPort"
            val hostPart      = main.substring(0, arrowIdx)
            val containerPart = main.substring(arrowIdx + 2)
            val containerPort = containerPart.toIntOption.getOrElse(0)
            // hostPart can be "0.0.0.0:8080", ":::8080", or just ":8080"
            val lastColon = hostPart.lastIndexOf(':')
            if lastColon >= 0 then
                val hostIp   = hostPart.substring(0, lastColon)
                val hostPort = hostPart.substring(lastColon + 1).toIntOption.getOrElse(0)
                Present(Config.PortBinding(
                    containerPort = containerPort,
                    hostPort = hostPort,
                    hostIp = if hostIp.nonEmpty then hostIp else "",
                    protocol = protocol
                ))
            else
                // No colon in host part -- handle gracefully
                val hostPort = hostPart.toIntOption.getOrElse(0)
                Present(Config.PortBinding(
                    containerPort = containerPort,
                    hostPort = hostPort,
                    hostIp = "",
                    protocol = protocol
                ))
            end if
        else
            // No host mapping: just "80" (containerPort only)
            Maybe.fromOption(main.toIntOption.map { containerPort =>
                Config.PortBinding.default.copy(containerPort = containerPort, protocol = protocol)
            })
        end if
    end parseDockerPortEntry

    private def mapDockerPsToSummary(dto: PsJsonDocker): Summary =
        val names = if dto.Names.nonEmpty then Chunk.from(dto.Names.split(",")) else Chunk.empty[String]
        val labels: Dict[String, String] = if dto.Labels.nonEmpty then
            Dict.from(dto.Labels.split(",").iterator.filter(_.contains("=")).map { pair =>
                val kv = pair.split("=", 2)
                kv(0) -> kv(1)
            }.toMap)
        else Dict.empty
        val createdAt = if dto.CreatedAt.nonEmpty then parseTimestamp(dto.CreatedAt) else Instant.Epoch
        val ports =
            if dto.Ports.nonEmpty then
                Chunk.from(dto.Ports.split(",").iterator.map(_.trim).filter(_.nonEmpty).map(parseDockerPortEntry).collect {
                    case Present(pb) => pb
                }.toSeq)
            else Chunk.empty[Config.PortBinding]
        val mounts =
            if dto.Mounts.nonEmpty then Chunk.from(dto.Mounts.split(",").map(_.trim).filter(_.nonEmpty))
            else Chunk.empty[String]
        Summary(
            id = Container.Id(dto.ID),
            names = names,
            image = ContainerImage.parse(dto.Image).getOrElse(ContainerImage(dto.Image)),
            imageId = ContainerImage.Id(dto.ImageID),
            command = dto.Command,
            state = parseState(dto.State),
            status = dto.Status,
            ports = ports,
            labels = labels,
            mounts = mounts,
            createdAt = createdAt
        )
    end mapDockerPsToSummary

    private def mapPodmanPsToSummary(dto: PsJsonPodman): Summary =
        val names      = Chunk.from(dto.Names.getOrElse(Seq.empty))
        val command    = dto.Command.getOrElse(Seq.empty).mkString(" ")
        val labels     = Dict.from(dto.Labels.getOrElse(Map.empty))
        val createdStr = if dto.Created.nonEmpty then dto.Created else dto.CreatedAt
        val createdAt = if createdStr.isEmpty then Instant.Epoch
        else
            Result.catching[NumberFormatException](Instant.fromJava(java.time.Instant.ofEpochSecond(createdStr.toLong)))
                .getOrElse(parseTimestamp(createdStr))
        val ports = Chunk.from(
            dto.Ports.getOrElse(Seq.empty).map { p =>
                val protocol = p.protocol match
                    case "udp"  => Config.Protocol.UDP
                    case "sctp" => Config.Protocol.SCTP
                    case _      => Config.Protocol.TCP
                Config.PortBinding(
                    containerPort = p.container_port,
                    hostPort = p.host_port,
                    hostIp = if p.host_ip.nonEmpty then p.host_ip else "",
                    protocol = protocol
                )
            }
        )
        val mounts = Chunk.from(dto.Mounts.getOrElse(Seq.empty))
        Summary(
            id = Container.Id(dto.Id),
            names = names,
            image = ContainerImage.parse(dto.Image).getOrElse(ContainerImage(dto.Image)),
            imageId = ContainerImage.Id(dto.ImageID),
            command = command,
            state = parseState(dto.State),
            status = dto.Status,
            ports = ports,
            labels = labels,
            mounts = mounts,
            createdAt = createdAt
        )
    end mapPodmanPsToSummary

    def prune(filters: Dict[String, Chunk[String]])(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        val args = Chunk("container", "prune", "-f") ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            })
        run(ResourceContext.Op("prune"), args.toSeq*).map { output =>
            // Parse prune output: lists deleted container IDs, then space reclaimed
            val lines   = output.split("\n")
            val deleted = Chunk.from(lines.map(_.trim).filter(_.matches("^[a-f0-9]{12,64}$")))
            val spaceReclaimed = lines.find(_.contains("reclaimed")).flatMap { line =>
                val pattern = """(\d+(?:\.\d+)?)\s*(B|kB|MB|GB|TB)""".r
                pattern.findFirstMatchIn(line).map { m =>
                    parseSizeString(m.group(0))
                }
            }.getOrElse(0L)
            PruneResult(deleted, spaceReclaimed)
        }
    end prune

    def attachById(idOrName: Container.Id)(using Frame): (Container.Id, Config) < (Async & Abort[ContainerException]) =
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

    // --- Image Operations ---

    def imagePull(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val ref = image.reference
        val args = Chunk("pull") ++
            platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
            Chunk(ref)
        runUnit(ResourceContext.Image(ref), args.toSeq*)
    end imagePull

    def imageEnsure(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val ref = image.reference
        val ctx = ResourceContext.Image(ref)
        Abort.runWith[ContainerException](
            run(ctx, "image", "inspect", "--format", "{{.Id}}", ref)
        ) {
            case Result.Success(_) => () // Image exists locally, skip pull
            case _ =>
                val args = Chunk("pull") ++
                    platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
                    Chunk(ref)
                runUnit(ctx, args.toSeq*)
        }
    end imageEnsure

    def imagePullWithProgress(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Stream[ContainerImage.PullProgress, Async & Abort[ContainerException]] =
        Stream {
            val ref = image.reference
            val ctx = ResourceContext.Image(ref)
            // Check locally first to avoid hanging on registry rate limits
            Abort.runWith[ContainerException](
                run(ctx, "image", "inspect", "--format", "{{.Id}}", ref)
            ) {
                case Result.Success(_) =>
                    // Image exists locally — emit up-to-date status
                    Emit.value(Chunk(ContainerImage.PullProgress(
                        id = Absent,
                        status = s"$ref: image is up to date",
                        progress = Absent,
                        error = Absent
                    )))
                case _ =>
                    // Image not found locally — pull from registry
                    val args = Chunk("pull") ++
                        platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
                        Chunk(ref)
                    val pullCmd = Command((cmd +: args.toSeq)*).redirectErrorStream(true)
                    Scope.run {
                        Abort.runWith[CommandException](pullCmd.spawn) {
                            case Result.Success(proc) =>
                                proc.stdout.mapChunk { bytes =>
                                    val text = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    Chunk.from(text.split("\n").filter(_.trim.nonEmpty).map { line =>
                                        ContainerImage.PullProgress(id = Absent, status = line, progress = Absent, error = Absent)
                                    })
                                }.emit
                            case Result.Failure(cmdEx) =>
                                Abort.fail[ContainerException](
                                    ContainerOperationException(s"pull failed for ${ref}", cmdEx)
                                ).unit
                            case Result.Panic(ex) =>
                                Abort.fail[ContainerException](
                                    ContainerBackendException(s"pull panicked for ${ref}", ex)
                                ).unit
                        }
                    }
            }
        }

    def imageList(all: Boolean, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[ContainerImage.Summary] < (Async & Abort[ContainerException]) =
        val args = Chunk("images") ++
            (if all then Chunk("--all") else Chunk.empty) ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--format", "{{json .}}")
        run(ResourceContext.Op("imageList"), args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[ContainerImage.Summary]
            else
                Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                    val trimmed = line.trim
                    Json.decode[ImageListJsonDocker](trimmed) match
                        case Result.Success(dto) if dto.ID.nonEmpty => mapDockerImageListToSummary(dto)
                        case _ =>
                            decodeJson[ImageListJsonPodman](trimmed).map(mapPodmanImageListToSummary)
                    end match
                }
        }
    end imageList

    private def mapDockerImageListToSummary(dto: ImageListJsonDocker)(using Frame): ContainerImage.Summary =
        val repoTags =
            if dto.Repository.nonEmpty && dto.Tag.nonEmpty then
                Chunk.from(Seq(ContainerImage.parse(s"${dto.Repository}:${dto.Tag}").getOrElse(ContainerImage(dto.Repository, dto.Tag))))
            else Chunk.empty[ContainerImage]
        ContainerImage.Summary(
            id = ContainerImage.Id(dto.ID),
            repoTags = repoTags,
            repoDigests = Chunk.empty,
            createdAt = if dto.CreatedAt.nonEmpty then parseTimestamp(dto.CreatedAt) else Instant.Epoch,
            size = parseSizeString(dto.Size),
            labels = Dict.empty
        )
    end mapDockerImageListToSummary

    private def mapPodmanImageListToSummary(dto: ImageListJsonPodman)(using Frame): ContainerImage.Summary =
        val id = dto.Id

        val repoTagStrs = dto.RepoTags.filter(_.nonEmpty).getOrElse {
            if dto.repository.nonEmpty && dto.tag.nonEmpty then Seq(s"${dto.repository}:${dto.tag}")
            else Seq.empty
        }
        val repoTags    = Chunk.from(repoTagStrs.map(s => ContainerImage.parse(s).getOrElse(ContainerImage(s))))
        val repoDigests = Chunk.from(dto.RepoDigests.getOrElse(Seq.empty).map(s => ContainerImage.parse(s).getOrElse(ContainerImage(s))))

        ContainerImage.Summary(
            id = ContainerImage.Id(id),
            repoTags = repoTags,
            repoDigests = repoDigests,
            createdAt = if dto.Created > 0 then Instant.fromJava(java.time.Instant.ofEpochSecond(dto.Created)) else Instant.Epoch,
            size = dto.Size,
            labels = Dict.from(dto.Labels.getOrElse(Map.empty))
        )
    end mapPodmanImageListToSummary

    def imageInspect(image: ContainerImage)(using Frame): ContainerImage.Info < (Async & Abort[ContainerException]) =
        run(ResourceContext.Image(image.reference), "image", "inspect", "--format", "{{json .}}", image.reference).map { raw =>
            decodeJson[ImageInspectJson](raw).map { dto =>
                ContainerImage.Info(
                    id = ContainerImage.Id(dto.Id),
                    repoTags = Chunk.from(dto.RepoTags.getOrElse(Seq.empty).map(s =>
                        ContainerImage.parse(s).getOrElse(ContainerImage(s))
                    )),
                    repoDigests = Chunk.from(dto.RepoDigests.getOrElse(Seq.empty).map(s =>
                        ContainerImage.parse(s).getOrElse(ContainerImage(s))
                    )),
                    createdAt = parseInstant(Option(dto.Created).filter(_.nonEmpty)).getOrElse(Instant.Epoch),
                    size = dto.Size,
                    labels = Dict.from(dto.Config.Labels.getOrElse(Map.empty)),
                    architecture = dto.Architecture,
                    os = dto.Os
                )
            }
        }

    def imageRemove(image: ContainerImage, force: Boolean, noPrune: Boolean)(
        using Frame
    ): Chunk[ContainerImage.DeleteResponse] < (Async & Abort[ContainerException]) =
        val args = Chunk("rmi") ++
            (if force then Chunk("-f") else Chunk.empty) ++
            (if noPrune then Chunk("--no-prune") else Chunk.empty) ++
            Chunk(image.reference)
        run(ResourceContext.Image(image.reference), args.toSeq*).map { output =>
            Chunk.from(output.split("\n").flatMap { line =>
                val trimmed = line.trim
                if trimmed.startsWith("Untagged:") then
                    Seq(ContainerImage.DeleteResponse(untagged = Present(trimmed.stripPrefix("Untagged:").trim), deleted = Absent))
                else if trimmed.startsWith("Deleted:") then
                    Seq(ContainerImage.DeleteResponse(untagged = Absent, deleted = Present(trimmed.stripPrefix("Deleted:").trim)))
                else Seq.empty
                end if
            })
        }
    end imageRemove

    def imageTag(source: ContainerImage, repo: String, tag: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Image(source.reference), "tag", source.reference, s"$repo:$tag")

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
        // Stream-based context not supported via CLI, use imageBuildFromPath instead
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
            val args = Chunk("build") ++
                tags.flatMap(t => Chunk("-t", t)) ++
                (if dockerfile != "Dockerfile" then Chunk("-f", dockerfile) else Chunk.empty) ++
                Chunk.from(buildArgs.toMap.toSeq.flatMap { case (k, v) => Seq("--build-arg", s"$k=$v") }) ++
                Chunk.from(labels.toMap.toSeq.flatMap { case (k, v) => Seq("--label", s"$k=$v") }) ++
                (if noCache then Chunk("--no-cache") else Chunk.empty) ++
                (if pull then Chunk("--pull") else Chunk.empty) ++
                target.map(t => Chunk("--target", t)).getOrElse(Chunk.empty) ++
                platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
                Chunk(path.toString)

            val buildCmd = Command((cmd +: args.toSeq)*).redirectErrorStream(true)
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
                        }.emit.andThen(verifyBuildProduced(tags))
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](
                            ContainerBuildFailedException(path.toString, "build command failed", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerBuildFailedException(path.toString, "build panicked", ex)
                        ).unit
                }
            }
        }

    /** After a build stream completes, confirm the daemon produced an image for the first tag.
      *
      * `docker build` / `podman build` write their progress to stdout/stderr and only signal failure via the process exit code, which this
      * backend's `.emit` streaming pipeline does not check. Inspecting the tagged image post-build gives an API-level ground truth that
      * works uniformly across builder variants.
      */
    private def verifyBuildProduced(tags: Chunk[String])(using Frame): Unit < (Async & Abort[ContainerException]) =
        tags.headOption match
            case None => Kyo.unit
            case Some(tag) =>
                Abort.run[ContainerException](imageInspect(ContainerImage.parse(tag).getOrElse(ContainerImage(tag)))).map {
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

    def imagePush(image: ContainerImage, auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Image(image.reference), "push", image.reference)

    def imageSearch(term: String, limit: Int, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[ContainerImage.SearchResult] < (Async & Abort[ContainerException]) =
        val args = Chunk("search") ++
            (if limit != Int.MaxValue then Chunk("--limit", limit.toString) else Chunk.empty) ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--format", "{{json .}}") ++
            Chunk(term)
        run(ResourceContext.Op("imageSearch"), args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[ContainerImage.SearchResult]
            else
                Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                    val trimmed = line.trim
                    // Docker: IsOfficial/IsAutomated are strings ("OK"/""), StarCount is a string
                    // Podman: IsOfficial/IsAutomated are booleans, StarCount is an int
                    Json.decode[ImageSearchJsonPodman](trimmed) match
                        case Result.Success(dto) if dto.Name.nonEmpty => mapPodmanImageSearchToResult(dto)
                        case _ =>
                            decodeJson[ImageSearchJsonDocker](trimmed).map(mapDockerImageSearchToResult)
                    end match
                }
        }
    end imageSearch

    private def mapDockerImageSearchToResult(dto: ImageSearchJsonDocker)(using Frame): ContainerImage.SearchResult =
        val stars =
            Result.catching[NumberFormatException](dto.StarCount.toInt).getOrElse(0)
        ContainerImage.SearchResult(
            name = dto.Name,
            description = dto.Description,
            stars = stars,
            isOfficial = dto.IsOfficial == "OK" || dto.IsOfficial.equalsIgnoreCase("true"),
            isAutomated = dto.IsAutomated == "OK" || dto.IsAutomated.equalsIgnoreCase("true")
        )
    end mapDockerImageSearchToResult

    private def mapPodmanImageSearchToResult(dto: ImageSearchJsonPodman)(using Frame): ContainerImage.SearchResult =
        ContainerImage.SearchResult(
            name = dto.Name,
            description = dto.Description,
            stars = dto.StarCount,
            isOfficial = dto.IsOfficial,
            isAutomated = dto.IsAutomated
        )
    end mapPodmanImageSearchToResult

    def imageHistory(image: ContainerImage)(using Frame): Chunk[ContainerImage.HistoryEntry] < (Async & Abort[ContainerException]) =
        run(ResourceContext.Image(image.reference), "history", "--format", "{{json .}}", image.reference).map { output =>
            if output.trim.isEmpty then Chunk.empty[ContainerImage.HistoryEntry]
            else
                Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                    decodeJson[ImageHistoryJson](line.trim).map { dto =>
                        val id         = if dto.ID.nonEmpty then dto.ID else dto.id
                        val createdStr = if dto.CreatedAt.nonEmpty then dto.CreatedAt else dto.created
                        val createdAt  = if createdStr.isEmpty then Instant.Epoch else parseTimestamp(createdStr)
                        val size       = if dto.size > 0 then dto.size else parseSizeString(dto.Size)
                        val comment    = if dto.Comment.nonEmpty then dto.Comment else dto.comment
                        ContainerImage.HistoryEntry(
                            id = id,
                            createdAt = createdAt,
                            createdBy = dto.CreatedBy,
                            size = size,
                            tags = Chunk.empty,
                            comment = comment
                        )
                    }
                }
        }

    def imagePrune(filters: Dict[String, Chunk[String]])(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        val args = Chunk("image", "prune", "-f") ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            })
        run(ResourceContext.Op("imagePrune"), args.toSeq*).map { output =>
            val lines = output.split("\n")
            val deleted = Chunk.from(lines.filter(l => l.startsWith("deleted:") || l.startsWith("untagged:"))
                .map(_.split(":").last.trim))
            val spaceReclaimed = lines.find(_.contains("reclaimed")).flatMap { line =>
                val pattern = """(\d+(?:\.\d+)?)\s*(B|kB|MB|GB|TB)""".r
                pattern.findFirstMatchIn(line).map(m => parseSizeString(m.group(0)))
            }.getOrElse(0L)
            PruneResult(deleted, spaceReclaimed)
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
        val args = Chunk("commit") ++
            (if comment.nonEmpty then Chunk("-m", comment) else Chunk.empty) ++
            (if author.nonEmpty then Chunk("-a", author) else Chunk.empty) ++
            (if !pause then Chunk("--pause=false") else Chunk.empty) ++
            Chunk(container.value) ++
            (if repo.nonEmpty && tag.nonEmpty then Chunk(s"$repo:$tag")
             else if repo.nonEmpty then Chunk(repo)
             else Chunk.empty)
        run(ResourceContext.Container(container), args.toSeq*).map(_.trim)
    end imageCommit

    // --- Network Operations ---

    def networkCreate(config: Network.Config)(using Frame): Network.Id < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "create") ++
            (if config.driver != Container.NetworkDriver.Bridge then Chunk("--driver", config.driver.cliName) else Chunk.empty) ++
            (if config.internal then Chunk("--internal") else Chunk.empty) ++
            // Note: podman doesn't support --attachable flag; skip it
            (if config.enableIPv6 then Chunk("--ipv6") else Chunk.empty) ++
            Chunk.from(config.labels.toMap.toSeq.flatMap { case (k, v) => Seq("--label", s"$k=$v") }) ++
            Chunk.from(config.options.toMap.toSeq.flatMap { case (k, v) => Seq("--opt", s"$k=$v") }) ++
            config.ipam.map { ipam =>
                (if ipam.driver != "default" then Chunk("--ipam-driver", ipam.driver) else Chunk.empty) ++
                    ipam.config.flatMap { pool =>
                        pool.subnet.map(s => Chunk("--subnet", s)).getOrElse(Chunk.empty) ++
                            pool.gateway.map(g => Chunk("--gateway", g)).getOrElse(Chunk.empty) ++
                            pool.ipRange.map(r => Chunk("--ip-range", r)).getOrElse(Chunk.empty)
                    }
            }.getOrElse(Chunk.empty) ++
            Chunk(config.name)
        run(ResourceContext.Op("networkCreate"), args.toSeq*).map { _ =>
            // Always return the network name as the ID.
            // Docker returns a hash but uses names as keys in inspect/connect;
            // Podman already returns the name. Using the name consistently
            // ensures Network.Id works with all subsequent operations.
            Network.Id(config.name)
        }
    end networkCreate

    def networkList(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Network.Info] < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "ls") ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--format", "{{json .}}")
        run(ResourceContext.Op("networkList"), args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[Network.Info]
            else
                Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                    val trimmed = line.trim
                    // Docker: all string fields (booleans as "true"/"false", labels as comma-separated)
                    // Podman: native types (booleans, labels as object)
                    Json.decode[NetworkListJsonPodman](trimmed) match
                        case Result.Success(dto) if dto.name.nonEmpty || dto.id.nonEmpty => mapPodmanNetworkListToInfo(dto)
                        case _ =>
                            decodeJson[NetworkListJsonDocker](trimmed).map(mapDockerNetworkListToInfo)
                    end match
                }
        }
    end networkList

    private def mapDockerNetworkListToInfo(dto: NetworkListJsonDocker)(using Frame): Network.Info =
        val labelsMap: Dict[String, String] =
            if dto.Labels.nonEmpty then
                Dict.from(dto.Labels.split(",").iterator.filter(_.contains("=")).map { pair =>
                    val kv = pair.split("=", 2)
                    kv(0).trim -> kv(1).trim
                }.toMap)
            else Dict.empty

        Network.Info(
            id = Network.Id(dto.ID),
            name = dto.Name,
            driver = Container.NetworkDriver.parse(dto.Driver),
            scope = dto.Scope,
            internal = dto.Internal.equalsIgnoreCase("true"),
            attachable = false,
            enableIPv6 = dto.IPv6.equalsIgnoreCase("true"),
            labels = labelsMap,
            options = Dict.empty,
            containers = Dict.empty,
            createdAt = parseInstant(Option(dto.CreatedAt).filter(_.nonEmpty)).getOrElse(Instant.Epoch)
        )
    end mapDockerNetworkListToInfo

    private def mapPodmanNetworkListToInfo(dto: NetworkListJsonPodman)(using Frame): Network.Info =
        Network.Info(
            id = Network.Id(dto.id),
            name = dto.name,
            driver = Container.NetworkDriver.parse(dto.driver),
            scope = dto.scope,
            internal = dto.internal,
            attachable = false,
            enableIPv6 = dto.ipv6_enabled,
            labels = Dict.from(dto.labels.getOrElse(Map.empty)),
            options = Dict.empty,
            containers = Dict.empty,
            createdAt = parseInstant(Option(dto.created).filter(_.nonEmpty)).getOrElse(Instant.Epoch)
        )
    end mapPodmanNetworkListToInfo

    def networkInspect(id: Network.Id)(using Frame): Network.Info < (Async & Abort[ContainerException]) =
        run(ResourceContext.Network(id), "network", "inspect", "--format", "{{json .}}", id.value).map { raw =>
            decodeJson[NetworkInspectJson](raw).map { dto =>
                val name = if dto.Name.nonEmpty then dto.Name else dto.name
                // Use name as ID for consistency with networkCreate (which returns name for podman)
                val nid = if name.nonEmpty then name
                else if dto.Id.nonEmpty then dto.Id
                else if dto.id.nonEmpty then dto.id
                else id.value

                val containersMap = dto.Containers.orElse(dto.containers).getOrElse(Map.empty)
                val containers = Dict.from(containersMap.map { case (cid, c) =>
                    Container.Id(cid) -> Info.NetworkEndpoint(
                        networkId = Network.Id(nid),
                        endpointId = c.EndpointID,
                        gateway = "",
                        ipAddress = c.IPv4Address.takeWhile(_ != '/'),
                        ipPrefixLen = 0,
                        macAddress = c.MacAddress
                    )
                })

                val createdStr = if dto.Created.nonEmpty then dto.Created else dto.created

                Network.Info(
                    id = Network.Id(nid),
                    name = name,
                    driver = Container.NetworkDriver.parse(if dto.Driver.nonEmpty then dto.Driver else dto.driver),
                    scope = if dto.Scope.nonEmpty then dto.Scope else dto.scope,
                    internal = dto.Internal || dto.internal,
                    attachable = dto.Attachable || dto.attachable,
                    enableIPv6 = dto.EnableIPv6 || dto.ipv6_enabled,
                    labels = Dict.from(dto.Labels.orElse(dto.labels).getOrElse(Map.empty)),
                    options = Dict.from(dto.Options.orElse(dto.options).getOrElse(Map.empty)),
                    containers = containers,
                    createdAt = parseInstant(Option(createdStr).filter(_.nonEmpty)).getOrElse(Instant.Epoch)
                )
            }
        }

    def networkRemove(id: Network.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Network(id), "network", "rm", id.value)

    def networkConnect(network: Network.Id, container: Container.Id, aliases: Chunk[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "connect") ++
            aliases.flatMap(a => Chunk("--alias", a)) ++
            Chunk(network.value) ++
            Chunk(container.value)
        Abort.run[ContainerException] {
            runUnit(ResourceContext.Network(network), args.toSeq*)
        }.map {
            case Result.Success(_)                             => ()
            case Result.Failure(_: ContainerConflictException) => ()
            case Result.Failure(e)                             => Abort.fail(e)
            case Result.Panic(e)                               => Abort.panic(e)
        }
    end networkConnect

    def networkDisconnect(network: Network.Id, container: Container.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "disconnect") ++
            (if force then Chunk("-f") else Chunk.empty) ++
            Chunk(network.value) ++
            Chunk(container.value)
        runUnit(ResourceContext.Network(network), args.toSeq*)
    end networkDisconnect

    def networkPrune(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Network.Id] < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "prune", "-f") ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            })
        run(ResourceContext.Op("networkPrune"), args.toSeq*).map { output =>
            Chunk.from(output.split("\n")
                .filter(_.trim.nonEmpty)
                .filterNot(_.toLowerCase.contains("deleted"))
                .map(l => Network.Id(l.trim)))
        }
    end networkPrune

    // --- Volume Operations ---

    def volumeCreate(config: Volume.Config)(using Frame): Volume.Info < (Async & Abort[ContainerException]) =
        val args = Chunk("volume", "create") ++
            // Podman uses positional name, not --name flag
            (if config.driver != "local" then Chunk("--driver", config.driver) else Chunk.empty) ++
            Chunk.from(config.driverOpts.toMap.toSeq.flatMap { case (k, v) => Seq("--opt", s"$k=$v") }) ++
            Chunk.from(config.labels.toMap.toSeq.flatMap { case (k, v) => Seq("--label", s"$k=$v") }) ++
            config.name.map(n => Chunk(n.value)).getOrElse(Chunk.empty)
        run(ResourceContext.Op("volumeCreate"), args.toSeq*).map(_.trim).map { name =>
            // After create, inspect to get full info
            volumeInspect(Volume.Id(name))
        }
    end volumeCreate

    def volumeList(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Volume.Info] < (Async & Abort[ContainerException]) =
        val args = Chunk("volume", "ls") ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--format", "{{json .}}")
        run(ResourceContext.Op("volumeList"), args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[Volume.Info]
            else
                Chunk.from(output.trim.split("\n")).flatMap { line =>
                    val trimmed = line.trim
                    Json.decode[VolumeListJsonDocker](trimmed) match
                        case Result.Success(dto) =>
                            val labels =
                                if dto.Labels.nonEmpty then
                                    Dict.from(dto.Labels.split(",").filter(_.contains("=")).map { pair =>
                                        val kv = pair.split("=", 2)
                                        kv(0) -> (if kv.length > 1 then kv(1) else "")
                                    }.toMap)
                                else Dict.empty[String, String]
                            Chunk(Volume.Info(
                                name = Volume.Id(dto.Name),
                                driver = dto.Driver,
                                mountpoint = dto.Mountpoint,
                                labels = labels,
                                options = Dict.empty,
                                createdAt = Instant.Epoch,
                                scope = dto.Scope
                            ))
                        case _ =>
                            Json.decode[VolumeListJsonPodman](trimmed) match
                                case Result.Success(dto) =>
                                    Chunk(Volume.Info(
                                        name = Volume.Id(dto.Name),
                                        driver = dto.Driver,
                                        mountpoint = dto.Mountpoint,
                                        labels = Dict.from(dto.Labels.getOrElse(Map.empty)),
                                        options = Dict.empty,
                                        createdAt = Instant.Epoch,
                                        scope = dto.Scope
                                    ))
                                case _ => Chunk.empty
                    end match
                }
        }
    end volumeList

    def volumeInspect(id: Volume.Id)(using Frame): Volume.Info < (Async & Abort[ContainerException]) =
        run(ResourceContext.Volume(id), "volume", "inspect", "--format", "{{json .}}", id.value).map { raw =>
            decodeJson[VolumeInspectJson](raw).map { dto =>
                Volume.Info(
                    name = if dto.Name.nonEmpty then Volume.Id(dto.Name) else id,
                    driver = if dto.Driver.nonEmpty then dto.Driver else "local",
                    mountpoint = dto.Mountpoint,
                    labels = Dict.from(dto.Labels.getOrElse(Map.empty)),
                    options = Dict.from(dto.Options.getOrElse(Map.empty)),
                    createdAt = parseInstant(Option(dto.CreatedAt).filter(_.nonEmpty)).getOrElse(Instant.Epoch),
                    scope = if dto.Scope.nonEmpty then dto.Scope else "local"
                )
            }
        }

    def volumeRemove(id: Volume.Id, force: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val ctx = ResourceContext.Volume(id)
        // Check if any containers (including stopped) reference this volume
        run(ctx, "ps", "-a", "--filter", s"volume=${id.value}", "--format", "{{.ID}}").map { output =>
            val ids = output.trim
            if ids.nonEmpty then
                Abort.fail[ContainerException](
                    ContainerVolumeInUseException(id, ids)
                )
            else
                val args = Chunk("volume", "rm") ++
                    (if force then Chunk("-f") else Chunk.empty) ++
                    Chunk(id.value)
                runUnit(ctx, args.toSeq*)
            end if
        }
    end volumeRemove

    def volumePrune(filters: Dict[String, Chunk[String]])(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        val args = Chunk("volume", "prune", "-f") ++
            Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
                vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
            })
        run(ResourceContext.Op("volumePrune"), args.toSeq*).map { output =>
            val lines   = output.split("\n")
            val deleted = Chunk.from(lines.filter(_.trim.nonEmpty).filterNot(_.toLowerCase.contains("reclaimed")))
            val spaceReclaimed = lines.find(_.contains("reclaimed")).flatMap { line =>
                val pattern = """(\d+(?:\.\d+)?)\s*(B|kB|MB|GB|TB)""".r
                pattern.findFirstMatchIn(line).map(m => parseSizeString(m.group(0)))
            }.getOrElse(0L)
            PruneResult(deleted, spaceReclaimed)
        }
    end volumePrune

    // --- Backend Detection ---

    def describe: String = s"ShellBackend(cli=$cmd)"

    def detect()(using Frame): Unit < (Async & Abort[ContainerException]) =
        // Already constructed with a specific cmd, so just verify it works
        Abort.runWith[CommandException](Command(cmd, "version").textWithExitCode) {
            case Result.Success((_, exitCode)) =>
                if exitCode != ExitCode.Success then
                    Abort.fail(ContainerBackendUnavailableException(cmd, s"$cmd version returned non-zero exit code"))
                else ()
            case Result.Failure(cmdEx) =>
                Abort.fail(ContainerBackendUnavailableException(cmd, s"$cmd not available: $cmdEx"))
            case Result.Panic(ex) =>
                Abort.fail(ContainerBackendUnavailableException(cmd, s"$cmd not available: $ex"))
        }

    // --- Private Helpers ---

    /** Run a container CLI command, returning stdout on success or mapping errors via [[mapError]].
      *
      * Merges stderr into stdout using `sh -c "... 2>&1"` instead of `redirectErrorStream` which does not merge on JS (Node.js discards
      * stderr instead of piping it to stdout). On non-zero exit, the combined output is passed to [[mapError]] for classification.
      */
    private def run(ctx: ResourceContext, args: String*)(using Frame): String < (Async & Abort[ContainerException]) =
        Abort.runWith[Closed](meter.run {
            val shellCmd = (cmd +: args).map(a => "'" + a.replace("'", "'\\''") + "'").mkString(" ")
            Abort.runWith[CommandException](Command("sh", "-c", s"$shellCmd 2>&1").textWithExitCode) {
                case Result.Success((stdout, exitCode)) =>
                    if exitCode == ExitCode.Success then stdout.trim
                    else Abort.fail(mapError(stdout.trim, ctx, args))
                case Result.Failure(cmdEx) =>
                    Abort.fail(ContainerOperationException(s"Command failed: ${(cmd +: args).mkString(" ")}", cmdEx))
                case Result.Panic(ex) =>
                    Abort.fail(ContainerBackendException(s"Command panicked: ${(cmd +: args).mkString(" ")}", ex))
            }
        }) {
            case Result.Success(v) => v
            case Result.Failure(_) =>
                Abort.fail(ContainerBackendException(s"Concurrency meter closed during ${cmd} ${args.mkString(" ")}", "meter was closed"))
            case Result.Panic(ex) =>
                Abort.fail(ContainerBackendException(s"Concurrency meter panicked during ${cmd} ${args.mkString(" ")}", ex))
        }
    end run

    private def runUnit(ctx: ResourceContext, args: String*)(using Frame): Unit < (Async & Abort[ContainerException]) =
        run(ctx, args*).unit

    /** Map shell command error output to a typed ContainerException.
      *
      * Classification uses a combination of exit codes and string matching on stderr/stdout:
      *   - Exit code 125: Podman infrastructure error (container not found, not running, etc.)
      *   - Exit code 126: Command cannot be invoked (permission denied or not executable)
      *   - Exit code 127: Command not found in container
      *   - Exit code 1: Generic error — must use string matching on output to classify
      *   - All other codes: Fall through to string matching on the combined output
      *
      * String matching is needed because Docker always uses exit code 1 for daemon errors, while Podman uses 125. Both engines emit
      * descriptive error messages on stderr that we match against known patterns to produce specific exception types.
      */
    private def mapError(output: String, ctx: ResourceContext, args: Seq[String])(using Frame): ContainerException =
        val lower = output.toLowerCase

        def matchesAny(patterns: Seq[String]): Boolean = patterns.exists(lower.contains)

        if matchesAny(ErrorPatterns.NoSuchContainer) then
            ctx match
                case ResourceContext.Container(id) => ContainerMissingException(id)
                case ResourceContext.Image(ref) =>
                    ContainerImageMissingException(ContainerImage.parse(ref).getOrElse(ContainerImage(ref)))
                case ResourceContext.Network(id) => ContainerNetworkMissingException(id)
                case ResourceContext.Volume(id)  => ContainerVolumeMissingException(id)
                case ResourceContext.Op(name)    => ContainerOperationException(s"Resource not found during $name", output)
        else if matchesAny(ErrorPatterns.ImageNotFound) then
            ctx match
                case ResourceContext.Image(ref) =>
                    ContainerImageMissingException(ContainerImage.parse(ref).getOrElse(ContainerImage(ref)))
                case _ => ContainerOperationException(s"Image not found during ${ctx.describe}", output)
        else if matchesAny(ErrorPatterns.Conflict) then
            ctx match
                case ResourceContext.Container(id) => ContainerAlreadyExistsException(id.value)
                case ResourceContext.Op(name)      => ContainerAlreadyExistsException(name)
                case other                         => ContainerAlreadyExistsException(other.describe)
        else if matchesAny(ErrorPatterns.AlreadyRunning) then
            ctx match
                case ResourceContext.Container(id) => ContainerAlreadyRunningException(id)
                case _                             => ContainerConflictException(s"Already running: ${ctx.describe}", output)
        else if matchesAny(ErrorPatterns.AlreadyStopped) then
            ctx match
                case ResourceContext.Container(id) => ContainerAlreadyStoppedException(id)
                case _                             => ContainerConflictException(s"Already stopped: ${ctx.describe}", output)
        else if lower.contains("network") && lower.contains("not found") then
            ctx match
                case ResourceContext.Network(id) => ContainerNetworkMissingException(id)
                case _                           => ContainerOperationException(s"Network not found during ${ctx.describe}", output)
        else if matchesAny(ErrorPatterns.VolumeNotFound) then
            ctx match
                case ResourceContext.Volume(id) => ContainerVolumeMissingException(id)
                case _                          => ContainerOperationException(s"Volume not found during ${ctx.describe}", output)
        else if matchesAny(ErrorPatterns.BackendUnavailable) then
            ContainerBackendUnavailableException(cmd, s"Backend unavailable: $output")
        else if matchesAny(ErrorPatterns.PortConflict) then
            val portPattern = """(\d+)""".r
            val port = portPattern.findFirstIn(
                Maybe.fromOption(output.split("port").lastOption).getOrElse("")
            ).flatMap(_.toIntOption).getOrElse(0)
            ContainerPortConflictException(port, output)
        else if matchesAny(ErrorPatterns.AuthFailed) then
            ContainerAuthException(ctx.describe, output)
        else if matchesAny(ErrorPatterns.VolumeInUse) then
            ctx match
                case ResourceContext.Volume(id) => ContainerVolumeInUseException(id, output)
                case _                          => ContainerConflictException(s"Volume in use during ${ctx.describe}", output)
        else if matchesAny(ErrorPatterns.NetworkEndpointExists) then
            ContainerConflictException(s"Network endpoint already attached: ${ctx.describe}", output)
        else if lower.contains("initializing source") then
            val dockerIdx = output.indexOf("docker://")
            val imageRef =
                if dockerIdx >= 0 then
                    val afterPrefix = output.substring(dockerIdx + "docker://".length)
                    val colonSpace  = afterPrefix.indexOf(": ")
                    if colonSpace >= 0 then afterPrefix.substring(0, colonSpace)
                    else afterPrefix.takeWhile(!_.isWhitespace)
                else
                    ctx match
                        case ResourceContext.Image(ref) => ref
                        case other                      => other.describe
            ContainerImageMissingException(ContainerImage.parse(imageRef).getOrElse(ContainerImage(imageRef)))
        else
            ContainerOperationException(s"${cmd} ${args.mkString(" ")} failed", output)
        end if
    end mapError

    /** Named constants for error message patterns used in [[mapError]].
      *
      * Each pattern set covers both Docker and Podman error messages. Docker uses "Error response from daemon: ..." on stderr with exit
      * code 1. Podman uses direct error messages with exit code 125 for infrastructure errors.
      */
    private object ErrorPatterns:
        /** Container not found — Docker: "No such container", Podman: "no such container", "no such object" */
        val NoSuchContainer: Seq[String] =
            Seq("no such container", "no such object", "no container with name or id")

        /** Image not found — Docker: "manifest unknown", Podman: "image not found" */
        val ImageNotFound: Seq[String] =
            Seq(
                "no such image",
                "image not found",
                "manifest unknown",
                "requested access to the resource is denied",
                "image not known",
                "repository does not exist",
                "name unknown"
            )

        /** Name/ID conflict — Docker & Podman both use "conflict" or "already in use" */
        val Conflict: Seq[String] =
            Seq("conflict", "already in use", "name is already in use")

        /** Container already running — Docker: "is already running", Podman: "container already started" */
        val AlreadyRunning: Seq[String] =
            Seq("is already running", "container already started")

        /** Container already stopped — Docker: "is not running", Podman: "already stopped" */
        val AlreadyStopped: Seq[String] =
            Seq("is not running", "already stopped", "is already stopped", "container already stopped")

        /** Volume not found — Docker & Podman */
        val VolumeNotFound: Seq[String] =
            Seq("no such volume", "volume not found")

        /** Backend unavailable — Docker daemon not running or socket not reachable */
        val BackendUnavailable: Seq[String] =
            Seq("cannot connect", "connection refused", "is the docker daemon running")

        /** Port conflict — address already bound */
        val PortConflict: Seq[String] =
            Seq("port is already allocated", "address already in use", "bind: address already in use")

        /** Authentication failures — registry auth required */
        val AuthFailed: Seq[String] =
            Seq("unauthorized", "authentication required", "insufficient_scope", "denied")

        /** Volume in use — cannot remove a volume that is referenced by containers */
        val VolumeInUse: Seq[String] =
            Seq("volume is in use", "volume being used")

        /** Network endpoint already attached — Docker: "endpoint with name ... already exists", Podman: "is already connected to network",
          * "network is already connected"
          */
        val NetworkEndpointExists: Seq[String] =
            Seq(
                "is already connected to network",
                "network is already connected",
                "endpoint with name",
                "already attached to network"
            )
    end ErrorPatterns

    /** Decode JSON using a Schema[A] instance, stripping outer array brackets if present. */
    private def decodeJson[A](raw: String)(using schema: Schema[A], frame: Frame): A < Abort[ContainerException] =
        val trimmed = raw.trim
        val obj = if trimmed.startsWith("[") then
            trimmed.stripPrefix("[").stripSuffix("]").trim
        else trimmed
        Json.decode[A](obj) match
            case Result.Success(a)   => a
            case Result.Failure(err) => Abort.fail(ContainerDecodeException("Parse error in JSON decode", err))
            case Result.Panic(t)     => Abort.panic(t)
        end match
    end decodeJson

    /** Parse a timestamp string trying multiple formats. */
    private def parseTimestamp(s: String): Instant =
        if s.isEmpty then Instant.Epoch
        else
            parseInstant(Some(s)).getOrElse {
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
                Result.catching[java.time.format.DateTimeParseException](
                    Instant.fromJava(java.time.ZonedDateTime.parse(s, formatter).toInstant)
                ).getOrElse {
                    Result.catching[java.time.format.DateTimeParseException](
                        Instant.fromJava(java.time.ZonedDateTime.parse(s).toInstant)
                    ).getOrElse(Instant.Epoch)
                }
            }

    /** Parse a duration-like string to seconds (for docker inspect). */
    private def parseDurationSeconds(d: Duration): Long =
        d.toMillis / 1000

    /** Check if stderr contains a Docker daemon error indicating an infrastructure issue. */
    private def isDockerDaemonError(stderr: String): Boolean =
        stderr.contains("Error response from daemon") ||
            stderr.contains("error response from daemon") ||
            stderr.contains("No such container") ||
            stderr.contains("no such container") ||
            stderr.contains("is not running") ||
            stderr.contains("is paused")

end ShellBackend

private[kyo] object ShellBackend:

    /** Default capacity for the channel that merges exec stdout/stderr into a single `LogEntry` stream. Overridable via
      * `BackendConfig.Shell(... , streamBufferSize = N)`.
      */
    val defaultStreamBufferSize: Int = 256

    /** Auto-detect an available container runtime. Tries podman first, then docker. */
    def detect(
        meter: Meter = Meter.Noop,
        streamBufferSize: Int = defaultStreamBufferSize
    )(using Frame): ShellBackend < (Async & Abort[ContainerException]) =
        Abort.runWith[CommandException](Command("podman", "version").textWithExitCode) {
            case Result.Success((_, exitCode)) if exitCode == ExitCode.Success =>
                val backend = new ShellBackend("podman", meter, streamBufferSize)
                Log.info(s"kyo-pod: using shell backend (CLI=podman)").andThen(backend)
            case _ =>
                Abort.runWith[CommandException](Command("docker", "version").textWithExitCode) {
                    case Result.Success((_, exitCode)) if exitCode == ExitCode.Success =>
                        val backend = new ShellBackend("docker", meter, streamBufferSize)
                        Log.info(s"kyo-pod: using shell backend (CLI=docker)").andThen(backend)
                    case _ =>
                        Abort.fail(ContainerBackendUnavailableException(
                            "auto-detect",
                            "Neither podman nor docker is available. Install one of them."
                        ))
                }
        }

    // --- JSON DTOs for container inspect ---

    private[internal] case class InspectJson(
        Id: String = "",
        Name: String = "",
        Image: String = "",
        ImageName: String = "",
        Created: String = "",
        State: InspectStateJson = InspectStateJson(),
        Config: InspectConfigJson = InspectConfigJson(),
        NetworkSettings: InspectNetworkSettingsJson = InspectNetworkSettingsJson(),
        RestartCount: Int = 0,
        Driver: String = "",
        Mounts: Option[Seq[InspectMountJson]] = None,
        Platform: String = ""
    ) derives Schema

    private[internal] case class InspectStateJson(
        Status: String = "",
        Running: Boolean = false,
        Paused: Boolean = false,
        Restarting: Boolean = false,
        Dead: Boolean = false,
        Pid: Int = 0,
        ExitCode: Int = 0,
        StartedAt: String = "",
        FinishedAt: String = "",
        Health: Option[InspectHealthJson] = None
    ) derives Schema

    private[internal] case class InspectHealthJson(
        Status: String = ""
    ) derives Schema

    private[internal] case class InspectConfigJson(
        Image: String = "",
        Hostname: String = "",
        Cmd: Option[Seq[String]] = None,
        Env: Option[Seq[String]] = None,
        Labels: Option[Map[String, String]] = None,
        Tty: Boolean = false
    ) derives Schema

    private[internal] case class InspectNetworkSettingsJson(
        IPAddress: String = "",
        Gateway: String = "",
        MacAddress: String = "",
        Ports: Option[Map[String, Seq[PortMappingJson]]] = None,
        Networks: Option[Map[String, NetworkEndpointEntryJson]] = None
    ) derives Schema

    private[internal] case class PortMappingJson(
        HostIp: String = "",
        HostPort: String = ""
    ) derives Schema

    /** Podman `ps --format '{{json .}}'` serializes Ports as a flat array of objects. */
    private[internal] case class PodmanPsPortJson(
        host_ip: String = "",
        host_port: Int = 0,
        container_port: Int = 0,
        protocol: String = "tcp",
        range: Int = 1
    ) derives Schema

    private[internal] case class NetworkEndpointEntryJson(
        NetworkID: String = "",
        EndpointID: String = "",
        Gateway: String = "",
        IPAddress: String = "",
        IPPrefixLen: Int = 0,
        MacAddress: String = ""
    ) derives Schema

    private[internal] case class InspectMountJson(
        Type: String = "",
        Name: String = "",
        Source: String = "",
        Destination: String = "",
        RW: Boolean = true
    ) derives Schema

    // --- JSON DTOs for container stats ---
    // Stats JSON differs significantly between docker and podman.
    // Docker uses formatted strings (CPUPerc: "0.50%", MemUsage: "100MiB / 1GiB").
    // Podman uses numeric fields (CPU: 0.5, MemUsage: 104857600).
    // We use two separate DTOs and try Docker format first.

    private[internal] case class DockerStatsJson(
        CPUPerc: String = "",
        MemPerc: String = "",
        MemUsage: String = "",
        NetIO: String = "",
        BlockIO: String = "",
        PIDs: String = "0"
    ) derives Schema

    private[internal] case class PodmanStatsJson(
        CPU: Double = 0.0,
        CPUNano: Long = 0,
        CPUSystemNano: Long = 0,
        SystemNano: Long = 0,
        MemPerc: Double = 0.0,
        MemUsage: Long = 0,
        MemLimit: Long = 0,
        BlockInput: Long = 0,
        BlockOutput: Long = 0,
        PIDs: Long = 0,
        Network: Option[Map[String, NetStatsJson]] = None
    ) derives Schema

    private[internal] case class NetStatsJson(
        RxBytes: Long = 0,
        RxPackets: Long = 0,
        RxErrors: Long = 0,
        RxDropped: Long = 0,
        TxBytes: Long = 0,
        TxPackets: Long = 0,
        TxErrors: Long = 0,
        TxDropped: Long = 0
    ) derives Schema

    // --- JSON DTOs for container list (ps) ---
    // Docker uses PascalCase strings; podman uses PascalCase but some fields are arrays/objects.

    // Docker ps: all fields are strings
    private[internal] case class PsJsonDocker(
        ID: String = "",
        Names: String = "",
        Image: String = "",
        ImageID: String = "",
        Command: String = "",
        State: String = "",
        Status: String = "",
        Labels: String = "",
        CreatedAt: String = "",
        Ports: String = "",
        Mounts: String = "",
        Networks: String = ""
    ) derives Schema

    // Podman ps: some fields are arrays/objects/different casing
    private[internal] case class PsJsonPodman(
        Id: String = "",
        Names: Option[Seq[String]] = None,
        Image: String = "",
        ImageID: String = "",
        Command: Option[Seq[String]] = None,
        State: String = "",
        Status: String = "",
        Labels: Option[Map[String, String]] = None,
        Created: String = "",
        CreatedAt: String = "",
        Ports: Option[Seq[PodmanPsPortJson]] = None,
        Mounts: Option[Seq[String]] = None,
        Networks: Option[Seq[String]] = None
    ) derives Schema

    // --- JSON DTOs for image list ---
    // Docker: all strings, ID uppercase, CreatedAt as string, Size as string
    private[internal] case class ImageListJsonDocker(
        ID: String = "",
        Repository: String = "",
        Tag: String = "",
        CreatedAt: String = "",
        Size: String = ""
    ) derives Schema

    // Podman: Id lowercase, Created as epoch number, Size as number, Labels as object
    private[internal] case class ImageListJsonPodman(
        Id: String = "",
        repository: String = "",
        tag: String = "",
        Created: Long = 0,
        Size: Long = 0,
        RepoTags: Option[Seq[String]] = None,
        RepoDigests: Option[Seq[String]] = None,
        Labels: Option[Map[String, String]] = None
    ) derives Schema

    // --- JSON DTOs for image inspect ---

    private[internal] case class ImageInspectJson(
        Id: String = "",
        RepoTags: Option[Seq[String]] = None,
        RepoDigests: Option[Seq[String]] = None,
        Created: String = "",
        Size: Long = 0,
        Architecture: String = "",
        Os: String = "",
        Config: ImageInspectConfigJson = ImageInspectConfigJson()
    ) derives Schema

    private[internal] case class ImageInspectConfigJson(
        Labels: Option[Map[String, String]] = None
    ) derives Schema

    // --- JSON DTOs for image search ---
    // Docker: IsOfficial/IsAutomated are strings ("OK" or ""), StarCount is a string
    private[internal] case class ImageSearchJsonDocker(
        Name: String = "",
        Description: String = "",
        StarCount: String = "0",
        IsOfficial: String = "",
        IsAutomated: String = ""
    ) derives Schema

    // Podman: IsOfficial/IsAutomated are booleans, StarCount is an int
    private[internal] case class ImageSearchJsonPodman(
        Name: String = "",
        Description: String = "",
        StarCount: Int = 0,
        IsOfficial: Boolean = false,
        IsAutomated: Boolean = false
    ) derives Schema

    // --- JSON DTOs for image history ---

    private[internal] case class ImageHistoryJson(
        ID: String = "",
        id: String = "",
        CreatedAt: String = "",
        created: String = "",
        CreatedBy: String = "",
        Size: String = "",
        size: Long = 0,
        Comment: String = "",
        comment: String = ""
    ) derives Schema

    // --- JSON DTOs for network list ---
    // Docker: all strings (booleans as "true"/"false", labels as comma-separated string)
    private[internal] case class NetworkListJsonDocker(
        ID: String = "",
        Name: String = "",
        Driver: String = "",
        Scope: String = "",
        Internal: String = "",
        IPv6: String = "",
        CreatedAt: String = "",
        Labels: String = ""
    ) derives Schema

    // Podman: native types (booleans, labels as object)
    private[internal] case class NetworkListJsonPodman(
        id: String = "",
        name: String = "",
        driver: String = "",
        scope: String = "",
        internal: Boolean = false,
        ipv6_enabled: Boolean = false,
        created: String = "",
        labels: Option[Map[String, String]] = None
    ) derives Schema

    // --- JSON DTOs for network inspect ---

    private[internal] case class NetworkInspectJson(
        Id: String = "",
        id: String = "",
        Name: String = "",
        name: String = "",
        Driver: String = "",
        driver: String = "",
        Scope: String = "",
        scope: String = "",
        Internal: Boolean = false,
        internal: Boolean = false,
        Attachable: Boolean = false,
        attachable: Boolean = false,
        EnableIPv6: Boolean = false,
        ipv6_enabled: Boolean = false,
        Labels: Option[Map[String, String]] = None,
        labels: Option[Map[String, String]] = None,
        Options: Option[Map[String, String]] = None,
        options: Option[Map[String, String]] = None,
        Created: String = "",
        created: String = "",
        Containers: Option[Map[String, NetworkContainerJson]] = None,
        containers: Option[Map[String, NetworkContainerJson]] = None
    ) derives Schema

    private[internal] case class NetworkContainerJson(
        EndpointID: String = "",
        IPv4Address: String = "",
        MacAddress: String = ""
    ) derives Schema

    // --- JSON DTOs for volume list ---
    // Docker serializes Labels as a comma-separated string ("key=val,key2=val2").
    // Podman serializes Labels as a JSON object ({"key":"val"}).
    // We use two separate DTOs and try Docker format first.

    private[internal] case class VolumeListJsonDocker(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Scope: String = "",
        Labels: String = ""
    ) derives Schema

    private[internal] case class VolumeListJsonPodman(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Scope: String = "",
        Labels: Option[Map[String, String]] = None
    ) derives Schema

    // --- JSON DTOs for volume inspect ---

    private[internal] case class VolumeInspectJson(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Labels: Option[Map[String, String]] = None,
        Options: Option[Map[String, String]] = None,
        CreatedAt: String = "",
        Scope: String = ""
    ) derives Schema

end ShellBackend
