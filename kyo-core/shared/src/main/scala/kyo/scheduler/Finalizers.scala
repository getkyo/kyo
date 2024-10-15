package kyo.scheduler

import java.util.ArrayDeque
import kyo.Maybe
import kyo.discard
import org.jctools.queues.MpmcArrayQueue
import scala.annotation.tailrec

private[kyo] opaque type Finalizers = Finalizers.Absent.type | (() => Unit) | ArrayDeque[() => Unit]

private[kyo] object Finalizers:
    case object Absent derives CanEqual

    val empty: Finalizers = Absent

    private val bufferCache = new MpmcArrayQueue[ArrayDeque[() => Unit]](1024)

    private def buffer(): ArrayDeque[() => Unit] =
        Maybe(bufferCache.poll()).getOrElse(new ArrayDeque())

    extension (e: Finalizers)

        /** Adds a finalizer function. */
        def add(f: () => Unit): Finalizers =
            (e: @unchecked) match
                case e if e.equals(Absent) || e.equals(f) => f
                case f0: (() => Unit) @unchecked =>
                    val b = buffer()
                    b.add(f0)
                    b.add(f)
                    b
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    arr.add(f)
                    arr

        /** Removes a finalizer function by its object identity. */
        def remove(f: () => Unit): Finalizers =
            (e: @unchecked) match
                case e if e.equals(Absent) => e
                case e if e.equals(f)      => Absent
                case f: (() => Unit) @unchecked =>
                    f
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    @tailrec def loop(): Unit =
                        if arr.remove(f) then loop()
                    loop()
                    arr

        def run(): Unit =
            (e: @unchecked) match
                case e if e.equals(Absent) =>
                case f: (() => Unit) @unchecked =>
                    f()
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    @tailrec def loop(): Unit =
                        arr.poll() match
                            case null =>
                            case f =>
                                f()
                                loop()
                    loop()
                    discard(bufferCache.offer(arr))

        def size(): Int =
            (e: @unchecked) match
                case e if e.equals(Absent) => 0
                case f: (() => Unit) @unchecked =>
                    1
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    arr.size()
    end extension
end Finalizers
