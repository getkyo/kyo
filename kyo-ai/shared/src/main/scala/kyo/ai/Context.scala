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
case class Context(
    raw: Chunk[Message],
    compacted: Chunk[Message],
    compaction: Maybe[Context.CompactionState] = Absent
) derives CanEqual, Schema:

    /** Appends a message to BOTH lists unconditionally (the pairing invariant), preserving the
      * compaction state seat.
      */
    def add(msg: Message): Context =
        copy(raw = raw.append(msg), compacted = compacted.append(msg))

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
        copy(raw = raw.concat(tail), compacted = compacted.concat(tail))
    end merge

    /** The compaction state seat (U13), defaulting to a fresh empty state. The Compactor reads and
      * rewrites it through render; it carries the boundary counter, the usage anchor, recall
      * records, and the write-once summary slots (§5e, §7). private[kyo]: not a lock symbol.
      */
    private[kyo] def compactionState: Context.CompactionState =
        compaction.getOrElse(Context.CompactionState())

    private[kyo] def withCompaction(state: Context.CompactionState): Context =
        copy(compaction = Present(state))

end Context

object Context:

    /** The raw index range a synthetic compacted entry stands for, plus the since-demotion
      * watermark. Lives on a Message inside compacted (not a third Context structure): start is the
      * covered unit id, end is exclusive, since is the raw index at the boundary that demoted it.
      */
    case class Origin(start: Int, end: Int, since: Int) derives CanEqual, Schema

    /** A per-message token stamp: the apportioned real token count paired with the id of the
      * tokenizer that produced it, so apportionment never mixes vocabularies across a provider
      * switch (§5a, DG-01; owner-confirm for the Schema wire impact). private[kyo]: not a lock symbol.
      */
    private[kyo] case class TokenStamp(tokenizerId: String, count: Int) derives CanEqual, Schema

    /** One recall, recorded in compaction state stamped with the boundary counter at recall time;
      * its seed contribution decays per boundary since (§5e). Lives in state, never inferred from
      * the view, so clearing the exchange never drops the signal.
      */
    private[kyo] case class RecallRecord(region: Int, boundaryStamp: Int) derives CanEqual, Schema

    /** One write-once span summary slot, keyed by the span's raw ordinal range [start, end) (§5d,
      * §7). In P2 no fill route exists, so summaries stay empty and the summary level renders the
      * fixed-size substitute elision; the slot and its write-once discipline are seated here and
      * filled from P3.
      */
    private[kyo] case class SpanSummary(start: Int, end: Int, bytes: String) derives CanEqual, Schema

    /** The kind of a directed relation the analysis pass emits (§5c). `DependsOn` renders as the
      * Dependency edge (weight 3.0), `Relates` as the Relatedness edge (weight 0.5), `Supersedes`
      * as no edge (it feeds the supersession machinery). `derives Schema` is the wire contract the
      * hostile-input decode rests on: an unknown discriminator fails the whole typed decode and
      * yields a dropped artifact, never a throw.
      */
    private[kyo] enum RelationKind derives CanEqual, Schema:
        case DependsOn, Relates, Supersedes

    /** One directed relation from an analyzed region to an EARLIER one (§5c). Backward-only:
      * `target < ordinal`, enforced at parse time by discarding violations.
      */
    private[kyo] case class Relation(target: Int, kind: RelationKind) derives CanEqual, Schema

    /** The write-once analysis of one newly closed region (§5c): the region's `ordinal` and its
      * backward relations, capped and no-weights/no-summary by construction. Frozen by ordinal into
      * compaction state exactly like a summary; a re-emission for an analyzed ordinal is discarded.
      */
    private[kyo] case class RegionAnalysis(ordinal: Int, relations: Chunk[Relation]) derives CanEqual, Schema

    /** The typed batch one analysis generation emits, one `RegionAnalysis` per named region (§5c).
      * Decoded over model-controlled output through `Schema`; every malformed member, out-of-index
      * or backward-violating target, over-cap relation, and unknown discriminator routes to a typed
      * drop.
      */
    private[kyo] case class Analysis(regions: Chunk[RegionAnalysis]) derives CanEqual, Schema

    /** The compaction state seat carried on Context (§7): the boundary counter (recall's decay
      * clock), the usage anchor and the raw size it was taken at (§5a), the recall records (§5e),
      * and the write-once span summary slots (§5d). The drift bookkeeping (pending-confirm flag,
      * last-fire index) is seated for P5. Adopted and rewritten only through Compactor.render.
      */
    private[kyo] case class CompactionState(
        boundaryCounter: Int = 0,
        lastUsage: Maybe[Int] = Absent,
        lastUsageRawSize: Int = 0,
        recalls: Chunk[RecallRecord] = Chunk.empty,
        summaries: Chunk[SpanSummary] = Chunk.empty,
        analyses: Chunk[RegionAnalysis] = Chunk.empty,
        driftPendingConfirm: Boolean = false,
        lastDriftFire: Int = -1
    ) derives CanEqual, Schema:
        // Write-once adoption: a summary lands only into an empty slot; a later write to a filled
        // slot is discarded (SPAN-FREEZING ii, §5d), so whichever bytes land first are permanent.
        def withSummary(start: Int, end: Int, bytes: String): CompactionState =
            if summaries.exists(s => s.start == start && s.end == end) then this
            else copy(summaries = summaries.append(SpanSummary(start, end, bytes)))

        def summaryOf(start: Int, end: Int): Maybe[String] =
            summaries.filter(s => s.start == start && s.end == end).headMaybe.map(_.bytes)

        // §5c write-once analysis adoption: a region's analysis freezes by ordinal exactly like a
        // summary; a re-emission for an already-analyzed ordinal (even from a disobedient pass) is
        // discarded, so incrementality needs no bookkeeping beyond the low-water ordinal.
        def withAnalysis(ra: RegionAnalysis): CompactionState =
            if analyses.exists(_.ordinal == ra.ordinal) then this
            else copy(analyses = analyses.append(ra))

        def analysisOf(ordinal: Int): Maybe[RegionAnalysis] =
            analyses.filter(_.ordinal == ordinal).headMaybe

        // Records a recall stamped with the current boundary counter (§5e).
        def withRecall(region: Int): CompactionState =
            copy(recalls = recalls.append(RecallRecord(region, boundaryCounter)))

        // Advances the boundary counter, ticked at every compaction boundary of either cause (§5e).
        def tickBoundary: CompactionState = copy(boundaryCounter = boundaryCounter + 1)

        // Re-anchors occupancy on a provider-reported request total (§5a).
        def withUsage(total: Int, rawSize: Int): CompactionState =
            copy(lastUsage = Present(total), lastUsageRawSize = rawSize)
    end CompactionState

    /** The empty conversation (both lists empty). */
    val empty: Context = Context(Chunk.empty, Chunk.empty)

    /** Single-arg factory: raw = compacted = messages (no compaction on a freshly built Context).
      * Keeps every existing Context(msgs) call site compiling while the field split lands.
      */
    def apply(messages: Chunk[Message]): Context = Context(messages, messages)

    /** CORE-field equality: content/role/image/calls/callId only, ignoring tokens/origin, so two
      * content-identical messages differing solely in enrichment state compare as the same.
      * INTERNAL: used by the default Compactor and Context.merge for deduplication; a custom
      * Compactor is not obligated to honor it, so this is not a lock symbol.
      */
    private[kyo] def coreEq(a: Message, b: Message): Boolean =
        (a, b) match
            case (SystemMessage(c1, _, _), SystemMessage(c2, _, _))               => c1 == c2
            case (UserMessage(c1, i1, _, _), UserMessage(c2, i2, _, _))           => c1 == c2 && i1 == i2
            case (AssistantMessage(c1, k1, _, _), AssistantMessage(c2, k2, _, _)) => c1 == c2 && k1 == k2
            case (ToolMessage(id1, c1, _, _), ToolMessage(id2, c2, _, _))         => id1 == id2 && c1 == c2
            case _                                                                => false

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

    /** A conversation message, tagged with its role. Each leaf carries two trailing defaulted
      * enrichment fields (tokens/origin): once-computed facts living on the message value that owns
      * them with no separate cache structure. `tokens` is the apportioned token stamp the compaction
      * seam writes once per message (§5a), carrying the tokenizer id alongside the count so
      * apportionment never mixes vocabularies; `origin` is set only on a synthetic entry a Compactor
      * builds to stand for a raw range.
      */
    sealed trait Message(val role: Role) derives CanEqual, Schema:
        def content: String
        def tokens: Maybe[TokenStamp]
        def origin: Maybe[Context.Origin]
    end Message

    /** A system instruction message. */
    case class SystemMessage(
        content: String,
        tokens: Maybe[TokenStamp] = Absent,
        origin: Maybe[Context.Origin] = Absent
    ) extends Message(Role.System)

    /** A user message, optionally carrying an image for vision models. */
    case class UserMessage(
        content: String,
        image: Maybe[Image],
        tokens: Maybe[TokenStamp] = Absent,
        origin: Maybe[Context.Origin] = Absent
    ) extends Message(Role.User)

    /** An assistant reply, optionally carrying tool calls. */
    case class AssistantMessage(
        content: String,
        calls: Chunk[Call] = Chunk.empty,
        tokens: Maybe[TokenStamp] = Absent,
        origin: Maybe[Context.Origin] = Absent
    ) extends Message(Role.Assistant)

    /** A tool-result message answering a prior call. */
    case class ToolMessage(
        callId: CallId,
        content: String,
        tokens: Maybe[TokenStamp] = Absent,
        origin: Maybe[Context.Origin] = Absent
    ) extends Message(Role.Tool)

end Context
