package kyo.internal

/** How a node is named durably: by the path it occupies in a composition, not by its bare name.
  *
  * A node inside subflow instance `review` is durably `review~<name>`, in every place a node's name reaches the store: its field, its
  * history events, its completion mark, its schema entry, and its wait row. The bare name is what the record is keyed by, because the
  * record's type is the flow's own; the path is what the store is keyed by, because two instances of one subflow are two sets of nodes
  * and the store has to tell them apart.
  *
  * Global uniqueness would be the cheaper rule and it is the wrong one: it forbids embedding the same subflow twice in one parent, which
  * is what a subflow is for.
  *
  * Two characters are reserved in a user's node name and refused at registration. [[Separator]] joins a path, and `#` is what
  * [[IterationName]] uses to distinguish a loop's iterations and what a dispatch's choice field rides. The two compose, because a loop
  * can sit inside a subflow: iteration 0 of `sum` under `review` is `review~sum#0`.
  */
private[kyo] object NodePath:

    /** The character that joins a subflow instance's name to a node inside it. */
    val Separator: Char = '~'

    /** The characters the engine reserves in a node name, because it builds durable keys with them. */
    val Reserved: Set[Char] = Set(Separator, '#')

    /** The durable name of `name` inside `path`, where an empty path is the flow's own top level. */
    def qualify(path: String, name: String): String =
        if path.isEmpty then name else s"$path$Separator$name"

    /** Every entry of `names` re-keyed as if it sat inside subflow instance `path`. */
    def qualifyAll[V](path: String, names: Map[String, V]): Map[String, V] =
        names.map((name, value) => (qualify(path, name), value))

end NodePath
