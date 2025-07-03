package kyo

import ZIOs.toExit
import kyo.Result.*
import scala.reflect.ClassTag
import zio.Cause
import zio.Exit
import zio.FiberId
import zio.Runtime
import zio.Scope
import zio.StackTrace
import zio.Trace
import zio.Unsafe
import zio.ZEnvironment
import zio.ZIO
import zio.stream.ZChannel
import zio.stream.ZStream

object ZStreams:

    /** Lifts a zio.stream.ZStream into a Kyo's Stream.
      *
      * @param stream
      *   The zio.stream.ZStream to lift
      * @return
      *   A Kyo's Stream that, when run, will execute the zio.stream.ZStream
      */
    def get[E, A](stream: => ZStream[Any, E, A])(using
        Frame,
        zio.Trace,
        kyo.Tag[kyo.Emit[kyo.Chunk[A]]]
    ): Stream[A, Abort[E] & Async] =
        Stream(Sync {
            val scope = Unsafe.unsafely(Scope.unsafe.make)
            Resource.run {
                Resource.ensure(ex => ZIOs.get(scope.close(ex.fold(Exit.unit)(_.toExit)))).andThen:
                    ZIOs.get(stream.channel.toPullIn(scope)).map: pullIn =>
                        Loop.foreach:
                            ZIOs.get(pullIn).map {
                                case Right(zioChunk) => Emit.valueWith(Chunk.from(zioChunk))(Loop.continue)
                                case _               => Loop.done
                            }
            }
        })
    end get

    /** Interprets a Kyo Stream to ZStream. Note that this method only accepts Abort[E] and Async pending effects. Plase handle any other
      * effects before calling this method.
      *
      * @param v
      *   The Kyo effect to run
      * @return
      *   A ZStream that, when run, will drain the Kyo Stream
      */
    def run[A, E](stream: => Stream[A, Abort[E] & Async])(using
        Frame,
        zio.Trace,
        kyo.Tag[kyo.Emit[kyo.Chunk[A]]]
    ): ZStream[Any, E, A] =

        sealed trait Handoff derives CanEqual

        sealed trait KyoSide                                                          extends Handoff
        final case class EmitData(data: Chunk[A], waitSignal: Promise[Nothing, Unit]) extends KyoSide
        case object Done                                                              extends KyoSide

        // ZIO scope will interupt Kyo fiber's, so we only need to care about when to continue
        sealed trait ZIOSide                                             extends Handoff
        final case class Waiting(waitSignal: zio.Promise[Nothing, Unit]) extends ZIOSide
        case object Empty                                                extends Handoff

        zio.Unsafe.unsafely:
            import AllowUnsafe.embrace.danger

            val handoff = new java.util.concurrent.atomic.AtomicReference[Handoff](Empty)

            // We only handle happy case here
            val kyoSide =
                Loop(stream.emit): emit =>
                    Emit.runFirst(emit).map: (maybeChunk, nextFn) =>
                        maybeChunk match
                            case Present(chunk) =>
                                for
                                    kyoWaitSignal <- Promise.init[Nothing, Unit]
                                    lastHandoff   <- Sync(handoff.getAndSet(EmitData(chunk, kyoWaitSignal)))
                                    _ <- Sync {
                                        // in happy case, this can only be Waiting or Empty
                                        (lastHandoff: @unchecked) match
                                            case Waiting(zioWaitSignal) =>
                                                discard(zio.Runtime.default.unsafe.run(zioWaitSignal.succeed(())))
                                            case Empty => ()
                                        end match
                                    }
                                    _ <- kyoWaitSignal.get
                                yield Loop.continue(nextFn())
                            case Absent =>
                                for
                                    lastHandoff <- Sync(handoff.getAndSet(Done))
                                    _ <- Sync {
                                        // in happy case, this can only be Waiting or Empty
                                        (lastHandoff: @unchecked) match
                                            case Waiting(zioWaitSignal) =>
                                                discard(zio.Runtime.default.unsafe.run(zioWaitSignal.succeed(())))
                                            case Empty => ()
                                        end match
                                    }
                                yield Loop.done

            val streamEffect = ZIO.scopeWith { scope =>

                def loop(
                    prev: ZIO[Any, E, Unit],
                    kyoErrorPromise: zio.Promise[E, Unit]
                ): ZChannel[Any, Any, Any, Any, E, zio.Chunk[A], Any] =
                    ZChannel.unwrap {
                        val effect =
                            for
                                _           <- prev
                                waitSignal  <- zio.Promise.make[Nothing, Unit]
                                lastHandoff <- ZIO.succeed(handoff.getAndSet(Waiting(waitSignal)))
                                dataAndNext <- ZIO.succeed[(zio.Chunk[A], Maybe[ZIO[Any, E, Unit]])] {
                                    // in happy case, this can only be EmitData, Done or Empty
                                    (lastHandoff: @unchecked) match
                                        case EmitData(data, kyoWaitSignal) =>
                                            val chunk = zio.Chunk.from(data)
                                            val next =
                                                for
                                                    _ <- ZIO.attempt {
                                                        Sync.Unsafe(
                                                            kyoWaitSignal.completeDiscard(Result.Success(()))
                                                        ).handle(Sync.Unsafe.evalOrThrow)
                                                    }.orDie
                                                    _ <- waitSignal.await.raceFirst(kyoErrorPromise.await)
                                                yield ()
                                            chunk -> Present(next)
                                        case Done =>
                                            zio.Chunk.empty -> Absent
                                        case Empty =>
                                            val chunk = zio.Chunk.empty
                                            val next  = waitSignal.await.raceFirst(kyoErrorPromise.await)
                                            chunk -> Present(next)
                                    end match
                                }
                            yield dataAndNext
                        effect.map { case (chunk, nextMaybe) =>
                            nextMaybe match
                                case Present(next) => ZChannel.write(chunk) *> loop(next, kyoErrorPromise)
                                case Absent        => ZChannel.write(chunk)
                        }
                    }
                end loop

                // Other unhappy cases are being handled here
                // 1. When ZIO failed, the scope will do the job and interrupt the Kyo fiber
                // 2. When Kyo fiber failed, we fail the error promise, this promise served as signal to fail the whole ZStream
                for
                    kyoErrorPromise <- zio.Promise.make[E, Unit]
                    _ <- ZIO.attemptUnsafe { unsafe =>
                        given zio.Unsafe = unsafe
                        val fiber = Fiber
                            .run(kyoSide).map: fiber =>
                                fiber.unsafe.onComplete {
                                    case Result.Success(_) => ()
                                    case Result.Failure(e) =>
                                        discard(zio.Runtime.default.unsafe.run(kyoErrorPromise.fail(e)))
                                    case Result.Panic(e) =>
                                        discard(zio.Runtime.default.unsafe.run(kyoErrorPromise.die(e)))
                                }
                                discard(
                                    zio.Runtime.default.unsafe.run(scope.addFinalizer(ZIO.succeed(discard(fiber.unsafe.interrupt()))))
                                )
                        Sync.Unsafe.evalOrThrow(fiber)
                    }.orDie
                yield ZStream.fromChannel(loop(ZIO.unit, kyoErrorPromise))
                end for
            }

            ZStream.unwrapScoped(streamEffect)
    end run

end ZStreams
