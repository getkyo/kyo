package kyo.scheduler

import java.util.ArrayDeque
import kyo.discard
import org.jctools.queues.MpmcArrayQueue
import scala.annotation.tailrec

private[kyo] opaque type Ensures = Ensures.Empty.type | (() => Unit) | ArrayDeque[() => Unit]

private[kyo] object Ensures:
    case object Empty

    val empty: Ensures = Empty

    private val bufferCache = new MpmcArrayQueue[ArrayDeque[() => Unit]](1000)
    private def buffer(): ArrayDeque[() => Unit] =
        val b = bufferCache.poll()
        if b == null then new ArrayDeque()
        else b
    end buffer

    extension (e: Ensures)
        def add(f: () => Unit): Ensures =
            e match
                case Empty => f
                case `f`   => f
                case f0: (() => Unit) @unchecked =>
                    val b = buffer()
                    b.add(f0)
                    b.add(f)
                    b
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    arr.add(f)
                    arr

        def remove(f: () => Unit): Ensures =
            e match
                case Empty => e
                case `f`   => Empty
                case f: (() => Unit) @unchecked =>
                    f
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    @tailrec def loop(): Unit =
                        if arr.remove(f) then loop()
                    loop()
                    arr

        def finalize(): Unit =
            e match
                case Empty =>
                case f: (() => Unit) @unchecked =>
                    f()
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    while !arr.isEmpty() do
                        val f = arr.poll()
                        f()
                    discard(bufferCache.offer(arr))

        def size(): Int =
            e match
                case Empty => 0
                case f: (() => Unit) @unchecked =>
                    1
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    arr.size()
    end extension
end Ensures
