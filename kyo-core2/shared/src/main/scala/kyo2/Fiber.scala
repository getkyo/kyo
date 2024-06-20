package kyo2

import kyo.Tag
import kyo2.kernel.*
import kyo2.scheduler.*

opaque type Fiber[+A] = IOFiber[A]

object Fiber:

    private val _unit     = value(())
    def unit: Fiber[Unit] = _unit

    private def _init[A, S](v: => A < Async): Fiber[A] < IO = ???

    inline def init[A, S](inline v: => A < (Async & S)): Fiber[A] < (Async & S) =
        RuntimeEffect.boundary[A, Fiber[A], Async & S, IO](v) { (cont: A < Async) =>
            _init(cont)
        }

    def value[A](v: A): Fiber[A]            = IOPromise(Result.success(v))
    def fail(ex: Throwable): Fiber[Nothing] = IOPromise(Result.failure(ex))

    extension [A](self: Fiber[A])
        def get(using Frame): A < Async                                   = Async.get(self)
        def getResult(using Frame): Result[A] < Async                     = Async.getResult(self)
        def isDone(using Frame): Boolean < IO                             = IO(self.isDone())
        def interrupts[B](i: Fiber[B])(using Frame): Unit < IO            = IO(self.interrupts(i))
        def interrupt(using Frame): Boolean < IO                          = IO(self.interrupt())
        def onComplete(f: Result[A] => Unit < IO)(using Frame): Unit < IO = IO(self.onComplete(r => IO.run(f(r)).eval))
        def block(deadline: Long)(using Frame): A < IO                    = IO(self.block(deadline).get)
    end extension

    opaque type Promise[A] <: Fiber[A] = IOPromise[A]

    object Promise:
        def init[A](using Frame): Promise[A] < IO = IO(IOPromise())
        extension [A](self: Promise[A])
            def completeSuccess(v: A)(using Frame): Boolean < IO          = IO(self.complete(Result.success(v)))
            def completeFailure(ex: Throwable)(using Frame): Boolean < IO = IO(self.complete(Result.failure(ex)))
            def completeResult(v: Result[A])(using Frame): Boolean < IO   = IO(self.complete(v))
            def become(other: Fiber[A])(using Frame): Boolean < IO        = IO(self.become(other))
        end extension
    end Promise

end Fiber

opaque type Async <: IO = Async.Get & IO

object Async:
    inline def getResult[A](inline fiber: Fiber[A]): Result[A] < Async =
        Effect.suspend(Tag[Get], fiber)

    inline def get[A](inline fiber: Fiber[A]): A < Async =
        Effect.suspend(Tag[Get], fiber, _.get)

    def run[A, S](v: A < (Async & IO)): Fiber[A] < IO =
        ???

    sealed trait Get extends Effect[Fiber, Result]
end Async
