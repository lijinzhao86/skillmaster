/**
 * The strip along the bottom. **Not part of the design** — the demo's own switch.
 *
 * **It lists the variants of the view you are on.** The skill page is five views, not one; one flat
 * list of every state under one label is a row of addresses nobody can tell apart. So the strip
 * reads the address, works out which view is up, and shows that view's variants. Who you are is
 * orthogonal to which screen you are on, so the identity switch stays at the end.
 */

import type { ReactNode } from 'react'
import { DEFAULT_STATE, STATES, TIERS, isConsolePage, tierRank } from '../demo'
import type { AuthPage, ConsolePage, PageName } from '../demo'
import { RENEWED_EXPIRES } from '../fixtures/keys'
import { membershipOf, tierForMembership } from '../fixtures/library'

interface Variant {
  label: string
  href: string
}

const SKILL_VARIANTS: Record<string, Variant[]> = {
  info: [
    { label: '正常', href: '?page=skill' },
    { label: '没有 title', href: '?page=skill&state=no-title' },
    { label: '没有这个 skill', href: '?page=skill&state=missing' },
    /* **六种库归属**（PRD #33）：这一屏的加入 / 移除按钮与「我的标签」一区都由它决定。 */
    { label: '共享给我的', href: '?page=skill&in=shared' },
    { label: '我加入的', href: '?page=skill&in=joined' },
    { label: '可加入', href: '?page=skill&in=visible' },
    { label: '不可加入', href: '?page=skill&in=blocked&offline=1' },
    { label: '条目休眠', href: '?page=skill&in=dormant&offline=1' },
  ],
  files: [
    { label: '根目录', href: '?page=skill&tab=files' },
    { label: '子目录', href: '?page=skill&path=references' },
    { label: '读一个文件', href: '?page=skill&file=references/split.md' },
    { label: '只有草稿', href: '?page=skill&tab=files&no-live=1' },
    { label: '新建版本菜单', href: '?page=skill&tab=files&new=open' },
  ],
  versions: [
    { label: '全部', href: '?page=skill&tab=versions' },
    { label: '筛选：草稿', href: '?page=skill&tab=versions&filter=draft' },
    { label: '搜索无结果', href: '?page=skill&tab=versions&filter=live&q=zzz' },
    { label: '很多版本', href: '?page=skill&tab=versions&versions=24' },
    { label: '新建版本菜单', href: '?page=skill&tab=versions&new=open' },
    { label: '导入', href: '?page=skill&tab=versions&import=open' },
    { label: '导入失败', href: '?page=skill&tab=versions&import=error' },
    { label: '名字不可用', href: '?page=skill&tab=versions&import=bad-names' },
    { label: '名字不可用（一个）', href: '?page=skill&tab=versions&import=bad-name-single' },
    { label: '名字不一致', href: '?page=skill&tab=versions&import=mismatch' },
    { label: '内容已存在', href: '?page=skill&tab=versions&import=exists' },
    { label: '内容已存在（草稿）', href: '?page=skill&tab=versions&import=exists-draft' },
    { label: '内容已存在（读不到）', href: '?page=skill&tab=versions&import=exists-hidden' },
  ],
  version: [
    { label: '线上', href: '?page=skill&tab=versions&version=1.2.3' },
    { label: '草稿', href: '?page=skill&tab=versions&version=7K3M9QX2' },
    { label: '历史版本', href: '?page=skill&tab=versions&version=1.2.2' },
    { label: '已下架', href: '?page=skill&tab=versions&version=1.2.1&sub=actions' },
    { label: '已丢弃', href: '?page=skill&tab=versions&version=Q4W8E2R6' },
    { label: '没有这一版', href: '?page=skill&tab=versions&version=nope' },
  ],
  settings: [
    { label: '常规', href: '?page=skill&tab=settings' },
    { label: '协作者', href: '?page=skill&tab=settings&sub=collaborators' },
    { label: '公开可见', href: '?page=skill&tab=settings&sub=general&vis=public-invoke' },
    { label: '已下线', href: '?page=skill&tab=settings&sub=general&offline=1' },
  ],
}

/** The two moments that need something to be about. The strip is how you look at a screen without
 *  walking the form that leads to it, so its 已创建 carries a demo key and its 已续期 renews the
 *  fixture closest to dying. */
