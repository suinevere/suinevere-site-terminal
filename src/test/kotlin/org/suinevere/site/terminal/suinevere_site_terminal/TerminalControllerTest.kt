/*----------------------
 | TerminalControllerTest.kt
 | Description: Verifies the RSocket channel round-trips and that the handshake sentinel never goes upstream.
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
import org.springframework.util.MimeTypeUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalControllerTest {

	@LocalServerPort
	private var port: Int = 0

	private fun requester(): RSocketRequester =
		RSocketRequester.builder()
			.dataMimeType(MimeTypeUtils.TEXT_PLAIN)
			.websocket(URI.create("ws://localhost:$port/rsocket"))

	private fun Flux<String>.accumulatedUntil(expected: String): Mono<String> =
		scan("") { acc, chunk -> acc + chunk }
			.filter { it == expected }
			.next()

	@Test
	fun `round-trips a typed line through the channel`() {
		val output = requester()
			.route("terminal.session")
			.data(Flux.just(INIT_SENTINEL, "suinevere\r\n"), String::class.java)
			.retrieveFlux<String>()
			.accumulatedUntil("> suinevere\r\n")

		StepVerifier.create(output)
			.expectNext("> suinevere\r\n")
			.expectComplete()
			.verify(Duration.ofSeconds(15))
	}

	@Test
	fun `never forwards the handshake sentinel upstream`() {
		val output = requester()
			.route("terminal.session")
			.data(Flux.just(INIT_SENTINEL, "suinevere\r\n"), String::class.java)
			.retrieveFlux<String>()
			.scan("") { acc, chunk -> acc + chunk }
			.filter { it.endsWith("suinevere\r\n") }
			.next()

		StepVerifier.create(output)
			.assertNext { assertEquals("> suinevere\r\n", it) }
			.expectComplete()
			.verify(Duration.ofSeconds(15))
	}

	@Test
	fun `preserves high bytes across the whole bridge`() {
		val output = requester()
			.route("terminal.session")
			.data(Flux.just(INIT_SENTINEL, "éÿ\r\n"), String::class.java)
			.retrieveFlux<String>()
			.scan("") { acc, chunk -> acc + chunk }
			.filter { it.endsWith("\r\n") }
			.next()

		StepVerifier.create(output)
			.assertNext { assertEquals("> éÿ\r\n", it) }
			.expectComplete()
			.verify(Duration.ofSeconds(15))
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
