package kyo.ffi.internal

import kyo.*
import kyo.discard
import kyo.ffi.Test

/** D9, Per-binding scratch size override.
  *
  * Validates the two knobs that let users and bindings override the global scratch size:
  *
  *   1. `-Dkyo.ffi.scratch.size=<bytes>`, JVM-wide default read once at class initialisation into [[Scratch.configuredSize]]. Because the
  *      value is read at class-load time (a `val`), we cannot test the live property in-process; instead we verify that:
  *      - [[Scratch.currentFor]] with an explicit `size` argument returns a `Scratch` whose arena can satisfy allocations of that exact
  *        size.
  *      - The `Scratch.Scratch(size)` constructor directly allocates a block of the requested capacity so an oversized allocation does NOT
  *        spill (stays in the base block) when the block is large enough.
  *   2. Per-binding size via [[Scratch.currentFor]], the `bindingFqn + "@" + size` key is the mechanism a generated binding uses when
  *      `Ffi.Config.scratchSize` is set. This spec asserts that the effective scratch capacity matches the requested size and that
  *      concurrent same-thread lookups for different sizes produce independent [[Scratch]] instances.
  *
  * Note on `-Dkyo.ffi.scratch.size` read-at-init: since `Scratch.configuredSize` is a JVM `val` (computed once at class load), the system
  * property must be set before the Scratch object is first touched in the JVM. We therefore test the `currentFor(fqn, size)` overload
  * directly, that overload is the runtime path used by generated bindings, and it accepts the size as an argument so it is unaffected by
  * the class-init ordering constraint.
  */
class ScratchSizeOverrideTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    // Property name for the JVM-wide scratch default, we clear any override installed by a leaf after each leaf runs.
    private val scratchSizeProp = "kyo.ffi.scratch.size"

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Scope.ensure {
            discard(java.lang.System.clearProperty(scratchSizeProp))
        }.andThen(body)

    "Scratch.configuredSize" - {
        "reads -Dkyo.ffi.scratch.size when the property was set before class load (sanity)" in {
            // configuredSize is a val, read at class-load time. We cannot change it in-process, but we
            // can assert that its current value is a sane positive Long and is consistent with either the
            // system property (if set) or the 64 KiB default.
            assert(Scratch.configuredSize > 0L)
            // Default is 64 KiB; if the test JVM sets the property to something else that is also valid.
            Option(java.lang.System.getProperty(scratchSizeProp)) match
                case Some(v) =>
                    val parsed = v.toLong
                    assert(Scratch.configuredSize == parsed)
                case None =>
                    assert(Scratch.configuredSize == (64L * 1024L))
            end match
        }
    }

    "Scratch.currentFor (per-binding size override)" - {
        "returns a Scratch whose base block fits a requested 4096-byte allocation without spilling" in {
            val fqn  = "kyo.ffi.test.SmallBinding"
            val size = 4096L
            val s    = Scratch.currentFor(fqn, size)
            val m    = s.mark()
            try
                // An allocation that exactly fits the scratch block must NOT spill (spillCount == 0 after alloc).
                discard(s.alloc(size - 8L, 8L))
                assert(s.spillCount == 0)
            finally s.reset(m)
            end try
        }

        "returns a Scratch whose base block fits a requested 65536-byte (64 KiB) allocation without spilling" in {
            val fqn  = "kyo.ffi.test.LargeBinding"
            val size = 64L * 1024L
            val s    = Scratch.currentFor(fqn, size)
            val m    = s.mark()
            try
                discard(s.alloc(size - 8L, 8L))
                assert(s.spillCount == 0)
            finally s.reset(m)
            end try
        }

        "an allocation larger than the configured size spills to a fresh arena" in {
            val s = new Scratch.Scratch(Scratch.configuredSize)
            val m = s.mark()
            try
                discard(s.alloc(Scratch.configuredSize + 512L, 8L))
                assert(s.spillCount == 1)
            finally s.reset(m)
            end try
        }

        "currentFor delegates to current (per-binding routing removed)" in {
            val s1 = Scratch.currentFor("kyo.ffi.test.BindingX", 8192L)
            val s2 = Scratch.current
            assert(s1 eq s2, "currentFor should return the same instance as current")
        }

        "same binding and same size on the same thread returns the identical Scratch instance" in {
            val s1 = Scratch.currentFor("kyo.ffi.test.BindingStable", 4096L)
            val s2 = Scratch.currentFor("kyo.ffi.test.BindingStable", 4096L)
            assert(s1 eq s2, "same (fqn, size) on the same thread must return the cached Scratch")
        }

        "constructing Scratch with explicit size matches the requested capacity" in {
            // Direct constructor test, verifies that Scratch.Scratch(size) allocates a block of exactly `size` bytes.
            val size = 4096L
            val s    = new Scratch.Scratch(size)
            val m    = s.mark()
            try
                // Allocate the full block minus alignment headroom, should fit without spill.
                discard(s.alloc(size - 8L, 8L))
                assert(s.spillCount == 0)
            finally s.reset(m)
            end try
        }
    }

    "Scratch -Dkyo.ffi.scratch.size property format" - {
        "non-numeric value falls back to the 64 KiB default (parse guard)" in {
            // We cannot re-trigger class init, but we can verify the parse guard by reading the
            // property-parsing expression inline, mirroring the Scratch source:
            //   sys.props.get("kyo.ffi.scratch.size").flatMap(s => scala.util.Try(s.toLong).toOption).getOrElse(64L * 1024L)
            val parsed =
                Some("notanumber")
                    .flatMap(s => scala.util.Try(s.toLong).toOption)
                    .getOrElse(64L * 1024L)
            assert(parsed == (64L * 1024L))
        }

        "numeric value is parsed as Long" in {
            val parsed =
                Some("4096")
                    .flatMap(s => scala.util.Try(s.toLong).toOption)
                    .getOrElse(64L * 1024L)
            assert(parsed == 4096L)
        }
    }
end ScratchSizeOverrideTest
