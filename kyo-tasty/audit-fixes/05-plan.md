# 05 Plan: Remediate all 48 findings from the kyo-tasty audits

Task type: refactor
Cites design: ./02-design.md (patched)
Cites invariants: ./04-invariants.md
Cites resolutions: ./03a-open-resolutions.md
Cites findings: ./audit-findings.md
Cites steering: ./steering.md

Module: kyo-tasty
crossPlatforms: [jvm, js, native]

## Cross-reference index

Every code in {C1-C4, M1-M10, L1-L7, B1-B15, T1-T8, A1-A4} maps to exactly one phase via the `addresses:` field below.

| Code | Phase |
|------|-------|
| L1 | 1 |
| L2 | 1 |
| L3 | 1 |
| L4 | 1 |
| L6 | 1 |
| L7 | 1 |
| A1 | 1 |
| A2 | 2 |
| A3 | 6 |
| A4 | 2 |
| B1 | 3 |
| B4 | 3 |
| B7 | 3 |
| B10 | 3 |
| C4 | 3 |
| C1 | 4 |
| B2 | 4 |
| B3 | 4 |
| B6 | 4 |
| B11 | 4 |
| B14 | 5 |
| B15 | 5 |
| C2 | 6 |
| B12 | 7 |
| B13 | 7 |
| B8 | 8 |
| B9 | 8 |
| B5 | 9 |
| C3 | 9 |
| M7 | 10 |
| M8 | 11 |
| M9 | 12 |
| T1 | 13 |
| L5 | 14 |
| T3 | 14 |
| M6 | 15 |
| M3 | 16 |
| M2 | 17 |
| M1 | 18 |
| M4 | 19 |
| M10 | 19 |
| M5 | 20 |
| T2 | 21 |
| T4 | 22 |
| T5 | 23 |
| T7 | 24 |
| T8 | 24 |
| T6 | 25 |
| (kyo-tasty-examples extract) | 26 |

<!-- flow-allow: M10-campaign-code; campaign code M10 references existing source legacy-comments that this plan resolves, not plan-time postponements -->
Note: M10 (stub-comment-removal campaign) is resolved as a documentation pass attached to Phase 19 because its three pending-removal-marker sites (Constant.scala:81, SnapshotReader.scala:170-171, Tasty.scala:709) are fixed in Phases 16, 19, and 17 respectively; the cleanup pass that removes the legacy comment lines lands in Phase 19. L5 and T3 share Phase 14 because both edit `TastyError.scala`. A3 ships with Phase 6 (OnceCell idempotence) because both edit the same file. Phase 26 covers the kyo-tasty-examples sibling module extraction (Q-009 Fork 4).

All 48 codes accounted for.

---

## Phase 1: Rewrite documentation

Depends on: none.

Rewrites `kyo-tasty/README.md`, `kyo-tasty/DESIGN.md`, and removes "Phase N" / "Phase C" / "Phase 0" production-code comments. Replaces every `kyo-reflect`, `Reflect.*`, `ReflectError`, `.kyo-reflect-cache` occurrence with `kyo-tasty`, `Tasty.*`, `TastyError`, `.kyo-tasty-cache`. Removes the `Reflect.Reads` mention at `README.md:41` (Q-008: aspirational, no implementation). Adds scaladoc to `Classpath.open`, `openCached`, `findClass`, `topLevelClasses`, `packages`, `Name.apply`, `Flags.empty`. Splits `DESIGN.md` Section 1 into user-facing goals vs performance targets.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/README.md`: replace `Reflect.*` / `ReflectError` / `kyo-reflect` / `.kyo-reflect-cache` / `Reflect.Reads` references; sample code matches `kyo.Tasty.*` API.

  ```scala
  // kyo-tasty/README.md ; BEFORE
  # kyo-reflect
  //
  // Reads TASTy files via `Reflect.Classpath.open(...)` ... `Reflect.findClass(fqn)`
  // emits `ReflectError`.
  //
  // There's also `Reflect.Reads[A]`, a typeclass for projecting a `Symbol` into
  // your own data type via `derives` clauses.
  //
  // Snapshot cache lives in `.kyo-reflect-cache`.

  // kyo-tasty/README.md ; AFTER
  # kyo-tasty
  //
  // Reads TASTy files via `Tasty.Classpath.open(...)` ... `cp.findClass(fqn)`
  // emits `TastyError`.
  //
  // (The Reads typeclass reference is removed; no such API exists.)
  //
  // Snapshot cache lives in `.kyo-tasty-cache`.
  ```

- `kyo-tasty/DESIGN.md`: replace `kyo-reflect`/`Reflect.*` namespace; split Section 1 into "Goals" (user-facing) and "Performance targets" (independent).

  ```text
  // kyo-tasty/DESIGN.md ; BEFORE
  # kyo-reflect Design
  ## 1. Goals
  - Read Scala 3 TASTy
  - Better cold-load than tasty-query
  - Cross-platform JVM+JS+Native
  ... (lines 1-34 mix user goals with perf targets) ...

  // kyo-tasty/DESIGN.md ; AFTER
  # kyo-tasty Design
  ## 1. Goals
  - Read Scala 3 TASTy
  - Read Java classfiles, Scala 2 pickles, JPMS modules
  - Cross-platform JVM+JS+Native
  - Provide a unified Symbol/Type API

  ## 1a. Performance targets
  - Cold-load median below tasty-query reference
  - Warm-cache median measured by kyo-tasty-bench
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` line 78: rewrite the campaign-phase comment.

  ```scala
  // kyo-tasty/shared/src/main/scala/kyo/Tasty.scala ; BEFORE
          // Phase 0 flags (bits 0-15)
          val Inline: Flag      = Flag(1L << 0, "Inline")

  // kyo-tasty/shared/src/main/scala/kyo/Tasty.scala ; AFTER
          // Core access flags (bits 0-15)
          val Inline: Flag      = Flag(1L << 0, "Inline")
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` line 95: rewrite the "Phase 3 flags" comment.

  ```scala
  // BEFORE
          // Phase 3 flags (bits 16+)
          val Open: Flag          = Flag(1L << 16, "Open")
  // AFTER
          // Extended modifier flags (bits 16+)
          val Open: Flag          = Flag(1L << 16, "Open")
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` line 124: rewrite the "Phase 10" comment.

  ```scala
  // BEFORE
          // Phase 10 flag (bit 44): identifies symbols decoded from Scala 2 pickles embedded in classfiles.
          val Scala2: Flag = Flag(1L << 44, "Scala2")
  // AFTER
          // Scala 2 origin flag (bit 44): identifies symbols decoded from Scala 2 pickles embedded in classfiles.
          val Scala2: Flag = Flag(1L << 44, "Scala2")
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` lines 589, 602, 1012: rewrite "Phase N" production-code comments and add scaladoc to public `Classpath.open` / `openCached` / `findClass` / `topLevelClasses` / `packages` explaining why Async (parallel per-file decode) and Scope (file-handle finalization) are required.

  ```scala
  // BEFORE
          // Resolving accessors (return TastyError.NotImplemented in Phase 0).
  ...
            * @note
            *   Implemented in v2 Phase 5. Populated eagerly during Pass 1 / mergeResults. Pure in v3 Phase 3.
  ...
            * (closed-state enforcement is Body-only, Phase 4).
  // AFTER
          // Resolving accessors.
  ...
            * @note
            *   Populated eagerly during cold-load mergeResults; readable as a pure accessor thereafter.
  ...
            * (closed-state enforcement is Symbol.body only).
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala` line 19: rewrite the "Phase C" comment to name the architectural concern (classfile attribute decoding).

  ```scala
  // BEFORE
  // Phase C: classfile attribute decoder
  // AFTER
  // Classfile attribute decoder (one match arm per JVMS attribute name).
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` Classpath companion (lines 893-911): add scaladoc paragraph on each public `open` overload explaining Async (parallel per-file decode) and Scope (mmap/JAR file-handle finalization) requirements; add doctest examples on `findClass`, `topLevelClasses`, `packages`, `findModule`, `findClassByBinary`.

  ```scala
  // BEFORE
          /** Open a classpath from directory/file roots. Soft-fail mode (errors accumulate in `cp.errors`).
            *
            * Registers a finalizer on the enclosing `Scope` to close the classpath.
            */
          def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
              openImpl(roots, strict = false)
  // AFTER
          /** Open a classpath from directory/file roots. Soft-fail mode (errors accumulate in `cp.errors`).
            *
            * Effect row rationale:
            *   - `Sync`: file I/O for JAR / classfile / TASTy reads.
            *   - `Async`: parallel per-file decode across the workgroup.
            *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas.
            *   - `Abort[TastyError]`: fatal errors (classpath build state, snapshot mismatch).
            */
          def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
              open(roots, strict = false)
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` Name extension and Flags.empty (lines 47-72): add doctest scaladoc.

  ```scala
  // BEFORE
          /** Construct a `Name` from a `String` by encoding to UTF-8 and interning the bytes. */
          def apply(s: String): Name =
  // AFTER
          /** Construct a `Name` from a `String` by encoding to UTF-8 and interning the bytes.
            *
            * Example:
            * {{{
            *   val n = Tasty.Name("scala.Predef")
            *   n.asString == "scala.Predef"
            * }}}
            */
          def apply(s: String): Name =
  ```

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None (scaladoc / comment edits only).

### Tests

Total: 4.

1. `TastyTest.scala`: README rename consistency
   - Given: the file at `kyo-tasty/README.md`.
   - When: the test reads the file as String.
   - Then: the substring `"kyo-reflect"` appears 0 times AND `"Reflect.Reads"` appears 0 times AND `"kyo-tasty"` appears at least once.
   - Pins: INV-020, INV-026.

2. `TastyTest.scala`: DESIGN section split
   - Given: the file at `kyo-tasty/DESIGN.md`.
   - When: the test reads the file and counts header lines.
   - Then: a header line `"## 1a. Performance targets"` (or `"## 2. Performance targets"`) appears exactly once AND `"## 1. Goals"` appears exactly once.
   - Pins: L7 behavioral requirement (goals vs perf separation).

3. `TastyTest.scala`: no phase-metadata comments in production source
   - Given: the recursive file scan over `kyo-tasty/shared/src/main/scala/kyo` and `kyo-tasty/jvm/src/main/scala/kyo`.
   - When: each `.scala` file is read.
   - Then: no line matches the regex `// Phase [0-9CB]` (case-sensitive) excluding files under `audit-fixes/`.
   - Pins: INV-021.

4. `TastyTest.scala`: README doctest extraction compiles
   - Given: the fenced `scala` code blocks inside `kyo-tasty/README.md`.
   - When: each block is compiled by the `kyo.Test` doctest extractor.
   - Then: every block compiles AND every API name in each block (e.g. `Tasty.Classpath.open`, `cp.findClass`) is reachable through `import kyo.*`.
   - Pins: INV-020, L6 behavioral requirement.

Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala` (new prefix-match file for `Tasty.scala`).

### Consumed invariants

None.

### Produced invariants

- INV-020: README, DESIGN, and code blocks reference `kyo.Tasty.*` and `kyo.TastyError` consistently.
- INV-021: Production-code comments never reference campaign phase metadata.
- INV-026: README and DESIGN.md contain no `Reflect.Reads` or other vaporware API reference.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyTest'`. JS / Native equivalents: `kyo-tastyJS/testOnly kyo.TastyTest`, `kyo-tastyNative/testOnly kyo.TastyTest`.

estimated_loc: 280.

---

## Phase 2: Propagate AllowUnsafe through accessor signatures

Depends on: none. (Phase 1 is doc-only; signature change is independent of comment edits.)

<!-- flow-allow: Sync.Unsafe.bridge-kyo-api; names the kyo-core public API as the bridge mechanism in phase description prose, not plan-postponement language -->
Replaces 24 `import AllowUnsafe.embrace.danger` sites inside routine `Symbol` and `Classpath` accessor bodies with `(using AllowUnsafe)` signatures per CONTRIBUTING.md §828 option 1. `Symbol.body` keeps its public effect-row signature; only the inner `_bodyOnce.get()` bridge changes from `import danger` to `Sync.Unsafe.defer { _bodyOnce.get() }` per §833 option 2 (preserving zero allocation per call because the defer is bridge-only, not per accessor). `Classpath.open` overload structure becomes explicit delegation: the one-arg variant calls the two-arg form with `strict = false` (Q-007). `Symbol.TastyOrigin.addrMap` moves to `private[kyo]` and loses its public `(using AllowUnsafe)` slot.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Name.asString` extension (lines 58-64):

  ```scala
  // BEFORE
          extension (n: Name)
              /** Decode the interned bytes to a String (lazily cached). */
              def asString: String =
                  // Unsafe: OnceCell.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public API boundary.
                  import AllowUnsafe.embrace.danger
                  n.string.get()
          end extension
  // AFTER
          extension (n: Name)
              /** Decode the interned bytes to a String (lazily cached).
                *
                * Requires `(using AllowUnsafe)`: the underlying `OnceCell.get()` is an unsafe-tier read.
                */
              def asString(using AllowUnsafe): String =
                  n.string.get()
          end extension
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.fullName` (line 545-549):

  ```scala
  // BEFORE
          def fullName: Name =
              // Unsafe: OnceCell.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public API boundary.
              import AllowUnsafe.embrace.danger
              _fullNameOnce.get()
          end fullName
  // AFTER
          def fullName(using AllowUnsafe): Name =
              _fullNameOnce.get()
          end fullName
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.isPackageObject` (lines 554-558):

  ```scala
  // BEFORE
          def isPackageObject: Boolean =
              // Unsafe: OnceCell.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public API boundary.
              import AllowUnsafe.embrace.danger
              flags.contains(Flag.Module) && name.string.get() == "package"
          end isPackageObject
  // AFTER
          def isPackageObject(using AllowUnsafe): Boolean =
              flags.contains(Flag.Module) && name.string.get() == "package"
          end isPackageObject
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.scaladoc` (lines 568-573):

  ```scala
  // BEFORE
          def scaladoc: Maybe[String] =
              // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
              import AllowUnsafe.embrace.danger
              if _scaladoc.isSet then _scaladoc.get()
              else Maybe.Absent
          end scaladoc
  // AFTER
          def scaladoc(using AllowUnsafe): Maybe[String] =
              if _scaladoc.isSet then _scaladoc.get()
              else Maybe.Absent
          end scaladoc
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.position` (lines 582-587): same shape as scaladoc.

  ```scala
  // BEFORE
          def position: Maybe[Position] =
              import AllowUnsafe.embrace.danger
              if _position.isSet then _position.get()
              else Maybe.Absent
          end position
  // AFTER
          def position(using AllowUnsafe): Maybe[Position] =
              if _position.isSet then _position.get()
              else Maybe.Absent
          end position
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.declaredType` (lines 604-611):

  ```scala
  // BEFORE
          def declaredType: Type =
              if kind == SymbolKind.Package then
                  throw new IllegalArgumentException("Symbol.declaredType is not available for Package symbols")
              else
                  import AllowUnsafe.embrace.danger
                  _declaredType.get()
  // AFTER
          def declaredType(using AllowUnsafe): Type =
              if kind == SymbolKind.Package then
                  throw new IllegalArgumentException("Symbol.declaredType is not available for Package symbols")
              else
                  _declaredType.get()
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.parents`, `Symbol.typeParams`, `Symbol.declarations` (lines 617-644): same shape, drop the `import danger`, add `(using AllowUnsafe)` to signature.

  ```scala
  // BEFORE
          def parents: Chunk[Type] =
              import AllowUnsafe.embrace.danger
              _parents.get()
          end parents
          def typeParams: Chunk[Symbol] =
              import AllowUnsafe.embrace.danger
              _typeParams.get()
          end typeParams
          def declarations: Chunk[Symbol] =
              import AllowUnsafe.embrace.danger
              _declarations.get()
          end declarations
  // AFTER
          def parents(using AllowUnsafe): Chunk[Type] =
              _parents.get()
          end parents
          def typeParams(using AllowUnsafe): Chunk[Symbol] =
              _typeParams.get()
          end typeParams
          def declarations(using AllowUnsafe): Chunk[Symbol] =
              _declarations.get()
          end declarations
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.companion` (lines 654-688): adds `(using AllowUnsafe)` to signature; inner `Name.asString` calls become eligible because the proof is in scope.

  ```scala
  // BEFORE
          def companion: Maybe[Symbol] =
  // AFTER
          def companion(using AllowUnsafe): Maybe[Symbol] =
  ```

<!-- flow-allow: Sync.Unsafe.bridge-kyo-api; names the kyo-core public API as the bridge replacement, not plan-postponement language -->
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.body` inner bridge (lines 718-727): replace `import AllowUnsafe.embrace.danger` followed by `_bodyOnce.get()` with `Sync.Unsafe.defer { _bodyOnce.get() }` per §833 option 2. The outer effect row stays `Sync & Abort[TastyError]`; the defer adds zero per-accessor-call closure because `body()` returns a Sync computation by contract.

  ```scala
  // BEFORE
                                  import AllowUnsafe.embrace.danger
                                  if home.get().isClosed then
                                      Abort.fail(TastyError.ClasspathClosed)
                                  else
                                      val decoded: Either[TastyError, Tree] =
                                          try Right(_bodyOnce.get())
                                          catch ...
  // AFTER
<!-- flow-allow: Sync.Unsafe.bridge-kyo-api; fenced Scala code block literal, not plan-postponement language -->
                                  Sync.Unsafe.defer:
                                      val cp = home.get()
                                      if cp.isClosed then
                                          Abort.fail(TastyError.ClasspathClosed)
                                      else
                                          val decoded: Either[TastyError, Tree] =
                                              try Right(_bodyOnce.get())
                                              catch ...
  ```

<!-- flow-allow: Sync.Unsafe.bridge-kyo-api; fenced Scala explanation of the API bridge mechanism, not plan-postponement language -->
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol._bodyOnce` init lambda (lines 528-542): the lambda runs inside `OnceCell.get()` which is already an unsafe-tier helper; the lambda body needs an `AllowUnsafe` to call `o.addrMap` (which becomes `private[kyo]`). The init lambda receives the proof from the outer `Sync.Unsafe.defer { _bodyOnce.get() }` bridge.

  ```scala
  // BEFORE
              new kyo.internal.tasty.symbol.OnceCell[Tree](() =>
                  import AllowUnsafe.embrace.danger
                  origin match ...
              )
  // AFTER
              new kyo.internal.tasty.symbol.OnceCell[Tree](() =>
                  // Init lambda runs under the AllowUnsafe proof passed to OnceCell.get().
                  // OnceCell.get() is unsafe-tier (CAS on AtomicReference) so the proof is in scope here.
                  origin match ...
              )
  ```

  Note: OnceCell's init function takes `() => A`. After the bridge change, `OnceCell.get()(using AllowUnsafe)` is the public site that imposes the proof; the init lambda is called from inside `get()` so it can read `import AllowUnsafe.embrace.danger`-style state if needed. For the lambda body's specific read of `addrMap`, the design is: the lambda is constructed once at Symbol-make time and stores the origin reference; when it fires, it executes `TreeUnpickler.decodeSync(o, this)`, which itself uses `(using AllowUnsafe)` in its own signature.

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.TastyOrigin.addrMap` (lines 862-864): drop `(using AllowUnsafe)`; tighten visibility to `private[kyo]`.

  ```scala
  // BEFORE
              def addrMap(using AllowUnsafe): IntMap[Tasty.Symbol] =
                  if _addrMap.isSet then _addrMap.get()
                  else IntMap.empty
  // AFTER
              private[kyo] def addrMap: IntMap[Tasty.Symbol] =
                  // Unsafe: SingleAssign.get() is unsafe-tier; private[kyo] visibility limits callers to kyo.internal.tasty.*
                  // which already runs inside an AllowUnsafe context.
                  import AllowUnsafe.embrace.danger
                  if _addrMap.isSet then _addrMap.get()
                  else IntMap.empty
  ```

  This single internal site keeps the `import danger` per CONTRIBUTING.md §839 case 3 (this is the internal accessor; the call sites in `TreeUnpickler` already run in `import danger` initialization contexts).

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Classpath.open` overload structure (lines 899-904): make delegation explicit per Q-007. The `(roots, strict)` two-arg overload is canonical; the one-arg form calls it with `strict = false` explicit (no default-param shim).

  ```scala
  // BEFORE
          def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
              openImpl(roots, strict = false)
          def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
              openImpl(roots, strict)
  // AFTER
          /** One-arg variant: delegates to the canonical two-arg form with `strict = false`. */
          def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
              open(roots, strict = false)
          /** Canonical two-arg form. Soft-fail when `strict = false`, fail-fast when `strict = true`. */
          def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
              openImpl(roots, strict)
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Classpath.assignHomes` (lines 992-1004): the `import AllowUnsafe.embrace.danger` stays per CONTRIBUTING.md §839 case 3 (initialization).

  No edit; this is steering-confirmed.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala` lines 68-115: drop `import AllowUnsafe.embrace.danger` from `pureClass`, `purePackage`, `pureModule`, `pureTopLevelClasses`, `purePackages`; add `(using AllowUnsafe)` to signatures.

  ```scala
  // BEFORE
      private[kyo] def pureClass(fqn: String): Maybe[Tasty.Symbol] =
          import AllowUnsafe.embrace.danger
          stateRef.unsafe.get() match
              case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
              case _                        => Maybe.Absent
          end match
      end pureClass
  // AFTER
      private[kyo] def pureClass(fqn: String)(using AllowUnsafe): Maybe[Tasty.Symbol] =
          stateRef.unsafe.get() match
              case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
              case _                        => Maybe.Absent
          end match
      end pureClass
  ```

  Apply the same shape to `purePackage`, `pureModule`, `pureTopLevelClasses`, `purePackages`, `accumulatedErrors` (lines 136-144), `allSymbols` (lines 154-162), `isClosed` (line 27), `transitionToReady` (line 202-217), `close` (line 220-224).

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala` (lines 19-37): drop `import AllowUnsafe.embrace.danger` from `assign`, `get`, `isAssigned`; add `(using AllowUnsafe)` to signatures.

  ```scala
  // BEFORE
      def assign(cp: Tasty.Classpath): Unit =
          import AllowUnsafe.embrace.danger
          slot.set(cp)
      end assign
      def get(): Tasty.Classpath =
          import AllowUnsafe.embrace.danger
          slot.get()
      end get
      def isAssigned: Boolean =
          import AllowUnsafe.embrace.danger
          slot.isSet
      end isAssigned
  // AFTER
      def assign(cp: Tasty.Classpath)(using AllowUnsafe): Unit =
          slot.set(cp)
      end assign
      def get()(using AllowUnsafe): Tasty.Classpath =
          slot.get()
      end get
      def isAssigned(using AllowUnsafe): Boolean =
          slot.isSet
      end isAssigned
  ```

<!-- flow-allow: Sync.Unsafe.bridge-kyo-api; caller-update narrative names the kyo-core public API as the wrapping mechanism -->
- Caller updates: every callsite of the 24 accessors needs to either be in `(using AllowUnsafe)` scope or have `Sync.Unsafe.defer { ... }` wrapping. Per Q-006 research, 133 sites are already in proof scope and 26 sites need to thread the proof. Each affected file gets its caller methods marked `(using AllowUnsafe)` where the caller was already an internal unpickler / orchestrator, or wrapped via `Sync.Unsafe.defer` where the caller is a public effectful entry point. Specific affected files: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala` (parent-chain walks), `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathOrchestrator.scala`, `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/TypeOps.scala`, the four example files in `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/*.scala`, and `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotWriter.scala`.

  Pattern (Subtyping.scala line 55):

  ```scala
  // BEFORE
      def isSubtype(sub: Tasty.Type, sup: Tasty.Type, cp: InternalClasspath, budget: Int): Boolean =
          ...
          case Tasty.Type.Named(supSym) if supSym.fullName.asString == AnyFqn =>
  // AFTER
      def isSubtype(sub: Tasty.Type, sup: Tasty.Type, cp: InternalClasspath, budget: Int)(using AllowUnsafe): Boolean =
          ...
          case Tasty.Type.Named(supSym) if supSym.fullName.asString == AnyFqn =>
  ```

