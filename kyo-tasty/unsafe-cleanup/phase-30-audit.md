# Phase 30 Final Audit -- unsafe-cleanup campaign

## Summary

The 7-phase unsafe-cleanup campaign (28a through 29-rest) has successfully
driven kyo-tasty to idiomatic-kyo status. All 33 net import-danger sites were
eliminated from production code, j.u.c.a residue is down to the single
permitted AtomicReferenceArray in Interner.scala, and every unsafe-tier
primitive enforces AllowUnsafe on every mutating method. All three platforms
are green at their expected counts. Three test class-body imports are missing
`// flow-allow:` annotations (WARN), but no production code violates any
CONTRIBUTING.md rule. The campaign may be declared complete subject to the
annotation follow-up.

---

## Findings

### 1. Class-constructor AllowUnsafe scan -- PASS

Command:
```
grep -rn 'class .*(using AllowUnsafe' kyo-tasty/{shared,jvm,js,native}/src/main
```
Result: 0 matches.

No class constructor anywhere in the production source tree takes
`(using AllowUnsafe)`. CONTRIBUTING.md §927-§929 constraint is satisfied.

---

### 2. Remaining import danger inventory -- PASS

13 surviving sites, all in method or `val` initializer bodies, all annotated.

| File | Line | Context | Classification |
|---|---|---|---|
| kyo/Tasty.scala | 39 | `private val globalInterner` in `object Tasty` | (c) module-load val initializer |
| kyo/Tasty.scala | 421 | `def show: String` on `Type` | (b) method body -- diagnostic display |
| kyo/Tasty.scala | 848 | `def computeFullName` in `object Symbol` | (b) method body -- private[Tasty] |
| kyo/Tasty.scala | 863 | `def computeBinaryName` in `object Symbol` | (b) method body -- private[Tasty] |
| kyo/Tasty.scala | 936 | OnceCell init lambda inside `Symbol.make` | (c) module-load / OnceCell init thunk |
| kyo/Tasty.scala | 990 | `def addrMap` in `TastyOrigin` | (b) method body -- private[kyo] accessor |
| kyo/Tasty.scala | 1010 | `val empty: TastyOrigin` in `object TastyOrigin` | (c) module-load val initializer |
| kyo/Tasty.scala | 1108 | `def fromPickles` Sync.map lambda | (b) method body -- Sync.map lambda |
| kyo/Tasty.scala | 1184 | `def assignHomes` private | (b) method body -- private init helper |
| reader/TypeUnpickler.scala | 37 | `val MatchCaseSentinel` in `object TypeUnpickler` | (c) module-load val initializer |
| snapshot/SnapshotReader.scala | 122 | `def deserialize` private | (b) method body |
| snapshot/SnapshotReader.scala | 239 | `def deserializeMapped` private | (b) method body |
| snapshot/SnapshotWriter.scala | 61 | `def serialize` private | (b) method body |

Every site has a `// flow-allow: §839 case 3` comment. No class-body import
in production code.

---

### 3. Method-signature propagation completeness -- PASS

Spot-checked 7 files:

**SingleAssign.scala**: `set`, `get`, `isSet`, `init` all carry
`(using AllowUnsafe)`. Constructor is private; factory is the only
allocation path.

**OnceCell.scala**: `init`, `get` carry `(using AllowUnsafe)`.

**DeclarationTable.scala**: `populate`, `get`, `all`, `storageKind`,
`init`, `build` all carry `(using AllowUnsafe)`.

**Classpath.scala**: `isClosed`, `pureClass`, `purePackage`, `pureModule`,
`pureTopLevelClasses`, `purePackages`, `accumulatedErrors`, `allSymbols`,
`transitionToReady`, `close` carry `(using AllowUnsafe)`. The six
suspension-based methods that use `(using Frame)` instead are correct
-- they suspend into Sync.

**ConstantPool.scala**: `Utf8Lazy.decode`, `Utf8Lazy.init`, `readU1`,
`readU2`, `readU4`, `readU8`, `read` carry `(using AllowUnsafe)` or
`(using Frame, AllowUnsafe)`. Pure helpers (`entry`, `tagName`) do not.

**SnapshotReader.scala**: `readSymbolsMapped` and `readSymbols` carry
`(using AllowUnsafe)`. `readNamePool`, `readErrors`, `deserializeRefLists`,
`readInt32LEFromView`, and `copyViewRange` are pure array readers that call
`SnapshotFormat.readInt32LE` and `ByteView.peekByte` only; neither requires
AllowUnsafe (peekByte is pure; readInt32LE operates on Array[Byte] directly).
These signatures are correct.

**SnapshotWriter.scala**: `serialize` (private, scoped import) calls
`stateRef.unsafe.get()` inside an AllowUnsafe scope. Correct.

No propagation gaps found.

---

### 4. j.u.c.a residue -- PASS

Command:
```
grep -rln 'java\.util\.concurrent\.atomic' kyo-tasty/shared/src/main
```
Result: 1 file -- `kyo/internal/tasty/symbol/Interner.scala`.

