package kyo.internal

import kyo.*

private[kyo] object FlowLint:

    case class Warning(message: String, location: String) derives CanEqual

    def check(flow: Flow[?, ?, ?]): Seq[Warning] =
        duplicateNames(flow) ++ emptyBranches(flow)
    end check

    // Type alias for the common chunk-collecting visitor pattern
    abstract private class CollectVisitor[A] extends FlowVisitorCollect[Chunk[A]](Chunk.empty, _ ++ _)

    /** The name collisions `Flow.lint` warns about, which are exactly the ones registration refuses.
      *
      * One classification with two consumers, because a warning broader than the refusal is worse than no warning at all. It
      * flags the module's own canonical race, whose branches share their result name because the union result type is readable only
      * through a field both carry, so every correct race would carry a warning teaching its author that right code is wrong; and it flags
      * two branches waiting on one input, which is one wait and one field and which the design blesses in as many words. A lint a user
      * learns to ignore protects nothing.
      *
      * The rendering keeps the count, because how many nodes claim the name is the first thing a reader wants and the locations carry it:
      * one flow value embedded three times is one call site, so the count is of distinct sites rather than of joins.
      */
    def duplicateNames(flow: Flow[?, ?, ?]): Seq[Warning] =
        nameConflicts(flow).map { conflict =>
            Warning(
                s"Duplicate node name '${conflict.name}' appears ${conflict.locations.size} times",
                conflict.locations.mkString(", ")
            )
        }
    end duplicateNames

    /** The characters the engine reserves in a node name, because it builds durable keys with them. See [[NodePath]]. */
    val Reserved: Set[Char] = NodePath.Reserved

    /** The flow's own name, taken from the `Init` node every public constructor roots.
      *
      * `Absent` for a flow with no `Init` at all, which no public constructor can build; the engine treats that and an empty name as the
      * same refusal, so the one code path that answers this question is the one that decides it.
      */
    def flowName(flow: Flow[?, ?, ?]): Maybe[String] =
        FlowFold(flow)(new FlowVisitorCollect[Maybe[String]](Maybe.empty, (a, b) => a.orElse(b)):
            override def onInit(name: String, frame: Frame, meta: Flow.Meta) = Maybe(name))

    /** Node names using a character the engine reserves for the keys it generates itself.
      *
      * Descends into subflows, because a child's names become durable keys under the parent's path, so a child node whose name carries
      * the separator would forge a path the parent never declared. The names are checked as their author wrote them, unqualified: the
      * question is about the characters a user chose, and checking the qualified form would refuse every node inside a subflow, since
      * the separator is exactly what qualifying adds.
      */
    def reservedNames(flow: Flow[?, ?, ?]): Seq[String] =
        val visitor = new CollectVisitor[String]:
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V])  = Chunk(name)
            override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) = Chunk(name)
            override def onStep(name: String, frame: Frame, meta: Flow.Meta)                               = Chunk(name)
            override def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta)          = Chunk(name)
            override def onDispatch[V](name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta)(using
                Tag[V],
                Schema[V]
            ) =
                Chunk(name)
            override def onLoop[V, State](name: String, frame: Frame, meta: Flow.Meta)(using
                Tag[V],
                Schema[V],
                Tag[State],
                Schema[State]
            ) = Chunk(name)
            override def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                Chunk(name)
            override def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: Chunk[String], frame: Frame, meta: Flow.Meta) =
                Chunk(name) ++ child
        FlowFold(flow)(visitor).toSeq.filter(_.exists(Reserved.contains)).distinct
    end reservedNames

    /** What a flow's nodes claim of one name, folded per composition so a join can decide whether the claim is shared.
      *
      * Three categories rather than one, because the answer differs by what a node's name is FOR. `result` names land in the composed
      * record, which is what makes them shareable by a race's branches and by nothing else. `internal` names are written but never appear
      * in the result, so nothing forces them to be shared and a shared one corrupts. `read` names are waited on rather than written, and
      * two waits on one name are one wait.
      */
    private case class Names(
        result: Map[String, Chunk[String]],
        internal: Map[String, Chunk[String]],
        read: Map[String, Chunk[String]],
        conflicts: Chunk[FlowNameConflict]
    ):
        def locationsOf(name: String): Chunk[String] =
            result.getOrElse(name, Chunk.empty[String]) ++ internal.getOrElse(name, Chunk.empty[String]) ++
                read.getOrElse(name, Chunk.empty[String])
        def written: Set[String] = result.keySet ++ internal.keySet
    end Names

    private object Names:
        val empty: Names                            = Names(Map.empty, Map.empty, Map.empty, Chunk.empty[FlowNameConflict])
        def result(name: String, at: String): Names = empty.copy(result = Map(name -> Chunk(at)))
        def internal(name: String, at: String)      = empty.copy(internal = Map(name -> Chunk(at)))
        def read(name: String, at: String): Names   = empty.copy(read = Map(name -> Chunk(at)))
    end Names

    private def mergeLocations(a: Map[String, Chunk[String]], b: Map[String, Chunk[String]]): Map[String, Chunk[String]] =
        b.foldLeft(a) { case (acc, (name, locations)) => acc.updated(name, acc.getOrElse(name, Chunk.empty[String]) ++ locations) }

    /** What a subflow's child claims, seen from the parent: every name re-keyed under the instance's path.
      *
      * A conflict the child has with itself is still a conflict and travels up under the same path, which is how a child embedded but
      * never registered on its own gets checked at all. A name the child shares with the PARENT stops being a conflict here, and that is
      * the point of qualifying: the two nodes write different durable keys.
      */
    private def underPath(path: String, names: Names): Names =
        Names(
            NodePath.qualifyAll(path, names.result),
            NodePath.qualifyAll(path, names.internal),
            NodePath.qualifyAll(path, names.read),
            names.conflicts.map(c => FlowNameConflict(NodePath.qualify(path, c.name), c.composition, c.locations))
        )

    /** Joins what two sides of a composition claim, and records every name they claim against each other.
      *
      * A name one side WRITES conflicts with the other side writing it, and equally with the other side READING it: the write lands on the
      * field the read already occupies, so the reader forever sees a value the writer did not put there and the writer's node skips as
      * already complete. Two READS of one name are not a conflict at all.
      *
      * `exemptShared` is the race, and only the race. Its branches return the winner's record rather than a merged one, and its result type
      * is the union of the two, readable downstream only through a field both branches carry, so branches sharing a RESULT name is the
      * shape the type forces rather than a collision. Everything else the two claim still conflicts, `internal` names included, because
      * nothing forces those to be shared: one sleep row would hand the second branch the first's deadline, and one step's completion would
      * mark the other branch's work done.
      */
    private def join(left: Names, right: Names, composition: String, exemptShared: Boolean): Names =
        val shared = (left.written & (right.written ++ right.read.keySet)) ++ (right.written & left.read.keySet)
        val exempt = if exemptShared then left.result.keySet & right.result.keySet else Set.empty[String]
        val found = (shared -- exempt).toSeq.sorted.map { name =>
            FlowNameConflict(name, composition, (left.locationsOf(name) ++ right.locationsOf(name)).toSeq)
        }
        Names(
            mergeLocations(left.result, right.result),
            mergeLocations(left.internal, right.internal),
            mergeLocations(left.read, right.read),
            left.conflicts ++ right.conflicts ++ Chunk.from(found)
        )
    end join

    /** Every durable-name collision that makes a flow unregisterable, and how each pair of nodes reaches the other.
      *
      * Narrower than [[duplicateNames]], which is the advisory warning `Flow.lint` reports and which counts every repeated name in the AST.
      * This is the enforced rule, so it refuses only what has no defensible reading: a name two nodes WRITE, or one node writes and another
      * reads. A race's branches sharing a result name and two branches waiting on one input are both left alone, for the reasons [[join]]
      * gives.
      *
      * The walk descends into a subflow and re-keys what it finds under the instance's path, so it answers about every durable key the
      * definition will write. A collision between a parent and a child is not one, because the child's keys carry the path; a collision
      * a child has with ITSELF is one, and descending is the only way to see it in a child that is embedded but never registered alone.
      */
    def nameConflicts(flow: Flow[?, ?, ?]): Seq[FlowNameConflict] =
        val visitor = new FlowVisitor[Names]:
            def onInit(name: String, frame: Frame, meta: Flow.Meta)                               = Names.empty
            def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V])  = Names.read(name, frame.snippetShort)
            def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) = Names.result(name, frame.snippetShort)
            def onStep(name: String, frame: Frame, meta: Flow.Meta)                               = Names.internal(name, frame.snippetShort)
            def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta)          = Names.internal(name, frame.snippetShort)
            def onDispatch[V](name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                Names.result(name, frame.snippetShort)
            def onLoop[V, State](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V], Tag[State], Schema[State]) =
                Names.result(name, frame.snippetShort)
            def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                Names.result(name, frame.snippetShort)
            def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: Names, frame: Frame, meta: Flow.Meta) =
                val own    = Names.result(name, frame.snippetShort)
                val inside = underPath(name, child)
                Names(
                    mergeLocations(own.result, inside.result),
                    inside.internal,
                    inside.read,
                    inside.conflicts
                )
            end onSubflow
            def onRace(left: Names, right: Names, frame: Frame) =
                join(left, right, "by two race branches", exemptShared = true)
            def onAndThen(first: Names, second: Names, frame: Frame) =
                join(first, second, "in sequence", exemptShared = false)
            def onZip(left: Names, right: Names, frame: Frame) =
                join(left, right, "by two zip branches", exemptShared = false)
            def onGather(flows: Seq[Names], frame: Frame) =
                flows.foldLeft(Names.empty)((acc, branch) => join(acc, branch, "by two gather branches", exemptShared = false))
        // One entry per name rather than one per JOIN. A fold over three branches joins twice, so keying by join would report a
        // name all three write twice and count two collisions where a reader sees one. Locations are deduplicated for the same
        // reason a name is: a flow VALUE embedded three times is one call site, and repeating it says nothing.
        FlowFold(flow)(visitor).conflicts.toSeq
            .groupBy(conflict => (conflict.name, conflict.composition))
            .toSeq
            .sortBy(_._1)
            .map { case ((name, composition), found) =>
                FlowNameConflict(name, composition, found.flatMap(_.locations).distinct)
            }
    end nameConflicts

    def emptyBranches(flow: Flow[?, ?, ?]): Seq[Warning] =
        val visitor = new CollectVisitor[Warning]:
            override def onDispatch[V](name: String, branchInfos: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta)(using
                Tag[V],
                Schema[V]
            ) =
                if branchInfos.size <= 1 then
                    Chunk(Warning(s"Dispatch '$name' has no conditional branches", frame.snippetShort))
                else Chunk.empty[Warning]
        FlowFold(flow)(visitor).toSeq
    end emptyBranches

    /** The names of one definition's OWN nodes, which is what a question about a single flow's shape wants. A subflow contributes its
      * own name and not its child's; see [[reservedNames]] for the walk that descends.
      */
    def nodeNames(flow: Flow[?, ?, ?]): Seq[String] =
        val visitor = new CollectVisitor[String]:
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V])  = Chunk(name)
            override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) = Chunk(name)
            override def onStep(name: String, frame: Frame, meta: Flow.Meta)                               = Chunk(name)
            override def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta)          = Chunk(name)
            override def onDispatch[V](name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta)(using
                Tag[V],
                Schema[V]
            ) =
                Chunk(name)
            override def onLoop[V, State](name: String, frame: Frame, meta: Flow.Meta)(using
                Tag[V],
                Schema[V],
                Tag[State],
                Schema[State]
            ) = Chunk(name)
            override def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                Chunk(name)
            override def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: Chunk[String], frame: Frame, meta: Flow.Meta) =
                Chunk(name)
        FlowFold(flow)(visitor).toSeq
    end nodeNames

    def inputNames(flow: Flow[?, ?, ?]): Seq[String] =
        val visitor = new CollectVisitor[String]:
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) = Chunk(name)
        FlowFold(flow)(visitor).toSeq
    end inputNames

    def inputMetas(flow: Flow[?, ?, ?]): Seq[InputMeta] =
        val visitor = new CollectVisitor[InputMeta]:
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                Chunk(InputMeta(name, Tag[V].erased, summon[Schema[V]].asInstanceOf[Schema[Any]], frame))
        FlowFold(flow)(visitor).toSeq
    end inputMetas

    def outputNames(flow: Flow[?, ?, ?]): Seq[String] =
        val visitor = new CollectVisitor[String]:
            override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) = Chunk(name)
            override def onDispatch[V](name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta)(using
                Tag[V],
                Schema[V]
            ) =
                Chunk(name)
            override def onLoop[V, State](name: String, frame: Frame, meta: Flow.Meta)(using
                Tag[V],
                Schema[V],
                Tag[State],
                Schema[State]
            ) = Chunk(name)
            override def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                Chunk(name)
            // The subflow's own name and not its child's: these are the names the PARENT's record carries, and a child's
            // outputs reach the parent through the subflow's field rather than beside it.
            override def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: Chunk[String], frame: Frame, meta: Flow.Meta) =
                Chunk(name)
        FlowFold(flow)(visitor).toSeq
    end outputNames

end FlowLint