const STRIP_MOMENTS: Record<string, Record<string, string>> = {
  created: {
    'made-id': 'k6',
    'made-name': '演示密钥',
    'made-scope': 'read',
    'made-expires': RENEWED_EXPIRES.replace(/\//g, '-'),
    'made-note': '从演示条打开的，不是真的签出来的',
  },
  rotated: { rotated: 'k2' },
}

const AUTH_VIEWS = [
  ['login', '登录'],
  ['register', '注册'],
  ['reset', '找回密码'],
] as const

function stripSignature(query: string): string {
  const copy = new URLSearchParams(query)
  copy.delete('as')
  if (copy.get('page') === 'skill' && copy.get('tab') === 'info') copy.delete('tab')
  if (copy.get('state') === DEFAULT_STATE[copy.get('page') as ConsolePage]) copy.delete('state')
  const pairs: string[] = []
  copy.forEach((value, name) => { pairs.push(name + '=' + value) })
  return pairs.sort().join('&')
}

function skillViewNow(params: URLSearchParams): string {
  if (params.get('version')) return 'version'
  const tab = params.get('tab')
  if (tab === 'files' || (!tab && (params.get('path') !== null || params.get('file') !== null))) {
    return 'files'
  }
  if (tab === 'versions') return 'versions'
  if (tab === 'settings') return 'settings'
  return 'info'
}

/**
 * The strip's options, in order, as elements rather than as a string: a separator and a note are
 * not links, and rendering them as one would put two things in the tab order that go nowhere.
 */
export default function DemoBar({
  page,
  state,
  params,
  authView,
}: {
  page: PageName
  state: string
  params: URLSearchParams
  authView: AuthPage
}) {
  const nodes: ReactNode[] = []
  const add = (label: string, href: string, on: boolean) => (
    <a key={label + '|' + href} href={href} className={on ? 'is-on' : undefined}>{label}</a>
  )
  const separator = () => <span key={'sep' + nodes.length} className="demo-sep">·</span>
  const note = (text: string) => <span key={'note' + nodes.length} className="demo-note">{text}</span>
  const push = (node: ReactNode) => { nodes.push(node) }

  const withTier = (href: string): string => {
    const as = params.get('as')
    if (!as) return href
    const query = new URLSearchParams(href)
    query.set('as', as)
    return '?' + query.toString()
  }

  const paneAddress = (sub: string): string => {
    const query = new URLSearchParams()
    query.set('page', 'skill')
    query.set('tab', 'versions')
    query.set('version', params.get('version') ?? '')
    query.set('sub', sub)
    const as = params.get('as')
    if (as) query.set('as', as)
    return '?' + query.toString()
  }

  if (!isConsolePage(page)) {
    AUTH_VIEWS.forEach(([view, label]) => {
      push(add(label, '?page=' + view, view === authView))
    })
    push(separator())
    push(note('演示账号 13800138000 / skillmaster'))
  } else {
    /** **The strip of the console.** */
    const here = stripSignature('?' + params.toString())
    const variant = (entry: Variant) => {
      push(add(entry.label, withTier(entry.href), stripSignature(entry.href) === here))
    }

    if (page === 'skill') {
      const view = skillViewNow(params)
      SKILL_VARIANTS[view].forEach(variant)
      if (view === 'version') {
        push(separator())
        push(note('栏目'))
        const panes: Variant[] = [
          { label: '改动', href: paneAddress('diff') },
          { label: '操作', href: paneAddress('actions') },
          { label: '大改动（124 文件）', href: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&load=1' },
        ]
        panes.forEach(variant)
      }
    } else {
      STATES[page].forEach((option) => {
        /* **有的状态需要一整个地址**：公域的「有结果」必须带一个真实查询——没有任何地址能让公域
           列出全部，所以它不能是 `?page=discover&state=results`。 */
        let href = option.href ??
          ('?page=' + page + (option.key !== DEFAULT_STATE[page] ? '&state=' + option.key : ''))
        /* **The strip's way into the form remembers the same thing the button does.** `list` is which
           fixture the form was opened from, and both exits return to it — but it is deliberately not
           carried by the other states: a value that keeps riding along once the form is left makes
           `?page=keys&list=empty` a second address for `?page=keys`. */
        if (page === 'keys' && option.key === 'new' && state !== DEFAULT_STATE.keys) {
          href += '&list=' + encodeURIComponent(state)
        }
        const needed = option.params ? STRIP_MOMENTS[option.params] : undefined
        if (needed) {
          const query = new URLSearchParams(href)
          const known = Object.keys(needed).some((name) => query.has(name))
          if (!known) {
            Object.keys(needed).forEach((name) => query.set(name, needed[name]))
            href = '?' + query.toString()
          }
        }
        push(add(option.label, withTier(href), option.key === state))
      })
      /* **标签页的第二份清单是一个页签，不是一种状态**：状态说的是这一屏读没读到，页签说的是读到了
         哪一份。所以它挨着状态排在后面，与「我的 skill」的「已删除」同一种位置。 */
      if (page === 'my-tags') {
        push(add('公开标签', '?page=my-tags&tab=public', params.get('tab') === 'public'))
      }
      /* **上线队列的其他样子**：勾选、全选、筛选、改类型、部分失败、长队列。它们都带一个
         `state=` 以外的参数，所以不是「读到没读到」那四态——但演示条是看这一屏的唯一入口，
         不在这里列出来就只能靠手敲地址（2026-10-25 用户找不到 20 个 skill 的那一张）。 */
      if (page === 'publish') {
        /* **命名空间是页头的单选**，所以它在演示条上也是一组"切到哪一个"，而不是一种状态。 */
        push(separator())
        push(note('命名空间'))
        const nsNow = params.get('ns') ?? 'dev'
        push(add('dev', '?page=publish', nsNow === 'dev'))
        push(add('yuhan', '?page=publish&ns=yuhan', nsNow === 'yuhan'))
        push(separator())
        push(note('队列的其他样子'))
        ;([
          { label: '已勾选 2 份', href: '?page=publish&picked=d-chg-2,d-api-1' },
          { label: '全选后', href: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1' },
          { label: '另一个空间里缺版本类型', href: '?page=publish&ns=yuhan&only=step' },
          { label: '改版本类型', href: '?page=publish&types=d-api-1' },
          { label: '确认单', href: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1&publish=confirm' },
          { label: '上线执行中', href: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1&publish=running' },
          { label: '结算', href: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1&publish=done' },
          /* **长批次**：单子比弹窗高，正在走的那一条被送到中间，结算也要滚着看。 */
          { label: '长批次 · 执行中', href: '?page=publish&many=1&publish=running' },
          { label: '长批次 · 结算', href: '?page=publish&many=1&publish=done' },
          { label: '上线之后', href: '?page=publish&publish=partial' },
          { label: '20 多个 skill', href: '?page=publish&many=1' },
          /* **被截断的队列**：一次最多取 500 个 skill，页头就在计数旁边说明这件事。 */
          { label: '500 个 skill', href: '?page=publish&cut=1' },
          { label: '名字很长', href: '?page=publish&long=1' },
          /* **两类历史行**：线上版本没有号（写 `—`）、草稿没有名字（写「未命名」）。 */
          { label: '历史空值', href: '?page=publish&legacy=1' },
        ] as Variant[]).forEach(variant)
      }
      if (page === 'skills') {
        push(add('已删除', '?page=skills&show=deleted', params.get('show') === 'deleted'))
        push(separator())
        push(note('标签筛选（多选，取「且」）'))
        push(add('一个分类', '?page=skills&tag=doc', params.getAll('tag').includes('doc')))
        push(add('一个我的标签', '?page=skills&my-tag=client', params.getAll('my-tag').includes('client')))
        push(add('两个一起', '?page=skills&tag=dev&my-tag=client', params.getAll('tag').includes('dev') && params.getAll('my-tag').includes('client')))
        push(separator())
        push(note('新建 skill（v9）'))
        push(add('菜单', '?page=skills&new=open', params.get('new') === 'open'))
        push(add('在线编辑', '?page=skills&newskill=open', params.get('newskill') === 'open'))
        push(add('名字已占用', '?page=skills&newskill=error', params.get('newskill') === 'error'))
        push(add('名字属于已删除的 skill', '?page=skills&newskill=deleted', params.get('newskill') === 'deleted'))
        push(add('导入对话框', '?page=skills&import=open', params.get('import') === 'open'))
        push(add('导入失败', '?page=skills&import=error', params.get('import') === 'error'))
        push(add('名字不可用', '?page=skills&import=bad-names', params.get('import') === 'bad-names'))
        push(add('名字不可用（一个）', '?page=skills&import=bad-name-single', params.get('import') === 'bad-name-single'))
        push(add('内容已存在', '?page=skills&import=exists', params.get('import') === 'exists'))
        push(add('内容已存在（草稿）', '?page=skills&import=exists-draft', params.get('import') === 'exists-draft'))
        push(add('内容已存在（读不到）', '?page=skills&import=exists-hidden', params.get('import') === 'exists-hidden'))
        push(add('名字已删除', '?page=skills&import=deleted', params.get('import') === 'deleted'))
      }
    }

    /* **同一页，六种人。** This is the demo's stand-in, not part of the interface: in the product,
       what someone can see is computed by the server (ADR 0047 §决定 3), and the page only draws the
       copy it was given. The switch swaps that copy, so "who sees what" can be looked at rather than
       believed. */
    if (page === 'skill' || page === 'skills') {
      push(separator())
      push(note('以谁的身份看'))
      const askedTier = params.get('as')
      /* 与页面同一条规则：没指定身份时，按这一屏的库归属推一档（`?in=`）。 */
      const tier = tierRank(askedTier) >= 0
        ? (askedTier as string)
        : tierForMembership(page === 'skill' ? membershipOf(params) : 'created')
      TIERS.forEach((entry) => {
        const identity = new URLSearchParams(params)
        identity.set('as', entry.key)
        push(add(entry.label, '?' + identity.toString(), entry.key === tier))
      })
    }
  }

  return (
    <div className="demo-bar">
      <span className="demo-tag">演示</span>
      <span className="demo-opts" id="demoOpts">{nodes}</span>
    </div>
  )
}
