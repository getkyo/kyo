package kyo.internal

import kyo.*

class FlowLintTest extends Test:

    "duplicateNames" - {

        "detects duplicate node names" in run {
            val flow = Flow.input[Int]("x")
                .output("x")(ctx => ctx.x + 1)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.size == 1)
            assert(warnings(0).message.contains("Duplicate node name 'x'"))
            assert(warnings(0).message.contains("2 times"))
            succeed
        }

        "no warnings for unique names" in run {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.isEmpty)
            succeed
        }

        "detects duplicates across compositions" in run {
            val left     = Flow.input[Int]("x").output("y")(ctx => ctx.x)
            val right    = Flow.input[Int]("x").output("z")(ctx => ctx.x)
            val flow     = left.zip(right)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.exists(_.message.contains("'x'")))
            succeed
        }

        "detects triplicate names" in run {
            val a        = Flow.input[Int]("x")
            val b        = Flow.input[Int]("y").output("x")(ctx => ctx.y)
            val flow     = a.andThen(b).andThen(Flow.input[Int]("x"))
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.exists(_.message.contains("3 times")))
            succeed
        }

        "detects duplicate between input and forEach" in run {
            val flow     = Flow.input[Int]("items").foreach("items")(ctx => Seq(1, 2))(i => i)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.size == 1)
            assert(warnings(0).message.contains("'items'"))
            succeed
        }

        "detects duplicate between input and subflow" in run {
            val child    = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val flow     = Flow.input[Int]("result").subflow("result", child)(ctx => "a" ~ ctx.result)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.size == 1)
            assert(warnings(0).message.contains("'result'"))
            succeed
        }
    }

    "emptyBranches" - {

        "detects empty dispatch branches" in run {
            // Construct a dispatch with no conditional branches (only orElse)
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .otherwise(ctx => "default", name = "default")
            val warnings = FlowLint.emptyBranches(flow)
            assert(warnings.size == 1)
            assert(warnings(0).message.contains("has no conditional branches"))
            succeed
        }

        "no warning for dispatch with branches" in run {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "pos")(ctx => "pos")
                .otherwise(ctx => "neg", name = "default")
            val warnings = FlowLint.emptyBranches(flow)
            assert(warnings.isEmpty)
            succeed
        }

        "no warning for non-dispatch flows" in run {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
                .sleep("wait", 1.second)
            val warnings = FlowLint.emptyBranches(flow)
            assert(warnings.isEmpty)
            succeed
        }
    }

    "nodeNames" - {

        "extracts names from linear flow" in run {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "y", "log"))
            succeed
        }

        "extracts names from sleep" in run {
            val flow = Flow.input[Int]("x")
                .sleep("pause", 1.hour)
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "pause"))
            succeed
        }

        "extracts names from dispatch" in run {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "yes")(ctx => "yes")
                .otherwise(ctx => "no", name = "default")
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "d"))
            succeed
        }

        "extracts names from loop" in run {
            val flow = Flow.input[Int]("x")
                .loop("r") { ctx => Loop.done(ctx.x - 1) }
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "r"))
            succeed
        }

        "extracts names from zip" in run {
            val left  = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val right = Flow.input[Int]("c").output("d")(ctx => ctx.c)
            val flow  = left.zip(right)
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("a", "b", "c", "d"))
            succeed
        }

        "extracts names from nested andThen" in run {
            val first  = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val second = Flow.input[String]("c").output("d")(ctx => ctx.c)
            val flow   = first.andThen(second)
            val names  = FlowLint.nodeNames(flow)
            assert(names == Seq("a", "b", "c", "d"))
            succeed
        }

        "extracts name from forEach" in run {
            val flow  = Flow.input[Int]("count").foreach("items")(ctx => Seq(1, 2))(i => i * 2)
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("count", "items"))
            succeed
        }

        "extracts name from subflow (child nodes excluded)" in run {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val flow  = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "result"))
            succeed
        }
    }

    "inputNames" - {

        "extracts only input names" in run {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
                .sleep("wait", 1.second)
            val inputs = FlowLint.inputNames(flow)
            assert(inputs == Seq("x"))
            succeed
        }

        "extracts multiple input names" in run {
            val flow = Flow.input[Int]("a")
                .input[String]("b")
                .output("c")(ctx => ctx.a)
            val inputs = FlowLint.inputNames(flow)
            assert(inputs == Seq("a", "b"))
            succeed
        }

        "extracts inputs from zipped flows" in run {
            val left   = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val right  = Flow.input[String]("c").output("d")(ctx => ctx.c)
            val flow   = left.zip(right)
            val inputs = FlowLint.inputNames(flow)
            assert(inputs == Seq("a", "c"))
            succeed
        }
    }

    "outputNames" - {

        "extracts only output names" in run {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("y"))
            succeed
        }

        "includes dispatch as output" in run {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "yes")(ctx => "yes")
                .otherwise(ctx => "no", name = "default")
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("d"))
            succeed
        }

        "includes loop as output" in run {
            val flow = Flow.input[Int]("x")
                .loop("r") { ctx => Loop.done(ctx.x - 1) }
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("r"))
            succeed
        }

        "excludes step and sleep" in run {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x)
                .step("s")(ctx => ())
                .sleep("z", 1.second)
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("y"))
            succeed
        }

        "includes forEach as output" in run {
            val flow    = Flow.input[Int]("count").foreach("items")(ctx => Seq(1, 2))(i => i * 2)
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("items"))
            succeed
        }

        "includes subflow as output" in run {
            val child   = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val flow    = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("result"))
            succeed
        }
    }

    "check" - {

        "clean flow has no warnings" in run {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
                .sleep("wait", 1.second)
            val warnings = FlowLint.check(flow)
            assert(warnings.isEmpty)
            succeed
        }

        "combines duplicate and empty branch warnings" in run {
            val left = Flow.input[Int]("x")
                .dispatch[String]("d")
                .otherwise(ctx => "default", name = "default")
            val right    = Flow.input[Int]("x").output("y")(ctx => ctx.x)
            val flow     = left.zip(right)
            val warnings = FlowLint.check(flow)
            assert(warnings.exists(_.message.contains("Duplicate")))
            assert(warnings.exists(_.message.contains("no conditional branches")))
            succeed
        }
    }

end FlowLintTest
