package kyo.internal

/** The compression primitives `StreamCompression` drives, backed by `PortableZip`.
  *
  * Neither host has `java.util.zip`, and neither needs a host binding: the codec is Scala, so a page and a Node process and a native binary
  * all run the same one, and the four methods keep the effect row they have on the JVM.
  */
private[kyo] object ZipCodec:

    type DataFormatException = PortableZip.DataFormatException

    export PortableZip.Crc32
    export PortableZip.Deflater
    export PortableZip.Inflater
end ZipCodec
