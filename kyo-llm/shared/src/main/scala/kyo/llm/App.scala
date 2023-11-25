package kyo.llm

import kyo._
import kyo.requests.Requests
import kyo.llm.ais.AIs
import kyo.consoles.Consoles

abstract class App {

  final def main(args: Array[String]): Unit =
    kyo.App.run {
      Requests.run {
        AIs.run {
          run(args.toList)
            .map(Consoles.println(_))
        }
      }
    }

  def run(
      args: List[String]
  ): Any > (kyo.App.Effects with AIs)
}
