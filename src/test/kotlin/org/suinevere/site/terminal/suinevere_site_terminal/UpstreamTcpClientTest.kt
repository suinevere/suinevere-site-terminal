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
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class UpstreamTcpClientTest {

	private fun clientFor(port: Int) = UpstreamTcpClient(
		TerminalProperties(host = "127.0.0.1", port = port, connectTimeout = Duration.ofSeconds(5)),
	)

	private fun Flux<String>.accumulatedUntil(expected: String): Mono<String> =
		scan("") { acc, chunk -> acc + chunk }
			.filter { it == expected }
			.next()

	@Test
	fun `receives the upstream banner`() {
		val server = EchoTcpServer.startEchoing("Hello sailor!\r\n>")
		try {
			StepVerifier.create(clientFor(server.port()).open(Flux.never()).accumulatedUntil("Hello sailor!\r\n>"))
				.expectNext("Hello sailor!\r\n>")
				.expectComplete()
				.verify(Duration.ofSeconds(10))
		} finally {
			server.disposeNow()
		}
	}

	@Test
	fun `sends a typed line upstream and returns the reply`() {
		val server = EchoTcpServer.startEchoing("> ")
		try {
			StepVerifier.create(clientFor(server.port()).open(Flux.just("suinevere\r\n")).accumulatedUntil("> suinevere\r\n"))
				.expectNext("> suinevere\r\n")
				.expectComplete()
				.verify(Duration.ofSeconds(10))
		} finally {
			server.disposeNow()
		}
	}

	@Test
	fun `preserves high bytes instead of corrupting them`() {
		val server = EchoTcpServer.startEchoing("")
		try {
			StepVerifier.create(clientFor(server.port()).open(Flux.just("éÿ\r\n")).accumulatedUntil("éÿ\r\n"))
				.expectNext("éÿ\r\n")
				.expectComplete()
				.verify(Duration.ofSeconds(10))
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
