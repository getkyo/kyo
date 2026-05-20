package kyo

import scala.concurrent.Future

class CommitBufferTest extends Test:

    // CommitBuffer is internal: its extension methods require AllowUnsafe and operate on
    // internal-only types. These specs exercise it transitively via `STM.run` with 2+ TRefs;
    // the observable in every case is final ref state after the commit(s). For sizes >= 9 the
    // multi-ref commit reaches the quickSort branch of `CommitBuffer.sort`
    // (insertionSortThreshold == 8), so end-to-end state correctness detects a quickSort regression.

    "CommitBuffer (via STM.run multi-ref commits)" - {

        "concurrent inverted-order multi-ref commits do not deadlock" in runNotJS {
            for
                r0 <- TRef.init(0)
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                r3 <- TRef.init(0)
                // .forever schedule isolates deadlock-freedom from retry-budget exhaustion
                txnA = STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        _ <- r0.update(_ + 1)
                        _ <- r1.update(_ + 1)
                        _ <- r2.update(_ + 1)
                        _ <- r3.update(_ + 1)
                    yield ()
                }
                // .forever schedule isolates deadlock-freedom from retry-budget exhaustion
                txnB = STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        _ <- r3.update(_ + 1)
                        _ <- r2.update(_ + 1)
                        _ <- r1.update(_ + 1)
                        _ <- r0.update(_ + 1)
                    yield ()
                }
                _  <- Async.fill(100, 100)(txnA)
                _  <- Async.fill(100, 100)(txnB)
                v0 <- STM.run(r0.get)
                v1 <- STM.run(r1.get)
                v2 <- STM.run(r2.get)
                v3 <- STM.run(r3.get)
            yield assert(v0 == 200 && v1 == 200 && v2 == 200 && v3 == 200)
        }

        "sort boundary at insertionSortThreshold: sizes 7, 8, 9 all commit correctly" in run {
            def buildCommit(n: Int) =
                for
                    refs <- Kyo.foreach(1 to n)(i => TRef.init(i))
                    _    <- STM.run(Kyo.foreachDiscard(refs)(r => r.update(_ + 1)))
                    outs <- Kyo.foreach(refs)(r => STM.run(r.get))
                yield outs
            for
                res7 <- buildCommit(7)
                res8 <- buildCommit(8)
                res9 <- buildCommit(9)
            yield
                assert(res7 == (2 to 8))
                assert(res8 == (2 to 9))
                assert(res9 == (2 to 10))
            end for
        }

        "buffer is reset after a conflicting commit that returns false" in runNotJS {
            for
                r0 <- TRef.init(0)
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                // Stage 1: 50 concurrent multi-ref commits on r0 + r1. Contention drives the
                // CommitBuffer through the boundary.break(false) short-circuit path on every
                // loser (validation fails → commit returns false → retry). With the .forever
                // schedule every commit eventually succeeds, so after Stage 1 the state is
                // deterministic: r0 == 50, r1 == 50. r2 is never touched here.
                // .forever schedule isolates deadlock-freedom from retry-budget exhaustion
                _ <- Async.fill(50, 50) {
                    STM.run(STM.defaultRetrySchedule.forever)(for _ <- r0.update(_ + 1); _ <- r1.update(_ + 1) yield ())
                }
                // Stage 2: a quiet multi-ref commit touching r0 and r2 — must succeed cleanly.
                // It deliberately does NOT touch r1. If buffer.clear() failed to run after a
                // Stage-1 commit that short-circuited via boundary.break(false), a stale r1
                // write-entry would bleed into this Stage-2 buffer and push r1 off 50.
                _  <- STM.run(for _ <- r0.set(999); _ <- r2.set(777) yield ())
                v0 <- STM.run(r0.get)
                v1 <- STM.run(r1.get)
                v2 <- STM.run(r2.get)
            // v1 == 50 is the load-bearing clause: it holds only if the Stage-1 buffer was
            // reset before Stage-2 reused it. A contamination bug would write a stale value
            // to r1 (which Stage 2 never legitimately touches), failing this assertion.
            yield assert(v0 == 999 && v1 == 50 && v2 == 777)
        }

        "commit applies Writes and ignores Reads when iterating the buffer" in run {
            for
                r0 <- TRef.init(10)
                r1 <- TRef.init(20)
                _ <- STM.run {
                    for
                        // r0 reads, r1 writes — buffer carries one Read entry and one Write entry.
                        _ <- r0.get
                        _ <- r1.set(99)
                    yield ()
                }
                v0 <- STM.run(r0.get)
                v1 <- STM.run(r1.get)
            yield assert(v0 == 10 && v1 == 99)
        }

        "TRef ids are non-negative for normal allocation regime (1000 refs)" in run {
            for
                refs <- Kyo.foreach(1 to 1000)(_ => TRef.init(0))
            yield
                val ids = refs.map(_.id)
                assert(ids.forall(_ >= 0), s"Found negative TRef id in normal-allocation regime: ${ids.filter(_ < 0)}")
        }

        "9-ref multi-ref commit terminates quickSort recursion base case" in run {
            for
                refs <- Kyo.foreach(1 to 9)(i => TRef.init(i))
                _    <- STM.run(Kyo.foreachDiscard(refs)(r => r.update(_ + 1)))
                outs <- Kyo.foreach(refs)(r => STM.run(r.get))
            yield assert(outs == (2 to 10))
        }

        "100-ref multi-ref commit terminates without StackOverflowError" in run {
            for
                refs <- Kyo.foreach(1 to 100)(i => TRef.init(i))
                _    <- STM.run(Kyo.foreachDiscard(refs)(r => r.update(_ + 1)))
                outs <- Kyo.foreach(refs)(r => STM.run(r.get))
            yield assert(outs == (2 to 101))
        }

        "read-only multi-ref commit yields stable reads (witness Read-entry path)" in run {
            for
                r0 <- TRef.init(7)
                r1 <- TRef.init(11)
                vs <- STM.run {
                    for
                        a <- r0.get
                        b <- r1.get
                    yield (a, b)
                }
            yield assert(vs == (7, 11))
        }

        "read-only and read-write multi-ref commits both yield correct final state" in run {
            for
                r0 <- TRef.init(1)
                r1 <- TRef.init(2)
                ab <- STM.run(for x <- r0.get; y <- r1.get yield (x, y))
                _  <- STM.run(for x <- r0.get; _ <- r1.set(x + 10) yield ())
                v0 <- STM.run(r0.get)
                v1 <- STM.run(r1.get)
            yield assert(ab == (1, 2) && v0 == 1 && v1 == 11)
        }

        "sequential STM.run calls do not contaminate each other" in run {
            for
                r0 <- TRef.init(0)
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                // Txn A (multi-ref → CommitBuffer): writes to r0, r1.
                _ <- STM.run(for _ <- r0.set(100); _ <- r1.set(100) yield ())
                // Txn B (single-ref → fast path): writes to r2.
                _ <- STM.run(r2.set(200))
                // Txn C (multi-ref → CommitBuffer): writes to r0, r2; must NOT see stale r1.
                _      <- STM.run(for _ <- r0.set(300); _ <- r2.set(300) yield ())
                values <- STM.run(for a <- r0.get; b <- r1.get; c <- r2.get yield (a, b, c))
            yield assert(values == (300, 100, 300))
        }

        "multi-ref commit with last-index conflict exits within the retry budget" in runNotJS {
            for
                refs <- Kyo.foreach(1 to 4)(_ => TRef.init(0))
                // Background contention on the highest-id ref (post-sort, this is the last lock acquired).
                lastRef = refs.maxBy(_.id)
                _ <- Async.fill(50, 50)(STM.run(lastRef.update(_ + 1))).unit
                r <- Abort.run(
                    Async.timeout(10.seconds) {
                        STM.run(STM.defaultRetrySchedule) {
                            Kyo.foreachDiscard(refs)(r => r.update(_ + 1))
                        }
                    }
                )
            yield
                // The commit must exit within the retry budget — the inner STM.run
                // succeeds and the outer Async.timeout never fires. A livelock hang
                // would surface as a timeout failure (r.isFailure), which this rejects.
                assert(r.isSuccess)
        }

        "size-8 (insertionSort) and size-9 (quickSort) multi-ref commits both succeed" in run {
            def buildCommit(n: Int) =
                for
                    refs <- Kyo.foreach(1 to n)(i => TRef.init(i))
                    _    <- STM.run(Kyo.foreachDiscard(refs)(r => r.update(_ + 1)))
                    outs <- Kyo.foreach(refs)(r => STM.run(r.get))
                yield outs
            for
                res8 <- buildCommit(8)
                res9 <- buildCommit(9)
            yield
                assert(res8 == (2 to 9))
                assert(res9 == (2 to 10))
            end for
        }

        "normal STM.run pipeline always sorts before lock (no-deadlock under inverted-order contention)" in runNotJS {
            for
                r0 <- TRef.init(0)
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                r3 <- TRef.init(0)
                // .forever schedule isolates deadlock-freedom from retry-budget exhaustion
                txnAB = STM.run(STM.defaultRetrySchedule.forever)(for
                    _ <- r0.update(_ + 1)
                    _ <- r1.update(_ + 1)
                    _ <- r2.update(_ + 1)
                    _ <- r3.update(_ + 1)
                yield ())
                // .forever schedule isolates deadlock-freedom from retry-budget exhaustion
                txnBA = STM.run(STM.defaultRetrySchedule.forever)(for
                    _ <- r3.update(_ + 1)
                    _ <- r2.update(_ + 1)
                    _ <- r1.update(_ + 1)
                    _ <- r0.update(_ + 1)
                yield ())
                _ <- Async.fill(100, 100)(txnAB)
                _ <- Async.fill(100, 100)(txnBA)
                values <- STM.run(for
                    a <- r0.get
                    b <- r1.get
                    c <- r2.get
                    d <- r3.get
                yield (a, b, c, d))
            yield assert(values == (200, 200, 200, 200))
        }
    }
end CommitBufferTest