<!-- flow-allow: Sync.Unsafe.bridge-kyo-api; names the kyo-core public API in a code-pattern description for the isSubtypeOf extension -->
  Pattern (the extension `isSubtypeOf` in Tasty.scala line 1091): wrap budget=64 call in Sync.Unsafe.defer because the public extension is currently a pure `Boolean` return.

  ```scala
  // BEFORE
      extension (t: Type)
          def isSubtypeOf(other: Type)(using cp: Classpath): Boolean =
              kyo.internal.tasty.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
  // AFTER
      extension (t: Type)
          def isSubtypeOf(other: Type)(using cp: Classpath, AllowUnsafe): Boolean =
              kyo.internal.tasty.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
  ```

  (Phase 15 further changes the return type to `SubtypeVerdict`; this phase changes only the proof propagation, not the return type. Phase 15 inherits the `(using AllowUnsafe)` on the extension.)

### Files to delete

None.

### Public API additions

None (signature suffix change, not new methods).

### Public API modifications

Every accessor listed in design `## Symbol accessor signatures take (using AllowUnsafe)` and `## Classpath pure accessors take (using AllowUnsafe)` and `## ClasspathRef accessors take (using AllowUnsafe)`: signature gains `(using AllowUnsafe)`. The before/after is in each file's code block above. `Symbol.TastyOrigin.addrMap` becomes `private[kyo]`. `Classpath.open` one-arg variant delegates explicitly to two-arg form.

### Tests

Total: 6. Test files: `TastyTest.scala` (new), `TastySymbolTest.scala` (extend if exists; create if not), `ClasspathRefDedupTest.scala` (extend).

1. `TastyTest.scala`: signature scan
   - Given: the source `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`.
   - When: the test reads the file and greps for `def (fullName|isPackageObject|scaladoc|position|declaredType|parents|typeParams|declarations|companion|asString)`.
   - Then: every matched signature line contains the literal `(using AllowUnsafe)` substring; AND zero occurrences of `import AllowUnsafe.embrace.danger` exist inside the bodies of these accessors (verified by extracting the body and grepping).
   - Pins: INV-001.

2. `TastyTest.scala`: addrMap not public
   - Given: a test that attempts to import `kyo.Tasty.Symbol.TastyOrigin` and call `.addrMap` from `package other`.
   - When: the test compiles.
   - Then: compilation fails with `private[kyo]` access error message AND the same call inside `package kyo.internal.tasty.symbol` succeeds.
   - Pins: INV-011.

3. `TastySymbolTest.scala`: `fullName` accessor reads correct FQN under explicit proof
   - Given: a Symbol for `scala.Predef` obtained via `Classpath.open` then `cp.findClass("scala.Predef").get`.
   - When: the test computes `sym.fullName.asString` with `given AllowUnsafe = AllowUnsafe.embrace.danger` in scope.
   - Then: the returned String equals `"scala.Predef"`.
   - Pins: INV-001 caller-side; behavioral target-state semantics for `fullName`.

<!-- flow-allow: Sync.Unsafe.bridge-kyo-api; acceptance criterion citing the API as a negative invariant (zero allocation) -->
4. `TastySymbolTest.scala`: accessors do not allocate a `Sync.Unsafe.defer` closure per call
   - Given: a Symbol `sym` for `scala.Predef`; the test sets up an `AllocationCounter` (custom helper using `java.lang.management.ThreadMXBean`).
   - When: the test calls `sym.fullName`, `sym.parents`, `sym.declarations` each 10000 times in a tight loop.
   - Then: total allocated bytes between the start and end snapshot equals 0 plus the underlying `OnceCell.get()` allocation budget (verified by comparison against a baseline run that calls only `OnceCell.get()` directly the same number of times).
   - Pins: INV-002, steering performance-non-regression rule.

5. `ClasspathRefDedupTest.scala`: `ClasspathRef.get` requires explicit proof
   - Given: a `ClasspathRef` with an assigned Classpath.
   - When: the test calls `ref.get()` once with `given AllowUnsafe = AllowUnsafe.embrace.danger` and once without.
   - Then: the proof-in-scope call returns the assigned `Classpath` instance; the no-proof call fails to compile with the message `no given instance of type kyo.AllowUnsafe was found`.
   - Pins: INV-001.

6. `TastyTest.scala`: `Classpath.open(roots)` delegates to `(roots, strict=false)`
   - Given: a stub `ClasspathOrchestrator.open` that records its arguments to a thread-local capture buffer.
   - When: the test calls `Tasty.Classpath.open(Seq("/tmp/x"))` then inspects the capture.
   - Then: the capture records exactly one call with `strict = false`.
   - Pins: INV-025.

### Consumed invariants

None (this phase produces INV-001, INV-002, INV-011, INV-025).

### Produced invariants

- INV-001: AllowUnsafe routine-accessor signatures take `(using AllowUnsafe)` with no `import danger` inside the body.
<!-- flow-allow: Sync.Unsafe.bridge-kyo-api; INV-002 references zero allocation closures as a negative invariant about the API -->
- INV-002: Cold-load and warm-cache paths allocate zero `Sync.Unsafe.defer` closures per Symbol or Classpath accessor call.
- INV-011: `Symbol.TastyOrigin.addrMap` is not publicly accessible.
- INV-025: The no-strict `Classpath.open(roots)` overload delegates by name to the canonical `Classpath.open(roots, strict)`.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyTest kyo.TastySymbolTest kyo.ClasspathRefDedupTest'`. JS / Native equivalents.

estimated_loc: 720.
oversize_justified: "Atomic cutover: every accessor signature must change together with every caller in a single compile-and-test-green unit; splitting would leave the module not compiling between phases. Q-006 confirms 159 callsites updated atomically."

---

## Phase 3: Reject malformed binary inputs

Depends on: none. (Independent of Phase 2 because the input-validation edits happen inside unpickler internals already running in `import danger` initialization contexts per §839 case 3.)

Adds bounds checks to `Varint.readLongNat`, `Varint.readNat`, `ByteView.subView`, `NameUnpickler` indexed name resolution, `SectionIndex` name resolution, `Interner.bytesEqual`. Each rejects out-of-bounds inputs with `TastyError.MalformedSection(name, reason, byteOffset)` rather than `ArrayIndexOutOfBoundsException`. Byte-offset enrichment lands in Phase 14; for this phase the error is `TastyError.MalformedSection(name, reason)` and Phase 14 adds the `byteOffset: Long` field to every callsite.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala` `readNat`, `readLongNat`: cap continuation-byte count to 5 (Int) or 10 (Long); on overflow throw `MalformedVarintException` (new local exception type) carrying byte cursor; caller catches and converts to `TastyError.MalformedSection`.

  ```scala
  // BEFORE
      def readLongNat(view: ByteView): Long =
          var b = 0L
          var x = 0L
          while
              b = view.readByte() & 0xffL
              x = (x << 7) | (b & 0x7fL)
              (b & 0x80L) == 0L
          do ()
          end while
          x
      end readLongNat
  // AFTER
      def readLongNat(view: ByteView): Long =
          var b      = 0L
          var x      = 0L
          var bytes  = 0
          while
              if bytes >= 10 then
                  throw new Varint.MalformedVarintException(
                      view.position,
                      "varint: continuation runs past 10 bytes (Long overflow)"
                  )
              b = view.readByte() & 0xffL
              x = (x << 7) | (b & 0x7fL)
              bytes += 1
              (b & 0x80L) == 0L
          do ()
          end while
          x
      end readLongNat
  ```

  Same shape for `readNat`, capped at 5 bytes (Int).

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala` `subView` (line 56): bounds-check `from`, `until` against `[0, bytes.length]`.

  ```scala
  // BEFORE
          override def subView(from: Int, until: Int): ByteView.Heap =
              new Heap(bytes, from, until)
  // AFTER
          override def subView(from: Int, until: Int): ByteView.Heap =
              if from < 0 || until < from || until > bytes.length then
                  throw new ArrayIndexOutOfBoundsException(
                      s"ByteView.subView: from=$from until=$until length=${bytes.length}"
                  )
              new Heap(bytes, from, until)
  ```

  Also: `ByteView.Heap.readByte` (line 72) already checks `cursor >= end`; add a parallel check that `cursor + len` does not wrap negative inside any caller that uses `view.position + len` patterns.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/NameUnpickler.scala` (lines 73-179): every indexed read `buf(prefix)`, `buf(selector)`, `buf(separator)`, `buf(underlying)`, `buf(original)`, `buf(target)`, `buf(resultSig)`, `buf(ps)` is preceded by a bounds check; out-of-range reads throw `MalformedSection`.

  ```scala
  // BEFORE
                  case TastyFormat.NameTags.QUALIFIED =>
                      val end      = view.readEnd()
                      val prefix   = view.readNat()
                      val selector = view.readNat()
                      view.goto(end)
                      val s = buf(prefix).asString + "." + buf(selector).asString
                      buf += internString(interner, s)
  // AFTER
                  case TastyFormat.NameTags.QUALIFIED =>
                      val end      = view.readEnd()
                      val prefix   = view.readNat()
                      val selector = view.readNat()
                      view.goto(end)
                      if prefix >= buf.length || selector >= buf.length || prefix < 0 || selector < 0 then
                          throw new ArrayIndexOutOfBoundsException(
                              s"QUALIFIED nameRef out of range: prefix=$prefix selector=$selector tableSize=${buf.length}"
                          )
                      val s = buf(prefix).asString + "." + buf(selector).asString
                      buf += internString(interner, s)
  ```

  Apply the same pattern at the EXPANDED, EXPANDPREFIX, UNIQUE, DEFAULTGETTER, SUPERACCESSOR, INLINEACCESSOR, OBJECTCLASS, BODYRETAINER, SIGNED, TARGETSIGNED cases. The outer `read(view, interner)` already catches `ArrayIndexOutOfBoundsException` and maps to `TastyError.MalformedSection("Names", "unexpected end of name table")`; the messages from the new checks propagate via the exception text.

  Additionally: SIGNED and TARGETSIGNED `ps` index reads at lines 144, 162: bounds-check `ps` against `buf.length` before calling `buf(ps).asString`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/SectionIndex.scala` `readSync` (line 54): bounds-check `nameRef` against `names.length` before calling `names(nameRef).asString`.

  ```scala
  // BEFORE
      private def readSync(view: ByteView, names: Array[Tasty.Name]): SectionIndex =
          val builder = Map.newBuilder[String, (Int, Int)]
          while view.remaining > 0 do
              val nameRef    = view.readNat() // 0-based index into names
              val sectionLen = view.readNat() // byte count of payload
              val offset     = view.position  // payload starts here
              val name       = names(nameRef).asString
              builder += (name -> (offset, sectionLen))
              view.goto(offset + sectionLen) // skip payload
          end while
          new SectionIndex(builder.result())
      end readSync
  // AFTER
      private def readSync(view: ByteView, names: Array[Tasty.Name])(using AllowUnsafe): SectionIndex =
          val builder = Map.newBuilder[String, (Int, Int)]
          while view.remaining > 0 do
              val nameRef    = view.readNat()
              val sectionLen = view.readNat()
              val offset     = view.position
              if nameRef < 0 || nameRef >= names.length then
                  throw new ArrayIndexOutOfBoundsException(
                      s"SectionIndex: nameRef=$nameRef out of range (names.length=${names.length}) at byte ${view.position}"
                  )
              if sectionLen < 0 then
                  throw new ArrayIndexOutOfBoundsException(
                      s"SectionIndex: negative section length $sectionLen at byte ${view.position}"
                  )
              val name = names(nameRef).asString
              builder += (name -> (offset, sectionLen))
              view.goto(offset + sectionLen)
          end while
          new SectionIndex(builder.result())
      end readSync
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala` `bytesEqual` (lines 125-134): verify `offset + length <= bytes.length` before slice comparison.

  ```scala
  // BEFORE
      private def bytesEqual(entry: Interner.Entry, bytes: Array[Byte], offset: Int, length: Int): Boolean =
          entry.bytes.length == length && {
              var i  = 0
              var eq = true
              while eq && i < length do
                  if entry.bytes(i) != bytes(offset + i) then eq = false
                  i += 1
              eq
          }
      end bytesEqual
  // AFTER
      private def bytesEqual(entry: Interner.Entry, bytes: Array[Byte], offset: Int, length: Int): Boolean =
          if offset < 0 || length < 0 || offset + length > bytes.length || offset + length < 0 then
              throw new ArrayIndexOutOfBoundsException(
                  s"Interner.bytesEqual: offset=$offset length=$length bytes.length=${bytes.length}"
              )
          entry.bytes.length == length && {
              var i  = 0
              var eq = true
              while eq && i < length do
                  if entry.bytes(i) != bytes(offset + i) then eq = false
                  i += 1
              eq
          }
      end bytesEqual
  ```

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 8. Test files: `VarintTest.scala` (new, prefix `Varint`), `ByteViewTest.scala` (extend), `NameUnpicklerTest.scala` (extend or new), `SectionIndexTest.scala` (new, prefix `SectionIndex`), `InternerTest.scala` (extend).

1. `VarintTest.scala`: `readNat` rejects > 5-byte continuation
   - Given: a `ByteView.Heap` over `Array.fill(6)(0x00.toByte)` (six continuation bytes, no stop bit).
   - When: the test calls `Varint.readNat(view)`.
   - Then: a `Varint.MalformedVarintException` is thrown with message containing `"continuation runs past 5"`.
   - Pins: INV-010, B4.

2. `VarintTest.scala`: `readLongNat` rejects > 10-byte continuation
   - Given: a `ByteView.Heap` over `Array.fill(11)(0x00.toByte)`.
   - When: the test calls `Varint.readLongNat(view)`.
   - Then: a `Varint.MalformedVarintException` is thrown with message containing `"continuation runs past 10"`.
   - Pins: INV-010, B4.

3. `VarintTest.scala`: `readLongNat` exact 10-byte continuation with terminator succeeds
   - Given: a `ByteView.Heap` over `Array(0x01, 0x01, ..., 0x01, 0x81)` (9 continuation bytes value 1, terminator 0x81 = stop+1).
   - When: the test calls `Varint.readLongNat(view)`.
   - Then: the returned Long equals the bit-pattern shift of nine 7-bit groups of `0x01` plus the final `0x01`.
   - Pins: B4 boundary condition.

4. `ByteViewTest.scala`: `subView` rejects negative `from`
   - Given: a `ByteView.Heap` over a 10-byte array; arguments `from = -1`, `until = 5`.
   - When: the test calls `view.subView(-1, 5)`.
   - Then: `ArrayIndexOutOfBoundsException` is thrown with message containing `"from=-1"`.
   - Pins: INV-010, B7.

5. `ByteViewTest.scala`: `subView` rejects `until > length`
   - Given: a `ByteView.Heap` over a 10-byte array; arguments `from = 0`, `until = 11`.
   - When: the test calls `view.subView(0, 11)`.
   - Then: `ArrayIndexOutOfBoundsException` is thrown with message containing `"until=11"`.
   - Pins: INV-010, B7.

6. `NameUnpicklerTest.scala`: QUALIFIED with out-of-range prefix returns `MalformedSection`
   - Given: a synthetic name-table byte stream of one `UTF8` entry then a `QUALIFIED` entry whose `prefix` Nat encodes the value 99 (no such index).
   - When: the test runs `NameUnpickler.read(view, interner)` via `Abort.run`.
   - Then: the result is `Result.Failure(TastyError.MalformedSection("Names", _))` where the reason string contains `"QUALIFIED nameRef out of range: prefix=99"`.
   - Pins: INV-010, B1.

7. `SectionIndexTest.scala`: section nameRef out-of-range returns `MalformedSection`
   - Given: a synthetic section-index byte stream whose first entry's `nameRef` encodes 99; `names.length == 3`.
   - When: the test runs `SectionIndex.read(view, names)` via `Abort.run`.
   - Then: the result is `Result.Failure(TastyError.MalformedSection("SectionIndex", reason))` where the reason contains `"nameRef=99 out of range"`.
   - Pins: INV-010, C4.

8. `InternerTest.scala`: `bytesEqual` rejects `offset + length > bytes.length`
   - Given: an interner with one inserted entry of bytes `[1, 2, 3]`; a caller bytes array of length 5; call args `offset = 4`, `length = 2`.
   - When: the test triggers a `bytesEqual` comparison via `interner.intern(bytes, 4, 2)` (the comparison runs against existing entries in the same shard slot).
   - Then: `ArrayIndexOutOfBoundsException` thrown with message containing `"offset=4 length=2 bytes.length=5"`.
   - Pins: INV-010, B10.

### Consumed invariants

None.

### Produced invariants

- INV-010: `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex`, `Interner` reject out-of-bounds reads with `TastyError.MalformedSection`.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.VarintTest kyo.ByteViewTest kyo.NameUnpicklerTest kyo.SectionIndexTest kyo.InternerTest'`. JS / Native equivalents.

estimated_loc: 380.

---

## Phase 4: Handle 64-bit JAR offsets

Depends on: none.

Replaces every `.toInt` truncation in `JarCentralDirectory` and `JarMappedReader` with `Long` arithmetic. Adds Zip64 EOCD locator detection. Widens `MappedByteView` field types and accessor return / parameter shapes. Rejects truncated CEN records via `MalformedSection`.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala` lines 140, 142, 174, 189, 342, 345, 526, 560, 570: replace `.toInt` truncations.

  ```scala
  // BEFORE (line 140)
          val cenBufSize = (eocdOffset - cenOffset).toInt.max(0)
          val cenBuf     = new Array[Byte](cenBufSize)
  // AFTER
          val cenBufSize: Long = (eocdOffset - cenOffset).max(0L)
          if cenBufSize > Int.MaxValue then
              throw new TastyError.MalformedSection.Toss(
                  "jar",
                  s"central directory size $cenBufSize exceeds 2GB; Zip64 required"
              )
          val cenBuf = new Array[Byte](cenBufSize.toInt)
  ```

  Apply the analogous shape to each of the nine cited sites: arithmetic in `Long`, explicit overflow check before any narrowing.

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala` `parseAllEntries`: detect truncated record (declared length exceeds remaining bytes) and emit `MalformedSection`.

  ```scala
  // BEFORE
          while pos < cenBuf.length do
              val nameLen = readShort(cenBuf, pos + 28) & 0xffff
              val record  = readRecord(cenBuf, pos, nameLen, ...)
              pos += record.size
  // AFTER
          while pos < cenBuf.length do
              val nameLen = readShort(cenBuf, pos + 28) & 0xffff
              val extraLen = readShort(cenBuf, pos + 30) & 0xffff
              val commentLen = readShort(cenBuf, pos + 32) & 0xffff
              val recordSize = 46 + nameLen + extraLen + commentLen
              if pos + recordSize > cenBuf.length then
                  throw new TastyError.MalformedSection.Toss(
                      "jar",
                      s"truncated CEN record at $pos: declared size $recordSize exceeds remaining ${cenBuf.length - pos}"
                  )
              val record = readRecord(cenBuf, pos, nameLen, ...)
              pos += recordSize
  ```

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala`: add Zip64 EOCD locator detection in the EOCD search loop. New private method `findZip64Eocd(buf: Array[Byte], eocdLocatorOffset: Long): Maybe[Long]` returns the Zip64 EOCD record offset if a Zip64 EOCD locator is present.

  ```scala
  // ADDED (after the EOCD search)
      private def findZip64Eocd(buf: Array[Byte], eocdLocatorOffset: Long): Maybe[Long] =
          val sig = readInt32LE(buf, (eocdLocatorOffset - 20).toInt)
          if sig == 0x07064b50 then
              Present(readInt64LE(buf, (eocdLocatorOffset - 20 + 8).toInt))
          else
              Absent
  ```

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala` lines 65, 72, 85: replace `entry.lfhOffset.toInt + 30 + nameLen + extraLen` arithmetic with `Long` and narrow at the boundary with explicit overflow check.

  ```scala
  // BEFORE
              val dataOffset = entry.lfhOffset.toInt + 30 + nameLen + extraLen
              channel.read(dataOffset, ...)
  // AFTER
              val dataOffset: Long = entry.lfhOffset + 30L + nameLen.toLong + extraLen.toLong
              if dataOffset < 0L || dataOffset > channel.size() then
                  throw new TastyError.MalformedSection.Toss(
                      "jar",
                      s"LFH dataOffset $dataOffset out of range (channel.size=${channel.size()})"
                  )
              channel.read(dataOffset, ...)
  ```

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala` lines 35, 46, 50, 53, 56, 58: widen Int returns / parameters to Long.

  ```scala
  // BEFORE
      def peekByte(at: Int): Byte = ...
      def readByte(): Byte =
          val b = buf.get(cursor.toInt)
      def readEnd(): Int =
          val len = Varint.readNat(this)
          cursor.toInt + len
      def subView(from: Int, until: Int): MappedByteView =
          new MappedByteView(buf, from.toLong, until.toLong, closed)
      def goto(addr: Int): Unit = cursor = addr.toLong
      def remaining: Int = (end - cursor).toInt
      def position: Int = cursor.toInt
  // AFTER
      def peekByte(at: Long): Byte = ...
      def readByte(): Byte =
          if cursor > Int.MaxValue then
              // MappedByteBuffer.get(int) is Int-indexed; segment-cross requires segmented view.
              throw new IllegalStateException(
                  s"MappedByteView cursor $cursor exceeds Int.MaxValue; mmap segment overflow"
              )
          val b = buf.get(cursor.toInt)
      def readEnd(): Long =
          val len = Varint.readNat(this).toLong
          cursor + len
      def subView(from: Long, until: Long): MappedByteView =
          new MappedByteView(buf, from, until, closed)
      def goto(addr: Long): Unit = cursor = addr
      def remaining: Long = end - cursor
      def position: Long = cursor
  ```

  This breaks the `ByteView` trait's `Int`-typed contract; trait `ByteView` adds Long-aware methods alongside the Int versions and the Int versions delegate via `.toIntExact`. The trait edit:

  ```scala
  // BEFORE (ByteView.scala lines 30-42)
      def readEnd(): Int
      def subView(from: Int, until: Int): ByteView
      def goto(addr: Int): Unit
      def remaining: Int
      def position: Int
  // AFTER
      def readEnd(): Long
      def subView(from: Long, until: Long): ByteView
      def goto(addr: Long): Unit
      def remaining: Long
      def position: Long
      // Legacy Int wrappers (delegate via toIntExact for callers that have not been migrated yet).
      final def readEndInt: Int = Math.toIntExact(readEnd())
      final def positionInt: Int = Math.toIntExact(position)
      final def remainingInt: Int = Math.toIntExact(remaining)
      final def gotoInt(addr: Int): Unit = goto(addr.toLong)
      final def subViewInt(from: Int, until: Int): ByteView = subView(from.toLong, until.toLong)
  ```

  Heap `subView`, `goto`, `position`, `remaining`, `readEnd` widen accordingly. Callers update: every call to `view.position`, `view.goto`, `view.remaining`, `view.readEnd`, `view.subView` that fits in an Int continues to work because Int auto-widens to Long; explicit `.toInt` callers convert via `Math.toIntExact`.

### Files to delete

None.

### Public API additions

None (internal).

### Public API modifications

None (internal).

### Tests

Total: 6. Test files: `JarCentralDirectoryTest.scala` (extend), `JvmFileSourceTest.scala` (extend), `MappedByteViewTest.scala` (new, prefix `MappedByteView`).

1. `JarCentralDirectoryTest.scala`: Zip64 EOCD locator detected
   - Given: a synthetic JAR whose EOCD locator at offset N carries the Zip64 EOCD locator signature `0x07064b50`; the Zip64 EOCD points to a central directory at byte offset `2_500_000_000L`.
   - When: the test calls `JarCentralDirectory.read(jarPath)`.
   - Then: the parsed central directory's first entry's `lfhOffset` equals `2_500_000_000L` (Long-valued, not truncated to Int).
   - Pins: INV-012, C1.

2. `JarCentralDirectoryTest.scala`: truncated CEN record returns `MalformedSection`
   - Given: a JAR whose CEN record at offset 0 declares `nameLen = 1000` but only 100 bytes follow.
   - When: the test calls `JarCentralDirectory.read(jarPath)` and runs `Abort.run`.
   - Then: the result is `Result.Failure(TastyError.MalformedSection("jar", reason))` where reason contains `"truncated CEN record"`.
   - Pins: INV-012, B11.

3. `JarCentralDirectoryTest.scala`: central directory size > 2GB without Zip64 returns `MalformedSection`
   - Given: a synthetic JAR with EOCD claiming a central directory of size `2_500_000_000L` and no Zip64 locator.
   - When: the test calls `JarCentralDirectory.read(jarPath)`.
   - Then: result is `Result.Failure(TastyError.MalformedSection("jar", reason))` where reason contains `"exceeds 2GB"`.
   - Pins: INV-012, B3.

4. `JvmFileSourceTest.scala`: 64-bit LFH offset round-trip
   - Given: a Zip64 JAR with an entry whose `lfhOffset` equals `Int.MaxValue + 1L`.
   - When: the test calls `JarMappedReader.read(jar, entry)`.
   - Then: the returned bytes equal the entry's actual compressed content (verified via `Arrays.equals` against the source bytes).
   - Pins: INV-012, B2.

5. `MappedByteViewTest.scala`: position is Long-typed
   - Given: a `MappedByteView` with `start = 0L`, `end = 5_000_000_000L`, `cursor = 3_000_000_000L`.
   - When: the test calls `view.position`.
   - Then: the returned value equals `3_000_000_000L`.
   - Pins: INV-018, B6.

6. `MappedByteViewTest.scala`: `readByte` past Int.MaxValue raises `IllegalStateException` (current MappedByteBuffer limitation)
   - Given: a `MappedByteView` with `cursor = Int.MaxValue.toLong + 1L`.
   - When: the test calls `view.readByte()`.
   - Then: `IllegalStateException` thrown with message containing `"mmap segment overflow"`.
   - Pins: INV-018, B6 documented limitation.

### Consumed invariants

None.

### Produced invariants

- INV-012: JAR offsets are 64-bit; Zip64 archives correctly parsed; no Int truncation past 2GB.
- INV-018: `MappedByteView` accessors that may address > 2GB regions return or accept `Long` offsets.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm]

Reason: JarCentralDirectory and MappedByteView live in `jvm/` only; JS uses `JsFileSource` and Native uses `NativeFileSource`. Zip64 handling is a JVM-side concern.

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JarCentralDirectoryTest kyo.JvmFileSourceTest kyo.MappedByteViewTest'`.

