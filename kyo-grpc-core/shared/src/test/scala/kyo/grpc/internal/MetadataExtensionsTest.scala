package kyo.grpc.internal

import kyo.*
import kyo.grpc.*
import org.scalactic.TripleEquals.*

class MetadataExtensionsTest extends Test:

    "Maybe[SafeMetadata] extension" - {

        "mergeIfDefined" - {

            "merges Present metadata with Present other" in run {
                val m1 = SafeMetadata.empty.add("key1", "value1")
                val m2 = SafeMetadata.empty.add("key2", "value2")

                Maybe.Present(m1).mergeIfDefined(Maybe.Present(m2)).map:
                    case Maybe.Present(merged) =>
                        assert(merged.getStrings("key1") === Seq("value1"))
                        assert(merged.getStrings("key2") === Seq("value2"))
                        succeed
                    case Maybe.Absent =>
                        fail("Expected Present but got Absent")
            }

            "returns Present metadata when other is Absent" in run {
                val m1 = SafeMetadata.empty.add("key1", "value1")

                Maybe.Present(m1).mergeIfDefined(Maybe.Absent).map:
                    case Maybe.Present(merged) =>
                        assert(merged.getStrings("key1") === Seq("value1"))
                        succeed
                    case Maybe.Absent =>
                        fail("Expected Present but got Absent")
            }

            "returns other when metadata is Absent and other is Present" in run {
                val m2 = SafeMetadata.empty.add("key1", "value1")

                (Maybe.Absent: Maybe[SafeMetadata]).mergeIfDefined(Maybe.Present(m2)).map:
                    case Maybe.Present(merged) =>
                        assert(merged.getStrings("key1") === Seq("value1"))
                        succeed
                    case Maybe.Absent =>
                        fail("Expected Present but got Absent")
            }

            "returns Absent when both are Absent" in run {
                (Maybe.Absent: Maybe[SafeMetadata]).mergeIfDefined(Maybe.Absent).map:
                    case Maybe.Present(_) =>
                        fail("Expected Absent but got Present")
                    case Maybe.Absent =>
                        succeed
            }
        }
    }

    "SafeMetadata" - {

        "fromJava and toJava round-trip" in {
            val sm   = SafeMetadata.empty.add("test-key", "test-value")
            val java = sm.toJava
            val back = SafeMetadata.fromJava(java)
            assert(back.getStrings("test-key") === Seq("test-value"))
            succeed
        }

        "merge combines entries" in {
            val m1     = SafeMetadata.empty.add("key1", "value1")
            val m2     = SafeMetadata.empty.add("key2", "value2")
            val merged = m1.merge(m2)
            assert(merged.getStrings("key1") === Seq("value1"))
            assert(merged.getStrings("key2") === Seq("value2"))
            succeed
        }

        "merge appends duplicate keys" in {
            val m1     = SafeMetadata.empty.add("key1", "value1")
            val m2     = SafeMetadata.empty.add("key1", "value2")
            val merged = m1.merge(m2)
            assert(merged.getStrings("key1") === Seq("value1", "value2"))
            succeed
        }
    }

end MetadataExtensionsTest
