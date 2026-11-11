package kyo.internal

import java.io.InputStream
import java.io.OutputStream
import kyo.*

private[kyo] object ProcessStreamsPlatform:
    def init(launch: ProcessLaunch, mask: Int)(using Frame): ProcessStreams < (Sync & Scope) =
        def pipe(slot: Int): Maybe[ProcessPipe] < (Sync & Scope) =
            if (mask & (1 << slot)) == 0 then Absent
            else ProcessPipe.acquire(launch, slot).map(Present(_))
        for
            input  <- pipe(0)
            output <- pipe(1)
            errors <- pipe(2)
        yield new ProcessStreams:
            val stdin: OutputStream = input.fold(ProcessStreams.closedOutput)(_.outputStream)
            val stdout: InputStream = output.fold(ProcessStreams.emptyInput)(_.inputStream)
            val stderr: InputStream = errors.fold(ProcessStreams.emptyInput)(_.inputStream)
            def write(bytes: Array[Byte], offset: Int, length: Int)(using Frame): Unit < Async =
                Sync.defer(stdin.write(bytes, offset, length))
            def finishInput()(using Frame): Unit < Async =
                input.fold(Kyo.unit)(_.closeAndWait())
            def cancelOutputs()(using Frame): Unit < Sync = Sync.Unsafe.defer {
                try output.foreach(_.cancelRead())
                finally errors.foreach(_.cancelRead())
            }
            def close()(using Frame): Unit < Async =
                ProcessStreams.finish(input.fold(Kyo.unit)(_.closeAndWait())) {
                    ProcessStreams.finish(output.fold(Kyo.unit)(_.closeAndWait()))(errors.fold(Kyo.unit)(_.closeAndWait()))
                }
        end for
    end init
end ProcessStreamsPlatform
