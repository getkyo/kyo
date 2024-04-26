package kyo

import java.util.concurrent.CopyOnWriteArraySet

object Hubs:

    private[kyo] val closed = IOs.fail("Hub closed!")

    def init[T: Flat](capacity: Int): Hub[T] < IOs =
        Channels.init[T](capacity).map { ch =>
            IOs {
                val listeners = new CopyOnWriteArraySet[Channel[T]]
                Fibers.init {
                    Loops.foreach {
                        ch.take.map { v =>
                            IOs {
                                val puts =
                                    listeners.toArray
                                        .toList.asInstanceOf[List[Channel[T]]]
                                        .map(child => IOs.attempt(child.put(v)))
                                Fibers.parallel(puts).map(_ => Loops.continueUnit)
                            }
                        }
                    }
                }.map { fiber =>
                    new Hub(ch, fiber, listeners)
                }
            }
        }

    class Listener[T] private[kyo] (hub: Hub[T], child: Channel[T]):

        def size: Int < IOs = child.size

        def isEmpty: Boolean < IOs = child.isEmpty

        def isFull: Boolean < IOs = child.isFull

        def poll: Option[T] < IOs = child.poll

        def takeFiber: Fiber[T] < IOs = child.takeFiber

        def take: T < Fibers = child.take

        def isClosed: Boolean < IOs = child.isClosed

        def close: Option[Seq[T]] < IOs =
            hub.remove(child).andThen(child.close)

    end Listener
end Hubs

import Hubs.*

class Hub[T: Flat] private[kyo] (
    ch: Channel[T],
    fiber: Fiber[Unit],
    listeners: CopyOnWriteArraySet[Channel[T]]
):

    def size: Int < IOs = ch.size

    def offer(v: T): Boolean < IOs = ch.offer(v)

    def offerUnit(v: T): Unit < IOs = ch.offerUnit(v)

    def isEmpty: Boolean < IOs = ch.isEmpty

    def isFull: Boolean < IOs = ch.isFull

    def putFiber(v: T): Fiber[Unit] < IOs = ch.putFiber(v)

    def put(v: T): Unit < Fibers = ch.put(v)

    def isClosed: Boolean < IOs = ch.isClosed

    def close: Option[Seq[T]] < IOs =
        fiber.interrupt.map { _ =>
            ch.close.map { r =>
                IOs {
                    val array = listeners.toArray()
                    listeners.removeIf(_ => true)
                    Loops.indexed { idx =>
                        if idx == array.length then Loops.doneUnit
                        else
                            IOs.attempt(array(idx).asInstanceOf[Channel[T]].close)
                                .map(_ => Loops.continueUnit)
                    }.andThen(r)
                }
            }
        }

    def listen: Listener[T] < IOs =
        listen(0)

    def listen(bufferSize: Int): Listener[T] < IOs =
        isClosed.map {
            case true => closed
            case false =>
                Channels.init[T](bufferSize).map { child =>
                    IOs {
                        listeners.add(child)
                        isClosed.map {
                            case true =>
                                // race condition
                                IOs {
                                    listeners.remove(child)
                                    closed
                                }
                            case false =>
                                new Listener[T](this, child)
                        }
                    }
                }
        }

    private[kyo] def remove(child: Channel[T]): Unit < IOs =
        IOs {
            listeners.remove(child)
            ()
        }
end Hub
