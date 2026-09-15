package kyo.interop.flow

import StreamSubscription.*
import java.util.concurrent.Flow.*
import kyo.*
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec

final private[kyo] class StreamSubscription[V, S](
    private val stream: Stream[V, S & Sync],
    subscriber: Subscriber[? >: V]
)(
    using
    Isolate[S, Sync, Any],
    AllowUnsafe,
    Frame
) extends Subscription:

    private val requestChannel = Channel.Unsafe.init[Long](Int.MaxValue)

    // How far this subscription has got, and therefore who owes the subscriber its one terminal event.
    //
    // A subscriber is owed exactly one terminal event from the moment onSubscribe reaches it, but for most of this object's life the only
    // thing able to send one is the drain fiber's completion handler, which does not exist yet. Every stage below names what is true about
    // the subscriber at that point, so `stop` can be total: whichever stage a teardown finds, it either delivers the terminal event itself
    // or hands the duty to the one party that can. A single compare-and-set decides that, which is what keeps the delivery exactly-once
    // when a teardown and the setup run concurrently.
    private val lifecycle = new java.util.concurrent.atomic.AtomicReference[Lifecycle](Lifecycle.Idle)

    // Every route to an ending passes through here, so the subscriber sees exactly one however many routes race. A subscriber that
    // cancelled claims it too: silence is its ending, and no later stop may break it.
    private val terminalSent = new java.util.concurrent.atomic.AtomicBoolean(false)

    private def deliverTerminal(signal: => Unit): Unit =
        if terminalSent.compareAndSet(false, true) then signal

    override def request(n: Long): Unit =
        if n <= 0 then subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
        discard(requestChannel.offer(n))
    end request

    override def cancel(): Unit =
        discard(requestChannel.close())
    end cancel

    /** Stop this subscription because the publisher is going away, not because the subscriber asked.
      *
      * Total: it is correct at every stage of the lifecycle, including the stages where no drain fiber exists and therefore nothing else
      * could ever signal the subscriber. That totality is the point. Ending the drain is not enough on its own, because closing a request
      * channel nobody is polling delivers nothing, so each stage below either sends the terminal event here or leaves it to the single
      * party that is already committed to sending one.
      */
    private[interop] def stop(): Unit =
        @tailrec def stopLoop(): Unit =
            lifecycle.get() match
                case _: Lifecycle.Stopped => ()
                case prior =>
                    if !lifecycle.compareAndSet(prior, Lifecycle.Stopped(prior)) then stopLoop()
                    else
                        discard(requestChannel.close())
                        prior match
                            case Lifecycle.Idle =>
                                // Never told about its subscription, so it is owed onSubscribe before anything else. No other thread has
                                // claimed the right to signal it, which is what makes sending both events from here safe.
                                subscriber.onSubscribe(Lifecycle.inertSubscription)
                                deliverTerminal(subscriber.onComplete())
                            case Lifecycle.Subscribing =>
                                // onSubscribe is in flight on another thread. Sending a terminal event now would interleave with it, so
                                // the duty passes to that thread, which delivers as soon as its own onSubscribe call returns.
                                ()
                            case Lifecycle.Active =>
                                // onSubscribe has been delivered and no drain fiber exists or ever will, so nothing else can signal this
                                // subscriber and nothing can be mid-onNext.
                                deliverTerminal(subscriber.onError(new PublisherStopped))
                            case Lifecycle.Consuming(gate) =>
                                // The drain owns the signalling, so ending its gate is what delivers, ordered after any onNext still in
                                // flight. Interrupting the gate works whether or not it has been linked to a drain fiber yet, which is
                                // what stops a teardown from racing the linking step.
                                Sync.Unsafe.evalOrThrow(gate.interruptDiscard(Result.Panic(Interrupted(summon[Frame]))))
                            case _: Lifecycle.Stopped => ()
                        end match
                    end if
        stopLoop()
    end stop

    private[interop] def subscribe(using Frame): Unit < Sync =
        Sync.defer {
            if lifecycle.compareAndSet(Lifecycle.Idle, Lifecycle.Subscribing) then
                subscriber.onSubscribe(this)
                if !lifecycle.compareAndSet(Lifecycle.Subscribing, Lifecycle.Active) then
                    // A teardown landed while the call above was in flight and deliberately sent nothing, to keep the subscriber's signals
                    // serialised. This thread is the one that knows onSubscribe has returned, so it owes the terminal event.
                    deliverTerminal(subscriber.onError(new PublisherStopped))
                end if
        }

    private[interop] def poll(using Tag[Poll[Chunk[V]]], Frame): StreamComplete < (Async & Poll[Chunk[V]] & Abort[StreamCanceled]) =
        def loopPoll(requesting: Long): (Chunk[V] | StreamComplete) < (Sync & Poll[Chunk[V]] & Abort[StreamCanceled]) =
            Loop[Long, Chunk[V] | StreamComplete, Sync & Poll[Chunk[V]] & Abort[StreamCanceled]](requesting): requesting =>
                // Stop draining the stream as soon as the subscription is cancelled. cancel() closes requestChannel; the outer
                // take-loop already observes that, but a single loopPoll call holding a large outstanding demand would otherwise
                // keep pulling and emitting the whole stream (calling onNext past cancellation) and leave this fiber running long
                // after the subscriber is gone (a leaked, effectively-uninterruptible consumer). Signal StreamCanceled (not
                // StreamComplete) so cancellation does not deliver a terminal onComplete, matching the outer take-loop's path.
                if requestChannel.closed() then Abort.fail(StreamCanceled)
                else
                    Poll.andMap:
                        case Present(values) =>
                            if values.size <= requesting then
                                Sync.defer(values.foreach(subscriber.onNext(_)))
                                    .andThen(Loop.continue(requesting - values.size))
                            else
                                Sync.defer(values.take(requesting.intValue).foreach(subscriber.onNext(_)))
                                    .andThen(Loop.done(values.drop(requesting.intValue)))
                        case Absent =>
                            Sync.defer(Loop.done(StreamComplete))

        Loop[Chunk[V], StreamComplete, Async & Poll[Chunk[V]] & Abort[StreamCanceled]](Chunk.empty[V]): leftOver =>
            Abort.run[Closed](requestChannel.safe.take).map:
                case Result.Success(requesting) =>
                    if requesting <= leftOver.size then
                        Sync.defer(leftOver.take(requesting.intValue).foreach(subscriber.onNext(_)))
                            .andThen(Loop.continue(leftOver.drop(requesting.intValue)))
                    else
                        Sync.defer(leftOver.foreach(subscriber.onNext(_)))
                            .andThen(loopPoll(requesting - leftOver.size))
                            .map {
                                case nextLeftOver: Chunk[V] => Loop.continue(nextLeftOver)
                                case _: StreamComplete      => Loop.done(StreamComplete)
                            }
                case result => Abort.get(result.mapFailure(_ => StreamCanceled)).andThen(Loop.done(StreamComplete))
    end poll

    private[interop] def consume(
        using
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]],
        Frame
    ): Fiber[StreamComplete, Abort[StreamCanceled]] < (Sync & S) =
        // The drain is reached through a promise whose completion handler is registered BEFORE anything can complete it. A fiber is
        // interruptible between effect steps, so a handler registered after the drain is spawned can be skipped, and a skipped
        // handler leaves nothing that would ever signal this subscriber. Nothing below has to run for the ending to be delivered.
        Fiber.Promise.init[StreamComplete, Abort[StreamCanceled]].map { gate =>
            gate.onComplete { result =>
                Sync.defer {
                    result.map(_.eval) match
                        case Result.Success(_)   => deliverTerminal(subscriber.onComplete())
                        case Result.Panic(error) => deliverTerminal(subscriber.onError(error))
                        case _                   =>
                            // Silence is correct only when the SUBSCRIBER cancelled: it asked not to be signalled again. One the
                            // publisher stopped is owed an event. Claiming the latch either way is what makes a cancelling
                            // subscriber's silence permanent against a later stop.
                            lifecycle.get() match
                                case _: Lifecycle.Stopped => deliverTerminal(subscriber.onError(new PublisherStopped))
                                case _                    => deliverTerminal(())
                    end match
                }
            }.andThen {
                Sync.Unsafe.defer(lifecycle.compareAndSet(Lifecycle.Active, Lifecycle.Consuming(gate))).map { claimed =>
                    if !claimed then
                        // Stopped before a drain could exist, so the stop has already delivered. Ending the gate leaves no caller
                        // awaiting a promise nothing would complete; the handler's delivery is absorbed by the latch.
                        gate.completeDiscard(Result.fail(StreamCanceled)).andThen(gate)
                    else
                        // Claiming the lifecycle before spawning is what keeps ownership settled: demand can already be queued, so a
                        // drain started here could signal onNext immediately.
                        Fiber.initUnscoped[StreamCanceled, StreamComplete, S, Any](Poll.runEmit(stream.emit)(poll).map(_._2))
                            .map(fiber => gate.becomeDiscard(fiber).andThen(gate))
                }
            }
        }
    end consume

