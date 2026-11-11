package kyo.internal

import java.io.InputStream
import java.io.OutputStream
import kyo.*

/** Independently owned pipeline stages connected by OS pipes. Their stderr shares one pipe, so no intermediate stage can block on an
  * unread private stderr stream. Each stage retains its own environment and working directory.
  */
final private[kyo] class ProcessPipeline(stages: Chunk[ProcessSupervisor], errors: ProcessNodeInputStream)
    extends Process.Unsafe:

    private val first                                                                            = stages.head
    private val last                                                                             = stages.last
    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Any]                         = last.waitFor()
    def waitFor(timeout: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[ExitCode], Any] = last.waitFor(timeout)
    def exitCode()(using AllowUnsafe): Maybe[ExitCode]                                           = last.exitCode()
    def isAlive()(using AllowUnsafe): Boolean                                                    = last.isAlive()
    def pid()(using AllowUnsafe): Long                                                           = last.pid()
    def destroy()(using AllowUnsafe): Unit                                                       = stages.foreach(_.destroy())
    def destroyForcibly()(using AllowUnsafe): Unit                                               = stages.foreach(_.destroyForcibly())
    def stdoutJava(using AllowUnsafe): InputStream                                               = last.stdoutJava
    def stderrJava(using AllowUnsafe): InputStream                                               = errors
    def stdinJava(using AllowUnsafe): OutputStream                                               = first.stdinJava
    override private[kyo] def stdoutStream(using Frame): Stream[Byte, Async & Scope]             = last.stdoutStream
    override private[kyo] def stderrStream(using Frame): Stream[Byte, Async & Scope]             = errors.stream
    override def stopInputFeeds()(using AllowUnsafe): Unit                                       = stages.foreach(_.stopInputFeeds())

    override def destroyTree()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] = Fiber.Unsafe.init {
        ProcessStreams.finish(releaseStages(process => Process.destroyTree(process.safe)))(Sync.defer(errors.cancelRead()))
    }

    override def close()(using Frame): Unit < Async =
        ProcessStreams.finish(releaseStages(_.close()))(errors.closeAndWait())

    private def releaseStages(release: ProcessSupervisor => Unit < Async)(using Frame): Unit < Async =
        val initial: Unit < Async = Kyo.unit
        stages.foldLeft(initial) { (previous, process) => ProcessStreams.finish(previous)(release(process)) }
end ProcessPipeline
