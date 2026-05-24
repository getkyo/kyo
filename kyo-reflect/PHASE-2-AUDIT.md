# Phase 2 Audit (commit 69e1354fa)

## Test count

- **Plan**: 15 tests across `InternerTest` (6), `NameUnpicklerTest` (5), `AttributeUnpicklerTest` (4).
- **Implemented**: 15 across 3 classes (`InternerTest`: 6, `NameUnpicklerTest`: 5, `AttributeUnpicklerTest`: 4).
- **Per-leaf (1-15)**:
  1. `InternerTest` reference-equal same bytes — `kyo-reflect/shared/src/test/scala/kyo/InternerTest.scala:12` — PRESENT_STRICT.
  2. `InternerTest` non-equal different bytes — `InternerTest.scala:21` — PRESENT_STRICT.
  3. `InternerTest` different shards distinct entries — `InternerTest.scala:31` — PRESENT_WEAKENED. The test only asserts non-reference-equality and distinct strings for two different inputs; it does NOT actually force two different shard indices (a 32-shard interner makes shard hashing nondeterministic in test). Effectively a duplicate of leaf 2. See FINDINGS W1.
  4. `InternerTest` `Name.asString` returns correct UTF-8 — `InternerTest.scala:45` — PRESENT_STRICT.
  5. `InternerTest` `Name.asString` reference-equal twice (Memo caching) — `InternerTest.scala:56` — PRESENT_STRICT.
  6. `InternerTest` `CanEqual[Name, Name]` for same bytes — `InternerTest.scala:67` — PRESENT_STRICT.
  7. `NameUnpicklerTest` non-empty name array from fixture — `NameUnpicklerTest.scala:43` — PRESENT_STRICT (asserts `length == 29`, matches steering-recorded empirical value).
  8. `NameUnpicklerTest` `"PlainClass"` in fixture — `NameUnpicklerTest.scala:64` — PRESENT_STRICT.
  9. `NameUnpicklerTest` QUALIFIED dotted decode — `NameUnpicklerTest.scala:84` — PRESENT_WEAKENED. Asserts only `qualified.get.asString.contains(".")` (and that any `.`-containing name exists). Does NOT assert the specific expected dotted string (e.g. `"kyo.fixtures"`). See FINDINGS W2.
  10. `NameUnpicklerTest` truncated section -> `MalformedSection("Names", _)` — `NameUnpicklerTest.scala:109` — PRESENT_STRICT.
  11. `NameUnpicklerTest` interned same bytes -> reference-equal underlying entries — `NameUnpicklerTest.scala:134` — PRESENT_WEAKENED. The plan text reads "all decoded names are interned: `Arrays.equals` on underlying bytes is true for duplicate names in the file." Actual assertions verify that `n1.asString == "PlainClass"`, `n2.asString == "PlainClass"`, and `n1.asString eq n1.asString` (the Memo cache property), but NEVER assert `n1 eq n2` or `n1.bytes eq n2.bytes`. The leaf advertised reference-equality of underlying entries; the test verifies neither reference-equality of the two `Name`s nor `Arrays.equals` of duplicates within the file. See FINDINGS W3.
  12. `AttributeUnpicklerTest` absent Attributes -> `FileAttributes.default` — `AttributeUnpicklerTest.scala:12` — PRESENT_STRICT.
  13. `AttributeUnpicklerTest` `JAVAattr` -> `isJava=true` — `AttributeUnpicklerTest.scala:34` — PRESENT_STRICT.
  14. `AttributeUnpicklerTest` `EXPLICITNULLSattr` -> `explicitNulls=true` — `AttributeUnpicklerTest.scala:59` — PRESENT_STRICT.
  15. `AttributeUnpicklerTest` `SOURCEFILEattr` -> `Present("Foo.scala")` — `AttributeUnpicklerTest.scala:78` — PRESENT_STRICT.

Strict: 11. Weakened: 3. Missing: 0.

