package kyo.internal

import kyo.*

class FlowRenderTest extends kyo.test.Test[Any]:

    // --- JSON DTOs for structural assertions ---

    case class JsonGraph(nodes: Seq[JsonNode], edges: Seq[JsonEdge]) derives Schema
    case class JsonNode(
        id: String,
        name: String,
        `type`: String,
        tag: Option[String] = None,
        duration: Option[String] = None,
        status: Option[String] = None
    ) derives Schema
    case class JsonEdge(source: String, target: String) derives Schema

    given CanEqual[Any, Any] = CanEqual.derived

    def parseJson(s: String): JsonGraph =
        Json.decode[JsonGraph](s) match
            case Result.Success(g) => g
            case other             => throw new AssertionError(s"Failed to parse JSON graph: $other\n$s")

    // --- Sample flows ---

    def sampleFlow = Flow.input[Int]("x")
        .output("y")(ctx => ctx.x + 1)
        .step("log")(ctx => ())
        .sleep("wait", 1.hour)

    def sampleProgress(completed: Set[String], status: Flow.Status): FlowEngine.Progress =
        FlowEngine.Progress.build(sampleFlow, completed, status)

    // =============================================================
    // ELK JSON
    // =============================================================

    "elk" - {

        "linear flow has correct structure" in {
            val result = FlowRender.renderElk(sampleFlow)
            assert(result.contains("\"id\": \"root\""))
            assert(result.contains("\"algorithm\""))
            assert(result.contains("\"direction\": \"RIGHT\""))
            // 4 nodes: x, y, log, wait
            val nodeCount = "\"nodeType\"".r.findAllIn(result).length
            assert(nodeCount == 4, s"Expected 4 nodes, got $nodeCount")
            // 3 edges: x->y, y->log, log->wait
            val edgeCount = "\"sources\"".r.findAllIn(result).length
            assert(edgeCount == 3, s"Expected 3 edges, got $edgeCount")
        }

        "nodes have correct types" in {
            val result = FlowRender.renderElk(sampleFlow)
            assert(result.contains("\"nodeType\":\"input\""))
            assert(result.contains("\"nodeType\":\"output\""))
            assert(result.contains("\"nodeType\":\"step\""))
            assert(result.contains("\"nodeType\":\"sleep\""))
        }

        "zip renders fork/join with 4 edges" in {
            val flow   = Flow.input[Int]("a").output("b")(ctx => ctx.a).zip(Flow.input[Int]("c").output("d")(ctx => 0))
            val result = FlowRender.renderElk(flow)
            assert(result.contains("\"fork\""))
            assert(result.contains("\"join\""))
            // fork->a, fork->c, b->join, d->join + internal edges = at least 4 fork/join edges
            val edgeCount = "\"sources\"".r.findAllIn(result).length
            assert(edgeCount >= 4, s"Expected at least 4 edges for zip, got $edgeCount")
        }

        "dispatch and loop have correct nodeType" in {
            val dispatchFlow =
                Flow.input[Int]("x").dispatch[String]("d").when(
                    ctx => ctx.x > 0,
                    name = "yes"
                )(ctx => "y").otherwise(ctx => "n", name = "default")
            val loopFlow = Flow.input[Int]("x").loop("r") { ctx => Loop.done(ctx.x - 1) }
            assert(FlowRender.renderElk(dispatchFlow).contains("\"nodeType\":\"dispatch\""))
            assert(FlowRender.renderElk(loopFlow).contains("\"nodeType\":\"loop\""))
        }
    }

    // =============================================================
    // JSON -- deserialized structural assertions
    // =============================================================

    "json" - {

        "linear flow has 4 nodes and 3 edges" in {
            val graph = parseJson(FlowRender.renderJson(sampleFlow))
            assert(graph.nodes.length == 4)
            assert(graph.edges.length == 3)
        }

        "nodes have correct names and types" in {
            val graph  = parseJson(FlowRender.renderJson(sampleFlow))
            val byName = graph.nodes.map(n => n.name -> n.`type`).toMap
            assert(byName("x") == "input")
            assert(byName("y") == "output")
            assert(byName("log") == "step")
            assert(byName("wait") == "sleep")
        }

        "edges form a linear chain" in {
            val graph   = parseJson(FlowRender.renderJson(sampleFlow))
            val nodeIds = graph.nodes.map(_.id)
            // Edges should connect consecutive nodes: n1->n2, n2->n3, n3->n4
            assert(graph.edges(0).source == nodeIds(0))
            assert(graph.edges(0).target == nodeIds(1))
            assert(graph.edges(1).source == nodeIds(1))
            assert(graph.edges(1).target == nodeIds(2))
            assert(graph.edges(2).source == nodeIds(2))
            assert(graph.edges(2).target == nodeIds(3))
        }

        "input node has tag" in {
            val graph = parseJson(FlowRender.renderJson(sampleFlow))
            val input = graph.nodes.find(_.`type` == "input").get
            assert(input.tag.isDefined)
        }

        "sleep node has duration" in {
            val graph = parseJson(FlowRender.renderJson(sampleFlow))
            val sleep = graph.nodes.find(_.`type` == "sleep").get
            assert(sleep.duration.isDefined)
        }

        "zip adds fork and join nodes with correct edges" in {
            val flow  = Flow.input[Int]("a").output("b")(ctx => ctx.a).zip(Flow.input[Int]("c").output("d")(ctx => 0))
            val graph = parseJson(FlowRender.renderJson(flow))
            val fork  = graph.nodes.find(_.`type` == "fork").get
            val join  = graph.nodes.find(_.`type` == "join").get
            // Fork should have edges to both branches
            val forkEdges = graph.edges.filter(_.source == fork.id)
            assert(forkEdges.length == 2, s"Fork should have 2 outgoing edges, got ${forkEdges.length}")
            // Join should have edges from both branches
            val joinEdges = graph.edges.filter(_.target == join.id)
            assert(joinEdges.length == 2, s"Join should have 2 incoming edges, got ${joinEdges.length}")
        }

        "dispatch renders as dispatch type" in {
            val flow = Flow.input[Int]("x").dispatch[String]("d").when(
                ctx => ctx.x > 0,
                name = "yes"
            )(ctx => "y").otherwise(ctx => "n", name = "default")
            val graph = parseJson(FlowRender.renderJson(flow))
            assert(graph.nodes.exists(n => n.name == "d" && n.`type` == "dispatch"))
        }

        "loop has self-referencing edge" in {
            val flow  = Flow.input[Int]("x").loop("r") { ctx => Loop.done(ctx.x - 1) }
            val graph = parseJson(FlowRender.renderJson(flow))
            val rNode = graph.nodes.find(_.name == "r").get
            assert(graph.edges.exists(e => e.source == rNode.id && e.target == rNode.id))
        }
    }

    // =============================================================
    // BPMN XML
    // =============================================================

    "bpmn" - {

        "valid XML structure" in {
            val result = FlowRender.renderBpmn(sampleFlow)
            assert(result.startsWith("<?xml version=\"1.0\""))
            assert(result.contains("<bpmn:definitions"))
            assert(result.contains("</bpmn:definitions>"))
            assert(result.contains("<bpmn:process"))
            assert(result.contains("</bpmn:process>"))
            assert(result.contains("<bpmn:startEvent"))
            assert(result.contains("<bpmn:endEvent"))
        }

        "node types map to BPMN elements" in {
            val result = FlowRender.renderBpmn(sampleFlow)
            assert(result.contains("<bpmn:userTask") && result.contains("name=\"x\""))
            assert(result.contains("<bpmn:serviceTask") && result.contains("name=\"y\""))
            assert(result.contains("<bpmn:task") && result.contains("name=\"log\""))
            assert(result.contains("<bpmn:intermediateCatchEvent") && result.contains("<bpmn:timerEventDefinition>"))
        }

        "has sequence flows connecting elements" in {
            val result    = FlowRender.renderBpmn(sampleFlow)
            val flowCount = "<bpmn:sequenceFlow".r.findAllIn(result).length
            // start->x, x->y, y->log, log->wait, wait->end = 5 minimum
            assert(flowCount >= 5, s"Expected at least 5 sequence flows, got $flowCount")
        }

        "dispatch renders as exclusiveGateway" in {
            val flow =
                Flow.input[Int]("x").dispatch[String]("route").when(
                    ctx => ctx.x > 0,
                    name = "pos"
                )(ctx => "pos").otherwise(ctx => "neg", name = "default")
            val result = FlowRender.renderBpmn(flow)
            assert(result.contains("<bpmn:exclusiveGateway"))
            assert(result.contains("name=\"route\""))
        }

        "zip renders as parallelGateway" in {
            val flow   = Flow.input[Int]("a").output("b")(ctx => ctx.a).zip(Flow.input[Int]("c").output("d")(ctx => 0))
            val result = FlowRender.renderBpmn(flow)
            assert(result.contains("<bpmn:parallelGateway"))
        }

        "loop renders as subProcess with loop" in {
            val flow   = Flow.input[Int]("x").loop("r") { ctx => Loop.done(ctx.x - 1) }
            val result = FlowRender.renderBpmn(flow)
            assert(result.contains("<bpmn:subProcess"))
            assert(result.contains("standardLoopCharacteristics"))
        }
    }

    // =============================================================
    // Mermaid with progress
    // =============================================================

    "mermaid with progress" - {

        "completed nodes get green style" in {
            val progress = sampleProgress(Set("x", "y", "log", "wait"), Flow.Status.Completed)
            val result   = FlowRender.renderMermaid(sampleFlow, progress)
            assert(result.startsWith("graph LR"))
            val greenCount = "#90EE90".r.findAllIn(result).length
            assert(greenCount == 4, s"Expected 4 green (completed) styles, got $greenCount")
        }

        "waiting input gets blue, rest pending grey" in {
            val progress = sampleProgress(Set.empty, Flow.Status.WaitingForInput("x"))
            val result   = FlowRender.renderMermaid(sampleFlow, progress)
            assert(result.contains("#87CEEB")) // blue for waiting
            assert(result.contains("#D3D3D3")) // grey for pending
        }

        "sleeping node gets purple" in {
            val progress = sampleProgress(Set("x", "y", "log"), Flow.Status.Sleeping("wait", Instant.Epoch + 1.hour))
            val result   = FlowRender.renderMermaid(sampleFlow, progress)
            assert(result.contains("#DDA0DD")) // purple for sleeping
        }
    }

    // =============================================================
    // DOT with progress
    // =============================================================

    "dot with progress" - {

        "completed nodes get green fillcolor" in {
            val progress = sampleProgress(Set("x", "y", "log", "wait"), Flow.Status.Completed)
            val result   = FlowRender.renderDot(sampleFlow, progress)
            assert(result.startsWith("digraph flow"))
            assert(result.contains("fillcolor=\"#90EE90\""))
            assert(result.contains("style=filled"))
        }

        "running node gets yellow fillcolor" in {
            val progress = sampleProgress(Set.empty, Flow.Status.Running)
            val result   = FlowRender.renderDot(sampleFlow, progress)
            assert(result.contains("fillcolor=\"#FFD700\""))
        }
    }

    // =============================================================
    // ELK with progress
    // =============================================================

    "elk with progress" - {

        "all completed shows completed status" in {
            val progress       = sampleProgress(Set("x", "y", "log", "wait"), Flow.Status.Completed)
            val result         = FlowRender.renderElk(sampleFlow, progress)
            val completedCount = "\"status\":\"completed\"".r.findAllIn(result).length
            assert(completedCount == 4, s"Expected 4 completed statuses, got $completedCount")
        }

        "waiting node shows waiting status" in {
            val progress = sampleProgress(Set.empty, Flow.Status.WaitingForInput("x"))
            val result   = FlowRender.renderElk(sampleFlow, progress)
            assert(result.contains("\"status\":\"waiting\""))
            assert(result.contains("\"status\":\"pending\""))
        }

        "sleeping node shows sleeping status" in {
            val progress = sampleProgress(Set("x", "y", "log"), Flow.Status.Sleeping("wait", Instant.Epoch + 1.hour))
            val result   = FlowRender.renderElk(sampleFlow, progress)
            assert(result.contains("\"status\":\"sleeping\""))
        }

        "has layout options" in {
            val progress = sampleProgress(Set.empty, Flow.Status.Running)
            val result   = FlowRender.renderElk(sampleFlow, progress)
            assert(result.contains("\"layoutOptions\""))
        }
    }

    // =============================================================
    // JSON with progress -- deserialized structural assertions
    // =============================================================

    "json with progress" - {

        "all completed nodes have completed status" in {
            val progress = sampleProgress(Set("x", "y", "log", "wait"), Flow.Status.Completed)
            val graph    = parseJson(FlowRender.renderJson(sampleFlow, progress))
            assert(graph.nodes.length == 4)
            assert(graph.nodes.forall(_.status == Some("completed")))
        }

        "running status: first node Running, rest Pending" in {
            val progress = sampleProgress(Set.empty, Flow.Status.Running)
            val graph    = parseJson(FlowRender.renderJson(sampleFlow, progress))
            assert(graph.nodes(0).status == Some("running"))
            assert(graph.nodes(1).status == Some("pending"))
            assert(graph.nodes(2).status == Some("pending"))
            assert(graph.nodes(3).status == Some("pending"))
        }

        "waiting status on correct node" in {
            val progress = sampleProgress(Set.empty, Flow.Status.WaitingForInput("x"))
            val graph    = parseJson(FlowRender.renderJson(sampleFlow, progress))
            assert(graph.nodes.find(_.name == "x").get.status == Some("waiting"))
        }

        "mixed: completed + running + pending" in {
            val progress = sampleProgress(Set("x"), Flow.Status.Running)
            val graph    = parseJson(FlowRender.renderJson(sampleFlow, progress))
            assert(graph.nodes.find(_.name == "x").get.status == Some("completed"))
            assert(graph.nodes.find(_.name == "y").get.status == Some("running"))
            assert(graph.nodes.find(_.name == "log").get.status == Some("pending"))
            assert(graph.nodes.find(_.name == "wait").get.status == Some("pending"))
        }

        "preserves edge structure" in {
            val progress = sampleProgress(Set.empty, Flow.Status.Running)
            val graph    = parseJson(FlowRender.renderJson(sampleFlow, progress))
            assert(graph.edges.length == 3)
            // Edges chain consecutive nodes
            assert(graph.edges(0).source == graph.nodes(0).id)
            assert(graph.edges(0).target == graph.nodes(1).id)
        }

        "different-named nodes get independent status" in {
            val flow     = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val progress = FlowEngine.Progress.build(flow, Set("a"), Flow.Status.Running)
            val graph    = parseJson(FlowRender.renderJson(flow, progress))
            assert(graph.nodes.find(_.name == "a").get.status == Some("completed"))
            assert(graph.nodes.find(_.name == "b").get.status == Some("running"))
        }

        "duplicate names: both get status from their positional progress entry" in {
            // Both named "x" but at different positions in the flow
            val flow     = Flow.input[Int]("x").output("x")(ctx => ctx.x + 1)
            val progress = FlowEngine.Progress.build(flow, Set("x"), Flow.Status.Running)
            val graph    = parseJson(FlowRender.renderJson(flow, progress))
            // Both are "completed" because completedSteps.contains("x") is true for both
            // This is inherent to name-based tracking -- FlowLint warns about duplicate names
            assert(graph.nodes.length == 2)
            assert(graph.nodes.forall(_.status == Some("completed")))
        }
    }

    // =============================================================
    // New node types rendering
    // =============================================================

    "foreach rendering" - {

        "json renders foreach as foreach type" in {
            val flow  = Flow.input[Int]("count").foreach("items")(ctx => Seq(1, 2))(i => i * 2)
            val graph = parseJson(FlowRender.renderJson(flow))
            assert(graph.nodes.exists(n => n.name == "items" && n.`type` == "foreach"))
        }

        "mermaid renders foreach node" in {
            val flow   = Flow.input[Int]("count").foreach("items")(ctx => Seq(1, 2))(i => i * 2)
            val result = FlowRender.renderMermaid(flow)
            assert(result.contains("items"))
        }

        "dot renders foreach node" in {
            val flow   = Flow.input[Int]("count").foreach("items")(ctx => Seq(1, 2))(i => i * 2)
            val result = FlowRender.renderDot(flow)
            assert(result.contains("items"))
        }
    }

    "race rendering" - {

        "json renders race sub-flows" in {
            case class Approval(approved: Boolean) derives Schema
            val left  = Flow.input[Approval]("approval")
            val right = Flow.input[Int]("x").output("fallback")(ctx => Approval(false))
            val flow  = Flow.race(left, right)
            val graph = parseJson(FlowRender.renderJson(flow))
            assert(graph.nodes.exists(n => n.name == "approval"))
            assert(graph.nodes.exists(n => n.name == "fallback"))
        }

        "mermaid renders race fork-join" in {
            case class Approval(approved: Boolean) derives Schema
            val left   = Flow.input[Approval]("approval")
            val right  = Flow.input[Int]("x").output("fallback")(ctx => Approval(false))
            val flow   = Flow.race(left, right)
            val result = FlowRender.renderMermaid(flow)
            assert(result.contains("race"))
        }
    }

    "gather rendering" - {

        "json renders gather with fork/join" in {
            val f1    = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2    = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val flow  = Flow.gather(f1, f2)
            val graph = parseJson(FlowRender.renderJson(flow))
            val fork  = graph.nodes.find(_.`type` == "fork")
            val join  = graph.nodes.find(_.`type` == "join")
            assert(fork.isDefined, "Gather should produce a fork node")
            assert(join.isDefined, "Gather should produce a join node")
        }

        "mermaid renders gather with fork/join" in {
            val f1     = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2     = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val flow   = Flow.gather(f1, f2)
            val result = FlowRender.renderMermaid(flow)
            assert(result.contains("fork"))
            assert(result.contains("join"))
        }
    }

    "invoke rendering" - {

        "json renders invoke as invoke type" in {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow  = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            val graph = parseJson(FlowRender.renderJson(flow))
            assert(graph.nodes.exists(n => n.name == "result" && n.`type` == "subflow"))
        }

        "mermaid renders invoke node" in {
            val child  = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow   = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            val result = FlowRender.renderMermaid(flow)
            assert(result.contains("result"))
        }
    }

end FlowRenderTest
