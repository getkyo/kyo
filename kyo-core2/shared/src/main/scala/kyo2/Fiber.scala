package kyo2

import kyo.Tag
import kyo2.Abort.HasAbort
import kyo2.kernel.*
import kyo2.kernel.ContextEffect.suspend
import kyo2.scheduler.*
import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.NotGiven
import scala.util.control.NonFatal

opaque type Fiber[E, A] = IOPromise[E, A]

object Demo:

    // Fork without Abort
    val a: Fiber[Nothing, Int] < IO = Async.run(1: Int < Any)

    // Fork with Abort
    val b: Fiber[Int, Int] < IO = Async.run(1: Int < (Abort[Int] & Async))

    // val y = x.map(Async.get)
    // val y: Fiber[Int, Int] < (Env[Int] & IO) = x
end Demo

object Fiber:

    private val _unit              = value(())
    def unit: Fiber[Nothing, Unit] = _unit

    def value[E, A](v: A): Fiber[E, A]                  = result(Result.success(v))
    def fail[E](ex: E): Fiber[E, Nothing]               = result(Result.failure(ex))
    def panic[E, A](ex: Throwable): Fiber[E, A]         = result(Result.panic(ex))
    def result[E, A](result: Result[E, A]): Fiber[E, A] = IOPromise(result)

    extension [E, A](self: Fiber[E, A])
        def get(using Frame): Result[E, A] < Async                           = Async.get(self)
        def isDone(using Frame): Boolean < IO                                = IO(self.isDone())
        def interrupt(using Frame): Boolean < IO                             = IO(self.interrupt())
        def onComplete(f: Result[E, A] => Unit < IO)(using Frame): Unit < IO = IO(self.onComplete(r => IO.run(f(r)).eval))
        def block(deadline: Long)(using Frame): Result[E, A] < IO            = IO(self.block(deadline))
    end extension

    opaque type Promise[E, A] <: Fiber[E, A] = IOPromise[E, A]

    object Promise:
        def init[E, A](using Frame): Promise[E, A] < IO = IO(IOPromise())
        extension [E, A](self: Promise[E, A])
            def completeSuccess(v: A)(using Frame): Boolean < IO           = completeResult(Result.success(v))
            def completeFailure(e: E)(using Frame): Boolean < IO           = completeResult(Result.failure(e))
            def completePanic(ex: Throwable)(using Frame): Boolean < IO    = completeResult(Result.panic(ex))
            def completeResult(v: Result[E, A])(using Frame): Boolean < IO = IO(self.complete(v))
            def become(other: Fiber[E, A])(using Frame): Boolean < IO      = IO(self.become(other))
        end extension
    end Promise

    sealed trait Async extends Effect[IOPromise[?, *], Result[Nothing, *]]

    object Async:

        def get[E, A](v: Fiber[E, A]): Result[E, A] < Async =
            Effect.suspend[A](Tag[Async], v).asInstanceOf[Result[E, A] < Async]

        def run[E, A, Ctx](v: => A < (Abort[E] & Async & IO & Ctx))(
            using b: Boundary[Ctx, IO]
        ): Fiber[E, A] < (IO & Ctx) =
            val x = Abort.run[Any](v).asInstanceOf[Result[E, A] < (Async & IO & Ctx)]
            b(x) { res =>
                def loop(v: Fiber[E, A] < (Async & IO)): Fiber[E, A] =
                    try
                        Effect.handle(Tag[IO], Tag[Async], v)(
                            [C] => (input, cont) => cont(()),
                            [C] =>
                                (input, cont) =>
                                    locally {
                                        val p = new IOPromise[E, A](interrupts = input)
                                        input.onComplete { r =>
                                            discard(p.become(loop(cont(r.asInstanceOf))))
                                        }
                                        p
                                }
                        ).eval
                    catch
                        case ex if NonFatal(ex) =>
                            Fiber.panic(ex)
                loop(res.map(Fiber.result))
            }
        end run
    end Async

end Fiber

export Fiber.Async
