package kyo.internal

import kyo.*

/** JSON Schema enrichment helpers: applies runtime Schema metadata (docs, constraints, examples, transforms) to a JsonSchema.Obj.
  *
  * These are pure functions on JsonSchema values, separated so that JSON Schema enrichment is not mixed with serialization or factory
  * concerns.
  */
private[kyo] object JsonSchemaEnricher:

    /** Enriches a JsonSchema.Obj with runtime metadata: doc, field docs, field deprecations, examples, and constraints.
      *
      * Separated from the inline enrichJsonSchema method so that all operations on the Obj are typed at the concrete JsonSchema.Obj level.
      */
    def enrichObj(
        obj: Json.JsonSchema.Obj,
        doc: Maybe[String],
        fieldDocs: Map[Seq[String], String],
        fieldDeprecated: Map[Seq[String], String],
        examples: Chunk[Structure.Value],
        constraints: Seq[Schema.Constraint] = Seq.empty,
        droppedFields: Set[String] = Set.empty,
        renamedFields: Map[String, String] = Map.empty
    ): Json.JsonSchema.Obj =
        val withDoc: Json.JsonSchema.Obj =
            if doc.isEmpty then obj else obj.copy(description = doc)
        val withExamples: Json.JsonSchema.Obj =
            if examples.isEmpty then withDoc else withDoc.copy(examples = examples)
        val withMeta: Json.JsonSchema.Obj =
            if fieldDocs.isEmpty && fieldDeprecated.isEmpty then withExamples
            else
                withExamples.copy(
                    properties = withExamples.properties.map { (name, schema) =>
                        val docced =
                            fieldDocs.get(Seq(name)) match
                                case Some(d) => addDescription(schema, d)
                                case None    => schema
                        val depped =
                            if fieldDeprecated.contains(Seq(name)) then addDeprecated(docced)
                            else docced
                        (name, depped)
                    }
                )
        val withConstraints: Json.JsonSchema.Obj =
            if constraints.isEmpty then withMeta
            else
                withMeta.copy(
                    properties = withMeta.properties.map { (name, schema) =>
                        val matching = constraints.filter(_.segments.lastOption.contains(name))
                        val enriched = matching.foldLeft(schema)((s, c) => applyConstraint(s, c))
                        (name, enriched)
                    }
                )
        val withTransforms: Json.JsonSchema.Obj =
            if droppedFields.isEmpty && renamedFields.isEmpty then withConstraints
            else
                // Filter out dropped fields from properties
                val filteredProps = withConstraints.properties.filterNot((name, _) => droppedFields.contains(name))
                // Rename fields in properties
                val renamedProps = filteredProps.map { (name, schema) =>
                    renamedFields.getOrElse(name, name) -> schema
                }
                // Filter and rename in required list
                val filteredRequired = withConstraints.required.filterNot(droppedFields.contains)
                val renamedRequired  = filteredRequired.map(name => renamedFields.getOrElse(name, name))
                withConstraints.copy(properties = renamedProps, required = renamedRequired)
        withTransforms
    end enrichObj

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

    /** Adds a description to a JsonSchema, supporting the types that have a description field.
      *
      * For types without a description field (Bool, NullType, Enum, etc.), returns the schema unchanged.
      */
    def addDescription(schema: Json.JsonSchema, desc: String): Json.JsonSchema =
        import Json.JsonSchema
        schema match
            case s: JsonSchema.Obj     => s.copy(description = Maybe(desc))
            case s: JsonSchema.Str     => s.copy(description = Maybe(desc))
            case s: JsonSchema.Num     => s.copy(description = Maybe(desc))
            case s: JsonSchema.Integer => s.copy(description = Maybe(desc))
            case s: JsonSchema.Arr     => s.copy(description = Maybe(desc))
            case other                 => other // types without description field
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
