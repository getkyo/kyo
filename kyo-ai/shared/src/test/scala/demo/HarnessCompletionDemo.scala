package demo

import kyo.*

/** Harness completion: exercises the command-backed providers with one persistent `AI` instance.
  *
  * This demo keeps message history in a named instance, sends a system message, sends an image-bearing user
  * message, performs a typed generation, then performs a second typed generation whose prompt only makes
  * sense if the previous assistant result is still in the instance history.
  *
  * Demonstrates: Config.Codex or Config.ClaudeCode selection, AI.initWith, system messages, image messages,
  * typed schema output, and multi-turn history.
  */
object HarnessCompletionDemo extends KyoApp:

    case class FirstTurn(marker: String, imageObserved: Boolean, description: String) derives Schema
    case class SecondTurn(marker: String, rememberedDescription: String, historyUsed: Boolean) derives Schema

    private val marker = "kyo-harness-demo-red-pixel"

    private val redPixelJpeg =
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcp" +
            "LDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy" +
            "MjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA" +
            "AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6" +
            "Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
            "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA" +
            "AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5" +
            "OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPE" +
            "xcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDi6KKK+ZP3E//Z"

    run {
        LLM.run {
            AI.initWith { ai =>
                for
                    config <- AI.config
                    _      <- Console.printLine(s"backend:             ${config.provider.name}")
                    _      <- Console.printLine(s"model:               ${config.modelName}")
                    _ <- ai.systemMessage(
                        "You are validating a harness completion adapter. Return compact factual values. " +
                            s"Preserve the marker '$marker' exactly when asked."
                    )
                    _ <- ai.userMessage(
                        s"Look at the attached image and remember this marker: $marker. " +
                            "Describe the image in five words or fewer.",
                        AI.Image.fromBase64(redPixelJpeg)
                    )
                    first <- ai.gen[FirstTurn]
                    _ <- ai.userMessage(
                        "Using only the conversation so far, return the same marker and the remembered image description. " +
                            "Set historyUsed to true only if the prior assistant result was used."
                    )
                    second <- ai.gen[SecondTurn]
                    _      <- Console.printLine(s"first marker:        ${first.marker}")
                    _      <- Console.printLine(s"image observed:      ${first.imageObserved}")
                    _      <- Console.printLine(s"description:         ${first.description}")
                    _      <- Console.printLine(s"second marker:       ${second.marker}")
                    _      <- Console.printLine(s"remembered:          ${second.rememberedDescription}")
                    _      <- Console.printLine(s"history used:        ${second.historyUsed}")
                yield ()
            }
        }
    }
end HarnessCompletionDemo
