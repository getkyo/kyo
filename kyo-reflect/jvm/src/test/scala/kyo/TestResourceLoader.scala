package kyo

/** JVM implementation: loads bytes from classloader resources. */
object TestResourceLoader:

    def loadBytes(resourcePath: String): Array[Byte] =
        val is = getClass.getClassLoader.getResourceAsStream(resourcePath.stripPrefix("/"))
        if is != null then
            val buf = new scala.collection.mutable.ArrayBuffer[Byte]()
            var b   = is.read()
            while b != -1 do
                buf += b.toByte
                b = is.read()
            is.close()
            buf.toArray
        else
            val is2 = getClass.getResourceAsStream(resourcePath)
            if is2 == null then throw new RuntimeException(s"Resource not found: $resourcePath")
            val buf = new scala.collection.mutable.ArrayBuffer[Byte]()
            var b   = is2.read()
            while b != -1 do
                buf += b.toByte
                b = is2.read()
            is2.close()
            buf.toArray
        end if
    end loadBytes

end TestResourceLoader
