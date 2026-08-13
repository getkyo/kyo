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

        "moves multiple keys within one collection in visible source order" in {
            val collection = Chunk("a", "b", "c", "d", "e")
            val request = move(
                Chunk("d", "b"),
                Present("e"),
                Position.After,
                source = sourceLocation,
                destination = sourceLocation
            )
            assert(
                Sortable.move(collection, collection, request) ==
                    Result.Success((Chunk("a", "c", "e", "b", "d"), Chunk("a", "c", "e", "b", "d")))
            )
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

        "prepends to a nonempty destination before an absent anchor" in {
            val request = move(Chunk("b"), Absent, Position.Before)
            assert(
                Sortable.move(Chunk("a", "b", "c"), Chunk("x", "y"), request) ==
                    Result.Success((Chunk("a", "c"), Chunk("b", "x", "y")))
            )
        }

        "appends to a nonempty destination after an absent anchor" in {
            val request = move(Chunk("b"), Absent, Position.After)
            assert(
                Sortable.move(Chunk("a", "b", "c"), Chunk("x", "y"), request) ==
                    Result.Success((Chunk("a", "c"), Chunk("x", "y", "b")))
            )
        }

        "moves a colliding destination key to the requested position" in {
            val request = move(Chunk("b"), Present("y"), Position.After)
            assert(
                Sortable.move(Chunk("a", "b", "c"), Chunk("x", "b", "y"), request) ==
                    Result.Success((Chunk("a", "c"), Chunk("x", "y", "b")))
            )
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

        "rejects a duplicate selected source key" in {
            val request = move(Chunk("a"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "a", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("Source collection keys must be unique."))
            )
        }

        "rejects a duplicate unselected source key" in {
            val request = move(Chunk("a"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b", "b"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("Source collection keys must be unique."))
            )
        }

        "rejects a missing key before duplicate source keys" in {
            val request = move(Chunk("a", "b"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "a"), Chunk("x", "y"), request) ==
                    Result.Failure(Rejection.Application("Every moving item must exist in the source collection."))
            )
        }

        "rejects a duplicate destination key" in {
            val request = move(Chunk("a"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "x", "y"), request) ==
                    Result.Failure(Rejection.Application("Destination collection keys must be unique."))
            )
        }

        "rejects a duplicate destination anchor" in {
            val request = move(Chunk("a"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "b"), Chunk("x", "y", "y"), request) ==
                    Result.Failure(Rejection.Application("Destination collection keys must be unique."))
            )
        }

        "ignores the destination argument for a same-location move" in {
            val request = move(
                Chunk("b"),
                Present("c"),
                Position.After,
                source = sourceLocation,
                destination = sourceLocation
            )
            assert(
                Sortable.move(Chunk("a", "b", "c"), Chunk("ignored", "ignored"), request) ==
                    Result.Success((Chunk("a", "c", "b"), Chunk("a", "c", "b")))
            )
        }

        "rejects source duplicates before destination duplicates" in {
            val request = move(Chunk("a"), Present("y"), Position.Before)
            assert(
                Sortable.move(Chunk("a", "a"), Chunk("y", "y"), request) ==
                    Result.Failure(Rejection.Application("Source collection keys must be unique."))
            )
        }

        "validation precedence" - {
            "rejects empty keys before later request errors" in {
                val request = move(Chunk.empty, Present("missing"), Position.On, Operation.Link)
                assert(
                    Sortable.move(Chunk("a", "a"), Chunk("y", "y"), request) ==
                        Result.Failure(Rejection.Application("At least one item must move."))
                )
            }

            "rejects duplicate requested keys before missing keys" in {
                val request = move(Chunk("missing", "missing"), Present("missing"), Position.Before)
                assert(
                    Sortable.move(Chunk("a", "a"), Chunk("y", "y"), request) ==
                        Result.Failure(Rejection.Application("Moving item keys must be unique."))
                )
            }

            "rejects missing keys before a selected anchor" in {
                val request = move(Chunk("missing"), Present("missing"), Position.Before)
                assert(
                    Sortable.move(Chunk("a", "a"), Chunk("y", "y"), request) ==
                        Result.Failure(Rejection.Application("Every moving item must exist in the source collection."))
                )
            }

            "rejects a selected anchor before a link operation" in {
                val request = move(Chunk("a"), Present("a"), Position.Before, Operation.Link)
                assert(
                    Sortable.move(Chunk("a", "a"), Chunk("a", "a"), request) ==
                        Result.Failure(Rejection.Application("The destination is part of the moving selection."))
                )
            }

            "rejects a link operation before invalid placement" in {
                val request = move(Chunk("a"), Present("missing"), Position.On, Operation.Link)
                assert(
                    Sortable.move(Chunk("a", "a"), Chunk("y", "y"), request) ==
                        Result.Failure(Rejection.Application("Sortable collections do not support link operations."))
                )
            }

            "rejects a same-collection copy before invalid placement" in {
                val request = move(
                    Chunk("a"),
                    Present("missing"),
                    Position.On,
                    Operation.Copy,
                    sourceLocation,
                    sourceLocation
                )
                assert(
                    Sortable.move(Chunk("a", "a"), Chunk("ignored"), request) ==
                        Result.Failure(
                            Rejection.Application("Copying within one keyed collection requires application-assigned destination keys.")
                        )
                )
            }

            "rejects invalid placement before a missing anchor" in {
                val request = move(Chunk("a"), Present("missing"), Position.Inside)
                assert(
                    Sortable.move(Chunk("a", "a"), Chunk("y", "y"), request) ==
                        Result.Failure(Rejection.Application("Sortable collections require Before or After placement."))
                )
            }

            "rejects a missing anchor before collection duplicates" in {
                val request = move(Chunk("a"), Present("missing"), Position.Before)
                assert(
                    Sortable.move(Chunk("a", "a"), Chunk("y", "y"), request) ==
                        Result.Failure(Rejection.Application("The destination anchor does not exist."))
                )
            }
        }
    }

end SortableTest
