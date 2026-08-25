package kyo.ffi.codegen.emitters

import EmitterFixtures.*
import kyo.ffi.codegen.model.*

class ReachabilityMetadataEmitterTest extends kyo.test.Test[Any]:

    "registers the generated impl for reflective instantiation" in {
        val spec = mkTrait("Sockets", "kyo_net", List(mkMethod("noop", "noop", Nil, ReturnShape.Void)))
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""type": "kyo.example.SocketsImpl""""))
        assert(json.contains(""""name": "<init>""""))
        assert(json.contains(""""reflection""""))
    }

    "maps primitive and pointer parameters to foreign tokens with errno capture" in {
        val spec = mkTrait(
            "Sockets",
            "kyo_net",
            List(
                mkMethod(
                    "recv",
                    "recv",
                    List(
                        ParamSpec("fd", TypeRef.IntT),
                        ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT)),
                        ParamSpec("len", TypeRef.IntT)
                    ),
                    ReturnShape.Primitive(TypeRef.LongT)
                )
            )
        )
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""returnType": "jlong""""))
        assert(json.contains(""""parameterTypes": ["jint", "void*", "jint"]"""))
        assert(json.contains(""""captureCallState": true"""))
    }

    "emits the void token for a void return" in {
        val spec =
            mkTrait("Sockets", "kyo_net", List(mkMethod("close", "close", List(ParamSpec("fd", TypeRef.IntT)), ReturnShape.Void)))
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""returnType": "void""""))
        assert(json.contains(""""parameterTypes": ["jint"]"""))
    }

    "a non-blocking array method adds the critical option" in {
        val spec = mkTrait(
            "Sockets",
            "kyo_net",
            List(
                mkMethod(
                    "writeAll",
                    "write_all",
                    List(ParamSpec("buf", TypeRef.ArrayT(TypeRef.ByteT))),
                    ReturnShape.Primitive(TypeRef.IntT)
                )
            )
        )
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""critical": { "allowHeapAccess": true }"""))
    }

    "a zero-parameter primitive method emits an empty parameter list and both sections" in {
        val spec = mkTrait("Sockets", "kyo_net", List(mkMethod("getpid", "getpid", Nil, ReturnShape.Primitive(TypeRef.IntT))))
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""foreign""""))
        assert(json.contains(""""downcalls""""))
        assert(json.contains(""""returnType": "jint", "parameterTypes": []"""))
    }

end ReachabilityMetadataEmitterTest
