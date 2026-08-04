/*----------------------
 | TerminalResilienceTest.kt
 | Description: Verifies unreachable upstream and session cap surface as readable terminal text.
 | Author: suinevere
 | Dependencies: spring-boot-starter-test, reactor-test
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.rsocket.RSocketRequester
import org.springframework.messaging.rsocket.retrieveFlux
import org.springframework.util.MimeTypeUtils
import org.springframework.test.context.TestPropertySource
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
	properties = [
		"terminal.upstream.host=127.0.0.1",
		"terminal.upstream.port=1",
		"terminal.upstream.connect-timeout=2s",
	],
)
class TerminalResilienceTest {

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
	fun `reports an unreachable upstream as terminal text`() {
		StepVerifier.create(openSession())
			.expectNext(unreachableMessage("127.0.0.1", 1))
			.expectComplete()
			.verify(Duration.ofSeconds(20))
	}
}
