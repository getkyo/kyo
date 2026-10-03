package kyo.internal

import kyo.*
import scala.util.boundary
import scala.util.boundary.break

/** The key each of a schema's fields is written and read under, decided once per schema, when the schema is built.
  *
  * Every slot's key follows one rule. A field renamed by `@rename` or `rename` keeps its renamed key, and the slot is settled. Otherwise
  * the schema's naming convention cases the field's name, and the slot is settled when a convention is set. Otherwise the key is the
  * field's name, not settled. A flattened field's keys are its record's keys, laid out by the record's own layout, at the parent level:
  * the parent's convention cases a child key only when the child's slot is not settled, so an explicit rename beats a convention at
  * every level.
  *
  * The layout reads only slots that never force a structure: the field lists, renames, drops, computed fields, the convention and
  * aliases, field-id pins, and the flattened fields' own layouts. A sum's sum-ness is its variant schemas. So a recursive schema,
  * whose structure reads a given still being built, is laid out safely.
  *
  * Construction finds a layout that cannot round-trip: two written keys or an alias naming one wire key
  * (`FieldNameCollisionException`), a flattened field that is not a stored field of the parent, a builder on the parent naming a
  * flattened field's own field, two flattened sums, and a flattened sum written as a bare name or an array (`TransformFailedException`).
  * It answers the failure, naming the field configuration, as the schema's configuration problem, which the first encode or decode
  * raises. A flattened sum's variant fields are the one set of keys it cannot see, since they live in variant schemas that may be
  * recursive givens; `flatSumProblem` checks them on first use, before anything is written or read.
  *
  * Read routes go the other way. A wire key reads as its field (a renamed, cased or aliased key, `fieldReverse`), or as a flattened
  * field and the key its record reads (`flatReverse`), so the record a child schema reads back carries keys that schema accepts.
  * `flatWire` is the way back for a failure: a child names a key by its own wire key under the parent's field, and the input holds it
  * at the parent level.
  * An optional record (`Maybe[R]`) is laid out as `R` is; absent, it writes none of its keys.
  */
final private[kyo] class WireLayout private (
    val slots: Chunk[WireLayout.Slot],
    private val fieldRoutes: Map[String, String],
    private val flatRoutes: Map[String, (String, String)],
    private val wires: Map[String, String],
    private val flatWires: Map[(String, String), String],
    private val childKeys: Map[String, Set[String]],
    private val convention: Maybe[String => String],
    val renamedAway: Set[String],
    val ownKeys: Set[String],
    val flatSum: Maybe[WireLayout.FlatSum],
    val fieldIds: Map[String, Int]
):
    import WireLayout.*

    /** Each source or computed field whose key is not its name, with that key: what a schema document names its properties by. */
    def renamedKeys: Map[String, String] = wires

    /** The key a source field or computed field is written under at this record's level. */
    def wireOf(source: String): String = wires.getOrElse(source, source)

    /** The renamed, cased and aliased keys and the source fields they read as, the flat keys excluded. */
    def fieldReverse: Map[String, String] = fieldRoutes

    /** The flattened record field and its record's key each flat key at this record's level reads as. */
    def flatReverse: Map[String, (String, String)] = flatRoutes

    /** The key a flattened record field's child key is written under at this record's level. */
    def flatWire(parent: String, childKey: String): Maybe[String] = Maybe.fromOption(flatWires.get((parent, childKey)))

    /** Each flattened record field and the parent-level key of each key its record writes, for a failure inside one. */
    def flatKeys: Map[(String, String), String] = flatWires

    /** The keys a flattened record field's record writes, as that record names them. */
    def expectedChildKeys(parent: String): Set[String] = childKeys.getOrElse(parent, Set.empty)

    private val flattenedByWire: Map[String, Slot] = slots.collect { case s if s.flattened => s.wire -> s }.toMap

    /** The flattened field written, nested, under `wire`. */
    def flattenedAt(wire: String): Maybe[Slot] = Maybe.fromOption(flattenedByWire.get(wire))

    /** The flattened fields, in declaration order. */
    def parentSources: Chunk[String] = slots.collect { case s if s.flattened => s.source }

    /** The flattened fields held as `Maybe`, which write none of their keys when absent. */
    def optionalParents: Set[String] = slots.collect { case s if s.flattened && s.optionalParent => s.source }.toSet

    def flattens: Boolean = slots.exists(_.flattened) || flatSum.nonEmpty

    /** The parent's convention applied to a key a flattened sum's variant writes: such a key is known only once written. */
    def casedFlatSumKey(key: String): String = convention.fold(key)(_(key))

    /** The keys a record writes at its own level: its own fields that are not flattened, its computed fields and its flattened
      * records' keys.
      */
    def writtenKeys: Chunk[String] = slots.collect { case s if !s.flattened => s.wire }

    /** A flattened sum whose variant writes, beside its tag, a key the parent's own fields write, as the problem the first write or read
      * raises with its own `Frame`, before anything is written: the variant schemas cannot be read while the parent is built. A
      * catch-all variant, and a variant that is itself a sum with one, holds the input it was read from, the parent's keys included,
      * and is exempt.
      */
    lazy val flatSumProblem: Maybe[BuilderProblem] =
        flatSum.flatMap { case FlatSum(parent, child) =>
            child.representation match
                case Schema.UnionRepresentation.Internal(_) =>
                    val parentKeys = writtenKeys.toSet
                    val names      = SchemaSerializer.variantNamesOf(child)
                    val clash      = child.variantSchemas.iterator.zipWithIndex.flatMap { (variantSchema, idx) =>
                        val name    = names.lift(idx).getOrElse(idx.toString)
                        val variant = variantSchema()
                        if !child.catchAll.exists(_.variant == name) && variant.catchAll.isEmpty && !isSum(variant) then
                            variant.wireLayout.writtenKeys.map(casedFlatSumKey).find(parentKeys.contains)
                        else None
                        end if
                    }.nextOption()
                    Maybe.fromOption(clash).map(key =>
                        BuilderProblem(
                            s"flatten(_.$parent)",
                            BuilderProblem.Failure.Transform(
                                s"flatten: the sum '$parent' wrote the key '$key', which the parent's field '$key' also writes"
                            )
                        )
                    )
                case _ => Absent
        }
