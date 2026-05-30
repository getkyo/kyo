# Phase 04c Audit

HEAD: `d8ee4233d` ("kyo-tasty Phase 04c: detect truncated JarCentralDirectory records")
Auditor: flow-phase-audit (tight)
Design anchor: `02-design.md:298-306` (B11 against `JarCentralDirectory parseAllEntries`), invariant INV-012 (`02-design.md:606`).
Diff stat: 1 prod file (+3/-1), 1 test file (+38/-0). Audit-fixes docs added but not in production scope.

---

## Category verdicts

### 1. Change minimality — PASS
Production diff is a single replacement at `JarCentralDirectory.scala:490-492` inside `parseCenRecordsAll` (cited as line 593-area in the prompt; actual lines are 488-492 at HEAD because the file was previously edited in 04a/04b; matches the intent). The silent `pos = cenSize // truncated record, stop` is replaced with an `IOException` carrying jarPath, position, declared recordSize, and remaining-bytes. No drive-by edits, no signature changes, no API surface change. Sister functions at lines 318 (`parseCenRecords`) and 403 (`parseCenRecordsFull`) are intentionally left untouched — confirmed below.

### 2. Sister functions left silent-skip — PASS (with NOTE)
Verified `parseCenRecords` (line 294) keeps `pos = cenSize // truncated record, stop` at line 318, and `parseCenRecordsFull` (line 378) keeps the same idiom at line 403. Design 02-design.md scopes B11 to `parseAllEntries` (= `parseCenRecordsAll`) only; sisters serve `findEntry` / single-lookup paths where silent skip historically was the accepted behavior. Routing to end-of-project regression sweep is correct per the commit message.

### 3. Test P04c-T1 assertion strength — PASS
`JarCentralDirectoryTest.scala:604-614`. The test uses `intercept[java.io.IOException]` (kyo.Test idiom) and then asserts `ex.getMessage.contains("truncated CEN record")` with a descriptive failure clue. This is a substring check against the exact phrase emitted by the production code — strong enough to catch silent regression (e.g. someone reverting to silent skip would produce no exception and `intercept` would fail), and strong enough to catch wording drift (substring would break). Not weaker. Buffer crafting (100 bytes, signature `0x02014b50`, nameLen=1000 at offset 28) is correct per CEN layout — recordSize evaluates to 46+1000=1046 vs 100 available → triggers the new branch.

### 4. Remediation history — PASS
Initial verify FAILed on Option/Some + semicolon chain (`phase-04c-verify.md:27,39,112-130`). At HEAD: 0 Option/Some tokens in the test addition (grep confirmed), 0 semicolons in the new block (each ByteBuffer write on its own line). Rewrite landed clean as a single commit (not amended onto a broken one).

---

## NOTEs

- **NOTE for end-of-project regression sweep**: `parseCenRecords` (line 318) and `parseCenRecordsFull` (line 403) in the same file still contain `pos = cenSize // truncated record, stop` silent-skip. If a future invariant tightens to "no silent CEN drops on any path", these two sites need the same IOException treatment plus paired tests. Tracked in commit message as "Phase 26 regression sweep".
- **NOTE for Phase 05a prep**: None. 05a is JvmFileSource territory, unrelated to JarCentralDirectory.

---

## Overall: READY

Phase 04c is correctly scoped, minimally implemented, well-tested with a strong assertion, and remediated cleanly. No blockers for Phase 05a.
