package kyo.test.runner

import kyo.Chunk
import kyo.test.runner.internal.Randomize

class RandomizeTest extends kyo.test.Test[Any]:

    "shuffle with same seed produces same order" in {
        val items = Chunk.from(1 to 10)
        val r1    = Randomize.shuffle(items, 42L)
        val r2    = Randomize.shuffle(items, 42L)
        assert(r1 == r2, s"Same seed should produce same order; got $r1 vs $r2")
    }

    "shuffle with different seeds produces different orders" in {
        // Probabilistic: P(same order for 10 elements with two distinct seeds) ≈ 1/10! < 3e-7
        val items = Chunk.from(1 to 10)
        val r1    = Randomize.shuffle(items, 42L)
        val r2    = Randomize.shuffle(items, 43L)
        assert(r1 != r2, s"Different seeds should (with overwhelming probability) produce different orders; both gave $r1")
    }

    // ── Shuffle edge cases ──────────────────────────────────────────────────────────────────────────

    "phase8-test-3: shuffle(Chunk.empty, seed) returns Chunk.empty without throwing" in {
        val result = Randomize.shuffle(Chunk.empty[Int], 42L)
        assert(result == Chunk.empty[Int], s"Expected Chunk.empty, got $result")
    }

    "phase8-test-4: shuffle(Chunk(42), seed) returns Chunk(42)" in {
        val result = Randomize.shuffle(Chunk(42), 42L)
        assert(result == Chunk(42), s"Expected Chunk(42), got $result")
    }

    "phase8-test-5: shuffle(Chunk(1,2,3), seed) == shuffle(Chunk(1,2,3), seed) (determinism)" in {
        val r1 = Randomize.shuffle(Chunk(1, 2, 3), 42L)
        val r2 = Randomize.shuffle(Chunk(1, 2, 3), 42L)
        assert(r1 == r2, s"Same seed must produce same order; got $r1 vs $r2")
    }

end RandomizeTest
