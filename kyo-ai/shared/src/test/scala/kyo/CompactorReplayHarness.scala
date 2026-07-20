package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Context.*

/** The validation-first replay harness: a test-only, model-free measurement of a dumb
  * keep-the-tail-plus-one-summary BASELINE against the [[Compactor]] default over six synthetic
  * sessions. It embeds the sessions as literal `Chunk[Message]` data (no recorded corpus, no file I/O,
  * no network), replays both arms through the SAME driver, scores each on three deterministic metrics
  * (task-success predicates, integer token cost, re-fetch rate), and folds the per-session scoreboards
  * into a two-valued go/no-go [[GoNoGo]] verdict. It ships nothing into the compactor itself.
  *
  * The full arm drives the real demote/ladder/forced decision through `Compactor.init.render(ctx)` under
  * `LLM.run(config)`; the baseline arm is a harness-local projector that never touches `render`. Both
  * arms are scored through the identical metric functions over their respective `Chunk[Message]` views,
  * so the comparison is not rigged toward the full design: session 6 is a short linear axis where recency
  * alone suffices and the baseline ties the full design, and [[decide]] returns NoGo whenever the full
  * design fails to clearly win.
  *
  * The six session transcripts are sized against the compactor's fixed internal constants (tailTurns=10,
  * tailTokens=12000, elisionThreshold=8000): every discrimination session carries at least eleven
  * trailing turns so the units meant to be demoted fall OUTSIDE the pinned tail window and `render`
  * actually compacts, following the shape of `CompactorTest.demotableContext`.
  */
