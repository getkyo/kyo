package kyo.internal

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import kyo.*

/** Standard descriptors transferred by the supervisor. Cancelling output retains only its buffered prefix and never signals a writer. */
private[kyo] trait ProcessStreams:
    def stdin: OutputStream
    def stdout: InputStream
    def stderr: InputStream
    def stdoutStream(using Frame): Stream[Byte, Async & Scope] = StreamCoreExtensions.streamFromJavaInputStream(stdout)
    def stderrStream(using Frame): Stream[Byte, Async & Scope] = StreamCoreExtensions.streamFromJavaInputStream(stderr)
    def write(bytes: Array[Byte], offset: Int, length: Int)(using Frame): Unit < Async
    def finishInput()(using Frame): Unit < Async
    def cancelOutputs()(using Frame): Unit < Sync
    def close()(using Frame): Unit < Async
end ProcessStreams

private[kyo] object ProcessStreams:

    /** A reader waits for readiness before returning bytes. The stream owns and joins its reader's close, including interruption. */
    def fromReader(read: (Array[Byte], Int, Int) => Int < Async, close: () => Unit < Async)(using
        Frame
    ): Stream[Byte, Async & Scope] = Stream {
        Scope.acquireRelease(Sync.defer(new Array[Byte](8192)))(_ => close()).map { buffer =>
            Loop.foreach {
                read(buffer, 0, buffer.length).map { count =>
                    if count < 0 then Loop.done
                    else if count == 0 then Abort.panic(new IOException("Process output read completed without data or EOF"))
                    else Emit.valueWith(Chunk.fromNoCopy(java.util.Arrays.copyOf(buffer, count)))(Loop.continue)
                }
            }
        }
    }
    val emptyInput: InputStream = new InputStream:
        def read(): Int = -1

    val closedOutput: OutputStream = new OutputStream:
        def write(value: Int): Unit = throw new IOException("Process stdin is not piped")

    /** Preserves the first cleanup failure after attempting and joining both releases. */
    def finish(body: => Unit < Async)(cleanup: => Unit < Async)(using Frame): Unit < Async =
        Abort.run[Throwable](body).map { result =>
            Abort.run[Throwable](cleanup).map { released =>
                result.getOrThrow
                released.getOrThrow
            }
        }
end ProcessStreams
