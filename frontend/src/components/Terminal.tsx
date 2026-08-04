/*----------------------
 | Terminal.tsx
 | Description: Mounts XTerm.js, wires local line editing to the RSocket session, and surfaces connection state.
 | Author: suinevere
 | Dependencies: @xterm/xterm, @xterm/addon-fit, react, lineEditor, terminalSession
 | Globals: N/A
 ----------------------*/
import { useEffect, useRef, useState } from 'react'
import { Terminal as XTerm } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { createLineEditor } from '../terminal/lineEditor'
import { openTerminalSession, type TerminalSession } from '../rsocket/terminalSession'

/*----------------------
 | sessionUrl
 | Description: Resolves the RSocket endpoint from the page origin so dev and production both work.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: WebSocket URL for the RSocket mapping path
 ----------------------*/
function sessionUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/rsocket`
}

/*----------------------
 | Terminal
 | Description: Renders the browser terminal for one upstream session.
 | Author: suinevere
 | Dependencies: XTerm, FitAddon, createLineEditor, openTerminalSession
 | Globals: N/A
 | Params: N/A
 | Returns: React element containing the terminal surface and a Reconnect control
 ----------------------*/
export default function Terminal() {
  const host = useRef<HTMLDivElement>(null)
  const [generation, setGeneration] = useState(0)
  const [live, setLive] = useState(true)

  useEffect(() => {
    if (!host.current) {
      return
    }

    const term = new XTerm({
      convertEol: false,
      cursorBlink: true,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
      fontSize: 14,
      theme: { background: '#0b0b0b', foreground: '#d8d8d8' },
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(host.current)
    fit.fit()

    const resize = () => fit.fit()
    window.addEventListener('resize', resize)

    let session: TerminalSession | null = null
    let disposed = false

    const editor = createLineEditor({
      onEcho: (text) => term.write(text),
      onLine: (line) => session?.send(`${line}\r\n`),
    })

    term.onData((data) => editor.handleInput(data))
    setLive(true)

    openTerminalSession({
      url: sessionUrl(),
      onData: (text) => term.write(text),
      onClose: () => {
        session = null
        if (!disposed) {
          setLive(false)
        }
      },
    })
      .then((opened) => {
        if (disposed) {
          opened.close()
          return
        }
        session = opened
      })
      .catch((error: Error) => {
        if (disposed) {
          return
        }
        term.write(`\r\n[connection lost: ${error.message}]\r\n`)
        setLive(false)
      })

    return () => {
      disposed = true
      window.removeEventListener('resize', resize)
      session?.close()
      term.dispose()
    }
  }, [generation])

  return (
    <div className="terminal-shell">
      <div className="terminal-surface" ref={host} />
      {!live && (
        <button type="button" className="reconnect" onClick={() => setGeneration((n) => n + 1)}>
          Reconnect
        </button>
      )}
    </div>
  )
}
