package kyo.internal

import kyo.*

/** Shared I/O event loop. Platform implementations provide the polling mechanism. All connections on a Transport share one IoLoop instance.
  */
private[kyo] trait IoLoop:
    /** Suspend until fd is ready for reading. */
    def awaitReadable(fd: Int)(using Frame): Unit < Async

    /** Suspend until fd is ready for writing. */
    def awaitWritable(fd: Int)(using Frame): Unit < Async

    /** Start the poller fiber. */
    def start()(using Frame): Unit < Async

    /** Close/release the underlying polling resource. */
    def close(): Unit
end IoLoop

/** Group of IoLoops with round-robin connection assignment for multi-core scaling. */
private[kyo] class IoLoopGroup[L <: IoLoop](loops: Seq[L]):
    private val counter = new java.util.concurrent.atomic.AtomicInteger(0)

    def next(): L =
        val idx = Math.floorMod(counter.getAndIncrement(), loops.size)
        loops(idx)

    def startAll()(using Frame): Unit < Async =
        Kyo.foreach(loops)(_.start()).unit

    def closeAll(): Unit =
        loops.foreach(_.close())
end IoLoopGroup
