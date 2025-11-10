package kyo.scheduler

import java.util.ArrayDeque
import kyo.Maybe
import kyo.Result.Error
import kyo.discard
import org.jctools.queues.MpmcArrayQueue
import scala.annotation.tailrec

private type Finalizer              = Maybe[Error[Any]] => Unit
private[kyo] opaque type Finalizers = Finalizers.Absent.type | Finalizer | ArrayDeque[Finalizer]

private[kyo] object Finalizers:
    case object Absent derives CanEqual

    val empty: Finalizers = Absent

    private val bufferCache = new MpmcArrayQueue[ArrayDeque[Finalizer]](1024)

    private def buffer(): ArrayDeque[Finalizer] =
        Maybe(bufferCache.poll()).getOrElse(new ArrayDeque())

    extension (e: Finalizers)

        def isEmpty: Boolean = e eq Absent

        /** Adds a finalizer function. */
        def add(f: Maybe[Error[Any]] => Unit): Finalizers =
            (e: @unchecked) match
                case e if e.isEmpty || e.eq(f) => f
                case f0: Finalizer @unchecked =>
                    val b = buffer()
                    b.add(f0)
                    b.add(f)
                    b
                case arr: ArrayDeque[Finalizer] @unchecked if !arr.contains(f) =>
                    arr.addLast(f)
                    arr
                case arr: ArrayDeque[Finalizer] @unchecked => arr

        /** Removes a finalizer function by its object identity. */
        def remove(f: Maybe[Error[Any]] => Unit): Finalizers =
            (e: @unchecked) match
                case e if e.isEmpty => e
                case e if e.eq(f)   => Absent
                case f: Finalizer @unchecked =>
                    f
                case arr: ArrayDeque[Finalizer] @unchecked =>
                    arr.removeFirstOccurrence(f) // functions will only be added once
                    arr

        def run(ex: Maybe[Error[Any]]): Unit =
            (e: @unchecked) match
                case e if e.isEmpty =>
                case f: (Maybe[Error[Any]] => Unit) @unchecked =>
                    f(ex)
                case arr: ArrayDeque[Finalizer] @unchecked =>
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
                case e if e.isEmpty => 0
                case f: Finalizer @unchecked =>
                    1
                case arr: ArrayDeque[Finalizer] @unchecked =>
                    arr.size()
    end extension
end Finalizers
