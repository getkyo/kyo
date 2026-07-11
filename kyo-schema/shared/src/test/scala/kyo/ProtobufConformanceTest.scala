package kyo

import kyo.Protobuf.Conformance

// Float key is not a proto3-native map key (isProto3MapKey rejects PrimitiveKind.Float).
// Local to this conformance suite; not added to the shared ProtobufTest fixtures.
case class PB1716MapFloat(f: Map[Float, Int]) derives Schema, CanEqual

class ProtobufConformanceTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "Protobuf conformance" - {

        "INV-PBC-02-native-int: native Int key accepted under Strict default" in {
            val v     = PB1716MapInt(3, Map(10 -> 100))
            val bytes = Protobuf.encode(v)
            assert(bytes.nonEmpty)
            assert(Protobuf.decode[PB1716MapInt](bytes) == Result.Success(v))
        }

        "INV-PBC-02-product-key-rejected: product message key rejected under Strict default" in {
            val v      = PB1716VcKey(Map(Pid(1) -> "x"))
            val result = scala.util.Try(Protobuf.encode(v))
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[SchemaNotSerializableException])
        }

        "INV-PBC-02-float-key-rejected: Float key rejected under Strict default" in {
            val v      = PB1716MapFloat(Map(1.5f -> 1))
            val result = scala.util.Try(Protobuf.encode(v))
            assert(result.isFailure)
            assert(result.failed.get.isInstanceOf[SchemaNotSerializableException])
        }

        "INV-PBC-02s-opaque-native-allowed: opaque type reducing to native scalar allowed under Strict" in {
            val v     = PB1716OpaqueKey(Map(PB1716Ids.UserId(1) -> "x"))
            val bytes = Protobuf.encode(v)
            assert(Protobuf.decode[PB1716OpaqueKey](bytes) == Result.Success(v))
        }

        "INV-PBC-02-permissive-allows-noncanonical: Permissive accepts product key and round-trips" in {
            given Protobuf = Protobuf(Protobuf.Config(conformance = Conformance.Permissive))
            val v          = PB1716VcKey(Map(Pid(1) -> "x", Pid(2) -> "y"))
            val bytes      = Protobuf.encode(v)
            assert(Protobuf.decode[PB1716VcKey](bytes) == Result.Success(v))
        }

        "INV-PBC-02-rejection-detail-prefix: rejection carries stable message prefix" in {
            val v      = PB1716VcKey(Map(Pid(1) -> "x"))
            val result = scala.util.Try(Protobuf.encode(v))
            assert(result.isFailure)
            val ex = result.failed.get.asInstanceOf[SchemaNotSerializableException]
            assert(ex.detail.startsWith("non-canonical proto3 map key: "))
        }

        "INV-PBC-01-roundtrip-both-modes: canonical value encodes identically under Strict and Permissive" in {
            val v           = PB1716MapInt(3, Map(10 -> 100))
            val strictBytes = Protobuf.encode(v)
            assert(Protobuf.decode[PB1716MapInt](strictBytes) == Result.Success(v))
            // Permissive mode must not change the wire bytes for a canonical (native-key) value.
            {
                given Protobuf      = Protobuf(Protobuf.Config(conformance = Conformance.Permissive))
                val permissiveBytes = Protobuf.encode(v)
                assert(Protobuf.decode[PB1716MapInt](permissiveBytes) == Result.Success(v))
                assert(strictBytes.toArray.toSeq == permissiveBytes.toArray.toSeq)
            }
        }

    }

end ProtobufConformanceTest
