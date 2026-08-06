/*----------------------
 | sessionUrl.ts
 | Description: Builds the RSocket websocket URL from the page location and the bundle's base path.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 ----------------------*/

export type UrlLocation = {
  protocol: string
  host: string
}

/*----------------------
 | sessionUrl
 | Description: Resolves the RSocket endpoint so the same bundle works at the site root and under /zork.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: location -- the page protocol and host; baseUrl -- the bundle base path, with or without a trailing slash
 | Returns: an absolute ws:// or wss:// URL for the RSocket mapping path
 ----------------------*/
export function sessionUrl(location: UrlLocation, baseUrl: string): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return `${scheme}://${location.host}${base}/rsocket`
}
