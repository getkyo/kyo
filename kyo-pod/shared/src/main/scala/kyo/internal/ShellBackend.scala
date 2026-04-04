package kyo.internal

import kyo.*

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
private[kyo] class ShellBackend(cmd: String) extends ContainerBackend:

    import Container.*
    import ShellBackend.*

    // --- Container Lifecycle ---

    def create(config: Config)(using Frame): Container.Id < (Async & Abort[ContainerException]) =
        val restartStr = config.restartPolicy match
            case Config.RestartPolicy.No                 => "no"
            case Config.RestartPolicy.Always             => "always"
            case Config.RestartPolicy.UnlessStopped      => "unless-stopped"
            case Config.RestartPolicy.OnFailure(retries) => s"on-failure:$retries"

        // Pre-resolve bind mount paths (effectful due to symlink resolution)
        Kyo.foreach(config.mounts) {
            case Config.Mount.Bind(source, target, readOnly) =>
                resolveHostPath(source).map { resolvedSrc =>
                    val ro = if readOnly then ":ro" else ""
                    Chunk("-v", s"$resolvedSrc:${target}$ro")
                }
            case Config.Mount.Volume(name, target, readOnly) =>
                val ro = if readOnly then ":ro" else ""
                Chunk("-v", s"${name.value}:${target}$ro"): Chunk[String] < (Async & Abort[ContainerException])
            case Config.Mount.Tmpfs(target, sizeBytes) =>
                Chunk(
                    "--tmpfs",
                    sizeBytes.map(s => s"${target}:size=$s").getOrElse(target.toString)
                ): Chunk[String] < (Async & Abort[ContainerException])
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
                config.labels.flatMap { case (k, v) => Chunk("--label", s"$k=$v") } ++
                // Ports
                config.ports.flatMap { pb =>
                    val proto = pb.protocol match
                        case Config.Protocol.TCP  => "tcp"
                        case Config.Protocol.UDP  => "udp"
                        case Config.Protocol.SCTP => "sctp"
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
                        case Config.NetworkMode.Bridge       => "bridge"
                        case Config.NetworkMode.Host         => "host"
                        case Config.NetworkMode.None         => "none"
                        case Config.NetworkMode.Shared(cid)  => s"container:${cid.value}"
                        case Config.NetworkMode.Custom(name) => name
                    Chunk("--network", modeStr)
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
                // Environment variables from Command
                config.command.env.flatMap { case (k, v) => Chunk("-e", s"$k=$v") } ++
                // Working directory from Command
                config.command.workDir.map(w => Chunk("-w", w.toString)).getOrElse(Chunk.empty) ++
                // Image
                Chunk(config.image.reference) ++
                // Command
                Chunk.from(config.command.args)

            // Run
            run(args.toSeq*).map(output => Container.Id(output.trim))
        }
    end create

    def start(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit("start", id.value)

    def stop(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit("stop", "-t", parseDurationSeconds(timeout).toString, id.value)

    def kill(id: Container.Id, signal: Container.Signal)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit("kill", "-s", signal.name, id.value)

    def restart(id: Container.Id, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit("restart", "-t", parseDurationSeconds(timeout).toString, id.value)

    def pause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit("pause", id.value)

    def unpause(id: Container.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit("unpause", id.value)

    def remove(id: Container.Id, force: Boolean, removeVolumes: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("rm") ++
            (if force then Chunk("-f") else Chunk.empty) ++
            (if removeVolumes then Chunk("-v") else Chunk.empty) ++
            Chunk(id.value)
        runUnit(args.toSeq*)
    end remove

    def rename(id: Container.Id, newName: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit("rename", id.value, newName)

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
                    ContainerException.General(s"waitForExit panicked for ${id.value}", ex)
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
            run("inspect", "--format", "{{.State.ExitCode}}", id.value)
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
            runUnit("container", "checkpoint", id.value, "--export", s"/tmp/$name.tar")
        else
            runUnit("checkpoint", "create", id.value, name)

    def restore(id: Container.Id, checkpoint: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        if cmd == "podman" then
            runUnit("container", "restore", "--import", s"/tmp/$checkpoint.tar")
        else
            runUnit("start", "--checkpoint", checkpoint, id.value)

    // --- Health ---

    def isHealthy(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException]) =
        state(id).map(_ == State.Running)

    // --- Inspection ---

    def inspect(id: Container.Id)(using Frame): Info < (Async & Abort[ContainerException]) =
        run("inspect", "--format", "{{json .}}", id.value).map { raw =>
            decodeJson[InspectJson](raw).map(mapInspectToInfo(id, _))
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
        val networks = dto.NetworkSettings.Networks.getOrElse(Map.empty).map { case (name, ep) =>
            Network.Id(name) -> Info.NetworkEndpoint(
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

        // Parse env "KEY=VALUE" strings into Map
        val envMap: Map[String, String] = dto.Config.Env.getOrElse(Seq.empty).flatMap { entry =>
            val idx = entry.indexOf('=')
            if idx >= 0 then Seq(entry.substring(0, idx) -> entry.substring(idx + 1))
            else Seq.empty
        }.toMap

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
            startedAt = parseInstant(dto.State.StartedAt),
            finishedAt = parseInstant(dto.State.FinishedAt),
            healthStatus = healthStatus,
            ports = portsChunk,
            mounts = mounts,
            labels = dto.Config.Labels.getOrElse(Map.empty),
            env = envMap,
            command = dto.Config.Cmd.getOrElse(Seq.empty).mkString(" "),
            createdAt = parseInstant(dto.Created).getOrElse(Instant.Epoch),
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

    private def parseState(status: String): State =
        status.toLowerCase match
            case "created"            => State.Created
            case "running"            => State.Running
            case "paused"             => State.Paused
            case "restarting"         => State.Restarting
            case "removing"           => State.Removing
            case "exited" | "stopped" => State.Stopped
            case "dead"               => State.Dead
            case _                    => State.Stopped

    def state(id: Container.Id)(using Frame): State < (Async & Abort[ContainerException]) =
        run("inspect", "--format", "{{.State.Status}}", id.value).map(parseState)

    def stats(id: Container.Id)(using Frame): Stats < (Async & Abort[ContainerException]) =
        // Check container state first — Docker returns zero-valued stats for stopped
        // containers with exit code 0, so we need to detect and fail explicitly.
        state(id).map { s =>
            if s == State.Stopped || s == State.Dead then
                Abort.fail[ContainerException](
                    ContainerException.AlreadyStopped(id)
                )
            else
                run("stats", "--no-stream", "--format", "{{json .}}", id.value).map { raw =>
                    val trimmed = raw.trim
                    // Try Docker format (string fields) first, then Podman format (numeric fields)
                    Json[DockerStatsJson].decode(trimmed) match
                        case Result.Success(dto) if dto.CPUPerc.nonEmpty => mapDockerStats(dto)
                        case _ =>
                            Json[PodmanStatsJson].decode(trimmed) match
                                case Result.Success(dto) => mapPodmanStats(dto)
                                case Result.Failure(err) =>
                                    Abort.fail(ContainerException.ParseError("stats JSON", err))
                    end match
                }
            end if
        }

    private def mapDockerStats(dto: DockerStatsJson)(using Frame): Stats =
        val cpuPerc                 = parsePercent(dto.CPUPerc)
        val memPerc                 = parsePercent(dto.MemPerc)
        val (memUsage, memLimit)    = parseSlashPair(dto.MemUsage)
        val (blockRead, blockWrite) = parseSlashPair(dto.BlockIO)
        val (netRx, netTx)          = parseSlashPair(dto.NetIO)
        val pidsVal =
            Result.catching[NumberFormatException](dto.PIDs.trim.toLong).getOrElse(0L)

        Stats(
            readAt = Instant.fromJava(java.time.Instant.now()),
            cpu = Stats.Cpu(
                totalUsage = Absent,
                systemUsage = Absent,
                onlineCpus = Math.max(1, Runtime.getRuntime.availableProcessors()),
                usagePercent = cpuPerc
            ),
            memory = Stats.Memory(
                usage = memUsage,
                maxUsage = Absent,
                limit = if memLimit > 0 then Present(memLimit) else Absent,
                usagePercent = memPerc
            ),
            network = Map("eth0" -> Stats.Net(
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

    private def mapPodmanStats(dto: PodmanStatsJson)(using Frame): Stats =
        val systemNano = if dto.CPUSystemNano != 0L then dto.CPUSystemNano else dto.SystemNano
        val networkMap = dto.Network.getOrElse(Map.empty).map { case (ifName, netDto) =>
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
        }
        val finalNetwork = if networkMap.nonEmpty then networkMap
        else Map("eth0" -> Stats.Net(0, Absent, Absent, Absent, 0, Absent, Absent, Absent))

        val onlineCpus = if systemNano > 0 && dto.CPUNano > 0 then
            Math.max(1, Runtime.getRuntime.availableProcessors())
        else 0

        Stats(
            readAt = Instant.fromJava(java.time.Instant.now()),
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
        run("top", id.value, psArgs).map { output =>
            val lines = output.split("\n")
            if lines.isEmpty then TopResult(Chunk.empty, Chunk.empty)
            else
                val titles    = Chunk.from(lines.head.split("\\s+"))
                val processes = Chunk.from(lines.tail.map(l => Chunk.from(l.split("\\s+", titles.length))))
                TopResult(titles, processes)
            end if
        }

    def changes(id: Container.Id)(using Frame): Chunk[FilesystemChange] < (Async & Abort[ContainerException]) =
        run("diff", id.value).map { output =>
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
        execOnce(id, command)
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
                            Abort.fail[ContainerException](mapError(stderrStr.trim, Seq("exec", id.value)))
                        // Exit code 126: command exists but cannot be invoked (permissions, not executable)
                        else if rawExit == ExitCode.Failure(126) then
                            Abort.fail[ContainerException](
                                ContainerException.ExecFailed(
                                    id,
                                    Chunk.from(command.args),
                                    ExitCode.Failure(126),
                                    "Command cannot be invoked (permission denied or not executable)"
                                )
                            )
                        // Exit code 127: command not found in the container's PATH
                        else if rawExit == ExitCode.Failure(127) then
                            Abort.fail[ContainerException](
                                ContainerException.ExecFailed(
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
                        ContainerException.General(s"exec failed in ${id.value}", cmdEx)
                    )
                case Result.Panic(ex) =>
                    Abort.fail[ContainerException](
                        ContainerException.General(s"exec panicked in ${id.value}", ex)
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

            // Redirect stderr to stdout so we get all output in one stream
            val execCmd = Command((cmd +: baseArgs.toSeq)*).redirectErrorStream(true)
            Scope.run {
                Abort.runWith[CommandException](execCmd.spawn) {
                    case Result.Success(proc) =>
                        // Stream stdout chunks as they arrive, converting bytes to LogEntry
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
        // Create pipes
        Sync.defer {
            val pipedOut = new java.io.PipedOutputStream()
            val pipedIn  = new java.io.PipedInputStream(pipedOut)
            // Spawn process
            val dockerCmd = Command((cmd +: args)*).stdin(Process.Input.FromStream(pipedIn))
            Abort.runWith[CommandException](dockerCmd.spawn) {
                case Result.Failure(cmdEx) =>
                    Abort.fail[ContainerException](ContainerException.General(errorMsg, cmdEx))
                case Result.Panic(ex) =>
                    Abort.fail[ContainerException](ContainerException.General(errorMsg, ex))
                case Result.Success(proc) =>
                    // Build session
                    Scope.ensure(Sync.defer {
                        pipedOut.close()
                        pipedIn.close()
                    }).andThen {
                        new AttachSession:
                            def write(data: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
                                Sync.defer {
                                    pipedOut.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    pipedOut.flush()
                                }

                            def write(data: Chunk[Byte])(using Frame): Unit < (Async & Abort[ContainerException]) =
                                Sync.defer {
                                    pipedOut.write(data.toArray)
                                    pipedOut.flush()
                                }

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
            }
        }
    end createAttachSession

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
        val args = Chunk("logs") ++
            (if tail != Int.MaxValue then Chunk("--tail", tail.toString) else Chunk.empty) ++
            (if since != Instant.Min then Chunk("--since", since.toString) else Chunk.empty) ++
            (if until != Instant.Max then Chunk("--until", until.toString) else Chunk.empty) ++
            (if timestamps then Chunk("-t") else Chunk.empty) ++
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
                            Abort.fail[ContainerException](mapError((outStr + errStr).trim, args.toSeq))
                        else
                            val outEntries = if stdout then
                                parseLogLines(outStr, LogEntry.Source.Stdout, timestamps)
                            else Chunk.empty[LogEntry]
                            val errEntries = if stderr then
                                parseLogLines(errStr, LogEntry.Source.Stderr, timestamps)
                            else Chunk.empty[LogEntry]
                            outEntries.concat(errEntries)
                        end if
                case Result.Failure(cmdEx) =>
                    Abort.fail[ContainerException](
                        ContainerException.General(s"logs failed for ${id.value}", cmdEx)
                    )
                case Result.Panic(ex) =>
                    Abort.fail[ContainerException](
                        ContainerException.General(s"logs panicked for ${id.value}", ex)
                    )
            }
        }
    end logs

    /** Parse log output lines into LogEntry, handling optional timestamps. */
    private def parseLogLines(raw: String, source: LogEntry.Source, hasTimestamps: Boolean): Chunk[LogEntry] =
        if raw.trim.isEmpty then Chunk.empty
        else
            Chunk.from(raw.split("\n").filter(_.nonEmpty).map { line =>
                if hasTimestamps then
                    // Docker timestamps format: "2024-01-01T00:00:00.000000000Z content..."
                    val spaceIdx = line.indexOf(' ')
                    if spaceIdx > 0 then
                        val tsStr   = line.substring(0, spaceIdx)
                        val content = line.substring(spaceIdx + 1)
                        val ts      = Instant.parse(tsStr).toMaybe
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
                val ts      = Instant.parse(tsStr).toMaybe
                LogEntry(source, content, ts)
            else LogEntry(source, line)
            end if
        else LogEntry(source, line)

    def logStream(
        id: Container.Id,
        stdout: Boolean,
        stderr: Boolean,
        since: Instant,
        tail: Int,
        timestamps: Boolean
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

            Scope.run {
                Abort.runWith[CommandException](logsCmd.spawn) {
                    case Result.Success(proc) =>
                        val byteStream = if source == LogEntry.Source.Stderr then proc.stderr else proc.stdout
                        byteStream.mapChunk { bytes =>
                            val text = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            Chunk.from(text.split("\n").filter(_.nonEmpty).map(line =>
                                parseLogLine(line, source, timestamps)
                            ))
                        }.emit
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"logStream failed for ${id.value}", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"logStream panicked for ${id.value}", ex)
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
        Abort.run[ContainerException] {
            Abort.runWith[CommandException](Command("readlink", "-f", pathStr).textWithExitCode) {
                case Result.Success((output, exitCode)) if exitCode == ExitCode.Success =>
                    val resolved = output.trim
                    if resolved.nonEmpty then resolved else pathStr
                case _ => pathStr
            }
        }.map {
            case Result.Success(resolved) => resolved
            case _                        => pathStr
        }
    end resolveHostPath

    def copyTo(id: Container.Id, source: Path, containerPath: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        resolveHostPath(source).map { resolved =>
            runUnit("cp", resolved, s"${id.value}:${containerPath}")
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
                            runUnit("cp", s"${id.value}:${containerPath}", s"$resolvedParent/$fileName")
                        }
                    case Absent =>
                        runUnit("cp", s"${id.value}:${containerPath}", destStr)
            case Absent =>
                runUnit("cp", s"${id.value}:${containerPath}", destStr)
        end match
    end copyFrom

    def stat(id: Container.Id, containerPath: Path)(using Frame): FileStat < (Async & Abort[ContainerException]) =
        // %N gives quoted name + link target for symlinks, %s=size, %f=hex mode, %Y=mtime epoch
        run("exec", id.value, "stat", "-c", "%N|%s|%f|%Y", containerPath.toString).map { output =>
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
                val baseName = name.split("/").lastOption.getOrElse(name)
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
                        Abort.fail(ContainerException.ParseError(
                            "stat",
                            s"Invalid stat output for container ${id.value}: ${output.trim}"
                        ))
                end match
            else
                FileStat(
                    name = containerPath.toString.split("/").lastOption.getOrElse(containerPath.toString),
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
                            ContainerException.General(s"export failed for ${id.value}", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"export panicked for ${id.value}", ex)
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
            runUnit((args ++ Chunk(id.value)).toSeq*)
        else ()
        end if
    end update

    // --- Network on Container ---

    def connectToNetwork(id: Container.Id, networkId: Network.Id, aliases: Seq[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "connect") ++
            Chunk.from(aliases.flatMap(a => Seq("--alias", a))) ++
            Chunk(networkId.value) ++
            Chunk(id.value)
        runUnit(args.toSeq*)
    end connectToNetwork

    def disconnectFromNetwork(id: Container.Id, networkId: Network.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "disconnect") ++
            (if force then Chunk("-f") else Chunk.empty) ++
            Chunk(networkId.value) ++
            Chunk(id.value)
        runUnit(args.toSeq*)
    end disconnectFromNetwork

    // --- Container Listing ---

    def list(all: Boolean, filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Summary] < (Async & Abort[ContainerException]) =
        val args = Chunk("ps") ++
            (if all then Chunk("--all") else Chunk.empty) ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--no-trunc", "--format", "{{json .}}")
        run(args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[Summary]
            else
                // Parse each line independently — skip lines that fail to decode
                // so one malformed container entry doesn't abort the entire list
                Chunk.from(output.trim.split("\n")).flatMap { line =>
                    val trimmed = line.trim
                    Json[PsJsonDocker].decode(trimmed) match
                        case Result.Success(dto) if dto.ID.nonEmpty =>
                            Chunk(mapDockerPsToSummary(dto))
                        case _ =>
                            Json[PsJsonPodman].decode(trimmed) match
                                case Result.Success(dto) if dto.Id.nonEmpty =>
                                    Chunk(mapPodmanPsToSummary(dto))
                                case _ => Chunk.empty
                    end match
                }
        }
    end list

    /** Parse a single Docker port entry like "0.0.0.0:8080->80/tcp", ":::8080->80/tcp", or "80/tcp". */
    private def parseDockerPortEntry(entry: String): Option[Config.PortBinding] =
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
                Some(Config.PortBinding(
                    containerPort = containerPort,
                    hostPort = hostPort,
                    hostIp = if hostIp.nonEmpty then hostIp else "",
                    protocol = protocol
                ))
            else
                // No colon in host part — shouldn't happen, but handle gracefully
                val hostPort = hostPart.toIntOption.getOrElse(0)
                Some(Config.PortBinding(
                    containerPort = containerPort,
                    hostPort = hostPort,
                    protocol = protocol
                ))
            end if
        else
            // No host mapping: just "80" (containerPort only)
            main.toIntOption.map { containerPort =>
                Config.PortBinding(containerPort = containerPort, protocol = protocol)
            }
        end if
    end parseDockerPortEntry

    private def mapDockerPsToSummary(dto: PsJsonDocker): Summary =
        val names = if dto.Names.nonEmpty then Chunk.from(dto.Names.split(",")) else Chunk.empty[String]
        val labels: Map[String, String] = if dto.Labels.nonEmpty then
            dto.Labels.split(",").iterator.filter(_.contains("=")).map { pair =>
                val kv = pair.split("=", 2)
                kv(0) -> kv(1)
            }.toMap
        else Map.empty
        val createdAt = if dto.CreatedAt.nonEmpty then parseTimestamp(dto.CreatedAt) else Instant.Epoch
        val ports =
            if dto.Ports.nonEmpty then
                Chunk.from(dto.Ports.split(",").iterator.map(_.trim).filter(_.nonEmpty).flatMap(parseDockerPortEntry).toSeq)
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
        val labels     = dto.Labels.getOrElse(Map.empty)
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

    def prune(filters: Map[String, Seq[String]])(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        val args = Chunk("container", "prune", "-f") ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            })
        run(args.toSeq*).map { output =>
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
            val config = new Config(
                image = info.image,
                name = if info.name.nonEmpty then Present(info.name) else Absent
            )
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
        runUnit(args.toSeq*)
    end imagePull

    def imageEnsure(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val ref = image.reference
        Abort.runWith[ContainerException](
            run("image", "inspect", "--format", "{{.Id}}", ref)
        ) {
            case Result.Success(_) => () // Image exists locally, skip pull
            case _ =>
                val args = Chunk("pull") ++
                    platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
                    Chunk(ref)
                runUnit(args.toSeq*)
        }
    end imageEnsure

    def imagePullWithProgress(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Stream[ContainerImage.PullProgress, Async & Abort[ContainerException]] =
        Stream {
            val ref = image.reference
            // Check locally first to avoid hanging on registry rate limits
            Abort.runWith[ContainerException](
                run("image", "inspect", "--format", "{{.Id}}", ref)
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
                                    ContainerException.General(s"pull failed for ${ref}", cmdEx)
                                ).unit
                            case Result.Panic(ex) =>
                                Abort.fail[ContainerException](
                                    ContainerException.General(s"pull panicked for ${ref}", ex)
                                ).unit
                        }
                    }
            }
        }

    def imageList(all: Boolean, filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[ContainerImage.Summary] < (Async & Abort[ContainerException]) =
        val args = Chunk("images") ++
            (if all then Chunk("--all") else Chunk.empty) ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--format", "{{json .}}")
        run(args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[ContainerImage.Summary]
            else
                Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                    val trimmed = line.trim
                    Json[ImageListJsonDocker].decode(trimmed) match
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
            labels = Map.empty
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
            labels = dto.Labels.getOrElse(Map.empty)
        )
    end mapPodmanImageListToSummary

    def imageInspect(image: ContainerImage)(using Frame): ContainerImage.Info < (Async & Abort[ContainerException]) =
        run("image", "inspect", "--format", "{{json .}}", image.reference).map { raw =>
            decodeJson[ImageInspectJson](raw).map { dto =>
                ContainerImage.Info(
                    id = ContainerImage.Id(dto.Id),
                    repoTags = Chunk.from(dto.RepoTags.getOrElse(Seq.empty).map(s =>
                        ContainerImage.parse(s).getOrElse(ContainerImage(s))
                    )),
                    repoDigests = Chunk.from(dto.RepoDigests.getOrElse(Seq.empty).map(s =>
                        ContainerImage.parse(s).getOrElse(ContainerImage(s))
                    )),
                    createdAt = parseInstant(dto.Created).getOrElse(Instant.Epoch),
                    size = dto.Size,
                    labels = dto.Config.Labels.getOrElse(Map.empty),
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
        run(args.toSeq*).map { output =>
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
        runUnit("tag", source.reference, s"$repo:$tag")

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
        // Stream-based context not supported via CLI, use imageBuildFromPath instead
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
                Chunk.from(tags.flatMap(t => Seq("-t", t))) ++
                (if dockerfile != "Dockerfile" then Chunk("-f", dockerfile) else Chunk.empty) ++
                Chunk.from(buildArgs.toSeq.flatMap { case (k, v) => Seq("--build-arg", s"$k=$v") }) ++
                Chunk.from(labels.toSeq.flatMap { case (k, v) => Seq("--label", s"$k=$v") }) ++
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
                        }.emit
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"build failed", cmdEx)
                        ).unit
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](
                            ContainerException.General(s"build panicked", ex)
                        ).unit
                }
            }
        }

    def imagePush(image: ContainerImage, auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        runUnit("push", image.reference)

    def imageSearch(term: String, limit: Int, filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[ContainerImage.SearchResult] < (Async & Abort[ContainerException]) =
        val args = Chunk("search") ++
            (if limit != Int.MaxValue then Chunk("--limit", limit.toString) else Chunk.empty) ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--format", "{{json .}}") ++
            Chunk(term)
        run(args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[ContainerImage.SearchResult]
            else
                Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                    val trimmed = line.trim
                    // Docker: IsOfficial/IsAutomated are strings ("OK"/""), StarCount is a string
                    // Podman: IsOfficial/IsAutomated are booleans, StarCount is an int
                    Json[ImageSearchJsonPodman].decode(trimmed) match
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
        run("history", "--format", "{{json .}}", image.reference).map { output =>
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

    def imagePrune(filters: Map[String, Seq[String]])(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        val args = Chunk("image", "prune", "-f") ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            })
        run(args.toSeq*).map { output =>
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
        run(args.toSeq*).map(_.trim)
    end imageCommit

    // --- Network Operations ---

    def networkCreate(config: Network.Config)(using Frame): Network.Id < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "create") ++
            (if config.driver != Container.NetworkDriver.Bridge then Chunk("--driver", config.driver.cliName) else Chunk.empty) ++
            (if config.internal then Chunk("--internal") else Chunk.empty) ++
            // Note: podman doesn't support --attachable flag; skip it
            (if config.enableIPv6 then Chunk("--ipv6") else Chunk.empty) ++
            config.labels.flatMap { case (k, v) => Chunk("--label", s"$k=$v") } ++
            config.options.flatMap { case (k, v) => Chunk("--opt", s"$k=$v") } ++
            config.ipam.map { ipam =>
                (if ipam.driver != "default" then Chunk("--ipam-driver", ipam.driver) else Chunk.empty) ++
                    ipam.config.flatMap { pool =>
                        pool.subnet.map(s => Chunk("--subnet", s)).getOrElse(Chunk.empty) ++
                            pool.gateway.map(g => Chunk("--gateway", g)).getOrElse(Chunk.empty) ++
                            pool.ipRange.map(r => Chunk("--ip-range", r)).getOrElse(Chunk.empty)
                    }
            }.getOrElse(Chunk.empty) ++
            Chunk(config.name)
        run(args.toSeq*).map { _ =>
            // Always return the network name as the ID.
            // Docker returns a hash but uses names as keys in inspect/connect;
            // Podman already returns the name. Using the name consistently
            // ensures Network.Id works with all subsequent operations.
            Network.Id(config.name)
        }
    end networkCreate

    def networkList(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Network.Info] < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "ls") ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--format", "{{json .}}")
        run(args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[Network.Info]
            else
                Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                    val trimmed = line.trim
                    // Docker: all string fields (booleans as "true"/"false", labels as comma-separated)
                    // Podman: native types (booleans, labels as object)
                    Json[NetworkListJsonPodman].decode(trimmed) match
                        case Result.Success(dto) if dto.name.nonEmpty || dto.id.nonEmpty => mapPodmanNetworkListToInfo(dto)
                        case _ =>
                            decodeJson[NetworkListJsonDocker](trimmed).map(mapDockerNetworkListToInfo)
                    end match
                }
        }
    end networkList

    private def mapDockerNetworkListToInfo(dto: NetworkListJsonDocker)(using Frame): Network.Info =
        val labelsMap: Map[String, String] =
            if dto.Labels.nonEmpty then
                dto.Labels.split(",").iterator.filter(_.contains("=")).map { pair =>
                    val kv = pair.split("=", 2)
                    kv(0).trim -> kv(1).trim
                }.toMap
            else Map.empty

        Network.Info(
            id = Network.Id(dto.ID),
            name = dto.Name,
            driver = Container.NetworkDriver.parse(dto.Driver),
            scope = dto.Scope,
            internal = dto.Internal.equalsIgnoreCase("true"),
            attachable = false,
            enableIPv6 = dto.IPv6.equalsIgnoreCase("true"),
            labels = labelsMap,
            options = Map.empty,
            containers = Map.empty,
            createdAt = if dto.CreatedAt.nonEmpty then parseInstant(dto.CreatedAt).getOrElse(Instant.Epoch) else Instant.Epoch
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
            labels = dto.labels.getOrElse(Map.empty),
            options = Map.empty,
            containers = Map.empty,
            createdAt = if dto.created.nonEmpty then parseInstant(dto.created).getOrElse(Instant.Epoch) else Instant.Epoch
        )
    end mapPodmanNetworkListToInfo

    def networkInspect(id: Network.Id)(using Frame): Network.Info < (Async & Abort[ContainerException]) =
        run("network", "inspect", "--format", "{{json .}}", id.value).map { raw =>
            decodeJson[NetworkInspectJson](raw).map { dto =>
                val name = if dto.Name.nonEmpty then dto.Name else dto.name
                // Use name as ID for consistency with networkCreate (which returns name for podman)
                val nid = if name.nonEmpty then name
                else if dto.Id.nonEmpty then dto.Id
                else if dto.id.nonEmpty then dto.id
                else id.value

                val containersMap = dto.Containers.orElse(dto.containers).getOrElse(Map.empty)
                val containers = containersMap.map { case (cid, c) =>
                    Container.Id(cid) -> Info.NetworkEndpoint(
                        networkId = Network.Id(nid),
                        endpointId = c.EndpointID,
                        gateway = "",
                        ipAddress = c.IPv4Address.takeWhile(_ != '/'),
                        ipPrefixLen = 0,
                        macAddress = c.MacAddress
                    )
                }

                val createdStr = if dto.Created.nonEmpty then dto.Created else dto.created

                Network.Info(
                    id = Network.Id(nid),
                    name = name,
                    driver = Container.NetworkDriver.parse(if dto.Driver.nonEmpty then dto.Driver else dto.driver),
                    scope = if dto.Scope.nonEmpty then dto.Scope else dto.scope,
                    internal = dto.Internal || dto.internal,
                    attachable = dto.Attachable || dto.attachable,
                    enableIPv6 = dto.EnableIPv6 || dto.ipv6_enabled,
                    labels = dto.Labels.orElse(dto.labels).getOrElse(Map.empty),
                    options = dto.Options.orElse(dto.options).getOrElse(Map.empty),
                    containers = containers,
                    createdAt = if createdStr.nonEmpty then parseInstant(createdStr).getOrElse(Instant.Epoch) else Instant.Epoch
                )
            }
        }

    def networkRemove(id: Network.Id)(using Frame): Unit < (Async & Abort[ContainerException]) =
        runUnit("network", "rm", id.value)

    def networkConnect(network: Network.Id, container: Container.Id, aliases: Seq[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "connect") ++
            Chunk.from(aliases.flatMap(a => Seq("--alias", a))) ++
            Chunk(network.value) ++
            Chunk(container.value)
        runUnit(args.toSeq*)
    end networkConnect

    def networkDisconnect(network: Network.Id, container: Container.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "disconnect") ++
            (if force then Chunk("-f") else Chunk.empty) ++
            Chunk(network.value) ++
            Chunk(container.value)
        runUnit(args.toSeq*)
    end networkDisconnect

    def networkPrune(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Network.Id] < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "prune", "-f") ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            })
        run(args.toSeq*).map { output =>
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
            config.driverOpts.flatMap { case (k, v) => Chunk("--opt", s"$k=$v") } ++
            config.labels.flatMap { case (k, v) => Chunk("--label", s"$k=$v") } ++
            config.name.map(n => Chunk(n.value)).getOrElse(Chunk.empty)
        run(args.toSeq*).map(_.trim).map { name =>
            // After create, inspect to get full info
            volumeInspect(Volume.Id(name))
        }
    end volumeCreate

    def volumeList(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Volume.Info] < (Async & Abort[ContainerException]) =
        val args = Chunk("volume", "ls") ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            }) ++
            Chunk("--format", "{{json .}}")
        run(args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[Volume.Info]
            else
                Chunk.from(output.trim.split("\n")).flatMap { line =>
                    val trimmed = line.trim
                    Json[VolumeListJsonDocker].decode(trimmed) match
                        case Result.Success(dto) =>
                            val labels =
                                if dto.Labels.nonEmpty then
                                    dto.Labels.split(",").filter(_.contains("=")).map { pair =>
                                        val kv = pair.split("=", 2)
                                        kv(0) -> (if kv.length > 1 then kv(1) else "")
                                    }.toMap
                                else Map.empty[String, String]
                            Chunk(Volume.Info(
                                name = Volume.Id(dto.Name),
                                driver = dto.Driver,
                                mountpoint = dto.Mountpoint,
                                labels = labels,
                                options = Map.empty,
                                createdAt = Instant.Epoch,
                                scope = dto.Scope
                            ))
                        case _ =>
                            Json[VolumeListJsonPodman].decode(trimmed) match
                                case Result.Success(dto) =>
                                    Chunk(Volume.Info(
                                        name = Volume.Id(dto.Name),
                                        driver = dto.Driver,
                                        mountpoint = dto.Mountpoint,
                                        labels = dto.Labels.getOrElse(Map.empty),
                                        options = Map.empty,
                                        createdAt = Instant.Epoch,
                                        scope = dto.Scope
                                    ))
                                case _ => Chunk.empty
                    end match
                }
        }
    end volumeList

    def volumeInspect(id: Volume.Id)(using Frame): Volume.Info < (Async & Abort[ContainerException]) =
        run("volume", "inspect", "--format", "{{json .}}", id.value).map { raw =>
            decodeJson[VolumeInspectJson](raw).map { dto =>
                Volume.Info(
                    name = if dto.Name.nonEmpty then Volume.Id(dto.Name) else id,
                    driver = if dto.Driver.nonEmpty then dto.Driver else "local",
                    mountpoint = dto.Mountpoint,
                    labels = dto.Labels.getOrElse(Map.empty),
                    options = dto.Options.getOrElse(Map.empty),
                    createdAt = if dto.CreatedAt.nonEmpty then parseInstant(dto.CreatedAt).getOrElse(Instant.Epoch) else Instant.Epoch,
                    scope = if dto.Scope.nonEmpty then dto.Scope else "local"
                )
            }
        }

    def volumeRemove(id: Volume.Id, force: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        // Check if any containers (including stopped) reference this volume
        run("ps", "-a", "--filter", s"volume=${id.value}", "--format", "{{.ID}}").map { output =>
            val ids = output.trim
            if ids.nonEmpty then
                Abort.fail[ContainerException](
                    ContainerException.VolumeInUse(id, ids)
                )
            else
                val args = Chunk("volume", "rm") ++
                    (if force then Chunk("-f") else Chunk.empty) ++
                    Chunk(id.value)
                runUnit(args.toSeq*)
            end if
        }
    end volumeRemove

    def volumePrune(filters: Map[String, Seq[String]])(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        val args = Chunk("volume", "prune", "-f") ++
            Chunk.from(filters.toSeq.flatMap { case (k, vs) =>
                vs.flatMap(v => Seq("--filter", s"$k=$v"))
            })
        run(args.toSeq*).map { output =>
            val lines   = output.split("\n")
            val deleted = Chunk.from(lines.filter(_.trim.nonEmpty).filterNot(_.toLowerCase.contains("reclaimed")))
            val spaceReclaimed = lines.find(_.contains("reclaimed")).flatMap { line =>
                val pattern = """(\d+(?:\.\d+)?)\s*(B|kB|MB|GB|TB)""".r
                pattern.findFirstMatchIn(line).map(m => parseSizeString(m.group(0)))
            }.getOrElse(0L)
            PruneResult(deleted, spaceReclaimed)
        }
    end volumePrune

    // --- RegistryAuth ---

    def registryAuthFromConfig(using Frame): ContainerImage.RegistryAuth < (Async & Abort[ContainerException]) =
        // Try docker config first, then podman
        val configPaths = Chunk.from(Seq(
            sys.props.get("user.home").map(_ + "/.docker/config.json"),
            sys.env.get("XDG_RUNTIME_DIR").map(_ + "/containers/auth.json"),
            sys.env.get("DOCKER_CONFIG").map(_ + "/config.json")
        ).flatten)

        // Find first existing config path using kyo Path.exists
        def findExisting(paths: Chunk[String]): Maybe[String] < Sync =
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
                        Json[AuthConfigJson].decode(content) match
                            case Result.Success(dto) =>
                                ContainerImage.RegistryAuth(dto.auths.getOrElse(Map.empty).map { case (k, v) =>
                                    ContainerImage.Registry(k) -> v
                                })
                            case Result.Failure(_) => ContainerImage.RegistryAuth(Map.empty)
                        end match
                    case Result.Failure(_) =>
                        ContainerImage.RegistryAuth(Map.empty)
                }
            case Absent =>
                ContainerImage.RegistryAuth(Map.empty)
        }
    end registryAuthFromConfig

    // --- Backend Detection ---

    def detect()(using Frame): Unit < (Async & Abort[ContainerException]) =
        // Already constructed with a specific cmd, so just verify it works
        Abort.runWith[CommandException](Command(cmd, "version").textWithExitCode) {
            case Result.Success((_, exitCode)) =>
                if exitCode != ExitCode.Success then
                    Abort.fail(ContainerException.BackendUnavailable(cmd, s"$cmd version returned non-zero exit code"))
                else ()
            case Result.Failure(cmdEx) =>
                Abort.fail(ContainerException.BackendUnavailable(cmd, s"$cmd not available: $cmdEx"))
            case Result.Panic(ex) =>
                Abort.fail(ContainerException.BackendUnavailable(cmd, s"$cmd not available: $ex"))
        }

    // --- Private Helpers ---

    /** Run a container CLI command, returning stdout on success or mapping errors via [[mapError]].
      *
      * Uses `redirectErrorStream(true)` so both stdout and stderr are captured together. On non-zero exit, the combined output is passed to
      * [[mapError]] for classification. The exit code itself is not directly used for classification here — that happens in [[execOnce]]
      * which has access to separate stdout/stderr streams and exit codes.
      */
    private def run(args: String*)(using Frame): String < (Async & Abort[ContainerException]) =
        Abort.runWith[CommandException](Command((cmd +: args)*).redirectErrorStream(true).textWithExitCode) {
            case Result.Success((stdout, exitCode)) =>
                if exitCode == ExitCode.Success then stdout.trim
                else Abort.fail(mapError(stdout.trim, args))
            case Result.Failure(cmdEx) =>
                Abort.fail(ContainerException.General(s"Command failed: ${(cmd +: args).mkString(" ")}", cmdEx))
            case Result.Panic(ex) =>
                Abort.fail(ContainerException.General(s"Command panicked: ${(cmd +: args).mkString(" ")}", ex))
        }

    private def runUnit(args: String*)(using Frame): Unit < (Async & Abort[ContainerException]) =
        run(args*).unit

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
    private def mapError(output: String, args: Seq[String])(using Frame): ContainerException =
        // Normalize
        val lower  = output.toLowerCase
        val target = args.lastOption.getOrElse("unknown")

        def matchesAny(patterns: Chunk[String]): Boolean =
            patterns.exists(lower.contains)

        // Match patterns
        if matchesAny(ErrorPatterns.NoSuchContainer) then
            ContainerException.NotFound(Container.Id(target))
        else if matchesAny(ErrorPatterns.ImageNotFound) then
            ContainerException.ImageNotFound(ContainerImage.parse(target).getOrElse(ContainerImage(target)))
        else if matchesAny(ErrorPatterns.Conflict) then
            ContainerException.AlreadyExists(target)
        else if matchesAny(ErrorPatterns.AlreadyRunning) then
            ContainerException.AlreadyRunning(Container.Id(target))
        else if matchesAny(ErrorPatterns.AlreadyStopped) then
            ContainerException.AlreadyStopped(Container.Id(target))
        else if lower.contains("network") && lower.contains("not found") then
            ContainerException.NetworkNotFound(Network.Id(target))
        else if matchesAny(ErrorPatterns.VolumeNotFound) then
            ContainerException.VolumeNotFound(Volume.Id(target))
        else if matchesAny(ErrorPatterns.BackendUnavailable) then
            ContainerException.BackendUnavailable(cmd, s"Backend unavailable: $output")
        else if matchesAny(ErrorPatterns.PortConflict) then
            // Try to extract port number from output
            val portPattern = """(\d+)""".r
            val port        = portPattern.findFirstIn(output.split("port").lastOption.getOrElse("")).flatMap(_.toIntOption).getOrElse(0)
            ContainerException.PortConflict(port, output)
        else if matchesAny(ErrorPatterns.AuthFailed) then
            ContainerException.AuthenticationError(target, output)
        else if matchesAny(ErrorPatterns.VolumeInUse) then
            ContainerException.VolumeInUse(Container.Volume.Id(target), output)
        else
            // Build exception
            ContainerException.General(s"${cmd} ${args.mkString(" ")} failed", output)
        end if
    end mapError

    /** Named constants for error message patterns used in [[mapError]].
      *
      * Each pattern set covers both Docker and Podman error messages. Docker uses "Error response from daemon: ..." on stderr with exit
      * code 1. Podman uses direct error messages with exit code 125 for infrastructure errors.
      */
    private object ErrorPatterns:
        /** Container not found — Docker: "No such container", Podman: "no such container", "no such object" */
        val NoSuchContainer: Chunk[String] =
            Chunk("no such container", "no such object", "no container with name or id")

        /** Image not found — Docker: "manifest unknown", Podman: "image not found" */
        val ImageNotFound: Chunk[String] =
            Chunk("no such image", "image not found", "manifest unknown", "requested access to the resource is denied")

        /** Name/ID conflict — Docker & Podman both use "conflict" or "already in use" */
        val Conflict: Chunk[String] =
            Chunk("conflict", "already in use", "name is already in use")

        /** Container already running — Docker: "is already running", Podman: "container already started" */
        val AlreadyRunning: Chunk[String] =
            Chunk("is already running", "container already started")

        /** Container already stopped — Docker: "is not running", Podman: "already stopped" */
        val AlreadyStopped: Chunk[String] =
            Chunk("is not running", "already stopped", "is already stopped", "container already stopped")

        /** Volume not found — Docker & Podman */
        val VolumeNotFound: Chunk[String] =
            Chunk("no such volume", "volume not found")

        /** Backend unavailable — Docker daemon not running or socket not reachable */
        val BackendUnavailable: Chunk[String] =
            Chunk("cannot connect", "connection refused", "is the docker daemon running")

        /** Port conflict — address already bound */
        val PortConflict: Chunk[String] =
            Chunk("port is already allocated", "address already in use", "bind: address already in use")

        /** Authentication failures — registry auth required */
        val AuthFailed: Chunk[String] =
            Chunk("unauthorized", "authentication required", "insufficient_scope", "denied")

        /** Volume in use — cannot remove a volume that is referenced by containers */
        val VolumeInUse: Chunk[String] =
            Chunk("volume is in use", "volume being used")
    end ErrorPatterns

    /** Decode JSON using a Json[A] instance, stripping outer array brackets if present. */
    private def decodeJson[A](raw: String)(using json: Json[A], frame: Frame): A < Abort[ContainerException] =
        val trimmed = raw.trim
        val obj = if trimmed.startsWith("[") then
            trimmed.stripPrefix("[").stripSuffix("]").trim
        else trimmed
        json.decode(obj) match
            case Result.Success(a)   => a
            case Result.Failure(err) => Abort.fail(ContainerException.ParseError("JSON decode", err))
    end decodeJson

    /** Parse an ISO-8601 timestamp or similar format. */
    private def parseInstant(s: String): Maybe[Instant] =
        if s == null || s.isEmpty || s == "0001-01-01T00:00:00Z" then Absent
        else
            Instant.parse(s).toMaybe

    /** Parse a timestamp string trying multiple formats. */
    private def parseTimestamp(s: String): Instant =
        if s.isEmpty then Instant.Epoch
        else
            parseInstant(s).getOrElse {
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

    /** Auto-detect an available container runtime. Tries podman first, then docker. */
    def detect()(using Frame): ShellBackend < (Async & Abort[ContainerException]) =
        Abort.runWith[CommandException](Command("podman", "version").textWithExitCode) {
            case Result.Success((_, exitCode)) if exitCode == ExitCode.Success =>
                new ShellBackend("podman")
            case _ =>
                Abort.runWith[CommandException](Command("docker", "version").textWithExitCode) {
                    case Result.Success((_, exitCode)) if exitCode == ExitCode.Success =>
                        new ShellBackend("docker")
                    case _ =>
                        Abort.fail(ContainerException.BackendUnavailable(
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
    ) derives Json

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
    ) derives Json

    private[internal] case class InspectHealthJson(
        Status: String = ""
    ) derives Json

    private[internal] case class InspectConfigJson(
        Image: String = "",
        Hostname: String = "",
        Cmd: Option[Seq[String]] = None,
        Env: Option[Seq[String]] = None,
        Labels: Option[Map[String, String]] = None,
        Tty: Boolean = false
    ) derives Json

    private[internal] case class InspectNetworkSettingsJson(
        IPAddress: String = "",
        Gateway: String = "",
        MacAddress: String = "",
        Ports: Option[Map[String, Seq[PortMappingJson]]] = None,
        Networks: Option[Map[String, NetworkEndpointEntryJson]] = None
    ) derives Json

    private[internal] case class PortMappingJson(
        HostIp: String = "",
        HostPort: String = ""
    ) derives Json

    /** Podman `ps --format '{{json .}}'` serializes Ports as a flat array of objects. */
    private[internal] case class PodmanPsPortJson(
        host_ip: String = "",
        host_port: Int = 0,
        container_port: Int = 0,
        protocol: String = "tcp",
        range: Int = 1
    ) derives Json

    private[internal] case class NetworkEndpointEntryJson(
        NetworkID: String = "",
        EndpointID: String = "",
        Gateway: String = "",
        IPAddress: String = "",
        IPPrefixLen: Int = 0,
        MacAddress: String = ""
    ) derives Json

    private[internal] case class InspectMountJson(
        Type: String = "",
        Name: String = "",
        Source: String = "",
        Destination: String = "",
        RW: Boolean = true
    ) derives Json

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
    ) derives Json

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
    ) derives Json

    private[internal] case class NetStatsJson(
        RxBytes: Long = 0,
        RxPackets: Long = 0,
        RxErrors: Long = 0,
        RxDropped: Long = 0,
        TxBytes: Long = 0,
        TxPackets: Long = 0,
        TxErrors: Long = 0,
        TxDropped: Long = 0
    ) derives Json

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
    ) derives Json

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
    ) derives Json

    // --- JSON DTOs for image list ---
    // Docker: all strings, ID uppercase, CreatedAt as string, Size as string
    private[internal] case class ImageListJsonDocker(
        ID: String = "",
        Repository: String = "",
        Tag: String = "",
        CreatedAt: String = "",
        Size: String = ""
    ) derives Json

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
    ) derives Json

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
    ) derives Json

    private[internal] case class ImageInspectConfigJson(
        Labels: Option[Map[String, String]] = None
    ) derives Json

    // --- JSON DTOs for image search ---
    // Docker: IsOfficial/IsAutomated are strings ("OK" or ""), StarCount is a string
    private[internal] case class ImageSearchJsonDocker(
        Name: String = "",
        Description: String = "",
        StarCount: String = "0",
        IsOfficial: String = "",
        IsAutomated: String = ""
    ) derives Json

    // Podman: IsOfficial/IsAutomated are booleans, StarCount is an int
    private[internal] case class ImageSearchJsonPodman(
        Name: String = "",
        Description: String = "",
        StarCount: Int = 0,
        IsOfficial: Boolean = false,
        IsAutomated: Boolean = false
    ) derives Json

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
    ) derives Json

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
    ) derives Json

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
    ) derives Json

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
    ) derives Json

    private[internal] case class NetworkContainerJson(
        EndpointID: String = "",
        IPv4Address: String = "",
        MacAddress: String = ""
    ) derives Json

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
    ) derives Json

    private[internal] case class VolumeListJsonPodman(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Scope: String = "",
        Labels: Option[Map[String, String]] = None
    ) derives Json

    // --- JSON DTOs for volume inspect ---

    private[internal] case class VolumeInspectJson(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Labels: Option[Map[String, String]] = None,
        Options: Option[Map[String, String]] = None,
        CreatedAt: String = "",
        Scope: String = ""
    ) derives Json

    // --- JSON DTOs for registry auth config ---

    private[internal] case class AuthConfigJson(
        auths: Option[Map[String, String]] = None
    ) derives Json

end ShellBackend
