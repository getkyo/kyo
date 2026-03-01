# Path Matching Analysis: kyo-http vs Other Frameworks

## 1. Current kyo-http Implementation

### HttpPath ADT (`kyo-http/shared/src/main/scala/kyo/HttpPath.scala`)

Four variants:
- **Literal(value: String)** — exact segment match (can contain `/` which gets split)
- **Capture[N, A](fieldName, wireName, codec)** — matches one segment, extracts typed value
- **Rest[N](fieldName)** — matches remaining path (all segments from that point on)
- **Concat[A, B](left, right)** — composition via `/` operator

Type safety: captures produce `N ~ A` (named field types), composed via intersection types (`A & B`).

### HttpRouter (`kyo-http/shared/src/main/scala/kyo/internal/HttpRouter.scala`)

Trie-based router serialized into flat arrays. At each node during the trie walk:
1. **Binary search** literal children (sorted array)
2. If no literal match, try **captureChild** (single-segment wildcard)
3. If no capture child, try **restChild** (consumes everything remaining)

Priority: literal > capture > rest (hardcoded in the walk).

### What Happens if Rest is in the Middle of a Path Today?

Looking at `pathToSegments` (line 324-334) and `insert` (line 350-386):

When `Rest` is encountered, it's converted to `Segment.Rest`. In `insert`, when processing `Segment.Rest`, it creates a `restChild` node and **registers the endpoint on that child node directly** (line 384) — it does NOT recurse with remaining segments. Any segments after `Rest` in the path definition are silently ignored.

So `"api" / Rest("path") / "suffix"` would match `/api/anything/here` and capture `path = "anything/here"`, but the `"suffix"` literal is never inserted into the trie. The route would match regardless of whether the path ends with `/suffix` or not. This is a silent bug/footgun.

### Performance Characteristics

- O(S * log L) per request where S = number of segments, L = max literal siblings at any node
- Zero-allocation fast path when no captures exist
- URL decoding only on captured segments
- Pre-computed streaming flags and error results

---

## 2. Framework Comparison

### Express.js (v5, path-to-regexp)
- **Named parameters**: `/users/:id` — single segment
- **Catch-all / splat**: `/files/*splat` or `/files/{*splat}` (v5 requires named wildcard)
- **Regex constraints**: `/user/:userId(\d+)` — inline regex on parameters
- **Middle wildcards**: Yes, parameters (including constrained ones) can appear anywhere
- **Optional segments**: Previously `?` modifier (v4); v5 changed this
- **No unnamed wildcards in v5**: `*` alone is no longer valid

### Spring MVC / WebFlux (PathPatternParser)
- **Single-segment wildcard**: `?` (one char), `*` (one segment)
- **Multi-segment wildcard**: `**` — but **only at end of pattern** in PathPatternParser
- **Path variables**: `{id}` with optional regex: `{id:\\d+}`
- **Catch-all**: `{*spring}` captures 0+ segments at end
- **Middle wildcards**: `*` works mid-path (single segment), `**` does NOT (end only)
- **Regex in variables**: Yes, e.g. `{id:[a-z]+}`
- AntPathMatcher (legacy) allowed `**` in middle but was slower

### Akka HTTP / Pekko HTTP
- **PathMatcher DSL**: composable Scala matchers
- **Segment**: matches one segment, extracts String
- **IntNumber, LongNumber, etc.**: typed single-segment extractors
- **Regex**: `"[a-f0-9]+".r` as a PathMatcher — matches and extracts
- **Remaining**: captures all remaining path as raw String
- **RemainingPath**: captures remaining as decoded Uri.Path
- **Segments**: matches remaining segments as `List[String]`
- **Middle wildcards**: Yes, since PathMatchers compose freely — `path("api" / Segment / "info")`
- **pathPrefix vs path**: prefix matching vs exact matching
- **Tilde combinator**: `~` combines extracted values into tuples

