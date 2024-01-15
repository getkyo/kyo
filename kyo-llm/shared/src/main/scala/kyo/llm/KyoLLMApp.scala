package kyo.llm

import kyo._
import kyo.requests._
import kyo.llm.ais._
import kyo.llm.configs._
import kyo.llm.tools._
import timers._
import scala.concurrent.duration.Duration
import scala.util.Try
import kyo.timers

abstract class KyoLLMApp extends KyoApp.Base[KyoLLMApp.Effects] {

  def tools: List[Tool] = Nil

  def config: Config = Config.default

  override protected def handle[T](v: T < KyoLLMApp.Effects)(
      implicit f: Flat[T < KyoLLMApp.Effects]
  ) =
    KyoLLMApp.run {
      AIs.configs.let(config) {
        Tools.enable(tools) {
          v.map(Consoles.println(_))
        }
      }
    }
}

object KyoLLMApp {

  type Effects = KyoApp.Effects with AIs

  private def handle[T](v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): T < KyoApp.Effects =
    Requests.run(AIs.run(v))

  def run[T](timeout: Duration)(v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): T =
    KyoApp.run(timeout)(handle(v))

  def run[T](v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): T =
    KyoApp.run(handle(v))

  def runFiber[T](v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): Fiber[Try[T]] =
    KyoApp.runFiber(handle(v))

  def runFiber[T](timeout: Duration)(v: T < Effects)(
      implicit f: Flat[T < Effects]
  ): Fiber[Try[T]] =
    KyoApp.runFiber(timeout)(handle(v))
}
