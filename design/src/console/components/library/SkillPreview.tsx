/**
 * 一条 skill 的预览：列表右边那一格（v35）。
 *
 * **它答的是同一个问题，只是早一格。** skill 页的「信息」那一栏写的是「决定用不用它所需的全部」；
 * 这一格把同样的四件事提前到列表旁边，省掉「进去、退出来、再进去」那一趟。所以它**不新增信息**，
 * 也不许另起一套写法——两边读的是同一组字段，文案也照那边。
 *
 * **它不改这一行是什么。** 名字仍然是进 skill 页的链接——在这里它是这一格的头，右边一枚「往外开」
 * 的图标是同一件事；预览只是让「要点进去吗」这个判断不必先跳一次。
 *
 * 宽度与出现方式归 `styles.css` 的 `.list-split` / `.skill-preview`：宽屏是并排的 380，窄了就盖在
 * 列表上——那是布局的事，组件不管自己在哪种形态下。
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { MY_NAMESPACE, categoryName, myTagName } from '../../fixtures/library'
import type { LibrarySkill } from '../../fixtures/library'

/**
 * **我的标签各占一行、带自己的名字。** 状态与来源拿掉了——它们在左边那一行上，而那一行就亮在
 * 预览旁边，再画一遍是同一句话说两次（2026-10-31 用户定）。分类没有留在标题上（那里放不下长
 * 地址），而是**自己一行**（`分类`），这一行是 `我的标签` ——两行各带 12px 的淡墨小字名字，
 * 读者分得出哪个是货架、哪个是自己的词。
 *
 * **标签默认只放一行，放不下的收进「+N」，点开才铺开。** 一个 skill 最多能贴 20 个我的标签
 * （产品上限），全铺出来会把描述挤下去。所以按**量出来的宽度**决定放得下几个，不按个数猜：标签
 * 名字最长 24 字，按个数算在窄处一定错。
 *
 * 量的办法与 `FoldableText` 同一套：先全画、再量、把放不下的藏起来。只减不增，所以最多画三遍
 * 就停（全画 → 算出 k → 画 k 个时「+N」也占了地方 → 可能再少一个）。
 */
function ChipRow({ skill }: { skill: LibrarySkill }) {
  const row = useRef<HTMLDivElement | null>(null)
  const tags = skill.myTags
  /* **换一条 skill 就整块重来**：调用方按地址（`命名空间/名字`）给 `key`，所以这里不必再自己复位——用 `useEffect`
     复位会晚一拍（它在 `useLayoutEffect` 之后跑，把刚量好的数又打回全集）。 */
  const [shown, setShown] = useState(tags.length)
  const [open, setOpen] = useState(false)

  useLayoutEffect(() => {
    if (open) return
    const element = row.current
    if (element === null) return
    /* 量不出来（还没排版、或测试环境没有布局）就不裁：宁可多画几颗，也不能把标签藏成 0 颗。 */
    if (element.clientWidth === 0) return
    /* 右边留出「+N」的位置；`16` 是这一行左右各 16 的内边距（内容不能越到留白里去）。 */
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
  }, [open, shown, tags.join('|')])

  const visible = open ? tags : tags.slice(0, shown)
  const hidden = tags.length - visible.length
  if (tags.length === 0) return null

  return (
    <div className={'preview-row' + (open ? ' is-open' : '')} id="previewChips" ref={row}>
      <span className="preview-row-k">我的标签</span>
      {visible.map((id) => (
        <span key={id} className="chip is-mine" data-chip>
          <span className="chip-text">{myTagName(id)}</span>
        </span>
      ))}
      {/* **一颗按钮，两种状态**（2026-10-31 审计）：它原来在「+N」与「收起」之间互斥渲染，点下去
          按钮自身被卸载，焦点掉回 body，预览的 Esc 就再没人接。现在同一颗按钮换文字，焦点留在原地。
          可访问名里带上可见的那个词（`+3` / `收起`）——语音控制按的是看得见的字。 */}
      {(open || hidden > 0) && (
        <button
          type="button"
          data-toggle
          className="link-button preview-tag-more"
          aria-expanded={open}
          aria-label={open ? '收起，收起标签' : '+' + hidden + '，还有 ' + hidden + ' 个标签'}
          onClick={() => setOpen(!open)}
        >
          {open ? '收起' : '+' + hidden}
        </button>
      )}
    </div>
  )
}

/**
 * **一段会折的文字：折在 8 行，装不下才画「展开全文」。**
 *
 * 两段话共用它，是因为两件事都得防：描述实测最长 949 字（19 行），而 `when_to_use` 在服务端
 * **没有长度上限**——DB 里是 `TEXT`，校验只要求 `name` 不超过 64 字、`description` 非空，唯一
 * 的天花板是整份 frontmatter 的 1,000,000 字符。所以预览不能假设哪一段是短的。
 *
 * 只有真的溢出才画按钮：默认折着、量一次 `scrollHeight > clientHeight`，装得下就什么都不多出来。
 *
 * **8 行是量出来的，不是挑的（v35 微调）。** 面板在 900px 高的窗口里只有 732px：去掉底栏之后固定开销
 * 是 205（头 37 ＋ 章 27 ＋ 三个小标题 120 ＋ 时间 21），两颗折按钮再占 80——剩下 447px 给正文。
 * 两段各 8 行 = 336px，留 111px 余量；两段各 12 行就是 504px，面板从那时起开始滚。
 */
