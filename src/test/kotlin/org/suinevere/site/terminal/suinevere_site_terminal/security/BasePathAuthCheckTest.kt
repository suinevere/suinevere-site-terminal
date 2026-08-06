/*----------------------
 | BasePathAuthCheckTest.kt
 | Description: Proves spring.webflux.base-path actually gates /_authcheck on a real server, since bindToApplicationContext bypasses the HttpHandler layer where base-path is applied.
 | Author: suinevere
 | Dependencies: SecurityConfig, spring-boot-starter-webflux-test, WebTestClient
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal.security

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		"spring.security.oauth2.client.registration.google.client-id=test-client",
		"spring.security.oauth2.client.registration.google.client-secret=test-secret",
	],
)
class BasePathAuthCheckTest {

	@LocalServerPort
	private var port: Int = 0

	/*----------------------
	 | client
	 | Description: A WebTestClient bound to the actual running server rather than the application context, so spring.webflux.base-path is genuinely exercised.
	 | Author: suinevere
	 | Dependencies: N/A
	 | Globals: N/A
	 | Params: N/A
	 | Returns: N/A
	 ----------------------*/
	private val client: WebTestClient
		get() = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()

	@Test
	fun `the prefixed auth check path answers 401 unauthenticated`() {
		client.get().uri("/zork/_authcheck")
			.exchange()
			.expectStatus().isUnauthorized
	}

	@Test
	fun `the unprefixed auth check path 404s because the base path was not sent`() {
		client.get().uri("/_authcheck")
			.exchange()
			.expectStatus().isNotFound
	}
}
