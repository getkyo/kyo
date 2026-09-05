package kyo.internal

import kyo.*

/** A typed entry linking a runtime type to its serialization codec and tag.
  *
  * TypeEntry[V] ensures schema and tag are both for the same V. The type parameter is erased when stored in WorkflowSchema's map, but
  * Tag-based lookup provides a safe downcast.
  */
private[kyo] case class TypeEntry[V](tag: Tag[V], schema: Schema[V]):
    def encode(value: V)(using Frame): FlowStore.FieldData = FlowStore.FieldData(schema.encodeString[Json](value), tag.erased)
    def decode(data: FlowStore.FieldData)(using Frame): Maybe[V] =
        if !(data.tag =:= tag.erased) then Maybe.empty
        else schema.decodeString[Json](data.value).toMaybe
end TypeEntry

/** The schema of a workflow: all type entries for its inputs and outputs.
  *
  * Immutable, derived from the flow AST. Serves as the bridge between serialized store state and live runtime types.
  */
private[kyo] case class WorkflowSchema(
    byName: Dict[String, TypeEntry[Any]],
    subflows: Chunk[WorkflowSchema.SubflowAssembly]
):

    /** Lookup by the name the store holds a field under, which is the node's composition path.
      *
      * The only index this carries, and the only one replay can use: a stored field is found by the path it was written under, and its
      * declared type is then compared against the reader's. A tag-keyed index answers a question nobody asks, because two nodes of one
      * flow routinely persist the same type and the tag alone cannot say which of them a field belongs to.
      */
    def fromStoreName(name: String): Maybe[TypeEntry[Any]] =
        byName.get(name)

end WorkflowSchema

