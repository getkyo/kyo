# Single-link native CI: A/B failed

> **CORRECTION (supersedes the heap-conflict conclusion below).** Fable's follow-up
> (`FABLE_SINGLELINK_AB_VERDICT.md`) root-caused the failure and it is NOT a heap
> conflict. Single-link is VIABLE. The aggregate ran ZERO of its 51 modules because
> sbt's `Command.process` QUEUES a `;`-batch instead of running it, so testKyo's
> per-scala passes drain in scrambled LIFO order at the wrong scala version: the 2.13
> batch (kyo-config) ran first, at 3.3.8 (not 2.13.18), its env-dependent
> FlagPlatformTest failed, and sbt batch-mode discarded the entire remaining queue
> (all 51 modules). The "6G cannot link large closures" premise below is REFUTED by
> production evidence (the old-design 6G test driver relinks 30k-44k-class closures
> fine). Fixes: (1) testKyo executes the plan in order (also fixes the pre-existing
> "2.13 runs at 3.3.8" coverage gap); (2) fix FlagPlatformTest; (3) de-edge the heavy
> heap (~10G / NATIVE_LINK_CPUS=1); (4) re-run the A/B. The section below is retained
> for the raw run evidence only; its conclusion is wrong.

---


Faithful local `podman-ci --arch arm test Native` A/B of the single-link change
(commit `d9d919c36d`, 16G container, `NATIVE_HEAVY=kyo-schema-tests`,
`NATIVE_LINK_CPUS=2`) **FAILED** (`build.sh` exit 1). The "exit 0" in the task
notification was the wrapper `echo`, not `build.sh`.

## What happened (from the run log)

- Compile-upfront phases worked: 45 native modules compiled main at 3.8.4, 0 links
  (correct: compile phases do not link).
- Heavy `--only kyo-schema-tests` session: **OOM-killed on attempt 1** (java linker
  ~12.5G RSS + 2 clang forks ~3.5G = ~16G, hit the cgroup limit), **retry passed**.
  So even 12G + `NATIVE_LINK_CPUS=2` is at the 16G edge; the crash-retry masked it.
- Aggregate `--exclude kyo-schema-tests` run at `-J-Xmx6G`: **linked only ONE module,
  kyo-config** (a small leaf, "Discovered 3243 classes", link 2.2s), which then hit a
  test failure (`kyo.FlagPlatformTest`, an env-var test, likely environmental). The
  ~51 large-closure modules (kyo-actor first, alphabetically) **never linked** - sbt's
  `;`-batch aborted on the first one and jumped to the 2.13 pass.
- Total native links in the whole run: **3** (2 heavy attempts + 1 kyo-config).

## Root cause (to be confirmed by the running heap probe)

Every scala-native **test binary links the whole-program closure**. A mid/high-stack
module (kyo-actor, kyo-core, kyo-net, kyo-schema-tests) discovers ~35-40k classes and
needs ~12G heap to link. A leaf (kyo-config, kyo-data) needs little and fits in 6G.

- **Old design (works):** link everything at **12G upfront** (kyoNative/Test/nativeLink
  + CPU cap, no forks during linking), then run pre-linked binaries at **6G** (the
  container/Chrome modules fork with ~10G headroom).
- **Single-link design fuses link+run into the aggregate at 6G.** 6G cannot link a
  large-closure test binary -> kyo-actor OOMs -> the batch aborts. Raising the
  aggregate to 12G is not an option: the container suites (kyo-sql-tests Postgres+MySQL,
  kyo-pod) and kyo-ui Chrome need the driver <=6G for fork headroom (the documented
  EAGAIN/OOM shape, build.yml RUN_HEAP_CAP note).

So **link needs 12G, running the container/browser tests needs <=6G, and single-link
fuses them into one session** - an irreconcilable heap conflict for those modules.
The double-link the change set out to remove existed *because* link (12G) and run (6G)
must be separate sessions; separating them is what triggers scala-native's
cross-session build-skip miss (#2514) and the relink.

## Implication

Single-link appears **unviable** in its current form. The dominated fallback,
**post-test-cleanup** (NATIVE_LINK_SLOWDOWN_PROPOSAL.md: keep the 12G upfront link and
the 6G run, but move the #1822 work-dir prune to AFTER each module's test so the
inevitable cross-session relink stays incremental/cheap instead of a full re-codegen),
sidesteps the conflict entirely because it keeps link and run in their correct,
separate heaps. It recovers most of the #1822 regression without fusing link and run.

Open question for the user: single-link was the explicitly requested direction. Given
this conflict, pivot to post-test-cleanup, or is there a single-link salvage worth
exploring (e.g. link non-container modules at 12G inline, keep the container/browser
modules on the old two-phase 12G-link / 6G-run path)? Consulting Fable on viability
before recommending.

## Secondary findings

- `kyo.FlagPlatformTest` "returns exactly what java.lang.System.getenv returns" failed
  on kyo-configNative in the container. Likely environmental (the podman container's
  process env), not the single-link change. Needs a separate clean-repro check.
- The heavy module is memory-fragile even at 12G+2CPU (OOM on attempt 1). Any design
  that links it in a 16G box is near the edge; the retry is load-bearing.
