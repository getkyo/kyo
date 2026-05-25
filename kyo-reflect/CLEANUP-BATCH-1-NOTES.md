# Cleanup Batch 1 Notes

## Status: COMPLETE (JVM 148/148 tests passing; JS + Native compile clean)

## Fix plan

### P1-W1: TastyHeader try/catch for control flow
- Add `tryReadByte: Maybe[Byte]` to ByteView trait + Heap impl
- Add `tryReadBytes(n: Int): Boolean` or use `remaining` guard
- Replace try/catch in TastyHeader.read with `if view.remaining < needed then Abort.fail(...)`
- The `readUncompressedLong` helper also reads 8 bytes unchecked; guard it too

### P1-W2: ByteViewTest asInstanceOf[ByteView.Heap]
- `subView` already returns `ByteView` (trait); simplest fix: narrow to `Heap` return type
- But `Mapped.subView` would also need to return `Heap` which is wrong
- Better: override `subView` in `Heap` to return `ByteView.Heap` specifically
- Then the test can just assign `val sub: ByteView.Heap = view.subView(1, 4)` without cast

### P1-W3: Early return from Abort computation
- rewrite magic-byte loop with `if !compatible then Abort.fail(...)` pattern
- Loop over magic bytes: extract to a recursive-style or fold approach
- readBytes should be rewritten without `return`

### P2-W1: InternerTest leaf 3 different shards
- Compute FNV-1a hash for "alpha" and "beta" with 2 shards
- Need to find two strings where FNV-1a hash & 1 differ (shard 0 vs shard 1)
- Use `new Interner(2)` and compute statically

### P2-W2: NameUnpicklerTest leaf 9 weak assertion
- Add `assert(qualified.get.asString == "kyo.fixtures")`

### P2-W3: NameUnpicklerTest leaf 11 interning identity
- Add `assert(n1 eq n2)` (reference equality of Name/Entry)

### P2-W4: Cross-platform fixture embedding
- Generate hex literal for PlainClass.tasty (509 bytes)
- Create `kyo-reflect/shared/src/test/scala/kyo/fixtures/Embedded.scala`
- Replace `getClass.getResourceAsStream(...)` calls with `Embedded.plainClassTasty`

### P2-W5: Missing trailing-padding-bytes test
- Add test 7 to NameUnpicklerTest
- nameTableByteCount=7, UTF8 tag + len=5 + 5 bytes + 1 trailing 0xFF
- Assert loop stops at byte 7

### P2-W6: Memo/SingleAssign asInstanceOf justification
- Add `// AsInstanceOf justified:` comments at each site

### P2-W7: Memo/SingleAssign outside Sync
- Rename to Memo.Unsafe / add WARNING scaladoc
- Add `(using AllowUnsafe)` to get()/set()
- Update callers with AllowUnsafe.embracing

## FNV-1a shard computation (for P2-W1)
FNV-1a on "alpha" = ?
FNV-1a on "beta" = ?
Need h & 1 to differ for shard selection with numShards=2
