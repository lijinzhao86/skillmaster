import { renderMarkdown } from '../markdown'

/**
 * A skill's `SKILL.md`, read as a document rather than as source.
 *
 * `v-html`, which is only acceptable because of what `renderMarkdown` guarantees: raw HTML in the
 * document is escaped and dangerous URL schemes are refused. Both are pinned by tests — see
 * `src/markdown.ts`, which is where the one rule that matters lives: **turning raw HTML on there
 * means adding a sanitiser here in the same commit.**
 *
 * **What changed in the port.** `v-html` is `dangerouslySetInnerHTML`, and the computed `html` is
 * derived during render — there is no reactive wrapper to keep in step.
 */
export interface Props {
  source: string
}

export default function MarkdownView({ source }: Props) {
  const html = renderMarkdown(source)

  return <div className="doc" dangerouslySetInnerHTML={{ __html: html }} />
}