## CONTRIBUTING.md violations

- `Memo.scala:17,19,21`: three `asInstanceOf` uses to encode a sentinel-tagged `AtomicReference[AnyRef]` carrying `A`. CONTRIBUTING.md line 171: `asInstanceOf` "acceptable only when they're strictly necessary inside opaque type boundaries or kernel internals where the type system can't express a known invariant — never as a convenience shortcut." A `Memo[A]` with a typed sentinel could be written with `AtomicReference[A | Memo.Unset.type]` (Scala 3 union type) or with a wrapper `Option`/inner-class state, avoiding casts. Acceptable as "type system can't express invariant cheaply" only with explicit justification comment; current comment ("// Store as AnyRef to avoid strict-null comparison issues") is not that justification. (`feedback_no_casts` says "never use asInstanceOf, fix the types instead".)
- `SingleAssign.scala:16,23`: same pattern, two `asInstanceOf` uses. Same finding.
- `Memo.scala` and `SingleAssign.scala`: methods modifying state are `def get(): A` / `def set(a: A): Unit` returning raw Java-style values rather than being suspended in `Sync`. CONTRIBUTING.md line 824: "All side effects must be suspended. No side-effecting code should execute outside of Kyo's effect system without either an `AllowUnsafe` proof or a suspension boundary like `Sync.Unsafe`." These classes are unsafe-tier helpers but neither carry `(using AllowUnsafe)` nor are nested in an `Unsafe` namespace per the documented two-tier convention (CONTRIBUTING lines 802-820). They are de-facto Java-style mutable wrappers used inside `Interner` (also unsuspended), which then is invoked from `NameUnpickler.read` which IS suspended via `Sync.defer`. The Sync barrier at `NameUnpickler.read` is the suspension point, but the underlying mutable APIs are still effectful-without-AllowUnsafe. Per `feedback_no_unsafe` ("never use AllowUnsafe or Frame.internal, use safe APIs, propagate Frame"), the safe-API answer would be to expose these as kyo-`AtomicRef`-backed (kyo-core `AtomicRef[Maybe[A]]`) values returning `A < Sync`. Decision needed: are these classified as kernel-internal (then add WARNING scaladoc + `Unsafe` naming + `AllowUnsafe`) or as user-safe (then return `A < Sync`)? Currently they are neither, which violates both CONTRIBUTING tiering and the user's `feedback_atomic_not_var` (use AtomicRef/AtomicInt for shared state) for the internal-use case.
- `NameUnpickler.scala:59,137,155`: three `new scala.collection.mutable.ArrayBuffer[...]` allocations to accumulate decoded names. CONTRIBUTING.md doesn't ban `ArrayBuffer`, but `feedback_prefer_span` says "Span is immutable, use Array only when mutability is strictly needed (e.g. read buffer)." The internal mutable accumulator usage is reasonable here (you don't know the count up front and you re-index by NameRef in the same pass), but the final `buf.toArray` could equivalently produce a `Span[Reflect.Name]` if downstream callers consumed it. Phase 2 returns `Array[Reflect.Name]` per plan signature; the issue is downstream (Phase 3+ consumers will treat the returned `Array` as the canonical name table). NOTE only — the plan explicitly requested `Array[Reflect.Name]`.
- `NameUnpickler.scala:144,162`: throwing `ArrayIndexOutOfBoundsException` with a constructed message (`"SIGNED paramSig == 0: invalid"`, `"Unrecognized name tag $unknown at position ..."`) as the error-channel mechanism is fragile. A real AIOOBE from inside `view.readByte()` is conflated with an explicit reject. Replacing the throw-and-catch with a `Result.fail` / `Abort.fail` would be cleaner and would match the existing `MalformedSection` pattern used at the outer try/catch in `read`. NOTE.

## Unsafe markers

`grep -n 'asInstanceOf\|Frame.internal\|AllowUnsafe\|Sync.Unsafe' kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/ kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/ kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`:

