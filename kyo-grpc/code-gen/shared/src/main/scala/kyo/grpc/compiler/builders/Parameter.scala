package kyo.grpc.compiler.builders

import org.typelevel.paiges.Doc
import org.typelevel.paiges.ExtendedSyntax.*

final case class Parameter(name: String, typeName: String, default: Option[String])

object Parameter {

}
