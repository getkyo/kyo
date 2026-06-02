package yamlbench

import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

class YamlEncodeBench extends BaseBench:

    @Param(Array("small", "nested", "wide", "collection", "workflow", "openapi", "compose"))
    var payload: String = uninitialized

    @Param(Array("default", "small", "fast"))
    var config: String = uninitialized

    private var encodeKyo: () => String = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        val writerConfig = YamlBenchData.writerConfig(config)
        payload match
            case "small" =>
                encodeKyo = () => YamlBenchData.encodeSmall(writerConfig)
            case "nested" =>
                encodeKyo = () => YamlBenchData.encodeNested(writerConfig)
            case "wide" =>
                encodeKyo = () => YamlBenchData.encodeWide(writerConfig)
            case "collection" =>
                encodeKyo = () => YamlBenchData.encodeCollection(writerConfig)
            case "workflow" =>
                encodeKyo = () => YamlBenchData.encodeWorkflow(writerConfig)
            case "openapi" =>
                encodeKyo = () => YamlBenchData.encodeOpenApi(writerConfig)
            case "compose" =>
                encodeKyo = () => YamlBenchData.encodeCompose(writerConfig)
        end match
    end setup

    @Benchmark def kyo(): String =
        encodeKyo()
end YamlEncodeBench
