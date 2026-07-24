package kyo

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

end BaseAITestSelectionTest
