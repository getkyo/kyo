package linkcheck

// Outside the kyo package, as an application is.
import kyo.*
import kyo.ai.*

/** A program that names one HTTP provider and reads its completion: the path an application takes when it talks to a model over the
  * network, and nothing more.
  *
  * What it is here to prove is what it does NOT link. kyo-ai also ships two CLI harnesses, which spawn a process through `kyo.Process` and
  * reach `node:child_process`; a program that never names one has no use for that code, and a browser page could not run it at all. The
  * `linkCheck` command reads this program's linked output for that marker.
  */
object AiHttp:
    def main(args: Array[String]): Unit =
        val completion = Config.OpenAI.default.provider.completion
        println(s"completion streams ${completion.streamsIncrementally}")
    end main
end AiHttp
