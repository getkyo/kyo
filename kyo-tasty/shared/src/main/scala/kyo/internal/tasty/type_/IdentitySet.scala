package kyo.internal.tasty.type_

import kyo.discard
import scala.collection.mutable

/** A mutable set that uses reference identity (eq) for membership tests.
  *
  * Used by TypeKey.computeHash as the in-progress cycle-detection guard. The set must use reference identity, not structural equality, to
  * avoid calling hashCode on deeply nested Tasty.Type values (which would overflow the JVM call stack for Applied chains at MaxDepth-1
  * levels under scoverage instrumentation).
  *
  * The set is used as a call-stack depth tracker: one element is added on entry to computeHash and removed on exit. Maximum size equals
  * the current recursion depth. For well-formed type trees (non-cyclic, bounded depth) this is typically small. For the pathological
  * MaxDepth-1 test, the size reaches MaxDepth-1 and contains/remove take O(depth) each (linear scan), so total work across one type
  * walk is O(depth^2). This is acceptable because MaxDepth-1 level types are extreme test fixtures, not normal input.
  */
final private[type_] class IdentitySet[A <: AnyRef]:
    private val entries = new mutable.ArrayBuffer[A](16)

    def contains(a: A): Boolean =
        var i = 0
        while i < entries.size do
            if entries(i) eq a then return true
            i += 1
        end while
        false
    end contains

    def add(a: A): Boolean =
        if contains(a) then false
        else
            entries.addOne(a)
            true

    def remove(a: A): Boolean =
        var i = 0
        while i < entries.size do
            if entries(i) eq a then
                discard(entries.remove(i))
                return true
            i += 1
        end while
        false
    end remove

end IdentitySet
