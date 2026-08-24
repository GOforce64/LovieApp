# Task 3 report: API models and client

## Files created

- `app/src/main/java/com/lovebutton/app/data/ApiModels.kt` — transcribed verbatim from the brief. No changes needed.
- `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt` — transcribed from the brief with three deliberate deviations (see below).
- `app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt` — transcribed verbatim from the brief. All eight tests and every assertion are intact; no test was weakened or removed.

## TDD sequence followed

1. Wrote the test file first.
2. Ran it — it failed to compile with `Unresolved reference 'LoveButtonApi'` / `Unresolved reference 'EnrolResult'` (23 errors), confirming the red step.
3. Wrote `ApiModels.kt` verbatim.
4. Wrote `LoveButtonApi.kt`, pre-emptively applying the fix for known risk 1 (see below), then ran the targeted test.
5. One test failed for an undocumented reason (see "third issue" below); fixed the source, re-ran, all green.
6. Ran the full suite and `assembleDebug`.

## Known risk 1 — `Response.body` nullability

Under OkHttp 5.2.0, `okhttp3.Response.body` is non-nullable (`ResponseBody`, not `ResponseBody?`). The brief's `response.body?.string().orEmpty()` would produce an unnecessary-safe-call situation (and in this codebase's case, would not type-check as written against a non-null property in a way that matches the intended safe-call semantics). I simplified both occurrences to `response.body.string()`, in `enrol()` and `send()`. The `.use { }` blocks around each response were kept exactly as in the brief — they still close the response and prevent a connection leak; only the null-handling on `.body` changed.

## Known risk 2 — MockWebServer package

Not actually a problem in this environment. I inspected the resolved `mockwebserver-5.2.0.jar` directly (`~/.gradle/caches/modules-2/files-2.1/com.squareup.okhttp3/mockwebserver/5.2.0/...`) and confirmed the legacy `okhttp3.mockwebserver` package (`MockWebServer`, `MockResponse`, `RecordedRequest`, etc.) is still shipped as deprecated shims alongside the new `mockwebserver3` package. The brief's test, using `okhttp3.mockwebserver.MockResponse`/`MockWebServer`, `server.takeRequest()`, and `request.body.readUtf8()`, compiled and ran without any change. No test code was ported to the new `mockwebserver3` API.

## Third issue — undocumented, found by running the tests

One test failed after both known risks were addressed: `enrol posts the code and parses the token`, on the assertion `assertEquals("application/json", request.getHeader("Content-Type"))`. The actual header value received by MockWebServer was `application/json; charset=utf-8`.

Root cause: OkHttp's `BridgeInterceptor` unconditionally overwrites the `Content-Type` header from `requestBody.contentType()` whenever that is non-null — this happens after the request leaves `Request.Builder`, so the explicit `.header("Content-Type", "application/json")` call in `post()` gets clobbered. Separately, OkHttp's `String.toRequestBody(contentType)` extension appends `; charset=utf-8` to any media type that doesn't already specify a charset. Combined, the brief's `body.toRequestBody(JSON_MEDIA_TYPE)` (where `JSON_MEDIA_TYPE = "application/json".toMediaType()`) produced a body whose `contentType()` was `application/json; charset=utf-8`, and the interceptor rewrote the header to match, overriding the explicit header set immediately after `.post(...)` in the same builder chain. This is longstanding OkHttp behavior, not something new to 5.2.0 — the brief's code as written would not have produced the header the test expects, on any recent OkHttp version.

