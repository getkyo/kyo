package kyo

import org.jctools.queues.MessagePassingQueue.Consumer
import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import scala.annotation.tailrec

opaque type Channel[A] = Channel.Unsafe[A]

object Channel:

    extension [A](self: Channel[A])
        def capacity: Int                                 = self.capacity
        def size(using Frame): Int < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.size()))

        def offer(value: A)(using Frame): Boolean < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.offer(value)))
        def poll(using Frame): Maybe[A] < (Abort[Closed] & IO)           = IO.Unsafe(Abort.get(self.poll()))

        def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
            IO.Unsafe {
                self.offer(value).fold(Abort.error) {
                    case true  => ()
                    case false => self.putFiber(value).safe.get
                }
            }
        def take(using Frame): A < (Abort[Closed] & Async) =
            IO.Unsafe {
                self.poll().fold(Abort.error) {
                    case Present(value) => value
                    case Absent         => self.takeFiber().safe.get
                }
            }

        def putFiber(value: A)(using Frame): Fiber[Closed, Unit] < IO = IO.Unsafe(self.putFiber(value).safe)
        def takeFiber(using Frame): Fiber[Closed, A] < IO             = IO.Unsafe(self.takeFiber().safe)

        def drain(using Frame): Seq[A] < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.drain()))
        def close(using Frame): Seq[A] < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.close()))

        def empty(using Frame): Boolean < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.empty()))
        def full(using Frame): Boolean < (Abort[Closed] & IO)  = IO.Unsafe(Abort.get(self.full()))
        def closed(using Frame): Boolean < IO                  = IO.Unsafe(self.closed())

        def unsafe: Unsafe[A] = self
    end extension

    def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Channel[A] < IO =
        IO.Unsafe(Unsafe.init(capacity, access))

    abstract class Unsafe[A]:
        def capacity: Int
        def size()(using AllowUnsafe): Result[Closed, Int]

        def offer(value: A)(using AllowUnsafe): Result[Closed, Boolean]
        def poll()(using AllowUnsafe): Result[Closed, Maybe[A]]

        def putFiber(value: A)(using AllowUnsafe): Fiber.Unsafe[Closed, Unit]
        def takeFiber()(using AllowUnsafe): Fiber.Unsafe[Closed, A]

        def drain()(using AllowUnsafe): Result[Closed, Seq[A]]
        def close()(using Frame, AllowUnsafe): Result[Closed, Seq[A]]

        def empty()(using AllowUnsafe): Result[Closed, Boolean]
        def full()(using AllowUnsafe): Result[Closed, Boolean]
        def closed()(using AllowUnsafe): Boolean

        def safe: Channel[A] = this
    end Unsafe

    object Unsafe:
        def init[A](
            _capacity: Int,
            access: Access = Access.MultiProducerMultiConsumer
        )(using initFrame: Frame, allow: AllowUnsafe): Unsafe[A] =
            new Unsafe[A]:
                import AllowUnsafe.embrace.danger

                val queue = Queue.Unsafe.init[A](_capacity, access)
                val takes = new MpmcUnboundedXaddArrayQueue[Promise.Unsafe[Closed, A]](8)
                val puts  = new MpmcUnboundedXaddArrayQueue[(A, Promise.Unsafe[Closed, Unit])](8)

                def capacity = _capacity

                def size()(using AllowUnsafe) = queue.size()

                def offer(value: A)(using AllowUnsafe) =
                    val result = queue.offer(value)
                    if result.contains(true) then flush()
                    result
                end offer

                def poll()(using AllowUnsafe) =
                    val result = queue.poll()
                    if result.exists(_.nonEmpty) then flush()
                    result
                end poll

                def putFiber(value: A)(using AllowUnsafe): Fiber.Unsafe[Closed, Unit] =
                    val promise = Promise.Unsafe.init[Closed, Unit]()
                    val tuple   = (value, promise)
                    puts.add(tuple)
                    flush()
                    promise
                end putFiber

                def takeFiber()(using AllowUnsafe): Fiber.Unsafe[Closed, A] =
                    val promise = Promise.Unsafe.init[Closed, A]()
                    takes.add(promise)
                    flush()
                    promise
                end takeFiber

                def drain()(using AllowUnsafe) =
                    val result = queue.drain()
                    if result.exists(_.nonEmpty) then flush()
                    result
                end drain

                def close()(using frame: Frame, allow: AllowUnsafe) =
                    queue.close().map { backlog =>
                        flush()
                        backlog
                    }

                def empty()(using AllowUnsafe)  = queue.empty()
                def full()(using AllowUnsafe)   = queue.full()
                def closed()(using AllowUnsafe) = queue.closed()

                @tailrec private def flush(): Unit =
                    import AllowUnsafe.embrace.danger

                    val queueClosed = queue.closed()
                    val queueSize   = queue.size().getOrElse(0)
                    val takesEmpty  = takes.isEmpty()
                    val putsEmpty   = puts.isEmpty()

                    if queueClosed && (!takesEmpty || !putsEmpty) then
                        val fail = queue.size() // just grab the failed Result
                        takes.drain(_.completeDiscard(fail))
                        puts.drain(_._2.completeDiscard(fail.unit))
                        flush()
                    else if queueSize > 0 && !takesEmpty then
                        Maybe(takes.poll()).foreach { promise =>
                            queue.poll() match
                                case Result.Success(Present(value)) =>
                                    if !promise.complete(Result.success(value)) && !queue.offer(value).contains(true) then
                                        val placeholder = Promise.Unsafe.init[Nothing, Unit]()
                                        discard(puts.add((value, placeholder)))
                                case _ =>
                                    discard(takes.add(promise))
                        }
                        flush()
                    else if queueSize < capacity && !putsEmpty then
                        Maybe(puts.poll()).foreach { tuple =>
                            val (value, promise) = tuple
                            if queue.offer(value).contains(true) then
                                discard(promise.complete(Result.unit))
                            else
                                discard(puts.add(tuple))
                            end if
                        }
                        flush()
                    else if queueSize == 0 && !putsEmpty && !takesEmpty then
                        Maybe(puts.poll()).foreach { putTuple =>
                            val (value, putPromise) = putTuple
                            Maybe(takes.poll()) match
                                case Present(takePromise) if takePromise.complete(Result.success(value)) =>
                                    putPromise.completeDiscard(Result.unit)
                                case _ =>
                                    discard(puts.add(putTuple))
                            end match
                        }
                        flush()
                    end if
                end flush
            end new
        end init
    end Unsafe
end Channel
