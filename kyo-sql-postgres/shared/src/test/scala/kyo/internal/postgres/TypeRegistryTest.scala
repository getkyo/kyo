package kyo.internal.postgres

import kyo.Test

class TypeRegistryTest extends Test:

    "TypeRegistry.empty has no entries" in {
        assert(TypeRegistry.empty.isEmpty)
    }

    "TypeRegistry construction from a map preserves entries" in {
        val reg = TypeRegistry(Map("hstore" -> 1234, "geometry" -> 5678))
        assert(reg.size == 2)
        assert(reg("hstore") == 1234)
        assert(reg("geometry") == 5678)
    }

    "TypeRegistry is a plain Map, standard Map operations work" in {
        val reg = TypeRegistry(Map("int4" -> 23, "text" -> 25))
        assert(reg.keySet == Set("int4", "text"))
        assert(reg.values.toSet == Set(23, 25))
    }

end TypeRegistryTest
