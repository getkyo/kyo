package kyo.scheduler

import java.util.ArrayDeque
import kyo.Maybe
import kyo.Result.Error
import kyo.discard
import org.jctools.queues.MpmcArrayQueue
import scala.annotation.tailrec

private[kyo] opaque type Finalizers = Finalizers.Absent.type | (Maybe[Error[Any]] => Unit) | ArrayDeque[Maybe[Error[Any]] => Unit]

private[kyo] object Finalizers:
    case object Absent derives CanEqual

    val empty: Finalizers = Absent

    private val bufferCache = new MpmcArrayQueue[ArrayDeque[Maybe[Error[Any]] => Unit]](1024)

    private def buffer(): ArrayDeque[Maybe[Error[Any]] => Unit] =
        Maybe(bufferCache.poll()).getOrElse(new ArrayDeque())

    extension (e: Finalizers)

        def isEmpty: Boolean = e eq Absent

        /** Adds a finalizer function. */
        def add(f: Maybe[Error[Any]] => Unit): Finalizers =
            (e: @unchecked) match
                case e if e.isEmpty || e.equals(f) => f
                case f0: (Maybe[Error[Any]] => Unit) @unchecked =>
                    val b = buffer()
                    b.add(f0)
                    b.add(f)
                    b
                case arr: ArrayDeque[Maybe[Error[Any]] => Unit] @unchecked =>
                    arr.add(f)
                    arr

        /** Removes a finalizer function by its object identity. */
        def remove(f: Maybe[Error[Any]] => Unit): Finalizers =
            (e: @unchecked) match
                case e if e.equals(Absent) => e
                case e if e.equals(f)      => Absent
                case f: (Maybe[Error[Any]] => Unit) @unchecked =>
                    f
                case arr: ArrayDeque[Maybe[Error[Any]] => Unit] @unchecked =>
                    @tailrec def loop(): Unit =
                        if arr.remove(f) then loop()
                    loop()
                    arr

        def run(ex: Maybe[Error[Any]]): Unit =
            (e: @unchecked) match
                case e if e.equals(Absent) =>
                case f: (Maybe[Error[Any]] => Unit) @unchecked =>
                    f(ex)
                case arr: ArrayDeque[Maybe[Error[Any]] => Unit] @unchecked =>
                    @tailrec def loop(): Unit =
                        arr.poll() match
                            case null =>
                            case f =>
                                f(ex)
                                loop()
                    loop()
                    discard(bufferCache.offer(arr))

        def size(): Int =
            (e: @unchecked) match
                case e if e.equals(Absent) => 0
                case f: (Maybe[Error[Any]] => Unit) @unchecked =>
                    1
                case arr: ArrayDeque[Maybe[Error[Any]] => Unit] @unchecked =>
                    arr.size()
    end extension
end Finalizers
