package kyo.interop

import java.util.concurrent.Flow.*
import kyo.*
import kyo.Emit.Ack
import kyo.Maybe.*
import kyo.Result.*
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec

object Adapters:

    private trait KyoSubscriber[A] extends Subscriber[A]:
        def isDone: Boolean < IO
        def await: Unit < (Async & Abort[SubscriberDone])
        def interrupt: Unit < IO
    end KyoSubscriber

    abstract class SubscriberDone
    case object SubscriberDone extends SubscriberDone

    private def makeSubscriber[A](
        bufferSize: Int
    )(
        using Frame
    ): (KyoSubscriber[A], Fiber.Promise[Nothing, (Subscription, Queue[A])]) < IO =
        IO.Unsafe.apply {
            val queue              = Queue.Unsafe.init[A](capacity = bufferSize, access = Access.MultiProducerMultiConsumer)
            val onSubscribePromise = Fiber.Promise.Unsafe.init[Nothing, (Subscription, Queue[A])]()
            val kyoSubscriber = new KyoSubscriber[A]:
                val isSubscribedOrInterrupted = AtomicBoolean.Unsafe.init(false)

                // volatile is enough, as these below can only be set by the producer thread
                @volatile
                private var _isDone: Maybe[Result[Throwable, Unit]] = Maybe.empty
                @volatile
                private var _waitPromise: Maybe[Fiber.Promise.Unsafe[SubscriberDone, Unit]] = Maybe.empty

                override def isDone: Boolean < IO = IO(_isDone.isDefined)

                override def interrupt: Unit < IO = isSubscribedOrInterrupted.safe.set(true)

                private def failDone(doneResult: Result[Throwable, Unit]): Nothing < Abort[SubscriberDone] =
                    doneResult.fold { error =>
                        Abort.panic(error.getFailure)
                    } { _ =>
                        Abort.fail(SubscriberDone)
                    }
                end failDone

                override def await: Unit < (Async & Abort[SubscriberDone]) =
                    _isDone.fold {
                        val awaitPromise = Fiber.Promise.Unsafe.init[SubscriberDone, Unit]()
                        _waitPromise = Maybe(awaitPromise)
                        queue.empty().fold { _ =>
                            // subscriber is not done, but queue is closed
                            _waitPromise = Maybe.empty
                            fail(new IllegalStateException("Queue is closed"))
                            await
                        } { isEmpty =>
                            if isEmpty then
                                _isDone.fold {
                                    // queue is empty, we parks until notified
                                    awaitPromise.safe.useResult {
                                        case Success(_)               => ()
                                        case Error(_: SubscriberDone) => Abort.fail(SubscriberDone)
                                        case Error(t: Throwable)      => Abort.panic(t)
                                    }
                                } { doneResult =>
                                    // The producer has cancelled or errored in the meantime
                                    _waitPromise = Maybe.empty
                                    failDone(doneResult)
                                }
                            else
                                // An element has arrived in the meantime, we do not need to start waiting
                                _waitPromise = Maybe.empty
                                IO.unit
                        }
                    } { doneResult =>
                        queue.empty().fold { _ =>
                            // queue is closed
                            failDone(doneResult)
                        } { isEmpty =>
                            if isEmpty then
                                failDone(doneResult)
                            else
                                // There are still elements in queue, we flush them first
                                IO.unit
                        }
                    }
                end await

                private def fail(t: Throwable): Unit =
                    _isDone = Maybe(Result.fail(t))
                    _waitPromise.foreach { p =>
                        p.completeDiscard(Result.panic(t))
                    }
                end fail

                private def failNullAndThrow(msg: String): Nothing =
                    val e = new NullPointerException(msg)
                    fail(e)
                    throw e
                end failNullAndThrow

                override def onSubscribe(s: Subscription): Unit =
                    if isNull(s) then
                        val e = new NullPointerException("s was null in onSubscribe")
                        onSubscribePromise.completeDiscard(Result.panic(e))
                        throw e
                    else
                        val shouldCancel = isSubscribedOrInterrupted.getAndSet(true)
                        if shouldCancel then
                            s.cancel()
                        else
                            onSubscribePromise.completeDiscard(Result.success(s -> queue.safe))
                        end if

                override def onNext(a: A): Unit =
                    if isNull(a) then
                        failNullAndThrow("a was null in onNext")
                    else
                        queue.offer(a) match
                            case Success(_) => _waitPromise.foreach { p =>
                                    p.completeDiscard(Result.success(()))
                                }
                            case _ => fail(new IllegalStateException("Queue is closed"))

                override def onError(t: Throwable): Unit =
                    if isNull(t) then
                        failNullAndThrow("t was null in onNext")
                    else
                        fail(t)

                override def onComplete(): Unit =
                    _isDone = Maybe(Result.success(()))
                    _waitPromise.foreach { p =>
                        p.completeDiscard(Result.fail(SubscriberDone))
                    }
                end onComplete

            (kyoSubscriber, onSubscribePromise.safe)
        }
    end makeSubscriber

    private def createEmit[A](
        subscription: Subscription,
        queue: Queue[A],
        doAwait: () => Unit < (Async & Abort[SubscriberDone]),
        checkDone: () => Boolean < IO
    )(
        using
        Tag[A],
        Frame
    ): Ack < (Emit[Chunk[A]] & Async) =
        def request(n: Long): Unit < IO = IO(subscription.request(n))

        def stop: Unit < IO = IO(subscription.cancel())

        def pullLoop(initialRequested: Long) =
            Loop[Long, Ack, Emit[Chunk[A]] & Async](initialRequested) { requested =>
                for
                    pollSize  <- IO(Math.min(requested, Int.MaxValue))
                    seqResult <- Abort.run[Closed].apply[Seq[A], (Emit[Chunk[A]] & Async), Nothing](queue.drain)
                    outcome <- seqResult match
                        case Success(seq) =>
                            if seq.isEmpty then
                                Abort.run[SubscriberDone](doAwait()).map {
                                    case Success(_) => Loop.continue[Long, Ack, Emit[Chunk[A]] & Async](requested)
                                    case _          => stop.map(_ => Loop.done[Long, Ack](Ack.Stop))
                                }
                            else
                                Emit.andMap[Chunk[A], (Ack, Long), Emit[Chunk[A]] & Async](Chunk.from(seq)) {
                                    case Ack.Stop =>
                                        for
                                            leftOver <- queue.close
                                            chunk = leftOver.map(Chunk.from(_)).getOrElse(Chunk.empty[A])
                                            _ <- stop
                                        yield (Ack.Stop, -1L)
                                    case continue: Ack =>
                                        checkDone().map { done =>
                                            if (requested == seq.size) && !done then
                                                request(queue.capacity).map(_ => continue -> initialRequested)
                                            else
                                                continue -> (requested - seq.size)
                                        }
                                }.map { (ack, nextRequested) =>
                                    ack match
                                        case Ack.Stop      => Loop.done[Long, Ack](ack)
                                        case continue: Ack => Loop.continue[Long, Ack, Emit[Chunk[A]] & Async](nextRequested)
                                }
                        case _ => stop.map(_ => Loop.done[Long, Ack](Ack.Stop))
                yield outcome
            }

        val initialRequested = queue.capacity.longValue
        request(initialRequested).map(_ => pullLoop(initialRequested))
    end createEmit

    def publisherToStream[A](
        publisher: => Publisher[A],
        bufferSize: Int = 16
    )(
        using
        Tag[A],
        Frame
    ): Stream[A, Async & Resource] =
        val emit: Ack < (Emit[Chunk[A]] & Async & Resource) =
            for
                (subscriber, onSubscribePromise) <- Resource.acquireRelease(makeSubscriber[A](bufferSize)) {
                    (subscriber, onSubscribePromise) =>
                        for
                            _ <- subscriber.interrupt
                            _ <- onSubscribePromise.interrupt
                        yield ()
                }
                _ = publisher.subscribe(subscriber)
                (subscription, queue) <- Resource.acquireRelease(onSubscribePromise.get) {
                    (subscription, queue) =>
                        for
                            _ <- IO(subscription.cancel())
                            _ <- queue.close
                        yield ()
                }
                ack <- createEmit[A](subscription, queue, () => subscriber.await, () => subscriber.isDone)
            yield ack
        Stream(emit)
    end publisherToStream

    private case class KyoSubscriptionState(
        requested: Long,
        maybeAwaitPromise: Maybe[(Int, Fiber.Promise[Unit, Int])]
    ) derives CanEqual

    private class KyoSubscription[A](subscriber: Subscriber[A])(using Frame, AllowUnsafe) extends Subscription:

        private val initialState = KyoSubscriptionState(0L, Absent)
        private val canceled     = KyoSubscriptionState(-1L, Absent)

        private def requested(n: Long) = KyoSubscriptionState(n, Absent)

        private def awaiting(n: Int, p: Fiber.Promise[Unit, Int]) = KyoSubscriptionState(0L, Present(n -> p))

        private val state: AtomicRef[KyoSubscriptionState] = AtomicRef.Unsafe.init(initialState).safe

        def offer(n: Int): Maybe[Int] < Async =
            state.get.map { initialState =>
                Loop[KyoSubscriptionState, Maybe[Int], Async](initialState) { curState =>
                    curState match
                        case `canceled` =>
                            state.cas(curState, canceled).map { success =>
                                if success then
                                    Loop.done[KyoSubscriptionState, Maybe[Int]](Maybe.empty[Int])
                                else
                                    state.get.map(nextState => Loop.continue[KyoSubscriptionState, Maybe[Int], Async](nextState))
                            }
                        case KyoSubscriptionState(0L, _) =>
                            for
                                promise <- Fiber.Promise.init[Unit, Int]
                                nextState = awaiting(n, promise)
                                success <- state.cas(curState, nextState)
                                outcome <- if success then
                                    promise.useResult {
                                        case Success(accepted) => Maybe(accepted)
                                        case _                 => Maybe.empty[Int]
                                    }.map(maybeAccepted => Loop.done[KyoSubscriptionState, Maybe[Int]](maybeAccepted))
                                else
                                    state.get.map(nextState => Loop.continue[KyoSubscriptionState, Maybe[Int], Async](nextState))
                            yield outcome
                        case KyoSubscriptionState(requestedCount, _) =>
                            val newRequestedCount = Math.max(requestedCount - n, 0L)
                            val accepted          = Math.min(requestedCount, n.toLong).toInt
                            val nextState         = requested(newRequestedCount)
                            state.cas(curState, nextState).map { success =>
                                if success then
                                    Loop.done[KyoSubscriptionState, Maybe[Int]](Maybe(accepted))
                                else
                                    state.get.map(nextState => Loop.continue[KyoSubscriptionState, Maybe[Int], Async](nextState))
                            }

                }
            }
        end offer

        def isCanceled: Boolean < IO = state.get.map(_.requested < 0)

        override def request(n: Long): Unit =
            if n <= 0 then subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
            val computation: Unit < IO = state.get.map { initialState =>
                Loop[KyoSubscriptionState, Unit, IO](initialState) { curState =>
                    curState match
                        case `canceled` =>
                            state.cas(curState, canceled).map { success =>
                                if success then
                                    Loop.done[KyoSubscriptionState, Unit](())
                                else
                                    state.get.map(nextState => Loop.continue[KyoSubscriptionState, Unit, IO](nextState))
                            }
                        case KyoSubscriptionState(requestedCount, Present(offered -> awaitPromise)) =>
                            val newRequestedCount = requestedCount + n
                            val accepted          = Math.min(offered.toLong, newRequestedCount)
                            val remaining         = newRequestedCount - accepted
                            val nextState         = requested(remaining)
                            state.cas(curState, nextState).map { success =>
                                if success then
                                    awaitPromise.completeDiscard(Success(accepted.toInt)).map(_ =>
                                        Loop.done[KyoSubscriptionState, Unit](())
                                    )
                                else
                                    state.get.map(nextState => Loop.continue[KyoSubscriptionState, Unit, IO](nextState))
                            }
                        case KyoSubscriptionState(requestedCount, _) if ((Long.MaxValue - n) > requestedCount) =>
                            val nextState = requested(requestedCount + n)
                            state.cas(curState, nextState).map { success =>
                                if success then
                                    Loop.done[KyoSubscriptionState, Unit](())
                                else
                                    state.get.map(nextState => Loop.continue[KyoSubscriptionState, Unit, IO](nextState))
                            }
                        case _ =>
                            val nextState = requested(Long.MaxValue)
                            state.cas(curState, nextState).map { success =>
                                if success then
                                    Loop.done[KyoSubscriptionState, Unit](())
                                else
                                    state.get.map(nextState => Loop.continue[KyoSubscriptionState, Unit, IO](nextState))
                            }
                }
            }
            IO.Unsafe.runLazy(computation).eval
        end request

        override def cancel(): Unit =
            val computation: Unit < IO =
                state.getAndSet(canceled).map { oldState =>
                    oldState.maybeAwaitPromise.fold {
                        IO.unit
                    } { (_, awaitPromise) =>
                        awaitPromise.completeDiscard(Result.fail(()))
                    }
                }
            IO.Unsafe.runLazy(computation).eval
        end cancel

    end KyoSubscription

    def streamToPublisher[A](
        stream: Stream[A, Async & Resource]
    )(
        using
        Tag[Emit[Chunk[A]]],
        Frame
    ): Publisher[A] < IO =

        def drainStreamComputation(
            emit: Ack < (Emit[Chunk[A]] & Async & Resource),
            subscription: KyoSubscription[? >: A],
            f: Chunk[A] => Unit < IO
        )(
            using
            tag: Tag[Emit[Chunk[A]]],
            frame: Frame
        ): Unit < (Async & Resource) =
            ArrowEffect.handle(tag, emit.unit)(
                [C] =>
                    (input, cont) =>
                        Loop[Chunk[A], Ack, Async & Resource](input) { curChunk =>
                            if curChunk.nonEmpty then
                                for
                                    maybeAccepted <- subscription.offer(curChunk.size)
                                    outcome <- maybeAccepted match
                                        case Present(accepted) =>
                                            f(curChunk.take(accepted)).andThen {
                                                Loop.continue[Chunk[A], Ack, Async & Resource](curChunk.drop(accepted))
                                            }
                                        case _ =>
                                            IO(Loop.done[Chunk[A], Ack](Ack.Stop))
                                yield outcome
                            else
                                Loop.done(Ack.Continue())
                        }.map(ack => cont(ack))
            )

        IO.Unsafe {
            new Publisher[A]:
                override def subscribe(subscriber: Subscriber[? >: A]): Unit =
                    if isNull(subscriber) then
                        throw new NullPointerException("Subscriber must not be null.")
                    else
                        val computation: Unit < (Async & Resource) =
                            for
                                subscription <- Resource.acquireRelease(new KyoSubscription(subscriber))(_.cancel())
                                _ = subscriber.onSubscribe(subscription)
                                _ <- drainStreamComputation(stream.emit, subscription, chunk => chunk.foreach(subscriber.onNext(_)))
                                _ <- subscription.isCanceled.map(isCanceled => if !isCanceled then subscriber.onComplete())
                            yield ()

                        discard(IO.Unsafe.run(Abort.run(Async.runAndBlock(Duration.Infinity)(Resource.run(computation)))).eval)
        }
    end streamToPublisher
end Adapters
