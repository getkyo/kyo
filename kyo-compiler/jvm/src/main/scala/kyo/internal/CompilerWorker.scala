package kyo.internal

import io.aeron.Aeron
import kyo.*
import kyo.CompilerError

/** The forked-worker JVM entry point.
  *
  * Launched as `java -cp <worker + scala3-pc:vN> kyo.internal.CompilerWorker`. It reads the per-config
  * classpath, scala version, source roots, scalac options, and the aeron request/response uris and
  * shared medium directory from its process system properties, hosts the same pc-driving code as the
  * in-process backend (a [[kyo.internal.LocalBackend]]) behind an aeron serve-loop, and stays alive until the
  * host kills it. KyoApp owns the process-boundary run and catch, so the worker's process boundary is
  * its containment boundary with no hand-rolled top-level catch.
  */
object CompilerWorker extends KyoApp:

    // The worker entry point is a synthesized-frame boundary: no user frame exists at a process main,
    // so the run call and its block root their Frame here (Frame cannot derive in `package kyo`).
    private given Frame = Frame.internal

    run {
        val reqStreamId  = WorkerFlags.reqStreamId.value
        val respStreamId = WorkerFlags.respStreamId.value
        val aeronDir     = WorkerFlags.aeronDir.value
        WorkerConfig.fromEnv().map(config => WorkerServer.serve(config, aeronDir, reqStreamId, respStreamId))
    }

end CompilerWorker

/** Worker-process config flags. Each flag's key is its fully-qualified object name
  * (`kyo.internal.WorkerFlags.<name>`); the spawning host sets them as `-D` properties on the worker
  * JVM and the worker reads them once at startup, the StaticFlag use case (startup infra config that
  * never changes for a worker's life).
  */
private[internal] object WorkerFlags:
    object reqStreamId  extends StaticFlag[Int](0)
    object respStreamId extends StaticFlag[Int](0)
    object aeronDir     extends StaticFlag[String]("")
    object scalaVersion extends StaticFlag[String]("")
    object classpath    extends StaticFlag[String]("")
    object sourceRoots  extends StaticFlag[String]("")
    object options      extends StaticFlag[String]("")
end WorkerFlags

/** Parses the worker's per-config Compiler.Config from the process system properties (the classpath,
  * scala version, source roots, and scalac options the spawning host passed). The classpath and
  * source-root strings are split on the platform path separator, the same separator the host joined
  * them with, so the worker's reconstructed Config carries the real source roots and its pc gets the
  * same `-sourcepath` the in-process backend would.
  */
private[internal] object WorkerConfig:
    def fromEnv()(using Frame): Compiler.Config < Sync =
        Sync.defer {
            val version     = WorkerFlags.scalaVersion.value
            val classpath   = parsePaths(WorkerFlags.classpath.value)
            val sourceRoots = parsePaths(WorkerFlags.sourceRoots.value)
            val options     = Chunk.from(WorkerFlags.options.value.split(' ').filter(_.nonEmpty).toSeq)
            Compiler.Config(
                toolchain = Compiler.Toolchain(version, classpath),
                classpath = classpath,
                scalacOptions = options,
                sourceRoots = sourceRoots,
                isolate = Present(false)
            )
        }

    private def parsePaths(raw: String): Chunk[Path] =
        Chunk.from(
            Maybe(raw).getOrElse("")
                .split(Path.pathSeparator.charAt(0))
                .iterator
                .filter(_.nonEmpty)
                .map(segment => Path(segment))
                .toSeq
        )
end WorkerConfig

/** The worker serve-loop: instantiate the Local backend over the config, connect an aeron client to
  * the host's shared medium (the host's driver directory, passed via a system property), then for
  * every `Envelope.Req(id, request)` the host publishes, drive the backend and publish the matching
  * `Envelope.Resp(id, response)`. The Local backend hosts the same pc-driving code as the in-process
  * path. The loop is a `Topic.stream.foreach`, never a bare loop, and runs until the host kills the
  * process.
  */
private[internal] object WorkerServer:
    def serve(config: Compiler.Config, aeronDir: String, reqStreamId: Int, respStreamId: Int)(using
        Frame
    ): Unit < (Async & Abort[CompilerError] & Scope) =
        LocalBackend.init(config).map { backend =>
            connect(aeronDir).map { aeron =>
                // The serve loop runs until the request/reply session breaks. A transport break (a
                // `Closed` medium, or backpressure the publish retry cannot clear) is NEVER swallowed
                // per response: it propagates here, ends the loop, and stops the worker, so the host's
                // Exchange observes a broken session and fails its pending op with a typed
                // `TransportError` rather than hanging on a silently dropped reply. The host then
                // force-kills and respawns. The break is logged, not discarded.
                Topic.run(aeron) {
                    Topic.stream[Envelope]("aeron:ipc", Topic.defaultRetrySchedule, Present(reqStreamId)).foreach {
                        case Envelope.Req(id, request) => respond(backend, respStreamId, id, request)
                        case _                         => ()
                    }
                }.handle(Abort.run[Closed | Topic.Backpressured]).map {
                    case Result.Success(_) => ()
                    case break             => Log.warn(s"[worker] serve session ended: $break")
                }
            }
        }

    /** Connects an aeron client to the host's existing shared-memory medium (the driver directory the
      * spawning host created), Scope-bound so the client is closed on scope close. The worker shares
      * the host's one `aeron:ipc` medium rather than launching a second driver.
      */
    private def connect(aeronDir: String)(using Frame): Aeron < (Sync & Scope) =
        Scope.acquireRelease(
            Sync.defer(Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir)))
        )(a => Sync.defer(a.close()))

    /** Drives the backend for one request and publishes the id-matched response; a backend failure or
      * panic is reported as a `Response.Failed` frame rather than killing the serve loop.
      */
    private def respond(backend: Backend, respStreamId: Int, id: Int, request: Request)(using
        Frame
    ): Unit < (Async & Abort[Closed | Topic.Backpressured] & Topic) =
        Abort.run[CompilerError](backend.run(request)).map {
            case Result.Success(response) => publish(respStreamId, Envelope.Resp(id, response))
            case Result.Failure(error)    => publish(respStreamId, Envelope.Resp(id, Response.Failed(error)))
            case Result.Panic(err)        => publish(respStreamId, Envelope.Resp(id, Response.Failed(CompilerError.Fatal(err.getMessage))))
        }

    /** Publishes one response frame. A transport break (`Closed`, or backpressure the publish retry
      * cannot clear) is NOT swallowed here: it propagates so the serve loop ends and the host observes
      * a broken session instead of hanging on a silently dropped reply.
      */
    private def publish(respStreamId: Int, frame: Envelope)(using Frame): Unit < (Async & Abort[Closed | Topic.Backpressured] & Topic) =
        Topic.publish[Envelope]("aeron:ipc", Topic.defaultRetrySchedule, Present(respStreamId))(Stream.init(Chunk(frame)))
end WorkerServer
