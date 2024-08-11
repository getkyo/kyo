package kyo

import scala.collection.mutable.ListBuffer
import scala.util.Try

abstract class KyoApp extends KyoApp.Base[KyoApp.Effects]:
    def log: Log.Unsafe = Log.unsafe
    def random: Random  = Random.live
    def clock: Clock    = Clock.live

    override protected def handle[T: Flat](v: T < KyoApp.Effects)(using Frame): Unit =
        v.map(Console.println)
            .pipe(Clock.let(clock))
            .pipe(Random.let(random))
            .pipe(Log.let(log))
            .pipe(KyoApp.run)
end KyoApp

object KyoApp:

    abstract class Base[S]:

        protected def handle[T: Flat](v: T < S)(using Frame): Unit

        final protected def args: Array[String] = _args

        private var _args: Array[String] = null

        private val initCode = new ListBuffer[() => Unit]

        final def main(args: Array[String]) =
            this._args = args
            for proc <- initCode do proc()

        protected def run[T: Flat](v: => T < S)(using Frame): Unit =
            initCode += (() => handle(v))
    end Base

    type Effects = Async & Resource & Abort[Throwable]

    def attempt[T: Flat](timeout: Duration)(v: T < Effects)(using Frame): Result[Throwable, T] =
        IO.run(runFiber(timeout)(v).block(timeout)).eval

    def run[T: Flat](timeout: Duration)(v: T < Effects)(using Frame): T =
        attempt(timeout)(v).getOrThrow

    def run[T: Flat](v: T < Effects)(using Frame): T =
        run(Duration.Infinity)(v)

    def runFiber[T: Flat](v: T < Effects)(using Frame): Fiber[Throwable, T] =
        runFiber(Duration.Infinity)(v)

    def runFiber[T: Flat](timeout: Duration)(v: T < Effects)(using Frame): Fiber[Throwable, T] =
        v.pipe(Resource.run).pipe(Async.run).pipe(IO.run).eval
end KyoApp