end WireLayout

/** One field `flatten` moves to the parent level: its name, the schema of its record or sum, and whether it is held as `Maybe`. */
final private[kyo] case class FlattenedField(name: String, schema: Schema[?], optional: Boolean)

private[kyo] object WireLayout:

    enum Origin derives CanEqual:
        case Own
        case Computed
        case Flattened(parent: String, childKey: String)
    end Origin

    /** One key a record writes. `flattened` marks a field written as its record's keys rather than under `wire` on a self-describing
      * format; it keeps its slot because a format that cannot flatten writes it nested, under `wire`.
      */
    final case class Slot(
        source: String,
        wire: String,
        origin: Origin,
        settled: Boolean,
        flattened: Boolean,
        optionalParent: Boolean
    )

    final case class FlatSum(parent: String, child: Schema[?])

    val empty: WireLayout =
        new WireLayout(
            Chunk.empty,
            Map.empty,
            Map.empty,
            Map.empty,
            Map.empty,
            Map.empty,
            Maybe.empty,
            Set.empty,
            Set.empty,
            Maybe.empty,
            Map.empty
        )

    /** The layout of `schema`, or the problem that keeps it from round-tripping, which the first codec call through the schema raises. */
    def apply(schema: Schema[?]): Result[BuilderProblem, WireLayout] = boundary {
        def reject(failure: BuilderProblem.Failure): Nothing = break(Result.fail(BuilderProblem(configurationOf(schema), failure)))
        def transformFailure(detail: String): Nothing        = reject(BuilderProblem.Failure.Transform(detail))

        // A flattened child that cannot be laid out fails the parent with its own problem, not with what its empty layout implies.
        schema.flattenedFields.foreach(field => field.schema.configurationProblem.foreach(problem => break(Result.fail(problem))))

        val product     = !isSum(schema)
        val sourceNames = Chunk.from(schema.sourceFields).map(_.name)
        val renameMap   = Schema.resolvedRenames(sourceNames, schema.renamedFields).toMap
        val convention  = conventionOf(schema)
        val cased       = convention.getOrElse(identity[String])
        val pins        = schema.fieldIdOverrides.collect { case (Seq(name), id) => name -> id }

        def ownWire(source: String): String     = renameMap.getOrElse(source, cased(source))
        def ownSettled(source: String): Boolean = renameMap.contains(source) || convention.nonEmpty

        // each flattened field as (parent source name, child schema, optional)
        val flattened = schema.flattenedFields.foldLeft(Chunk.empty[(String, Schema[?], Boolean)]) {
            case (acc, FlattenedField(name, child, optional)) =>
                val source =
                    if sourceNames.contains(name) && !renameMap.contains(name) then name
                    else
                        renameMap.collectFirst { case (src, `name`) => src } match
                            case Some(src) => src
                            case None      =>
                                acc.find((_, c, _) => !isSum(c) && childEntries(c).exists((s, w, _) => s == name || w == name)) match
                                    case Some((parent, _, _)) => transformFailure(nestedFieldDetail(name, parent))
                                    case None                 => transformFailure(s"flatten: '$name' is not a stored field")
                acc :+ ((source, child, optional))
        }
        val flattenedSources = flattened.map(_._1).toSet

        val sums = flattened.filter((_, child, _) => isSum(child))
        if sums.size > 1 then
            transformFailure(
                s"flatten: '${sums(0)._1}' and '${sums(1)._1}' are both sums; a record holds at most one flattened sum, since each " +
                    "reads the whole record"
            )
        end if
        sums.foreach { (parent, child, _) =>
            (child.representation +: child.representationChain.getOrElse(Chunk.empty)).foreach {
                case Schema.UnionRepresentation.TagOnly =>
                    transformFailure(
                        s"flatten(_.$parent): the sum is written as a bare name, not a record, so it has no keys to move to the parent level"
                    )
                case Schema.UnionRepresentation.Tuple | Schema.UnionRepresentation.TupleFlat =>
                    transformFailure(
                        s"flatten(_.$parent): the sum is written as an array, not a record, so it has no keys to move to the parent level"
                    )
                case _ => ()
            }
        }

        val ownSlots = sourceNames.filterNot(schema.droppedFields.contains).map { source =>
            val wire = ownWire(source)
            Slot(
                source,
                wire,
                Origin.Own,
                ownSettled(source),
                flattenedSources.contains(source),
                flattened.exists((p, _, optional) => p == source && optional)
            )
        }
        val computedSlots = Chunk.from(schema.computedFields).map { (name, _) =>
            Slot(name, cased(name), Origin.Computed, convention.nonEmpty, false, false)
        }
        val flatSlots = flattened.filterNot((_, child, _) => isSum(child)).flatMap { (parent, child, _) =>
            childEntries(child).map { (childSource, childKey, childSettled) =>
                val wire = if childSettled then childKey else cased(childKey)
                Slot(s"$parent.$childSource", wire, Origin.Flattened(parent, childKey), childSettled || convention.nonEmpty, false, false)
            }
        }
        val slots = ownSlots ++ computedSlots ++ flatSlots

        // A sum's own fields are its variants', which its variant schemas lay out; only a record's keys are checked here.
        if product then
            // a flattened field is never written under its own name, so its name is not a label a key could collide with
            val sumKeys = sums.flatMap { (parent, child, _) =>
                sumFixedKeys(child).map(key => cased(key) -> s"$parent.$key")
            }
            val labels =
                slots.filterNot(_.flattened).map(s => s.wire -> s.source) ++ sumKeys
            val byWire = labels.groupBy(_._1)
            labels.map(_._1).distinct.foreach { wire =>
                val group = Chunk.from(byWire(wire).map(_._2)).distinct
                if group.size > 1 then reject(BuilderProblem.Failure.FieldCollision(wire, group))
            }
        end if
        if schema.variantNaming.fieldAliases.nonEmpty then
            Schema.fieldAliasClash(schema.variantNaming.fieldAliases, sourceNames.map(ownWire).toSet).foreach(reject)

        val configured =
            schema.renamedFields.map(_._1).filterNot(n => schema.renamedFields.exists(_._2 == n)) ++
                Chunk.from(schema.droppedFields) ++
                schema.omitPolicies.map(_._1) ++
                schema.fieldDefaults.map(_._1) ++
                schema.fieldTransforms.map(_._1)
        configured.foreach { name =>
            if !sourceNames.contains(name) then
                flattened.find((_, c, _) => !isSum(c) && childEntries(c).exists((s, w, _) => s == name || w == name)).foreach {
                    (parent, _, _) => transformFailure(nestedFieldDetail(name, parent))
                }
        }

        // Read routes, as wire key -> source field. A rename's key, then an alias, then a convention's key: a later kind wins.
        val renameRoutes     = renameMap.map((source, wire) => wire -> source)
        val conventionRoutes = convention match
            case Maybe.Present(fn) => sourceNames.iterator.filterNot(renameMap.contains).map(src => fn(src) -> src).toMap
            case _                 => Map.empty[String, String]
        val wireToSource = conventionRoutes ++ renameRoutes ++ sourceNames.map(n => n -> n)
        val aliasRoutes  = schema.variantNaming.fieldAliases.flatMap((alias, primary) => wireToSource.get(primary).map(alias -> _)).toMap
        val fieldRoutes  = renameRoutes ++ aliasRoutes ++ conventionRoutes

        val flatRoutes = flattened.filterNot((_, child, _) => isSum(child)).flatMap { (parent, child, _) =>
            val keys = childEntries(child).map(_._2) ++ child.variantNaming.fieldAliases.map(_._1)
            keys.flatMap(key => Chunk(key -> (parent, key), cased(key) -> (parent, key)))
        }.toMap

        val flatWires = flatSlots.collect { case Slot(_, wire, Origin.Flattened(parent, childKey), _, _, _) =>
            (parent, childKey) -> wire
        }.toMap

        val wires = (sourceNames.map(n => n -> ownWire(n)) ++ computedSlots.map(s => s.source -> s.wire)).filter((s, w) => s != w).toMap

        // The field numbers a field-id codec reads and writes: an explicit pin, and every field whose key is not its name under that
        // key's number, registered under both names because the writer presents the key and the reader matches the source name. A sum's
        // convention names its variants' fields, which their own schemas number.
        val renamedIds = sourceNames.iterator.filter(n => renameMap.contains(n) || (product && ownWire(n) != n)).flatMap { n =>
            val wire   = if product then ownWire(n) else renameMap(n)
            val number = pins.getOrElse(n, CodecMacro.fieldId(wire))
            Iterator(n -> number, wire -> number)
        }.toMap

        Result.succeed(new WireLayout(
            slots,
            fieldRoutes,
            flatRoutes,
            wires,
            flatWires,
            flattened.filterNot((_, child, _) => isSum(child)).map((parent, child, _) =>
                parent -> childEntries(child).map(_._2).toSet
            ).toMap,
            convention,
            renameMap.keySet,
            flattened.flatMap((parent, _, _) => Chunk(renameMap.getOrElse(parent, parent), ownWire(parent))).toSet,
            Maybe.fromOption(sums.headOption.map((parent, child, _) => FlatSum(parent, child))),
            pins ++ renamedIds
        ))
    }
    end apply

    /** A sum's sum-ness without its structure: only a sum or a union is built with variant schemas. */
    private[kyo] def isSum(schema: Schema[?]): Boolean = schema.variantSchemas.nonEmpty

    /** Each key a child record writes, as (its source name, its key, settled), from its own layout: its stored fields, flattened
      * ones under their own key, and its computed fields.
      */
    private def childEntries(child: Schema[?]): Chunk[(String, String, Boolean)] =
        child.wireLayout.slots.collect {
            case s if s.origin == Origin.Own || s.origin == Origin.Computed => (s.source, s.wire, s.settled)
        }

    /** A flattened sum's keys that do not depend on the variant: its tag and content keys. */
    private[kyo] def sumFixedKeys(child: Schema[?]): Chunk[String] =
        child.representation match
            case Schema.UnionRepresentation.Internal(tagKey)             => Chunk(tagKey)
            case Schema.UnionRepresentation.Adjacent(tagKey, contentKey) => Chunk(tagKey, contentKey)
            case _                                                       => Chunk.empty

    private def conventionOf(schema: Schema[?]): Maybe[String => String] =
        schema.variantNaming.fieldCase.map(NameCaseConversion.convert)

    private def nestedFieldDetail(name: String, parent: String): String =
        s"'$name' is a field of the flattened field '$parent'; configure it on the schema of the flattened field's type"

    /** The field configuration a layout failure comes from, as the builder calls that set it: the failure names the colliding keys,
      * and this names what produced them.
      */
    private def configurationOf(schema: Schema[?]): String =
        def call(name: String, args: Iterable[String]): Chunk[String] =
            if args.isEmpty then Chunk.empty else Chunk(s"$name(${args.mkString(", ")})")
        val calls =
            call("rename", schema.renamedFields.map((s, w) => s"$s -> $w")) ++
                call("renameAllFields", schema.variantNaming.fieldCase.toChunk.map(_.toString)) ++
                call("alias", schema.variantNaming.fieldAliases.map((a, p) => s"$a -> $p")) ++
                call("flatten", schema.flattenedFields.map(_.name)) ++
                call("drop", schema.droppedFields)
        if calls.isEmpty then "the derived field layout" else calls.mkString(", ")
    end configurationOf

end WireLayout
