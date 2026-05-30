# Phase 05c Audit — C3: Mapped ByteView in ConstantPool Utf8Lazy

HEAD: a57dde403
Path: kyo-tasty/audit-fixes/phase-05c-audit.md

## Verdicts

### 1. Cursor preservation — PASS
`ConstantPool.scala:209` captures `off = view.positionInt` BEFORE the advance
loop at 211-214 walks the cursor over `len` bytes via `readByte()`. The
view-variant dispatch at 217-229 then runs with the cursor already past the
UTF-8 payload. `peekByte((off + i).toLong)` uses absolute offsets only and
does not touch the cursor (confirmed by the base-trait contract at
`ByteView.scala:14-15`: "without advancing the cursor"). Test 2 pins the
post-read cursor at `bytes.length`, directly exercising the ordering.

### 2. HeapMappedStub fidelity — PASS
`ConstantPoolTest.scala:21-41` extends `ByteView.Mapped` and implements
`peekByte(at)` as `data(at.toInt)` (absolute-positioned, no cursor mutation),
matching the Heap implementation at `ByteView.scala:85`. `readByte()`
advances a private `cursor`. `subView`, `goto`, `position`, `remaining`,
`readEnd` are all backing-array-faithful. Sufficient for exercising the
Mapped branch on all three platforms without mmap.

### 3. peekByte semantics across Heap/Mapped — PASS
`peekByte` is declared on the `ByteView` base trait with absolute-offset
semantics; both Heap (`bytes(Math.toIntExact(at))`) and the Mapped stub
honor "no cursor advance". The Phase 05c arm captures `off` pre-advance and
indexes `[off, off+len)`, which is correctness-equivalent to the Heap
`copyBytes(off, off+len)` path.

### 4. New `var i` loop counter — PASS (benign)
Inner `var i` at line 224 shadows the outer `var i` at line 211 but is
scoped to the Mapped case arm; outer `i` is no longer live. Mirrors the
existing line-211 pattern. Class-A NEW hit flagged by verify is a false
positive.

## NOTE for Phase 06 prep
None. C3 closes the Mapped/classfile gap symmetrically with Phase 04b's
snapshot mmap widening. Phase 06 can proceed against the unified ByteView
contract without further classfile-side caveats.

## Overall
READY.
