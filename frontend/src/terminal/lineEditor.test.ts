/*----------------------
 | lineEditor.test.ts
 | Description: Unit tests for local echo, backspace bounds, Enter semantics, and control-key handling.
 | Author: suinevere
 | Dependencies: vitest, lineEditor
 | Globals: N/A
 ----------------------*/
import { describe, expect, it } from 'vitest'
import { createLineEditor } from './lineEditor'

function harness() {
  const echoed: string[] = []
  const lines: string[] = []
  const editor = createLineEditor({
    onEcho: (text) => echoed.push(text),
    onLine: (line) => lines.push(line),
  })
  return { editor, echoed, lines }
}

describe('createLineEditor', () => {
  it('echoes printable characters as they are typed', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('h')
    editor.handleInput('i')
    expect(echoed).toEqual(['h', 'i'])
    expect(lines).toEqual([])
  })

  it('flushes the buffered line on Enter and echoes a newline', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('h')
    editor.handleInput('i')
    editor.handleInput('\r')
    expect(lines).toEqual(['hi'])
    expect(echoed[echoed.length - 1]).toBe('\r\n')
  })

  it('flushes an empty line on a bare Enter', () => {
    const { editor, lines } = harness()
    editor.handleInput('\r')
    expect(lines).toEqual([''])
  })

  it('erases one character on Backspace', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('h')
    editor.handleInput('i')
    editor.handleInput('\x7f')
    editor.handleInput('\r')
    expect(echoed).toContain('\b \b')
    expect(lines).toEqual(['h'])
  })

  it('ignores Backspace at column zero so the prompt survives', () => {
    const { editor, echoed } = harness()
    editor.handleInput('\x7f')
    expect(echoed).toEqual([])
  })

  it('clears the buffer on Ctrl+C', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('h')
    editor.handleInput('\x03')
    editor.handleInput('\r')
    expect(echoed).toContain('^C\r\n')
    expect(lines).toEqual([''])
  })

  it('drops escape sequences instead of echoing their letters', () => {
    const { editor, echoed, lines } = harness()
    editor.handleInput('\x1b[A')
    editor.handleInput('\r')
    expect(echoed).toEqual(['\r\n'])
    expect(lines).toEqual([''])
  })

  it('handles a pasted multi-character chunk', () => {
    const { editor, lines } = harness()
    editor.handleInput('suinevere')
    editor.handleInput('\r')
    expect(lines).toEqual(['suinevere'])
  })
})
