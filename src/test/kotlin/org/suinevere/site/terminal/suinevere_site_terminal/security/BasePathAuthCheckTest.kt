/*----------------------
 | BasePathAuthCheckTest.kt
 | Description: Proves spring.webflux.base-path actually gates /_authcheck on a real server, since bindToApplicationContext bypasses the HttpHandler layer where base-path is applied.
 | Author: suinevere
 | Dependencies: SecurityConfig, spring-boot-starter-webflux-test, WebTestClient
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal.security

import org.junit.jupiter.api.Assertions.assertTrue
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

	/*----------------------
	 | assertRedirectsToGoogleLogin
	 | Description: Shares the Location assertion used by both the slashed and slashless base-path cases below.
	 | Author: suinevere
	 | Dependencies: N/A
	 | Globals: N/A
	 | Params: uri -- the path to request unauthenticated
	 | Returns: N/A
	 ----------------------*/
	private fun assertRedirectsToGoogleLogin(uri: String) {
		val result = client.get().uri(uri)
			.exchange()
			.expectStatus().is3xxRedirection
			.returnResult(Void::class.java)

		val location = result.responseHeaders.getFirst("Location")
		assertTrue(!location.isNullOrEmpty(), "Location header was missing for $uri")
		assertTrue(
			location!!.contains("/zork/oauth2/authorization/google"),
			"Location for $uri was: $location",
		)
	}

	@Test
	fun `the base path with a trailing slash redirects to the google login start`() {
		assertRedirectsToGoogleLogin("/zork/")
	}

	// spring-projects/spring-security#8967 (still open) reports that a base path plus
	// oauth2Login breaks the slashless login redirect. Measured against this Spring Boot 4.1
	// build, it does not reproduce: /zork redirects identically to /zork/, both landing on
	// /zork/oauth2/authorization/google. The nginx redirect added alongside this test is kept
	// regardless, as cost-free insurance against a future Spring Security regression.
	@Test
	fun `the base path without a trailing slash also redirects to the google login start`() {
		assertRedirectsToGoogleLogin("/zork")
	}

	// The session cookie defaults to Path=/zork/, which RFC 6265 path-matching does not send
	// to /zork. Sign-in then completed and the post-login redirect to /zork arrived with no
	// cookie, bouncing the visitor back to Google and landing on /zork/login?error.
	@Test
	fun `the session cookie path has no trailing slash so it is sent to the base path itself`() {
		val result = client.get().uri("/zork/oauth2/authorization/google")
			.exchange()
			.expectStatus().is3xxRedirection
			.returnResult(Void::class.java)

		val setCookie = result.responseHeaders["Set-Cookie"]?.firstOrNull { it.startsWith("SESSION=") }
		assertTrue(setCookie != null, "no SESSION cookie was issued by the authorization endpoint")

		val path = Regex("(?i)Path=([^;]+)").find(setCookie!!)?.groupValues?.get(1)
		assertTrue(path == "/zork", "session cookie Path was '$path', expected '/zork'")
	}
}
