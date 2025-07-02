package kyo

import Result.Error
import kyo.internal.OsSignal

/** An abstract base class for Kyo applications.
  *
  * This class provides a foundation for building applications using the Kyo framework, with built-in support for logging, random number
  * generation, and clock operations.
  *
  * Note: This class and its methods are unsafe and should only be used as the entrypoint of an application.
  */
abstract class KyoApp extends KyoAppPlatformSpecific:
    override def timeout: Duration = Duration.Infinity

    private val awaitInterrupt =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val promise       = Promise.Unsafe.init[Nothing, Nothing]()

        val interrupt = (signal: String) =>
            () =>
                promise
                    .completeDiscard(Result.panic(Fiber.Interrupted(Frame.internal, s"Interrupt Signal Reached: $signal")))

        if System.live.unsafe.operatingSystem() != System.OS.Windows then
            OsSignal.handle("INT", interrupt("INT"))
            OsSignal.handle("TERM", interrupt("TERM"))

        promise.mask().safe
    end awaitInterrupt

    override protected def handle[A](v: A < (Async & Resource & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable]) =
        Async.raceFirst(Resource.run(v), awaitInterrupt.get)
    end handle
end KyoApp

object KyoApp:

    /** An abstract base class for Kyo applications.
      *
      * This class provides a foundation for building applications using the Kyo framework.
      *
      * @tparam S
      *   The effect type used by the application.
      */
    abstract class Base[S]:
        protected def timeout: Duration

        protected def handle[A](v: A < S)(using Frame): A < (Async & Abort[Throwable])

        final protected def args: Array[String] = _args

        private var _args: Array[String] = null

        protected var initCode: Chunk[() => Unit] = Chunk.empty

        final def main(args: Array[String]) =
            this._args = args
            for proc <- initCode do proc()
        end main

        protected def run[A](v: => A < S)(using Frame, Render[A]): Unit

        protected def exit(code: Int)(using AllowUnsafe): Unit = kernel.Platform.exit(code)

        protected def onResult[E, A](result: Result[E, A])(using Render[Result[E, A]], AllowUnsafe): Unit =
            if !result.exists(().equals(_)) then println(result.show)
            result match
                case Error(e: Throwable) => throw e
                case Error(_)            => exit(1)
                case _                   =>
            end match
        end onResult
    end Base

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        /** Attempts to run an effect with a specified timeout.
          *
          * Note: This method is unsafe and should only be used as the entrypoint of an application.
          *
          * @param timeout
          *   The maximum duration to wait for the effect to complete.
          * @param v
          *   The effect to run.
          * @param frame
          *   The implicit Frame.
          * @return
          *   A Result containing either the computed value or a failure.
          */
        def runAndBlock[A](timeout: Duration)(
            v: A < (Async & Resource & Abort[Throwable])
        )(using Frame, AllowUnsafe): Result[Throwable, A] =
            Abort.run(Sync.Unsafe.run(Async.runAndBlock(timeout)(Resource.run(v)))).eval
    end Unsafe

end KyoApp
