package kyo

import kyo.ai.completion.provider

/** Unit coverage for BaseAITest.selectBackends, the pure flag-to-backends selection. Extends BaseAITest
  * to reach the private[kyo] method but registers no backend leaf of its own, so nothing hits a provider.
  */
class BaseAITestSelectionTest extends BaseAITest:

    "selectBackends" - {
        "an empty flag runs every backend" in {
            assert(selectBackends("").map(_.label) == allBackends.map(_.label), "no narrowing selects all")
            assert(selectBackends("   ").map(_.label) == allBackends.map(_.label), "blank narrows nothing")
        }

        "a single name selects exactly that backend" in {
            val selected = selectBackends("anthropic")
            assert(selected.map(_.label) == Chunk("Anthropic"), s"one name, one backend: ${selected.map(_.label)}")
        }

        "a comma list selects each named backend, order following the catalog" in {
            val selected = selectBackends("deepseek, anthropic").map(_.label)
            assert(selected.contains("DeepSeek") && selected.contains("Anthropic"), s"both named: $selected")
            assert(selected.size == 2, s"only the two named: $selected")
        }

        "hyphen and underscore spellings both match a two-word label" in {
            assert(selectBackends("claude-code").map(_.label) == Chunk("Claude Code"))
            assert(selectBackends("claude_code").map(_.label) == Chunk("Claude Code"))
        }

        "a name matching nothing throws, listing the known names" in {
            val ex = intercept[IllegalArgumentException](selectBackends("nope"))
            assert(ex.getMessage.contains("nope"), s"names the bad input: ${ex.getMessage}")
            assert(ex.getMessage.contains("anthropic"), s"lists the known names: ${ex.getMessage}")
        }
    }

    "the live matrix is pinned to codex, whatever the flag says" in {
        // The protection that failed twice and cost real money, restated as the property that actually
        // holds now. It used to read: an absent `kyo.ai.completion.provider` flag selects the full matrix,
        // so a narrowed run depends on the flag reaching the FORKED test JVM. That made the safe state the
        // one you reach by remembering something, and it was missed once through the build wiring and once
        // through a plain `sbt kyo-aiJVM/test`, which drove every keyed backend.
        //
        // BaseAITest now pins the live list, so the flag cannot widen a run and neither can a forgotten
        // flag or broken forwarding. Reverting to the full matrix is a one-line change there.
        val flag = provider()
        println(s"[flag-resolution] provider()='$flag' backends=${backends.map(_.label).mkString(",")}")
        assert(
            backends.map(_.label) == Chunk("Codex"),
            s"the live matrix must be pinned to codex regardless of the flag '$flag', got ${backends.map(_.label)}"
        )
        assert(
            allBackends.size > 1 && selectBackends("").size == allBackends.size,
            "and the selection logic itself is untouched, so restoring the full matrix stays a one-line revert"
        )
    }

end BaseAITestSelectionTest