estimated_loc: 480.

---

## Phase 5: Atomicize resource lifecycle

Depends on: Phase 2 (uses the new `(using AllowUnsafe)` on `Classpath.transitionToReady` and `Classpath.close`).

`JvmFileSource.activePool.set` (line 152) and `Scope.ensure` registration (line 155) become a single atomic block via the `Scope.acquireRelease` idiom. `JarMappedReader.channel.map` failure (line 125) does not retain a channel reference in the thrown exception.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JvmFileSource.scala` lines 149-156: wrap `activePool.set(pool)` and the Scope finalizer in a single `Scope.acquireRelease`.

  ```scala
  // BEFORE
          val pool = new JarPool(...)
          activePool.set(pool)
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
          Scope.ensure(Sync.defer(pool.close())).andThen {
              ...
          }
  // AFTER
          Scope.acquireRelease(
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
              acquire = Sync.defer:
                  val pool = new JarPool(...)
                  activePool.set(pool)
                  pool
              ,
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
              release = pool => Sync.defer(pool.close())
          ).map { pool =>
              ...
          }
  ```

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala` lines 119-132: ensure no channel reference leaks via thrown exception.

  ```scala
  // BEFORE
          val raf = new RandomAccessFile(jarPath, "r")
          try
              val channel = raf.getChannel
              try
                  val mapped = channel.map(MapMode.READ_ONLY, 0L, raf.length())
                  ...
              finally channel.close()
          finally raf.close()
  // AFTER
          val raf = new RandomAccessFile(jarPath, "r")
          var mapped: MappedByteBuffer = null
          try
              val channel = raf.getChannel
              try
                  mapped = channel.map(MapMode.READ_ONLY, 0L, raf.length())
              catch
                  case ex: java.io.IOException =>
                      throw new java.io.IOException(s"map failed for $jarPath: ${ex.getMessage}")
              finally channel.close()
          finally raf.close()
          mapped
  ```

  The inner catch replaces the IOException with a fresh exception that names the file path; the channel reference is not propagated.

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 3. Test files: `JvmFileSourceTest.scala` (extend).

1. `JvmFileSourceTest.scala`: pool registration atomic on Scope.ensure
   - Given: a synthetic `Scope.ensure` that throws on the first invocation.
   - When: the test calls `JvmFileSource.openPool(...)` and observes whether `activePool` retains the partially-set pool.
   - Then: after the throw, `activePool.get()` returns the prior pool (or `null` if first call), AND the new pool's `close()` has been called via the acquireRelease release branch.
   - Pins: INV-002 boundary; B14 atomicity.

2. `JvmFileSourceTest.scala`: `channel.map` failure does not leak channel reference
   - Given: a non-existent JAR path that triggers `IOException` inside `channel.map(...)`.
   - When: the test calls `JarMappedReader.read(jarPath)` and inspects the thrown exception.
   - Then: the exception is `java.io.IOException` whose message contains `"map failed"` and whose `getStackTrace` contains no `java.nio.channels.FileChannel` class reference; AND `raf.getFD().valid()` returns `false` after the exception.
   - Pins: B15 channel reference hygiene.

3. `JvmFileSourceTest.scala`: pool exhaustion under scope close
   - Given: a `JvmFileSource` with `maxPoolSize = 1`; ten concurrent fibers each call `withReader(jarPath)`; the outer Scope closes mid-execution.
   - When: each fiber completes; the test inspects the pool's `activeCount`.
   - Then: `activeCount` equals 0 (every reader returned to pool or closed by Scope finalizer); no `IllegalStateException` from any fiber's exit path.
   - Pins: T8 resource cleanup; B14.

### Consumed invariants

- INV-001 (uses the new `(using AllowUnsafe)` accessor surface from Phase 2).

### Produced invariants

None (this phase strengthens existing resource invariants; the existing INV-002 and INV-018 still hold).

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm]

Reason: JvmFileSource and JarMappedReader live in `jvm/`; the corresponding JS / Native FileSource implementations have their own resource lifecycle (no mmap, no JAR pool).

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JvmFileSourceTest'`.

estimated_loc: 220.

---

## Phase 6: Enforce OnceCell idempotence

Depends on: Phase 2 (consumes the new `(using AllowUnsafe)` surface on `OnceCell.get`).

Adds a debug-mode duplicate-result detection flag to `OnceCell` that flags non-idempotent init lambdas. Canonicalizes the three `asInstanceOf` comments at lines 37, 41, 45 to the `// Unsafe: ...` prefix per CONTRIBUTING.md §415. Documents the idempotence requirement on `init()` in scaladoc.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala` lines 24-49: add a `debugIdempotent` flag readable via a system property; on CAS-loss-and-stored-value-differs, throw `IllegalStateException`. Canonicalize `// Unsafe:` comments.

  ```scala
  // BEFORE
  final class OnceCell[A](init: () => A):
      private val ref = new AtomicReference[AnyRef](OnceCell.Unset)

      def get()(using AllowUnsafe): A =
          val cached = ref.get()
          if cached ne OnceCell.Unset then
              cached.asInstanceOf[A]
          else
              val v = init().asInstanceOf[AnyRef]
              ref.compareAndSet(OnceCell.Unset, v)
              ref.get().asInstanceOf[A]
          end if
      end get
  // AFTER
  /** A lazy one-time computation cell.
    *
    * REQUIRES IDEMPOTENT INIT: if two threads race on `get()`, both run `init()` redundantly; the design assumes `init() == init()` modulo
    * equality. Non-idempotent init produces undefined results.
    *
    * Debug mode: setting `-Dkyo.tasty.OnceCell.debug=true` enables a runtime check that flags non-idempotent init when the CAS-losing
    * thread's computed value differs from the CAS-winning thread's stored value (compared via `==`). The check throws `IllegalStateException`
    * naming the cell.
    */
  final class OnceCell[A](init: () => A):
      private val ref = new AtomicReference[AnyRef](OnceCell.Unset)

      def get()(using AllowUnsafe): A =
          val cached = ref.get()
          if cached ne OnceCell.Unset then
              // Unsafe: AnyRef-sentinel pattern; ne-Unset guarantees the stored value is A.
              cached.asInstanceOf[A]
          else
              // Unsafe: we store A as AnyRef to coexist with the Unset sentinel in AtomicReference[AnyRef].
              val v = init().asInstanceOf[AnyRef]
              val won = ref.compareAndSet(OnceCell.Unset, v)
              if !won && OnceCell.debugIdempotent then
                  // Unsafe: same sentinel pattern; ref.get() now holds the CAS-winning value.
                  val winner = ref.get()
                  if winner != v then
                      throw new IllegalStateException(
                          s"OnceCell idempotence violated: init() returned $v but stored value is $winner"
                      )
              // Unsafe: same sentinel pattern; either CAS won (ref holds v) or lost (ref holds another thread's stored value).
              ref.get().asInstanceOf[A]
          end if
      end get

  end OnceCell

  object OnceCell:
      private val Unset: AnyRef = new AnyRef
      private[kyo] val debugIdempotent: Boolean =
          java.lang.System.getProperty("kyo.tasty.OnceCell.debug", "false").equalsIgnoreCase("true")
  end OnceCell
  ```

### Files to delete

None.

### Public API additions

None (OnceCell is internal).

### Public API modifications

None.

### Tests

Total: 4. Test files: `OnceCellTest.scala` (new, prefix `OnceCell`).

1. `OnceCellTest.scala`: first call returns init result
   - Given: an `OnceCell[Int](() => 42)`.
   - When: a single thread calls `cell.get()` with `given AllowUnsafe = AllowUnsafe.embrace.danger`.
   - Then: the returned value equals `42`.
   - Pins: INV-009 base case.

2. `OnceCellTest.scala`: subsequent calls return the cached value
   - Given: an `OnceCell[Int](() => { counter.incrementAndGet(); 7 })` where `counter` is an external `AtomicInteger` starting at 0.
   - When: the test calls `cell.get()` ten times sequentially.
   - Then: every call returns `7` AND `counter.get()` equals `1`.
   - Pins: INV-009 caching property.

3. `OnceCellTest.scala`: concurrent first-callers see consistent value
   - Given: an `OnceCell[String](() => "x" + java.lang.System.nanoTime().toString)` (intentionally non-idempotent); 32 fibers race on `cell.get()`.
   - When: every fiber records its returned String.
   - Then: every fiber's String equals the others (reference-equal), proving CAS-winner semantics.
   - Pins: INV-009.

4. `OnceCellTest.scala`: debug-mode flags non-idempotent init
   - Given: `OnceCell.debugIdempotent` is true (set via system property in the test fixture); an `OnceCell[Int](() => { val n = counter.incrementAndGet(); n })` with `counter` starting at 0.
   - When: 8 fibers race on `cell.get()`.
   - Then: at least one fiber's call throws `IllegalStateException` whose message contains `"idempotence violated"`.
   - Pins: INV-009 debug-mode detection; A3 canonical Unsafe comments.

### Consumed invariants

- INV-001 (consumes the `(using AllowUnsafe)` signature on `OnceCell.get`).

### Produced invariants

- INV-009: `OnceCell.init` lambdas are idempotent; concurrent first-callers compute the same value modulo equality (enforced via doc + debug-mode detection).

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.OnceCellTest'`. JS / Native equivalents.

estimated_loc: 140.

---

## Phase 7: Harden Interner concurrency

Depends on: Phase 3 (consumes the `bytesEqual` bounds check from Phase 3).

Widens `Interner.growShard` synchronization window so the double-checked observe never crosses the un-synchronized boundary. Adds `PerfCounters.snapshot()` for a coherent multi-counter read; `reset()` becomes an atomic snapshot-then-zero.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala` lines 67-92: widen the synchronized window. The `internInShard` loop currently re-reads `shardRef.get()` after each grow without re-synchronizing; widen so a grow-then-insert sequence runs under the same monitor.

  ```scala
  // BEFORE
      private def internInShard(...): Interner.Entry =
          val table = shardRef.get()
          ...
          while ret eq null do
              val existing = table.get(slot)
              if existing eq null then
                  if loadCounter.get() * 4 >= len * 3 then
                      growShard(shardRef, loadCounter, len)
                      return internInShard(shardRef, loadCounter, hash, bytes, offset, length)
                  end if
                  ...
  // AFTER
      private def internInShard(...): Interner.Entry =
          val table = shardRef.get()
          ...
          while ret eq null do
              val existing = table.get(slot)
              if existing eq null then
                  if loadCounter.get() * 4 >= len * 3 then
                      // Re-acquire the shard monitor and re-check whether the grown table now has the entry.
                      // The grow-and-recheck pair runs under the same monitor as growShard itself, eliminating
                      // the prior unsynchronized re-read window.
                      shardRef.synchronized {
                          if shardRef.get() eq table then
                              growShard(shardRef, loadCounter, len)
                      }
                      return internInShard(shardRef, loadCounter, hash, bytes, offset, length)
                  end if
                  ...
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/PerfCounters.scala` lines 32-45: add `snapshot()` returning a frozen view; `reset()` invokes snapshot-then-zero.

  ```scala
  // BEFORE
      def reset(): Unit =
          jarOpenCount.set(0)
          entryReadCount.set(0)
          ...
  // AFTER
      /** Frozen snapshot of every counter at one instant. */
      final case class Snapshot(
          jarOpenCount: Int,
          entryReadCount: Int,
          bytesReadTotal: Long,
          jarConstructTimeNs: Long,
          jarReadTimeNs: Long,
          tastyHeaderTimeNs: Long,
          nameUnpicklerTimeNs: Long,
          sectionIndexTimeNs: Long,
          attributeUnpicklerTimeNs: Long,
          astPass1TimeNs: Long,
          commentsUnpicklerTimeNs: Long,
          positionsUnpicklerTimeNs: Long
      )

      def snapshot(): Snapshot =
          Snapshot(
              jarOpenCount.get(),
              entryReadCount.get(),
              bytesReadTotal.get(),
              jarConstructTimeNs.get(),
              jarReadTimeNs.get(),
              tastyHeaderTimeNs.get(),
              nameUnpicklerTimeNs.get(),
              sectionIndexTimeNs.get(),
              attributeUnpicklerTimeNs.get(),
              astPass1TimeNs.get(),
              commentsUnpicklerTimeNs.get(),
              positionsUnpicklerTimeNs.get()
          )

      def reset(): Snapshot =
          val s = snapshot()
          jarOpenCount.set(0)
          entryReadCount.set(0)
          bytesReadTotal.set(0L)
          jarConstructTimeNs.set(0L)
          jarReadTimeNs.set(0L)
          tastyHeaderTimeNs.set(0L)
          nameUnpicklerTimeNs.set(0L)
          sectionIndexTimeNs.set(0L)
          attributeUnpicklerTimeNs.set(0L)
          astPass1TimeNs.set(0L)
          commentsUnpicklerTimeNs.set(0L)
          positionsUnpicklerTimeNs.set(0L)
          s
      end reset
  ```

### Files to delete

None.

### Public API additions

None.

### Public API modifications

`PerfCounters.reset` (internal): was `Unit`, now `Snapshot` (returns the pre-reset snapshot). Internal-only; no external caller.

### Tests

Total: 4. Test files: `InternerTest.scala` (extend), `PerfCountersTest.scala` (new, prefix `PerfCounters`).

1. `InternerTest.scala`: concurrent grow-and-insert under contention
   - Given: an `Interner(numShards = 2, initialShardCapacity = 4)`; 8 fibers each insert 1000 unique byte sequences with hashes hitting the same shard.
   - When: every fiber completes.
   - Then: the interner's total entry count (sum of `shardSize(0)` + `shardSize(1)`) equals 8000 AND every inserted byte sequence has a unique `Entry` reference (verified by `interner.intern(b, 0, b.length) eq interner.intern(b, 0, b.length)` for every b).
   - Pins: INV-009-adjacent (Interner is similar concurrency); B12.

2. `InternerTest.scala`: grow during contention preserves reference equality
   - Given: an `Interner(numShards = 1, initialShardCapacity = 2)` (forces frequent grows); 4 fibers race to insert byte sequence `[1, 2, 3]`.
   - When: every fiber completes.
   - Then: every fiber's returned `Entry` is reference-equal to every other fiber's `Entry`.
   - Pins: B12 invariant preservation.

3. `PerfCountersTest.scala`: `snapshot` returns a coherent view
   - Given: a thread that increments `jarOpenCount` and `entryReadCount` in a tight loop; another thread reads `snapshot()` while the loop runs.
   - When: the reader thread observes 100 snapshots.
   - Then: every snapshot satisfies `snapshot.entryReadCount >= snapshot.jarOpenCount` (writer increments jarOpenCount first, then entryReadCount; coherent read preserves the order).
   - Pins: B13.

4. `PerfCountersTest.scala`: `reset` returns the pre-reset snapshot
   - Given: `jarOpenCount.set(42)`, `entryReadCount.set(7)` ahead of time.
   - When: the test calls `PerfCounters.reset()`.
   - Then: the returned `Snapshot` has `jarOpenCount = 42` AND `entryReadCount = 7`, AND post-reset `jarOpenCount.get()` is 0.
   - Pins: B13.

### Consumed invariants

- INV-010 (uses the bounds-checked `bytesEqual` from Phase 3).

### Produced invariants

None (B12, B13 fixes strengthen existing concurrency posture; no new INV).

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.InternerTest kyo.PerfCountersTest'`. JS / Native equivalents.

estimated_loc: 250.

---

## Phase 8: Bound recursion depth

Depends on: none.

`TypeArena.internRec` adds a depth-bound guard. Pathological nested `Applied(Applied(...))` reports `TastyError.MalformedSection` instead of `StackOverflowError`. `PositionsUnpickler.lineStarts` cumulative arithmetic widens to `Long` or detects Int overflow.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/TypeArena.scala` lines 81-96: thread a `depth: Int` parameter through `internRec` and `recurse`; cap at 1024.

  ```scala
  // BEFORE
          def internRec(t: Tasty.Type): Tasty.Type =
              val key = TypeKey.of(t)
              canonical.map.get(key) match
                  case Some(canon) => canon
                  case None =>
                      inProgress.get(key) match
                          case Some(placeholder) => placeholder
                          case None =>
                              inProgress(key) = t
                              val recurInterned = recurse(t)
                              ...
  // AFTER
          def internRec(t: Tasty.Type, depth: Int = 0): Tasty.Type =
              if depth >= TypeArena.MaxDepth then
                  throw new TypeArena.DepthExceededException(
                      s"TypeArena.internRec depth ${TypeArena.MaxDepth} exceeded; pathological nesting"
                  )
              val key = TypeKey.of(t)
              canonical.map.get(key) match
                  case Some(canon) => canon
                  case None =>
                      inProgress.get(key) match
                          case Some(placeholder) => placeholder
                          case None =>
                              inProgress(key) = t
                              val recurInterned = recurse(t, depth + 1)
                              ...
  ```

  Add `object TypeArena { val MaxDepth: Int = 1024 ; class DepthExceededException(msg: String) extends RuntimeException(msg) }`. Caller catches and surfaces via `TastyError.MalformedSection`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/PositionsUnpickler.scala` line 82: detect Int overflow in cumulative arithmetic.

  ```scala
  // BEFORE
              lineStarts(k + 1) = lineStarts(k) + lineSizes(k) + 1
  // AFTER
              val nextStart: Long = lineStarts(k).toLong + lineSizes(k).toLong + 1L
              if nextStart > Int.MaxValue then
                  throw new ArrayIndexOutOfBoundsException(
                      s"PositionsUnpickler: cumulative lineStart at line ${k + 1} exceeds Int.MaxValue ($nextStart); source file too large"
                  )
              lineStarts(k + 1) = nextStart.toInt
  ```

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 4. Test files: `TypeArenaTest.scala` (extend), `PositionsUnpicklerTest.scala` (extend).

1. `TypeArenaTest.scala`: deeply nested `Applied` chain reports `MalformedSection`
   - Given: a `Tasty.Type` value `t` formed by nesting `Applied(scala.Function1, Applied(...)) ...` 2000 levels deep.
   - When: the test calls `TypeArena.canonical().merge(Map("k" -> t))` via `Abort.run`.
   - Then: result is `Result.Failure(TastyError.MalformedSection("Types", reason))` where reason contains `"depth 1024 exceeded"`.
   - Pins: INV-019, B8.

2. `TypeArenaTest.scala`: nesting at exactly MaxDepth - 1 succeeds
   - Given: a `Tasty.Type` nested exactly 1023 levels deep.
   - When: the test calls `merge`.
   - Then: result is `Result.Success(_)`; the interned type matches the input structurally.
   - Pins: B8 boundary condition.

3. `PositionsUnpicklerTest.scala`: overflow detected on very large source
   - Given: a synthetic Positions section whose cumulative `lineSize` sum exceeds `Int.MaxValue` (constructed with 1000 lines each declaring `Int.MaxValue / 1000` characters).
   - When: the test calls `PositionsUnpickler.read(view)` via `Abort.run`.
   - Then: result is `Result.Failure(TastyError.MalformedSection("Positions", reason))` where reason contains `"exceeds Int.MaxValue"`.
   - Pins: B9.

4. `PositionsUnpicklerTest.scala`: normal-sized file decodes positions correctly
   - Given: a synthetic Positions section for a 200-line source file; line k has size `100 + k`.
   - When: `PositionsUnpickler.read(view)`.
   - Then: `lineStarts(10)` equals `100 + 101 + 102 + ... + 109 + 10` (sum of line sizes 100-109 plus 10 newlines).
   - Pins: B9 baseline.

### Consumed invariants

None.

### Produced invariants

- INV-019: `TypeArena.internRec` enforces a recursion-depth cap and reports a structured error on overflow.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TypeArenaTest kyo.PositionsUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 180.

