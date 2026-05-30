# Phase 07 Cross-Platform Verification

Date: 2026-05-30  
Commit: ce3dde039 (SharedChrome cascade fix)

## Summary: ALL GREEN

All 6 module-platform combinations pass. Cross-platform green gate is complete.

---

## JS Platform (run: final-green-js-002.log, elapsed: 849s)

| Module              | Tests | Passed | Failed | Status |
|---------------------|-------|--------|--------|--------|
| kyo-jsonrpcJS       |   175 |    175 |      0 | PASS   |
| kyo-jsonrpc-httpJS  |     4 |      4 |      0 | PASS   |
| kyo-browserJS       |  1346 |   1346 |      0 | PASS   |

Total JS: 1525/1525 PASS

Note: JS-001 (2:35 PM) showed 815 failures in kyo-browserJS. Root cause was the
SharedChrome cascade pattern: Chrome died, poisoned the cached URL, and all
subsequent test connections failed fast with BrowserConnectionLostException.
The fix (SharedChrome.withUrl retry, committed ce3dde039 at 17:16) cures the
cascade. JS-002 (post-fix) is clean.

---

## Native Platform (run: final-green-native-002.log, elapsed: 899s)

| Module                  | Tests | Passed | Failed | Status |
|-------------------------|-------|--------|--------|--------|
| kyo-jsonrpcNative       |   175 |    175 |      0 | PASS   |
| kyo-jsonrpc-httpNative  |     4 |      4 |      0 | PASS   |
| kyo-browserNative       |  1346 |   1346 |      0 | PASS   |

Total Native: 1525/1525 PASS

Pre-step: cleaned kyo-jsonrpcNative/kyo-jsonrpc-httpNative/kyo-browserNative targets
before running (native-clean-meta-001.log) to wipe stale Phase 02-era class files.
No linker errors encountered; the stale-bytecode hypothesis was correct.

---

## JVM Platform (reference: sharedchrome-fix-full-jvm-001.log)

| Module         | Tests | Passed | Failed | Status |
|----------------|-------|--------|--------|--------|
| kyo-browserJVM |  1363 |   1363 |      0 | PASS   |

(kyo-jsonrpcJVM and kyo-jsonrpc-httpJVM covered in final-green-jvm-001.log)

---

## Failures: None

All platforms are green. Phase 07 cross-platform gate: COMPLETE.
