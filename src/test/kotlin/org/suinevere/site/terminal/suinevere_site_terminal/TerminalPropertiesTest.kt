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
