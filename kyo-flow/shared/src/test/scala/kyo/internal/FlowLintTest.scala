package kyo.internal

import kyo.*

class FlowLintTest extends kyo.test.Test[Any]:

    "duplicateNames" - {

        "detects duplicate node names" in {
            val flow = Flow.input[Int]("x")
                .output("x")(ctx => ctx.x + 1)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.size == 1)
            assert(warnings(0).message.contains("Duplicate node name 'x'"))
            assert(warnings(0).message.contains("2 times"))
        }

        "no warnings for unique names" in {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.isEmpty)
        }

        /** Across a composition the warning follows what a name is FOR, and this leaf pins the boundary from both sides.
          *
          * Two zip branches READING one input must not warn: that is one wait and one field, so a warning there would teach users
          * that the shape the design blesses is a mistake. The adjacent case, two branches WRITING one name, is the real ambiguity,
          * since zip merges into one record with one slot and one of the two writers wins silently. Asserting only the silent half
          * would let a lint that warns about nothing pass.
          */
        "detects duplicates across compositions" in {
            val readLeft    = Flow.input[Int]("x").output("y")(ctx => ctx.x)
            val readRight   = Flow.input[Int]("x").output("z")(ctx => ctx.x)
            val readShared  = FlowLint.duplicateNames(readLeft.zip(readRight))
            val writeLeft   = Flow.input[Int]("x").output("y")(ctx => ctx.x)
            val writeRight  = Flow.input[Int]("a").output("y")(ctx => ctx.a)
            val writeShared = FlowLint.duplicateNames(writeLeft.zip(writeRight))
            assert(readShared.isEmpty, s"two branches waiting on one input is one wait, got $readShared")
            assert(
                writeShared.exists(_.message.contains("'y'")),
                s"two branches writing one name is ambiguous in the merged record, got $writeShared"
            )
        }

        "detects triplicate names" in {
            val a        = Flow.input[Int]("x")
            val b        = Flow.input[Int]("y").output("x")(ctx => ctx.y)
            val flow     = a.andThen(b).andThen(Flow.input[Int]("x"))
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.exists(_.message.contains("3 times")))
        }

        "detects duplicate between input and forEach" in {
            val flow     = Flow.input[Int]("items").foreach("items")(ctx => Seq(1, 2))(i => i)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.size == 1)
            assert(warnings(0).message.contains("'items'"))
        }

        "detects duplicate between input and subflow" in {
            val child    = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val flow     = Flow.input[Int]("result").subflow("result", child)(ctx => "a" ~ ctx.result)
            val warnings = FlowLint.duplicateNames(flow)
            assert(warnings.size == 1)
            assert(warnings(0).message.contains("'result'"))
        }
    }

    "emptyBranches" - {

        "detects empty dispatch branches" in {
            // A dispatch with no `when` branch at all, closed by `otherwise` alone
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .otherwise(ctx => "default", name = "default")
            val warnings = FlowLint.emptyBranches(flow)
            assert(warnings.size == 1)
            assert(warnings(0).message.contains("has no conditional branches"))
        }

        "no warning for dispatch with branches" in {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "pos")(ctx => "pos")
                .otherwise(ctx => "neg", name = "default")
            val warnings = FlowLint.emptyBranches(flow)
            assert(warnings.isEmpty)
        }

        "no warning for non-dispatch flows" in {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
                .sleep("wait", 1.second)
            val warnings = FlowLint.emptyBranches(flow)
            assert(warnings.isEmpty)
        }
    }

    "nodeNames" - {

        "extracts names from linear flow" in {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "y", "log"))
        }

        "extracts names from sleep" in {
            val flow = Flow.input[Int]("x")
                .sleep("pause", 1.hour)
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "pause"))
        }

        "extracts names from dispatch" in {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "yes")(ctx => "yes")
                .otherwise(ctx => "no", name = "default")
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "d"))
        }

        "extracts names from loop" in {
            val flow = Flow.input[Int]("x")
                .loop("r") { ctx => Loop.done(ctx.x - 1) }
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "r"))
        }

        "extracts names from zip" in {
            val left  = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val right = Flow.input[Int]("c").output("d")(ctx => ctx.c)
            val flow  = left.zip(right)
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("a", "b", "c", "d"))
        }

        "extracts names from nested andThen" in {
            val first  = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val second = Flow.input[String]("c").output("d")(ctx => ctx.c)
            val flow   = first.andThen(second)
            val names  = FlowLint.nodeNames(flow)
            assert(names == Seq("a", "b", "c", "d"))
        }

        "extracts name from forEach" in {
            val flow  = Flow.input[Int]("count").foreach("items")(ctx => Seq(1, 2))(i => i * 2)
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("count", "items"))
        }

        "extracts name from subflow (child nodes excluded)" in {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val flow  = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            val names = FlowLint.nodeNames(flow)
            assert(names == Seq("x", "result"))
        }
    }

    "inputNames" - {

        "extracts only input names" in {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
                .sleep("wait", 1.second)
            val inputs = FlowLint.inputNames(flow)
            assert(inputs == Seq("x"))
        }

        "extracts multiple input names" in {
            val flow = Flow.input[Int]("a")
                .input[String]("b")
                .output("c")(ctx => ctx.a)
            val inputs = FlowLint.inputNames(flow)
            assert(inputs == Seq("a", "b"))
        }

        "extracts inputs from zipped flows" in {
            val left   = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val right  = Flow.input[String]("c").output("d")(ctx => ctx.c)
            val flow   = left.zip(right)
            val inputs = FlowLint.inputNames(flow)
            assert(inputs == Seq("a", "c"))
        }
    }

    "outputNames" - {

        "extracts only output names" in {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("y"))
        }

        "includes dispatch as output" in {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "yes")(ctx => "yes")
                .otherwise(ctx => "no", name = "default")
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("d"))
        }

        "includes loop as output" in {
            val flow = Flow.input[Int]("x")
                .loop("r") { ctx => Loop.done(ctx.x - 1) }
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("r"))
        }

        "excludes step and sleep" in {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x)
                .step("s")(ctx => ())
                .sleep("z", 1.second)
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("y"))
        }

        "includes forEach as output" in {
            val flow    = Flow.input[Int]("count").foreach("items")(ctx => Seq(1, 2))(i => i * 2)
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("items"))
        }

        "includes subflow as output" in {
            val child   = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val flow    = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            val outputs = FlowLint.outputNames(flow)
            assert(outputs == Seq("result"))
        }
    }

    /** The enforced rule, as opposed to [[FlowLint.duplicateNames]]'s advisory warning above.
      *
      * Registration refuses what this reports, so its SCOPE is the whole content: it is narrower than the warning in two places, and
      * both are load-bearing. A name only READ is shared freely, because two branches waiting on one input is one wait and one field. A
      * name written by the branches of a race is the canonical race rather than a collision, because the result type is the union of the
      * two branches and is readable only through a field both carry. Everything else two nodes claim is refused.
      */
    "nameConflicts" - {

        "no conflicts for a clean flow" in {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
                .sleep("wait", 1.second)
            assert(FlowLint.nameConflicts(flow).isEmpty)
        }

        "an output named after an input is a conflict" in {
            val flow      = Flow.input[String]("x").output("x")(ctx => ctx.x)
            val conflicts = FlowLint.nameConflicts(flow)
            assert(conflicts.map(_.name) == Seq("x"), s"expected one conflict on 'x', got $conflicts")
            assert(conflicts.head.composition == "in sequence", s"got ${conflicts.head.composition}")
        }

        "two nodes sharing a name in sequence are a conflict" in {
            val flow      = Flow.init("dup").step("a")(_ => ()).step("a")(_ => ()).output("done")(_ => 1)
            val conflicts = FlowLint.nameConflicts(flow)
            assert(conflicts.map(_.name) == Seq("a"), s"expected one conflict on 'a', got $conflicts")
        }

        "two branches waiting on one input are not a conflict" in {
            val left  = Flow.init("read").input[Int]("x").output("a")(ctx => ctx.x)
            val right = Flow.init("read").input[Int]("x").output("b")(ctx => ctx.x)
            assert(FlowLint.nameConflicts(left.zip(right)).isEmpty)
        }

        "two zip branches sharing a step name are a conflict" in {
            val left      = Flow.init("z").step("validate")(_ => ()).output("a")(_ => 1)
            val right     = Flow.init("z").step("validate")(_ => ()).output("b")(_ => 2)
            val conflicts = FlowLint.nameConflicts(left.zip(right))
            assert(conflicts.map(_.name) == Seq("validate"), s"expected one conflict on 'validate', got $conflicts")
            assert(conflicts.head.composition == "by two zip branches", s"got ${conflicts.head.composition}")
        }

        "a race's shared result name is not a conflict" in {
            val waiting = Flow.init("r").input[String]("ack").output("answer")(ctx => ctx.ack)
            val timeout = Flow.init("r").sleep("deadline", 5.seconds).output("answer")(_ => "timed out")
            assert(FlowLint.nameConflicts(Flow.race(waiting, timeout)).isEmpty)
        }

        "a race's shared step name is a conflict, though its shared result name is not" in {
            val left      = Flow.init("r").step("notify")(_ => ()).output("answer")(_ => "a")
            val right     = Flow.init("r").step("notify")(_ => ()).output("answer")(_ => "b")
            val conflicts = FlowLint.nameConflicts(Flow.race(left, right))
            assert(
                conflicts.map(_.name) == Seq("notify"),
                s"only the step name is refused; the shared result name is what makes a race readable, got $conflicts"
            )
        }

        "a race's shared name written outside the race too is a conflict" in {
            val left      = Flow.init("o").output("answer")(_ => "left")
            val right     = Flow.init("o").output("answer")(_ => "right")
            val flow      = Flow.init("o").output("answer")(_ => "pre").andThen(Flow.race(left, right))
            val conflicts = FlowLint.nameConflicts(flow)
            assert(conflicts.map(_.name) == Seq("answer"), s"the share is not only among the race's branches, got $conflicts")
        }

        /** One entry per NAME, not per join, which a three-branch fan-out is the only way to see.
          *
          * `gather` folds its branches pairwise, so a name all three write is found at two separate joins. Reporting both would say
          * "2 durable names are claimed" about a single name, and would list the same call site twice, since the three branches are
          * one flow value embedded three times.
          */
        "a gather of one flow three times reports the name once" in {
            val review    = Flow.init("g").output("verdict")(_ => "ok")
            val conflicts = FlowLint.nameConflicts(Flow.gather(review, review, review))
            assert(conflicts.map(_.name) == Seq("verdict"), s"expected exactly one conflict entry, got $conflicts")
            assert(conflicts.head.locations.size == 1, s"one flow value is one call site, got ${conflicts.head.locations}")
            assert(conflicts.head.composition == "by two gather branches", s"got ${conflicts.head.composition}")
        }

        "the walk does not descend into a subflow" in {
            val child = Flow.init("c").output("shared")(_ => 1)
            val flow  = Flow.init("p").output("shared")(_ => 2).subflow("sub", child)(_ => Record.empty)
            val names = FlowLint.nameConflicts(flow).map(_.name)
            assert(
                !names.contains("shared"),
                s"a child's node keyed by its bare name is not the parent's collision to refuse, got $names"
            )
        }
    }

    "reservedNames" - {

        "a node name using '#' is reported" in {
            val flow = Flow.init("res").output("total#chosen")(_ => 1)
            assert(FlowLint.reservedNames(flow) == Seq("total#chosen"))
        }

        /** The other reserved character, refused by the same rule and named by the refusal it triggers.
          *
          * `~` joins a subflow instance to the nodes inside it, so a node named `a~b` at the top level is indistinguishable from node
          * `b` inside instance `a`, which is a collision with a key the engine generates rather than one a flow declared. The message
          * a caller reads must name `~` as well as `#`: naming only `#` tells a flow refused for this that it used a character it
          * did not.
          */
        "a node name using '~' is reported, and the refusal names the character it used" in {
            val flow = Flow.init("res").output("review~step")(_ => 1)
            assert(FlowLint.reservedNames(flow) == Seq("review~step"))
            val message = FlowReservedNameException("res", FlowLint.reservedNames(flow)).getMessage
            assert(
                message.contains("'~'") && message.contains("subflow"),
                s"the refusal must name the character the flow actually used, got: $message"
            )
        }

        "a clean flow reports nothing" in {
            val flow = Flow.init("res").input[Int]("x").output("total")(ctx => ctx.x)
            assert(FlowLint.reservedNames(flow).isEmpty)
        }

        "the flow's own name is not a node name" in {
            val flow = Flow.init("res#1").output("total")(_ => 1)
            assert(
                FlowLint.reservedNames(flow).isEmpty,
                "the reservation protects durable field keys, and a workflow id is not one"
            )
        }
    }

    "flowName" - {

        "answers the name every public constructor roots" in {
            assert(FlowLint.flowName(Flow.init("named").output("y")(_ => 1)) == Maybe("named"))
            assert(FlowLint.flowName(Flow.input[Int]("x")) == Maybe("x"))
        }

        "answers the empty name rather than hiding it" in {
            assert(
                FlowLint.flowName(Flow.init("").output("y")(_ => 1)) == Maybe(""),
                "registration is what refuses an empty name, so this must report it rather than treat it as absent"
            )
        }
    }

    "check" - {

        "clean flow has no warnings" in {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x + 1)
                .step("log")(ctx => ())
                .sleep("wait", 1.second)
            val warnings = FlowLint.check(flow)
            assert(warnings.isEmpty)
        }

        /** Both warning kinds reach the caller from one call.
          *
          * The duplicate half is carried by a shared STEP name rather than by a shared input: an input two branches read is one wait
          * and no duplicate at all, so a fixture built on it would assert a warning the lint is right not to raise. A step name is
          * written by exactly one node by definition, so two branches claiming it is a collision under any reading, which keeps this
          * leaf about `check` combining its two sources rather than about which shapes collide.
          */
        "combines duplicate and empty branch warnings" in {
            val left = Flow.input[Int]("x")
                .dispatch[String]("d")
                .otherwise(ctx => "default", name = "default")
                .step("shared")(ctx => ())
            val right    = Flow.input[Int]("x").step("shared")(ctx => ())
            val flow     = left.zip(right)
            val warnings = FlowLint.check(flow)
            assert(warnings.exists(_.message.contains("Duplicate")), s"expected a duplicate warning, got $warnings")
            assert(warnings.exists(_.message.contains("no conditional branches")), s"expected a branch warning, got $warnings")
        }
    }

end FlowLintTest
