package kyo

// JVM-only: Java object serialization (java.io.ObjectOutputStream / ObjectInputStream)
// has no equivalent on Scala.js or Scala Native.
class STMSerializationJvmTest extends Test:

    "TRef" - {

        "serializes and deserializes preserving the committed value" in run {
            for
                ref0 <- TRef.init(42)
                bytes <- Sync.defer {
                    val baos = new java.io.ByteArrayOutputStream()
                    val oos  = new java.io.ObjectOutputStream(baos)
                    oos.writeObject(ref0)
                    oos.close()
                    baos.toByteArray
                }
                ref1 <- Sync.defer {
                    val ois = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))
                    ois.readObject().asInstanceOf[TRef[Int]]
                }
                v <- STM.run(ref1.get)
            yield assert(v == 42, "deserialized TRef must yield original value via STM.run(ref.get)")
        }
    }
end STMSerializationJvmTest
