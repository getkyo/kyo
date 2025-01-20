package kyo.interop.flow

import StreamSubscriber.*
import java.util.concurrent.Flow.*
import kyo.*
import scala.annotation.tailrec

final private[kyo] class StreamSubscriber[V](
    bufferSize: Int,
    strategy: EmitStrategy
)(
    using AllowUnsafe
) extends Subscriber[V]:

    private enum UpstreamState derives CanEqual:
        case Uninitialized                                                               extends UpstreamState
        case WaitForRequest(subscription: Subscription, items: Chunk[V], remaining: Int) extends UpstreamState
        case Finished(reason: Maybe[Throwable], leftOver: Chunk[V])                      extends UpstreamState
    end UpstreamState

    private val state = AtomicRef.Unsafe.init(
        UpstreamState.Uninitialized -> Maybe.empty[Fiber.Promise.Unsafe[Nothing, Unit]]
    )

    private inline def throwIfNull[A](b: A): Unit = if isNull(b) then throw new NullPointerException()

    override def onSubscribe(subscription: Subscription): Unit =
        throwIfNull(subscription)
        @tailrec def handleSubscribe(): Unit =
            val curState = state.get()
            curState match
                case (UpstreamState.Uninitialized, maybePromise) =>
                    val nextState = UpstreamState.WaitForRequest(subscription, Chunk.empty, 0) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        maybePromise.foreach(_.completeDiscard(Result.success(())))
                    else
                        handleSubscribe()
                    end if
                case other =>
                    if state.compareAndSet(curState, other) then
                        subscription.cancel()
                    else
                        handleSubscribe()
            end match
        end handleSubscribe
        handleSubscribe()
    end onSubscribe

    override def onNext(item: V): Unit =
        throwIfNull(item)
        @tailrec def handleNext(): Unit =
            val curState = state.get()
            curState match
                case (UpstreamState.WaitForRequest(subscription, items, remaining), maybePromise) =>
                    if (strategy == EmitStrategy.Eager) || (strategy == EmitStrategy.Buffer && remaining == 1) then
                        val nextState = UpstreamState.WaitForRequest(subscription, items.append(item), remaining - 1) -> Absent
                        if state.compareAndSet(curState, nextState) then
                            maybePromise.foreach(_.completeDiscard(Result.success(())))
                        else
                            handleNext()
                        end if
                    else
                        val nextState = UpstreamState.WaitForRequest(subscription, items.append(item), remaining - 1) -> maybePromise
                        if !state.compareAndSet(curState, nextState) then handleNext()
                case other =>
                    if !state.compareAndSet(curState, other) then handleNext()
            end match
        end handleNext
        handleNext()
    end onNext

    override def onError(throwable: Throwable): Unit =
        throwIfNull(throwable)
        @tailrec def handleError(): Unit =
            val curState = state.get()
            curState match
                case (UpstreamState.WaitForRequest(_, items, _), maybePromise) =>
                    val nextState = UpstreamState.Finished(Maybe(throwable), items) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        maybePromise.foreach(_.completeDiscard(Result.success(())))
                    else
                        handleError()
                    end if
                case other =>
                    if !state.compareAndSet(curState, other) then handleError()
            end match
        end handleError
        handleError()
    end onError

    override def onComplete(): Unit =
        @tailrec def handleComplete(): Unit =
            val curState = state.get()
            curState match
                case (UpstreamState.WaitForRequest(_, items, _), maybePromise) =>
                    val nextState = UpstreamState.Finished(Absent, items) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        maybePromise.foreach(_.completeDiscard(Result.success(())))
                    else
                        handleComplete()
                    end if
                case other =>
                    if !state.compareAndSet(curState, other) then handleComplete()
            end match
        end handleComplete
        handleComplete()
    end onComplete

    private[interop] def await(using Frame): Boolean < Async =
        @tailrec def handleAwait(): Boolean < Async =
            val curState = state.get()
            curState match
                case (UpstreamState.Uninitialized, Absent) =>
                    val promise   = Fiber.Promise.Unsafe.init[Nothing, Unit]()
                    val nextState = UpstreamState.Uninitialized -> Present(promise)
                    if state.compareAndSet(curState, nextState) then
                        promise.safe.use(_ => false)
                    else
                        handleAwait()
                    end if
                case s @ (UpstreamState.Uninitialized, Present(promise)) =>
                    if state.compareAndSet(curState, s) then
                        promise.safe.use(_ => false)
                    else
                        handleAwait()
                case s @ (UpstreamState.WaitForRequest(subscription, items, remaining), Absent) =>
                    if items.isEmpty then
                        if remaining == 0 then
                            val nextState = UpstreamState.WaitForRequest(subscription, Chunk.empty[V], 0) -> Absent
                            if state.compareAndSet(curState, nextState) then
                                IO(true)
                            else
                                handleAwait()
                            end if
                        else
                            val promise   = Fiber.Promise.Unsafe.init[Nothing, Unit]()
                            val nextState = UpstreamState.WaitForRequest(subscription, Chunk.empty[V], remaining) -> Present(promise)
                            if state.compareAndSet(curState, nextState) then
                                promise.safe.use(_ => false)
                            else
                                handleAwait()
                            end if
                    else
                        if state.compareAndSet(curState, s) then
                            IO(false)
                        else
                            handleAwait()
                case other =>
                    if state.compareAndSet(curState, other) then
                        IO(false)
                    else
                        handleAwait()
            end match
        end handleAwait
        IO(handleAwait())
    end await

    private[interop] def request(using Frame): Long < IO =
        @tailrec def handleRequest(): Long < IO =
            val curState = state.get()
            curState match
                case (UpstreamState.WaitForRequest(subscription, items, remaining), maybePromise) =>
                    val nextState = UpstreamState.WaitForRequest(subscription, items, remaining + bufferSize) -> maybePromise
                    if state.compareAndSet(curState, nextState) then
                        IO(subscription.request(bufferSize)).andThen(bufferSize.toLong)
                    else
                        handleRequest()
                    end if
                case other =>
                    if state.compareAndSet(curState, other) then
                        IO(0L)
                    else
                        handleRequest()
            end match
        end handleRequest
        IO(handleRequest())
    end request

    private[interop] def poll(using Frame): Result[Throwable | SubscriberDone, Chunk[V]] < IO =
        @tailrec def handlePoll(): Result[Throwable | SubscriberDone, Chunk[V]] < IO =
            val curState = state.get()
            curState match
                case (UpstreamState.WaitForRequest(subscription, items, remaining), Absent) =>
                    val nextState = UpstreamState.WaitForRequest(subscription, Chunk.empty, remaining) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        IO(Result.success(items))
                    else
                        handlePoll()
                    end if
                case s @ (UpstreamState.Finished(reason, leftOver), Absent) =>
                    if leftOver.isEmpty then
                        if state.compareAndSet(curState, s) then
                            IO {
                                reason match
                                    case Present(error) => Result.fail(error)
                                    case Absent         => Result.fail(SubscriberDone)
                            }
                        else
                            handlePoll()
                    else
                        val nextState = UpstreamState.Finished(reason, Chunk.empty) -> Absent
                        if state.compareAndSet(curState, nextState) then
                            IO(Result.success(leftOver))
                        else
                            handlePoll()
                        end if
                    end if
                case other =>
                    if state.compareAndSet(curState, other) then
                        IO(Result.success(Chunk.empty))
                    else
                        handlePoll()
            end match
        end handlePoll
        IO(handlePoll())
    end poll

    private[interop] def interupt(using Frame): Unit < IO =
        @tailrec def handleInterupt(): Unit < IO =
            val curState = state.get()
            curState match
                case (UpstreamState.Uninitialized, maybePromise) =>
                    val nextState = UpstreamState.Finished(Absent, Chunk.empty) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        IO(maybePromise.foreach(_.completeDiscard(Result.success(()))))
                    else
                        handleInterupt()
                    end if
                case (UpstreamState.WaitForRequest(subscription, _, _), Absent) =>
                    val nextState = UpstreamState.Finished(Absent, Chunk.empty) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        IO(subscription.cancel())
                    else
                        handleInterupt()
                    end if
                case other =>
                    if state.compareAndSet(curState, other) then
                        IO.unit
                    else
                        handleInterupt()
            end match
        end handleInterupt
        IO(handleInterupt())
    end interupt

    private[interop] def emit(using Frame, Tag[Emit[Chunk[V]]]): Ack < (Emit[Chunk[V]] & Async) =
        Emit.valueWith(Chunk.empty) { ack =>
            Loop(ack) {
                case Ack.Stop => interupt.andThen(Loop.done(Ack.Stop))
                case Ack.Continue(_) =>
                    await
                        .map {
                            case true => request.andThen(Ack.Continue())
                            case false => poll.map {
                                    case Result.Success(nextChunk)  => Emit.value(nextChunk)
                                    case Result.Error(e: Throwable) => Abort.panic(e)
                                    case _                          => Ack.Stop
                                }
                        }
                        .map(Loop.continue(_))
            }
        }

    def stream(using Frame, Tag[Emit[Chunk[V]]]): Stream[V, Async] = Stream(emit)

end StreamSubscriber

object StreamSubscriber:

    abstract private[flow] class SubscriberDone
    private[flow] case object SubscriberDone extends SubscriberDone

    enum EmitStrategy derives CanEqual:
        case Eager  // Emit value to downstream stream as soon as the subscriber receives one
        case Buffer // Subscriber buffers received values and emit them only when reaching bufferSize
    end EmitStrategy

    def apply[V](
        bufferSize: Int,
        strategy: EmitStrategy = EmitStrategy.Eager
    )(
        using Frame
    ): StreamSubscriber[V] < IO = IO.Unsafe(new StreamSubscriber(bufferSize, strategy))
end StreamSubscriber
