package kyo.internal

/** The compression primitives `StreamCompression` drives, backed by `PortableZip`.
  *
  * Scala Native has no `java.util.zip` to delegate to, and the codec is Scala, so a native binary runs the same one a page does, with no
  * host binding, and the four methods keep the effect row they have on the JVM.
  */
private[kyo] object ZipCodec:

    type DataFormatException = PortableZip.DataFormatException

    export PortableZip.Crc32
    export PortableZip.Deflater
    export PortableZip.Inflater
end ZipCodec
