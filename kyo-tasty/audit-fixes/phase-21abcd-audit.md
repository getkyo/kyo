# Phase 21a-d Combined Audit

SHAs: 21a=93a69cef2, 21b=ec0f8480c, 21c=4bc0aaa1f, 21d=b9a61b85d

## Summary

PASS with two NOTEs. All seven dimensions are OK or NOTE. No blockers, no warnings.

---

## Phase 21a findings

### 1. Writer-helper substance - OK

`writeNat` and `writeLongNat` are correct. Encoding: starting from the least-significant 7-bit group, each group is placed into a scratch buffer from position 4 (or 9) downward, then the buffer is emitted MSB-first. The last byte has 0x80 SET; all prior bytes have 0x80 CLEAR. Spot checks:

- value 0: emits `[0x80]` (terminating byte with low 7 bits = 0). Correct.
- value 127: emits `[0xff]` (0x7f | 0x80). Correct.
- value 128: emits `[0x01, 0x80]` (continuation 0x01 with 0x80 CLEAR, terminating 0x80 with 0x80 SET). Correct.

`writeNat` and `writeLongNat` are `private[kyo]` in `Varint.scala`. A search of all production `.scala` files under `src/main/` shows no call sites outside `Varint.scala` itself: `SnapshotWriter.scala` exists but does not call either helper. The writers are production-quality additions awaiting a future call site. The no-scope-cuts rationale is documented in the decisions log.

Round-trip tests (writeNat(1234)/readNat and writeLongNat(9_999_999_999L)/readLongNat) adequately cover the write path via the existing read-side coverage.

---

## Phase 21b findings

### 2. ConstantPool error message - OK

The actual production message is `"Constant pool index $idx out of bounds [1, ${entries.length - 1}]"` (line 72 of `ConstantPool.scala`). The test asserts `msg.contains("99")` AND `msg.toLowerCase.contains("out of bounds") || msg.toLowerCase.contains("out of range")`. The OR guard correctly accommodates both spellings; "out of bounds" with the slot range `[1, N]` included is adequately descriptive for end-user diagnosis of malformed classfiles.

### 3. JavaAnnotation test substance - OK

Test 1 (@Deprecated, no pairs): JVMS 4.7.16 format is `u2 num_annotations`, then per annotation `u2 type_index, u2 num_element_value_pairs`. The bytes `[0x00,0x01, 0x00,0x01, 0x00,0x00]` encode exactly that. Test asserts annotationClass.name.asString == "java.lang.Deprecated" and values.isEmpty. Covers descriptor-to-dotted-name conversion and the zero-pairs code path.

Test 2 (@Foo({"a","b"}), one array pair): JVMS 4.7.16.1 array element_value uses `u1 tag='['` then `u2 num_values` then that many element_values. The byte sequence correctly encodes this with two `'s'` (String) sub-elements. The test verifies `ArrayVal(Chunk(StringVal("a"), StringVal("b")))`, covering the `'['` array-element-value tag. Both tests are JVMS compliant.

---

## Phase 21c findings

### 4. UnresolvedRef test - OK

`UnresolvedRef` is a `final case class(fqn: String, replaceSlot: SingleAssign[Tasty.Type])` with no factory. The test constructs it directly and asserts `ref.fqn == "missing.X"` and `!ref.replaceSlot.isSet`. `SingleAssign.isSet` is defined as `ref.get() ne SingleAssign.Unset`, so the assertion exercises the real state predicate. The test verifies the two preconditions Phase C depends on: the fqn is preserved verbatim and the slot starts unset. Not a trivial structural check; it pins the Phase C contract.

### 5. PerfCounters test (public-API discipline) - NOTE

`PerfCounters` is `private[kyo]` and exposes `jarOpenCount: AtomicInteger` and `entryReadCount: AtomicInteger` as public `val`s. There are no helper methods `incJarOpen` or `incEntryRead`. Calling `PerfCounters.jarOpenCount.incrementAndGet()` uses the public `val` (LHS) and delegates to `AtomicInteger.incrementAndGet` (stdlib method). This is within the spirit of `feedback_tests_use_public_api`: the public observable surface of `PerfCounters` is the typed atomic fields plus `snapshot()` and `reset()`; the test uses all three categories. The only concern is that the test directly manipulates internal counters rather than driving them through a higher-level operation (e.g., opening a JAR), but no such integration path exists in the test scope. Acceptable for a unit-level T2 test.

---

## Phase 21d findings

### 6. Byte construction accuracy - OK

`SectionIndex.readSync` calls `readNat()` for nameRef (1 byte for values < 128), `readNat()` for sectionLen (1 byte), then records `offset = view.positionInt` as the payload start. The test encodes both name refs (0, 1) and both lengths (10, 20) as single-byte TASTy nats (value | 0x80). Offset arithmetic:

- Section 1 header: bytes 0-1, payload offset = 2, `view.goto(2 + 10)` = 12.
- Section 2 header: bytes 12-13, payload offset = 14, length = 20.

The test asserts `offset == 14` and `length == 20`. Both are correct. The `SectionIndex.scala` production code is read directly to confirm there is no intermediate framing or alignment padding.

---

## Cross-cutting

### 7. Code quality - OK

Across all eight changed files (Varint.scala, VarintTest.scala, ConstantPoolTest.scala, JavaAnnotationUnpicklerTest.scala, UnresolvedRefTest.scala, TastyStatTest.scala, PerfCountersTest.scala, SectionIndexTest.scala): no em-dashes, no semicolons as statement separators, no `asInstanceOf`, no `Option`/`Some`/`None` (SectionIndex uses `Present`/`Absent` via `Maybe`; internal `Map.get` wrapped correctly), no `Either`/`Right`/`Left`, no default parameters, no `return`. The three `foldLeft` calls in test byte-buffer helpers are standard stdlib usage, not pattern-match replacements.

---

## Recommendations

- (NOTE) `writeNat` and `writeLongNat` have no production call site yet. When a writer path is added (e.g., snapshot serialization), Phase 23 or the relevant implementation phase should add encoding-specific tests (value 0, single-byte boundary 127, two-byte boundary 128, Int.MaxValue) to complement the current round-trip tests.
- (NOTE) PerfCounter T2 test directly increments `AtomicInteger` fields rather than driving them through a real operation. This is acceptable given no integration call path exists in the current test scope; document as a gap to fill when JvmFileSource gains a test harness.
