package kyo.internal.charset

import kyo.charset.Charset

/** One decoder per charset, shared: the single-byte decoders are created lazily so a charset's index table is decoded from its literals
  * only when a program first decodes in it.
  */
private[kyo] object Decoders:

    def of(charset: Charset): Decoder =
        charset match
            case Charset.Utf8         => Utf8
            case Charset.Ibm866       => SingleByte.ibm866
            case Charset.Iso8859_2    => SingleByte.iso8859_2
            case Charset.Iso8859_3    => SingleByte.iso8859_3
            case Charset.Iso8859_4    => SingleByte.iso8859_4
            case Charset.Iso8859_5    => SingleByte.iso8859_5
            case Charset.Iso8859_6    => SingleByte.iso8859_6
            case Charset.Iso8859_7    => SingleByte.iso8859_7
            case Charset.Iso8859_8    => SingleByte.iso8859_8
            case Charset.Iso8859_8I   => SingleByte.iso8859_8I
            case Charset.Iso8859_10   => SingleByte.iso8859_10
            case Charset.Iso8859_13   => SingleByte.iso8859_13
            case Charset.Iso8859_14   => SingleByte.iso8859_14
            case Charset.Iso8859_15   => SingleByte.iso8859_15
            case Charset.Iso8859_16   => SingleByte.iso8859_16
            case Charset.Koi8R        => SingleByte.koi8R
            case Charset.Koi8U        => SingleByte.koi8U
            case Charset.Macintosh    => SingleByte.macintosh
            case Charset.Windows874   => SingleByte.windows874
            case Charset.Windows1250  => SingleByte.windows1250
            case Charset.Windows1251  => SingleByte.windows1251
            case Charset.Windows1252  => SingleByte.windows1252
            case Charset.Windows1253  => SingleByte.windows1253
            case Charset.Windows1254  => SingleByte.windows1254
            case Charset.Windows1255  => SingleByte.windows1255
            case Charset.Windows1256  => SingleByte.windows1256
            case Charset.Windows1257  => SingleByte.windows1257
            case Charset.Windows1258  => SingleByte.windows1258
            case Charset.XMacCyrillic => SingleByte.xMacCyrillic
            case Charset.Utf16Be      => Utf16Decoder.bigEndian
            case Charset.Utf16Le      => Utf16Decoder.littleEndian
            case Charset.Utf16        => Utf16Decoder.byteOrderMark
            case Charset.Utf32Be      => Utf32Decoder.bigEndian
            case Charset.Utf32Le      => Utf32Decoder.littleEndian
            case Charset.Utf32        => Utf32Decoder.byteOrderMark
            case Charset.Gb18030      => Gb18030Decoder.gb18030
            case Charset.Gbk          => Gb18030Decoder.gbk
            case Charset.Big5         => Big5Decoder
            case Charset.EucJp        => EucJpDecoder
            case Charset.ShiftJis     => ShiftJisDecoder
            case Charset.EucKr        => EucKrDecoder
            case Charset.Iso2022Jp    => Iso2022JpDecoder
            case Charset.Iso2022Kr    => Iso2022KrDecoder
            case Charset.HzGb2312     => HzGb2312Decoder
            case Charset.Utf7         => Utf7Decoder

    private object SingleByte:
        lazy val ibm866: Decoder       = new SingleByteDecoder(Charset.Ibm866, IndexIbm866.table)
        lazy val iso8859_2: Decoder    = new SingleByteDecoder(Charset.Iso8859_2, IndexIso8859_2.table)
        lazy val iso8859_3: Decoder    = new SingleByteDecoder(Charset.Iso8859_3, IndexIso8859_3.table)
        lazy val iso8859_4: Decoder    = new SingleByteDecoder(Charset.Iso8859_4, IndexIso8859_4.table)
        lazy val iso8859_5: Decoder    = new SingleByteDecoder(Charset.Iso8859_5, IndexIso8859_5.table)
        lazy val iso8859_6: Decoder    = new SingleByteDecoder(Charset.Iso8859_6, IndexIso8859_6.table)
        lazy val iso8859_7: Decoder    = new SingleByteDecoder(Charset.Iso8859_7, IndexIso8859_7.table)
        lazy val iso8859_8: Decoder    = new SingleByteDecoder(Charset.Iso8859_8, IndexIso8859_8.table)
        lazy val iso8859_8I: Decoder   = new SingleByteDecoder(Charset.Iso8859_8I, IndexIso8859_8.table)
        lazy val iso8859_10: Decoder   = new SingleByteDecoder(Charset.Iso8859_10, IndexIso8859_10.table)
        lazy val iso8859_13: Decoder   = new SingleByteDecoder(Charset.Iso8859_13, IndexIso8859_13.table)
        lazy val iso8859_14: Decoder   = new SingleByteDecoder(Charset.Iso8859_14, IndexIso8859_14.table)
        lazy val iso8859_15: Decoder   = new SingleByteDecoder(Charset.Iso8859_15, IndexIso8859_15.table)
        lazy val iso8859_16: Decoder   = new SingleByteDecoder(Charset.Iso8859_16, IndexIso8859_16.table)
        lazy val koi8R: Decoder        = new SingleByteDecoder(Charset.Koi8R, IndexKoi8R.table)
        lazy val koi8U: Decoder        = new SingleByteDecoder(Charset.Koi8U, IndexKoi8U.table)
        lazy val macintosh: Decoder    = new SingleByteDecoder(Charset.Macintosh, IndexMacintosh.table)
        lazy val windows874: Decoder   = new SingleByteDecoder(Charset.Windows874, IndexWindows874.table)
        lazy val windows1250: Decoder  = new SingleByteDecoder(Charset.Windows1250, IndexWindows1250.table)
        lazy val windows1251: Decoder  = new SingleByteDecoder(Charset.Windows1251, IndexWindows1251.table)
        lazy val windows1252: Decoder  = new SingleByteDecoder(Charset.Windows1252, IndexWindows1252.table)
        lazy val windows1253: Decoder  = new SingleByteDecoder(Charset.Windows1253, IndexWindows1253.table)
        lazy val windows1254: Decoder  = new SingleByteDecoder(Charset.Windows1254, IndexWindows1254.table)
        lazy val windows1255: Decoder  = new SingleByteDecoder(Charset.Windows1255, IndexWindows1255.table)
        lazy val windows1256: Decoder  = new SingleByteDecoder(Charset.Windows1256, IndexWindows1256.table)
        lazy val windows1257: Decoder  = new SingleByteDecoder(Charset.Windows1257, IndexWindows1257.table)
        lazy val windows1258: Decoder  = new SingleByteDecoder(Charset.Windows1258, IndexWindows1258.table)
        lazy val xMacCyrillic: Decoder = new SingleByteDecoder(Charset.XMacCyrillic, IndexXMacCyrillic.table)
    end SingleByte

end Decoders
