package kyo.llm

import kyo._
import kyo.requests._
import kyo.llm.ais._
import kyo.llm.configs._
import kyo.llm.agents._
import kyo.consoles._
import concurrent.fibers._
import concurrent.timers._
import scala.concurrent.duration.Duration
import scala.util.Try

abstract class KyoLLMApp {

  private var _args: List[String]  = List.empty
  protected def args: List[String] = _args

  def agents: List[Agent] = Nil

  def config: Config = Config.default

  final def main(args: Array[String]): Unit = {
    _args = args.toList
    KyoLLMApp.run {
      AIs.configs.let(config) {
        Agents.enable(agents) {
          run.map(Consoles.println(_))
        }
      }
    }
  }

  def run: Any > KyoLLMApp.Effects
}

object KyoLLMApp {

  type Effects = KyoApp.Effects with AIs

  private def handle[T](v: T > Effects)(
      implicit f: Flat[T > Effects]
  ): T > KyoApp.Effects =
    Requests.run(AIs.run(v))

  def run[T](timeout: Duration)(v: T > Effects)(
      implicit f: Flat[T > Effects]
  ): T =
    KyoApp.run(timeout)(handle(v))

  def run[T](v: T > Effects)(
      implicit f: Flat[T > Effects]
  ): T =
    KyoApp.run(handle(v))

  def runFiber[T](v: T > Effects)(
      implicit f: Flat[T > Effects]
  ): Fiber[Try[T]] =
    KyoApp.runFiber(handle(v))

  def runFiber[T](timeout: Duration)(v: T > Effects)(
      implicit f: Flat[T > Effects]
  ): Fiber[Try[T]] =
    KyoApp.runFiber(timeout)(handle(v))
}
