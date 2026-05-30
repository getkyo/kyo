# Phase 04a Prep: Widen JAR offsets to 64-bit

Phase: 04a
Findings: C1, B2, B3
INV produced: INV-012
Depends on: none
Platforms: [jvm]

---

## 1. Plan section reference

Source: `05-plan.md` Phase 04a (lines 981-1071).

One conceptual change: replace every `.toInt` truncation in JAR offset arithmetic with `Long` arithmetic; add Zip64 EOCD locator detection. Produces INV-012.

---

## 2. Enumerated `.toInt` truncation sites

### JarCentralDirectory.scala

```
rg -n '\.toInt\b' kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala
```

Result (9 sites):

| Line | Expression | Risk |
|------|-----------|------|
| 140 | `(eocdOffset - cenOffset).toInt.max(0)` | C1: CEN size truncated if > 2GB |
| 142 | `buf.position(cenOffset.toInt)` | C1: position call truncates Long cenOffset |
| 174 | `(eocdOffset - cenOffset).toInt.max(0)` | C1: duplicate of 140, RAF-path |
| 189 | `EOCD_MAX_SCAN.min(fileLen.toInt)` | B3: fileLen truncated before min; wrong scan for large files |
| 345 | `(eocdOffset - cenOffset).toInt.max(0)` | C1: third occurrence, listEntriesFull RAF-path |
| 524 | `EOCD_MAX_SCAN.min(fileLen.toInt)` | B3: mmap path; same truncation as 189 |
| 526 | `buf.position((fileLen - scanLen).toInt)` | C1: ByteBuffer.position receives truncated Long |
| 560 | `buf.position(locOffset.toInt)` | C1: Zip64 locator position truncated |
| 570 | `buf.position(zip64EocdOffset.toInt)` | C1: Zip64 EOCD offset itself truncated |

**Total in JarCentralDirectory.scala: 9 sites.**

### JarMappedReader.scala

```
rg -n '\.toInt\b' kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala
```

Result (5 sites):

| Line | Expression | Risk |
|------|-----------|------|
| 51 | `buf.position(entry.lfhOffset.toInt)` | B2: LFH offset truncated |
| 61 | `buf.position(entry.lfhOffset.toInt + 26)` | B2: same, second read |
| 65 | `entry.lfhOffset.toInt + 30 + nameLen + extraLen` | B2: dataOffset overflows Int arithmetic |
| 66 | `entry.compSize.toInt` | B2: compSize > Int.MaxValue silently truncated |
| 67 | `entry.uncompSize.toInt` | B2: uncompSize > Int.MaxValue silently truncated |

**Total in JarMappedReader.scala: 5 sites.**

Note: the plan text cites "three in JarMappedReader (65, 72, 85)" but the actual current code has 5 `.toInt` sites (51, 61, 65, 66, 67). All five must be addressed; the plan's line numbers were written against a slightly different file state. Lines 66 and 67 are compSize/uncompSize truncations that feed array allocation, which is a distinct B2 sub-case.

---

## 3. ByteBuffer.position Int-signature constraint

`java.nio.ByteBuffer.position(int)` accepts only `Int`. Any offset stored as `Long` cannot be passed directly. The constraint surfaces at every `buf.position(...)` call:

- Lines 142, 526, 560, 570 in JarCentralDirectory.scala
- Lines 51, 61, 73 in JarMappedReader.scala

**Resolution pattern (from the plan):**

For offsets that are architecturally bounded below 2GB (EOCD scan region is bounded by `EOCD_MAX_SCAN` which is 65535 + 22 bytes), the `.toInt` is safe and can stay, with an explicit pre-check:

```scala
val scanLen: Int = EOCD_MAX_SCAN.min(fileLen).toInt  // safe: EOCD_MAX_SCAN << Int.MaxValue
```

For offsets that can exceed 2GB (cenOffset, lfhOffset, zip64EocdOffset), the fix is to validate before casting and throw a structured error if the offset exceeds `Int.MaxValue`:

