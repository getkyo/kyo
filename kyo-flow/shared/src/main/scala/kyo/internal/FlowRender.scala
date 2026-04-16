package kyo.internal

import kyo.*

private[kyo] object FlowRender:

    def render(flow: Flow[?, ?, ?], format: Flow.DiagramFormat, progress: Maybe[FlowEngine.Progress] = Maybe.empty): String =
        (format, progress) match
            case (Flow.DiagramFormat.Mermaid, Present(p)) => renderMermaid(flow, p)
            case (Flow.DiagramFormat.Mermaid, _)          => renderMermaid(flow)
            case (Flow.DiagramFormat.Dot, Present(p))     => renderDot(flow, p)
            case (Flow.DiagramFormat.Dot, _)              => renderDot(flow)
            case (Flow.DiagramFormat.Bpmn, _)             => renderBpmn(flow)
            case (Flow.DiagramFormat.Elk, Present(p))     => renderElk(flow, p)
            case (Flow.DiagramFormat.Elk, _)              => renderElk(flow)
            case (Flow.DiagramFormat.Json, Present(p))    => renderJson(flow, p)
            case (Flow.DiagramFormat.Json, _)             => renderJson(flow)

    // --- Mermaid ---

    def renderMermaid(flow: Flow[?, ?, ?]): String =
        toMermaid(FlowGraph.build(flow))

    def renderMermaid(flow: Flow[?, ?, ?], progress: FlowEngine.Progress): String =
        val graph = FlowGraph.build(flow, progress)
        val base  = toMermaid(graph)
        val styles = graph.nodes.flatMap { node =>
            if node.status.nonEmpty && node.name.nonEmpty then
                val color = statusColorFromName(node.status)
                Some(s"    style ${node.id} fill:$color")
            else None
        }
        if styles.isEmpty then base
        else base + styles.mkString("\n") + "\n"
    end renderMermaid

    private def toMermaid(g: FlowGraph): String =
        val lines = Chunk.from(g.nodes.flatMap(mermaidNode)) ++
            Chunk.from(g.edges.map(mermaidEdge))
        "graph LR\n" + lines.mkString("\n") + "\n"
    end toMermaid

    private def mermaidNode(n: FlowGraph.Node): Option[String] =
        n.nodeType match
            case "input"         => Some(s"    ${n.id}[/${n.name}/]")
            case "output"        => Some(s"    ${n.id}[${n.name}]")
            case "step"          => Some(s"    ${n.id}[${n.name}]")
            case "sleep"         => Some(s"    ${n.id}[[${n.name}]]")
            case "dispatch"      => Some(s"    ${n.id}{${n.name}}")
            case "join-dispatch" => Some(s"    ${n.id}(( ))")
            case "loop"          => Some(s"    ${n.id}{{${n.name}}}")
            case "foreach" =>
                val label = n.concurrency match
                    case Present(c) if c < Int.MaxValue => s"${n.name} [x$c]"
                    case _                              => n.name
                Some(s"    ${n.id}[[$label]]")
            case "race-fork" => Some(s"    ${n.id}{${n.name}}")
            case "race-join" => Some(s"    ${n.id}(( ))")
            case "subflow"   => Some(s"    ${n.id}[[${n.name}]]")
            case "fork"      => Some(s"    ${n.id}{{${n.name}}}")
            case "join"      => Some(s"    ${n.id}{{${n.name}}}")
            case _           => None
    end mermaidNode

    private def mermaidEdge(e: FlowGraph.Edge): String =
        if e.style == "dashed" then
            if e.label.nonEmpty then s"    ${e.source} -.->|${e.label}| ${e.target}"
            else s"    ${e.source} -.-> ${e.target}"
        else if e.label.nonEmpty then s"    ${e.source} -->|${e.label}| ${e.target}"
        else s"    ${e.source} --> ${e.target}"

    // --- DOT ---

    def renderDot(flow: Flow[?, ?, ?]): String =
        toDot(FlowGraph.build(flow))

    def renderDot(flow: Flow[?, ?, ?], progress: FlowEngine.Progress): String =
        val graph = FlowGraph.build(flow, progress)
        val colorAttrs = graph.nodes.flatMap { node =>
            if node.status.nonEmpty && node.name.nonEmpty then
                val color = statusColorFromName(node.status)
                Some(s"""    ${node.id} [fillcolor="$color" style=filled]""")
            else None
        }
        val base = toDot(graph)
        base.stripSuffix("}\n") + colorAttrs.mkString("\n") + "\n}\n"
    end renderDot

    private def toDot(g: FlowGraph): String =
        val nodes = g.nodes.flatMap(dotNode)
        val edges = g.edges.map(dotEdge)
        "digraph flow {\n    rankdir=LR\n" + (nodes ++ edges).mkString("\n") + "\n}\n"
    end toDot

    private def dotNode(n: FlowGraph.Node): Option[String] =
        n.nodeType match
            case "input"         => Some(s"""    ${n.id} [label="${n.name}" shape=parallelogram]""")
            case "output"        => Some(s"""    ${n.id} [label="${n.name}" shape=box style=rounded]""")
            case "step"          => Some(s"""    ${n.id} [label="${n.name}" shape=box]""")
            case "sleep"         => Some(s"""    ${n.id} [label="${n.name}" shape=doubleoctagon]""")
            case "dispatch"      => Some(s"""    ${n.id} [label="${n.name}" shape=diamond]""")
            case "join-dispatch" => Some(s"""    ${n.id} [label="" shape=diamond width=0.3 height=0.3]""")
            case "loop"          => Some(s"""    ${n.id} [label="${n.name}" shape=hexagon]""")
            case "foreach" =>
                val label = n.concurrency match
                    case Present(c) if c < Int.MaxValue => s"${n.name} [x$c]"
                    case _                              => n.name
                Some(s"""    ${n.id} [label="$label" shape=component]""")
            case "race-fork" => Some(s"""    ${n.id} [label="${n.name}" shape=diamond]""")
            case "race-join" => Some(s"""    ${n.id} [label="" shape=diamond width=0.3 height=0.3]""")
            case "subflow"   => Some(s"""    ${n.id} [label="${n.name}" shape=component]""")
            case "fork"      => Some(s"""    ${n.id} [label="${n.name}" shape=diamond]""")
            case "join"      => Some(s"""    ${n.id} [label="${n.name}" shape=diamond]""")
            case _           => None
    end dotNode

    private def dotEdge(e: FlowGraph.Edge): String =
        if e.style == "dashed" then
            if e.label.nonEmpty then s"""    ${e.source} -> ${e.target} [style=dashed label="${e.label}"]"""
            else s"""    ${e.source} -> ${e.target} [style=dashed]"""
        else if e.label.nonEmpty then s"""    ${e.source} -> ${e.target} [label="${e.label}"]"""
        else s"    ${e.source} -> ${e.target}"

    // --- ELK JSON ---

    def renderElk(flow: Flow[?, ?, ?]): String =
        toElk(FlowGraph.build(flow), withStatus = false)

    def renderElk(flow: Flow[?, ?, ?], progress: FlowEngine.Progress): String =
        toElk(FlowGraph.build(flow, progress), withStatus = true)

    private def toElk(g: FlowGraph, withStatus: Boolean): String =
        // ELK uses only logical nodes (skip structural sub-nodes)
        val logicalNodes = g.nodes.filter(isLogicalNode)
        val logicalIds   = logicalNodes.map(_.id).toSet
        val logicalEdges = g.edges.filter(e => logicalIds.contains(e.source) && logicalIds.contains(e.target))

        val nodeJson = logicalNodes.map { n =>
            if withStatus && n.status.nonEmpty then elkNodeJsonWithStatus(n, n.status)
            else elkNodeJson(n)
        }.mkString(",\n    ")

        val edgeJson = logicalEdges.map(elkEdgeJson).mkString(",\n    ")

        s"""|{
            |  "id": "root",
            |  "layoutOptions": { "algorithm": "layered", "direction": "RIGHT" },
            |  "children": [$nodeJson],
            |  "edges": [$edgeJson]
            |}""".stripMargin
    end toElk

    private def elkNodeJson(n: FlowGraph.Node): String =
        s"""{"id":"${n.id}","labels":[{"text":"${escapeJson(
                n.name
            )}"}],"properties":{"nodeType":"${n.nodeType}"},"width":120,"height":40}"""

    private def elkNodeJsonWithStatus(n: FlowGraph.Node, status: String): String =
        s"""{"id":"${n.id}","labels":[{"text":"${escapeJson(
                n.name
            )}"}],"properties":{"nodeType":"${n.nodeType}","status":"$status"},"width":120,"height":40}"""

    private def elkEdgeJson(e: FlowGraph.Edge): String =
        val labelPart = if e.label.isEmpty then "" else s""","labels":[{"text":"${escapeJson(e.label)}"}]"""
        s"""{"id":"${e.id}","sources":["${e.source}"],"targets":["${e.target}"]$labelPart}"""

    // --- JSON ---

    def renderJson(flow: Flow[?, ?, ?]): String =
        toJson(FlowGraph.build(flow), withStatus = false)

    def renderJson(flow: Flow[?, ?, ?], progress: FlowEngine.Progress): String =
        toJson(FlowGraph.build(flow, progress), withStatus = true)

    private def toJson(g: FlowGraph, withStatus: Boolean): String =
        // JSON uses only logical nodes (skip structural sub-nodes like branches, race sub-nodes)
        // but keeps fork/join nodes for zip/gather
        val logicalNodes = g.nodes.filter(isLogicalNode)
        val logicalIds   = logicalNodes.map(_.id).toSet
        val logicalEdges = g.edges.filter(e => logicalIds.contains(e.source) && logicalIds.contains(e.target))

        val nodeStrs = logicalNodes.map { n =>
            jsonNodeStr(n, withStatus)
        }
        val edgeStrs = logicalEdges.map(e => s"""{"source":"${e.source}","target":"${e.target}"}""")

        val nodesStr = nodeStrs.mkString(",\n    ")
        val edgesStr = edgeStrs.mkString(",\n    ")
        s"""|{
            |  "nodes": [$nodesStr],
            |  "edges": [$edgesStr]
            |}""".stripMargin
    end toJson

    private def jsonNodeStr(n: FlowGraph.Node, withStatus: Boolean): String =
        val base = s""""id":"${n.id}","name":"${escapeJson(n.name)}","type":"${n.nodeType}""""
        val tagPart = n.tag match
            case Present(t) => s""","tag":"${escapeJson(t)}""""
            case _          => ""
        val durPart = n.duration match
            case Present(d) => s""","duration":"${d.show}""""
            case _          => ""
        val statusPart =
            if withStatus then
                val st =
                    if n.status.nonEmpty then s""","status":"${n.status}""""
                    // fork/join always get "completed" status in progress mode
                    else if n.nodeType == "fork" || n.nodeType == "join" then s""","status":"completed""""
                    else s""","status":"pending""""
                st
            else ""
        s"{$base$tagPart$durPart$statusPart}"
    end jsonNodeStr

    // --- BPMN XML ---

    def renderBpmn(flow: Flow[?, ?, ?]): String =
        toBpmn(FlowGraph.build(flow))

    private def toBpmn(g: FlowGraph): String =
        // BPMN needs a different node mapping than the graph structure.
        // We iterate FlowGraph nodes/edges and produce BPMN XML elements using pure functions.

        // Generate BPMN elements from nodes
        val elements = g.nodes.flatMap { n =>
            n.nodeType match
                case "input" =>
                    Seq(s"""    <bpmn:userTask id="${n.id}" name="${escapeXml(n.name)}" />""")
                case "output" =>
                    Seq(s"""    <bpmn:serviceTask id="${n.id}" name="${escapeXml(n.name)}" />""")
                case "step" =>
                    Seq(s"""    <bpmn:task id="${n.id}" name="${escapeXml(n.name)}" />""")
                case "sleep" =>
                    val durStr = n.duration match
                        case Present(d) => d.show
                        case _          => ""
                    Seq(s"""    <bpmn:intermediateCatchEvent id="${n.id}" name="${escapeXml(
                            n.name
                        )}"><bpmn:timerEventDefinition><bpmn:timeDuration>$durStr</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:intermediateCatchEvent>""")
                case "dispatch" =>
                    Seq(s"""    <bpmn:exclusiveGateway id="${n.id}" name="${escapeXml(n.name)}" />""")
                case "join-dispatch" =>
                    Seq(s"""    <bpmn:exclusiveGateway id="${n.id}" name="" />""")
                case "loop" =>
                    Seq(s"""    <bpmn:subProcess id="${n.id}" name="${escapeXml(
                            n.name
                        )}" triggeredByEvent="false"><bpmn:standardLoopCharacteristics /></bpmn:subProcess>""")
                case "foreach" =>
                    Seq(s"""    <bpmn:subProcess id="${n.id}" name="${escapeXml(
                            n.name
                        )}" triggeredByEvent="false"><bpmn:multiInstanceLoopCharacteristics /></bpmn:subProcess>""")
                case "race-fork" =>
                    Seq(s"""    <bpmn:eventBasedGateway id="${n.id}" name="${escapeXml(n.name)}" />""")
                case "race-join" =>
                    Seq(s"""    <bpmn:exclusiveGateway id="${n.id}" name="" />""")
                case "subflow" =>
                    Seq(s"""    <bpmn:callActivity id="${n.id}" name="${escapeXml(n.name)}" />""")
                case "fork" =>
                    Seq(s"""    <bpmn:parallelGateway id="${n.id}" name="${n.name}" />""")
                case "join" =>
                    Seq(s"""    <bpmn:parallelGateway id="${n.id}" name="${n.name}" />""")
                case _ => Seq.empty
        }

        // Add branch node tasks from dispatch nodes
        val branchElements = g.nodes.flatMap { n =>
            n.branches.map { bn =>
                s"""    <bpmn:task id="${bn.id}" name="${escapeXml(bn.name)}" />"""
            }
        }

        // Generate sequence flows for all non-dashed edges
        val flowElements = g.edges.collect {
            case e if e.style != "dashed" =>
                s"""    <bpmn:sequenceFlow id="${e.id}" sourceRef="${e.source}" targetRef="${e.target}" />"""
        }

        // Derive start/end event IDs deterministically from graph structure
        val startId   = "bpmn1"
        val endId     = "bpmn2"
        val startFlow = "bpmn3"
        val endFlow   = "bpmn4"

        val allElements = Seq(
            s"""    <bpmn:startEvent id="$startId" />""",
            s"""    <bpmn:sequenceFlow id="$startFlow" sourceRef="$startId" targetRef="${g.start}" />"""
        ) ++ elements ++ branchElements ++ flowElements ++ Seq(
            s"""    <bpmn:sequenceFlow id="$endFlow" sourceRef="${g.end}" targetRef="$endId" />""",
            s"""    <bpmn:endEvent id="$endId" />"""
        )

        s"""|<?xml version="1.0" encoding="UTF-8"?>
            |<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
            |  <bpmn:process id="flow" isExecutable="true">
            |${allElements.mkString("\n")}
            |  </bpmn:process>
            |</bpmn:definitions>""".stripMargin
    end toBpmn

    // --- Helpers ---

    /** Determines if a node appears in simplified formats (JSON, ELK).
      *
      * Structural sub-nodes are hidden:
      *   - "join-dispatch": merge point after dispatch branches (branches shown via edges)
      *   - "race-fork"/"race-join": race boundary nodes (sub-flows shown directly)
      *
      * Fork/join nodes from zip/gather ARE shown because they represent user-declared parallelism (vs dispatch which is conditional
      * routing, and race which is first-to-complete).
      */
    private def isLogicalNode(n: FlowGraph.Node): Boolean =
        n.nodeType match
            case "join-dispatch" | "race-fork" | "race-join" => false
            case _                                           => true

    private def statusColorFromName(status: String): String =
        status match
            case "completed" => "#90EE90"
            case "running"   => "#FFD700"
            case "pending"   => "#D3D3D3"
            case "waiting"   => "#87CEEB"
            case "sleeping"  => "#DDA0DD"
            case "failed"    => "#FF6B6B"
            case _           => "#D3D3D3"

    private def escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private def escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

end FlowRender
