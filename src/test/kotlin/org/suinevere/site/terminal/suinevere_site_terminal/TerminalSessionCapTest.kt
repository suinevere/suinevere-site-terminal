/*----------------------
 | TerminalSessionCapTest.kt
 | Description: Verifies the concurrent session cap rejects an extra session with readable terminal text.
 | Author: suinevere
 | Dependencies: spring-boot-starter-test, reactor-test, EchoTcpServer
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.rsocket.RSocketRequester
import org.springframework.messaging.rsocket.retrieveFlux
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.util.MimeTypeUtils
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["terminal.upstream.max-sessions=1"])
class TerminalSessionCapTest {

	@LocalServerPort
	private var port: Int = 0

	private fun openSession(): Flux<String> =
		RSocketRequester.builder()
			.dataMimeType(MimeTypeUtils.TEXT_PLAIN)
			.websocket(URI.create("ws://localhost:$port/rsocket"))
			.route("terminal.session")
			.data(Flux.just(INIT_SENTINEL), String::class.java)
			.retrieveFlux<String>()

	@Test
	fun `reports server busy once the session cap is reached`() {
		val connected = CountDownLatch(1)
		val holder: Disposable = openSession()
			.doOnNext { connected.countDown() }
			.subscribe()

		try {
			assertTrue(connected.await(15, TimeUnit.SECONDS), "the first session never reached upstream")

			StepVerifier.create(openSession())
				.expectNext(BUSY_MESSAGE)
				.thenCancel()
				.verify(Duration.ofSeconds(15))
		} finally {
			holder.dispose()
		}
	}

	companion object {
		private val upstream = EchoTcpServer.startEchoing("> ")

		@JvmStatic
		@DynamicPropertySource
		fun upstreamEndpoint(registry: DynamicPropertyRegistry) {
			registry.add("terminal.upstream.host") { "127.0.0.1" }
			registry.add("terminal.upstream.port") { upstream.port() }
		}

		@JvmStatic
		@AfterAll
		fun stopUpstream() {
			upstream.disposeNow()
		}
	}
}