The single import in that file is:
```
import java.util.concurrent.atomic.AtomicReferenceArray
```
This is the documented campaign exception. `AtomicReferenceArray` has no
kyo equivalent; the phase-28e decisions document records this explicitly.
No other j.u.c.a type (AtomicInteger, AtomicLong, AtomicReference,
AtomicBoolean) appears in shared/main.

---

### 5. Test discipline -- PASS with WARN

Spot-checked 5 test files.

**DeclarationTableTest.scala**: AllowUnsafe scoped to individual method
bodies. Factory constructors used throughout (`DeclarationTable.init`,
`DeclarationTable.build`, `ClasspathRef.init`). No `new DeclarationTable`.
Clean.

**OnceCellTest.scala**: AllowUnsafe scoped to individual `in { ... }` blocks.
`OnceCell.init` factory used. No `new OnceCell`. Clean.

**ClasspathRefTest.scala**: `import AllowUnsafe.embrace.danger` at class body
level (line 12). WARN: missing `// flow-allow:` annotation. Uses
`ClasspathRef.init()` factory. No `new ClasspathRef`. The class-level import
is §839 case 3 (test boundary), but the annotation is absent.

**SingleAssignTest.scala**: `import AllowUnsafe.embrace.danger` at class body
level (line 11). WARN: missing `// flow-allow:` annotation. Uses
`SingleAssign.init[Int]()` factory. No `new SingleAssign`. The class-level
import is §839 case 3, but the annotation is absent.

**InternerTest.scala**: `import AllowUnsafe.embrace.danger` at class body
level (line 7). WARN: missing `// flow-allow:` annotation. Uses
`Interner.init(...)` factory. No `new Interner`. The class-level import is
§839 case 3, but the annotation is absent.

---

### 6. Tier-preference adherence -- PASS

Command:
```
grep -rn 'private val.*: AtomicRef\.Unsafe\|private val.*: AtomicInt\.Unsafe\|private val.*: AtomicLong\.Unsafe'
  kyo-tasty/{shared,jvm,js,native}/src/main
```

3 matches:

| File | Line | Class | Classification |
|---|---|---|---|
| symbol/SingleAssign.scala | 14 | `SingleAssign[A]` | IS unsafe-tier primitive -- the kyo-tasty write-once slot; constructor private; factory requires AllowUnsafe |
| symbol/DeclarationTable.scala | 15 | `DeclarationTable` | IS unsafe-tier primitive -- CAS-backed declaration map; constructor private; factory requires AllowUnsafe |
| classfile/ConstantPool.scala | 26 | `CpEntry.Utf8Lazy` | IS unsafe-tier primitive -- lazy UTF-8 entry with AtomicRef.Unsafe for once-decode; constructor private; factory requires AllowUnsafe |

All three are themselves the unsafe-tier abstraction boundary, matching the
`AtomicRef.Unsafe` role from CONTRIBUTING.md §889. No safe-tier class holds
an Unsafe-tier field directly.

---

### 7. Em-dash count -- PASS

Command:
```
grep -rn '[--]' kyo-tasty/{shared,jvm,js,native}/src/main
```
(Unicode em-dash U+2014 and en-dash U+2013)

Result: 0 matches. No em-dashes or en-dashes in any production source file.

---

### 8. Cross-platform test results -- PASS

| Platform | Tests run | Passed | Failed | Ignored |
|---|---|---|---|---|
| JVM | 482 | 482 | 0 | 3 |
| JS | 393 | 393 | 0 | 48 |
| Native | 395 | 395 | 0 | 49 |

All three platforms pass at the expected counts.

---

## Open recommendations

- WARN: Add `// flow-allow: §839 case 3 -- test boundary; all tests in this
  class exercise unsafe-tier primitives directly` above the class-body
  `import AllowUnsafe.embrace.danger` in `SingleAssignTest.scala`,
  `ClasspathRefTest.scala`, and `InternerTest.scala`. These imports are
  legitimate but are the only annotationless import-danger sites in the
  entire module. Route to a cleanup commit (does not require a full phase).

- NOTE: `SnapshotReader.readNamePool`, `readErrors`, and
  `deserializeRefLists` lack `(using AllowUnsafe)` signatures even though
  they are called from within AllowUnsafe-scoped callers. They are pure
  array readers using `SnapshotFormat.readInt32LE` and do not touch any
  Unsafe-tier operation, so the omission is correct. This is recorded as a
  NOTE in case a future reader questions the asymmetry with
  `readSymbolsMapped` and `readSymbols`.

---

## Campaign verdict

ALL CLEAR. The unsafe-cleanup campaign goals are met:

- import AllowUnsafe.embrace.danger: 46 -> 13 (NET -33), all 13 annotated,
  none in class bodies of production code.
- j.u.c.a.* in shared/main: 6 files -> 1 file (Interner/AtomicReferenceArray
  documented exception only).
- All three platforms green: JVM 482, JS 393, Native 395.
- No class-constructor AllowUnsafe parameters.
- All unsafe-tier fields confined to unsafe-tier primitive classes.

One WARN (3 test class-body imports missing annotations) is a cosmetic
follow-up; it does not affect correctness or the safety invariants.
