/**
 * 一个 skill: a repository-shaped page — what it says, which files it has, whether a version is
 * waiting, and who may do what with it.
 *
 * **Every screen here is in the address.** `tab` picks the section, `path`/`file` the listing and
 * the file being read, `version` the one version the 版本 tab is showing, `sub` the second level
 * under 设置 and the pane under 版本. A filtered, sorted, second-page, pinned screen is a thing
 * somebody can send to somebody else.
 *
 * **A rung decides what is drawn, not what is greyed out.** 调用 gets 信息 and 版本; 浏览 adds 文件;
 * 写入 adds the draft row and 新建版本; 维护 adds the lifecycle actions; 管理 adds 设置; 所有者 adds
 * the danger zone. The demo's stand-in for the server-computed tier is `?as=`.
 *
 * The fixtures are the demo's; the rules that read them live in `fixtures/skill.ts`.
 */

import { Fragment, useEffect, useRef, useState } from 'react'
import type { DemoPageProps } from './types'
import { replace } from '../address'
import { tierRank } from '../demo'
import CommandMenu from '../components/CommandMenu'
import type { CommandItem } from '../components/CommandMenu'
import { dialogs } from '../components/dialogs/store'
import InfoPane from '../components/skill/InfoPane'
import FilesPane from '../components/skill/FilesPane'
import VersionsPane from '../components/skill/VersionsPane'
import SettingsPane from '../components/skill/SettingsPane'
import UsagePane from '../components/skill/UsagePane'
import VersionDetail from '../components/skill/VersionDetail'
import type { AtLeast, Grant, Sub, Version, Visibility } from '../fixtures/skill'
import SkillAvatar from '../components/SkillAvatar'
import { CATEGORIES, DEMO_LICENSE, DEMO_ORIGIN, DEMO_PROFILE, DEMO_USAGE, LIBRARY, MY_TAGS, PUBLIC_TAGS, membershipOf, tierForMembership } from '../fixtures/library'
import type { Membership } from '../fixtures/library'
import { DEFAULT_RANGE, rangeFromParams } from '../fixtures/usage'
import type { UsageRange } from '../fixtures/usage'
import {
  BASE_VERSIONS,
  DEMO_NOW,
  GRANTS,
  INVITATIONS,
  PUBLIC_RUNG,
  REQUESTS,
  ROOT_FILE,
  SKILL_NAME,
  SKILL_OWNER,
  SUBS,
  TREE,
  VISIBILITIES,
  dirOf,
  encodePath,
  fileSnapshot,
  liveVersion,
  manyVersions,
  rungIndex,
  sortVersions,
  versionByPin,
  versionOptionValue,
  visibleVersions,
} from '../fixtures/skill'

