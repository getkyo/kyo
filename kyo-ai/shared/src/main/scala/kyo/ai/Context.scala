package kyo.ai

import Context.*
import kyo.*

/** raw is the full transcript; compacted is exactly what providers are sent. They start identical
  * (structural sharing) and diverge only at the first boundary render, which the Compactor performs;
  * Context interprets neither list. add appends to BOTH (the pairing invariant); every builder
  * delegates to add. raw is append-only under compaction (a Compactor rebuilds only compacted and can
  * never touch raw), with ONE sanctioned exception: tool dispatch reconciles the transient "Processing
  * tool call" placeholder in the tail of BOTH lists symmetrically (replacing it with the result, or
  * removing it and the failing call on a decode failure); no other path rewrites raw.
  */
case class Context(raw: Chunk[Message], compacted: Chunk[Message]) derives CanEqual, Schema:

    /** Appends a message to BOTH lists unconditionally (the pairing invariant). */
    def add(msg: Message): Context =
        Context(raw.append(msg), compacted.append(msg))

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

    /** Whether the conversation has no raw messages. */
    def isEmpty: Boolean = raw.isEmpty

    /** Both-list prefix-aware merge: common prefix on raw (by CORE fields, ignoring enrichment),
      * the argument fork's non-common raw suffix appended verbatim to BOTH lists, keeping the
      * receiver's frozen compacted prefix (view-prefix-consistency by construction).
      */
    def merge(that: Context): Context =
        val n    = raw.zip(that.raw).takeWhile((a, b) => Context.coreEq(a, b)).size
        val tail = that.raw.drop(n)
        Context(raw.concat(tail), compacted.concat(tail))
    end merge

end Context

object Context:

    /** The raw index range a synthetic compacted entry stands for, plus the since-demotion
      * watermark. Lives on a Message inside compacted (not a third Context structure): start is the
      * covered unit id, end is exclusive, since is the raw index at the boundary that demoted it.
      */
    case class Origin(start: Int, end: Int, since: Int) derives CanEqual, Schema

    /** The empty conversation (both lists empty). */
    val empty: Context = Context(Chunk.empty, Chunk.empty)

    /** Single-arg factory: raw = compacted = messages (no compaction on a freshly built Context).
      * Keeps every existing Context(msgs) call site compiling while the field split lands.
      */
    def apply(messages: Chunk[Message]): Context = Context(messages, messages)

    /** CORE-field equality: content/role/image/calls/callId only, ignoring embedding/tokens/origin,
      * so two content-identical messages differing solely in enrichment state compare as the same.
      * INTERNAL: used by the default Compactor and Context.merge for deduplication; a custom
      * Compactor is not obligated to honor it, so this is not a lock symbol.
      */
    private[kyo] def coreEq(a: Message, b: Message): Boolean =
        (a, b) match
            case (SystemMessage(c1, _, _, _), SystemMessage(c2, _, _, _))               => c1 == c2
            case (UserMessage(c1, i1, _, _, _), UserMessage(c2, i2, _, _, _))           => c1 == c2 && i1 == i2
            case (AssistantMessage(c1, k1, _, _, _), AssistantMessage(c2, k2, _, _, _)) => c1 == c2 && k1 == k2
            case (ToolMessage(id1, c1, _, _, _), ToolMessage(id2, c2, _, _, _))         => id1 == id2 && c1 == c2
            case _                                                                      => false

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
    case class Call(id: CallId, function: String, arguments: String) derives Schema, CanEqual

    /** A conversation message, tagged with its role. Each leaf carries three trailing defaulted
      * enrichment fields (embedding/tokens/origin): once-computed facts living on the message value that
      * owns them with no separate cache structure. `tokens` is the real token count the compaction seam
      * stamps once per message; `embedding` is the vector the seam stamps at a boundary when an embedding
      * is configured; `origin` is set only on a synthetic entry a Compactor builds to stand for a raw range.
      */
    sealed trait Message(val role: Role) derives CanEqual, Schema:
        def content: String
        def embedding: Maybe[Embedding]
        def tokens: Maybe[Int]
        def origin: Maybe[Context.Origin]
    end Message

    /** A system instruction message. */
    case class SystemMessage(
        content: String,
        embedding: Maybe[Embedding] = Absent,
        tokens: Maybe[Int] = Absent,
        origin: Maybe[Context.Origin] = Absent
    ) extends Message(Role.System)

    /** A user message, optionally carrying an image for vision models. */
    case class UserMessage(
        content: String,
        image: Maybe[Image],
        embedding: Maybe[Embedding] = Absent,
        tokens: Maybe[Int] = Absent,
        origin: Maybe[Context.Origin] = Absent
    ) extends Message(Role.User)

    /** An assistant reply, optionally carrying tool calls. */
    case class AssistantMessage(
        content: String,
        calls: Chunk[Call] = Chunk.empty,
        embedding: Maybe[Embedding] = Absent,
        tokens: Maybe[Int] = Absent,
        origin: Maybe[Context.Origin] = Absent
    ) extends Message(Role.Assistant)

    /** A tool-result message answering a prior call. */
    case class ToolMessage(
        callId: CallId,
        content: String,
        embedding: Maybe[Embedding] = Absent,
        tokens: Maybe[Int] = Absent,
        origin: Maybe[Context.Origin] = Absent
    ) extends Message(Role.Tool)

end Context