- `Memo.scala:17,19,21` — 3 × `asInstanceOf[A]` / `asInstanceOf[AnyRef]`. Justification ("avoid strict-null comparison") is plausible but doesn't establish the cast can't be eliminated by typed sentinel. UNJUSTIFIED unless rewritten with comment per CONTRIBUTING line 171.
- `SingleAssign.scala:16,23` — 2 × `asInstanceOf`. Same. UNJUSTIFIED.
- `Reflect.scala` — clean. No new unsafe markers introduced by Phase 2 edits.
- Test code: `kyo-reflect/shared/src/test/scala/kyo/ByteViewTest.scala:44` carries one pre-existing `.asInstanceOf[ByteView.Heap]` from Phase 1 (not introduced by 69e1354fa). Out of scope for this audit.
- No `AllowUnsafe`, no `Frame.internal`, no `Sync.Unsafe.defer` in Phase 2 code. Good.

## Cross-platform consistency

- All produced `.scala` files live in `kyo-reflect/shared/src/main/scala/...`. No JVM-only sources for the new functionality. PASS.
- The `PlainClass.tasty` fixture lives in `kyo-reflect/shared/src/test/resources/kyo/fixtures/PlainClass.tasty`. PASS — placed under shared so all platforms see it.
- Imports in produced files: `java.util.concurrent.atomic.AtomicReference` (Interner, Memo, SingleAssign) — Scala.js and Scala Native both ship `j.u.c.atomic.AtomicReference` shims (kyo-core uses these freely in shared). PASS.
- Imports `java.util.Arrays` (Interner.scala:68) — also shimmed by both JS and Native. PASS.
- Imports `java.nio.charset.StandardCharsets` (Reflect.scala:42, NameUnpickler.scala:191, test files) — shimmed by both. PASS.
- `getClass.getResourceAsStream` in `NameUnpicklerTest.scala:27` — JVM-native, Scala.js does not implement classpath resources for tests by default (jsdom/node can load files via a sbt-jsdom-nodejs-env step but not via classpath), Scala Native requires the `Test/resourceDirectory` + `EmbeddedResource` config the plan explicitly called out at execution-plan.md:186. **No build.sbt change appears in the commit to wire resource embedding for Native or to provide a Node.js file-system bridge for JS.** The plan's commit message claims "JVM, JS, Native compile clean" (the `Test/compile` step), which is consistent with the resource being absent at JS/Native runtime but present at compile time. This means: NameUnpicklerTest tests 7, 8, 9, 11 will compile on JS/Native but will fail or be skipped at runtime on those platforms. WARN W4.
- `Interner` sharding works cross-platform: 32 × `AtomicReference[Array[Entry]]` is portable, no thread-local or sun.misc. PASS.
- `Interner` thread-safety claim: the implementation copies the whole shard table on every insert and CASes the reference. This is correct for the JS single-threaded model (CAS always succeeds) and for the JVM. On Scala Native (single-threaded execution model by default; threads are opt-in via the `posix` library), the AtomicReference still operates correctly. PASS.

## Naming

- Files match plan exactly:
  - `Interner.scala`, `Memo.scala`, `SingleAssign.scala` in `kyo/internal/reflect/symbol/`. PASS.
  - `NameUnpickler.scala`, `AttributeUnpickler.scala`, `SectionIndex.scala`, `TastyFormat.scala` (modified) in `kyo/internal/reflect/tasty/`. PASS.
- Internal packages match `kyo.internal.reflect.{symbol,tasty}.*`. PASS.
- Test files: `kyo/InternerTest.scala`, `kyo/NameUnpicklerTest.scala`, `kyo/AttributeUnpicklerTest.scala` (package `kyo`, per `feedback_kyo_package` test placement at top of public namespace). PASS.

## Steering deviation