### http4s
- **Literal segments**: pattern matching with `/`
- **Type extractors**: `IntVar`, `LongVar`, `UUIDVar` — via Scala unapply
- **Catch-all**: right-associative `/:`  extractor captures remaining `Path`
- **File extensions**: `~` extractor for extensions
- **Custom extractors**: any `unapply(String): Option[T]`
- **Middle wildcards**: Yes, via pattern matching flexibility — `case GET -> Root / "api" / _ / "info"`
- **No regex**: relies on Scala pattern matching, not regex
- **No named parameters**: purely structural matching

### Play Framework
- **Named params**: `:id` — single segment
- **Wildcard**: `*path` — captures multiple segments (uses `.*` regex)
- **Custom regex**: `$id<[0-9]+>` — arbitrary regex on a parameter
- **Middle wildcards**: `:id` works anywhere; `*path` technically works mid-route but is unusual
- **Defined in `routes` file**: external DSL, not type-safe at definition site

### Vert.x
- **Path params**: `/flights/:from-:to`
- **Wildcard suffix**: trailing `*` matches prefix
- **Full regex routes**: `router.routeWithRegex(".*foo")` with named capture groups
- **Middle wildcards**: params work anywhere; regex is fully flexible
- **No type safety**: all string-based

### FastAPI (Starlette)
- **Path params**: `{user_id}` — single segment
- **Path converter**: `{file_path:path}` — captures rest including slashes
- **Type converters**: `{id:int}`, `{id:str}`
- **Regex validation**: via `Path(regex=...)` on parameter metadata, not in route pattern itself
- **Middle params**: Yes, `{id}` works anywhere
- **No true regex routing**: unlike Django
- **Order-dependent**: first-match wins

---

## 3. Feature Matrix

| Feature                         | kyo-http | Express v5 | Spring | Akka/Pekko | http4s | Play | Vert.x | FastAPI |
|---------------------------------|----------|------------|--------|------------|--------|------|--------|---------|
| Literal segments                | Yes      | Yes        | Yes    | Yes        | Yes    | Yes  | Yes    | Yes     |
| Single-segment capture          | Yes      | Yes        | Yes    | Yes        | Yes    | Yes  | Yes    | Yes     |
| Typed captures                  | Yes      | No         | No     | Yes        | Yes    | No   | No     | Yes     |
| Catch-all / rest-of-path        | Yes      | Yes        | Yes    | Yes        | Yes    | Yes  | Yes    | Yes     |
| Capture in middle position      | Yes      | Yes        | Yes    | Yes        | Yes    | Yes  | Yes    | Yes     |
| Rest/catch-all in middle        | No*      | No         | No     | Yes**      | Yes**  | No   | No     | No      |
| Regex constraints on params     | No       | Yes        | Yes    | Yes        | No***  | Yes  | Yes    | No****  |
| Glob/wildcard within segment    | No       | No         | Spring | No         | No     | No   | No     | No      |
| Optional segments               | No       | Limited    | No     | Yes        | No     | No   | No     | No      |
| Literal > capture priority      | Yes      | Order      | Yes    | Manual     | Manual | Order| Order  | Order   |
| Type-safe route composition     | Yes      | No         | No     | Yes        | Yes    | No   | No     | Yes     |

\* Silently drops segments after Rest — a bug, not a feature.
\** Akka/http4s use composable matchers/pattern matching, so "middle rest" is structurally different.
\*** http4s uses Scala extractors which can contain arbitrary logic including regex.
\**** FastAPI validates via Path() metadata, not route pattern.

---

## 4. Analysis of Potential Enhancements

### A. Rest in Middle Position

**Current behavior**: Segments after `Rest` are silently ignored. This is a bug.

**Could the trie support it?** Not naturally. Rest consumes all remaining input in a single step during the trie walk (line 147-149). Supporting `"api" / Rest("mid") / "suffix"` would require:
- Backtracking: try to match suffix from the end, give everything else to Rest
- Or greedy + suffix check: consume greedily, then verify the suffix literal matches

Both approaches break the zero-allocation, single-pass trie walk. Backtracking is O(n) worst case per segment. This is why Spring PathPatternParser explicitly forbids `**` in middle positions.

**Recommendation**: Either (a) make it a compile-time error to place `Rest` in non-terminal position, or (b) don't support it. Option (a) is strongly preferred — it prevents silent bugs.

