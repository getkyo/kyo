# Cross-phase invariants ledger

Source: derived from 02-design.md `## Cross-phase invariants (candidates)` and the open-question resolutions in 03a-open-resolutions.md. Phase ids assigned by flow-plan (05-plan.md). Phase numbering is the numeric id in 05-plan.yaml.

INV-001: AllowUnsafe routine-accessor signatures take `(using AllowUnsafe)` with no `import danger` inside the body.
  produced_by: Phase 2
  consumed_by: Phase 5, 6, 11, 13, 15, 16, 17, 25, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-001

INV-002: Cold-load and warm-cache paths allocate zero `Sync.Unsafe.defer` closures per Symbol or Classpath accessor call.
  produced_by: Phase 2
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-002

INV-003: Snapshot format major bump invalidates old snapshots; minor bump is add-only per `SnapshotFormat.scala:42-44`.
  produced_by: Phase 23
  consumed_by: Phase 23, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-003

INV-004: Every TASTy type tag in `TypeUnpickler.decodeTag` has either an explicit decode branch or routes through the unknown-tag warning hook.
  produced_by: Phase 10
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-004

INV-005: TASTy AST tag coverage in `TreeUnpickler` matches the decomposition axis per Q-003 with no remaining `Tree.Unknown` emission for tags emitted by Scala 3.6+ TASTy v28.8 output.
  produced_by: Phase 22
  consumed_by: Phase 26, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-005

INV-006: Every `TastyError.MalformedSection` event carries the byte offset of the failure.
  produced_by: Phase 14
  consumed_by: Phase 17, 18, 24, 25, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-006

INV-007: Test files prefix-match their source basename; 1:1 preferred per `steering.md:97-104`.
  produced_by: Phase 1
  consumed_by: Phase 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-007

INV-008: Java classfile attributes `BootstrapMethods`, `NestHost`, `NestMembers`, `PermittedSubclasses`, `MethodParameters`, `RuntimeTypeAnnotations` parsed and exposed via `Symbol` or `JavaMetadata`.
  produced_by: Phase 11
  consumed_by: Phase 13, 31
  smoke_test_path: kyo-tasty/jvm/src/test/scala/kyo/InvariantsSpec.scala::INV-008

INV-009: `OnceCell.init` lambdas are idempotent; concurrent first-callers compute the same value modulo equality.
  produced_by: Phase 6
  consumed_by: Phase 25, 28, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-009

INV-010: `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex`, `Interner` reject out-of-bounds reads with a structured `TastyError.MalformedSection` rather than an uncaught exception.
  produced_by: Phase 3
  consumed_by: Phase 7, 11, 14, 26, 29, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-010

INV-011: `Symbol.TastyOrigin.addrMap` is not publicly accessible.
  produced_by: Phase 2
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-011

INV-012: `JarCentralDirectory` and `JarMappedReader` handle 64-bit offsets and Zip64 archives correctly with no Int truncation past 2GB.
  produced_by: Phase 4
  consumed_by: Phase 14, 26, 31
  smoke_test_path: kyo-tasty/jvm/src/test/scala/kyo/InvariantsSpec.scala::INV-012

INV-013: `Constant.ClassConst` constants carry the real referenced `Type` rather than the `classConstSentinel` placeholder.
  produced_by: Phase 16
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-013

INV-014: `Annotation.args` decode succeeds for any annotation discovered through the classpath orchestrator, including accesses after the initial decode boundary.
  produced_by: Phase 17
  consumed_by: Phase 18, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-014

INV-015: Snapshot warm-cache restore returns the full `_parents`, `_typeParams`, `_declarations` chunks.
  produced_by: Phase 23
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-015

INV-016: `Type.isSubtypeOf` returns `enum SubtypeVerdict { Sub, NotSub, Unknown }` and throws no exceptions; callers pattern-match all three cases.
  produced_by: Phase 15
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-016

INV-017: JS and Native `InflateHook` implementations produce byte-for-byte parity with the JVM `java.util.zip.InflaterInputStream` reference on valid RFC 1950 input.
  produced_by: Phase 24
  consumed_by: Phase 27, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-017

INV-018: `MappedByteView` accessors that may address > 2GB regions return or accept `Long` offsets.
  produced_by: Phase 4
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/jvm/src/test/scala/kyo/InvariantsSpec.scala::INV-018

INV-019: `TypeArena.internRec` enforces a recursion-depth cap and reports a structured error on overflow.
  produced_by: Phase 8
  consumed_by: Phase 26, 28, 29, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-019

INV-020: README, DESIGN, and code blocks therein reference `kyo.Tasty.*` and `kyo.TastyError` consistently; no `Reflect` or `kyo-reflect` occurrences remain.
  produced_by: Phase 1
  consumed_by: Phase 30, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-020

INV-021: Production-code comments never reference campaign phase metadata (`Phase 3`, `Phase C`, `Phase 0`).
  produced_by: Phase 1
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-021

INV-022: The `kyo.tasty.examples` package does not ship in the main `kyo-tasty` artifact; the sibling `kyo-tasty-examples` sbt module is the publication point and uses top-level `package examples`.
  # rationale: Q-009 Fork 4 safety lens; sibling module precedent matches kyo-examples and kyo-tasty-fixtures/kyo-tasty-bench at build.sbt:508,518.
  produced_by: Phase 30
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-022

INV-023: Snapshot `minorVersion` increments from 2 to 3; readers at minor 2 still parse new sections (forward-compatible reader, add-only writer).
  # rationale: Q-005 research-findings; preserves INV-003 versioning policy at SnapshotFormat.scala:42-44.
  produced_by: Phase 23
  consumed_by: Phase 25, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-023

INV-024: JVM and Native `InflateHook` use `java.util.zip.InflaterInputStream`; JS uses an in-tree pure-Scala RFC 1950 inflate matching the JVM reference byte-for-byte.
  # rationale: Q-002 research-findings; Scala Native javalib provides InflaterInputStream via libz FFI, JS has no java.util.zip.
  produced_by: Phase 24
  consumed_by: Phase 27, 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-024

INV-025: The no-strict `Classpath.open(roots)` overload delegates by name to the canonical `Classpath.open(roots, strict)` with `strict = false` explicit; no default-parameter shim.
  # rationale: Q-007 research-findings; CONTRIBUTING.md §358-§382 canonical-vs-shorthand pattern, no default params on internal APIs.
  produced_by: Phase 2
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-025

INV-026: The README and DESIGN.md contain no reference to a `Reflect.Reads` typeclass or any other vaporware API.
  # rationale: Q-008 research-findings; no Reads trait/class/object/alias exists in kyo-tasty source; README line 41 reference is aspirational and must be removed.
  produced_by: Phase 1
  consumed_by: Phase 31
  smoke_test_path: kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala::INV-026

INV-027: No phase regresses kyo-tasty cold-load or warm-cache benchmark medians beyond steering tolerance versus the pre-campaign baseline measured by `kyo-tasty-bench`.
  # rationale: steering.md no-perf-regression binding rule; kyo-tasty-bench head-to-head harness already established (see fefd37024, a04457b65).
  produced_by: Phase 31
  consumed_by: (none yet; INV-027 is the campaign's terminal acceptance gate)
  smoke_test_path: kyo-tasty/jvm/src/test/scala/kyo/InvariantsSpec.scala::INV-027