Fix (in `LoveButtonApi.kt`, source only — the test file is untouched): removed the `JSON_MEDIA_TYPE` constant and its `toMediaType` import, and changed `body.toRequestBody(JSON_MEDIA_TYPE)` to `body.toRequestBody()` (no media type). With no `MediaType` attached to the body, `requestBody.contentType()` is `null`, `BridgeInterceptor` skips overwriting the header, and the explicit `.header("Content-Type", "application/json")` call stands untouched. Bytes are still encoded as UTF-8 (the extension's default charset when no `MediaType` is given), so wire content is unaffected. Added a doc comment in `post()` explaining this so a future editor doesn't reintroduce the `MediaType` and silently break the header again.

This is a source-code fix, not a test change — the assertion in the brief is exactly as specified and was not touched.

## Commands run

### Failing stage (Step 2)

```
$ export ANDROID_HOME="$HOME/Android/Sdk"
$ ./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.LoveButtonApiTest"
...
> Task :app:compileDebugUnitTestKotlin FAILED
e: .../LoveButtonApiTest.kt:3:32 Unresolved reference 'EnrolResult'.
e: .../LoveButtonApiTest.kt:4:32 Unresolved reference 'LoveButtonApi'.
... (23 unresolved-reference errors total)
BUILD FAILED in 1s
```

### Passing stage — targeted test (Step 5)

```
$ ./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.LoveButtonApiTest"
...
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 3s
27 actionable tasks: 6 executed, 21 up-to-date
```

(This run was after the Content-Type fix; before that fix, this same command reported `8 tests completed, 1 failed` with `org.junit.ComparisonFailure: expected:<application/json[]> but was:<application/json[; charset=utf-8]>` in `enrol posts the code and parses the token`.)

### Full unit test suite (Step 6)

```
$ ./gradlew :app:testDebugUnitTest
...
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 1s
27 actionable tasks: 1 executed, 26 up-to-date
```

Verified test counts directly from the JUnit XML reports:

```
app/build/test-results/testDebugUnitTest/TEST-com.lovebutton.app.LoveButtonApiTest.xml: tests="8" skipped="0" failures="0" errors="0"
app/build/test-results/testDebugUnitTest/TEST-com.lovebutton.app.MessagesTest.xml: tests="5" skipped="0" failures="0" errors="0"
```

13 tests total, all passing (8 new + 5 from Task 2), as expected.

### assembleDebug

```
$ ./gradlew :app:assembleDebug
...
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1s
40 actionable tasks: 3 executed, 37 up-to-date
```

40 tasks, matching the expected baseline.

## Invariant checks (explicit, since these are the point of the task)

- `send posts only the message id and never a recipient` — passes, asserting the body contains `"msg_id":3` and does **not** contain `to_person` or `from_person`. Assertion untouched.
- `send reports delivered zero without throwing` — passes; `send()` does not throw on `delivered: 0`, only on non-2xx HTTP status.
- `enrol maps 403 to InvalidCode`, `enrol maps 429 to RateLimited`, `enrol maps an unexpected status to Failed` — all pass, matching the specified mapping.
- `registerDevice reports failure on 401` — passes; `registerDevice` returns `false`, does not throw.
- No `authToken` value is ever written to Logcat or embedded in an exception message anywhere in `LoveButtonApi.kt`. `send()`'s thrown `IOException` message is built from `errorMessage(text, response.code)` — the server's own error text and HTTP code — never the token.

## Notes for Task 4's implementer

- `LoveButtonApi` has no `Content-Type` media type attached to its request bodies (see the fix above) — this is intentional, not an oversight. If a future change needs a body with real UTF-8 multi-byte content, note that `toRequestBody()` with no `MediaType` still defaults to UTF-8 encoding, so this is safe as-is.
- `LoveButtonApi(baseUrl, client)` takes a plain base URL string with no trailing slash (see the test's `server.url("/").toString().removeSuffix("/")`); callers must not append a trailing slash to `baseUrl`, and each path constant in `post()` already starts with `/v1/...`.
- The client throws plain `IOException` from `send()` on any non-2xx response — Task 4's WorkManager retry logic should catch `IOException` (or a supertype) to trigger backoff/retry, consistent with the doc comment on `send()`.
- `enrol` and `registerDevice` never throw for HTTP-level failures (only for genuine `IOException`/network failures inside `enrol`, which is caught and converted to `EnrolResult.Failed`); `registerDevice` swallows `IOException` too and returns `false`. Task 4 should not wrap calls to these two in additional try/catch for HTTP-status handling — it's already done.

## Commit

```
git add app/src/main/java/com/lovebutton/app/data/ApiModels.kt app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt
git commit -m "feat(app): add API models and Worker client"
```

---

## Fix round 1

The coordinator reversed deviation 3 from the initial pass. The diagnosis of `BridgeInterceptor` overwriting the header was correct, but the fix direction was wrong: production code was changed (dropping the `MediaType` from the request body) to make an over-specified test assertion pass, rather than relaxing the assertion, which was the actual thing at fault. The server only checks `contentType.includes("application/json")` (`server/src/http.ts:32`), so `application/json; charset=utf-8` was always acceptable — the test's exact-equality assertion pinned an incidental detail (the charset suffix), not real behavior.

### Changes made

**`app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`**
- Restored the `JSON_MEDIA_TYPE` constant and its `toMediaType` import.
- `post()` now passes `JSON_MEDIA_TYPE` back into `body.toRequestBody(JSON_MEDIA_TYPE)` and no longer sets `Content-Type` by hand — the explicit header call is deleted since it was redundant at best and losing at worst (`BridgeInterceptor` overwrites it regardless).
- Replaced the previous comment (which justified dropping the media type) with the coordinator's comment explaining that the header is derived from the body and that hand-setting it loses to the interceptor.

**`app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt`**
- In `enrol posts the code and parses the token`, replaced `assertEquals("application/json", request.getHeader("Content-Type"))` with a `startsWith("application/json")` check via `assertTrue`, with a comment explaining why exact equality over-specified the header. `assertTrue` was already imported. No other assertion in the file was touched — the `msg_id`-only invariant test and the `delivered: 0` test are unchanged.

### Verification

```
$ export ANDROID_HOME="$HOME/Android/Sdk"
$ ./gradlew :app:testDebugUnitTest
...
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 2s
27 actionable tasks: 6 executed, 21 up-to-date
```

Test counts from JUnit XML:
```
TEST-com.lovebutton.app.LoveButtonApiTest.xml: tests="8" skipped="0" failures="0" errors="0"
TEST-com.lovebutton.app.MessagesTest.xml: tests="5" skipped="0" failures="0" errors="0"
```
13 tests total, all passing.

```
$ ./gradlew :app:assembleDebug
...
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1s
40 actionable tasks: 4 executed, 36 up-to-date
```

No build files were touched. `local.properties` was not staged.
