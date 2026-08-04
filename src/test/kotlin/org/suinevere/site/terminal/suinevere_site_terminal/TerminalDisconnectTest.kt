/*----------------------
 | TerminalDisconnectTest.kt
 | Description: Verifies an upstream hangup reaches the terminal as readable text.
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
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalDisconnectTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `reports an upstream hangup as terminal text`() {
		val output = RSocketRequester.builder()
			.dataMimeType(MimeTypeUtils.TEXT_PLAIN)
			.websocket(URI.create("ws://localhost:$port/rsocket"))
			.route("terminal.session")
			.data(Flux.just(INIT_SENTINEL), String::class.java)
			.retrieveFlux<String>()
			.reduce("") { acc, chunk -> acc + chunk }

		StepVerifier.create(output)
			.assertNext { assertEquals("bye\r\n$DISCONNECTED_MESSAGE", it) }
			.expectComplete()
			.verify(Duration.ofSeconds(15))
	}

	companion object {
		private val upstream = EchoTcpServer.startThenClose("bye\r\n")

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
