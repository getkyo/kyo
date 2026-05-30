# Phase 05b post-commit audit

HEAD: d72193baa
Scope: B15 — JarMappedReader.open inner try/catch/finally with explicit channel.close(); var+null pattern with `// Unsafe:` comment; +2 tests (P05b-T1 empty file, P05b-T2 malformed file).

## 1. Inner try/finally semantics — PASS

Verified at JarMappedReader.scala:150-170. Control flow:

- Inner try (lines 155-158): channel.size() guard, channel.map() assignment to `mbb`.
- Inner catch (lines 159-161): wraps IOException from map() with "map failed for $jarPath" prefix, rethrows.
- Inner finally (lines 162-163): `channel.close()` runs on ANY inner-try exit — normal completion (size>0, map success), pre-map throw (size==0 IOException), and the catch-rethrow path.
- Outer finally (line 169): `raf.close()` runs unconditionally. RandomAccessFile.close() closes the underlying FileChannel via AbstractInterruptibleChannel; second close is a no-op (closed flag guards re-entry), so no double-close error.
- After inner-try exit on success, line 165 calls `parseAllEntries(jarPath, mbb)` with channel already closed; safe because MappedByteBuffer survives FD close (OS mapping outlives the channel — documented at lines 142-143).

## 2. `// Unsafe:` comment — PASS

Lines 152-153 carry the rationale:
> `// Unsafe: var + null hold the MappedByteBuffer across the inner try / finally so the value is still accessible after channel.close() runs; Java NIO interop, no shared state.`

Matches the precedent established at line 38 (existing HashMap.get null bridge). Documents both the structural necessity (must survive channel.close()) and the boundary (Java NIO interop, no shared state).

## 3. P05b-T2 negative assertion — NOTE (weak discriminator)

`assert(!ex.isInstanceOf[ClosedChannelException])` is structurally weak. `parseAllEntries(jarPath, mbb)` receives only the MappedByteBuffer, never the channel, so there is no code path in the malformed-file scenario that *could* throw `ClosedChannelException` regardless of whether the inner finally fired. The assertion passes trivially. The companion `getMessage != null && nonEmpty` is also tolerant. The test still exercises the right *path* (post-map IOException with channel already closed), but the specific negative assertion does not directly prove the close-before-propagation invariant. T1's positive "empty file" message check is the stronger half of the pair. Verify report flagged this as class-B non-blocking (phase-05b-verify.md:59); concurring.

## 4. Remediation history — PASS

flow-verify run 1 exit 1: class-A `bare-var` + `null-literal` hits at line 152 (no inline annotation). Remediation added the `// Unsafe:` comment matching line 38's pattern. Final flow-verify exit 0. Documented in commit message and phase-05b-verify.md:79-83.

## NOTE for Phase 05c prep

Carry forward Phase 26 (test-only introspector) NOTE from Phase 05a. Additionally: when 05c authors close-state assertions, prefer reflection-on-`FileChannel.isOpen()` or post-error mapped-buffer-still-readable checks over `!isInstanceOf[ClosedChannelException]`, which is structurally vacuous when the suspect resource is not referenced on the exception construction path.

## Overall

READY. Class-A clean, class-B accepted with documented rationale, B15 closed.
