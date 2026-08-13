package kyo

import Drag.*

class SortableTest extends kyo.test.Test[Any]:

    private val sourceLocation      = Location("source")
    private val destinationLocation = Location("destination")

    private def move(
        keys: Chunk[String],
        anchor: Maybe[String],
        position: Position,
        operation: Operation = Operation.Move,
        source: Location = sourceLocation,
        destination: Location = destinationLocation
    ): Move =
        Move(keys, source, destination, anchor, position, operation)

    "move" - {
        "moves forward within one collection" in {
            val collection = Chunk("a", "b", "c", "d")
            val request = move(
                Chunk("b"),
                Present("d"),
                Position.After,
                source = sourceLocation,
                destination = sourceLocation
            )
            assert(Sortable.move(collection, collection, request) == Result.Success((Chunk("a", "c", "d", "b"), Chunk("a", "c", "d", "b"))))
        }

        "moves backward within one collection" in {
            val collection = Chunk("a", "b", "c", "d")
            val request = move(
                Chunk("d"),
                Present("b"),
                Position.Before,
                source = sourceLocation,
                destination = sourceLocation
            )
            assert(Sortable.move(collection, collection, request) == Result.Success((Chunk("a", "d", "b", "c"), Chunk("a", "d", "b", "c"))))
        }

        "moves between collections before an anchor" in {
            val request = move(Chunk("b"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b", "c"), Chunk("x", "y", "z"), request) ==
                    Result.Success((Chunk("a", "c"), Chunk("x", "b", "y", "z")))
            )
        }

        "moves between collections after an anchor" in {
            val request = move(Chunk("b"), Present("y"), Position.After)
            assert(
                Sortable.move(Chunk("a", "b", "c"), Chunk("x", "y", "z"), request) ==
                    Result.Success((Chunk("a", "c"), Chunk("x", "y", "b", "z")))
            )
        }

        "prepends to an empty destination before an absent anchor" in {
            val request = move(Chunk("b"), Absent, Position.Before)
            assert(Sortable.move(Chunk("a", "b", "c"), Chunk.empty, request) == Result.Success((Chunk("a", "c"), Chunk("b"))))
        }

        "appends to an empty destination after an absent anchor" in {
            val request = move(Chunk("b"), Absent, Position.After)
            assert(Sortable.move(Chunk("a", "b", "c"), Chunk.empty, request) == Result.Success((Chunk("a", "c"), Chunk("b"))))
        }

        "inserts selected keys in visible source order" in {
            val request = move(Chunk("d", "b"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b", "c", "d"), Chunk("x", "y"), request) ==
                    Result.Success((Chunk("a", "c"), Chunk("x", "b", "d", "y")))
            )
        }

        "rejects a missing selected key" in {
            val request = move(Chunk("b", "missing"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("Every moving item must exist in the source collection."))
            )
        }

        "rejects duplicate requested keys" in {
            val request = move(Chunk("b", "b"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("Moving item keys must be unique."))
            )
        }

        "rejects an empty selection" in {
            val request = move(Chunk.empty, Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("At least one item must move."))
            )
        }

        "rejects a selected destination anchor" in {
            val request = move(Chunk("b"), Present("b"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "b"), request) ==
                    Result.Failure(Rejection.Application("The destination is part of the moving selection."))
            )
        }

        "rejects a missing destination anchor" in {
            val request = move(Chunk("b"), Present("missing"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("The destination anchor does not exist."))
            )
        }

        "rejects link operations" in {
            val request = move(Chunk("b"), Present("y"), Position.Before, Operation.Link)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("Sortable collections do not support link operations."))
            )
        }

        "rejects copies within one collection" in {
            val request = move(
                Chunk("b"),
                Present("c"),
                Position.Before,
                Operation.Copy,
                sourceLocation,
                sourceLocation
            )
            assert(
                Sortable.move(Chunk("a", "b", "c"), Chunk("a", "b", "c"), request) ==
                    Result.Failure(
                        Rejection.Application("Copying within one keyed collection requires application-assigned destination keys.")
                    )
            )
        }

        "copies between collections without removing the source" in {
            val request = move(Chunk("d", "b"), Present("y"), Position.After, Operation.Copy)
            assert(
                Sortable.move(Chunk("a", "b", "c", "d"), Chunk("x", "y", "b"), request) ==
                    Result.Success((Chunk("a", "b", "c", "d"), Chunk("x", "y", "b", "d")))
            )
        }

        "rejects placement on an anchor" in {
            val request = move(Chunk("b"), Present("y"), Position.On)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("Sortable collections require Before or After placement."))
            )
        }

        "rejects placement inside an anchor" in {
            val request = move(Chunk("b"), Present("y"), Position.Inside)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("Sortable collections require Before or After placement."))
            )
        }
    }

end SortableTest
