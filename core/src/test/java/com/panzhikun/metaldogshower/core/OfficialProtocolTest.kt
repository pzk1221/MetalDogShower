package com.panzhikun.metaldogshower.core

import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OfficialProtocolTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun emitsExactPathsQueriesHeadersAndJsonBodies() = runTest {
        val token = "t".repeat(128)
        server.enqueue(json("""{"mark":"mark-123"}"""))
        server.enqueue(json("{}"))
        server.enqueue(json("""{"token":"$token"}"""))
        server.enqueue(
            json(
                """{"dev_id":"CANONICAL-1","dev_name":"Test shower","stadium_id":42,"type":"shower"}""",
            ),
        )
        server.enqueue(json("""{"shower":{"is_opened":1,"rest_time":1200}}"""))
        server.enqueue(json("{}"))
        server.enqueue(json("""{"shower":{"is_opened":0,"rest_time":1199}}"""))

        val repository = repository(token)
        repository.requestOtp(PHONE)
        assertEquals(token, repository.login(PHONE, OTP))
        assertEquals("CANONICAL-1", repository.resolveDevice("QR-ALIAS").deviceId)
        assertTrue(repository.status(ROUTE).isOpen)
        val control = repository.control(ROUTE, desiredOpen = false)
        assertTrue(control is ControlResult.Confirmed)
        control as ControlResult.Confirmed
        assertFalse(control.status.isOpen)

        val mark = server.takeRequest()
        assertEquals("GET", mark.method)
        assertEquals("/user/0/0/public/validateCodeMark?phone=$PHONE", mark.path)
        assertNoToken(mark)

        val send = server.takeRequest()
        assertEquals("GET", send.method)
        assertEquals(
            "/user/0/0/public/validateCode?phone=$PHONE&app_id=null&key=" +
                "ccdcc0281cb9e1f6a04590f56928d667",
            send.path,
        )
        assertNoToken(send)

        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("/user/0/0/public/yzmLogin", login.path)
        assertNoToken(login)
        val loginJson = JsonParser.parseString(login.body.readUtf8()).asJsonObject
        assertTrue(loginJson["app_id"].isJsonNull)
        assertTrue(loginJson["code"].isJsonNull)
        assertEquals(PHONE, loginJson["phone"].asString)
        assertEquals(OTP, loginJson["validate_code"].asString)
        assertEquals("网页用户", loginJson["nick_name"].asString)
        assertTrue(loginJson["avatar_url"].isJsonNull)
        assertTrue(loginJson["gender"].isJsonNull)
        assertTrue(loginJson["city"].isJsonNull)

        val device = server.takeRequest()
        assertEquals("GET", device.method)
        assertEquals("/device/0/0/public/info?dev_id=QR-ALIAS", device.path)
        assertNoToken(device)

        val initialStatus = server.takeRequest()
        assertEquals(
            "/device/7/42/protect/shower/info?_route_dev_id=CANONICAL-1",
            initialStatus.path,
        )
        assertProtectedToken(initialStatus, token)

        val switch = server.takeRequest()
        assertEquals("POST", switch.method)
        assertEquals("/device/0/0/protect/shower/switch", switch.path)
        assertProtectedToken(switch, token)
        val switchJson = JsonParser.parseString(switch.body.readUtf8()).asJsonObject
        assertEquals("CANONICAL-1", switchJson["_route_dev_id"].asString)
        assertEquals(0, switchJson["switch"].asInt)

        val followUpStatus = server.takeRequest()
        assertEquals(
            "/device/7/42/protect/shower/info?_route_dev_id=CANONICAL-1",
            followUpStatus.path,
        )
        assertProtectedToken(followUpStatus, token)
        assertEquals(7, server.requestCount)
    }

    @Test
    fun mapsHttp401ToAuthenticationRequiredWithoutRefreshAttempt() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private error"))

        try {
            repository("t".repeat(128)).status(ROUTE)
            fail("Expected AuthenticationRequiredException")
        } catch (_: AuthenticationRequiredException) {
            // Expected. There is no token refresh or automatic retry.
        }

        assertEquals(1, server.requestCount)
        assertEquals(
            "/device/7/42/protect/shower/info?_route_dev_id=CANONICAL-1",
            server.takeRequest().path,
        )
    }

    @Test
    fun explicitSwitch403IsNotRetriedOrFollowedByStatusRead() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("private error"))

        try {
            repository("t".repeat(128)).control(ROUTE, desiredOpen = true)
            fail("Expected ApiHttpException")
        } catch (exception: ApiHttpException) {
            assertEquals(403, exception.statusCode)
        }

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/device/0/0/protect/shower/switch", request.path)
    }

    @Test
    fun knownOpenStatusPreventsAnotherOpenPost() = runTest {
        server.enqueue(json("""{"shower":{"is_opened":1,"rest_time":1200}}"""))
        val repository = repository("t".repeat(128))

        assertTrue(repository.status(ROUTE).isOpen)
        val result = repository.control(ROUTE, desiredOpen = true)

        assertTrue(result is ControlResult.Rejected)
        result as ControlResult.Rejected
        assertEquals(ControlRejection.ALREADY_OPEN, result.reason)
        assertEquals(0L, result.retryAfterMillis)
        assertEquals(1, server.requestCount)
        assertEquals(
            "/device/7/42/protect/shower/info?_route_dev_id=CANONICAL-1",
            server.takeRequest().path,
        )
    }

    @Test
    fun missingOpenedFlagNeverBecomesKnownClosedState() = runTest {
        server.enqueue(json("""{"shower":{"rest_time":1200}}"""))

        try {
            repository("t".repeat(128)).status(ROUTE)
            fail("Expected ProtocolException")
        } catch (_: ProtocolException) {
            // Expected: malformed data must not enable a control button.
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun invalidOpenedFlagNeverBecomesKnownState() = runTest {
        server.enqueue(json("""{"shower":{"is_opened":2,"rest_time":1200}}"""))

        try {
            repository("t".repeat(128)).status(ROUTE)
            fail("Expected ProtocolException")
        } catch (_: ProtocolException) {
            // Expected: only the verified 0 and 1 states are accepted.
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun switchTimeoutNeverRepeatsPostAndPerformsOneStatusRead() = runTest {
        var switchPosts = 0
        var statusGets = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/device/0/0/protect/shower/switch" -> {
                    switchPosts++
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                request.path?.startsWith("/device/7/42/protect/shower/info") == true -> {
                    statusGets++
                    json("""{"shower":{"is_opened":1,"rest_time":1180}}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = ShowerRepositories.realForTesting(
            baseUrl = server.url("/"),
            tokenProvider = TokenProvider { "t".repeat(128) },
            connectTimeoutMillis = 150L,
            readTimeoutMillis = 150L,
            writeTimeoutMillis = 150L,
            callTimeoutMillis = 250L,
        )

        val result = repository.control(ROUTE, desiredOpen = true)
        assertTrue(result is ControlResult.Ambiguous)
        result as ControlResult.Ambiguous
        assertEquals(true, result.observedStatus?.isOpen)
        assertEquals(1, switchPosts)
        assertEquals(1, statusGets)
        assertEquals(2, server.requestCount)

        val duplicate = repository.control(ROUTE, desiredOpen = true)
        assertTrue(duplicate is ControlResult.Rejected)
        duplicate as ControlResult.Rejected
        assertEquals(ControlRejection.ALREADY_OPEN, duplicate.reason)
        assertEquals(1, switchPosts)
        assertEquals(1, statusGets)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun disconnectAfterSwitchRequestIsAmbiguousAndNeverRepeatsPost() = runTest {
        var switchPosts = 0
        var statusGets = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/device/0/0/protect/shower/switch" -> {
                    switchPosts++
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
                request.path?.startsWith("/device/7/42/protect/shower/info") == true -> {
                    statusGets++
                    json("""{"shower":{"is_opened":0,"rest_time":1170}}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repository = repository("t".repeat(128))

        val result = repository.control(ROUTE, desiredOpen = true)

        assertTrue(result is ControlResult.Ambiguous)
        result as ControlResult.Ambiguous
        assertEquals(false, result.observedStatus?.isOpen)
        assertEquals(1, switchPosts)
        assertEquals(1, statusGets)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cancellationDuringAmbiguousVerificationKeepsRouteLocked() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/device/0/0/protect/shower/switch" ->
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                "/device/7/42/protect/shower/info?_route_dev_id=CANONICAL-1" ->
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repository = ShowerRepositories.realForTesting(
            baseUrl = server.url("/"),
            tokenProvider = TokenProvider { "t".repeat(128) },
            connectTimeoutMillis = 5_000L,
            readTimeoutMillis = 5_000L,
            writeTimeoutMillis = 5_000L,
            callTimeoutMillis = 10_000L,
        )

        val controlJob = launch(Dispatchers.Default) {
            repository.control(ROUTE, desiredOpen = true)
        }
        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)?.method)
        assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)?.method)

        controlJob.cancelAndJoin()

        val duplicate = repository.control(ROUTE, desiredOpen = true)
        assertTrue(duplicate is ControlResult.Ambiguous)
        assertNull((duplicate as ControlResult.Ambiguous).observedStatus)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun successfulSwitchWithFailedConfirmationLocksUntilManualStatusRead() = runTest {
        var switchPosts = 0
        var statusGets = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/device/0/0/protect/shower/switch" -> {
                    switchPosts++
                    json("{}")
                }
                request.path?.startsWith("/device/7/42/protect/shower/info") == true -> {
                    statusGets++
                    if (statusGets == 1) {
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    } else {
                        json("""{"shower":{"is_opened":0,"rest_time":1160}}""")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val clock = MutableClock()
        val repository = ShowerRepositories.realForTesting(
            baseUrl = server.url("/"),
            tokenProvider = TokenProvider { "t".repeat(128) },
            requestGate = RequestGate(clock),
            connectTimeoutMillis = 150L,
            readTimeoutMillis = 150L,
            writeTimeoutMillis = 150L,
            callTimeoutMillis = 250L,
        )

        val result = repository.control(ROUTE, desiredOpen = true)
        assertTrue(result is ControlResult.Ambiguous)
        result as ControlResult.Ambiguous
        assertNull(result.observedStatus)
        assertEquals(1, switchPosts)
        assertEquals(1, statusGets)

        // Even after the normal cooldown, the same command remains locally locked.
        clock.now = 6_000L
        val duplicate = repository.control(ROUTE, desiredOpen = true)
        assertTrue(duplicate is ControlResult.Ambiguous)
        assertEquals(1, switchPosts)
        assertEquals(1, statusGets)
        assertEquals(2, server.requestCount)

        // Only a user-requested status read is allowed to clear the uncertain state.
        assertFalse(repository.status(ROUTE).isOpen)
        assertEquals(1, switchPosts)
        assertEquals(2, statusGets)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun successfulSwitchThenConfirmation401IsAmbiguousAndAuthenticationRequired() = runTest {
        server.enqueue(json("{}"))
        server.enqueue(MockResponse().setResponseCode(401))
        val clock = MutableClock()
        val repository = ShowerRepositories.realForTesting(
            baseUrl = server.url("/"),
            tokenProvider = TokenProvider { "t".repeat(128) },
            requestGate = RequestGate(clock),
        )

        val result = repository.control(ROUTE, desiredOpen = true)

        assertTrue(result is ControlResult.Ambiguous)
        result as ControlResult.Ambiguous
        assertNull(result.observedStatus)
        assertTrue(result.authenticationRequired)
        assertEquals(2, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)

        clock.now = 6_000L
        val duplicate = repository.control(ROUTE, desiredOpen = true)
        assertTrue(duplicate is ControlResult.Ambiguous)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun statusAndUncertaintyAreIsolatedByRoute() = runTest {
        val secondRoute = ROUTE.copy(deviceId = "CANONICAL-2")
        server.enqueue(json("""{"shower":{"is_opened":1,"rest_time":900}}"""))
        server.enqueue(json("{}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(json("""{"shower":{"is_opened":0,"rest_time":800}}"""))
        val clock = MutableClock()
        val repository = ShowerRepositories.realForTesting(
            baseUrl = server.url("/"),
            tokenProvider = TokenProvider { "t".repeat(128) },
            requestGate = RequestGate(clock),
            connectTimeoutMillis = 150L,
            readTimeoutMillis = 150L,
            writeTimeoutMillis = 150L,
            callTimeoutMillis = 250L,
        )

        assertTrue(repository.status(ROUTE).isOpen)
        val ambiguous = repository.control(secondRoute, desiredOpen = true)
        assertTrue(ambiguous is ControlResult.Ambiguous)
        ambiguous as ControlResult.Ambiguous
        assertNull(ambiguous.observedStatus)

        val routeOneDuplicate = repository.control(ROUTE, desiredOpen = true)
        assertTrue(routeOneDuplicate is ControlResult.Rejected)
        routeOneDuplicate as ControlResult.Rejected
        assertEquals(ControlRejection.ALREADY_OPEN, routeOneDuplicate.reason)
        assertEquals(3, server.requestCount)

        assertFalse(repository.status(secondRoute).isOpen)
        assertEquals(4, server.requestCount)
    }

    private fun repository(token: String): ShowerRepository =
        ShowerRepositories.realForTesting(
            baseUrl = server.url("/"),
            tokenProvider = TokenProvider { token },
        )

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun assertNoToken(request: RecordedRequest) {
        assertNull(request.getHeader(OfficialProtocol.TOKEN_HEADER))
        assertNull(request.getHeader("X-MetalDog-Requires-Token"))
    }

    private fun assertProtectedToken(request: RecordedRequest, token: String) {
        assertEquals(token, request.getHeader(OfficialProtocol.TOKEN_HEADER))
        assertNull(request.getHeader("X-MetalDog-Requires-Token"))
    }

    companion object {
        private const val PHONE = "13800138000"
        private const val OTP = "123456"
        private val ROUTE = DeviceRoute(
            brandId = 7L,
            stadiumId = 42L,
            deviceId = "CANONICAL-1",
        )
    }

    private class MutableClock(var now: Long = 0L) : MonotonicClock {
        override fun nowMillis(): Long = now
    }
}
