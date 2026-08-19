# Harness Design Program: Execution Plan

Status: v2, reworked per held-out Fable review r1; pending owner gate. Date: 2026-08-19.
Owner input pending (W0.T1): doc location and audience. Until ruled, all artifacts live under
`harness-design/` in this worktree and move wholesale when the project directory exists.

## Amendment log

- v1 (2026-08-19 am): initial plan.
- v2 (2026-08-19): rework per `harness-design/reviews/plan-fable-r1.md`. Added: W1.T0 pinned
  segmented extraction; differentiated pass-B lens; ledger-append at every owner gate;
  uncited-decision audit direction; partition audit and per-area id lists; prompts for every
  dispatch including W10 dry-run, W13, W14; mini-contracts for in-session tasks with the
  adjudication escalation rule; absolute path bases in all prompts; grounding-script gate;
  spend envelope and rework loops; per-wave commits; gate packages; area-I exploration
  re-homed; area-B amendment scheduled as W3.T4; fixture-note disposition; cordis paper copied
  durably to `harness-design/inventories/cordis-paper.txt`. Model changes: W13.T1 and W14.T2
  to sonnet; W7.G exploration to sonnet.

## Launch discipline (binding)

Every agent dispatch copies its prompt from this document verbatim. Slots (`{LIKE_THIS}`) are
filled only with (a) artifact paths, or (b) text that appears verbatim in this document's
parameter blocks. Filling a slot never adds, removes, or rephrases instructions. If a prompt
proves wrong at launch, this document is amended first (dated entry in the amendment log), then
dispatched. In-session tasks executed by me run under the mini-contracts below; they are not
exempt from the discipline. Deviations without amendment are defects.

BASE = `/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus`
TRANSCRIPT = `/Users/fwbrasil/.claude/projects/-Users-fwbrasil-workspace-kyo--claude-worktrees-majestic-zooming-quiche/32789380-338f-4ca0-80c5-d4b8d798354f.jsonl`
KYO = `/Users/fwbrasil/workspace/kyo` (main workspace; inventory the working tree as-is and
record `git rev-parse HEAD` + branch in every inventory header)
REN = `/Users/fwbrasil/workspace/ren`

Every prompt below states paths absolutely or names its base explicitly.

## Program controls

- **Spend envelope**: planned dispatch counts per wave are recorded in each wave header. A wave
  exceeding 2x its planned dispatches halts and reports to the owner. Rework loops cap at 2
  iterations, then escalate to the owner with the disagreement stated.
- **Acceptance gates**: every W1 output passes a mechanical acceptance check before consumption
  (the grounding gate for ledger passes; presence/citation spot-checks for inventories). An
  output failing twice halts its wave.
- **Rework loops**: a REWORK from W5.T3 or W12.T4 loops as: me applying directives, conformance
  audit re-run always, naive and Fable reviews re-run only when changes are structural; cap 2,
  then owner.
- **Commits**: at every wave join, `harness-design/` is committed (owner identity, no
  attribution). The artifacts are the program's only state; git is the preservation mechanism.
- **Gate packages**: every owner gate presents exactly: the artifact under review, the
  conformance findings, unresolved DISPUTED or `adjudicated` ledger entries, and the open dials
  with recorded defaults. Nothing else; asks carry information or are not made.
- **Ledger appends**: every owner gate ends with a ledger-append (mini-contract ML-1) so rulings
  made during the program enter LEDGER.md with the same schema as extracted ones. W12.T2 audits
  against the ledger as appended, never the W3.T3 freeze.

## Mini-contracts for in-session tasks (FABLE, me)

Common: inputs and output path stated per task; I do not edit LEDGER.md except via ML-1/ML-2;
anything requiring an owner value-judgment escalates to the next gate rather than being decided.

- **ML-1 Gate-append**: after each owner gate, append each ruling as a ledger entry: id G-xxx,
  owner_words verbatim from the gate exchange, status ruled, source: gate-Wn. No paraphrase in
  owner_words.
- **ML-2 Adjudication (W3.T3)**: DISPUTED entries resolve only on transcript evidence (the
  stronger verbatim quote wins); resolved entries are marked `adjudicated:` with the evidence
  quote. Evidence-less disputes stay DISPUTED and go to the W6 gate package. Adjudicated entries
  are never silently equal to owner-verbatim ones.
- **ML-3 Capability map (W2.T2)**: inputs kyo-modules.md + kyo-deepdive.md; output
  `BASE/harness-design/inventories/capability-map.md`; every mapping cites the inventory line;
  BUILD items carry no design, only the gap statement.
