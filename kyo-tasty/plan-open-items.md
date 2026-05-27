# kyo-reflect Plan: Open Items Audit

## Summary

32 open items found across categories A, B, C, D, E, G, H, I. Categories F and J turned up zero findings.

---

## Category A: Deferred decisions

A1. `:466` , `TypeRepr` over `Mirror` but no fallback for missing `classSymbol`. Resolution: `report.errorAndAbort("requires a named class symbol")`.

A2. `:468` , two `symbolKinds` branches both yield `Set(values*)`. Resolution: pure-only → all kinds; `parents`/`declarations`/`typeParams` → `Set(Class, Trait, Object)`.

A3. `:610` , JS Node "Array[Byte] fallback" without naming API. Resolution: `fs.readFileSync(path)` + `Int8Array` copy.

A4. `:610` , `FileChannel.map` vs `MemorySegment` (DESIGN.md:1332) unpicked. Resolution: pick `MemorySegment` (JDK 25 required); drop `MappedByteBuffer`.

---

## Category B: Undocumented assumptions

B1. `:19` , `kyo-reflect-fixtures` build form not shown. Resolution: `crossProject(JS, JVM, Native).crossType(Full).in(file("kyo-reflect-fixtures"))`, `% Test` link.

B2. `:15` , `Test.scala` copy semantics unstated. Resolution: "verbatim copy, package `kyo`, no edits".

B3. `:596` , `Cache.memo` path/signature implicit. Resolution: cite `kyo-core/shared/src/main/scala/kyo/Cache.scala` + signature.

B4. `:597` , unqualified `Promise`. Resolution: name `kyo.Promise`.

B5. `:60` , `readInt(): Int` "LEB128 signed" disagrees with TASTy spec. Resolution: cite dotty `TastyBuffer.readInt` semantics (zigzag).

B6. `:135` , name tag variants not enumerated. Resolution: list (QUALIFIED=1, EXPANDED=2, EXPANDPREFIX=3, UNIQUE=4, DEFAULTGETTER=5, SUPERACCESSOR=8, INLINEACCESSOR=9, OBJECTCLASS=10, BODYRETAINER=11, SIGNED=63, TARGETSIGNED=62).

B7. `:341` , wildcard `(lo, hi)` order unstated. Resolution: `Wildcard(lower, upper)`; `+T → (Nothing, T)`, `-T → (T, Object)`, `* → (Nothing, Object)`.

B8. `:599` , `exists` is `< Sync` while siblings carry `Abort[ReflectError]`. Resolution: state `false`-on-error rationale or unify the row.

---

## Category C: forward-reference compile-isolation violations

C1. `:202` , Phase 3 `Symbol.home: ClasspathRef` has unset `SingleAssign` until Phase 7. Resolution: state constraint that no Phase 3 path calls `home.checkOpen`/`get`; audit `computeFullName`/`computeBinaryName`.

C2. `:208` , `Symbol.Origin` ADT introduced Phase 3 but `JavaOrigin` constructed Phase 5. Resolution: Phase 3 declares both `TastyOrigin` and `JavaOrigin` (cases only); Phase 5 only adds construction sites.

C3. `:439` , Phase 5b t18 asserts `SymbolKind.Unresolved` but creation is Phase 7. Resolution: move `Unresolved` row to Phase 7 or specify a synthesized fixture.

---

## Category D: tests that lack exact verification

D1. `:239` (P3 t19 forward-ref `C[T1 <: T2, T2]`) , "without decode order errors" vague. Resolution: assert `addrMap(T1Addr).name.asString == "T1"`, `addrMap(T2Addr).name.asString == "T2"`.

D2. `:309` (P4 t21 `MATCHtype`) , no fixture or count. Resolution: fixture `type Tup[X] = X match { case Int => String; case _ => Int }`; `cases.size == 2`, named scrutinee.

D3. `:431` (P5b t10 `enclosingMethod`) , `Present` value unspecified. Resolution: assert `methodName == <fixtureMethodName>`.

D4. `:672` (P7 t32 partial decode) , "does not prevent others" lacks count. Resolution: assert `topLevelClasses.size == n-1` AND `errors.size == 1`.

D5. `:507` (P6 t16 hygiene guard 2) , runtime criterion silent. Resolution: assert compile AND `touchedFields` excludes pattern-only fields.

---

## Category E: cross-platform gaps

E1. `:200` , `CLASSconst` cross-platform path unspecified. Resolution: decode to `ClassLiteral(typeRef)` without resolution; consumers query via `findClass`.

E2. `:611` , `DigestComputer` mtime non-portable. Resolution: JVM/Native real `mtime`; JS-node `fs.statSync().mtimeMs`; JS-browser `openCached` unavailable.

E3. `:612` , `evictOlderThan` on JS browser must not error. Resolution: explicit no-op on browser.

E4. `:185` , Phase 2 `NameUnpicklerTest` Native resource-embedding unstated. Resolution: specify `Test/resourceDirectory` copy + Native lookup API.

---

## Category F: state crossing phases without explicit transition

Zero findings. `ClasspathRef` `SingleAssign` transition Phase 3 → Phase 7 is documented at 202; `UnresolvedRef` placeholder mechanics at 272; `TypeArena.merge` reused from Phase 4 in Phase 7 at 600.

---

## Category G: error paths

G1. `:583` , `symbolToRecord` runtime path on Java-only accessor + Scala symbol unspecified. Resolution: `javaSpecific` returns `Absent`; add explicit test.

