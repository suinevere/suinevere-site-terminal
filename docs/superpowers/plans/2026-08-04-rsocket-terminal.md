# RSocket Terminal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the line-oriented TCP service at `suinevere.duckdns.org:23` inside a browser terminal, bridged through Spring Boot over RSocket.

**Architecture:** A React page renders XTerm.js and owns echo and line editing, because the upstream service does not echo. Each terminal opens one RSocket `requestChannel` over WebSocket, and the backend maps that channel one-to-one onto a reactor-netty TCP connection, so the channel's lifetime is the socket's lifetime and no session registry is needed.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.1.0 (RSocket + WebFlux), reactor-netty, Gradle 9.5.1 with Foojay-provisioned JDK 25, React 19, Vite, TypeScript, XTerm.js 6, `@rsocket/core` 1.0.0-alpha.3, JUnit 5 + StepVerifier, vitest.

**Spec:** `docs/superpowers/specs/2026-08-04-rsocket-terminal-design.md`

## Global Constraints

- Package for all backend code: `org.suinevere.site.terminal.suinevere_site_terminal`.
- Spring Boot `4.1.0`, Kotlin `2.3.21`, Java toolchain `25`, Gradle `9.5.1`. Do not change these versions.
- Upstream charset is **ISO-8859-1** everywhere bytes become text or text becomes bytes. Never UTF-8 on the TCP leg.
- Upstream host and port come only from configuration. Never accept them from a client payload.
- Frontend dependency versions are **pinned exactly** — no `^`, no `~`.
- Author of record is **suinevere**.
- Every file, function, and constant gets a header block in the form below. Tests and generated files get a file header only, not per-function headers.

```kotlin
/*----------------------
 | open
 | Description: One sentence on what it does.
 | Author: suinevere
 | Dependencies: TerminalProperties, reactor-netty
 | Globals: N/A
 | Params: inbound -- lines typed by the browser, already CRLF-terminated
 | Returns: Flux of upstream output decoded as ISO-8859-1
 ----------------------*/
```

- No comments inside function bodies.
- Commit after every task. Message is **one sentence**, no body, no bullets, no trailers. Never mention Claude, AI, or the session, and never add a `Claude-Session:` line or a `claude.ai/code` URL.
- Run backend tests with `./gradlew test`. Run frontend tests with `npm test --prefix frontend`.

---

## File Structure

**Backend** — `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/`

| File | Responsibility |
|---|---|
| `SuinevereSiteTerminalApplication.kt` | Entry point. Gains `@ConfigurationPropertiesScan`. |
| `TerminalProperties.kt` | Bound configuration: upstream host, port, timeout, caps. |
| `UpstreamTcpClient.kt` | The only networking code. One TCP connection per call. |
| `TerminalController.kt` | RSocket route, sentinel filtering, error text, session cap. |
| `TerminalMessages.kt` | User-facing bracketed strings and the handshake sentinel. |

**Backend tests** — `src/test/kotlin/.../`

| File | Responsibility |
|---|---|
| `SuinevereSiteTerminalApplicationTests.kt` | Context loads (exists). |
| `TerminalPropertiesTest.kt` | Configuration binding. |
| `EchoTcpServer.kt` | Test fixture: local upstream on an ephemeral port. |
| `UpstreamTcpClientTest.kt` | Byte fidelity, round-trip, disconnect, connect failure. |
| `TerminalControllerTest.kt` | RSocket channel round-trip and sentinel filtering. |
| `TerminalResilienceTest.kt` | Unreachable upstream surfaces as terminal text. |
| `TerminalSessionCapTest.kt` | Concurrent session cap rejects the extra session. |

**Frontend** — `frontend/`

| File | Responsibility |
|---|---|
| `package.json`, `vite.config.ts`, `tsconfig.json`, `index.html` | Build config and Node-global shims. |
| `src/terminal/lineEditor.ts` | Pure line editing and echo. No DOM, no network. |
| `src/terminal/lineEditor.test.ts` | Unit tests for the editor. |
| `src/rsocket/terminalSession.ts` | RSocket connect + channel. `onData` / `send` / `close`. |
| `src/components/Terminal.tsx` | XTerm mount, wiring, connection status, Reconnect. |
| `src/App.tsx`, `src/main.tsx` | Shell. |
| `scripts/verify-rsocket.mjs` | Live round-trip check against a running backend. |

---

## Task 1: Build foundation and WebSocket RSocket transport

Gradle must provision JDK 25 on its own, and RSocket must answer on the WebFlux port over WebSocket rather than raw TCP.

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts:21-30`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/SuinevereSiteTerminalApplication.kt`
- Test: `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/SuinevereSiteTerminalApplicationTests.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a running context with an RSocket WebSocket endpoint at `/rsocket` on the WebFlux port, and `@ConfigurationPropertiesScan` active for Task 2.

- [ ] **Step 1: Add the Foojay toolchain resolver**

Replace the whole of `settings.gradle.kts`:

```kotlin
plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "suinevere-site-terminal"
```

- [ ] **Step 2: Add WebFlux**

In `build.gradle.kts`, add this line immediately after the `spring-boot-starter-rsocket` line:

```kotlin
	implementation("org.springframework.boot:spring-boot-starter-webflux")
```

Add these two test dependencies after the existing `testImplementation` lines:

```kotlin
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
```

- [ ] **Step 3: Configure the WebSocket transport**

Replace the whole of `src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: suinevere-site-terminal
  rsocket:
    server:
      transport: websocket
      mapping-path: /rsocket

terminal:
  upstream:
    host: suinevere.duckdns.org
    port: 23
    connect-timeout: 10s
    max-sessions: 32
    output-buffer: 1024
```

- [ ] **Step 4: Enable configuration property scanning**

Replace the whole of `SuinevereSiteTerminalApplication.kt`:

```kotlin
/*----------------------
 | SuinevereSiteTerminalApplication.kt
 | Description: Boots the RSocket bridge between browser terminals and the upstream TCP service.
 | Author: suinevere
 | Dependencies: spring-boot-starter-rsocket, spring-boot-starter-webflux
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SuinevereSiteTerminalApplication

/*----------------------
 | main
 | Description: Process entry point.
 | Author: suinevere
 | Dependencies: SuinevereSiteTerminalApplication
 | Globals: N/A
 | Params: args -- command line arguments passed to Spring Boot
 | Returns: N/A
 ----------------------*/
