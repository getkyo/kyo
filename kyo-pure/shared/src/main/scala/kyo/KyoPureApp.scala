package kyo

import scala.collection.mutable.ListBuffer

abstract class KyoPureApp extends KyoPureApp.Base[KyoPureApp.Effects]:

    override protected def handle[T](v: T < KyoPureApp.Effects)(implicit
        f: Flat[T < KyoPureApp.Effects]
    ): Unit =
        println(KyoPureApp.run(v))

end KyoPureApp

object KyoPureApp:

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

    type Effects = Defers & Aborts[Throwable]

    def run[T](v: T < Effects)(
        using f: Flat[T < Effects]
    ): T =
        Defers.run(Aborts.run[Throwable](v)).pure match
            case Left(ex)     => throw ex
            case Right(value) => value

end KyoPureApp
