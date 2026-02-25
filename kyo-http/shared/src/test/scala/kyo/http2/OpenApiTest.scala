package kyo.http2

import kyo.Record2.*
import kyo.Result

class OpenApiTest extends kyo.Test:
    override protected def useTestClient: Boolean = false

    val petStoreSpec = OpenApi(
        openapi = "3.0.0",
        info = OpenApi.Info(
            title = "Petstore",
            version = "1.0.0",
            description = Some("A sample API")
        ),
        paths = Map(
            "/pets" -> OpenApi.PathItem(
                get = Some(OpenApi.Operation(
                    tags = Some(List("pets")),
                    summary = Some("List all pets"),
                    description = None,
                    operationId = Some("listPets"),
                    deprecated = None,
                    parameters = Some(List(
                        OpenApi.Parameter(
                            name = "limit",
                            in = "query",
                            required = Some(false),
                            schema = OpenApi.SchemaObject.integer,
                            description = Some("How many items to return")
                        )
                    )),
                    requestBody = None,
                    responses = Map(
                        "200" -> OpenApi.Response(
                            description = "A list of pets",
                            content = Some(Map(
                                "application/json" -> OpenApi.MediaType(
                                    schema = OpenApi.SchemaObject.array(
                                        OpenApi.SchemaObject(
                                            `type` = None,
                                            format = None,
                                            items = None,
                                            properties = None,
                                            required = None,
                                            additionalProperties = None,
                                            oneOf = None,
                                            `enum` = None,
                                            `$ref` = Some("#/components/schemas/Pet")
                                        )
                                    )
                                )
                            ))
                        )
                    ),
                    security = None
                )),
                post = Some(OpenApi.Operation(
                    tags = Some(List("pets")),
                    summary = Some("Create a pet"),
                    description = None,
                    operationId = Some("createPet"),
                    deprecated = None,
                    parameters = None,
                    requestBody = Some(OpenApi.RequestBody(
                        required = Some(true),
                        content = Map(
                            "application/json" -> OpenApi.MediaType(
                                schema = OpenApi.SchemaObject(
                                    `type` = None,
                                    format = None,
                                    items = None,
                                    properties = None,
                                    required = None,
                                    additionalProperties = None,
                                    oneOf = None,
                                    `enum` = None,
                                    `$ref` = Some("#/components/schemas/Pet")
                                )
                            )
                        ),
                        description = None
                    )),
                    responses = Map(
                        "201" -> OpenApi.Response(
                            description = "Pet created",
                            content = None
                        )
                    ),
                    security = None
                )),
                put = None,
                delete = None,
                patch = None,
                head = None,
                options = None
            ),
            "/pets/{petId}" -> OpenApi.PathItem(
                get = Some(OpenApi.Operation(
                    tags = Some(List("pets")),
                    summary = Some("Info for a specific pet"),
                    description = None,
                    operationId = Some("showPetById"),
                    deprecated = None,
                    parameters = Some(List(
                        OpenApi.Parameter(
                            name = "petId",
                            in = "path",
                            required = Some(true),
                            schema = OpenApi.SchemaObject.string,
                            description = Some("The id of the pet to retrieve")
                        )
                    )),
                    requestBody = None,
                    responses = Map(
                        "200" -> OpenApi.Response(
                            description = "Expected response to a valid request",
                            content = Some(Map(
                                "application/json" -> OpenApi.MediaType(
                                    schema = OpenApi.SchemaObject(
                                        `type` = None,
                                        format = None,
                                        items = None,
                                        properties = None,
                                        required = None,
                                        additionalProperties = None,
                                        oneOf = None,
                                        `enum` = None,
                                        `$ref` = Some("#/components/schemas/Pet")
                                    )
                                )
                            ))
                        )
                    ),
                    security = None
                )),
                post = None,
                put = None,
                delete = None,
                patch = None,
                head = None,
                options = None
            )
        ),
        components = Some(OpenApi.Components(
            schemas = Some(Map(
                "Pet" -> OpenApi.SchemaObject(
                    `type` = Some("object"),
                    format = None,
                    items = None,
                    properties = Some(Map(
                        "id"   -> OpenApi.SchemaObject.long,
                        "name" -> OpenApi.SchemaObject.string,
                        "tag"  -> OpenApi.SchemaObject.string
                    )),
                    required = Some(List("id", "name")),
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None
                )
            )),
            securitySchemes = None
        ))
    )

    "OpenApi" - {

        "toJson produces valid JSON" in {
            val json = OpenApi.toJson(petStoreSpec)
            assert(json.contains("\"openapi\":\"3.0.0\"") || json.contains("\"openapi\" : \"3.0.0\""))
            assert(json.contains("Petstore"))
            assert(json.contains("listPets"))
            assert(json.contains("showPetById"))
            assert(json.contains("#/components/schemas/Pet"))
        }

        "roundtrip toJson/decode" in {
            val json   = OpenApi.toJson(petStoreSpec)
            val parsed = Schema[OpenApi].decode(json)
            parsed match
                case Result.Success(spec) =>
                    assert(spec.openapi == "3.0.0")
                    assert(spec.info.title == "Petstore")
                    assert(spec.info.version == "1.0.0")
                    assert(spec.info.description == Some("A sample API"))
                    assert(spec.paths.size == 2)
                    assert(spec.paths.contains("/pets"))
                    assert(spec.paths.contains("/pets/{petId}"))
                case Result.Failure(e) =>
                    fail(s"Failed to parse: $e")
                case Result.Panic(ex) =>
                    throw ex
            end match
        }

        "roundtrip preserves operations" in {
            val json   = OpenApi.toJson(petStoreSpec)
            val parsed = Schema[OpenApi].decode(json)
            parsed match
                case Result.Success(spec) =>
                    val petsPath = spec.paths("/pets")
                    assert(petsPath.get.isDefined)
                    assert(petsPath.post.isDefined)
                    assert(petsPath.put.isEmpty)

                    val listPets = petsPath.get.get
                    assert(listPets.operationId == Some("listPets"))
                    assert(listPets.tags == Some(List("pets")))
                    assert(listPets.parameters.get.size == 1)
                    assert(listPets.parameters.get.head.name == "limit")
                    assert(listPets.parameters.get.head.in == "query")

                    val createPet = petsPath.post.get
                    assert(createPet.operationId == Some("createPet"))
                    assert(createPet.requestBody.isDefined)
                    assert(createPet.requestBody.get.required == Some(true))
                case Result.Failure(e) =>
                    fail(s"Failed to parse: $e")
                case Result.Panic(ex) =>
                    throw ex
            end match
        }

        "roundtrip preserves components" in {
            val json   = OpenApi.toJson(petStoreSpec)
            val parsed = Schema[OpenApi].decode(json)
            parsed match
                case Result.Success(spec) =>
                    assert(spec.components.isDefined)
                    val schemas = spec.components.get.schemas.get
                    assert(schemas.contains("Pet"))
                    val pet = schemas("Pet")
                    assert(pet.`type` == Some("object"))
                    assert(pet.properties.get.size == 3)
                    assert(pet.required == Some(List("id", "name")))
                case Result.Failure(e) =>
                    fail(s"Failed to parse: $e")
                case Result.Panic(ex) =>
                    throw ex
            end match
        }

        "decode with minimal spec" in {
            val json   = """{"openapi":"3.0.0","info":{"title":"Min","version":"0.1"},"paths":{}}"""
            val parsed = Schema[OpenApi].decode(json)
            parsed match
                case Result.Success(spec) =>
                    assert(spec.openapi == "3.0.0")
                    assert(spec.info.title == "Min")
                    assert(spec.paths.isEmpty)
                    assert(spec.components.isEmpty)
                case Result.Failure(e) =>
                    fail(s"Failed to parse: $e")
                case Result.Panic(ex) =>
                    throw ex
            end match
        }

        "decode with invalid JSON fails" in {
            val parsed = Schema[OpenApi].decode("not json")
            assert(!parsed.isSuccess)
        }

        "SchemaObject convenience constructors" in {
            assert(OpenApi.SchemaObject.string.`type` == Some("string"))
            assert(OpenApi.SchemaObject.integer.`type` == Some("integer"))
            assert(OpenApi.SchemaObject.integer.format == Some("int32"))
            assert(OpenApi.SchemaObject.long.format == Some("int64"))
            assert(OpenApi.SchemaObject.number.`type` == Some("number"))
            assert(OpenApi.SchemaObject.boolean.`type` == Some("boolean"))
            assert(OpenApi.SchemaObject.obj.`type` == Some("object"))

            val arr = OpenApi.SchemaObject.array(OpenApi.SchemaObject.string)
            assert(arr.`type` == Some("array"))
            assert(arr.items.get.`type` == Some("string"))
        }
    }

    "OpenApi.fromJson" - {

        "single GET route" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets": {
                        "get": {
                            "operationId": "listPets",
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute[Any, Any, Nothing] = api.listPets
            assert(route.method == HttpMethod.GET)
        }

        "multiple routes" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets": {
                        "get": {
                            "operationId": "listPets",
                            "responses": {"200": {"description": "ok"}}
                        },
                        "post": {
                            "operationId": "createPet",
                            "responses": {"201": {"description": "created"}}
                        }
                    }
                }
            }""")
            val list: HttpRoute[Any, Any, Nothing]   = api.listPets
            val create: HttpRoute[Any, Any, Nothing] = api.createPet
            assert(list.method == HttpMethod.GET)
            assert(create.method == HttpMethod.POST)
        }

        "path with capture" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets/{petId}": {
                        "get": {
                            "operationId": "getPet",
                            "parameters": [{
                                "name": "petId",
                                "in": "path",
                                "required": true,
                                "schema": {"type": "integer", "format": "int32"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["petId" ~ Int, Any, Nothing] = api.getPet
            assert(route.method == HttpMethod.GET)
        }

        "query parameter" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets": {
                        "get": {
                            "operationId": "listPets",
                            "parameters": [{
                                "name": "limit",
                                "in": "query",
                                "required": true,
                                "schema": {"type": "integer", "format": "int32"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["limit" ~ Int, Any, Nothing] = api.listPets
            assert(route.method == HttpMethod.GET)
            assert(route.request.fields.size == 1)
        }

        "optional query parameter" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets": {
                        "get": {
                            "operationId": "listPets",
                            "parameters": [{
                                "name": "limit",
                                "in": "query",
                                "schema": {"type": "integer", "format": "int32"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["limit" ~ kyo.Maybe[Int], Any, Nothing] = api.listPets
            assert(route.request.fields.size == 1)
        }

        "header parameter" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets": {
                        "get": {
                            "operationId": "listPets",
                            "parameters": [{
                                "name": "X-Request-Id",
                                "in": "header",
                                "required": true,
                                "schema": {"type": "string"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["X-Request-Id" ~ String, Any, Nothing] = api.listPets
            assert(route.request.fields.size == 1)
        }

        "response body json" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/greeting": {
                        "get": {
                            "operationId": "greet",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "schema": {"type": "string"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            val route: HttpRoute[Any, "body" ~ String, Nothing] = api.greet
            assert(route.response.fields.size == 1)
        }

        "path capture + query + response body" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets/{petId}": {
                        "get": {
                            "operationId": "getPet",
                            "parameters": [
                                {"name": "petId", "in": "path", "required": true, "schema": {"type": "integer"}},
                                {"name": "verbose", "in": "query", "required": true, "schema": {"type": "boolean"}}
                            ],
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {"schema": {"type": "string"}}
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            val route: HttpRoute["petId" ~ Int & "verbose" ~ Boolean, "body" ~ String, Nothing] = api.getPet
            assert(route.method == HttpMethod.GET)
            assert(route.request.fields.size == 1)
            assert(route.response.fields.size == 1)
        }

        "generated operation name when no operationId" in {
            val api = OpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/users/{id}/posts": {
                        "get": {
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute[?, Any, Nothing] = api.getUsersIdPosts
            assert(route.method == HttpMethod.GET)
        }
    }
end OpenApiTest
