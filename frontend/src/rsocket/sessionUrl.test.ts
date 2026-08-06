/*----------------------
 | sessionUrl.test.ts
 | Description: Covers websocket scheme selection and base-path joining for the RSocket endpoint.
 | Author: suinevere
 | Dependencies: vitest, sessionUrl
 | Globals: N/A
 ----------------------*/
import { describe, expect, it } from 'vitest'
import { sessionUrl } from './sessionUrl'

describe('sessionUrl', () => {
  it('joins a base path onto a secure endpoint', () => {
    expect(sessionUrl({ protocol: 'https:', host: 'suin.uk' }, '/zork/'))
      .toBe('wss://suin.uk/zork/rsocket')
  })

  it('uses ws when the page is not secure', () => {
    expect(sessionUrl({ protocol: 'http:', host: 'localhost:5173' }, '/'))
      .toBe('ws://localhost:5173/rsocket')
  })

  it('tolerates a base path without a trailing slash', () => {
    expect(sessionUrl({ protocol: 'https:', host: 'suin.uk' }, '/zork'))
      .toBe('wss://suin.uk/zork/rsocket')
  })
})
