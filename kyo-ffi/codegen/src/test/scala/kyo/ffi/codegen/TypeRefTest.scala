package kyo.ffi.codegen

import kyo.ffi.codegen.model.*

class TypeRefTest extends kyo.test.Test[Any]:

    "isPrimitive" - {
        "returns true for all primitive TypeRefs" in {
            List(
                TypeRef.BooleanT,
                TypeRef.ByteT,
                TypeRef.ShortT,
                TypeRef.IntT,
                TypeRef.LongT,
                TypeRef.FloatT,
                TypeRef.DoubleT,
                TypeRef.UnitT
            ).foreach(t => assert(TypeRef.isPrimitive(t) == true, s"expected isPrimitive($t) = true"))
        }

        "returns false for non-primitive TypeRefs" in {
            List(
                TypeRef.StringT,
                TypeRef.GuardT,
                TypeRef.ArrayT(TypeRef.IntT),
                TypeRef.BufferT(TypeRef.ByteT),
                TypeRef.StructT("com.example.Foo"),
                TypeRef.FnPtrT(Nil, TypeRef.UnitT)
            ).foreach(t => assert(TypeRef.isPrimitive(t) == false, s"expected isPrimitive($t) = false"))
        }
    }

    "TypeRef case classes support equality and nesting" in {
        val nested = TypeRef.ArrayT(TypeRef.ArrayT(TypeRef.IntT))
        assert(nested == TypeRef.ArrayT(TypeRef.ArrayT(TypeRef.IntT)))
        val fn = TypeRef.FnPtrT(List(TypeRef.IntT, TypeRef.StringT), TypeRef.BooleanT)
        assert(fn.params.size == 2)
        assert(fn.ret == TypeRef.BooleanT)
    }
end TypeRefTest