- **ML-4 Spine skeleton (W3.T2)**: inputs merged.md + W0.T1 answer (default if unanswered:
  audience = owner plus future collaborators, register = internal engineering doc); output
  `BASE/harness-design/spine/skeleton.md`; every section lists the ledger ids it must honor.
- **ML-5 Outlines (W4.T0)**: output `BASE/harness-design/spine/outlines/<cluster>.md`, one per
  cluster; each outline lists ledger ids, inventory citations, and the open questions the
  section must mark OPEN.
- **ML-6 Area designs (C2)**: input the area's exploration + LEDGER.md + spine; output
  `BASE/harness-design/areas/<area>/design.md`; alternatives carry real strengths; new
  decision-shaped content is marked PROPOSED pending its C4 gate; a PROPOSED item accepted at
  gate enters the ledger via ML-1.
- **ML-7 Integration (W12.T1)**: seams resolved only by citing the ledger or marking OPEN;
  cross-area contradictions I cannot resolve by citation go to the W14 package.
- **ML-8 Area-B amendment (W3.T4)**: write area B's five-wave prompts into this document (dated
  amendment) once LEDGER.md exists; its extraction briefs cite ledger ids; its worked-example
  acceptance list explicitly includes the plan-correction exchange fixture (see Fixture note).

## Wave overview (planned dispatches)

- W0: owner gate (0)
- W1: pinned extraction + capture mining (script + up to 26 sonnet segment agents + 5 sonnet
  miners + 1 opus probe)
- W2: grounding gate (script) + merge (1 opus) + capability map (me)
- W3: recall (up to 5 opus) + skeleton (me) + consolidation (me) + area-B amendment (me)
- W4: outlines (me) + 4 opus drafters
- W5: 3 reviews (1 sonnet, 1 opus, 1 fable) + apply (me)
- W6: owner gate + ML-1 + partition audit (1 sonnet + me)
- W7: 8 explorations (sonnet/opus per parameters) + area J cycle (me + 1 sonnet)
- W8: owner gate + ML-1
- W9-11: area designs (me), C3 audits (1 sonnet each), gates pipelined (owner + ML-1),
  W10 dry-run (1 sonnet + 1 opus)
- W12: integration (me) + 3 reviews (1 sonnet, 1 opus, 1 fable)
- W13: apply (1 sonnet + me)
- W14: owner gate + ML-1 + freeze (1 sonnet, me verifying)

---

## Wave 0

### W0.T1 — Gate: doc location and audience — OWNER
Question: where does the harness project live, and who is the doc for. Default recorded if
unanswered by W3.T2: artifacts stay here; audience owner-plus-collaborators. Ends with ML-1.

---

## Wave 1 — pinned extraction and capture mining

### W1.T0 — Run: pinned transcript extraction — SCRIPT (me, in-session; no model)
Recorded here as the executable step:

```bash
cd /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger
python3 - "$TRANSCRIPT" <<'EOF'
import json, sys
src = sys.argv[1]
out = []
with open(src) as f:
    for line in f:
        try: o = json.loads(line)
        except: continue
        t = o.get("type")
        if t not in ("user","assistant"): continue
        m = o.get("message") or {}
        c = m.get("content")
        texts = []
        if isinstance(c,str): texts=[c]
        elif isinstance(c,list):
            texts=[b.get("text","") for b in c if isinstance(b,dict) and b.get("type")=="text"]
        txt = "\n".join(x for x in texts if x).strip()
        if not txt: continue
        if txt.startswith("Continue the bench-harness work"): continue
        if "<system-reminder>" in txt[:200]: continue
        out.append((t.upper(), txt))
open("extraction-manifest.txt","w").write(f"source={src}\nturns={len(out)}\n")
seg, size, idx, buf = 1, 0, [], []
for role, txt in out:
    entry = f"=== {role} [turn {len(idx)+1}] ===\n{txt}\n"
    buf.append(entry); size += len(entry); idx.append(seg)
    if size > 350_000:
        open(f"extraction-seg-{seg:02d}.txt","w").write("".join(buf))
        seg, size, buf = seg+1, 0, []
if buf: open(f"extraction-seg-{seg:02d}.txt","w").write("".join(buf))
print(f"segments={seg}")
EOF
wc -c extraction-seg-*.txt >> extraction-manifest.txt
```

The cutoff is the file's byte length at run time, recorded in the manifest with the run
timestamp. All transcript-reading tasks read ONLY these segments. Committed immediately.