### B. Regex Constraints on Captures

**What it would look like**:
```scala
HttpPath.Capture[Int]("id", regex = "[0-9]+")
```

**Trie impact**: Minimal. The regex check would happen *after* a segment is captured, as a validation step. The trie walk itself doesn't change — you still match the capture child node, extract the segment, then validate. On regex failure, you'd need to either reject (404) or backtrack.

Without backtracking (simpler, recommended): regex is purely a validation/constraint. If the segment doesn't match the regex, the route fails. This is what Express and Spring do for parameter constraints.

**Type-safety angle**: The codec already validates types (e.g., `HttpCodec[Int]` will fail on non-numeric strings). Regex constraints are redundant for typed captures but useful for String captures with patterns (e.g., slugs matching `[a-z0-9-]+`, UUIDs).

**Recommendation**: Low priority. The typed codec system already provides validation. If added, implement as post-capture validation (no trie changes needed).

### C. Glob/Wildcard Within a Segment

E.g., matching `/files/*.json` where `*` matches any characters within a single segment.

**Trie impact**: Significant. Binary search on literal segments assumes exact match. Intra-segment wildcards would require linear scan with glob matching at each node. This defeats the O(log L) lookup.

**Recommendation**: Don't support. No major framework does this well. It's an edge case better handled by application logic after capture.

### D. Optional Segments

E.g., `/api/v1?/users` matching both `/api/v1/users` and `/api/users`.

**Trie impact**: Can be done at build time by inserting multiple routes (one with, one without the optional segment). No runtime cost.

**Recommendation**: Could be useful but adds API complexity. Low priority.

---

## 5. Summary of Recommendations

1. **Fix Rest-in-middle bug** (HIGH): Add compile-time or build-time validation that `Rest` can only appear as the last segment in a path. Currently it silently drops trailing segments.

2. **Don't add regex constraints** (LOW): The typed codec system (`HttpCodec[Int]`, `HttpCodec[UUID]`, etc.) already provides parameter validation. Regex on String captures is a nice-to-have but doesn't justify the API surface.

3. **Don't add intra-segment wildcards** (SKIP): No framework does this well, and it breaks trie performance.

4. **Don't add middle-position rest/wildcard** (SKIP): Requires backtracking, which conflicts with the zero-allocation single-pass design. Spring made the same decision.

5. **Consider optional segments** (LOW): Purely a build-time expansion, no runtime cost. But adds API complexity.

The current kyo-http router design is well-aligned with industry best practices. The trie-based approach with literal > capture > rest priority matches what Spring PathPatternParser does, and the type-safe capture system is stronger than most frameworks. The main gap is the silent Rest-in-middle bug.

---

## Sources

- [Express.js Routing](https://expressjs.com/en/guide/routing.html)
- [Express v5 wildcard changes](https://github.com/expressjs/express/issues/6711)
- [path-to-regexp](https://www.npmjs.com/package/path-to-regexp)
- [Spring URL Matching with PathPattern](https://spring.io/blog/2020/06/30/url-matching-with-pathpattern-in-spring-mvc/)
- [Spring PathPattern in Spring MVC](https://www.springcloud.io/post/2022-09/springmvc-pathpattern/)
- [Spring Request Mapping docs](https://docs.spring.io/spring-framework/reference/6.0/web/webmvc/mvc-controller/ann-requestmapping.html)
- [Akka HTTP PathMatcher DSL](https://doc.akka.io/docs/akka-http/current/routing-dsl/path-matchers.html)
- [Pekko HTTP PathMatcher DSL](https://pekko.apache.org/docs/pekko-http/current/routing-dsl/path-matchers.html)
- [http4s DSL](https://http4s.org/v1/docs/dsl.html)
- [Play Framework Scala Routing](https://www.playframework.com/documentation/2.9.x/ScalaRouting)
- [Vert.x Web Routing](https://vertx.io/docs/vertx-web/java/)
- [FastAPI Path Parameters](https://fastapi.tiangolo.com/tutorial/path-params/)
