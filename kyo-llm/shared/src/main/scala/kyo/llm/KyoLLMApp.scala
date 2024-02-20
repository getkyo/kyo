package kyo.llm

import kyo._

import scala.concurrent.duration.Duration
import scala.util.Try

abstract class KyoLLMApp extends KyoApp.Base[KyoLLMApp.Effects] {

  def tools: List[Tool] = Nil

  def config: Config = Config.default

  override protected def handle[T](v: T < KyoLLMApp.Effects)(
      using f: Flat[T < KyoLLMApp.Effects]
  ) =
    KyoLLMApp.run {
      Configs.let(config) {
        Tools.enable(tools) {
          v.map(Consoles.println(_))
        }
      }
    }
}

object KyoLLMApp {

  type Effects = KyoApp.Effects & AIs

  private def handle[T](v: T < Effects)(
      using f: Flat[T < Effects]
  ): T < KyoApp.Effects =
    AIs.run(v)

  def run[T](timeout: Duration)(v: T < Effects)(
      using f: Flat[T < Effects]
  ): T =
    KyoApp.run(timeout)(handle(v))

  def run[T](v: T < Effects)(
      using f: Flat[T < Effects]
  ): T =
    KyoApp.run(handle(v))

  def runFiber[T](v: T < Effects)(
      using f: Flat[T < Effects]
  ): Fiber[Try[T]] =
    KyoApp.runFiber(handle(v))

  def runFiber[T](timeout: Duration)(v: T < Effects)(
      using f: Flat[T < Effects]
  ): Fiber[Try[T]] =
    KyoApp.runFiber(timeout)(handle(v))
}
