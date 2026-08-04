/*----------------------
 | polyfills.ts
 | Description: Installs Buffer as a browser global, which the RSocket packages use without ever importing it.
 | Author: suinevere
 | Dependencies: buffer
 | Globals: Buffer
 ----------------------*/
import { Buffer } from 'buffer'

/*----------------------
 | installBufferGlobal
 | Description: Makes Buffer reachable as a bare global so the RSocket codecs resolve it under a browser.
 | Author: suinevere
 | Dependencies: buffer
 | Globals: Buffer
 | Params: N/A
 | Returns: N/A
 ----------------------*/
function installBufferGlobal(): void {
  const scope = globalThis as unknown as { Buffer?: typeof Buffer }
  if (!scope.Buffer) {
    scope.Buffer = Buffer
  }
}

installBufferGlobal()
