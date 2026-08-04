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
