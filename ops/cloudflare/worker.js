/*----------------------
 | worker.js
 | Description: Passes suin.uk/zork* through to the terminal origin unchanged, including websocket upgrades.
 | Author: suinevere
 | Dependencies: Cloudflare Workers runtime
 | Globals: ORIGIN_HOST, PUBLIC_HOST
 ----------------------*/

/*----------------------
 | ORIGIN_HOST
 | Description: The terminal vhost on the Oracle box, resolved DNS-only so this fetch reaches it directly.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const ORIGIN_HOST = 'terminal.suin.uk'

/*----------------------
 | PUBLIC_HOST
 | Description: The hostname the visitor sees, forwarded so the origin can build correct absolute URLs.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const PUBLIC_HOST = 'suin.uk'

export default {
  /*----------------------
   | fetch
   | Description: Rewrites the hostname and relays the response, preserving a websocket handshake if one occurred.
   | Author: suinevere
   | Dependencies: ORIGIN_HOST, PUBLIC_HOST
   | Globals: ORIGIN_HOST, PUBLIC_HOST
   | Params: request -- the inbound request on the suin.uk/zork* route
   | Returns: Promise of the origin's response, websocket included
   ----------------------*/
  async fetch(request) {
    const url = new URL(request.url)
    url.hostname = ORIGIN_HOST

    const upstream = new Request(url, request)
    upstream.headers.set('X-Forwarded-Host', PUBLIC_HOST)

    const response = await fetch(upstream)

    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers,
      webSocket: response.webSocket,
    })
  },
}
