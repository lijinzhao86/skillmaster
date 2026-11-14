/**
 * 公域里的一行结果。
 *
 * **「发现」的两处用它**：第一次进入的「为你推荐」，和搜索 / 分类之后的结果清单。两处是同一行、
 * 同一套加入状态——如果各写一份，某一处的「已在库」迟早会漏掉。
 *
 * **只保留三行，其余在详情页。** 第一行名字与分类；第二行作者描述；
 * 第三行左边属性（可点，与分类同一套筛选地址）、右边公开标签。行尾三格——累计调用量（只读）、收藏（占位）、入库——
 * 数量都在图标正下方。许可、更新时间、适用场景、版本号与能力字段都移到 skill 信息页。
 * 这样 1440 宽一屏能扫 7 行左右，而不是两三张卡（v49 第 2 关，2026-11-05 用户定）。
 *
 * **v66：预期结果不上列表。** 列表先回答「这个 skill 是什么」——作者描述比一句主张更能讲明白；
 * 预期结果回答「用了得到什么」，放在信息页的适用场景之后（2026-11-13 用户定）。
 *
 * **动作只有一颗图标，钉在行尾。** 加入是一枚**书签**：未加入是描边，加入后是实心、转成主色，
 * 再点一下就退出。库里已有的条目画同一颗实心书签，但是静的——移除自己的库条目在 skill 页上，
 * 那里带确认（ADR 0056）。行的右端不再写「已在库 · 来源」：书签本身就是那个事实，来源在 title
 * 与读屏文本里。
 */

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

import SkillAvatar from './SkillAvatar'
import { SOURCE_LABEL, categoryName, profileOf, publicTagName } from '../fixtures/library'
import type { DiscoverSkill, LabelGroup, Source, SourceKind } from '../fixtures/library'

export type JoinState = 'idle' | 'busy' | 'failed'

/* 标识的图形；名字与地址跟着每条来源走（同一个 GitHub 平台，仓库可以不同）。
   本版最多一枚；加一个来源平台 ＝ 在这里加一枚 mark，页面不分支。 */
