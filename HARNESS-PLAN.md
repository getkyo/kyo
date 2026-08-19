# Harness Design Program: Execution Plan

Status: draft for held-out review, then owner gate. Date: 2026-08-19.
Owner input pending (W0.T1): doc location and audience. Until ruled, all artifacts land under
`harness-design/` in this worktree and move wholesale when the project directory exists.

## Launch discipline (binding)

This document is the launch artifact. Every agent dispatch copies its prompt from this document
verbatim. Slots (marked `{LIKE_THIS}`) are filled with artifact paths only, mechanically; filling
a slot never adds, removes, or rephrases instructions. If a prompt proves wrong at launch time,
the fix is an amendment to this document first, then dispatch. Improvised launch-time briefs are
the failure class this rule exists to prevent: plans decay at dispatch when prompts are invented
under momentum. Deviations without amendment are defects.

Standing constraints carried by every prompt: read-only unless the task says otherwise; outputs
to the named path; line limits respected; no editing of kyo, ren, or session artifacts; verbatim
quotes for anything that will bind later work.

Artifact directory layout:

```
harness-design/
  ledger/            decision ledger (the binding artifact) and its inputs
  inventories/       kyo, ren, bench-oracle, prior-art reports
  spine/             the design doc spine and its reviews
  areas/             one subdirectory per area A..J
  reviews/           held-out review outputs
```

## Wave overview

- W0: owner gate (location/audience), concurrent with W1
- W1: capture mining, 8 agents parallel
- W2: ledger merge + capability map, 2 parallel
- W3: recall verification + spine skeleton, then ledger consolidation (join)
- W4: spine section drafting, parallel per section
- W5: spine reviews, 3 parallel; apply
- W6: owner gate (spine, area order)
- W7: all area explorations parallel + area J full cycle
- W8: owner gate (constitution)
- W9-11: area design batches, pipelined (me drafting, sonnet auditing, owner gating)
- W12: integration + 3 parallel reviews
- W13: apply directives
- W14: final gate, freeze

Serial resources: Fable (me) for design synthesis, the owner for gates. Everything else is
prefetched or pipelined around them.

---

## Wave 0

### W0.T1 — Gate: doc location and audience — OWNER
No prompt. Question: where does the harness project live (new directory presumed), and who is
the doc for (owner only / future collaborators / eventually public)? Register and restated
context depend on it. Nothing in W1 blocks on the answer.

---

## Wave 1 — capture mining (8 parallel)

### W1.T1 — Mining: decision ledger, pass A — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Sources, read in this order:
1. The session transcript: /Users/fwbrasil/.claude/projects/-Users-fwbrasil-workspace-kyo--claude-worktrees-majestic-zooming-quiche/32789380-338f-4ca0-80c5-d4b8d798354f.jsonl
   This is JSONL, one JSON object per line, large. Never read it raw end to end. Extract user
   and assistant text turns with jq/python (role, text), skipping tool results, system
   reminders, and cron boilerplate (repeated blocks starting "Continue the bench-harness work").
2. /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/INTERACTIONS.md
3. /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/WORK.md (the
   OPEN section and design-thread entries only; skip bench mechanics).

Task: extract EVERY design decision of the harness design conversation (the thread about an
evolution machine over LLM "minds", backtests, skills, knowledge stores; ignore kyo-kernel
implementation work except where the owner generalized it into the harness design).

One ledger entry per decision:
- id: sequential (A-001, A-002, ...)
- ruling: one sentence, present tense
- owner_words: the owner's verbatim sentence(s) that made or confirmed the ruling, quoted
  exactly, with enough words to be findable in the transcript
