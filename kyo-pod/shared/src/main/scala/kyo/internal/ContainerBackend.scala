package kyo.internal

import kyo.*

/** Abstract contract that container runtime backends implement.
  *
  * Users don't interact with this directly. Container and ContainerImage delegate all operations to the active backend. Auto-detected via
  * `ContainerBackend.detect` which probes Unix domain sockets. Override with `Container.withBackend` to force a specific runtime.
  *
  * @see
  *   [[HttpContainerBackend]] HTTP-based implementation via Unix domain socket
  * @see
  *   [[kyo.Container.BackendConfig]] Configuration for backend selection
  */
abstract private[kyo] class ContainerBackend:
    // Container lifecycle
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
    // Checkpoint/Restore
    def checkpoint(id: Container.Id, name: String)(using Frame): Unit < (Async & Abort[ContainerException])
    def restore(id: Container.Id, checkpoint: String)(using Frame): Unit < (Async & Abort[ContainerException])
    // Health
    def isHealthy(id: Container.Id)(using Frame): Boolean < (Async & Abort[ContainerException])
    // Inspection
    def inspect(id: Container.Id)(using Frame): Container.Info < (Async & Abort[ContainerException])
    def state(id: Container.Id)(using Frame): Container.State < (Async & Abort[ContainerException])
    def stats(id: Container.Id)(using Frame): Container.Stats < (Async & Abort[ContainerException])
    def statsStream(id: Container.Id)(using Frame): Stream[Container.Stats, Async & Abort[ContainerException]]
    def statsStream(id: Container.Id, interval: Duration)(using Frame): Stream[Container.Stats, Async & Abort[ContainerException]]
    def top(id: Container.Id, psArgs: String)(using Frame): Container.TopResult < (Async & Abort[ContainerException])
    def changes(id: Container.Id)(using Frame): Chunk[Container.FilesystemChange] < (Async & Abort[ContainerException])
    // Exec
    def exec(id: Container.Id, command: Command)(using Frame): Container.ExecResult < (Async & Abort[ContainerException])
    def execStream(id: Container.Id, command: Command)(
        using Frame
    ): Stream[Container.LogEntry, Async & Abort[ContainerException]]
    def execInteractive(id: Container.Id, command: Command)(
        using Frame
    ): Container.AttachSession < (Async & Abort[ContainerException] & Scope)
    // Attach
    def attach(id: Container.Id, stdin: Boolean, stdout: Boolean, stderr: Boolean)(
        using Frame
    ): Container.AttachSession < (Async & Abort[ContainerException] & Scope)
    // Logs
    def logs(
        id: Container.Id,
        stdout: Boolean,
        stderr: Boolean,
        since: Instant,
        until: Instant,
        tail: Int,
        timestamps: Boolean
    )(using Frame): Chunk[Container.LogEntry] < (Async & Abort[ContainerException])
    def logStream(
        id: Container.Id,
        stdout: Boolean,
        stderr: Boolean,
        since: Instant,
        tail: Int,
        timestamps: Boolean
    )(using Frame): Stream[Container.LogEntry, Async & Abort[ContainerException]]
    // File ops
    def copyTo(id: Container.Id, source: Path, containerPath: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    def copyFrom(id: Container.Id, containerPath: Path, destination: Path)(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    def stat(id: Container.Id, containerPath: Path)(using Frame): Container.FileStat < (Async & Abort[ContainerException])
    def exportFs(id: Container.Id)(using Frame): Stream[Byte, Async & Abort[ContainerException]]
    // Resource updates
    def update(
        id: Container.Id,
        memory: Maybe[Long],
        memorySwap: Maybe[Long],
        cpuLimit: Maybe[Double],
        cpuAffinity: Maybe[String],
        maxProcesses: Maybe[Long],
        restartPolicy: Maybe[Container.Config.RestartPolicy]
    )(using Frame): Unit < (Async & Abort[ContainerException])
    // Network on container
    def connectToNetwork(id: Container.Id, networkId: Container.Network.Id, aliases: Seq[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    def disconnectFromNetwork(id: Container.Id, networkId: Container.Network.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    // Container listing
    def list(all: Boolean, filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Container.Summary] < (Async & Abort[ContainerException])
    def prune(filters: Map[String, Seq[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException])
    def attachById(idOrName: Container.Id)(using Frame): (Container.Id, Container.Config) < (Async & Abort[ContainerException])
    // Image operations
    def imagePull(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    def imageEnsure(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    def imagePullWithProgress(image: ContainerImage, platform: Maybe[Container.Platform], auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Stream[ContainerImage.PullProgress, Async & Abort[ContainerException]]
    def imageList(all: Boolean, filters: Map[String, Seq[String]])(
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
        tags: Seq[String],
        buildArgs: Map[String, String],
        labels: Map[String, String],
        noCache: Boolean,
        pull: Boolean,
        target: Maybe[String],
        platform: Maybe[Container.Platform],
        auth: Maybe[ContainerImage.RegistryAuth]
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]]
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
    )(using Frame): Stream[ContainerImage.BuildProgress, Async & Abort[ContainerException]]
    def imagePush(image: ContainerImage, auth: Maybe[ContainerImage.RegistryAuth])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    def imageSearch(term: String, limit: Int, filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[ContainerImage.SearchResult] < (Async & Abort[ContainerException])
    def imageHistory(image: ContainerImage)(using Frame): Chunk[ContainerImage.HistoryEntry] < (Async & Abort[ContainerException])
    def imagePrune(filters: Map[String, Seq[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException])
    def imageCommit(
        container: Container.Id,
        repo: String,
        tag: String,
        comment: String,
        author: String,
        pause: Boolean
    )(using Frame): String < (Async & Abort[ContainerException])
    // Network operations
    def networkCreate(config: Container.Network.Config)(using Frame): Container.Network.Id < (Async & Abort[ContainerException])
    def networkList(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Container.Network.Info] < (Async & Abort[ContainerException])
    def networkInspect(id: Container.Network.Id)(using Frame): Container.Network.Info < (Async & Abort[ContainerException])
    def networkRemove(id: Container.Network.Id)(using Frame): Unit < (Async & Abort[ContainerException])
    def networkConnect(network: Container.Network.Id, container: Container.Id, aliases: Seq[String])(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    def networkDisconnect(network: Container.Network.Id, container: Container.Id, force: Boolean)(
        using Frame
    ): Unit < (Async & Abort[ContainerException])
    def networkPrune(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Container.Network.Id] < (Async & Abort[ContainerException])
    // Volume operations
    def volumeCreate(config: Container.Volume.Config)(using Frame): Container.Volume.Info < (Async & Abort[ContainerException])
    def volumeList(filters: Map[String, Seq[String]])(
        using Frame
    ): Chunk[Container.Volume.Info] < (Async & Abort[ContainerException])
    def volumeInspect(id: Container.Volume.Id)(using Frame): Container.Volume.Info < (Async & Abort[ContainerException])
    def volumeRemove(id: Container.Volume.Id, force: Boolean)(using Frame): Unit < (Async & Abort[ContainerException])
    def volumePrune(filters: Map[String, Seq[String]])(using Frame): Container.PruneResult < (Async & Abort[ContainerException])
    // RegistryAuth
    def registryAuthFromConfig(using Frame): ContainerImage.RegistryAuth < (Async & Abort[ContainerException])
    // Backend detection
    def detect()(using Frame): Unit < (Async & Abort[ContainerException])
end ContainerBackend

private[kyo] object ContainerBackend:

    /** Auto-detect an available container runtime.
      *
      * Tries HTTP backend via Unix domain socket first, falls back to ShellBackend via CLI.
      */
    def detect()(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        Abort.run[ContainerException](HttpContainerBackend.detect()).map {
            case Result.Success(backend) => backend: ContainerBackend
            case Result.Failure(_)       => detectShell()
            case Result.Panic(ex) =>
                Log.warn("Unexpected error during HTTP backend detection", ex).andThen(
                    detectShell()
                )
        }

    private def detectShell()(using Frame): ContainerBackend < (Async & Abort[ContainerException]) =
        ShellBackend.detect().map(b => b: ContainerBackend)

end ContainerBackend

private[kyo] case class ContainerHealthState(healthy: Boolean, check: Maybe[Container.HealthCheck]) derives CanEqual
