# Phase 05c decisions

Finding: C3. `ConstantPool.scala:217-223` `Utf8Lazy` construction rejects `ByteView.Mapped` with an error message instead of supporting it. If classfile reading is wired to mmap, the decode path throws `IllegalStateException`.

## Approach chosen: eager copy via `peekByte` in `ConstantPool.read`

The plan's BEFORE/AFTER described a `Utf8Lazy` with a `view: ByteView` field and an inline dispatch decode method. The actual code has already reduced `Utf8Lazy` to hold an `Array[Byte]` (bytes are always heap-resident after construction). The structural fix is therefore in `ConstantPool.read` - the `ByteView.Mapped` branch that previously set `errorMsg`.

The fix replaces the error-setting branch with an eager `peekByte`-loop that reads bytes from the mapped region at absolute offsets `[off, off+len)` into a fresh `Array[Byte]`, then constructs `CpEntry.Utf8Lazy(buf, 0, len)` exactly like the `Heap` branch does.

Rationale for `peekByte` over `goto + readByte`:
- The cursor has already advanced past the UTF-8 bytes (the while loop consumed them). Using `peekByte(off + i)` reads by absolute position without disturbing the cursor, which is the contract the enclosing while loop depends on.
- `peekByte` is defined on the `ByteView` trait and available on all concrete implementations.

## Files modified

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala`: replaced the `ByteView.Mapped` error branch with an eager `peekByte` copy loop (lines 217-223).
- `kyo-tasty/shared/src/test/scala/kyo/ConstantPoolTest.scala`: new file with 2 tests.

## Tests added

1. `ConstantPool.read on Mapped ByteView decodes UTF-8 entry correctly` - builds a minimal one-entry constant pool, wraps it in a `HeapMappedStub` (in-memory `ByteView.Mapped` concrete subclass), calls `ConstantPool.read`, then decodes utf8(1) and asserts it equals `"foo"`. Pins C3.

2. `ConstantPool.read on Mapped ByteView: cursor is positioned past pool after read` - same setup with `"bar"`, asserts that after `ConstantPool.read` the view cursor equals `bytes.length`. Pins C3 (cursor integrity).

The `HeapMappedStub` is a private inner class in the test file, extending `ByteView.Mapped` with an `Array[Byte]` backing. It lives in `shared/` so the test is cross-platform.

## Convention sweep

- No em-dashes, no semicolons in chains, no `Option`/`Some`, no `var+null` without `// Unsafe:` comment.
- The new `var cursor` in `HeapMappedStub` is test-local private state, not a `null`-initialized holder.

## Verification results

- `project kyo-tasty / testOnly kyo.ConstantPoolTest`: 2/2 passed.
- `kyo-tastyJS/Test/compile`: success.
- `kyo-tastyNative/Test/compile`: success.
- HEAD: `d72193baa` (unchanged).