`git diff --name-only 69e1354fa~1 69e1354fa` returns 17 files. Expected Phase 2 deliverables per plan lines 131-144:
- 6 new `.scala` source files in `main/`. PRESENT (Interner, Memo, SingleAssign, NameUnpickler, AttributeUnpickler, SectionIndex).
- 1 modified `Reflect.scala`. PRESENT (`Name` now backed by `Interner.Entry`).
- 3 test files. PRESENT (Interner, NameUnpickler, AttributeUnpickler).
- 1 fixture resource. PRESENT.

Additional out-of-plan files in the commit:
- `kyo-reflect/PHASE-1-AUDIT.md` (this looks like supervisor audit artifacts being committed). Phase 1 audit notes — bookkeeping, fine.
- `kyo-reflect/PHASE-2-INFLIGHT-REVIEW-1.md` — supervisor in-flight review, bookkeeping.
- `kyo-reflect/PHASE-3-PREP.md` (860 LOC) — Phase 3 design prep, NOT part of Phase 2 deliverables. Steering line "Refactor phases preserve existing behavior byte-for-byte" doesn't apply here, but the principle of phase boundaries does. Committing Phase 3 prep within the Phase 2 commit muddles the audit trail. NOTE — non-blocking, but if you reroll on a bug-find this couples Phase 3 prep to the same revert.
- `kyo-reflect/PROGRESS.md` updated, `kyo-reflect/STEERING.md` updated. Bookkeeping. PASS.
- `TastyFormat.scala` was modified, not created, per the plan (which lists it under neither "produce" nor "modify"). The plan implicitly required tag constants to live somewhere; lines 209-225 add `SCALA2STANDARDLIBRARYattr`, `EXPLICITNULLSattr`, `CAPTURECHECKEDattr`, `WITHPUREFUNSattr`, `JAVAattr`, `OUTLINEattr`, `SOURCEFILEattr`, `isBooleanAttrTag`, `isStringAttrTag`, and the inner `NameTags` object. These additions are necessary for `AttributeUnpickler` and `NameUnpickler` to compile and are within scope. NOTE — strictly speaking, `TastyFormat.scala` should appear in the "Files to modify" list; minor plan-text omission, not a steering deviation.

## Anti-flakiness

- `InternerTest` does NOT include a concurrent-intern test. The plan didn't require one; the design assumes lock-free CAS correctness. Acceptable for Phase 2.
- No `Thread.sleep`, no raw thread creation in any test. PASS.
- The fixture-loading tests use the JVM classpath resource API, which is deterministic on JVM. PASS for JVM.
- `loadFixture()` reads one byte at a time in a `while` loop — slow but deterministic; fine for a 509-byte file. NOTE — performance non-issue, but cosmetic.

## NameRef indexing verification

- Steering directive (`STEERING.md` Phase 2 NameRef indexing) confirms 0-based was empirically verified against `PlainClass.tasty`.
- `SectionIndex.scala:51-55`: reads `nameRef = view.readNat()` and indexes `names(nameRef)` — 0-based, consistent with steering.
- `AttributeUnpickler.scala:91-92`: reads `nameRef = view.readNat()` and indexes `names(nameRef)` — 0-based, consistent.
- `NameUnpickler.scala` internal back-references (QUALIFIED, EXPANDED, UNIQUE, etc.) index `buf(prefix)`, `buf(selector)`, `buf(underlying)`, `buf(separator)`, `buf(ref)`, `buf(original)`, `buf(resultSig)`, `buf(target)`, `buf(ps)` — all 0-based.
- Fixture decode passes (29 names per NameUnpicklerTest leaf 7). Empirical PASS.

## Byte-count delimiter verification