- rationale: the reasoning stated (owner's, or assistant reasoning the owner endorsed)
- rejected: the alternative(s) explicitly rejected, if any, each with the verbatim rejection
- status: ruled | default (assistant proposal not contradicted) | open (explicitly unresolved)
- depends_on: ids of entries this one assumes, if evident

Over-extract: near-duplicates are acceptable, silence is not. Include reversals (a later ruling
superseding an earlier one) as separate entries with a supersedes: field.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/ledger/pass-A.md
Maximum 600 lines. Corpus extraction only; no analysis, no editorializing.
```

### W1.T2 — Mining: decision ledger, pass B (independent) — SONNET
Same prompt as W1.T1 verbatim, with two substitutions: output path
`harness-design/ledger/pass-B.md`, and id prefix `B-`. Dispatched as a separate agent with no
shared context; independence is the recall mechanism.

### W1.T3 — Mining: kyo module map — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Repository: /Users/fwbrasil/workspace/kyo (the main worktree, not .claude/worktrees copies).

Task: produce the complete module inventory of the kyo platform.
1. Enumerate modules from build.sbt and the directory layout (every kyo-* module).
2. For each module: one paragraph on what it provides (from its README if present, otherwise
   from scanning its public API surface), the platforms it supports (JVM/JS/Native), and its
   3-8 most load-bearing public types with one-line descriptions.
3. Flag specifically, with file citations: effect definitions and their handlers (what effects
   exist platform-wide); resource safety (Scope and friends); serialization/schema support;
   container/process isolation (kyo-pod or equivalent); network/http/sql/browser reach;
   streaming; STM/actors; test infrastructure (kyo-test, doctest); anything AI-related.
4. Note modules that exist but look immature or experimental (marked as such, with evidence).

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/kyo-modules.md
Maximum 500 lines. Ground every claim in a file path. No speculation about future modules.
```

### W1.T4 — Mining: kyo deep-dive (ai, pod, Schema, test) — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Repository: /Users/fwbrasil/workspace/kyo. Deep-dive on four subsystems the harness design
leans on hardest. For each: public API surface with signatures quoted verbatim (file:line),
what exists versus what the design would need built.

1. kyo-ai (or equivalent AI module): the LLM effect, AI.gen, providers (which vendors, how a
   provider is configured, whether a base-URL override / OpenAI-compatible endpoint exists),
   conversation/history handling, streaming, structured output support. Design needs to check
   against: LLM as a tracked effect, per-call token budgets via handlers, model-tier selection
   as a handler binding, cost recording per call.
2. kyo-pod (or the container/isolation module): what isolation exists, how processes/containers
   are launched and controlled, podman integration if any. Design needs: mind+container as the
   skill unit, VFS mediation, checkpoint hooks.
3. Schema (kyo-data or kyo-schema): derivation, what types are expressible, JSON round-trip,
   evolution/versioning of schemas. Design needs: typed neuron I/O, activation schemas,
   genome serialization, knowledge-store records.
4. kyo-test + doctest: how suites run, how a tool's acceptance tests would be expressed and
   executed programmatically. Design needs: toolsmith acceptance pipeline.

Close with a single BUILD-VS-HAVE table: each design-required capability, HAVE (with citation)
or BUILD (one line on the gap).

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/kyo-deepdive.md
Maximum 450 lines.
```

### W1.T5 — Mining: ren reusable-component inventory — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Repository: /Users/fwbrasil/workspace/ren. The harness design reuses ren's evolution machinery
where possible. Read the main sources under ren/ (Mind, Neuron, Network, Gate, History, Memory,
Evolution, Population, Fitness, Selection, Crossover, Mutation, Context, Lineage, Pick and
neighbors) plus README.md sections that document them.

For each component, classify:
- CARRIES: usable as-is for the harness (one line why, file citation)
- REWORK: usable with named changes (list the changes the harness design implies: typed neuron
  I/O schemas, tool grants per neuron, model-tier as gene, tool-authoring mutations, budget in
  fitness)
- REPLACE: superseded by a harness design decision (name which)

Also inventory: the checkpoint/resume mechanism (genN-phase files), the operator minds (bundled
Mind.State JSONs and prompt-base.md), the judge/Eval structure, and the maxTestsByIteration
sampling lever. Note anything load-bearing that is undocumented.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/ren-components.md
Maximum 400 lines. Every claim cited file:line.
```

### W1.T6 — Mining: bench-harness oracle surface — SONNET

```
Read-only data mining. Do NOT edit, create, or delete any file outside your output path.

Directory: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/kyo-kernel2/.claude/skills/kernel/bench-harness

This tool is the deterministic oracle for the harness's first skill (performance optimization).
Extract its integration contract:
1. Every runnable command (the BenchRun/BenchCompare/BenchBracket/BenchPlan/BenchIngest/
   BenchJit/BenchList/BenchShow mains and any others): arguments, what it consumes, what it
   produces (files, formats).
2. The verdict vocabulary: every classification a comparison can produce, with the exact
   criteria (thresholds, resolution, A/A nulls, replication) quoted from code.
3. The validity gates: every way a run can be declared invalid (warmup, load, mid-run source
   changes, malformed flags), quoted from code.
4. The data model: Run, legs, iterations, allocation data, JVM args, benchmark class fields.
5. Machine discipline the tool assumes (quiet machine, no concurrent builds) as encoded or
   documented.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/bench-oracle.md
Maximum 350 lines. File:line citations throughout.
```

### W1.T7 — Mining: prior-art dossier — SONNET

```
Read-only data mining plus web verification. Do NOT edit, create, or delete any file outside
your output path.

Consolidate the prior-art research already performed in this project into citable notes. Source
material: the design-thread sections of
/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/INTERACTIONS.md
and the session transcript
/Users/fwbrasil/.claude/projects/-Users-fwbrasil-workspace-kyo--claude-worktrees-majestic-zooming-quiche/32789380-338f-4ca0-80c5-d4b8d798354f.jsonl
(extract text turns only, skip tool noise). Where a claim cites a web source, re-verify the
citation exists (fetch the URL or search) and record the link.

One note per prior-art item, covering at minimum: Prime Agent / Prime Intellect (ARC-AGI-3
result, /refine, RLM, no-sandbox default); the Cordis / DeepSeek Harness spatiotemporal
composability paper (revertible effects, reactive coeffects, its ZIO and Effekt related-work
positions; the PDF is at /private/tmp/claude-501/-Users-fwbrasil-workspace-kyo--claude-worktrees-majestic-zooming-quiche/32789380-338f-4ca0-80c5-d4b8d798354f/scratchpad/cordis-paper.txt
as extracted text, copy it into the output directory so it survives temp cleanup); Hillis 1990
co-evolving parasites; PAIRED; POET; AlphaZero self-play framing; FunSearch and AlphaEvolve;
Dynabench; SWE-bench family and Terminal-Bench; LLMOps trace-to-dataset loops (LangSmith and
kin); off-policy evaluation / counterfactual learning; quant-finance backtest-overfitting
methodology; bitemporal stores (Datomic, XTDB), truth maintenance systems, Wikidata's
statement/reference model.

Per note: what it is, the specific claims the harness design makes about it, what differentiates
the harness design, links.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/prior-art.md
Maximum 400 lines.
```

### W1.T8 — Run: CRIU-on-podman feasibility probe — OPUS (hands-on)

```
Feasibility probe, hands-on, scoped. You may run podman commands and create files ONLY under
your scratchpad and the named output path. Do NOT touch any git worktree, do NOT run sbt, do
NOT stop or modify existing containers.

Machine context: macOS with podman 5.x and a running podman machine (Linux VM). Goal: establish
what container checkpoint/restore can actually do here, for a design that wants to snapshot
mind+environment state.

1. Verify: podman version, machine state, whether the machine's VM has criu available
   (podman machine ssh into it to check).
2. Attempt: run a trivial long-lived container (e.g. alpine with a counting shell loop),
   `podman container checkpoint` it, restore it, verify the process resumed (counter
   continued). Document exact commands and outputs.
3. Attempt the same with --export/--import (portable checkpoint file).
4. Attempt with a small JVM process in a container (any minimal JVM image; a sleep-loop java
   program you write to your scratchpad is fine). JVM checkpointability is the load-bearing
   question.
5. Document every failure honestly with the error text: rootless vs rootful constraints, VM
   criu availability, TCP/file-lock flags needed, timing.
6. Conclude: WORKS / WORKS-WITH-CONSTRAINTS (list them) / BLOCKED (what would unblock), for
   (a) generic processes, (b) JVM processes, on this machine today.

Output: /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/harness-design/inventories/criu-probe.md
Maximum 250 lines. Raw command transcripts included for the load-bearing steps.
```

Join condition: all eight outputs exist.

---

## Wave 2 — first consolidation (2 parallel)

### W2.T1 — Synthesis: ledger merge — OPUS

```
Synthesis task. Inputs:
- harness-design/ledger/pass-A.md
- harness-design/ledger/pass-B.md
(paths relative to /Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/)

Merge the two independently-extracted decision ledgers into one:
1. Match entries describing the same decision (owner_words overlap is the strongest signal).
2. Merged entry keeps: the clearer ruling phrasing, the LONGER verbatim owner quote, the union
   of rationale and rejected-alternatives, the more conservative status (ruled beats default
   beats open only when the evidence supports it; when passes disagree on status, mark
   status: DISPUTED with both readings).
3. Entries found by only one pass are kept and marked single-source.
4. Renumber as L-001... preserving a mapping table (L-id -> A-id, B-id).
5. Order by dependency where stated, else chronologically.

Do NOT drop any entry. Do NOT resolve disputes by judgment; mark them. Output is input to an
adversarial recall pass and a human adjudication.

Output: harness-design/ledger/merged.md
Maximum 700 lines.
```

### W2.T2 — Synthesis: capability-to-design map — FABLE (me, in-session)
Inputs: kyo-modules.md, kyo-deepdive.md. Output: `harness-design/inventories/capability-map.md`.
Module-by-module: which design claims each capability carries; BUILD list consolidated. No
agent prompt; my task.

---

## Wave 3 — verification and skeleton (2 parallel, then join)

### W3.T1 — Review: adversarial recall pass — OPUS (held-out)

```
Held-out adversarial review. Read-only except your output path. You are checking a decision
ledger for RECALL against its source. Do not trust the ledger; hunt what it missed.

Inputs:
- The merged ledger: harness-design/ledger/merged.md
- The source transcript: /Users/fwbrasil/.claude/projects/-Users-fwbrasil-workspace-kyo--claude-worktrees-majestic-zooming-quiche/32789380-338f-4ca0-80c5-d4b8d798354f.jsonl
  (extract user/assistant text turns with jq/python; skip tool results, system reminders, and
  repeated cron boilerplate)
- Secondary: INTERACTIONS.md in the worktree root.

Method: walk the transcript's design thread chronologically. At every owner message that
steers, corrects, rejects, approves, or poses a design question later answered, check the
ledger contains it. Pay special attention to: rulings embedded in corrections ("no, we'd let
evolution do that"), reversals, quick confirmations ("yes, and..."), and constraints stated in
passing. For each miss: MISSING entry in ledger format with verbatim owner_words and position.
For each ledger entry you cannot ground in the transcript: UNGROUNDED with the entry id.

Output: harness-design/ledger/recall-findings.md
Maximum 300 lines. An empty findings list must state how much of the transcript was actually
walked, and how.
```

### W3.T2 — Design: spine skeleton — FABLE (me, in-session)
Goals, non-goals, audience statement, section structure per Google practice. Output:
`harness-design/spine/skeleton.md`.

### W3.T3 — Judgment: ledger consolidation (join) — FABLE (me, in-session)
Adjudicate DISPUTED and recall findings, commit `harness-design/ledger/LEDGER.md` as the
binding, append-only artifact. Every later conformance audit cites it.

---

## Wave 4 — spine drafting (parallel per section)

### W4.T0 — Design: section outlines, then integration — FABLE (me, in-session)
Outlines per section with the ledger ids each section must honor. Integration as drafts land.

### W4.T1..T4 — Drafting: spine sections — OPUS (one agent per section cluster)
Template, one dispatch per cluster (context+background; architecture; alternatives-considered;
cross-cutting concerns):

```
Drafting task. Write ONE section of a design document from a provided outline and binding
sources. Do NOT invent design content: every claim traces to the ledger, an inventory, or the
outline. Where the outline marks something open, write it as an explicit open question, never
resolve it yourself.

Inputs:
- Your section outline: {OUTLINE_PATH}
- The decision ledger (binding): harness-design/ledger/LEDGER.md
- Inventories as cited by the outline: {INVENTORY_PATHS}

Style: prose for engineers, terse, no marketing adjectives, no em-dashes, numbered lists only
where items are discussable units. Cite ledger ids inline as [L-xxx] after each decision-derived
statement; these citations are load-bearing for a later conformance audit. Alternatives are
written honestly: the rejected option's real strengths stated before the reason it lost.

Output: {SECTION_PATH}
Maximum {LINE_BUDGET} lines.
```

---

## Wave 5 — spine reviews (3 parallel), then apply

### W5.T1 — Audit: conformance vs ledger — SONNET

```
Mechanical audit. Read-only except your output path.

Inputs: the spine draft {SPINE_PATH}; the ledger harness-design/ledger/LEDGER.md.

For EVERY ledger entry with status ruled: verify the spine either honors it (cite the section
and line), contradicts it (CONFLICT, quote both sides verbatim), or omits it where its topic is
covered (OMISSION). For every [L-xxx] citation in the spine: verify the cited entry actually
supports the sentence (MISCITE if not, quote both). Statuses default and open: verify the spine
marks them as such and does not silently upgrade them to decided.

Output: harness-design/reviews/spine-conformance.md
Findings only, one per line item, no commentary. Zero findings must state the counts checked.
```

### W5.T2 — Review: naive-reader pass — OPUS (held-out)

```
Naive-reader review. Read ONLY the document at {SPINE_PATH}. You have no other context about
this project, and you must not seek any: no other files, no transcript. You are a strong
engineer encountering this design cold.

Report, with line references: terms used before definition; concepts defined twice or
inconsistently; claims that contradict your platform knowledge; sections whose purpose you
cannot state after reading them; leaps where the design assumes context you were never given;
anything that reads as leftover from a different document. Do not review against any rubric;
report where you actually stumbled, in reading order.

Output: harness-design/reviews/spine-naive.md
Maximum 200 lines.
```

### W5.T3 — Review: strategic spine review — FABLE (held-out)

```
ANALYSIS ONLY. Strategic held-out review; do not edit anything; your deliverable is the report.

Subject: the spine of a design document at {SPINE_PATH}, for an evolution machine over LLM
minds (a personal harness bred against a professional's recorded verdicts). Binding context:
the decision ledger at harness-design/ledger/LEDGER.md (the owner's rulings, verbatim);
inventories under harness-design/inventories/.

Judge severely on: (1) whether the spine's architecture honestly reflects the ledger or has
drifted toward the drafter's own preferences; (2) whether goals/non-goals actually bound the
project or are decorative; (3) whether alternatives-considered are real alternatives with real
strengths or strawmen; (4) technical soundness of the load-bearing mechanisms (snapshot-swap
backtests, temporal sealing, exam wall, specialist lineages, ladder economics) as described;
(5) what a skeptical senior reviewer would attack first; (6) what is missing that the area
cycles cannot later repair because the spine's structure forecloses it.

Verdict: READY / REWORK (with located directives) / REJECT (with the structural reason).
Output: harness-design/reviews/spine-fable.md
Maximum 300 lines.
```

### W5.T4 — Judgment: apply findings — FABLE (me, in-session)

---

## Wave 6

### W6.T1 — Gate: spine approval, area order — OWNER

---

## Wave 7 — area explorations (9 parallel) + area J cycle

### W7.T1..T9 — Exploration: areas A..I — SONNET (mechanical) / OPUS (judgment-reading)
Template; per-area parameters follow.

```
Exploration for one design area of the harness program. Read-only except your output path.
You gather grounded inputs; you do NOT design. Every finding cites file:line or ledger id.

Area: {AREA_NAME}
Binding constraints: the ledger entries listed in {LEDGER_IDS} (read them in
harness-design/ledger/LEDGER.md first; your findings must never contradict them; if reality
contradicts a ledger entry, report the tension explicitly, do not resolve it).
Sources: {AREA_SOURCES}
Questions to answer: {AREA_QUESTIONS}

Output: harness-design/areas/{AREA_DIR}/exploration.md
Maximum 350 lines.
```

Per-area parameters:
- **A. Mind schema v2** — OPUS. Sources: ren Mind/Neuron/Gate/Memory sources, kyo Schema
  deep-dive. Questions: exact current Mind.State shape; what typed neuron I/O requires; how
  activations become schema-typed; serialization round-trip; what breaks in ren when schemas
  tighten.
- **B. Backtest subsystem** — runs the already-planned five-wave mechanism program (see
  HARNESS-PLAN addendum reference below); its wave 1 doubles as this exploration.
- **C. Knowledge store** — SONNET. Sources: ren docs/anchoring/DESIGN.md, prior-art dossier
  (Datomic/XTDB/TMS/Wikidata notes). Questions: anchor-library schema as precedent; minimal
  bitemporal record shapes; query patterns fixtures need (as-of slicing).
- **D. Evolution engine deltas** — SONNET. Sources: ren-components inventory, ren
  evolution-design-doc.md, advisor/plateau docs. Questions: exact operator interfaces today;
  where tool-authoring hooks in; what the ladder abstraction replaces; budget accounting today.
- **E. Scoring** — OPUS. Sources: INTERACTIONS.md failure classes, bench-oracle inventory,
  x-ray doc (ren docs/raio-x-execucao.html, extract text). Questions: detector candidates per
  failure class with mechanical check sketches; judge containment arithmetic precedent (shrink
  weights); calibration data available day one.
- **F. Toolsmith** — SONNET. Sources: kyo-test/doctest deep-dive, toolsmith ledger entries.
  Questions: acceptance-test expression for a tool; registry/versioning precedent in the kyo
  build; what a request record must carry so capability review works at request time.
- **G. Serving runtime** — OPUS. Sources: criu-probe.md, kyo-pod deep-dive, Ollama/local-model
  notes in the prior-art dossier. Questions: container topology per skill on macOS and Linux;
  where the VFS layer sits; local-model serving topology (host Metal endpoint vs in-container);
  checkpoint quiescence points.
- **H. Deployment and versions** — SONNET. Sources: ledger entries on promotion/approval,
  fixture schema (from B as available). Questions: what evidence exists per promotion; version
  record contents; policy-language precedents (auto-adopt conditions).
- **I. Perf skill end-to-end** — OPUS. Sources: bench-oracle inventory, areas B/E outputs as
  available, kernel-campaign fixture candidates named in INTERACTIONS.md. Questions: the
  integration contract; gen-0 candidates; arena sequencing evidence (ARC v2 results location
  in ren runs/).

### W7.T10 — Design: area J (constitution) full cycle — FABLE (me) + SONNET audit + OWNER gate (W8)
No exploration needed; drafts directly from the ledger.

---

## Wave 8

### W8.T1 — Gate: constitution — OWNER

---

## Waves 9-11 — area design batches (pipelined)

Per area: C2 design proposal — FABLE (me, in-session), from the area's exploration + ledger.
C3 audit — SONNET, prompt identical to W5.T1 with `{SPINE_PATH}` replaced by the area section
path and scope narrowed to the area's ledger ids. C4 gate — OWNER, pipelined (you review area N
while I draft N+1).

