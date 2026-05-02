package kyo.internal

import kyo.*
import kyo.internal.ContainerBackend.exitCodeForState
import kyo.internal.ContainerBackend.fromEpochSecond
import kyo.internal.ContainerBackend.parseInstant
import kyo.internal.ContainerBackend.parseInstantOrEpoch
import kyo.internal.ContainerBackend.parseState

/** Container backend that delegates to Docker or Podman CLI commands.
  *
  * Each operation builds a [[kyo.Command]], executes it via the shell, and parses stdout/stderr. Docker and Podman produce different JSON
  * formats for inspect, stats, and list operations — separate DTO case classes handle the divergence with a decode-fallback pattern.
  *
  * IMPORTANT: Requires `docker` or `podman` CLI on PATH. Detection tries Podman first, then Docker.
  *
  * @param cmd
  *   The CLI binary to invoke (`"docker"` or `"podman"`).
  * @param meter
  *   Concurrency limiter applied to backend operations; defaults to [[kyo.Meter.Noop]] (no limit).
  * @param streamBufferSize
  *   Buffer size for [[execStream]] / [[logStream]] inter-fiber channels.
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
        val restartStr = restartPolicyStr(config.restartPolicy)

        // Pre-resolve bind mount paths (effectful due to symlink resolution).
        // The pure helper segments below receive the already-resolved paths.
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
        }.map { resolvedMounts =>
            // Build base args from pure helpers (all resolve-free)
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
                portArgs(config.ports) ++
                // Mounts (pre-resolved)
                resolvedMounts.flatten ++
                // Network mode + alias flags
                networkArgs(config.networkMode) ++
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
                // Security: --privileged, --cap-add, --cap-drop, --read-only
                securityArgs(config) ++
                // Behavior: -i, -t, --rm, --restart, --stop-signal
                behaviorArgs(config, restartStr) ++
                // Environment variables from Config.env merged with Command.env (command overrides on conflict)
                (config.env.toMap ++ config.command.map(_.env).getOrElse(Map.empty)).flatMap { case (k, v) => Chunk("-e", s"$k=$v") } ++
                // Working directory from Command
                config.command.flatMap(_.workDir).map(w => Chunk("-w", w.toString)).getOrElse(Chunk.empty) ++
                // Image
                Chunk(config.image.reference) ++
                // Command args (only when command is Present)
                config.command.map(cmd => Chunk.from(cmd.args)).getOrElse(Chunk.empty)

            // Run. Use Image context so mapError classifies image-not-found as ImageNotFound.
            // Take the last non-empty line: docker auto-pulls (printing progress to stdout) and
            // podman emits cgroupv2 fallback warnings before the ID; the actual container ID is
            // always the final line.
            run(ResourceContext.Image(config.image.reference), args.toSeq*).map(output => Container.Id(lastLine(output)))
        }
    end create

    /** Builds `-p` flag pairs for each port binding. Pure — no effects. */
    private def portArgs(ports: Chunk[Config.PortBinding]): Chunk[String] =
        ports.flatMap { pb =>
            val proto = pb.protocol.cliName
            val binding = (pb.hostIp, pb.hostPort) match
                case (ip, hp) if ip.nonEmpty && hp != 0 => s"$ip:$hp:${pb.containerPort}/$proto"
                case ("", hp) if hp != 0                => s"$hp:${pb.containerPort}/$proto"
                case (ip, _) if ip.nonEmpty             => s"$ip::${pb.containerPort}/$proto"
                case _                                  => s"${pb.containerPort}/$proto"
            Chunk("-p", binding)
        }

    /** Builds `--network` and optional `--network-alias` flags. Pure — no effects. */
    private def networkArgs(mode: Config.NetworkMode): Chunk[String] =
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
    end networkArgs

    /** Builds security-related flags: `--privileged`, `--cap-add`, `--cap-drop`, `--read-only`. Pure — no effects. */
    private def securityArgs(config: Config): Chunk[String] =
        (if config.privileged then Chunk("--privileged") else Chunk.empty) ++
            config.addCapabilities.flatMap(c => Chunk("--cap-add", c.cliName)) ++
            config.dropCapabilities.flatMap(c => Chunk("--cap-drop", c.cliName)) ++
            (if config.readOnlyFilesystem then Chunk("--read-only") else Chunk.empty)

    /** Converts a [[Config.RestartPolicy]] to its CLI string representation (`"no"`, `"always"`, `"unless-stopped"`, or `"on-failure:N"`).
      * Used in both `create` and `update` to avoid duplicating the policy-to-string mapping.
      */
    private def restartPolicyStr(rp: Config.RestartPolicy): String = rp match
        case Config.RestartPolicy.OnFailure(retries) => s"${rp.cliName}:$retries"
        case _                                       => rp.cliName

    /** Builds behavior-related flags: `-i`, `-t`, `--rm`, `--restart`, `--stop-signal`. Pure — no effects. */
    private def behaviorArgs(config: Config, restartStr: String): Chunk[String] =
        (if config.interactive then Chunk("-i") else Chunk.empty) ++
            (if config.allocateTty then Chunk("-t") else Chunk.empty) ++
            (if config.autoRemove then Chunk("--rm") else Chunk.empty) ++
            Chunk("--restart", restartStr) ++
            config.stopSignal.map(s => Chunk("--stop-signal", s.name)).getOrElse(Chunk.empty)

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
            case Result.Failure(_) =>
                // Both wait and inspect failed — container is gone
                ExitCode.Success
            case Result.Panic(t) =>
                Log.warn(s"unexpected error in inspectExitCode for ${id.value}: $t").andThen(ExitCode.Success)
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
        val imageRef = ContainerImage(imageName)

        // Parse platform
        val platform = Container.Platform.parse(dto.Platform).getOrElse(Container.Platform("", ""))

        // Parse state
        Info(
            id = Container.Id(dto.Id),
            name = dto.Name.stripPrefix("/"),
            image = imageRef,
            imageId = ContainerImage.Id(dto.Image),
            state = parseState(dto.State.Status),
            exitCode = exitCodeForState(parseState(dto.State.Status), dto.State.ExitCode),
            pid = dto.State.Pid,
            startedAt = parseInstant(Option(dto.State.StartedAt).filter(_.nonEmpty)),
            finishedAt = parseInstant(Option(dto.State.FinishedAt).filter(_.nonEmpty)),
            healthStatus = healthStatus,
            ports = portsChunk,
            mounts = mounts,
            labels = Dict.from(dto.Config.Labels.getOrElse(Map.empty)),
            env = envMap,
            command = dto.Config.Cmd.getOrElse(Seq.empty).mkString(" "),
            createdAt = parseInstantOrEpoch(dto.Created),
            restartCount = dto.RestartCount,
            driver = dto.Driver,
            platform = platform,
            networkSettings = buildNetworkSettings(dto.NetworkSettings, networks)
        )
    end mapInspectToInfo

    /** Build the Info.NetworkSettings from the inspect DTO, performing the Docker/Podman fallback: top-level
      * NetworkSettings.{IPAddress,Gateway,MacAddress} are populated for Docker but empty for rootless Podman, which only fills per-network
      * endpoint entries. `firstNetEntry` is computed once and referenced three times.
      */
    private def buildNetworkSettings(
        settings: InspectNetworkSettingsJson,
        networks: Dict[Network.Id, Info.NetworkEndpoint]
    ): Info.NetworkSettings =
        // Podman's container inspect leaves the top-level NetworkSettings.{IPAddress,Gateway,MacAddress}
        // empty and only populates per-network entries under Networks. Fall back to the first non-empty
        // entry so the top-level Maybe fields are populated for both daemons.
        val firstNetEntry: Maybe[NetworkEndpointEntryJson] =
            Maybe.fromOption(settings.Networks.flatMap(_.values.find(ep =>
                ep.IPAddress.nonEmpty || ep.Gateway.nonEmpty || ep.MacAddress.nonEmpty
            )))
        Info.NetworkSettings(
            ipAddress =
                if settings.IPAddress.nonEmpty then Present(settings.IPAddress)
                else firstNetEntry.flatMap(ep => if ep.IPAddress.nonEmpty then Present(ep.IPAddress) else Absent),
            gateway =
                if settings.Gateway.nonEmpty then Present(settings.Gateway)
                else firstNetEntry.flatMap(ep => if ep.Gateway.nonEmpty then Present(ep.Gateway) else Absent),
            macAddress =
                if settings.MacAddress.nonEmpty then Present(settings.MacAddress)
                else firstNetEntry.flatMap(ep => if ep.MacAddress.nonEmpty then Present(ep.MacAddress) else Absent),
            networks = networks
        )
    end buildNetworkSettings

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
                                case Result.Panic(t)                             =>
                                    // Unexpected panic decoding Docker stats; fall through to Podman
                                    Log.warn(s"unexpected panic decoding Docker stats DTO: $t").andThen(
                                        Json.decode[PodmanStatsJson](trimmed) match
                                            case Result.Success(dto) => mapPodmanStats(dto, now, cpuCount)
                                            case Result.Failure(err) =>
                                                Abort.fail(ContainerDecodeException("Parse error in stats JSON", err))
                                            case Result.Panic(t2) => Abort.panic(t2)
                                    )
                                case _ =>
                                    // Docker guard failed or Failure — fall through to Podman
                                    Json.decode[PodmanStatsJson](trimmed) match
                                        case Result.Success(dto) => mapPodmanStats(dto, now, cpuCount)
                                        case Result.Failure(err) =>
                                            Abort.fail(ContainerDecodeException("Parse error in stats JSON", err))
                                        case Result.Panic(t) => Abort.panic(t)
                                    end match
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

    def top(id: Container.Id, psArgs: String)(using Frame): TopResult < (Async & Abort[ContainerException]) =
        run(ResourceContext.Container(id), "top", id.value, psArgs).map(ShellBackend.parseTopOutput)

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
        }(meter.run(retryOnTransientUnavailable(execOnce(id, command))))
    end exec

    /** Retry on transient `BackendUnavailable` (the SSH-bridge multiplex limit on macOS podman machines is the common cause when many shell
      * invocations land at once). The backend itself is healthy — a short backoff lets the SSH server free a slot and the call succeeds.
      */
    private def retryOnTransientUnavailable[A](v: => A < (Async & Abort[ContainerException]))(
        using Frame
    ): A < (Async & Abort[ContainerException]) =
        val schedule = Schedule.exponentialBackoff(initial = 50.millis, factor = 2, maxBackoff = 500.millis).take(3)
        def attempt(remaining: Schedule): A < (Async & Abort[ContainerException]) =
            Abort.runWith[ContainerException](v) {
                case Result.Success(value) => value
                case Result.Failure(e: ContainerBackendUnavailableException) =>
                    Clock.now.map { now =>
                        remaining.next(now) match
                            case Present((delay, next)) => Async.sleep(delay).andThen(attempt(next))
                            case Absent                 => Abort.fail[ContainerException](e)
                    }
                case Result.Failure(other) => Abort.fail[ContainerException](other)
                case Result.Panic(t)       => Abort.panic[ContainerException](t)
            }
        attempt(schedule)
    end retryOnTransientUnavailable

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
            envFlags(command.env) ++
            workDirFlags(command.workDir) ++
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
                        if rawExit == ExitCode.Failure(125) then
                            val msg = if stderrStr.nonEmpty then stderrStr.trim else "podman exec failed with exit code 125"
                            Abort.fail[ContainerException](mapError(msg, ResourceContext.Container(id), Seq("exec", id.value)))
                        else if isDockerDaemonError(stderrStr) && stderrStr.nonEmpty then
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
                envFlags(command.env) ++
                workDirFlags(command.workDir) ++
                Chunk(id.value) ++
                Chunk.from(command.args)

            // Keep stdout and stderr separate so each LogEntry carries its true source tag,
            // matching the HTTP backend's multiplexed-stream behavior. Drain both subprocess
            // pipes into a shared channel from a forked fiber; the main stream consumes the
            // channel and emits entries in arrival order.
            val execCmd = Command((cmd +: baseArgs.toSeq)*)
            Scope.run {
                Abort.runWith[CommandException](execCmd.spawn) {
                    case Result.Success(proc) =>
                        Channel.initUnscoped[LogEntry](streamBufferSize).map { channel =>
                            def drain(
                                byteStream: Stream[Byte, Sync & Scope],
                                source: LogEntry.Source
                            ): Unit < (Async & Abort[Closed]) =
                                Scope.run(
                                    byteStream
                                        .mapChunkPure { bytes => Seq(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)) }
                                        .into(LineAssembler.pipe)
                                        .foreachChunk { lines =>
                                            Kyo.foreachDiscard(lines.toSeq.filter(_.trim.nonEmpty)) { line =>
                                                channel.put(LogEntry(source, line))
                                            }
                                        }
                                )
                            val drainBoth: Unit < (Async & Abort[Closed]) =
                                Async.zip(
                                    drain(proc.stdout, LogEntry.Source.Stdout),
                                    drain(proc.stderr, LogEntry.Source.Stderr)
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
            envFlags(command.env) ++
            workDirFlags(command.workDir) ++
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

    /** @see [[ShellBackend.parseLogLines]] */
    private def parseLogLines(raw: String, source: LogEntry.Source, hasTimestamps: Boolean): Chunk[LogEntry] =
        ShellBackend.parseLogLines(raw, source, hasTimestamps)

    private def parseLogLine(line: String, source: LogEntry.Source, hasTimestamps: Boolean): LogEntry =
        ShellBackend.parseLogLine(line, source, hasTimestamps)

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

            // Per-stream state: lines spanning chunk boundaries are re-assembled by `LineAssembler.pipe`.
            // No flush on termination — matches prior behavior of dropping trailing partial lines.
            Scope.run {
                Abort.runWith[CommandException](logsCmd.spawn) {
                    case Result.Success(proc) =>
                        val byteStream = if source == LogEntry.Source.Stderr then proc.stderr else proc.stdout
                        byteStream
                            .mapChunkPure { bytes => Seq(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)) }
                            .into(LineAssembler.pipe)
                            .mapChunkPure { lines =>
                                lines.collect { case line if line.nonEmpty => parseLogLine(line, source, timestamps) }
                            }
                            .emit
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

    /** @see [[HttpContainerBackend.resolveHostPath]] for the rationale (the two backends use the same approach). */
    private def resolveHostPath(path: Path)(using Frame): String < Async =
        val pathStr = path.toString
        ContainerBackend.runCliQuery(
            Command("readlink", "-f", pathStr),
            trimmed =>
                if trimmed.nonEmpty then Present(trimmed) else Absent
        ).map(_.getOrElse(pathStr))
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
                // Result.catching[NumberFormatException] can technically surface a Panic for non-NFE throws;
                // the three arms are logically exhaustive — @nowarn suppresses a false-positive compiler warning.
                (Result.catching[NumberFormatException] {
                    FileStat(
                        name = baseName,
                        size = parts(1).toLong,
                        mode = java.lang.Integer.parseInt(parts(2), 16),
                        modifiedAt = Instant.fromJava(java.time.Instant.ofEpochSecond(parts(3).toLong)),
                        linkTarget = linkTarget
                    )
                } match
                    case Result.Success(fs) => fs
                    case Result.Failure(_) =>
                        Abort.fail(ContainerDecodeException(
                            "Parse error in stat",
                            s"Invalid stat output for container ${id.value}: ${output.trim}"
                        ))
                    case Result.Panic(t) =>
                        Log.warn(s"unexpected panic parsing stat output for container ${id.value}: $t").andThen(
                            Abort.fail(ContainerDecodeException(
                                "Parse error in stat",
                                s"Invalid stat output for container ${id.value}: ${output.trim}"
                            ))
                        )
                ): @scala.annotation.nowarn("msg=match may not be exhaustive")
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
                Chunk("--restart", restartPolicyStr(rp))
            }.getOrElse(Chunk.empty)
        // Docker requires at least one flag; skip the command for no-op updates
        if args.length > 1 then
            runUnit(ResourceContext.Container(id), (args ++ Chunk(id.value)).toSeq*)
        else ()
        end if
    end update

    // --- Container Listing ---

    def list(all: Boolean, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Summary] < (Async & Abort[ContainerException]) =
        val args = Chunk("ps") ++
            (if all then Chunk("--all") else Chunk.empty) ++
            filterFlags(filters) ++
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
                        case Result.Failure(_) | Result.Success(_) =>
                            // Docker guard failed or decode error — fall through to Podman
                            Json.decode[PsJsonPodman](trimmed) match
                                case Result.Success(dto) if dto.Id.nonEmpty =>
                                    Chunk(mapPodmanPsToSummary(dto))
                                case Result.Failure(_) | Result.Success(_) =>
                                    Chunk.empty
                                case Result.Panic(_) =>
                                    Chunk.empty
                            end match
                        case Result.Panic(_) =>
                            Chunk.empty
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
            image = ContainerImage(dto.Image),
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
            image = ContainerImage(dto.Image),
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
        val args = Chunk("container", "prune", "-f") ++ filterFlags(filters)
        run(ResourceContext.Op("prune"), args.toSeq*).map { output =>
            val (deleted, spaceReclaimed) = parsePruneOutput(output, _.matches("^[a-f0-9]{12,64}$"))
            PruneResult(deleted, spaceReclaimed)
        }
    end prune

    // --- Image Operations ---

    def imagePull(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        val ref = image.reference
        val ctx = ResourceContext.Image(ref)
        val baseArgs = Chunk("pull") ++
            platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
            Chunk(ref)
        withRegistryAuth(image, auth, ctx) { creds =>
            // Podman accepts `--creds=user:password` directly on the pull. Docker does not — we have to
            // `docker login` first (handled in withRegistryAuth) and pass an unflagged pull.
            val args = creds match
                case Present(c) if cmd.endsWith("podman") =>
                    Chunk("pull", "--creds", c) ++
                        platform.map(p => Chunk("--platform", p.reference)).getOrElse(Chunk.empty) ++
                        Chunk(ref)
                case _ => baseArgs
            runUnit(ctx, args.toSeq*)
        }
    end imagePull

    /** Decode the Base64 `username:password` credential the API stored under [[ContainerImage.RegistryAuth.auths]] for the image's
      * registry, and apply it to the surrounding pull/push action.
      *
      * On podman the decoded credential is forwarded by the caller as `--creds=user:pass`. On docker we run `docker login` against the
      * registry first (with the same credential) so the subsequent pull/push picks the auth up from `~/.docker/config.json`, then run
      * `docker logout` afterwards as a best-effort cleanup. When `auth` is `Absent` or no credential matches, the action runs without any
      * login.
      */
    private def withRegistryAuth(image: ContainerImage, auth: Maybe[ContainerImage.RegistryAuth], ctx: ResourceContext)(
        run: Maybe[String] => Unit < (Async & Abort[ContainerException])
    )(using Frame): Unit < (Async & Abort[ContainerException]) =
        val creds: Maybe[String] = auth.flatMap { a =>
            val key = image.registry.getOrElse(ContainerImage.Registry.DockerHub)
            a.auths.get(key).orElse {
                if key == ContainerImage.Registry.DockerHub then
                    a.auths.get(ContainerImage.Registry("https://index.docker.io/v1/"))
                else Absent
            }
        }.map { encoded =>
            new String(java.util.Base64.getDecoder.decode(encoded), java.nio.charset.StandardCharsets.UTF_8)
        }
        creds match
            case Absent                               => run(Absent)
            case Present(c) if cmd.endsWith("podman") =>
                // Podman embeds the credential on the pull/push args; nothing to wire here.
                run(Present(c))
            case Present(c) =>
                // Docker: login → action → logout (best-effort).
                val server = image.registry.map(_.value).getOrElse("docker.io")
                val (user, password) = c.split(":", 2) match
                    case Array(u, p) => (u, p)
                    case Array(u)    => (u, "")
                    case _           => ("", "")
                val loginCmd = Command(cmd, "login", "--username", user, "--password-stdin", server)
                    .stdin(Process.Input.FromStream(
                        new java.io.ByteArrayInputStream(password.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    ))
                Abort.runWith[CommandException](loginCmd.textWithExitCode) {
                    case Result.Success((_, exit)) if exit.isSuccess =>
                        // Manual try/finally — Sync.ensure can't take an Async finalizer (logout calls
                        // through the daemon CLI which is Async). Logout is best-effort.
                        Abort.run[ContainerException](run(Absent)).map { result =>
                            Abort.run[ContainerException](runUnit(ctx, "logout", server)).map { _ =>
                                result match
                                    case Result.Success(_)   => ()
                                    case Result.Failure(err) => Abort.fail[ContainerException](err)
                                    case Result.Panic(ex)    => Abort.panic(ex)
                            }
                        }
                    case Result.Success((out, _)) =>
                        Abort.fail[ContainerException](mapError(out, ctx, Seq("login", server)))
                    case Result.Failure(cmdEx) =>
                        Abort.fail[ContainerException](ContainerOperationException(s"docker login failed for $server", cmdEx))
                    case Result.Panic(ex) =>
                        Abort.fail[ContainerException](ContainerBackendException(s"docker login panicked for $server", ex))
                }
        end match
    end withRegistryAuth

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
                case Result.Panic(t) =>
                    // Unexpected panic during local image check — log and re-panic
                    Log.warn(s"unexpected panic checking local image $ref: $t").andThen(Abort.panic(t))
                case Result.Failure(_) =>
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
            filterFlags(filters) ++
            Chunk("--format", "{{json .}}")
        run(ResourceContext.Op("imageList"), args.toSeq*).map { output =>
            if output.trim.isEmpty then Chunk.empty[ContainerImage.Summary]
            else
                Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                    val trimmed = line.trim
                    Json.decode[ImageListJsonDocker](trimmed) match
                        case Result.Success(dto) if dto.ID.nonEmpty => mapDockerImageListToSummary(dto)
                        case Result.Failure(_) | Result.Success(_) =>
                            decodeJson[ImageListJsonPodman](trimmed).map(mapPodmanImageListToSummary)
                        case Result.Panic(t) =>
                            Log.warn(s"unexpected panic decoding Docker image list DTO: $t").andThen(
                                decodeJson[ImageListJsonPodman](trimmed).map(mapPodmanImageListToSummary)
                            )
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
        val repoTags    = Chunk.from(repoTagStrs.map(ContainerImage(_)))
        val repoDigests = Chunk.from(dto.RepoDigests.getOrElse(Seq.empty).map(ContainerImage(_)))

        ContainerImage.Summary(
            id = ContainerImage.Id(id),
            repoTags = repoTags,
            repoDigests = repoDigests,
            createdAt = fromEpochSecond(dto.Created),
            size = dto.Size,
            labels = Dict.from(dto.Labels.getOrElse(Map.empty))
        )
    end mapPodmanImageListToSummary

    def imageInspect(image: ContainerImage)(using Frame): ContainerImage.Info < (Async & Abort[ContainerException]) =
        run(ResourceContext.Image(image.reference), "image", "inspect", "--format", "{{json .}}", image.reference).map { raw =>
            decodeJson[ImageInspectJson](raw).map { dto =>
                ContainerImage.Info(
                    id = ContainerImage.Id(dto.Id),
                    repoTags = Chunk.from(dto.RepoTags.getOrElse(Seq.empty).map(ContainerImage(_))),
                    repoDigests = Chunk.from(dto.RepoDigests.getOrElse(Seq.empty).map(ContainerImage(_))),
                    createdAt = parseInstantOrEpoch(dto.Created),
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

    def imagePush(image: ContainerImage, auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException]) =
        runUnit(ResourceContext.Image(image.reference), "push", image.reference)

    def imageSearch(term: String, limit: Int, filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[ContainerImage.SearchResult] < (Async & Abort[ContainerException]) =
        val args = Chunk("search") ++
            (if limit != Int.MaxValue then Chunk("--limit", limit.toString) else Chunk.empty) ++
            filterFlags(filters) ++
            Chunk("--format", "{{json .}}") ++
            Chunk(term)
        run(ResourceContext.Op("imageSearch"), args.toSeq*).map { output =>
            val parsed: Chunk[ContainerImage.SearchResult] < (Async & Abort[ContainerException]) =
                if output.trim.isEmpty then Chunk.empty[ContainerImage.SearchResult]
                else
                    Kyo.foreach(Chunk.from(output.trim.split("\n"))) { line =>
                        val trimmed = line.trim
                        // Docker: IsOfficial/IsAutomated are strings ("OK"/""), StarCount is a string
                        // Podman: IsOfficial/IsAutomated are booleans, StarCount is an int
                        Json.decode[ImageSearchJsonPodman](trimmed) match
                            case Result.Success(dto) if dto.Name.nonEmpty => mapPodmanImageSearchToResult(dto)
                            case Result.Failure(_) | Result.Success(_) =>
                                decodeJson[ImageSearchJsonDocker](trimmed).map(mapDockerImageSearchToResult)
                            case Result.Panic(t) =>
                                Log.warn(s"unexpected panic decoding Podman image search DTO: $t").andThen(
                                    decodeJson[ImageSearchJsonDocker](trimmed).map(mapDockerImageSearchToResult)
                                )
                        end match
                    }
            parsed.map(results => if limit != Int.MaxValue then results.take(limit) else results)
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
        val ref = image.reference
        run(ResourceContext.Image(ref), "history", "--format", "{{json .}}", ref).map { output =>
            val parsed: Chunk[ContainerImage.HistoryEntry] < (Async & Abort[ContainerException]) =
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
            parsed.map { es =>
                // `podman history` reports each layer's source-comment (e.g. "FROM …:latest");
                // the user's `commit -m` text lives on the image-level Comment instead.
                // Overlay it on the head entry so callers see the same shape as docker.
                if cmd.endsWith("podman") && es.nonEmpty then
                    run(ResourceContext.Image(ref), "image", "inspect", "--format", "{{.Comment}}", ref).map { out =>
                        val imgComment = out.trim
                        if imgComment.isEmpty then es
                        else es.updated(0, es(0).copy(comment = imgComment))
                    }
                else (es: Chunk[ContainerImage.HistoryEntry] < (Async & Abort[ContainerException]))
            }
        }
    end imageHistory

    def imagePrune(filters: Dict[String, Chunk[String]])(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        val args = Chunk("image", "prune", "-f") ++ filterFlags(filters)
        run(ResourceContext.Op("imagePrune"), args.toSeq*).map { output =>
            val (rawDeleted, spaceReclaimed) = parsePruneOutput(
                output,
                l => l.startsWith("deleted:") || l.startsWith("untagged:")
            )
            val deleted = rawDeleted.map(_.split(":").last.trim)
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
        // Podman defaults to OCI image format which has no `Comment` field;
        // `-m` is rejected unless we also pass `-f docker`.
        val needsDockerFormat = comment.nonEmpty && cmd.endsWith("podman")
        val args = Chunk("commit") ++
            (if needsDockerFormat then Chunk("-f", "docker") else Chunk.empty) ++
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
        if config.name.isEmpty then Abort.fail(ContainerOperationException("Network name cannot be empty"))
        else
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
        end if
    end networkCreate

    def networkList(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Network.Info] < (Async & Abort[ContainerException]) =
        val args = Chunk("network", "ls") ++
            filterFlags(filters) ++
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
                        case Result.Failure(_) | Result.Success(_) =>
                            decodeJson[NetworkListJsonDocker](trimmed).map(mapDockerNetworkListToInfo)
                        case Result.Panic(t) =>
                            Log.warn(s"unexpected panic decoding Podman network list DTO: $t").andThen(
                                decodeJson[NetworkListJsonDocker](trimmed).map(mapDockerNetworkListToInfo)
                            )
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
            createdAt = parseInstantOrEpoch(dto.CreatedAt)
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
            createdAt = parseInstantOrEpoch(dto.created)
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
                    createdAt = parseInstantOrEpoch(createdStr)
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
        val args = Chunk("network", "prune", "-f") ++ filterFlags(filters)
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
        run(ResourceContext.Op("volumeCreate"), args.toSeq*).map(lastLine).map { name =>
            // After create, inspect to get full info
            volumeInspect(Volume.Id(name))
        }
    end volumeCreate

    def volumeList(filters: Dict[String, Chunk[String]])(
        using Frame
    ): Chunk[Volume.Info] < (Async & Abort[ContainerException]) =
        val args = Chunk("volume", "ls") ++
            filterFlags(filters) ++
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
                        case Result.Failure(_) =>
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
                                case Result.Failure(_) | Result.Success(_) =>
                                    Chunk.empty
                                case Result.Panic(_) =>
                                    Chunk.empty
                            end match
                        case Result.Panic(_) =>
                            Chunk.empty
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
                    createdAt = parseInstantOrEpoch(dto.CreatedAt),
                    scope = if dto.Scope.nonEmpty then dto.Scope else "local"
                )
            }
        }

    def volumeRemove(id: Volume.Id, force: Boolean)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val ctx = ResourceContext.Volume(id)
        if force then
            // `docker/podman volume rm -f` rejects removal when ANY container (including stopped) references the
            // volume — `--force` only matters for "in use by running containers". To make `force=true` actually
            // tear the volume down, force-remove every referencing container first, then drop the volume.
            run(ctx, "ps", "-a", "--filter", s"volume=${id.value}", "--format", "{{.ID}}").map { output =>
                val containerIds = output.trim.split("\n").iterator.map(_.trim).filter(_.nonEmpty).toSeq
                Kyo.foreach(Chunk.from(containerIds)) { cid =>
                    Abort.run[ContainerException](runUnit(ResourceContext.Container(Container.Id(cid)), "rm", "-f", cid)).unit
                }.andThen(runUnit(ctx, "volume", "rm", "-f", id.value))
            }
        else
            // Check if any containers (including stopped) reference this volume before removing
            run(ctx, "ps", "-a", "--filter", s"volume=${id.value}", "--format", "{{.ID}}").map { output =>
                val ids = output.trim
                if ids.nonEmpty then
                    Abort.fail[ContainerException](
                        ContainerVolumeInUseException(id, ids)
                    )
                else
                    runUnit(ctx, "volume", "rm", id.value)
                end if
            }
        end if
    end volumeRemove

    def volumePrune(filters: Dict[String, Chunk[String]])(using Frame): PruneResult < (Async & Abort[ContainerException]) =
        val args = Chunk("volume", "prune", "-f") ++ filterFlags(filters)
        run(ResourceContext.Op("volumePrune"), args.toSeq*).map { output =>
            val (deleted, spaceReclaimed) = parsePruneOutput(
                output,
                l => l.nonEmpty && !l.toLowerCase.contains("reclaimed")
            )
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

    /** Regex for size strings emitted by prune commands (e.g. "1.5 kB", "200 MB"). */
    private val sizePattern = """(\d+(?:\.\d+)?)\s*(B|kB|MB|GB|TB)""".r

    /** Encode a filters dict as repeated `--filter k=v` CLI flags. */
    private def filterFlags(filters: Dict[String, Chunk[String]]): Chunk[String] =
        Chunk.from(filters.toMap.toSeq.flatMap { case (k, vs) =>
            vs.toSeq.flatMap(v => Seq("--filter", s"$k=$v"))
        })

    /** Encode a command's env map as repeated `-e k=v` CLI flags. */
    private def envFlags(env: Map[String, String]): Chunk[String] =
        Chunk.from(env.flatMap { case (k, v) => Seq("-e", s"$k=$v") })

    /** Encode an optional working directory as a `-w path` CLI flag pair, or empty if absent. */
    private def workDirFlags(workDir: Maybe[Path]): Chunk[String] =
        workDir.map(w => Chunk("-w", w.toString)).getOrElse(Chunk.empty)

    /** Parse the text output of a prune command into (deletedIds, spaceReclaimedBytes).
      *
      * @param output
      *   Raw stdout from the prune command
      * @param deletedFilter
      *   Returns true for lines that represent a deleted item (backend-specific format)
      */
    private def parsePruneOutput(output: String, deletedFilter: String => Boolean): (Chunk[String], Long) =
        val lines   = output.split("\n")
        val deleted = Chunk.from(lines.filter(l => deletedFilter(l.trim)).map(_.trim))
        val spaceReclaimed = lines.find(_.contains("reclaimed")).flatMap { line =>
            sizePattern.findFirstMatchIn(line).map(m => parseSizeString(m.group(0)))
        }.getOrElse(0L)
        (deleted, spaceReclaimed)
    end parsePruneOutput

    /** Run a container CLI command, returning stdout on success or mapping errors via [[mapError]].
      *
      * Spawns the process directly (no shell wrapper) and captures stdout and stderr on independent fibers. This keeps stdout clean for
      * parsing (IDs, JSON) — important because rootless podman without a systemd user session emits `cgroupv2 manager` warnings to stderr
      * on every command. On non-zero exit, stderr is the primary error source (with stdout as fallback for tools that emit errors there).
      */
    private def run(ctx: ResourceContext, args: String*)(using Frame): String < (Async & Abort[ContainerException]) =
        Abort.runWith[Closed](meter.run {
            Abort.runWith[CommandException](runWithStreams(args*)) {
                case Result.Success((stdout, stderr, exitCode)) =>
                    if exitCode == ExitCode.Success then stdout.trim
                    else
                        val errMsg = if stderr.trim.nonEmpty then stderr.trim else stdout.trim
                        Abort.fail(mapError(errMsg, ctx, args))
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

    /** Spawn the underlying CLI directly and return (stdout, stderr, exitCode). */
    private def runWithStreams(args: String*)(using Frame): (String, String, ExitCode) < (Async & Abort[CommandException]) =
        Scope.run {
            for
                proc     <- Command((cmd +: args)*).spawn
                outFib   <- Fiber.init(Scope.run(proc.stdout.run))
                errFib   <- Fiber.init(Scope.run(proc.stderr.run))
                code     <- proc.waitFor
                outBytes <- outFib.get
                errBytes <- errFib.get
            yield (
                new String(outBytes.toArray, java.nio.charset.StandardCharsets.UTF_8),
                new String(errBytes.toArray, java.nio.charset.StandardCharsets.UTF_8),
                code
            )
        }

    private def runUnit(ctx: ResourceContext, args: String*)(using Frame): Unit < (Async & Abort[ContainerException]) =
        run(ctx, args*).unit

    private def lastLine(output: String): String = ShellBackend.lastLine(output)

    private type ErrorEntry = (Seq[String], (ResourceContext, String) => ContainerException)

    /** Patterns-to-builder pairs for the common error classifications. First-match-wins; the per-entry `[N]` comments below pin the
      * ordering contract — do not reorder. Two cases (network-not-found AND, PortConflict regex, "initializing source" surgery) live as
      * explicit else-if branches in [[mapError]] because they need logic the table can't express.
      */
    private def errorTable(using Frame): Seq[ErrorEntry] = Seq(
        // [1] NoSuchContainer — before Conflict
        ErrorPatterns.NoSuchContainer -> { (ctx, out) =>
            ErrorClassification.missingFor(ctx, out)
        },
        // [2] ImageNotFound — before Conflict
        ErrorPatterns.ImageNotFound -> { (ctx, out) =>
            ctx match
                case ResourceContext.Image(ref) =>
                    ContainerImageMissingException(ContainerImage(ref))
                case _ => ContainerOperationException(s"Image not found during ${ctx.describe}", out)
        },
        // [3] Conflict — general name/ID conflict
        ErrorPatterns.Conflict -> { (ctx, _) =>
            ErrorClassification.conflictFor(ctx)
        },
        // [4] AlreadyRunning — before AlreadyStopped
        ErrorPatterns.AlreadyRunning -> { (ctx, out) =>
            ctx match
                case ResourceContext.Container(id) => ContainerAlreadyRunningException(id)
                case _                             => ContainerConflictException(s"Already running: ${ctx.describe}", out)
        },
        // [5] AlreadyStopped — after AlreadyRunning
        ErrorPatterns.AlreadyStopped -> { (ctx, out) =>
            ctx match
                case ResourceContext.Container(id) => ContainerAlreadyStoppedException(id)
                case _                             => ContainerConflictException(s"Already stopped: ${ctx.describe}", out)
        },
        // [6] VolumeNotFound — before BackendUnavailable
        ErrorPatterns.VolumeNotFound -> { (ctx, out) =>
            ctx match
                case ResourceContext.Volume(id) => ContainerVolumeMissingException(id)
                case _                          => ContainerOperationException(s"Volume not found during ${ctx.describe}", out)
        },
        // [7] BackendUnavailable — after resource-missing checks
        ErrorPatterns.BackendUnavailable -> { (_, out) =>
            ContainerBackendUnavailableException(cmd, s"Backend unavailable: $out")
        },
        // [8] VolumeInUse — before AuthFailed ("denied" in AuthFailed is broad)
        ErrorPatterns.VolumeInUse -> { (ctx, out) =>
            ctx match
                case ResourceContext.Volume(id) => ContainerVolumeInUseException(id, out)
                case _                          => ContainerConflictException(s"Volume in use during ${ctx.describe}", out)
        },
        // [9] NetworkEndpointExists — before AuthFailed
        ErrorPatterns.NetworkEndpointExists -> { (ctx, out) =>
            ContainerConflictException(s"Network endpoint already attached: ${ctx.describe}", out)
        },
        // [10] AuthFailed — last simple entry; "denied" is broad so it comes last
        ErrorPatterns.AuthFailed -> { (ctx, out) =>
            ContainerAuthException(ctx.describe, out)
        }
    )

    /** Classify shell command error output into a typed [[ContainerException]].
      *
      * Docker uses exit code 1 for daemon errors; Podman uses 125. Both engines emit stable English phrases on stderr — those phrases are
      * the primary classification signal. Looks up [[errorTable]] first for simple match-and-build cases; falls through to inline branches
      * for the three patterns that need richer logic (network-not-found AND, PortConflict regex, initializing-source surgery).
      */
    private def mapError(output: String, ctx: ResourceContext, args: Seq[String])(using Frame): ContainerException =
        val lower = output.toLowerCase

        def matchesAny(patterns: Seq[String]): Boolean = patterns.exists(lower.contains)

        // PortConflict is checked first because podman's port-conflict message contains
        // "address already in use" which would otherwise match the broader Conflict pattern.
        // Extract the host port from the bind expression:
        //   docker: "Bind for 0.0.0.0:52915 failed: port 7136 is already allocated"
        //   podman: "listen tcp :12346: bind: address already in use"
        // The regex anchors on `<ip>?:NNNN` so it never picks up unrelated digits.
        if matchesAny(ErrorPatterns.PortConflict) then
            val bindPattern = """(?:0\.0\.0\.0|\[::\]|::)?:(\d+)""".r
            val port = bindPattern.findFirstMatchIn(output)
                .flatMap(m => m.group(1).toIntOption)
                .getOrElse(0)
            ContainerPortConflictException(port, output)
        else
            errorTable
                .find { case (patterns, _) => matchesAny(patterns) }
                .map { case (_, builder) => builder(ctx, output) }
                .getOrElse {
                    // network-not-found: two-phrase AND condition — not expressible as plain Seq[String] with exists
                    if lower.contains("network") && lower.contains("not found") then
                        ctx match
                            case ResourceContext.Network(id) => ContainerNetworkMissingException(id)
                            case _ => ContainerOperationException(s"Network not found during ${ctx.describe}", output)
                    // initializing source with an auth signal — registry rejected the request, not the image
                    // missing. Common shapes: `403 (Forbidden)`, `401 (Unauthorized)`, "bearer token", "denied".
                    else if lower.contains("initializing source") &&
                        (lower.contains("403") || lower.contains("401") ||
                            lower.contains("forbidden") || lower.contains("unauthorized") ||
                            lower.contains("bearer token") || lower.contains("denied"))
                    then
                        ContainerAuthException(ctx.describe, output)
                    // initializing source: multi-step string surgery to extract the image ref
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
                        ContainerImageMissingException(ContainerImage(imageRef))
                    else
                        ContainerOperationException(s"${cmd} ${args.mkString(" ")} failed", output)
                    end if
                }
        end if
    end mapError

    /** Named constants for error message patterns used in [[mapError]].
      *
      * Each pattern set covers both Docker and Podman error messages. Docker uses "Error response from daemon: ..." on stderr with exit
      * code 1. Podman uses direct error messages with exit code 125 for infrastructure errors.
      */
    private object ErrorPatterns:
        /** Container not found — Docker: "No such container", Podman: "no such container", "no such object".
          *
          * Extends [[DaemonErrorPhrases.NoSuchContainer]] with Shell-only phrases ("no such object", "no container with name or id").
          */
        val NoSuchContainer: Seq[String] =
            DaemonErrorPhrases.NoSuchContainer ++ Seq("no such object", "no container with name or id")

        /** Image not found — Docker: "manifest unknown", Podman: "image not found".
          *
          * Extends [[DaemonErrorPhrases.NoSuchImage]] with Shell-only phrases.
          */
        val ImageNotFound: Seq[String] =
            DaemonErrorPhrases.NoSuchImage ++ Seq(
                "image not found",
                "requested access to the resource is denied",
                "repository does not exist",
                "name unknown"
            )

        /** Name/ID conflict — Docker & Podman both use "conflict" or "already in use".
          *
          * Extends [[DaemonErrorPhrases.AlreadyInUse]] with Shell-only phrases ("conflict", "name is already in use").
          */
        val Conflict: Seq[String] =
            DaemonErrorPhrases.AlreadyInUse ++ Seq("conflict", "name is already in use")

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
            stderr.contains("is paused") ||
            stderr.contains("container state improper") ||
            stderr.contains("exec sessions on running")

end ShellBackend

private[kyo] object ShellBackend:

    /** Default capacity for the channel that merges exec stdout/stderr into a single `LogEntry` stream. Overridable via
      * `BackendConfig.Shell(... , streamBufferSize = N)`.
      */
    val defaultStreamBufferSize: Int = 256

    /** Extract the last non-empty trimmed line from CLI output. Used to parse the resource ID emitted by `docker create`, `podman create`,
      * and `docker/podman volume create` — those commands print the ID on the final line, but stdout may also include image-pull progress
      * (docker auto-pull on missing image) or runtime warnings (podman cgroupv2 fallback when no systemd user session is available) before
      * it.
      */
    def lastLine(output: String): String =
        output.linesIterator.map(_.trim).filter(_.nonEmpty).toSeq.lastOption.getOrElse("")

    /** Parse a single log line into a [[Container.LogEntry]], splitting off the timestamp prefix when `hasTimestamps` is true.
      *
      * Reuses [[ContainerBackend.parseInstant]] for the timestamp parse so Docker Zulu format and Podman offset format are both handled.
      */
    private[kyo] def parseLogLine(line: String, source: Container.LogEntry.Source, hasTimestamps: Boolean): Container.LogEntry =
        if hasTimestamps then
            val spaceIdx = line.indexOf(' ')
            if spaceIdx > 0 then
                val tsStr   = line.substring(0, spaceIdx)
                val content = line.substring(spaceIdx + 1)
                val ts      = ContainerBackend.parseInstant(Some(tsStr))
                Container.LogEntry(source, content, ts)
            else Container.LogEntry(source, line)
            end if
        else Container.LogEntry(source, line)

    private[kyo] def parseLogLines(raw: String, source: Container.LogEntry.Source, hasTimestamps: Boolean): Chunk[Container.LogEntry] =
        if raw.trim.isEmpty then Chunk.empty
        else Chunk.from(raw.split("\n").iterator.filter(_.nonEmpty).map(parseLogLine(_, source, hasTimestamps)).toSeq)
    end parseLogLines

    /** Parse the output of the `top` command into a [[Container.TopResult]].
      *
      * Preserves current behavior: empty output returns empty titles and processes; single-line output (headers only) returns titles with
      * no processes.
      */
    private[kyo] def parseTopOutput(output: String): Container.TopResult =
        if output.trim.isEmpty then Container.TopResult(Chunk.empty, Chunk.empty)
        else
            val lines     = output.split("\n").iterator.filter(_.trim.nonEmpty).toArray
            val titles    = Chunk.from(lines.head.split("\\s+"))
            val processes = Chunk.from(lines.tail.map(l => Chunk.from(l.split("\\s+", titles.length))))
            Container.TopResult(titles, processes)
        end if
    end parseTopOutput

    /** Auto-detect an available container runtime by probing CLI binaries on `PATH`.
      *
      * Resolution order:
      *   1. `podman version` — if it exits successfully, the resulting backend uses the `podman` CLI.
      *   2. `docker version` — if it exits successfully, the resulting backend uses the `docker` CLI.
      *
      * Failures and panics from one probe never short-circuit the next: a panic on `podman` is logged and we fall through to `docker`.
      * Fails with [[ContainerBackendUnavailableException]] only when neither binary is usable.
      *
      * @see
      *   [[HttpContainerBackend.detect]] for the socket-based companion path used when no CLI is available.
      */
    def detect(
        meter: Meter = Meter.Noop,
        streamBufferSize: Int = defaultStreamBufferSize
    )(using Frame): ShellBackend < (Async & Abort[ContainerException]) =
        Abort.runWith[CommandException](Command("podman", "version").textWithExitCode) {
            case Result.Success((_, exitCode)) if exitCode == ExitCode.Success =>
                val backend = new ShellBackend("podman", meter, streamBufferSize)
                Log.info(s"kyo-pod: using shell backend (CLI=podman)").andThen(backend)
            case Result.Failure(_) =>
                Abort.runWith[CommandException](Command("docker", "version").textWithExitCode) {
                    case Result.Success((_, exitCode)) if exitCode == ExitCode.Success =>
                        val backend = new ShellBackend("docker", meter, streamBufferSize)
                        Log.info(s"kyo-pod: using shell backend (CLI=docker)").andThen(backend)
                    case Result.Failure(_) =>
                        Abort.fail(ContainerBackendUnavailableException(
                            "auto-detect",
                            "Neither podman nor docker is available. Install one of them."
                        ))
                    case Result.Panic(t) =>
                        Log.warn(s"unexpected error in detect (docker probe): $t").andThen(
                            Abort.fail(ContainerBackendUnavailableException(
                                "auto-detect",
                                "Neither podman nor docker is available. Install one of them."
                            ))
                        )
                }
            case Result.Panic(t) =>
                Log.warn(s"unexpected error in detect (podman probe): $t").andThen(
                    Abort.runWith[CommandException](Command("docker", "version").textWithExitCode) {
                        case Result.Success((_, exitCode)) if exitCode == ExitCode.Success =>
                            val backend = new ShellBackend("docker", meter, streamBufferSize)
                            Log.info(s"kyo-pod: using shell backend (CLI=docker)").andThen(backend)
                        case Result.Failure(_) =>
                            Abort.fail(ContainerBackendUnavailableException(
                                "auto-detect",
                                "Neither podman nor docker is available. Install one of them."
                            ))
                        case Result.Panic(t2) =>
                            Log.warn(s"unexpected error in detect (docker probe after podman panic): $t2").andThen(
                                Abort.fail(ContainerBackendUnavailableException(
                                    "auto-detect",
                                    "Neither podman nor docker is available. Install one of them."
                                ))
                            )
                    }
                )
        }

    // --- JSON DTOs for container inspect ---

    final private[internal] case class InspectJson(
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
        Mounts: Option[Seq[InspectMountDto]] = None,
        Platform: String = ""
    ) derives Schema

    final private[internal] case class InspectStateJson(
        Status: String = "",
        Pid: Int = 0,
        ExitCode: Int = 0,
        StartedAt: String = "",
        FinishedAt: String = "",
        Health: Option[InspectHealthDto] = None
    ) derives Schema

    final private[internal] case class InspectConfigJson(
        Image: String = "",
        Hostname: String = "",
        Cmd: Option[Seq[String]] = None,
        Env: Option[Seq[String]] = None,
        Labels: Option[Map[String, String]] = None,
        Tty: Boolean = false
    ) derives Schema

    final private[internal] case class InspectNetworkSettingsJson(
        IPAddress: String = "",
        Gateway: String = "",
        MacAddress: String = "",
        Ports: Option[Map[String, Seq[PortMappingJson]]] = None,
        Networks: Option[Map[String, NetworkEndpointEntryJson]] = None
    ) derives Schema

    final private[internal] case class PortMappingJson(
        HostIp: String = "",
        HostPort: String = ""
    ) derives Schema

    /** Podman `ps --format '{{json .}}'` serializes Ports as a flat array of objects. */
    final private[internal] case class PodmanPsPortJson(
        host_ip: String = "",
        host_port: Int = 0,
        container_port: Int = 0,
        protocol: String = "tcp",
        range: Int = 1
    ) derives Schema

    final private[internal] case class NetworkEndpointEntryJson(
        NetworkID: String = "",
        EndpointID: String = "",
        Gateway: String = "",
        IPAddress: String = "",
        IPPrefixLen: Int = 0,
        MacAddress: String = ""
    ) derives Schema

    // --- JSON DTOs for container stats ---
    // Stats JSON differs significantly between docker and podman.
    // Docker uses formatted strings (CPUPerc: "0.50%", MemUsage: "100MiB / 1GiB").
    // Podman uses numeric fields (CPU: 0.5, MemUsage: 104857600).
    // We use two separate DTOs and try Docker format first.

    final private[internal] case class DockerStatsJson(
        CPUPerc: String = "",
        MemPerc: String = "",
        MemUsage: String = "",
        NetIO: String = "",
        BlockIO: String = "",
        PIDs: String = "0"
    ) derives Schema

    final private[internal] case class PodmanStatsJson(
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

    final private[internal] case class NetStatsJson(
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
    final private[internal] case class PsJsonDocker(
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
    final private[internal] case class PsJsonPodman(
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
    final private[internal] case class ImageListJsonDocker(
        ID: String = "",
        Repository: String = "",
        Tag: String = "",
        CreatedAt: String = "",
        Size: String = ""
    ) derives Schema

    // Podman: Id lowercase, Created as epoch number, Size as number, Labels as object
    final private[internal] case class ImageListJsonPodman(
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

    final private[internal] case class ImageInspectJson(
        Id: String = "",
        RepoTags: Option[Seq[String]] = None,
        RepoDigests: Option[Seq[String]] = None,
        Created: String = "",
        Size: Long = 0,
        Architecture: String = "",
        Os: String = "",
        Config: ImageInspectConfigJson = ImageInspectConfigJson()
    ) derives Schema

    final private[internal] case class ImageInspectConfigJson(
        Labels: Option[Map[String, String]] = None
    ) derives Schema

    // --- JSON DTOs for image search ---
    // Docker: IsOfficial/IsAutomated are strings ("OK" or ""), StarCount is a string
    final private[internal] case class ImageSearchJsonDocker(
        Name: String = "",
        Description: String = "",
        StarCount: String = "0",
        IsOfficial: String = "",
        IsAutomated: String = ""
    ) derives Schema

    // Podman: IsOfficial/IsAutomated are booleans, StarCount is an int
    final private[internal] case class ImageSearchJsonPodman(
        Name: String = "",
        Description: String = "",
        StarCount: Int = 0,
        IsOfficial: Boolean = false,
        IsAutomated: Boolean = false
    ) derives Schema

    // --- JSON DTOs for image history ---

    final private[internal] case class ImageHistoryJson(
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
    final private[internal] case class NetworkListJsonDocker(
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
    final private[internal] case class NetworkListJsonPodman(
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

    final private[internal] case class NetworkInspectJson(
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

    final private[internal] case class NetworkContainerJson(
        EndpointID: String = "",
        IPv4Address: String = "",
        MacAddress: String = ""
    ) derives Schema

    // --- JSON DTOs for volume list ---
    // Docker serializes Labels as a comma-separated string ("key=val,key2=val2").
    // Podman serializes Labels as a JSON object ({"key":"val"}).
    // We use two separate DTOs and try Docker format first.

    final private[internal] case class VolumeListJsonDocker(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Scope: String = "",
        Labels: String = ""
    ) derives Schema

    final private[internal] case class VolumeListJsonPodman(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Scope: String = "",
        Labels: Option[Map[String, String]] = None
    ) derives Schema

    // --- JSON DTOs for volume inspect ---

    final private[internal] case class VolumeInspectJson(
        Name: String = "",
        Driver: String = "",
        Mountpoint: String = "",
        Labels: Option[Map[String, String]] = None,
        Options: Option[Map[String, String]] = None,
        CreatedAt: String = "",
        Scope: String = ""
    ) derives Schema

end ShellBackend
