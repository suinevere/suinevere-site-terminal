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
