# Phase 10 verify report

Run-id: phase-10-verify-2
HEAD: 832aa6533 (Phase 09; Phase 10 still uncommitted on working tree)
Plan: kyo-tasty/audit-fixes/05-plan.yaml, phase id "10"
Authorized files (files_modified):
- kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TypeUnpickler.scala
Authorized tests file:
- kyo-tasty/shared/src/test/scala/kyo/TypeUnpicklerTest.scala
Co-authorized refactor file (Frame plumbing per prior verify directive):
- kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/AstUnpickler.scala
Addresses: M7. Produces invariant: INV-004. Convention sweep declared:
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params,
Frame.internal, java.util.concurrent, llm-tells].

Status: PASS

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: GREEN.
  - JVM testOnly: `kyo-tasty/audit-fixes/runs/phase-10-flow-verify-testOnly-jvm-2.log`
    contains `Tests: succeeded 17, failed 0, ... [success]`. Both new
    M7 leaves run and pass ("unknown category-5 TASTy type tag fires a
    warn-level log", "known TASTy type tag does not emit a warn-level
    log").
  - JS compile: `kyo-tasty/audit-fixes/runs/phase-10-flow-verify-compile-js-4.log`
    GREEN (`done compiling` / `[success]`).
  - Native compile: `kyo-tasty/audit-fixes/runs/phase-10-flow-verify-compile-native-1.log`
    GREEN (`done compiling` / `[success]`).
- reward-hacking grep: 0 NEW hits on added lines, 0 overridden. All
  catalog hits on the worktree are pre-existing artifact / comment
  text scanned by default; none introduced by this phase's added
  source/test lines.
- fp-discipline grep: 0 NEW unoverridden hits.
  - `frame-internal-unknown` at `TypeUnpickler.scala:135`: OVERRIDDEN
    by the `// flow-allow:` block at lines 131-134 (readTypeForTree
    OnceCell init-lambda path, the single legitimate site identified
    by the prior verify's class-B BLOCKER analysis).
  - `Log.live.unsafe.warn` token: GONE. Replaced by
    `Sync.Unsafe.evalOrThrow(Log.warn(...))` at TypeUnpickler.scala:608
    and :619.
  - Pre-existing hits in the same file (`null-literal`, `left-constructor`,
    `unsafe-site` on the existing `import AllowUnsafe.embrace.danger`,
    `private-over-annotation`) are out of scope for Phase 10
    classification; they predate this commit and live on unchanged
    lines.
- llm-tells grep: 0 NEW em-dash / en-dash hits on Phase 10 added
  source/test lines. The two em-dashes the prior verify flagged on
  `TypeUnpickler.scala:133, :199` are gone. Remaining catalog hits
  blame to:
  - `kyo-tasty/audit-fixes/phase-09-audit.md` (pre-existing artifact)
  - `kyo-tasty/audit-fixes/phase-10-verify.md` (the prior verify
    report itself; this run overwrites that file)
  - `TypeUnpicklerTest.scala:281, :289` (commit a04457b65, pre-Phase 10)
- dev-tag grep: 0 NEW hits. The three `phase-reference-in-comment`
  matches in `AstUnpickler.scala:135, :593, :605` blame to commit
  ad01c90b75 (pre-Phase 10, from the kyo-reflect import history) and
  live on unchanged lines.
- plan-diff (with baseline `phase-10-baseline.txt`): the bash script
  reports 3 MISSING (yq parsing the `path:` / `before:` / `after:`
  yaml keys as filenames, not real missing files) and 6
  DRIFT-FROM-IMPL (all already COMMITTED in HEAD from Phase 08b / 09;
  not in the dirty tree). Real Phase 10 dirty-tree classification:
  - AUTHORIZED: kyo-tasty/.../TypeUnpickler.scala (in plan
    files_modified)
  - AUTHORIZED: kyo-tasty/.../TypeUnpicklerTest.scala (in plan
    tests.files)
  - AUTHORIZED-REFACTOR: kyo-tasty/.../AstUnpickler.scala (Frame
    propagation required by the prior verify's class-B BLOCKER
    refactor directive; touches signature of `runPass1`, `walkStats`,
    `decodeOneTypeIfPresent`, `readDefDefReturnType`,
    `decodeTemplateParents` to thread `using Frame` to
    `readTypeIntoSession`; this is the same Frame plumbing the prior
    verify explicitly required)
  - AUTHORIZED: kyo-tasty/audit-fixes/phase-10-{baseline.txt,
    decisions.md, verify.md} (control artifacts; the baseline self-
    reference and the decisions / verify documents)
  - DRIFT-FROM-IMPL (real, dirty tree): NONE.
- test-count: expected=2, actual=2. PASS. Two test leaves in
  TypeUnpicklerTest.scala matching the plan's leaves[1] and leaves[2]:
  "unknown category-5 TASTy type tag fires a warn-level log"
  ("unknown tag logged"), "known TASTy type tag does not emit a warn-
  level log" ("known tag does not warn").
- stowaway-commit: NONE. HEAD is unchanged at 832aa6533 (Phase 09 tip);
  no commit ran inside the impl dispatch.
- cross-platform (plan declares platforms: [jvm, js, native]):
  JVM: 17/17 PASS (testOnly log captured this run).
  JS:  compile PASS (compile log captured this run).
  Native: compile PASS (compile log captured this run).
- M7 / INV-004 verdict: SATISFIED.
  - Behavior: M7-1 exercises tag 250 (unknown category-5) and asserts
    the captured stdout contains `unknown TASTy type tag 250`. M7-2
    exercises UNITconst (known category-1) and asserts the captured
    stdout is empty.
  - INV-004 ("unknown tags log a warn-level diagnostic at the
    unpickler boundary"): SATISFIED via `Log.warn` (effectful, routed
    through `Log.local` Local handler), wrapped in `Sync.Unsafe.evalOrThrow`
    at the synchronous decode-loop bridge.
  - Convention sweep: SATISFIED. `Frame.internal` retained at exactly
    ONE site (`TypeUnpickler.scala:135`, readTypeForTree OnceCell init)
    with a `// flow-allow:` rationale. `Log.live.unsafe.warn` GONE.
    Em-dashes on Phase 10 added lines GONE.

## Class-B findings (opus judgment)

- ACCEPT: `Sync.Unsafe.evalOrThrow(Log.warn(...))` bridge at
  `TypeUnpickler.scala:608` and `:619`.

  Comparison to Phase 02d's `Sync.Unsafe.defer` bridge
  (`Tasty.scala:698, 700, 708, 714`): both are unsafe-tier bridges
  installed at the synchronous boundary where the surrounding
  callee returns a plain (non-effect-typed) value. Phase 02d defers
  the entire body so the inner code can freely use unsafe helpers
  while still producing a `T < Sync`. Phase 10's site is different:
  `decodeTag` returns `Tasty.Type` (plain value) and is called
  recursively through `readTypeNode` from dozens of internal dispatch
  arms. Rewriting `decodeTag` to return `Tasty.Type < Sync` would
  propagate `Sync` through the entire type-decode dispatch and every
  recursive `readTypeNode` arm (40+ sites: TYPEREFsymbol, APPLIEDtype,
  TYPELAMBDAtype, ANNOTATEDtype, RECtype, MATCHtype, OR/AND, etc.).
  The decode loop is also called from the OnceCell-init path
  (readTypeForTree) which CANNOT receive an effectful `Sync` return
  per the prior verify's OnceCell-signature analysis. So the bridge
  is installed at the leaf (the unknown-tag fallback arm), which is
  the smallest possible unsafe surface.

  Held-out check on routing: `Log.warn` desugars to
  `logWhen(Level.warn)` which goes through
  `Sync.Unsafe.withLocal(local)` (kyo-core/Log.scala:142-149). This
  means the configured `Log.local` handler IS resolved at execution
  time even when wrapped in `Sync.Unsafe.evalOrThrow`. The prior
  verify's held-out check 2 ("a user with a custom Log handler sees
  the message") is now SATISFIED, because the unsafe boundary
  reflects the Local context.

  decodeTag's callers (`readTypeNode` line 266, recursively from 16+
  arms inside decodeTag itself plus the four entry points at lines
  90, 159, 176, 211) lock the synchronous return type in: each
  recursive call participates in the address-cache `addrCache(addr)
  = interned` side effect that depends on a synchronous return.
  Returning `Tasty.Type < Sync` would force every recursive call to
  flatMap, every `addrCache` write to lift into the effect, every
  pure case arm (UNITconst, TRUEconst, INTconst, etc.) to wrap a
  pure value in `Sync`. That cascades through `readTypeIntoSession`
  (DecodeSession Pass 1), `readTypeForTree` (OnceCell init), and
  `readPass1` — three entry points each with different effect-row
  invariants. The bridge at the leaf is the correct architectural
  choice.

  Verdict: ACCEPT. The bridge is at the smallest possible unsafe
  surface, the user-configurable Log handler is preserved through
  the Local-bridge, and the alternative (Sync everywhere) is a
  decode-loop rewrite that is out of scope for an M7 "log unknown
  tag" phase.

## Held-out acceptance check (class-B, opus)

Derived from the design's INV-004 statement ("the type unpickler MUST
log a warn-level diagnostic on every unknown TASTy type tag rather
than silently fall through to an unresolved sentinel"):

- Check 1: when the reader sees a tag not in TastyFormat known set,
  the user-observable log stream contains a warn line referencing
  the tag and the offset.
  Result: SATISFIED on JVM. M7-1 exercises tag 250 and the captured
  stdout contains `unknown TASTy type tag 250`.

- Check 2: the warn diagnostic must use the kyo Log effect so a user
  who has installed a custom Log handler sees it.
  Result: SATISFIED. `Log.warn` desugars to `logWhen(Level.warn)`
  which routes through `Sync.Unsafe.withLocal(Log.local)` so a
  user-configured Log handler is resolved at evaluation time, even
  when the surrounding `Sync.Unsafe.evalOrThrow` collapses the
  effect at the decode-loop boundary. The prior verify's class-B
  BLOCKER on this axis is now CLEARED.

## Overrides

- `// flow-allow:` at `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TypeUnpickler.scala:131-134`
  on `Frame.internal` at line 135.
  Rationale: "internal frame used here because readTypeForTree is
  called from TreeUnpickler.decodeSync, which is the OnceCell init
  lambda for Symbol.body. The init lambda has type () => Tree and
  cannot accept a Frame parameter. This is the one legitimate
  flow-allow site; all other decode paths propagate a real Frame."

## Exit code: 0
