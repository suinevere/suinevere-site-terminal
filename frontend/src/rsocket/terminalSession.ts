/*----------------------
 | terminalSession.ts
 | Description: Opens an RSocket channel over WebSocket and exposes it as a line-oriented terminal session.
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

const ROUTE = 'terminal.session'
const INIT_SENTINEL = '\u0000INIT'
const REQUEST_N = 2147483647
const KEEPALIVE_MS = 20000
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

  const requester = rsocket.requestChannel(
    {
      data: Buffer.from(INIT_SENTINEL, 'latin1'),
      metadata: routeMetadata(ROUTE),
    },
    REQUEST_N,
    false,
    {
      onNext: (payload) => {
        if (payload.data) {
          options.onData(Buffer.from(payload.data).toString('latin1'))
        }
      },
      onError: (error) => options.onClose(error.message),
      onComplete: () => options.onClose('closed'),
      onExtension: () => {},
      request: () => {},
      cancel: () => {},
    },
  )

  return {
    send: (line: string): void => {
      requester.onNext({ data: Buffer.from(line, 'latin1') }, false)
    },
    close: (): void => {
      requester.cancel()
      rsocket.close()
    },
  }
}
