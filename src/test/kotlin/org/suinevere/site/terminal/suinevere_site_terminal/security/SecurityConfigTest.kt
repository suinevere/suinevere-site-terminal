/*----------------------
 | SecurityConfigTest.kt
 | Description: Proves the app admits only a Google identity and offers no password credential of any kind.
 | Author: suinevere
 | Dependencies: SecurityConfig, spring-security-test, spring-boot-starter-webflux-test, spring-boot-starter-security-test, WebTestClient
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOAuth2Login
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
	properties = [
		"spring.security.oauth2.client.registration.google.client-id=test-client",
		"spring.security.oauth2.client.registration.google.client-secret=test-secret",
	],
)
@AutoConfigureWebTestClient
class SecurityConfigTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `an anonymous visitor is sent to google`() {
		val location = client.get().uri("/")
			.exchange()
			.expectStatus().is3xxRedirection
			.returnResult(String::class.java)
			.responseHeaders.location

		assertNotNull(location)
		assertTrue(
			location.toString().endsWith("/oauth2/authorization/google"),
			"expected a redirect to the google authorization endpoint, got $location",
		)
	}

	@Test
	fun `a google identity is admitted`() {
		client.mutateWith(mockOAuth2Login())
			.get().uri("/")
			.exchange()
			.expectStatus().isOk
	}

	@Test
	fun `there is no form login page`() {
		client.get().uri("/login")
			.exchange()
			.expectStatus().is3xxRedirection
	}

	@Test
	fun `the auth check endpoint answers 401 and never a redirect`() {
		client.get().uri("/_authcheck")
			.exchange()
			.expectStatus().isUnauthorized
	}

	@Test
	fun `the auth check endpoint answers 204 once signed in`() {
		client.mutateWith(mockOAuth2Login())
			.get().uri("/_authcheck")
			.exchange()
			.expectStatus().isNoContent
	}

	@Test
	fun `the unauthenticated entry point offers no password field`() {
		val body = client.get().uri("/login")
			.exchange()
			.expectBody(String::class.java)
			.returnResult()
			.responseBody ?: ""

		assertTrue(!body.contains("type=\"password\""), "a password input was rendered: $body")
		assertTrue(!body.contains("name=\"password\""), "a password field name was rendered: $body")
	}

	@Test
	fun `basic authentication is not offered`() {
		val challenge = client.get().uri("/")
			.header("Authorization", "Basic dXNlcjpwYXNzd29yZA==")
			.exchange()
			.returnResult(String::class.java)
			.responseHeaders.getFirst("WWW-Authenticate")

		assertNull(challenge, "the app offered a password challenge: $challenge")
	}
}
