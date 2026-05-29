# kyo-tasty audit findings inventory

Source: six audit agents executed in conversation context (correctness,
completeness, clarity, potential bugs, test coverage, CONTRIBUTING.md
adherence). Every finding carries a file:line anchor. The 48 codes
below are the authoritative checklist for the audit-fix campaign;
flow-design, flow-resolve-open, flow-invariants, flow-plan, and
flow-validate all bind to this inventory.

Encoding convention:
- C = correctness
- M = completeness (missing features)
- L = clarity
- B = potential bugs (latent / edge-case)
- T = test coverage gaps
- A = CONTRIBUTING.md adherence

## Correctness findings

- **C1**. `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala:140,142,174,189,342,345,526,560,570`. `.toInt` truncation on Long file or central-directory offsets. JARs > 2GB with central directory past 2GB silently mis-read. Same shape at line 570 for the Zip64 EOCD offset.
- **C2**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala:42`. CAS-without-check then unconditional `ref.get()`. Safe only if `init()` is idempotent (documented at line 18 but not enforced).
- **C3**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala:217-223`. Utf8Lazy eagerly copies bytes from Heap ByteView and rejects Mapped ByteView with an error. If classfile reading is later wired to mmap, the lazy decode throws IllegalStateException when the buffer is unmapped.
- **C4**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/SectionIndex.scala:54`. `names(nameRef).asString` no bounds check. On malformed input throws ArrayIndexOutOfBoundsException reported as misleading "unexpected end".

## Completeness findings

- **M1**. `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:224`. `Symbol.body` decodes only IDENT, SELECT, APPLY, TYPEAPPLY, BLOCK Tree tags; the remaining tags map to `Tree.Unknown(tag, length)` with payload discarded.
- **M2**. `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:181-184`. `Annotation.argsPickle` lazy decode returns `Abort.fail(TastyError.NotImplemented("annotation args decode requires file decode context"))` when accessed outside the unpickler boundary. Callers cannot read deprecation messages or custom annotation parameters.
- **M3**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Constant.scala:81-85`. CLASSconst type reference is skipped at decode (line 83: `skipTree(view)`); returns placeholder `ClassConst(Type.Named(classConstSentinel))`. Comment references a Phase 4 that was never built.
- **M4**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotReader.scala:170-177`. Snapshot restore populates `_parents`, `_typeParams`, `_declarations` with `Chunk.empty`. Warm-cache relationship queries return empty for all symbols.
- **M5**. `kyo-tasty/{js,native}/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`. Scala 2 "Scala" attribute (ZLIB) returns `TastyError.NotImplemented` on JS and Native. JVM-only via `java.util.zip.InflaterInputStream`.
- **M6**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala:18,144`. Rec-unfolding budget of 64 steps; over-budget returns conservative `false`. Partial-classpath cases also return `false`. Caller cannot distinguish "not a subtype" from "unknown".
- **M7**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TypeUnpickler.scala:593,598`. Unknown TASTy type tag silently becomes `Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other", ctx.home))`. No warning logged.
- **M8**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala`. Handled classfile attributes: Signature, InnerClasses, EnclosingMethod, Record, Exceptions, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, ScalaSig, Scala. Not handled: BootstrapMethods, NestHost, NestMembers, PermittedSubclasses, MethodParameters, RuntimeTypeAnnotations.
- **M9**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/Scala2PickleReader.scala:260-275`. Handled entry types: NONEsym, TYPEsym, ALIASsym, CLASSsym, MODULEsym, VALsym. Not handled: EXTref (7), EXTMODCLASSref (8).
- **M10**. TODO/FIXME inventory to resolve as part of M3, M4, and L4: `Constant.scala:81` ("Phase 4"), `SnapshotReader.scala:170-171` (future format version), `Tasty.scala:709` (defensive `stub("Symbol.body")` guard).

## Clarity findings

- **L1**. `kyo-tasty/README.md:1` and `kyo-tasty/DESIGN.md:1`. Title says "kyo-reflect" but the module is "kyo-tasty". Cache directory references mention `.kyo-reflect-cache`.
- **L2**. `kyo-tasty/README.md:11,13-15,35-41,49,52-76`. Code examples reference `Reflect.*` / `ReflectError`; the actual public API is `Tasty.*` / `TastyError`. Code copy-pasted from README does not compile.
- **L3**. `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:899-911`. `Classpath.open` and `openCached` document `Sync & Async & Scope & Abort[TastyError]` inline but do not explain why Async (parallel per-file decode) and Scope (file-handle finalization) are required.
- **L4**. `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:78,1012` and `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:19`. Production-code comments reference "Phase 3", "Phase C", "Phase 0", mixing payload concerns with delivery phase metadata.
- **L5**. `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala:1-22`. `MalformedSection(name: String, reason: String)` uses an untyped reason string. Unpickler errors embed raw exception messages ("unexpected end: null") instead of structured payloads such as byte offset.
- **L6**. `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:48,72,1014-1047`. Missing doctests on `Name.apply`, `Flags.empty`, `Classpath.findClass`, `topLevelClasses`, `packages`, and other public methods.
- **L7**. `kyo-tasty/DESIGN.md:9-34`. Section 1 "Goals" mixes user-facing goals (read Scala 3 TASTy) with performance targets (better cold-load than tasty-query).

