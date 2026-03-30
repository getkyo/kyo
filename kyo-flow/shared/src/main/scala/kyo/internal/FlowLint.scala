package kyo.internal

import kyo.*

// TODO do we have comprehensive tests for this?
private[kyo] object FlowLint:

    case class Warning(message: String, location: String) derives CanEqual

    def check(flow: Flow[?, ?, ?]): Seq[Warning] =
        duplicateNames(flow) ++ emptyBranches(flow)
    end check

    // Type alias for the common chunk-collecting visitor pattern
    abstract private class CollectVisitor[A] extends FlowVisitorCollect[Chunk[A]](Chunk.empty, _ ++ _)

    def duplicateNames(flow: Flow[?, ?, ?]): Seq[Warning] =
        val visitor = new CollectVisitor[(String, String)]:
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
                Chunk((name, frame.snippetShort))
            override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
                Chunk((name, frame.snippetShort))
            override def onStep(name: String, frame: Frame, meta: Flow.Meta) =
                Chunk((name, frame.snippetShort))
            override def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta) =
                Chunk((name, frame.snippetShort))
            override def onDispatch(name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                Chunk((name, frame.snippetShort))
            override def onLoop(name: String, frame: Frame, meta: Flow.Meta) =
                Chunk((name, frame.snippetShort))
            override def onForEach(name: String, concurrency: Int, frame: Frame, meta: Flow.Meta) =
                Chunk((name, frame.snippetShort))
            override def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta) =
                Chunk((name, frame.snippetShort))
        val names   = FlowFold(flow)(visitor)
        val grouped = names.toSeq.groupBy(_._1)
        grouped.collect {
            case (name, locations) if locations.size > 1 =>
                Warning(
                    s"Duplicate node name '$name' appears ${locations.size} times",
                    locations.map(_._2).mkString(", ")
                )
        }.toSeq
    end duplicateNames

    def emptyBranches(flow: Flow[?, ?, ?]): Seq[Warning] =
        val visitor = new CollectVisitor[Warning]:
            override def onDispatch(name: String, branchInfos: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                if branchInfos.size <= 1 then
                    Chunk(Warning(s"Dispatch '$name' has no conditional branches", frame.snippetShort))
                else Chunk.empty[Warning]
        FlowFold(flow)(visitor).toSeq
    end emptyBranches

    def nodeNames(flow: Flow[?, ?, ?]): Seq[String] =
        val visitor = new CollectVisitor[String]:
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V])  = Chunk(name)
            override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) = Chunk(name)
            override def onStep(name: String, frame: Frame, meta: Flow.Meta)                             = Chunk(name)
            override def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta)        = Chunk(name)
            override def onDispatch(name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                Chunk(name)
            override def onLoop(name: String, frame: Frame, meta: Flow.Meta)                              = Chunk(name)
            override def onForEach(name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)         = Chunk(name)
            override def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta) = Chunk(name)
        FlowFold(flow)(visitor).toSeq
    end nodeNames

    def inputNames(flow: Flow[?, ?, ?]): Seq[String] =
        val visitor = new CollectVisitor[String]:
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) = Chunk(name)
        FlowFold(flow)(visitor).toSeq
    end inputNames

    def inputMetas(flow: Flow[?, ?, ?]): Seq[InputMeta] =
        val visitor = new CollectVisitor[InputMeta]:
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
                Chunk(InputMeta(name, Tag[V].erased, summon[Json[V]].erased, frame))
        FlowFold(flow)(visitor).toSeq
    end inputMetas

    def outputNames(flow: Flow[?, ?, ?]): Seq[String] =
        val visitor = new CollectVisitor[String]:
            override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) = Chunk(name)
            override def onDispatch(name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                Chunk(name)
            override def onLoop(name: String, frame: Frame, meta: Flow.Meta)                              = Chunk(name)
            override def onForEach(name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)         = Chunk(name)
            override def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta) = Chunk(name)
        FlowFold(flow)(visitor).toSeq
    end outputNames

end FlowLint
