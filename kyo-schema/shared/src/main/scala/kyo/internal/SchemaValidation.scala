package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import scala.annotation.nowarn

/** Validation and metadata accumulation helpers for Schema instances.
  *
  * These methods produce new Schema instances with added checks, constraints, field documentation, or deprecation markers. They are called
  * from Schema's inline methods which use Focus lambdas to identify fields.
  */
private[kyo] object SchemaValidation:

    /** Internal factory for field-level check accumulation. Called from inline check method. */
    @nowarn("msg=anonymous")
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
        Schema.copyWith(meta)(
            checks = meta.checks :+ check
        )
    end fieldCheck

    /** Internal factory for constraint-based check accumulation. Like fieldCheck but also stores the Constraint for JsonSchema enrichment.
      */
    @nowarn("msg=anonymous")
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
        Schema.copyWith(meta)(
            checks = meta.checks :+ check,
            constraints = meta.constraints :+ constraint
        )
    end fieldCheckWithConstraint

    /** Internal factory for advisory-only constraints (no runtime predicate). Used by format. */
    @nowarn("msg=anonymous")
    def fieldConstraintOnly[A, V](
        meta: Schema[A],
        constraint: Schema.Constraint
    ): Schema[A] { type Focused = meta.Focused } =
        Schema.copyWith(meta)(
            constraints = meta.constraints :+ constraint
        )
    end fieldConstraintOnly

    /** Internal helper for field-level doc accumulation. Called from inline doc method. */
    @nowarn("msg=anonymous")
    def withFieldDoc[A](
        meta: Schema[A],
        fieldPath: Seq[String],
        description: String
    ): Schema[A] { type Focused = meta.Focused } =
        Schema.copyWith(meta)(
            fieldDocs = meta.fieldDocs.updated(fieldPath, description)
        )
    end withFieldDoc

    /** Internal helper for field-level deprecated accumulation. Called from inline deprecated method. */
    @nowarn("msg=anonymous")
    def withFieldDeprecated[A](
        meta: Schema[A],
        fieldPath: Seq[String],
        reason: String
    ): Schema[A] { type Focused = meta.Focused } =
        Schema.copyWith(meta)(
            fieldDeprecated = meta.fieldDeprecated.updated(fieldPath, reason)
        )
    end withFieldDeprecated

end SchemaValidation
