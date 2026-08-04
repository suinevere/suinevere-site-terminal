/*----------------------
 | SuinevereSiteTerminalApplicationTests.kt
 | Description: Verifies the context loads and RSocket is exposed over WebSocket on the web port.
 | Author: suinevere
 | Dependencies: spring-boot-starter-test
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SuinevereSiteTerminalApplicationTests {

	@Value("\${spring.rsocket.server.transport}")
	private lateinit var transport: String

	@Value("\${spring.rsocket.server.mapping-path}")
	private lateinit var mappingPath: String

	@Test
	fun `rsocket is exposed over websocket`() {
		assertEquals("websocket", transport)
		assertEquals("/rsocket", mappingPath)
	}

	@Test
	fun `webflux is on the classpath`() {
		assertTrue(
			runCatching { Class.forName("org.springframework.web.reactive.DispatcherHandler") }.isSuccess,
			"WebFlux is required for the RSocket WebSocket transport",
		)
	}
}