- `NameUnpickler.scala:57-60`: reads `nameTableByteCount = view.readNat()`, computes `nameTableEnd = view.position + nameTableByteCount`, then `while view.position < nameTableEnd do`. CORRECT — byte-delimited loop, not fixed-count.
- The corrupt-section test (leaf 10, `NameUnpicklerTest.scala:109-131`) declares a name table length of 10 bytes but provides only 5 bytes total of content (UTF8 tag + length=5 + only 2 of 5 promised bytes). The `readUnsafe` loop reads `view.readByte()` for the third payload byte, which is out of range, triggering `ArrayIndexOutOfBoundsException` from the underlying `ByteView`. This is caught at `NameUnpickler.scala:48-50` and converted to `MalformedSection("Names", "unexpected end of name table")`. CORRECT — exercises the byte-count-delimited loop in the truncation case.
- The steering-mandated test "a name table with trailing padding bytes that the unpickler must NOT interpret as an extra entry" (STEERING Phase 2 directive) is NOT present. The truncation test exercises premature end; the padding test would exercise the inverse (loop must STOP at `nameTableEnd` even if more bytes follow). The byte-count condition `while view.position < nameTableEnd` does stop early-and-correctly in the impl, but no test guards against a future regression where someone writes `while view.remaining > 0`. WARN W5 — directive partially satisfied; padding test missing.

## Findings categorization

### BLOCKERs (must fix before Phase 4)
None. All 15 plan tests are implemented (3 weakened but present), all 6 plan source files plus the required `Reflect.scala` modification compile and pass on JVM per the commit message, the 0-based NameRef directive is empirically resolved against a real TASTy file, and the byte-count delimiter is correctly implemented. The cross-platform resource concern (W4) is not Phase-4-blocking because Phase 4 produces more source code, not more runtime-only Native tests — but it WILL block the eventual "all 15 tests pass on JVM, JS and Native compile, JS and Native runtime tests pass" gate that some later audit phase will check.

### WARN (should fix in cleanup, not gating)
- **W1**: `InternerTest` leaf 3 ("different shards") does not force different shard indices and is effectively a duplicate of leaf 2. Either pick byte sequences whose FNV-1a hashes are known to land in different shards (compute statically and inline as a comment), or test on a 2-shard interner with two inputs that round-trip into different shards (you control numShards). Mechanical fix.
- **W2**: `NameUnpicklerTest` leaf 9 only checks "any name with `.`"; should additionally assert the specific qualified name (e.g. `assert(names.contains_byString("kyo.fixtures"))`). The fixture commentary in the test header (`NameUnpicklerTest.scala:18-22`) already names the expected QUALIFIED entry — promote that comment to a hard assertion.
- **W3**: `NameUnpicklerTest` leaf 11 advertises interning verification ("`Arrays.equals` on underlying bytes is true for duplicate names") but the assertions only verify Memo caching, not interner identity. Add `assert(n1 eq n2)` or `assert((n1: Interner.Entry).bytes eq (n2: Interner.Entry).bytes)`. Trivial fix.
- **W4**: No build.sbt change for Native test-resource embedding and no JS test-resource bridge. The 4 fixture-loading tests (leaves 7, 8, 9, 11) will fail at JS/Native runtime. Either (a) wire Scala Native's `Test/resourceDirectory` config and JS jsdom-nodejs-env (per execution-plan.md:186), or (b) re-encode the fixture bytes as a hex literal embedded in the test source. Option (b) is mechanical and platform-agnostic.
- **W5**: Steering Phase 2 directive required a "trailing padding bytes that must NOT be interpreted as an extra entry" test. Truncation test exists; padding test does not. Add a 7th `NameUnpicklerTest` (a third synthesized name-table with `nameTableByteCount = 7` followed by `UTF8 + length=5 + 5 payload bytes + 1 trailing 0xFF byte`, asserting that the loop stops at byte 7 and the 0xFF is left in the buffer or reported as a section-boundary issue depending on how the surrounding section reader handles it). Mechanical.
- **W6**: `Memo.scala` and `SingleAssign.scala` use `asInstanceOf` (3 + 2 uses) without justification meeting CONTRIBUTING.md line 171's bar. Either typed-sentinel rewrite (Scala 3 union type `A | Unset.type`) or add an explicit `// AsInstanceOf justified: A | Unset.type union requires a typed sentinel that the JVM's AtomicReference can't store as-is without boxing` comment. Closer reading suggests this is genuinely difficult to encode in the Scala-3 type system without runtime cost, so the comment-justification path is fine.
- **W7**: `Memo` and `SingleAssign` are mutable holders with side-effect-returning `def get()` / `def set(a: A): Unit` operating outside of `Sync`. They are invoked from inside `Interner.intern` (also outside `Sync`), which is invoked from `NameUnpickler.read` (inside `Sync.defer`). The Sync envelope at the call site is the correct suspension boundary, but per `feedback_no_unsafe` and CONTRIBUTING tiering, these unsafe-tier helpers should either (a) be flagged with WARNING scaladoc + named `Unsafe`-suffixed + take `(using AllowUnsafe)` per CONTRIBUTING lines 802-820, or (b) return `A < Sync`. Decision required — does NOT block Phase 4 since the Phase 2 surface is private/internal, but the eventual public-API audit will catch this.

