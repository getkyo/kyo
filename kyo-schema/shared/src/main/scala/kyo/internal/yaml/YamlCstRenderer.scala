package kyo.internal.yaml

import kyo.*
import scala.annotation.tailrec

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
                val builder = StringBuilder()
                val childConfig =
                    if stream.documents.size > 1 then config.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.None)
                    else config

                @tailrec def loop(index: Int): String =
                    if index >= stream.documents.size then
                        appendStreamEndMarker()
                        builder.toString
                    else
                        if stream.documents.size > 1 then
                            if builder.nonEmpty && builder.charAt(builder.length - 1) != '\n' then
                                val _ = builder.append('\n')
                            val _ = builder.append("---\n")
                        end if
                        val _ = builder.append(document(stream.documents(index))(using childConfig))
                        loop(index + 1)
                    end if
                end loop

                def appendStreamEndMarker(): Unit =
                    if stream.documents.size > 1 && config.documentMarkers == Yaml.WriterConfig.DocumentMarkers.StartAndEnd then
                        if builder.nonEmpty && builder.charAt(builder.length - 1) != '\n' then
                            val _ = builder.append('\n')
                        val _ = builder.append("...\n")
                    end if
                end appendStreamEndMarker

                loop(0)
        end match
    end stream
end YamlCstRenderer
