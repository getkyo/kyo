# bench-harness improvement plan

## The thesis

The harness is good at **refusing** and weak at **volunteering**.

Refusing is what it was built for and it works. Across this campaign it killed five optimization
candidates cheaply and correctly, caught an operator contaminating a live measurement, inverted a
conclusion that a −17.4% number would otherwise have cemented, and corrected a headline result from
five wins to three. Twenty-nine of its own defects were found by using it; twenty-five are fixed;
324 checks are green across eight suites.

Volunteering is where the remaining value is. Almost every insight that redirected this campaign was a
**synthesis the tool had all the data for and left to the reader**: that the largest deltas were the
unresolvable ones, that a row had never once been measurable across four attempts, that 70% of a row
belongs to code no kernel change can touch, that the top budget candidate was the benchmark's own
closure. The operator this tool exists for demonstrably skims. Every item in section A converts
something already in the store into something the report says out loud.

Ordering is by value, not by cost. Section A first.

---

## A. Make the tool volunteer what it already knows

### A1. Flag when the largest movements are the unresolvable ones

**Evidence.** In the replicated sweep the two biggest deltas on the board, −12.5% and −10.7%, were
both flat, while a −7.1% row was a win. That inversion is the single most counterintuitive fact the
campaign produced and the reader has to notice it unaided by scanning fifteen rows.

**Change.** After the table, when any row in the top three by |delta| is `Flat` or `BelowResolution`,
say so explicitly and name the leg count that would change it.

**Acceptance.** Fires on the replicated sweep naming both rows. Silent on a run where the largest
deltas resolve. Both directions tested, from the real stored legs.

### A2. Cross-reference a row against its own history in the store

**Evidence.** `emittingClausesPayRegionRebuild` produced −9.78%, then "unmeasurable", then −9.5%, then
−12.5%-flat across four separate attempts. Every one of those is in the store. Three times a verdict
about that row was recorded and later withdrawn, and nothing ever said "this row has disagreed with
itself before".

**Change.** A comparison consults the store for prior runs on the same rows and flags any row whose
current verdict contradicts a previous one, or whose deltas across runs exceed its own resolution.

**Acceptance.** On the current store it names `emittingClausesPayRegionRebuild` and does **not** name
`continuationBodiesFuse`, which has been consistent across every measurement.

**Risk to watch.** This is the most speculative item here: "contradicts" needs a definition that does
not fire on every ordinary re-measurement. Prefer under-firing.

### A3. Per-row noise share, not one global number

**Evidence.** `noiseShare` exists, computes a whole-run figure, and warns above 25%. But 29.1% of
`nestedPayloadsUnwrapInMaps` is `boxToInteger` and another 41.4% is the benchmark's own generated
methods: **70% of that row is code no kernel change can move**. That fact refuted C1's premise and
reframed IN-2 and DIS-2, and in each case I derived it by hand from a profile.

**Change.** Attribute the CPU profile per row where the profile allows it, and mark rows whose
kernel-attributable share is below a stated threshold. Where the profile covers only one row, say that
rather than generalising.

**Acceptance.** Names `nestedPayloadsUnwrapInMaps` from the stored capture with a share near 70%,
and states plainly that the other fourteen rows have no profile.

### A4. Say when a budget candidate is not kernel code

**Evidence.** The top entry in the budget-proximity ranking, above every kernel method, is
`ProtoKernelBench::run$56` at 379 B refused 10/10. It is the benchmark's own closure. The tool ranks
it helpfully and never mentions that optimizing it would be optimizing the benchmark.

**Change.** Partition the ranking into kernel and non-kernel by package prefix and label it.

**Acceptance.** `run$56` appears under a non-kernel heading; `dispatch$1` and `Stack::grow` under
kernel.

### A5. Run `BenchPlan` automatically before a bracket

**Evidence.** `BenchPlan` now reproduces real thresholds exactly, verified against all fifteen rows of
the replicated sweep. It still runs only on request. Sessions were spent reporting a 25% regression on
a row that resolves to ±22.6%, and the tool could have said so beforehand from data already stored.

**Change.** A bracket forecasts from any prior legs in its store before measuring, and warns, naming
rows that cannot resolve the target. Refusing outright is probably too strong; the operator may be
measuring exactly to improve the estimate.

**Acceptance.** A bracket on the current store prints the forecast first. A bracket on an empty store
says it has no basis to forecast from, rather than silently skipping.

---

## B. Ergonomics that cost real time

### B1. `bench session <store>`

Re-reading a five-leg bracket currently means assembling ten `--control`/`--variant` flags with a
shell loop, and getting the arm split right by hand. A store plus a session id already determines
this. **Acceptance:** one command reproduces the replicated verdict for a stored bracket.

### B2. Invert the fork default

Every report in this campaign carries "-f 1 is diagnostic and not a claim", and `-f 3` was never once
run. The label is doing the work that a default should. **Change:** `-f 3` for anything that renders a
verdict; `-f 1` behind `--diagnostic`. **Acceptance:** the existing label becomes unreachable without
the flag.

### B3. Detect a dangling configuration reference

`Run.jvmArgs` now records `-XX:CompileCommandFile=/path`. If that file is deleted, the run is exactly
as unrecoverable as the headline pair was, and nothing notices. **Change:** on load, check referenced
files still exist and mark the run's configuration unverifiable if not. **Acceptance:** deleting
`forced-inline.cmd` makes the sweep replication report its configuration as unverifiable.

---

## C. Coverage gaps in the tool's own tests

### C1. The CLI dispatch is untested

Two lines choose `compareReplicated` when given more than one leg per side. They are exercised by
running the command, not by a test. Given that defect 27 was exactly this path being *absent*, it
should be pinned.

### C2. `compare` and `compareReplicated` disagree by design and only one is labelled

On the same legs the pair says five wins and the replicate statistic says three. The CLI now prints
which it used. The stronger guard is that a single-pair comparison over legs that *could* have been
replicated should say so.

---

## D. Structural, already filed

The harness is 3,500 lines with hand-rolled test mains, no CI, and a `check` that aborts the suite on
first failure. Filed in `kernel2-backlog.md` with two shapes and a recommendation; unchanged by this
plan and still awaiting a ruling.

---

## What this plan deliberately does not propose

- **No new statistics.** The replicate statistic, the A/A null and the resolution floor are validated
  and should not be touched.
- **No changes to the refusal set.** Every blocker in the tool exists because its absence produced a
  wrong conclusion that was believed at the time.
- **Nothing that makes a verdict easier to obtain.** Every item either adds context to a verdict or
  makes an existing refusal easier to act on.