G2. `:597` , `Resolver.resolve` error enumeration absent. Resolution: "produces `ClasspathClosed` only; missing FQN returns `Absent`".

G3. `:606-611` , snapshot reader IO errors unhandled. Resolution: add `SnapshotIoError` (or reuse `FileNotFound`) + test for unreadable cache dir.

G4. `:600` , missing-root error case absent. Resolution: add `FileNotFound` for missing root + test.

---

## Category H: external state not in scope

H1. `:357-368` , Phase 5 tests read JDK classes via `jrt:/`. Resolution: state JDK 25 + `jrt:/` assumption + fixture-bytes fallback.

H2. `:611` , SHA-256 absent on Native/JS without JDK `MessageDigest`. Resolution: name dep or vendor single-file SHA-256 for Native/JS.

---

## Category I: anti-flakiness measures

I1. `:665` (t25 concurrent writers) , no sleep/barrier semantics. Resolution: `Async.parallel(2)`, no sleeps, `Async.timeout(1.second)`; flakiness budget zero.

I2. `:243` (P3 t23 CAS visibility) , unnamed latch. Resolution: `kyo.Latch` + `Async.timeout(1.second)` (hangs the test, not CI).

I3. `:676` (t36 FD leak) , "finalizer counter" unspecified. Resolution: `AtomicInt`, increment in `acquireRelease`, decrement in finalizer, assert `== 0`.

---

## Category J: scope-cut signals in test enumerations

Zero findings. Every phase enumerates tests explicitly with a total; no ellipses or "additional cases as needed".

---

## Final list of items needing resolution

| # | Item | Where | Recommended resolution |
|---|------|-------|------------------------|
| 1 | Unnamed-class fallback in Reads macro | 466 | `report.errorAndAbort("requires a named class symbol")` |
| 2 | Ambiguous `symbolKinds` inference | 468 | Single rule: pure-only → all kinds; structural → Class/Trait/Object |
| 3 | Unnamed JS Node read API | 610 | `fs.readFileSync` + `Int8Array` copy |
| 4 | Mapped vs MemorySegment unpicked | 610 | Pick `MemorySegment` (JDK 25), drop legacy path |
| 5 | Fixtures sbt project form | 19 | `crossProject(...).crossType(Full).in(file(...))`, `% Test` link |
| 6 | `Test.scala` copy semantics | 15 | "Verbatim copy, package `kyo`, no edits" |
| 7 | `Cache.memo` reference unspecified | 596 | Cite `kyo-core/.../Cache.scala` + signature |
| 8 | `Promise` not qualified | 597 | Name `kyo.Promise` with file path |
| 9 | `readInt` LEB128 semantics | 60 | Cite dotty `TastyBuffer.readInt` (zigzag) |
| 10 | Name tag enumeration missing | 135 | List values (QUALIFIED..TARGETSIGNED) |
| 11 | Wildcard `(lo, hi)` order | 341 | `Wildcard(lower, upper)`; `+T/-T/*` mapping |
| 12 | `exists` lacks `Abort` effect | 599 | State `false`-on-error rationale or unify |
| 13 | `ClasspathRef.get` in Phase 3? | 202 | Constraint: no Phase 3 code calls it; audit `computeFullName` |
| 14 | `Symbol.Origin` ADT introduction | 208 | Phase 3 declares both cases; Phase 5 only constructs |
| 15 | `Unresolved` coverage in Phase 5b | 439 | Move to Phase 7 or specify synthesized fixture |
| 16 | Phase 3 t19 forward-ref assertions | 239 | Name-equality assertions on `addrMap(T1Addr)`, `addrMap(T2Addr)` |
| 17 | Phase 4 t21 MATCHtype fixture | 309 | Named match type, `cases.size == 2`, named scrutinee |
| 18 | Phase 5b t10 enclosingMethod value | 431 | Assert `methodName == <fixtureName>` |
| 19 | Phase 7 t32 partial-success counts | 672 | Assert `topLevelClasses.size == n-1` AND `errors.size == 1` |
| 20 | Phase 6 t16 hygiene assertion | 507 | Assert compile + `touchedFields` excludes pattern-only fields |
| 21 | `CLASSconst` cross-platform | 200 | Decode to `ClassLiteral(typeRef)` without resolution |
| 22 | `DigestComputer` mtime per-platform | 611 | JS-node `mtimeMs`; browser `openCached` unavailable |
| 23 | `evictOlderThan` JS-browser | 612 | Explicit no-op |
| 24 | Test resources on Native | 185 | Specify embedding mechanism + lookup API |
| 25 | Resolver error enumeration | 597 | "`ClasspathClosed` only; missing → `Absent`" |
| 26 | Snapshot IO error case missing | 606 | Add `SnapshotIoError` + test |
| 27 | Missing-root error case | 600 | Add `FileNotFound` for missing root + test |
| 28 | JDK `jrt:/` dependency at test time | 357 | State JDK 25 assumption + fixture-bytes fallback |
| 29 | SHA-256 on Native/JS | 611 | Name dep or vendor single-file SHA-256 impl |
| 30 | Concurrent-writer test flakiness | 665 | `Async.parallel(2)`, no sleeps, bounded timeout |
| 31 | CAS-visibility latch unnamed | 243 | `kyo.Latch` + `Async.timeout(1.second)` |
| 32 | FD-leak counter mechanism | 676 | `AtomicInt` inc/dec sites + assert `== 0` |