fun main(args: Array<String>) {
	runApplication<SuinevereSiteTerminalApplication>(*args)
}
```

- [ ] **Step 5: Write the failing test**

Replace the whole of `SuinevereSiteTerminalApplicationTests.kt`:

```kotlin
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
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests '*SuinevereSiteTerminalApplicationTests*'`

Expected: Gradle downloads Temurin JDK 25 on first run (this can take a few minutes), then both tests PASS. If the toolchain download fails, the error names the missing JDK — do not lower the toolchain version, fix the resolver.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts src/main/resources/application.yaml src/main/kotlin src/test/kotlin
git commit -m "Add WebFlux and expose RSocket over WebSocket with a Foojay-provisioned JDK 25 toolchain"
```

---

## Task 2: Upstream configuration properties

**Files:**
- Create: `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/TerminalProperties.kt`
- Test: `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/TerminalPropertiesTest.kt`

**Interfaces:**
- Consumes: `@ConfigurationPropertiesScan` from Task 1.
- Produces: `TerminalProperties(host: String, port: Int, connectTimeout: Duration, maxSessions: Int, outputBuffer: Int)` — a data class, injectable, also directly constructible in tests.

- [ ] **Step 1: Write the failing test**

Create `TerminalPropertiesTest.kt`:

```kotlin
/*----------------------
 | TerminalPropertiesTest.kt
 | Description: Verifies upstream settings bind from application.yaml.
 | Author: suinevere
 | Dependencies: spring-boot-starter-test
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import kotlin.test.assertEquals

@SpringBootTest
class TerminalPropertiesTest {

	@Autowired
	private lateinit var properties: TerminalProperties

	@Test
	fun `binds upstream settings from configuration`() {
		assertEquals("suinevere.duckdns.org", properties.host)
		assertEquals(23, properties.port)
		assertEquals(Duration.ofSeconds(10), properties.connectTimeout)
		assertEquals(32, properties.maxSessions)
		assertEquals(1024, properties.outputBuffer)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TerminalPropertiesTest*'`
Expected: FAIL — compilation error, `TerminalProperties` is not defined.

- [ ] **Step 3: Write the implementation**

Create `TerminalProperties.kt`:

```kotlin
/*----------------------
 | TerminalProperties.kt
 | Description: Bound settings describing the upstream TCP service and session limits.
 | Author: suinevere
 | Dependencies: spring-boot-autoconfigure
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/*----------------------
 | TerminalProperties
 | Description: Upstream endpoint and safety limits, sourced only from server configuration.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: host -- upstream hostname; port -- upstream TCP port; connectTimeout -- how long to wait for the socket;
 |         maxSessions -- concurrent bridged connections allowed; outputBuffer -- upstream chunks buffered per session
 | Returns: N/A
 ----------------------*/
@ConfigurationProperties("terminal.upstream")
data class TerminalProperties(
	val host: String = "suinevere.duckdns.org",
	val port: Int = 23,
	val connectTimeout: Duration = Duration.ofSeconds(10),
	val maxSessions: Int = 32,
	val outputBuffer: Int = 1024,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*TerminalPropertiesTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "Add bound configuration properties for the upstream terminal endpoint"
```

---

## Task 3: Upstream TCP client

The heart of the bridge. A local echo server stands in for the real host so tests stay hermetic.

**Files:**
- Create: `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/UpstreamTcpClient.kt`
- Create: `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/EchoTcpServer.kt`
- Test: `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/UpstreamTcpClientTest.kt`

**Interfaces:**
- Consumes: `TerminalProperties` from Task 2.
- Produces: `UpstreamTcpClient(properties: TerminalProperties)` with `fun open(inbound: Flux<String>): Flux<String>`. Task 4 calls exactly this.
- Produces (test-only): `EchoTcpServer.startEchoing(banner: String): DisposableServer` and `EchoTcpServer.startThenClose(banner: String): DisposableServer`, both bound to `127.0.0.1` on an ephemeral port; read the port with `server.port()`.

- [ ] **Step 1: Write the test fixture**

Create `EchoTcpServer.kt`:

```kotlin
/*----------------------
 | EchoTcpServer.kt
 | Description: Local stand-in for the upstream service, so client tests need no network.
 | Author: suinevere
 | Dependencies: reactor-netty
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.tcp.TcpServer
import java.nio.charset.StandardCharsets

object EchoTcpServer {

	fun startEchoing(banner: String): DisposableServer =
		TcpServer.create()
			.host("127.0.0.1")
			.port(0)
			.handle { inbound, outbound ->
				outbound.sendString(
					Flux.concat(
						Flux.just(banner),
						inbound.receive().asString(StandardCharsets.ISO_8859_1),
					),
					StandardCharsets.ISO_8859_1,
				).neverComplete()
			}
			.bindNow()

	fun startThenClose(banner: String): DisposableServer =
		TcpServer.create()
			.host("127.0.0.1")
			.port(0)
			.handle { _, outbound ->
				outbound.sendString(Mono.just(banner), StandardCharsets.ISO_8859_1)
			}
			.bindNow()
}
```

- [ ] **Step 2: Write the failing tests**

Create `UpstreamTcpClientTest.kt`:

