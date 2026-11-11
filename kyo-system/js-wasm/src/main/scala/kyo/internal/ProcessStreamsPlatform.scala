package kyo.internal

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import kyo.*
import kyo.ffi.*

private[kyo] object ProcessStreamsPlatform:
    def init(launch: ProcessLaunch, mask: Int)(using Frame): ProcessStreams < (Sync & Scope) =
        def output(slot: Int): Maybe[ProcessNodeInputStream] < (Sync & Scope) =
            if (mask & (1 << slot)) == 0 then Absent
            else
                Scope.acquireRelease(Sync.Unsafe.defer {
                    val bindings   = Ffi.load[ProcessPipeBindings]
                    val descriptor = launch.bindings.take_stream(launch.handle, slot)
                    if descriptor < 0 then throw new IOException(s"Process output transfer failed: errno ${-descriptor}")
                    ProcessNodeInputStream.init(descriptor, bindings)
                })(_.closeAndWait()).map(Present(_))
        val input: Maybe[ProcessNodeOutputStream] < (Sync & Scope) =
            if (mask & 1) == 0 then Absent
            else
                Scope.acquireRelease(Sync.Unsafe.defer {
                    val descriptor = launch.bindings.take_stream(launch.handle, 0)
                    if descriptor < 0 then throw new IOException(s"Process stdin transfer failed: errno ${-descriptor}")
                    ProcessNodeOutputStream.init(descriptor)
                })(_.closeAndWait()).map(Present(_))
        for
            in  <- input
            out <- output(1)
            err <- output(2)
        yield new ProcessStreams:
            val stdin: OutputStream                                             = in.fold(ProcessStreams.closedOutput)(identity)
            val stdout: InputStream                                             = out.fold(ProcessStreams.emptyInput)(identity)
            val stderr: InputStream                                             = err.fold(ProcessStreams.emptyInput)(identity)
            override def stdoutStream(using Frame): Stream[Byte, Async & Scope] = out.fold(Stream.empty[Byte])(_.stream)
            override def stderrStream(using Frame): Stream[Byte, Async & Scope] = err.fold(Stream.empty[Byte])(_.stream)
            def write(bytes: Array[Byte], offset: Int, length: Int)(using Frame): Unit < Async =
                in match
                    case Present(stream) => stream.writeAsync(bytes, offset, length)
                    case Absent          => Sync.defer(stdin.write(bytes, offset, length))
            def finishInput()(using Frame): Unit < Async  = in.fold(Kyo.unit)(_.finish())
            def cancelOutputs()(using Frame): Unit < Sync = Sync.Unsafe.defer {
                try out.foreach(_.cancelRead())
                finally err.foreach(_.cancelRead())
            }
            def close()(using Frame): Unit < Async =
                ProcessStreams.finish(in.fold(Kyo.unit)(_.closeAndWait())) {
                    ProcessStreams.finish(out.fold(Kyo.unit)(_.closeAndWait()))(err.fold(Kyo.unit)(_.closeAndWait()))
                }
        end for
    end init
end ProcessStreamsPlatform
