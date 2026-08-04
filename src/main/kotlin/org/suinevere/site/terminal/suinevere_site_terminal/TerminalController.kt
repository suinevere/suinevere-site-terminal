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
