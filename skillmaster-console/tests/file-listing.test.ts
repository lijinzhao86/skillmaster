import { describe, expect, it } from 'vitest'
import type { AuthoredFile } from '../src/api/types'
import { currentDirOf, isDirectoryOf } from '../src/pages/skill/fileListing'

/**
 * The one level a file address names.
 *
 * A file address carries the directory it lives in, and a link written in a document may name one
 * this version no longer has. The design's rule for a directory in the address is the root — not
 * an empty level — and it has to hold for the directory a file address implies as much as for
 * `?path=`.
 */

const file = (relpath: string): AuthoredFile => ({
  relpath,
  sha256: 'sha256:x',
  size: 1,
  is_binary: false,
})

describe('the directory a file address names', () => {
  it('is the folder the file lives in', () => {
    expect(
      currentDirOf('references/split.md', null, [file('SKILL.md'), file('references/split.md')]),
    ).toBe('references')
  })

  it('falls back to the root when that folder is not in this version', () => {
    expect(currentDirOf('gone/split.md', null, [file('SKILL.md')])).toBe('')
  })
})

describe('the directory ?path= names', () => {
  it('is taken when the manifest has it, and the root when it does not', () => {
    const files = [file('SKILL.md'), file('references/split.md')]
    expect(currentDirOf(undefined, 'references', files)).toBe('references')
    expect(currentDirOf(undefined, 'nowhere', files)).toBe('')
  })
})

describe('telling a folder from a file', () => {
  it('is what the manifest answers; the root is not its business', () => {
    const files = [file('SKILL.md'), file('references/deep/a.md')]
    expect(isDirectoryOf(files, 'references')).toBe(true)
    expect(isDirectoryOf(files, 'references/deep')).toBe(true)
    expect(isDirectoryOf(files, 'references/deep/a.md')).toBe(false)
    expect(isDirectoryOf(files, '')).toBe(false)
  })
})