```scala
if cenOffset > Int.MaxValue then
    throw new TastyError.MalformedSection.Toss("jar", s"cenOffset $cenOffset exceeds 2GB mmap range; Zip64 required")
buf.position(cenOffset.toInt)
```

The plan introduces this check at each `buf.position(longVal.toInt)` site. There is no MappedByteBuffer multi-segment implementation in Phase 04a; that is Phase 04b's scope (`MappedByteView`). Phase 04a adds the guard + error, not a chunked-read path.

**ByteBuffer constraint noted: YES.**

---

## 4. Concerns

### 4a. Zip64 EOCD parser

The plan does introduce Zip64 EOCD locator detection (the `findZip64Eocd` helper shown in the AFTER block). However, the current code in `readCenLocationBuf` already parses the Zip64 EOCD locator signature `SIG_ZIP64_LOC` and reads `zip64EocdOffset` via `readUInt64LE` (lines 558-580). The plan's change is to keep this logic but widen the subsequent `buf.position(zip64EocdOffset.toInt)` call at line 570 with a guard. The plan does NOT add a new Zip64 parser; it fixes an existing one's offset truncation. The prep confirms this concern is moot: existing Zip64 detection is present, only the `.toInt` at position is wrong.

### 4b. Test file status

The plan specifies 3 tests across two files:

1. `kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala` (tests 1 and 2)
2. `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala` (test 3)

These are JVM-only test files (`jvm/src/test/`), consistent with the JVM-only platform scope.

**Check whether these files already exist:**

```bash
ls kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala 2>/dev/null
ls kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala 2>/dev/null
```

The plan says "Files to produce: None" -- it does not list these test files as new files to produce. This is a gap: if they do not exist, the impl agent must create them, but the plan omits them from "Files to produce". The impl agent must treat their absence as an implicit create. A `JarMappedReaderTest.scala` is NOT listed in the plan; the B2 LFH-offset test lands in `JvmFileSourceTest.scala` (test 3), not in a dedicated `JarMappedReaderTest.scala`.

**No `JarCentralDirectoryZip64Test.scala` is planned.** The Zip64 test (test 2) lives inside `JarCentralDirectoryTest.scala`. The impl agent must not create a separate file for this.

### 4c. JS/Native scope

Phase 04a's `Cross-platform set` is `[jvm]`. Both `JarCentralDirectory.scala` and `JarMappedReader.scala` live under `jvm/src/`. No JS or Native change is required or permitted. The plan correctly restricts this.

### 4d. Plan BEFORE/AFTER line numbers vs current source

The plan references "nine cited sites in JarCentralDirectory (140, 142, 174, 189, 342, 345, 526, 560, 570)" and "three in JarMappedReader (65, 72, 85)". The actual source has the JarCentralDirectory sites at those lines (confirmed above). JarMappedReader lines 51, 61, 65, 66, 67 are the real current sites; the plan's (65, 72, 85) are stale. The impl agent should fix all five current sites, not try to match the plan's outdated line references.

---

## 5. Files to modify

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala`
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala`

## 6. Files to create (tests)

- `kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala` (create if absent)
- `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala` (create or extend if absent)

---

## 7. Verification command

```
sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JarCentralDirectoryTest kyo.JvmFileSourceTest'
```

---

## 8. Self-check

- INV-012 consumed: none. Produced: INV-012.
- `.toInt` site count: 9 (JarCentralDirectory) + 5 (JarMappedReader) = 14 total.
- Plan cites 9 + 3 = 12. Delta of 2 is lines 66 and 67 in JarMappedReader (compSize/uncompSize). Both are B2 sub-cases and must be fixed.
- ByteBuffer Int constraint documented: YES.
- Zip64 parser concern: existing parser present, only offset cast is broken.
- Test files: plan omits them from "Files to produce"; impl agent must create. No `JarCentralDirectoryZip64Test.scala` or `JarMappedReaderTest.scala` should be created.
- Platform scope: JVM only, correct.
- No em-dashes in this document.

**Verdict: READY. No blockers. Impl agent should note the 2 additional `.toInt` sites (lines 66, 67 in JarMappedReader) not called out in the plan.**