### W1.T1 — Mining: decision ledger, pass A (chronological lens) — SONNET, one agent per segment

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Your source is ONE segment of a pinned extraction of a design conversation:
{SEGMENT_PATH}
(plain text, turns marked === USER/ASSISTANT [turn N] ===). Read it in full, in order.
Secondary context if a ruling references earlier material you lack: note it as
CONTEXT-BEFORE-SEGMENT rather than guessing.

Task: extract EVERY design decision of the harness design conversation (the thread about an
evolution machine over LLM "minds", backtests, skills, knowledge stores, model ladders; ignore
kyo-kernel implementation work except where the owner generalized it into the harness design).

One ledger entry per decision:
- id: {SEGMENT_ID}-A-001, -A-002, ...
- ruling: one sentence, present tense
- owner_words: the owner's verbatim sentence(s), quoted exactly
- anchor: a verbatim substring of owner_words, 12 words or fewer, distinctive enough to grep
- turn: the turn number from the segment markers
- rationale: the reasoning stated (owner's, or assistant reasoning the owner endorsed)
- rejected: alternatives explicitly rejected, each with the verbatim rejection
- status: ruled | default (assistant proposal not contradicted) | open (explicitly unresolved)
- supersedes: earlier ruling this reverses, if evident from your segment

Over-extract: near-duplicates are acceptable, silence is not. The line guidance (400 lines) is
soft and YIELDS to the no-drop rule: if your segment is decision-dense, exceed it rather than
drop entries.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/pass-A-{SEGMENT_ID}.md
Corpus extraction only; no analysis.
```

### W1.T2 — Mining: decision ledger, pass B (correction lens) — SONNET, one agent per segment

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Your source is ONE segment of a pinned extraction of a design conversation:
{SEGMENT_PATH}
(plain text, turns marked === USER/ASSISTANT [turn N] ===).

Your lens is DIFFERENT from a chronological sweep: work correction-outward. First locate every
USER turn that corrects, rejects, redirects, probes, or approves ("no", "lol no", "I'm not
sure", "yes, and", "that's the thing", "I think we should", "note how", explicit rulings).
For each such moment, extract the decision content it creates, changes, or confirms, walking
outward to the surrounding turns for the rationale and the rejected alternative.

Entry format, exactly:
- id: {SEGMENT_ID}-B-001, ...
- ruling: one sentence, present tense
- owner_words: verbatim
- anchor: verbatim substring of owner_words, 12 words or fewer, greppable
- turn: turn number
- rationale: stated reasoning
- rejected: what the correction displaced, with the verbatim rejection
- status: ruled | default | open
- supersedes: if evident

Also extract decisions visible only as confirmations of assistant proposals the owner accepted.
Line guidance 400, soft, yields to the no-drop rule.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/pass-B-{SEGMENT_ID}.md
Corpus extraction only; no analysis.
```

### W1.T3 — Mining: kyo module map — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Repository: /Users/fwbrasil/workspace/kyo (the main workspace; inventory the working tree
as-is; record `git rev-parse HEAD` and the branch name in your output header).

Task: the complete module inventory of the kyo platform.
1. Enumerate modules from build.sbt and the directory layout (every kyo-* module).
2. Per module: one paragraph on what it provides (README first, else public API scan),
   platforms (JVM/JS/Native), and its 3-8 most load-bearing public types, one line each.
3. Flag specifically, with file citations: effect definitions and handlers; resource safety
   (Scope); serialization/schema; container/process isolation; network/http/sql/browser reach;
   streaming; STM/actors; test infrastructure (kyo-test, doctest); AI modules.
4. Mark immature/experimental modules as such, with evidence.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/kyo-modules.md
Line guidance 500, soft. Ground every claim in a file path.
```

### W1.T4 — Mining: kyo deep-dive (ai, pod, Schema, test) — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Repository: /Users/fwbrasil/workspace/kyo (main workspace; record HEAD sha and branch in your
header). Deep-dive on four subsystems, public API signatures quoted verbatim with file:line.

1. kyo-ai (at /Users/fwbrasil/workspace/kyo/kyo-ai if present, else locate the AI module and
   name its path): the LLM effect, AI.gen, providers (vendors, configuration, base-URL
   override / OpenAI-compatible endpoint support), conversation/history handling, streaming,
   structured output. Check against design needs: LLM as tracked effect, per-call token budgets
   via handlers, model tier as handler binding, per-call cost recording.
2. kyo-pod (or the container/isolation module): isolation model, process/container launch and
   control, podman integration. Design needs: mind+container skill unit, VFS mediation,
   checkpoint hooks.
3. Schema (kyo-data / kyo-schema): derivation, expressible types, JSON round-trip, schema
   evolution. Design needs: typed neuron I/O, activation schemas, genome serialization,
   knowledge-store records.
4. kyo-test + doctest: programmatic suite execution, how a tool's acceptance tests would be
   expressed and run. Design needs: toolsmith acceptance pipeline.

Close with one BUILD-VS-HAVE table: each design-required capability, HAVE (citation) or BUILD
(one-line gap).

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/kyo-deepdive.md
Line guidance 450, soft.
```

