package kyo

import scala.collection.mutable.ListBuffer
import scala.util.Try

abstract class KyoApp extends KyoApp.Base[KyoApp.Effects]:
    def log: Logs.Unsafe = Logs.unsafe
    def random: Random   = Random.default
    def clock: Clock     = Clock.default

    override protected def handle[T: Flat](v: T < KyoApp.Effects): Unit =
        KyoApp.run {
            Logs.let(log) {
                Randoms.let(random) {
                    Clocks.let(clock) {
                        v.map(Consoles.println)
                    }
                }
            }
        }
end KyoApp

object KyoApp:

    abstract class Base[S]:

        protected def handle[T: Flat](v: T < S): Unit

        final protected def args: Array[String] = _args

        private var _args: Array[String] = null

        private val initCode = new ListBuffer[() => Unit]

        final def main(args: Array[String]) =
            this._args = args
            for proc <- initCode do proc()

        protected def run[T: Flat](v: => T < S): Unit =
            initCode += (() => handle(v))
    end Base

    type Effects = Fibers & Resources & Aborts[Throwable] & Consoles

    def attempt[T: Flat](timeout: Duration)(v: T < Effects): Try[T] =
        IOs.run(runFiber(timeout)(v).block(timeout))

    def run[T: Flat](timeout: Duration)(v: T < Effects): T =
        attempt(timeout)(v).get

    def run[T: Flat](v: T < Effects): T =
        run(Duration.Infinity)(v)

    def runFiber[T: Flat](v: T < Effects): Fiber[Try[T]] =
        runFiber(Duration.Infinity)(v)

    def runFiber[T: Flat](timeout: Duration)(v: T < Effects): Fiber[Try[T]] =
        def v0: Try[T] < (Fibers & Resources & Consoles) = Aborts.run[Throwable](v).map(_.toTry)
        def v1: Try[T] < (Fibers & Resources)            = Consoles.run(v0)
        def v2: Try[T] < Fibers                          = Resources.run(v1)
        def v3: Try[T] < Fibers                          = IOs.attempt(v2).map(_.flatten)
        def v4: Try[T] < Fibers                          = Fibers.timeout(timeout)(v3)
        def v5: Try[T] < Fibers                          = IOs.attempt(v4).map(_.flatten)
        def v6: Try[T] < Fibers                          = Fibers.init(v5).map(_.get)
        def v7: Fiber[Try[T]] < IOs                      = Fibers.run(v6)
        IOs.run(v7)
    end runFiber
end KyoApp
