/*----------------------
 | terminalSession.ts
 | Description: Opens an RSocket channel over WebSocket and exposes it as a line-oriented terminal session;
 |              payloads are UTF-8 because Spring's text/plain codecs are, and ISO-8859-1 stays on the TCP leg.
 | Author: suinevere
 | Dependencies: @rsocket/core, @rsocket/websocket-client, @rsocket/composite-metadata, buffer
 | Globals: N/A
 ----------------------*/
import { Buffer } from 'buffer'
import { RSocketConnector } from '@rsocket/core'
import { WebsocketClientTransport } from '@rsocket/websocket-client'
import {
  encodeCompositeMetadata,
  encodeRoute,
  WellKnownMimeType,
} from '@rsocket/composite-metadata'

/*----------------------
 | ROUTE
 | Description: The Spring message-mapping this channel targets.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const ROUTE = 'terminal.session'
/*----------------------
 | INIT_SENTINEL
 | Description: The handshake payload the backend filters as the channel's first inbound element; the leading NUL is unreachable from a keyboard, so it can never collide with real input.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const INIT_SENTINEL = '\u0000INIT'
/*----------------------
 | REQUEST_N
 | Description: An effectively-unbounded request(n) so the server is never throttled by the client's declared demand.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const REQUEST_N = 2147483647
/*----------------------
 | KEEPALIVE_MS
 | Description: Interval between RSocket keepalive frames sent to the server.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const KEEPALIVE_MS = 20000
/*----------------------
 | LIFETIME_MS
 | Description: How long the server may go without a keepalive before it treats this connection as dead.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const LIFETIME_MS = 90000

export type TerminalSessionOptions = {
  url: string
  onData: (text: string) => void
  onClose: (reason: string) => void
}

export type TerminalSession = {
  send: (line: string) => void
  close: () => void
}

/*----------------------
 | routeMetadata
 | Description: Encodes the RSocket route as composite metadata, without which Spring cannot dispatch.
 | Author: suinevere
 | Dependencies: @rsocket/composite-metadata
 | Globals: N/A
 | Params: route -- the message mapping to target
 | Returns: Buffer holding one composite metadata entry
 ----------------------*/
function routeMetadata(route: string): Buffer {
  return encodeCompositeMetadata([
    [WellKnownMimeType.MESSAGE_RSOCKET_ROUTING, encodeRoute(route)],
  ])
}

/*----------------------
 | openTerminalSession
 | Description: Connects to the bridge and opens the bidirectional channel that carries one terminal.
 | Author: suinevere
 | Dependencies: @rsocket/core, @rsocket/websocket-client
 | Globals: N/A
 | Params: options -- endpoint URL plus callbacks for upstream output and channel termination
 | Returns: Promise of a TerminalSession for sending lines and closing the channel
 ----------------------*/
export async function openTerminalSession(
  options: TerminalSessionOptions,
): Promise<TerminalSession> {
  const connector = new RSocketConnector({
    setup: {
      keepAlive: KEEPALIVE_MS,
      lifetime: LIFETIME_MS,
      dataMimeType: 'text/plain',
      metadataMimeType: WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.toString(),
    },
    transport: new WebsocketClientTransport({ url: options.url }),
  })

  const rsocket = await connector.connect()
  let closed = false

  const requester = rsocket.requestChannel(
    {
      data: Buffer.from(INIT_SENTINEL, 'utf8'),
      metadata: routeMetadata(ROUTE),
    },
    REQUEST_N,
    false,
    {
      onNext: (payload) => {
        if (payload.data) {
          options.onData(Buffer.from(payload.data).toString('utf8'))
        }
      },
      onError: (error) => {
        if (!closed) {
          closed = true
          rsocket.close()
        }
        options.onClose(error.message)
      },
      onComplete: () => {
        if (!closed) {
          closed = true
          rsocket.close()
        }
        options.onClose('closed')
      },
      onExtension: () => {},
      request: () => {},
      cancel: () => {},
    },
  )

  return {
    send: (line: string): void => {
      requester.onNext({ data: Buffer.from(line, 'utf8') }, false)
    },
    close: (): void => {
      if (closed) {
        return
      }
      closed = true
      requester.cancel()
      rsocket.close()
    },
  }
}
