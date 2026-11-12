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
    ): Schema[A] { type Focused = meta.Focused } =
        val check: (A, Frame) => Seq[ValidationFailedException] = (root: A, frame: Frame) =>
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
    ): Schema[A] { type Focused = meta.Focused } =
        val check: (A, Frame) => Seq[ValidationFailedException] = (root: A, frame: Frame) =>
            getter(root) match
                case Maybe.Present(v) =>
                    if pred(v) then Seq.empty
                    else Seq(ValidationFailedException(segs, msg)(using frame))
                case _ => Seq.empty
        Schema.copyWith(meta)(
            checks = meta.checks :+ check,
            constraints = meta.constraints :+ sourceConstraint(meta, constraint)
        )
    end fieldCheckWithConstraint

    /** Internal factory for advisory-only constraints (no runtime predicate). Used by format. */
    @nowarn("msg=anonymous")
    def fieldConstraintOnly[A, V](
        meta: Schema[A],
        constraint: Schema.Constraint
    ): Schema[A] { type Focused = meta.Focused } =
        Schema.copyWith(meta)(
            constraints = meta.constraints :+ sourceConstraint(meta, constraint)
        )
    end fieldConstraintOnly

    // A focus names the field as the builder currently exposes it. Keep its source path in metadata so subsequent renames and
    // drops can project the constraint without confusing a renamed field with a dropped field whose name was reused.
    private def sourceConstraint(meta: Schema[?], constraint: Schema.Constraint): Schema.Constraint =
        // Only this schema's Focused type carries its builder renames. Navigation below it uses each field's declared type.
        // Do not force field schemas here: a recursive given can still be under construction when a check is installed.
        val path = constraint.segments.toList match
            case Nil          => Nil
            case name :: rest => Schema.renamedSource(meta.renamedFields, name) :: rest
        if path == constraint.segments then constraint
        else
            constraint match
                case c: Schema.Constraint.Min         => c.copy(segments = path)
                case c: Schema.Constraint.Max         => c.copy(segments = path)
                case c: Schema.Constraint.MinLength   => c.copy(segments = path)
                case c: Schema.Constraint.MaxLength   => c.copy(segments = path)
                case c: Schema.Constraint.Pattern     => c.copy(segments = path)
                case c: Schema.Constraint.Format      => c.copy(segments = path)
                case c: Schema.Constraint.MinItems    => c.copy(segments = path)
                case c: Schema.Constraint.MaxItems    => c.copy(segments = path)
                case c: Schema.Constraint.UniqueItems => c.copy(segments = path)
        end if
    end sourceConstraint

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
