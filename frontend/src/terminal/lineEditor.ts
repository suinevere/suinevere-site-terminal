/*----------------------
 | lineEditor.ts
 | Description: Local echo and line assembly for a terminal whose upstream never echoes.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 ----------------------*/

export type LineEditorHandlers = {
  onEcho: (text: string) => void
  onLine: (line: string) => void
}

export type LineEditor = {
  handleInput: (data: string) => void
}

const ENTER = '\r'
const BACKSPACE = '\x7f'
const CTRL_C = '\x03'
const ESCAPE = '\x1b'

/*----------------------
 | createLineEditor
 | Description: Builds a stateful editor that echoes keystrokes locally and emits completed lines.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: handlers -- onEcho writes to the terminal, onLine receives each completed line without its newline
 | Returns: LineEditor with handleInput for raw terminal data and reset to drop the buffer
 ----------------------*/
export function createLineEditor(handlers: LineEditorHandlers): LineEditor {
  let buffer = ''

  const handleChar = (char: string): void => {
    if (char === ENTER) {
      handlers.onEcho('\r\n')
      handlers.onLine(buffer)
      buffer = ''
      return
    }
    if (char === BACKSPACE) {
      if (buffer.length > 0) {
        buffer = buffer.slice(0, -1)
        handlers.onEcho('\b \b')
      }
      return
    }
    if (char === CTRL_C) {
      buffer = ''
      handlers.onEcho('^C\r\n')
      return
    }
    if (char >= ' ') {
      buffer += char
      handlers.onEcho(char)
    }
  }

  return {
    handleInput: (data: string): void => {
      if (data.startsWith(ESCAPE)) {
        return
      }
      for (const char of data) {
        handleChar(char)
      }
    },
  }
}
