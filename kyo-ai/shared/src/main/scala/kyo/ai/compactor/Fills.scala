package kyo.ai.compactor

import kyo.*
import kyo.Compactor
import kyo.Compactor.Mechanism
import kyo.Compactor.Tuning
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** The maintenance model calls: one summary fill and one region analysis.
  *
  * Where [[Preparation]] decides WHEN to prepare and owns the fiber, this is what actually runs:
  * routing a fill to the cheap tier without redirecting it away from the caller's gateway, building the
  * bounded request for one span, and decoding a reply that is untrusted by construction. Every decode here
  * is total, so a malformed relation becomes a dropped artifact rather than a throw: the input is
  * model-controlled output, and a summarizer must never be able to fail a user's turn.
  */
private[kyo] trait Fills:
    self: Compactor.internal.Default =>

    import Model.*
    import calibration.*
    import tuning.*

    // summarizer knob -> fill config: Present pins the fill model; Absent
    // selects the warm route with provider.small as the degraded fallback. The degraded/pinned
    // route runs here (the warm prompt-cache route is future work), so
    // Absent resolves to provider.small.
    /** The provider's cheap entry, carrying the CALLER's connection settings.
      *
      * `provider.small` is a fresh catalog entry, so it arrives with the provider's default base
      * URL and no key. Taking it as-is sends maintenance traffic to the public endpoint even when
      * the caller was configured for a gateway, a proxy, or a local server, and drops an explicitly
      * configured key. Only the MODEL should change here, never where the request goes or what
      * authenticates it.
      */
    def cheapSlot(config: Config): Config =
        summarizer match
            case Present(cfg) => cfg
            case Absent =>
                val small  = config.provider.small
                val routed = small.apiUrl(config.apiUrl)
                config.apiKey match
                    case Present(key) => routed.apiKey(key)
                    case Absent       => routed
    end cheapSlot

    def resolveFillConfig(config: Config): Config =
        val base = cheapSlot(config)
        // the fill caps its summary output at summaryOutputCap so summary size stays in the demotion
        // arithmetic's output-cap class and a demoted summary never inflates the served view.
        // Reasoning is disabled: with it on, the default reasoning budget is added to the ceiling
        // (effectiveMaxOutputTokens raises summaryOutputCap past the cap the demotion arithmetic
        // relies on), and internal maintenance fills must stay cheap. Off means the ceiling
        // resolves back to summaryOutputCap.
        base.maxTokens(summaryOutputCap).disableReasoning
    end resolveFillConfig

    // the degraded packing rule: the summarizer has its own (smaller) window, so the
    // input is BOUNDED and relevance-selected, never the whole history: the raw span, a
    // size-bounded set of earlier prior-span summaries (priorSummaryBudget, recency-first), and the
    // task anchor (system head + first user turn). The instruction asks for labeled sections, most
    // load-bearing first, the one thing terse asks of the fill.
    def buildFillContext(sp: Span, spans: Chunk[Span], ctx: Context, staged: Staged)(using Frame): Context =
        val raw      = ctx.raw
        val anchor   = raw.take(1) ++ raw.filter(_.role == Role.User).take(1)
        val spanMsgs = raw.slice(sp.start, sp.end)
        val priors =
            spans.filter(_.end <= sp.start).reverse.foldLeft(Chunk.empty[Message]) { (acc, p) =>
                if acc.size >= priorSummaryBudget then acc
                else
                    staged.summaryOf(SpanKey(p.start, p.end)) match
                        case Present(b) => acc.append(SystemMessage(b))
                        case Absent     => acc
            }
        val instruction: Message = SystemMessage(
            "Summarize the following span of a conversation into labeled sections, most load-bearing " +
                "first. Preserve identifiers, decisions, and unresolved threads; omit pleasantries."
        )
        val body = Chunk(instruction) ++ anchor ++ priors ++ spanMsgs
        Context(body, body)
    end buildFillContext

    // A single degraded-route fill. Runs a self-contained nested LLM.run over the
    // snapshot: the wire entry (Completion.apply) carries LLM, so the nested run discharges it,
    // coupling to NO foreground state (the LLM-free property :1320). A transport failure maps
    // to AITransportException; the caller recovers it to an absent artifact.
    def runFill(sp: Span, spans: Chunk[Span], ctx: Context, config: Config, prep: Model.Preparation)(using
        Frame
    ): String < (Async & Abort[AIGenException]) =
        val fillConfig = resolveFillConfig(config)
        prep.staged.get.map { staged =>
            val fillCtx = buildFillContext(sp, spans, ctx, staged)
            // TYPED generation through the regular kyo-ai API (the result tool + eval-loop repair), so a
            // summary is produced on EVERY provider including command harnesses (codex), where a raw
            // provider.completion requires a result schema and fails outright with an Absent one (the
            // same reason the analysis pass generates typed; see runAnalysis). A self-contained nested
            // LLM.run over the snapshot keeps it decoupled from the foreground handler-threaded LLM.State
            // (a fresh AI, no compactor/tools/thoughts inherited); the caller degrades any failure to an
            // absent slot, so a failed fill never fails the user's generation.
            LLM.run(fillConfig) {
                AI.init.map(ai0 => ai0.setContext(fillCtx).andThen(ai0.gen[String]))
            }
        }
    end runFill

    // A marker-grade one-line descriptor of a region: the region's raw content
    // flattened to one line and bounded. Names content without carrying it.
    def descriptorLine(u: Region, raw: Chunk[Message]): String =
        val c = unitContent(u, raw).replace('\n', ' ').trim
        if c.length <= 80 then c else c.take(80)

    // the analysis input: the SERVED VIEW (the warm bytes the foreground request carried,
    // ctx.compacted) plus a mechanical instruction suffix naming the low-water ordinal, the regions
    // to analyze, and a compact region index (each REACHABLE region's ordinal beside its
    // marker-grade descriptor). The reach is summary-or-verbatim only: a pointer-level region (its
    // descriptor names content without carrying it) is out of the analysis's reach by design, so it
    // is excluded from the index and can never be a relation target. The suffix adds not one byte to
    // the view the foreground model sees. It asks for the typed Analysis JSON.
    def buildAnalysisContext(ctx: Context, pending: Chunk[Region], config: Config, lowWater: Int, reachable: Set[Int])(using
        Frame
    ): Context =
        val units   = group(ctx.raw)
        val closed  = units.filter(u => reachable.contains(u.id)).sortBy(_.id)
        val index   = closed.map(u => s"${u.id}: ${descriptorLine(u, ctx.raw)}").mkString("\n")
        val targets = pending.map(_.id).mkString(", ")
        val instruction: Message = SystemMessage(
            "Analyze how each listed region depends on, relates to, or supersedes an EARLIER region " +
                "in this conversation, and return the structured analysis. Relation kinds are DependsOn, " +
                "Relates, or Supersedes. Every target MUST be an earlier ordinal (target < ordinal). " +
                s"Low-water ordinal: $lowWater. Analyze exactly these region ordinals: $targets. " +
                s"Region index:\n$index"
        )
        val body = ctx.compacted.append(instruction)
        Context(body, body)
    end buildAnalysisContext

    // the five load-bearing properties, enforced by DISCARDING, never throwing. Decode the
    // typed Analysis over model-controlled output; a whole-batch decode failure (malformed JSON,
    // unknown RelationKind discriminator) yields no analyses. Per member: drop a member whose
    // ordinal is not a closed region in the suffix index; keep only relations that are backward-only
    // (target < ordinal) AND name an in-index target AND carry a known kind; cap survivors at
    // relationCap in emission order. The member survives with its first cap-many valid relations, or
    // with none, never as a throw.
    // The per-member validation of a decoded Analysis: drop members whose ordinal is not a closed
    // reachable region; keep only backward-only (target < ordinal) relations that name an in-reach
    // target and a known kind; cap survivors at relationCap. Shared by the raw-text decode path and
    // the typed-generation path, so both apply identical guards to model-controlled output.
    def validateAnalysis(regions: Chunk[RegionAnalysis], validOrdinals: Set[Int]): Chunk[RegionAnalysis] =
        regions.collect {
            case ra if validOrdinals.contains(ra.ordinal) =>
                val kept =
                    ra.relations.filter(r => r.target < ra.ordinal && validOrdinals.contains(r.target)).take(relationCap)
                RegionAnalysis(ra.ordinal, kept)
        }
    end validateAnalysis

    def parseAnalysis(text: String, validOrdinals: Set[Int])(using Frame): Chunk[RegionAnalysis] =
        Json.decode[Analysis](text) match
            case Result.Success(a) => validateAnalysis(a.regions, validOrdinals)
            case _                 => Chunk.empty
    end parseAnalysis

    // the analysis pass: ONE typed generation on the conversation's OWN model (the main
    // config), reading the served view warm (transparent prompt caching is
    // future work; the pass is functional whether or not the completion impl caches). Runs a
    // self-contained nested LLM.run over the snapshot so it never couples to the foreground
    // handler-threaded LLM.State (LLM-free), decodes the typed Analysis, and stages each
    // surviving region write-once by ordinal. EVERY failure is recovered here: a failed analysis
    // leaves its regions unanalyzed, the graph runs on structural edges, and gen never fails;
    // nothing ever waits for it (the boundary join is for fills only).
    def runAnalysis(ctx: Context, pending: Chunk[Region], config: Config, prep: Model.Preparation, reachable: Set[Int])(using
        Frame
    ): Unit < Async =
        if !mechanisms.contains(Mechanism.Analysis) || pending.isEmpty then Kyo.unit
        else
            // the valid target set is the REACHABLE (summary-or-verbatim) region set: a relation
            // targeting a pointer-level region is dropped by parseAnalysis, so a semantic edge can
            // never lift pointer-level liveness (the coldest history stays out of reach).
            val valid       = reachable
            val lowWater    = pending.headMaybe.map(_.id).getOrElse(-1)
            val analysisCtx = buildAnalysisContext(ctx, pending, config, lowWater, reachable)
            // Stays on the CONVERSATION'S OWN model. The design specifies this pass as a cache-warm
            // read of the view the foreground just sent and prices it as one warm prefix read per
            // arming event, which is why it is not routed to the cheap slot the fills use.
            //
            // MEASURED CAVEAT: that warmth is not universal. On a harness that opens a fresh
            // session per generation (codex), a calibration over 14 consecutive generations found
            // the cached prefix pinned at a fixed preamble while the conversation grew, so this
            // pass is a COLD near-window read there, not a warm one. The routing is kept because
            // the cheap slot would be cold too AND weaker, so it is worse on both axes; but the
            // "warm read" cost model does not hold on every transport, and a cadence or routing
            // change should be argued from a measurement on the target transport rather than from
            // that premise.
            //
            // The fills are a different case and correctly use the cheap slot: their input is a
            // bounded per-span packing the foreground never sent, so it was never warm anywhere.
            //
            // Reasoning is disabled: the reasoning-on default would inflate the output ceiling with
            // the default reasoning budget.
            val analysisConfig = config.disableReasoning
            // TYPED generation through the regular kyo-ai API (the result tool + eval-loop repair),
            // so a decodable typed Analysis is produced on EVERY provider including command harnesses
            // (codex), where a raw completion's prose/markdown content would not parse as bare JSON. A
            // self-contained nested LLM.run over the snapshot keeps it decoupled from the foreground
            // handler-threaded LLM.State (a fresh AI, no compactor/tools/thoughts inherited). Every
            // failure is recovered here: a failed analysis leaves its regions unanalyzed and gen never
            // fails; nothing waits for it (the boundary join is fills-only).
            LLM.run(analysisConfig) {
                AI.init.map(ai0 => ai0.setContext(analysisCtx).andThen(ai0.gen[Analysis]))
            }.handle(Abort.run[AIGenException]).map {
                case Result.Success(analysis) =>
                    val validated = validateAnalysis(analysis.regions, valid)
                    // What the model proposed against what survived validation. Without this the two
                    // ways the layer can contribute nothing are indistinguishable from outside: the
                    // model emitting no relations, and every relation being dropped for targeting a
                    // forward or out-of-reach region. They have opposite fixes.
                    val proposed = analysis.regions.foldLeft(0)((n, ra) => n + ra.relations.size)
                    val kept     = validated.foldLeft(0)((n, ra) => n + ra.relations.size)
                    Log.debug(
                        s"compaction analysis: regions proposed=${analysis.regions.size} kept=${validated.size}, " +
                            s"relations proposed=$proposed kept=$kept, reach=${valid.size}"
                    ).andThen {
                        Kyo.foreachDiscard(validated) { ra =>
                            prep.staged.getAndUpdate(_.withAnalysis(ra.ordinal, ra)).unit
                        }
                    }
                // A failed analysis is DESIGNED to degrade silently: the graph falls back to
                // structural-only and gen never fails. But silent recovery also hides a layer that
                // is failing on every call from a layer that is simply finding nothing, and those
                // are different defects. The recovery stays; only the silence goes.
                case other =>
                    Log.debug(s"compaction analysis did not complete, degrading to structural-only: $other")
            }
        end if
    end runAnalysis

    // the analysis's reach: the closed, non-tail regions that are summary-or-verbatim at the
    // projected boundary (levels = A_prep). A region a demoted span projects to Level.Pointer is out
    // of reach (its descriptor names content without carrying it), so it is excluded from both the
    // region index and the valid target set. Pure over the projected assignment the caller holds.
    def analysisReach(units: Chunk[Region], spans: Chunk[Span], levels: Dict[Int, Level], tail: Set[Int]): Set[Int] =
        val pointerRegions = spans.flatMap { sp =>
            levels.get(sp.start) match
                case Present(Level.Pointer) => sp.regionIds.toList
                case _                      => Nil
        }.toSet
        units.filterNot(u => tail.contains(u.id) || pointerRegions.contains(u.id)).map(_.id).toSet
    end analysisReach

    // A pinned unit rendered verbatim, with the role-2 within-verbatim elision applied when its own
    // content exceeds the generous budget; a smaller unit renders unchanged. The stamp is dropped
    // on elision so the demotion arithmetic and the hard-limit check price the unit at its elided size.
    def keepVerbatim(m: Message)(using Frame): Message =
        if m.content.length <= generousElisionChars then m
        else
            val elided = elideMessage(m, generousElisionChars)
            stamp(elided, TokenStamp("elided", offlineEstimate(elided)))

    // ---- project: build the view. A summary/terse span renders ONE synthetic marker (span grain);
    // a pointer span renders one marker per member region (region grain); a pinned span or a
    // tail-band region renders verbatim. A demoted marker carries Present(origin) so the next
    // boundary recognizes it without parsing marker text. demotions keys are span.start.
    def project(
        raw: Chunk[Message],
        units: Chunk[Region],
        spans: Chunk[Span],
        demotions: Dict[Int, Level],
        since: Int,
        prevLevels: Dict[Int, Context.Origin],
        state: Compaction.State = Compaction.State(),
        keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
    )(using Frame): Chunk[Message] =
        val byRegionId = units.map(u => u.id -> u).toMap
        val idxToRegion =
            units.foldLeft(Map.empty[Int, Int])((m, u) => u.indices.foldLeft(m)((mm, idx) => mm.updated(idx, u.id)))
        val spanFor = spans.flatMap { sp =>
            demotions.get(sp.start) match
                case Present(level) => sp.regionIds.toList.map(rid => rid -> (sp, level))
                case Absent         => Nil
        }.toMap
        // conditional clearing: a recall exchange whose target region is reinstated verbatim at
        // this boundary (not a member of any demoted span) reproduces content the view now carries in
        // place, so it is dropped; under pressure that keeps the target demoted the tail copy stays.
        val cleared = reinstatedRecallIndices(raw, spanFor.keySet, state)
        // Role 2 elision: a pinned unit kept verbatim whose content alone exceeds the generous
        // budget is rendered exact-surface elided (head+tail), a within-verbatim treatment priced at
        // its elided size. Deterministic over frozen raw, so it re-renders byte-identically each boundary.
        val oversized = raw.exists(_.content.length > generousElisionChars)
        if demotions.isEmpty && cleared.isEmpty && !oversized then raw
        else
            val (out, _) =
                raw.zipWithIndex.foldLeft((Chunk.empty[Message], Set.empty[String])) {
                    case ((acc, emitted), (m, i)) =>
                        val regionId = idxToRegion.getOrElse(i, i)
                        spanFor.get(regionId) match
                            // A forgotten retention band's tombstone: an empty raw slot carrying a
                            // band Origin, kept for ordinal stability, never emitted; the band head
                            // (its non-empty coarse text) renders verbatim as the one band marker
                            // for the run.
                            case None if m.content.isEmpty && m.origin.isDefined => (acc, emitted)
                            case None =>
                                if cleared.contains(i) then (acc, emitted)
                                else (acc.append(keepVerbatim(m)), emitted)
                            case Some((sp, level)) =>
                                level match
                                    case Level.Pointer =>
                                        val key = s"p$regionId"
                                        if emitted.contains(key) then (acc, emitted)
                                        else
                                            val region = byRegionId.getOrElse(regionId, Region(regionId, Chunk(i), false, 0))
                                            (acc.append(pointerMarker(region, raw, since, prevLevels, keys)), emitted + key)
                                        end if
                                    case Level.Summary | Level.Terse =>
                                        val key = s"s${sp.start}"
                                        if emitted.contains(key) then (acc, emitted)
                                        else
                                            (
                                                acc.append(summaryMarker(sp, raw, units, level, since, prevLevels, state, keys)),
                                                emitted + key
                                            )
                                        end if
                                    case Level.Verbatim => (acc.append(keepVerbatim(m)), emitted)
                        end match
                }
            out
        end if
    end project

    // the recall exchange indices to clear: for each tail recall exchange (an
    // assistant `recall` call fused with its answering tool result) whose target region was recalled
    // AND is reinstated verbatim (absent from the demoted-region set) at this boundary, the call and
    // its result index. The recall RECORD lives in state and is untouched here, so clearing the view
    // copy never drops the decaying liveness seed. Under pressure that keeps the target demoted,
    // its id is in demotedRegionIds and the exchange is retained.
    def reinstatedRecallIndices(
        raw: Chunk[Message],
        demotedRegionIds: Set[Int],
        state: Compaction.State
    )(using Frame): Set[Int] =
        val recalled = state.recalls.map(_.region).toSet
        if recalled.isEmpty then Set.empty
        else
            val toolIdxByCallId = raw.zipWithIndex.foldLeft(Dict.empty[String, Int]) {
                case (m, (ToolMessage(cid, _, _, _), j)) => m.update(cid.id, j)
                case (m, _)                              => m
            }
            raw.zipWithIndex.foldLeft(Set.empty[Int]) {
                case (acc, (msg, i)) =>
                    msg match
                        case AssistantMessage(_, calls, _, _) =>
                            val recallCalls = calls.filter(_.function == "recall")
                            val target = recallCalls.foldLeft(Absent: Maybe[Int]) { (f, c) =>
                                f match
                                    case Present(_) => f
                                    case Absent     => recallArgId(c.arguments)
                            }
                            target match
                                case Present(r) if recalled.contains(r) && !demotedRegionIds.contains(r) =>
                                    recallCalls.foldLeft(acc + i) { (s, c) =>
                                        toolIdxByCallId.get(c.id.id) match
                                            case Present(j) => s + j
                                            case Absent     => s
                                    }
                                case _ => acc
                            end match
                        case _ => acc
            }
        end if
    end reinstatedRecallIndices

    // A span's summary/terse marker: the mechanical descriptor plus the write-once summary
    // bytes (Summary) or a fixed-budget prefix of them (Terse). With no fill route, an empty
    // slot renders the FIXED-SIZE substitute elision (role 1); the cut never assigns Terse to a
    // blob-less span, so a Terse marker always has bytes.
    def summaryMarker(
        sp: Span,
        raw: Chunk[Message],
        units: Chunk[Region],
        level: Level,
        since: Int,
        prevLevels: Dict[Int, Context.Origin],
        state: Compaction.State,
        keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
    )(using Frame): Message =
        val origin = Present(spanOrigin(sp, since, prevLevels))
        val descr  = spanDescriptor(sp, raw, units, keys)
        val body = state.summaryOf(sp.start, sp.end) match
            case Present(bytes) => s"$descr\n${if level == Level.Terse then tersePrefix(bytes) else bytes}"
            case Absent         =>
                // No fill route: render the fixed-size substitute elision, but
                // never inflate. A demotion is a size reduction; when the elided substitute would not
                // render smaller than the span's own verbatim content, fall back to the degenerate note
                // alone (recall restores the exact bytes), so a demoted span is never larger than raw.
                val content = spanContent(sp, raw)
                val full    = s"$descr\n${substituteElision(content, substituteElisionChars)}"
                if full.length < content.length then full
                else s"$descr\n$substituteNote"
        SystemMessage(body, origin = origin)
    end summaryMarker

    // A region's pointer marker: the mechanical descriptor plus recall id, no content.
    // origin.since is PRESERVED across re-renders (prevLevels) so recall decay is not reset.
    def pointerMarker(
        region: Region,
        raw: Chunk[Message],
        since: Int,
        prevLevels: Dict[Int, Context.Origin],
        keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
    ): Message =
        val endExcl = region.indices.lastOption.map(_ + 1).getOrElse(region.id + 1)
        val since2  = prevLevels.get(region.id).map(_.since).getOrElse(since)
        SystemMessage(regionDescriptor(region, raw, keys), origin = Present(Context.Origin(region.id, endExcl, since2)))
    end pointerMarker

    def spanOrigin(sp: Span, since: Int, prevLevels: Dict[Int, Context.Origin]): Context.Origin =
        Context.Origin(sp.start, sp.end, prevLevels.get(sp.start).map(_.since).getOrElse(since))

    // Mechanical descriptors: the id range, the tool name and compaction key the
    // render already knows (the recall-decision signal), a bounded first-line snippet, the stamped
    // token count, and the recall id. Never model-generated text.
    def regionDescriptor(region: Region, raw: Chunk[Message], keys: Dict[Int, (String, Tool.Kind)] = Dict.empty): String =
        val tag = descriptorTag(toolName(region, raw), keys.get(region.id).map(_._1))
        s"[region ${region.id}:$tag ${firstLine(unitContent(region, raw))}, ${region.tokens} tokens; recall(${region.id}) restores verbatim]"

    def spanDescriptor(
        sp: Span,
        raw: Chunk[Message],
        units: Chunk[Region],
        keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
    ): String =
        val toks  = units.filter(u => sp.regionIds.contains(u.id)).foldLeft(0)((n, u) => n + u.tokens)
        val headU = units.filter(_.id == sp.start).headMaybe
        val tag   = descriptorTag(headU.flatMap(u => toolName(u, raw)), keys.get(sp.start).map(_._1))
        s"[regions ${sp.start}-${sp.end - 1}:$tag ${firstLine(spanContent(sp, raw))}, $toks tokens; recall(${sp.start}) restores verbatim]"
    end spanDescriptor

    // The tool name a region's exchange invoked: the first tool call's
    // function name in the region's assistant message, Absent for a call-less region. Pure over raw.
    def toolName(u: Region, raw: Chunk[Message]): Maybe[String] =
        u.indices.foldLeft(Absent: Maybe[String]) { (found, idx) =>
            found match
                case Present(_) => found
                case Absent =>
                    raw(idx) match
                        case AssistantMessage(_, calls, _, _) => calls.headMaybe.map(_.function)
                        case _                                => Absent
        }

    // The descriptor's tool-name + compaction-key tag: both are recall-decision
    // signal, each omitted when Absent (a keyless or call-less region carries neither).
    def descriptorTag(tool: Maybe[String], key: Maybe[String]): String =
        // Both parts are model-influenced (a tool name from the registry, a key extracted from model-written
        // arguments), and a newline in either would make a pointer marker MULTI-LINE. That matters beyond
        // presentation: automatic recall recovers a marker's level after a restore from exactly that
        // property, a single-line body being a pointer, so an embedded newline would misclassify it.
        def oneLine(v: String) = v.takeWhile(c => c != '\n' && c != '\r')
        val t                  = tool.map(n => s" ${oneLine(n)}").getOrElse("")
        val k                  = key.map(kk => s", key ${oneLine(kk)}").getOrElse("")
        s"$t$k"
    end descriptorTag

    def firstLine(s: String): String =
        val line = s.takeWhile(_ != '\n')
        if line.length <= 80 then line else line.take(77) + "..."
end Fills
