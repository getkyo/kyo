package kyo

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.util.Try

abstract class KyoApp extends KyoApp.Base[KyoApp.Effects]:

    override protected def handle[T](v: T < KyoApp.Effects)(implicit
        f: Flat[T < KyoApp.Effects]
    ): Unit =
        KyoApp.run(v.map(Consoles.println(_)))
end KyoApp

object KyoApp:

    abstract class Base[S]:

        protected def handle[T](v: T < S)(using f: Flat[T < S]): Unit

        final protected def args: Array[String] = _args

        private var _args: Array[String] = null

        private val initCode = new ListBuffer[() => Unit]

        final def main(args: Array[String]) =
            this._args = args
            for proc <- initCode do proc()

        protected def run[T](v: => T < S)(
            using f: Flat[T < S]
        ): Unit =
            initCode += (() => handle(v))
    end Base

    type Effects = Fibers & Resources & Aborts[Throwable]

    def attempt[T](timeout: Duration)(v: T < Effects)(
        using f: Flat[T < Effects]
    ): Try[T] =
        IOs.run(runFiber(timeout)(v).block(timeout))

    def run[T](timeout: Duration)(v: T < Effects)(
        using f: Flat[T < Effects]
    ): T =
        attempt(timeout)(v).get

    def run[T](v: T < Effects)(
        using f: Flat[T < Effects]
    ): T =
        run(Duration.Inf)(v)

    def runFiber[T](v: T < Effects)(
        using f: Flat[T < Effects]
    ): Fiber[Try[T]] =
        runFiber(Duration.Inf)(v)

    def runFiber[T](timeout: Duration)(v: T < Effects)(
        using f: Flat[T < Effects]
    ): Fiber[Try[T]] =
        def v0: Try[T] < (Fibers & Resources) = Aborts[Throwable].run(v).map(_.toTry)
        def v1: Try[T] < Fibers               = Resources.run(v0)
        def v2: Try[T] < Fibers               = IOs.attempt(v1).map(_.flatten)
        def v3: Try[T] < Fibers               = Fibers.timeout(timeout)(v2)
        def v4: Try[T] < Fibers               = IOs.attempt(v3).map(_.flatten)
        def v5: Try[T] < Fibers               = Fibers.init(v4).map(_.get)
        def v6: Fiber[Try[T]] < IOs           = Fibers.run(v5)
        IOs.run(v6)
    end runFiber
end KyoApp