```kotlin
/*----------------------
 | UpstreamTcpClientTest.kt
 | Description: Verifies byte fidelity, line round-trip, disconnect propagation, and connect failure.
 | Author: suinevere
 | Dependencies: reactor-test, EchoTcpServer
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.time.Duration

class UpstreamTcpClientTest {

	private fun clientFor(port: Int) = UpstreamTcpClient(
		TerminalProperties(host = "127.0.0.1", port = port, connectTimeout = Duration.ofSeconds(5)),
	)

	@Test
	fun `receives the upstream banner`() {
		val server = EchoTcpServer.startEchoing("Hello sailor!\r\n>")
		try {
			StepVerifier.create(clientFor(server.port()).open(Flux.never()).take(1))
				.expectNext("Hello sailor!\r\n>")
				.verifyComplete()
		} finally {
			server.disposeNow()
		}
	}

	@Test
	fun `sends a typed line upstream and returns the reply`() {
		val server = EchoTcpServer.startEchoing("> ")
		try {
			val output = clientFor(server.port())
				.open(Flux.just("suinevere\r\n"))
				.take(2)
				.reduce("") { acc, chunk -> acc + chunk }

			StepVerifier.create(output)
				.expectNext("> suinevere\r\n")
				.verifyComplete()
		} finally {
			server.disposeNow()
		}
	}

	@Test
	fun `preserves high bytes instead of corrupting them`() {
		val server = EchoTcpServer.startEchoing("")
		try {
			val output = clientFor(server.port())
				.open(Flux.just("éÿ\r\n"))
				.filter { it.isNotEmpty() }
				.take(1)

			StepVerifier.create(output)
				.expectNext("éÿ\r\n")
				.verifyComplete()
		} finally {
			server.disposeNow()
		}
	}

	@Test
	fun `completes when upstream hangs up`() {
		val server = EchoTcpServer.startThenClose("bye\r\n")
		try {
			StepVerifier.create(clientFor(server.port()).open(Flux.never()))
				.expectNext("bye\r\n")
				.expectComplete()
				.verify(Duration.ofSeconds(10))
		} finally {
			server.disposeNow()
		}
	}

	@Test
	fun `errors when upstream is unreachable`() {
		StepVerifier.create(clientFor(1).open(Flux.never()))
			.expectError()
			.verify(Duration.ofSeconds(15))
	}
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests '*UpstreamTcpClientTest*'`
Expected: FAIL — compilation error, `UpstreamTcpClient` is not defined.

- [ ] **Step 4: Write the implementation**

Create `UpstreamTcpClient.kt`:

```kotlin
/*----------------------
 | UpstreamTcpClient.kt
 | Description: Opens one TCP connection to the upstream service and bridges it to a pair of Flux streams.
 | Author: suinevere
 | Dependencies: TerminalProperties, reactor-netty
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.tcp.TcpClient
import java.nio.charset.StandardCharsets

@Component
class UpstreamTcpClient(private val properties: TerminalProperties) {

	/*----------------------
	 | open
	 | Description: Connects upstream, pumps inbound lines out and returns decoded upstream output.
	 | Author: suinevere
	 | Dependencies: TerminalProperties, reactor-netty TcpClient
	 | Globals: N/A
	 | Params: inbound -- lines typed by the browser, already CRLF-terminated
	 | Returns: Flux of upstream output decoded as ISO-8859-1, completing when either side closes
	 ----------------------*/
	fun open(inbound: Flux<String>): Flux<String> =
		TcpClient.create()
			.host(properties.host)
			.port(properties.port)
			.connect()
			.timeout(properties.connectTimeout)
			.flatMapMany { connection ->
				val pump: Mono<String> = connection.outbound()
					.sendString(inbound, StandardCharsets.ISO_8859_1)
					.then()
					.cast(String::class.java)
					.onErrorComplete()

				connection.inbound()
					.receive()
					.asString(StandardCharsets.ISO_8859_1)
					.mergeWith(pump)
					.onBackpressureBuffer(properties.outputBuffer)
					.doFinally { connection.dispose() }
			}
}
```

The write pump is a `Mono<Void>` cast to `Mono<String>`. It emits no elements, so the cast never runs; merging it just keeps writes flowing concurrently with reads on the same connection. `onErrorComplete` absorbs the abort that reactor-netty raises on the outbound side when the channel closes normally, which would otherwise surface to the user as an error.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests '*UpstreamTcpClientTest*'`
Expected: all five PASS.

If `completes when upstream hangs up` hangs instead of completing, the merged pump is holding the stream open. Fix it by deriving termination from the read side, replacing the `mergeWith(pump)` line with:

```kotlin
					.mergeWith(pump)
					.takeUntilOther(connection.onDispose())
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "Bridge one upstream TCP connection per session with ISO-8859-1 byte fidelity"
```

---

## Task 4: RSocket route and handshake sentinel

**Files:**
- Create: `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/TerminalMessages.kt`
- Create: `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/TerminalController.kt`
- Test: `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/TerminalControllerTest.kt`

**Interfaces:**
- Consumes: `UpstreamTcpClient.open` from Task 3, `TerminalProperties` from Task 2.
- Produces: RSocket route `terminal.session` (requestChannel, `String` in, `String` out) and the constants `INIT_SENTINEL`, `BUSY_MESSAGE`, `DISCONNECTED_MESSAGE`, plus `unreachableMessage(host: String, port: Int): String`. Task 5 tests the error paths; Task 7 speaks this route from the browser.

- [ ] **Step 1: Write the constants**

Create `TerminalMessages.kt`:

```kotlin
/*----------------------
 | TerminalMessages.kt
 | Description: Handshake sentinel and the bracketed strings shown to the user in the terminal.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: INIT_SENTINEL, BUSY_MESSAGE, DISCONNECTED_MESSAGE
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

/*----------------------
 | INIT_SENTINEL
 | Description: First channel payload, sent only to carry route metadata and never forwarded upstream.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const val INIT_SENTINEL: String = "\u0000INIT"

/*----------------------
 | BUSY_MESSAGE
 | Description: Shown when the concurrent session cap is already reached.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const val BUSY_MESSAGE: String = "\r\n[server busy, try again shortly]\r\n"

/*----------------------
 | DISCONNECTED_MESSAGE
 | Description: Shown when the upstream service closes the connection.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const val DISCONNECTED_MESSAGE: String = "\r\n[disconnected]\r\n"

/*----------------------
 | unreachableMessage
 | Description: Builds the message shown when the upstream socket cannot be opened.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: host -- configured upstream host; port -- configured upstream port
 | Returns: Bracketed, CRLF-wrapped text safe to write straight to a terminal
 ----------------------*/
