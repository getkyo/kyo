package demo

import kyo.*

/** Live tool use: the model answers from real Wikipedia data it fetches through a tool.
  *
  * The composition is the point: the LLM decides to call `wikipedia`, the tool runs a real HTTP GET
  * (kyo-http) against Wikipedia's REST API, and the model grounds its answer in the fetched summary. The
  * language model and the HTTP call are both just effects in the same program.
  *
  * Demonstrates: Tool.init with an async (HTTP) body, HttpClient.getJson, AI.enable, LLM.run (auto-config)
  * Run on OpenAI:    OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.WikiResearchDemo"
  * Run on Anthropic: ANTHROPIC_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.WikiResearchDemo"
  */
object WikiResearchDemo extends KyoApp:

    case class Topic(title: String) derives Schema
    case class Summary(title: String, extract: String) derives Schema

    // Wikipedia's API rejects requests without a descriptive User-Agent (their robot policy), so set one.
    val userAgent = Seq("User-Agent" -> "kyo-ai-demo/1.0 (https://github.com/getkyo/kyo)")

    // The tool body makes a real HTTP request; it reports "not found" rather than aborting the generation.
    val wikipedia =
        Tool.init[Topic]("wikipedia", "Fetch the lead summary of a Wikipedia article by its exact title") { topic =>
            Abort.recover[HttpException](_ => Summary(topic.title, "No Wikipedia article found by that exact title.")) {
                HttpClient.getJson[Summary](summaryUrl(topic.title), headers = userAgent)
            }
        }

    run {
        for
            answer <- LLM.run {
                AI.enable(wikipedia) {
                    AI.gen[String]("Who was Ada Lovelace, and why is she historically important? Look it up.")
                }
            }
            _ <- Console.printLine(s"assistant: $answer")
        yield ()
    }

    // Wikipedia's REST path wants the title with underscores for spaces; the model supplies an article title.
    private def summaryUrl(title: String): String =
        s"https://en.wikipedia.org/api/rest_v1/page/summary/${title.trim.replace(" ", "_")}"
end WikiResearchDemo
