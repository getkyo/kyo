package kyo

import scala.collection.mutable.ListBuffer

/** An abstract base class for Kyo applications.
  *
  * This class provides a foundation for building applications using the Kyo framework, with built-in support for logging, random number
  * generation, and clock operations.
  *
  * Note: This class and its methods are unsafe and should only be used as the entrypoint of an application.
  */
abstract class KyoApp extends KyoApp.Base[KyoApp.Effects]:
    def log: Log       = Log.live
    def random: Random = Random.live
    def clock: Clock   = Clock.live

    override protected def handle[A: Flat](v: A < KyoApp.Effects)(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        v.map { v =>
            if (()).equals(v) then ()
            else Console.println(v)
        }.pipe(
            Clock.let(clock),
            Random.let(random),
            Log.let(log),
            KyoApp.Unsafe.run
        )
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

        protected def handle[A: Flat](v: A < S)(using Frame): Unit

        final protected def args: Array[String] = _args

        private var _args: Array[String] = null

        private val initCode = new ListBuffer[() => Unit]

        final def main(args: Array[String]) =
            this._args = args
            for proc <- initCode do proc()

        protected def run[A: Flat](v: => A < S)(using Frame): Unit =
            initCode += (() => handle(v))
    end Base

    /** The combined effect type used by KyoApp. */
    type Effects = Async & Resource & Abort[Throwable]

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
          *   A Result containing either the computed value or a Throwable.
          */
        def attempt[A: Flat](timeout: Duration)(v: A < Effects)(using Frame, AllowUnsafe): Result[Throwable, A] =
            IO.Unsafe.run(runFiber(timeout)(v).block(timeout)).eval

        /** Runs an effect with a specified timeout, throwing an exception if it fails.
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
          *   The computed value of type A.
          * @throws Throwable
          *   if the effect fails or times out.
          */
        def run[A: Flat](timeout: Duration)(v: A < Effects)(using Frame, AllowUnsafe): A =
            attempt(timeout)(v).getOrThrow

        /** Runs an effect with an infinite timeout.
          *
          * Note: This method is unsafe and should only be used as the entrypoint of an application.
          *
          * @param v
          *   The effect to run.
          * @param ev
          *   Evidence that A is Flat.
          * @param frame
          *   The implicit Frame.
          * @return
          *   The computed value of type A.
          * @throws Throwable
          *   if the effect fails.
          */
        def run[A: Flat](v: A < Effects)(using Frame, AllowUnsafe): A =
            run(Duration.Infinity)(v)

        /** Creates a Fiber to run an effect with an infinite timeout.
          *
          * Note: This method is unsafe and should only be used as the entrypoint of an application.
          *
          * @param v
          *   The effect to run.
          * @param ev
          *   Evidence that A is Flat.
          * @param frame
          *   The implicit Frame.
          * @return
          *   A Fiber representing the running effect.
          */
        def runFiber[A: Flat](v: A < Effects)(using Frame, AllowUnsafe): Fiber[Throwable, A] =
            runFiber(Duration.Infinity)(v)

        /** Creates a Fiber to run an effect with a specified timeout.
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
          *   A Fiber representing the running effect.
          */
        def runFiber[A: Flat](timeout: Duration)(v: A < Effects)(using Frame, AllowUnsafe): Fiber[Throwable, A] =
            v.pipe(Resource.run, Async.timeout(timeout), Async.run, IO.Unsafe.run).eval
    end Unsafe

end KyoApp