private[kyo] object WorkflowSchema:

    import Flow.internal.*

    /** How a subflow's promised field is put back together from what the store holds.
      *
      * `.subflow("result", child)` types the parent's record with `"result" ~ Record[Out2]` and the node persists nothing under that
      * name by design: the child's nodes write under `result~<name>`, which is what makes two instances of one subflow two sets of
      * fields rather than one set written twice. A reader that hands back a typed record therefore owes the assembly, and this is the
      * entry it asks for.
      *
      * It nests, because a subflow inside a subflow is a path inside a path: the entry for `outer` carries the entry for `inner`, and
      * assembling the first assembles the second under it.
      *
      * What the assembled record carries is what the store holds under the path, which is everything the child ran on: its inputs,
      * written at the instance's entry before the child's first node, and everything the child computed after them. That is what
      * `Record[Out2]` promises, inputs included, so the assembled record answers every field the type says it has.
      *
      * @param name
      *   the subflow instance's own name, which is where the assembled record sits in its parent
      * @param path
      *   the durable path the child's fields sit under, `outer~inner` for an instance two levels down
      * @param children
      *   the subflow instances the child itself declares
      */
    case class SubflowAssembly(name: String, path: String, children: Chunk[SubflowAssembly]):

        /** The record this instance's field holds, built from the fields a reader has already decoded.
          *
          * The child's own fields are the ones directly under the path. A node's name cannot carry the separator, since registration
          * refuses one that does, so a name that still carries it once the path is stripped belongs to a subflow the child declares
          * and is assembled by that instance's own entry rather than landing here flattened.
          */
        def assemble(decoded: Dict[String, Any]): Record[Any] =
            val prefix = s"$path${NodePath.Separator}"
            val own = decoded.foldLeft(Dict.empty[String, Any]) { (acc, name, value) =>
                if !name.startsWith(prefix) then acc
                else
                    val bare = name.substring(prefix.length)
                    if bare.indexOf(NodePath.Separator) >= 0 then acc else acc.update(bare, value)
            }
            new Record[Any](children.foldLeft(own)((acc, child) => acc.update(child.name, child.assemble(decoded))))
        end assemble

    end SubflowAssembly

    /** XXH32 hash of the flow's version identity: the shape it composes its nodes in, and the type of every value it persists.
      *
      * Version identity is what tells an engine that the code changed under an execution it is about to resume, so it covers two
      * things. The shape is walked with a total [[FlowVisitor]], so every combinator contributes its own bracket and sequencing,
      * zipping and racing the same nodes are three different versions; a subflow contributes its body rather than its name, so a
      * child rewritten under a parent is a new version of the parent. The types are listed separately, in walk order, because
      * replay decodes every persisted field back through [[WorkflowSchema]]: a loop that stored an `Int` and one that stores a
      * `String` are different versions even where their shapes match, and resuming the second against the first's state fails the
      * decode, drops the field, and re-runs the node it belonged to with its side effects.
      */
    def structuralHash(flow: Flow[?, ?, ?]): String =
        val walked   = FlowFold(flow)(IdentityVisitor)
        val identity = walked.shape + "||" + walked.types.mkString("|")
        java.lang.Integer.toHexString(XXHash.hash32(identity))
    end structuralHash

    /** A flow's version identity as one walk produces it: the composition shape, and the types listed in walk order.
      *
      * @param shape
      *   each node's kind, name and shape-bearing arguments, with each combinator's own bracket
      * @param types
      *   the type of every value the flow persists, one entry per field replay decodes, depth first and left to right
      */
    private case class Identity(shape: String, types: Chunk[String])

    /** Both halves of the identity, from one total walk.
      *
      * Total rather than defaulted, so a node kind added to the AST states what it contributes to the version instead of folding away
      * silently. A flow's own name and metadata are labels rather than shape, so they are deliberately absent: renaming a flow or
      * editing its description must not hold every execution running under it.
      *
      * Type entries are positional and never keyed or sorted, so two nodes sharing a name stay two entries, and the result depends on
      * nothing but the AST's shape and its nodes' tags: no map or set iteration order, no frames, no closures, so the same flow yields
      * the same string in every process on every platform. See [[structuralHash]] for why the types are part of the identity at all.
      *
      * A subflow contributes its CHILD's shape under its own bracket and its child's type entries at the position it occupies, so a
      * change inside a child re-keys every parent that embeds it, in both halves.
      */
    private object IdentityVisitor extends FlowVisitor[Identity]:
        def onInit(name: String, frame: Frame, meta: Flow.Meta) = Identity("INIT", Chunk.empty)
        def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
            Identity(s"I:$name:${Tag[V].show}", Chunk(s"$name:${Tag[V].show}"))
        def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
            Identity(s"O:$name:${Tag[V].show}", Chunk(s"$name:${Tag[V].show}"))
        def onStep(name: String, frame: Frame, meta: Flow.Meta)                      = Identity(s"S:$name", Chunk.empty)
        def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta) = Identity(s"SL:$name:$duration", Chunk.empty)
        def onDispatch[V](name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
            Identity(s"D:$name:${branches.map(_.name).mkString(",")}", Chunk(s"$name:${Tag[V].show}"))
        def onLoop[V, State](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V], Tag[State], Schema[State]) =
            Identity(s"L:$name", Chunk(s"$name:${Tag[V].show}:${Tag[State].show}"))
        def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
            Identity(s"FE:$name:$concurrency", Chunk(s"$name:${Tag[V].show}"))
        def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: Identity, frame: Frame, meta: Flow.Meta) =
            Identity(s"INV:$name(${child.shape})", child.types)
        def onAndThen(first: Identity, second: Identity, frame: Frame) =
            Identity(s"SEQ(${first.shape},${second.shape})", first.types ++ second.types)
        def onZip(left: Identity, right: Identity, frame: Frame) =
            Identity(s"ZIP(${left.shape},${right.shape})", left.types ++ right.types)
        def onRace(left: Identity, right: Identity, frame: Frame) =
            Identity(s"RACE(${left.shape},${right.shape})", left.types ++ right.types)
        def onGather(flows: Seq[Identity], frame: Frame) =
            Identity(
                s"ALL(${flows.map(_.shape).mkString(",")})",
                flows.foldLeft(Chunk.empty[String])((acc, f) => acc ++ f.types)
            )
    end IdentityVisitor

    private type Entries = Dict[String, TypeEntry[Any]]
    private val empty: Entries                         = Dict.empty
    private def merge(a: Entries, b: Entries): Entries = a ++ b

    private def entry(name: String, tag: Tag[Any], schema: Schema[Any]): Entries =
        Dict(name -> TypeEntry(tag, schema))

    /** Every entry of `entries`, re-keyed under a subflow instance's path, which is what the store holds a child's fields under. */
    private def underPath(path: String, entries: Entries): Entries =
        entries.foldLeft(Dict.empty[String, TypeEntry[Any]]) { (acc, name, e) =>
            acc.update(NodePath.qualify(path, name), e)
        }

    /** Build a schema by walking all AST nodes and collecting type entries.
      *
      * A child's entries are keyed by their path, because that is what the store holds them under. Two instances of one subflow are two
      * sets of entries, which is exactly what makes both instances decodable from one execution's fields.
      */
    def of(flow: Flow[?, ?, ?]): WorkflowSchema =
        def loop(f: Flow[?, ?, ?]): Entries =
            f match
                case n: Output[?, ?, ?, ?, ?] @unchecked =>
                    entry(n.name, n.erased.tag, n.erased.schema)
                case n: Input[?, ?] @unchecked =>
                    entry(n.name, n.erased.tag, n.erased.schema)
                case n: Dispatch[?, ?, ?, ?, ?] @unchecked =>
                    entry(n.name, n.erased.tag, n.erased.schema)
                case n: LoopNode[?, ?, ?, ?, ?] @unchecked =>
                    entry(n.name, n.erased.tag, n.erased.schema)
                case n: ForEach[?, ?, ?, ?, ?] @unchecked =>
                    // The node carries the evidence for the whole `Chunk[V]` it persists, captured at the DSL site, so the entry
                    // replay decodes through is built the same way every other node's is. Building it here from a `Tag[Any]` and a
                    // `Seq` schema instead would produce an entry no typed read could ever match.
                    entry(n.name, n.erased.tag.erased, n.erased.schema.asInstanceOf[Schema[Any]])
                case n: Subflow[?, ?, ?, ?, ?, ?] @unchecked => underPath(n.name, loop(n.childFlow))
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
        WorkflowSchema(loop(flow), subflowsOf(flow, ""))
    end of

    /** Every subflow instance the flow declares, each under the durable path its child's fields sit under.
      *
      * Nested rather than flat, because assembling a parent's field means knowing which of the names under its path are the child's own
      * and which belong to an instance the child itself declares. A combinator is not a path component, so a subflow inside a `zip` or
      * a `race` branch sits at the same level as one written in sequence.
      */
    private def subflowsOf(flow: Flow[?, ?, ?], path: String): Chunk[SubflowAssembly] =
        flow match
            case n: Subflow[?, ?, ?, ?, ?, ?] @unchecked =>
                val instance  = n.name: String
                val childPath = NodePath.qualify(path, instance)
                Chunk(SubflowAssembly(instance, childPath, subflowsOf(n.childFlow, childPath)))
            case n: AndThen[?, ?, ?, ?, ?, ?] @unchecked => subflowsOf(n.first, path) ++ subflowsOf(n.second, path)
            case n: Zip[?, ?, ?, ?, ?, ?] @unchecked     => subflowsOf(n.left, path) ++ subflowsOf(n.right, path)
            case n: Race[?, ?, ?, ?, ?, ?] @unchecked    => subflowsOf(n.left, path) ++ subflowsOf(n.right, path)
            case n: Gather[?, ?, ?] @unchecked =>
                n.flows.foldLeft(Chunk.empty[SubflowAssembly])((acc, f) => acc ++ subflowsOf(f, path))
            case _ => Chunk.empty

end WorkflowSchema