## Potential-bug findings

- **B1**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/NameUnpickler.scala:74-100`. `buf(prefix)`, `buf(selector)`, `buf(separator)` index into the name table without bounds checks. Malformed TASTy with QUALIFIED, EXPANDED, EXPANDPREFIX, or UNIQUE tags pointing past name-table size throws uncaught ArrayIndexOutOfBoundsException; the catch at line 49 only covers table reading.
- **B2**. `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala:65`. `entry.lfhOffset.toInt + 30 + nameLen + extraLen`. With lfhOffset near Int.MaxValue the sum wraps past the actual offset; subsequent inflate reads from the wrong position or allocates the wrong-sized array (lines 72, 85).
- **B3**. `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala:140,174`. `(eocdOffset - cenOffset).toInt.max(0)` truncates if the central directory exceeds 2GB; subsequent `buf.get(cenBuf)` underruns or reads stale.
- **B4**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala:42-51`. `readLongNat` with 10 continuation bytes shifts past the sign bit, silently producing a negative value that a caller may treat as an address.
- **B5**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala:66-77,82,102`. `entry(idx)` validates range only; cross-entry references (such as ClassRef.nameIdx expecting Utf8) are not re-typed. Malformed classfile with a wrong-type reference falls through to a cryptic match failure.
- **B6**. `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala:41,48,56,58`. Fields `start`, `end`, `cursor` are Long; `peekByte(at: Int)`, `readEnd(): Int`, `position: Int`, `goto(addr: Int)` accept or return Int. Snapshot files larger than 2GB or mapped at offsets larger than 2GB silently corrupt reads.
- **B7**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala:90-92`. `cursor + len` where `len` is a Nat from Varint can overflow Int to negative; subsequent `goto` and `subView` calls bypass bounds checks.
- **B8**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/TypeArena.scala:81-96`. `internRec` is recursive; cycle detection via `inProgress` handles cycles but does not bound depth. Pathologically nested `Applied(Applied(...))` produces StackOverflowError.
- **B9**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/PositionsUnpickler.scala:82`. `lineStarts(k + 1) = lineStarts(k) + lineSizes(k) + 1` cumulative Int overflow on very large files; negative offsets break position math.
- **B10**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala:125-134`. `bytesEqual` length check ensures slice length matches but does not verify `offset + length <= bytes.length`. Buggy caller crashes on the byte read.
- **B11**. `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala` (`parseAllEntries`). Truncated record with declared length larger than remaining bytes advances past the malformed record; silently omits entries.
- **B12**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala:101-122`. `growShard` has a double-check window between the un-synchronized observe (line 72) and entering `shardRef.synchronized` (line 101). Correct under normal load; pathological contention yields a silent-corruption window.
- **B13**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/PerfCounters.scala:32-45`. `reset()` zeroes each counter individually; concurrent reads observe partial reset (benign for instrumentation, inconsistent for snapshots).
- **B14**. `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JvmFileSource.scala:149-156`. `activePool.set(pool)` (line 152) and `Scope.ensure` registration (line 155) are not atomic. Exception or interrupt between them leaks the mapped buffers.
- **B15**. `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala:119-132`. If `channel.map()` throws (line 125) before line 130, RAF is closed in `finally` which also closes the channel, but the exception object may retain a channel reference.

## Test-coverage findings

- **T1**. Untested or thin public API methods: `Tasty.Symbol.binaryName` (thin: only `java.util.Map$Entry` covered, missing Scala nested-class binary names), `Tasty.Symbol.isPackageObject` (no explicit test), `Tasty.Type.show` (no direct test, only via error messages), `Tasty.Annotation` public synthetic factory (only internal decode path covered).
- **T2**. Internal classes with no dedicated test file: `ConstantPool`, `JavaAnnotationUnpickler`, `Varint` (only via VarintTest cases inside ByteViewTest; no dedicated file), `ClasspathRef`, `UnresolvedRef`, `TastyStat`, `PerfCounters`, `SectionIndex`, `InflateHook` (JVM/JS/Native), `DigestComputer`, `SnapshotFormat`, `SnapshotReader`/`SnapshotWriter` (only round-trip integration tests), `Constant`, `FqnCanonicalizer`, `OnceCell` (indirect via body/name), `SingleAssign` (indirect via parent-chain), `Symbol` (indirect via resolution/query), `SymbolKind`, `PlatformHashingState`.
- **T3**. Untested `TastyError` ADT cases: `SymbolNotFound`, `ParameterizedTypeNotAllowed`.
- **T4**. Edge inputs without coverage: Varint at exact buffer boundary, corrupted continuation bit patterns, negative Nat; UTF-8 surrogate pairs, modified-UTF-8 null (`0xC0 0x80`), 4-byte UTF-8 outside BMP; empty `Chunk[Type]` for Function or Tuple params; deeply nested Rec types at and beyond budget (64); cyclic type references at depth; root-owned symbols (owner equals owner) and null owner; deeply nested inner classes for binaryName; Zip64 JARs, multi-disk archives, central directory at unusual offsets, encrypted or signed JARs, JMODs; corrupted mmap region (OS fault); concurrent snapshot modification; platforms without file locking.
- **T5**. Cross-platform parity in tests: no dedicated JS-only or Native-only tests. Uncovered: `JsFileSource` ArrayBuffer behavior, JS Utf8 path, JS InflateHook fallback, `NativeFileSource` POSIX behavior, `NativeMmapReader` signal-safety and page-faults, Native Utf8 path.
- **T6**. No property-based tests (no scalacheck or hedgehog).
- **T7**. Only one explicit concurrency test (Interner 8-fiber). `OnceCell`, `SingleAssign`, `TypeArena` under contention untested directly.
- **T8**. Resource-cleanup tests missing: JAR pool exhaustion, classpath close during pending body decodes, mmap arena close during `Symbol.body` access.

## CONTRIBUTING.md adherence findings

- **A1**. README and DESIGN still use the `Reflect` / `ReflectError` / "kyo-reflect" namespace (overlaps with L1, L2). Public source surface is `kyo.Tasty.*` / `kyo.TastyError`.
- **A2**. `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:903-904`. `Classpath.open(roots)` and `Classpath.open(roots, strict)` overload structure does not make delegation explicit per CONTRIBUTING.md §309-§383. CONTRIBUTING.md `feedback_no_default_params_internal` forbids defaults on internal APIs; these are public so a default is permitted but the relationship should be explicit.
- **A3**. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala:37,41,45`. `asInstanceOf` comments are inline rather than using the canonical `// Unsafe: ...` prefix from CONTRIBUTING.md §415.
- **A4**. AllowUnsafe import-scope misuse. 24 routine `import AllowUnsafe.embrace.danger` sites inside public accessor bodies hide the proof from callers, violating CONTRIBUTING.md §844. The fix per §828 option 1 is to propagate the proof: change accessor signatures to take `(using AllowUnsafe)`, return raw values, no `Sync.defer` wrapping (preserves performance). Specific sites: `Tasty.scala:62,533,547,556,570,584,610,620,631,642,719,995` (13 sites in Symbol public accessors and `addrMap`); `internal/tasty/query/Classpath.scala:70,80,90,100,110,138,156,214,222` (8 sites in pure accessors and `transitionToReady`/`close`); `internal/tasty/query/ClasspathRef.scala:21,28,35` (3 sites in `get`/`assign`/`isAssigned`). `Tasty.scala:862` `addrMap(using AllowUnsafe)` leaks `AllowUnsafe` into a public Origin type; move behind `private[kyo]`. DO NOT introduce a `Symbol.Unsafe` or `Classpath.Unsafe` two-tier companion (that pattern per CONTRIBUTING.md §794-§812 is for concurrent types). The `import danger` sites that STAY per §839 case 3 initialization: unpicklers (`AstUnpickler:161`, `TreeUnpickler:61`, `ClassfileUnpickler:73`, `ConstantPool:86,92`, `Subtyping:139,184`, `Scala2PickleReader:284,369,401,445`, `SnapshotReader:173,245`, `SnapshotWriter:61`), orchestration (`ClasspathOrchestrator:377,389,446`), `ClasspathRef.assign` during Phase 7 (kept distinct from `get`), test helpers (`ClasspathTestHelpers:18`).

## Cross-reference

Total findings: 48 (4 correctness + 10 completeness + 7 clarity + 15 potential bugs + 8 test coverage + 4 CONTRIBUTING.md adherence).

Every finding above is mandatory for the audit-fix campaign per
`audit-fixes/steering.md`. Phases of `05-plan.md` will reference
finding codes in the `addresses` field.
