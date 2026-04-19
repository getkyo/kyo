package kyo.internal

import kyo.*

/** Validation and metadata accumulation helpers for Schema instances.
  *
  * These methods produce new Schema instances with added checks, constraints, field documentation, or deprecation markers. They are called
  * from Schema's inline methods which use Focus lambdas to identify fields.
  */
private[kyo] object SchemaValidation:

    /** Internal factory for field-level check accumulation. Called from inline check method. */
    def fieldCheck[A, V](
        meta: Schema[A],
        getter: A => Maybe[V],
        segs: Seq[String],
        pred: V => Boolean,
        msg: String
    )(using frame: Frame): Schema[A] { type Focused = meta.Focused } =
        val check: A => Seq[ValidationFailedException] = (root: A) =>
            getter(root) match
                case Maybe.Present(v) =>
                    if pred(v) then Seq.empty
                    else Seq(ValidationFailedException(segs, msg)(using frame))
                case _ => Seq.empty
        SchemaFactory.createWithFocused[A, meta.Focused](
            meta.getter,
            meta.setter,
            meta.segments,
            meta.checks :+ check,
            meta.computedFields,
            meta.renamedFields,
            meta.sourceFields,
            meta.droppedFields,
            meta.documentation,
            meta.fieldDocs,
            meta.examples,
            meta.fieldDeprecated,
            meta.constraints,
            meta.fieldIdOverrides,
            serializeWrite = meta.serializeWrite,
            serializeRead = meta.serializeRead,
            discriminatorField = meta.discriminatorField
        )
    end fieldCheck

    /** Internal factory for constraint-based check accumulation. Like fieldCheck but also stores the Constraint for JsonSchema enrichment.
      */
    def fieldCheckWithConstraint[A, V](
        meta: Schema[A],
        getter: A => Maybe[V],
        segs: Seq[String],
        pred: V => Boolean,
        msg: String,
        constraint: Schema.Constraint
    )(using frame: Frame): Schema[A] { type Focused = meta.Focused } =
        val check: A => Seq[ValidationFailedException] = (root: A) =>
            getter(root) match
                case Maybe.Present(v) =>
                    if pred(v) then Seq.empty
                    else Seq(ValidationFailedException(segs, msg)(using frame))
                case _ => Seq.empty
        SchemaFactory.createWithFocused[A, meta.Focused](
            meta.getter,
            meta.setter,
            meta.segments,
            meta.checks :+ check,
            meta.computedFields,
            meta.renamedFields,
            meta.sourceFields,
            meta.droppedFields,
            meta.documentation,
            meta.fieldDocs,
            meta.examples,
            meta.fieldDeprecated,
            meta.constraints :+ constraint,
            meta.fieldIdOverrides,
            serializeWrite = meta.serializeWrite,
            serializeRead = meta.serializeRead,
            discriminatorField = meta.discriminatorField
        )
    end fieldCheckWithConstraint

    /** Internal factory for advisory-only constraints (no runtime predicate). Used by format. */
    def fieldConstraintOnly[A, V](
        meta: Schema[A],
        constraint: Schema.Constraint
    ): Schema[A] { type Focused = meta.Focused } =
        SchemaFactory.createWithFocused[A, meta.Focused](
            meta.getter,
            meta.setter,
            meta.segments,
            meta.checks,
            meta.computedFields,
            meta.renamedFields,
            meta.sourceFields,
            meta.droppedFields,
            meta.documentation,
            meta.fieldDocs,
            meta.examples,
            meta.fieldDeprecated,
            meta.constraints :+ constraint,
            meta.fieldIdOverrides,
            serializeWrite = meta.serializeWrite,
            serializeRead = meta.serializeRead,
            discriminatorField = meta.discriminatorField
        )
    end fieldConstraintOnly

    /** Internal helper for field-level doc accumulation. Called from inline doc method. */
    def withFieldDoc[A](
        meta: Schema[A],
        fieldPath: Seq[String],
        description: String
    ): Schema[A] { type Focused = meta.Focused } =
        SchemaFactory.createWithFocused[A, meta.Focused](
            meta.getter,
            meta.setter,
            meta.segments,
            meta.checks,
            meta.computedFields,
            meta.renamedFields,
            meta.sourceFields,
            meta.droppedFields,
            meta.documentation,
            meta.fieldDocs.updated(fieldPath, description),
            meta.examples,
            meta.fieldDeprecated,
            meta.constraints,
            meta.fieldIdOverrides,
            serializeWrite = meta.serializeWrite,
            serializeRead = meta.serializeRead,
            discriminatorField = meta.discriminatorField
        )
    end withFieldDoc

    /** Internal helper for field-level deprecated accumulation. Called from inline deprecated method. */
    def withFieldDeprecated[A](
        meta: Schema[A],
        fieldPath: Seq[String],
        reason: String
    ): Schema[A] { type Focused = meta.Focused } =
        SchemaFactory.createWithFocused[A, meta.Focused](
            meta.getter,
            meta.setter,
            meta.segments,
            meta.checks,
            meta.computedFields,
            meta.renamedFields,
            meta.sourceFields,
            meta.droppedFields,
            meta.documentation,
            meta.fieldDocs,
            meta.examples,
            meta.fieldDeprecated.updated(fieldPath, reason),
            meta.constraints,
            meta.fieldIdOverrides,
            serializeWrite = meta.serializeWrite,
            serializeRead = meta.serializeRead,
            discriminatorField = meta.discriminatorField
        )
    end withFieldDeprecated

end SchemaValidation
