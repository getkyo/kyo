package kyo

import Drag.*

class DragTest extends kyo.test.Test[Any]:

    "AllowedOperations" - {
        "constants contain exactly their named operations" in {
            assert(AllowedOperations.none == AllowedOperations(Set.empty))
            assert(AllowedOperations.copy == AllowedOperations(Set(Operation.Copy)))
            assert(AllowedOperations.move == AllowedOperations(Set(Operation.Move)))
            assert(AllowedOperations.link == AllowedOperations(Set(Operation.Link)))
            assert(AllowedOperations.all == AllowedOperations(Set(Operation.Copy, Operation.Move, Operation.Link)))
        }

        "allows only contained operations" in {
            assert(!AllowedOperations.none.allows(Operation.Copy))
            assert(AllowedOperations.copy.allows(Operation.Copy))
            assert(!AllowedOperations.copy.allows(Operation.Move))
            assert(AllowedOperations.all.allows(Operation.Copy))
            assert(AllowedOperations.all.allows(Operation.Move))
            assert(AllowedOperations.all.allows(Operation.Link))
        }
    }

    "Accept" - {
        "matches an exact text representation" in {
            val accept          = Accept.types("application/x-card", "text/plain")
            val result: Boolean = accept.accepts(Item.Text(Map("application/x-card" -> "card")))
            assert(result)
            assert(!accept.accepts(Item.Text(Map("application/x-other" -> "other"))))
        }

        "normalizes configured and transferred media types" in {
            val accept = Accept.types("  APPLICATION/X-CARD  ", " Text/Plain ")
            assert(accept.mediaTypes == Set("application/x-card", "text/plain"))
            assert(accept.accepts(Item.Text(Map(" APPLICATION/X-CARD " -> "card"))))
        }

        "matches a type wildcard" in {
            val accept = Accept.types("image/*")
            assert(accept.accepts(Item.Text(Map("image/png" -> "png"))))
            assert(!accept.accepts(Item.Text(Map("text/plain" -> "text"))))
        }

        "rejects text with no transfer representations" in {
            assert(!Accept().accepts(Item.Text(Map.empty)))
        }

        "does not enforce item count for a single item" in {
            val accept = Accept(maxItems = Present(0))
            assert(accept.accepts(Item.Text(Map("text/plain" -> "text"))))
        }

        "accepts URI items as text/uri-list" in {
            assert(Accept.types("text/uri-list").accepts(Item.Uri("https://getkyo.io")))
            assert(!Accept.types("text/plain").accepts(Item.Uri("https://getkyo.io")))
        }

        "checks file media type and size" in {
            val file = Item.File(FileMeta("token", "card.bin", " APPLICATION/X-CARD ", 65.kib, Instant.Epoch))
            val accept = Accept(
                mediaTypes = Set("application/x-card"),
                operations = AllowedOperations.copy,
                maxFileSize = Present(64.kib)
            )
            assert(accept.operations == AllowedOperations.copy)
            assert(!accept.accepts(file))
            assert(!Accept.types("application/x-other").accepts(file))
            assert(Accept.types("application/*").accepts(file))
        }

        "checks directory acceptance" in {
            val directory = Item.Directory("token", "assets")
            assert(!Accept().accepts(directory))
            assert(Accept(directories = true).accepts(directory))
        }
    }

    "Move" - {
        "preserves selected key order and stable anchor" in {
            val move = Move(
                keys = Chunk("2", "5"),
                source = Location("backlog"),
                destination = Location("done"),
                anchor = Present("8"),
                position = Position.Before,
                operation = Operation.Move
            )
            assert(move.keys == Chunk("2", "5"))
            assert(move.source == Location("backlog"))
            assert(move.destination == Location("done"))
            assert(move.anchor == Present("8"))
            assert(move.position == Position.Before)
            assert(move.operation == Operation.Move)
        }
    }

end DragTest