function SourceMark({ kind }: { kind: SourceKind }) {
  if (kind === 'github') {
    return (
      <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
        <path d="M12 2C6.48 2 2 6.58 2 12.26c0 4.53 2.87 8.37 6.84 9.73.5.1.68-.22.68-.49 0-.24-.01-.87-.01-1.71-2.78.62-3.37-1.37-3.37-1.37-.45-1.18-1.11-1.5-1.11-1.5-.91-.64.07-.63.07-.63 1 .07 1.53 1.06 1.53 1.06.9 1.57 2.35 1.12 2.92.86.09-.67.35-1.12.63-1.38-2.22-.26-4.56-1.14-4.56-5.07 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.71 0 0 .84-.28 2.75 1.05a9.3 9.3 0 0 1 5 0c1.91-1.33 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.63 1.03 2.75 0 3.94-2.34 4.81-4.57 5.06.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.81 0 .27.18.6.69.49A10.02 10.02 0 0 0 22 12.26C22 6.58 17.52 2 12 2Z" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="8.5" stroke="currentColor" strokeWidth="1.6" />
      <path d="M8.3 12.2 10.8 14.7 15.8 9.6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/** v66：属性类别的图标——终端（语言）、屏幕（系统）、工具（工具与资源）、钥匙（凭据）、价签（定价）。 */
function BlockerIcon({ kind }: { kind: 'key' | 'runtime' | 'os' | 'tool' | 'price' }) {
  if (kind === 'key') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="8.2" cy="8.2" r="3.6" stroke="currentColor" strokeWidth="1.8" />
        <path d="M10.8 10.8 19 19M15.2 15.2l2-2M17.2 17.2l2-2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  if (kind === 'runtime') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M4.8 7.2l4.6 4.8-4.6 4.8M12.4 16.8h6.8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  if (kind === 'os') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <rect x="3.5" y="4.5" width="17" height="12" rx="1.6" stroke="currentColor" strokeWidth="1.8" />
        <path d="M8.5 20h7M12 16.5V20" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  if (kind === 'tool') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M14.5 6.2a4 4 0 0 0-5 5L4.8 15.9a1.8 1.8 0 0 0 2.5 2.5l4.7-4.7a4 4 0 0 0 5-5l-2.6 2.6-2.4-2.4 2.5-2.7Z" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M12 3.8 20 8v8l-8 4.2L4 16V8l8-4.2Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M9.5 9.5h5M12 9.5v6M9.8 12.2h4.4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  )
}

/** 属性的类别决定图标；颜色由 CSS 的 `data-group` 决定。 */
function iconKind(group: LabelGroup): 'key' | 'runtime' | 'os' | 'tool' | 'price' {
  if (group === 'credential') return 'key'
  if (group === 'os') return 'os'
  if (group === 'tool') return 'tool'
  if (group === 'pricing') return 'price'
  return 'runtime'
}

/** 复制一行地址。`file://` 打开演示件时没有 Clipboard API，退回一段隐藏的 textarea。 */
function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text)
  const area = document.createElement('textarea')
  area.value = text
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  document.execCommand('copy')
  area.remove()
  return Promise.resolve()
}


export default function ResultCard({
  skill,
  source,
  joined,
  onJoin,
  onRemove,
}: {
  skill: DiscoverSkill
  /** 它现在在不在我的库里；在的话只说来源。 */
  source: Source | null
  joined: JoinState
  onJoin: () => void
  /** 刚在这一屏加入的那张：再点一次那枚实心书签就撤销。库里原有的条目不给这个动作。 */
  onRemove?: () => void
}) {
  /* **复制地址**：抄的是 `namespace/name`（与 CLI 打出来的地址同一个），点一下换成对勾一秒半，
     再回到复制图标。它是名字旁边的第二颗控件，不占一行、不挤掉分类。**2026-11-09 起叫「复制地址」**：
     「复制」已经是一个独立动作（v54，把一版内容复制成自己的 skill），剪贴板这一颗必须写明抄的是什么。 */
  const [copied, setCopied] = useState(false)
  const copyTimer = useRef<number | null>(null)
  useEffect(() => () => {
    if (copyTimer.current !== null) window.clearTimeout(copyTimer.current)
  }, [])
  const address = skill.namespace + '/' + skill.name
  const onCopy = () => {
    void copyText(address)
      .then(() => {
        setCopied(true)
        if (copyTimer.current !== null) window.clearTimeout(copyTimer.current)
        copyTimer.current = window.setTimeout(() => setCopied(false), 1600)
      })
      .catch(() => {})
  }

  const corner =
    source === 'joined' && onRemove ? (
      /* 刚加入的那张：图标是开关，再点一下撤销。 */
      <button type="button" className="result-join" data-state="joined" onClick={onRemove} title="移出库">
        <svg key="joined" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" fill="currentColor" />
        </svg>
        <span className="visually-hidden">移出库</span>
      </button>
    ) : source !== null ? (
      /* 库里已有的条目：同一颗对勾，但是静的——移除在 skill 页上。 */
      <span className="result-inlibrary" title={'已在库 · ' + SOURCE_LABEL[source]}>
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" fill="currentColor" />
        </svg>
        <span className="visually-hidden">{'已在库 · ' + SOURCE_LABEL[source]}</span>
      </span>
    ) : skill.state === 'pending' ? (
      /* v61（ADR 0184）：下线中不接受新入库，卡片不画一个点了必被拒绝的动作。 */
      null
    ) : joined === 'busy' ? (
      /* 加入中：同一个位置换成一段转动的弧。 */
      <button type="button" className="result-join" data-state="busy" disabled title="入库中">
        <svg className="spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="12" r="8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeDasharray="30 20" />
        </svg>
        <span className="visually-hidden">入库中…</span>
      </button>
    ) : (
      <button type="button" className="result-join" data-state="idle" onClick={onJoin} title="入库">
        <svg key="idle" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
        </svg>
        <span className="visually-hidden">入库</span>
      </button>
    )

  /* **第三行：左边属性、右边公开标签**（2026-11-13 用户定）。属性回答「跑起来缺什么」，
     公开标签回答「它为什么被搜到」；两类词不再混在同一串 `#` 里。 */
  /* **详情页跟着归属走**：公域里还没入库的（`inLibrary === null`）进「可加入」那一态，
     在库的带着自己的来源进去——导航据此点亮「发现」还是「我的 skill」。 */
  const skillHref = skill.inLibrary === null ? '?page=skill&in=visible' : '?page=skill&in=' + skill.inLibrary

  /* **v66：列表只显示作者描述。** 预期结果是平台的主张（ADR 0193），回答「用了得到什么」，
     回答不了「这个 skill 是什么、怎么用」；它只放信息页，不占列表的第二行。 */
  const profile = profileOf(skill.name)
  /* **v66：运行环境是多选，每个非默认值各一枚 chip**（2026-11-13 用户定）：用了 Node 和
     Python 就两枚都画；纯提示词不画。API key / 账号用钥匙图标，其余用终端图标；付费单独一枚
     价签。没有阻拦就不画，不把「免费 · 纯提示词」这种默认状态写出来。
     **第三行放前三枚，第四枚起收成 `+N`**（2026-11-13 用户定）：三枚时 Java / Linux / Docker
     都能看见，不用点；四枚以上才收，点 `+N` 展开全部。
     **前端只写短名**（2026-11-13 用户定）：`Python` / `macOS` / `API key`，不把「需要 Python」
     「仅 macOS」这类完整语义放进胶囊、tooltip 或读屏；完整语义留在词表数据里（抽取证据 / CLI / 运营）。 */
  /* 行上的属性由同一份 profile 生成：运行环境除纯提示词外全画，定价只画付费（免费是默认值，
     与生产读模型的排除口径一致）。可展示、不可筛的词（`filterable === false`）没有链接——
     服务端会把它当未知标签拒绝。 */
  const attributeChips = (profile.labels ?? [])
    .filter((item) => !(
      (item.key === 'runtime' && item.value === 'prompt-only') ||
      (item.key === 'pricing_type' && item.value !== 'paid')
    ))
    .map((item) => ({
      id: item.key + ':' + item.value,
      href: item.filterable === false
        ? undefined
        : '?page=discover&label=' + item.key + ':' + item.value,
      label: item.valueLabel,
      group: item.group,
      kind: iconKind(item.group),
    }))
  const shownAttrs = attributeChips.slice(0, 3)
  const hiddenAttrs = attributeChips.slice(3)

  const shownTags = skill.publicTags.slice(0, 3)
  const moreTags = skill.publicTags.length - shownTags.length
  /* **`+N` 点开是全部标签或全部属性的浮层**（2026-11-05 用户定；2026-11-13 加上属性）：
     行只有三行，展开不能把行撑高，所以浮层挂在 body 上、按按钮的位置定位；点外面或 Esc 关上。 */
  const [openMore, setOpenMore] = useState<{ kind: 'tags' | 'attrs'; left: number; top: number } | null>(null)
  const tagsRef = useRef<HTMLButtonElement>(null)
  const attrsRef = useRef<HTMLButtonElement>(null)
  const toggleMore = (kind: 'tags' | 'attrs', ref: { current: HTMLButtonElement | null }) => {
    if (openMore?.kind === kind) {
      setOpenMore(null)
      return
    }
    const rect = ref.current?.getBoundingClientRect()
    if (rect === undefined) return
    setOpenMore({
      kind,
      left: Math.max(12, Math.min(rect.left, window.innerWidth - 356)),
      top: rect.bottom + 6,
    })
  }
  useEffect(() => {
    if (openMore === null) return
    const onDown = (event: MouseEvent) => {
      const node = event.target
      if (node instanceof Element && (node.closest('.tags-pop') !== null || node.closest('.result-more') !== null)) return
      setOpenMore(null)
    }
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') setOpenMore(null)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [openMore])
  /* 入库数：库里的加入条数；刚在这一屏加入的那一条先加一，不用等下一次读取。 */
  const libraryCount = (skill.joins ?? 0) + (onRemove === undefined ? 0 : 1)

  return (
    <li
      className="result"
      data-name={skill.name}
      /* **整行可点，去 skill 页**（2026-11-05 用户定）：行里已经有复制、收藏、入库、分类、来源
         这些控件，所以点在它们身上时让给它们；其余位置都走名字那条路。 */
      onClick={(event) => {
        const target = event.target
        if (
          target instanceof Element &&
          target.closest('a,button,.result-copy,.result-more,.result-category,.result-action.is-reserved,.result-source-badge') !== null
        ) return
        window.location.assign(skillHref)
      }}
    >
      <SkillAvatar name={skill.name} />
      <div className="result-main">
        <div className="result-head">
          <span className="result-ident">
            {/* 地址里不留空格：`namespace/name` 是 CLI 里那个写法，复制出去就能直接用。 */}
            <a className="result-name" href={skillHref} title={address}>
              <span className="ns">{skill.namespace}/</span>{skill.name}
            </a>
            <button
              type="button"
              className="result-copy"
              onClick={onCopy}
              title={copied ? '已复制' : '复制地址'}
              aria-label={copied ? '已复制' : '复制地址'}
              data-copied={copied}
            >
              {copied ? (
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M5 12.5 10 17.5 19 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.7" />
                  <path d="M15 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h3" stroke="currentColor" strokeWidth="1.7" />
                </svg>
              )}
            </button>
          </span>
          {skill.category !== null && (
            /* **行上的分类是浏览入口**（2026-11-05 用户定）：点它去看同类；表头不再铺分类栏。 */
            <a
              className="chip is-plain result-category"
              href={'?page=discover&tag=' + skill.category}
              title={'查看这个分类下的 skill'}
            >
              {categoryName(skill.category)}
            </a>
          )}
          {skill.state === 'pending' && (
            /* v61（ADR 0184）：下线中的 skill 还在公域结果里——它仍然在服务——所以行上要说
               它要走、什么时候走，而不是让它看起来是常驻的。 */
            <span className="chip is-soon" title="下线中：仍可调用，已不接受新入库">
              下线中{skill.pendingUntil === undefined ? '' : ` · ${skill.pendingUntil}`}
            </span>
          )}
        </div>
        {/* **v66：列表只显示作者描述。** 封顶一行，完整的那一段在 `title` 里；主路是点名字进
            skill 页。预期结果不上列表——它回答「用了得到什么」，在信息页的适用场景之后。 */}
        <p className="result-title" title={skill.description}>
          {skill.description}
        </p>
        {/* **第三行：左属性、右公开标签**（2026-11-13 用户定）。属性最多三枚，再多收成 `+N`；
            公开标签仍在右端，最多三个。两组都可展开，行高不变。 */}
        <div className="result-bottom">
          <div className="result-attrs" aria-label="属性">
            {shownAttrs.map((chip) => {
              const body = (
                <>
                  <BlockerIcon kind={chip.kind} />
                  {chip.label}
                </>
              )
              return chip.href === undefined ? (
                <span className="chip is-blocker" key={chip.id} data-group={chip.group}>
                  {body}
                </span>
              ) : (
                <a className="chip is-blocker" key={chip.id} href={chip.href} data-group={chip.group}>
                  {body}
                </a>
              )
            })}
            {hiddenAttrs.length > 0 && (
              <button
                type="button"
                ref={attrsRef}
                className="result-more"
                aria-expanded={openMore?.kind === 'attrs'}
                title={'显示全部 ' + attributeChips.length + ' 个属性'}
                onClick={() => toggleMore('attrs', attrsRef)}
              >
                {'+' + hiddenAttrs.length}
              </button>
            )}
          </div>
          <div className="result-tags">
            {shownTags.map((id) => (
              <span className="result-tag" key={id}>{'#' + publicTagName(id)}</span>
            ))}
            {moreTags > 0 && (
              <button
                type="button"
                ref={tagsRef}
                className="result-more"
                aria-expanded={openMore?.kind === 'tags'}
                title={'显示全部 ' + skill.publicTags.length + ' 个公开标签'}
                onClick={() => toggleMore('tags', tagsRef)}
              >
                {'+' + moreTags}
              </button>
            )}
          </div>
        </div>
        {joined === 'failed' && (
          <p className="result-error" role="alert">
            没有入库：服务器没有响应。
            <button type="button" className="link-button" onClick={onJoin}>重试</button>
          </p>
        )}
      </div>
      {openMore !== null &&
        createPortal(
          <div
            className="tags-pop"
            role="dialog"
            aria-label={openMore.kind === 'tags' ? '全部公开标签' : '全部属性'}
            style={{ left: openMore.left, top: openMore.top }}
          >
            {openMore.kind === 'tags' ? (
              <>
                <p className="tags-pop-head">{'公开标签 · ' + skill.publicTags.length}</p>
                <div className="tags-pop-list">
                  {skill.publicTags.map((id) => (
                    <span className="tags-pop-tag" key={id}>{'#' + publicTagName(id)}</span>
                  ))}
                </div>
              </>
            ) : (
              <>
                <p className="tags-pop-head">{'属性 · ' + attributeChips.length}</p>
                <div className="tags-pop-list">
                  {attributeChips.map((chip) => {
                    const body = (
                      <>
                        <BlockerIcon kind={chip.kind} />
                        {chip.label}
                      </>
                    )
                    return chip.href === undefined ? (
                      <span className="tags-pop-attr" key={chip.id} data-group={chip.group}>
                        {body}
                      </span>
                    ) : (
                      <a className="tags-pop-attr" key={chip.id} href={chip.href} data-group={chip.group}>
                        {body}
                      </a>
                    )
                  })}
                </div>
              </>
            )}
          </div>,
          document.body,
        )}
      {/* **行尾三格，数量都在图标正下方**（2026-11-05 用户定）：累计调用量 → 收藏（占位）
          → 入库。前两格 vs 第三格要一眼分得开：收藏与入库是动作，悬停有底色、鼠标是手；
          调用量不是控件，颜色更淡、悬停没有任何反应。 */}
      <div className="result-actions">
        <div className="result-action-row">
          <div className="result-action is-static" title="累计调用次数">
            <span className="result-action-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none">
                <path d="M13 2.5 5 14h6l-1 7.5 9-12h-6l0-7Z"
                  stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
              </svg>
            </span>
            <span className="result-count">{skill.callsBucket ?? '—'}</span>
            <span className="visually-hidden">累计调用次数</span>
          </div>
          <div className="result-action is-reserved" title="收藏（功能暂未开放）">
            <span className="result-action-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none">
                <path d="M12 20s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 10c0 5.65-7 10-7 10Z"
                  stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
              </svg>
            </span>
            <span className="result-count">—</span>
            <span className="visually-hidden">收藏（功能暂未开放）</span>
          </div>
          <div className="result-action">
            {corner}
            <span className="result-count">{libraryCount}</span>
          </div>
        </div>
        {/* **来源标识在右下角**（2026-11-05 用户定）：本版最多一枚；它的右边缘
            与入库那一格对齐。它说的是「这条从哪来」，所以不并进动作行。没有来源时这一格留空
            但保留高度——否则有标识的行会比没标识的行高出一截，整份结果就不齐了。 */}
        <div className="result-sources">
          {/* **本版最多一枚**（镜像留给同步那版）：有来源网页的在新标签打开；官方没有独立
              来源页，只作说明。 */}
          {skill.sources?.[0] !== undefined && (
            skill.sources[0].url === undefined ? (
              <span className="result-source-badge" data-kind={skill.sources[0].kind} title={skill.sources[0].label}>
                <SourceMark kind={skill.sources[0].kind} />
                <span className="visually-hidden">{'来源：' + skill.sources[0].label}</span>
              </span>
            ) : (
              <a
                className="result-source-badge"
                data-kind={skill.sources[0].kind}
                href={skill.sources[0].url}
                target="_blank"
                rel="noreferrer"
                title={skill.sources[0].label}
                aria-label={'在新标签打开来源：' + skill.sources[0].label}
              >
                <SourceMark kind={skill.sources[0].kind} />
                <span className="visually-hidden">{'来源：' + skill.sources[0].label}</span>
              </a>
            )
          )}
        </div>
      </div>
    </li>
  )
}
