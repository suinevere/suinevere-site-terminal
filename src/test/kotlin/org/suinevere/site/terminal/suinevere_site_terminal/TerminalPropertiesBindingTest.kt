/*----------------------
 | TerminalPropertiesBindingTest.kt
 | Description: Proves the binder reads configuration rather than silently falling back to defaults.
 | Author: suinevere
 | Dependencies: spring-boot-starter-test
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import kotlin.test.assertEquals

@SpringBootTest
@TestPropertySource(
	properties = [
		"terminal.upstream.host=example.invalid",
		"terminal.upstream.port=2323",
		"terminal.upstream.connect-timeout=3s",
		"terminal.upstream.max-sessions=7",
		"terminal.upstream.output-buffer=64",
	],
)
class TerminalPropertiesBindingTest {

	@Autowired
	private lateinit var properties: TerminalProperties

	@Test
	fun `overridden values reach the bound class`() {
		assertEquals("example.invalid", properties.host)
		assertEquals(2323, properties.port)
		assertEquals(Duration.ofSeconds(3), properties.connectTimeout)
		assertEquals(7, properties.maxSessions)
		assertEquals(64, properties.outputBuffer)
	}
}
