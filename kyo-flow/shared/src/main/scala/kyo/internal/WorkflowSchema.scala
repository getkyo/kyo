package kyo.internal

import kyo.*

/** A typed entry linking a runtime type to its serialization codec and tag.
  *
  * TypeEntry[V] ensures json, tag, and concreteTag are all for the same V. The type parameter is erased when stored in WorkflowSchema's
  * map, but Tag-based lookup provides a safe downcast.
  */
private[kyo] case class TypeEntry[V](tag: Tag[V], json: Json[V], postDecode: Any => Any = identity):
    def encode(value: V): FlowStore.FieldData = FlowStore.FieldData(json.encode(value), tag.erased)
    def decode(data: FlowStore.FieldData): Maybe[V] =
        if !(data.tag =:= tag.erased) then Maybe.empty
        else json.decode(data.value).toMaybe.map(v => postDecode(v).asInstanceOf[V])
end TypeEntry

/** The schema of a workflow — all type entries for its inputs and outputs.
  *
  * Immutable, derived from the flow AST. Serves as the bridge between serialized store state and live runtime types.
  */
private[kyo] case class WorkflowSchema(
    byTag: Dict[Tag[Any], TypeEntry[Any]],
    byName: Dict[String, TypeEntry[Any]]
):

    /** Runtime typed lookup — Tag is the proof for the safe cast. */
    def apply[V](using tag: Tag[V]): Maybe[TypeEntry[V]] =
        byTag.get(tag.erased).map(_.asInstanceOf[TypeEntry[V]])

    /** Store recovery — match a stored tag.show string against registered tags. */
    def fromStore(tagStr: String): Maybe[TypeEntry[Any]] =
        byTag.find((tag, _) => tag.show == tagStr).map((_, entry) => entry)

    /** Lookup by output/input name — for result reconstruction. */
    def fromStoreName(name: String): Maybe[TypeEntry[Any]] =
        byName.get(name)

end WorkflowSchema

private[kyo] object WorkflowSchema:

    /** SHA-256 hash of the flow's structure (names, types, node types). */
    def structuralHash(flow: Flow[?, ?, ?]): String =
        val structure = FlowFold(flow)(new FlowVisitorCollect[String]("", _ + "|" + _):
            override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) = s"O:$name:${Tag[V].show}"
            override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V])  = s"I:$name:${Tag[V].show}"
            override def onStep(name: String, frame: Frame, meta: Flow.Meta)                             = s"S:$name"
            override def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta)        = s"SL:$name:$duration"
            override def onDispatch(name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                s"D:$name:${branches.map(_.name).mkString(",")}"
            override def onLoop(name: String, frame: Frame, meta: Flow.Meta)                              = s"L:$name"
            override def onForEach(name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)         = s"FE:$name:$concurrency"
            override def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta) = s"INV:$name")
        scala.util.hashing.MurmurHash3.stringHash(structure).toHexString
    end structuralHash

    import Flow.internal.*

    private type Maps = (Dict[Tag[Any], TypeEntry[Any]], Dict[String, TypeEntry[Any]])
    private val empty: Maps                   = (Dict.empty, Dict.empty)
    private def merge(a: Maps, b: Maps): Maps = (a._1 ++ b._1, a._2 ++ b._2)

    private def entry(name: String, tag: Tag[Any], json: Json[Any]): Maps =
        val e = TypeEntry(tag, json)
        (Dict(tag -> e), Dict(name -> e))

    /** Build a schema by walking all AST nodes and collecting type entries. */
    def of(flow: Flow[?, ?, ?]): WorkflowSchema =
        def loop(f: Flow[?, ?, ?]): Maps =
            f match
                case n: Output[?, ?, ?, ?, ?] @unchecked =>
                    entry(n.name, n.erased.tag, n.erased.json)
                case n: Input[?, ?] @unchecked =>
                    entry(n.name, n.erased.tag, n.erased.json)
                case n: Dispatch[?, ?, ?, ?, ?] @unchecked =>
                    entry(n.name, n.erased.tag, n.erased.json)
                case n: LoopNode[?, ?, ?, ?, ?] @unchecked =>
                    entry(n.name, n.erased.tag, n.erased.json)
                case n: ForEach[?, ?, ?, ?, ?] @unchecked =>
                    given Json[Any] = n.erased.json
                    val seqJson     = summon[Json[Seq[Any]]].erased
                    val e           = TypeEntry[Any](Tag[Any], seqJson, v => Chunk.from(v.asInstanceOf[Seq[Any]]))
                    (Dict(Tag[Any] -> e), Dict(n.name -> e))
                case n: Subflow[?, ?, ?, ?, ?, ?] @unchecked => loop(n.childFlow)
                case _: Sleep                                => empty
                case _: Step[?, ?]                           => empty
                case n: AndThen[?, ?, ?, ?, ?, ?] @unchecked =>
                    merge(loop(n.first), loop(n.second))
                case n: Zip[?, ?, ?, ?, ?, ?] @unchecked =>
                    merge(loop(n.left), loop(n.right))
                case n: Race[?, ?, ?, ?, ?, ?] @unchecked =>
                    merge(loop(n.left), loop(n.right))
                case n: Gather[?, ?, ?] @unchecked =>
                    n.flows.foldLeft(empty)((acc, f) => merge(acc, loop(f)))
                case _: Init => empty
        val (tagMap, nameMap) = loop(flow)
        WorkflowSchema(tagMap, nameMap)
    end of

end WorkflowSchema
