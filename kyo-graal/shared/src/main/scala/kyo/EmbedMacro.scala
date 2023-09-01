package kyo

import org.graalvm.polyglot._
import scala.quoted._
import kyo.ios._

protected object EmbedMacro {
  import embeds._
  type CodeGenerator = (String, String) => String

  val lambdafy: Map[String, CodeGenerator] = Map(
      "python" -> ((params, code) => s"""def func($params):\n$code\nreturn func($params)"""),
      "js"     -> ((params, code) => s"(function($params) { return $code; })"),
      "ruby"   -> ((params, code) => s"lambda { |$params| $code }"),
      "R"      -> ((params, code) => s"function($params) { $code }")
  )

  def evalLanguageImpl(language: String, sc: Expr[StringContext], args: Expr[Seq[Any]])(using
      Quotes
  ): Expr[Value > Embeds] = {
    import quotes.reflect._

    sc match {
      case '{ StringContext(${ Varargs(parts) }: _*) } =>
        val argCount = args match {
          case Varargs(argList) => argList.length
          case _                => 0
        }

        val argNames           = (0 until argCount).map(i => s"arg$i").toList
        val functionParameters = argNames.mkString(", ")

        val codeParts = parts.map {
          case '{ $part: String } => part.show.stripPrefix("\"").stripSuffix("\"")
          case _ =>
            report.errorAndAbort("All parts must be string literals.")

        }

        val code = codeParts.zipAll(argNames, "", "").flatMap {
          case (part, arg) => Seq(part, arg)
        }.mkString

        val indentedCode = code.split("\\n").map(line => s"  $line").mkString("\n")

        val functionCode = lambdafy(language)(functionParameters, indentedCode)

        println(s"Generated code: $functionCode")

        // Validation of the generated code.
        // try {
        //   val context = Context.create()
        //   context.parse(language, functionCode)
        // } catch {
        //   case ex: PolyglotException =>
        //     report.errorAndAbort(
        //         s"Failed to parse the ${language} code: $functionCode \nException: ${ex.toString}"
        //     )
        // }

        '{
          IOs {
            val result =
              Context.create().eval(${ Expr(language) }, ${ Expr(functionCode) })
            if (${ Expr(argCount) } > 0) {
              result.execute($args: _*)
            } else {
              result
            }
          }
        }

      case _ =>
        report.errorAndAbort("Context is not a StringContext")
    }
  }
}