### W1.T5 — Mining: ren reusable-component inventory — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Repository: /Users/fwbrasil/workspace/ren (record HEAD sha and branch in your header). Read the
main sources under ren/ (Mind, Neuron, Network, Gate, History, Memory, Evolution, Population,
Fitness, Selection, Crossover, Mutation, Context, Lineage, Pick and neighbors) plus the README
sections documenting them.

Classify each component:
- CARRIES: usable as-is for the harness (one line why, file citation)
- REWORK: usable with named changes (typed neuron I/O schemas, tool grants per neuron, model
  tier as gene, tool-authoring mutations, budget in fitness)
- REPLACE: superseded by a harness design decision (name which)

Also inventory: checkpoint/resume (genN-phase files), the bundled operator minds
(mind-*.json, prompt-base.md), the judge/Eval structure, the maxTestsByIteration lever. Note
anything load-bearing and undocumented.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/ren-components.md
Line guidance 400, soft. Every claim cited file:line.
```

### W1.T6 — Mining: bench-harness oracle surface — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Directory: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/kyo-kernel2/.claude/skills/kernel/bench-harness

This tool is the deterministic oracle for the harness's first skill (performance optimization).
Extract its integration contract:
1. Every runnable command (BenchRun/BenchCompare/BenchBracket/BenchPlan/BenchIngest/BenchJit/
   BenchList/BenchShow and any others): arguments, consumes, produces (files, formats).
2. The verdict vocabulary: every classification with exact criteria (thresholds, resolution,
   A/A nulls, replication), quoted from code.
3. Validity gates: every way a run is declared invalid, quoted from code.
4. The data model: Run, legs, iterations, allocations, jvmArgs, benchmarkClass.
5. Machine discipline assumed (quiet machine, no concurrent builds), as encoded or documented.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/bench-oracle.md
Line guidance 350, soft. File:line citations throughout.
```

### W1.T7 — Mining: prior-art dossier — SONNET

```
Read-only data mining plus web verification. You may write ONLY to your single output path
(the cordis paper text is already at
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/cordis-paper.txt;
read it there, do not copy anything).

Consolidate the prior-art research already performed into citable notes. Sources: the
design-thread sections of
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/INTERACTIONS.md;
the pinned extraction segments at
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/extraction-seg-*.txt
(grep for the research passages rather than reading all segments); the cordis paper text above.
Where a claim cites a web source, re-verify the link exists (fetch or search) and record it.

One note per item, minimum set: Prime Agent / Prime Intellect (ARC-AGI-3 result, /refine, RLM,
no-sandbox default); the Cordis spatiotemporal composability paper (revertible effects,
reactive coeffects, its ZIO and Effekt related-work positions); Hillis 1990 co-evolving
parasites; PAIRED; POET; AlphaZero self-play framing; FunSearch and AlphaEvolve; Dynabench;
SWE-bench family and Terminal-Bench; LLMOps trace-to-dataset loops; off-policy evaluation and
counterfactual learning; quant-finance backtest-overfitting methodology; bitemporal stores
(Datomic, XTDB), truth maintenance systems, Wikidata's statement/reference model.

Per note: what it is, the specific claims the harness design makes about it, the
differentiation, links.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/prior-art.md
Line guidance 400, soft.
```

### W1.T8 — Run: CRIU-on-podman feasibility probe — OPUS (hands-on)

