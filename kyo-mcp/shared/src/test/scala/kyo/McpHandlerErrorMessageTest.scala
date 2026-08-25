package kyo

import scala.compiletime.testing.typeCheckErrors

/** Pins the compile errors a handler author actually reads.
  *
  * A handler body is the one place a user writes an effect row that the factory then closes, so the error
  * it produces when the row does not fit is this module's most-read diagnostic. It has to name the
  * offending effect and nothing else. A second, unrelated error sends a cold reader hunting a problem that
  * does not exist, and readers who work bottom-up start at that one.
  */
class McpHandlerErrorMessageTest extends Test:

    case class Query(city: String) derives Schema, CanEqual
    case class Report(summary: String) derives Schema, CanEqual

    /** True when the errors blame the `Schema[Out]` search, which is the phantom this guards against.
      *
      * `Out` degrades to `Any` when the body fails, and an unguarded search then matches several
      * primitive `Schema` givens at once. The instances named are whichever two the search reached
      * first, so the check is on the shape of the complaint, not on a particular pair.
      */
    private def blamesOutputSchema(errors: List[String]): Boolean =
        errors.exists(e => e.contains("Ambiguous given instances") && e.contains("Schema[Out]"))

    "a tool body carrying an undischarged Env" - {
        inline def errors: List[String] =
            typeCheckErrors(
                """
                McpHandler.tool[Query]("weather") { in =>
                    Env.use[Int](n => Report(s"${in.city}:$n"))
                }
                """
            ).map(_.message)

        "names the effect row that does not fit" in {
            assert(
                errors.exists(e => e.contains("kyo.Env") && e.contains("JsonRpcResponse.Halt")),
                s"the offending effect must be named; errors were:\n${errors.mkString("\n--\n")}"
            )
        }

        "does not blame the output schema search" in {
            assert(!blamesOutputSchema(errors), s"phantom ambiguity reported; errors were:\n${errors.mkString("\n--\n")}")
        }

        "points the author at the body rather than at a missing instance" in {
            assert(
                errors.exists(_.contains("could not be inferred")),
                s"the author must be told why the output type is unknown; errors were:\n${errors.mkString("\n--\n")}"
            )
        }
    }

    "a tool body carrying an undischarged Var does not blame the output schema search" in {
        val errors =
            typeCheckErrors(
                """
                McpHandler.tool[Query]("weather") { in =>
                    Var.use[Int](n => Report(s"${in.city}:$n"))
                }
                """
            ).map(_.message)
        assert(errors.exists(_.contains("kyo.Var")), s"errors were:\n${errors.mkString("\n--\n")}")
        assert(!blamesOutputSchema(errors), s"phantom ambiguity reported; errors were:\n${errors.mkString("\n--\n")}")
    }

    "a tool body referencing an undefined symbol reports that symbol" in {
        // The worst shape before the guard: the phantom ambiguity was the ONLY error reported, so the
        // undefined name never reached the author at all.
        val errors =
            typeCheckErrors(
                """
                McpHandler.tool[Query]("weather") { in =>
                    noSuchFunction(in)
                }
                """
            ).map(_.message)
        assert(
            errors.exists(_.contains("noSuchFunction")),
            s"the real error must survive; errors were:\n${errors.mkString("\n--\n")}"
        )
        assert(!blamesOutputSchema(errors), s"phantom ambiguity reported; errors were:\n${errors.mkString("\n--\n")}")
    }

    "a custom handler body carrying an undischarged effect does not blame the output schema search" in {
        val errors =
            typeCheckErrors(
                """
                McpHandler.custom[Query]("weather") { in =>
                    Env.use[Int](n => Report(s"${in.city}:$n"))
                }
                """
            ).map(_.message)
        assert(!blamesOutputSchema(errors), s"phantom ambiguity reported; errors were:\n${errors.mkString("\n--\n")}")
    }

    "a well-formed tool still builds, so the guard costs a correct handler nothing" in {
        // Compiled normally rather than through `typeCheckErrors`, which cannot derive a `Frame` inside
        // the kyo package. The rest of this module's suite is the broader positive control: it builds
        // handlers in around thirty files and would stop compiling if the guard rejected a good one.
        val handler = McpHandler.tool[Query]("weather", "Current conditions")(in => Report(in.city))
        assert(handler.name == "weather")
    }

end McpHandlerErrorMessageTest
