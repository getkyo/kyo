package kyo.internal

import kyo.*

/** Shared I/O event loop. Platform implementations provide the polling mechanism. All connections on a Transport share one IoLoop instance.
  */
private[kyo] trait IoLoop:
    /** Suspend until fd is ready for reading. */
    def awaitReadable(fd: Int)(using Frame): Unit < Async

    /** Suspend until fd is ready for writing. */
    def awaitWritable(fd: Int)(using Frame): Unit < Async
end IoLoop
