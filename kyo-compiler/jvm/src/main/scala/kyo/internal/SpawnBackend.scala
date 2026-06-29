package kyo.internal

import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import kyo.*

/** The forked-worker backend: drives a per-config worker JVM over an aeron request/response session.
  *
  * `init` spawns `java -cp <worker + scala3-pc:vN> kyo.internal.CompilerWorker` via [[kyo.Command]], connects
  * an aeron client to the pool's shared embedded medium, and wires a [[kyo.Exchange]] over two
  * `aeron:ipc` streams. The request and reply ride distinct aeron `stream-id`s (per direction and per
  * config), so a host never reads its own request back and two configs' workers never cross-talk on
  * the one shared medium. The host computes the stream ids and threads them to the worker, so both
  * sides agree on them across the process boundary. The op calls `exchange(req)`; the Exchange owns the request/reply id correlation,
  * the pending-promise map, the reader fiber, and the cleanup on a broken session. A `TransportError`
  * or a closed session maps to `CompilerTransportException` at this boundary so the op surface stays
  * `Abort[CompilerException]`; a broken session fails every pending op, and a stuck worker is reclaimed by
  * force-killing the process. The wire codec is the upickle [[Compiler.AsMessage]] the `Envelope`
  * derives, carried directly by kyo-aeron's native `Topic.publish`/`Topic.stream`.
  *
  * The process, aeron client, and Exchange are acquired without binding them to an ambient `Scope`
  * (the pool resolves backends outside any per-backend scope); `close` releases all three, and the
  * pool's close-on-evict cache finalizer drives `close` on eviction.
  */
final private[kyo] class SpawnBackend(
    private[kyo] val process: Process,
    aeron: Aeron,
    exchange: Exchange[Request, Response, Nothing, TransportError]
) extends Backend:

    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerException]) =
        exchange(request)
            .handle(Abort.run[TransportError | Closed])
            .map {
                case Result.Success(Response.Failed(error)) => Abort.fail(error)
                case Result.Success(response)               => response
                case Result.Failure(TransportError(m))      => Abort.fail(CompilerTransportException(m))
                case Result.Failure(_)                      => Abort.fail(CompilerTransportException("worker session closed"))
                case Result.Panic(err)                      => Abort.fail(CompilerTransportException(err))
            }

    def close(using Frame): Unit < (Async & Abort[Throwable]) =
        exchange.close
            .andThen(Sync.defer(aeron.close()))
            .andThen(process.destroyForcibly)

end SpawnBackend

