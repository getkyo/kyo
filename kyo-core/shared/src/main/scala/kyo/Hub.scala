package kyo

import Hub.*
import java.util.concurrent.CopyOnWriteArraySet

class Hub[A] private[kyo] (
    ch: Channel[A],
    fiber: Fiber[Nothing, Unit],
    listeners: CopyOnWriteArraySet[Channel[A]]
)(using initFrame: Frame):

    def size(using Frame): Int < IO = ch.size

    def offer(v: A)(using Frame): Boolean < IO = ch.offer(v)

    def offerUnit(v: A)(using Frame): Unit < IO = ch.offerUnit(v)

    def isEmpty(using Frame): Boolean < IO = ch.isEmpty

    def isFull(using Frame): Boolean < IO = ch.isFull

    def putFiber(v: A)(using Frame): Fiber[Nothing, Unit] < IO = ch.putFiber(v)

    def put(v: A)(using Frame): Unit < Async = ch.put(v)

    def isClosed(using Frame): Boolean < IO = ch.isClosed

    def close(using frame: Frame): Maybe[Seq[A]] < IO =
        fiber.interruptUnit(Result.Panic(Closed("Hub", initFrame, frame))).andThen {
            ch.close.map { r =>
                IO {
                    val array = listeners.toArray()
                    listeners.removeIf(_ => true)
                    Loop.indexed { idx =>
                        if idx == array.length then Loop.done
                        else
                            array(idx).asInstanceOf[Channel[A]].close
                                .map(_ => Loop.continue)
                    }.andThen(r)
                }
            }
        }

    def listen(using Frame): Listener[A] < IO =
        listen(0)

    def listen(bufferSize: Int)(using frame: Frame): Listener[A] < IO =
        def closed = IO(throw Closed("Hub", initFrame, frame))
        isClosed.map {
            case true => closed
            case false =>
                Channel.init[A](bufferSize).map { child =>
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
                                new Listener[A](this, child)
                        }
                    }
                }
        }
    end listen

    private[kyo] def remove(child: Channel[A])(using Frame): Unit < IO =
        IO {
            listeners.remove(child)
            ()
        }
end Hub

object Hub:

    def init[A](capacity: Int)(using Frame): Hub[A] < IO =
        Channel.init[A](capacity).map { ch =>
            IO {
                val listeners = new CopyOnWriteArraySet[Channel[A]]
                Async.run {
                    Loop.foreach {
                        ch.take.map { v =>
                            IO {
                                val puts =
                                    listeners.toArray
                                        .toList.asInstanceOf[List[Channel[A]]]
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

    class Listener[A] private[kyo] (hub: Hub[A], child: Channel[A]):

        def size(using Frame): Int < IO = child.size

        def isEmpty(using Frame): Boolean < IO = child.isEmpty

        def isFull(using Frame): Boolean < IO = child.isFull

        def poll(using Frame): Maybe[A] < IO = child.poll

        def takeFiber(using Frame): Fiber[Nothing, A] < IO = child.takeFiber

        def take(using Frame): A < Async = child.take

        def isClosed(using Frame): Boolean < IO = child.isClosed

        def close(using Frame): Maybe[Seq[A]] < IO =
            hub.remove(child).andThen(child.close)

    end Listener
end Hub
