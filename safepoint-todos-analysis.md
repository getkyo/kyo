# Safepoint design: settled state and open decisions

This file previously analyzed the original probing-plus-compaction design's TODOs. That design went through three measured iterations in review (chained per-home entry lines, pure ThreadLocal state, and the final shape) and this refresh records where things landed.

## Settled design (committed)

- Probing slot table, 65536 cells. The home-hit fast path is one array read and a reference compare, byte-identical to the original design.
- A thread claims exactly once; the claimed index is cached in a ThreadLocal consulted only on home miss. This structurally fixes the slot-instability bug found in review (a displaced thread could re-claim a nearer cell after a neighbor died, leaking cells and losing preemption).
- Dead cells are reclaimed in place during claim walks. There is no compaction sweep.
- Probe exhaustion returns a stable Overflowed sentinel: enter is true without counting, exit and restore are inert, save returns 0, consumeStopped is false, and stop misses. Reached past 64k concurrently live evaluating threads.
- stop is false for dead threads; preemption delivery uses the Stop wrapper on the target's unique cell.
- Period and Slots are StaticFlags (kyo.kernel.internal.Safepoint.period, kyo.kernel.internal.Safepoint.slotCount, power-of-two validated) mirrored once into @static finals. Measured: the mirror is load-bearing, direct flag reads cost 27 percent on the enter/exit micro row (1.705 vs 1.344) because the JVM does not constant-fold final instance fields; no StaticFlag-side change can help since only static finals fold.
- handlePartial's Defer arm checks only consumeStopped; the budget enter/exit pair was removed as redundant (every thunk is budget-guarded at its map application site).

## Coverage

SafepointConcurrencyTest pins: displaced threads keep slot and budget after nearby cells free (24576 parked virtual threads, red on the original design), claims reuse dead cells, the overflowed no-op contract at a full table, stop delivery and consumption races, dead threads not stoppable. SafepointTest pins the budget arithmetic against the 512 default.

## Open decisions

1. The original design printed a one-shot stderr warning when the table exhausted; the current design degrades silently. Restore a one-shot report on the first Overflowed claim, or keep silence?
2. inlineLimitKeepsZeroAllocation history for the record: 1.29 (spine board, 8192 slots, inline constants), 1.71 (65536 inline constants), 1.344 (65536 via static-final mirrors). The mirror shape recovered most of the regression; the remaining 4 percent vs the board best is unattributed.
