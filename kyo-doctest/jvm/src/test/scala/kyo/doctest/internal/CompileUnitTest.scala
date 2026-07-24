package kyo.doctest.internal

import kyo.*

/** Tests for CompileUnit covering Isolated, Inherited, Env, Nested, and setup block grouping. */
class CompileUnitTest extends kyo.test.Test[Any]:

    private def makeBlock(
        body: String,
        visibility: Block.Visibility = Block.Visibility.Isolated,
        file: String = "README.md",
        lineStart: Int = 1
    ): Block =
        Block(
            file = kyo.Path(file),
            lineStart = lineStart,
            lineEnd = lineStart + 3,
            body = body,
            visibility = visibility,
            expect = Block.Expectation.Compiles,
            platform = Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native),
            carrier = Block.Carrier.Visible
        )

    "Isolated blocks each become their own compile unit" in {
        val b1 = makeBlock("val a = 1", Block.Visibility.Isolated, lineStart = 1)
        val b2 = makeBlock("val b = 2", Block.Visibility.Isolated, lineStart = 10)
        val b3 = makeBlock("val c = 3", Block.Visibility.Isolated, lineStart = 20)

        val units = CompileUnit.group(Chunk(b1, b2, b3))
        assert(units.size == 3, s"expected 3 compile units, got ${units.size}")
        // Each unit has exactly one block.
        assert(units.forall(_.blocks.size == 1), "each Isolated unit should contain exactly one block")
        // Each unit's source contains only its own block body.
        assert(units(0).syntheticSource.content.contains("val a = 1"))
        assert(!units(0).syntheticSource.content.contains("val b = 2"))
        assert(units(1).syntheticSource.content.contains("val b = 2"))
        assert(!units(1).syntheticSource.content.contains("val a = 1"))
    }

    "Inherited blocks accumulate prior bodies as prelude" in {
        val b1 = makeBlock("val a = 1", Block.Visibility.Inherited, lineStart = 1)
        val b2 = makeBlock("val b = a + 1", Block.Visibility.Inherited, lineStart = 10)
        val b3 = makeBlock("val c = b + 1", Block.Visibility.Inherited, lineStart = 20)

        val units = CompileUnit.group(Chunk(b1, b2, b3))
        assert(units.size == 3, s"expected 3 compile units, got ${units.size}")
        // First unit: only its own body.
        assert(units(0).syntheticSource.content.contains("val a = 1"))
        // Second unit: must include val a = 1 as prelude.
        val unit2 = units(1).syntheticSource.content
        assert(unit2.contains("val a = 1"), s"unit 2 should include val a as prelude: $unit2")
        assert(unit2.contains("val b = a + 1"), s"unit 2 should include val b: $unit2")
        // Third unit: must include both val a and val b as prelude.
        val unit3 = units(2).syntheticSource.content
        assert(unit3.contains("val a = 1"), s"unit 3 should include val a: $unit3")
        assert(unit3.contains("val b = a + 1"), s"unit 3 should include val b: $unit3")
        assert(unit3.contains("val c = b + 1"), s"unit 3 should include val c: $unit3")
    }

    "Env(\"tutorial\") blocks compile together as one unit" in {
        val b1 = makeBlock("val x = 1", Block.Visibility.Env("tutorial"), lineStart = 1)
        val b2 = makeBlock("val y = x + 1", Block.Visibility.Env("tutorial"), lineStart = 10)
        val b3 = makeBlock("val z = y + 1", Block.Visibility.Env("tutorial"), lineStart = 20)

        val units = CompileUnit.group(Chunk(b1, b2, b3))
        assert(units.size == 1, s"expected 1 compile unit for Env(tutorial), got ${units.size}")
        val content = units(0).syntheticSource.content
        // All three bodies must appear in the single compile unit.
        assert(content.contains("val x = 1"), "val x missing")
        assert(content.contains("val y = x + 1"), "val y missing")
        assert(content.contains("val z = y + 1"), "val z missing")
        // The object name includes the sanitised env name.
        assert(content.contains("Env_tutorial_"), s"expected Env_tutorial_ object name in:\n$content")
        // All three blocks are referenced in the unit.
        assert(units(0).blocks.size == 3, s"expected 3 blocks in the env unit, got ${units(0).blocks.size}")
    }

    "Nested block body is in a local block; names do not leak forward to next Inherited block" in {
        val b1 = makeBlock("val shared = 1", Block.Visibility.Inherited, lineStart = 1)
        val b2 = makeBlock("val hidden = 99", Block.Visibility.Nested, lineStart = 10)
        val b3 = makeBlock("val visible = shared", Block.Visibility.Inherited, lineStart = 20)

        val units = CompileUnit.group(Chunk(b1, b2, b3))
        assert(units.size == 3, s"expected 3 compile units, got ${units.size}")

        // Unit 2 (Nested): must contain a local block { ... }.
        val unit2 = units(1).syntheticSource.content
        assert(unit2.contains("{\n") || unit2.contains("{ "), s"unit 2 should contain a block opener:\n$unit2")
        assert(unit2.contains("val hidden = 99"), s"unit 2 should contain hidden val:\n$unit2")

        // Unit 3 (Inherited after Nested): must see b1's body but NOT b2's body in the prelude.
        val unit3 = units(2).syntheticSource.content
        assert(unit3.contains("val shared = 1"), s"unit 3 should include val shared from b1:\n$unit3")
        assert(!unit3.contains("val hidden = 99"), s"unit 3 must NOT include val hidden from nested block:\n$unit3")
    }

    "setup block (Env(\"__doc__\")) injects prelude into Isolated blocks" in {
        val setup    = makeBlock("val setupVal = 42", Block.Visibility.Env("__doc__"), lineStart = 1)
        val isolated = makeBlock("val use = setupVal", Block.Visibility.Isolated, lineStart = 10)

        val units = CompileUnit.group(Chunk(setup, isolated))
        // Setup-only or isolated: the isolated block should receive the setup body as a prelude.
        // Since there is one non-setup block, we expect one compile unit for the isolated block.
        val isolatedUnits = units.filter(u => u.blocks.exists(wb => wb.block.visibility == Block.Visibility.Isolated))
        assert(isolatedUnits.nonEmpty, "expected at least one compile unit for the isolated block")
        val isolatedContent = isolatedUnits(0).syntheticSource.content
        assert(
            isolatedContent.contains("val setupVal = 42"),
            s"isolated unit should contain setup prelude:\n$isolatedContent"
        )
        assert(
            isolatedContent.contains("val use = setupVal"),
            s"isolated unit should contain block body:\n$isolatedContent"
        )
        ()
    }

    // Additional: setup-only file produces one compile unit.
    "setup-only file (only Env(\"__doc__\") blocks) produces one compile unit" in {
        val s1 = makeBlock("val a = 1", Block.Visibility.Env("__doc__"), lineStart = 1)
        val s2 = makeBlock("val b = 2", Block.Visibility.Env("__doc__"), lineStart = 10)

        val units = CompileUnit.group(Chunk(s1, s2))
        assert(units.size == 1, s"expected 1 compile unit for setup-only file, got ${units.size}")
        val content = units(0).syntheticSource.content
        assert(content.contains("val a = 1"))
        assert(content.contains("val b = 2"))
    }

    // Additional: empty block list produces empty Chunk.
    "empty block list produces empty Chunk of compile units" in {
        val units = CompileUnit.group(Chunk.empty)
        assert(units.isEmpty, s"expected empty Chunk but got ${units.size} units")
    }

    // Additional: Env("tutorial") blocks in a file with a setup block carry the setup block in WrappedBlock.setupBlocks.
    // groupEnvBlocks threads the file's setup-block list into every WrappedBlock
    // even though the env synthetic content does not inject the setup bodies.
    "Env(\"tutorial\") blocks in file with setup block carry setupBlocks metadata" in {
        val setup     = makeBlock("val setupVal = 42", Block.Visibility.Env("__doc__"), lineStart = 1)
        val envBlock  = makeBlock("val x = 1", Block.Visibility.Env("tutorial"), lineStart = 10)
        val envBlock2 = makeBlock("val y = x + 1", Block.Visibility.Env("tutorial"), lineStart = 20)

        val units = CompileUnit.group(Chunk(setup, envBlock, envBlock2))
        // Should produce 1 unit for Env("tutorial") (setup-only unit is suppressed since there are non-setup blocks)
        val tutorialUnits = units.filter(u => u.syntheticSource.content.contains("Env_tutorial_"))
        assert(tutorialUnits.nonEmpty, "expected a compile unit for Env(tutorial)")
        val tutorialUnit = tutorialUnits(0)
        assert(tutorialUnit.blocks.size == 2, s"expected 2 blocks in tutorial unit, got ${tutorialUnit.blocks.size}")
        // Every WrappedBlock in the env unit must carry the setup block in setupBlocks
        for wb <- tutorialUnit.blocks do
            assert(
                wb.setupBlocks.nonEmpty,
                s"expected setupBlocks to be populated for env WrappedBlock ${wb.block.lineStart}"
            )
            assert(
                wb.setupBlocks.exists(_.visibility == Block.Visibility.Env("__doc__")),
                s"expected setup block (Env(__doc__)) in setupBlocks, got ${wb.setupBlocks}"
            )
        end for
        ()
    }

    // Additional: two different env names produce two compile units.
    "two different Env names produce separate compile units" in {
        val b1 = makeBlock("val a = 1", Block.Visibility.Env("group1"), lineStart = 1)
        val b2 = makeBlock("val b = 2", Block.Visibility.Env("group2"), lineStart = 10)
        val b3 = makeBlock("val c = 3", Block.Visibility.Env("group1"), lineStart = 20)

        val units = CompileUnit.group(Chunk(b1, b2, b3))
        assert(units.size == 2, s"expected 2 compile units (one per env name), got ${units.size}")
        // group1 has 2 blocks, group2 has 1 block.
        val group1 = units.filter(_.syntheticSource.content.contains("Env_group1_"))
        val group2 = units.filter(_.syntheticSource.content.contains("Env_group2_"))
        assert(group1.size == 1, s"expected 1 unit for group1")
        assert(group2.size == 1, s"expected 1 unit for group2")
        assert(group1(0).blocks.size == 2, s"group1 unit should have 2 blocks")
        assert(group2(0).blocks.size == 1, s"group2 unit should have 1 block")
    }

end CompileUnitTest
