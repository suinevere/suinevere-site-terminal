/*----------------------
 | AuthCheckController.kt
 | Description: The endpoint nginx subrequests to decide whether a websocket upgrade carries a signed-in session.
 | Author: suinevere
 | Dependencies: spring-boot-starter-webflux
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal.security

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthCheckController {

	/*----------------------
	 | authCheck
	 | Description: Returns an empty success once the filter chain has proved the caller is signed in.
	 | Author: suinevere
	 | Dependencies: N/A
	 | Globals: N/A
	 | Params: N/A
	 | Returns: N/A -- the 204 status is the whole answer
	 ----------------------*/
	@GetMapping("/_authcheck")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun authCheck() {
	}
}
