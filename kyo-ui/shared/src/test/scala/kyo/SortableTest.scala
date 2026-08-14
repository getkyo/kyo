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

    "expandSelection" - {
        "expands to the whole selection in visible order when every dragged key is selected" in {
            val expanded = Sortable.expandSelection(Chunk("a", "b", "c", "d"), Set("d", "a"), Chunk("d"))
            assert(expanded == Chunk("a", "d"))
        }

        "keeps the dragged keys when any dragged key is outside the selection" in {
            val expanded = Sortable.expandSelection(Chunk("a", "b", "c"), Set("a"), Chunk("b"))
            assert(expanded == Chunk("b"))
        }

        "keeps empty dragged keys empty" in {
            assert(Sortable.expandSelection(Chunk("a", "b"), Set("a"), Chunk.empty) == Chunk.empty)
        }
    }

    "moveBy" - {
        case class Item(id: String, label: String) derives CanEqual

        val items = Chunk(Item("a", "Anvil"), Item("b", "Rope"), Item("c", "Magnet"))

        "reorders typed values within one collection" in {
            val request = move(Chunk("c"), Present("a"), Position.Before, source = sourceLocation, destination = sourceLocation)
            val result  = Sortable.moveBy(items, items, request)(_.id)
            assert(result == Result.Success((
                Chunk(Item("c", "Magnet"), Item("a", "Anvil"), Item("b", "Rope")),
                Chunk(Item("c", "Magnet"), Item("a", "Anvil"), Item("b", "Rope"))
            )))
        }

        "moves typed values across collections carrying the source values" in {
            val destination = Chunk(Item("x", "Crate"))
            val request     = move(Chunk("b"), Absent, Position.After)
            val result      = Sortable.moveBy(items, destination, request)(_.id)
            assert(result == Result.Success((
                Chunk(Item("a", "Anvil"), Item("c", "Magnet")),
                Chunk(Item("x", "Crate"), Item("b", "Rope"))
            )))
        }

        "rejects a locked moving key without mutation" in {
            val request = move(Chunk("a"), Absent, Position.After, source = sourceLocation, destination = sourceLocation)
            val result  = Sortable.moveBy(items, items, request, locked = Set("a"))(_.id)
            assert(result == Result.Failure(Rejection.Application("Locked keys cannot move or anchor a move.")))
        }

        "rejects a locked anchor without mutation" in {
            val request = move(Chunk("b"), Present("a"), Position.Before, source = sourceLocation, destination = sourceLocation)
            val result  = Sortable.moveBy(items, items, request, locked = Set("a"))(_.id)
            assert(result == Result.Failure(Rejection.Application("Locked keys cannot move or anchor a move.")))
        }

        "propagates engine rejections unchanged" in {
            val request = move(Chunk("missing"), Absent, Position.After)
            val result  = Sortable.moveBy(items, Chunk.empty[Item], request)(_.id)
            assert(result == Result.Failure(Rejection.Application("Every moving item must exist in the source collection.")))
        }
    }

    "moveGroups" - {
        val lanes = Chunk(
            "todo"  -> Chunk("1", "2"),
            "doing" -> Chunk("3"),
            "done"  -> Chunk("4")
        )

        def laneMove(
            keys: Chunk[String],
            source: String,
            destination: String,
            anchor: Maybe[String] = Absent,
            position: Position = Position.After
        ): Move =
            Move(keys, Location(source), Location(destination), anchor, position, Operation.Move)

        "reorders within one group" in {
            val result = Sortable.moveGroups(lanes, laneMove(Chunk("2"), "todo", "todo", Present("1"), Position.Before))
            assert(result == Result.Success(Chunk(
                "todo"  -> Chunk("2", "1"),
                "doing" -> Chunk("3"),
                "done"  -> Chunk("4")
            )))
        }

        "moves keys spanning groups contiguously at the destination anchor" in {
            val result = Sortable.moveGroups(lanes, laneMove(Chunk("2", "3"), "doing", "done", Present("4"), Position.Before))
            assert(result == Result.Success(Chunk(
                "todo"  -> Chunk("1"),
                "doing" -> Chunk.empty[String],
                "done"  -> Chunk("2", "3", "4")
            )))
        }

        "rejects an unknown destination group" in {
            val result = Sortable.moveGroups(lanes, laneMove(Chunk("1"), "todo", "archive"))
            assert(result == Result.Failure(Rejection.Application("Unknown collection: archive")))
        }

        "rejects a key missing from every group" in {
            val result = Sortable.moveGroups(lanes, laneMove(Chunk("9"), "todo", "done"))
            assert(result == Result.Failure(Rejection.Application("Every moving item must exist in the source collections.")))
        }

        "rejects an anchor inside the moving keys across groups" in {
            val result = Sortable.moveGroups(lanes, laneMove(Chunk("1", "3"), "todo", "done", Present("3"), Position.Before))
            assert(result == Result.Failure(Rejection.Application("The destination is part of the moving selection.")))
        }

        "rejects locked keys and locked anchors" in {
            val moved = Sortable.moveGroups(lanes, laneMove(Chunk("1"), "todo", "done"), locked = Set("1"))
            val anchored =
                Sortable.moveGroups(lanes, laneMove(Chunk("1"), "todo", "done", Present("4"), Position.Before), locked = Set("4"))
            assert(moved == Result.Failure(Rejection.Application("Locked keys cannot move or anchor a move.")))
            assert(anchored == Result.Failure(Rejection.Application("Locked keys cannot move or anchor a move.")))
        }

        "rejects duplicate moving keys" in {
            val result = Sortable.moveGroups(lanes, laneMove(Chunk("1", "1"), "todo", "done"))
            assert(result == Result.Failure(Rejection.Application("Moving item keys must be unique.")))
        }
    }

    "Decision.fromResult" - {
        "commits and accepts on success" in {
            for
                committed <- AtomicRef.init(Absent: Maybe[Int])
                decision  <- Decision.fromResult(Result.succeed[Rejection, Int](42))(v => committed.set(Present(v)))
                value     <- committed.get
            yield
                assert(decision == Decision.Accept)
                assert(value == Present(42))
        }

        "rejects without committing on failure" in {
            for
                committed <- AtomicRef.init(false)
                decision  <- Decision.fromResult(Result.fail[Rejection, Int](Rejection.Application("no")))(_ => committed.set(true))
                value     <- committed.get
            yield
                assert(decision == Decision.Reject(Rejection.Application("no")))
                assert(!value)
        }
    }

    "sortable constructors" - {
        "a sortable source payload is accepted by a sortable target" in {
            val source = Source.sortable("card-1", Present("Card one"))
            val target = Target.sortable("lane", Present("Lane"))
            assert(source.key == "card-1")
            assert(source.label == Present("Card one"))
            assert(target.key == "lane")
            assert(source.items.size == 1)
            assert(source.items.forall(target.accepts.accepts))
        }

        "a sortable target rejects plain text payloads" in {
            val target = Target.sortable("lane")
            val plain  = Item.Text(Map(MediaType.parse("text/plain").get -> "value"))
            assert(!target.accepts.accepts(plain))
        }
    }

end SortableTest