---

## Phase 9: Verify ConstantPool cross-entry kinds

Depends on: none.

`ConstantPool.entry(idx)` validates that cross-entry references match the expected pool tag: `ClassRef.nameIdx` targets Utf8, `MethodHandleRef.referenceIdx` targets MethodRef, `FieldRef.classIdx` targets ClassRef, `MethodRef.classIdx` targets ClassRef, `MethodRef.nameAndTypeIdx` targets NameAndType, `NameAndType.nameIdx` targets Utf8, `NameAndType.descriptorIdx` targets Utf8. `Utf8Lazy` accepts both `Heap` and `Mapped` `ByteView` rather than rejecting `Mapped`.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala` lines 66-102: add typed accessors `def utf8At(idx: Int): String`, `def classRefAt(idx: Int): ClassRef`, `def methodRefAt(idx: Int): MethodRef`, `def fieldRefAt(idx: Int): FieldRef`, `def nameAndTypeAt(idx: Int): NameAndType`, `def methodHandleAt(idx: Int): MethodHandle`, `def methodTypeAt(idx: Int): MethodType`, each validating the tag of the referenced entry.

  ```scala
  // BEFORE
      def entry(idx: Int): Entry =
          if idx < 1 || idx >= entries.length then
              throw new ClassfileFormatException(s"constant pool index $idx out of range")
          entries(idx)
  // AFTER
      def entry(idx: Int): Entry =
          if idx < 1 || idx >= entries.length then
              throw new ClassfileFormatException(s"constant pool index $idx out of range")
          entries(idx)

      def utf8At(idx: Int): String =
          entry(idx) match
              case e: Entry.Utf8 => e.value
              case other =>
                  throw new ClassfileFormatException(
                      s"constant pool index $idx: expected Utf8, found ${other.getClass.getSimpleName}"
                  )

      def classRefAt(idx: Int): Entry.ClassRef =
          entry(idx) match
              case e: Entry.ClassRef => e
              case other =>
                  throw new ClassfileFormatException(
                      s"constant pool index $idx: expected ClassRef, found ${other.getClass.getSimpleName}"
                  )
  ```

  Callers `ClassRef.nameIdx`, `MethodHandleRef.referenceIdx`, `FieldRef.classIdx`, `FieldRef.nameAndTypeIdx`, `MethodRef.classIdx`, `MethodRef.nameAndTypeIdx`, `NameAndType.nameIdx`, `NameAndType.descriptorIdx` switch to `utf8At` / `classRefAt` / `methodRefAt` / `fieldRefAt` / `nameAndTypeAt` / `methodHandleAt` / `methodTypeAt`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala` lines 217-223 `Utf8Lazy`: accept `Mapped` ByteView.

  ```scala
  // BEFORE
      final case class Utf8Lazy(view: ByteView.Heap, offset: Int, length: Int):
          def decode: String =
              ...
  // AFTER
      final case class Utf8Lazy(view: ByteView, offset: Int, length: Int):
          def decode: String =
              view match
                  case h: ByteView.Heap =>
                      Utf8.decode(h.allBytes, offset, length)
                  case m: ByteView.Mapped =>
                      val buf = new Array[Byte](length)
                      val savedCursor = m.position
                      m.goto(offset.toLong)
                      var i = 0
                      while i < length do
                          buf(i) = m.readByte()
                          i += 1
                      m.goto(savedCursor)
                      Utf8.decode(buf, 0, length)
  ```

### Files to delete

None.

### Public API additions

None (internal).

### Public API modifications

`ConstantPool.Utf8Lazy.view` type widens from `ByteView.Heap` to `ByteView`.

### Tests

Total: 4. Test files: `ConstantPoolTest.scala` (new, prefix `ConstantPool`).

1. `ConstantPoolTest.scala`: `utf8At` rejects ClassRef
   - Given: a constant pool with `entries[5] = ClassRef(nameIdx = 6)`.
   - When: the test calls `pool.utf8At(5)`.
   - Then: `ClassfileFormatException` thrown with message `"expected Utf8, found ClassRef"`.
   - Pins: B5.

2. `ConstantPoolTest.scala`: `classRefAt` rejects Utf8
   - Given: a constant pool with `entries[3] = Utf8("foo")`.
   - When: `pool.classRefAt(3)`.
   - Then: `ClassfileFormatException` with `"expected ClassRef, found Utf8"`.
   - Pins: B5.

3. `ConstantPoolTest.scala`: `Utf8Lazy.decode` reads from `Mapped` view
   - Given: a `Mapped` ByteView wrapping bytes `[0x66, 0x6f, 0x6f]` (UTF-8 "foo") at offset 0; `Utf8Lazy(mapped, 0, 3)`.
   - When: the test calls `lazyUtf8.decode`.
   - Then: returned String equals `"foo"`.
   - Pins: C3.

4. `ConstantPoolTest.scala`: `Utf8Lazy.decode` preserves cursor of `Mapped` view
   - Given: a `Mapped` ByteView with `cursor = 5L` at start; `Utf8Lazy(mapped, 0, 3)`.
   - When: `lazyUtf8.decode` runs; then test reads `mapped.position`.
   - Then: position returns to `5L`.
   - Pins: C3 cursor preservation.

### Consumed invariants

None.

### Produced invariants

None (B5 and C3 strengthen ConstantPool; no new INV).

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ConstantPoolTest'`. JS / Native equivalents.

estimated_loc: 200.

---

## Phase 10: Log unknown TASTy type tags

Depends on: none.

`TypeUnpickler.decodeTag` fallback (lines 593, 598) logs the unknown tag via `kyo.Log` with tag id, classfile path, and byte offset, then routes to the existing `Type.Named(makeUnresolvedSym(...))` fallback so downstream code does not crash.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TypeUnpickler.scala` lines 593-600: hook structured log on unknown tag.

  ```scala
  // BEFORE
              case other =>
                  Tasty.Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other", ctx.home))
  // AFTER
              case other =>
                  // Emit a structured warning so unknown tags surface in logs rather than as silent unresolved symbols.
                  // The log call is fire-and-forget; the decoder continues to produce a fallback Named.
                  Log.live.unsafe.warn(
                      s"TypeUnpickler: unknown TASTy type tag $other at offset ${ctx.bytePosition} in ${ctx.classfilePath}"
                  )
                  Tasty.Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other", ctx.home))
  ```

  `Log.live.unsafe.warn` is the synchronous fire-and-forget variant used inside `import danger` initialization contexts; the decode pass already runs under §839 case 3.

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 2. Test files: `TypeUnpicklerTest.scala` (extend).

1. `TypeUnpicklerTest.scala`: unknown tag logged
   - Given: a synthetic TASTy type section whose first byte encodes tag value 250 (no known case); a `Log` test backend that captures emitted messages to a list.
   - When: the test calls `TypeUnpickler.decode(view, ctx)`.
   - Then: the captured list contains exactly one warn-level message whose text contains `"unknown TASTy type tag 250"` AND the returned Type is a `Type.Named(unresolved)` whose unresolved name equals `"unknown-type-tag-250"`.
   - Pins: INV-004, M7.

2. `TypeUnpicklerTest.scala`: known tag does not emit a warning
   - Given: a synthetic type section encoding `TYPEREF` (a known tag); the Log test backend.
   - When: the test calls `TypeUnpickler.decode`.
   - Then: the captured list is empty.
   - Pins: INV-004 negative.

### Consumed invariants

None.

### Produced invariants

- INV-004: Every TASTy type tag in `TypeUnpickler.decodeTag` has either an explicit decode branch or routes through the unknown-tag warning hook.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TypeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 60.

---

## Phase 11: Decode missing classfile attributes

Depends on: Phase 9 (uses `ConstantPool.utf8At` / `classRefAt` typed accessors).

Adds `BootstrapMethods`, `NestHost`, `NestMembers`, `PermittedSubclasses`, `MethodParameters`, `RuntimeTypeAnnotations` decoders to `ClassfileUnpickler`. New Symbol-level accessor `def permittedSubclasses(using AllowUnsafe): Maybe[Chunk[Tasty.Symbol]]`; `JavaMetadata` extended with parameter names and runtime type annotations.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`: extend Symbol with `_permittedSubclasses: SingleAssign[Maybe[Chunk[Symbol]]]` and accessor.

  ```scala
  // BEFORE (Symbol class members)
          private[kyo] val _declarations: SingleAssign[Chunk[Symbol]] = new SingleAssign
  // AFTER
          private[kyo] val _declarations: SingleAssign[Chunk[Symbol]] = new SingleAssign
          private[kyo] val _permittedSubclasses: SingleAssign[Maybe[Chunk[Symbol]]] = new SingleAssign

          /** Sealed-hierarchy permitted subclasses, if this symbol is a sealed class with a `PermittedSubclasses` attribute. */
          def permittedSubclasses(using AllowUnsafe): Maybe[Chunk[Symbol]] =
              if _permittedSubclasses.isSet then _permittedSubclasses.get()
              else Maybe.Absent
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `JavaMetadata` (line 232-238): add `paramNames: Chunk[(Name, Chunk[Name])]` (method to parameter names) and `runtimeTypeAnnotations: Chunk[JavaAnnotation]`.

  ```scala
  // BEFORE
      final case class JavaMetadata(
          throwsTypes: Chunk[Type],
          annotations: Chunk[JavaAnnotation],
          enclosingMethod: Maybe[(Symbol, Name)],
          accessFlags: Int,
          recordComponents: Chunk[(Name, Type)]
      )
  // AFTER
      final case class JavaMetadata(
          throwsTypes: Chunk[Type],
          annotations: Chunk[JavaAnnotation],
          enclosingMethod: Maybe[(Symbol, Name)],
          accessFlags: Int,
          recordComponents: Chunk[(Name, Type)],
          paramNames: Chunk[(Name, Chunk[Name])] = Chunk.empty,
          runtimeTypeAnnotations: Chunk[JavaAnnotation] = Chunk.empty,
          nestHost: Maybe[Symbol] = Maybe.Absent,
          nestMembers: Chunk[Symbol] = Chunk.empty,
          bootstrapMethods: Chunk[Chunk[Int]] = Chunk.empty
      )
  ```

  Note: this case class adds defaults because steering's "no default params on internal APIs" rule applies to method APIs; case class field defaults are not the same surface (they enable forward-compatible field addition without breaking call sites). However if the strict steering reading rejects defaults here, the alternative is to add an explicit overload `JavaMetadata.apply(...all original fields...)` that fills the new fields with defaults. The plan retains defaults pending impl-time confirmation. // plan: confirm during impl whether case class field defaults are accepted by steering or whether explicit factory overloads are required.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala`: add six new match arms.

  ```scala
  // BEFORE (attribute dispatch around line 343)
          attr.name match
              case "Signature" => decodeSignature(...)
              case "InnerClasses" => decodeInnerClasses(...)
              ...
              case other => /* skip */
  // AFTER
          attr.name match
              case "Signature" => decodeSignature(...)
              case "InnerClasses" => decodeInnerClasses(...)
              ...
              case "BootstrapMethods" => decodeBootstrapMethods(view, pool, mdBuilder)
              case "NestHost" => decodeNestHost(view, pool, mdBuilder)
              case "NestMembers" => decodeNestMembers(view, pool, mdBuilder)
              case "PermittedSubclasses" => decodePermittedSubclasses(view, pool, symBuilder)
              case "MethodParameters" => decodeMethodParameters(view, pool, mdBuilder, methodName)
              case "RuntimeVisibleTypeAnnotations" | "RuntimeInvisibleTypeAnnotations" =>
                  decodeRuntimeTypeAnnotations(view, pool, mdBuilder)
              case other =>
                  Log.live.unsafe.warn(s"ClassfileUnpickler: unknown attribute '$other' at offset ${view.position}")
  ```

  New private decoder methods:

  ```scala
      private def decodeBootstrapMethods(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadataBuilder): Unit =
          val numMethods = view.readU2()
          val bootstrapMethods = new scala.collection.mutable.ArrayBuffer[Chunk[Int]]()
          var i = 0
          while i < numMethods do
              val mhIdx = view.readU2()
              val numArgs = view.readU2()
              val args = new scala.collection.mutable.ArrayBuffer[Int]()
              var j = 0
              while j < numArgs do
                  args += view.readU2()
                  j += 1
              bootstrapMethods += (Chunk(mhIdx) ++ Chunk.from(args))
              i += 1
          mdBuilder.bootstrapMethods = Chunk.from(bootstrapMethods)

      private def decodeNestHost(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadataBuilder): Unit =
          val classIdx = view.readU2()
          val classRef = pool.classRefAt(classIdx)
          val nestHostFqn = pool.utf8At(classRef.nameIdx).replace('/', '.')
          mdBuilder.nestHost = resolveLater(nestHostFqn)

      private def decodeNestMembers(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadataBuilder): Unit =
          val numClasses = view.readU2()
          val members = new scala.collection.mutable.ArrayBuffer[Tasty.Symbol]()
          var i = 0
          while i < numClasses do
              val classIdx = view.readU2()
              val classRef = pool.classRefAt(classIdx)
              val fqn = pool.utf8At(classRef.nameIdx).replace('/', '.')
              members += resolveLater(fqn).getOrElse(makeUnresolvedSym(fqn))
              i += 1
          mdBuilder.nestMembers = Chunk.from(members)

      private def decodePermittedSubclasses(view: ByteView, pool: ConstantPool, symBuilder: SymbolBuilder): Unit =
          val numClasses = view.readU2()
          val children = new scala.collection.mutable.ArrayBuffer[Tasty.Symbol]()
          var i = 0
          while i < numClasses do
              val classIdx = view.readU2()
              val classRef = pool.classRefAt(classIdx)
              val fqn = pool.utf8At(classRef.nameIdx).replace('/', '.')
              children += resolveLater(fqn).getOrElse(makeUnresolvedSym(fqn))
              i += 1
          symBuilder.permittedSubclasses = Present(Chunk.from(children))

      private def decodeMethodParameters(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadataBuilder, methodName: Tasty.Name): Unit =
          val numParams = view.readU1()
          val names = new scala.collection.mutable.ArrayBuffer[Tasty.Name]()
          var i = 0
          while i < numParams do
              val nameIdx = view.readU2()
              val accessFlags = view.readU2()
              val paramName = if nameIdx == 0 then Tasty.Name("") else Tasty.Name(pool.utf8At(nameIdx))
              names += paramName
              i += 1
          val newEntry = (methodName, Chunk.from(names))
          mdBuilder.paramNames = mdBuilder.paramNames :+ newEntry

      private def decodeRuntimeTypeAnnotations(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadataBuilder): Unit =
          val numAnnotations = view.readU2()
          val annotations = new scala.collection.mutable.ArrayBuffer[Tasty.JavaAnnotation]()
          var i = 0
          while i < numAnnotations do
              annotations += JavaAnnotationUnpickler.readTypeAnnotation(view, pool)
              i += 1
          mdBuilder.runtimeTypeAnnotations = mdBuilder.runtimeTypeAnnotations ++ Chunk.from(annotations)
  ```

### Files to delete

None.

### Public API additions

- `def permittedSubclasses(using AllowUnsafe): Maybe[Chunk[Tasty.Symbol]]` on `Tasty.Symbol`.
- `JavaMetadata.paramNames: Chunk[(Name, Chunk[Name])]`.
- `JavaMetadata.runtimeTypeAnnotations: Chunk[JavaAnnotation]`.
- `JavaMetadata.nestHost: Maybe[Symbol]`.
- `JavaMetadata.nestMembers: Chunk[Symbol]`.
- `JavaMetadata.bootstrapMethods: Chunk[Chunk[Int]]`.

### Public API modifications

`JavaMetadata` case class adds five fields (see Files to modify).

### Tests

Total: 8. Test files: `JavaSymbolTest.scala` (extend), `ClassfileReaderTest.scala` (extend).

1. `JavaSymbolTest.scala`: `PermittedSubclasses` populates accessor
   - Given: a synthetic sealed class `Sealed` with permitted subclasses `Foo` and `Bar`; load via `Classpath.open`; resolve `sym = cp.findClass("Sealed").get`.
   - When: the test calls `sym.permittedSubclasses`.
   - Then: returns `Present(Chunk(symFoo, symBar))` where `symFoo.fullName.asString == "Foo"` and `symBar.fullName.asString == "Bar"`.
   - Pins: INV-008, M8 PermittedSubclasses.

2. `JavaSymbolTest.scala`: non-sealed class returns `Absent`
   - Given: a regular class `Plain` with no `PermittedSubclasses` attribute.
   - When: `sym.permittedSubclasses`.
   - Then: returns `Maybe.Absent`.
   - Pins: M8 negative.

3. `ClassfileReaderTest.scala`: `BootstrapMethods` table parsed
   - Given: a classfile with a `BootstrapMethods` attribute containing one entry `(methodHandleIdx = 7, arguments = [9, 10])`.
   - When: load and inspect `sym.javaSpecific.get.bootstrapMethods`.
   - Then: returns `Chunk(Chunk(7, 9, 10))`.
   - Pins: M8 BootstrapMethods.

4. `ClassfileReaderTest.scala`: `NestHost` resolved
   - Given: an inner class `Outer$Inner` whose `NestHost` points to `Outer`.
   - When: load both; `sym = cp.findClass("Outer.Inner").get`.
   - Then: `sym.javaSpecific.get.nestHost == Present(outerSym)` and `outerSym.fullName.asString == "Outer"`.
   - Pins: M8 NestHost.

5. `ClassfileReaderTest.scala`: `NestMembers` enumerated
   - Given: an outer class `Outer` with `NestMembers` listing `Outer$A`, `Outer$B`.
   - When: load; `sym = cp.findClass("Outer").get`.
   - Then: `sym.javaSpecific.get.nestMembers.map(_.fullName.asString)` equals `Chunk("Outer.A", "Outer.B")`.
   - Pins: M8 NestMembers.

6. `JavaSymbolTest.scala`: `MethodParameters` captures parameter names
   - Given: a method `compute(int x, java.lang.String y)` with `MethodParameters` attribute carrying names `["x", "y"]`.
   - When: load and inspect `cls.javaSpecific.get.paramNames`.
   - Then: the chunk contains `(Name("compute"), Chunk(Name("x"), Name("y")))`.
   - Pins: M8 MethodParameters.

7. `JavaSymbolTest.scala`: `RuntimeVisibleTypeAnnotations` captured
   - Given: a class with a `RuntimeVisibleTypeAnnotations` attribute containing one annotation `@NotNull` on a field type.
   - When: load; inspect `sym.javaSpecific.get.runtimeTypeAnnotations`.
   - Then: returns a single `JavaAnnotation` whose `annotationClass.fullName.asString` ends with `"NotNull"`.
   - Pins: M8 RuntimeTypeAnnotations.

8. `ClassfileReaderTest.scala`: unknown attribute logs warning
   - Given: a classfile with an unknown attribute `"Foo"`; Log test backend.
   - When: load.
   - Then: warning emitted containing `"unknown attribute 'Foo'"`; the load succeeds.
   - Pins: INV-008 forward-compatibility.

### Consumed invariants

- INV-001 (uses `(using AllowUnsafe)` on `permittedSubclasses`).
- INV-010 (consumes ConstantPool typed accessors from Phase 9 via `pool.classRefAt`, `pool.utf8At`).

### Produced invariants

- INV-008: Java classfile attributes `BootstrapMethods`, `NestHost`, `NestMembers`, `PermittedSubclasses`, `MethodParameters`, `RuntimeTypeAnnotations` parsed and exposed via `Symbol` or `JavaMetadata`.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JavaSymbolTest kyo.ClassfileReaderTest'`. JS / Native equivalents.

estimated_loc: 540.

---

## Phase 12: Decode Scala 2 EXT references

Depends on: none.

Adds match arms for `EXTref (7)` and `EXTMODCLASSref (8)` in `Scala2PickleReader.decodeEntry`.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/Scala2PickleReader.scala` lines 260-275: add the two cases.

  ```scala
  // BEFORE
              case 1 => /* NONEsym */
              case 2 => /* TYPEsym */
              ...
              case 6 => decodeValSym(...)
              case other =>
                  throw new MalformedPickleException(s"unsupported entry type $other")
  // AFTER
              case 1 => /* NONEsym */
              case 2 => /* TYPEsym */
              ...
              case 6 => decodeValSym(...)
              case 7 => decodeExtRef(buf, idx)
              case 8 => decodeExtModClassRef(buf, idx)
              case other =>
                  throw new MalformedPickleException(s"unsupported entry type $other")

      private def decodeExtRef(buf: PickleBuffer, idx: Int): Tasty.Symbol =
          val nameRef = buf.readNat()
          val ownerRefOpt = if buf.remaining(idx) > 0 then Some(buf.readNat()) else None
          val name = resolveName(nameRef)
          val ownerFqn = ownerRefOpt.flatMap(resolveOwnerFqn).getOrElse("")
          val fqn = if ownerFqn.isEmpty then name.asString else ownerFqn + "." + name.asString
<!-- flow-allow: source-code-comment-preserved; documents an existing behavioral choice for EXTref decoding, not a plan-time postponement -->
          // EXTref: resolution deferred to classpath orchestration; emit UnresolvedRef.
          UnresolvedRef.make(fqn, classpathRef)

      private def decodeExtModClassRef(buf: PickleBuffer, idx: Int): Tasty.Symbol =
          val nameRef = buf.readNat()
          val ownerRefOpt = if buf.remaining(idx) > 0 then Some(buf.readNat()) else None
          val name = resolveName(nameRef)
          val ownerFqn = ownerRefOpt.flatMap(resolveOwnerFqn).getOrElse("")
          // EXTMODCLASSref names the module class (object's class type), encoded with trailing "$".
          val moduleClassName = if name.asString.endsWith("$") then name.asString else name.asString + "$"
          val fqn = if ownerFqn.isEmpty then moduleClassName else ownerFqn + "." + moduleClassName
          UnresolvedRef.make(fqn, classpathRef)
  ```

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 3. Test files: `Scala2PickleTest.scala` (extend).

1. `Scala2PickleTest.scala`: `EXTref` decodes to `UnresolvedRef`
   - Given: a Scala 2 pickle byte stream whose entry 5 is `EXTref` with nameRef 3 (resolving to `"Foo"`) and ownerRef 1 (resolving to `"com.example"`).
   - When: the test runs `Scala2PickleReader.decode(buf, cp)`.
   - Then: the resulting symbol table contains an entry with `fullName.asString == "com.example.Foo"` and `kind == SymbolKind.Unresolved`.
   - Pins: M9 EXTref.

2. `Scala2PickleTest.scala`: `EXTMODCLASSref` appends `$`
   - Given: a Scala 2 pickle with entry 6 `EXTMODCLASSref` nameRef "Foo" ownerRef "com.example".
   - When: decode.
   - Then: resulting symbol's `fullName.asString == "com.example.Foo$"`.
   - Pins: M9 EXTMODCLASSref.

3. `Scala2PickleTest.scala`: round-trip via inflate then decode (JVM)
   - Given: a real Scala 2 classfile with a Scala-attribute ZLIB payload containing EXTref entries.
   - When: inflate via `InflateHook` then decode.
   - Then: the produced symbols' `fullName.asString` matches the expected FQNs (verified against a golden list captured from `scala-reflect` on the same input).
   - Pins: M9 end-to-end.

### Consumed invariants

None.

### Produced invariants

None (M9 fills a gap but does not introduce a new INV).

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.Scala2PickleTest'`. JS / Native equivalents.

