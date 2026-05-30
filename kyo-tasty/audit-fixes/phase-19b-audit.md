# Phase 19b Audit

## Summary

PASS with two WARNs and two NOTEs. The core serialization mechanics are correct and the
forward-compat path is sound. The two WARNs are: (1) the round-trip test does not assert
that parents are actually populated for symbols that do have local Named parents, only that
the slot is non-null; and (2) the decisions doc states "assignHomes in ClasspathOrchestrator
assigns every symbol's ClasspathRef before transitioning to Ready state," but assignHomes
is a private method in `Tasty.scala`, called after orchestration completes, not inside the
orchestrator itself. The invariant holds in practice, but the decisions doc description is
imprecise.

---

## Findings

### 1. PARENTS lossy round-trip - WARN

`Symbol.parents` returns a strict subset after warm-cache reload: external parents such as
`java.lang.Object` are lost. The subtyping consumer at `Subtyping.scala:201-227` walks
`checkParents` and falls into `NotSub` on `parents.isEmpty` or into `Unknown` only when a
Named parent's own parents are unset. It does NOT assert that `java.lang.Object` is present.
The parent-chain walk returns `Unknown` for incomplete chains, never `Sub` by false
inference. No consumer in kyo-tasty asserts `parents.contains(Object)`. The behavior change
is: pre-snapshot, `isSubtype(SomeTrait, Object)` may return `Sub`; post-snapshot, it returns
`Unknown` (or `NotSub` if the Object symbol is genuinely absent). This is a semantic
regression for cross-classpath subtype queries, but the consumer gracefully downgrades to
`Unknown` rather than producing an incorrect `Sub`. WARN, not BLOCKER.

Route: Phase 20+ should consider encoding external parents by FQN string for post-load
re-resolution (noted in decisions doc).

### 2. PARENTS encoding spec - NOTE

The -1 sentinel and filter-on-deserialize pattern are clear and self-describing. The wire
format scaladoc in both `serializeSymbolRelLists` and `deserializeRefLists` explicitly
documents the sentinel meaning. A future phase encoding external parents by FQN string could
add a separate section (e.g. EXTPAR_) or extend the existing section with a sign-bit
discriminant without breaking minor-version compat. No action required now.

### 3. stub helper removal - OK

`grep -rn "stub" kyo-tasty/shared/src/main` returns one hit: a comment in `ByteView.scala`
("stub for memory-mapped file support"). No production call site uses the `stub` helper.
The `stub` definition and the `stub("Symbol.body")` call are fully removed from `Tasty.scala`.
Compilation is clean (405/405 tests green per commit message).

### 4. home.isAssigned invariant - WARN (precision)

The decisions doc states "assignHomes in ClasspathOrchestrator assigns every symbol's
ClasspathRef." This is inaccurate. `assignHomes` is a private method defined and called
inside `object Tasty.Classpath` in `Tasty.scala` (lines 1135-1147), not inside
`ClasspathOrchestrator`. The orchestrator produces the fully-populated Classpath state;
`Tasty.Classpath.open` calls `assignHomes` immediately after `ClasspathOrchestrator.open`
returns or after `SnapshotReader.readMapped` succeeds (lines 1087, 1119). Both warm and
cold paths call `assignHomes` unconditionally before returning `cp` to the caller. The
invariant is correct in substance, but the decisions doc attribution is wrong. WARN because
a future maintainer reading the decisions doc who looks in `ClasspathOrchestrator.scala`
for `assignHomes` will not find it. Route to Phase 21d doc sweep.

### 5. Round-trip test substance - WARN

`SnapshotRoundTripTest` verifies that (a) every cold class's declarations are non-empty in
the warm load if they were non-empty cold, and (b) every warm symbol's `_parents` slot is
non-null. Assertion (b) is trivially satisfied by the fallback `Chunk.empty` set on every
unset slot. It does not check that a symbol with local Named parents (e.g. a trait that
extends another trait in the same snapshot) has those parents populated in the warm load.
The fixture is `SomeTrait.tasty`, which likely has only external parents (java.lang.Object).
There is no assertion of the form "warm parent count >= 1 for a symbol whose cold parents
contain at least one local Named parent." The PARENTS section writer and reader correctness
is unverified end-to-end by this test for the non-empty case. Route: add a fixture with a
locally-defined parent (class A; class B extends A) and assert `warmSym.parents.nonEmpty`.

### 6. SnapshotReaderTest minor=2 fixture - OK

The test constructs a complete binary-valid KRFL byte array at minor=2: magic bytes, version
bytes (major=1, minor=2), flags, digest, section count, section index entries for NAMES,
SYMBOLS, ERRORS, and copied payloads. This is a real minor=2 snapshot, not a trivial stub.
It loads via `SnapshotReader.read`, confirms no `SnapshotVersionMismatch`, and asserts zero
symbols. The forward-compat path (absence of PARENTS/MEMBERS/TPARAMS_ sections results in
`Chunk.empty` fallback) is exercised. The fixture is adequate for INV-003.

### 7. Code quality - OK

No em-dashes, no semicolons as statement separators, no `asInstanceOf`, no `Either`/`Right`/
`Left` in new code. `Some`/`None` in the new reader code are used exclusively as match arms
on `sectionMap.get(...)` which returns stdlib `scala.Option`; this pattern is consistent
with all pre-existing SnapshotReader code and is the idiomatic approach when consuming
`scala.collection.Map.get`. The `None else Some(...)` in `serializeSymbolRelLists` is inside
a `flatMap` on `symbols.zipWithIndex` (stdlib collection), also consistent with existing
usage. No default parameters introduced. No `Either` in new production code.

---

## Recommendations

- WARN (round-trip parents not end-to-end tested, route Phase 20/21f): add a fixture with
  a locally-defined parent class and assert `warmSym.parents.nonEmpty` after snapshot
  round-trip to pin PARENTS section correctness for the non-empty case.
- WARN (decisions doc misstates assignHomes location, route Phase 21d): correct
  "assignHomes in ClasspathOrchestrator" to "assignHomes in Tasty.Classpath (Tasty.scala)"
  in phase-19b-decisions.md.
- NOTE (PARENTS lossy round-trip, route Phase 20+): external parents (java.lang.Object etc.)
  are not retained. Subtyping degrades to Unknown for cross-classpath chains. A future phase
  can add FQN-string encoding for external parents in a new section.
- NOTE (encoding spec): wire format is self-describing and future-extensible. No action
  required.
