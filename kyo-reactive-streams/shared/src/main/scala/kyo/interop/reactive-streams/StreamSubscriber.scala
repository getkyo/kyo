package kyo.interop.reactivestreams

import StreamSubscriber.*
import kyo.*
import kyo.Emit.Ack
import org.reactivestreams.*

final class StreamSubscriber[V] private (
    bufferSize: Int
)(
    using
    allowance: AllowUnsafe,
    frame: Frame
) extends Subscriber[V]:

    private enum UpstreamState derives CanEqual:
        case Uninitialized                                                               extends UpstreamState
        case WaitForRequest(subscription: Subscription, items: Chunk[V], remaining: Int) extends UpstreamState
        case Finished(reason: Maybe[Throwable], leftOver: Chunk[V])                      extends UpstreamState
    end UpstreamState

    private val state = AtomicRef.Unsafe.init(
        UpstreamState.Uninitialized -> Maybe.empty[Fiber.Promise.Unsafe[Nothing, Unit]]
    )

    private def throwIfNull[A](b: A): Unit = if isNull(b) then throw new NullPointerException()

    override def onSubscribe(subscription: Subscription): Unit =
        throwIfNull(subscription)
        var sideEffect: () => Unit = () => ()
        state.update {
            case (UpstreamState.Uninitialized, maybePromise) =>
                // Notify if someone wait
                sideEffect = () => maybePromise.foreach(_.completeDiscard(Result.success(())))
                UpstreamState.WaitForRequest(subscription, Chunk.empty, 0) -> Absent
            case other =>
                // wrong state, cancel incoming subscription
                sideEffect = () => subscription.cancel()
                other
        }
        sideEffect()
    end onSubscribe

    override def onNext(item: V): Unit =
        throwIfNull(item)
        var sideEffect: () => Unit = () => ()
        state.update {
            case (UpstreamState.WaitForRequest(subscription, items, remaining), maybePromise) =>
                sideEffect = () => maybePromise.foreach(_.completeDiscard(Result.success(())))
                UpstreamState.WaitForRequest(subscription, items.append(item), remaining - 1) -> Absent
            case other =>
                sideEffect = () => ()
                other
        }
        sideEffect()
    end onNext

    override def onError(throwable: Throwable): Unit =
        throwIfNull(throwable)
        var sideEffect: () => Unit = () => ()
        state.update {
            case (UpstreamState.WaitForRequest(_, items, _), maybePromise) =>
                sideEffect = () => maybePromise.foreach(_.completeDiscard(Result.success(())))
                UpstreamState.Finished(Maybe(throwable), items) -> Absent
            case other =>
                sideEffect = () => ()
                other
        }
        sideEffect()
    end onError

    override def onComplete(): Unit =
        var sideEffect: () => Unit = () => ()
        state.update {
            case (UpstreamState.WaitForRequest(_, items, _), maybePromise) =>
                sideEffect = () => maybePromise.foreach(_.completeDiscard(Result.success(())))
                UpstreamState.Finished(Absent, items) -> Absent
            case other =>
                sideEffect = () => ()
                other
        }
        sideEffect()
    end onComplete

    private[interop] def await: Boolean < Async =
        var sideEffect: () => (Boolean < Async) = () => IO(false)
        state.update {
            case (UpstreamState.Uninitialized, Absent) =>
                val promise = Fiber.Promise.Unsafe.init[Nothing, Unit]()
                sideEffect = () => promise.safe.use(_ => false)
                UpstreamState.Uninitialized -> Present(promise)
            case s @ (UpstreamState.Uninitialized, Present(promise)) =>
                sideEffect = () => promise.safe.use(_ => false)
                s
            case s @ (UpstreamState.WaitForRequest(subscription, items, remaining), Absent) =>
                if items.isEmpty then
                    if remaining == 0 then
                        sideEffect = () => IO(true)
                        UpstreamState.WaitForRequest(subscription, items, remaining) -> Absent
                    else
                        val promise = Fiber.Promise.Unsafe.init[Nothing, Unit]()
                        sideEffect = () => promise.safe.use(_ => false)
                        UpstreamState.WaitForRequest(subscription, items, remaining) -> Present(promise)
                else
                    sideEffect = () => IO(false)
                    s
            case s @ (UpstreamState.Finished(_, _), maybePromise) =>
                sideEffect = () =>
                    maybePromise match
                        case Present(promise) => promise.safe.use(_ => false)
                        case Absent           => IO(false)
                s
            case other =>
                sideEffect = () => IO(false)
                other
        }
        sideEffect()
    end await

    private[interop] def request: Long < IO =
        var sideEffect: () => Long < IO = () => IO(0L)
        state.update {
            case (UpstreamState.WaitForRequest(subscription, items, remaining), maybePromise) =>
                sideEffect = () => IO(subscription.request(bufferSize)).andThen(bufferSize.toLong)
                UpstreamState.WaitForRequest(subscription, items, remaining + bufferSize) -> maybePromise
            case other =>
                sideEffect = () => IO(0L)
                other
        }
        sideEffect()
    end request

    private[interop] def poll: Result[Throwable | SubscriberDone, Chunk[V]] < IO =
        var sideEffect: () => (Result[Throwable | SubscriberDone, Chunk[V]] < IO) = () => IO(Result.success(Chunk.empty))
        state.update {
            case (UpstreamState.WaitForRequest(subscription, items, remaining), Absent) =>
                sideEffect = () => IO(Result.success(items))
                UpstreamState.WaitForRequest(subscription, Chunk.empty, remaining) -> Absent
            case s @ (UpstreamState.Finished(reason, leftOver), Absent) =>
                if leftOver.isEmpty then
                    sideEffect = () =>
                        IO {
                            reason match
                                case Present(error) => Result.fail(error)
                                case Absent         => Result.fail(SubscriberDone)

                        }
                    s
                else
                    sideEffect = () => IO(Result.success(leftOver))
                    UpstreamState.Finished(reason, Chunk.empty) -> Absent
            case other =>
                sideEffect = () => IO(Result.success(Chunk.empty))
                other
        }
        sideEffect()
    end poll

    private[interop] def interupt: Unit < IO =
        var sideEffect: () => (Unit < IO) = () => IO.unit
        state.update {
            case (UpstreamState.Uninitialized, maybePromise) =>
                // Notify if someone wait
                sideEffect = () => maybePromise.foreach(_.completeDiscard(Result.success(())))
                UpstreamState.Finished(Absent, Chunk.empty) -> Absent
            case (UpstreamState.WaitForRequest(subscription, _, _), Absent) =>
                sideEffect = () => IO(subscription.cancel())
                UpstreamState.Finished(Absent, Chunk.empty) -> Absent
            case other =>
                sideEffect = () => IO.unit
                other
        }
        sideEffect()
    end interupt

    private[interop] def emit(ack: Ack)(using Tag[V]): Ack < (Emit[Chunk[V]] & Async) =
        ack match
            case Ack.Stop => interupt.andThen(Ack.Stop)
            case Ack.Continue(_) =>
                await.map {
                    if _ then
                        request.andThen(Ack.Continue())
                    else
                        poll.map {
                            case Result.Success(nextChunk)  => Emit(nextChunk)
                            case Result.Error(e: Throwable) => Abort.panic(e)
                            case _                          => Ack.Stop
                        }
                }.map(emit)

    def stream(using Tag[V]): Stream[V, Async] = Stream(Emit.andMap(Chunk.empty)(emit))

end StreamSubscriber

object StreamSubscriber:

    abstract private[reactivestreams] class SubscriberDone
    private[reactivestreams] case object SubscriberDone extends SubscriberDone

    def apply[V](
        bufferSize: Int
    )(
        using Frame
    ): StreamSubscriber[V] < IO = IO.Unsafe(new StreamSubscriber(bufferSize))
end StreamSubscriber