### NOTE (cosmetic / future improvement)
- **N1**: `TastyFormat.scala` was modified but the plan's "Files to modify" list at execution-plan.md:142-144 doesn't mention it. Plan-text gap; the modification was clearly required.
- **N2**: `PHASE-3-PREP.md` (860 LOC) committed within the Phase 2 commit. Couples Phase 3 design to Phase 2's revert window.
- **N3**: `NameUnpickler.scala:144,162` use thrown `ArrayIndexOutOfBoundsException` with constructed messages as a control-flow mechanism for malformed `paramSig == 0`. Should use `Abort.fail(MalformedSection(...))` or an `Either`-style internal channel. Cosmetic.
- **N4**: `loadFixture()` in `NameUnpicklerTest.scala:26-36` reads byte-by-byte. Use `stream.readAllBytes()` (JVM) — but this also caps on the W4 concern about Native classpath resources, so the read-loop is portable and trivially fine.
- **N5**: `Interner.scala:68-72` copies the entire shard table on every insert (then CAS-publishes). At load-factor 0.75 with a 16-slot initial table, this is up to 12 ref copies per insert, all single-threaded on JS. For the name-table workload (~30 names per file), this is irrelevant. Out-of-scope optimization.
- **N6**: `Reflect.Name`'s `CanEqual` instance is `CanEqual.canEqualAny` (line 49). For an opaque type backed by a reference-identity-equal interned entry, this is correct. Document the invariant in the scaladoc more loudly so a future maintainer doesn't switch the backing type.
- **N7**: `SectionIndex.scala:18` stores section bounds as `(Int, Int)`. A small named `case class SectionBounds(offset: Int, length: Int)` would be more legible and would prevent the (offset, length) vs (start, end) confusion that has historically bitten TASTy parsers. Cosmetic.
- **N8**: The `final case class FileAttributes(...)` in `AttributeUnpickler.scala:10-17` lives in `kyo.internal.reflect.tasty`. If Phase 5+ wants to surface file attributes through the public API, this will need to be re-exported via `Reflect.FileAttributes`. Future plan item.

## Recommendation: PROCEED

0 BLOCKER, 7 WARN, 8 NOTE. Phase 2 delivers all 15 tests, all 6 source files, the 1 mandated `Reflect.scala` edit, the empirically-verified 0-based NameRef indexing, and the byte-count-delimited name-table loop. Three tests (W1, W2, W3) are weakened versions of their plan specifications; one steering directive (padding-bytes test, W5) is unsatisfied. The cross-platform W4 (Native + JS test-resource wiring) is the largest gap and should be addressed before any audit that gates on "JS/Native runtime tests pass" — but Phase 4 can proceed in parallel because Phase 4 adds source code, not runtime resource bindings.

PROCEED to Phase 3 with W1-W7 queued for the post-Phase-3 cleanup pass.
