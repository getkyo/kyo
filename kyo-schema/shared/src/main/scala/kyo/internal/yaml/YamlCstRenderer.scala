package kyo.internal.yaml

import kyo.*

private[kyo] object YamlCstRenderer:

    def document(document: Yaml.Cst.Document)(using config: Yaml.WriterConfig): String =
        document.originalSource match
            case Present(source) => source
            case Absent =>
                val renderer = Yaml.Events.Renderer(config)
                YamlCstBuilder.emitDocument(document, ())(renderer) match
                    case Result.Success(_) => renderer.resultString
                    case Result.Failure(e) => throw e
                    case Result.Panic(e)   => throw e
                end match
        end match
    end document

    def stream(stream: Yaml.Cst.Stream)(using config: Yaml.WriterConfig): String =
        stream.originalSource match
            case Present(source) => source
            case Absent =>
                stream.documents.iterator.map(document).mkString
        end match
    end stream
end YamlCstRenderer
