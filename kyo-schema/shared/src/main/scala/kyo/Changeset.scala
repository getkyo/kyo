package kyo

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import scala.annotation.tailrec

/** Serializable sequence of patch operations produced by comparing two values.
  *
  * Use Changeset when changes need to be stored, transmitted, or replayed independently of the original values. Unlike Compare, which holds
  * live references to the original values, Changeset is a self-contained ADT that can be serialized, sent over the wire, stored in a
  * database, and applied later to reconstruct the target state from any matching source value.
  *
  * {{{
  * val cs = Changeset(oldUser, newUser)   // compute diff
  * val serialized = Json.encode(cs)       // transmit
  * val restored = cs.applyTo(oldUser)     // replay on the other side
  * }}}
  *
  * @tparam A
  *   The type of values being compared and patched
  *
  * @see
  *   [[Compare]] for read-only field comparison without serialization
  * @see
  *   [[Modify]] for accumulating imperative mutations in a single step
  */
final case class Changeset[A](operations: Chunk[Changeset.Patch]) derives Schema:

    /** Returns true when the two values are identical (no operations). */
    def isEmpty: Boolean = operations.isEmpty

    /** Applies this changeset to a value, producing the updated value.
      *
      * Each operation modifies the value's Structure.Value representation, then the result is decoded back to type A. Returns a `Result` to
      * handle cases where the changeset operations produce an invalid dynamic structure that cannot be decoded back to type A.
      */
    def applyTo(value: A)(using schema: Schema[A], frame: Frame): Result[SchemaException, A] =
        if isEmpty then Result.succeed(value)
        else
            val dynVal  = schema.toStructureValue(value)
            val updated = Changeset.applyOps(dynVal, operations)
            schema.fromStructureValue(updated)
        end if
    end applyTo

    /** Composes two changesets: applies this changeset first, then the other. */
    def andThen(other: Changeset[A]): Changeset[A] =
        Changeset[A](operations ++ other.operations)

end Changeset

