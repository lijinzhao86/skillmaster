/**
 * One skill's preview: the pane beside the list (v35).
 *
 * **It answers the same question one step earlier.** The skill page's 信息 tab carries "everything
 * you need to decide whether to use it"; this pane lifts the same four facts next to the list and
 * saves the trip in-and-back-out. So it adds no information, and it must not grow its own wording —
 * both sides read the same fields.
 *
 * **It does not change what a row is.** The name is still the link into the skill page — here it is
 * the head of the pane, with an arrow-out button beside it — and the preview only removes the "do I
 * have to go in to find out?" hop.
 *
 * Width and presentation belong to `styles.css` (`.list-split` / `.skill-preview`): 380px in the
 * flow on a wide window, an overlay on a narrow one. The component does not know which it is in.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { formatTime } from '../../time'
import type { SkillCard } from '../../api/types'

/**
 * **The category has a line of its own, and my tags have the next one.** State and source came off —
 * both sit on the row to the left, and that row is highlighted right beside the pane, so drawing them
 * again said the same thing twice (2026-10-31, the user's call). The category is *not* back beside
 * the name: that line has to hold a 64-character address, and it lost the argument to it. Each of the
 * two rows carries its own 12px faint name (分类 / 我的标签), so a reader can tell which is a shelf and
 * which is their own word.
 *
 * **Tags fill one line; the rest go behind a `+N`.** One skill can hold 20 of my tags, and drawing
 * them all pushes the description down. So how many fit is **measured**, not counted: a tag name can
 * be 24 characters, and counting would be wrong wherever the column is narrower.
 *
 * Same measuring method as `FoldableText`: draw all, measure, hide what does not fit. It only ever
 * shrinks, so it settles in at most three passes (all → k → the `+N` takes room too → maybe k−1).
 */
function ChipRow({ skill }: { skill: SkillCard }) {
  const row = useRef<HTMLDivElement | null>(null)
  const tags = skill.my_tags
  /* **A different skill starts this over**: the caller keys the component by name, so there is no
     reset effect here — one would run after the layout effect and undo the count just measured. */
  const [shown, setShown] = useState(tags.length)
  const [open, setOpen] = useState(false)

  useLayoutEffect(() => {
    if (open) return
    const element = row.current
    if (element === null) return
    /* Nothing to measure (not laid out yet, or a test environment without layout) means do not trim:
       drawing a few chips too many beats hiding every tag. */
    if (element.clientWidth === 0) return
    /* Room for the `+N` on the right; `16` is this row's left and right padding — content must not
       reach into that white space. */
    const toggle = element.querySelector<HTMLElement>('[data-toggle]')
    const edge = element.getBoundingClientRect().right - 16 -
      (toggle === null ? 0 : toggle.getBoundingClientRect().width + 8)
    const chips = [...element.querySelectorAll<HTMLElement>('[data-chip]')]
    let fit = 0
    for (const chip of chips) {
      if (chip.getBoundingClientRect().right > edge + 0.5) break
      fit += 1
    }
    if (fit < shown) setShown(fit)
  }, [open, shown, tags.map((tag) => tag.id).join('|'), skill.category])

  const visible = open ? tags : tags.slice(0, shown)
  const hidden = tags.length - visible.length
  if (tags.length === 0) return null

  return (
    <div className={'preview-row' + (open ? ' is-open' : '')} id="previewChips" ref={row}>
      <span className="preview-row-k">我的标签</span>
      {visible.map((tag) => (
        <span key={tag.id} className="chip is-mine" data-chip>
          <span className="chip-text">{tag.name}</span>
        </span>
      ))}
      {/* **One button with two states.** It used to be two, rendered exclusively: pressing `+N`
          unmounted the button, focus fell to the body, and the pane's Escape handler stopped being
          reachable. Same element now, different word — focus stays put. The accessible name starts
          with the visible word (`+3` / 收起) so voice control can say what is on screen. */}
      {(open || hidden > 0) && (
        <button
          type="button"
          data-toggle
          className="link-button preview-tag-more"
          aria-expanded={open}
          aria-label={open ? '收起，收起标签' : `+${hidden}，还有 ${hidden} 个标签`}
          onClick={() => setOpen(!open)}
        >
          {open ? '收起' : `+${hidden}`}
        </button>
      )}
    </div>
  )
}

/**
 * **A text block that folds at 8 lines, with 展开全文 only when it really overflows.**
 *
 * Both blocks use it because both need the guard: descriptions run to 949 characters (19 lines), and
 * `when_to_use` has **no length limit on the server at all** — the column is `TEXT`, validation only
 * requires `name` to be 64 characters or fewer and `description` to be present, and the only ceiling
 * is the 1,000,000-character cap on a whole frontmatter. So the pane cannot assume either is short.
 *
 * The button is drawn from a measurement taken once after paint: folded, `clientHeight` is eight
 * lines and `scrollHeight` is the whole text, and unequal means there is more than is shown.
 */
