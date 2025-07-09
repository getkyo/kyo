package kyo

import kyo.Result.Error
import kyo.Result.Panic
import kyo.Tag
import kyo.kernel.ContextEffect

opaque type Scope <: Sync = Sync

object Scope:

    private val local = Local.init(Maybe.empty[Finalizer])

    def ensure(v: => Any < (Async & Abort[Throwable]))(using Frame): Unit < Scope =
        ensure(_ => v)

    def ensure(callback: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Scope =
        withFinalizerUnsafe(f => Abort.get(f.ensure(callback)))

    def acquireRelease[A, S](acquire: => A < S)(release: A => Any < (Async & Abort[Throwable]))(using Frame): A < (Scope & S) =
        withFinalizerUnsafe { finalizer =>
            acquire.ensureMap { resource =>
                val result = finalizer.ensure(_ => release(resource))
                Abort.get(result).andThen(resource)
            }
        }

    def acquire[A <: java.io.Closeable, S](resource: => A < S)(using Frame): A < (Scope & S) =
        acquireRelease(resource)(_.close())

    def run[A, S](v: A < (Scope & S))(using Frame): A < (Async & S) =
        run(1, true)(v)

    def run[A, S](closeParallelism: Int, awaitClose: Boolean = true)(v: A < (Scope & S))(using Frame): A < (Async & S) =
        Sync.Unsafe {
            val finalizer = new Finalizer.Unsafe
            runAndClose(finalizer, closeParallelism, awaitClose) {
                local.let(Present(finalizer))(v)
            }
        }

    def runIsolated[A, S](f: Finalizer => A < (Scope & S))(using Frame): A < (Async & S) =
        runIsolated(1)(f)

    def runIsolated[A, S](closeParallelism: Int, awaitClose: Boolean = true)(f: Finalizer => A < (Scope & S))(
        using Frame
    ): A < (Async & S) =
        Sync.withLocal(local) { parent =>
            import AllowUnsafe.embrace.danger
            val child = new Finalizer.Unsafe
            parent.foreach(p => discard(p.ensure(child.close(_, closeParallelism))))
            runAndClose(child, closeParallelism, awaitClose)(f(child))
        }

    private def runAndClose[A, S](finalizer: Finalizer, closeParallelism: Int, awaitClose: Boolean)(v: A < (Scope & S))(
        using Frame
    ): A < (Async & S) =
        v.handle(
            Sync.ensure { error =>
                import AllowUnsafe.embrace.danger
                finalizer.close(error, closeParallelism)
            },
            Abort.run[Any]
        ).map { result =>
            import AllowUnsafe.embrace.danger
            finalizer.close(result.error, closeParallelism)
            Kyo.when(awaitClose)(finalizer.await().safe.get)
                .andThen(Abort.get(result.asInstanceOf[Result[Nothing, A]]))
        }
    end runAndClose

    private def withFinalizerUnsafe[A, S](f: AllowUnsafe ?=> Finalizer => A < S)(using Frame): A < (Scope & S) =
        local.use {
            case Present(finalizer) => f(using AllowUnsafe.embrace.danger)(finalizer)
            case Absent             => bug("Missing finalizer from context")
        }

    given Isolate.Contextual[Scope, Sync] = Isolate.Contextual.derive[Sync, Sync]

    opaque type Finalizer = Finalizer.Unsafe

    object Finalizer:

        extension (self: Finalizer)

            def ensure(callback: Callback)(using Frame): Unit < Sync =
                Sync.Unsafe(Abort.get(self.ensure(callback)))

            def unsafe: Unsafe = self

        end extension

        type Callback = Maybe[Error[Any]] => Any < (Async & Abort[Throwable])

        final class Unsafe(using frame: Frame, allow: AllowUnsafe):

            private val queue   = Queue.Unbounded.Unsafe.init[Callback](Access.MultiProducerSingleConsumer)
            private val promise = Promise.Unsafe.init[Nothing, Unit]()

            def ensure(callback: Callback)(using AllowUnsafe): Result[Nothing, Unit] =
                if !queue.offer(callback).contains(true) then
                    Result.panic(new Closed(
                        "Finalizer",
                        frame,
                        "This finalizer is already closed. This may happen if a background fiber escapes the scope of a 'Resource.run' call."
                    ))
                else Result.unit

            def close(ex: Maybe[Error[Any]], parallelism: Int)(using AllowUnsafe): Unit =
                queue.close() match
                    case Absent => ()
                    case Present(tasks) =>
                        if tasks.isEmpty then
                            promise.completeDiscard(Result.unit)
                        else
                            Sync.Unsafe.evalOrThrow(
                                Async.foreachDiscard(tasks, parallelism) { task =>
                                    Abort.run[Throwable](task(ex))
                                        .map(_.foldError(_ => (), ex => Log.error("Resource finalizer failed", ex.exception)))
                                }
                                    .handle(Fiber.init[Nothing, Unit, Any])
                                    .map(promise.safe.becomeDiscard)
                            )

            def await()(using AllowUnsafe): Fiber.Unsafe[Nothing, Unit] =
                promise

            def safe: Finalizer = this
        end Unsafe

    end Finalizer

end Scope
