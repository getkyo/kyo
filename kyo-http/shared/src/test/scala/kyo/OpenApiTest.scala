package kyo

import kyo.*

class HttpOpenApiTest extends kyo.Test:

    val petStoreSpec = HttpOpenApi(
        openapi = "3.0.0",
        info = HttpOpenApi.Info(
            title = "Petstore",
            version = "1.0.0",
            description = Some("A sample API")
        ),
        paths = Map(
            "/pets" -> HttpOpenApi.PathItem(
                get = Some(HttpOpenApi.Operation(
                    tags = Some(List("pets")),
                    summary = Some("List all pets"),
                    description = None,
                    operationId = Some("listPets"),
                    deprecated = None,
                    parameters = Some(List(
                        HttpOpenApi.Parameter(
                            name = "limit",
                            in = "query",
                            required = Some(false),
                            json = HttpOpenApi.SchemaObject.integer,
                            description = Some("How many items to return")
                        )
                    )),
                    requestBody = None,
                    responses = Map(
                        "200" -> HttpOpenApi.Response(
                            description = "A list of pets",
                            content = Some(Map(
                                "application/json" -> HttpOpenApi.MediaType(
                                    json = HttpOpenApi.SchemaObject.array(
                                        HttpOpenApi.SchemaObject(
                                            `type` = None,
                                            format = None,
                                            items = None,
                                            properties = None,
                                            required = None,
                                            additionalProperties = None,
                                            oneOf = None,
                                            `enum` = None,
                                            `$ref` = Some("#/components/jsons/Pet")
                                        )
                                    )
                                )
                            ))
                        )
                    ),
                    security = None
                )),
                post = Some(HttpOpenApi.Operation(
                    tags = Some(List("pets")),
                    summary = Some("Create a pet"),
                    description = None,
                    operationId = Some("createPet"),
                    deprecated = None,
                    parameters = None,
                    requestBody = Some(HttpOpenApi.RequestBody(
                        required = Some(true),
                        content = Map(
                            "application/json" -> HttpOpenApi.MediaType(
                                json = HttpOpenApi.SchemaObject(
                                    `type` = None,
                                    format = None,
                                    items = None,
                                    properties = None,
                                    required = None,
                                    additionalProperties = None,
                                    oneOf = None,
                                    `enum` = None,
                                    `$ref` = Some("#/components/jsons/Pet")
                                )
                            )
                        ),
                        description = None
                    )),
                    responses = Map(
                        "201" -> HttpOpenApi.Response(
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
            "/pets/{petId}" -> HttpOpenApi.PathItem(
                get = Some(HttpOpenApi.Operation(
                    tags = Some(List("pets")),
                    summary = Some("Info for a specific pet"),
                    description = None,
                    operationId = Some("showPetById"),
                    deprecated = None,
                    parameters = Some(List(
                        HttpOpenApi.Parameter(
                            name = "petId",
                            in = "path",
                            required = Some(true),
                            json = HttpOpenApi.SchemaObject.string,
                            description = Some("The id of the pet to retrieve")
                        )
                    )),
                    requestBody = None,
                    responses = Map(
                        "200" -> HttpOpenApi.Response(
                            description = "Expected response to a valid request",
                            content = Some(Map(
                                "application/json" -> HttpOpenApi.MediaType(
                                    json = HttpOpenApi.SchemaObject(
                                        `type` = None,
                                        format = None,
                                        items = None,
                                        properties = None,
                                        required = None,
                                        additionalProperties = None,
                                        oneOf = None,
                                        `enum` = None,
                                        `$ref` = Some("#/components/jsons/Pet")
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
        components = Some(HttpOpenApi.Components(
            schemas = Some(Map(
                "Pet" -> HttpOpenApi.SchemaObject(
                    `type` = Some("object"),
                    format = None,
                    items = None,
                    properties = Some(Map(
                        "id"   -> HttpOpenApi.SchemaObject.long,
                        "name" -> HttpOpenApi.SchemaObject.string,
                        "tag"  -> HttpOpenApi.SchemaObject.string
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

    "HttpOpenApi" - {

        "toJson produces valid JSON" in {
            val json = HttpOpenApi.toJson(petStoreSpec)
            assert(json.contains("\"openapi\":\"3.0.0\"") || json.contains("\"openapi\" : \"3.0.0\""))
            assert(json.contains("Petstore"))
            assert(json.contains("listPets"))
            assert(json.contains("showPetById"))
            assert(json.contains("#/components/jsons/Pet"))
        }

        "roundtrip toJson/decode" in {
            val json   = HttpOpenApi.toJson(petStoreSpec)
            val parsed = Json[HttpOpenApi].decode(json)
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
            val json   = HttpOpenApi.toJson(petStoreSpec)
            val parsed = Json[HttpOpenApi].decode(json)
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
            val json   = HttpOpenApi.toJson(petStoreSpec)
            val parsed = Json[HttpOpenApi].decode(json)
            parsed match
                case Result.Success(spec) =>
                    assert(spec.components.isDefined)
                    val jsons = spec.components.get.schemas.get
                    assert(jsons.contains("Pet"))
                    val pet = jsons("Pet")
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
            val parsed = Json[HttpOpenApi].decode(json)
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
            val parsed = Json[HttpOpenApi].decode("not json")
            assert(!parsed.isSuccess)
        }

        "SchemaObject convenience constructors" in {
            assert(HttpOpenApi.SchemaObject.string.`type` == Some("string"))
            assert(HttpOpenApi.SchemaObject.integer.`type` == Some("integer"))
            assert(HttpOpenApi.SchemaObject.integer.format == Some("int32"))
            assert(HttpOpenApi.SchemaObject.long.format == Some("int64"))
            assert(HttpOpenApi.SchemaObject.number.`type` == Some("number"))
            assert(HttpOpenApi.SchemaObject.boolean.`type` == Some("boolean"))
            assert(HttpOpenApi.SchemaObject.obj.`type` == Some("object"))

            val arr = HttpOpenApi.SchemaObject.array(HttpOpenApi.SchemaObject.string)
            assert(arr.`type` == Some("array"))
            assert(arr.items.get.`type` == Some("string"))
        }
    }

    "HttpOpenApi.fromJson" - {

        "single GET route" in {
            val api = HttpOpenApi.fromJson("""{
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
            val api = HttpOpenApi.fromJson("""{
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
            val api = HttpOpenApi.fromJson("""{
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
                                "json": {"type": "integer", "format": "int32"}
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
            val api = HttpOpenApi.fromJson("""{
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
                                "json": {"type": "integer", "format": "int32"}
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
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets": {
                        "get": {
                            "operationId": "listPets",
                            "parameters": [{
                                "name": "limit",
                                "in": "query",
                                "json": {"type": "integer", "format": "int32"}
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
            val api = HttpOpenApi.fromJson("""{
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
                                "json": {"type": "string"}
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
            val api = HttpOpenApi.fromJson("""{
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
                                            "json": {"type": "string"}
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
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets/{petId}": {
                        "get": {
                            "operationId": "getPet",
                            "parameters": [
                                {"name": "petId", "in": "path", "required": true, "json": {"type": "integer"}},
                                {"name": "verbose", "in": "query", "required": true, "json": {"type": "boolean"}}
                            ],
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {"json": {"type": "string"}}
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

        "PUT method" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets/{id}": {
                        "put": {
                            "operationId": "updatePet",
                            "parameters": [{"name": "id", "in": "path", "required": true, "json": {"type": "string"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["id" ~ String, Any, Nothing] = api.updatePet
            assert(route.method == HttpMethod.PUT)
        }

        "DELETE method" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets/{id}": {
                        "delete": {
                            "operationId": "deletePet",
                            "parameters": [{"name": "id", "in": "path", "required": true, "json": {"type": "string"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["id" ~ String, Any, Nothing] = api.deletePet
            assert(route.method == HttpMethod.DELETE)
        }

        "PATCH method" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets/{id}": {
                        "patch": {
                            "operationId": "patchPet",
                            "parameters": [{"name": "id", "in": "path", "required": true, "json": {"type": "string"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["id" ~ String, Any, Nothing] = api.patchPet
            assert(route.method == HttpMethod.PATCH)
        }

        "HEAD method" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets": {
                        "head": {
                            "operationId": "headPets",
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute[Any, Any, Nothing] = api.headPets
            assert(route.method == HttpMethod.HEAD)
        }

        "OPTIONS method" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/pets": {
                        "options": {
                            "operationId": "optionsPets",
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute[Any, Any, Nothing] = api.optionsPets
            assert(route.method == HttpMethod.OPTIONS)
        }

        "integer/int64 maps to Long" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items/{id}": {
                        "get": {
                            "operationId": "getItem",
                            "parameters": [{"name": "id", "in": "path", "required": true, "json": {"type": "integer", "format": "int64"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["id" ~ Long, Any, Nothing] = api.getItem
            assert(route.method == HttpMethod.GET)
        }

        "number maps to Double" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{"name": "price", "in": "query", "required": true, "json": {"type": "number"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["price" ~ Double, Any, Nothing] = api.listItems
            assert(route.request.fields.size == 1)
        }

        "number/double maps to Double" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{"name": "price", "in": "query", "required": true, "json": {"type": "number", "format": "double"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["price" ~ Double, Any, Nothing] = api.listItems
            assert(route.request.fields.size == 1)
        }

        "string/uuid maps to java.util.UUID" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items/{id}": {
                        "get": {
                            "operationId": "getItem",
                            "parameters": [{"name": "id", "in": "path", "required": true, "json": {"type": "string", "format": "uuid"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["id" ~ java.util.UUID, Any, Nothing] = api.getItem
            assert(route.method == HttpMethod.GET)
        }

        "boolean path capture" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items/{active}": {
                        "get": {
                            "operationId": "getActive",
                            "parameters": [{"name": "active", "in": "path", "required": true, "json": {"type": "boolean"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["active" ~ Boolean, Any, Nothing] = api.getActive
            assert(route.method == HttpMethod.GET)
        }

        "multiple path captures" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/orgs/{orgId}/repos/{repoId}": {
                        "get": {
                            "operationId": "getRepo",
                            "parameters": [
                                {"name": "orgId", "in": "path", "required": true, "json": {"type": "string"}},
                                {"name": "repoId", "in": "path", "required": true, "json": {"type": "integer"}}
                            ],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["orgId" ~ String & "repoId" ~ Int, Any, Nothing] = api.getRepo
            assert(route.method == HttpMethod.GET)
        }

        "mixed parameter types: path + query + header + response body" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items/{id}": {
                        "get": {
                            "operationId": "getItem",
                            "parameters": [
                                {"name": "id", "in": "path", "required": true, "json": {"type": "integer"}},
                                {"name": "format", "in": "query", "required": true, "json": {"type": "string"}},
                                {"name": "X-Token", "in": "header", "required": true, "json": {"type": "string"}}
                            ],
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {"application/json": {"json": {"type": "string"}}}
                                }
                            }
                        }
                    }
                }
            }""")
            val route: HttpRoute["id" ~ Int & "format" ~ String & "X-Token" ~ String, "body" ~ String, Nothing] = api.getItem
            assert(route.method == HttpMethod.GET)
            assert(route.request.fields.size == 2) // query + header (path is in HttpPath)
            assert(route.response.fields.size == 1)
        }

        "optional header parameter" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{"name": "X-Trace-Id", "in": "header", "json": {"type": "string"}}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["X-Trace-Id" ~ Maybe[String], Any, Nothing] = api.listItems
            assert(route.request.fields.size == 1)
        }

        "default response fallback" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "default": {
                                    "description": "default response",
                                    "content": {"application/json": {"json": {"type": "string"}}}
                                }
                            }
                        }
                    }
                }
            }""")
            val route: HttpRoute[Any, "body" ~ String, Nothing] = api.listItems
            assert(route.response.fields.size == 1)
        }

        "response status selection picks 200 over 201" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "post": {
                            "operationId": "createItem",
                            "responses": {
                                "201": {
                                    "description": "created",
                                    "content": {"application/json": {"json": {"type": "integer"}}}
                                },
                                "200": {
                                    "description": "ok",
                                    "content": {"application/json": {"json": {"type": "string"}}}
                                }
                            }
                        }
                    }
                }
            }""")
            // 200 is picked (sorted first), so response body type is String
            val route: HttpRoute[Any, "body" ~ String, Nothing] = api.createItem
            assert(route.response.status.code == 200)
        }

        "path parameter without explicit parameter definition defaults to String" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items/{id}": {
                        "get": {
                            "operationId": "getItem",
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["id" ~ String, Any, Nothing] = api.getItem
            assert(route.method == HttpMethod.GET)
        }

        "empty response body (no content) maps to Any" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "delete": {
                            "operationId": "deleteAll",
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute[Any, Any, Nothing] = api.deleteAll
            assert(route.response.fields.isEmpty)
        }

        // BUG: requestBody is ignored — only parameters are processed.
        // A POST with requestBody should have a body field in the request.
        "requestBody should produce a body field" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "post": {
                            "operationId": "createItem",
                            "requestBody": {
                                "required": true,
                                "content": {"application/json": {"json": {"type": "string"}}}
                            },
                            "responses": {"201": {"description": "created"}}
                        }
                    }
                }
            }""")
            assert(api.createItem.request.fields.nonEmpty)
            ()
        }

        // BUG: $ref jsons are not resolved — falls through to String.
        // A $ref response should not produce a "body" ~ String field.
        "$ref json should be resolved" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "json": {"$ref": "#/components/jsons/Item"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            // BUG: $ref is unresolved, silently falls to String. Should fail to compile
            // when the referenced json cannot be resolved.
            typeCheckFailure("""
                HttpOpenApi.fromJson(
                    "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{\"/items\":{\"get\":{\"operationId\":\"listItems\",\"responses\":{\"200\":{\"description\":\"ok\",\"content\":{\"application/json\":{\"json\":{\"$$ref\":\"#/components/jsons/Item\"}}}}}}}}}"
                )
            """)("ref")
        }

        // BUG: array json type is not handled — falls through to String.
        "array json should not fall through to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "json": {"type": "array", "items": {"type": "string"}}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            // BUG: array falls to String. Should compile as Chunk[String].
            typeCheck("""val r: HttpRoute[Any, "body" ~ Chunk[String], Nothing] = api.listItems""")
        }

        // BUG: object json type is not handled — falls through to String.
        "object json should not fall through to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "json": {"type": "object"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            // BUG: object falls to String. A bare object json should not produce "body" ~ String.
            typeCheck("""val r: HttpRoute[Any, Any, Nothing] = api.listItems""")
        }

        // Enum values are not used for type safety — parameter is just String.
        "enum json maps to String" in {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "sort",
                                "in": "query",
                                "required": true,
                                "json": {"type": "string", "enum": ["asc", "desc"]}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val route: HttpRoute["sort" ~ String, Any, Nothing] = api.listItems
            assert(route.request.fields.size == 1)
        }

        "empty paths spec fails to compile" in {
            typeCheckFailure("""HttpOpenApi.fromJson(
                "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{}}"
            )""")("no operations")
        }

        "duplicate operationId fails to compile" in {
            typeCheckFailure("""HttpOpenApi.fromJson(
                "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{\"/a\":{\"get\":{\"operationId\":\"op\",\"responses\":{\"200\":{\"description\":\"ok\"}}}},\"/b\":{\"get\":{\"operationId\":\"op\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}"
            )""")("Duplicate")
        }

        // BUG: cookie parameters are silently dropped — only query and header are processed.
        "cookie parameter should be included" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "session",
                                "in": "cookie",
                                "required": true,
                                "json": {"type": "string"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            assert(api.listItems.request.fields.nonEmpty)
            ()
        }

        // BUG: string/date-time format falls through to plain String.
        "string/date-time should not map to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "since",
                                "in": "query",
                                "required": true,
                                "json": {"type": "string", "format": "date-time"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            // Should map to a date/time type, not plain String
            typeCheck("""val r: HttpRoute["since" ~ java.time.Instant, Any, Nothing] = api.listItems""")
        }

        // BUG: string/date format falls through to plain String.
        "string/date should not map to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "since",
                                "in": "query",
                                "required": true,
                                "json": {"type": "string", "format": "date"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            // Should map to a date type, not plain String
            typeCheck("""val r: HttpRoute["since" ~ java.time.LocalDate, Any, Nothing] = api.listItems""")
        }

        // BUG: non-JSON response content types are silently ignored.
        "text/plain response should produce a body field" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
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
                                        "text/plain": {
                                            "json": {"type": "string"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            assert(api.greet.response.fields.nonEmpty)
            ()
        }

        // BUG: oneOf json is not handled — falls through to String.
        "oneOf json should not fall through to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "json": {
                                                "oneOf": [
                                                    {"type": "string"},
                                                    {"type": "integer"}
                                                ]
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            // Should produce a union type, not String
            typeCheck("""val r: HttpRoute[Any, "body" ~ (String | Int), Nothing] = api.listItems""")
        }

        // BUG: string/byte format falls through to plain String.
        "string/byte should not map to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "data",
                                "in": "query",
                                "required": true,
                                "json": {"type": "string", "format": "byte"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            // Should map to a byte array type, not plain String
            typeCheck("""val r: HttpRoute["data" ~ Span[Byte], Any, Nothing] = api.listItems""")
        }

        // BUG: string/binary format falls through to plain String.
        "string/binary should not map to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "data",
                                "in": "query",
                                "required": true,
                                "json": {"type": "string", "format": "binary"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            // Should map to a binary type, not plain String
            typeCheck("""val r: HttpRoute["data" ~ Span[Byte], Any, Nothing] = api.listItems""")
        }

        // BUG: anyOf json is not handled — falls through to String.
        "anyOf json should not fall through to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "json": {
                                                "anyOf": [
                                                    {"type": "string"},
                                                    {"type": "integer"}
                                                ]
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            typeCheck("""val r: HttpRoute[Any, "body" ~ (String | Int), Nothing] = api.listItems""")
        }

        // BUG: allOf json is not handled — falls through to String.
        "allOf json should not fall through to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "json": {
                                                "allOf": [
                                                    {"type": "object"},
                                                    {"type": "object"}
                                                ]
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            // allOf with no type should not silently produce "body" ~ String
            typeCheck("""val r: HttpRoute[Any, Any, Nothing] = api.listItems""")
        }

        // BUG: application/xml response content is silently ignored.
        "application/xml response should produce a body field" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/xml": {
                                            "json": {"type": "string"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            assert(api.listItems.response.fields.nonEmpty)
            ()
        }

        // BUG: $ref in parameter json falls through to String.
        "$ref in parameter json should be resolved" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items/{id}": {
                        "get": {
                            "operationId": "getItem",
                            "parameters": [{
                                "name": "id",
                                "in": "path",
                                "required": true,
                                "json": {"$ref": "#/components/jsons/ItemId"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            // $ref should be resolved or produce a compile error — not silently default to String.
            // This spec should fail to compile because ItemId json is not provided.
            typeCheckFailure("""
                HttpOpenApi.fromJson(
                    "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{\"/items/{id}\":{\"get\":{\"operationId\":\"getItem\",\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"json\":{\"$$ref\":\"#/components/jsons/ItemId\"}}],\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}"
                )
            """)("ref")
        }

        // BUG: only error responses (no 2xx, no default) silently falls back to OK with empty response.
        // Should produce a compile error or at least warn.
        "only error responses should not silently default to OK" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "404": {"description": "not found"},
                                "500": {"description": "error"}
                            }
                        }
                    }
                }
            }""")
            // Silently defaults to status 200 — should not be 200
            assert(api.listItems.response.status.code != 200)
            ()
        }

        // BUG: nested array of objects — combines array and object limitations.
        "array of objects response should not fall through to String" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "json": {
                                                "type": "array",
                                                "items": {"type": "object"}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            // Should map to Chunk, not String
            typeCheck("""val r: HttpRoute[Any, "body" ~ Chunk[String], Nothing] = api.listItems""")
        }

        // BUG: security schemes are parsed but ignored — no auth headers/params generated.
        "security scheme bearer token should produce a header field" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "security": [{"bearerAuth": []}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                },
                "components": {
                    "securitySchemes": {
                        "bearerAuth": {
                            "type": "http",
                            "scheme": "bearer"
                        }
                    }
                }
            }""")
            assert(api.listItems.request.fields.nonEmpty)
            ()
        }

        // BUG: security scheme apiKey should produce a header/query field.
        "security scheme apiKey should produce a parameter field" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "security": [{"apiKey": []}],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                },
                "components": {
                    "securitySchemes": {
                        "apiKey": {
                            "type": "apiKey",
                            "name": "X-API-Key",
                            "in": "header"
                        }
                    }
                }
            }""")
            assert(api.listItems.request.fields.nonEmpty)
            ()
        }

        // BUG: path-level parameters are ignored — only operation-level parameters are read.
        "path-level parameters should be merged with operation parameters" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "parameters": [{
                            "name": "X-Tenant",
                            "in": "header",
                            "required": true,
                            "json": {"type": "string"}
                        }],
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "limit",
                                "in": "query",
                                "required": true,
                                "json": {"type": "integer"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            // Should have both X-Tenant (path-level) and limit (operation-level)
            assert(api.listItems.request.fields.size == 2)
            ()
        }

        // BUG: nullable parameter is not handled — should map to Maybe[T].
        "nullable parameter should map to Maybe" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "filter",
                                "in": "query",
                                "required": true,
                                "json": {"type": "string", "nullable": true}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            // nullable + required should be Maybe[String], not String
            typeCheck("""val r: HttpRoute["filter" ~ Maybe[String], Any, Nothing] = api.listItems""")
        }

        // BUG: default values in parameters are ignored.
        "parameter default value should be used" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "limit",
                                "in": "query",
                                "json": {"type": "integer", "default": 10}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val field = api.listItems.request.fields.head.asInstanceOf[HttpRoute.Field.Param[?, ?, ?]]
            assert(field.default.isDefined)
            ()
        }

        // BUG: requestBody with multipart/form-data should produce a Multipart field.
        "requestBody multipart/form-data should produce a body field" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/upload": {
                        "post": {
                            "operationId": "upload",
                            "requestBody": {
                                "required": true,
                                "content": {"multipart/form-data": {"json": {"type": "object"}}}
                            },
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            assert(api.upload.request.fields.nonEmpty)
            ()
        }

        // BUG: requestBody with application/x-www-form-urlencoded should produce a field.
        "requestBody form-urlencoded should produce a body field" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/login": {
                        "post": {
                            "operationId": "login",
                            "requestBody": {
                                "required": true,
                                "content": {"application/x-www-form-urlencoded": {"json": {"type": "object"}}}
                            },
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            assert(api.login.request.fields.nonEmpty)
            ()
        }

        // BUG: application/octet-stream response should use ContentType.Binary.
        "application/octet-stream response should produce a binary body" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/download": {
                        "get": {
                            "operationId": "download",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/octet-stream": {
                                            "json": {"type": "string", "format": "binary"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            assert(api.download.response.fields.nonEmpty)
            ()
        }

        // BUG: multiple content types in response — only application/json is checked,
        // other types are silently ignored even when present alongside JSON.
        "response with multiple content types should handle all" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "responses": {
                                "200": {
                                    "description": "ok",
                                    "content": {
                                        "application/json": {
                                            "json": {"type": "string"}
                                        },
                                        "text/plain": {
                                            "json": {"type": "string"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }""")
            // Should represent both content types, not just JSON
            assert(api.listItems.response.fields.size > 1)
            ()
        }

        // BUG: string minLength constraint is silently ignored.
        "string parameter minLength should not be ignored" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "q",
                                "in": "query",
                                "required": true,
                                "json": {"type": "string", "minLength": 1}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val field = api.listItems.request.fields.head.asInstanceOf[HttpRoute.Field.Param[?, ?, ?]]
            // The constraint should be captured in the field description — currently silently dropped
            assert(field.description.nonEmpty)
            ()
        }

        // BUG: integer minimum/maximum constraints are silently ignored.
        "integer parameter minimum/maximum should not be ignored" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "page",
                                "in": "query",
                                "required": true,
                                "json": {"type": "integer", "minimum": 1, "maximum": 100}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val field = api.listItems.request.fields.head.asInstanceOf[HttpRoute.Field.Param[?, ?, ?]]
            assert(field.description.nonEmpty)
            ()
        }

        // BUG: string pattern constraint is silently ignored.
        "string parameter pattern should not be ignored" in pendingUntilFixed {
            val api = HttpOpenApi.fromJson("""{
                "openapi": "3.0.0",
                "info": {"title": "Test", "version": "1.0"},
                "paths": {
                    "/items": {
                        "get": {
                            "operationId": "listItems",
                            "parameters": [{
                                "name": "code",
                                "in": "query",
                                "required": true,
                                "json": {"type": "string", "pattern": "^[A-Z]{3}$"}
                            }],
                            "responses": {"200": {"description": "ok"}}
                        }
                    }
                }
            }""")
            val field = api.listItems.request.fields.head.asInstanceOf[HttpRoute.Field.Param[?, ?, ?]]
            assert(field.description.nonEmpty)
            ()
        }

        "generated operation name when no operationId" in {
            val api = HttpOpenApi.fromJson("""{
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
end HttpOpenApiTest