function FoldableText({ text, label }: { text: string; label: string }) {
  const body = useRef<HTMLParagraphElement | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [folds, setFolds] = useState(false)
  /* 展开之后本来就不再溢出，这时重量会把「收起」说成「不折」——按钮随之消失。所以展开态下不量。 */
  const expandedNow = useRef(false)
  expandedNow.current = expanded

  /* **量一次不够**：这一格在窄窗口里是 `min(380px,100%)`，宽度变了、折的行数就变了。只盯
     `[text]` 的话，缩窄之后文字被 CSS 裁掉、而「展开全文」永远不会出现——那一段就再也读不到。
     所以宽度变化也重量（2026-10-31 审计）。 */
  useLayoutEffect(() => {
    const element = body.current
    if (element === null) return
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
      <p ref={body} className={'preview-text' + (expanded ? '' : ' is-folded')}>{text}</p>
      {folds && (
        /* 一格里可能同时有两颗（描述与适用场景都长）；名字里带上那一段，读屏才分得清是哪一颗，
           可见的字仍在名字里。 */
        <button
          type="button"
          className="link-button preview-more"
          aria-expanded={expanded}
          aria-label={(expanded ? '收起' : '展开全文') + '：' + label}
          onClick={() => setExpanded((open) => !open)}
        >
          {expanded ? '收起' : '展开全文'}
        </button>
      )}
    </>
  )
}

export default function SkillPreview({
  skill,
  deleted = false,
  onClose,
}: {
  skill: LibrarySkill
  /** 已删除那一份列表：没有调用地址（它现在调不动），日期写的是「删除于」。 */
  deleted?: boolean
  onClose: () => void
}) {
  const panel = useRef<HTMLElement | null>(null)


  /* **打开就把焦点收进这一格。** 它是浮在列表上的一块（窄屏尤其），焦点不落进来的话 Esc 没地方接。
     落在容器上而不是关闭键上：落在键上会给它画一圈焦点环，打开就在那儿亮一个框，看着像选中了它。
     `tabIndex={-1}` 让它可聚焦但不进 Tab 顺序；里面的关闭键与标签那颗照旧按 Tab 走到。 */
  useEffect(() => {
    panel.current?.focus({ preventScroll: true })
  }, [skill.namespace, skill.name])

  return (
    <aside
      className="skill-preview"
      id="skillPreview"
      ref={panel}
      tabIndex={-1}
      aria-label={'预览：' + (skill.namespace === MY_NAMESPACE ? '' : skill.namespace + '/') + skill.name}
      onKeyDown={(event) => {
        if (event.key === 'Escape') { event.stopPropagation(); onClose() }
      }}
    >
      <div className="preview-head">
        {/* **入口在标题上，而且有两条。** 名字本身是链接（悬停转主色加下划线），右边那枚
            「往外开」的箭头是同一件事的图标版——两处都点得动，是因为标题这一行本来就是「这一条是
            谁」，人看它的第一眼就该能进去。底栏那一行「打开 skill 页」因此拿掉了：它把唯一的出口
            压在一格面板的最下面，还要占 48px。 */}
        {/* **已删除那一条的名字不是链接**（2026-10-31 审计）：行首就是这么画的——那条地址已经打不开，
            一个看起来像链接、点下去 404 的名字比纯文本更糟。图标同理，一起拿掉。 */}
        {deleted ? (
          <span className="preview-name">
            {skill.namespace !== MY_NAMESPACE && (
              <>
                <span className="ns">{skill.namespace} /</span>{' '}
              </>
            )}
            {skill.name}
          </span>
        ) : (
        <a className="preview-name" href="?page=skill" title="打开 skill 页">
          {/* **自己的命名空间不写前缀**，与列表同一条规则：这一页上的行大多在它里面，写了只是每行
              重复一个字。别人（与团队命名空间）的照写——那正是要看见的那一类。 */}
          {skill.namespace !== MY_NAMESPACE && (
            <>
              <span className="ns">{skill.namespace} /</span>{' '}
            </>
          )}
          {skill.name}
        </a>
        )}
        {!deleted && (
          <a className="preview-open-icon" href="?page=skill" aria-label={'打开 skill 页：' + skill.name}>
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
        /* **分类自己一行、带着自己的名字，我的标签另一行**（2026-10-31 用户定）。两者不是一类东西：
           分类是平台给的货架（只有一个），我的标签是我自己贴的词（一串）。同一行里两颗胶囊长得一样，
           读的人分不出——所以这一行写「分类」两个字，用的就是正文小标题那一档（`.preview-k` 的
           字号与颜色，只是不画上线）。名字与技能页「信息」那一栏同一套说法。 */
        <div className="preview-row" id="previewCategory">
          <span className="preview-row-k">分类</span>
          <span className="chip is-plain">
            <span className="chip-text">{categoryName(skill.category)}</span>
          </span>
        </div>
      )}

      <ChipRow key={skill.namespace + '/' + skill.name} skill={skill} />

      <div className="preview-k">描述</div>
      <FoldableText text={skill.description} label="描述" />

      <div className="preview-k">适用场景</div>
      <FoldableText text={skill.whenToUse} label="适用场景" />

      <div className="preview-k">{deleted ? '删除于' : '更新于'}</div>
      <p className="preview-text preview-mono">{deleted ? skill.deleted : skill.updated}</p>

    </aside>
  )
}