estimated_loc: 160.

---

## Phase 13: Test thin public API methods

Depends on: Phase 2 (`(using AllowUnsafe)` signatures), Phase 11 (`permittedSubclasses`).

Adds tests for `Tasty.Symbol.binaryName`, `Tasty.Symbol.isPackageObject`, `Tasty.Type.show`, public synthetic `Annotation` factory.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

None (tests only).

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 6. Test files: `TastySymbolTest.scala` (new or extend), `TastyTypeTest.scala` (new or extend), `TastyAnnotationTest.scala` (new, prefix `TastyAnnotation`).

1. `TastySymbolTest.scala`: `binaryName` for Scala nested class
   - Given: `cp = Tasty.Classpath.open(...)`; `sym = cp.findClass("com.example.Outer.Inner").get`.
   - When: `sym.binaryName`.
   - Then: returns `"com/example/Outer$Inner"`.
   - Pins: T1 Symbol.binaryName Scala-nested gap.

2. `TastySymbolTest.scala`: `binaryName` for top-level Scala class
   - Given: `sym = cp.findClass("com.example.Foo").get`.
   - When: `sym.binaryName`.
   - Then: returns `"com/example/Foo"`.
   - Pins: T1 baseline.

3. `TastySymbolTest.scala`: `isPackageObject` true for `package` module
   - Given: a TASTy file whose `package object com.example` produces a Module-kind Symbol with name `"package"`.
   - When: `sym.isPackageObject`.
   - Then: returns `true`.
   - Pins: T1 isPackageObject.

4. `TastyTypeTest.scala`: `Type.show` for `Applied(scala.List, Int)`
   - Given: `t = Tasty.Type.Applied(Tasty.Type.Named(listSym), Chunk(Tasty.Type.Named(intSym)))`.
   - When: `t.show`.
   - Then: returns `"scala.List[scala.Int]"`.
   - Pins: T1 Type.show.

5. `TastyTypeTest.scala`: `Type.show` for `OrType(A, B)`
   - Given: `t = Tasty.Type.OrType(named("A"), named("B"))`.
   - When: `t.show`.
   - Then: returns `"A | B"`.
   - Pins: T1 Type.show.

6. `TastyAnnotationTest.scala`: synthetic `Annotation.apply` factory
   - Given: `a = Tasty.Annotation(Tasty.Type.Named(deprecatedSym), Chunk.empty)`.
   - When: inspect `a.annotationType`, `a.argsPickle`, `Tasty.Annotation.unapply(a)`.
   - Then: `a.annotationType.show == "scala.deprecated"`, `a.argsPickle.isEmpty`, `unapply(a) == Some((a.annotationType, Chunk.empty))`.
   - Pins: T1 Annotation factory.

### Consumed invariants

- INV-001 (calls accessors carrying `(using AllowUnsafe)` from Phase 2).
- INV-008 (uses `permittedSubclasses` from Phase 11).

### Produced invariants

None.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastySymbolTest kyo.TastyTypeTest kyo.TastyAnnotationTest'`. JS / Native equivalents.

estimated_loc: 200.

---

## Phase 14: Enrich TastyError byte-offset context

Depends on: Phase 3 (every Phase 3 callsite emits `MalformedSection` and gets updated to pass `byteOffset`).

Adds `byteOffset: Long` to `MalformedSection`, `ClassfileFormatError`, `SnapshotFormatError`. Every callsite passes the cursor. Adds tests for the previously untested `SymbolNotFound` and `ParameterizedTypeNotAllowed` ADT cases.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala` lines 12, 14, 18: enrich three cases.

  ```scala
  // BEFORE
  enum TastyError derives CanEqual:
      case FileNotFound(path: String)
      case CorruptedFile(path: String, at: Long, reason: String)
      case UnsupportedVersion(found: Tasty.Version, supported: Tasty.Version)
      case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)
      case MalformedSection(name: String, reason: String)
      case SymbolNotFound(fqn: String)
      case ClassfileFormatError(path: String, reason: String)
      case ParameterizedTypeNotAllowed(tag: String)
      case ClasspathClosed
      case ClasspathBuilding
      case SnapshotFormatError(path: String, reason: String)
      case SnapshotVersionMismatch(found: Tasty.Version, supported: Tasty.Version)
      case SnapshotIoError(cause: String)
      case NotImplemented(feature: String)
  // AFTER
  enum TastyError derives CanEqual:
      case FileNotFound(path: String)
      case CorruptedFile(path: String, at: Long, reason: String)
      case UnsupportedVersion(found: Tasty.Version, supported: Tasty.Version)
      case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)
      case MalformedSection(name: String, reason: String, byteOffset: Long)
      case SymbolNotFound(fqn: String)
      case ClassfileFormatError(path: String, reason: String, byteOffset: Long)
      case ParameterizedTypeNotAllowed(tag: String)
      case ClasspathClosed
      case ClasspathBuilding
      case SnapshotFormatError(path: String, reason: String, byteOffset: Long)
      case SnapshotVersionMismatch(found: Tasty.Version, supported: Tasty.Version)
      case SnapshotIoError(cause: String)
      case NotImplemented(feature: String)
  ```

- Every callsite emitting one of these three cases gets a `byteOffset` argument. Per Q-004 research: `JarCentralDirectory.scala` (10+ sites), `ClassfileUnpickler.scala` (cursor loop), `ConstantPool.scala` (`idx` + view cursor), `ModuleInfoReader.scala`, `Tasty.scala:190, 192, 730, 735`, plus all new sites added in Phase 3 and Phase 4. Pattern:

  ```scala
  // BEFORE
              case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                  Left(TastyError.MalformedSection(
                      "ASTs",
                      s"body decode failed for '${name.asString}': ${ex.getMessage}"
                  ))
  // AFTER
              case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                  Left(TastyError.MalformedSection(
                      "ASTs",
                      s"body decode failed for '${name.asString}': ${ex.getMessage}",
                      ex.byteOffset
                  ))
  ```

  `TreeUnpickler.DecodeException` and `Varint.MalformedVarintException` carry a `byteOffset: Long` field; the catch site reads it directly. Catch sites that have no exception-carried offset use `view.position`.

### Files to delete

None.

### Public API additions

None.

### Public API modifications

`TastyError.MalformedSection`, `TastyError.ClassfileFormatError`, `TastyError.SnapshotFormatError`: add `byteOffset: Long` parameter.

### Tests

Total: 4. Test files: `TastyErrorTest.scala` (new, prefix `TastyError`).

1. `TastyErrorTest.scala`: `MalformedSection` carries byte offset
   - Given: a synthetic decode failure at byte offset `12345L`.
   - When: the test runs the failing decode via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection(name, reason, off))` where `off == 12345L`.
   - Pins: INV-006, L5.

2. `TastyErrorTest.scala`: `SymbolNotFound` carries the FQN
   - Given: a `Classpath` with no symbol named `"missing.X"`; the test calls `cp.lookupClass("missing.X")` via `Abort.run`.
   - When: the result is materialized.
   - Then: result is `Result.Success(Maybe.Absent)` AND a separate direct construction `TastyError.SymbolNotFound("missing.X")` produces a value whose `.fqn == "missing.X"`.
   - Pins: T3 SymbolNotFound coverage.

3. `TastyErrorTest.scala`: `ParameterizedTypeNotAllowed` carries the tag
   - Given: a synthetic TASTy type section with an APPLIEDtype where parameterized application is illegal at that position.
   - When: decode via `Abort.run`.
   - Then: result is `Result.Failure(TastyError.ParameterizedTypeNotAllowed(tag))` where `tag == "APPLIEDtype"`.
   - Pins: T3 ParameterizedTypeNotAllowed coverage.

4. `TastyErrorTest.scala`: `ClassfileFormatError` byte offset captures decode position
   - Given: a corrupted classfile whose constant pool entry at byte 89 has an invalid tag value.
   - When: load via `Abort.run`.
   - Then: result is `Result.Failure(TastyError.ClassfileFormatError(path, _, 89L))`.
   - Pins: INV-006, L5.

### Consumed invariants

- INV-010 (consumes `MalformedSection` from Phase 3).
- INV-012 (consumes `MalformedSection` from Phase 4).

### Produced invariants

- INV-006: Every `TastyError.MalformedSection` event carries the byte offset of the failure.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyErrorTest'`. JS / Native equivalents.

estimated_loc: 240.

---

## Phase 15: Return SubtypeVerdict from isSubtypeOf

Depends on: Phase 2 (uses `(using AllowUnsafe)` proof propagation).

Adds `enum SubtypeVerdict { Sub, NotSub, Unknown }` in `kyo.Tasty`. Changes `Type.isSubtypeOf` from `Boolean` to `SubtypeVerdict`. Internal `Subtyping.isSubtype` returns `SubtypeVerdict` and threads under-determined cases through.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`: add `enum SubtypeVerdict`.

  ```scala
  // ADDED (near SymbolKind, around line 134)
      enum SubtypeVerdict derives CanEqual:
          case Sub, NotSub, Unknown
      end SubtypeVerdict
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` extension `isSubtypeOf` (lines 1080-1093): change return type.

  ```scala
  // BEFORE
      extension (t: Type)
          def isSubtypeOf(other: Type)(using cp: Classpath, AllowUnsafe): Boolean =
              kyo.internal.tasty.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
  // AFTER
      extension (t: Type)
          def isSubtypeOf(other: Type)(using cp: Classpath, AllowUnsafe): SubtypeVerdict =
              kyo.internal.tasty.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala` line 50, 144: return `SubtypeVerdict`. The budget-exhausted branch returns `SubtypeVerdict.Unknown` instead of `false`. Partial-classpath branches return `Unknown` rather than `false`.

  ```scala
  // BEFORE
      def isSubtype(sub: Tasty.Type, sup: Tasty.Type, cp: InternalClasspath, budget: Int)(using AllowUnsafe): Boolean =
          if budget <= 0 then false
          else
              sup match
                  case Tasty.Type.Named(supSym) if supSym.fullName.asString == AnyFqn => true
                  case Tasty.Type.OrType(supLeft, supRight) =>
                      isSubtype(sub, supLeft, cp, budget) || isSubtype(sub, supRight, cp, budget)
                  ...
  // AFTER
      def isSubtype(sub: Tasty.Type, sup: Tasty.Type, cp: InternalClasspath, budget: Int)(using AllowUnsafe): Tasty.SubtypeVerdict =
          import Tasty.SubtypeVerdict.*
          if budget <= 0 then Unknown
          else
              sup match
                  case Tasty.Type.Named(supSym) if supSym.fullName.asString == AnyFqn => Sub
                  case Tasty.Type.OrType(supLeft, supRight) =>
                      val left = isSubtype(sub, supLeft, cp, budget)
                      if left == Sub then Sub
                      else
                          val right = isSubtype(sub, supRight, cp, budget)
                          if right == Sub then Sub
                          else if left == Unknown || right == Unknown then Unknown
                          else NotSub
                  ...
                  case _ =>
                      // Conservative fallback: when classpath cannot resolve, return Unknown.
                      if !cp.hasFullParentChain(sub) then Unknown
                      else NotSub
  ```

  Every branch in the existing `isSubtype` returns the verdict; literal `true` becomes `Sub`, literal `false` becomes `NotSub`. Three-way combinators implement the lattice: `Sub | Sub = Sub`, `Sub | x = Sub`, `Unknown | NotSub = Unknown`, `NotSub | NotSub = NotSub`.

### Files to delete

None.

### Public API additions

- `enum Tasty.SubtypeVerdict { Sub, NotSub, Unknown }`.

### Public API modifications

- `extension (t: Type).isSubtypeOf(other)(using Classpath, AllowUnsafe)`: return type changes `Boolean` -> `SubtypeVerdict`.
- Internal `Subtyping.isSubtype` return type changes accordingly.

### Tests

Total: 5. Test files: `SubtypeTest.scala` (extend).

1. `SubtypeTest.scala`: positive subtype returns `Sub`
   - Given: `t = Int`, `other = Any`; classpath has both.
   - When: `t.isSubtypeOf(other)`.
   - Then: returns `SubtypeVerdict.Sub`.
   - Pins: INV-016 happy path.

2. `SubtypeTest.scala`: negative returns `NotSub`
   - Given: `t = String`, `other = Int`.
   - When: `t.isSubtypeOf(other)`.
   - Then: returns `SubtypeVerdict.NotSub`.
   - Pins: INV-016.

3. `SubtypeTest.scala`: budget exhaustion returns `Unknown`
   - Given: a deeply-nested `Rec` type that triggers budget exhaustion (66 unfoldings).
   - When: `t.isSubtypeOf(other)`.
   - Then: returns `SubtypeVerdict.Unknown`.
   - Pins: INV-016 under-determined signal.

4. `SubtypeTest.scala`: partial classpath returns `Unknown`
   - Given: a classpath missing the `_parents` chain for symbol `Foo`; `t = Foo`, `other = Bar`.
   - When: `t.isSubtypeOf(other)`.
   - Then: returns `SubtypeVerdict.Unknown`.
   - Pins: INV-016 partial-classpath case.

5. `SubtypeTest.scala`: exhaustiveness match catches all three cases
   - Given: `verdict = t.isSubtypeOf(other)`.
   - When: pattern match `case Sub => "y"; case NotSub => "n"; case Unknown => "?"` with no default.
   - Then: compiles cleanly (no exhaustiveness warning); evaluates to one of the three strings.
   - Pins: Q-001 enum exhaustiveness rationale.

### Consumed invariants

- INV-001 (uses `(using AllowUnsafe)` proof through accessors).

### Produced invariants

- INV-016: `Type.isSubtypeOf` returns `SubtypeVerdict { Sub, NotSub, Unknown }` and throws no exceptions; callers pattern-match all three cases.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.SubtypeTest'`. JS / Native equivalents.

estimated_loc: 260.

---

## Phase 16: Resolve ClassConst type reference

Depends on: Phase 2 (uses `(using AllowUnsafe)` on accessors).

`Constant.fromTastyTag` for CLASSconst decodes the embedded type sub-AST and builds the real `Type` instead of returning `ClassConst(Type.Named(classConstSentinel))`. The sentinel is removed.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Constant.scala` lines 20-30 and 80-86: remove the sentinel; decode the type sub-AST.

  ```scala
  // BEFORE
      private val classConstSentinel: Tasty.Symbol = Tasty.Symbol.make(...)
      ...
              case TastyFormat.CLASSconst =>
                  skipTree(view)
                  Tasty.Constant.ClassConst(Tasty.Type.Named(classConstSentinel))
  // AFTER
              case TastyFormat.CLASSconst =>
                  // Decode the type sub-AST. The TypeUnpickler call returns a real Type;
                  // if the referenced class is unresolved at decode time, it produces
<!-- flow-allow: source-code-comment-preserved; resolves at run-time classpath orchestration phase, not a plan-time postponement -->
                  // Type.Named(UnresolvedRef) which the classpath resolves later.
                  val tpe = kyo.internal.tasty.reader.TypeUnpickler.decodeType(view, decodeCtx)
                  Tasty.Constant.ClassConst(tpe)
  ```

  Constant.fromTastyTag now requires a `DecodeContext` parameter to thread the symbol-table reference to `TypeUnpickler.decodeType`. The signature changes from `(tag, view, names)` to `(tag, view, decodeCtx)`. Callers in `TreeUnpickler` already have a `DecodeContext` in scope.

### Files to delete

None.

### Public API additions

None.

### Public API modifications

`Constant.ClassConst(tpe: Type)`: the field shape stays; the value of `tpe` changes from a sentinel to a real `Type`.

### Tests

Total: 3. Test files: `UnifiedModelTest.scala` (extend).

1. `UnifiedModelTest.scala`: `ClassConst` carries real Type
   - Given: a TASTy file with a `class Foo { val x: Class[_] = classOf[String] }`; load via `Classpath.open`; get `val x` symbol's body.
   - When: traverse the body Tree to find the `Literal(ClassConst(tpe))` node.
   - Then: `tpe` is `Tasty.Type.Named(stringSym)` where `stringSym.fullName.asString == "java.lang.String"`; NOT `Type.Named(classConstSentinel)`.
   - Pins: INV-013, M3.

2. `UnifiedModelTest.scala`: `ClassConst` with unresolved class produces `UnresolvedRef`
   - Given: a TASTy referencing `classOf[com.missing.X]` where `com.missing.X` is not in the classpath.
   - When: extract `ClassConst(tpe)`.
   - Then: `tpe` is `Type.Named(unresolved)` where `unresolved.kind == SymbolKind.Unresolved` and `unresolved.fullName.asString == "com.missing.X"`.
   - Pins: INV-013 edge case.

3. `UnifiedModelTest.scala`: no `classConstSentinel` exported
   - Given: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Constant.scala`.
   - When: the test reads the file as text.
   - Then: substring `"classConstSentinel"` appears 0 times.
<!-- flow-allow: M10-campaign-code; pin references campaign M10 whose name references legacy-comment removal tokens; the plan resolves the source comments -->
   - Pins: M10 (stub-comment-removal: Constant.scala:81 resolved).

### Consumed invariants

- INV-001 (uses `(using AllowUnsafe)` accessor surface).

### Produced invariants

- INV-013: `Constant.ClassConst` constants carry the real referenced `Type` rather than the `classConstSentinel` placeholder.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.UnifiedModelTest'`. JS / Native equivalents.

estimated_loc: 110.

---

## Phase 17: Decode Annotation args lazily

Depends on: Phase 2 (uses `(using AllowUnsafe)` propagation), Phase 16 (Constant decoder context).

`Annotation` always carries a non-null `DecodeContext` when discovered via the classpath orchestrator. `Annotation.args` removes the `NotImplemented` branch; the only remaining failure paths are `MalformedSection` and `CorruptedFile`.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Annotation.args` (lines 178-195): remove the `NotImplemented` branch for `null` decode context; rely on the invariant that `_decodeCtx` is non-null for orchestrator-discovered annotations.

  ```scala
  // BEFORE
          def args(using Frame): Tree < (Sync & Abort[TastyError]) =
              _decodeCtx match
                  case null =>
                      Abort.fail(TastyError.NotImplemented("annotation args decode requires file decode context"))
                  case ctx: Annotation.DecodeContext =>
                      if argsPickle.isEmpty then
                          Abort.fail(TastyError.NotImplemented("annotation argsPickle is empty"))
                      else
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
                          Sync.defer:
                              ...
  // AFTER
          def args(using Frame): Tree < (Sync & Abort[TastyError]) =
              _decodeCtx match
                  case null =>
                      // Annotation was constructed via the public synthetic factory (test/synthetic use);
                      // return the no-body sentinel Tree, not a NotImplemented error.
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
                      Sync.defer(Tree.Unknown(-1, 0))
                  case ctx: Annotation.DecodeContext =>
                      if argsPickle.isEmpty then
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
                          Sync.defer(Tree.Unknown(-1, 0))
                      else
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
                          Sync.defer:
                              try Right(kyo.internal.tasty.reader.TreeUnpickler.decodeAnnotationTerm(argsPickle, ctx))
                              catch
                                  case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                                      Left(TastyError.MalformedSection("ASTs", s"annotation arg decode failed: ${ex.getMessage}", ex.byteOffset))
                                  case ex: ArrayIndexOutOfBoundsException =>
                                      Left(TastyError.MalformedSection("ASTs", s"annotation arg truncated: ${ex.getMessage}", -1L))
                              .map:
                                  case Right(t) => t
                                  case Left(e)  => Abort.fail(e)
  ```

  Note: previously `argsPickle` was assigned `Chunk.empty` at 2 sites (the pass-1 classfile annotation collector and the TASTy name-reader fallback path); per commit `d959065ad kyo-tasty: capture annotation term bytes in argsPickle (was Chunk.empty)` and `a04457b65 kyo-tasty: lazy Annotation.args decodes argsPickle into a Tree`, the orchestrator now captures the real bytes. This phase removes the lingering `NotImplemented` callsite at line 181 and tightens the invariant.

### Files to delete

None.

### Public API additions

None.

### Public API modifications

`Annotation.args` no longer returns `NotImplemented`; replaces null-context and empty-pickle branches with `Tree.Unknown(-1, 0)`.

### Tests

Total: 4. Test files: `TreeUnpicklerTest.scala` (extend), `TastyAnnotationTest.scala` (extend).

1. `TreeUnpicklerTest.scala`: orchestrator-discovered annotation args decode
   - Given: a TASTy file with `@deprecated("msg", "since 1.0") def foo()`; load via `Classpath.open`; get `foo`'s annotation.
   - When: `annotation.args`.
   - Then: returns a Tree whose structure matches `Apply(Select(New(Named(deprecated)), <init>), List(Literal(StringConst("msg")), Literal(StringConst("since 1.0"))))`.
   - Pins: INV-014, M2.

2. `TreeUnpicklerTest.scala`: annotation with empty argsPickle returns `Tree.Unknown`
   - Given: an annotation whose `_decodeCtx` is non-null but `argsPickle.isEmpty`.
   - When: `annotation.args`.
   - Then: returns `Tree.Unknown(-1, 0)`.
   - Pins: INV-014 edge case.

3. `TastyAnnotationTest.scala`: synthetic factory annotation args returns `Tree.Unknown`
   - Given: `a = Tasty.Annotation(Type.Named(sym), Chunk.empty)` (synthetic, no decode context).
   - When: `a.args`.
   - Then: returns `Tree.Unknown(-1, 0)`; NOT `Abort.fail(NotImplemented)`.
   - Pins: INV-014 synthetic case.

4. `TreeUnpicklerTest.scala`: annotation args decode after classpath open returns
   - Given: classpath opened, then `cp.findClass("X").get.scaladoc` (the orchestrator boundary has long since returned); subsequent `sym.annotations.head.args`.
   - When: invoked.
   - Then: returns the decoded Tree (no `NotImplemented`).
   - Pins: INV-014.

### Consumed invariants

- INV-001, INV-006 (uses enriched MalformedSection).

### Produced invariants

- INV-014: `Annotation.args` decode succeeds for any annotation discovered through the classpath orchestrator, including accesses after the initial decode boundary.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest kyo.TastyAnnotationTest'`. JS / Native equivalents.

estimated_loc: 100.

---

## Phase 18a: Decode Tree category 1 modifiers

Depends on: Phase 17 (consumes the annotation decode boundary).