```
Feasibility probe, hands-on, scoped. You may run podman commands and create files ONLY under
your scratchpad and the named output path. Do NOT touch any git worktree, do NOT run sbt, do
NOT stop or modify existing containers.

Machine: macOS with podman 5.x and a running podman machine (Linux VM). Goal: what container
checkpoint/restore can actually do here, for a design that snapshots mind+environment state.

1. Verify: podman version, machine state, criu availability inside the VM (podman machine ssh).
2. Attempt: trivial long-lived container (alpine, counting shell loop), checkpoint, restore,
   verify the counter continued. Exact commands and outputs.
3. Same with --export/--import (portable checkpoint file).
4. Same with a small JVM process in a container (write a sleep-loop java program to your
   scratchpad; any minimal JVM image). JVM checkpointability is the load-bearing question.
5. Document every failure honestly with error text: rootless vs rootful, VM criu availability,
   TCP/file-lock flags, timing.
6. Conclude per case: WORKS / WORKS-WITH-CONSTRAINTS (list) / BLOCKED (what unblocks), for
   (a) generic processes, (b) JVM processes, on this machine today.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/criu-probe.md
Line guidance 250, soft. Raw command transcripts for the load-bearing steps.
```

Join: W1.T0 manifest + all segment passes + 5 miners + probe present; commit.

---

## Wave 2 — grounding gate and first consolidation

### W2.T0 — Run: owner_words grounding gate — SCRIPT (me, in-session; no model)

```bash
cd /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger
python3 - <<'EOF'
import glob, re
segs = {p: open(p).read() for p in glob.glob("extraction-seg-*.txt")}
allseg = "".join(segs.values())
bad = 0
for f in glob.glob("pass-[AB]-*.md"):
    for m in re.finditer(r"^- anchor: (.+)$", open(f).read(), re.M):
        a = m.group(1).strip().strip('"')
        if a and a not in allseg:
            print(f"UNGROUNDED {f}: {a}"); bad += 1
print(f"ungrounded={bad}")
EOF
```

Entries whose anchor fails to grep are flagged back for single re-extraction (same prompt, same
segment, listing the flagged ids); a second failure drops the entry with a note in the manifest.

### W2.T1 — Synthesis: ledger merge — OPUS

```
Synthesis task. Read-only except your output path. Base directory:
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/

Inputs: every pass-A-*.md and pass-B-*.md (grounded per the manifest).

Merge into one ledger:
1. Match entries describing the same decision across passes and segments (owner_words overlap
   is the strongest signal; anchors help).
2. Merged entry keeps: the clearer ruling phrasing, the LONGER verbatim owner quote, the union
   of rationale and rejected, all anchors, all turn references.
3. Status: take the status backed by the stronger verbatim evidence; if the evidence genuinely
   conflicts, mark status: DISPUTED with both readings and both quotes. Never resolve by
   judgment.
4. Entries found by one pass only: keep, mark single-source.
5. Renumber L-001... with a mapping table (L-id -> source ids). Order by dependency where
   stated, else by turn.

Do NOT drop any entry. There is no line cap; completeness wins.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/merged.md
```

### W2.T2 — Synthesis: capability-to-design map — FABLE (me, ML-3)
Starts when W1.T3/T4 land (does not wait for the full W1 join).

---

## Wave 3 — verification, skeleton, consolidation

### W3.T1 — Review: adversarial recall pass — OPUS (held-out), one agent per 3 segments

```
Held-out adversarial review. Read-only except your output path. You check a decision ledger
for RECALL against its source; do not trust the ledger, hunt what it missed.

Inputs:
- The merged ledger: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/merged.md
- Your source segments (read fully, in order): {SEGMENT_PATHS}

Method: walk your segments turn by turn. At every USER turn that steers, corrects, rejects,
approves, or poses a design question later answered, check the ledger contains it. Special
attention: rulings embedded in corrections, reversals, quick confirmations, constraints stated
in passing. For each miss: a MISSING entry in full ledger format (owner_words verbatim, anchor,
turn). For each ledger entry claiming a turn inside your segments that you cannot ground:
UNGROUNDED with the entry id.

Always end with a coverage map: which turns you walked, and how (full read is expected; state
any exception and why).

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/recall-{BATCH_ID}.md
Line guidance 300, soft, yields to completeness.
```

### W3.T2 — Design: spine skeleton — FABLE (me, ML-4)
### W3.T3 — Judgment: ledger consolidation — FABLE (me, ML-2)
Output: `BASE/harness-design/ledger/LEDGER.md`, append-only from here, committed.
### W3.T4 — Design: area-B prompt amendment — FABLE (me, ML-8)
Join: LEDGER.md committed; area-B prompts amended into this document.

---

## Wave 4 — spine drafting

### W4.T0 — Design: outlines — FABLE (me, ML-5)
### W4.T1..T4 — Drafting: spine sections — OPUS, one per cluster
Clusters and budgets (soft, yield to outline completeness): context+background 150;
architecture 300; alternatives-considered 250; cross-cutting concerns 200.