fun unreachableMessage(host: String, port: Int): String = "\r\n[unable to reach $host:$port]\r\n"
```

A leading NUL makes `INIT_SENTINEL` unreachable from the keyboard, because the line editor in Task 6 drops control characters. No user input can collide with it.

- [ ] **Step 2: Write the failing test**

Create `TerminalControllerTest.kt`. The upstream stand-in starts in a static initializer so it is listening before Spring resolves `@DynamicPropertySource`:

```kotlin
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
import reactor.test.StepVerifier
import java.net.URI

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalControllerTest {

	@LocalServerPort
	private var port: Int = 0

	private fun requester(): RSocketRequester =
		RSocketRequester.builder()
			.dataMimeType(MimeTypeUtils.TEXT_PLAIN)
			.websocket(URI.create("ws://localhost:$port/rsocket"))

	@Test
	fun `round-trips a typed line through the channel`() {
		val output = requester()
			.route("terminal.session")
			.data(Flux.just(INIT_SENTINEL, "suinevere\r\n"), String::class.java)
			.retrieveFlux<String>()
			.take(2)
			.reduce("") { acc, chunk -> acc + chunk }

		StepVerifier.create(output)
			.expectNext("> suinevere\r\n")
			.verifyComplete()
	}

	@Test
	fun `never forwards the handshake sentinel upstream`() {
		val output = requester()
			.route("terminal.session")
			.data(Flux.just(INIT_SENTINEL), String::class.java)
			.retrieveFlux<String>()
			.take(1)

		StepVerifier.create(output)
			.expectNext("> ")
			.verifyComplete()
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
```

The second test proves the sentinel is filtered: the echo server returns everything it receives, so if the sentinel reached upstream the first chunk would contain it rather than just the banner.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests '*TerminalControllerTest*'`
Expected: FAIL — compilation error, `TerminalController` is not defined.

- [ ] **Step 4: Write the implementation**

Create `TerminalController.kt`:

```kotlin
/*----------------------
 | TerminalController.kt
 | Description: Maps the RSocket terminal channel onto one upstream TCP connection.
 | Author: suinevere
 | Dependencies: UpstreamTcpClient, TerminalProperties, TerminalMessages
 | Globals: INIT_SENTINEL
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux

@Controller
class TerminalController(private val upstream: UpstreamTcpClient) {

	/*----------------------
	 | session
	 | Description: Bridges one browser terminal to one upstream TCP connection for the channel's lifetime.
	 | Author: suinevere
	 | Dependencies: UpstreamTcpClient
	 | Globals: INIT_SENTINEL
	 | Params: inbound -- payloads from the browser, the first being the handshake sentinel
	 | Returns: Flux of upstream output to write straight into the terminal
	 ----------------------*/
	@MessageMapping("terminal.session")
	fun session(inbound: Flux<String>): Flux<String> =
		upstream.open(inbound.filter { it != INIT_SENTINEL })
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests '*TerminalControllerTest*'`
Expected: both PASS.

If routing fails with "No handler for destination", the client is not sending composite metadata — confirm `RSocketRequester.builder()` was used without overriding `metadataMimeType`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "Expose the terminal session as an RSocket channel and filter the handshake sentinel"
```

---

## Task 5: Error text and session cap

Failures must reach the terminal as readable text, never as an RSocket error frame that leaves a dead prompt.

**Files:**
- Modify: `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/TerminalController.kt`
- Test: `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/TerminalResilienceTest.kt`
- Test: `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/TerminalSessionCapTest.kt`

The two failure modes need opposite upstreams — a dead port to force a connect failure, a live one to hold a session slot open — so they are two classes, not two methods.

**Interfaces:**
- Consumes: `TerminalController.session` from Task 4, `unreachableMessage`, `BUSY_MESSAGE`, `DISCONNECTED_MESSAGE` from Task 4, `TerminalProperties.maxSessions` from Task 2.
- Produces: no new signatures. Behavior only.

- [ ] **Step 1: Write the failing tests**

Create `TerminalResilienceTest.kt`:

```kotlin
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
```

Now create `TerminalSessionCapTest.kt`. The first session must be proven connected before the second opens, otherwise the cap check races the channel setup — so the test waits on a latch fed by the upstream banner, and holds the subscription open for the duration:

```kotlin
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
```

`EchoTcpServer.startEchoing` never completes its outbound, so the first session keeps its slot for the whole test. The assertion is a single exact value — remove the cap and this test fails.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*TerminalResilienceTest*' --tests '*TerminalSessionCapTest*'`
Expected: FAIL — the channel errors instead of emitting `[unable to reach 127.0.0.1:1]`, and no cap is enforced.

- [ ] **Step 3: Write the implementation**

Replace the whole of `TerminalController.kt`:

```kotlin
/*----------------------
 | TerminalController.kt
 | Description: Maps the RSocket terminal channel onto one upstream TCP connection, with caps and readable failures.
 | Author: suinevere
 | Dependencies: UpstreamTcpClient, TerminalProperties, TerminalMessages
 | Globals: INIT_SENTINEL, BUSY_MESSAGE, DISCONNECTED_MESSAGE
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

@Controller
class TerminalController(
	private val upstream: UpstreamTcpClient,
	private val properties: TerminalProperties,
) {

	private val active = AtomicInteger(0)

	/*----------------------
	 | session
	 | Description: Bridges one browser terminal to one upstream TCP connection for the channel's lifetime.
	 | Author: suinevere
	 | Dependencies: UpstreamTcpClient, TerminalProperties
	 | Globals: INIT_SENTINEL, BUSY_MESSAGE, DISCONNECTED_MESSAGE
	 | Params: inbound -- payloads from the browser, the first being the handshake sentinel
	 | Returns: Flux of upstream output, or a bracketed explanation, to write straight into the terminal
	 ----------------------*/
	@MessageMapping("terminal.session")
	fun session(inbound: Flux<String>): Flux<String> = Flux.defer {
		if (active.incrementAndGet() > properties.maxSessions) {
			active.decrementAndGet()
			return@defer Flux.just(BUSY_MESSAGE)
		}
		upstream.open(inbound.filter { it != INIT_SENTINEL })
			.concatWith(Flux.just(DISCONNECTED_MESSAGE))
			.onErrorResume { Flux.just(unreachableMessage(properties.host, properties.port)) }
			.doFinally { active.decrementAndGet() }
	}
}
```

`Flux.defer` moves the cap check to subscription time, so a channel that is never subscribed cannot leak a slot.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*TerminalResilienceTest*' --tests '*TerminalSessionCapTest*'`
Expected: both PASS.

- [ ] **Step 5: Run the whole backend suite**

Run: `./gradlew test`
Expected: all tests PASS.

Task 4's `round-trips a typed line` test takes two chunks and now a third (`DISCONNECTED_MESSAGE`) exists on the stream. It still passes because `take(2)` stops first. If it does not, raise its `take` and assert the disconnect notice explicitly.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "Report upstream failures and the session cap as readable terminal text"
```

---

## Task 6: Frontend scaffold and line editor

The line editor is pure — no DOM, no network — so it is the one piece of frontend logic with real unit tests.

**Files:**
- Create: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/vite.config.ts`, `frontend/index.html`
- Create: `frontend/src/terminal/lineEditor.ts`
- Test: `frontend/src/terminal/lineEditor.test.ts`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: `createLineEditor(handlers: { onEcho: (text: string) => void; onLine: (line: string) => void }): { handleInput(data: string): void }`. Task 8 wires XTerm's `onData` straight into `handleInput`.

- [ ] **Step 1: Create the package manifest with pinned versions**

Create `frontend/package.json`:

```json
{
  "name": "suinevere-site-terminal-frontend",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "test": "vitest run",
    "verify:rsocket": "node scripts/verify-rsocket.mjs"
  },
  "dependencies": {
    "@rsocket/composite-metadata": "1.0.0-alpha.3",
    "@rsocket/core": "1.0.0-alpha.3",
    "@rsocket/websocket-client": "1.0.0-alpha.3",
    "@xterm/addon-fit": "0.11.0",
    "@xterm/xterm": "6.0.0",
    "buffer": "6.0.3",
    "react": "19.1.0",
    "react-dom": "19.1.0"
  },
  "devDependencies": {
    "@types/react": "19.1.0",
    "@types/react-dom": "19.1.0",
    "@vitejs/plugin-react": "5.0.0",
    "typescript": "5.9.2",
    "vite": "7.1.0",
    "vitest": "3.2.0"
  }
}
```

- [ ] **Step 2: Create TypeScript and Vite configuration**

Create `frontend/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noEmit": true,
    "skipLibCheck": true,
    "types": ["vitest/globals"]
  },
  "include": ["src"]
}
```

Create `frontend/vite.config.ts`. `defineConfig` comes from `vitest/config`, not `vite`, because the `test` block is otherwise a type error. The `define` and `alias` entries exist because `@rsocket/core` expects Node globals that browsers do not provide:

```ts
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  define: {
    global: 'globalThis',
  },
  resolve: {
    alias: {
      buffer: 'buffer/',
    },
  },
  optimizeDeps: {
    include: ['buffer'],
  },
  server: {
    port: 5173,
    proxy: {
      '/rsocket': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'node',
  },
})
```

Create `frontend/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>suinevere</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 3: Ignore build output**

Append to `.gitignore`:

```
### Frontend ###
frontend/node_modules/
frontend/dist/
```

- [ ] **Step 4: Install dependencies**

Run: `npm install --prefix frontend`
Expected: completes and writes `frontend/package-lock.json`. Peer-dependency warnings from the alpha RSocket packages are expected and harmless; an `ERESOLVE` failure is not — if that happens, record the conflict before changing any pinned version.

- [ ] **Step 5: Write the failing tests**

Create `frontend/src/terminal/lineEditor.test.ts`:

```ts
/*----------------------
 | lineEditor.test.ts
 | Description: Unit tests for local echo, backspace bounds, Enter semantics, and control-key handling.
 | Author: suinevere
 | Dependencies: vitest, lineEditor
 | Globals: N/A
 ----------------------*/
import { describe, expect, it } from 'vitest'
import { createLineEditor } from './lineEditor'

function harness() {
  const echoed: string[] = []
  const lines: string[] = []
  const editor = createLineEditor({
    onEcho: (text) => echoed.push(text),
    onLine: (line) => lines.push(line),
  })
  return { editor, echoed, lines }
}

describe('createLineEditor', () => {
  it('echoes printable characters as they are typed', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('h')
    editor.handleInput('i')
    expect(echoed).toEqual(['h', 'i'])
    expect(lines).toEqual([])
  })

  it('flushes the buffered line on Enter and echoes a newline', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('h')
    editor.handleInput('i')
    editor.handleInput('\r')
    expect(lines).toEqual(['hi'])
    expect(echoed[echoed.length - 1]).toBe('\r\n')
  })

  it('flushes an empty line on a bare Enter', () => {
    const { editor, lines } = harness()
    editor.handleInput('\r')
    expect(lines).toEqual([''])
  })

  it('erases one character on Backspace', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('h')
    editor.handleInput('i')
    editor.handleInput('\x7f')
    editor.handleInput('\r')
    expect(echoed).toContain('\b \b')
    expect(lines).toEqual(['h'])
  })

  it('ignores Backspace at column zero so the prompt survives', () => {
    const { editor, echoed } = harness()
    editor.handleInput('\x7f')
    expect(echoed).toEqual([])
  })

  it('clears the buffer on Ctrl+C', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('h')
    editor.handleInput('\x03')
    editor.handleInput('\r')
    expect(echoed).toContain('^C\r\n')
    expect(lines).toEqual([''])
  })

  it('drops escape sequences instead of echoing their letters', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('\x1b[A')
    editor.handleInput('\r')
    expect(echoed).toEqual(['\r\n'])
    expect(lines).toEqual([''])
  })

  it('handles a pasted multi-character chunk', () => {
    const { editor, lines } = harness()
    editor.handleInput('suinevere')
    editor.handleInput('\r')
    expect(lines).toEqual(['suinevere'])
  })
})
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `npm test --prefix frontend`
Expected: FAIL — cannot resolve `./lineEditor`.

