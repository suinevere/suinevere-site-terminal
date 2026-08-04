/*----------------------
 | UpstreamTcpClient.kt
 | Description: Opens one TCP connection to the upstream service and bridges it to a pair of Flux streams.
 | Author: suinevere
 | Dependencies: TerminalProperties, reactor-netty
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.channel.AbortedException
import reactor.netty.tcp.TcpClient
import java.nio.charset.StandardCharsets

/*----------------------
 | log
 | Description: Logger for upstream write failures that are absorbed rather than surfaced.
 | Author: suinevere
 | Dependencies: slf4j
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
private val log = LoggerFactory.getLogger(UpstreamTcpClient::class.java)

@Component
class UpstreamTcpClient(private val properties: TerminalProperties) {

	/*----------------------
	 | open
	 | Description: Connects upstream and returns decoded output; the merged write pump emits nothing and
	 |              exists only so writes run concurrently with reads on the one connection.
	 | Author: suinevere
	 | Dependencies: TerminalProperties, reactor-netty TcpClient
	 | Globals: log
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
					.doOnError { log.debug("upstream write failed", it) }
					.onErrorComplete(AbortedException::class.java)

				connection.inbound()
					.receive()
					.asString(StandardCharsets.ISO_8859_1)
					.mergeWith(pump)
					.onBackpressureBuffer(properties.outputBuffer)
					.doFinally { connection.dispose() }
			}
}