```
Drafting task. Write ONE section of a design document from a provided outline and binding
sources. Do NOT invent design content: every claim traces to the ledger, an inventory, or the
outline. Where the outline marks something open, write it as an explicit OPEN question; never
resolve it yourself.

Inputs:
- Your section outline: {OUTLINE_PATH}
- The decision ledger (binding): /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/LEDGER.md
- Inventories as cited by the outline: {INVENTORY_PATHS}

Style: prose for engineers, terse, no marketing adjectives, no em-dashes, numbered lists only
for discussable units. Cite ledger ids inline as [L-xxx] after each decision-derived statement;
statements that are proposals rather than rulings are marked PROPOSED; open items are marked
OPEN. These markers are load-bearing for a conformance audit that treats unmarked normative
statements as defects.

Output: {SECTION_PATH}
Line budget per this document's cluster table, soft.
```

---

## Wave 5 — spine reviews (3 parallel), then apply

### W5.T1 — Audit: conformance vs ledger — SONNET

```
Mechanical audit. Read-only except your output path.

Inputs: the document under audit at {DOC_PATH}; the ledger at
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/LEDGER.md;
the id scope {ID_SCOPE} ("ALL" or an explicit id list).

Three directions, all mandatory:
1. Ledger-to-doc: for EVERY in-scope entry with status ruled, the doc either honors it (cite
   section and line), contradicts it (CONFLICT, quote both sides verbatim), or does not cover
   it anywhere (MISSING). There is no topic-covered exemption: an entry with no home is MISSING.
2. Citation-to-entry: every [L-xxx] citation actually supports its sentence (MISCITE if not,
   quote both).
3. Doc-to-ledger: every normative or decision-shaped statement carries [L-xxx], PROPOSED, or
   OPEN. Unmarked normative statements are UNCITED findings, quoted.
Also: entries with status default or open must appear as such, never silently upgraded.

Output: {FINDINGS_PATH}
Findings only, one per line item. Zero findings must state the counts checked in each
direction.
```

### W5.T2 — Review: naive-reader pass — OPUS (held-out)

```
Naive-reader review. Read ONLY the document at {DOC_PATH}. You have no other context about
this project and must not seek any: no other files, no transcript. You are a strong engineer
encountering this design cold.

Report, with line references, in reading order: terms used before definition; concepts defined
twice or inconsistently; claims contradicting your platform knowledge; sections whose purpose
you cannot state after reading; leaps assuming context you were never given; anything reading
as leftover from another document. No rubric; report where you actually stumbled.

Output: {FINDINGS_PATH}
Line guidance 200, soft.
```

### W5.T3 — Review: strategic spine review — FABLE (held-out)

```
ANALYSIS ONLY. Strategic held-out review; edit nothing; the report is the deliverable.

Subject: the design-document spine at {DOC_PATH}, for an evolution machine over LLM minds (a
personal harness bred against a professional's recorded verdicts). Binding context: the ledger
at /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/LEDGER.md
(owner rulings, verbatim); inventories under
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/.

Judge severely: (1) does the architecture honestly reflect the ledger or drift toward drafter
preference; (2) do goals/non-goals actually bound the project; (3) are alternatives real
alternatives with real strengths; (4) technical soundness of the load-bearing mechanisms
(snapshot-swap backtests, temporal sealing, exam wall, specialist lineages, ladder economics)
as described; (5) what a skeptical senior reviewer attacks first; (6) what is missing that
area cycles cannot later repair because the spine forecloses it.

Verdict: READY / REWORK (located directives) / REJECT (structural reason).
Output: {FINDINGS_PATH}
Line guidance 300, soft.
```

### W5.T4 — Judgment: apply findings — FABLE (me; rework loop per Program controls)

---

## Wave 6 — gate and partition

### W6.T1 — Gate: spine approval, area order — OWNER (gate package per Program controls; ML-1)
### W6.T2 — Audit: ledger partition — SONNET, then me adjudicating

```
Mechanical audit. Read-only except your output path.

Inputs: the ledger at
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/LEDGER.md;
the approved spine at {SPINE_PATH}; the area list A..J with one-line scopes at {AREA_LIST_PATH}.

For EVERY entry with status ruled: assign it to the spine and/or one or more areas by topic,
or mark UNASSIGNED. Output a partition table (entry id -> spine/area ids) and the UNASSIGNED
list. Every area's id list must be explicit; an empty area list is a finding.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/partition.md
```

I adjudicate UNASSIGNED (assign, or mark DEFERRED with a reason in the partition file). The
partition's per-area id lists fill `{AREA_LEDGER_IDS}` in C3 prompts.

