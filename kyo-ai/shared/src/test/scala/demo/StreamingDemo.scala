package demo

import kyo.*

/** Streaming, in its two forms, inferred from the result type.
  *
  * `ai.stream[A]` projects a generation as a `Stream`. For a `String` it streams incremental text chunks
  * whose concatenation is the final answer (the chat-UI token-by-token case). For any other type it streams
  * object by object: the model produces a sequence of `A` and each element is emitted once it is complete,
  * never a half-filled value. The first run below streams text; the second streams whole `Destination`
  * records.
  *
  * Demonstrates: ai.stream[String] (incremental text), ai.stream[A] (object by object), Stream.foreach
  * Run on OpenAI:    OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.StreamingDemo"
  * Run on Anthropic: ANTHROPIC_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.StreamingDemo"
  */
object StreamingDemo extends KyoApp:

    case class Destination(name: String, country: String) derives Schema

    run {
        LLM.run {
            AI.initWith { ai =>
                for
                    _     <- Console.printLine("-- text streaming --")
                    _     <- ai.userMessage("In two or three sentences, explain why the sky is blue.")
                    text  <- ai.stream[String]
                    _     <- text.foreach(chunk => Console.printLine(s"[${chunk.length} chars] $chunk"))
                    _     <- Console.printLine("-- object streaming --")
                    _     <- ai.userMessage("Recommend four great hiking destinations worldwide, each with its name and country.")
                    dests <- ai.stream[Destination]
                    _     <- dests.foreach(d => Console.printLine(s"got: ${d.name} (${d.country})"))
                yield ()
            }
        }
    }
end StreamingDemo
