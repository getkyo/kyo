package jsonbench

import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

class JsonEncodeBench extends BaseBench:

    @Param(Array("small", "nested", "wide", "collection"))
    var payload: String = uninitialized

    private var encodeKyo: () => String       = uninitialized
    private var encodeJsoniter: () => String  = uninitialized
    private var encodeZioJson: () => String   = uninitialized
    private var encodeCirce: () => String     = uninitialized
    private var encodeZioBlocks: () => String = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        payload match
            case "small" =>
                encodeKyo = () => JsonBenchData.kyoEncodeSmall()
                encodeJsoniter = () => JsonBenchData.jsoniterEncodeSmall()
                encodeZioJson = () => JsonBenchData.zioEncodeSmall()
                encodeCirce = () => JsonBenchData.circeEncodeSmall()
                encodeZioBlocks = () => JsonBenchData.zbEncodeSmall()
            case "nested" =>
                encodeKyo = () => JsonBenchData.kyoEncodeNested()
                encodeJsoniter = () => JsonBenchData.jsoniterEncodeNested()
                encodeZioJson = () => JsonBenchData.zioEncodeNested()
                encodeCirce = () => JsonBenchData.circeEncodeNested()
                encodeZioBlocks = () => JsonBenchData.zbEncodeNested()
            case "wide" =>
                encodeKyo = () => JsonBenchData.kyoEncodeWide()
                encodeJsoniter = () => JsonBenchData.jsoniterEncodeWide()
                encodeZioJson = () => JsonBenchData.zioEncodeWide()
                encodeCirce = () => JsonBenchData.circeEncodeWide()
                encodeZioBlocks = () => JsonBenchData.zbEncodeWide()
            case "collection" =>
                encodeKyo = () => JsonBenchData.kyoEncodeCollection()
                encodeJsoniter = () => JsonBenchData.jsoniterEncodeCollection()
                encodeZioJson = () => JsonBenchData.zioEncodeCollection()
                encodeCirce = () => JsonBenchData.circeEncodeCollection()
                encodeZioBlocks = () => JsonBenchData.zbEncodeCollection()
        end match
    end setup

    @Benchmark def kyo(): String = encodeKyo()

    @Benchmark def jsoniter(): String = encodeJsoniter()

    @Benchmark def zioJson(): String = encodeZioJson()

    @Benchmark def circe(): String = encodeCirce()

    @Benchmark def zioBlocks(): String = encodeZioBlocks()

end JsonEncodeBench
