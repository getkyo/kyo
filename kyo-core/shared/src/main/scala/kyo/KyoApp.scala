package kyo

/** An abstract base class for Kyo applications.
  *
  * This class provides a foundation for building applications using the Kyo framework, with built-in support for logging, random number
  * generation, and clock operations.
  *
  * Note: This class and its methods are unsafe and should only be used as the entrypoint of an application.
  */
abstract class KyoApp extends KyoAppPlatformSpecific

object KyoApp:
    final case class FailureException private[kyo] (error: Any)(using Frame)
        extends KyoException("Uncaught error", String.valueOf(error))

    def apply[A](v: => A < (Async & Scope & Abort[Any]))(using Frame, Render[A]): KyoApp =
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
        using frame: Frame
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
        self: KyoAppRunner =>

        private var _args: Array[String] = null

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
            else runInitCode()
            end if
        end main

        /** The argument(s) this application was started with. */
        final protected def args: Chunk[String] =
            if _args eq null then Chunk.empty
            else Chunk.fromNoCopy(_args)

        /** Unsafely exits the application. */
        protected def exit(code: Int)(using AllowUnsafe): Unit =
            internal.Platform.exit(code)

        /** The main entrypoint to this application. */
        protected def run[A](v: => A < S)(using Frame, Render[A]): Unit
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
            v: A < (Async & Scope & Abort[Any])
        )(using Frame, AllowUnsafe): Result[Throwable, A] =
            Abort.run(Sync.Unsafe.run(KyoApp.runAndBlock(timeout)(KyoApp.abortAnyToThrowable(Scope.run(v))))).eval
    end Unsafe

    private[kyo] def abortAnyToThrowable[A, S](v: => A < (Abort[Any] & S))(using Frame): A < (Abort[Throwable] & S) =
        Abort.run[Any](v).map {
            case Result.Success(value)            => value
            case Result.Failure(error: Throwable) => Abort.fail(error)
            case Result.Failure(error)            => Abort.fail(FailureException(error))
            case panic: Result.Panic              => Abort.get(panic)
        }
    end abortAnyToThrowable

end KyoApp
