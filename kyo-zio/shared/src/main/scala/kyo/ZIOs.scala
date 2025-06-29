package kyo

import kyo.Result.*
import zio.Cause
import zio.Chunk
import scala.reflect.ClassTag
import zio.Exit
import zio.FiberId
import zio.Runtime
import zio.StackTrace
import zio.Trace
import zio.Scope
import zio.Unsafe
import zio.ZEnvironment
import zio.ZIO
import zio.stream.ZChannel
import zio.stream.ZStream

object ZIOs:

    /** Lifts a zio.ZIO into a Kyo effect.
      *
      * @param zio
      *   The zio.ZIO to lift
      * @return
      *   A Kyo effect that, when run, will execute the zio.ZIO
      */
    def get[E, A](v: => ZIO[Any, E, A])(using f: Frame, t: Trace): A < (Abort[E] & Async) =
        Sync.Unsafe {
            Unsafe.unsafely {
                given ce: CanEqual[E, E] = CanEqual.derived
                val p                    = Promise.Unsafe.init[E, A]()
                val f                    = Runtime.default.unsafe.fork(v)
                f.unsafe.addObserver { (exit: Exit[E, A]) =>
                    p.completeDiscard(exit.toResult)
                }
                p.onInterrupt(_ =>
                    discard(f.unsafe.interrupt(Cause.interrupt(FiberId.None, StackTrace(FiberId.None, Chunk(t)))))
                )
                p.safe.get
            }
        }
    end get

    /** Placeholder for ZIO effects with environments (currently not supported).
      *
      * @tparam R
      *   The environment type
      * @tparam E
      *   The error type
      * @tparam A
      *   The result type
      */
    inline def get[R: zio.Tag, E, A](v: ZIO[R, E, A])(using Tag[Env[R]], Frame, zio.Trace): A < (Env[R] & Abort[E] & Async) =
        compiletime.error("ZIO environments are not supported yet. Please handle them before calling this method.")

    /** Lifts a zio.stream.ZStream into a Kyo's Stream.
      *
      * @param stream
      *   The zio.stream.ZStream to lift
      * @return
      *   A Kyo's Stream that, when run, will execute the zio.stream.ZStream
      */
    def get[E, A](stream: => ZStream[Any, E, A])(using
        frame: Frame,
        trace: zio.Trace,
        tag: kyo.Tag[kyo.Emit[kyo.Chunk.Indexed[A]]],
        classTag: ClassTag[A]
    ): Stream[A, Abort[E] & Async] =
        zio.Unsafe.unsafely { unsafe ?=>
            val emit = Resource
                .acquireRelease(Scope.unsafe.make(using unsafe))(_.close(Exit.unit))
                .map: scope =>
                    get(stream.channel.toPullIn(scope))
                        .map: pullIn =>
                            Loop.foreach:
                                get(pullIn).map {
                                    case Right(zioChunk) => Emit.valueWith(Chunk.from(zioChunk.toArray))(Loop.continue)
                                    case _               => Loop.done
                                }
            Stream(Resource.run(emit))
        }
    end get

    /** Interprets a Kyo computation to ZIO. Note that this method only accepts Abort[E] and Async pending effects. Plase handle any other
      * effects before calling this method.
      *
      * @param v
      *   The Kyo effect to run
      * @return
      *   A zio.ZIO that, when run, will execute the Kyo effect
      */
    def run[A, E](v: => A < (Abort[E] & Async))(using frame: Frame, trace: zio.Trace): ZIO[Any, E, A] =
        ZIO.suspendSucceed {
            import AllowUnsafe.embrace.danger
            Fiber.run(v).map { fiber =>
                ZIO.asyncInterrupt[Any, E, A] { cb =>
                    fiber.unsafe.onComplete {
                        case Result.Success(a) => cb(Exit.succeed(a))
                        case Result.Failure(e) => cb(Exit.fail(e))
                        case Result.Panic(e)   => cb(Exit.die(e))
                    }
                    Left(ZIO.succeed {
                        fiber.unsafe.interrupt(Result.Panic(Fiber.Interrupted(frame)))
                    })
                }
            }.handle(Sync.Unsafe.evalOrThrow)
        }
    end run

    def run[A, E](stream: => Stream[A, Abort[E] & Async])(using
        frame: Frame,
        trace: zio.Trace,
        tag: Tag[kyo.Emit[kyo.Chunk[A]]],
        classTag: ClassTag[A]
    ): ZStream[Any, E, A] =
        import AllowUnsafe.embrace.danger
        val channel = ZChannel.suspend {
            def loop(emit: Unit < (Emit[Chunk[A]] & Abort[E] & Async)): ZChannel[Any, Any, Any, Any, E, zio.Chunk[A], Any] =
                ZChannel
                    .fromZIO(run(Emit.runFirst(emit)))
                    .flatMap: (chunkMaybe, cont) =>
                        chunkMaybe match
                            case Present(chunk) =>
                                ZChannel.write(zio.Chunk.fromArray(chunk.toArray)) *> loop(cont())
                            case Absent => ZChannel.unit
            end loop

            loop(stream.emit)
        }
        ZStream.fromChannel(channel)
    end run

    def run[A, E](stream: => Stream[A, Abort[E] & Async])(using
        frame: Frame,
        trace: zio.Trace,
        tag: Tag[kyo.Emit[kyo.Chunk[A]]],
        classTag: ClassTag[A]
    ): ZStream[Any, E, A] =
        import AllowUnsafe.embrace.danger
        val channel = ZChannel.suspend {
            def loop(emit: Unit < (Emit[Chunk[A]] & Abort[E] & Async)): ZChannel[Any, Any, Any, Any, E, zio.Chunk[A], Any] =
                ZChannel
                    .fromZIO(run(Emit.runFirst(emit)))
                    .flatMap: (chunkMaybe, cont) =>
                        chunkMaybe match
                            case Present(chunk) =>
                                ZChannel.write(zio.Chunk.fromArray(chunk.toArray)) *> loop(cont())
                            case Absent => ZChannel.unit
            end loop

            loop(stream.emit)
        }
        ZStream.fromChannel(channel)
    end run

    extension [E, A](exit: zio.Exit[E, A])
        /** Converts a zio.Exit to a kyo.Result.
          *
          * @return
          *   A kyo.Result
          */
        def toResult(using Frame, CanEqual[E, E]): Result[E, A] =
            exit match
                case Exit.Success(a)     => Result.succeed(a)
                case Exit.Failure(cause) => cause.toError
    end extension

    extension [E](cause: Cause[E])
        /** Converts a zio.Cause to a kyo.Result.Error.
          *
          * This method recursively traverses the zio.Cause structure and converts it to the appropriate kyo.Result.Error type.
          *
          * @return
          *   A kyo.Result.Error
          */
        def toError(using frame: Frame, ce: CanEqual[E, E]): Result.Error[E] =
            import Cause.*
            def loop(cause: Cause[E]): Maybe[Result.Error[E]] =
                cause match
                    case Fail(e, trace)            => Maybe(Result.Failure(e))
                    case Die(e, trace)             => Maybe(Result.Panic(e))
                    case Interrupt(fiberId, trace) => Maybe(Result.Panic(Fiber.Interrupted(frame)))
                    case Then(left, right)         => loop(left).orElse(loop(right))
                    case Both(left, right)         => loop(left).orElse(loop(right))
                    case Stackless(e, trace)       => loop(e)
                    case _: Empty.type             => Maybe.empty
            loop(cause).getOrElse(Result.Panic(new Exception("Unexpected zio.Cause.Empty at " + frame.position.show)))
        end toError
    end extension

    extension [E](ex: Result.Error[E])
        def toCause: Cause[E] =
            ex match
                case Result.Failure[E](e) => Cause.fail(e)
                case Result.Panic(e)      => Cause.die(e)
    end extension

    extension [E, A](result: Result[E, A])
        def toExit: Exit[E, A] =
            result match
                case Result.Success(a) => Exit.succeed(a)
                case Result.Failure(e) => Exit.fail(e)
                case Result.Panic(e)   => Exit.die(e)
    end extension

end ZIOs
