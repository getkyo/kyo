package kyo

import scala.concurrent.duration.Duration
import scala.util.Try
import kyo.KyoApp.Effects
import scala.annotation.nowarn

abstract class KyoApp extends KyoApp.Base[KyoApp.Effects] {

  override protected def handle[T](v: T < Effects)(implicit f: Flat[T < Effects]): Unit =
    KyoApp.run(v.map(Consoles.println(_)))
}

object KyoApp {

  abstract class Base[S] extends App {

    protected def handle[T](v: T < S)(implicit f: Flat[T < S]): Unit

    @nowarn
    protected def run[T](v: => T < S)(
        implicit f: Flat[T < S]
    ): Unit =
      delayedInit(handle(v))
  }

  type Effects =
    Fibers with Resources with Tries

  def run[T](timeout: Duration)(v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): T =
    IOs.run(runFiber(timeout)(v).block.map(_.get))(kyo.Flat.unsafe.checked)

  def run[T](v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): T =
    run(Duration.Inf)(v)

  def runFiber[T](v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): Fiber[Try[T]] =
    runFiber(Duration.Inf)(v)

  def runFiber[T](timeout: Duration)(v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): Fiber[Try[T]] = {

    // since scala 2 can't use the macro in the same compilation unit
    implicit def flat[T, S]: Flat[T < S] = Flat.unsafe.checked

    def v1: T < (Fibers with Tries) = Resources.run(v)
    def v2: Try[T] < Fibers         = Tries.run(v1)
    def v3: Try[T] < Fibers         = Fibers.timeout(timeout)(v2)
    def v4: Try[T] < Fibers         = Tries.run(v3).map(_.flatten)
    def v5: Try[T] < Fibers         = Fibers.init(v4).map(_.get)
    def v6: Fiber[Try[T]] < IOs     = Fibers.run(v5)
    IOs.run(v6)
  }
}
