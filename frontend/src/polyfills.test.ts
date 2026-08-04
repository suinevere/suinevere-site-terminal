/*----------------------
 | polyfills.test.ts
 | Description: Guards that the Buffer polyfill is imported before anything that can pull in the RSocket codecs.
 | Author: suinevere
 | Dependencies: vitest
 | Globals: N/A
 ----------------------*/
import { describe, expect, it } from 'vitest'
import mainSource from './main.tsx?raw'
import polyfillSource from './polyfills.ts?raw'

describe('Buffer polyfill wiring', () => {
  it('is the first import in main.tsx', () => {
    const firstImport = mainSource
      .split('\n')
      .map((line: string) => line.trim())
      .find((line: string) => line.startsWith('import '))

    expect(firstImport).toBe("import './polyfills'")
  })

  it('assigns Buffer onto the global scope', () => {
    expect(polyfillSource).toContain("import { Buffer } from 'buffer'")
    expect(polyfillSource).toMatch(/scope\.Buffer\s*=\s*Buffer/)
  })
})
