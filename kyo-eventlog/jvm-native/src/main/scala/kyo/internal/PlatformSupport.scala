package kyo.internal

// Yields the current carrier thread to reduce spin-wait contention during claim loops.
// On JVM and Native, threads can truly contend; yielding lets the holder's blocking
// fsync complete before the waiter retries.
private[kyo] def yieldCurrentThread(): Unit = Thread.`yield`()
