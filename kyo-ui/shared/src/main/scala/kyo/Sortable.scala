package kyo

import Drag.Operation
import Drag.Position
import Drag.Rejection

/** Pure movement semantics for keyed sortable collections.
  *
  * A move identifies keys in one collection and places them relative to an optional destination
  * anchor. Selected keys retain their visible source order, independent of the order supplied by
  * the move request. Cross-collection copies retain the source values, while moves remove them.
  *
  * Invalid requests return an application rejection without changing either input collection.
  * The engine does not render UI or perform effects, so callers can apply it consistently across
  * presentation layers and platforms.
  *
  * @see [[Drag.Move]] for move descriptions
  * @see [[Drag.Rejection]] for rejected requests
  * @see [[Chunk]] for collection values
  */
object Sortable:

    // --- Public API ---

    /** Applies a sortable move and returns `(updatedSource, updatedDestination)`.
      *
      * Location equality is authoritative. When the locations match, `destination` is ignored and
      * both result members contain the identical updated source collection. For different locations,
      * existing destination occurrences of moving keys are removed before reinsertion.
      *
      * An absent anchor with [[Drag.Position.Before]] prepends the moving keys, while an absent anchor
      * with [[Drag.Position.After]] appends them. Every effective input collection must contain unique
      * keys. Invalid collections return `Application("Source collection keys must be unique.")` or
      * `Application("Destination collection keys must be unique.")` as appropriate.
      */
    def move(
        source: Chunk[String],
        destination: Chunk[String],
        move: Drag.Move
    ): Result[Drag.Rejection, (Chunk[String], Chunk[String])] =
        if move.keys.isEmpty then
            reject("At least one item must move.")
        else
            val sameCollection = move.source == move.destination
            val requested      = move.keys.toSet
            if requested.size != move.keys.size then
                reject("Moving item keys must be unique.")
            else
                val sourceKeys = source.toSet
                if !requested.forall(sourceKeys.contains) then
                    reject("Every moving item must exist in the source collection.")
                else if move.anchor.exists(requested.contains) then
                    reject("The destination is part of the moving selection.")
                else if move.operation == Operation.Link then
                    reject("Sortable collections do not support link operations.")
                else if sameCollection && move.operation == Operation.Copy then
                    reject("Copying within one keyed collection requires application-assigned destination keys.")
                else if move.position == Position.On || move.position == Position.Inside then
                    reject("Sortable collections require Before or After placement.")
                else
                    val effectiveDestination =
                        if sameCollection then source
                        else destination
                    move.anchor match
                        case Present(anchor) if !effectiveDestination.contains(anchor) =>
                            reject("The destination anchor does not exist.")
                        case _ if sourceKeys.size != source.size =>
                            reject("Source collection keys must be unique.")
                        case _ if !sameCollection && destination.toSet.size != destination.size =>
                            reject("Destination collection keys must be unique.")
                        case _ =>
                            val ordered = source.filter(requested.contains)
                            if sameCollection then
                                val cleaned = source.filterNot(requested.contains)
                                val updated = insert(cleaned, ordered, move.anchor, move.position)
                                Result.Success((updated, updated))
                            else
                                val base    = destination.filterNot(requested.contains)
                                val updated = insert(base, ordered, move.anchor, move.position)
                                val updatedSource =
                                    if move.operation == Operation.Copy then source
                                    else source.filterNot(requested.contains)
                                Result.Success((updatedSource, updated))
                            end if
                    end match
                end if
            end if
        end if
    end move

    /** Expands dragged keys to the whole selection when every dragged key is selected.
      *
      * The expanded keys keep `visible` order, so a multi-selection always moves in the order the
      * user sees. When any dragged key is outside the selection, the dragged keys pass through
      * unchanged. This is the standard multi-select rule shared by the demo applications.
      */
    def expandSelection(visible: Chunk[String], selected: Set[String], keys: Chunk[String]): Chunk[String] =
        if keys.nonEmpty && keys.forall(selected.contains) then visible.filter(selected.contains)
        else keys

    /** Applies a sortable move to typed collections, projecting keys with `key`.
      *
      * Delegates validation and ordering to [[move]] and rebuilds the typed collections from the
      * updated key order, carrying source values across collections. Keys in `locked` can neither
      * move nor anchor a move.
      */
    def moveBy[A](
        source: Chunk[A],
        destination: Chunk[A],
        move: Drag.Move,
        locked: Set[String] = Set.empty
    )(key: A => String): Result[Drag.Rejection, (Chunk[A], Chunk[A])] =
        if touchesLocked(locked, move) then rejectWith("Locked keys cannot move or anchor a move.")
        else
            Sortable.move(source.map(key), destination.map(key), move).map { (updatedSource, updatedDestination) =>
                val byKey = source.concat(destination).map(a => key(a) -> a).toMap
                (updatedSource.map(byKey), updatedDestination.map(byKey))
            }

    /** Applies a sortable move across an ordered set of named key collections.
      *
      * The moving keys are located in whichever collections hold them; each per-collection group is
      * applied through [[move]] in collection order, threading the anchor so keys spanning several
      * collections land contiguously at the destination. The result preserves the input collection
      * order. Keys in `locked` can neither move nor anchor a move.
      */
    def moveGroups(
        collections: Chunk[(String, Chunk[String])],
        move: Drag.Move,
        locked: Set[String] = Set.empty
    ): Result[Drag.Rejection, Chunk[(String, Chunk[String])]] =
        val destination = move.destination.collection
        if !collections.exists(_._1 == destination) then rejectWith(s"Unknown collection: $destination")
        else if touchesLocked(locked, move) then rejectWith("Locked keys cannot move or anchor a move.")
        else if move.keys.isEmpty then rejectWith("At least one item must move.")
        else if move.keys.toSet.size != move.keys.size then rejectWith("Moving item keys must be unique.")
        else
            val moving = move.keys.toSet
            if !move.keys.forall(k => collections.exists(_._2.contains(k))) then
                rejectWith("Every moving item must exist in the source collections.")
            else if move.anchor.exists(moving.contains) then
                rejectWith("The destination is part of the moving selection.")
            else
                val groups = collections.map((name, keys) => name -> keys.filter(moving.contains)).filter(_._2.nonEmpty)
                val start  = collections.toMap
                val applied = groups.foldLeft(
                    Result.succeed[Drag.Rejection, (Map[String, Chunk[String]], Maybe[String], Drag.Position)](
                        (start, move.anchor, move.position)
                    )
                ) { case (acc, (name, keys)) =>
                    acc.flatMap { (state, anchor, position) =>
                        val group = Drag.Move(keys, Drag.Location(name), move.destination, anchor, position, move.operation)
                        Sortable.move(state(name), state(destination), group).map { (src, dst) =>
                            val updated =
                                if name == destination then state.updated(name, src)
                                else state.updated(name, src).updated(destination, dst)
                            (updated, Present(keys.last), Drag.Position.After)
                        }
                    }
                }
                applied.map((state, _, _) => collections.map((name, _) => name -> state(name)))
            end if
        end if
    end moveGroups

    // --- Internal operations ---

    private def touchesLocked(locked: Set[String], move: Drag.Move): Boolean =
        locked.nonEmpty && (move.keys.exists(locked.contains) || move.anchor.exists(locked.contains))

    private def rejectWith[A](reason: String): Result[Drag.Rejection, A] =
        Result.Failure(Rejection.Application(reason))

    private def insert(
        base: Chunk[String],
        ordered: Chunk[String],
        anchor: Maybe[String],
        position: Position
    ): Chunk[String] =
        val index =
            anchor match
                case Present(value) =>
                    val anchorIndex = base.indexOf(value)
                    if position == Position.Before then anchorIndex else anchorIndex + 1
                case Absent =>
                    if position == Position.Before then 0 else base.size
        if base.isEmpty then ordered
        else if index == 0 then ordered.concat(base)
        else if index == base.size then base.concat(ordered)
        else
            val builder = ChunkBuilder.init[String]
            builder.addAll(base.take(index))
            builder.addAll(ordered)
            builder.addAll(base.drop(index))
            builder.result()
        end if
    end insert

    private def reject(
        reason: String
    ): Result[Drag.Rejection, (Chunk[String], Chunk[String])] =
        Result.Failure(Rejection.Application(reason))

end Sortable
