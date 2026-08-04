/*----------------------
 | TerminalMessages.kt
 | Description: Handshake sentinel and the bracketed strings shown to the user in the terminal.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: INIT_SENTINEL, BUSY_MESSAGE, DISCONNECTED_MESSAGE
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal

/*----------------------
 | INIT_SENTINEL
 | Description: First channel payload, sent only to carry route metadata and never forwarded upstream.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const val INIT_SENTINEL: String = "\u0000INIT"

/*----------------------
 | BUSY_MESSAGE
 | Description: Shown when the concurrent session cap is already reached.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const val BUSY_MESSAGE: String = "\r\n[server busy, try again shortly]\r\n"

/*----------------------
 | DISCONNECTED_MESSAGE
 | Description: Shown when the upstream service closes the connection.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const val DISCONNECTED_MESSAGE: String = "\r\n[disconnected]\r\n"

/*----------------------
 | unreachableMessage
 | Description: Builds the message shown when the upstream socket cannot be opened.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: host -- configured upstream host; port -- configured upstream port
 | Returns: Bracketed, CRLF-wrapped text safe to write straight to a terminal
 ----------------------*/
fun unreachableMessage(host: String, port: Int): String = "\r\n[unable to reach $host:$port]\r\n"
