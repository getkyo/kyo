package kyo.internal

import io.aeron.driver.MediaDriver
import kyo.*

/** The internal implementation of the locked `Compiler.Pool` trait.
  *
  * Owns the live pc instances in a close-on-evict `Cache[Config, Promise[Instance]]`, the global
  * compile-cap `Meter`, and the one shared embedded `MediaDriver` every Spawn worker session connects
  * to. A `compiler(config)` is a `Sync` resolve returning a thin view; the instance is created
  * single-flight on the first op via the cache's own promise registry, with the backend chosen by
  * `effectiveIsolate`. Each op runs under the per-instance mutex held inside the global semaphore.
  * An evicted instance is closed by the cache finalizer and re-created on the next op.
  */
final private[kyo] class CompilerPool(
    settings: Compiler.Pool.Settings,
    instances: Cache[Compiler.Config, Promise[Instance, Abort[CompilerError]]],
    globalSemaphore: Meter,
    driver: MediaDriver,
    // A pool-owned monotonic counter: each created worker gets a unique stream-id base, so distinct
    // configs land on provably-distinct aeron streams (a 32-bit config.hashCode could collide and
    // silently mis-route one worker's reply to another host's request on the shared medium).
    streamIdCounter: AtomicInt,
    stuckTimeout: Duration = CompilerPool.defaultStuckTimeout
) extends Compiler.Pool:

    def compiler(config: Compiler.Config)(using Frame): Compiler < Sync =
        Sync.defer(view(config))

    /** The thin per-config view: every op resolves-or-creates the instance single-flight, then runs
      * under the meters. The view holds no instance (creation is on the first op).
      */
    private def view(config: Compiler.Config): Compiler =
        new Compiler:
            def compile(uri: Compiler.Uri, text: String)(using Frame) =
                run(config, Request.Compile(uri, text)).map {
                    case Response.Diagnostics(value) => value
                    case other                       => unexpected(other)
                }

            def completions(uri: Compiler.Uri, text: String, offset: Int)(using Frame) =
                run(config, Request.Completions(uri, text, offset)).map {
                    case Response.Completions(value) => value
                    case other                       => unexpected(other)
                }

            def hover(uri: Compiler.Uri, text: String, offset: Int)(using Frame) =
                run(config, Request.Hover(uri, text, offset)).map {
                    case Response.Hover(value) => value
                    case other                 => unexpected(other)
                }

            def signatureHelp(uri: Compiler.Uri, text: String, offset: Int)(using Frame) =
                run(config, Request.SignatureHelp(uri, text, offset)).map {
                    case Response.Signature(value) => value
                    case other                     => unexpected(other)
                }

            def symbol(uri: Compiler.Uri, text: String, offset: Int)(using Frame) =
                run(config, Request.Symbol(uri, text, offset)).map {
                    case Response.Symbol(value) => value
                    case other                  => unexpected(other)
                }

            def didClose(uri: Compiler.Uri)(using Frame) =
                run(config, Request.DidClose(uri)).map {
                    case Response.Closed => ()
                    case other           => unexpected(other)
                }

    /** Resolves-or-creates the instance single-flight, then runs the op: the per-instance mutex is
      * held inside the global semaphore; a `Response.Failed` surfaces as a typed Abort.
      *
      * The op is bounded by `stuckTimeout`, the leg-3 reclaim of the cancellation ladder: a normal pc
      * op is sub-second, so a run that outlasts `stuckTimeout` is a genuinely-stuck instance whose
      * cooperative cancel was defeated. On timeout the inner fiber is interrupted (releasing the
      * per-instance mutex and the global permit), the instance is removed from the cache so its
      * close-on-evict finalizer reclaims the worker (Spawn: `destroyForcibly`; Local: a best-effort
      * `shutdown`, the documented `isolate=false` cost), and the op fails `Fatal`. The next op for this
      * config recreates a fresh instance via the single-flight create. A normal fiber interrupt aborts
      * the timeout rather than firing it, so the leg-1 cleanup still runs and no reclaim happens.
      */
    private def run(config: Compiler.Config, request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
        resolve(config).map { instance =>
            val metered =
                instance.mutex.run(globalSemaphore.run(instance.backend.run(request)))
                    .handle(Abort.run[Closed])
                    .map {
                        case Result.Success(Response.Failed(error)) => Abort.fail(error)
                        case Result.Success(response)               => response
                        case Result.Failure(_)                      => Abort.fail(CompilerError.Fatal("pool meter closed"))
                        case Result.Panic(err)                      => Abort.fail(CompilerError.Fatal(err.getMessage))
                    }
            Async.timeout(stuckTimeout)(metered)
                .handle(Abort.run[Timeout])
                .map {
                    case Result.Success(response) => response
                    case Result.Failure(_) =>
                        instances.remove(config).andThen(
                            Abort.fail(CompilerError.Fatal("compiler unresponsive, worker reclaimed"))
                        )
                    case Result.Panic(err) => Abort.fail(CompilerError.Fatal(err.getMessage))
                }
        }

    /** Single-flight resolve: the live instance if present, else create one under the per-config
      * create mutex (concurrent first-ops serialize; the winner creates and inserts).
      */
    private def resolve(config: Compiler.Config)(using Frame): Instance < (Async & Abort[CompilerError]) =
        Sync.Unsafe.defer {
            // The cache itself is the single-flight registry: the winner inserts a fresh Promise as the
            // token and creates the instance; concurrent first-ops find that Promise and await it, so
            // exactly one instance is created with no separate lock map. A create failure or interrupt
            // removes the Promise so the next op retries rather than waiting on a poisoned entry.
            val promise = Promise.Unsafe.init[Instance, Abort[CompilerError]]().safe
            val cached  = instances.unsafe.getOrElse(config, promise)
            if (cached.asInstanceOf[AnyRef]) eq (promise.asInstanceOf[AnyRef]) then
                Sync.Unsafe.ensure {
                    if promise.unsafe.interrupt() then instances.unsafe.remove(config)
                } {
                    Abort.runWith[CompilerError](create(config)) {
                        case Result.Success(instance) =>
                            Sync.Unsafe.defer {
                                promise.unsafe.completeDiscard(Result.succeed(instance))
                                instance
                            }
                        case error: Result.Error[CompilerError] @unchecked =>
                            Sync.Unsafe.defer {
                                instances.unsafe.remove(config)
                                promise.unsafe.completeDiscard(error)
                                Abort.get(error)
                            }
                    }
                }
            else
                cached.get
            end if
        }

    /** Creates the per-config instance: chooses the backend by effectiveIsolate, builds it, and
      * wraps it with a fresh per-instance serialization mutex.
      */
    private def create(config: Compiler.Config)(using Frame): Instance < (Async & Abort[CompilerError]) =
        backendFor(config).map { backend =>
            Meter.initMutexUnscoped.map(mutex => Instance(backend, mutex))
        }

    /** The backend-selection rule: a forked worker whenever `isolate` resolves true OR the
      * toolchain version differs from `ownVersion`; the in-process backend only on a
      * version-matched opt-out.
      */
    private def backendFor(config: Compiler.Config)(using Frame): Backend < (Async & Abort[CompilerError]) =
        val isolate          = config.isolate.getOrElse(settings.isolate)
        val effectiveIsolate = isolate || config.toolchain.scalaVersion != CompilerPool.ownVersion
        if effectiveIsolate then streamIdCounter.getAndIncrement.map(base => SpawnBackend.init(config, driver, base))
        else LocalBackend.init(config)
    end backendFor

    private def unexpected(response: Response)(using Frame): Nothing < Abort[CompilerError] =
        Abort.fail(CompilerError.Fatal(s"unexpected worker response: $response"))
end CompilerPool

private[kyo] object CompilerPool:
    /** kyo's own Scala version; the same-version fast path for `effectiveIsolate`. A toolchain
      * pinned to a different version forces a forked worker regardless of `isolate`.
      */
    val ownVersion: String = "3.8.4"

    /** The leg-3 stuck-op backstop: how long a single op may run before the pool treats the instance
      * as genuinely stuck and reclaims it. Far beyond normal pc latency (a real op is sub-second; a
      * stuck one never returns), so it never fires on a healthy instance. Internal only: it is not on
      * the public `Pool.Settings` surface. `init` passes this default; tests pass a short value.
      */
    val defaultStuckTimeout: Duration = 60.seconds

    /** Opens a pool: allocates the global compile-cap Meter, the one shared embedded MediaDriver,
      * and the close-on-evict instance cache (the finalizer closes every evicted instance and
      * force-kills every evicted worker). Scope-managed.
      */
    def init(settings: Compiler.Pool.Settings)(using Frame): Compiler.Pool < (Sync & Scope) =
        Meter.initSemaphore(settings.maxConcurrentCompiles).map { globalSemaphore =>
            Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                settings.maxLiveCompilers,
                expireAfterAccess = settings.idleEviction
            )(promise =>
                Abort.run[CompilerError](promise.get).map {
                    case Result.Success(instance) => instance.close
                    case _                        => ()
                }
            ).map { instances =>
                Scope.acquireRelease(Sync.defer(MediaDriver.launchEmbedded()))(d => Sync.defer(d.close())).map { driver =>
                    AtomicInt.init(0).map { streamIdCounter =>
                        new CompilerPool(settings, instances, globalSemaphore, driver, streamIdCounter): Compiler.Pool
                    }
                }
            }
        }
end CompilerPool
