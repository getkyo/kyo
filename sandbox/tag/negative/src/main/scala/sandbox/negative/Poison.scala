package sandbox.negative

import kyo.Tag

// This project must NOT compile. Its single row is the cache-poisoning check: Tag[Long] is
// derived out of any scope first, then a real definition inside a scope asks for the same type.
// The correct macro refuses the second one with [Tag.opaque.collapsed]; a macro that consulted its
// memo before the scope check would serve the first encoding and this file would compile.
object Poison:
    object First:
        val long: Tag[Long] = Tag.derive[Long]
    object Second:
        opaque type D = Long
        object D:
            val long: Tag[Long] = Tag.derive[Long]
    end Second
end Poison