object Changeset:

    /** A single patch operation targeting a field path within the Structure.Value tree.
      *
      * Variants:
      *   - [[Patch.SetField]]: replace a field with an entirely new value
      *   - [[Patch.RemoveField]]: delete a field from a record
      *   - [[Patch.SetNull]]: clear an optional field to null
      *   - [[Patch.NumericDelta]]: store a numeric delta rather than the absolute value
      *   - [[Patch.StringPatch]]: insert/delete/replace a character range within a string
      *   - [[Patch.Nested]]: recursively patch a nested record
      *   - [[Patch.SequencePatch]]: add, remove, or reorder sequence elements
      *   - [[Patch.MapPatch]]: add, remove, or update map entries
      */
    sealed abstract class Patch derives CanEqual, Schema:
        def fieldPath: Chunk[String]

    object Patch:
        /** Replaces the field at `fieldPath` with `value`. */
        case class SetField(fieldPath: Chunk[String], value: Structure.Value) extends Patch

        /** Removes the field at `fieldPath` from the enclosing record. */
        case class RemoveField(fieldPath: Chunk[String]) extends Patch

        /** Clears the optional field at `fieldPath` by setting it to null. */
        case class SetNull(fieldPath: Chunk[String]) extends Patch

        /** Applies a numeric delta to the field at `fieldPath` (stores the difference, not the absolute value). */
        case class NumericDelta(fieldPath: Chunk[String], delta: BigDecimal) extends Patch

        /** Applies a character-range edit to the string field at `fieldPath`.
          *
          * @param offset
          *   start position of the edit (0-based)
          * @param deleteCount
          *   number of characters to delete starting at `offset`
          * @param insert
          *   string to insert at `offset` after deletion
          */
        case class StringPatch(fieldPath: Chunk[String], offset: Int, deleteCount: Int, insert: String) extends Patch

        /** Recursively patches the nested record at `fieldPath` using the given sub-operations. */
        case class Nested(fieldPath: Chunk[String], operations: Chunk[Patch]) extends Patch

        /** Adds, removes, or reorders elements in the sequence at `fieldPath`. */
        case class SequencePatch(
            fieldPath: Chunk[String],
            added: Chunk[Structure.Value],
            removed: Chunk[Int],
            moved: Chunk[(Int, Int)]
        ) extends Patch

        /** Adds, removes, or updates entries in the map at `fieldPath`. */
        case class MapPatch(
            fieldPath: Chunk[String],
            added: Chunk[(Structure.Value, Structure.Value)],
            removed: Chunk[Structure.Value],
            updated: Chunk[(Structure.Value, Structure.Value)]
        ) extends Patch

    end Patch

    /** Produces a serializable changeset by comparing two values field-by-field. */
    def apply[A](old: A, updated: A)(using schema: Schema[A], frame: Frame): Changeset[A] =
        val oldDyn = schema.toStructureValue(old)
        val newDyn = schema.toStructureValue(updated)
        val ops    = computeOps(oldDyn, newDyn, Chunk.empty)
        Changeset[A](ops)
    end apply

    // --- Internal: compute operations by comparing two Structure.Value trees ---

    private def computeOps(
        oldVal: Structure.Value,
        newVal: Structure.Value,
        path: Chunk[String]
    ): Chunk[Patch] =
        if oldVal == newVal then Chunk.empty
        else
            (oldVal, newVal) match
                case (Structure.Value.Record(oldFields), Structure.Value.Record(newFields)) =>
                    computeRecordOps(oldFields, newFields, path)
                case (Structure.Value.Str(ov), Structure.Value.Str(nv)) =>
                    computeStringPatch(ov, nv, path)
                case (Structure.Value.Integer(ov), Structure.Value.Integer(nv)) =>
                    Chunk(Patch.NumericDelta(path, BigDecimal(nv) - BigDecimal(ov)))
                case (Structure.Value.Decimal(ov), Structure.Value.Decimal(nv)) =>
                    Chunk(Patch.NumericDelta(path, BigDecimal(nv) - BigDecimal(ov)))
                case (Structure.Value.BigNum(ov), Structure.Value.BigNum(nv)) =>
                    Chunk(Patch.NumericDelta(path, nv - ov))
                case (_, Structure.Value.Null) =>
                    Chunk(Patch.SetNull(path))
                case (Structure.Value.Sequence(oldElems), Structure.Value.Sequence(newElems)) =>
                    computeSequenceOps(oldElems, newElems, path)
                case (Structure.Value.MapEntries(oldEntries), Structure.Value.MapEntries(newEntries)) =>
                    computeMapOps(oldEntries, newEntries, path)
                case _ =>
                    Chunk(Patch.SetField(path, newVal))
    end computeOps

    private def computeStringPatch(oldStr: String, newStr: String, path: Chunk[String]): Chunk[Patch] =
        val minLen = math.min(oldStr.length, newStr.length)

        @tailrec def prefix(i: Int): Int =
            if i < minLen && oldStr.charAt(i) == newStr.charAt(i) then prefix(i + 1)
            else i

        val prefixLen = prefix(0)

        @tailrec def suffix(i: Int): Int =
            if i < minLen - prefixLen &&
                oldStr.charAt(oldStr.length - 1 - i) == newStr.charAt(newStr.length - 1 - i)
            then suffix(i + 1)
            else i

        val suffixLen   = suffix(0)
        val deleteCount = oldStr.length - prefixLen - suffixLen
        val insert      = newStr.substring(prefixLen, newStr.length - suffixLen)
        Chunk(Patch.StringPatch(path, prefixLen, deleteCount, insert))
    end computeStringPatch

    private def computeRecordOps(
        oldFields: Chunk[(String, Structure.Value)],
        newFields: Chunk[(String, Structure.Value)],
        path: Chunk[String]
    ): Chunk[Patch] =
        val oldMap = oldFields.toMap
        val newMap = newFields.toMap

        val allKeys = (oldFields.map(_._1) ++ newFields.map(_._1)).distinct

        Chunk.from(allKeys.flatMap { key =>
            val fieldPath = path :+ key
            (oldMap.get(key), newMap.get(key)) match
                case (Some(ov), Some(nv)) =>
                    if ov == nv then Chunk.empty
                    else
                        (ov, nv) match
                            case (Structure.Value.Record(of), Structure.Value.Record(nf)) =>
                                val nested = computeRecordOps(of, nf, Chunk.empty)
                                if nested.isEmpty then Chunk.empty
                                else Chunk(Patch.Nested(fieldPath, nested))
                            case _ =>
                                computeOps(ov, nv, fieldPath)
                case (Some(_), None) =>
                    Chunk(Patch.RemoveField(fieldPath))
                case (None, Some(nv)) =>
                    Chunk(Patch.SetField(fieldPath, nv))
                case (None, None) =>
                    Chunk.empty
            end match
        })
    end computeRecordOps

    private def computeSequenceOps(
        oldElems: Chunk[Structure.Value],
        newElems: Chunk[Structure.Value],
        path: Chunk[String]
    ): Chunk[Patch] =
        val oldSet = oldElems.toSet
        val newSet = newElems.toSet

        val added   = Chunk.from(newElems.filterNot(oldSet.contains))
        val removed = Chunk.from(oldElems.zipWithIndex.filter { case (v, _) => !newSet.contains(v) }.map(_._2))

        if added.isEmpty && removed.isEmpty then Chunk.empty
        else Chunk(Patch.SequencePatch(path, added, removed, Chunk.empty))
    end computeSequenceOps

    private def computeMapOps(
        oldEntries: Chunk[(Structure.Value, Structure.Value)],
        newEntries: Chunk[(Structure.Value, Structure.Value)],
        path: Chunk[String]
    ): Chunk[Patch] =
        val oldMap = oldEntries.toMap
        val newMap = newEntries.toMap

        val allKeys = (oldEntries.map(_._1) ++ newEntries.map(_._1)).distinct

        val categorized = allKeys.map { key =>
            (oldMap.get(key), newMap.get(key)) match
                case (None, Some(nv))                 => (Some((key, nv)), None, None)
                case (Some(_), None)                  => (None, Some(key), None)
                case (Some(ov), Some(nv)) if ov != nv => (None, None, Some((key, nv)))
                case _                                => (None, None, None)
        }

        val added   = Chunk.from(categorized.flatMap(_._1))
        val removed = Chunk.from(categorized.flatMap(_._2))
        val updated = Chunk.from(categorized.flatMap(_._3))

        if added.isEmpty && removed.isEmpty && updated.isEmpty then Chunk.empty
        else Chunk(Patch.MapPatch(path, added, removed, updated))
    end computeMapOps

    // --- Internal: apply operations to a Structure.Value ---

    private[kyo] def applyOps(value: Structure.Value, ops: Chunk[Patch]): Structure.Value =
        ops.foldLeft(value) { (v, op) => applyOp(v, op) }

    private def applyOp(value: Structure.Value, op: Patch): Structure.Value =
        op match
            case Patch.SetField(fieldPath, newVal) =>
                setAtPath(value, fieldPath, newVal)
            case Patch.RemoveField(fieldPath) =>
                removeAtPath(value, fieldPath)
            case Patch.SetNull(fieldPath) =>
                setAtPath(value, fieldPath, Structure.Value.Null)
            case Patch.NumericDelta(fieldPath, delta) =>
                modifyAtPath(value, fieldPath) {
                    case Structure.Value.Integer(v) =>
                        val newValue = BigDecimal(v) + delta
                        Structure.Value.Integer(newValue.toLong)
                    case Structure.Value.Decimal(v) =>
                        val newValue = BigDecimal(v) + delta
                        Structure.Value.Decimal(newValue.toDouble)
                    case Structure.Value.BigNum(v) =>
                        Structure.Value.BigNum(v + delta)
                    case other => other
                }
            case Patch.StringPatch(fieldPath, offset, deleteCount, insert) =>
                modifyAtPath(value, fieldPath) {
                    case Structure.Value.Str(s) =>
                        val before = s.substring(0, math.min(offset, s.length))
                        val after  = s.substring(math.min(offset + deleteCount, s.length))
                        Structure.Value.Str(before + insert + after)
                    case other => other
                }
            case Patch.Nested(fieldPath, nestedOps) =>
                modifyAtPath(value, fieldPath) { nested =>
                    applyOps(nested, nestedOps)
                }
            case Patch.SequencePatch(fieldPath, added, removed, _) =>
                modifyAtPath(value, fieldPath) {
                    case Structure.Value.Sequence(elems) =>
                        val removedSet = removed.toSet
                        val kept       = elems.zipWithIndex.filterNot { case (_, i) => removedSet.contains(i) }.map(_._1)
                        Structure.Value.Sequence(kept ++ Chunk.from(added))
                    case other => other
                }
            case Patch.MapPatch(fieldPath, added, removed, updated) =>
                modifyAtPath(value, fieldPath) {
                    case Structure.Value.MapEntries(entries) =>
                        val removedSet  = removed.toSet
                        val updatedMap  = updated.toMap
                        val afterRemove = entries.filterNot { case (k, _) => removedSet.contains(k) }
                        val afterUpdate = afterRemove.map { case (k, v) => (k, updatedMap.getOrElse(k, v)) }
                        val afterAdd    = afterUpdate ++ Chunk.from(added)
                        Structure.Value.MapEntries(afterAdd)
                    case other => other
                }
    end applyOp

    private def setAtPath(value: Structure.Value, fieldPath: Chunk[String], newVal: Structure.Value): Structure.Value =
        if fieldPath.isEmpty then newVal
        else
            value match
                case Structure.Value.Record(fields) =>
                    val key  = fieldPath.head
                    val rest = fieldPath.tail
                    if fields.exists(_._1 == key) then
                        val updated = fields.map { case (n, v) =>
                            if n == key then (n, setAtPath(v, rest, newVal))
                            else (n, v)
                        }
                        Structure.Value.Record(updated)
                    else
                        // Field not present — add it
                        Structure.Value.Record(fields :+ (key, setAtPath(Structure.Value.Null, rest, newVal)))
                    end if
                case other =>
                    other
    end setAtPath

    private def removeAtPath(value: Structure.Value, fieldPath: Chunk[String]): Structure.Value =
        if fieldPath.isEmpty then Structure.Value.Null
        else
            value match
                case Structure.Value.Record(fields) =>
                    val key  = fieldPath.head
                    val rest = fieldPath.tail
                    if rest.isEmpty then
                        Structure.Value.Record(fields.filterNot(_._1 == key))
                    else
                        val updated = fields.map { case (n, v) =>
                            if n == key then (n, removeAtPath(v, rest))
                            else (n, v)
                        }
                        Structure.Value.Record(updated)
                    end if
                case other =>
                    other
    end removeAtPath

    private def modifyAtPath(value: Structure.Value, fieldPath: Chunk[String])(f: Structure.Value => Structure.Value): Structure.Value =
        if fieldPath.isEmpty then f(value)
        else
            value match
                case Structure.Value.Record(fields) =>
                    val key  = fieldPath.head
                    val rest = fieldPath.tail
                    val updated = fields.map { case (n, v) =>
                        if n == key then (n, modifyAtPath(v, rest)(f))
                        else (n, v)
                    }
                    Structure.Value.Record(updated)
                case other =>
                    other
    end modifyAtPath

end Changeset
