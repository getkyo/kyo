package kyo.ai.compactor

import kyo.*
import kyo.Compactor
import kyo.Compactor.Tuning
import kyo.Schema
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*
import kyo.ai.completion.Completion
import scala.annotation.tailrec

/** The default compactor's DATA MODEL and token accounting, split out of [[Compactor]] so neither file
  * carries the whole subsystem.
  *
  * Everything here is either a shape the ranking works over (regions, spans, the edge graph, the staged
  * cells) or an accounting function over messages (stamping, apportionment, occupancy). None of it decides
  * what to keep: the decisions live with the strategy in [[Compactor]]. `Compactor.internal` re-exports this
  * object wholesale, so every existing `Compactor.internal.<name>` reference resolves here unchanged.
  */
private[kyo] object Model:

    // Pure MEASUREMENT constants, deliberately not on `Tuning`: they estimate a token count rather
    // than decide what to keep, and the seam reads occupancy with no compactor in hand.
    val imageSurchargeChars: Int = 6000 // ~2000-token vision surcharge, char units

    /** The occupancy axis: the compactor's fractions projected onto one model's window.
      *
      * Lives here rather than on `Config` because every consumer of it is compaction. The seam no
      * longer reads a watermark at all: it asks the compactor to compact and the compactor decides,
      * which is what lets the trigger be policy the compactor owns.
      *
      * Projected at USE rather than validated at construction, because the window is a per-model fact
      * and one `Tuning` serves many models. The fraction ordering that holds without a window is
      * checked on `Tuning` itself; what is checked here is the part that needs one.
      */
    final case class Axis(high: Int, low: Int, prepare: Int, hard: Int)

    def axis(tuning: Tuning, config: Config): Axis =
        val window = config.modelContextWindow
        val frac   = (tuning.trigger * window).toInt
        val high = tuning.contextCeiling match
            case Present(ceiling) => math.min(frac, ceiling)
            case Absent           => frac
        // The output reservation, counted ONCE on the hard-limit side. A verified maximum large enough
        // to reorder the axis falls back to the conservative default, so a catalog entry whose model
        // reports a huge output ceiling still yields a coherent axis rather than failing to serve.
        val candidate = config.outputReservation
        val reserved  = if high < (tuning.hardLimit * (window - candidate)).toInt then candidate else Config.defaultMaxOutputReservation
        val low       = (tuning.target * high).toInt
        // The projection can collapse two distinct fractions onto one integer (target 0.6 and the next
        // representable double above it both project to 76800 at high=128000), so the prepare line is
        // raised to the first integer above the target rather than failing a caller whose fractions
        // were already correctly ordered.
        val prepare = math.max((tuning.prepare * high).toInt, low + 1)
        Axis(high, low, math.min(prepare, high), (tuning.hardLimit * (window - reserved)).toInt)
    end axis

    /** The default compactor's CALIBRATED constants: how this implementation ranks and presents.
      *
      * Deliberately not on [[Compactor.Tuning]]. These are measured properties of one ranking design
      * (BPE rank floors taken from the bundled vocabulary, edge weights whose ratios were fitted
      * together, byte budgets biased over a token size class), not decisions a caller makes, and a
      * caller who wants different mechanics implements [[Compactor]] instead. They stay reachable here
      * so an ablation can move exactly one of them and leave the rest untouched, which is what makes a
      * single mechanism separately measurable.
      */
    final case class Calibration(
        // --- edge weights ---
        adjacencyWeight: Double = 1.0,        // EdgeKind.Adjacency
        referenceWeight: Double = 3.0,        // EdgeKind.Reference on a structural identifier
        dependencyWeight: Double = 3.0,       // EdgeKind.Dependency, the analysis DependsOn edge
        relatednessWeight: Double = 0.5,      // EdgeKind.Relatedness, the analysis Relates edge
        contentReferenceWeight: Double = 1.5, // EdgeKind.Reference on a bare content word/bigram
        // --- the content-reference (plain-word) tier's admission gates ---
        // Drop a content term present in more than this fraction of regions. Deliberately generous: a
        // genuine reference in a focused conversation recurs across MOST turns ("the retry budget",
        // discussed throughout), so a tight fraction rejects exactly the references worth catching.
        // Frequency alone cannot separate a stopword from a reference (both recur), so the primary
        // discriminator is contentRarityFloor.
        contentDfCutoffFraction: Double = 0.9,
        // The stopword discriminator: a word's BPE rank in the tokenizer's own frequency-ordered
        // vocabulary. The merge table is built most-frequent-first, so English function words ("the",
        // "is", "line") occupy very low ranks while distinctive content words ("gizmokey", "quorum") rank
        // high or are absent entirely. A term is admitted only at or above this floor (or unranked), which
        // is a real stopword filter WITHOUT shipping a stopword list, language-general and identical on
        // every platform because the rank table is the bundled pure-Scala tiktoken one. Measured ranks
        // (o200k, space-prefixed) that set this floor: "the" 290, "is" 382, "line" 2543, "second" 3099
        // (generic prose) vs "pool" 8389, "budget" 9946, "cache" 11956, "retry" 45364, "quorum" 179068,
        // "gizmokey" unranked (the reference-bearing words). 5000 sits in that gap.
        contentRarityFloor: Int = 5000,
        contentMinRegions: Int = 2,      // a content term must recur in >= this many regions to be a reference
        contentRefPerRegionCap: Int = 8, // at most this many content-reference edges out of one region
        // --- propagation ---
        restartWeight: Double = 0.15, // PPR restart probability
        pprIterations: Int = 20,      // stability bound; the demotion decision converges by ~12
        supersessionPenalty: Double = 0.2,
        // --- seeds; the shipped values sum to 1.0 ---
        seedObjective: Double = 0.35,  // last user turn
        seedTask: Double = 0.20,       // first user turn / task origin
        seedTail: Double = 0.25,       // geometrically decayed recent tail
        seedUnresolved: Double = 0.15, // unresolved tool calls
        seedSystem: Double = 0.05,     // system head
        seedTailTurns: Int = 10,       // recent regions carrying the geometric tail seed
        seedTailTokens: Int = 12000,   // token bound on the geometric tail seed
        recallSeedWeight: Double = 0.20,
        recallDecay: Double = 0.5,
        // --- span formation and the tail band ---
        spanCapTokens: Int = 4000,
        spanCapRegions: Int = 8,
        tailBandFraction: Double = 0.25,
        // --- presentation byte budgets; char units, biased over the token size class ---
        tersePrefixChars: Int = 200,       // ~50-token terse prefix
        substituteElisionChars: Int = 800, // fixed-size summary-level substitute
        generousElisionChars: Int = 24000, // exact-surface pinned-oversized
        priorSummaryBudget: Int = 4,       // previously written summaries a fill request carries as context
        // --- automatic recall (Mechanism.Expansion) ---
        // Every value here is ASSUMED rather than measured: the experiment that sets them is a replay
        // over real agent transcripts, and this repository has no such corpus. Each ships with its
        // falsifier test written, which is the same standing every other constant here has.
        expansionShare: Double = 0.15, // one fire's budget as a share of headroom to the trigger
        // The absolute clamp on that share, following the span cap's precedent. A share of headroom is
        // largest exactly when the view is smallest, so in a session where nearly everything is demoted
        // the headroom approaches the whole trigger and 0.15 of it can exceed the view the delivery
        // lands in.
        expansionFireCeiling: Int = 3000,
        expansionPerTurn: Int = 2,             // delivered regions inside one turn's carrier
        expansionPerInterval: Int = 3,         // fires between two boundaries
        expansionThreshold: Double = 3.0,      // content-only admission: two mid-rarity terms, or one rare
        expansionPriorThreshold: Double = 1.5, // for a region the model already recalled: half the above
        expansionPruneHorizon: Int = 8,        // boundaries after which a record's decayed seed is dead
        // The damper, as a fraction of recallSeedWeight, keyed on evidence class. Damping a
        // geometrically decaying seed has an exact meaning: PRE-AGING. A record at fraction f is
        // indistinguishable in seed space from a tool record log2(1/f) boundaries older, at every
        // region count. So an identifier fire enters as an ask one boundary old and a content-only
        // fire as one two boundaries old. The derived safe range is [0.15, 0.5]: below 0.15 the record
        // is dead weight under any realistic floor, and at 1.0 a sustained guess outweighs a fresh ask
        // two to one, which leaves the weaker-evidence premise with no arithmetic expression at the
        // boundary that decides. Sustained guessing saturates at f * recallSeedWeight / (1 -
        // recallDecay), so at 0.5 the ceiling is exactly one fresh explicit ask.
        expansionSeedFractionIdentifier: Double = 0.5,
        expansionSeedFractionContent: Double = 0.25,
        // The rarity multiplier's bounds: a term at contentRarityFloor scores the low end, a term
        // absent from the vocabulary the high end, ranks between interpolate on a log scale. Bounded at
        // both ends deliberately, so no single exotic token can dominate a score and so the admission
        // thresholds above have stable units.
        expansionRarityMin: Double = 1.0,
        expansionRarityMax: Double = 2.0,
        // --- raw-retention eviction: the band between the two watermarks ---
        rawHighWatermark: Double = 0.9,
        rawLowWatermark: Double = 0.5,
        // The raw cap when the user leaves `Config.Compaction.rawRetentionCap` Absent: several
        // window-widths, since raw is only a verbatim cache behind the persisted summaries.
        rawRetentionWidths: Int = 4
    )

    /** The detail ladder: how much of a region's content the served view still carries.
      *
      * Content descends this ladder rather than being deleted, which is the design's central claim: a
      * demoted region stays ADDRESSABLE (its marker carries an `Origin`) even once its bytes are gone,
      * so the model can tell history was folded and recall can pull it back. Deletion forecloses both.
      *
      * `Verbatim` is not a demoted state and never enters a demotions map, so `project` only ever
      * renders the other three.
      *
      *   - `Verbatim`: the original messages, unchanged.
      *   - `Summary`: a model-written summary standing in for a whole span, the grain summaries are
      *     staged at.
      *   - `Terse`: a descent-only prefix of the summary bytes, reached when a span already demoted to
      *     `Summary` must give up more room.
      *   - `Pointer`: a marker with no body at all, at region grain. It says content existed here and
      *     where, and nothing about what it said.
      */
    enum Level derives CanEqual:
        case Verbatim, Summary, Terse, Pointer

        /** How much content this level carries, as a total order over the ladder.
          *
          * The ladder is a total order by construction (each level is a strict reduction of the one
          * above), but the enum's declaration order is descending while a comparison reads more
          * naturally ascending, so the mapping is written out rather than taken from `ordinal`.
          */
        def detail: Int = this match
            case Verbatim => 3
            case Summary  => 2
            case Terse    => 1
            case Pointer  => 0

        /** Whether this level carries STRICTLY more content than `other`.
          *
          * The admissibility test automatic recall runs before every fire: a delivery is worth a slot
          * only if it moves the region up, and an equal level would spend budget re-presenting bytes the
          * model can already read.
          */
        def above(other: Level): Boolean = detail > other.detail
    end Level

    /** The atomic unit of ranking and demotion: an assistant message fused with the tool results that
      * answer it.
      *
      * Fusion is what makes a region atomic. A tool call and its result are one exchange, and demoting
      * either half alone would leave the model either a call with no answer or an answer with no call,
      * so they rank and descend together.
      *
      * @param id
      *   the region's identity, which is the raw ordinal of its first message. Ordinals are NOT
      *   renumbered by the retention forget, so an id stays valid for the life of the session and a
      *   summary or analysis keyed by it can never alias a later region. For a folded forgotten band
      *   this is the band's start ordinal rather than any surviving message.
      * @param indices
      *   every raw ordinal this region covers, ascending: the owning message first, then each
      *   answering tool result in arrival order. A region with no tool calls covers exactly one.
      * @param unresolved
      *   true while at least one of the region's tool calls has no answering result yet, so the turn
      *   is still in flight. An unresolved region is not eligible to be aged into the closed prefix,
      *   because its content is not final.
      * @param tokens
      *   the apportioned stamped token total over `indices`, in the active tokenizer's units
      */
    final case class Region(id: Int, indices: Chunk[Int], unresolved: Boolean, tokens: Int) derives CanEqual

    /** A region under construction during [[Default.group]], before its token total is known.
      *
      * Separate from [[Region]] because grouping is a single forward pass: an assistant message opens
      * a unit whose answering results arrive at later ordinals, so the unit must stay mutable-shaped
      * until the pass ends. Sizing happens once at the end, over the finished index list.
      *
      * @param id
      *   the owning message's raw ordinal, which becomes the region's id
      * @param indices
      *   the ordinals fused so far, owner first
      * @param open
      *   the call ids still awaiting a result. A result removes its id here; whatever remains when the
      *   pass ends is what makes the finished region `unresolved`.
      */
    final case class Building(id: Int, indices: Chunk[Int], open: Set[String])

    /** A contiguous run of regions: the grain the summary level works at.
      *
      * Summaries are staged per span rather than per region because one model call covering a run of
      * related turns is both cheaper and more coherent than one call per turn. Identity is a
      * deterministic model-free function of frozen content, so the same span forms again on every
      * pass and its write-once summary slot is found rather than duplicated.
      *
      * @param start
      *   the inclusive raw ordinal of the span's first message, and with `end` the summary slot's key
      * @param end
      *   the exclusive raw ordinal bound of the span
      * @param regionIds
      *   the ids of the regions in the run, ascending
      */
    final case class Span(start: Int, end: Int, regionIds: Chunk[Int]) derives CanEqual

    /** Why one region points at another, which is what sets the edge's weight.
      *
      * Four kinds rather than one weighted number because they come from different evidence and deserve
      * different confidence. Two are STRUCTURAL, derived from the transcript alone and always available:
      *
      *   - `Adjacency`: the regions are neighbours. The weakest signal, and the reason a conversation with
      *     no other structure still ranks its recent turns above its oldest.
      *   - `Reference`: a later region names something an earlier one introduced, either a structural
      *     identifier or a distinctive content term. This is the tier that keeps a fact alive because the
      *     conversation kept using it.
      *
      * Two are ANALYZED, emitted by the model and present only when the analysis mechanism runs:
      *
      *   - `Dependency`: the model said this region depends on that one. Weighted as heavily as a
      *     structural reference, because a stated dependency is stronger evidence than a repeated word.
      *   - `Relatedness`: the model said the two are merely related. Deliberately weak, so a model that
      *     relates everything to everything cannot flatten the ranking.
      */
    enum EdgeKind derives CanEqual:
        case Adjacency, Reference, Dependency, Relatedness

    /** One directed edge in the liveness graph.
      *
      * @param target
      *   the region id this edge points at, always an EARLIER region: liveness flows backwards, from the
      *   turns still in play toward what they depend on
      * @param kind
      *   why the edge exists, see [[EdgeKind]]
      * @param weight
      *   the edge's share of its source's outbound mass, taken from the calibration for its kind
      */
    final case class Edge(target: Int, kind: EdgeKind, weight: Double) derives CanEqual

    /** The liveness graph: outbound edges per region id.
      *
      * Adjacency-list rather than a matrix because the graph is sparse (a region references a handful of
      * others, not hundreds) and Personalized PageRank only ever walks a node's outbound list.
      *
      * @param edges
      *   region id to its outbound edges. A region absent from the map is DANGLING, and this PPR does not
      *   renormalize such a node's mass: it is simply lost each iteration, which is why an ablation that
      *   zeroes an edge class changes the system's total mass and not only its structure, and why a
      *   mechanism's effect is read as a rank change rather than as a floor crossing.
      */
    final case class Graph(edges: Dict[Int, Chunk[Edge]]):
        def isEmpty: Boolean = edges.isEmpty
    object Graph:
        val empty: Graph = Graph(Dict.empty)

    /** Everything edge derivation reads out of the transcript, extracted once.
      *
      * Every field here is a function of `units` and `raw` alone. Nothing in it depends on
      * supersession, on the analysis edges, or on any part of the compaction state, which is what lets
      * a boundary build it once and derive the graph from it more than once. The two tokenizations
      * (identifiers and content words, each over every region's full text) are the bulk of a
      * boundary's non-model work, so extracting them is where the saving is.
      */
    final case class TokenIndex(
        ordered: List[Region],
        perUnit: List[(Int, Set[String])],
        introducer: Dict[String, Int],
        mentions: Map[String, Int],
        adjacency: List[(Int, Edge)],
        perUnitContent: List[(Int, Set[String])],
        contentDf: Map[String, Int],
        contentIntro: Dict[String, Int],
        dfMax: Int
    )

    /** What a fired boundary works out about the transcript before it decides anything.
      *
      * A boundary runs its decision pipeline TWICE in the foreground: once to compute the fill need it
      * will join on, and once to project the view that gets served. Between the two, adoption writes
      * summaries and analyses into the compaction state, and nothing else: `raw`, `compacted`, the
      * config, the tool infos, the recall records and the boundary counter are all fixed across the
      * pair, because the counter is ticked once before either run.
      *
      * Every field below is a function of exactly those fixed inputs, so computing it twice is
      * guaranteed to produce the same value. It is therefore computed once and threaded. What is NOT
      * here is everything that legitimately reads the adopted summaries and analyses: the merged
      * supersession map, edge derivation (structural edges repoint through supersession, so they are
      * not invariant even though their token inputs are), scoring, the cut, and the projection. Those
      * re-run on the second pass and must keep re-running.
      */
    final case class BoundaryFacts(
        units: Chunk[Region],
        spans: Chunk[Span],
        keys: Dict[Int, (String, Tool.Kind)],
        index: TokenIndex,
        seed: Dict[Int, Double],
        prevLevels: Dict[Int, Context.Origin],
        since: Int,
        occupied: Int
    )

    /** What automatic recall retains between boundaries so a per-turn fire costs the turn rather than
      * the history.
      *
      * The whole efficiency claim of the mechanism is a PLACEMENT decision rather than an algorithmic
      * one. Detecting relevance means comparing the newest turn against the not-fully-presented set, and
      * the obvious implementation tokenizes the transcript on every turn, since the boundary's own
      * [[TokenIndex]] is transient. But the not-fully-presented set changes only at boundaries. So this
      * is built once, at the boundary that demoted the content, as a FILTER of the index the boundary
      * already built, and it is retained until the next one. Per turn the cost is then tokenizing the
      * turn and probing a map.
      *
      * Stored INVERTED, and that is not incidental: probing per-region term sets instead would cost the
      * turn's term count times the demoted-region count, which reintroduces exactly the history factor
      * the placement removes.
      *
      * Lives in the ephemeral [[Preparation]] cell rather than in `Compaction.State`, on the module's own
      * rule that derivable bookkeeping is never persisted: it is wholly a function of `raw` and the
      * served view. A restore therefore correctly loses it, and the first fire attempt after one
      * re-derives it from the served markers rather than leaving the mechanism dark for an interval.
      *
      * @param boundaryStamp
      *   the boundary counter at build. Interval identity, so a fire can tell this interval's records
      *   from earlier ones and the two index constructions can be told apart in the field.
      * @param rawWatermark
      *   `raw.size` at build. The suffix beyond it is exactly this interval's appends, which is what
      *   bounds the since-the-boundary supersession scan to the interval rather than to history.
      * @param byTerm
      *   term to the regions holding it: the probe path. Both tiers live here, identifiers and
      *   distinctive content terms, each already past its document-frequency ceiling.
      * @param entries
      *   region id to everything a delivery needs about it
      * @param source
      *   which construction produced this index. Carried rather than inferred because the two paths are
      *   the two-paths-that-drift shape, and the only thing that makes a drift visible in the field
      *   rather than at review time is every fire naming the path it read.
      */
    final case class ExpansionIndex(
        boundaryStamp: Int,
        rawWatermark: Int,
        byTerm: Dict[String, Chunk[Int]],
        entries: Dict[Int, ExpansionIndex.Entry],
        source: ExpansionIndex.Source
    )

    object ExpansionIndex:

        /** How an [[ExpansionIndex]] came to exist.
          *
          *   - `Boundary`: filtered out of the index a fired boundary had already built, which is the
          *     ordinary path and the cheap one.
          *   - `Restored`: rebuilt from the served markers after a snapshot restore lost the session
          *     cell. Correct but not free, since the document-frequency ceiling is defined over all
          *     regions and so the rebuild tokenizes the whole transcript.
          */
        enum Source derives CanEqual:
            case Boundary, Restored

        /** One not-fully-presented region, as automatic recall needs to see it.
          *
          * @param region
          *   the region id, which is its first raw ordinal
          * @param indices
          *   every raw ordinal the region covers: the verbatim delivery's source
          * @param spanStart
          *   with `spanEnd`, the write-once summary slot's key, so a summary-tier delivery finds the
          *   same bytes the marker would render
          * @param spanEnd
          *   the exclusive raw ordinal bound of the region's span
          * @param level
          *   the boundary's own assignment: `Summary`, `Terse` or `Pointer`. Never `Verbatim`, since a
          *   verbatim region is fully presented and has nothing to expand.
          * @param identifiers
          *   the region's structural identifiers, for classifying a hit rather than for probing. The
          *   probe goes through `byTerm`; this answers "was the term that matched an identifier", which
          *   is what fixes the delivery's target level.
          * @param key
          *   the region's tool supersession key, if it has one: the per-turn gate against delivering
          *   content a later call has already replaced
          * @param superseded
          *   whether the region was already superseded at build time
          * @param tokens
          *   the stamped region size, which is the verbatim delivery's price
          */
        final case class Entry(
            region: Int,
            indices: Chunk[Int],
            spanStart: Int,
            spanEnd: Int,
            level: Level,
            identifiers: Set[String],
            key: Maybe[(String, Tool.Kind)],
            superseded: Boolean,
            tokens: Int
        ) derives CanEqual
    end ExpansionIndex

    /** A not-fully-presented region the newest user turn matched, with everything the allocator needs to
      * price it and the observability layer needs to explain it.
      *
      * @param entry
      *   the index entry the match resolved to
      * @param score
      *   the ranking's own edge weights applied to the matched terms, each weighted by rarity, so the
      *   subsystem keeps one confidence vocabulary rather than inventing a second
      * @param identifierHit
      *   whether any matched term is one of the region's structural identifiers. This is the evidence
      *   CLASS, and it fixes both the delivery's target level and the damper's fraction: an identifier is
      *   a request for the referent's exact content, a content term is topical.
      * @param matched
      *   the terms that matched, which the excerpt renderer centres its window on and which the
      *   suppression log names
      */
    final case class Candidate(
        entry: ExpansionIndex.Entry,
        score: Double,
        identifierHit: Boolean,
        matched: Set[String]
    )

    /** Why a matched region did NOT get a delivery.
      *
      * A CLOSED set, and closed by the type system rather than by a test: the reason is a required field
      * wherever a suppression is reported, so a new branch cannot omit one and no test has to notice. A
      * layer that guesses degrades silently otherwise, which the analysis pass already demonstrated: one
      * that is finding nothing and one that is broken look identical from outside.
      *
      * The pairs that look alike have opposite fixes, which is why they stay separate. `AlreadyAtLevel`
      * says the boundary never demoted the region far enough for content evidence to matter;
      * `SameLevelDedup` says this interval already delivered at that depth. `TurnCap` says the fire ran
      * out of slots; `IntervalCap` says the interval ran out of fires; `Budget` says the tokens ran out.
      *
      * `Forgotten` is an ALARM rather than a statistic. The index is rebuilt after every eviction, so no
      * live entry should be able to point at forgotten content and this should never be emitted; the
      * defensive check that produces it costs one read. It exists as its own case because the alternative,
      * folding that branch into `Superseded`, means the one line that would tell you the invariant broke
      * says something else instead. It is NOT the retention cap's error signal, which would need evicted
      * regions' term sets retained past the eviction that destroys their bytes.
      */
    enum Suppression derives CanEqual:
        case BelowThreshold, Superseded, Forgotten, AlreadyAtLevel, RecalledThisInterval, SameLevelDedup, TurnCap,
            IntervalCap, Budget

        // One hyphenated token each, deliberately: the lines these appear on are meant to survive
        // whitespace tokenization, and a label with a space in it silently breaks that.
        def label: String = this match
            case BelowThreshold       => "below-threshold"
            case Superseded           => "superseded"
            case Forgotten            => "forgotten"
            case AlreadyAtLevel       => "already-at-level"
            case RecalledThisInterval => "recalled-this-interval"
            case SameLevelDedup       => "same-level-dedup"
            case TurnCap              => "turn-cap"
            case IntervalCap          => "interval-cap"
            case Budget               => "budget"
    end Suppression

    /** What one turn's detection found, including what it REFUSED.
      *
      * The refusals are carried rather than dropped because the interesting failure of a guessing
      * mechanism is invisible otherwise: a detector that never fires is indistinguishable from a session
      * that never needed one. `topRejected` is the direct under-fire counter, and crossing it with the
      * model's own `recall` calls gives the one measurement that matters most, tool-asked while
      * detector-silent.
      */
    final case class Detection(
        admitted: Chunk[Candidate],
        suppressed: Chunk[(Int, Suppression)],
        topRejected: Maybe[(Int, Double)]
    )

    /** One delivered block: the candidate, what it delivered, and the BYTES that went out.
      *
      * The bytes are carried rather than re-rendered because pricing and emission must be the same value.
      * Rendering twice is how a delivery gets priced at one size and emitted at another, which is exactly
      * the defect that let a fire cost 1278 tokens against a 779 budget.
      */
    final case class Allocated(candidate: Candidate, delivered: Delivered, block: String)

    // The write-once staging cell key: a span's raw ordinal range. Ordinals are
    // stable under the retention forget, so keys never alias across a session.
    final case class SpanKey(start: Int, end: Int) derives CanEqual

    // staging cell. Write-once summaries keyed by SpanKey and write-once analyses
    // keyed by region ordinal; the background fiber CAS-updates both as calls complete.
    final case class Staged(
        summaries: Dict[SpanKey, String] = Dict.empty[SpanKey, String],
        analyses: Dict[Int, RegionAnalysis] = Dict.empty[Int, RegionAnalysis]
    ):
        // First-writer-wins: a summary lands only into an EMPTY slot; a re-emission to a filled
        // slot is discarded, so whichever bytes reach the slot first are permanent.
        def withSummary(key: SpanKey, bytes: String): Staged =
            if summaries.contains(key) then this
            else copy(summaries = summaries.update(key, bytes))
        def summaryOf(key: SpanKey): Maybe[String] = summaries.get(key)
        // write-once analysis staging by region ordinal; a re-emission to a filled ordinal is
        // discarded, so whichever analysis lands first is permanent (idempotent under re-arming).
        def withAnalysis(ordinal: Int, ra: RegionAnalysis): Staged =
            if analyses.contains(ordinal) then this
            else copy(analyses = analyses.update(ordinal, ra))
        def analysisOf(ordinal: Int): Maybe[RegionAnalysis] = analyses.get(ordinal)
    end Staged

    // One per session: the staging cell plus the single-flight handle and whether the session is
    // currently inside the prepare band (latched on the line-cross, cleared on a recross-below, so
    // it re-arms only when the line is recrossed). The background fiber writes ONLY `staged` (CAS
    // updates); the foreground adopts staged entries into write-once compaction state and joins the
    // fiber through `inFlight` at the boundary.
    final case class Preparation(
        staged: AtomicRef[Staged],
        inFlight: AtomicRef[Maybe[Fiber[Unit, Any]]],
        armed: AtomicRef[Boolean],
        // The retained expansion index, rebuilt at every boundary and read by every per-turn fire
        // attempt. Absent before the first boundary, which is correct rather than degraded: nothing
        // is demoted yet, so there is nothing to expand. Absent after a snapshot restore too, which
        // the first fire attempt repairs by re-deriving from the served markers.
        expansion: AtomicRef[Maybe[ExpansionIndex]]
    )

    object Preparation:
        def init(using Frame): Preparation < Sync =
            for
                s <- AtomicRef.init(Staged())
                f <- AtomicRef.init(Absent: Maybe[Fiber[Unit, Any]])
                a <- AtomicRef.init(false)
                x <- AtomicRef.init(Absent: Maybe[ExpansionIndex])
            yield Preparation(s, f, a, x)
    end Preparation

    // Lifecycle: interrupt this session's in-flight preparation fiber, if any. Reached through
    // Compactor.release at session teardown, so a fiber cannot outlive the session that forked it.
    // A boundary never calls this (it JOINS); only teardown and a disarm do. Interrupting a completed
    // fiber is a harmless no-op.
    def releasePreparation(prep: Preparation)(using Frame): Unit < Sync =
        prep.inFlight.getAndSet(Absent).map {
            case Present(f) => f.interrupt.unit
            case Absent     => Kyo.unit
        }

    /** The recall tool's typed input, object-wrapped so the wire schema is
      * `{"id":{"type":"integer"}}` (providers reject a bare integer parameter schema).
      */
    final case class Recall(id: Int) derives Schema, CanEqual

    // --- usage-anchored occupancy, apportionment, and stamp reads ---
    // The demotion loop reads STORED stamps and makes zero requests; counting narrows to
    // apportionment (stamping, below) and the offline suffix/bootstrap estimate.

    // The stored apportioned count for a message. A message with no stamp yet (a fresh synthetic
    // marker, or a bootstrap message before the first apportionment) falls back to the offline estimate.
    def stampedTokens(msg: Message): Int =
        msg.tokens match
            case Present(stamp) => stamp.count
            case Absent         => offlineEstimate(msg)

    // The offline conservative char-based estimate (bootstrap + suffix delta): ~1 token per 3
    // chars plus a per-message envelope, biased to over-count so occupancy never under-reads. A
    // user-message image adds a fixed conservative surcharge.
    def offlineEstimate(msg: Message): Int =
        val chars = msg match
            case AssistantMessage(c, calls, _, _) => c.length + calls.foldLeft(0)((n, x) => n + x.arguments.length)
            case UserMessage(c, image, _, _)      => c.length + (if image.isDefined then imageSurchargeChars else 0)
            case m                                => m.content.length
        (chars + 2) / 3 + 4
    end offlineEstimate

    // Usage-anchored occupancy: the last provider-reported request total plus the offline
    // estimate of the messages appended to the served view since that anchor; bootstrap / no-usage
    // falls wholly to the offline estimate over the served view. maxOutputTokens is NOT part of this
    // (it is counted once on the hard-limit side).
    def occupancy(ctx: Context): Int =
        val state = ctx.compactionState
        state.lastUsage match
            case Present(total) =>
                // The suffix rides in tokenizer units: stampLiveSuffix stamps each live
                // appended message with its tokenizer count at the turn start, so stampedTokens reads
                // the tokenizer count, not the char/3 estimate the doc rejects; a still-unstamped
                // message falls back to offlineEstimate.
                val suffix = ctx.compacted.drop(state.lastUsageRawSize).foldLeft(0)((n, m) => n + stampedTokens(m))
                total + suffix
            case Absent =>
                // The wholly-offline path (a provider that never reports usage): tokenizer-stamped sum
                // under a DISTINCT widened margin, so the never-corrected whole-session
                // case carries extra overflow headroom the each-turn-corrected anchored suffix does not.
                val offline = ctx.compacted.foldLeft(0)((n, m) => n + stampedTokens(m))
                (offline * noUsageMargin).toInt
        end match
    end occupancy

    // The keep floor: the liveness a span's hottest member must hold to stay verbatim, as a multiple of
    // the nominal uniform share 1/N. Deliberately NOT a function of pressure. Pinning answers "is this
    // content live", which is a property of the content; the budget is pass 2's job, and pass 2 already
    // adapts by descending levels until the view fits. Scaling the floor by pressure made it serve both
    // masters and reintroduced the very failure the share form exists to remove, with pressure as the
    // driver instead of N: at pressure 4 a region holding twice the mean share demoted anyway.
    //
    // `regions` counts every region, not only the spannable closed ones, so "uniform share" means
    // uniform over the whole context. The zero-region branch is unreachable (no regions means no spans,
    // so no span's floor is ever consulted) and is written to be total rather than to divide by zero.
    def keepFloor(regions: Int, tuning: Tuning): Double =
        if regions <= 0 then tuning.keepShare
        else tuning.keepShare / regions.toDouble

    // Apportionment: count each served-view message with the ACTIVE tokenizer, then normalize
    // those exact counts to sum EXACTLY to the provider's reported total via a largest-remainder
    // distribution (structural overhead absorbed by the normalization, relative ordering preserved,
    // no token lost or invented). Each message is stamped (tokenizerId, count), never a bare count,
    // so a stamp never mixes vocabularies across a provider switch. The tokenizer counts, never the
    // offline char estimate: the chars/3 estimate is content-dependent and shifts apportioned mass
    // from prose regions to tool-result regions, so the demotion loop must read the tokenizer's exact
    // per-message sizes (offlineEstimate stays only the bootstrap/suffix fallback).
    def apportion(messages: Chunk[Message], reportedTotal: Int, tokenizer: Tokenizer, tokenizerId: String)(
        using Frame
    ): Chunk[Message] < (LLM & Async & Abort[HttpException | AIGenException]) =
        if messages.isEmpty || reportedTotal <= 0 then Kyo.lift(messages)
        else
            Tokenizer.internal.countMessages(tokenizer, messages).map { counts =>
                val total = counts.foldLeft(0)((n, x) => n + x)
                if total <= 0 then messages
                else
                    val exact  = counts.map(c => c.toDouble * reportedTotal / total)
                    val floors = exact.map(_.toInt)
                    val used   = floors.foldLeft(0)((n, x) => n + x)
                    val order  = exact.zipWithIndex.sortBy((v, _) => -(v - math.floor(v))).map(_._2)
                    val bump   = order.take(math.max(0, reportedTotal - used)).toSet
                    messages.zipWithIndex.map { (m, i) =>
                        val count = floors(i) + (if bump.contains(i) then 1 else 0)
                        stamp(m, TokenStamp(tokenizerId, count))
                    }
                end if
            }

    def stamp(m: Message, s: TokenStamp): Message = m match
        case msg: SystemMessage    => msg.copy(tokens = Present(s))
        case msg: UserMessage      => msg.copy(tokens = Present(s))
        case msg: AssistantMessage => msg.copy(tokens = Present(s))
        case msg: ToolMessage      => msg.copy(tokens = Present(s))

    // The active tokenizer and its vocabulary id: the user override when set, else the offline
    // tiktoken default (o200k for the openai-compatible tail). The id tags every apportioned stamp so
    // counts never cross vocabularies; a provider switch changes the id and the next anchor re-stamps.
    def activeTokenizer(config: Config): (Tokenizer, String) =
        config.tokenizer match
            case Present(t) => (t, s"${config.provider.name}:user")
            case Absent     => (Tokenizer.tiktoken(Tokenizer.Encoding.O200kBase), "o200k")

    // The fused usage re-anchor, the ONE step every usage-consumption site runs so the anchor
    // scalar and the per-message stamps it covers can never disagree: record the provider's exact
    // reported total as the anchor at the sent view's size, apportion EXACTLY the sent view (the
    // messages that total covers, never the post-response tail), and propagate each apportioned stamp
    // onto the identical raw entry. The full sent view is re-stamped every anchor (not only where a
    // stamp is Absent) so a mid-session provider switch converges in one round-trip.
    def reanchor(ctx: Context, sentView: Chunk[Message], reportedTotal: Int, tokenizer: Tokenizer, tokenizerId: String)(
        using Frame
    ): Context < (LLM & Async & Abort[HttpException | AIGenException]) =
        apportion(sentView, reportedTotal, tokenizer, tokenizerId).map { stamped =>
            val anchored = ctx.withCompaction(ctx.compactionState.withUsage(reportedTotal, sentView.size))
            // Live raw twins take their apportioned stamp through propagateStamps; synthetic markers
            // (origin Present) have no raw twin, so their apportioned share is written back into the
            // SERVED view here, so viewTokens and demotion
            // sizing price a marker by its share, never the char estimate.
            anchored.copy(
                raw = propagateStamps(anchored.raw, stamped),
                compacted = propagateMarkerStamps(anchored.compacted, stamped)
            )
        }

    // Propagate the sent view's apportioned stamps onto the identical raw entries by core identity
    // (Context.coreEq ignores tokens, so a freshly stamped copy still matches its raw twin). Only a
    // LIVE raw entry (origin Absent) is eligible: a coarse band head or content-freed tombstone
    // (origin Present, stamp 0) is projection-skipped and must never be overwritten. A synthetic
    // marker in the sent view (origin Present) has no raw twin and is excluded from the match.
    def propagateStamps(raw: Chunk[Message], stampedView: Chunk[Message]): Chunk[Message] =
        val live = stampedView.filter(_.origin.isEmpty)
        raw.map { m =>
            if m.origin.isDefined then m
            else
                live.find(s => Context.coreEq(s, m)) match
                    case Some(s) =>
                        s.tokens match
                            case Present(t) => stamp(m, t)
                            case Absent     => m
                    case None => m
        }
    end propagateStamps

    // Install each synthetic marker's apportioned stamp into the served view. A marker
    // (origin Present) is matched by its Origin, unique to a marker; a live entry (origin Absent) is
    // stamped through raw propagation + the suffix pass, never here.
    def propagateMarkerStamps(compacted: Chunk[Message], stampedView: Chunk[Message]): Chunk[Message] =
        val markers = stampedView.filter(_.origin.isDefined)
        compacted.map { m =>
            if m.origin.isEmpty then m
            else
                markers.find(s => s.origin == m.origin) match
                    case Some(s) =>
                        s.tokens match
                            case Present(t) => stamp(m, t)
                            case Absent     => m
                    case None => m
        }
    end propagateMarkerStamps

    // The distinct widened margin for a provider that never reports usage: the wholly
    // offline occupancy carries extra overflow headroom the each-turn-corrected anchored suffix does
    // not need.
    val noUsageMargin: Double = 1.15 // not configuration: it prices the uncertainty of an offline
    // estimate against a reported count, so a caller cannot know better than the estimator does

    // The seated stream-usage carrier: a prior streaming turn's reported-usage SINK (written
    // by the adapter SSE projection at stream end, OUTSIDE the LLM handler, hence an AtomicRef), plus
    // the rendered sent view and the active tokenizer/id captured when the stream request was
    // assembled. applyStreamMeasure consumes it at the next turn's start. Ephemeral, never serialized.
    case class StreamAnchor(
        usageSink: AtomicRef[Maybe[AIStats]],
        sentView: Chunk[Message],
        tokenizer: Tokenizer,
        tokenizerId: String
    )

    // the suffix in tokenizer units: tokenizer-count every LIVE message (origin Absent)
    // with no stamp yet and stamp it in BOTH compacted and raw twins (by coreEq, mirroring
    // propagateStamps). A raw un-normalized count the next anchor apportions and overwrites; a marker
    // (origin Present) is owned by the anchor's apportioned share, never stamped here. Returns ctx by
    // reference when nothing needs stamping, so a below-trigger re-serve stays reference-identical.
    // Model-free: the offline tiktoken count is a pure local call.
    def stampLiveSuffix(ctx: Context, tokenizer: Tokenizer, tokenizerId: String)(using
        Frame
    ): Context < (LLM & Async & Abort[HttpException | AIGenException]) =
        val pending = ctx.compacted.filter(m => m.origin.isEmpty && m.tokens.isEmpty)
        if pending.isEmpty then Kyo.lift(ctx)
        else
            Tokenizer.internal.countMessages(tokenizer, pending).map { counts =>
                val stampedPending = pending.zipWithIndex.map((m, i) => stamp(m, TokenStamp(tokenizerId, counts(i))))
                def restamp(m: Message): Message =
                    if m.origin.isDefined || m.tokens.isDefined then m
                    else
                        stampedPending.find(s => Context.coreEq(s, m)) match
                            case Some(s) =>
                                s.tokens match
                                    case Present(t) => stamp(m, t)
                                    case Absent     => m
                            case None => m
                ctx.copy(compacted = ctx.compacted.map(restamp), raw = ctx.raw.map(restamp))
            }
        end if
    end stampLiveSuffix

    // the turn-start measure step, shared by gen (eval) and stream
    // (streamAgainst). FIRST apply a pending stream re-anchor (a prior streaming turn seated its
    // reported-usage sink + sent view; the anchor updates HERE, the next turn's start, the only point
    // the LLM handler is live, observationally equivalent to gen's post-response re-anchor since
    // occupancy's sole consumer is this same turn). THEN stamp any live suffix in tokenizer units.
    // Idempotent: no pending anchor + every live message already stamped makes no write, so a
    // below-trigger re-serve is byte-identical. Model-free: zero completions,
    // no fiber.
    def applyStreamMeasure(ai: AI, config: Config)(using
        Frame
    ): Unit < (LLM & Async & Abort[HttpException | AIGenException]) =
        ai.context.map { ctx =>
            LLM.session(ai).map { session =>
                val anchored: (Context, Boolean) < (LLM & Async & Abort[HttpException | AIGenException]) =
                    session.streamAnchor match
                        case Present(anchor) =>
                            anchor.usageSink.get.map {
                                case Present(u) =>
                                    reanchor(ctx, anchor.sentView, u.inputTokens.toInt, anchor.tokenizer, anchor.tokenizerId)
                                        .map(rc => (rc, true))
                                case Absent => Kyo.lift((ctx, false))
                            }
                        case Absent => Kyo.lift((ctx, false))
                anchored.map { (ctx1, reanchored) =>
                    val (tokenizer, tokenizerId) = activeTokenizer(config)
                    stampLiveSuffix(ctx1, tokenizer, tokenizerId).map { ctx2 =>
                        val session1       = if reanchored then session.clearStreamAnchor else session
                        val ctxChanged     = !(ctx2 eq ctx)
                        val sessionChanged = !(session1 eq session)
                        val writeSession   = if sessionChanged then LLM.setSession(ai, session1) else Kyo.unit
                        val writeContext   = if ctxChanged then ai.setContext(ctx2) else Kyo.unit
                        writeSession.andThen(writeContext)
                    }
                }
            }
        }
    end applyStreamMeasure

    // ---------------------------------------------------------------------------------------
    // The single shipped default. `compact` owns the boundary: it ticks the clock, adopts what
    // preparation staged, derives the fill need and joins it, renders, installs, evicts, and rebuilds
    // automatic recall's index. The projection rebuilds only `compacted`; eviction is the one step that
    // writes `raw`. The adopt, derive-need and join sequence lives HERE rather than at the seam, which an
    // earlier form of this comment still described. The render itself reads the ACTIVE kyo.ai.Config live,
    // makes ZERO model calls, and forks NO fibers: it derives A_fresh and projects from the write-once
    // summaries already adopted, so every summary-level slot it reads is filled. The validity gate selects
    // the join's need source; the rendered assignment is always A_fresh, so a false invalidation costs
    // zero new model calls.
    // ---------------------------------------------------------------------------------------
end Model
