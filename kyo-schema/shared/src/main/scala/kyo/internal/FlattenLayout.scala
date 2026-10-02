package kyo.internal

import kyo.*

/** Where each flattened field's keys sit in the parent record, computed once per schema from the parent's naming configuration and the
  * flattened fields' own schemas.
  *
  * A flat key is the child schema's own wire name for one of its fields (its renames, drops, computed fields and naming convention
  * applied), with the parent's naming convention applied on top, because `applyFieldConvention` runs over the spliced record. The read
  * side routes a wire key back to its parent through `readMap`, keyed by the converted key, the child's own key and each child alias,
  * so the record the child schema reads back carries keys that schema accepts. `flatKeys` is the way back for a failure: the child
  * names a key by its own wire name under the parent's field, and the input holds it at the parent level under the converted key.
  *
  * Construction rejects a layout that cannot round-trip: two fields writing one wire key (`FieldNameCollisionException`), a flattened
  * field that does not resolve to a stored field of the parent, and a parent builder naming a flattened field's own field, which no
  * write or read step applies (`TransformFailedException`).
  *
  * A flattened sum has no fixed key set: which keys it writes depends on the variant. It reads the whole parent record, so a
  * catch-all variant keeps every key the input had, and the variants ignore the parent's own keys. That is why a record holds at
  * most one flattened sum, and why a sum written as a bare name or an array, which has no keys to move, is refused here. Its tag and
  * content keys are known, so their collisions are rejected here; a variant field's collision is rejected when it is written.
  *
  * An optional record (`Maybe[R]`) is laid out as `R` is. Absent writes none of its keys and reads back from an input with none of
  * them; a flattened field is never written under its own name, so one of its keys may be that name (a caption beside its
  * entities), and `ownKeys` lets the reader route such a key to the record instead of the field.
  */
final private[kyo] class FlattenLayout private (
    val parentSources: Chunk[String],
    val parentByWire: Map[String, String],
    val childKeys: Map[String, Set[String]],
    val readMap: Map[String, (String, String)],
    val sumParent: Maybe[(String, Schema[?])],
    val flatKeys: Map[(String, String), String],
    val optionalParents: Set[String],
    val ownKeys: Set[String]
)

/** One field `flatten` moves to the parent level: its name, the schema of its record or sum, and whether it is held as `Maybe`. */
final private[kyo] case class FlattenedField(name: String, schema: Schema[?], optional: Boolean)

