package kyo.ffi.codegen

import kyo.ffi.codegen.model.*
import scala.tasty.inspector.*

/** Walks TASTy trees for `Ffi`-extending traits and produces [[kyo.ffi.codegen.model.TraitSpec]]s.
  *
  * Entry point: [[inspect]]. Designed to be invoked from the sbt plugin with a classpath and the list of TASTy files from the bindings
  * module.
  */
final class TastyExtractor:
    private val results = scala.collection.mutable.Buffer.empty[TraitSpec]
    private val errors  = scala.collection.mutable.Buffer.empty[ExtractorError]

    /** Runs the TASTy inspector over the given files with the supplied classpath and returns one [[kyo.ffi.codegen.model.TraitSpec]] per
      * discovered `Ffi`-extending trait.
      *
      * @param tastyFiles
      *   absolute paths to `.tasty` files.
      * @param classpath
      *   classpath entries needed to resolve types referenced by the TASTy files (typically the bindings module's `Compile/fullClasspath`).
      * @throws kyo.ffi.codegen.FfiExtractionError
      *   if one or more traits failed extraction. The exception carries every reported error.
      */
    def inspect(tastyFiles: List[String], classpath: List[String]): List[TraitSpec] =
        results.clear()
        errors.clear()
        if tastyFiles.nonEmpty then
            val inspector = new FfiInspector(this)
            val _         = TastyInspector.inspectAllTastyFiles(tastyFiles, Nil, classpath)(inspector)
        if errors.nonEmpty then
            throw FfiExtractionError(errors.toList)
        results.toList
    end inspect

    private[codegen] def addResult(spec: TraitSpec): Unit =
        results += spec
        ()

    private[codegen] def addError(err: ExtractorError): Unit =
        errors += err
        ()
end TastyExtractor

/** A single extractor failure, carrying source position context for user-friendly error messages. */
final case class ExtractorError(file: String, line: Int, message: String):
    override def toString: String =
        if line > 0 then s"$file:$line: $message"
        else s"$file: $message"
end ExtractorError

/** Aggregated extraction failure thrown by [[TastyExtractor.inspect]]. */
final case class FfiExtractionError(errors: List[ExtractorError])
    extends Exception(errors.map(_.toString).mkString("\n"))
