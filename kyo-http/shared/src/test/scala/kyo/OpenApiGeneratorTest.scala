package kyo

import kyo.*
import kyo.internal.OpenApiGenerator

class OpenApiGeneratorTest extends kyo.Test:

    "OpenApiGenerator" - {

        "empty handlers" in {
            val spec = OpenApiGenerator.generate(Seq.empty)
            assert(spec.paths.isEmpty)
            assert(spec.openapi == "3.0.0")
        }

        "config fields" in {
            val spec = OpenApiGenerator.generate(
                Seq.empty,
                OpenApiGenerator.Config(title = "My API", version = "2.0", description = Some("desc"))
            )
            assert(spec.info.title == "My API")
            assert(spec.info.version == "2.0")
            assert(spec.info.description == Some("desc"))
        }

        "simple GET route" in {
            val h    = HttpHandler.const(HttpMethod.GET, "pets", HttpStatus.OK)
            val spec = OpenApiGenerator.generate(Seq(h))
            assert(spec.paths.contains("/pets"))
            val pathItem = spec.paths("/pets")
            assert(pathItem.get.isDefined)
            assert(pathItem.post.isEmpty)
        }

        "multiple methods on same path" in {
            val spec = OpenApiGenerator.generate(Seq(
                HttpHandler.const(HttpMethod.GET, "pets", HttpStatus.OK),
                HttpHandler.const(HttpMethod.POST, "pets", HttpStatus.Created)
            ))
            assert(spec.paths.size == 1)
            val pathItem = spec.paths("/pets")
            assert(pathItem.get.isDefined)
            assert(pathItem.post.isDefined)
        }

        "path capture" in {
            val route = HttpRoute.getRaw("pets" / HttpPath.Capture[Int]("petId"))
            val h     = route.handler(_ => HttpResponse.ok)
            val spec  = OpenApiGenerator.generate(Seq(h))
            assert(spec.paths.contains("/pets/{petId}"))
            val params = spec.paths("/pets/{petId}").get.get.parameters.get
            assert(params.size == 1)
            assert(params.head.name == "petId")
            assert(params.head.in == "path")
            assert(params.head.required == Some(true))
            assert(params.head.schema.`type` == Some("integer"))
        }

        "query parameter" in {
            val route      = HttpRoute.getRaw("pets").request(_.query[Int]("limit"))
            val h          = route.handler(_ => HttpResponse.ok)
            val spec       = OpenApiGenerator.generate(Seq(h))
            val params     = spec.paths("/pets").get.get.parameters.get
            val limitParam = params.find(_.name == "limit").get
            assert(limitParam.in == "query")
            assert(limitParam.required == Some(true))
            assert(limitParam.schema.`type` == Some("integer"))
        }

        "optional query parameter" in {
            val route      = HttpRoute.getRaw("pets").request(_.queryOpt[Int]("limit"))
            val h          = route.handler(_ => HttpResponse.ok)
            val spec       = OpenApiGenerator.generate(Seq(h))
            val params     = spec.paths("/pets").get.get.parameters.get
            val limitParam = params.find(_.name == "limit").get
            assert(limitParam.required.isEmpty || limitParam.required == Some(false))
        }

        "header parameter" in {
            val route  = HttpRoute.getRaw("pets").request(_.header[String]("X-Request-Id"))
            val h      = route.handler(_ => HttpResponse.ok)
            val spec   = OpenApiGenerator.generate(Seq(h))
            val params = spec.paths("/pets").get.get.parameters.get
            assert(params.exists(p => p.name == "X-Request-Id" && p.in == "header"))
        }

        "json response body" in {
            val route = HttpRoute.getRaw("greeting").response(_.bodyJson[String])
            val h     = route.handler(_ => HttpResponse.okJson("hello"))
            val spec  = OpenApiGenerator.generate(Seq(h))
            val resp  = spec.paths("/greeting").get.get.responses("200")
            assert(resp.content.isDefined)
            assert(resp.content.get.contains("application/json"))
        }

        "json request body" in {
            val route = HttpRoute.postRaw("pets").request(_.bodyJson[String])
            val h     = route.handler(_ => HttpResponse.ok)
            val spec  = OpenApiGenerator.generate(Seq(h))
            val op    = spec.paths("/pets").post.get
            assert(op.requestBody.isDefined)
            assert(op.requestBody.get.content.contains("application/json"))
            assert(op.requestBody.get.required == Some(true))
        }

        "request body with Option fields excludes them from required" in {
            case class UpdateItem(name: String, description: Option[String]) derives Schema
            val route = HttpRoute.putRaw("items" / HttpPath.Capture[Int]("id"))
                .request(_.bodyJson[UpdateItem])
                .response(_.bodyJson[String])
            val h    = route.handler(_ => HttpResponse.okJson("ok"))
            val spec = OpenApiGenerator.generate(Seq(h))
            val json = HttpOpenApi.toJson(spec)
            // The UpdateItem schema should have "name" in required but NOT "description"
            assert(json.contains("properties"), s"OpenAPI should contain schema properties, got: $json")
            // Find the schema — it may be inline or in components
            val schema = spec.paths("/items/{id}").put.get.requestBody.get
                .content("application/json").schema
            val resolvedSchema: HttpOpenApi.SchemaObject = schema.`$ref` match
                case Some(ref) =>
                    val schemaName = ref.stripPrefix("#/components/schemas/")
                    spec.components.get.schemas.get.apply(schemaName)
                case None => schema
            val required = resolvedSchema.required.getOrElse(Nil)
            assert(required.contains("name"), s"'name' (non-optional) should be in required, got: $required")
            assert(
                !required.contains("description"),
                s"'description' (Option[String]) should NOT be in required, got: $required"
            )
        }

        "text response body" in {
            val route = HttpRoute.getRaw("hello").response(_.bodyText)
            val h     = route.handler(_ => HttpResponse.okText("hi"))
            val spec  = OpenApiGenerator.generate(Seq(h))
            val resp  = spec.paths("/hello").get.get.responses("200")
            assert(resp.content.get.contains("text/plain"))
        }

        "metadata" in {
            val route = HttpRoute.getRaw("pets")
                .metadata(_.summary("List pets").description("Returns all pets").operationId("listPets").tag("pets"))
            val h    = route.handler(_ => HttpResponse.ok)
            val spec = OpenApiGenerator.generate(Seq(h))
            val op   = spec.paths("/pets").get.get
            assert(op.operationId == Some("listPets"))
            assert(op.summary == Some("List pets"))
            assert(op.description == Some("Returns all pets"))
            assert(op.tags == Some(List("pets")))
        }

        "deprecated route" in {
            val route = HttpRoute.getRaw("old").metadata(_.markDeprecated)
            val h     = route.handler(_ => HttpResponse.ok)
            val spec  = OpenApiGenerator.generate(Seq(h))
            assert(spec.paths("/old").get.get.deprecated == Some(true))
        }

        "codec type inference" - {
            "string codec" in {
                val route = HttpRoute.getRaw("test").request(_.query[String]("q"))
                val h     = route.handler(_ => HttpResponse.ok)
                val spec  = OpenApiGenerator.generate(Seq(h))
                val param = spec.paths("/test").get.get.parameters.get.head
                assert(param.schema.`type` == Some("string"))
            }

            "boolean codec" in {
                val route = HttpRoute.getRaw("test").request(_.query[Boolean]("flag"))
                val h     = route.handler(_ => HttpResponse.ok)
                val spec  = OpenApiGenerator.generate(Seq(h))
                val param = spec.paths("/test").get.get.parameters.get.head
                assert(param.schema.`type` == Some("boolean"))
            }

            "long codec" in {
                val route = HttpRoute.getRaw("test" / HttpPath.Capture[Long]("id"))
                val h     = route.handler(_ => HttpResponse.ok)
                val spec  = OpenApiGenerator.generate(Seq(h))
                val param = spec.paths("/test/{id}").get.get.parameters.get.head
                assert(param.schema.format == Some("int64"))
            }
        }

        "roundtrip to JSON" in {
            val route = HttpRoute.getRaw("pets" / HttpPath.Capture[Int]("petId"))
                .request(_.query[Boolean]("verbose"))
                .response(_.bodyJson[String])
                .metadata(_.operationId("getPet").summary("Get a pet"))
            val h = route.handler(_ => HttpResponse.okJson("cat"))
            val spec = OpenApiGenerator.generate(
                Seq(h),
                OpenApiGenerator.Config(title = "Pet API", version = "1.0.0")
            )
            val json = HttpOpenApi.toJson(spec)
            assert(json.contains("getPet"))
            assert(json.contains("Pet API"))
            assert(json.contains("petId"))
        }
    }
end OpenApiGeneratorTest
