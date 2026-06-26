package demo

import kyo.*

/** PromptKit: a reusable prompt-library MCP server.
  *
  * A host surfaces each registered prompt as a slash-command the user can invoke. This demo shows
  * the three things a prompt server does:
  *
  *   - **Typed prompts** (`McpHandler.prompt[In]`): the fields of `In` become the prompt's
  *     advertised arguments, decoded from the inbound argument map into `In` before the handler runs.
  *   - **Multi-message outcomes**: a prompt renders a system message that primes the model plus a
  *     user message that states the task, via `PromptMessage.system` / `.user`.
  *   - **Argument completion** (`McpHandler.completion`): as the user fills an argument, the host
  *     asks for suggestions; the handler filters its candidate pool by the partial value.
  *
  * Run as a stdio MCP server: `java -cp <kyo-mcpJVM test classpath> demo.PromptKit`.
  */
object PromptKit extends KyoApp:

    case class ReviewIn(language: String, focus: String) derives Schema, CanEqual
    case class ExplainIn(topic: String, level: String) derives Schema, CanEqual
    case class CommitIn(diff: String) derives Schema, CanEqual

    private val languages = Chunk("scala", "rust", "typescript", "python", "go")
    private val focuses   = Chunk("correctness", "performance", "readability", "security", "api-design")
    private val levels    = Chunk("beginner", "intermediate", "expert")

    private def suggest(pool: Chunk[String], arg: McpHandler.CompletionArg): McpHandler.CompletionOutcome =
        McpHandler.CompletionOutcome.of(pool.filter(_.startsWith(arg.value))*)

    run {
        val codeReview =
            McpHandler.prompt[ReviewIn]("code-review", "Review a code snippet in a language with a chosen focus") { in =>
                McpHandler.PromptOutcome.of(
                    Present(s"${in.language} review focused on ${in.focus}"),
                    McpHandler.PromptMessage.system(s"You are a meticulous ${in.language} reviewer. Judge strictly on ${in.focus}."),
                    McpHandler.PromptMessage.user(s"Review the ${in.language} code I provide, focused on ${in.focus}.")
                )
            }

        val explain =
            McpHandler.prompt[ExplainIn]("explain", "Explain a topic at a chosen level") { in =>
                McpHandler.PromptOutcome.of(
                    Present(s"${in.topic} for a ${in.level} audience"),
                    McpHandler.PromptMessage.system(s"Explain at a ${in.level} level; do not exceed that bar."),
                    McpHandler.PromptMessage.user(s"Explain ${in.topic}.")
                )
            }

        val commitMessage =
            McpHandler.prompt[CommitIn]("commit-message", "Draft a commit message from a diff") { in =>
                McpHandler.PromptOutcome.of(
                    Absent,
                    McpHandler.PromptMessage.system("Write a Problem/Solution commit message. No marketing, no dashes."),
                    McpHandler.PromptMessage.user(s"Diff:\n${in.diff}")
                )
            }

        val completeReview =
            McpHandler.completion(codeReview) { arg =>
                suggest(if arg.name == "language" then languages else focuses, arg)
            }

        val completeExplain =
            McpHandler.completion(explain) { arg =>
                suggest(if arg.name == "level" then levels else Chunk.empty, arg)
            }

        val handlers = Seq[McpHandler[?, ?, ?]](codeReview, explain, commitMessage, completeReview, completeExplain)
        JsonRpcTransport.stdio().map(t => McpServer.initWith(t, handlers*)(_ => Async.never))
    }
end PromptKit