---

## Wave 7 — area explorations + area J

### W7.T1..T8 — Exploration: areas A..H (I re-homed to W10) — model per parameter block

```
Exploration for one design area of the harness program. Read-only except your output path.
You gather grounded inputs and precedents; you do NOT design. Every finding cites file:line
or a ledger id.

Area: {AREA_NAME}
Binding constraints: the ledger entries listed in {AREA_LEDGER_IDS} (read them first in
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/LEDGER.md;
findings must never contradict them; if reality contradicts an entry, report the tension
explicitly, do not resolve it).
Sources: {AREA_SOURCES}
Questions: {AREA_QUESTIONS}

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/areas/{AREA_DIR}/exploration.md
Line guidance 350, soft.
```

Parameter blocks (AREA_NAME / model / sources / questions; all paths under BASE unless noted):

- **A. Mind schema v2** — OPUS. Sources: REN Mind/Neuron/Gate/Memory sources;
  inventories/kyo-deepdive.md (Schema section). Questions: exact current Mind.State shape;
  what typed neuron I/O requires; how activations become schema-typed; serialization
  round-trip; what breaks in ren when schemas tighten.
- **B. Backtest subsystem** — executes per the W3.T4 amendment (its own wave plan).
- **C. Knowledge store** — SONNET. Sources: REN docs/anchoring/DESIGN.md;
  inventories/prior-art.md (Datomic/XTDB/TMS/Wikidata notes). Questions: anchor-library schema
  as precedent; minimal bitemporal record shapes; query patterns fixtures need (as-of slicing).
- **D. Evolution engine deltas** — SONNET. Sources: inventories/ren-components.md; REN
  docs/evolution-design-doc.md, advisor and plateau docs. Questions: exact operator interfaces
  today; where tool-authoring hooks in; what the ladder abstraction replaces; budget accounting
  today.
- **E. Scoring precedents** — OPUS. Sources: INTERACTIONS.md failure classes;
  inventories/bench-oracle.md; REN docs/raio-x-execucao.html (extract text first). Questions:
  detector PRECEDENTS per failure class (existing checks, existing formats; candidate sketches
  belong to the design task, not here); judge containment arithmetic precedent (shrink
  weights); calibration data available day one.
- **F. Toolsmith** — SONNET. Sources: inventories/kyo-deepdive.md (test section); LEDGER
  toolsmith entries. Questions: acceptance-test expression for a tool; registry/versioning
  precedent in the kyo build; what a request record must carry for capability review at
  request time.
- **G. Serving runtime** — SONNET. Sources: inventories/criu-probe.md;
  inventories/kyo-deepdive.md (pod section); inventories/prior-art.md (local-model notes).
  Questions: container topology facts per platform; where a VFS layer can sit; local-model
  serving topology on this machine; checkpoint quiescence constraints. (Topology judgment is
  G's C2, not here.)
- **H. Deployment and versions** — SONNET. Sources: LEDGER promotion/approval entries;
  INTERACTIONS.md design-thread promotion sections. Questions: what evidence exists per
  promotion today; version record contents; policy-language precedents (auto-adopt
  conditions). (Fixture-schema dependency deferred to C2 refresh from area B output.)

### W7.T9 — Design: area J (constitution) full cycle — FABLE (me, ML-6) + C3 audit (SONNET,
W5.T1 prompt with area scope) + gate at W8.

---

## Wave 8

### W8.T1 — Gate: constitution — OWNER (gate package; ML-1)

---

## Waves 9-11 — area design batches (pipelined)

Per area: C2 design — FABLE (me, ML-6). C3 audit — SONNET, the W5.T1 prompt with
`{DOC_PATH}` = the area design, `{ID_SCOPE}` = the partition's `{AREA_LEDGER_IDS}`. C4 gate —
OWNER (gate package; ML-1). Pipelined: you gate area N while I draft N+1.

- **W9 batch**: A, B (per its amended plan), G, C
- **W10 batch**: E (needs B), F, D (needs A and F), plus area I's exploration (OPUS; W7
  template with sources: inventories/bench-oracle.md, areas/B outputs, areas/E outputs, the
  kernel-campaign fixture candidates named in INTERACTIONS.md; questions: integration
  contract, gen-0 candidates, arena-sequencing evidence including the ren ARC v2 run
  locations)
