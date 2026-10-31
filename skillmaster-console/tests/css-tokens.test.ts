import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * **A custom property that is never defined fails silently.**
 *
 * `background: var(--wash)` with no `--wash` anywhere is not an error: CSS treats the declaration as
 * invalid at computed-value time and falls back to the *initial* value — transparent. No warning, no
 * console message, and the rule looks right when you read it. That is how the list row lost its
 * hover wash (2026-11-01): the row's name still turned accent, so it read as a colour bug rather
 * than as a missing token, and the selected row and the floating pane's shadow had gone the same way.
 *
 * The only exception is a property something sets at runtime: `var(--member-name, 168px)` and
 * `var(--editor-banner, 0px)` are written by the page, and both carry a fallback, so they are not
 * missing — they are defaults.
 */
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

describe('styles.css', () => {
  it('every custom property it reads is one it defines (or a documented runtime default)', () => {
    const used = [...css.matchAll(/var\(\s*(--[a-z0-9-]+)\s*(,)?/gi)]
      .filter((match) => match[2] === undefined)
      .map((match) => match[1])
    const defined = new Set([...css.matchAll(/(--[a-z0-9-]+)\s*:/g)].map((match) => match[1]))
    const missing = [...new Set(used)].filter((name) => !defined.has(name)).sort()

    expect(missing).toEqual([])
  })
})
