package kyo.internal

import kyo.*
import scala.annotation.publicInBinary

/** JSON Schema enrichment helpers: applies runtime Schema metadata (docs, constraints, examples, transforms) to a JsonSchema.Obj.
  *
  * These are pure functions on JsonSchema values, separated so that JSON Schema enrichment is not mixed with serialization or factory
  * concerns.
  */
@publicInBinary private[kyo] object JsonSchemaEnricher:

    /** Enriches a JsonSchema.Obj with runtime metadata: doc, field docs, field deprecations and examples.
      *
      * The properties are the keys the schema writes, while docs and deprecations name a field by its source name or by the name a
      * rename gave it; `renamedFields` maps each source field whose key is not its name to that key.
      */
    def enrichObj(
        obj: Json.JsonSchema.Obj,
        doc: Maybe[String],
        fieldDocs: Map[Seq[String], String],
        fieldDeprecated: Map[Seq[String], String],
        examples: Chunk[Structure.Value],
        renamedFields: Map[String, String] = Map.empty
    ): Json.JsonSchema.Obj =
        def key(source: String): String  = renamedFields.getOrElse(source, source)
        val docs                         = fieldDocs.collect { case (Seq(source), d) => key(source) -> d }
        val deprecated                   = fieldDeprecated.keySet.collect { case Seq(source) => key(source) }
        val withDoc: Json.JsonSchema.Obj =
            if doc.isEmpty then obj else obj.copy(description = doc)
        val withExamples: Json.JsonSchema.Obj =
            if examples.isEmpty then withDoc else withDoc.copy(examples = examples)
        if docs.isEmpty && deprecated.isEmpty then withExamples
        else
            withExamples.copy(
                properties = withExamples.properties.map { (name, schema) =>
                    val docced =
                        docs.get(name) match
                            case Some(d) => addDescription(schema, d)
                            case None    => schema
                    val depped =
                        if deprecated.contains(name) then addDeprecated(docced)
                        else docced
                    (name, depped)
                }
            )
        end if
    end enrichObj

    /** Each constraint a schema's JSON schema states, with the keys from the root to the node it applies to.
      *
      * A derived product's fields come first, each field's own schema's constraints under the field's key, then the schema's own
      * constraints, so a bound declared on the holder is applied after the one its field's type declares. A field schema already
      * entered on the way down is not entered again, which ends a recursive type.
      */
    def keyedConstraints(schema: Schema[?]): Chunk[(List[String], Schema.Constraint)] =
        def loop(schema: Schema[?], entered: List[Schema[?]]): Chunk[(List[String], Schema.Constraint)] =
            val fields = schema.fieldSchemas().flatMap { (source, field) =>
                if entered.exists(_ eq field) then Chunk.empty
                else
                    ownSlot(schema, source) match
                        case Present(slot) =>
                            loop(field, field :: entered).map((keys, c) => (underSlot(schema, slot, keys), c))
                        case Absent => Chunk.empty
            }
            val own = Chunk.from(schema.constraints).flatMap(c => Chunk.from(keysOf(schema, c.segments.toList).map((_, c))))
            fields ++ own
        end loop
        loop(schema, List(schema))
    end keyedConstraints

    /** Applies a constraint to the node `keys` leads to from `schema`, through the properties of each object on the way. */
    def placeConstraint(schema: Json.JsonSchema, keys: List[String], constraint: Schema.Constraint): Json.JsonSchema =
        keys match
            case Nil         => applyConstraint(schema, constraint)
            case key :: rest =>
                schema match
                    case obj: Json.JsonSchema.Obj =>
                        obj.copy(properties = obj.properties.map { (name, property) =>
                            if name == key then (name, placeConstraint(property, rest, constraint)) else (name, property)
                        })
                    case other => other
    end placeConstraint

    // The keys a path of source names is written under. A laid-out product names each field by its slot and continues in the field's
    // own schema; a field it has no slot for was dropped, so the path leads nowhere. Past a schema that lays out no fields, the source
    // names are the keys.
    private def keysOf(schema: Schema[?], path: List[String]): Maybe[List[String]] =
        path match
            case Nil            => Present(Nil)
            case source :: rest =>
                ownSlot(schema, source) match
                    case Present(slot) =>
                        val inner = schema.fieldSchemas().find(_._1 == source) match
                            case Some((_, field)) => keysOf(field, rest)
                            case None             => Present(rest)
                        inner.map(keys => underSlot(schema, slot, keys))
                    case Absent =>
                        if schema.wireLayout.slots.isEmpty then Present(path) else Absent
    end keysOf

    private def ownSlot(schema: Schema[?], source: String): Maybe[WireLayout.Slot] =
        Maybe.fromOption(schema.wireLayout.slots.find(s => s.origin == WireLayout.Origin.Own && s.source == source))

    // Flattened records contribute their keys at the parent; other fields keep their own key before their nested path.
    private def underSlot(schema: Schema[?], slot: WireLayout.Slot, keys: List[String]): List[String] =
        keys match
            case key :: rest if slot.flattened => schema.wireLayout.flatWire(slot.source, key).getOrElse(key) :: rest
            case _ if slot.flattened           => keys
            case _                             => slot.wire :: keys

    /** Applies a single Constraint to a JsonSchema property, returning the enriched schema.
      *
      * Only applies constraints that match the schema type (e.g., Min on Num/Integer, MinLength on Str). Non-matching combinations are
      * returned unchanged.
      */
    def applyConstraint(
        schema: Json.JsonSchema,
        constraint: Schema.Constraint
    ): Json.JsonSchema =
        import Json.JsonSchema
        (schema, constraint) match
            case (s: JsonSchema.Num, Schema.Constraint.Min(_, v, false))     => s.copy(minimum = Maybe(v))
            case (s: JsonSchema.Num, Schema.Constraint.Min(_, v, true))      => s.copy(exclusiveMinimum = Maybe(v))
            case (s: JsonSchema.Num, Schema.Constraint.Max(_, v, false))     => s.copy(maximum = Maybe(v))
            case (s: JsonSchema.Num, Schema.Constraint.Max(_, v, true))      => s.copy(exclusiveMaximum = Maybe(v))
            case (s: JsonSchema.Integer, Schema.Constraint.Min(_, v, false)) => s.copy(minimum = Maybe(v.toLong))
            case (s: JsonSchema.Integer, Schema.Constraint.Min(_, v, true))  => s.copy(exclusiveMinimum = Maybe(v.toLong))
            case (s: JsonSchema.Integer, Schema.Constraint.Max(_, v, false)) => s.copy(maximum = Maybe(v.toLong))
            case (s: JsonSchema.Integer, Schema.Constraint.Max(_, v, true))  => s.copy(exclusiveMaximum = Maybe(v.toLong))
            case (s: JsonSchema.Str, Schema.Constraint.MinLength(_, v))      => s.copy(minLength = Maybe(v))
            case (s: JsonSchema.Str, Schema.Constraint.MaxLength(_, v))      => s.copy(maxLength = Maybe(v))
            case (s: JsonSchema.Str, Schema.Constraint.Pattern(_, regex))    => s.copy(pattern = Maybe(regex))
            case (s: JsonSchema.Str, Schema.Constraint.Format(_, fmt))       => s.copy(format = Maybe(fmt))
            case (s: JsonSchema.Arr, Schema.Constraint.MinItems(_, v))       => s.copy(minItems = Maybe(v))
            case (s: JsonSchema.Arr, Schema.Constraint.MaxItems(_, v))       => s.copy(maxItems = Maybe(v))
            case (s: JsonSchema.Arr, Schema.Constraint.UniqueItems(_))       => s.copy(uniqueItems = Maybe(true))
            case (other, _)                                                  => other
        end match
    end applyConstraint

    /** Sets the description of a JsonSchema node; every case carries one. */
    def addDescription(schema: Json.JsonSchema, desc: String): Json.JsonSchema =
        import Json.JsonSchema
        schema match
            case s: JsonSchema.Obj      => s.copy(description = Maybe(desc))
            case s: JsonSchema.Str      => s.copy(description = Maybe(desc))
            case s: JsonSchema.Num      => s.copy(description = Maybe(desc))
            case s: JsonSchema.Integer  => s.copy(description = Maybe(desc))
            case s: JsonSchema.Arr      => s.copy(description = Maybe(desc))
            case s: JsonSchema.Bool     => s.copy(description = Maybe(desc))
            case s: JsonSchema.Null     => s.copy(description = Maybe(desc))
            case s: JsonSchema.Nullable => s.copy(description = Maybe(desc))
            case s: JsonSchema.OneOf    => s.copy(description = Maybe(desc))
        end match
    end addDescription

    /** Marks a JsonSchema as deprecated.
      *
      * Only Obj supports the deprecated field in our ADT. For other types, returns unchanged.
      */
    def addDeprecated(schema: Json.JsonSchema): Json.JsonSchema =
        import Json.JsonSchema
        schema match
            case s: JsonSchema.Obj => s.copy(deprecated = Maybe(true))
            case other             => other // JSON Schema deprecated is object-level only
        end match
    end addDeprecated

end JsonSchemaEnricher
