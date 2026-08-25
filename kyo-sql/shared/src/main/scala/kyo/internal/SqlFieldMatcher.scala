package kyo.internal

import kyo.Chunk
import kyo.Maybe
import kyo.SqlNaming
import kyo.SqlRow

/** Decides which field of a row codec the column at a given index belongs to, for [[kyo.SqlSchema]]'s row read loop.
  *
  * The row read consumes columns in incoming order and asks, per column, which field slot it fills. A matcher that answered "yes" to every
  * probe would pin every column to the first field. Both backends need the same answer, so the rule lives here rather than twice in the
  * readers, and it is built once per decode where the row's field names are in scope.
  *
  * Which mode applies is a property of the STATEMENT that produced the row, carried as a [[kyo.SqlRow.FieldMatch]] rather than inferred
  * from whether the names happen to line up (an inference that would let a missing name silently convert a by-name read to a positional
  * one and transpose same-typed columns).
  *
  * A DSL statement is POSITIONAL: the renderer emits the column list explicitly, in field order (a projection's `Mirror.ProductOf`
  * constraint proves the field types line up the same way), so the n-th column feeds the n-th field regardless of what any column is
  * called (`sum`, `?column?`, a cased name).
  *
  * A `sql"..."` fragment is BY NAME: the caller wrote the column order, so `SELECT age, name FROM person` decoded into `Person(name,
  * age)` still lands each value in its own field, and a field that resolves to no column is a decode failure the codec raises before
  * reading, never a silent switch to position.
  *
  * The manual [[kyo.SqlRow.decode]] API is VERBATIM: by name when every field name matches a column verbatim, positional otherwise,
  * because a bare row carries no statement to consult.
  */
private[kyo] object SqlFieldMatcher:

    /** Builds the matcher for reading a row whose codec resolves to `fieldNames` out of a row whose columns are `columnNames`.
      *
      * @param fieldNames
      *   the row codec's column names, post `@column` rename
      * @param columnNames
      *   the row's column names as the server reported them, already sliced to the columns the codec occupies
      * @param naming
      *   the run-scope [[SqlNaming]] casing threaded from the query site; `Absent` matches verbatim
      */
    def of(
        fieldNames: Chunk[String],
        columnNames: Chunk[String],
        naming: Maybe[SqlNaming],
        fieldMatch: SqlRow.FieldMatch
    ): (Int, String) => Boolean =
        val resolve: String => String =
            naming match
                case Maybe.Present(n) => n.columnName(_)
                case Maybe.Absent     => identity
        fieldMatch match
            case SqlRow.FieldMatch.Positional => byPosition(fieldNames)
            case SqlRow.FieldMatch.ByName     => byName(columnNames, resolve)
            case SqlRow.FieldMatch.Verbatim =>
                val resolved = fieldNames.map(resolve)
                if resolved.forall(columnNames.contains) then byName(columnNames, resolve)
                else byPosition(fieldNames)
        end match
    end of

    /** The first field name that resolves to no column, for the strict [[kyo.SqlRow.FieldMatch.ByName]] mode's pre-read check, or
      * [[Maybe.Absent]] when every field is addressable. Tuple element names (`_1`, `_2`, ...) are positional by nature and never count
      * as missing.
      */
    def missingByName(fieldNames: Chunk[String], columnNames: Chunk[String], naming: Maybe[SqlNaming]): Maybe[String] =
        val resolve: String => String =
            naming match
                case Maybe.Present(n) => n.columnName(_)
                case Maybe.Absent     => identity
        Maybe.fromOption(fieldNames.find { field =>
            !isTupleName(field) && !columnNames.contains(field) && !columnNames.contains(resolve(field))
        }).map(resolve)
    end missingByName

    /** The matcher for a reader with no row codec in hand: the probe must name the column at `idx`, or be its tuple element.
      *
      * A single-column read never drives the field loop, so this exists to keep one definition of the rules rather than a second, subtly
      * different one at each reader's fallback.
      */
    def verbatim(columnNames: Chunk[String]): (Int, String) => Boolean =
        byName(columnNames, identity)

    /** Matches when the probe names the column at `idx`, verbatim or through the run-scope casing.
      *
      * The resolution is what makes a cased read work: the probes are the codec's field names, while the server reports the cased ones, so
      * `firstName` has to be resolved to `first_name` here or the field is never found.
      */
    private def byName(columnNames: Chunk[String], resolve: String => String): (Int, String) => Boolean =
        (idx, probe) =>
            idx < columnNames.size && {
                val column = columnNames(idx)
                column == probe || column == resolve(probe) || isTupleElement(probe, idx)
            }

    /** Matches when the probe names the codec's `idx`-th field, so the n-th column feeds the n-th field. */
    private def byPosition(fieldNames: Chunk[String]): (Int, String) => Boolean =
        (idx, probe) => idx < fieldNames.size && (fieldNames(idx) == probe || isTupleElement(probe, idx))

    /** True for the `_1` / `_2` probes a tuple row emits, at their own position.
      *
      * A tuple has no field names of its own, so its probes are positional already. Accepting them in both modes is what lets a tuple
      * projection decode when its columns are aliased or named after the expressions they compute.
      */
    private def isTupleElement(probe: String, idx: Int): Boolean =
        isTupleName(probe) && probe.drop(1) == (idx + 1).toString

    /** True for a tuple element's synthetic field name (`_1`, `_2`, ...), which is positional rather than a column name. */
    private def isTupleName(probe: String): Boolean =
        probe.length > 1 && probe.charAt(0) == '_' && probe.drop(1).forall(_.isDigit)

end SqlFieldMatcher
