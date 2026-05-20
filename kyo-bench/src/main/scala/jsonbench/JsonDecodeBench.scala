package jsonbench

import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

class JsonDecodeBench extends BaseBench:

    @Param(Array("small", "nested", "wide", "collection"))
    var payload: String = uninitialized

    private var decodeKyo: () => Any       = uninitialized
    private var decodeJsoniter: () => Any  = uninitialized
    private var decodeZioJson: () => Any   = uninitialized
    private var decodeCirce: () => Any     = uninitialized
    private var decodeZioBlocks: () => Any = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        payload match
            case "small" =>
                val json = JsonBenchData.smallJson
                decodeKyo = () => JsonBenchData.kyoDecodeSmall(json)
                decodeJsoniter = () => JsonBenchData.jsoniterDecodeSmall(json)
                decodeZioJson = () => JsonBenchData.zioDecodeSmall(json)
                decodeCirce = () => JsonBenchData.circeDecodeSmall(json)
                decodeZioBlocks = () => JsonBenchData.zbDecodeSmall(json)
            case "nested" =>
                val json = JsonBenchData.nestedJson
                decodeKyo = () => JsonBenchData.kyoDecodeNested(json)
                decodeJsoniter = () => JsonBenchData.jsoniterDecodeNested(json)
                decodeZioJson = () => JsonBenchData.zioDecodeNested(json)
                decodeCirce = () => JsonBenchData.circeDecodeNested(json)
                decodeZioBlocks = () => JsonBenchData.zbDecodeNested(json)
            case "wide" =>
                val json = JsonBenchData.wideJson
                decodeKyo = () => JsonBenchData.kyoDecodeWide(json)
                decodeJsoniter = () => JsonBenchData.jsoniterDecodeWide(json)
                decodeZioJson = () => JsonBenchData.zioDecodeWide(json)
                decodeCirce = () => JsonBenchData.circeDecodeWide(json)
                decodeZioBlocks = () => JsonBenchData.zbDecodeWide(json)
            case "collection" =>
                val json = JsonBenchData.collectionJson
                decodeKyo = () => JsonBenchData.kyoDecodeCollection(json)
                decodeJsoniter = () => JsonBenchData.jsoniterDecodeCollection(json)
                decodeZioJson = () => JsonBenchData.zioDecodeCollection(json)
                decodeCirce = () => JsonBenchData.circeDecodeCollection(json)
                decodeZioBlocks = () => JsonBenchData.zbDecodeCollection(json)
        end match
    end setup

    @Benchmark def kyo(): Any = decodeKyo()

    @Benchmark def jsoniter(): Any = decodeJsoniter()

    @Benchmark def zioJson(): Any = decodeZioJson()

    @Benchmark def circe(): Any = decodeCirce()

    @Benchmark def zioBlocks(): Any = decodeZioBlocks()

end JsonDecodeBench
