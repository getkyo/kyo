package linkcheck

// Outside the kyo package, as an application is.
import kyo.*
import kyo.ai.*

/** A program that asks one HTTP provider for a generation, against a port nothing answers on.
  *
  * The request is meant to fail: what the program is for is the path it links on the way there, which is the whole HTTP completion, the
  * client under it, and the error classification that names the failure. An application talking to a model links exactly this.
  *
  * What it must NOT link is the other half of kyo-ai: two CLI harnesses that spawn a process through `kyo.Process`, which reaches
  * `node:child_process`. A program that names neither harness has no use for them, and a browser page could not run them at all, so
  * `linkCheck` reads this program's linked output for that marker.
  */
object AiHttp extends KyoApp:
    run {
        val config = Config.OpenAI.default.apiKey("link-check").apiUrl("http://127.0.0.1:1/v1")
        Abort.run[Any](LLM.run(config)(AI.gen[String]("hello"))).map(result => Console.printLine(Report(result)))
    }
end AiHttp

/** A program that names no provider and lets `Config.default` choose one, which is what an application does when it sets no
  * `kyo.ai.provider`.
  *
  * [[AiHttp]] holds the rule that a program with no use for a process must not carry `node:child_process`, but it holds it on the path
  * where the provider is named at the call site. This is the other path: auto-detect probes `Provider.defaultCandidates`, and that list
  * names the two CLI harnesses ahead of every API provider. Naming a provider to probe for its key is not using its completion, so the
  * same rule applies here, and this is the path where it is easy to lose.
  */
object AiAuto extends KyoApp:
    run {
        Abort.run[Any](Config.default.map(_ => "auto")).map(result => Console.printLine(Report(result)))
    }
end AiAuto