- [ ] **Step 7: Write the implementation**

Create `frontend/src/terminal/lineEditor.ts`:

```ts
/*----------------------
 | lineEditor.ts
 | Description: Local echo and line assembly for a terminal whose upstream never echoes.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 ----------------------*/

export type LineEditorHandlers = {
  onEcho: (text: string) => void
  onLine: (line: string) => void
}

export type LineEditor = {
  handleInput: (data: string) => void
}

const ENTER = '\r'
const BACKSPACE = '\x7f'
const CTRL_C = '\x03'
const ESCAPE = '\x1b'

/*----------------------
 | createLineEditor
 | Description: Builds a stateful editor that echoes keystrokes locally and emits completed lines.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: handlers -- onEcho writes to the terminal, onLine receives each completed line without its newline
 | Returns: LineEditor with handleInput for raw terminal data and reset to drop the buffer
 ----------------------*/
export function createLineEditor(handlers: LineEditorHandlers): LineEditor {
  let buffer = ''

  const handleChar = (char: string): void => {
    if (char === ENTER) {
      handlers.onEcho('\r\n')
      handlers.onLine(buffer)
      buffer = ''
      return
    }
    if (char === BACKSPACE) {
      if (buffer.length > 0) {
        buffer = buffer.slice(0, -1)
        handlers.onEcho('\b \b')
      }
      return
    }
    if (char === CTRL_C) {
      buffer = ''
      handlers.onEcho('^C\r\n')
      return
    }
    if (char >= ' ') {
      buffer += char
      handlers.onEcho(char)
    }
  }

  return {
    handleInput: (data: string): void => {
      if (data.startsWith(ESCAPE)) {
        return
      }
      for (const char of data) {
        handleChar(char)
      }
    },
  }
}
```

