package kyo.ai

import kyo.*
import kyo.ai.Context.*

/** Compaction's PERSISTED state and the types it is made of.
  *
  * These lived in the `Context` companion, which meant the conversation type named eight things it never
  * interprets: recall records, write-once summary slots, the analysis relation vocabulary, the boundary
  * counter, the usage anchor. `Context` is two message lists and a cell it carries; what is inside the
  * cell is compaction's business, and a reader of `Context` should not have to skip past it.
  *
  * Persisted rather than ephemeral, which is the distinction that decides what belongs here. Summaries
  * and analyses are frozen write-once and must survive a snapshot, so they ride the `Context` and are
  * `Schema`-derivable. The in-flight preparation fiber and the staging cell are ephemeral and live in
  * `Compactor.State` instead, where a snapshot correctly loses them.
  *
  * `private[kyo]`: the shape is not a lock symbol. A custom compactor is handed the cell and may carry
  * it unchanged, but is not obligated to interpret it, and nothing here is part of the boundary.
  */
private[kyo] object Compaction:

    /** One recall, recorded in compaction state stamped with the boundary counter at recall time;
      * its seed contribution decays per boundary since. Lives in state, never inferred from
      * the view, so clearing the exchange never drops the signal.
      */
    private[kyo] case class RecallRecord(region: Int, boundaryStamp: Int) derives CanEqual, Schema

    /** What an automatic expansion actually delivered.
      *
      * Its own type rather than the ladder's [[kyo.ai.compactor.Model.Level]], because the two answer
      * different questions. `Excerpt` is a delivered artifact and not a ladder level: it is the
      * empty-slot case, kept distinct because its evidence guarantee is weaker than a summary's (a
      * summary covers the span, an excerpt covers a window around the matched terms). And `SeedOnly` is
      * a fire that found nothing affordable: it writes its record so the need still reaches the next
      * boundary's seed, and it suppresses nothing by construction, so it can never block a later
      * delivery. There is deliberately no `Pointer` or `Terse` case, since a delivery never produces
      * either: terse is a truncated summary, which is the artifact the ladder exists to avoid handing
      * back as evidence.
      */
    private[kyo] enum Delivered derives CanEqual, Schema:
        case Verbatim, Summary, Excerpt, SeedOnly

        /** How much the model actually received, as a total order.
          *
          * Used to keep a region's record MONOTONE within an interval. An excerpt ranks below a summary
          * even though suppression treats the two alike, because a window around the matched terms is
          * weaker coverage than a summary of the whole span, and when both are recorded for one region the
          * stronger one is the truthful account of what went out.
          */
        def rank: Int = this match
            case Verbatim => 3
            case Summary  => 2
            case Excerpt  => 1
            case SeedOnly => 0
    end Delivered

    /** What justified an automatic expansion, which is what the damper is keyed on.
      *
      * An identifier match is a request for the referent's exact content; a content-term match is
      * topical. The two earn different seed fractions at the next boundary, so the record must carry the
      * class directly: it cannot be recovered from what was delivered, because a fire that delivered
      * nothing records `SeedOnly` whatever justified it.
      */
    private[kyo] enum Evidence derives CanEqual, Schema:
        case Identifier, Content

    /** One automatic expansion, recorded in compaction state so the record and the delivery it describes
      * commit together when the seam installs the compactor's answer.
      *
      * Its seed contribution decays per boundary exactly as a [[RecallRecord]]'s does, at a fraction of
      * the weight set by `evidence`, because a framework guess is weaker evidence than a model's ask.
      *
      * @param region
      *   the region the fire was about. Records are region-grain even when the delivery was span-grain,
      *   so a span delivery writes one record per matched member and suppression sees the coverage that
      *   actually went out.
      * @param boundaryStamp
      *   the boundary counter at fire time. It is interval identity, and it does double duty:
      *   suppression is scoped to the current interval's records, and the decay clock counts boundaries
      *   since this one.
      * @param turnStamp
      *   the raw ordinal of the trailing user run's first message, which is what makes a retried turn
      *   idempotent without storing a counter. Two user messages with no generation between them are one
      *   turn by construction, so this is not strictly increasing per message.
      * @param evidence
      *   what justified the fire, never what was delivered
      * @param delivered
      *   what actually went out, `SeedOnly` when nothing was affordable
      */
    private[kyo] case class ExpansionRecord(
        region: Int,
        boundaryStamp: Int,
        turnStamp: Int,
        evidence: Evidence,
        delivered: Delivered
    ) derives CanEqual, Schema

    /** One write-once span summary slot, keyed by the span's raw ordinal range [start, end). An
      * empty slot renders the fixed-size substitute elision at the summary level; the fill route
      * writes the slot once, so whichever bytes land first are permanent.
      */
    private[kyo] case class SpanSummary(start: Int, end: Int, bytes: String) derives CanEqual, Schema

    /** The kind of a directed relation the analysis pass emits. `DependsOn` renders as the
      * Dependency edge (weight 3.0), `Relates` as the Relatedness edge (weight 0.5), `Supersedes`
      * as no edge (it feeds the supersession machinery). `derives Schema` is the wire contract the
      * hostile-input decode rests on: an unknown discriminator fails the whole typed decode and
      * yields a dropped artifact, never a throw.
      */
    private[kyo] enum RelationKind derives CanEqual:
        case DependsOn, Relates, Supersedes

    private[kyo] object RelationKind:
        private given internalFrame: Frame = Frame.internal

        /** The wire form is the BARE NAME, `"DependsOn"`, not the derived variant object.
          *
          * The default derivation for a Scala 3 enum emits each case as a wrapped object,
          * `{"kind":{"DependsOn":{}}}`, and decodes only that shape. No model produces it: asked for a
          * relation kind, a model writes the name as a string, the payload fails to decode, and the eval
          * loop exhausts its retries, so the whole analysis is dropped. The only analyses that survived
          * were the ones carrying NO relations, since an empty array decodes under either shape, which is
          * why the layer looked like it was running while never delivering a single relation.
          *
          * A bare string is also what provider structured-output tooling expects for an enum, and it
          * survives the strict-schema rewrite as a plain string rather than an `anyOf` of objects.
          *
          * An unrecognized name still fails the decode, which is the contract the analysis parse relies
          * on: `parseAnalysis` turns any decode failure into a dropped artifact, never a throw.
          */
        given Schema[RelationKind] =
            summon[Schema[String]].transform[RelationKind] {
                case "DependsOn"  => RelationKind.DependsOn
                case "Relates"    => RelationKind.Relates
                case "Supersedes" => RelationKind.Supersedes
                case unknown      => throw ParseException(Json(), unknown, "RelationKind")
            }(_.toString)
        end given
    end RelationKind

    /** One directed relation from an analyzed region to an EARLIER one. Backward-only:
      * `target < ordinal`, enforced at parse time by discarding violations.
      */
    private[kyo] case class Relation(target: Int, kind: RelationKind) derives CanEqual, Schema

    /** The write-once analysis of one newly closed region: the region's `ordinal` and its
      * backward relations, capped and no-weights/no-summary by construction. Frozen by ordinal into
      * compaction state exactly like a summary; a re-emission for an analyzed ordinal is discarded.
      */
    private[kyo] case class RegionAnalysis(ordinal: Int, relations: Chunk[Relation]) derives CanEqual, Schema

    /** The typed batch one analysis generation emits, one `RegionAnalysis` per named region.
      * Decoded over model-controlled output through `Schema`; every malformed member, out-of-index
      * or backward-violating target, over-cap relation, and unknown discriminator routes to a typed
      * drop.
      */
    private[kyo] case class Analysis(regions: Chunk[RegionAnalysis]) derives CanEqual, Schema

    /** The compaction state seat carried on Context: the boundary counter (recall's decay
      * clock), the usage anchor and the raw size it was taken at, the recall records,
      * and the write-once span summary slots. Adopted and rewritten only through Compactor.compact.
      */
    private[kyo] case class State(
        boundaryCounter: Int = 0,
        lastUsage: Maybe[Int] = Absent,
        lastUsageRawSize: Int = 0,
        recalls: Chunk[RecallRecord] = Chunk.empty,
        summaries: Chunk[SpanSummary] = Chunk.empty,
        analyses: Chunk[RegionAnalysis] = Chunk.empty,
        expansions: Chunk[ExpansionRecord] = Chunk.empty,
        expansionTurns: Chunk[Int] = Chunk.empty
    ) derives CanEqual, Schema:
        // Write-once adoption: a summary lands only into an empty slot; a later write to a filled
        // slot is discarded, so whichever bytes land first are permanent.
        def withSummary(start: Int, end: Int, bytes: String): State =
            if summaries.exists(s => s.start == start && s.end == end) then this
            else copy(summaries = summaries.append(SpanSummary(start, end, bytes)))

        def summaryOf(start: Int, end: Int): Maybe[String] =
            summaries.filter(s => s.start == start && s.end == end).headMaybe.map(_.bytes)

        // Write-once analysis adoption: a region's analysis freezes by ordinal exactly like a
        // summary; a re-emission for an already-analyzed ordinal (even from a disobedient pass) is
        // discarded, so incrementality needs no bookkeeping beyond the low-water ordinal.
        def withAnalysis(ra: RegionAnalysis): State =
            if analyses.exists(_.ordinal == ra.ordinal) then this
            else copy(analyses = analyses.append(ra))

        def analysisOf(ordinal: Int): Maybe[RegionAnalysis] =
            analyses.filter(_.ordinal == ordinal).headMaybe

        // Records a recall stamped with the current boundary counter.
        def withRecall(region: Int): State =
            copy(recalls = recalls.append(RecallRecord(region, boundaryCounter)))

        // Records one automatic expansion. NOT append-only, unlike every other collection here: a
        // region contributes at most ONE seed term per interval, so an escalation (a second fire on
        // the same region in the same interval, delivering more than the first) REPLACES that
        // region's record rather than adding beside it. Appending would let a region accumulate
        // several seed terms within one interval, which is the accumulation the damper's ceiling
        // arithmetic assumes cannot happen.
        def withExpansion(r: ExpansionRecord): State =
            val prior = expansions.filter(e => e.region == r.region && e.boundaryStamp == r.boundaryStamp).headMaybe
            // MONOTONE in delivered detail, which is what makes "an escalation updates the record" safe.
            // A plain replace is not: within ONE fire a span-grain summary covers every matched member of
            // its span, including a member that just went out VERBATIM in the same carrier, and last-write
            // wins would record that region as Summary. The next turn would then read it as still
            // expandable to verbatim and re-present the identical bytes, which is precisely the duplicate
            // the admissibility guard exists to prevent, and `recall` would stop seeing the resident copy.
            // De-escalation is never legitimate: admissibility already refuses a delivery at or below what
            // the model can read, so a lower record can only ever be a bookkeeping artifact.
            prior match
                case Present(p) if p.delivered.rank >= r.delivered.rank => this
                case _ =>
                    copy(expansions =
                        expansions.filterNot(e => e.region == r.region && e.boundaryStamp == r.boundaryStamp).append(r)
                    )
            end match
        end withExpansion

        // Records the TURN a fire happened on, kept apart from the records themselves because the two
        // count different things. A record is keyed by (region, interval) and is replaced by an
        // escalation, so the turn stamps surviving on records are a LOWER bound on how many turns fired:
        // counting them let a session escalate its way past the interval cap, and let a retried turn whose
        // stamp had been overwritten fire a second carrier. Fires are counted here instead, exactly.
        def withExpansionTurn(turnStamp: Int): State =
            if expansionTurns.contains(turnStamp) then this else copy(expansionTurns = expansionTurns.append(turnStamp))

        def firedThisInterval(turnStamp: Int): Boolean = expansionTurns.contains(turnStamp)
        def expansionFireCount: Int                    = expansionTurns.size

        // This interval's records: the suppression scope. Records outlive their deliveries in order to
        // seed, so suppression must read only the current interval's; reading all of them would leave a
        // region re-demoted at the next boundary undeliverable for the whole prune horizon while
        // nothing of it was resident.
        def expansionsIn(stamp: Int): Chunk[ExpansionRecord] = expansions.filter(_.boundaryStamp == stamp)

        // Drops expansion records past the prune horizon, where their decayed contribution is below any
        // floor. Applied AFTER tickBoundary, so ages count against the new counter. Recall records are
        // deliberately untouched: they have no horizon today, and giving them one is a behaviour change
        // to the tool rather than a side effect of this one.
        def pruneExpansions(horizon: Int): State =
            val kept = expansions.filter(e => boundaryCounter - e.boundaryStamp <= horizon)
            if kept.size == expansions.size then this else copy(expansions = kept)

        // Advances the boundary counter, ticked at every compaction boundary. The fired-turn list is
        // INTERVAL-scoped, so it clears here: a boundary redefines the not-fully-presented set wholesale,
        // and the caps bound guesses per interval rather than per session.
        def tickBoundary: State = copy(boundaryCounter = boundaryCounter + 1, expansionTurns = Chunk.empty)

        // Re-anchors occupancy on a provider-reported request total.
        def withUsage(total: Int, rawSize: Int): State =
            copy(lastUsage = Present(total), lastUsageRawSize = rawSize)
    end State

    /** Folds a forked context's compaction state back into its parent's.
      *
      * A tool runs against a FORKED instance, and [[Context.merge]] previously rebuilt the parent with
      * `copy(raw = ..., compacted = ...)`, which silently kept the parent's `compaction` and discarded
      * everything the forked side recorded. The recall tool writes its record exactly there, so a recall
      * performed by the model was thrown away on merge: the content still reached the model, but the
      * record that carries recall's decaying reinstatement seed never survived, and the session's recall
      * count stayed at zero however many times the tool ran.
      *
      * The additive, write-once collections (recall records, summary slots, region analyses) union, with
      * the parent's entry winning a collision, since a slot is frozen by whichever write landed first.
      * The scalars stay the PARENT's: the boundary counter is the parent's clock, and the usage anchor
      * belongs to the parent's own last completion, so a forked instance must not reseat either.
      */
    private[kyo] def merge(
        parent: Maybe[Compaction.State],
        forked: Maybe[Compaction.State]
    ): Maybe[Compaction.State] =
        if parent.isEmpty then forked
        else if forked.isEmpty then parent
        else
            val p = parent.get
            val f = forked.get
            if p == f then parent
            else
                Present(p.copy(
                    recalls = p.recalls.concat(f.recalls.filterNot(r => p.recalls.exists(_.region == r.region))),
                    summaries = p.summaries.concat(
                        f.summaries.filterNot(s => p.summaries.exists(e => e.start == s.start && e.end == s.end))
                    ),
                    analyses = p.analyses.concat(f.analyses.filterNot(a => p.analyses.exists(_.ordinal == a.ordinal))),
                    // Deduplicated by (region, INTERVAL) with the PARENT winning inside a stamp. Newest-wins
                    // was considered, on the argument that a fresher stamp is a fresher decay clock, and
                    // rejected: it would let a fork's speculative fire override the parent's established
                    // evidence, which inverts the write-once principle the rest of this state is built on.
                    //
                    // By region ALONE was the first form, and it aliases across intervals: a fork's fresh
                    // record for a region the parent holds an OLD record for would be dropped, so the
                    // current interval's suppression would lose it and the same bytes would be
                    // deliverable again next turn. The stamp keeps "the parent's evidence wins" scoped to
                    // evidence about the same interval.
                    //
                    // One consequence is accepted rather than fixed here, because the alternative is worse.
                    // A fork's expansion carrier lives in `compacted` only, and a merge keeps the
                    // receiver's served list, so a fork's delivery BYTES never survive while its record now
                    // does. Suppression and the recall pointer-answer can therefore act on a
                    // current-interval record whose bytes are not in the merged view. That window existed
                    // before, for the narrower case of a region the parent had no record for at all; the
                    // key change widens it, and it is still the right trade against the cross-interval
                    // aliasing it removes.
                    expansions = p.expansions.concat(
                        f.expansions.filterNot(e =>
                            p.expansions.exists(o => o.region == e.region && o.boundaryStamp == e.boundaryStamp)
                        )
                    ),
                    // Unioned rather than parent-wins, because these count FIRES rather than carry
                    // evidence: a fork that fired is a fire, and dropping its stamp would let the merged
                    // session fire past the interval cap.
                    expansionTurns = p.expansionTurns.concat(f.expansionTurns.filterNot(p.expansionTurns.contains))
                ))
            end if
    end merge

end Compaction
