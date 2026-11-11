package kyo.internal

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kyo.*

/** A command whose group is owned by an isolated supervisor. The pid is diagnostic data; every signal and release uses the owned channel. */
final private[kyo] class ProcessSupervisor private (
    processId: Int,
    launch: ProcessLaunch,
    control: ProcessControl,
    val streams: ProcessStreams,
    cleanupStarted: AtomicBoolean,
    inputFeed: AtomicReference[ProcessSupervisor.InputFeed],
    cleanup: Promise.Unsafe[Unit, Any]
)(using Frame) extends Process.Unsafe:

    import ProcessSupervisor.InputFeed

    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Any] = control.waitFor

    def waitFor(timeout: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[ExitCode], Any] =
        Fiber.Unsafe.init {
            Clock.let(Clock.live) {
                Abort.run[Timeout](Async.timeout(timeout)(control.waitFor.safe.get)).map {
                    case Result.Success(code) => Present(code)
                    case Result.Failure(_)    => Absent
                    case Result.Panic(error)  => Abort.panic(error)
                }
            }
        }

    def exitCode()(using AllowUnsafe): Maybe[ExitCode]                               = control.waitFor.poll().map(_.getOrThrow.eval)
    def isAlive()(using AllowUnsafe): Boolean                                        = exitCode().isEmpty
    def pid()(using AllowUnsafe): Long                                               = processId.toLong
    def destroy()(using AllowUnsafe): Unit                                           = control.terminate()
    def destroyForcibly()(using AllowUnsafe): Unit                                   = control.kill()
    def stdoutJava(using AllowUnsafe): InputStream                                   = streams.stdout
    def stderrJava(using AllowUnsafe): InputStream                                   = streams.stderr
    def stdinJava(using AllowUnsafe): OutputStream                                   = streams.stdin
    override private[kyo] def stdoutStream(using Frame): Stream[Byte, Async & Scope] = streams.stdoutStream
    override private[kyo] def stderrStream(using Frame): Stream[Byte, Async & Scope] = streams.stderrStream

    override def destroyTree()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        if cleanupStarted.compareAndSet(false, true) then
            Fiber.Unsafe.init {
                ProcessStreams.finish(control.releaseGroup()) {
                    ProcessStreams.finish(streams.cancelOutputs()) {
                        ProcessStreams.finish(control.close())(launch.close())
                    }
                }
            }.onComplete(cleanup.completeDiscard)
        end if
        cleanup
    end destroyTree

    /** Releases the group and every descriptor, including native calls and Node callbacks already in flight. */
    override def close()(using Frame): Unit < Async =
        // Unsafe: feed interruption and group cleanup start inside the suspended close operation.
        ProcessStreams.finish(Sync.Unsafe.defer(stopInputFeeds())) {
            ProcessStreams.finish(Sync.Unsafe.defer(destroyTree().safe.get))(streams.close())
        }

    def registerInputFeed(stop: () => Unit)(using AllowUnsafe): Unit =
        if !inputFeed.compareAndSet(InputFeed.Empty, InputFeed.Running(stop)) then stop()

    override def stopInputFeeds()(using AllowUnsafe): Unit = inputFeed.getAndSet(InputFeed.Closed) match
        case InputFeed.Running(stop) => stop()
        case _                       => ()
end ProcessSupervisor

private[kyo] object ProcessSupervisor:
    private enum InputFeed:
        case Empty, Closed
        case Running(stop: () => Unit)

    def init(command: String, streamsMask: Int)(spawn: (String, String) => Result[CommandException, Process.Unsafe])(using
        Frame
    ): ProcessSupervisor < (Async & Scope & Abort[CommandException]) =
        ProcessLaunch.init(command).map { launch =>
            // Unsafe: native acquisition and stream transfers have owners before the process is delivered to its caller.
            Sync.Unsafe.defer {
                for
                    pid     <- launch.start(command, streamsMask)(spawn)
                    streams <- ProcessStreamsPlatform.init(launch, streamsMask)
                    control <- ProcessControlPlatform.init(launch)
                    process <- Scope.acquireRelease(Sync.Unsafe.defer {
                        new ProcessSupervisor(
                            pid,
                            launch,
                            control,
                            streams,
                            new AtomicBoolean(false),
                            new AtomicReference[InputFeed](InputFeed.Empty),
                            Promise.Unsafe.initUninterruptible[Unit, Any]()
                        )
                    })(_.close())
                yield process
            }
        }
end ProcessSupervisor