Escape-prefixed chunks are dropped whole, because arrow keys arrive as `\x1b[A` and echoing the `[` and `A` would corrupt the line.

- [ ] **Step 8: Run tests to verify they pass**

Run: `npm test --prefix frontend`
Expected: all eight PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/tsconfig.json frontend/vite.config.ts frontend/index.html frontend/src .gitignore
git commit -m "Scaffold the Vite frontend and add a tested local line editor"
```

---

## Task 7: RSocket session client

This task carries the plan's real risk. `@rsocket/core` has not shipped since February 2024, so prove the connection works before any UI depends on it.

**Files:**
- Create: `frontend/src/rsocket/terminalSession.ts`
- Create: `frontend/scripts/verify-rsocket.mjs`

**Interfaces:**
- Consumes: the `terminal.session` route and `INIT_SENTINEL` from Task 4.
- Produces: `openTerminalSession(options: { url: string; onData: (text: string) => void; onClose: (reason: string) => void }): Promise<TerminalSession>` where `TerminalSession = { send(line: string): void; close(): void }`. Task 8 consumes exactly this.

- [ ] **Step 1: Write the implementation**

Create `frontend/src/rsocket/terminalSession.ts`:

```ts
/*----------------------
 | terminalSession.ts
 | Description: Opens an RSocket channel over WebSocket and exposes it as a line-oriented terminal session.
 | Author: suinevere
 | Dependencies: @rsocket/core, @rsocket/websocket-client, @rsocket/composite-metadata, buffer
 | Globals: N/A
 ----------------------*/
import { Buffer } from 'buffer'
import { RSocketConnector } from '@rsocket/core'
import { WebsocketClientTransport } from '@rsocket/websocket-client'
import {
  encodeCompositeMetadata,
  encodeRoute,
  WellKnownMimeType,
} from '@rsocket/composite-metadata'

const ROUTE = 'terminal.session'
const INIT_SENTINEL = '\u0000INIT'
const REQUEST_N = 2147483647
const KEEPALIVE_MS = 20000
const LIFETIME_MS = 90000

export type TerminalSessionOptions = {
  url: string
  onData: (text: string) => void
  onClose: (reason: string) => void
}

export type TerminalSession = {
  send: (line: string) => void
  close: () => void
}

/*----------------------
 | routeMetadata
 | Description: Encodes the RSocket route as composite metadata, without which Spring cannot dispatch.
 | Author: suinevere
 | Dependencies: @rsocket/composite-metadata
 | Globals: N/A
 | Params: route -- the message mapping to target
 | Returns: Buffer holding one composite metadata entry
 ----------------------*/
function routeMetadata(route: string): Buffer {
  return encodeCompositeMetadata([
    [WellKnownMimeType.MESSAGE_RSOCKET_ROUTING, encodeRoute(route)],
  ])
}

/*----------------------
 | openTerminalSession
 | Description: Connects to the bridge and opens the bidirectional channel that carries one terminal.
 | Author: suinevere
 | Dependencies: @rsocket/core, @rsocket/websocket-client
 | Globals: N/A
 | Params: options -- endpoint URL plus callbacks for upstream output and channel termination
 | Returns: Promise of a TerminalSession for sending lines and closing the channel
 ----------------------*/
export async function openTerminalSession(
  options: TerminalSessionOptions,
): Promise<TerminalSession> {
  const connector = new RSocketConnector({
    setup: {
      keepAlive: KEEPALIVE_MS,
      lifetime: LIFETIME_MS,
      dataMimeType: 'text/plain',
      metadataMimeType: WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.toString(),
    },
    transport: new WebsocketClientTransport({ url: options.url }),
  })

  const rsocket = await connector.connect()

  const requester = rsocket.requestChannel(
    {
      data: Buffer.from(INIT_SENTINEL, 'latin1'),
      metadata: routeMetadata(ROUTE),
    },
    REQUEST_N,
    false,
    {
      onNext: (payload) => {
        if (payload.data) {
          options.onData(Buffer.from(payload.data).toString('latin1'))
        }
      },
      onError: (error) => options.onClose(error.message),
      onComplete: () => options.onClose('closed'),
      onExtension: () => {},
      request: () => {},
      cancel: () => {},
    },
  )

  return {
    send: (line: string): void => {
      requester.onNext({ data: Buffer.from(line, 'latin1') }, false)
    },
    close: (): void => {
      requester.cancel()
      rsocket.close()
    },
  }
}
```

`latin1` is Node's name for ISO-8859-1, matching the backend charset exactly, so bytes survive the round trip unchanged.

- [ ] **Step 2: Write the live verification script**

Create `frontend/scripts/verify-rsocket.mjs`. This runs on Node against the real backend, which is the only way to prove an abandoned alpha library actually speaks to Spring:

```js
/*----------------------
 | verify-rsocket.mjs
 | Description: Proves the RSocket client library round-trips against a running backend.
 | Author: suinevere
 | Dependencies: @rsocket/core, @rsocket/websocket-client, @rsocket/composite-metadata, ws
 | Globals: N/A
 ----------------------*/
