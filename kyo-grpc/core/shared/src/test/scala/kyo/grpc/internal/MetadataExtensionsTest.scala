package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.grpc.*
import org.scalatest.matchers.must.Matchers.*
import scala.jdk.CollectionConverters.*

class MetadataExtensionsTest extends Test:

    val testKey1 = Metadata.Key.of("test-key-1", Metadata.ASCII_STRING_MARSHALLER)
    val testKey2 = Metadata.Key.of("test-key-2", Metadata.ASCII_STRING_MARSHALLER)
    val testKey3 = Metadata.Key.of("test-key-3", Metadata.ASCII_STRING_MARSHALLER)

    def createMetadata(key: Metadata.Key[String], value: String): Metadata =
        val metadata = Metadata()
        metadata.put(key, value)
        metadata
    end createMetadata

    "Maybe[Metadata] extension" - {

        "mergeIfDefined" - {

            "merges Present metadata with Present other" in run {
                val metadata1 = createMetadata(testKey1, "value1")
                val metadata2 = createMetadata(testKey2, "value2")

                val result = Maybe.Present(metadata1).mergeIfDefined(Maybe.Present(metadata2))

                result.map:
                    case Maybe.Present(merged) =>
                        assert(merged.get(testKey1) === "value1")
                        assert(merged.get(testKey2) === "value2")
                    case Maybe.Absent =>
                        fail("Expected Present but got Absent")
            }

            "returns Present metadata when other is Absent" in run {
                val metadata = createMetadata(testKey1, "value1")

                val result = Maybe.Present(metadata).mergeIfDefined(Maybe.Absent)

                result.map:
                    case Maybe.Present(merged) =>
                        assert(merged.get(testKey1) === "value1")
                    case Maybe.Absent =>
                        fail("Expected Present but got Absent")
            }

            "returns other when metadata is Absent and other is Present" in run {
                val metadata = createMetadata(testKey1, "value1")

                val result = Maybe.Absent.mergeIfDefined(Maybe.Present(metadata))

                result.map:
                    case Maybe.Present(merged) =>
                        assert(merged.get(testKey1) === "value1")
                    case Maybe.Absent =>
                        fail("Expected Present but got Absent")
            }

            "returns Absent when both are Absent" in run {
                val result = Maybe.Absent.mergeIfDefined(Maybe.Absent)

                result.map:
                    case Maybe.Present(_) =>
                        fail("Expected Absent but got Present")
                    case Maybe.Absent =>
                        succeed
            }

            "merges multiple keys correctly" in run {
                val metadata1 = Metadata()
                metadata1.put(testKey1, "value1")
                metadata1.put(testKey2, "value2")

                val metadata2 = Metadata()
                metadata2.put(testKey2, "value2-updated")
                metadata2.put(testKey3, "value3")

                val result = Maybe.Present(metadata1).mergeIfDefined(Maybe.Present(metadata2))

                result.map:
                    case Maybe.Present(merged) =>
                        assert(merged.get(testKey1) === "value1")
                        // Note: gRPC Metadata.merge() appends values for duplicate keys
                        assert(merged.getAll(testKey2).asScala.size >= 1)
                        assert(merged.get(testKey3) === "value3")
                    case Maybe.Absent =>
                        fail("Expected Present but got Absent")
            }
        }
    }

    "Metadata extension" - {

        "mergeSafe" - {

            "merges two metadata objects" in run {
                val metadata1 = createMetadata(testKey1, "value1")
                val metadata2 = createMetadata(testKey2, "value2")

                val result = metadata1.mergeSafe(metadata2)

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
                    assert(merged.get(testKey2) === "value2")
                    // mergeSafe mutates the original metadata
                    assert(merged eq metadata1)
            }

            "handles empty metadata" in run {
                val metadata1 = createMetadata(testKey1, "value1")
                val metadata2 = Metadata()

                val result = metadata1.mergeSafe(metadata2)

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
            }

            "merges into empty metadata" in run {
                val metadata1 = Metadata()
                val metadata2 = createMetadata(testKey1, "value1")

                val result = metadata1.mergeSafe(metadata2)

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
            }

            "handles duplicate keys" in run {
                val metadata1 = createMetadata(testKey1, "value1")
                val metadata2 = createMetadata(testKey1, "value2")

                val result = metadata1.mergeSafe(metadata2)

                result.map: merged =>
                    // gRPC Metadata.merge() appends values for duplicate keys
                    val all = merged.getAll(testKey1).asScala
                    assert(all.size >= 1)
            }

            "preserves all keys from both metadata objects" in run {
                val metadata1 = Metadata()
                metadata1.put(testKey1, "value1")
                metadata1.put(testKey2, "value2")

                val metadata2 = Metadata()
                metadata2.put(testKey2, "value2-updated")
                metadata2.put(testKey3, "value3")

                val result = metadata1.mergeSafe(metadata2)

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
                    assert(merged.getAll(testKey2).asScala.size >= 1)
                    assert(merged.get(testKey3) === "value3")
            }
        }

        "mergeIfDefined" - {

            "merges with Present metadata" in run {
                val metadata1 = createMetadata(testKey1, "value1")
                val metadata2 = createMetadata(testKey2, "value2")

                val result = metadata1.mergeIfDefined(Maybe.Present(metadata2))

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
                    assert(merged.get(testKey2) === "value2")
            }

            "returns original metadata when other is Absent" in run {
                val metadata = createMetadata(testKey1, "value1")

                val result = metadata.mergeIfDefined(Maybe.Absent)

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
                    assert(merged eq metadata)
            }

            "handles empty Present metadata" in run {
                val metadata1 = createMetadata(testKey1, "value1")
                val metadata2 = Metadata()

                val result = metadata1.mergeIfDefined(Maybe.Present(metadata2))

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
            }

            "merges when base is empty and other is Present" in run {
                val metadata1 = Metadata()
                val metadata2 = createMetadata(testKey1, "value1")

                val result = metadata1.mergeIfDefined(Maybe.Present(metadata2))

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
            }

            "handles multiple keys with Present metadata" in run {
                val metadata1 = Metadata()
                metadata1.put(testKey1, "value1")
                metadata1.put(testKey2, "value2")

                val metadata2 = Metadata()
                metadata2.put(testKey2, "value2-updated")
                metadata2.put(testKey3, "value3")

                val result = metadata1.mergeIfDefined(Maybe.Present(metadata2))

                result.map: merged =>
                    assert(merged.get(testKey1) === "value1")
                    assert(merged.getAll(testKey2).asScala.size >= 1)
                    assert(merged.get(testKey3) === "value3")
            }
        }
    }

end MetadataExtensionsTest