object CompactorReplayHarness:

    // ---- go/no-go value types (harness-local; NOT a compaction-internal enum) ----

    /** The two-valued go/no-go outcome. Go ships the full design; NoGo routes the design's
      * ship-baseline-plus-recall escape hatch to a human decision.
      */
    enum GoNoGo derives CanEqual:
        case Go, NoGo

    /** One arm's measured outcome on one session: whether the task-success predicates hold, the integer
      * token cost of the projected view, and the fraction of needed content the arm forces a re-fetch
      * for (content not present verbatim in the view).
      */
    case class Scoreboard(taskSuccess: Boolean, tokenCost: Int, refetchRate: Double) derives CanEqual

    /** Both arms' scoreboards for one session, plus the occupancy facts the frontier-drift axis reads
      * (the raw view occupancy and the two thresholds it must cross).
      */
    case class SessionScores(
        name: String,
        baseline: Scoreboard,
        full: Scoreboard,
        occupancy: Int,
        updateTrigger: Double,
        hardWindow: Double,
        window: Int,
        fullView: Chunk[Message] = Chunk.empty
    ) derives CanEqual

    // ---- construction helpers (reused verbatim from CompactorTest) ----

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def tm(id: String, s: String): ToolMessage        = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, args: String)    = Call(CallId(id), fn, args)
    def ctxOf(msgs: Message*): Context                = Context(Chunk.from(msgs))

    /** The fixed token accountant both arms count with, so the token comparison is apples-to-apples: the
      * same conservative estimator `render` itself reads from `Config.tokenizer` by default.
      */
    val tokenizer0: Compactor.Tokenizer = Compactor.Tokenizer.default

    /** A base config: the OpenAI backend pointed at an unreachable dummy URL with a dummy key, a fixed
      * model window, and the compaction knobs. `render` is model-free and forks no fibers, so the dummy
      * URL is never dialed; keeping it documents the model-free contract explicitly.
      */
    def serverConfig(window: Int, budget: Int, hard: Double = 0.9): Config =
        Config.OpenAI.default.apiKey("test").model(Config.OpenAI, "gpt-4o", window).apiUrl("http://127.0.0.1:1")
            .compactionBudget(budget)
            .compactionLowWatermark(0.45)
            .compactionHighWatermark(0.7)
            .compactionHardLimit(hard)

    /** Fixed trailing turns that push the earlier interesting units out of the fixed tailTurns=10 window,
      * so those units are non-root and demotion-eligible (the `CompactorTest.demotableContext` shape).
      */
    private def pad(n: Int): Chunk[Message] = Chunk.from((0 until n).map(i => am(s"pad$i")))

    // ---- keyed tools for the supersession session (registered on the scope env so superKeys resolves) ----

    case class FileArg(path: String) derives Schema, CanEqual

    def readTool(using Frame): Tool[Any] =
        Tool.init[FileArg]("read", kind = Tool.Kind.Read, compactionKey = (a: FileArg) => Present(a.path)) { (_: FileArg) => "" }

    def writeTool(using Frame): Tool[Any] =
        Tool.init[FileArg]("write", kind = Tool.Kind.Write, compactionKey = (a: FileArg) => Present(a.path)) { (_: FileArg) => "" }

    // ---- the dumb baseline projector (harness-local; never touches Compactor's render path) ----

    /** Keep the last N grouped turns verbatim, replacing everything before them with ONE mechanically
      * assembled summary placeholder. N is fixed and documented, never tuned per session to win. It is
      * >= the design's tailTurns=10 recency horizon, so the baseline is NOT handicapped on recency: the
      * only structural difference is that the design keeps each older unit as a recall-recoverable marker
      * (and elides/co-pins the high-signal ones), while the baseline folds all older units into one
      * opaque summary. The cut lands on grouped-unit boundaries (reusing `Default.group`), so a
      * tool_use/tool_result pair is never severed.
      */
    val baselineTailTurns = 12

    def baselineProject(transcript: Chunk[Message]): Chunk[Message] =
        val units = Default.group(transcript).toList.sortBy(_.id)
        if units.size <= baselineTailTurns then transcript
        else
            val kept         = units.takeRight(baselineTailTurns)
            val keptIdx      = kept.flatMap(_.indices).toSet
            val droppedCount = units.size - kept.size
            val summary      = SystemMessage(s"[baseline summary of $droppedCount earlier turns]")
            val keptMsgs     = transcript.zipWithIndex.collect { case (m, i) if keptIdx.contains(i) => m }
            Chunk(summary).concat(keptMsgs)
        end if
    end baselineProject

    // ---- the three deterministic metrics (identical functions for both arms) ----

    // Any compaction marker carries "region <id>", so a region id present in the view names content that
    // is recall-recoverable by slicing that unit id from the immutable transcript. The baseline summary
    // carries no region marker, so its folded content is never recoverable.
    private val markerRegex = """region (\d+)""".r

    def viewText(view: Chunk[Message]): String = view.map(_.content).mkString("\n")

    def verbatim(view: Chunk[Message], needle: String): Boolean = viewText(view).contains(needle)

    def markerIds(view: Chunk[Message]): Set[Int] =
        markerRegex.findAllMatchIn(viewText(view)).map(_.group(1).toInt).toSet

    // Recall-recoverable: the view carries a region marker whose id, sliced from the live transcript,
    // still contains the needle. The metric is recoverability, not that the model actually called recall.
    def recoverable(raw: Chunk[Message], view: Chunk[Message], needle: String): Boolean =
        val units = Default.group(raw)
        markerIds(view).exists { id =>
            units.filter(_.id == id).headMaybe match
                case Present(u) => Default.unitContent(u, raw).contains(needle)
                case Absent     => false
        }
    end recoverable

    def available(raw: Chunk[Message], view: Chunk[Message], needle: String): Boolean =
        verbatim(view, needle) || recoverable(raw, view, needle)

    def scoreArm(session: Session, view: Chunk[Message]): Scoreboard =
        val raw = session.transcript.raw
        val len = raw.size
        Scoreboard(
            taskSuccess = session.requiredNeedles.forall(n => available(raw, view, n)),
            tokenCost = Default.viewTokens(view, tokenizer0),
            refetchRate = session.neededNeedles.count(n => !verbatim(view, n)).toDouble / len.toDouble
        )
    end scoreArm

    // ---- the two-valued go/no-go decision (a real function of the numeric/boolean fields) ----

    /** The integer-token slack tolerated on the recency axis before a full-arm token regression counts
      * against a Go.
      */
    val tokenEpsilon = 4

    /** Go iff the full design clearly wins: it never loses task-success where the baseline succeeds, its
      * total token cost stays within epsilon of the baseline, its total re-fetch does not regress, and it
      * strictly wins on at least one axis somewhere. NoGo otherwise. Genuinely two-valued: a scoreboard
      * where the baseline beats the full design returns NoGo.
      */
    def decide(sessions: Seq[SessionScores]): GoNoGo =
        val losesTask   = sessions.exists(s => s.baseline.taskSuccess && !s.full.taskSuccess)
        val fullTokens  = sessions.map(_.full.tokenCost).sum
        val baseTokens  = sessions.map(_.baseline.tokenCost).sum
        val fullRefetch = sessions.map(_.full.refetchRate).sum
        val baseRefetch = sessions.map(_.baseline.refetchRate).sum
        val strictWin = sessions.exists(s =>
            (s.full.taskSuccess && !s.baseline.taskSuccess) ||
                s.full.tokenCost < s.baseline.tokenCost ||
                s.full.refetchRate < s.baseline.refetchRate
        )
        val clearWin =
            !losesTask &&
                fullTokens <= baseTokens + tokenEpsilon &&
                fullRefetch <= baseRefetch + 1e-9 &&
                strictWin
        if clearWin then GoNoGo.Go else GoNoGo.NoGo
    end decide

    // ---- the six synthetic sessions ----

    /** One synthetic session: its literal transcript, the LLM config that drives the full render path,
      * whether the keyed supersession tools are registered, its model window, and the needles the
      * task-success and re-fetch metrics read.
      */
    case class Session(
        name: String,
        transcript: Context,
        config: Config,
        keyed: Boolean,
        window: Int,
        requiredNeedles: Seq[String],
        neededNeedles: Seq[String]
    )

    // Session 1: read, re-read, write of one keyed path. The stale reads are large and sit at positions
    // 11-12 from the end (outside the fixed tailTurns=10 window but inside the baseline's keep-last-12
    // window), so the full arm supersedes and demotes them to markers while the baseline keeps them
    // verbatim; the write (with the needle) stays in the tail, so both arms retain it verbatim. Win axis:
    // token cost (full demotes the large stale reads the baseline keeps whole), both task-success.
    val session1: Session =
        Session(
            name = "session 1 (supersession)",
            transcript = Context(
                Chunk[Message](
                    sm("sys"),
                    um("task open config for KEY1"),
                    am("reading", call("r1", "read", """{"path":"KEY1"}""")),
                    tm("r1", "old contents " + ("x" * 6000)),
                    am("re-reading", call("r2", "read", """{"path":"KEY1"}""")),
                    tm("r2", "stale contents " + ("y" * 6000)),
                    am("writing", call("w1", "write", """{"path":"KEY1"}""")),
                    tm("w1", "LATEST_STATE_NEEDLE persisted")
                ).concat(pad(8)).append(um("latest confirm the write"))
            ),
            config = serverConfig(window = 128000, budget = 1),
            keyed = true,
            window = 128000,
            requiredNeedles = Seq("LATEST_STATE_NEEDLE"),
            neededNeedles = Seq("LATEST_STATE_NEEDLE")
        )

    // Session 2 + 3: an oversized tool result (> elisionThreshold=8000 chars) the full arm elides
    // (head/tail plus marker, keeping the leading needle verbatim) and an earlier note it demotes to a
    // recall-recoverable marker. The baseline folds both into its opaque summary, so it fails the task
    // (neither needle available) and re-fetches more (neither needle verbatim). Win axis: task-success +
    // re-fetch rate.
    private val oversizedResult =
        "ELIDE_HEAD_NEEDLE report begins\n" + ("z" * 30000) + "\nfinal row TAILMARK"

    val session23: Session =
        Session(
            name = "session 2 (oversized result) + session 3 (recall)",
            transcript = Context(
                Chunk[Message](
                    sm("sys"),
                    um("task analyze the report"),
                    am("noting KEY_FACT_NEEDLE for later " + ("q" * 500)),
                    am("fetching", call("f1", "read", """{"path":"REPORT"}""")),
                    tm("f1", oversizedResult)
                ).concat(pad(12)).append(um("recall the earlier note please"))
            ),
            config = serverConfig(window = 128000, budget = 8000),
            keyed = false,
            window = 128000,
            requiredNeedles = Seq("ELIDE_HEAD_NEEDLE", "KEY_FACT_NEEDLE"),
            neededNeedles = Seq("ELIDE_HEAD_NEEDLE", "KEY_FACT_NEEDLE")
        )

    // Session 4: a small window and a budget large enough that the low watermark exceeds the hard limit,
    // so the normal cut cannot get the view under the hard window and the FORCED omit-only path runs.
    // Occupancy crosses both the update trigger and the hard window. A mid-session objective change
    // references an earlier dependency via a shared identifier (LEXER_DEP), so the forced path co-pins
    // that dependency (stays live) while omitting the least-live filler, and fits under the window with no
    // over-limit send. Win axis: task-success (the moved objective's dependency survives; the baseline
    // drops it).
    val session4: Session =
        Session(
            name = "session 4 (frontier drift)",
            transcript = Context(
                Chunk[Message](
                    sm("sys"),
                    um("task build the PARSER_MODULE"),
                    am("dependency note PARSER_MODULE needs LEXER_DEP OBJDEP_NEEDLE"),
                    am("step one " + ("a" * 600)),
                    am("step two " + ("b" * 600)),
                    am("step three " + ("c" * 600)),
                    am("step four " + ("d" * 600)),
                    am("step five " + ("e" * 600)),
                    am("step six " + ("f" * 600)),
                    am("step seven " + ("g" * 600)),
                    am("step eight " + ("h" * 600)),
                    am("step nine " + ("i" * 600)),
                    am("step ten " + ("j" * 600))
                ).concat(pad(12)).append(um("new objective finish LEXER_DEP now"))
            ),
            config = serverConfig(window = 1000, budget = 3000),
            keyed = false,
            window = 1000,
            requiredNeedles = Seq("OBJDEP_NEEDLE"),
            neededNeedles = Seq("OBJDEP_NEEDLE")
        )

    // Session 5: the discriminator DISCRIMINATOR_XY lives ONLY in an EARLY NON-ROOT assistant note (the
    // triage note), never in a pinned root. The note also introduces the shared identifier LEDGER_KEY;
    // the last-user objective (a pinned root) references LEDGER_KEY, so deriveGraph draws a Ref edge
    // objective -> triage-note and coPinReferrers records the note as co-pinned by a live root. Under
    // real pressure (budget=1) the cut demotes the large filler units but skips the co-pinned triage
    // note, so DISCRIMINATOR_XY survives verbatim by transitive liveness, while the baseline's
    // keep-last-12 truncation folds the early note into its opaque summary and drops it. The two large
    // fillers sit at positions 11-12 from the end (inside the baseline window, outside the tail), so the
    // full arm also wins tokens by demoting them. The baseline outcome is MEASURED, never forced.
    val session5: Session =
        Session(
            name = "session 5 (discriminator)",
            transcript = Context(
                Chunk[Message](
                    sm("sys"),
                    um("task investigate the failing deploy pipeline"),
                    am("early triage note the LEDGER_KEY field is corrupt and DISCRIMINATOR_XY is the exact failing case"),
                    am("step one " + ("a" * 1800)),
                    am("step two " + ("b" * 1800))
                ).concat(pad(9)).append(um("confirm the LEDGER_KEY fix is landing before we close out"))
            ),
            config = serverConfig(window = 128000, budget = 1),
            keyed = false,
            window = 128000,
            requiredNeedles = Seq("DISCRIMINATOR_XY"),
            neededNeedles = Seq("DISCRIMINATOR_XY")
        )

    // Session 6: a short linear session with no supersession, no oversized result, and no recall need.
    // Recency alone suffices, so the dumb baseline keeps everything verbatim and ties the full design:
    // the full arm's graph and PPR machinery earn no advantage here. This is the non-full-favoring axis
    // that proves the harness is not rigged.
    val session6: Session =
        Session(
            name = "session 6 (short linear, recency wins)",
            transcript = ctxOf(
                um("task list the action items NEEDLE6"),
                am("here are the action items"),
                um("also add a deadline"),
                am("deadline added NEEDLE6B on Friday")
            ),
            config = serverConfig(window = 128000, budget = 1),
            keyed = false,
            window = 128000,
            requiredNeedles = Seq("NEEDLE6", "NEEDLE6B"),
            neededNeedles = Seq("NEEDLE6", "NEEDLE6B")
        )

    val sessions: List[Session] = List(session1, session23, session4, session5, session6)

    // ---- the shared replay driver (both arms) ----

    /** Replays one session through both arms and scores each identically. The full arm drives the real
      * render path (`Compactor.init.render` under `LLM.run`, with the keyed tools enabled for the
      * supersession session); the baseline arm is the local projector. Returns both scoreboards plus the
      * occupancy facts the frontier-drift axis reads. `render` is model-free and forks no fibers, so this
      * is fully deterministic with no server, no network, and no sleeps.
      */
    def replay(session: Session)(using Frame): SessionScores < (Async & Abort[AIGenException]) =
        val computation = Compactor.init.render(session.transcript)
        val withTools   = if session.keyed then AI.enable(readTool, writeTool)(computation) else computation
        LLM.run(session.config)(withTools).map { fullView =>
            val raw      = session.transcript.raw
            val baseView = baselineProject(raw)
            val config   = session.config
            val budget   = config.effectiveCompactionBudget
            val trigger  = math.max(config.compactionLowWatermark, config.compactionHighWatermark) * budget
            SessionScores(
                name = session.name,
                baseline = scoreArm(session, baseView),
                full = scoreArm(session, fullView),
                occupancy = Default.viewTokens(raw, tokenizer0),
                updateTrigger = trigger,
                hardWindow = config.compactionHardLimit * config.modelMaxTokens,
                window = session.window,
                fullView = fullView
            )
        }
    end replay

end CompactorReplayHarness
