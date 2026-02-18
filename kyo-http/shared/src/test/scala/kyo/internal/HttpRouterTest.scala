package kyo.internal

import kyo.*
import kyo.HttpPath
import kyo.HttpPath.*
import kyo.HttpRequest.Method

class HttpRouterTest extends Test:

    // Helper to assert handler matches
    def assertFound(result: Result[HttpRouter.FindError, HttpHandler[?]], expected: HttpHandler[?]): Assertion =
        result match
            case Result.Success(h) => assert(h eq expected, s"Expected handler $expected but got $h")
            case other             => fail(s"Expected Success with handler, got $other")

    def assertNotFound(result: Result[HttpRouter.FindError, HttpHandler[?]]): Assertion =
        result match
            case Result.Failure(HttpRouter.FindError.NotFound) => succeed
            case other                                         => fail(s"Expected NotFound, got $other")

    def assertMethodNotAllowed(result: Result[HttpRouter.FindError, HttpHandler[?]], expected: Set[Method]): Assertion =
        result match
            case Result.Failure(HttpRouter.FindError.MethodNotAllowed(allowed)) =>
                assert(allowed == expected, s"Expected allowed methods $expected but got $allowed")
            case other => fail(s"Expected MethodNotAllowed, got $other")

    "empty router" - {
        "returns NotFound for any path" in {
            val router = HttpRouter(Seq.empty)
            assertNotFound(router.find(Method.GET, "/any/path"))
        }

        "returns NotFound for root path" in {
            val router = HttpRouter(Seq.empty)
            assertNotFound(router.find(Method.GET, "/"))
        }
    }

    "single route" - {
        "exact match returns correct handler" in {
            val handler = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/users"), handler)
        }

        "method mismatch returns MethodNotAllowed with correct methods" in {
            val handler = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertMethodNotAllowed(router.find(Method.POST, "/users"), Set(Method.GET))
        }

        "path mismatch returns NotFound" in {
            val handler = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertNotFound(router.find(Method.GET, "/posts"))
        }

        "partial path returns NotFound" in {
            val handler = HttpHandler.get("/api/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertNotFound(router.find(Method.GET, "/api"))
        }

        "extra segments returns NotFound" in {
            val handler = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertNotFound(router.find(Method.GET, "/users/123"))
        }
    }

    "multiple routes" - {
        "routes with shared prefix return correct handlers" in {
            val usersHandler    = HttpHandler.get("/api/users") { _ => HttpResponse.ok }
            val postsHandler    = HttpHandler.get("/api/posts") { _ => HttpResponse.ok }
            val commentsHandler = HttpHandler.get("/api/comments") { _ => HttpResponse.ok }
            val router          = HttpRouter(Seq(usersHandler, postsHandler, commentsHandler))

            assertFound(router.find(Method.GET, "/api/users"), usersHandler)
            assertFound(router.find(Method.GET, "/api/posts"), postsHandler)
            assertFound(router.find(Method.GET, "/api/comments"), commentsHandler)
            assertNotFound(router.find(Method.GET, "/api/other"))
        }

        "different methods same path return correct handlers" in {
            val getHandler    = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val postHandler   = HttpHandler.post("/users") { _ => HttpResponse.ok }
            val deleteHandler = HttpHandler.delete("/users") { _ => HttpResponse.ok }
            val router        = HttpRouter(Seq(getHandler, postHandler, deleteHandler))

            assertFound(router.find(Method.GET, "/users"), getHandler)
            assertFound(router.find(Method.POST, "/users"), postHandler)
            assertFound(router.find(Method.DELETE, "/users"), deleteHandler)
            assertMethodNotAllowed(router.find(Method.PUT, "/users"), Set(Method.GET, Method.POST, Method.DELETE))
        }

        "binary search finds all handlers regardless of insertion order" in {
            // Add routes in non-alphabetic order to test sorting
            val zebraHandler  = HttpHandler.get("/zebra") { _ => HttpResponse.ok }
            val appleHandler  = HttpHandler.get("/apple") { _ => HttpResponse.ok }
            val mangoHandler  = HttpHandler.get("/mango") { _ => HttpResponse.ok }
            val bananaHandler = HttpHandler.get("/banana") { _ => HttpResponse.ok }
            val cherryHandler = HttpHandler.get("/cherry") { _ => HttpResponse.ok }
            val router        = HttpRouter(Seq(zebraHandler, appleHandler, mangoHandler, bananaHandler, cherryHandler))

            assertFound(router.find(Method.GET, "/apple"), appleHandler)
            assertFound(router.find(Method.GET, "/banana"), bananaHandler)
            assertFound(router.find(Method.GET, "/cherry"), cherryHandler)
            assertFound(router.find(Method.GET, "/mango"), mangoHandler)
            assertFound(router.find(Method.GET, "/zebra"), zebraHandler)
            assertNotFound(router.find(Method.GET, "/aardvark"))
            assertNotFound(router.find(Method.GET, "/zzz"))
        }
    }

    "path captures" - {
        "single capture matches any segment" in {
            val handler = HttpHandler.init(Method.GET, "users" / HttpPath.Capture[Int]("id")) { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/users/123"), handler)
            assertFound(router.find(Method.GET, "/users/abc"), handler) // Router matches, validation happens later
            assertNotFound(router.find(Method.GET, "/users"))
        }

        "multiple captures in path" in {
            val handler =
                HttpHandler.init(Method.GET, "users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId")) { _ =>
                    HttpResponse.ok
                }
            val router = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/users/1/posts/2"), handler)
            assertNotFound(router.find(Method.GET, "/users/1/posts"))
            assertNotFound(router.find(Method.GET, "/users/1"))
        }

        "literal takes priority over capture" in {
            val literalHandler = HttpHandler.get("/users/admin") { _ => HttpResponse.ok("literal") }
            val captureHandler = HttpHandler.init(Method.GET, "users" / HttpPath.Capture[String]("id")) { _ => HttpResponse.ok("capture") }
            val router         = HttpRouter(Seq(literalHandler, captureHandler))

            assertFound(router.find(Method.GET, "/users/admin"), literalHandler)
            assertFound(router.find(Method.GET, "/users/other"), captureHandler)
            assertFound(router.find(Method.GET, "/users/123"), captureHandler)
        }

        "capture with multiple literals at same level" in {
            val adminHandler   = HttpHandler.get("/users/admin") { _ => HttpResponse.ok }
            val meHandler      = HttpHandler.get("/users/me") { _ => HttpResponse.ok }
            val captureHandler = HttpHandler.init(Method.GET, "users" / HttpPath.Capture[String]("id")) { _ => HttpResponse.ok }
            val router         = HttpRouter(Seq(adminHandler, meHandler, captureHandler))

            assertFound(router.find(Method.GET, "/users/admin"), adminHandler)
            assertFound(router.find(Method.GET, "/users/me"), meHandler)
            assertFound(router.find(Method.GET, "/users/other"), captureHandler)
        }
    }

    "path edge cases" - {
        "root path" in {
            val handler = HttpHandler.get("/") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/"), handler)
            assertFound(router.find(Method.GET, ""), handler)
        }

        "trailing slash ignored" in {
            val handler = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/users"), handler)
            assertFound(router.find(Method.GET, "/users/"), handler)
        }

        "multiple slashes collapsed" in {
            val handler = HttpHandler.get("/api/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "//api//users"), handler)
            assertFound(router.find(Method.GET, "/api///users/"), handler)
        }

        "no leading slash" in {
            val handler = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "users"), handler)
        }

        "path with special characters" in {
            val handler = HttpHandler.get("/api/v1.0/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/api/v1.0/users"), handler)
        }
    }

    "all HTTP methods" - {
        "GET" in {
            val handler = HttpHandler.get("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/test"), handler)
        }

        "POST" in {
            val handler = HttpHandler.post("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.POST, "/test"), handler)
        }

        "PUT" in {
            val handler = HttpHandler.put("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.PUT, "/test"), handler)
        }

        "PATCH" in {
            val handler = HttpHandler.patch("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.PATCH, "/test"), handler)
        }

        "DELETE" in {
            val handler = HttpHandler.delete("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.DELETE, "/test"), handler)
        }

        "HEAD" in {
            val handler = HttpHandler.head("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.HEAD, "/test"), handler)
        }

        "OPTIONS" in {
            val handler = HttpHandler.options("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.OPTIONS, "/test"), handler)
        }

        "TRACE" in {
            val handler = HttpHandler.init(Method.TRACE, "/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.TRACE, "/test"), handler)
        }

        "CONNECT" in {
            val handler = HttpHandler.init(Method.CONNECT, "/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.CONNECT, "/test"), handler)
        }
    }

    "deep nesting" - {
        "many levels deep" in {
            val handler = HttpHandler.get("/a/b/c/d/e/f/g/h") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/a/b/c/d/e/f/g/h"), handler)
            assertNotFound(router.find(Method.GET, "/a/b/c/d/e/f/g"))
            assertNotFound(router.find(Method.GET, "/a/b/c/d/e/f/g/h/i"))
        }

        "branching at each level returns correct handlers" in {
            val abcHandler = HttpHandler.get("/a/b/c") { _ => HttpResponse.ok }
            val abdHandler = HttpHandler.get("/a/b/d") { _ => HttpResponse.ok }
            val aefHandler = HttpHandler.get("/a/e/f") { _ => HttpResponse.ok }
            val ghiHandler = HttpHandler.get("/g/h/i") { _ => HttpResponse.ok }
            val router     = HttpRouter(Seq(abcHandler, abdHandler, aefHandler, ghiHandler))

            assertFound(router.find(Method.GET, "/a/b/c"), abcHandler)
            assertFound(router.find(Method.GET, "/a/b/d"), abdHandler)
            assertFound(router.find(Method.GET, "/a/e/f"), aefHandler)
            assertFound(router.find(Method.GET, "/g/h/i"), ghiHandler)
            assertNotFound(router.find(Method.GET, "/a/b/e"))
            assertNotFound(router.find(Method.GET, "/a/e/c"))
        }
    }

    "realistic API structure" - {
        "REST-like routes return correct handlers" in {
            val listUsers  = HttpHandler.get("/api/v1/users") { _ => HttpResponse.ok }
            val createUser = HttpHandler.post("/api/v1/users") { _ => HttpResponse.ok }
            val getUser    = HttpHandler.init(Method.GET, "api" / "v1" / "users" / HttpPath.Capture[Int]("id")) { _ => HttpResponse.ok }
            val updateUser = HttpHandler.init(Method.PUT, "api" / "v1" / "users" / HttpPath.Capture[Int]("id")) { _ => HttpResponse.ok }
            val deleteUser = HttpHandler.init(Method.DELETE, "api" / "v1" / "users" / HttpPath.Capture[Int]("id")) { _ => HttpResponse.ok }
            val getUserPosts =
                HttpHandler.init(Method.GET, "api" / "v1" / "users" / HttpPath.Capture[Int]("id") / "posts") { _ => HttpResponse.ok }
            val listPosts = HttpHandler.get("/api/v1/posts") { _ => HttpResponse.ok }
            val getPost   = HttpHandler.init(Method.GET, "api" / "v1" / "posts" / HttpPath.Capture[Int]("id")) { _ => HttpResponse.ok }

            val router = HttpRouter(Seq(listUsers, createUser, getUser, updateUser, deleteUser, getUserPosts, listPosts, getPost))

            // Users collection
            assertFound(router.find(Method.GET, "/api/v1/users"), listUsers)
            assertFound(router.find(Method.POST, "/api/v1/users"), createUser)
            assertMethodNotAllowed(router.find(Method.DELETE, "/api/v1/users"), Set(Method.GET, Method.POST))

            // User resource
            assertFound(router.find(Method.GET, "/api/v1/users/123"), getUser)
            assertFound(router.find(Method.PUT, "/api/v1/users/456"), updateUser)
            assertFound(router.find(Method.DELETE, "/api/v1/users/789"), deleteUser)
            assertMethodNotAllowed(router.find(Method.PATCH, "/api/v1/users/123"), Set(Method.GET, Method.PUT, Method.DELETE))

            // Nested resource
            assertFound(router.find(Method.GET, "/api/v1/users/123/posts"), getUserPosts)

            // Posts
            assertFound(router.find(Method.GET, "/api/v1/posts"), listPosts)
            assertFound(router.find(Method.GET, "/api/v1/posts/999"), getPost)
        }

        "versioned API with v1 and v2" in {
            val v1Users = HttpHandler.get("/api/v1/users") { _ => HttpResponse.ok }
            val v2Users = HttpHandler.get("/api/v2/users") { _ => HttpResponse.ok }
            val v1User  = HttpHandler.init(Method.GET, "api" / "v1" / "users" / HttpPath.Capture[Int]("id")) { _ => HttpResponse.ok }
            val v2User  = HttpHandler.init(Method.GET, "api" / "v2" / "users" / HttpPath.Capture[String]("id")) { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(v1Users, v2Users, v1User, v2User))

            assertFound(router.find(Method.GET, "/api/v1/users"), v1Users)
            assertFound(router.find(Method.GET, "/api/v2/users"), v2Users)
            assertFound(router.find(Method.GET, "/api/v1/users/123"), v1User)
            assertFound(router.find(Method.GET, "/api/v2/users/abc-123"), v2User)
            assertNotFound(router.find(Method.GET, "/api/v3/users"))
        }

        "deeply nested resources" in {
            val getComment = HttpHandler.init(
                Method.GET,
                "users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId") / "comments" / HttpPath.Capture[Int](
                    "commentId"
                )
            ) { _ => HttpResponse.ok }
            val listComments = HttpHandler.init(
                Method.GET,
                "users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId") / "comments"
            ) { _ => HttpResponse.ok }
            val router = HttpRouter(Seq(getComment, listComments))

            assertFound(router.find(Method.GET, "/users/1/posts/2/comments"), listComments)
            assertFound(router.find(Method.GET, "/users/1/posts/2/comments/3"), getComment)
            assertNotFound(router.find(Method.GET, "/users/1/posts/2/comments/3/replies"))
        }

        "web application routes" in {
            val home      = HttpHandler.get("/") { _ => HttpResponse.ok }
            val login     = HttpHandler.get("/login") { _ => HttpResponse.ok }
            val doLogin   = HttpHandler.post("/login") { _ => HttpResponse.ok }
            val logout    = HttpHandler.post("/logout") { _ => HttpResponse.ok }
            val profile   = HttpHandler.get("/profile") { _ => HttpResponse.ok }
            val settings  = HttpHandler.get("/settings") { _ => HttpResponse.ok }
            val dashboard = HttpHandler.get("/dashboard") { _ => HttpResponse.ok }
            val router    = HttpRouter(Seq(home, login, doLogin, logout, profile, settings, dashboard))

            assertFound(router.find(Method.GET, "/"), home)
            assertFound(router.find(Method.GET, "/login"), login)
            assertFound(router.find(Method.POST, "/login"), doLogin)
            assertFound(router.find(Method.POST, "/logout"), logout)
            assertFound(router.find(Method.GET, "/profile"), profile)
            assertFound(router.find(Method.GET, "/settings"), settings)
            assertFound(router.find(Method.GET, "/dashboard"), dashboard)
            assertMethodNotAllowed(router.find(Method.GET, "/logout"), Set(Method.POST))
        }

        "admin panel with mixed static and dynamic routes" in {
            val adminDashboard = HttpHandler.get("/admin/dashboard") { _ => HttpResponse.ok }
            val adminUsers     = HttpHandler.get("/admin/users") { _ => HttpResponse.ok }
            val adminUser      = HttpHandler.init(Method.GET, "admin" / "users" / HttpPath.Capture[Int]("id")) { _ => HttpResponse.ok }
            val adminUserEdit = HttpHandler.init(Method.GET, "admin" / "users" / HttpPath.Capture[Int]("id") / "edit") { _ =>
                HttpResponse.ok
            }
            val adminSettings = HttpHandler.get("/admin/settings") { _ => HttpResponse.ok }
            val router        = HttpRouter(Seq(adminDashboard, adminUsers, adminUser, adminUserEdit, adminSettings))

            assertFound(router.find(Method.GET, "/admin/dashboard"), adminDashboard)
            assertFound(router.find(Method.GET, "/admin/users"), adminUsers)
            assertFound(router.find(Method.GET, "/admin/users/42"), adminUser)
            assertFound(router.find(Method.GET, "/admin/users/42/edit"), adminUserEdit)
            assertFound(router.find(Method.GET, "/admin/settings"), adminSettings)
            assertNotFound(router.find(Method.GET, "/admin"))
            assertNotFound(router.find(Method.GET, "/admin/unknown"))
        }

        "static assets and files" in {
            val css    = HttpHandler.get("/static/css/style.css") { _ => HttpResponse.ok }
            val js     = HttpHandler.get("/static/js/app.js") { _ => HttpResponse.ok }
            val image  = HttpHandler.init(Method.GET, "images" / HttpPath.Capture[String]("name")) { _ => HttpResponse.ok }
            val file   = HttpHandler.init(Method.GET, "files" / HttpPath.Capture[String]("path")) { _ => HttpResponse.ok }
            val router = HttpRouter(Seq(css, js, image, file))

            assertFound(router.find(Method.GET, "/static/css/style.css"), css)
            assertFound(router.find(Method.GET, "/static/js/app.js"), js)
            assertFound(router.find(Method.GET, "/images/logo.png"), image)
            assertFound(router.find(Method.GET, "/images/banner.jpg"), image)
            assertFound(router.find(Method.GET, "/files/document.pdf"), file)
            assertNotFound(router.find(Method.GET, "/static/css/other.css"))
        }

        "webhooks and callbacks" in {
            val githubWebhook  = HttpHandler.post("/webhooks/github") { _ => HttpResponse.ok }
            val stripeWebhook  = HttpHandler.post("/webhooks/stripe") { _ => HttpResponse.ok }
            val genericWebhook = HttpHandler.init(Method.POST, "webhooks" / HttpPath.Capture[String]("provider")) { _ => HttpResponse.ok }
            val oauthCallback = HttpHandler.init(Method.GET, "auth" / HttpPath.Capture[String]("provider") / "callback") { _ =>
                HttpResponse.ok
            }
            val router = HttpRouter(Seq(githubWebhook, stripeWebhook, genericWebhook, oauthCallback))

            // Specific webhooks take priority
            assertFound(router.find(Method.POST, "/webhooks/github"), githubWebhook)
            assertFound(router.find(Method.POST, "/webhooks/stripe"), stripeWebhook)
            // Generic webhook for others
            assertFound(router.find(Method.POST, "/webhooks/slack"), genericWebhook)
            assertFound(router.find(Method.POST, "/webhooks/custom"), genericWebhook)
            // OAuth callbacks
            assertFound(router.find(Method.GET, "/auth/google/callback"), oauthCallback)
            assertFound(router.find(Method.GET, "/auth/facebook/callback"), oauthCallback)
        }

        "health and monitoring endpoints" in {
            val health  = HttpHandler.get("/health") { _ => HttpResponse.ok }
            val ready   = HttpHandler.get("/ready") { _ => HttpResponse.ok }
            val live    = HttpHandler.get("/live") { _ => HttpResponse.ok }
            val metrics = HttpHandler.get("/metrics") { _ => HttpResponse.ok }
            val info    = HttpHandler.get("/info") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(health, ready, live, metrics, info))

            assertFound(router.find(Method.GET, "/health"), health)
            assertFound(router.find(Method.GET, "/ready"), ready)
            assertFound(router.find(Method.GET, "/live"), live)
            assertFound(router.find(Method.GET, "/metrics"), metrics)
            assertFound(router.find(Method.GET, "/info"), info)
        }

        "microservice with multiple resource types" in {
            // Products
            val listProducts = HttpHandler.get("/products") { _ => HttpResponse.ok }
            val getProduct   = HttpHandler.init(Method.GET, "products" / HttpPath.Capture[String]("sku")) { _ => HttpResponse.ok }
            // Orders
            val listOrders  = HttpHandler.get("/orders") { _ => HttpResponse.ok }
            val createOrder = HttpHandler.post("/orders") { _ => HttpResponse.ok }
            val getOrder    = HttpHandler.init(Method.GET, "orders" / HttpPath.Capture[String]("id")) { _ => HttpResponse.ok }
            val cancelOrder = HttpHandler.init(Method.DELETE, "orders" / HttpPath.Capture[String]("id")) { _ => HttpResponse.ok }
            // Order items
            val getOrderItems = HttpHandler.init(Method.GET, "orders" / HttpPath.Capture[String]("id") / "items") { _ => HttpResponse.ok }
            // Customers
            val getCustomer = HttpHandler.init(Method.GET, "customers" / HttpPath.Capture[String]("id")) { _ => HttpResponse.ok }
            val getCustomerOrders = HttpHandler.init(Method.GET, "customers" / HttpPath.Capture[String]("id") / "orders") { _ =>
                HttpResponse.ok
            }

            val router = HttpRouter(Seq(
                listProducts,
                getProduct,
                listOrders,
                createOrder,
                getOrder,
                cancelOrder,
                getOrderItems,
                getCustomer,
                getCustomerOrders
            ))

            // Products
            assertFound(router.find(Method.GET, "/products"), listProducts)
            assertFound(router.find(Method.GET, "/products/SKU-123"), getProduct)

            // Orders
            assertFound(router.find(Method.GET, "/orders"), listOrders)
            assertFound(router.find(Method.POST, "/orders"), createOrder)
            assertFound(router.find(Method.GET, "/orders/ORD-456"), getOrder)
            assertFound(router.find(Method.DELETE, "/orders/ORD-456"), cancelOrder)
            assertFound(router.find(Method.GET, "/orders/ORD-456/items"), getOrderItems)
            assertMethodNotAllowed(router.find(Method.PUT, "/orders/ORD-456"), Set(Method.GET, Method.DELETE))

            // Customers
            assertFound(router.find(Method.GET, "/customers/CUST-789"), getCustomer)
            assertFound(router.find(Method.GET, "/customers/CUST-789/orders"), getCustomerOrders)
        }
    }

    "duplicate route registration" - {
        "later handler overwrites earlier for same path and method" in {
            val firstHandler  = HttpHandler.get("/users") { _ => HttpResponse.ok("first") }
            val secondHandler = HttpHandler.get("/users") { _ => HttpResponse.ok("second") }
            val router        = HttpRouter(Seq(firstHandler, secondHandler))

            // Second handler should win
            assertFound(router.find(Method.GET, "/users"), secondHandler)
        }

        "different methods on same path are independent" in {
            val getHandler  = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val postHandler = HttpHandler.post("/users") { _ => HttpResponse.ok }
            val getHandler2 = HttpHandler.get("/users") { _ => HttpResponse.ok("replaced") }
            val router      = HttpRouter(Seq(getHandler, postHandler, getHandler2))

            // GET replaced, POST unchanged
            assertFound(router.find(Method.GET, "/users"), getHandler2)
            assertFound(router.find(Method.POST, "/users"), postHandler)
        }
    }

    "segment comparison edge cases" - {
        "route segment shorter than path segment" in {
            val handler = HttpHandler.get("/ab") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertNotFound(router.find(Method.GET, "/abc"))
            assertFound(router.find(Method.GET, "/ab"), handler)
        }

        "route segment longer than path segment" in {
            val handler = HttpHandler.get("/abc") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertNotFound(router.find(Method.GET, "/ab"))
            assertFound(router.find(Method.GET, "/abc"), handler)
        }

        "similar prefixes distinguished correctly" in {
            val usersHandler    = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val userHandler     = HttpHandler.get("/user") { _ => HttpResponse.ok }
            val usersNewHandler = HttpHandler.get("/users_new") { _ => HttpResponse.ok }
            val router          = HttpRouter(Seq(usersHandler, userHandler, usersNewHandler))

            assertFound(router.find(Method.GET, "/users"), usersHandler)
            assertFound(router.find(Method.GET, "/user"), userHandler)
            assertFound(router.find(Method.GET, "/users_new"), usersNewHandler)
            assertNotFound(router.find(Method.GET, "/use"))
            assertNotFound(router.find(Method.GET, "/userss"))
        }

        "single character segments" in {
            val aHandler = HttpHandler.get("/a") { _ => HttpResponse.ok }
            val bHandler = HttpHandler.get("/b") { _ => HttpResponse.ok }
            val router   = HttpRouter(Seq(aHandler, bHandler))

            assertFound(router.find(Method.GET, "/a"), aHandler)
            assertFound(router.find(Method.GET, "/b"), bHandler)
            assertNotFound(router.find(Method.GET, "/c"))
        }
    }

    "unicode in paths" - {
        "unicode segment names" in {
            val handler = HttpHandler.get("/users/日本語") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/users/日本語"), handler)
            assertNotFound(router.find(Method.GET, "/users/中文"))
        }

        "emoji in path" in {
            val handler = HttpHandler.get("/api/🚀/launch") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/api/🚀/launch"), handler)
        }

        "mixed unicode and ascii" in {
            val handler = HttpHandler.get("/api/データ/items") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/api/データ/items"), handler)
        }
    }

    "very long paths" - {
        "20 segments deep" in {
            val path    = (1 to 20).map(i => s"seg$i").mkString("/", "/", "")
            val handler = HttpHandler.get(path) { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))

            assertFound(router.find(Method.GET, path), handler)
            assertNotFound(router.find(Method.GET, path + "/extra"))
            assertNotFound(router.find(Method.GET, path.dropRight(5))) // Remove "/seg20"
        }

        "many routes at same level" in {
            val handlers = (1 to 100).map { i =>
                HttpHandler.get(s"/items/item$i") { _ => HttpResponse.ok }
            }
            val router = HttpRouter(handlers)

            // Check first, middle, and last
            assertFound(router.find(Method.GET, "/items/item1"), handlers.head)
            assertFound(router.find(Method.GET, "/items/item50"), handlers(49))
            assertFound(router.find(Method.GET, "/items/item100"), handlers(99))
            assertNotFound(router.find(Method.GET, "/items/item101"))
            assertNotFound(router.find(Method.GET, "/items/item0"))
        }
    }

    "binary search boundary cases" - {
        "single child node" in {
            val handler = HttpHandler.get("/only") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            assertFound(router.find(Method.GET, "/only"), handler)
            assertNotFound(router.find(Method.GET, "/other"))
        }

        "match first in sorted order" in {
            val aHandler = HttpHandler.get("/aaa") { _ => HttpResponse.ok }
            val bHandler = HttpHandler.get("/bbb") { _ => HttpResponse.ok }
            val cHandler = HttpHandler.get("/ccc") { _ => HttpResponse.ok }
            val router   = HttpRouter(Seq(cHandler, aHandler, bHandler)) // Insert out of order

            assertFound(router.find(Method.GET, "/aaa"), aHandler)
        }

        "match last in sorted order" in {
            val aHandler = HttpHandler.get("/aaa") { _ => HttpResponse.ok }
            val bHandler = HttpHandler.get("/bbb") { _ => HttpResponse.ok }
            val cHandler = HttpHandler.get("/ccc") { _ => HttpResponse.ok }
            val router   = HttpRouter(Seq(cHandler, aHandler, bHandler))

            assertFound(router.find(Method.GET, "/ccc"), cHandler)
        }

        "match middle in sorted order" in {
            val aHandler = HttpHandler.get("/aaa") { _ => HttpResponse.ok }
            val bHandler = HttpHandler.get("/bbb") { _ => HttpResponse.ok }
            val cHandler = HttpHandler.get("/ccc") { _ => HttpResponse.ok }
            val router   = HttpRouter(Seq(cHandler, aHandler, bHandler))

            assertFound(router.find(Method.GET, "/bbb"), bHandler)
        }

        "not found before first" in {
            val handlers = Seq(
                HttpHandler.get("/bbb") { _ => HttpResponse.ok },
                HttpHandler.get("/ccc") { _ => HttpResponse.ok }
            )
            val router = HttpRouter(handlers)
            assertNotFound(router.find(Method.GET, "/aaa"))
        }

        "not found after last" in {
            val handlers = Seq(
                HttpHandler.get("/aaa") { _ => HttpResponse.ok },
                HttpHandler.get("/bbb") { _ => HttpResponse.ok }
            )
            val router = HttpRouter(handlers)
            assertNotFound(router.find(Method.GET, "/ccc"))
        }

        "not found between elements" in {
            val handlers = Seq(
                HttpHandler.get("/aaa") { _ => HttpResponse.ok },
                HttpHandler.get("/ccc") { _ => HttpResponse.ok }
            )
            val router = HttpRouter(handlers)
            assertNotFound(router.find(Method.GET, "/bbb"))
        }
    }

    "intermediate nodes without handlers" - {
        "intermediate node returns NotFound not MethodNotAllowed" in {
            // Route: /api/v1/users - only the leaf has a handler
            val handler = HttpHandler.get("/api/v1/users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))

            // /api exists as a node but has no handlers
            assertNotFound(router.find(Method.GET, "/api"))
            assertNotFound(router.find(Method.POST, "/api"))

            // /api/v1 exists as a node but has no handlers
            assertNotFound(router.find(Method.GET, "/api/v1"))
        }

        "mixed intermediate and leaf handlers" in {
            val apiHandler   = HttpHandler.get("/api") { _ => HttpResponse.ok }
            val usersHandler = HttpHandler.get("/api/v1/users") { _ => HttpResponse.ok }
            val router       = HttpRouter(Seq(apiHandler, usersHandler))

            assertFound(router.find(Method.GET, "/api"), apiHandler)
            assertNotFound(router.find(Method.GET, "/api/v1")) // Intermediate, no handler
            assertFound(router.find(Method.GET, "/api/v1/users"), usersHandler)
        }
    }

    "unknown HTTP method" - {
        "throws IllegalArgumentException for unknown first letter" in {
            val handler = HttpHandler.get("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            // XCUSTOM starts with 'X' which is not a known HTTP method first letter
            val unknownMethod = Method("XCUSTOM")

            assertThrows[IllegalArgumentException] {
                router.find(unknownMethod, "/test")
            }
        }

        "throws IllegalArgumentException for method starting with P but invalid length" in {
            val handler = HttpHandler.get("/test") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))
            // PROPFIND starts with P but has 8 chars (not 3, 4, or 5)
            val unknownMethod = Method("PROPFIND")

            assertThrows[IllegalArgumentException] {
                router.find(unknownMethod, "/test")
            }
        }
    }

    "route misconfiguration scenarios" - {
        "conflicting captures at same position - last wins" in {
            // Two routes with captures at same position - they share the same capture child
            // This is a misconfiguration: user probably meant different things
            val byId   = HttpHandler.init(Method.GET, "users" / HttpPath.Capture[Int]("id")) { _ => HttpResponse.ok("byId") }
            val byName = HttpHandler.init(Method.GET, "users" / HttpPath.Capture[String]("name")) { _ => HttpResponse.ok("byName") }
            val router = HttpRouter(Seq(byId, byName))

            // Both resolve to the same capture child - last registered wins
            assertFound(router.find(Method.GET, "/users/123"), byName)
            assertFound(router.find(Method.GET, "/users/john"), byName)
        }

        "ambiguous routes - literal vs capture at different levels" in {
            // /a/:x/c and /a/b/:y - request /a/b/c could match either
            // Behavior: literal "b" takes priority over capture :x
            val route1 = HttpHandler.init(Method.GET, "a" / HttpPath.Capture[String]("x") / "c") { _ => HttpResponse.ok("route1") }
            val route2 = HttpHandler.init(Method.GET, "a" / "b" / HttpPath.Capture[String]("y")) { _ => HttpResponse.ok("route2") }
            val router = HttpRouter(Seq(route1, route2))

            // /a/b/c - "b" matches literal, then :y matches "c"
            assertFound(router.find(Method.GET, "/a/b/c"), route2)
            // /a/x/c - "x" matches capture :x, then "c" matches literal
            assertFound(router.find(Method.GET, "/a/x/c"), route1)
            // /a/b/d - "b" matches literal, then :y matches "d"
            assertFound(router.find(Method.GET, "/a/b/d"), route2)
        }

        "route shadows another - more specific should be registered" in {
            // /users/:id shadows /users/admin if registered in wrong order
            // With literal priority, order shouldn't matter - but let's verify
            val capture = HttpHandler.init(Method.GET, "users" / HttpPath.Capture[String]("id")) { _ => HttpResponse.ok("capture") }
            val literal = HttpHandler.get("/users/admin") { _ => HttpResponse.ok("literal") }

            // Order 1: capture first, then literal
            val router1 = HttpRouter(Seq(capture, literal))
            assertFound(router1.find(Method.GET, "/users/admin"), literal)
            assertFound(router1.find(Method.GET, "/users/other"), capture)

            // Order 2: literal first, then capture
            val router2 = HttpRouter(Seq(literal, capture))
            assertFound(router2.find(Method.GET, "/users/admin"), literal)
            assertFound(router2.find(Method.GET, "/users/other"), capture)
        }

        "multiple captures - no collision in routing" in {
            // /users/:userId/posts/:postId - two captures
            // Router doesn't care about names, just positions
            val handler =
                HttpHandler.init(Method.GET, "users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId")) { _ =>
                    HttpResponse.ok
                }
            val router = HttpRouter(Seq(handler))

            assertFound(router.find(Method.GET, "/users/1/posts/2"), handler)
        }

        "only captures route - valid but unusual" in {
            val handler = HttpHandler.init(
                Method.GET,
                HttpPath.Capture[String]("a") / HttpPath.Capture[String]("b") / HttpPath.Capture[String]("c")
            ) { _ =>
                HttpResponse.ok
            }
            val router = HttpRouter(Seq(handler))

            assertFound(router.find(Method.GET, "/x/y/z"), handler)
            assertFound(router.find(Method.GET, "/1/2/3"), handler)
            assertNotFound(router.find(Method.GET, "/x/y"))
            assertNotFound(router.find(Method.GET, "/x/y/z/w"))
        }

        "very similar paths - typo potential" in {
            val user   = HttpHandler.get("/user") { _ => HttpResponse.ok }
            val users  = HttpHandler.get("/users") { _ => HttpResponse.ok }
            val router = HttpRouter(Seq(user, users))

            assertFound(router.find(Method.GET, "/user"), user)
            assertFound(router.find(Method.GET, "/users"), users)
            // Typo goes to NotFound, not wrong handler
            assertNotFound(router.find(Method.GET, "/usr"))
            assertNotFound(router.find(Method.GET, "/userss"))
        }

        "trailing slash in route definition" in {
            // Route defined with trailing slash
            val withSlash    = HttpHandler.get("/users/") { _ => HttpResponse.ok("withSlash") }
            val withoutSlash = HttpHandler.get("/posts") { _ => HttpResponse.ok("withoutSlash") }
            val router       = HttpRouter(Seq(withSlash, withoutSlash))

            // Trailing slash is stripped during route parsing, so /users/ becomes /users
            assertFound(router.find(Method.GET, "/users"), withSlash)
            assertFound(router.find(Method.GET, "/users/"), withSlash)
            assertFound(router.find(Method.GET, "/posts"), withoutSlash)
            assertFound(router.find(Method.GET, "/posts/"), withoutSlash)
        }

        "empty segments in path - collapsed" in {
            // Route with apparent empty segment
            val handler = HttpHandler.get("/api//users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))

            // Empty segments are filtered out during parsing
            assertFound(router.find(Method.GET, "/api/users"), handler)
            assertFound(router.find(Method.GET, "/api//users"), handler)
        }

        "capture after literal with same prefix" in {
            // /users/list and /users/:id - "list" could be mistaken for an ID
            val list   = HttpHandler.get("/users/list") { _ => HttpResponse.ok("list") }
            val byId   = HttpHandler.init(Method.GET, "users" / HttpPath.Capture[String]("id")) { _ => HttpResponse.ok("byId") }
            val router = HttpRouter(Seq(list, byId))

            // "list" is a literal, takes priority
            assertFound(router.find(Method.GET, "/users/list"), list)
            // Anything else goes to capture
            assertFound(router.find(Method.GET, "/users/123"), byId)
            assertFound(router.find(Method.GET, "/users/create"), byId)
        }

        "overlapping method registrations" in {
            // Same path with overlapping method sets
            val get1   = HttpHandler.get("/resource") { _ => HttpResponse.ok("get1") }
            val get2   = HttpHandler.get("/resource") { _ => HttpResponse.ok("get2") }
            val post   = HttpHandler.post("/resource") { _ => HttpResponse.ok("post") }
            val router = HttpRouter(Seq(get1, post, get2))

            // GET was registered twice - last one wins
            assertFound(router.find(Method.GET, "/resource"), get2)
            // POST unaffected
            assertFound(router.find(Method.POST, "/resource"), post)
            // Allowed methods reflects final state
            assertMethodNotAllowed(router.find(Method.PUT, "/resource"), Set(Method.GET, Method.POST))
        }

        "deeply nested with partial handlers" in {
            // Only leaf has handler, but intermediate paths might be requested
            val deepHandler = HttpHandler.get("/a/b/c/d/e") { _ => HttpResponse.ok }
            val router      = HttpRouter(Seq(deepHandler))

            assertFound(router.find(Method.GET, "/a/b/c/d/e"), deepHandler)
            // All intermediate paths return NotFound (not MethodNotAllowed)
            assertNotFound(router.find(Method.GET, "/a"))
            assertNotFound(router.find(Method.GET, "/a/b"))
            assertNotFound(router.find(Method.GET, "/a/b/c"))
            assertNotFound(router.find(Method.GET, "/a/b/c/d"))
            // Beyond the leaf also NotFound
            assertNotFound(router.find(Method.GET, "/a/b/c/d/e/f"))
        }

        "capture consumes reserved words" in {
            // Capture might accidentally match reserved-looking segments
            val handler = HttpHandler.init(Method.GET, "api" / HttpPath.Capture[String]("version") / "users") { _ => HttpResponse.ok }
            val router  = HttpRouter(Seq(handler))

            // These all work because :version is just a string capture
            assertFound(router.find(Method.GET, "/api/v1/users"), handler)
            assertFound(router.find(Method.GET, "/api/latest/users"), handler)
            assertFound(router.find(Method.GET, "/api/null/users"), handler)
            assertFound(router.find(Method.GET, "/api/undefined/users"), handler)
            // Note: /api//users collapses to /api/users (2 segments), doesn't match 3-segment route
            assertNotFound(router.find(Method.GET, "/api//users"))
        }
    }

end HttpRouterTest