private[kyo] object SpawnBackend:
    /** Spawns the worker JVM, connects the aeron client, wires the Exchange, and wraps all three in a
      * SpawnBackend.
      *
      * `onSpawn` is a test-only observation seam, handed the spawned worker process once the
      * interrupt-safe kill is armed and immediately before the readiness probe runs, so a test can
      * interrupt mid-probe and assert the partial worker is force-killed. The real call site uses the
      * no-op default, so production behavior is unchanged.
      */
    def init(
        config: Compiler.Config,
        driver: MediaDriver,
        streamIdBase: Int,
        onSpawn: Process => Unit = _ => (),
        readyTimeout: Duration = 30.seconds
    )(using Frame): SpawnBackend < (Async & Abort[CompilerException]) =
        // Per-direction, per-config STREAM ids on the one shared `aeron:ipc` medium: req and resp carry
        // distinct stream ids (even/odd off the base) so a host never reads its own request, and distinct
        // configs carry distinct bases so two workers never cross-talk. The base is a unique per-config
        // value the pool allocates from a monotonic counter (so distinctness is guaranteed, not merely a
        // 32-bit hashCode probability); the host threads the ids to the worker so both sides agree across
        // the process boundary.
        val reqStreamId  = streamIdBase * 2
        val respStreamId = streamIdBase * 2 + 1
        Abort.run[Throwable] {
            Abort.catching[Throwable] {
                spawnWorker(config, driver, reqStreamId, respStreamId).map { process =>
                    aeronClient(driver).map { aeron =>
                        connect(aeron, reqStreamId, respStreamId).map { exchange =>
                            val backend = new SpawnBackend(process, aeron, exchange)
                            // The process and aeron client are owned by `close` only once init succeeds.
                            // Until then a failure OR an interrupt during the up-to-30s readiness probe (an
                            // IDE routinely cancels a cold-start) must force-kill the partial worker and close
                            // the client, else it leaks an orphaned JVM plus an aeron conductor thread. The
                            // finalizer runs uninterruptibly on every non-success path (the kill and the aeron
                            // close are the leak-critical parts; the worker's reader fiber ends when the
                            // client closes); on success ownership transfers to `close`.
                            AtomicBoolean.init(false).map { started =>
                                Sync.ensure(
                                    started.get.map(ok =>
                                        if ok then () else Sync.defer(aeron.close()).andThen(process.destroyForcibly)
                                    )
                                ) {
                                    // onSpawn fires inside the armed finalizer's scope, so a test observing the
                                    // process knows the kill-on-interrupt path is live before it interrupts.
                                    Sync.defer(onSpawn(process))
                                        .andThen(ready(backend, config.toolchain.scalaVersion, readyTimeout))
                                        .andThen(started.set(true).andThen(backend))
                                }
                            }
                        }
                    }
                }
            }
        }.map {
            case Result.Success(value) => value
            // Log the failure; CompilerWorkerSpawnException carries the Scala version and the cause.
            case Result.Failure(t) =>
                Log.error("worker backend failed to initialize", t)
                    .andThen(Abort.fail(CompilerWorkerSpawnException(config.toolchain.scalaVersion, t)))
            case Result.Panic(t) =>
                Log.error("worker backend failed to initialize", t)
                    .andThen(Abort.fail(CompilerWorkerSpawnException(config.toolchain.scalaVersion, t)))
        }
    end init

    /** A bounded readiness round-trip so a worker that cannot start (e.g. an unusable classpath, whose
      * publication never sees a subscriber and would otherwise retry forever) or hangs surfaces as
      * InitializationFailed here, not as a forever-retrying publish on the caller's first real op. A
      * `DidClose` probe is cheap and idempotent on the worker; if no reply arrives within the bound the
      * worker is taken as failed to start.
      */
    private def ready(backend: SpawnBackend, scalaVersion: String, readyTimeout: Duration)(using
        Frame
    ): Unit < (Async & Abort[CompilerException]) =
        val probe = backend.run(Request.DidClose(Compiler.Uri("kyo-compiler-readiness-probe.scala")))
        Abort.run[CompilerException | Timeout](Async.timeout(readyTimeout)(probe)).map {
            case Result.Success(_) => ()
            case _                 => Abort.fail(CompilerWorkerReadyException(scalaVersion, readyTimeout))
        }
    end ready

    /** Builds and spawns the worker Command, threading the config (classpath, version, source roots,
      * options), the aeron uris, and the shared medium's directory through `-D` system properties (read
      * by `WorkerConfig.fromEnv`/`CompilerWorker` on the worker side); the parent JVM's module-opener flags are
      * forwarded so the worker's presentation compiler reaches the same internal modules. The process
      * is spawned unscoped (released in `close`); a `CommandException` launch failure surfaces as
      * CompilerWorkerSpawnException.
      */
    private[kyo] def spawnWorker(config: Compiler.Config, driver: MediaDriver, reqStreamId: Int, respStreamId: Int)(using
        Frame
    ): Process < (Sync & Abort[CompilerException]) =
        val targetClasspath =
            (config.classpath ++ config.toolchain.compilerClasspath).map(_.toString).mkString(Path.pathSeparator)
        // The current JVM's own java launcher, not whatever a bare "java" resolves to on PATH.
        val javaBin = Path(java.lang.System.getProperty("java.home"), "bin", "java").toString
        val args =
            Chunk(javaBin) ++
                moduleArgs ++
                Chunk(
                    s"-Dkyo.internal.WorkerFlags.reqStreamId=$reqStreamId",
                    s"-Dkyo.internal.WorkerFlags.respStreamId=$respStreamId",
                    s"-Dkyo.internal.WorkerFlags.aeronDir=${driver.aeronDirectoryName()}",
                    s"-Dkyo.internal.WorkerFlags.scalaVersion=${config.toolchain.scalaVersion}",
                    s"-Dkyo.internal.WorkerFlags.classpath=${config.classpath.map(_.toString).mkString(Path.pathSeparator)}",
                    s"-Dkyo.internal.WorkerFlags.sourceRoots=${config.sourceRoots.map(_.toString).mkString(Path.pathSeparator)}",
                    s"-Dkyo.internal.WorkerFlags.options=${config.scalacOptions.mkString(" ")}"
                ) ++
                Chunk("-cp", targetClasspath, "kyo.internal.CompilerWorker")
        // The worker is spawned unscoped: its lifetime is owned by this backend's `close` (and the
        // pool's close-on-evict finalizer), not by an enclosing scope.
        Abort.run[CommandException](Command(args*).inheritStderr.spawnUnscoped).map {
            case Result.Success(proc) => proc
            case Result.Failure(e)    => Abort.fail(CompilerWorkerSpawnException(config.toolchain.scalaVersion, e))
            case Result.Panic(t)      => Abort.fail(CompilerWorkerSpawnException(config.toolchain.scalaVersion, t))
        }
    end spawnWorker

    /** The parent JVM's module-system flags (`--add-opens`/`--add-exports`/`--add-modules`/
      * `--enable-native-access`), forwarded to the worker so its presentation compiler reaches the same
      * internal modules the host opened.
      */
    private def moduleArgs: Chunk[String] =
        import scala.jdk.CollectionConverters.*
        Chunk.from(
            java.lang.management.ManagementFactory.getRuntimeMXBean.getInputArguments.asScala
                .filter(a =>
                    a.startsWith("--add-opens") || a.startsWith("--add-exports") ||
                        a.startsWith("--add-modules") || a.startsWith("--enable-native-access")
                )
                .toSeq
        )
    end moduleArgs

    /** Connects an aeron client to the pool's shared driver directory; closed in `close`. */
    private def aeronClient(driver: MediaDriver)(using Frame): Aeron < Sync =
        Sync.defer(Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))

    /** Wires the Exchange over the two aeron streams against the captured client: `send` publishes an
      * `Envelope.Req` via `Topic.publish[Envelope]`, `receive` is the reply `Topic.stream[Envelope]`,
      * `encode` stamps the id into an `Envelope.Req`, and `decode` routes an `Envelope.Resp` by id.
      * Built unscoped; the Exchange and its reader fiber are closed by `SpawnBackend.close`.
      */
    private def connect(aeron: Aeron, reqStreamId: Int, respStreamId: Int)(using
        Frame
    ): Exchange[Request, Response, Nothing, TransportError] < Sync =
        Exchange.initUnscoped[Request, Response, Envelope, Nothing, TransportError](
            encode = (id, req) => Envelope.Req(id, req),
            send = frame => sendFrame(aeron, reqStreamId, frame),
            receive = replyStream(aeron, respStreamId),
            decode = frame => decodeFrame(frame)
        )

    /** Publishes one `Envelope` to the request stream against the captured client, the `Topic` effect
      * discharged locally so the row is `Async & Abort[TransportError]`; a `Closed`/`Backpressured`
      * transport break maps to one typed `TransportError`.
      */
    private def sendFrame(aeron: Aeron, reqStreamId: Int, frame: Envelope)(using Frame): Unit < (Async & Abort[TransportError]) =
        transportErrors(Topic.run(aeron)(Topic.publish[Envelope](
            "aeron:ipc",
            Topic.defaultRetrySchedule,
            Present(reqStreamId)
        )(Stream.init(Chunk(frame)))))

    /** The reply stream against the captured client, the `Topic` effect discharged so the row is
      * `Async & Abort[TransportError]` as Exchange's `receive` requires.
      */
    private def replyStream(aeron: Aeron, respStreamId: Int)(using Frame): Stream[Envelope, Async & Abort[TransportError]] =
        Stream(transportErrors(Topic.run(aeron)(Topic.stream[Envelope](
            "aeron:ipc",
            Topic.defaultRetrySchedule,
            Present(respStreamId)
        ).emit)))

    /** Classifies one inbound `Envelope`: an `Envelope.Resp(id, response)` is a solicited response
      * routed by id; any other frame is ignored (the reader fiber never parks and never throws).
      */
    private def decodeFrame(frame: Envelope)(using Frame): Exchange.Message[Int, Response, Nothing] < Sync =
        Sync.defer {
            frame match
                case Envelope.Resp(id, response) => Exchange.Message.Response(id, response)
                case _                           => Exchange.Message.Skip
        }

    /** Maps a `Topic` transport break (`Closed` or `Backpressured`) onto the Exchange's
      * `TransportError`, so a broken session fails every pending op with one typed error. An
      * unexpected panic is not a transport break, so it re-raises unchanged and its live `Throwable`
      * (stack included) reaches the op's `CompilerTransportException` rather than being flattened to a
      * message.
      */
    private def transportErrors[A, S](v: A < (S & Abort[Closed | Topic.Backpressured]))(using Frame): A < (S & Abort[TransportError]) =
        Abort.run[Closed | Topic.Backpressured](v).map {
            case Result.Success(value)  => value
            case Result.Failure(closed) => Abort.fail(TransportError(closed.toString))
            case Result.Panic(err)      => Abort.panic(err)
        }
end SpawnBackend
