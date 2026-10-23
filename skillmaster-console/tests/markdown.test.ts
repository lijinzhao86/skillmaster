import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../src/markdown'

/**
 * The claims `src/markdown.ts` makes: the ones the DOM depends on, and the one the page's shape does.
 *
 * That page puts this output into the DOM with `v-html`. It is safe because of what is asserted here,
 * not because of anything the page does — so the escaping tests are the ones that would fail on the
 * day somebody turns raw HTML on for a formatting reason and does not think about it again.
 */

describe('rendering a SKILL.md', () => {
  it('renders the Markdown a skill is written in', () => {
    const html = renderMarkdown('# 什么时候用它\n\n- 打标签\n- 筛人\n\n```sh\nlark contacts\n```\n')

    expect(html).toContain('<h1>什么时候用它</h1>')
    expect(html).toContain('<li>打标签</li>')
    expect(html).toContain('<pre><code class="language-sh">')
  })

  it('drops a leading frontmatter block rather than drawing the YAML as a document', () => {
    // Left in, those three lines are a rule, a paragraph, and then a setext heading made of the raw
    // YAML — which is what the page showed before this rule existed.
    const html = renderMarkdown('---\nname: pdf-tools\ndescription: 拆 PDF\n---\n\n# 标题\n')

    expect(html).not.toContain('name: pdf-tools')
    expect(html).not.toContain('<hr>')
    expect(html).toContain('<h1>标题</h1>')
  })

  it('leaves a rule that is part of the document alone', () => {
    // Only the very first line opens frontmatter: a break further down is the author's own.
    const html = renderMarkdown('# 标题\n\n---\n\n后面\n')

    expect(html).toContain('<hr>')
    expect(html).toContain('<p>后面</p>')
  })

  it('leaves a document that merely opens with a rule alone', () => {
    // "`---`, anything, `---`" is also what a document whose first block is a rule looks like, and
    // stripping that would delete everything above the second rule. Requiring a `key:` on the line
    // after the opening fence is what tells the two apart — so nothing here is frontmatter.
    const html = renderMarkdown('---\n引言\n\n---\n\n正文\n')

    expect(html).toContain('引言')
    expect(html).toContain('正文')
  })

  it('renders a document with no frontmatter at all', () => {
    expect(renderMarkdown('# 只有正文\n')).toContain('<h1>只有正文</h1>')
  })

  it('escapes raw HTML instead of emitting it', () => {
    const html = renderMarkdown('前面\n\n<script>alert(1)</script>\n\n<img src=x onerror=alert(2)>\n')

    // The substring "onerror=" does appear, and that is fine: it is inside an escaped text node.
    // What must not appear is a tag — an attribute is only dangerous as part of one.
    expect(html).not.toContain('<script>')
    expect(html).not.toContain('<img')
    // Shown as text — the reader sees what the document says rather than the document running.
    expect(html).toContain('&lt;script&gt;')
    expect(html).toContain('&lt;img src=x onerror=alert(2)&gt;')
  })

  it('refuses a link that would run script', () => {
    const html = renderMarkdown('[点我](javascript:alert(1))\n\n[也是](vbscript:msgbox)\n')

    expect(html).not.toContain('href="javascript:')
    expect(html).not.toContain('href="vbscript:')
  })

  it('keeps an ordinary link, and makes a bare URL one', () => {
    const html = renderMarkdown('[文档](https://example.com/doc)\n\n见 https://example.com/x\n')

    expect(html).toContain('href="https://example.com/doc"')
    expect(html).toContain('href="https://example.com/x"')
  })
})
