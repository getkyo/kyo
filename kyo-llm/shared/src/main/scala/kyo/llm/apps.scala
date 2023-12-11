package kyo.llm

import kyo._
import kyo.requests.Requests
import kyo.llm.ais.AIs
import kyo.consoles.Consoles

object apps {

  abstract class App {

    private var _args: List[String]  = List.empty
    protected def args: List[String] = _args

    final def main(args: Array[String]): Unit = {
      _args = args.toList
      kyo.apps.App.run {
        Requests.run {
          AIs.run {
            run.map(Consoles.println(_))
          }
        }
      }
    }

    def run: Any < (kyo.apps.App.Effects with AIs)
  }
}
