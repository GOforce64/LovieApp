package com.lovebutton.app

import com.lovebutton.app.data.EnrolResult
import com.lovebutton.app.data.LoveButtonApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoveButtonApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LoveButtonApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = LoveButtonApi(server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    @Test
    fun `enrol posts the code and parses the token`() = runBlocking {
        server.enqueue(
            json("""{"device_id":"d1","auth_token":"tok","person":2,"partner_name":"Giorgos"}""")
        )

        val result = api.enrol("secret-code", "fcm-1", "her phone")

        assertTrue(result is EnrolResult.Ok)
        result as EnrolResult.Ok
        assertEquals("tok", result.authToken)
        assertEquals(2, result.person)
        assertEquals("Giorgos", result.partnerName)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/enroll", request.path)
        assertEquals("application/json", request.getHeader("Content-Type"))

        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"code\":\"secret-code\""))
        assertTrue(sent.contains("\"fcm_token\":\"fcm-1\""))
    }

    @Test
    fun `enrol maps 403 to InvalidCode`() = runBlocking {
        server.enqueue(json("""{"error":"invalid_code","message":"nope"}""", 403))

        assertEquals(EnrolResult.InvalidCode, api.enrol("wrong", "fcm-1", "phone"))
    }

    @Test
    fun `enrol maps 429 to RateLimited`() = runBlocking {
        server.enqueue(json("""{"error":"rate_limited","message":"slow down"}""", 429))

        assertEquals(EnrolResult.RateLimited, api.enrol("code", "fcm-1", "phone"))
    }

    @Test
    fun `enrol maps an unexpected status to Failed`() = runBlocking {
        server.enqueue(json("""{"error":"boom","message":"server exploded"}""", 500))

        val result = api.enrol("code", "fcm-1", "phone")

        assertTrue(result is EnrolResult.Failed)
    }

    @Test
    fun `send posts only the message id and never a recipient`() = runBlocking {
        server.enqueue(json("""{"send_id":"s1","delivered":1}"""))

        val result = api.send("tok", 3)

        assertEquals("s1", result.sendId)
        assertEquals(1, result.delivered)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/send", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))

        // Invariant 2 lives on the server, but the client must not even try to
        // name a recipient — if this body ever grows a to_person field, the
        // server ignores it and this test is the reminder of why.
        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"msg_id\":3"))
        assertFalse(sent.contains("to_person"))
        assertFalse(sent.contains("from_person"))
    }

    @Test
    fun `send reports delivered zero without throwing`() = runBlocking {
        // The server returns 200 with delivered 0 when her phone has no active
        // device. That is information, not a failure, and must not raise.
        server.enqueue(json("""{"send_id":"s2","delivered":0}"""))

        assertEquals(0, api.send("tok", 1).delivered)
    }

    @Test
    fun `registerDevice sends the bearer token and reports success`() = runBlocking {
        server.enqueue(json("""{"ok":true}"""))

        assertTrue(api.registerDevice("tok", "fcm-new"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/devices", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"fcm_token\":\"fcm-new\""))
    }

    @Test
    fun `registerDevice reports failure on 401`() = runBlocking {
        server.enqueue(json("""{"error":"unauthorized","message":"no"}""", 401))

        assertFalse(api.registerDevice("stale-token", "fcm-new"))
    }
}
