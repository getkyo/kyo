package kyo

import scala.collection.mutable.ListBuffer

/** An abstract base class for Kyo applications.
  *
  * This class provides a foundation for building applications using the Kyo framework, with built-in support for logging, random number
  * generation, and clock operations.
  *
  * Note: This class and its methods are unsafe and should only be used as the entrypoint of an application.
  */
abstract class KyoApp extends KyoApp.Base[Async & Resource & Abort[Throwable]]:
    def timeout: Duration = Duration.Infinity

    override protected def handle[A: Flat](v: A < (Async & Resource & Abort[Throwable]))(using Frame): Result[Any, A] =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(timeout)(v)
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

        protected def handle[A: Flat](v: A < S)(using Frame): Result[Any, A]

        final protected def args: Array[String] = _args

        private var _args: Array[String] = null

        private val initCode = new ListBuffer[() => Unit]

        final def main(args: Array[String]) =
            this._args = args
            for proc <- initCode do proc()

        protected def exit(code: Int): Unit = java.lang.System.exit(code)

        protected def onResult(result: Result[Any, Any]): Unit =
            if !result.exists(().equals(_)) then
                println(pprint.apply(result).plainText)
            result match
                case Result.Error(_) => exit(1)
                case _               => ()
        end onResult

        protected def run[A: Flat](v: => A < S)(using Frame): Unit =
            initCode += (() => onResult(handle(v)))
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
          * @param ev
          *   Evidence that A is Flat.
          * @param frame
          *   The implicit Frame.
          * @return
          *   A Result containing either the computed value or a failure.
          */
        def runAndBlock[A: Flat](timeout: Duration)(
            v: A < (Async & Resource & Abort[Throwable])
        )(using Frame, AllowUnsafe): Result[Throwable, A] =
            IO.Unsafe.run(Abort.run(Async.runAndBlock(timeout)(Resource.run(v)))).eval
    end Unsafe

end KyoApp
