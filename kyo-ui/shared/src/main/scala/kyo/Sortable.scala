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

    /** Applies a sortable move to source and destination collections. */
    def move(
        source: Chunk[String],
        destination: Chunk[String],
        move: Drag.Move
    ): Result[Drag.Rejection, (Chunk[String], Chunk[String])] =
        val requested = move.keys.toSet
        if move.keys.isEmpty then
            reject("At least one item must move.")
        else if requested.size != move.keys.size then
            reject("Moving item keys must be unique.")
        else if !requested.forall(source.contains) then
            reject("Every moving item must exist in the source collection.")
        else if move.anchor.exists(requested.contains) then
            reject("The destination is part of the moving selection.")
        else if move.operation == Operation.Link then
            reject("Sortable collections do not support link operations.")
        else if move.source == move.destination && move.operation == Operation.Copy then
            reject("Copying within one keyed collection requires application-assigned destination keys.")
        else if move.position == Position.On || move.position == Position.Inside then
            reject("Sortable collections require Before or After placement.")
        else
            val sameCollection = move.source == move.destination
            val ordered        = source.filter(requested.contains)
            val cleanedSource  = source.filterNot(requested.contains)
            val base =
                if sameCollection then cleanedSource
                else destination.filterNot(requested.contains)

            move.anchor match
                case Present(anchor) if !base.contains(anchor) =>
                    reject("The destination anchor does not exist.")
                case _ =>
                    val updated = insert(base, ordered, move.anchor, move.position)
                    if sameCollection then Result.Success((updated, updated))
                    else
                        val updatedSource =
                            if move.operation == Operation.Copy then source
                            else cleanedSource
                        Result.Success((updatedSource, updated))
                    end if
            end match
        end if
    end move

    // --- Internal operations ---

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
        base.take(index).concat(ordered).concat(base.drop(index))
    end insert

    private def reject(
        reason: String
    ): Result[Drag.Rejection, (Chunk[String], Chunk[String])] =
        Result.Failure(Rejection.Application(reason))

end Sortable