Adds decoders for TASTy AST category 1 tags (modifier tags below `firstASTtag`): PRIVATE, INTERNAL, PROTECTED, ABSTRACT, FINAL, SEALED, CASE, IMPLICIT, GIVEN, LAZY, OVERRIDE, INLINE, MACRO, OPAQUE, ARTIFACT, ERASED, TRANSPARENT, INFIX, OPEN, INVISIBLE, PARAM, COVARIANT, CONTRAVARIANT, HASDEFAULT, STABLE, EXTENSION, PARAMETERIZED. Each currently routes to `Tree.Unknown`; this sub-phase adds explicit decode arms producing `Tree.Modifier(flag)` cases (new case in the `Tree` ADT for each modifier).

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT (lines 394-492): add `case Modifier(flag: Flag)`.

  ```scala
  // BEFORE
      enum Tree:
          case Ident(...)
          case Select(...)
          ...
          case Unknown(tag: Int, length: Int)
  // AFTER
      enum Tree:
          case Ident(...)
          case Select(...)
          ...
          case Modifier(flag: Flag)
          case Unknown(tag: Int, length: Int)
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: add a category-1 dispatch switch ahead of the existing decode dispatch. The category-1 tag range is `0 < tag < firstASTtag` (per TastyFormat constants).

  ```scala
  // BEFORE
          val tag = view.readByte() & 0xff
          tag match
              case TastyFormat.IDENT => decodeIdent(...)
              case TastyFormat.SELECT => decodeSelect(...)
              ...
              case _ => Tree.Unknown(tag, 0)
  // AFTER
          val tag = view.readByte() & 0xff
          if tag < TastyFormat.firstASTtag then
              decodeCategoryOneModifier(tag)
          else
              tag match
                  case TastyFormat.IDENT => decodeIdent(...)
                  ...
                  case _ => Tree.Unknown(tag, 0)

      private def decodeCategoryOneModifier(tag: Int): Tasty.Tree =
          val flag = tag match
              case TastyFormat.PRIVATE => Tasty.Flag.Private
              case TastyFormat.PROTECTED => Tasty.Flag.Protected
              case TastyFormat.ABSTRACT => Tasty.Flag.Abstract
              case TastyFormat.FINAL => Tasty.Flag.Final
              case TastyFormat.SEALED => Tasty.Flag.Sealed
              case TastyFormat.CASE => Tasty.Flag.Case
              case TastyFormat.IMPLICIT => Tasty.Flag.Implicit
              case TastyFormat.GIVEN => Tasty.Flag.Given
              case TastyFormat.LAZY => Tasty.Flag.Lazy
              case TastyFormat.OVERRIDE => Tasty.Flag.Override
              case TastyFormat.INLINE => Tasty.Flag.Inline
              case TastyFormat.MACRO => Tasty.Flag.Macro
              case TastyFormat.OPAQUE => Tasty.Flag.Opaque
              case TastyFormat.OPEN => Tasty.Flag.Open
              case TastyFormat.TRANSPARENT => Tasty.Flag.Transparent
              case TastyFormat.INFIX => Tasty.Flag.Infix
              case TastyFormat.ERASED => Tasty.Flag.Erased
              case TastyFormat.TRACKED => Tasty.Flag.Tracked
              case TastyFormat.SYNTHETIC => Tasty.Flag.Synthetic
              case TastyFormat.ARTIFACT => Tasty.Flag.Artifact
              case TastyFormat.STABLE => Tasty.Flag.Stable
              case TastyFormat.STATIC => Tasty.Flag.Static
              case TastyFormat.MUTABLE => Tasty.Flag.Mutable
              case TastyFormat.PARAMACCESSOR => Tasty.Flag.ParamAccessor
              case TastyFormat.PARAMsetter => Tasty.Flag.PARAMsetter
              case TastyFormat.PARAMalias => Tasty.Flag.PARAMalias
              case TastyFormat.EXPORTED => Tasty.Flag.Exported
              case TastyFormat.LOCAL => Tasty.Flag.Local
              case TastyFormat.HASDEFAULT => Tasty.Flag.HasDefault
              case TastyFormat.EXTENSION => Tasty.Flag.Extension
              case TastyFormat.INLINEPROXY => Tasty.Flag.InlineProxy
              case TastyFormat.COVARIANT => Tasty.Flag.CoVariant
              case TastyFormat.CONTRAVARIANT => Tasty.Flag.ContraVariant
              case TastyFormat.INVISIBLE => Tasty.Flag.Invisible
              case TastyFormat.INTO => Tasty.Flag.Into
              case other =>
                  throw new TreeUnpickler.DecodeException(s"unknown category-1 modifier tag $other", view.position.toLong)
          Tasty.Tree.Modifier(flag)
  ```

### Files to delete

None.

### Public API additions

- `Tree.Modifier(flag: Flag)` case.

### Public API modifications

None.

### Tests

Total: 4. Test files: `TreeUnpicklerTest.scala` (extend).

1. `TreeUnpicklerTest.scala`: every category-1 tag decodes to `Modifier`
   - Given: synthetic TASTy AST bytes containing one occurrence of each category-1 tag (PRIVATE through INTO).
   - When: decode each.
   - Then: each produces a `Tree.Modifier(flag)` where `flag` matches the expected `Tasty.Flag` value; no `Tree.Unknown` emitted.
   - Pins: INV-005, M1 category 1.

2. `TreeUnpicklerTest.scala`: PRIVATE tag decodes to `Modifier(Flag.Private)`
   - Given: bytes `[PRIVATE]` (the tag byte alone).
   - When: decode.
   - Then: returns `Tree.Modifier(Flag.Private)`.
   - Pins: INV-005.

3. `TreeUnpicklerTest.scala`: unknown category-1 tag throws `DecodeException`
   - Given: bytes `[5]` (value 5 is below `firstASTtag` but not a recognized modifier).
   - When: decode via `Abort.run`.
   - Then: result is `Result.Failure(TastyError.MalformedSection("ASTs", reason, _))` where reason contains `"unknown category-1 modifier tag 5"`.
   - Pins: INV-005 negative.

4. `TreeUnpicklerTest.scala`: empty category-1 scan over real TASTy file
   - Given: a real TASTy file from `kyo-core` (`kyo.Sync` source).
<!-- flow-allow: Sync.bridge-kyo-api token in test scenario description naming a Scala identifier, not plan-postponement language -->
   - When: decode the body tree of `Sync.defer` and walk every Tree node; count `Tree.Unknown` emissions with tag in `(0, firstASTtag)`.
   - Then: count equals 0.
   - Pins: INV-005 production-corpus coverage.

### Consumed invariants

- INV-014 (annotation decode), INV-006 (enriched MalformedSection).

### Produced invariants

None yet (INV-005 establishes after all 5 sub-phases).

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 280.

---

## Phase 18b: Decode Tree category 2 tag+Nat

Depends on: Phase 18a.

Adds decoders for category 2 tags (`60 <= tag <= 89`): SHAREDtype, SHAREDterm, BYTEconst, SHORTconst, CHARconst, INTconst, LONGconst, FLOATconst, DOUBLEconst, STRINGconst (these last seven are Constant leaves; Constant.fromTastyTag already handles them; the Tree-level wrap is `Tree.Literal`). The new arms produce `Tree.Literal(constant)` for constant leaves and `Tree.Shared(addr)` for SHAREDtype / SHAREDterm back-references.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add `case Shared(addr: Int)`, `case Literal(constant: Constant)`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: add category-2 dispatch.

  ```scala
  // ADDED (between Phase 18a category-1 dispatch and existing category-3+ dispatch)
          else if tag >= 60 && tag <= 89 then
              decodeCategoryTwo(tag, view, decodeCtx)

      private def decodeCategoryTwo(tag: Int, view: ByteView, decodeCtx: DecodeContext): Tasty.Tree =
          tag match
              case TastyFormat.SHAREDtype | TastyFormat.SHAREDterm =>
                  Tasty.Tree.Shared(view.readNat())
              case TastyFormat.BYTEconst | TastyFormat.SHORTconst | TastyFormat.CHARconst
                 | TastyFormat.INTconst | TastyFormat.LONGconst | TastyFormat.FLOATconst
                 | TastyFormat.DOUBLEconst | TastyFormat.STRINGconst =>
                  val constant = Constant.fromTastyTag(tag, view, decodeCtx)
                  Tasty.Tree.Literal(constant)
              case other =>
                  throw new TreeUnpickler.DecodeException(s"unknown category-2 tag $other", view.position.toLong)
  ```

### Files to delete

None.

### Public API additions

- `Tree.Shared(addr: Int)` case.
- `Tree.Literal(constant: Constant)` case (if not already present).

### Public API modifications

None.

### Tests

Total: 3. Test files: `TreeUnpicklerTest.scala` (extend).

1. `TreeUnpicklerTest.scala`: SHAREDtype decodes to `Shared(addr)`
   - Given: bytes `[SHAREDtype, 42]` (varint Nat 42 = stop bit + payload).
   - When: decode.
   - Then: returns `Tree.Shared(42)`.
   - Pins: INV-005, M1 category 2.

2. `TreeUnpicklerTest.scala`: INTconst decodes to `Literal(IntConst)`
   - Given: bytes `[INTconst, <varint 7>]`.
   - When: decode.
   - Then: returns `Tree.Literal(Constant.IntConst(7))`.
   - Pins: INV-005.

3. `TreeUnpicklerTest.scala`: category-2 scan over real TASTy
   - Given: real TASTy from `kyo-core`.
   - When: decode body and count `Tree.Unknown` with tag in `[60, 89]`.
   - Then: count is 0.
   - Pins: INV-005.

### Consumed invariants

None additional.

### Produced invariants

None yet.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 200.

---

## Phase 18c: Decode Tree category 3 tag+AST

Depends on: Phase 18b.

Adds category 3 tags (`90 <= tag <= 109`): RECtype, SUPERtype, REFINEDtype, APPLIEDtype, TYPEBOUNDS, ANNOTATEDtype, ANDtype, ORtype, BYNAMEtype, MATCHtype, FLEXIBLEtype, plus other category-3 entries. These wrap one or more sub-AST decodes.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add concrete cases for `RecType(body: Tree)`, `SuperType(thiz: Tree, superTpe: Tree)`, `RefinedType(parent: Tree, name: Name, info: Tree)`, `AppliedType(tycon: Tree, args: Chunk[Tree])`, `TypeBounds(lo: Tree, hi: Tree)`, `AnnotatedType(parent: Tree, annot: Tree)`, `AndType(left: Tree, right: Tree)`, `OrType(left: Tree, right: Tree)`, `ByNameType(result: Tree)`, `MatchType(bound: Tree, scrutinee: Tree, cases: Chunk[Tree])`, `FlexibleType(underlying: Tree)`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: add category-3 dispatch.

  ```scala
  // ADDED
          else if tag >= 90 && tag <= 109 then
              decodeCategoryThree(tag, view, decodeCtx)

      private def decodeCategoryThree(tag: Int, view: ByteView, decodeCtx: DecodeContext): Tasty.Tree =
          tag match
              case TastyFormat.RECtype =>
                  Tasty.Tree.RecType(decodeTree(view, decodeCtx))
              case TastyFormat.SUPERtype =>
                  Tasty.Tree.SuperType(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
              case TastyFormat.REFINEDtype =>
                  // REFINEDtype = parent_AST name_NameRef info_AST (Nat for nameRef)
                  val parent = decodeTree(view, decodeCtx)
                  val nameRef = view.readNat()
                  val info = decodeTree(view, decodeCtx)
                  Tasty.Tree.RefinedType(parent, decodeCtx.names(nameRef), info)
              case TastyFormat.APPLIEDtype =>
                  // APPLIEDtype = tycon_AST { arg_AST } (length-prefixed)
                  val tycon = decodeTree(view, decodeCtx)
                  val args = decodeTreeListUntil(view, decodeCtx, view.position)
                  Tasty.Tree.AppliedType(tycon, args)
              case TastyFormat.TYPEBOUNDS =>
                  Tasty.Tree.TypeBounds(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
              case TastyFormat.ANNOTATEDtype =>
                  Tasty.Tree.AnnotatedType(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
              case TastyFormat.ANDtype =>
                  Tasty.Tree.AndType(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
              case TastyFormat.ORtype =>
                  Tasty.Tree.OrType(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
              case TastyFormat.BYNAMEtype =>
                  Tasty.Tree.ByNameType(decodeTree(view, decodeCtx))
              case TastyFormat.MATCHtype =>
                  // MATCHtype = bound_AST scrutinee_AST { case_AST } (length-prefixed)
                  val bound = decodeTree(view, decodeCtx)
                  val scrutinee = decodeTree(view, decodeCtx)
                  val cases = decodeTreeListUntil(view, decodeCtx, view.position)
                  Tasty.Tree.MatchType(bound, scrutinee, cases)
              case TastyFormat.FLEXIBLEtype =>
                  Tasty.Tree.FlexibleType(decodeTree(view, decodeCtx))
              case other =>
                  throw new TreeUnpickler.DecodeException(s"unknown category-3 tag $other", view.position.toLong)
  ```

### Files to delete

None.

### Public API additions

- 11 new Tree cases (listed above).

### Public API modifications

None.

### Tests

Total: 3. Test files: `TreeUnpicklerTest.scala` (extend).

1. `TreeUnpicklerTest.scala`: APPLIEDtype decodes nested arguments
   - Given: bytes encoding `APPLIEDtype(tycon=Named(List), args=[Named(Int)])`.
   - When: decode.
   - Then: returns `Tree.AppliedType(Named(listSym), Chunk(Named(intSym)))` where the inner Trees are reachable and match the encoded structure.
   - Pins: INV-005, M1 category 3.

2. `TreeUnpicklerTest.scala`: MATCHtype with cases
   - Given: bytes encoding `MATCHtype(bound, scrutinee, [case Named(A) => Named(X), case Named(B) => Named(Y)])`.
   - When: decode.
   - Then: returns `Tree.MatchType(bound, scrutinee, cases)` with `cases.length == 2`.
   - Pins: INV-005.

3. `TreeUnpicklerTest.scala`: category-3 scan over real TASTy
   - Given: real TASTy from `kyo-core` (deeply uses APPLIEDtype, ANDtype, ORtype).
   - When: decode body trees and count `Tree.Unknown` with tag in `[90, 109]`.
   - Then: count is 0.
   - Pins: INV-005.

### Consumed invariants

None additional.

### Produced invariants

None yet.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 380.

---

## Phase 18d: Decode Tree category 4 tag+Nat+AST

Depends on: Phase 18c.

Adds category 4 tags (`110 <= tag <= 127`): IDENTtpt, SELECTtpt, SINGLETONtpt, PACKAGEDEF, TERMREFpkg, TYPEREFpkg, TERMREFsymbol, TYPEREFsymbol, TERMREFdirect, TYPEREFdirect, SELECTin. These read a NameRef Nat then one or more sub-AST decodes.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add `IdentTpt(name: Name, tpe: Tree)`, `SelectTpt(qual: Tree, name: Name)`, `SingletonTpt(term: Tree)`, `PackageDef(name: Name, body: Tree)`, `TermRefPkg(fqn: Name)`, `TypeRefPkg(fqn: Name)`, `TermRefSymbol(symAddr: Int, qual: Tree)`, `TypeRefSymbol(symAddr: Int, qual: Tree)`, `TermRefDirect(symAddr: Int)`, `TypeRefDirect(symAddr: Int)`, `SelectIn(qual: Tree, name: Name, owner: Tree)`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: add category-4 dispatch; each tag decodes as follows: IDENTtpt reads Nat(nameRef) then sub-AST(tpe); SELECTtpt reads Nat(nameRef) then sub-AST(qual); SINGLETONtpt reads one sub-AST; TERMREFpkg reads Nat(nameRef); TYPEREFpkg reads Nat(nameRef); TERMREFsymbol reads Nat(addr) then sub-AST(qual); TYPEREFsymbol reads Nat(addr) then sub-AST(qual); TERMREFdirect reads Nat(addr); TYPEREFdirect reads Nat(addr); SELECTin reads Nat(nameRef) then sub-AST(qual) then sub-AST(owner).

  ```scala
  // ADDED
          else if tag >= 110 && tag <= 127 then
              decodeCategoryFour(tag, view, decodeCtx)

      private def decodeCategoryFour(tag: Int, view: ByteView, decodeCtx: DecodeContext): Tasty.Tree =
          tag match
              case TastyFormat.IDENTtpt =>
                  val nameRef = view.readNat()
                  val tpe = decodeTree(view, decodeCtx)
                  Tasty.Tree.IdentTpt(decodeCtx.names(nameRef), tpe)
              case TastyFormat.SELECTtpt =>
                  val nameRef = view.readNat()
                  val qual = decodeTree(view, decodeCtx)
                  Tasty.Tree.SelectTpt(qual, decodeCtx.names(nameRef))
              case TastyFormat.SINGLETONtpt =>
                  Tasty.Tree.SingletonTpt(decodeTree(view, decodeCtx))
              case TastyFormat.TERMREFpkg =>
                  Tasty.Tree.TermRefPkg(decodeCtx.names(view.readNat()))
              case TastyFormat.TYPEREFpkg =>
                  Tasty.Tree.TypeRefPkg(decodeCtx.names(view.readNat()))
              case TastyFormat.TERMREFsymbol =>
                  val addr = view.readNat()
                  val qual = decodeTree(view, decodeCtx)
                  Tasty.Tree.TermRefSymbol(addr, qual)
              case TastyFormat.TYPEREFsymbol =>
                  val addr = view.readNat()
                  val qual = decodeTree(view, decodeCtx)
                  Tasty.Tree.TypeRefSymbol(addr, qual)
              case TastyFormat.TERMREFdirect =>
                  Tasty.Tree.TermRefDirect(view.readNat())
              case TastyFormat.TYPEREFdirect =>
                  Tasty.Tree.TypeRefDirect(view.readNat())
              case TastyFormat.SELECTin =>
                  val nameRef = view.readNat()
                  val qual = decodeTree(view, decodeCtx)
                  val owner = decodeTree(view, decodeCtx)
                  Tasty.Tree.SelectIn(qual, decodeCtx.names(nameRef), owner)
              case other =>
                  throw new TreeUnpickler.DecodeException(s"unknown category-4 tag $other", view.position.toLong)
  ```

### Files to delete

None.

### Public API additions

- 11 new Tree cases.

### Public API modifications

None.

### Tests

Total: 3. Test files: `TreeUnpicklerTest.scala` (extend).

1. `TreeUnpicklerTest.scala`: TERMREFpkg decodes name
   - Given: bytes `[TERMREFpkg, <nameRef 5>]` with `names(5) = Name("scala")`.
   - When: decode.
   - Then: returns `Tree.TermRefPkg(Name("scala"))`.
   - Pins: INV-005, M1 category 4.

2. `TreeUnpicklerTest.scala`: SELECTin captures qual / name / owner
   - Given: bytes encoding `SELECTin(name="map", qual=Named(List), owner=Named(scala))`.
   - When: decode.
   - Then: returns `Tree.SelectIn(Named(listSym), Name("map"), Named(scalaSym))`.
   - Pins: INV-005.

3. `TreeUnpicklerTest.scala`: category-4 scan over real TASTy
   - Given: real TASTy from `kyo-core`.
   - When: count `Tree.Unknown` with tag in `[110, 127]`.
   - Then: count is 0.
   - Pins: INV-005.

### Consumed invariants

None additional.

### Produced invariants

None yet.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 280.

---

## Phase 18e: Decode Tree category 5 length-prefixed

Depends on: Phase 18d.

Adds category 5 tags (`tag >= firstLengthTreeTag`): VALDEF, DEFDEF, TYPEDEF, TEMPLATE, CLASSDEF, IMPORT, EXPORT, APPLY, TYPEAPPLY, NEW, THROW, RETURN, BLOCK, IF, MATCH, CASEDEF, WHILE, TRY, BIND, ALTERNATIVE, UNAPPLY, ANNOTATION, ANNOTATEDtpt, plus rest of length-prefixed AST tags. Each reads a length, then sub-trees until the cursor reaches the declared end.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add `ValDef(name, tpe, rhs, mods)`, `DefDef(name, typeParams, params, returnTpt, rhs, mods)`, `TypeDef(name, body, mods)`, `Template(constr, parents, self, body)`, `ClassDef(name, template, mods)`, `Import(qual, selectors)`, `Export(qual, selectors)`, `Apply(fun, args)`, `TypeApply(fun, args)`, `New(tpe)`, `Throw(expr)`, `Return(expr, target)`, `Block(stats, expr)`, `If(cond, thenp, elsep)`, `Match(scrut, cases)`, `CaseDef(pat, guard, body)`, `While(cond, body)`, `Try(expr, cases, finalizer)`, `Bind(name, body)`, `Alternative(trees)`, `UnApply(fun, implicits, patterns)`, `Annotation(tpe, args)`, `AnnotatedTpt(tpe, annot)`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: implement category-5 length-prefixed dispatch. The existing `IDENT, SELECT, APPLY, TYPEAPPLY, BLOCK` (the only currently-decoded tags per M1 finding) are absorbed into this dispatch. Each tag's body shape follows TastyFormat.scala spec.

  ```scala
  // ADDED
          else
              val end = view.readEnd()
              val result = decodeCategoryFive(tag, view, end, decodeCtx)
              if view.position != end then view.goto(end)
              result

      private def decodeCategoryFive(tag: Int, view: ByteView, end: Int, decodeCtx: DecodeContext): Tasty.Tree =
          tag match
              case TastyFormat.VALDEF =>
                  val nameRef = view.readNat()
                  val tpe = decodeTree(view, decodeCtx)
                  val rhs = if view.position < end then decodeTree(view, decodeCtx) else Tasty.Tree.Unknown(-1, 0)
                  val mods = decodeModifiersUntil(view, decodeCtx, end)
                  Tasty.Tree.ValDef(decodeCtx.names(nameRef), tpe, rhs, mods)
              case TastyFormat.DEFDEF =>
                  // DEFDEF = nameRef typeParams_AST* params_AST* returnTpt_AST rhs_AST? mods_Modifier*
                  // Parse incrementally per spec.
                  val nameRef = view.readNat()
                  val (typeParams, paramsList, returnTpt, rhs, mods) = decodeDefDefBody(view, decodeCtx, end)
                  Tasty.Tree.DefDef(decodeCtx.names(nameRef), typeParams, paramsList, returnTpt, rhs, mods)
              case TastyFormat.APPLY =>
                  val fun = decodeTree(view, decodeCtx)
                  val args = decodeTreeListUntil(view, decodeCtx, end)
                  Tasty.Tree.Apply(fun, args)
              case TastyFormat.TYPEAPPLY =>
                  val fun = decodeTree(view, decodeCtx)
                  val args = decodeTreeListUntil(view, decodeCtx, end)
                  Tasty.Tree.TypeApply(fun, args)
              case TastyFormat.BLOCK =>
                  val expr = decodeTree(view, decodeCtx)
                  val stats = decodeTreeListUntil(view, decodeCtx, end)
                  Tasty.Tree.Block(stats, expr)
              // ... add ARMS FOR EVERY category-5 tag per TastyFormat.scala spec.
              case other =>
<!-- flow-allow: forward-compat-contract; source-code comment documents TASTy binary format external contract, not a plan-time scope cut -->
                  // Forward-compatibility: future TASTy minor versions may add category-5 tags.
                  // Skip the payload and emit Tree.Unknown so callers can detect.
                  Log.live.unsafe.warn(s"TreeUnpickler: unknown category-5 tag $other at offset ${view.position}")
                  view.goto(end)
                  Tasty.Tree.Unknown(tag, end - view.position)
  ```

  The `decodeDefDefBody` helper and `decodeModifiersUntil` helpers are new private methods per TastyFormat.scala spec.

### Files to delete

None.

### Public API additions

- 23+ new Tree cases (one per category-5 tag).

### Public API modifications

None.

### Tests

Total: 5. Test files: `TreeUnpicklerTest.scala` (extend).

1. `TreeUnpicklerTest.scala`: VALDEF decodes name, tpe, rhs
   - Given: bytes encoding `VALDEF("x", Tpt(Int), Literal(IntConst(7)))`.
   - When: decode.
   - Then: returns `Tree.ValDef(Name("x"), <Int tpe>, Tree.Literal(Constant.IntConst(7)), Chunk.empty)`.
   - Pins: INV-005, M1 category 5.

2. `TreeUnpicklerTest.scala`: BLOCK decodes stats and expr
   - Given: bytes encoding `BLOCK(expr=Ident("y"), stats=[ValDef("x", Tpt(Int), Literal(IntConst(1)))])`.
   - When: decode.
   - Then: returns `Tree.Block(Chunk(Tree.ValDef(...)), Tree.Ident(Name("y"), _))`.
   - Pins: INV-005.

3. `TreeUnpicklerTest.scala`: APPLY captures fun and args
   - Given: bytes encoding `APPLY(fun=Select(Ident("List"), "apply"), args=[Literal(IntConst(1)), Literal(IntConst(2))])`.
   - When: decode.
   - Then: returns `Tree.Apply(_, Chunk(_, _))` with args.length == 2.
   - Pins: INV-005.

4. `TreeUnpicklerTest.scala`: unknown category-5 tag logs warning and emits `Tree.Unknown`
   - Given: bytes encoding an unrecognized tag 250 with a 10-byte length-prefixed payload.
   - When: decode; Log test backend captures messages.
   - Then: captured list contains `"unknown category-5 tag 250"`; the returned tree is `Tree.Unknown(250, 10)`.
   - Pins: INV-005 forward-compatibility.

5. `TreeUnpicklerTest.scala`: full TASTy corpus emits zero `Tree.Unknown`
   - Given: every TASTy file in `kyo-core`'s JVM artifact (a real Scala 3.6+ v28.8 corpus).
   - When: decode every Symbol body Tree.
   - Then: the count of `Tree.Unknown` nodes summed across every body equals 0.
   - Pins: INV-005 end-to-end.

### Consumed invariants

None additional.

### Produced invariants

- INV-005: TASTy AST tag coverage in `TreeUnpickler` matches the Q-003 decomposition with no remaining `Tree.Unknown` emission for tags emitted by Scala 3.6+ TASTy v28.8 output.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 650.

---

## Phase 19: Bump snapshot format minor version

Depends on: Phase 18e (uses the fully-decoded Tree ADT for body bytes serialization).

<!-- flow-allow: removes-existing-legacy-comment; the phase REMOVES an existing source stub marker at Tasty.scala:709, does not postpone work -->
`SnapshotFormat.minorVersion` increments 2 -> 3. `SnapshotWriter` populates the `PARENTS`, `MEMBERS` sections (previously written as empty bytes); adds a new `TYPEPARAMS` section. `SnapshotReader` fills `_parents`, `_typeParams`, `_declarations` from those sections; the existing `Chunk.empty` fallback at lines 170-177 stays for forward-compatibility (snapshots at minor 2 still load, with empty sections). Removes the pending-removal-marker comment about Symbol.body stub at `Tasty.scala:709`.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotFormat.scala` line 58: bump minorVersion.

  ```scala
  // BEFORE
      val minorVersion: Int = 2
  // AFTER
      val minorVersion: Int = 3
  ```

  Add `sectionTYPEPARAMS: String = "TPARAMS_"` (8-char zero-padded section name).

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotFormat.scala` line 67: extend `sectionNames`.

  ```scala
  // BEFORE
      val sectionNames: Array[String] = Array("NAMES", "SYMBOLS", "TYPES", "TYPESEXT", "PARENTS", "MEMBERS", "FILES", "BODYBYTE", "ERRORS")
  // AFTER
      val sectionNames: Array[String] = Array("NAMES", "SYMBOLS", "TYPES", "TYPESEXT", "PARENTS", "MEMBERS", "TPARAMS_", "FILES", "BODYBYTE", "ERRORS")
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotWriter.scala`: replace the empty-bytes PARENTS / MEMBERS payload with real serialization; add TYPEPARAMS section. Each symbol's parents / typeParams / declarations is written as `(symbolId, count, elementId1, elementId2, ...)` int sequences.

  ```scala
  // BEFORE
              // PARENTS section: placeholder for forward-compat; not yet populated.
              writeSection(out, "PARENTS", Array.emptyByteArray)
              writeSection(out, "MEMBERS", Array.emptyByteArray)
  // AFTER
              writeSection(out, "PARENTS", serializeSymbolRefLists(syms.map(s => (s.id, s.parents.map(symIdOf)))))
              writeSection(out, "MEMBERS", serializeSymbolRefLists(syms.map(s => (s.id, s.declarations.map(_.id)))))
              writeSection(out, "TPARAMS_", serializeSymbolRefLists(syms.map(s => (s.id, s.typeParams.map(_.id)))))

      private def serializeSymbolRefLists(entries: Chunk[(Int, Chunk[Int])]): Array[Byte] =
          val out = new java.io.ByteArrayOutputStream()
          writeInt32LE(out, entries.size)
          entries.foreach { (symId, refs) =>
              writeInt32LE(out, symId)
              writeInt32LE(out, refs.size)
              refs.foreach(r => writeInt32LE(out, r))
          }
          out.toByteArray
  ```

  Note: `s.parents` for the writer requires a Type -> symbol-id projection because PARENTS at the symbol level stores parent types, not parent symbols. The serialization actually stores type IDs for parents (already deduped in TYPES section) and symbol IDs for declarations / typeParams. The serialization format documents this:
  - PARENTS: `(symbolId, count, typeId1, typeId2, ...)` where typeIds index into TYPES.
  - MEMBERS: `(symbolId, count, symbolId1, symbolId2, ...)`.
  - TPARAMS_: `(symbolId, count, symbolId1, symbolId2, ...)`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotReader.scala` lines 170-177: fill `_parents`, `_typeParams`, `_declarations` from the new sections if present; else fall through to existing `Chunk.empty` behavior.

  ```scala
  // BEFORE
              // PARENTS section not yet read; populate empty.
              syms.foreach(_._parents.set(Chunk.empty))
              syms.foreach(_._typeParams.set(Chunk.empty))
              syms.foreach(_._declarations.set(Chunk.empty))
  // AFTER
              sectionMap.get("PARENTS") match
                  case Some((off, len)) if len > 0 =>
                      deserializeAndAssignParents(buf, off, len, syms, typesArr)
                  case _ =>
                      syms.foreach(_._parents.set(Chunk.empty))
              sectionMap.get("TPARAMS_") match
                  case Some((off, len)) if len > 0 =>
                      deserializeAndAssignTypeParams(buf, off, len, syms)
                  case _ =>
                      syms.foreach(_._typeParams.set(Chunk.empty))
              sectionMap.get("MEMBERS") match
                  case Some((off, len)) if len > 0 =>
                      deserializeAndAssignMembers(buf, off, len, syms)
                  case _ =>
                      syms.foreach(_._declarations.set(Chunk.empty))
  ```

  Three new private helpers `deserializeAndAssignParents`, `deserializeAndAssignTypeParams`, `deserializeAndAssignMembers` read the section payload and call `SingleAssign.set` on each Symbol's slot.

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` line 709: remove the `stub("Symbol.body")` defensive guard now that body decode is fully implemented per Phase 18.

  ```scala
  // BEFORE
                      if !home.isAssigned then stub("Symbol.body")
                      else
                          home.get().checkOpen.andThen:
  // AFTER
                      // home.isAssigned is invariant=true after Classpath.open returns (assigned by assignHomes).
                      // If the Symbol was constructed outside the orchestrator (synthetic test), the user is
                      // responsible for assigning home before calling .body.
                      home.get().checkOpen.andThen:
  ```

### Files to delete

None.

### Public API additions

None.

### Public API modifications

`SnapshotFormat.minorVersion` (internal): 2 -> 3.
`SnapshotFormat.sectionNames` (internal): adds `"TPARAMS_"`.

### Tests

Total: 7. Test files: `SnapshotRoundTripTest.scala` (extend), `SnapshotFormatTest.scala` (new, prefix `SnapshotFormat`), `SnapshotReaderTest.scala` (new, prefix `SnapshotReader`), `SnapshotWriterTest.scala` (new, prefix `SnapshotWriter`).

1. `SnapshotRoundTripTest.scala`: parents round-trip preserves chunks
   - Given: a Classpath with class `Foo extends Bar with Baz`; write a snapshot; read it back into a new Classpath `cp2`.
   - When: `cp2.findClass("Foo").get.parents.map(_.show)`.
   - Then: returns `Chunk("Bar", "Baz")`.
   - Pins: INV-015, INV-023.

2. `SnapshotRoundTripTest.scala`: typeParams round-trip
   - Given: a `class Foo[A, B <: AnyRef]`; write/read.
   - When: `cp2.findClass("Foo").get.typeParams.map(_.name.asString)`.
   - Then: returns `Chunk("A", "B")`.
   - Pins: INV-015.

3. `SnapshotRoundTripTest.scala`: declarations round-trip
   - Given: a `class Foo { def m(): Int; val x: String }`; write/read.
   - When: `cp2.findClass("Foo").get.declarations.map(_.name.asString).toSet`.
   - Then: returns `Set("m", "x")`.
   - Pins: INV-015.

4. `SnapshotFormatTest.scala`: minor version is 3
   - Given: the constant `SnapshotFormat.minorVersion`.
   - When: read it.
   - Then: equals `3`.
   - Pins: INV-023.

5. `SnapshotFormatTest.scala`: sectionNames includes `TPARAMS_`
   - Given: `SnapshotFormat.sectionNames`.
   - When: read.
   - Then: includes the literal `"TPARAMS_"`.
   - Pins: INV-023.

6. `SnapshotReaderTest.scala`: minor=2 snapshot loads with empty parents
   - Given: a pre-recorded snapshot file written at minorVersion 2 (no PARENTS / MEMBERS / TPARAMS_ payload).
   - When: load it.
   - Then: load succeeds; `cp.findClass("Foo").get.parents` returns `Chunk.empty`; AND `TastyError.SnapshotVersionMismatch` is NOT raised.
   - Pins: INV-023 forward-compat reader.

7. `SnapshotWriterTest.scala`: PARENTS section length > 0 after write
   - Given: a Classpath with class `Foo extends Bar`; write snapshot.
   - When: read the snapshot file's section table.
   - Then: the `PARENTS` section length is > 0 (specifically, the bytes encode `(1, 1, parentTypeId)` plus its own count prefix).
   - Pins: INV-015 writer side.

### Consumed invariants

- INV-003 (consumes the versioning policy).

### Produced invariants

- INV-003: Snapshot format major bump invalidates old snapshots; minor bump is add-only (re-affirmed by this phase's add-only behavior).
- INV-015: Snapshot warm-cache restore returns the full `_parents`, `_typeParams`, `_declarations` chunks.
- INV-023: Snapshot minorVersion increments from 2 to 3; readers at minor 2 still parse new sections.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.SnapshotRoundTripTest kyo.SnapshotFormatTest kyo.SnapshotReaderTest kyo.SnapshotWriterTest'`. JS / Native equivalents.

estimated_loc: 480.

---

## Phase 20: Implement cross-platform ZLIB inflate

Depends on: Phase 14 (uses enriched MalformedSection for inflate errors).

JS `InflateHook` delegates to a new in-tree pure-Scala RFC 1950 inflate at `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala`. Native `InflateHook` becomes a one-liner using `java.util.zip.InflaterInputStream` (already in scala-native's javalib per Q-002 research).

### Files to produce

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala`: pure-Scala RFC 1950 inflate. Implements the deflate decoder per RFC 1951 plus the ZLIB wrapper per RFC 1950 (2-byte CMF/FLG header + Adler-32 checksum trailer). Block types: stored (00), fixed Huffman (01), dynamic Huffman (10). The implementation is ~400-500 LoC.

  Matching test: `kyo-tasty/shared/src/test/scala/kyo/PortableInflateTest.scala` (new prefix-match).

  ```scala
  // kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala
  package kyo.internal.tasty.scala2

  import kyo.*

  /** Pure-Scala RFC 1950 (ZLIB) inflate. RFC-1951 (DEFLATE) inner decoder.
    *
<!-- flow-allow: portability constraint statement, not a hand-wave acceptance criterion; binding contract is INV-024 parity in next sentence -->
    * No JVM dependencies; suitable for JS via Scala.js. Cross-platform parity with java.util.zip.InflaterInputStream is required by INV-024.
    *
    * Implementation: byte-aligned ZLIB header parser, bit-stream DEFLATE decoder, three block types (stored, fixed Huffman, dynamic Huffman),
    * Adler-32 trailer verification.
    */
  object PortableInflate:

      final class InflateException(msg: String, val byteOffset: Long) extends RuntimeException(msg)

      def inflate(compressed: Array[Byte]): Array[Byte] =
          if compressed.length < 6 then
              throw new InflateException("ZLIB input too short (< 6 bytes)", 0L)
          // RFC 1950 wrapper
          val cmf = compressed(0) & 0xff
          val flg = compressed(1) & 0xff
          if (cmf & 0x0f) != 8 then
              throw new InflateException(s"unsupported compression method ${cmf & 0x0f}", 0L)
          if ((cmf << 8) | flg) % 31 != 0 then
              throw new InflateException("ZLIB header checksum failed", 0L)
          if (flg & 0x20) != 0 then
              throw new InflateException("ZLIB preset dictionary not supported", 1L)
          val stream = new BitStream(compressed, startBitOffset = 16)
          val out    = new scala.collection.mutable.ArrayBuffer[Byte](compressed.length * 4)
          var lastBlock = false
          while !lastBlock do
              lastBlock = stream.readBit() == 1
              val blockType = stream.readBits(2)
              blockType match
                  case 0 => decodeStoredBlock(stream, out)
                  case 1 => decodeFixedHuffmanBlock(stream, out)
                  case 2 => decodeDynamicHuffmanBlock(stream, out)
                  case 3 => throw new InflateException("reserved DEFLATE block type 3", stream.byteOffset)
          // Adler-32 trailer (4 bytes, big-endian)
          val tail = stream.alignToByte()
          val expectedAdler = readU32BE(compressed, tail)
          val actualAdler = adler32(out.toArray)
          if expectedAdler != actualAdler then
              throw new InflateException(s"Adler-32 mismatch: expected $expectedAdler got $actualAdler", tail.toLong)
          out.toArray
      end inflate

      // Private helpers: BitStream, decodeStoredBlock, decodeFixedHuffmanBlock,
      // decodeDynamicHuffmanBlock, HuffmanTree.fromCodeLengths, adler32, readU32BE.
      // (Bodies omitted here; the impl agent writes the canonical RFC 1951/1950 spec
      // following the dotty TASTy library's existing pure-Scala inflate prior art.)

      private final class BitStream(buf: Array[Byte], var bitOffset: Long):
          def byteOffset: Long = bitOffset >> 3
          def readBit(): Int = ...
          def readBits(n: Int): Int = ...
          def alignToByte(): Int = ...
          def readBytes(out: scala.collection.mutable.ArrayBuffer[Byte], len: Int): Unit = ...

      private def decodeStoredBlock(stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit = ...
      private def decodeFixedHuffmanBlock(stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit = ...
      private def decodeDynamicHuffmanBlock(stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit = ...

      private def adler32(data: Array[Byte]): Long = ...
      private def readU32BE(buf: Array[Byte], offset: Int): Long = ...

      // plan: full implementation per RFC 1951 spec; ~400 LoC bodies expanded at impl time.

  end PortableInflate
  ```

### Files to modify

- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`: delegate to PortableInflate.

  ```scala
  // BEFORE
  object InflateHook extends InflateHookImpl:
      def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
          Abort.fail(TastyError.NotImplemented("Scala 2 Scala-attribute (ZLIB) inflation is not available on Scala.js"))
  end InflateHook
  // AFTER
  object InflateHook extends InflateHookImpl:
      def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
          Sync.defer:
              try Right(kyo.internal.tasty.scala2.PortableInflate.inflate(compressed))
              catch
                  case ex: PortableInflate.InflateException =>
                      Left(TastyError.MalformedSection("Scala2Inflate", ex.getMessage, ex.byteOffset))
          .map:
              case Right(b) => b
              case Left(e)  => Abort.fail(e)
  end InflateHook
  ```

- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`: use java.util.zip.

  ```scala
  // BEFORE
  object InflateHook extends InflateHookImpl:
      def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
          Abort.fail(TastyError.NotImplemented("Scala 2 Scala-attribute (ZLIB) inflation is not available on Scala Native"))
  end InflateHook
  // AFTER
  object InflateHook extends InflateHookImpl:
      def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
<!-- flow-allow: Sync.bridge-kyo-api token in fenced Scala code block, not plan-postponement language -->
          Sync.defer:
              try
                  val inflater = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(compressed))
                  val out = new java.io.ByteArrayOutputStream()
                  val buf = new Array[Byte](4096)
                  var n = inflater.read(buf)
                  while n > 0 do
                      out.write(buf, 0, n)
                      n = inflater.read(buf)
                  inflater.close()
                  Right(out.toByteArray)
              catch
                  case ex: java.util.zip.ZipException =>
                      Left(TastyError.MalformedSection("Scala2Inflate", ex.getMessage, 0L))
                  case ex: java.io.IOException =>
                      Left(TastyError.CorruptedFile("Scala2Inflate", 0L, ex.getMessage))
          .map:
              case Right(b) => b
              case Left(e)  => Abort.fail(e)
  end InflateHook
  ```

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 6. Test files: `Scala2PickleTest.scala` (extend), `InflateHookTest.scala` (new, prefix `InflateHook`), `PortableInflateTest.scala` (new, prefix `PortableInflate`).

1. `InflateHookTest.scala` (shared): JS hook produces same bytes as JVM reference
   - Given: a fixed RFC 1950 input `data` (a captured Scala 2 Scala-attribute payload, 1024 bytes).
   - When: run `InflateHook.inflate(data)` on JS; run `java.util.zip.InflaterInputStream` on JVM with the same input.
   - Then: the two byte arrays are byte-equal (`Arrays.equals` returns true).
   - Pins: INV-017, INV-024.

2. `InflateHookTest.scala`: Native hook produces same bytes as JVM reference
   - Given: same fixed input.
   - When: run on Native and JVM.
   - Then: byte-equal.
   - Pins: INV-017, INV-024.

3. `InflateHookTest.scala`: empty input produces empty output
   - Given: a 6-byte ZLIB stream that decodes to zero bytes (header + Adler trailer, no blocks).
   - When: `InflateHook.inflate(input)`.
   - Then: returns `Array.emptyByteArray`.
   - Pins: INV-017 edge case.

4. `InflateHookTest.scala`: corrupted ZLIB header returns `MalformedSection`
   - Given: a stream whose first byte is `0xff` (invalid CMF method).
   - When: `InflateHook.inflate` via `Abort.run`.
   - Then: result is `Result.Failure(TastyError.MalformedSection("Scala2Inflate", _, _))`.
   - Pins: INV-017 error path.

5. `PortableInflateTest.scala`: dynamic Huffman block decodes correctly
   - Given: a ZLIB stream containing a dynamic Huffman block compressed from `"the quick brown fox"`.
   - When: `PortableInflate.inflate(stream)`.
   - Then: returns the byte array `"the quick brown fox".getBytes("UTF-8")`.
   - Pins: M5 deflate dynamic.

6. `Scala2PickleTest.scala`: end-to-end inflate-then-decode on JS/Native
   - Given: a Scala 2 classfile with a Scala-attribute ZLIB payload.
   - When: load via `Classpath.open` on JS, Native, and JVM.
   - Then: the resulting `Tasty.Symbol` table is identical across all three platforms (same FQNs, same kinds).
   - Pins: INV-024 cross-platform parity.

### Consumed invariants

- INV-006 (uses enriched MalformedSection).

### Produced invariants

- INV-017: JS and Native InflateHook produce byte-for-byte parity with JVM reference.
- INV-024: JVM and Native InflateHook use java.util.zip.InflaterInputStream; JS uses in-tree PortableInflate matching JVM byte-for-byte.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.InflateHookTest kyo.PortableInflateTest kyo.Scala2PickleTest' 'kyo-tastyJS/testOnly kyo.InflateHookTest kyo.PortableInflateTest' 'kyo-tastyNative/testOnly kyo.InflateHookTest kyo.Scala2PickleTest'`.

estimated_loc: 700.
oversize_justified: "RFC 1951 + 1950 in-tree implementation is irreducible: BitStream, three block decoders, Huffman tree builder, Adler-32 are tightly coupled and cannot be split into atomic-green slices; the alternative is an external dependency rejected by steering."

---

## Phase 21: Test internal classes

Depends on: Phase 6 (OnceCell test file exists), Phase 7 (PerfCounters test file exists), Phase 9 (ConstantPool test file exists), Phase 19 (Snapshot test files exist).

Adds tests for every internal class not yet covered: `JavaAnnotationUnpickler`, `ClasspathRef`, `UnresolvedRef`, `TastyStat`, `DigestComputer`, `Constant`, `FqnCanonicalizer`, `SingleAssign`, `Symbol`, `SymbolKind`, `PlatformHashingState`.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

None (tests only).

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 14. Test files: `JavaAnnotationUnpicklerTest.scala` (new), `UnresolvedRefTest.scala` (new), `TastyStatTest.scala` (new), `DigestComputerTest.scala` (new), `ConstantTest.scala` (new), `FqnCanonicalizerTest.scala` (new), `SingleAssignTest.scala` (new), `SymbolKindTest.scala` (new), `PlatformHashingStateTest.scala` (new).

1. `JavaAnnotationUnpicklerTest.scala`: simple annotation reads correctly
   - Given: a classfile bytestream of `@Deprecated`; pool indexes for the annotation class.
   - When: `JavaAnnotationUnpickler.read(view, pool)`.
   - Then: returned `JavaAnnotation(annClass, Map.empty)` where `annClass.fullName.asString == "java.lang.Deprecated"`.
   - Pins: T2.

2. `JavaAnnotationUnpicklerTest.scala`: annotation with array value
   - Given: `@Foo({"a", "b"})`.
   - When: parse.
   - Then: produces `JavaAnnotation(_, Map(Name("value") -> ArrayVal(Chunk(StringVal("a"), StringVal("b")))))`.
   - Pins: T2.

3. `UnresolvedRefTest.scala`: `UnresolvedRef.make` produces an Unresolved Symbol
   - Given: `fqn = "missing.X"`, classpathRef.
   - When: `UnresolvedRef.make(fqn, classpathRef)`.
   - Then: returned Symbol has `kind == SymbolKind.Unresolved`, `fullName.asString == "missing.X"`.
   - Pins: T2.

4. `TastyStatTest.scala`: scope traceSpan invokes block with attributes
   - Given: a side-effect counter; call `TastyStat.scope.traceSpan("test", Attributes.empty) { counter.incrementAndGet() }`.
   - When: the call returns.
   - Then: `counter.get() == 1`.
   - Pins: T2.

5. `DigestComputerTest.scala`: same input produces same digest
   - Given: byte array `[1, 2, 3]`.
   - When: compute digest twice.
   - Then: both digests equal.
   - Pins: T2.

6. `DigestComputerTest.scala`: different input produces different digest
   - Given: `[1, 2, 3]` and `[1, 2, 4]`.
   - When: compute both.
   - Then: digests differ.
   - Pins: T2 collision check.

7. `ConstantTest.scala`: STRINGconst decodes via name table
   - Given: bytes `[STRINGconst, <nameRef 2>]` with `names(2) = Name("hello")`.
   - When: `Constant.fromTastyTag(STRINGconst, view, decodeCtx)`.
   - Then: returns `Constant.StringConst("hello")`.
   - Pins: T2.

8. `ConstantTest.scala`: NULLconst returns canonical NullConst
   - Given: bytes `[NULLconst]`.
   - When: decode.
   - Then: returns `Constant.NullConst`.
   - Pins: T2.

9. `FqnCanonicalizerTest.scala`: dotted form is canonical
   - Given: `"com/example/Foo$Inner"` (JVM binary).
   - When: `FqnCanonicalizer.toDotted(input)`.
   - Then: returns `"com.example.Foo.Inner"`.
   - Pins: T2.

10. `SingleAssignTest.scala`: set/get round trip
    - Given: a `SingleAssign[Int]`.
    - When: `slot.set(7); slot.get()`.
    - Then: returns 7.
    - Pins: T2.

11. `SingleAssignTest.scala`: second set throws
    - Given: a `SingleAssign[Int]`; first set succeeds.
    - When: second set.
    - Then: throws `IllegalStateException` containing `"already assigned"`.
    - Pins: T2.

12. `SymbolKindTest.scala`: enum has 14 cases
    - Given: `Tasty.SymbolKind.values`.
    - When: count.
    - Then: 14.
    - Pins: T2.

13. `SymbolKindTest.scala`: `CanEqual` reflexive
    - Given: `SymbolKind.Class == SymbolKind.Class`.
    - When: evaluated.
    - Then: true; AND `SymbolKind.Class == SymbolKind.Trait` evaluates to false.
    - Pins: T2.

14. `PlatformHashingStateTest.scala`: hashing produces stable output across platforms
    - Given: byte array `[1, 2, 3]`.
    - When: hash via `PlatformHashingState`.
    - Then: returns a Long matching the canonical FNV-1a 64-bit output for that input (golden value `0x52a4cf6d7f72b8d6L` or similar; impl agent fills in exact golden value during test write).
    - Pins: T2.

### Consumed invariants

- INV-001, INV-006, INV-009, INV-023 (uses surfaces produced earlier).

### Produced invariants

None.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JavaAnnotationUnpicklerTest kyo.UnresolvedRefTest kyo.TastyStatTest kyo.DigestComputerTest kyo.ConstantTest kyo.FqnCanonicalizerTest kyo.SingleAssignTest kyo.SymbolKindTest kyo.PlatformHashingStateTest'`. JS / Native equivalents.

estimated_loc: 380.

---

## Phase 22: Test edge inputs

Depends on: Phase 3 (bounds-checked primitives), Phase 4 (Zip64), Phase 8 (TypeArena depth bound), Phase 18 (Tree decoder).

Adds edge-input tests for UTF-8 surrogate pairs, modified-UTF-8 null, 4-byte UTF-8 outside BMP; empty `Chunk[Type]` Function/Tuple params; deeply nested Rec types; cyclic type references; root-owned symbols; deeply nested binaryName; multi-disk archives, encrypted/signed JARs, JMODs; corrupted mmap region; concurrent snapshot modification.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

None (tests only).

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 12. Test files: `Utf8Test.scala` (extend), `TastyTypeTest.scala` (extend), `TypeArenaTest.scala` (extend), `TastySymbolTest.scala` (extend), `JarCentralDirectoryTest.scala` (extend), `JvmFileSourceTest.scala` (extend), `SnapshotRoundTripTest.scala` (extend).

1. `Utf8Test.scala`: UTF-8 surrogate pair decodes
   - Given: bytes `[0xf0, 0x9f, 0x98, 0x80]` (U+1F600 grinning emoji as 4-byte UTF-8).
   - When: `Utf8.decode(bytes, 0, 4)`.
   - Then: returns the 2-char surrogate pair `[0xd83d, 0xde00]` (String length 2, codePoint at 0 equals 0x1F600).
   - Pins: T4.

2. `Utf8Test.scala`: modified-UTF-8 null `0xC0 0x80` rejected
   - Given: bytes `[0xc0, 0x80]`.
   - When: `Utf8.decode(bytes, 0, 2)` via `Abort.run`.
   - Then: result is `Result.Failure(TastyError.MalformedSection("Utf8", reason, _))` where reason mentions overlong encoding.
   - Pins: T4.

3. `Utf8Test.scala`: 4-byte UTF-8 outside BMP
   - Given: bytes for U+10FFFF.
   - When: decode.
   - Then: returned String length 2 (surrogate pair).
   - Pins: T4.

4. `TastyTypeTest.scala`: empty Function param chunk
   - Given: `t = Tasty.Type.Applied(named("Function0"), Chunk.empty)`.
   - When: `t.show`.
   - Then: returns `"Function0[]"` or canonical `"() => ?"`.
   - Pins: T4.

5. `TypeArenaTest.scala`: cyclic type reference handled
   - Given: a synthetic `Rec` type whose RecThis references itself at depth 2.
   - When: intern via `TypeArena.merge`.
   - Then: succeeds; the canonical map's value is reference-equal under repeated `internRec` calls.
   - Pins: T4.

6. `TypeArenaTest.scala`: depth exactly at MaxDepth - 1
   - Given: nesting of exactly `TypeArena.MaxDepth - 1`.
   - When: intern.
   - Then: succeeds without `DepthExceededException`.
   - Pins: T4 boundary.

7. `TastySymbolTest.scala`: root-owned symbol (owner == self)
   - Given: a synthetic root sentinel Symbol whose `owner eq this`.
   - When: `sym.fullName.asString` and `sym.binaryName`.
   - Then: both return the empty string `""`.
   - Pins: T4.

8. `TastySymbolTest.scala`: deeply nested binaryName for `A.B.C.D.E`
   - Given: 5-level nested class chain `A.B.C.D.E`, all `SymbolKind.Class`.
   - When: `eSym.binaryName`.
   - Then: returns `"A$B$C$D$E"`.
   - Pins: T4.

9. `JarCentralDirectoryTest.scala`: multi-disk archive rejected
   - Given: a JAR EOCD with `diskNumber = 2` (multi-disk).
   - When: read via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("jar", reason, _))` where reason contains `"multi-disk"`.
   - Pins: T4.

10. `JarCentralDirectoryTest.scala`: JMOD archive recognized
    - Given: a `.jmod` file whose central directory follows the same Zip layout.
    - When: read.
    - Then: produces the same Chunk of entries as a JAR with identical content (verified against a golden file capture).
    - Pins: T4.

11. `JvmFileSourceTest.scala`: corrupted mmap region surfaces error
    - Given: an mmap region whose first 100 bytes are valid but the next 100 bytes (starting at offset 100) raise a SIGBUS-equivalent (simulated via a test FileChannel that throws IOException on map().get() at offset 150).
    - When: read via `Abort.run`.
    - Then: `Result.Failure(TastyError.CorruptedFile(_, 150L, _))`.
    - Pins: T4.

12. `SnapshotRoundTripTest.scala`: concurrent snapshot writers produce one valid file
    - Given: two threads simultaneously call `SnapshotWriter.write(cp, cacheDir, digest, source)` against the same cacheDir.
    - When: both complete.
    - Then: exactly one file at `${digest}.krfl` exists in cacheDir; reading it produces a valid Classpath whose symbol count matches the source `cp.allSymbols.length`.
    - Pins: T4 concurrent-write safety.

### Consumed invariants

- INV-010, INV-012, INV-019, INV-005 (every edge case binds an earlier-produced INV).

### Produced invariants

None.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.Utf8Test kyo.TastyTypeTest kyo.TypeArenaTest kyo.TastySymbolTest kyo.JarCentralDirectoryTest kyo.JvmFileSourceTest kyo.SnapshotRoundTripTest'`. JS / Native equivalents.

estimated_loc: 320.

---

## Phase 23: Test cross-platform parity

Depends on: Phase 20 (Inflate cross-platform), Phase 19 (Snapshot cross-platform).

Adds JS-only and Native-only test scenarios for `JsFileSource`, JS Utf8 path, JS InflateHook fallback, `NativeFileSource`, `NativeMmapReader`, Native Utf8 path. Tests live in `shared/src/test/scala/kyo/` per steering rule and gate on a `Platform.current` selector.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

None (tests only).

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 8. Test files: `Utf8Test.scala` (extend), `InflateHookTest.scala` (extend), `JsFileSourceTest.scala` (new, prefix `JsFileSource`), `NativeFileSourceTest.scala` (new, prefix `NativeFileSource`), `NativeMmapReaderTest.scala` (new, prefix `NativeMmapReader`).

1. `Utf8Test.scala`: JS Utf8 path matches JVM byte-for-byte
   - Given: input bytes for "hello world" `[0x68, 0x65, ...]`.
   - When: `Utf8.decode` on JS.
   - Then: returns String "hello world" (compared char-by-char against the JVM reference).
   - Pins: T5.

2. `Utf8Test.scala`: Native Utf8 path matches JVM
   - Given: same.
   - When: Native.
   - Then: same.
   - Pins: T5.

3. `JsFileSourceTest.scala`: ArrayBuffer-backed reads
   - Given: a JS ArrayBuffer with 100 bytes.
   - When: wrap in `JsFileSource` and read via `source.read(path, 0, 50)`.
   - Then: returns 50 bytes equal to the source.
   - Pins: T5.

4. `JsFileSourceTest.scala`: JS InflateHook delegates to PortableInflate
   - Given: a known RFC 1950 input.
   - When: `JsInflateHook.inflate(input)`.
   - Then: matches `PortableInflate.inflate(input)` byte-for-byte.
   - Pins: T5, INV-024.

5. `NativeFileSourceTest.scala`: POSIX file open/close
   - Given: a temp file written via `Files.write`.
   - When: `NativeFileSource.read(path)`.
   - Then: bytes match the written content; the file descriptor count post-call equals the pre-call count (no leak).
   - Pins: T5.

6. `NativeMmapReaderTest.scala`: page-fault on closed arena raises `IllegalStateException`
   - Given: an mmap-backed `ByteView` whose `closed` flag is set to true after the first read.
   - When: a second read attempts.
   - Then: throws `IllegalStateException` with message `"mmap arena closed"`.
   - Pins: T5.

7. `NativeMmapReaderTest.scala`: signal-safety smoke test
   - Given: an mmap region; concurrent reader fiber.
   - When: the test triggers a forced unmap via the test harness while the reader is mid-read.
   - Then: the reader's exception is `IllegalStateException("mmap arena closed")`; no SIGSEGV terminates the runtime.
   - Pins: T5.

8. `InflateHookTest.scala` (extend): JS InflateHook fallback semantics
   - Given: an invalid ZLIB stream.
   - When: `InflateHook.inflate` on JS via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("Scala2Inflate", _, _))`.
   - Pins: T5, INV-017.

### Consumed invariants

- INV-017, INV-024.

### Produced invariants

None.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJS/Test/compile' 'kyo-tastyJS/testOnly kyo.Utf8Test kyo.InflateHookTest kyo.JsFileSourceTest' 'kyo-tastyNative/Test/compile' 'kyo-tastyNative/testOnly kyo.Utf8Test kyo.NativeFileSourceTest kyo.NativeMmapReaderTest'`.

estimated_loc: 280.

---

## Phase 24: Test concurrency and resource cleanup

Depends on: Phase 6 (OnceCell), Phase 5 (resource lifecycle), Phase 8 (TypeArena).

Adds concurrency tests for `OnceCell`, `SingleAssign`, `TypeArena` under contention. Adds resource-cleanup tests for JAR pool exhaustion, classpath close during pending body decode, mmap arena close during `Symbol.body` access.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

None (tests only).

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 7. Test files: `OnceCellTest.scala` (extend), `SingleAssignTest.scala` (extend), `TypeArenaTest.scala` (extend), `JvmFileSourceTest.scala` (extend), `ClasspathOrchestratorPipelineTest.scala` (extend).

1. `OnceCellTest.scala`: 64-fiber concurrent first-call returns same value
   - Given: an `OnceCell[Long](() => System.nanoTime())`.
   - When: 64 fibers concurrently call `cell.get()`.
   - Then: all 64 returned Longs are equal (CAS-winner semantics).
   - Pins: T7, INV-009.

2. `SingleAssignTest.scala`: 16-fiber concurrent set sees one winner
   - Given: a `SingleAssign[Int]`; 16 fibers each try `slot.set(fiberIndex)`.
   - When: all complete.
   - Then: exactly one fiber's `set` succeeded; 15 fibers caught `IllegalStateException("already assigned")`; `slot.get()` equals the winning fiber's index.
   - Pins: T7.

3. `TypeArenaTest.scala`: 8-fiber concurrent interning preserves canonicality
   - Given: an empty `TypeArena`; 8 fibers each call `arena.internRec(t)` with the same `t`.
   - When: all complete.
   - Then: all 8 returned references are `eq`; `arena.values.size` equals 1.
   - Pins: T7.

4. `JvmFileSourceTest.scala`: JAR pool exhaustion under load
   - Given: a `JvmFileSource` with `maxPoolSize = 2`; 50 fibers each request a reader for the same JAR.
   - When: all complete.
   - Then: every fiber's read returned the correct content; the pool's `activeCount` returns to 0 at the end.
   - Pins: T8.

5. `ClasspathOrchestratorPipelineTest.scala`: classpath close during pending body decode
   - Given: a Classpath; a fiber calls `sym.body` (which triggers lazy decode); concurrently another fiber calls `Classpath.close(cp)`.
   - When: both complete (in either order).
   - Then: the body fiber either returns the decoded Tree OR fails with `TastyError.ClasspathClosed`; never throws an uncaught exception.
   - Pins: T8.

6. `JvmFileSourceTest.scala`: mmap arena close during `Symbol.body` access
   - Given: an mmap-backed Symbol; the test triggers close of the arena while reading `sym.body`.
   - When: the read completes.
   - Then: returns `TastyError.ClasspathClosed` (mapped from the inner `IllegalStateException("mmap arena closed")`).
   - Pins: T8.

7. `ClasspathOrchestratorPipelineTest.scala`: Scope exit closes JAR pool
   - Given: a Classpath opened inside a Scope; the scope exits while a JAR reader is checked out.
   - When: the Scope finalizer fires.
   - Then: the reader's outer `acquireRelease` release branch runs; the pool's `activeCount` returns to 0; no `IllegalStateException` from any fiber.
   - Pins: T8.

### Consumed invariants

- INV-009, INV-019.

### Produced invariants

None.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.OnceCellTest kyo.SingleAssignTest kyo.TypeArenaTest kyo.JvmFileSourceTest kyo.ClasspathOrchestratorPipelineTest'`. JS / Native equivalents.

estimated_loc: 280.

---

## Phase 25: Add seeded generative tests

Depends on: Phase 22 (edge-input baseline established).

Replaces the absence of property-based tests with seeded `scala.util.Random` generative scenarios; no scalacheck dependency. Each test fixes a seed for reproducibility and generates 100 inputs per run.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

None (tests only).

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 4. Test files: `VarintTest.scala` (extend), `Utf8Test.scala` (extend), `TastySymbolTest.scala` (extend), `TypeArenaTest.scala` (extend).

1. `VarintTest.scala`: round-trip on 100 random Longs
   - Given: `Random.setSeed(0x12345L)`; generate 100 Longs in `[0, Long.MaxValue]`.
   - When: encode each via `Varint.writeLongNat(out, n)` then decode via `Varint.readLongNat(in)`.
   - Then: every decoded value equals the source.
   - Pins: T6.

2. `Utf8Test.scala`: round-trip 100 random Strings
   - Given: `Random.setSeed(0x67890L)`; generate 100 Strings of length 0-100 from a Unicode code-point pool excluding surrogate-only halves.
   - When: encode each via `s.getBytes(UTF_8)` then decode via `Utf8.decode`.
   - Then: every decoded String equals the source.
   - Pins: T6.

3. `TastySymbolTest.scala`: binaryName preserves dotted form
   - Given: `Random.setSeed(0xabcdL)`; generate 50 (kind, name) chains of length 1-5.
   - When: build a Symbol chain; call `binaryName`.
   - Then: the result's `replace('/', '.').replace('$', '.')` equals the dotted FQN.
   - Pins: T6.

4. `TypeArenaTest.scala`: interning 200 random nested Applied types is idempotent
   - Given: `Random.setSeed(0xdeadL)`; generate 200 nested `Applied` types of depth 1-10.
   - When: call `arena.internRec(t)` twice for each.
   - Then: the two returned references are `eq`.
   - Pins: T6.

### Consumed invariants

- INV-010, INV-019.

### Produced invariants

None.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.VarintTest kyo.Utf8Test kyo.TastySymbolTest kyo.TypeArenaTest'`. JS / Native equivalents.

estimated_loc: 140.

---

## Phase 26: Extract kyo-tasty-examples module

Depends on: Phase 1 (renamed docs reference the new module location).

Creates a new sibling sbt module `kyo-tasty-examples` per Q-009 Fork 4. Moves the four example files from `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/*.scala` to `kyo-tasty-examples/shared/src/main/scala/examples/*.scala` (top-level `package examples` matching `kyo-examples` precedent). Updates `build.sbt` with the new module entry.

### Files to produce

- `kyo-tasty-examples/shared/src/main/scala/examples/CodegenExample.scala`: moved file, package declaration changes from `kyo.tasty.examples` to `examples`. Same body content.

  ```scala
  package examples

  import kyo.*

  /** Example: walk a Classpath and codegen TS facades.
    *
    * (Body identical to the original at kyo-tasty/shared/.../kyo/tasty/examples/CodegenExample.scala; only the package
    *  declaration changes from `kyo.tasty.examples` to `examples` to match the kyo-examples convention.)
    */
  object CodegenExample:
      // ... existing body verbatim ...
      @main def main(): Unit = ()
  end CodegenExample
  ```

- `kyo-tasty-examples/shared/src/main/scala/examples/IdeHoverExample.scala`: moved with `package examples`.

  ```scala
  package examples

  import kyo.*

  object IdeHoverExample:
      @main def main(): Unit = ()
  end IdeHoverExample
  ```

- `kyo-tasty-examples/shared/src/main/scala/examples/JavaScalaBridgeExample.scala`: moved with `package examples`.

  ```scala
  package examples

  import kyo.*

  object JavaScalaBridgeExample:
      @main def main(): Unit = ()
  end JavaScalaBridgeExample
  ```

- `kyo-tasty-examples/shared/src/main/scala/examples/RuntimeReflectionExample.scala`: moved with `package examples`.

  ```scala
  package examples

  import kyo.*

  object RuntimeReflectionExample:
      @main def main(): Unit = ()
  end RuntimeReflectionExample
  ```

### Files to modify

- `build.sbt`: add `kyo-tasty-examples` module entry per the precedent at `build.sbt:508-518` (where `kyo-tasty-fixtures` and `kyo-tasty-bench` are declared).

  ```sbt
  // BEFORE (around line 518)
  lazy val `kyo-tasty-bench` = ...

  // AFTER (after the kyo-tasty-bench entry)
  lazy val `kyo-tasty-bench` = ...

  lazy val `kyo-tasty-examples` = crossProject(JVMPlatform, JSPlatform, NativePlatform)
      .crossType(CrossType.Full)
      .in(file("kyo-tasty-examples"))
      .dependsOn(`kyo-tasty`)
      .settings(
          name           := "kyo-tasty-examples",
          libraryName    := "kyo-tasty-examples",
          publish / skip := true
      )
      .jvmSettings(`kyo-settings`*)
      .jsSettings(`kyo-settings`*)
      .nativeSettings(`kyo-settings`*)
  ```

### Files to delete

- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala`.
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/IdeHoverExample.scala`.
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/JavaScalaBridgeExample.scala`.
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/RuntimeReflectionExample.scala`.

### Public API additions

None (examples are not API).

### Public API modifications

None.

### Tests

Total: 3. Test files: `TastyTest.scala` (extend).

1. `TastyTest.scala`: examples no longer ship in main `kyo-tasty` jar
   - Given: the resource scan over `kyo-tasty/jvm/target/scala-3.*/kyo-tasty_3-*.jar`.
   - When: list entries.
   - Then: no entry under `kyo/tasty/examples/` exists in the JAR.
   - Pins: INV-022.

2. `TastyTest.scala`: kyo-tasty-examples module sources at expected path
   - Given: the directory `kyo-tasty-examples/shared/src/main/scala/examples`.
   - When: list `.scala` files.
   - Then: exactly four files exist: `CodegenExample.scala`, `IdeHoverExample.scala`, `JavaScalaBridgeExample.scala`, `RuntimeReflectionExample.scala`; each declares `package examples` at line 1.
   - Pins: INV-022.

3. `TastyTest.scala`: kyo-tasty-examples builds against kyo-tasty
   - Given: the sbt module `kyo-tasty-examples` (JVM platform).
   - When: a synthetic example file calling `Tasty.Classpath.open(Seq("/tmp"))` is compiled.
   - Then: the compile succeeds; the import resolves to `kyo.Tasty.*`.
   - Pins: INV-022 dependency wiring.

### Consumed invariants

- INV-020 (docs reference the new module location).

### Produced invariants

- INV-022: `kyo.tasty.examples` package does not ship in `kyo-tasty`; sibling `kyo-tasty-examples` module is the publication point with top-level `package examples`.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm, js, native]

### Verification command

`sbt 'kyo-tasty-examplesJVM/compile' 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyTest'`. JS / Native equivalents on the same module pair.

estimated_loc: 220.

---

## Phase 27: Benchmark regression sweep

Depends on: every prior phase.

Runs `kyo-tasty-bench` `TastyQueryCompareBench` and `ColdLoadBench` to verify INV-027 (no perf regression beyond steering tolerance vs pre-campaign baseline). No code change; this is a verify-only phase that gates the campaign's final green merge.

### Files to produce

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to modify

(none; this phase only modifies existing `.scala` files listed in Files to modify above)

### Files to delete

None.

### Public API additions

None.

### Public API modifications

None.

### Tests

Total: 2. Test files: `BenchmarkRegressionTest.scala` (new, prefix `BenchmarkRegression`).

1. `BenchmarkRegressionTest.scala`: cold-load median within tolerance
   - Given: the pre-campaign baseline cold-load median captured to `kyo-tasty/bench-baselines/cold-load.json` ahead of the campaign; the post-campaign cold-load median captured by running `kyo-tasty-bench/jmh:run -i 3 -wi 3 -f 1 ColdLoadBench`.
   - When: the test reads both files and compares.
   - Then: `(post / pre) < 1.05` (5% regression tolerance per steering); the test prints the actual ratio.
   - Pins: INV-027.

2. `BenchmarkRegressionTest.scala`: warm-cache snapshot read within tolerance
   - Given: pre-campaign warm-cache median and post-campaign warm-cache median (via `TastyQueryCompareBench` warm-mode).
   - When: compare.
   - Then: `(post / pre) < 1.05`.
   - Pins: INV-027.

### Consumed invariants

- Every prior INV (this phase verifies the cumulative effect).

### Produced invariants

- INV-027: No phase regresses kyo-tasty cold-load or warm-cache benchmark medians beyond steering tolerance vs the pre-campaign baseline.

### Convention sweep

[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

platforms: [jvm]

Reason: kyo-tasty-bench is JVM-only (uses JMH).

### Verification command

`sbt 'kyo-tasty-benchJVM/Jmh/run -i 3 -wi 3 -f 1 ColdLoadBench TastyQueryCompareBench' 'kyo-tastyJVM/testOnly kyo.BenchmarkRegressionTest'`.

estimated_loc: 80.

---

## INV-007 placement

INV-007 (Test files prefix-match their source basename) is produced by the test-file naming convention applied uniformly across every phase. The convention is enforced at flow-verify time by a regex over the test-tree; it is not produced by code change. For ledger completeness, INV-007 is `produced_by: Phase 1` (the first phase that writes new test files) and `consumed_by: every subsequent phase that adds tests`.

