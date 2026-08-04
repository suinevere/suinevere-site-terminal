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
