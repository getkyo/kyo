package kyo

import java.util.concurrent.CopyOnWriteArraySet

object Hubs:

    def init[T](capacity: Int)(using Frame): Hub[T] < IO =
        Channel.init[T](capacity).map { ch =>
            IO {
                val listeners = new CopyOnWriteArraySet[Channel[T]]
                Async.run {
                    Loop.foreach {
                        ch.take.map { v =>
                            IO {
                                val puts =
                                    listeners.toArray
                                        .toList.asInstanceOf[List[Channel[T]]]
                                        .map(child => Abort.run[Throwable](child.put(v)))
                                Async.parallel(puts).map(_ => Loop.continue)
                            }
                        }
                    }
                }.map { fiber =>
                    new Hub(ch, fiber, listeners)
                }
            }
        }

    class Listener[T] private[kyo] (hub: Hub[T], child: Channel[T]):

        def size(using Frame): Int < IO = child.size

        def isEmpty(using Frame): Boolean < IO = child.isEmpty

        def isFull(using Frame): Boolean < IO = child.isFull

        def poll(using Frame): Maybe[T] < IO = child.poll

        def takeFiber(using Frame): Fiber[Nothing, T] < IO = child.takeFiber

        def take(using Frame): T < Async = child.take

        def isClosed(using Frame): Boolean < IO = child.isClosed

        def close(using Frame): Maybe[Seq[T]] < IO =
            hub.remove(child).andThen(child.close)

    end Listener
end Hubs

import Hubs.*

class Hub[T] private[kyo] (
    ch: Channel[T],
    fiber: Fiber[Nothing, Unit],
    listeners: CopyOnWriteArraySet[Channel[T]]
)(using initFrame: Frame):

    def size(using Frame): Int < IO = ch.size

    def offer(v: T)(using Frame): Boolean < IO = ch.offer(v)

    def offerUnit(v: T)(using Frame): Unit < IO = ch.offerUnit(v)

    def isEmpty(using Frame): Boolean < IO = ch.isEmpty

    def isFull(using Frame): Boolean < IO = ch.isFull

    def putFiber(v: T)(using Frame): Fiber[Nothing, Unit] < IO = ch.putFiber(v)

    def put(v: T)(using Frame): Unit < Async = ch.put(v)

    def isClosed(using Frame): Boolean < IO = ch.isClosed

    def close(using frame: Frame): Maybe[Seq[T]] < IO =
        fiber.interruptUnit(Result.Panic(Closed("Hub", initFrame, frame))).andThen {
            ch.close.map { r =>
                IO {
                    val array = listeners.toArray()
                    listeners.removeIf(_ => true)
                    Loop.indexed { idx =>
                        if idx == array.length then Loop.done
                        else
                            array(idx).asInstanceOf[Channel[T]].close
                                .map(_ => Loop.continue)
                    }.andThen(r)
                }
            }
        }

    def listen(using Frame): Listener[T] < IO =
        listen(0)

    def listen(bufferSize: Int)(using frame: Frame): Listener[T] < IO =
        def closed = IO(throw Closed("Hub", initFrame, frame))
        isClosed.map {
            case true => closed
            case false =>
                Channel.init[T](bufferSize).map { child =>
                    IO {
                        listeners.add(child)
                        isClosed.map {
                            case true =>
                                // race condition
                                IO {
                                    listeners.remove(child)
                                    closed
                                }
                            case false =>
                                new Listener[T](this, child)
                        }
                    }
                }
        }
    end listen

    private[kyo] def remove(child: Channel[T])(using Frame): Unit < IO =
        IO {
            listeners.remove(child)
            ()
        }
end Hub
