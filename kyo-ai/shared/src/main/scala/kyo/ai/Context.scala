package kyo.ai

import Context.*
import kyo.*

/** The conversation history for an LLM interaction: an ordered sequence of typed messages.
  *
  * `Context` IS the conversation. Each builder appends a typed `Message` and returns a new `Context`
  * (the value is immutable). Builders log-and-skip degenerate inputs: a blank system/user/assistant
  * content is dropped, a user message with neither content nor image is dropped, an assistant message
  * with neither content nor calls is dropped. `Role` carries the exact lowercase wire-strings the
  * providers require (`system`/`user`/`assistant`/`tool`), surfaced via `role.name` on every request.
  *
  * `merge` is prefix-aware: it finds the common prefix shared with the argument and appends only the
  * non-common suffix, so merging an accumulated context with a derived one never duplicates the shared
  * history. `Context`, `Role`, `CallId`, and the `Message` trait all `derives CanEqual` so the
  * equality-based merge and context-repair logic compile under strict equality.
  *
  * @param messages
  *   the ordered conversation messages
  */
case class Context(messages: Chunk[Message]) derives CanEqual, Schema:

    /** Appends a message unconditionally. */
    def add(msg: Message): Context =
        Context(messages.append(msg))

    /** Appends a system message, skipping blank content. */
    def systemMessage(content: String): Context =
        if content.isBlank then this
        else add(SystemMessage(content))

    /** Appends a user message, skipping when both content is blank and no image is present. */
    def userMessage(content: String, image: Maybe[Image] = Absent): Context =
        if content.isBlank && image.isEmpty then this
        else add(UserMessage(content, image))

    /** Appends an assistant message, skipping when both content is blank and there are no calls. */
    def assistantMessage(content: String, calls: Chunk[Call] = Chunk.empty): Context =
        if content.isBlank && calls.isEmpty then this
        else add(AssistantMessage(content, calls))

    /** Appends a tool-result message. */
    def toolMessage(callId: CallId, content: String): Context =
        add(ToolMessage(callId, content))

    /** Whether the conversation has no messages. */
    def isEmpty: Boolean = messages.isEmpty

    /** Prefix-aware merge: appends only the argument's non-common suffix, never duplicating shared history. */
    def merge(that: Context): Context =
        val commonPrefix = messages.zip(that.messages).takeWhile((a, b) => a == b).size
        Context(messages.concat(that.messages.drop(commonPrefix)))

end Context

object Context:

    /** The empty conversation. */
    val empty: Context = Context(Chunk.empty)

    /** A message role carrying its exact lowercase provider wire-string. */
    enum Role(val name: String) derives CanEqual:
        case System    extends Role("system")
        case User      extends Role("user")
        case Assistant extends Role("assistant")
        case Tool      extends Role("tool")
    end Role

    /** The provider-assigned identifier of a tool call. */
    case class CallId(id: String) derives CanEqual, Schema

    /** A single tool call requested by the assistant: the call id, the function name, the raw argument JSON. */
    case class Call(id: CallId, function: String, arguments: String) derives Schema

    /** A conversation message, tagged with its role. */
    sealed trait Message(val role: Role) derives CanEqual, Schema:
        def content: String

    /** A system instruction message. */
    case class SystemMessage(content: String) extends Message(Role.System)

    /** A user message, optionally carrying an image for vision models. */
    case class UserMessage(content: String, image: Maybe[Image]) extends Message(Role.User)

    /** An assistant reply, optionally carrying tool calls. */
    case class AssistantMessage(content: String, calls: Chunk[Call] = Chunk.empty) extends Message(Role.Assistant)

    /** A tool-result message answering a prior call. */
    case class ToolMessage(callId: CallId, content: String) extends Message(Role.Tool)

end Context