- **W10 dry-run pair** (validates area B's worked example):

SONNET execution:
```
Run task. You may write only to your output path. Execute the fixture scoring procedure
specified at {AREA_B_SCORING_SPEC_PATH} against the fixture at {FIXTURE_PATH}, scoring the
recorded incumbent output embedded in the fixture. Follow the spec exactly; do not improvise
missing steps, report them as SPEC-GAP findings instead. Record every detector's raw result
and the inputs you gave it.
Output: {DRYRUN_OUTPUT_PATH}
```

OPUS verdict:
```
Verdict task. Read-only except your output path. Inputs: the fixture at {FIXTURE_PATH} (its
sealed labels), the dry-run results at {DRYRUN_OUTPUT_PATH}, the scoring spec at
{AREA_B_SCORING_SPEC_PATH}.
Adjudicate fixture validity: the recorded incumbent must fail exactly where the sealed labels
say the owner faulted it, and detectors must fire on the labeled spans. Verdict VALID /
INVALID per label, with the evidence line for each. SPEC-GAP findings from the run are
blocking: list them for the area-E design to close.
Output: {DRYRUN_VERDICT_PATH}
```

- **W11 batch**: H (needs B, E), I (needs all; the ARC-v3-vs-perf-first arena ruling lands at
  its C4 gate)

---

## Wave 12 — integration + 3 parallel reviews

### W12.T1 — Synthesis: integrate, resolve seams — FABLE (me, ML-7)
### W12.T2 — Audit: full-document conformance — SONNET (W5.T1 prompt; `{DOC_PATH}` = integrated
doc, `{ID_SCOPE}` = ALL, against LEDGER.md as appended through every gate)
### W12.T3 — Review: naive-reader — OPUS (held-out; W5.T2 prompt, integrated-doc path)
### W12.T4 — Review: strategic integrated review — FABLE (held-out)

```
ANALYSIS ONLY. Strategic held-out review; edit nothing; the report is the deliverable.

Subject: the integrated design document at {DOC_PATH}. Binding context: the ledger at
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/LEDGER.md
(as appended through all gates); inventories under
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/;
area artifacts under
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/areas/.

Judge severely on the W5.T3 axes (ledger fidelity vs drafter drift; goals/non-goals as real
bounds; honest alternatives; technical soundness of load-bearing mechanisms; the skeptical
senior reviewer's first attack; irreparable omissions) PLUS one axis specific to integration:
cross-area seam soundness, the places where two areas' assumptions meet (schema/backtest,
scoring/knowledge-store, toolsmith/constitution, runtime/deployment), judged for contradiction,
double-definition, and unowned gaps.

Verdict: READY / REWORK (located directives) / REJECT (structural reason).
Output: {FINDINGS_PATH}
Line guidance 300, soft.
```

---

## Wave 13 — apply (2 parallel)

### W13.T1 — Drafting: mechanical directive application — SONNET

```
Mechanical edit task. Inputs: the document at {DOC_PATH}; the directive list at
{DIRECTIVES_PATH}, each directive located (section/line) and marked MECHANICAL by the
coordinator. Apply ONLY the directives marked MECHANICAL, exactly as written: wording fixes,
citation fixes, marker additions, relocations. Do not rephrase beyond the directive text, do
not touch content near a directive that the directive does not name. Directives marked
JUDGMENT are not yours; skip them.
Output: edit {DOC_PATH} in place; write an application log (directive -> lines changed) to
{APPLY_LOG_PATH}.
```

### W13.T2 — Judgment: content-changing directives — FABLE (me; marks the MECHANICAL/JUDGMENT
split on the directive list before T1 dispatches)

---

## Wave 14 — freeze

### W14.T1 — Gate: final rulings on surviving open questions — OWNER (gate package; ML-1)
### W14.T2 — Run: v1 freeze — SONNET, verified by FABLE (me)

```
Mechanical finalization. Inputs: the approved document at {DOC_PATH}; the gate records under
{GATES_PATH}; the plan amendment log in
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/HARNESS-PLAN.md.
1. Add the status header (frozen v1, date, owner).
2. Build the dated decision log section from the gate records (gate, date, rulings by ledger
   id).
3. Verify every OPEN item in the doc appears in the final open-questions section; list any
   mismatch instead of fixing content.
No content changes anywhere else.
Output: edit {DOC_PATH} in place; mismatch list to {FREEZE_LOG_PATH}.
```

---

## Fixture note (disposition)

This planning exchange (plan-then-improvise correction, and this document's own REWORK cycle)
is fixture material by owner ruling. Disposition: area B's worked-example acceptance list
(ML-8) explicitly includes the plan-correction exchange as a fixture, with the launch
discipline as the corrective mechanism under test. Not a dangling note; a named acceptance
case.
