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
    private val awaitInterrupt =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val promise       = Promise.Unsafe.init[Nothing, Any]()

        val interrupt = (signal: String) =>
            () =>
                promise
                    .completeDiscard(Result.panic(Interrupted(Frame.internal, s"Interrupt Signal: $signal")))

        if System.live.unsafe.operatingSystem() != System.OS.Windows then
            OsSignal.handle("INT", interrupt("INT"))
            OsSignal.handle("TERM", interrupt("TERM"))

        promise.mask().safe
    end awaitInterrupt

    override protected def handle[A](v: A < (Async & Scope & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable]) =
        Async.raceFirst(Scope.run(v), awaitInterrupt.get)
    end handle
end KyoApp

object KyoApp:
    def apply[A](v: => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): KyoApp =
        new KyoApp:
            run(v)

    /** Runs an asynchronous computation in a new fiber and blocks until completion or timeout.
      *
      * @param timeout
      *   The maximum duration to wait
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation, or a Timeout error
      */
    def runAndBlock[E, A, S](
        using isolate: Isolate[S, Sync, Any]
    )(timeout: Duration)(v: => A < (Abort[E] & Async & S))(
        using
        frame: Frame,
        t: Tag[Abort[E | Timeout]]
    ): A < (Abort[E | Timeout] & Sync & S) =
        Fiber.initUnscoped(v).map { fiber =>
            fiber.block(timeout).map(Abort.get(_))
        }

    /** An abstract base class for Kyo applications.
      *
      * This class provides a foundation for building applications using the Kyo framework.
      *
      * @tparam S
      *   The effect type used by the application.
      */
    abstract class Base[S]:
        private var _args: Array[String]          = null
        protected var initCode: Chunk[() => Unit] = Chunk.empty

        final def main(args: Array[String]) =
            this._args = args
            if initCode.isEmpty then
                import AllowUnsafe.embrace.danger
                onResult(Result.fail(Ansi.highlight(
                    header = "KyoApp: nothing to execute. Did you forget to use a run block?",
                    code = """|
                              | object Example extends KyoApp:
                              |   run {
                              |     Console.printLine("Hello, world!")
                              |   }
                              |""".stripMargin,
                    trailer = ""
                )))
            else for proc <- initCode do proc()
            end if
        end main

        /** The argument(s) this application was started with. */
        final protected def args: Chunk[String] =
            if _args eq null then Chunk.empty
            else Chunk.fromNoCopy(_args)

        /** Unsafely exits the application. */
        protected def exit(code: Int)(using AllowUnsafe): Unit =
            internal.Platform.exit(code)

        /** Unified handling logic for supporting arbitrary effects. */
        protected def handle[A](v: A < S)(using Frame): A < (Async & Abort[Throwable])

        /** The timeout for each [[run]] block. */
        protected def runTimeout: Duration = Duration.Infinity

        /** The main entrypoint to this application. */
        protected def run[A](v: => A < S)(using Frame, Render[A]): Unit

        /** Handles the result of the [[run]] block computation.
          *
          * Override this method to control how the result is handled.
          */
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
            v: A < (Async & Scope & Abort[Throwable])
        )(using Frame, AllowUnsafe): Result[Throwable, A] =
            Abort.run(Sync.Unsafe.run(KyoApp.runAndBlock(timeout)(Scope.run(v)))).eval
    end Unsafe

end KyoApp
