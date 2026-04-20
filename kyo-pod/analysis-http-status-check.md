# Analysis: HttpContainerBackend status check fix

## Problem
Text-based HTTP methods (`postTextResponse`, `deleteTextResponse`, `postText`, `getText`) never throw `HttpStatusException` on non-2xx responses. They silently return the body text regardless of status code. This means `withErrorMapping` (which catches `HttpException`) never fires for text-based calls. Container operations like `remove()`, `start()`, `stop()`, `kill()` silently succeed even on 4xx/5xx.

## Fix Plan

### 1. Add `checkStatus` helper method (after `withErrorMapping`)
A new method that takes an `HttpResponse[?]` and checks `response.status`, mapping non-2xx to `ContainerException`.

### 2. Fix `postUnit` (line 72-73)
Move `.map(_ => ())` outside `withErrorMapping`, use `checkStatus`.

### 3. Fix `postUnitAccept304` (line 76-83)
Rewrite to check `response.status` directly on the response instead of trying to catch `HttpStatusException`.

### 4. Fix `remove` (line 215-220)
Move `.map(_ => ())` outside `withErrorMapping`, use `checkStatus`.

### 5. Fix `imagePull` (line 635-637)
Use `checkStatus` instead of `_.map(_ => ())`.

### 6. Fix `imagePullWithProgress` inner postText (line 680-681)
Change `HttpClient.postText` to `HttpClient.postTextResponse`, check status on response.

### 7. Fix `imageTag` (line 769-771)
Use `checkStatus`.

### 8. Fix `imagePush` (line 861-863)
Use `checkStatus`.

### 9. Fix `networkRemove` (line 984-987)
Move `.map(_ => ())` outside `withErrorMapping`, use `checkStatus`.

### 10. Fix `volumeRemove` (line 1058-1061)
Move `.map(_ => ())` outside `withErrorMapping`, use `checkStatus`.

### 11. Fix `detect` _ping (line 1119)
Change `response == "OK"` to `response.trim == "OK"`.