import { Buffer } from 'buffer'
import { RSocketConnector } from '@rsocket/core'
import { WebsocketClientTransport } from '@rsocket/websocket-client'
import {
  encodeCompositeMetadata,
  encodeRoute,
  WellKnownMimeType,
} from '@rsocket/composite-metadata'

const URL = process.env.RSOCKET_URL ?? 'ws://localhost:8080/rsocket'

const connector = new RSocketConnector({
  setup: {
    keepAlive: 20000,
    lifetime: 90000,
    dataMimeType: 'text/plain',
    metadataMimeType: WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.toString(),
  },
  transport: new WebsocketClientTransport({
    url: URL,
    wsCreator: (url) => new WebSocket(url),
  }),
})

const rsocket = await connector.connect()
let received = ''

const requester = rsocket.requestChannel(
  {
    data: Buffer.from('\u0000INIT', 'latin1'),
    metadata: encodeCompositeMetadata([
      [WellKnownMimeType.MESSAGE_RSOCKET_ROUTING, encodeRoute('terminal.session')],
    ]),
  },
  2147483647,
  false,
  {
    onNext: (payload) => {
      received += Buffer.from(payload.data).toString('latin1')
      process.stdout.write(Buffer.from(payload.data).toString('latin1'))
    },
    onError: (error) => {
      console.error('\nERROR:', error.message)
      process.exit(1)
    },
    onComplete: () => {},
    onExtension: () => {},
    request: () => {},
    cancel: () => {},
  },
)

setTimeout(() => {
  if (!received.includes('Hello sailor')) {
    console.error('\nFAIL: no upstream banner received')
    process.exit(1)
  }
  console.log('\n\nOK: banner received, sending a bare Enter')
  requester.onNext({ data: Buffer.from('\r\n', 'latin1') }, false)
  setTimeout(() => {
    if (!received.includes("What's your name")) {
      console.error('\nFAIL: no response to Enter')
      process.exit(1)
    }
    console.log('\n\nOK: round trip verified')
    process.exit(0)
  }, 4000)
}, 4000)
```

Node 24 provides a global `WebSocket`, so `wsCreator` needs no extra dependency.

- [ ] **Step 3: Start the backend**

Run in a separate terminal: `./gradlew bootRun`
Expected: starts on port 8080. Leave it running.

- [ ] **Step 4: Verify the round trip against the real upstream**

Run: `npm run verify:rsocket --prefix frontend`
Expected: prints the `Hello sailor!` banner, then `OK: round trip verified`.

This is the go/no-go gate for the library. If it fails on a Node-global or module-resolution error, add the missing shim to `vite.config.ts` and retry. If it fails inside RSocket framing, stop and switch to the hand-rolled encoder fallback described in the spec — do not proceed to Task 8 on a broken transport.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/rsocket frontend/scripts
git commit -m "Add the RSocket terminal session client and a live round-trip verification script"
```

---

## Task 8: Terminal component

**Files:**
- Create: `frontend/src/components/Terminal.tsx`
- Create: `frontend/src/App.tsx`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/index.css`

**Interfaces:**
- Consumes: `createLineEditor` from Task 6, `openTerminalSession` from Task 7.
- Produces: a default-exported `Terminal` React component and the mounted app.

- [ ] **Step 1: Write the terminal component**

Create `frontend/src/components/Terminal.tsx`:

```tsx
/*----------------------
 | Terminal.tsx
 | Description: Mounts XTerm.js, wires local line editing to the RSocket session, and surfaces connection state.
 | Author: suinevere
 | Dependencies: @xterm/xterm, @xterm/addon-fit, react, lineEditor, terminalSession
 | Globals: N/A
 ----------------------*/
import { useEffect, useRef, useState } from 'react'
import { Terminal as XTerm } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { createLineEditor } from '../terminal/lineEditor'
import { openTerminalSession, type TerminalSession } from '../rsocket/terminalSession'

/*----------------------
 | sessionUrl
 | Description: Resolves the RSocket endpoint from the page origin so dev and production both work.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: WebSocket URL for the RSocket mapping path
 ----------------------*/
function sessionUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/rsocket`
}

/*----------------------
 | Terminal
 | Description: Renders the browser terminal for one upstream session.
 | Author: suinevere
 | Dependencies: XTerm, FitAddon, createLineEditor, openTerminalSession
 | Globals: N/A
 | Params: N/A
 | Returns: React element containing the terminal surface and a Reconnect control
 ----------------------*/
export default function Terminal() {
  const host = useRef<HTMLDivElement>(null)
  const [generation, setGeneration] = useState(0)
  const [live, setLive] = useState(true)

  useEffect(() => {
    if (!host.current) {
      return
    }

    const term = new XTerm({
      convertEol: false,
      cursorBlink: true,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
      fontSize: 14,
      theme: { background: '#0b0b0b', foreground: '#d8d8d8' },
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(host.current)
    fit.fit()

    const resize = () => fit.fit()
    window.addEventListener('resize', resize)

    let session: TerminalSession | null = null
    let disposed = false

    const editor = createLineEditor({
      onEcho: (text) => term.write(text),
      onLine: (line) => session?.send(`${line}\r\n`),
    })

    term.onData((data) => editor.handleInput(data))
    setLive(true)

    openTerminalSession({
      url: sessionUrl(),
      onData: (text) => term.write(text),
      onClose: () => {
        if (!disposed) {
          setLive(false)
        }
      },
    })
      .then((opened) => {
        if (disposed) {
          opened.close()
          return
        }
        session = opened
      })
      .catch((error: Error) => {
        term.write(`\r\n[connection lost: ${error.message}]\r\n`)
        setLive(false)
      })

    return () => {
      disposed = true
      window.removeEventListener('resize', resize)
      session?.close()
      term.dispose()
    }
  }, [generation])

  return (
    <div className="terminal-shell">
      <div className="terminal-surface" ref={host} />
      {!live && (
        <button type="button" className="reconnect" onClick={() => setGeneration((n) => n + 1)}>
          Reconnect
        </button>
      )}
    </div>
  )
}
```

