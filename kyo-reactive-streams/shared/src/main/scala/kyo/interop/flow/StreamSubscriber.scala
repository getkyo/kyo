package kyo.interop.flow

import StreamSubscriber.*
import java.util.concurrent.Flow.*
import kyo.*
import kyo.Result.Error
import kyo.Result.Panic
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
        case Finished(reason: Maybe[Error[Any]], leftOver: Chunk[V])                     extends UpstreamState
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
                        maybePromise.foreach(_.completeDiscard(Result.succeed(())))
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
                            maybePromise.foreach(_.completeDiscard(Result.succeed(())))
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
                    val nextState = UpstreamState.Finished(Maybe(Panic(throwable)), items) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        maybePromise.foreach(_.completeDiscard(Result.succeed(())))
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
                        maybePromise.foreach(_.completeDiscard(Result.succeed(())))
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
                                Sync(true)
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
                            Sync(false)
                        else
                            handleAwait()
                case other =>
                    if state.compareAndSet(curState, other) then
                        Sync(false)
                    else
                        handleAwait()
            end match
        end handleAwait
        Sync(handleAwait())
    end await

    private[interop] def request(using Frame): Long < Sync =
        @tailrec def handleRequest(): Long < Sync =
            val curState = state.get()
            curState match
                case (UpstreamState.WaitForRequest(subscription, items, remaining), maybePromise) =>
                    val nextState = UpstreamState.WaitForRequest(subscription, items, remaining + bufferSize) -> maybePromise
                    if state.compareAndSet(curState, nextState) then
                        Sync(subscription.request(bufferSize)).andThen(bufferSize.toLong)
                    else
                        handleRequest()
                    end if
                case other =>
                    if state.compareAndSet(curState, other) then
                        Sync(0L)
                    else
                        handleRequest()
            end match
        end handleRequest
        Sync(handleRequest())
    end request

    private[interop] def poll(using Frame): Result[Error[Any] | SubscriberDone, Chunk[V]] < Sync =
        @tailrec def handlePoll(): Result[Error[Any] | SubscriberDone, Chunk[V]] < Sync =
            val curState = state.get()
            curState match
                case (UpstreamState.WaitForRequest(subscription, items, remaining), Absent) =>
                    val nextState = UpstreamState.WaitForRequest(subscription, Chunk.empty, remaining) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        Sync(Result.succeed(items))
                    else
                        handlePoll()
                    end if
                case s @ (UpstreamState.Finished(reason, leftOver), Absent) =>
                    if leftOver.isEmpty then
                        if state.compareAndSet(curState, s) then
                            Sync {
                                reason match
                                    case Present(error) => Result.fail(error)
                                    case Absent         => Result.fail(SubscriberDone)
                            }
                        else
                            handlePoll()
                    else
                        val nextState = UpstreamState.Finished(reason, Chunk.empty) -> Absent
                        if state.compareAndSet(curState, nextState) then
                            Sync(Result.succeed(leftOver))
                        else
                            handlePoll()
                        end if
                    end if
                case other =>
                    if state.compareAndSet(curState, other) then
                        Sync(Result.succeed(Chunk.empty))
                    else
                        handlePoll()
            end match
        end handlePoll
        Sync(handlePoll())
    end poll

    private[interop] def interupt(using Frame): Unit < Sync =
        @tailrec def handleInterupt(): Unit < Sync =
            val curState = state.get()
            curState match
                case (UpstreamState.Uninitialized, maybePromise) =>
                    val nextState = UpstreamState.Finished(Absent, Chunk.empty) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        Sync(maybePromise.foreach(_.completeDiscard(Result.succeed(()))))
                    else
                        handleInterupt()
                    end if
                case (UpstreamState.WaitForRequest(subscription, _, _), Absent) =>
                    val nextState = UpstreamState.Finished(Absent, Chunk.empty) -> Absent
                    if state.compareAndSet(curState, nextState) then
                        Sync(subscription.cancel())
                    else
                        handleInterupt()
                    end if
                case other =>
                    if state.compareAndSet(curState, other) then
                        Kyo.unit
                    else
                        handleInterupt()
            end match
        end handleInterupt
        Sync(handleInterupt())
    end interupt

    private[interop] def emit(using Frame, Tag[Emit[Chunk[V]]]): Unit < (Emit[Chunk[V]] & Async) =
        Emit.valueWith(Chunk.empty) {
            Loop.foreach {
                await
                    .map {
                        case true => request.andThen(Loop.continue)
                        case false => poll.map {
                                case Result.Success(nextChunk)  => Emit.value(nextChunk).andThen(Loop.continue)
                                case Result.Error(e: Throwable) => Abort.panic(e)
                                case _                          => Loop.done
                            }
                    }
            }
        }

    def stream(using Frame, Tag[Emit[Chunk[V]]]): Stream[V, Async] < (Resource & Sync) =
        Resource.ensure(interupt).andThen:
            Stream(emit)

end StreamSubscriber

object StreamSubscriber:

    sealed abstract private[flow] class SubscriberDone
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
    ): StreamSubscriber[V] < Sync = Sync.Unsafe(new StreamSubscriber(bufferSize, strategy))
end StreamSubscriber
