/*----------------------
 | verify-rsocket.mjs
 | Description: Proves the RSocket client library round-trips against a running backend.
 | Author: suinevere
 | Dependencies: @rsocket/core, @rsocket/websocket-client, @rsocket/composite-metadata, ws
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
 | URL
 | Description: The bridge endpoint to connect to, overridable via RSOCKET_URL for testing against non-default hosts.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const URL = process.env.RSOCKET_URL ?? 'ws://localhost:8080/rsocket'

const connector = new RSocketConnector({
  setup: {
    keepAlive: 20000,
    lifetime: 90000,
    dataMimeType: 'text/plain',
    metadataMimeType: WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.toString(),
  },
  transport: new WebsocketClientTransport({
    url: URL,
    wsCreator: (url) => new WebSocket(url),
  }),
})

const rsocket = await connector.connect()
let received = ''

const requester = rsocket.requestChannel(
  {
    data: Buffer.from('\u0000INIT', 'latin1'),
    metadata: encodeCompositeMetadata([
      [WellKnownMimeType.MESSAGE_RSOCKET_ROUTING, encodeRoute('terminal.session')],
    ]),
  },
  2147483647,
  false,
  {
    onNext: (payload) => {
      if (payload.data) {
        received += Buffer.from(payload.data).toString('latin1')
        process.stdout.write(Buffer.from(payload.data).toString('latin1'))
      }
    },
    onError: (error) => {
      console.error('\nERROR:', error.message)
      process.exit(1)
    },
    onComplete: () => {},
    onExtension: () => {},
    request: () => {},
    cancel: () => {},
  },
)

setTimeout(() => {
  if (!received.includes('Hello sailor')) {
    console.error('\nFAIL: no upstream banner received')
    process.exit(1)
  }
  console.log('\n\nOK: banner received, sending a bare Enter')
  requester.onNext({ data: Buffer.from('\r\n', 'latin1') }, false)
  setTimeout(() => {
    if (!received.includes("What's your name")) {
      console.error('\nFAIL: no response to Enter')
      process.exit(1)
    }
    console.log('\n\nOK: round trip verified')
    process.exit(0)
  }, 4000)
}, 4000)
