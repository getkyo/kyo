package kyo.internal

// No-op on JS/Wasm: the runtime is single-threaded, so the claim spin cannot
// have a concurrent holder. The loop terminates immediately on first CAS success.
private[kyo] def yieldCurrentThread(): Unit = ()
