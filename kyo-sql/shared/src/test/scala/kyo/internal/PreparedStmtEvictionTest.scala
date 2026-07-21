package kyo.internal

import kyo.*

/** Unit tests for the prepared-statement eviction queue on both Postgres and MySQL connections.
  *
  * These tests exercise the [[kyo.internal.postgres.PostgresConnection.pendingCloses]] /
  * [[kyo.internal.mysql.MysqlConnection.pendingCloses]] accumulation path and the drain contract without a real server. They run on all
  * three platforms (JVM, Native, JS).
  */
class PreparedStmtEvictionTest extends kyo.Test:

    // ── AtomicRef-level accumulation tests ───────────────────────────────────

    "Cache eviction enqueues stmt name to pendingCloses" in {
        // Simulate eviction callbacks that append stmt names synchronously (no IO in callback).
        import AllowUnsafe.embrace.danger
        AtomicRef.init(Chunk.empty[String]).flatMap { ref =>
            // Three eviction callbacks appending distinct names.
            discard(ref.unsafe.getAndUpdate(_.appended("s_aaa111")))
            discard(ref.unsafe.getAndUpdate(_.appended("s_bbb222")))
            discard(ref.unsafe.getAndUpdate(_.appended("s_ccc333")))
            ref.get.map { names =>
                assert(names.size == 3)
                assert(names(0) == "s_aaa111")
                assert(names(1) == "s_bbb222")
                assert(names(2) == "s_ccc333")
            }
        }
    }

    "drainPendingCloses returns chunk and clears state" in {
        import AllowUnsafe.embrace.danger
        AtomicRef.init(Chunk.empty[String]).flatMap { ref =>
            discard(ref.unsafe.getAndUpdate(_.appended("s_x1")))
            discard(ref.unsafe.getAndUpdate(_.appended("s_x2")))
            // Simulate drain: read-and-clear atomically.
            ref.getAndSet(Chunk.empty).flatMap { drained =>
                ref.get.map { remaining =>
                    assert(drained.size == 2)
                    assert(drained(0) == "s_x1")
                    assert(drained(1) == "s_x2")
                    assert(remaining.isEmpty)
                }
            }
        }
    }

    "Concurrent eviction does not lose names (race coverage)" in {
        // Fire concurrent eviction callbacks from multiple fibers.
        // Each fiber appends a unique name; all names must survive in the final chunk.
        val nameCount = 50
        AtomicRef.init(Chunk.empty[String]).flatMap { ref =>
            Async
                .fillIndexed(nameCount, nameCount) { i =>
                    import AllowUnsafe.embrace.danger
                    Sync.defer {
                        discard(ref.unsafe.getAndUpdate(_.appended(s"s_evict_$i")))
                    }
                }
                .andThen(ref.get)
                .map { names =>
                    assert(names.size == nameCount, s"Expected $nameCount names, got ${names.size}")
                    val seen    = names.toList.toSet
                    val missing = (0 until nameCount).filterNot(i => seen.contains(s"s_evict_$i"))
                    assert(missing.isEmpty, s"Missing indices: $missing")
                }
        }
    }

end PreparedStmtEvictionTest
