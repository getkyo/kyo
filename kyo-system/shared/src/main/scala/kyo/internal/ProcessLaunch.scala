package kyo.internal

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.ffi.*

/** Owns preparation, acquisition and the runtime-owned supervisor until its enclosing scope closes. A helper-exit watcher can wake a
  * failed acquisition, but cannot outlive the native context it uses. Transferred streams belong to scopes nested after this owner.
  */
final private[kyo] class ProcessLaunch private (
    val bindings: ProcessLaunchBindings,
    val handle: Ffi.Handle[ProcessLaunchHandle]
)(using Frame):

    private val helper      = new AtomicReference[Maybe[Process.Unsafe]](Absent)
    private val watcher     = new AtomicReference[Maybe[Fiber.Unsafe[Unit, Any]]](Absent)
    private val acquisition = new AtomicReference[Maybe[Fiber.Unsafe[Int, Any]]](Absent)
    // 0: prepared, 1: start owns the context, 2: cleanup owns it. Close and start compete at this one transition.
    private val state = new AtomicInteger(0)
    // Unsafe: construction runs inside init's suspended acquisition, before the scope registers this owner.
    private val completion =
        import AllowUnsafe.embrace.danger
        Promise.Unsafe.initUninterruptible[Unit, Any]()
    private val startFinished =
        import AllowUnsafe.embrace.danger
        Promise.Unsafe.initUninterruptible[Unit, Any]()

    /** Starts the runtime-owned helper, then acquires the command's descriptors. The helper builder preserves the command's environment
      * and inherited/file stdio. Native calls run on an owned producer even on JVM/Native, where a downcall can park its carrier.
      */
    def start(command: String, streamsMask: Int)(spawn: (String, String) => Result[CommandException, Process.Unsafe])(using
        AllowUnsafe
    ): Int < (Async & Abort[CommandException]) =
        Sync.acquireReleaseWith(Sync.defer {
            if !state.compareAndSet(0, 1) then throw new IllegalStateException("Process launch already started or closed")
        })((_, _) => Sync.defer(startFinished.completeDiscard(Result.succeed(())))) { _ =>
            Sync.defer {
                val result = spawn(bindings.executable(handle).value, bindings.endpoint(handle).value).map { process =>
                    // Result.map has no safepoint between acquiring the child and recording its owner.
                    helper.set(Present(process))
                    process
                }
                Abort.get(result).map { process =>
                    Sync.defer {
                        watcher.set(Present(Fiber.Unsafe.init {
                            ProcessStreams.finish(process.waitFor().uninterruptible().safe.get.unit)(Sync.defer {
                                ProcessLaunch.check(bindings.helper_exited(handle))
                            })
                        }))
                        val producer = Fiber.Unsafe.init(Sync.defer(bindings.await(handle, streamsMask).uninterruptible().safe.get))
                        acquisition.set(Present(producer))
                        producer
                    }.map { producer =>
                        producer.uninterruptible().safe.get.map { result =>
                            if result > 0 then result
                            else
                                val failure = -result match
                                    case 2     => ProgramNotFoundException(command)
                                    case 13    => PermissionDeniedException(command)
                                    case error => ProcessLaunchException(command, error)
                                Abort.fail(failure)
                            end if
                        }
                    }
                }
            }
        }
    end start

    /** Joins cleanup exactly once, including concurrent explicit close and the enclosing scope's finalizer. */
    def close(): Unit < Async =
        Sync.Unsafe.defer {
            val previous = state.getAndSet(2)
            if previous != 2 then
                Fiber.Unsafe.init(closeOnce(previous == 1)).onComplete(completion.completeDiscard)
            completion.safe.get
        }

    private def closeOnce(waitForStart: Boolean)(using AllowUnsafe): Unit < Async =
        // No native allocation is freed until both acquisition and the helper-exit watcher have returned. Cleanup errors do not skip
        // later stages and are re-raised after those stages; this owner does not convert failed cleanup into a successful result.
        import ProcessStreams.finish
        finish {
            finish {
                finish {
                    finish(Sync.defer(ProcessLaunch.check(bindings.cancel(handle)))) {
                        (if waitForStart then startFinished.safe.get else Kyo.unit).andThen {
                            acquisition.get().fold(Kyo.unit)(_.uninterruptible().safe.get.unit)
                        }
                    }
                }(Sync.defer(ProcessLaunch.check(bindings.disconnect(handle))))
            } {
                finish {
                    watcher.get() match
                        case Present(producer) => producer.uninterruptible().safe.get
                        case Absent            => helper.get().fold(Kyo.unit)(_.waitFor().uninterruptible().safe.get.unit)
                }(Sync.defer(helper.get().foreach(_.closeStreams())))
            }
        }(bindings.release(handle).uninterruptible().safe.get.map(ProcessLaunch.check))
    end closeOnce
end ProcessLaunch

private[kyo] object ProcessLaunch:

    def init(command: String)(using Frame): ProcessLaunch < (Async & Scope & Abort[CommandException]) =
        Sync.Unsafe.defer(init(command, Ffi.load[ProcessLaunchBindings]))

    def init(command: String, bindings: ProcessLaunchBindings)(using Frame): ProcessLaunch < (Async & Scope & Abort[CommandException]) =
        // Unsafe: the scope owns the producer before any await; every observer is masked so interruption cannot settle a producer early.
        Sync.Unsafe.defer {
            val owner = new AtomicReference[Maybe[ProcessLaunch]](Absent)
            Scope.acquireRelease(Sync.defer {
                val error = Buffer.alloc[Int](1)
                try (error, bindings.init("", error))
                catch
                    case failure: Throwable =>
                        error.close()
                        throw failure
                end try
            }) { (error, producer) =>
                producer.uninterruptible().safe.get.map {
                    case Present(handle) => owner.get() match
                            case Present(launch) => launch.close()
                            case Absent          => bindings.release(handle).uninterruptible().safe.get.map(check)
                    case Absent => Kyo.unit
                }.handle(Sync.ensure(error.close()))
            }.map { (error, producer) =>
                producer.uninterruptible().safe.get.ensureMap { result =>
                    // The producer has returned. Diagnostics do not belong to the handed-over process; an unscoped caller drops the
                    // acquisition finalizer. Abandoned acquisition still joins the producer before closing this buffer above.
                    try
                        result match
                            case Present(handle) =>
                                val launch = new ProcessLaunch(bindings, handle)
                                owner.set(Present(launch))
                                launch
                            case Absent => Abort.fail(ProcessLaunchException(command, error.get(0)))
                    finally error.close()
                }
            }
        }
    end init

    // Unsafe: an unexpected cleanup errno is a failed resource release, never a successful close.
    private def check(result: Int): Unit =
        if result < 0 then throw new IOException(s"Process launch cleanup failed: errno ${-result}")
end ProcessLaunch