export default function SkillPage({ active, state, params }: DemoPageProps) {
  /* ------------------------------------------------------ 这一屏的库归属 (v14) */

  /* **六种归属，一行地址一种**（PRD #11/#33）：我创建的（默认）、共享给我的、我加入的、
     不在库但能看见（可加入）、能看见但当前不可加入（下线 / 没有当前版本）、库条目休眠。 */
  const askedMembership: Membership = membershipOf(params)

  /* ---------------------------------------------------------------- the tier */

  const askedTier = params.get('as')
  const tier = tierRank(askedTier) >= 0 ? (askedTier as string) : tierForMembership(askedMembership)
  const atLeast: AtLeast = (key) => {
    const mine = tierRank(tier)
    const need = tierRank(key)
    return mine >= 0 && need >= 0 && mine <= need
  }

  /* ------------------------------------------------------------- the fixtures */

  const [versions, setVersions] = useState<Version[]>(() => {
    let list = BASE_VERSIONS.map((version) => ({ ...version }))
    const many = parseInt(params.get('versions') ?? '', 10)
    if (many > 0) list = list.concat(manyVersions(many))
    /* **演示件没有「只有草稿」的那一态**：地址带 `no-live=1` 时只留草稿. */
    if (params.get('no-live') === '1') list = list.filter((version) => version.draft)
    return list
  })
  const [grants, setGrants] = useState<Grant[]>(
    () => (params.get('members') === 'none' ? [] : GRANTS.map((grant) => ({ ...grant }))),
  )
  const [requests, setRequests] = useState<Grant[]>(() => REQUESTS.map((request) => ({ ...request })))
  const [invitations, setInvitations] = useState<Grant[]>(
    () => INVITATIONS.map((invitation) => ({ ...invitation })),
  )
  const [myRequest, setMyRequest] = useState<{ role: string } | null>(() => {
    const asked = params.get('ask')
    return asked && rungIndex(asked) > rungIndex(tier) ? { role: asked } : null
  })
  const [askedRung, setAskedRung] = useState<string | null>(null)
  /* **下线有三态（v61）**：在线、下线中、已下线。地址只带一个：`pending=1` / `offline=1`，
     都不带就是在线。 */
  const [phase, setPhaseState] = useState<'online' | 'pending' | 'offline'>(() => (
    params.get('pending') === '1' ? 'pending' : params.get('offline') === '1' ? 'offline' : 'online'
  ))
  const offline = phase === 'offline'
  const pending = phase === 'pending'
  /* 演示件的到期日；产品里由服务端按开始时刻 + 30 天算出。 */
  const pendingUntil = '2026/12/11'

  /* ------------------------------------------------------- 库与两套标签 (v14) */

  const [membership, setMembership] = useState<Membership>(askedMembership)
  const [joining, setJoining] = useState(false)
  const inLibrary = membership === 'created' || membership === 'shared' || membership === 'joined'
  /* **我的适用场景**：库条目才有；地址里 `myWhen=` 覆盖演示数据，空串就是空态。 */
  const [myWhenToUse, setMyWhenToUse] = useState<string>(() => {
    const asked = params.get('myWhen')
    if (asked !== null) return asked
    if (membership === 'visible' || membership === 'blocked' || membership === 'dormant') return ''
    return LIBRARY.find((skill) => skill.name === 'pdf-tools')?.myWhenToUse ?? ''
  })
  /* 默认是「办公效率」；地址里的 `category=` 让演示能落在别的货架上——包括停用词
     （停用的词不能新增，但已经贴在它上面的仍然保留）。 */
  const [category, setCategory] = useState<string | null>(() => {
    const asked = params.get('category')
    return asked !== null && CATEGORIES.some((entry) => entry.id === asked) ? asked : 'office-efficiency'
  })
  /* **公开标签属于这个 skill，不属于某个人**：换谁来看都是同一组；只有作者能改。 */
  const [publicTagDefs, setPublicTagDefs] = useState(() => PUBLIC_TAGS.map((tag) => ({ ...tag })))
  const [publicTagIds, setPublicTagIds] = useState<string[]>(() =>
    [...(LIBRARY.find((skill) => skill.name === 'pdf-tools')?.publicTags ?? [])])
  const [tagDefs, setTagDefs] = useState(() => MY_TAGS.map((tag) => ({ ...tag })))
  const [myTagIds, setMyTagIds] = useState<string[]>(() => (membership === 'visible' || membership === 'blocked' || membership === 'dormant' ? [] : ['client']))

  const joinLibrary = () => {
    setJoining(true)
    /* 演示件没有服务端：600ms 之后成功，好让「加入中」真的被看见。 */
    window.setTimeout(() => {
      setJoining(false)
      setMembership('joined')
      setMyTagIds((ids) => (ids.length > 0 ? ids : ['client']))
    }, 600)
  }

  const askRemove = () => {
    dialogs.openDanger({
      title: '从我的 skill 移除？',
      /* **普通弹窗，不染红**：移除的是我这边的一条引用，不拿走任何东西（ADR 0056）。 */
      body: '标签会保留，重新入库后会回来。这个 skill 仍在公域，仍可直接调用。',
      confirmLabel: '确认移除',
      danger: false,
      onConfirm: () => {
        setMembership('visible')
        setMyTagIds([])
      },
    })
  }

  /* **两套标签都就地改，不开弹窗**（2026-10-22）：每一次改动立即生效，与那一版的契约一致
     ——改标签不产生新版本、不动 current_version_id。 */
  const changeCategory = (id: string | null) => setCategory(id)
  const pickTag = (id: string) => {
    if (myTagIds.includes(id)) return
    setMyTagIds((list) => list.concat(id))
  }
  const createTag = (name: string) => {
    const tag = { id: 'tag-' + Date.now(), name }
    setTagDefs((list) => list.concat(tag))
    setMyTagIds((list) => list.concat(tag.id))
  }
  const removeTag = (id: string) => setMyTagIds((list) => list.filter((value) => value !== id))
  /* 公开标签的输入框与我的标签同一个组件：已有的词选中它，没有的词就新建一个公开标签。 */
  const pickPublicTag = (id: string) => {
    if (publicTagIds.includes(id)) return
    setPublicTagIds((list) => list.concat(id))
  }
  const createPublicTag = (name: string) => {
    const existing = publicTagDefs.find((tag) => tag.name.toLowerCase() === name.toLowerCase())
    if (existing) {
      pickPublicTag(existing.id)
      return
    }
    const tag = { id: 'public-' + Date.now(), name }
    setPublicTagDefs((list) => list.concat(tag))
    setPublicTagIds((list) => list.concat(tag.id))
  }
  const removePublicTag = (id: string) => setPublicTagIds((list) => list.filter((value) => value !== id))

  /* 可见但当前不可加入时说原因；休眠的条目也要说一句它为什么不在列表里。 */
  const membershipNote = membership === 'blocked'
    ? (offline ? '已下线，暂时不能入库。' : pending ? '下线中，暂时不能入库。' : '还没有上线的版本，暂时不能入库。')
    : membership === 'visible' && (pending || offline)
      ? (pending ? '下线中，暂时不能入库。' : '已下线，暂时不能入库。')
      : membership === 'dormant'
        ? '暂时不可见，恢复后会回到你的库。'
        : ''

  /* **移除的那一问也能由地址打开**：它有一屏可以发出去。两套标签不再有弹窗，`?edit=`
     只负责「进来就展开那一组」（由 `InfoPane` 的初始态读走）。 */
  useEffect(() => {
    if (!active) return
    if (params.get('remove') === '1' && membership === 'joined') askRemove()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  /* ------------------------------------------------------------------ menus */

  const versionCreateRef = useRef<HTMLButtonElement>(null)
  const filesCreateRef = useRef<HTMLButtonElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [menuOwner, setMenuOwner] = useState<'files' | 'versions' | null>(null)
  const [menuTrigger, setMenuTrigger] = useState<HTMLElement | null>(null)
  /* **复制收在页头的「⋯」里**（2026-11-09 用户定）：入库是平台推荐用户做的动作，留在页头
     一级；复制不是，所以收进更多。两颗菜单各自独立，别把同一个 open 状态借来借去。 */
  const [copyMenuOpen, setCopyMenuOpen] = useState(false)
  const [copyMenuTrigger, setCopyMenuTrigger] = useState<HTMLElement | null>(null)
  const joinButton = useRef<HTMLButtonElement>(null)

  /* 地址里带 `new=open` 时把当前这一页的「新建」菜单打开：点得出来的状态也要能单独发出去。 */
  useEffect(() => {
    if (!active || params.get('new') !== 'open') return
    /* 两颗「新建版本」各开各的：文件栏那一颗在文件栏，不在版本栏。 */
    const onFiles = params.get('tab') === 'files'
    const trigger = onFiles ? filesCreateRef.current : versionCreateRef.current
    if (!trigger || trigger.hidden) return
    setMenuOwner(onFiles ? 'files' : 'versions')
    setMenuTrigger(trigger)
    setMenuOpen(true)
  }, [active])

  const toggleMenu = (owner: 'files' | 'versions') => {
    if (menuOpen) {
      setMenuOpen(false)
      menuTrigger?.focus()
      return
    }
    setMenuOwner(owner)
    setMenuTrigger(owner === 'files' ? filesCreateRef.current : versionCreateRef.current)
    setMenuOpen(true)
  }

  const menuItems: CommandItem[] = [
    { text: '在线编辑', href: '?page=editor&state=new' },
    { text: '导入压缩包', onSelect: (trigger) => dialogs.openImport('skill', 'open', trigger) },
  ]

  /* ---------------------------------------------------------------- the tab */

  /* **没入库就只有「信息」**（2026-11-05 用户定）：公域里还没入库的 skill 看不到版本；
     入库（含休眠的库条目）之后才有「文件 / 版本 / 设置」。 */
  const inLibraryForTabs = inLibrary || membership === 'dormant'
  const openTabs = ['info']
    .concat(atLeast('browse') ? ['files'] : [])
    .concat(inLibraryForTabs ? ['versions'] : [])
    /* **「使用」对每个能打开这一页的人都有一份**（v58）：所有者读到聚合与协作者统计，
       其他人读到自己在这个 skill 上的记录——两种都在这一个页签里，按关系分。 */
    .concat(['usage'])
    .concat(atLeast('admin') ? ['settings'] : [])
  const askedPath = params.get('path') || null
  const askedFile = params.get('file') || null
  const askTab = params.get('tab')
  const namedFiles = askedPath !== null || askedFile !== null
  const tab = askTab !== null && openTabs.includes(askTab)
    ? askTab
    : (namedFiles && openTabs.includes('files') ? 'files' : openTabs[0])

  const askSub = params.get('sub')
  const sub: Sub = SUBS.includes(askSub as Sub) ? (askSub as Sub) : 'general'

  /* 非官方命名空间不能公开（ADR 0146）：演示件用 `&official=1` 表示官方账号，默认是非官方，
     这时即使地址里带了 `vis` 也只落私有。 */
  const official = params.get('official') === '1'
  /* 演示件用地址里的一个参数代替服务端的 `can_publish_public`，所以分区间走动时要带着它。 */
  const officialQuery = official ? '&official=1' : ''
  /* **使用页签的两个视图走地址**（2026-11-10）：`use=mine` 是「我的使用」，不带就是「全部使用」。
     和页签、版本选择同一条规矩——能深链的视图都写在地址里。 */
  const usageScope: 'all' | 'mine' = params.get('use') === 'mine' ? 'mine' : 'all'
  const setUsageScope = (next: 'all' | 'mine') => {
    const query = new URLSearchParams(params)
    query.set('page', 'skill')
    query.set('tab', 'usage')
    if (next === 'mine') query.set('use', 'mine')
    else query.delete('use')
    replace('?' + query.toString())
  }
  /* **「使用」页签的时间范围也走地址**（v60）：与使用记录页共用同一组 `days` / `from` / `to`；
     深链、刷新、从记录页过来都还原同一屏。切视图（全部 / 我的）不丢区间。 */
  const usageRange = rangeFromParams(params)
  const setUsageRange = (next: UsageRange) => {
    const query = new URLSearchParams(params)
    query.set('page', 'skill')
    query.set('tab', 'usage')
    query.delete('days')
    query.delete('from')
    query.delete('to')
    if (next.from !== null && next.to !== null) {
      query.set('from', next.from)
      query.set('to', next.to)
    } else if ((next.days ?? DEFAULT_RANGE.days) !== DEFAULT_RANGE.days) {
      query.set('days', String(next.days))
    }
    replace('?' + query.toString())
  }
  const askedVis = params.get('vis')
  /* **`in=visible` 与 `in=joined` 在演示件里只能是公开 skill**：没有关系就看不见，而只有公开的
    才能入库。所以这两种归属默认按公开算——否则这两屏会自相矛盾（看见一个你既没创建也没被共享的
    私有 skill）。其余归属默认私有，要用 `official=1&vis=public-*` 才公开。 */
  const visibility: Visibility = official && (VISIBILITIES as readonly string[]).includes(askedVis ?? '')
    ? (askedVis as Visibility)
    : askedMembership === 'visible' || askedMembership === 'joined'
      ? 'public-browse'
      : 'private'
  const publicRung = PUBLIC_RUNG[visibility] ?? null

  /* ---------------------------------------------------------------- the files */

  let reading = askedFile !== null ? askedFile : (askedPath === null ? ROOT_FILE : null)
  let here = reading !== null ? dirOf(reading) : (askedPath ?? '')
  /* The address is typed by hand as often as it is linked to, so a path that names nothing lands
     on the root rather than on a page with nothing in it. */
  if (!Object.prototype.hasOwnProperty.call(TREE, here)) {
    here = ''
    reading = ROOT_FILE
  }

  const live = liveVersion(versions)
  const visible = visibleVersions(versions, atLeast)
  const snapshot = fileSnapshot(versions, atLeast, params)
  const target = versionByPin(versions, params.get('version'), atLeast)
  const versionCount = visible.filter((version) => !version.draft && version.state !== 'discarded').length + ' 个版本'
  const published = visible.filter((version) => !version.draft && version.state !== 'discarded').length

  /* ------------------------------------------------------------- 复制 (v54) */

  /* **谁能复制：浏览及以上、不是自己的、并且有一版读得到的已发布版本。** 共享给我的私有 skill
     也算——复制是「库到库」，不是「公开到私有」；已下线、休眠、不可加入（没有线上版本）都不画。
     草稿、已丢弃、已下架不进版本下拉：它们不是一个能拿走的完整版本，或者作者已经收回了。 */
  const copyable = visible.filter((version) =>
    !version.draft && version.state !== 'discarded' && version.state !== 'revoked')
  const canCopy = membership !== 'created' && membership !== 'blocked' && membership !== 'dormant'
    && atLeast('browse') && copyable.length > 0 && !offline
  const copyVersions = copyable.map((version) => {
    const label = (version.name ?? version.draftName ?? version.code).replace(/^@/, '')
    return {
      value: (version.name ?? version.code).replace(/^@/, ''),
      label: version.live ? '线上 · ' + label : label,
    }
  })
  const liveCopy = copyVersions.find((entry) => entry.label.startsWith('线上 · '))?.value
    ?? copyVersions[0]?.value ?? ''

  /* 地址里带 `copy=` 时把这一张弹窗打开：点得出来的状态也要能单独发出去。 */
  useEffect(() => {
    if (!active) return
    const asked = params.get('copy')
    if (!asked || !canCopy) return
    const mode = asked === 'taken' || asked === 'invalid' || asked === 'done' ? asked : 'open'
    const wanted = params.get('copyVersion')
    const initial = wanted !== null && copyVersions.some((entry) => entry.value === wanted)
      ? wanted
      : liveCopy
    dialogs.openCopy({ mode, skillName: SKILL_NAME, versions: copyVersions, initialVersion: initial, trigger: null })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  /* 地址里带 `more=1` 时把那颗「⋯」菜单打开：点得出来的状态也要能单独发出去。 */
  useEffect(() => {
    if (!active || params.get('more') !== '1' || !canCopy) return
    const trigger = document.getElementById('skillMore')
    if (!trigger) return
    setCopyMenuTrigger(trigger)
    setCopyMenuOpen(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  /* **两种信号边角态，地址一种一态**（v49 第 2 关）：许可缺失、没人用过。
     正常态用夹具里的完整值。许可冲突不在这一版——来源文件与声明不一致时用文件的值，
     拦截与校验留到 v50 的入库流程（2026-11-06 用户定）。 */
  const signalVariant = params.get('signals') ?? ''
  const demoOrigin = DEMO_ORIGIN
  const demoLicense = signalVariant === 'license-missing'
    ? { ...DEMO_LICENSE, spdx: 'NOASSERTION', name: '未声明', copyright: undefined, status: 'missing', url: undefined, allows: [], requires: [] }
    : DEMO_LICENSE
  const demoUsage = signalVariant === 'cold'
    ? { bucket: '少于 10 个账号', lastUsed: undefined, joins: 0 }
    : DEMO_USAGE

  /* --------------------------------------------------------------- mutations */

  const changeVersion = (key: string, change: (version: Version) => void) => {
    setVersions((list) => list.map((version) => {
      if (versionOptionValue(version) !== key) return version
      const next = { ...version }
      change(next)
      return next
    }))
  }

  const publishDraft = (key: string, name: string) => {
    setVersions((list) => {
      const next = list.map((version) => ({ ...version }))
      const chosen = next.find((version) => versionOptionValue(version) === key)
      if (!chosen) return list
      const previous = next.find((version) => version.live)
      if (previous && previous !== chosen) {
        previous.live = false
        previous.stateAt = DEMO_NOW
        previous.supersededAt = true
        previous.restoredAt = false
      }
      chosen.name = '@' + name
      chosen.draft = false
      chosen.state = 'published'
      /* 首次上线的时间在这里落下：线上版本页拿它找「上一版」。 */
      chosen.firstPublishedAt = chosen.firstPublishedAt || chosen.at
      chosen.stateAt = chosen.at
      next.forEach((version) => { version.live = false })
      chosen.live = true
      return sortVersions(next)
    })
  }

  const republish = (key: string) => {
    setVersions((list) => {
      const next = list.map((version) => ({ ...version }))
      const chosen = next.find((version) => versionOptionValue(version) === key)
      if (!chosen) return list
      const previous = next.find((version) => version.live)
      if (previous && previous !== chosen) {
        previous.live = false
        previous.stateAt = DEMO_NOW
        previous.supersededAt = true
        previous.restoredAt = false
      }
      next.forEach((version) => { version.live = false })
      chosen.live = true
      chosen.draft = false
      chosen.state = 'published'
      chosen.stateAt = DEMO_NOW
      chosen.restoredAt = false
      chosen.supersededAt = false
      return sortVersions(next)
    })
  }

  const restoreVersion = (key: string) => {
    changeVersion(key, (version) => {
      version.state = 'published'
      version.stateAt = DEMO_NOW
      version.restoredAt = true
      version.supersededAt = false
    })
  }

  const discardDraft = (key: string) => {
    changeVersion(key, (version) => {
      version.draft = false
      version.state = 'discarded'
      version.stateAt = DEMO_NOW
      version.restoredAt = false
      version.supersededAt = false
    })
    /* 丢弃自己的人可能只是「写入」：那一档看不到已丢弃的行，所以把钉从地址上摘掉、回到清单，
       而不是把人留在一屏「没有这一版」上。 */
    if (!atLeast('maintain')) {
      const query = new URLSearchParams(params)
      query.set('page', 'skill')
      query.set('tab', 'versions')
      query.delete('version')
      query.delete('sub')
      replace('?' + query.toString())
    }
  }

  const revokeVersion = (key: string, reason: string) => {
    changeVersion(key, (version) => {
      version.state = 'revoked'
      version.reason = reason
      version.stateAt = DEMO_NOW
      version.restoredAt = false
      version.supersededAt = false
    })
  }

  const renameDraft = (key: string, name: string) => changeVersion(key, (version) => { version.draftName = name })
  const setNote = (key: string, note: string) => changeVersion(key, (version) => { version.note = note })

  /* -------------------------------------------------------------- visibility */

  const setVisibility = (value: Visibility) => {
    const query = new URLSearchParams(params)
    query.set('page', 'skill')
    query.set('tab', 'settings')
    query.set('sub', 'general')
    if (value === 'private') query.delete('vis')
    else query.set('vis', value)
    replace('?' + query.toString())
  }

  /* 技能级下线（v61）：公开且有入库者先进「下线中」30 天；其余即时下线。 */
  const setPhase = (next: 'online' | 'pending' | 'offline') => {
    setPhaseState(next)
    const query = new URLSearchParams(params)
    query.set('page', 'skill')
    query.set('tab', 'settings')
    query.set('sub', 'general')
    query.delete('offline')
    query.delete('pending')
    if (next === 'pending') query.set('pending', '1')
    if (next === 'offline') query.set('offline', '1')
    if (visibility === 'private') query.delete('vis')
    else query.set('vis', visibility)
    replace('?' + query.toString())
  }

  /* ---------------------------------------------------------------- members */

  const approveRequest = (index: number, role: string) => {
    const request = requests[index]
    if (!request) return
    setGrants((list) => {
      const existing = list.find((grant) => grant.who === request.who)
      if (existing) {
        return list.map((grant) => (grant === existing ? { ...grant, role } : grant))
      }
      return [...list, { who: request.who, role }]
    })
    setRequests((list) => list.filter((_, position) => position !== index))
  }
  const denyRequest = (index: number) => {
    setRequests((list) => list.filter((_, position) => position !== index))
  }

  /* ------------------------------------------------------------------ render */

  const crumb = here === ''
    ? null
    : here.split('/').map((part, index, parts) => {
      const upto = parts.slice(0, index + 1).join('/')
      return (
        <Fragment key={upto}>
          {' / '}
          {index === parts.length - 1
            ? <span>{part}</span>
            : <a href={'?page=skill&tab=files&path=' + encodePath(upto)}>{part}</a>}
        </Fragment>
      )
    })

  return (
    <section className="page" id="page-skill" hidden={!active}>
      <div data-when="missing" hidden={state !== 'missing'}>
        <div className="notfound">
          <h2>没有这个 skill</h2>
          {/* **Same screen, several reasons.** The address may name nothing, a deleted skill, or a
              skill this person cannot reach. The 404 is one answer on purpose. */}
          <p className="hint">这个地址下没有你能打开的 skill，也可能它已经被删除了。</p>
          <p><a href="?page=skills">回到我的 skill</a>　·　<a href="?page=skills&show=deleted">查看已删除的 skill</a></p>
        </div>
      </div>

      <div data-when="ready no-title" hidden={state !== 'ready' && state !== 'no-title'}>
        <header className="skill-head">
          <div className="skill-ident">
            <SkillAvatar name="pdf-tools" />
            <div className="skill-who">
              <div className="skill-name-row">
                <h1><span className="ns">dev /</span> <a className="name" href={'?page=skill' + officialQuery}>pdf-tools</a><span className="crumb" id="skillCrumb">{crumb}</span></h1>
                <a
                  className="chip is-plain"
                  id="skillBadge"
                  href={'?page=skill&tab=settings&sub=general' + officialQuery}
                  hidden={!atLeast('admin')}
                >
                  {atLeast('browse') && publicRung !== null ? '公开' : '私有'}
                </a>
                <span className="chip is-soon" id="skillPendingBadge" hidden={!pending}>下线中 · {pendingUntil} 下线</span>
                <span className="chip is-offline" id="skillOfflineBadge" hidden={!offline}>已下线</span>
              </div>
              <p className="skill-title" hidden={state === 'no-title'}>把 PDF 拆页、合并与压缩。</p>
            </div>
            {/* **加入 / 移除在页头，和仓库页的 Star 同一个位置。** 它是我这边的一条关系，
                不是这个 skill 的属性；申请在正文下方，两者因此不会读成同一件事。 */}
            <div className="skill-ident-actions">
              {/* **右上角：证据 → 收藏 → 主按钮 → ⋯**（2026-11-05 用户定；复制 2026-11-09）。
                  调用量与入库人数是社交证据，贴着主按钮；「入库」是这页唯一的主色实心按钮——
                  图标可以弱，主按钮不能弱；收藏是幽灵按钮，功能未开放，计数先写 `—`。
                  **复制是次级动作，收在主按钮右边的「⋯」里**：它不改变这个 skill 的任何东西，
                  只是造一份自己的；放到主按钮左边会与「入库」抢同一个位置。 */}
              {/* **按数据画，不按关系画**（2026-11-06 用户定）：公开且有线上版本才有这份使用数据，
                  所以自己创建的公开 skill 也看得到。私有 skill 没有数（ADR 0149 只记公开的非作者读），
                  未上线或已下线也没有可说的。 */}
              {publicRung !== null && live !== null && phase === 'online' && (
                <p className="skill-evidence" id="skillEvidence">
                  <span>{DEMO_USAGE.calls} 次调用</span>
                  <span className="skill-evidence-dot">·</span>
                  <span>{DEMO_USAGE.joins} 人入库</span>
                </p>
              )}
              {(membership === 'visible' || membership === 'joined') && (
                <span className="skill-favorite" title="收藏（功能暂未开放）">
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M12 20s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 10c0 5.65-7 10-7 10Z"
                      stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
                  </svg>
                  <span className="skill-favorite-count">—</span>
                  <span className="visually-hidden">收藏（功能暂未开放）</span>
                </span>
              )}
              {membership === 'visible' && phase === 'online' && (
                <button
                  type="button"
                  className="button skill-join"
                  id="joinSkill"
                  ref={joinButton}
                  disabled={joining}
                  onClick={() => {
                    /* **入库是一次依赖声明，不是收藏。** 先说明它是引用、不复制，再让人确认；
                       要长期独立运行的人在这一刻就知道该用「复制」。 */
                    dialogs.openDanger({
                      title: '入库这个 skill？',
                      body: '入库是把它收进你的库，不是复制一份内容。作者下线时会提前 30 天通知你，删除时也会通知你；要长期独立运行，请用「复制」留一份自己的。',
                      confirmLabel: '确认入库',
                      pendingLabel: '正在入库…',
                      danger: false,
                      onConfirm: joinLibrary,
                      returnFocus: joinButton.current,
                    })
                  }}
                  aria-label={joining ? '入库中' : '入库'}
                >
                  {joining ? (
                    <svg className="spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <circle cx="12" cy="12" r="8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeDasharray="30 20" />
                    </svg>
                  ) : (
                    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z"
                        stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
                    </svg>
                  )}
                  {joining ? '入库中…' : '入库'}
                </button>
              )}
              {membership === 'joined' && (
                <details className="skill-joined">
                  <summary className="button skill-join is-quiet is-joined">
                    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" fill="currentColor" />
                    </svg>
                    已入库
                    <svg className="skill-joined-caret" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path d="M7 10l5 5 5-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </summary>
                  <div className="skill-joined-menu">
                    <button type="button" id="removeSkill" aria-label="移出库" onClick={askRemove}>移出库</button>
                  </div>
                </details>
              )}
              {canCopy && (
                <button
                  type="button"
                  className="skill-more"
                  id="skillMore"
                  aria-label="更多操作"
                  aria-haspopup="menu"
                  aria-expanded={copyMenuOpen}
                  title="更多操作"
                  onClick={(event) => {
                    setCopyMenuTrigger(event.currentTarget)
                    setCopyMenuOpen(!copyMenuOpen)
                  }}
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <circle cx="5.5" cy="12" r="1.4" fill="currentColor" />
                    <circle cx="12" cy="12" r="1.4" fill="currentColor" />
                    <circle cx="18.5" cy="12" r="1.4" fill="currentColor" />
                  </svg>
                </button>
              )}
            </div>
          </div>

          {/* **「⋯」里的复制**：菜单本身是 portal，放在页头外面也画在同一处。 */}
          <CommandMenu
            open={copyMenuOpen}
            trigger={copyMenuTrigger}
            label="这个 skill 的更多操作"
            variant="actions"
            items={[{
              text: '复制',
              icon: (
                <svg className="menu-ico" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect x="9" y="9" width="10" height="10" rx="2" stroke="currentColor" strokeWidth="1.8" />
                  <path d="M5.5 15H5a1.5 1.5 0 0 1-1.5-1.5v-8A1.5 1.5 0 0 1 5 4h8a1.5 1.5 0 0 1 1.5 1.5V6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                </svg>
              ),
              onSelect: (trigger) => dialogs.openCopy({
                mode: 'open',
                skillName: SKILL_NAME,
                versions: copyVersions,
                initialVersion: liveCopy,
                trigger,
              }),
            }]}
            onClose={(refocus) => {
              setCopyMenuOpen(false)
              if (refocus) copyMenuTrigger?.focus()
            }}
          />


          <nav className="skill-tabs" id="skillTabs" aria-label="这个 skill 的分区">
            <a
              className={'skill-tab' + (tab === 'info' ? ' is-current' : '')}
              data-tab="info"
              href={'?page=skill' + officialQuery}
              aria-current={tab === 'info' ? 'page' : undefined}
              hidden={!openTabs.includes('info')}
            >
              <svg className="skill-tab-icon" aria-hidden="true"><use href="#infoMark" /></svg>
              <span className="skill-tab-label">信息</span>
            </a>
            <a
              className={'skill-tab' + (tab === 'files' ? ' is-current' : '')}
              data-tab="files"
              href={'?page=skill&tab=files' + officialQuery}
              aria-current={tab === 'files' ? 'page' : undefined}
              hidden={!openTabs.includes('files')}
            >
              <svg className="skill-tab-icon" aria-hidden="true"><use href="#fileMark" /></svg>
              <span className="skill-tab-label">文件</span>
            </a>
            <a
              className={'skill-tab' + (tab === 'versions' ? ' is-current' : '')}
              data-tab="versions"
              href={'?page=skill&tab=versions' + officialQuery}
              aria-current={tab === 'versions' ? 'page' : undefined}
              hidden={!openTabs.includes('versions')}
            >
              <svg className="skill-tab-icon" aria-hidden="true"><use href="#versionMark" /></svg>
              <span className="skill-tab-label">版本</span>
              <span className="skill-tab-count" id="skillVersionCount" hidden={published === 0}>
                {published > 0 ? String(published) : ''}
              </span>
            </a>
            <a
              className={'skill-tab' + (tab === 'usage' ? ' is-current' : '')}
              data-tab="usage"
              href={'?page=skill&tab=usage' + officialQuery}
              aria-current={tab === 'usage' ? 'page' : undefined}
              hidden={!openTabs.includes('usage')}
            >
              <svg className="skill-tab-icon" aria-hidden="true"><use href="#clockMark" /></svg>
              <span className="skill-tab-label">使用</span>
            </a>
            <a
              className={'skill-tab' + (tab === 'settings' ? ' is-current' : '')}
              data-tab="settings"
              href={'?page=skill&tab=settings' + officialQuery}
              aria-current={tab === 'settings' ? 'page' : undefined}
              hidden={!openTabs.includes('settings')}
            >
              <svg className="skill-tab-icon" aria-hidden="true"><use href="#settingsMark" /></svg>
              <span className="skill-tab-label">设置</span>
            </a>
          </nav>
        </header>

        <svg style={{ position: 'absolute', width: 0, height: 0, overflow: 'hidden' }} aria-hidden="true">
          <symbol id="fileMark" viewBox="0 0 24 24" fill="none">
            <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
            <path d="M14 3v5h5" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
          </symbol>
          <symbol id="descMark" viewBox="0 0 24 24" fill="none">
            <path d="M5 6h14M5 10.5h14M5 15h9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </symbol>
          <symbol id="outcomeMark" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
            <circle cx="12" cy="12" r="3.6" stroke="currentColor" strokeWidth="1.8" />
            <circle cx="12" cy="12" r="1.2" fill="currentColor" />
          </symbol>
          <symbol id="personMark" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="8.2" r="3.4" stroke="currentColor" strokeWidth="1.8" />
            <path d="M5.6 19.4c.7-3.4 3.4-5.4 6.4-5.4s5.7 2 6.4 5.4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </symbol>
          <symbol id="infoMark" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
            <path d="M12 11v5M12 8.1v.2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </symbol>
          <symbol id="settingsMark" viewBox="0 0 24 24" fill="none">
            <path d="M4 7h9M18 7h2M4 12h4M13 12h7M4 17h9M18 17h2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            <circle cx="15.5" cy="7" r="2" stroke="currentColor" strokeWidth="1.8" />
            <circle cx="10.5" cy="12" r="2" stroke="currentColor" strokeWidth="1.8" />
            <circle cx="15.5" cy="17" r="2" stroke="currentColor" strokeWidth="1.8" />
          </symbol>
          <symbol id="clockMark" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
            <path d="M12 7.4V12l3.2 1.9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </symbol>
          <symbol id="versionMark" viewBox="0 0 24 24" fill="none">
            <path d="M20.1 12.7l-7.4 7.4a1.8 1.8 0 0 1-2.5 0L4 14.1V4h10.1l6 6a1.8 1.8 0 0 1 .0 2.7z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
            <circle cx="8.4" cy="8.4" r="1.25" fill="currentColor" />
          </symbol>
          <symbol id="envMark" viewBox="0 0 24 24" fill="none">
            <path d="M4.8 7.2l4.6 4.8-4.6 4.8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M12.4 16.8h6.8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </symbol>
          <symbol id="folderMark" viewBox="0 0 24 24" fill="none">
            <path d="M3.5 18.5V6.5a1 1 0 0 1 1-1h4.2l2 2.5h8.8a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
          </symbol>
        </svg>

        <InfoPane
          hidden={tab !== 'info'}
          tier={tier}
          atLeast={atLeast}
          live={live}
          myRequest={myRequest}
          askedRung={askedRung}
          onAskedRung={setAskedRung}
          onSubmitRequest={(role) => setMyRequest({ role })}
          onWithdraw={() => setMyRequest(null)}
          category={category}
          publicTags={publicTagIds.map((id) => ({
            id,
            name: publicTagDefs.find((tag) => tag.id === id)?.name ?? '已删除的公开标签',
          }))}
          publicTagDefs={publicTagDefs}
          canEditPublicTags={atLeast('admin')}
          onPublicTagPick={pickPublicTag}
          onPublicTagCreate={createPublicTag}
          onPublicTagRemove={removePublicTag}
          myTags={myTagIds.map((id) => ({
            id,
            name: tagDefs.find((tag) => tag.id === id)?.name ?? '已删除的标签',
          }))}
          tagDefs={tagDefs}
          inLibrary={inLibrary}
          myWhenToUse={myWhenToUse}
          onMyWhenToUseChange={setMyWhenToUse}
          canEditCategories={atLeast('admin')}
          onCategoryChange={changeCategory}
          onTagPick={pickTag}
          onTagCreate={createTag}
          onTagRemove={removeTag}
          initialEdit={
            params.get('edit') === 'categories'
              ? 'categories'
              : params.get('edit') === 'public-tags'
                ? 'public-tags'
                : params.get('edit') === 'my-tags'
                  ? 'my-tags'
                  : null
          }
          membershipNote={membershipNote}
          outcome={DEMO_PROFILE.outcome}
          attributes={(DEMO_PROFILE.labels ?? [])
            /* 默认状态不上信息页：纯提示词与免费不画；运行环境是多值，每个非默认值一颗。
               `key` 带上值，避免同维度的多颗胶囊在 React 里撞键。前端只传短名与类别，
               完整语义留在词表数据里。 */
            .filter((label) => !(
              (label.key === 'runtime' && label.value === 'prompt-only') ||
              (label.key === 'pricing_type' && label.value === 'free')
            ))
            .map((label) => ({
              key: label.key + ':' + label.value,
              valueLabel: label.valueLabel,
              group: label.group,
              href: label.filterable === false
                ? undefined
                : '?page=discover&label=' + label.key + ':' + label.value,
            }))}
          origin={demoOrigin}
          license={demoLicense}
          usage={demoUsage}
          copiedFrom={
            params.get('copied') === '1'
              ? { address: 'dev/pdf-tools', version: '1.2.3', url: '?page=skill&in=visible&as=browse' }
              : null
          }
        />

        <FilesPane
          hidden={tab !== 'files'}
          atLeast={atLeast}
          params={params}
          versions={versions}
          shown={snapshot}
          here={here}
          reading={reading}
          countText={versionCount}
          address={SKILL_OWNER + '/' + SKILL_NAME}
          menuOpen={menuOpen}
          menuOwner={menuOwner}
          filesCreateRef={filesCreateRef}
          onToggleMenu={toggleMenu}
        />

        <VersionsPane
          hidden={tab !== 'versions'}
          versions={versions}
          atLeast={atLeast}
          params={params}
          menuOpen={menuOpen}
          menuOwner={menuOwner}
          versionCreateRef={versionCreateRef}
          onToggleMenu={toggleMenu}
        >
          {(target ?? versions[0]) ? (
            <VersionDetail
              /* **A version this reader cannot open still leaves the block in the document**, hidden:
                 that is the shape the flat page had, and the header's ids are part of the contract the
                 old page kept even while a 「没有这一版」 sentence was the only thing on screen. The
                 hidden copy is a structural stand-in; the visible state is `#versionMissing` below. */
              hidden={target === null}
              target={(target ?? versions[0])!}
              params={params}
              versions={versions}
              atLeast={atLeast}
              onRename={renameDraft}
              onSetNote={setNote}
              onPublish={publishDraft}
              onDiscard={discardDraft}
              onRestore={restoreVersion}
              onRepublish={republish}
              onRevoke={revokeVersion}
            />
          ) : (
            <div id="versionDetail" hidden></div>
          )}
        </VersionsPane>

        <UsagePane
          hidden={tab !== 'usage'}
          tier={tier}
          visibility={visibility}
          usage={visibility === 'private'
            ? { bucket: '3', calls: '12', lastUsed: '2026-11-05', joins: 45 }
            : demoUsage}
          versions={versions}
          scope={usageScope}
          onScope={setUsageScope}
          range={usageRange}
          onRange={setUsageRange}
          rangeOpen={params.get('range') === 'open'}
          rangeAsk={params.get('range') === 'custom'}
        />

        <SettingsPane
          hidden={tab !== 'settings'}
          sub={sub}
          atLeast={atLeast}
          canPublishPublic={official}
          params={params}
          versions={versions}
          visibility={visibility}
          publicRung={publicRung}
          phase={phase}
          pendingUntil={pendingUntil}
          joins={visibility === 'private' || params.get('no-joins') === '1' ? 0 : DEMO_USAGE.joins}
          grantees={grants.length}
          grants={grants}
          requests={requests}
          invitations={invitations}
          onVisibility={setVisibility}
          onOffline={setPhase}
          /* **共享 = 发邀请（v45）。** 它不写授权行：对方在通知中心接受之后才进名单。 */
          onAdd={(who, role) => setInvitations((list) => (
            list.some((invitation) => invitation.who === who)
              ? list.map((invitation) => (invitation.who === who ? { ...invitation, role } : invitation))
              : [...list, { who, role }]
          ))}
          onRole={(who, role) => {
            const current = grants.find((grant) => grant.who === who)
            if (!current) return
            const delta = rungIndex(role) - rungIndex(current.role)
            if (delta < 0) {
              setGrants((list) => list.map((grant) => (
                grant.who === who ? { ...grant, role } : grant
              )))
            } else if (delta > 0) {
              // Raising is new access: it becomes an invitation until the person answers.
              setInvitations((list) => [
                ...list.filter((invitation) => invitation.who !== who),
                { who, role, state: 'pending' },
              ])
            }
          }}
          onWithdraw={(who) => setGrants((list) => list.filter((grant) => grant.who !== who))}
          onWithdrawInvite={(who) => setInvitations((list) => list.filter((invitation) => invitation.who !== who))}
          onApprove={approveRequest}
          onDeny={denyRequest}
        />
      </div>

      <CommandMenu
        open={menuOpen}
        trigger={menuTrigger}
        label="新建版本"
        items={menuItems}
        onClose={(refocus) => {
          setMenuOpen(false)
          if (refocus) menuTrigger?.focus()
        }}
      />
    </section>
  )
}
