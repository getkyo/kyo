package kyo

import scala.concurrent.Future
import scala.util.Try

class ReadmeTest extends Test:

    given ce[A, B]: CanEqual[A, B] = CanEqual.canEqualAny

    "readme" in run {
        import scala.concurrent.duration.*
        import java.io.IOException

        trait HelloService:
            def sayHelloTo(saluee: String): Unit < (IO & Abort[Throwable])

        object HelloService:
            val live = Layer(Live)

            object Live extends HelloService:
                override def sayHelloTo(saluee: String): Unit < (IO & Abort[Throwable]) =
                    Kyo.suspendAttempt { // Adds IO & Abort[Throwable] effect
                        println(s"Hello $saluee!")
                    }
            end Live
        end HelloService

        val keepTicking: Nothing < (Async & Abort[IOException]) =
            (Console.print(".") *> Kyo.sleep(1.second)).forever

        val effect: Unit < (Async & Resource & Abort[Throwable] & Env[HelloService]) =
            for
                nameService <- Kyo.service[HelloService] // Adds Env[NameService] effect
                _           <- keepTicking.forkScoped    // Adds Async, Abort[IOException], and Resource effects
                // saluee      <- Console.readln
                // _           <- Kyo.sleep(2.seconds)           // Uses Async (semantic blocking)
                _ <- nameService.sayHelloTo("test") // Lifts Abort[IOException] to Abort[Throwable]
            yield ()
            end for
        end effect

        // There are no combinators for handling IO or blocking Async, since this should
        // be done at the edge of the program
        Async.run {      // Handles Async
            Kyo.scoped { // Handles Resource
                Memo.run:
                    effect
                        .catching((thr: Throwable) => // Handles Abort[Throwable]
                            Kyo.logDebug(s"Failed printing to console: ${thr}")
                        )
                        .provide(HelloService.live) // Works like ZIO[R,E,A]#provide
                        .map(_ => assert(true))
            }
        }.map(_.toFuture)

    }

end ReadmeTest
