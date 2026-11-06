package com.trustsy.verify

import com.trustsy.verify.internal.net.ApiClient
import com.trustsy.verify.internal.net.ApiException
import com.trustsy.verify.internal.net.Sealed
import com.trustsy.verify.internal.net.SealedTransport
import com.trustsy.verify.internal.net.SealedTransportException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in: the sealed transport against a REAL api (interop with the Rust
 * gateway, not just with the vectors). Skipped unless all three are set:
 *
 *   TRUSTSY_LIVE_API=http://localhost:8096 TRUSTSY_LIVE_TOKEN=<applicant token> \
 *   TRUSTSY_LIVE_KEY=<key config> ./gradlew :trustsy-verify:testDebugUnitTest --tests '*LiveSealedServerTest'
 *
 * Read-only: one GET of the snapshot, plus a request with a wrong clock.
 */
class LiveSealedServerTest {
    private val api: String? = System.getenv("TRUSTSY_LIVE_API")
    private val token: String? = System.getenv("TRUSTSY_LIVE_TOKEN")
    private val key: String? = System.getenv("TRUSTSY_LIVE_KEY")

    private fun client(clock: () -> Long = System::currentTimeMillis) = ApiClient(
        SealedTransport(api!!, token!!, Sealed.KeyConfig.select(key!!)!!, ApiClient.defaultHttp, clock),
        telemetry = { null },
    )

    @Test
    fun `the real gateway opens our request and we open its answer`() = runTest {
        assumeTrue(api != null && token != null && key != null)
        val snapshot = client().snapshot()
        println("live snapshot: id=${snapshot.id} state=${snapshot.state} events=${snapshot.availableEvents}")
        assertTrue(snapshot.id.isNotEmpty())
    }

    @Test
    fun `a clock ten minutes off is clock_skew`() = runTest {
        assumeTrue(api != null && token != null && key != null)
        val e = runCatching { client { System.currentTimeMillis() - 10 * 60_000 }.snapshot() }.exceptionOrNull()
        assertTrue("$e", e is SealedTransportException)
        assertEquals(SealedTransport.CLOCK_SKEW, (e as SealedTransportException).resultCode)
    }

    @Test
    fun `an unknown token is a sealed 401, not a transport failure`() = runTest {
        assumeTrue(api != null && key != null)
        val transport = SealedTransport(api!!, "00000000-0000-4000-8000-00000000dead", Sealed.KeyConfig.select(key!!)!!, ApiClient.defaultHttp)
        val e = runCatching { ApiClient(transport, telemetry = { null }).snapshot() }.exceptionOrNull()
        assertTrue("$e", e is ApiException && e.status == 401)
    }
}
