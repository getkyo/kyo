package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Path as KPath

/** MCP server exposing read-only `git` introspection for a configured repository.
  *
  * Three tools (`git_status`, `git_log`, `git_diff`) plus a concrete `repo://summary`
  * resource that bundles status + latest commit. Demonstrates:
  *
  *   - [[kyo.Command]] for safe subprocess invocation (no shell interpretation; arguments
  *     are passed verbatim, so user-supplied refs cannot inject extra args).
  *   - Typed [[kyo.CommandException]] mapping to a user-visible `GitError` via
  *     `McpHandler.error[E2]`.
  *   - [[kyo.Path]] for the working directory; no `java.nio` in the handler bodies.
  *   - [[McpHandler.resource]] (the concrete, non-template variant) for the summary URI.
  *
  * Pass the absolute path to the git repository as the first command-line argument;
  * defaults to the current working directory. The repository is not validated at start;
  * each tool surfaces a `GitError` if `git` fails (e.g. not a repo, permission denied).
  */
object GitInsight extends KyoApp:

    // MARK: -- tool argument types

    case class GitLog(limit: Maybe[Int] = Absent) derives Schema, CanEqual
    case class GitDiff(staged: Maybe[Boolean] = Absent) derives Schema, CanEqual

    // MARK: -- typed application error

    case class GitError(reason: String, exitCode: Maybe[Int] = Absent) derives Schema, CanEqual

    // MARK: -- main

    run {
        val repoArg = args.headOption.getOrElse(".")
        val repo    = KPath.of(java.nio.file.Paths.get(repoArg).toAbsolutePath.normalize())

        // Run a `git` subcommand under `repo`, returning the trimmed stdout. Any pre-launch failure
        // (missing program, permission denied, missing cwd) or non-zero exit is mapped to GitError.
        def git(subcommand: String*)(using Frame): String < (Async & Abort[GitError]) =
            // Build the full argv as one Seq so the splat is the only varargs argument.
            val argv = "git" +: subcommand
            Command(argv*)
                .cwd(repo)
                .text
                .map(_.trim)
                .handle(Abort.recover[CommandException](ex => Abort.fail(GitError(reason = ex.getMessage))))
        end git

        // -- git_status: short porcelain output, one line per changed path.
        val statusTool =
            McpHandler.tool[Unit](
                name = "git_status",
                description = "Run `git status --short` in the configured repo."
            ) { _ =>
                git("status", "--short").map { out =>
                    McpContent.text(if out.isEmpty then "(working tree clean)" else out)
                }
            }.error[GitError](code = -32010, message = "git-error")

        // -- git_log: recent commits, oneline format. Limit is optional and capped at 100.
        val logTool =
            McpHandler.tool[GitLog](
                name = "git_log",
                description = "Recent commits as oneline. Optional `limit` (default 10, max 100)."
            ) { req =>
                val n = req.limit.getOrElse(10).max(1).min(100)
                git("log", "--oneline", s"-n$n").map(McpContent.text(_))
            }.error[GitError](code = -32010, message = "git-error")

        // -- git_diff: pending or staged diff (default: unstaged).
        val diffTool =
            McpHandler.tool[GitDiff](
                name = "git_diff",
                description = "Show the working diff. Set `staged: true` for the index diff."
            ) { req =>
                val args = if req.staged.getOrElse(false) then Seq("diff", "--staged") else Seq("diff")
                git(args*).map { out =>
                    McpContent.text(if out.isEmpty then "(no changes)" else out)
                }
            }.error[GitError](code = -32010, message = "git-error")

        // -- concrete resource: a one-shot summary URI bundling status + latest commit.
        val summaryUri = McpResourceUri("repo://summary")
        val summary =
            McpHandler.resource(
                uri = summaryUri,
                name = "repo-summary",
                description = "Combined `git status --short` + latest commit, for one-call read.",
                mimeType = Present(McpMimeType("text/plain"))
            ) {
                for
                    status <- git("status", "--short").handle(
                        Abort.recover[GitError](e => s"(status unavailable: ${e.reason})")
                    )
                    head <- git("log", "-1", "--oneline").handle(
                        Abort.recover[GitError](e => s"(log unavailable: ${e.reason})")
                    )
                yield Chunk(McpHandler.ResourceContents.text(
                    uri = summaryUri,
                    text = s"HEAD: $head\n\nStatus:\n${if status.isEmpty then "  (clean)" else status}",
                    mimeType = Present(McpMimeType("text/plain"))
                ))
            }

        JsonRpcTransport.stdio().map { t =>
            McpServer.initWith(t, statusTool, logTool, diffTool, summary) { _ =>
                Async.never
            }
        }
    }
end GitInsight