- W9 batch: A, B, G, C
- W10 batch: E (needs B; includes the incumbent dry-run: SONNET executes the fixture scoring
  per area-B spec, OPUS writes the verdict), F, D (needs A and F)
- W11 batch: H (needs B, E), I (needs all; your ARC-v3-vs-perf-first arena ruling lands here)

---

## Wave 12 — integration + 3 parallel reviews

### W12.T1 — Synthesis: integrate, resolve seams — FABLE (me, in-session)
### W12.T2 — Audit: full-document conformance sweep — SONNET (W5.T1 prompt, full-doc scope)
### W12.T3 — Review: naive-reader on integrated doc — OPUS (held-out; W5.T2 prompt, new path)
### W12.T4 — Review: strategic integrated review — FABLE (held-out; W5.T3 prompt, integrated-doc
path, plus one added axis: cross-area seam soundness, the places two areas' assumptions meet)

---

## Wave 13 — apply (2 parallel)

### W13.T1 — Drafting: mechanical directive application — OPUS
### W13.T2 — Judgment: content-changing directive calls — FABLE (me, in-session)

---

## Wave 14 — freeze

### W14.T1 — Gate: final rulings — OWNER
### W14.T2 — Run: v1 freeze, status header, dated decision log — OPUS, verified by FABLE (me)

---

## Addendum: area B's mechanism program

Area B executes the previously-approved five-wave backtest-selection plan (selection inputs;
fixture schema and sealing; worked example over this session with the plan-correction exchange
as the acceptance case; scoring integration; held-out review and gate). Its agent prompts are
written into this document before its wave 1 dispatches, under the same launch discipline, as
an amendment once the ledger exists (its extraction briefs cite ledger ids that do not exist
yet today).

## Fixture note

This planning exchange itself is fixture material by owner ruling: the plan-then-improvise
failure class (launch-time prompt invention) is the labeled behavior; this document's launch
discipline is the corrective mechanism; evolved planning minds should produce launch-ready
prompts as part of any plan, out of the box.