end StreamSubscription

object StreamSubscription:

    type StreamComplete = StreamComplete.type
    case object StreamComplete
    type StreamCanceled = StreamCanceled.type
    case object StreamCanceled

    /** Delivered to a subscriber whose publisher went away while it was still subscribed.
      *
      * The subscriber did not cancel, so the specification's allowance for going silent after a cancellation does not apply to it: it is
      * owed a terminal event, and this is that event.
      */
    final class PublisherStopped extends RuntimeException("the publisher was torn down while this subscription was active")

    /** How far a subscription has got, which is what decides who owes its subscriber the one terminal event it is entitled to. */
    private[flow] enum Lifecycle derives CanEqual:
        /** Constructed. The subscriber has not been told anything and is owed onSubscribe before any terminal event. */
        case Idle

        /** onSubscribe is in flight. No other thread may signal, or the subscriber's signals would overlap. */
        case Subscribing

        /** onSubscribe has returned and no drain fiber exists, so nothing can be mid-onNext. */
        case Active

        /** A drain owns the signalling, reached through `gate`. The gate carries the completion handler from before any drain existed,
          * so ending it delivers whether or not a drain fiber has been linked to it yet.
          */
        case Consuming(gate: Fiber.Promise[StreamComplete, Abort[StreamCanceled]])

        /** Terminal and absorbing. `prior` is the stage the stop found, which is what selected who delivered. */
        case Stopped(prior: Lifecycle)
    end Lifecycle

    private[flow] object Lifecycle:
        /** Handed to a subscriber that is being told about a subscription only so that its terminal event is legal, never to be driven. */
        val inertSubscription: Subscription =
            new Subscription:
                def request(n: Long): Unit = ()
                def cancel(): Unit         = ()
    end Lifecycle

    def subscribe[V, S](
        using Isolate[S, Sync, Any]
    )(
        stream: Stream[V, S & Sync],
        subscriber: Subscriber[? >: V]
    )(
        using
        Frame,
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]]
    ): StreamSubscription[V, S] < (Sync & S & Scope) =
        for
            subscription <- Sync.Unsafe.defer(new StreamSubscription[V, S](stream, subscriber))
            // Registered before onSubscribe reaches the subscriber, so the scope covers every point at which the subscriber could start
            // waiting for a terminal event. `stop` ends the drain as well, which is why consuming needs no release of its own.
            _ <- Scope.ensure(Sync.defer(subscription.stop()))
            _ <- subscription.subscribe
            _ <- subscription.consume
        yield subscription

    object Unsafe:
        def subscribe[V, S](
            using Isolate[S, Sync, Any]
        )(
            stream: Stream[V, S & Sync],
            subscriber: Subscriber[? >: V]
        )(
            subscribeCallback: (Fiber[StreamComplete, Abort[StreamCanceled]] < (Sync & S)) => Unit
        )(
            using
            AllowUnsafe,
            Frame,
            Tag[Emit[Chunk[V]]],
            Tag[Poll[Chunk[V]]]
        ): StreamSubscription[V, S] =
            val subscription = new StreamSubscription[V, S](stream, subscriber)
            subscribeCallback(subscription.subscribe.andThen(subscription.consume))
            subscription
        end subscribe
    end Unsafe

end StreamSubscription