private[kyo] object FlattenLayout:

    def apply[A](schema: Schema[A]): FlattenLayout =
        given Frame = Frame.internal

        val sourceNames                        = Chunk.from(schema.sourceFields).map(_.name)
        val renameMap                          = Schema.resolvedRenames(sourceNames, schema.renamedFields).toMap
        val parentFn                           = convention(schema)
        def parentWire(source: String): String = renameMap.getOrElse(source, parentFn(source))

        val targetToSource = renameMap.map((source, wire) => wire -> source)

        // each flattened field as (parent source name, child schema, (child Scala name, child wire key) per written key)
        val resolved = schema.flattenedFields.foldLeft(Chunk.empty[(String, Schema[?], Chunk[(String, String)])]) {
            case (acc, FlattenedField(name, child, _)) =>
                val source =
                    if sourceNames.contains(name) && !renameMap.contains(name) then name
                    else
                        targetToSource.get(name) match
                            case Some(src) => src
                            case None      =>
                                acc.find((_, _, entries) => entries.exists((s, w) => s == name || w == name)) match
                                    case Some((parent, _, _)) => throw nestedFieldException(name, parent)
                                    case None                 => throw TransformFailedException(s"flatten: '$name' is not a stored field")
                acc :+ ((source, child, childEntries(child)))
        }

        val sums = resolved.filter((_, child, _) => isSum(child))
        if sums.size > 1 then
            throw TransformFailedException(
                s"flatten: '${sums(0)._1}' and '${sums(1)._1}' are both sums; a record holds at most one flattened sum, since each " +
                    "reads the whole record"
            )
        end if
        sums.foreach { (parent, child, _) =>
            (child.representation +: child.representationChain.getOrElse(Chunk.empty)).foreach {
                case Schema.UnionRepresentation.TagOnly =>
                    throw TransformFailedException(
                        s"flatten(_.$parent): the sum is written as a bare name, not a record, so it has no keys to move to the parent level"
                    )
                case Schema.UnionRepresentation.Tuple | Schema.UnionRepresentation.TupleFlat =>
                    throw TransformFailedException(
                        s"flatten(_.$parent): the sum is written as an array, not a record, so it has no keys to move to the parent level"
                    )
                case _ => ()
            }
        }

        // a flattened field is never written under its own name, so its name is not a label a key could collide with
        val flattenedSources = resolved.map(_._1).toSet
        val wireLabels       =
            sourceNames.filterNot(schema.droppedFields.contains).filterNot(flattenedSources.contains).map(src => parentWire(src) -> src) ++
                schema.computedFields.map((name, _) => parentFn(name) -> name) ++
                resolved.flatMap((parent, _, entries) => entries.map((scala, wire) => parentFn(wire) -> s"$parent.$scala"))
        val byWire = wireLabels.groupBy(_._1)
        wireLabels.map(_._1).distinct.foreach { wire =>
            val labels = Chunk.from(byWire(wire).map(_._2)).distinct
            if labels.size > 1 then throw FieldNameCollisionException(wire, labels)
        }

        val configured =
            schema.renamedFields.map(_._1).filterNot(n => schema.renamedFields.exists(_._2 == n)) ++
                Chunk.from(schema.droppedFields) ++
                schema.omitPolicies.map(_._1) ++
                schema.fieldDefaults.map(_._1) ++
                schema.fieldTransforms.map(_._1)
        configured.foreach { name =>
            if !sourceNames.contains(name) then
                resolved.find((_, _, entries) => entries.exists((s, w) => s == name || w == name)).foreach { (parent, _, _) =>
                    throw nestedFieldException(name, parent)
                }
        }

        val readMap = resolved.filterNot((_, child, _) => isSum(child)).flatMap { (parent, child, entries) =>
            val keys = entries.map(_._2) ++ child.variantNaming.fieldAliases.map(_._1)
            keys.flatMap(key => Chunk(key -> (parent, key), parentFn(key) -> (parent, key)))
        }.toMap

        new FlattenLayout(
            resolved.map(_._1),
            // the splice runs before the parent's naming convention, so a parent is found under its renamed name only
            resolved.map((parent, _, _) => renameMap.getOrElse(parent, parent) -> parent).toMap,
            resolved.map((parent, _, entries) => parent -> entries.map(_._2).toSet).toMap,
            readMap,
            Maybe.fromOption(sums.headOption.map((parent, child, _) => parent -> child)),
            resolved.filterNot((_, child, _) => isSum(child)).flatMap { (parent, _, entries) =>
                entries.map((_, key) => (parent, key) -> parentFn(key))
            }.toMap,
            // `resolved` holds one entry per flattened field, in the same order
            resolved.zip(schema.flattenedFields).collect { case ((parent, _, _), field) if field.optional => parent }.toSet,
            resolved.flatMap((parent, _, _) => Chunk(renameMap.getOrElse(parent, parent), parentWire(parent))).toSet
        )
    end apply

    private def isSum(schema: Schema[?]): Boolean =
        schema.structure match
            case _: Structure.Type.Sum => true
            case _                     => false

    /** Each key a child schema writes, as (the field's Scala name, its wire key), mirroring the write path's drop, rename, computed-field
      * and convention steps. A sum's only fixed keys are its tag and content keys.
      */
    private def childEntries(child: Schema[?]): Chunk[(String, String)] =
        if isSum(child) then
            child.representation match
                case Schema.UnionRepresentation.Internal(tagKey)             => Chunk(tagKey -> tagKey)
                case Schema.UnionRepresentation.Adjacent(tagKey, contentKey) => Chunk(tagKey -> tagKey, contentKey -> contentKey)
                case _                                                       => Chunk.empty
        else recordEntries(child)

    private def recordEntries(child: Schema[?]): Chunk[(String, String)] =
        val fn     = convention(child)
        val stored = Chunk.from(child.sourceFields).map(_.name).filterNot(child.droppedFields.contains).map { name =>
            name -> Schema.renamedWire(child.renamedFields, name).getOrElse(fn(name))
        }
        stored ++ child.computedFields.map((name, _) => name -> fn(name))
    end recordEntries

    private def convention(schema: Schema[?]): String => String =
        schema.variantNaming.fieldCase match
            case Maybe.Present(nc) => NameCaseConversion.convert(nc)
            case _                 => identity

    private def nestedFieldException(name: String, parent: String)(using Frame): TransformFailedException =
        TransformFailedException(
            s"'$name' is a field of the flattened field '$parent'; configure it on the schema of the flattened field's type"
        )

end FlattenLayout