function FoldableText({ text, label }: { text: string; label: string }) {
  const body = useRef<HTMLParagraphElement | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [folds, setFolds] = useState(false)

  /* **Measuring once is not enough**: the pane is `min(380px, 100%)`, so narrowing the column folds
     the same text into more lines — and with a `[text]`-only effect the clamp would cut it with no
     展开全文 to reach the rest. Re-measure when the width changes; skip while expanded, where "no
     overflow" is the expected state and would otherwise remove the 收起 button. */
  const expandedNow = useRef(false)
  expandedNow.current = expanded
  useLayoutEffect(() => {
    const element = body.current
    if (element === null) {
      return
    }
    const measure = () => {
      if (expandedNow.current) return
      setFolds(element.scrollHeight > element.clientHeight + 1)
    }
    setExpanded(false)
    /* **The ref follows the state we just asked for, not the last render's.** `expandedNow` is
       assigned during render, so right here it still holds the previous value — with a long text
       expanded and a short one selected next, `measure()` returned early and the short text kept a
       「展开全文」 that unfolded nothing (audit 2026-11-01). */
    expandedNow.current = false
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [text])

  return (
    <>
      <p ref={body} className={`preview-text${expanded ? '' : ' is-folded'}`}>{text}</p>
      {folds ? (
        /* Two of these can sit in one pane; the name carries which one it unfolds, and still
           contains the visible words. */
        <button
          type="button"
          className="link-button preview-more"
          aria-expanded={expanded}
          aria-label={`${expanded ? '收起' : '展开全文'}：${label}`}
          onClick={() => setExpanded((open) => !open)}
        >
          {expanded ? '收起' : '展开全文'}
        </button>
      ) : null}
    </>
  )
}

export default function SkillPreview({
  skill,
  deleted = false,
  mine = false,
  onClose,
}: {
  skill: SkillCard
  /** The deleted list: no invocation address (nothing to invoke), and the date reads 删除于. */
  deleted?: boolean
  /** Whether this skill lives in the reader's own namespace — then the title leaves the prefix off. */
  mine?: boolean
  onClose: () => void
}) {
  const panel = useRef<HTMLElement | null>(null)
  const href = `/skills/${encodeURIComponent(skill.namespace)}/${encodeURIComponent(skill.name)}`


  /* **Focus lands in the pane on open.** It floats over the list on a narrow window, and without
     focus inside, Escape has nowhere to go. It lands on the container, not the close button:
     focusing the button draws a ring around it, which reads as "this one is selected".
     `tabIndex={-1}` keeps it out of the Tab order; its buttons are still reached by Tab. */
  useEffect(() => {
    panel.current?.focus({ preventScroll: true })
  }, [skill.namespace, skill.name])

  return (
    <aside
      className="skill-preview"
      id="skillPreview"
      ref={panel}
      tabIndex={-1}
      aria-label={`预览：${mine ? '' : `${skill.namespace}/`}${skill.name}`}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation()
          onClose()
        }
      }}
    >
      <div className="preview-head">
        {/* **The way in sits on the title, and there are two doors to it.** The name itself is a
            link (hover turns it to the accent with an underline); the arrow-out button to its right
            is the same action as an icon. Both are live because this row is "which skill is this",
            and the first thing anyone does with that is go in. The footer row 打开 skill 页 is gone
            for the same reason: it pushed the only exit to the very bottom of the pane and cost 48px
            of height doing it. */}
        {/* **A deleted skill's name is not a link** (audit 2026-10-31): the row draws it as text for
            the same reason — that address opens nothing now, and a name that looks clickable and
            lands on a 404 is worse than plain text. The arrow goes with it. */}
        {deleted ? (
          <span className="preview-name">
            {mine ? null : (
              <>
                <span className="ns">{skill.namespace} /</span>{' '}
              </>
            )}
            {skill.name}
          </span>
        ) : (
        <a className="preview-name" href={href} title="打开 skill 页">
          {mine ? null : (
            <>
              <span className="ns">{skill.namespace} /</span>{' '}
            </>
          )}
          {skill.name}
        </a>
        )}
        {deleted ? null : (
          <a className="preview-open-icon" href={href} aria-label={`打开 skill 页：${skill.name}`}>
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M8 16 16 8M16 8H9.5M16 8v6.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </a>
        )}
        <button type="button" className="preview-close" onClick={onClose} aria-label="关闭预览">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
        </button>
      </div>

      {skill.category !== null && (
        /* **The category gets its own row, my tags another (2026-10-31, the user's call).** They are
           not the same kind of thing: the category is the platform's shelf (exactly one), my tags are
           words I stuck on it (a run). Side by side they looked alike, and the reader could not tell
           them apart. */
        <div className="preview-row" id="previewCategory">
          <span className="preview-row-k">分类</span>
          <span className="chip is-plain">
            <span className="chip-text">{skill.category.name}</span>
          </span>
        </div>
      )}

      <ChipRow key={`${skill.namespace}/${skill.name}`} skill={skill} />

      <div className="preview-k">描述</div>
      <FoldableText text={skill.description} label="描述" />

      {skill.when_to_use !== null && skill.when_to_use !== '' ? (
        <>
          {/* **适用场景** is the product's own word for this field (`voice-spec.md` §名词表) and
              the caption the skill page's 信息 tab already uses; 「什么时候该想起它」 was the only
              place that said it another way (2026-10-31, the user's call). */}
          <div className="preview-k">适用场景</div>
          <FoldableText text={skill.when_to_use} label="适用场景" />
        </>
      ) : null}

      <div className="preview-k">{deleted ? '删除于' : '更新于'}</div>
      <p className="preview-text preview-mono">
        {deleted
          ? skill.deleted_at !== null ? formatTime(skill.deleted_at) : '—'
          : skill.updated_at !== null ? formatTime(skill.updated_at) : '—'}
      </p>
    </aside>
  )
}
