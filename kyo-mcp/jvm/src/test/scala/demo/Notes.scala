package demo

import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** MCP server backed by an in-memory note store.
  *
  * Demonstrates the three MCP factory kinds the Filesystem demo doesn't cover:
  *   - `toolMulti`: a tool whose handler returns a multi-content `ToolOutcome` directly
  *     (here, a `search_notes` call returns each matching note as a separate text block plus
  *     an `isError` indicator).
  *   - `prompt`: a `summarize_notes` prompt with a typed `topic` argument that the engine
  *     advertises in `prompts/list` and that the user fills via `prompts/get`.
  *   - `completion`: a completion provider scoped to the `summarize_notes` prompt's `topic`
  *     argument; suggests previously-stored note titles when the client types a prefix.
  *
  * Notes are kept in a process-local `ConcurrentHashMap` keyed by title. Empty store at start.
  *
  * No command-line arguments. Drive via stdio with an MCP client (e.g. Claude Code).
  */
object Notes extends KyoApp:

    case class AddNote(title: String, body: String) derives Schema, CanEqual
    case class SearchNotes(query: String) derives Schema, CanEqual

    case class NotesBadInput(reason: String) derives Schema, CanEqual

    private val notes: ConcurrentHashMap[String, String] = new ConcurrentHashMap[String, String]()

    run {
        // -- add_note: stores a note keyed by title (replaces existing). Empty titles are rejected
        // so two empty-title adds don't silently collide on the same map key.
        val addNoteTool =
            McpHandler.tool[AddNote](
                name = "add_note",
                description = "Stores a note keyed by title. Overwrites any existing note with the same title. Title must be non-empty."
            ) { req =>
                if req.title.isEmpty then
                    Abort.fail(NotesBadInput("title must be non-empty"))
                else
                    Sync.defer(notes.put(req.title, req.body))
                        .map(_ => McpContent.text(s"stored note '${req.title}' (${req.body.length} chars)"))
            }
                .error[NotesBadInput](code = -32060, message = "notes-bad-input")

        // -- search_notes: case-insensitive substring search over title + body. Empty query
        // returns no matches (rather than matching everything) so callers don't accidentally
        // page through the entire store.
        val searchNotesTool =
            McpHandler.toolMulti[SearchNotes](
                name = "search_notes",
                description = "Returns every note whose title or body contains the query substring (case-insensitive). " +
                    "An empty query returns no matches."
            ) { req =>
                Sync.defer {
                    import scala.jdk.CollectionConverters.*
                    val q = req.query.toLowerCase
                    val matches =
                        if q.isEmpty then List.empty
                        else
                            notes.entrySet.iterator.asScala
                                .filter(e => e.getKey.toLowerCase.contains(q) || e.getValue.toLowerCase.contains(q))
                                .map(e => McpContent.text(s"${e.getKey}: ${e.getValue}"))
                                .toList
                    val content =
                        if matches.isEmpty then Chunk(McpContent.text(s"No notes matching '${req.query}'."))
                        else Chunk.from(matches)
                    McpHandler.ToolOutcome(
                        content = content,
                        isError = false,
                        structuredContent = Absent
                    )
                }
            }

        // -- summarize_notes: a prompt with a `topic` argument
        val summarizePrompt =
            McpHandler.prompt(
                name = "summarize_notes",
                description = "Summarizes stored notes filtered by topic.",
                arguments = Chunk(McpHandler.PromptArgument(
                    name = "topic",
                    description = Present("Optional topic to filter notes by."),
                    required = false
                ))
            ) { args =>
                Sync.defer {
                    import scala.jdk.CollectionConverters.*
                    val topic = args.get("topic").getOrElse("")
                    val filtered = notes.entrySet.iterator.asScala
                        .filter(e => topic.isEmpty || e.getKey.contains(topic) || e.getValue.contains(topic))
                        .map(e => s"- ${e.getKey}: ${e.getValue}")
                        .toList
                    val body =
                        if filtered.isEmpty then "No matching notes."
                        else s"Notes${if topic.isEmpty then "" else s" about '$topic'"}:\n${filtered.mkString("\n")}"
                    McpHandler.PromptOutcome(
                        description = Present(s"Summary of ${filtered.size} note(s)"),
                        messages = Chunk(McpHandler.PromptMessage(
                            role = McpContent.Role.User,
                            content = McpContent.text(body)
                        ))
                    )
                }
            }

        // -- topic completion for the summarize_notes prompt
        val topicCompletion =
            McpHandler.completion(McpHandler.CompletionRef.Prompt("summarize_notes")) { arg =>
                Sync.defer {
                    import scala.jdk.CollectionConverters.*
                    val titles = Chunk.from(notes.keySet.iterator.asScala.toList)
                    val matches =
                        if arg.value.isEmpty then titles
                        else titles.filter(_.startsWith(arg.value))
                    McpHandler.CompletionOutcome(
                        values = matches,
                        total = Present(matches.size),
                        hasMore = Present(false)
                    )
                }
            }

        JsonRpcTransport.stdio().map { t =>
            McpServer.initWith(t, addNoteTool, searchNotesTool, summarizePrompt, topicCompletion) { _ =>
                Async.never
            }
        }
    }
end Notes
