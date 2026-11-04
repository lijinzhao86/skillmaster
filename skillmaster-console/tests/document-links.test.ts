import { describe, expect, it } from 'vitest'
import { documentHrefOf, documentTargetOf } from '../src/pages/skill/documentLinks'

/**
 * What a link written in a document read on the file tab means.
 *
 * The browser cannot answer this and gets it wrong in the visible way: it resolves the authored
 * string against the address bar, whose path is the skill's page rather than the folder the file
 * lives in. These cases pin the resolution the page does instead — including the two that have no
 * address to give (a path above the skill's root) and the one that must not be swallowed (a target
 * the version does not have).
 */

const fileHref = (relpath: string): string => `/files/${relpath}`
const dirHref = (dir: string): string => `/dir/${dir}`

describe('a link written beside a file', () => {
  it('names a path in the same folder', () => {
    expect(documentHrefOf('SKILL.md', 'references/split.md', fileHref, dirHref)).toBe(
      '/files/references/split.md',
    )
  })

  it('resolves ../ against the document, not against the page', () => {
    expect(documentHrefOf('references/split.md', '../SKILL.md', fileHref, dirHref)).toBe(
      '/files/SKILL.md',
    )
    expect(documentHrefOf('references/deep/a.md', '../../concepts/b.md', fileHref, dirHref)).toBe(
      '/files/concepts/b.md',
    )
  })

  it('leaves a path that climbs above the skill alone — there is nothing to name', () => {
    expect(documentHrefOf('SKILL.md', '../outside.md', fileHref, dirHref)).toBeNull()
    expect(documentHrefOf('references/a.md', '../../outside.md', fileHref, dirHref)).toBeNull()
  })

  it('sends a link whose target is the skill root to the root listing', () => {
    // The root is a real directory, and a link that names it must not keep the authored `../`:
    // the browser resolves that against the address bar, where the path is one the console does
    // not have — which is the home-page fallback this whole module exists to prevent.
    expect(documentHrefOf('references/guide.md', '../', fileHref, dirHref)).toBe('/dir/')
    expect(documentHrefOf('references/deep/a.md', '../../', fileHref, dirHref)).toBe('/dir/')
    expect(documentHrefOf('SKILL.md', './', fileHref, dirHref)).toBe('/dir/')
  })

  it('reads . and .. as the folders they are, even without a trailing slash', () => {
    expect(documentHrefOf('references/deep/a.md', '..', fileHref, dirHref)).toBe('/dir/references')
  })

  it('keeps the fragment or query the author wrote', () => {
    expect(documentHrefOf('SKILL.md', 'references/split.md#usage', fileHref, dirHref)).toBe(
      '/files/references/split.md#usage',
    )
    expect(documentHrefOf('SKILL.md', 'references/split.md?plain=1#top', fileHref, dirHref)).toBe(
      '/files/references/split.md?plain=1#top',
    )
  })

  it('leaves addresses that are not paths inside the skill exactly as written', () => {
    for (const href of [
      'https://example.com/doc',
      'http://example.com/doc',
      '//cdn.example.com/a.md',
      'mailto:someone@example.com',
      '#usage',
      '',
    ]) {
      expect(documentHrefOf('SKILL.md', href, fileHref, dirHref)).toBeNull()
    }
  })

  it('sends a trailing slash to the directory it names', () => {
    expect(documentHrefOf('SKILL.md', 'references/', fileHref, dirHref)).toBe('/dir/references')
    expect(documentHrefOf('references/a.md', '../scripts/', fileHref, dirHref)).toBe('/dir/scripts')
  })

  it('takes a bare folder name from the manifest rather than guessing from a slash', () => {
    const isDirectory = (relpath: string): boolean => relpath === 'references'
    expect(documentHrefOf('SKILL.md', 'references', fileHref, dirHref, isDirectory)).toBe(
      '/dir/references',
    )
    expect(documentHrefOf('SKILL.md', 'references', fileHref, dirHref)).toBe('/files/references')
  })

  it('appends the authored parameters to a directory address instead of a second ?', () => {
    const dirWithQuery = (dir: string): string => `/dir/${dir}?tab=files`
    expect(documentHrefOf('SKILL.md', 'references/?plain=1', fileHref, dirWithQuery)).toBe(
      '/dir/references?tab=files&plain=1',
    )
  })

  it('still answers for a target this version does not have', () => {
    // The page then lists the directory the link names, which says more than the skill list does.
    expect(documentHrefOf('SKILL.md', 'gone.md', fileHref, dirHref)).toBe('/files/gone.md')
  })

  it('leaves a site path alone — a path starting with / is not a path inside the skill', () => {
    expect(documentHrefOf('references/split.md', '/SKILL.md', fileHref, dirHref)).toBeNull()
    expect(documentHrefOf('SKILL.md', '/skills', fileHref, dirHref)).toBeNull()
  })

  it('decodes an encoded path before resolving it', () => {
    expect(
      documentHrefOf('SKILL.md', 'references/%E6%8B%86%E5%88%86.md', fileHref, dirHref),
    ).toBe('/files/references/拆分.md')
    expect(documentTargetOf('SKILL.md', 'references/%E6%8B%86%E5%88%86.md')?.relpath).toBe(
      'references/拆分.md',
    )
  })

  it('leaves a malformed escape alone rather than throwing during render', () => {
    expect(documentHrefOf('SKILL.md', '%E0%A4%A.md', fileHref, dirHref)).toBeNull()
  })

  it('refuses an encoded slash rather than letting it become a second segment', () => {
    expect(documentHrefOf('SKILL.md', 'a%2Fb.md', fileHref, dirHref)).toBeNull()
  })

  it('reads ./ as the document folder rather than as a target', () => {
    expect(documentTargetOf('references/a.md', './b.md')).toEqual({
      relpath: 'references/b.md',
      isDir: false,
      suffix: '',
    })
  })
})
