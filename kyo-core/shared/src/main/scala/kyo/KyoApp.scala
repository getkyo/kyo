package kyo

import scala.collection.mutable.ListBuffer
import scala.util.Try

abstract class KyoApp extends KyoApp.Base[KyoApp.Effects]:
    def log: Log.Unsafe = Log.unsafe
    def random: Random  = Random.live
    def clock: Clock    = Clock.live

    override protected def handle[A: Flat](v: A < KyoApp.Effects)(using Frame): Unit =
        v.map(Console.println)
            .pipe(Clock.let(clock))
            .pipe(Random.let(random))
            .pipe(Log.let(log))
            .pipe(KyoApp.run)
end KyoApp

object KyoApp:

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

    type Effects = Async & Resource & Abort[Throwable]

    def attempt[A: Flat](timeout: Duration)(v: A < Effects)(using Frame): Result[Throwable, A] =
        IO.run(runFiber(timeout)(v).block(timeout)).eval

    def run[A: Flat](timeout: Duration)(v: A < Effects)(using Frame): A =
        attempt(timeout)(v).getOrThrow

    def run[A: Flat](v: A < Effects)(using Frame): A =
        run(Duration.Infinity)(v)

    def runFiber[A: Flat](v: A < Effects)(using Frame): Fiber[Throwable, A] =
        runFiber(Duration.Infinity)(v)

    def runFiber[A: Flat](timeout: Duration)(v: A < Effects)(using Frame): Fiber[Throwable, A] =
        v.pipe(Resource.run).pipe(Async.run).pipe(IO.run).eval
end KyoApp
