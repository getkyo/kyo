package kyo.internal

import kyo.*

private[kyo] case class FlowGraph(nodes: Seq[FlowGraph.Node], edges: Seq[FlowGraph.Edge], start: String, end: String)

private[kyo] object FlowGraph:

    case class Node(
        id: String,
        name: String,
        nodeType: String,
        meta: Flow.Meta,
        // Extra fields for format-specific rendering
        tag: Maybe[String] = Maybe.empty,
        duration: Maybe[Duration] = Maybe.empty,
        concurrency: Maybe[Int] = Maybe.empty,
        branches: Seq[BranchNode] = Seq.empty,
        status: String = ""
    )

    case class BranchNode(id: String, name: String)

    case class Edge(id: String, source: String, target: String, label: String = "", style: String = "")

    // Internal result type for building the graph -- captures start/end for linking
    private case class SubGraph(
        nodes: Seq[Node],
        edges: Seq[Edge],
        start: String,
        end: String
    )

    // State-threading builder: takes a counter, returns (SubGraph, nextCounter).
    // This is the pure State monad pattern encoded as a function — no mutable state.
    private type Builder = Int => (SubGraph, Int)

    private def nextId(counter: Int): (String, Int) =
        val c = counter + 1
        (s"n$c", c)

    def build(flow: Flow[?, ?, ?]): FlowGraph =
        val builder  = buildSub(flow)
        val (sub, _) = builder(0)
        FlowGraph(sub.nodes, sub.edges, sub.start, sub.end)
    end build

    def build(flow: Flow[?, ?, ?], progress: FlowEngine.Progress): FlowGraph =
        val graph = build(flow)
        val annotated = graph.nodes.map { node =>
            progress.nodeByName(node.name) match
                case Present(np) => node.copy(status = statusName(np.status))
                case _           => node // structural nodes keep default empty status
        }
        FlowGraph(annotated, graph.edges, graph.start, graph.end)
    end build

    private def buildSub(flow: Flow[?, ?, ?]): Builder =
        FlowFold(flow)(new FlowVisitor[Builder]:
            def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
                (counter: Int) =>
                    val (id, c) = nextId(counter)
                    (
                        SubGraph(
                            Seq(Node(id, name, "input", meta, tag = Maybe(Tag[V].show))),
                            Seq.empty,
                            id,
                            id
                        ),
                        c
                    )
            end onInput

            def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
                (counter: Int) =>
                    val (id, c) = nextId(counter)
                    (
                        SubGraph(
                            Seq(Node(id, name, "output", meta, tag = Maybe(Tag[V].show))),
                            Seq.empty,
                            id,
                            id
                        ),
                        c
                    )
            end onOutput

            def onStep(name: String, frame: Frame, meta: Flow.Meta) =
                (counter: Int) =>
                    val (id, c) = nextId(counter)
                    (SubGraph(Seq(Node(id, name, "step", meta)), Seq.empty, id, id), c)

            def onSleep(name: String, dur: Duration, frame: Frame, meta: Flow.Meta) =
                (counter: Int) =>
                    val (id, c) = nextId(counter)
                    (
                        SubGraph(
                            Seq(Node(id, name, "sleep", meta, duration = Maybe(dur))),
                            Seq.empty,
                            id,
                            id
                        ),
                        c
                    )
            end onSleep

            def onDispatch(name: String, branchInfos: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                (counter: Int) =>
                    val (did, c1) = nextId(counter)
                    val (jid, c2) = nextId(c1)
                    val (branchNodes, c3) = branchInfos.foldLeft((Seq.empty[BranchNode], c2)) { case ((acc, c), bi) =>
                        val (bid, cn) = nextId(c)
                        (acc :+ BranchNode(bid, bi.name), cn)
                    }
                    val (branchEdges, c4) = branchNodes.foldLeft((Seq.empty[Edge], c3)) { case ((acc, c), bn) =>
                        val (eid1, cn1) = nextId(c)
                        val (eid2, cn2) = nextId(cn1)
                        (acc ++ Seq(Edge(eid1, did, bn.id, bn.name), Edge(eid2, bn.id, jid)), cn2)
                    }
                    (
                        SubGraph(
                            Seq(
                                Node(did, name, "dispatch", meta, branches = branchNodes),
                                Node(jid, "", "join-dispatch", Flow.Meta())
                            ),
                            branchEdges,
                            did,
                            jid
                        ),
                        c4
                    )
            end onDispatch

            def onLoop(name: String, frame: Frame, meta: Flow.Meta) =
                (counter: Int) =>
                    val (lid, c1) = nextId(counter)
                    val (eid, c2) = nextId(c1)
                    (
                        SubGraph(
                            Seq(Node(lid, name, "loop", meta)),
                            Seq(Edge(eid, lid, lid)),
                            lid,
                            lid
                        ),
                        c2
                    )
            end onLoop

            def onForEach(name: String, concurrency: Int, frame: Frame, meta: Flow.Meta) =
                (counter: Int) =>
                    val (id, c) = nextId(counter)
                    (
                        SubGraph(
                            Seq(Node(id, name, "foreach", meta, concurrency = Maybe(concurrency))),
                            Seq.empty,
                            id,
                            id
                        ),
                        c
                    )
            end onForEach

            def onRace(leftBuilder: Builder, rightBuilder: Builder, frame: Frame) =
                (counter: Int) =>
                    val (subL, c1) = leftBuilder(counter)
                    val (subR, c2) = rightBuilder(c1)
                    val (fid, c3)  = nextId(c2)
                    val (jid, c4)  = nextId(c3)
                    val (e1, c5)   = nextId(c4)
                    val (e2, c6)   = nextId(c5)
                    val (e3, c7)   = nextId(c6)
                    val (e4, c8)   = nextId(c7)
                    (
                        SubGraph(
                            Seq(
                                Node(fid, "race", "race-fork", Flow.Meta()),
                                Node(jid, "race-join", "race-join", Flow.Meta())
                            ) ++ subL.nodes ++ subR.nodes,
                            subL.edges ++ subR.edges ++ Seq(
                                Edge(e1, fid, subL.start),
                                Edge(e2, fid, subR.start),
                                Edge(e3, subL.end, jid),
                                Edge(e4, subR.end, jid)
                            ),
                            fid,
                            jid
                        ),
                        c8
                    )
            end onRace

            def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta) =
                (counter: Int) =>
                    val (id, c) = nextId(counter)
                    (SubGraph(Seq(Node(id, name, "subflow", meta)), Seq.empty, id, id), c)

            def onAndThen(first: Builder, second: Builder, frame: Frame) =
                (counter: Int) =>
                    val (sub1, c1) = first(counter)
                    val (sub2, c2) = second(c1)
                    if sub1.nodes.isEmpty then (sub2, c2)
                    else if sub2.nodes.isEmpty then (sub1, c2)
                    else
                        val (eid, c3) = nextId(c2)
                        (
                            SubGraph(
                                sub1.nodes ++ sub2.nodes,
                                sub1.edges ++ sub2.edges :+ Edge(eid, sub1.end, sub2.start),
                                sub1.start,
                                sub2.end
                            ),
                            c3
                        )
                    end if

            def onZip(left: Builder, right: Builder, frame: Frame) =
                (counter: Int) =>
                    val (subL, c1) = left(counter)
                    val (subR, c2) = right(c1)
                    val (fid, c3)  = nextId(c2)
                    val (jid, c4)  = nextId(c3)
                    val (e1, c5)   = nextId(c4)
                    val (e2, c6)   = nextId(c5)
                    val (e3, c7)   = nextId(c6)
                    val (e4, c8)   = nextId(c7)
                    (
                        SubGraph(
                            Seq(
                                Node(fid, "fork", "fork", Flow.Meta()),
                                Node(jid, "join", "join", Flow.Meta())
                            ) ++ subL.nodes ++ subR.nodes,
                            subL.edges ++ subR.edges ++ Seq(
                                Edge(e1, fid, subL.start),
                                Edge(e2, fid, subR.start),
                                Edge(e3, subL.end, jid),
                                Edge(e4, subR.end, jid)
                            ),
                            fid,
                            jid
                        ),
                        c8
                    )
            end onZip

            def onGather(results: Seq[Builder], frame: Frame) =
                (counter: Int) =>
                    // Thread counter through each builder in sequence
                    val (subs, c1) = results.foldLeft((Seq.empty[SubGraph], counter)) { case ((acc, c), builder) =>
                        val (sub, cn) = builder(c)
                        (acc :+ sub, cn)
                    }
                    val (fid, c2) = nextId(c1)
                    val (jid, c3) = nextId(c2)
                    val (forkEdges, c4) = subs.foldLeft((Seq.empty[Edge], c3)) { case ((acc, c), sub) =>
                        val (eid, cn) = nextId(c)
                        (acc :+ Edge(eid, fid, sub.start), cn)
                    }
                    val (joinEdges, c5) = subs.foldLeft((Seq.empty[Edge], c4)) { case ((acc, c), sub) =>
                        val (eid, cn) = nextId(c)
                        (acc :+ Edge(eid, sub.end, jid), cn)
                    }
                    (
                        SubGraph(
                            Seq(
                                Node(fid, "fork", "fork", Flow.Meta()),
                                Node(jid, "join", "join", Flow.Meta())
                            ) ++ subs.flatMap(_.nodes),
                            subs.flatMap(_.edges) ++ forkEdges ++ joinEdges,
                            fid,
                            jid
                        ),
                        c5
                    )
            end onGather

            def onInit(name: String, frame: Frame, meta: Flow.Meta) =
                (counter: Int) =>
                    (SubGraph(Seq.empty, Seq.empty, "", ""), counter))
    end buildSub

    private def statusName(status: FlowEngine.Progress.NodeStatus): String =
        status match
            case FlowEngine.Progress.NodeStatus.Completed       => "completed"
            case FlowEngine.Progress.NodeStatus.Running         => "running"
            case FlowEngine.Progress.NodeStatus.Pending         => "pending"
            case FlowEngine.Progress.NodeStatus.WaitingForInput => "waiting"
            case FlowEngine.Progress.NodeStatus.Sleeping(_)     => "sleeping"
            case FlowEngine.Progress.NodeStatus.Failed(_)       => "failed"

end FlowGraph