- [ ] **Step 2: Write the app shell**

Create `frontend/src/App.tsx`:

```tsx
/*----------------------
 | App.tsx
 | Description: Page shell hosting a single terminal.
 | Author: suinevere
 | Dependencies: Terminal
 | Globals: N/A
 ----------------------*/
import Terminal from './components/Terminal'

/*----------------------
 | App
 | Description: Renders the page around the terminal.
 | Author: suinevere
 | Dependencies: Terminal
 | Globals: N/A
 | Params: N/A
 | Returns: React element for the whole page
 ----------------------*/
export default function App() {
  return <Terminal />
}
```

Create `frontend/src/main.tsx`:

```tsx
/*----------------------
 | main.tsx
 | Description: Mounts the React application.
 | Author: suinevere
 | Dependencies: react-dom, App
 | Globals: N/A
 ----------------------*/
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

Create `frontend/src/index.css`:

```css
/*----------------------
 | index.css
 | Description: Full-viewport dark layout for the terminal surface.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 ----------------------*/
html,
body,
#root {
  height: 100%;
  margin: 0;
  background: #0b0b0b;
}

.terminal-shell {
  position: relative;
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
}

.terminal-surface {
  height: 100%;
}

.reconnect {
  position: absolute;
  top: 16px;
  right: 16px;
  padding: 6px 14px;
  font: inherit;
  color: #0b0b0b;
  background: #d8d8d8;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}
```

- [ ] **Step 3: Type-check and build**

Run: `npm run build --prefix frontend`
Expected: `tsc --noEmit` reports no errors and Vite writes `frontend/dist`.

- [ ] **Step 4: Verify in a browser**

With `./gradlew bootRun` still running, run: `npm run dev --prefix frontend`

Open `http://localhost:5173` and confirm all five:
1. The `Hello sailor!` banner appears.
2. Typed characters appear as you type them — this is the local echo, and its absence means Task 6 is not wired.
3. Backspace erases your own text but cannot eat the `>` prompt.
4. Pressing Enter on an empty line advances to `What's your name?`.
5. Stopping `bootRun` makes the Reconnect button appear.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "Render the browser terminal with XTerm.js wired to the RSocket session"
```

---

## Task 9: Single-artifact packaging

`bootJar` should produce one runnable jar that serves both the frontend and the RSocket endpoint.

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: the `npm run build` script from Task 6.
- Produces: `./gradlew bootJar` yielding a jar that serves the bundle at `/` and RSocket at `/rsocket`.

- [ ] **Step 1: Add the frontend build tasks**

Append to `build.gradle.kts`:

```kotlin
val frontendDir = layout.projectDirectory.dir("frontend")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
	description = "Installs frontend dependencies from the lockfile."
	workingDir = frontendDir.asFile
	commandLine(npmCommand, "ci")
	inputs.file(frontendDir.file("package-lock.json"))
	outputs.dir(frontendDir.dir("node_modules"))
}

val npmBuild by tasks.registering(Exec::class) {
	description = "Builds the production frontend bundle."
	dependsOn(npmInstall)
	workingDir = frontendDir.asFile
	commandLine(npmCommand, "run", "build")
	inputs.dir(frontendDir.dir("src"))
	inputs.file(frontendDir.file("package.json"))
	inputs.file(frontendDir.file("vite.config.ts"))
	inputs.file(frontendDir.file("index.html"))
	outputs.dir(frontendDir.dir("dist"))
}

tasks.named<ProcessResources>("processResources") {
	dependsOn(npmBuild)
	from(frontendDir.dir("dist")) {
		into("static")
	}
}
```

`ProcessResources` needs this import at the top of `build.gradle.kts`:

```kotlin
import org.gradle.language.jvm.tasks.ProcessResources
```

- [ ] **Step 2: Build the jar**

Run: `./gradlew bootJar`
Expected: SUCCESS, and `build/libs/suinevere-site-terminal-0.0.1-SNAPSHOT.jar` exists.

- [ ] **Step 3: Confirm the bundle is inside the jar**

Run: `./gradlew bootJar && jar tf build/libs/suinevere-site-terminal-0.0.1-SNAPSHOT.jar | grep 'static/index.html'`
Expected: one matching line, `BOOT-INF/classes/static/index.html`.

- [ ] **Step 4: Run the jar and verify both surfaces**

Stop any running `bootRun` first, then run the jar:

```bash
java -jar build/libs/suinevere-site-terminal-0.0.1-SNAPSHOT.jar
```

In another terminal, run: `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/`
Expected: `200`.

Then run: `RSOCKET_URL=ws://localhost:8080/rsocket npm run verify:rsocket --prefix frontend`
Expected: `OK: round trip verified`.

Finally open `http://localhost:8080` in a browser and confirm the terminal works with no Vite dev server running.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test && npm test --prefix frontend`
Expected: everything PASSES.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "Bundle the frontend into the boot jar so one artifact serves the terminal and RSocket"
```

---

## Definition of done

- `./gradlew test` and `npm test --prefix frontend` both pass.
- `java -jar build/libs/suinevere-site-terminal-0.0.1-SNAPSHOT.jar` serves a working terminal at `http://localhost:8080` with no dev server.
- Typing echoes locally, Enter reaches the upstream service, and its replies render.
- Backspace cannot erase the upstream prompt.
- Stopping the upstream or the backend produces bracketed text and a Reconnect button, never a silent dead prompt.
- Upstream host and port appear only in `application.yaml`, never in a client payload.
