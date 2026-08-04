/*----------------------
 | TerminalController.kt
 | Description: Maps the RSocket terminal channel onto one upstream TCP connection, with caps and readable failures.
 | Author: suinevere
 | Dependencies: UpstreamTcpClient, TerminalProperties, TerminalMessages
 | Globals: INIT_SENTINEL, BUSY_MESSAGE, DISCONNECTED_MESSAGE
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

/*----------------------
 | log
 | Description: Logger for session failures that are converted to terminal text and would otherwise leave no trace.
 | Author: suinevere
 | Dependencies: slf4j
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
private val log = LoggerFactory.getLogger(TerminalController::class.java)

@Controller
class TerminalController(
	private val upstream: UpstreamTcpClient,
	private val properties: TerminalProperties,
) {

	/*----------------------
	 | active
	 | Description: Live bridged sessions, used to enforce the concurrent session cap.
	 | Author: suinevere
	 | Dependencies: N/A
	 | Globals: N/A
	 | Params: N/A
	 | Returns: N/A
	 ----------------------*/
	private val active = AtomicInteger(0)

	/*----------------------
	 | session
	 | Description: Bridges one browser terminal to one upstream TCP connection for the channel's lifetime.
	 | Author: suinevere
	 | Dependencies: UpstreamTcpClient, TerminalProperties
	 | Globals: INIT_SENTINEL, BUSY_MESSAGE, DISCONNECTED_MESSAGE, log
	 | Params: inbound -- payloads from the browser, the first being the handshake sentinel
	 | Returns: Flux of upstream output, or a bracketed explanation, to write straight into the terminal
	 ----------------------*/
	@MessageMapping("terminal.session")
	fun session(inbound: Flux<String>): Flux<String> = Flux.defer {
		if (active.incrementAndGet() > properties.maxSessions) {
			active.decrementAndGet()
			return@defer Flux.just(BUSY_MESSAGE)
		}
		Flux.defer { upstream.open(inbound.filter { it != INIT_SENTINEL }) }
			.concatWith(Flux.just(DISCONNECTED_MESSAGE))
			.doOnError { log.warn("terminal session failed for {}:{}", properties.host, properties.port, it) }
			.onErrorResume { Flux.just(unreachableMessage(properties.host, properties.port)) }
			.doFinally { active.decrementAndGet() }
	}
}
