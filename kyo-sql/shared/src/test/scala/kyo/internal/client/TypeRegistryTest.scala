package kyo.internal.client

import kyo.Absent
import kyo.Present
import kyo.Test
import kyo.internal.client.TypeRegistry.*

class TypeRegistryTest extends Test:

    "TypeRegistry.empty has no entries" in {
        assert(TypeRegistry.empty.isEmpty)
    }

    "TypeRegistry.empty.lookup returns Absent for any name" in {
        assert(TypeRegistry.empty.lookup("foo") == Absent)
    }

    "TypeRegistry construction from a map preserves entries" in {
        val reg = TypeRegistry(Map("hstore" -> 1234, "geometry" -> 5678))
        assert(reg.size == 2)
        assert(reg("hstore") == 1234)
        assert(reg("geometry") == 5678)
    }

    "TypeRegistry.lookup returns Present(oid) for a known type name" in {
        val reg = TypeRegistry(Map("hstore" -> 1234))
        assert(reg.lookup("hstore") == Present(1234))
    }

    "TypeRegistry.lookup returns Absent for an unknown type name" in {
        val reg = TypeRegistry(Map("hstore" -> 1234))
        assert(reg.lookup("missing") == Absent)
    }

    "TypeRegistry is a plain Map — standard Map operations work" in {
        val reg = TypeRegistry(Map("int4" -> 23, "text" -> 25))
        assert(reg.keySet == Set("int4", "text"))
        assert(reg.values.toSet == Set(23, 25))
    }

end TypeRegistryTest
