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
import VersionDetail from '../components/skill/VersionDetail'
import type { AtLeast, Grant, Sub, Version, Visibility } from '../fixtures/skill'
import { CATEGORIES, LIBRARY, MY_TAGS, PUBLIC_TAGS, membershipOf, tierForMembership } from '../fixtures/library'
import type { Membership } from '../fixtures/library'
import {
  BASE_VERSIONS,
  DEMO_NOW,
  GRANTS,
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
  const [myRequest, setMyRequest] = useState<{ role: string } | null>(() => {
    const asked = params.get('ask')
    return asked && rungIndex(asked) > rungIndex(tier) ? { role: asked } : null
  })
  const [askedRung, setAskedRung] = useState<string | null>(null)
  const [offline, setOfflineState] = useState(() => params.get('offline') === '1')

  /* ------------------------------------------------------- 库与两套标签 (v14) */

  const [membership, setMembership] = useState<Membership>(askedMembership)
  const [joining, setJoining] = useState(false)
  const inLibrary = membership === 'created' || membership === 'shared' || membership === 'joined'
  /* 默认是「文档与演示」；地址里的 `category=` 让演示能落在别的货架上——包括停用词
     （停用的词不能新增，但已经贴在它上面的仍然保留）。 */
  const [category, setCategory] = useState<string | null>(() => {
    const asked = params.get('category')
    return asked !== null && CATEGORIES.some((entry) => entry.id === asked) ? asked : 'doc'
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
      body: '标签会保留，重新加入后会回来。这个 skill 仍在公域，仍可直接调用。',
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
    ? (offline ? '已下线，暂时不能加入。' : '还没有上线的版本，暂时不能加入。')
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

  const openTabs = ['info']
    .concat(atLeast('browse') ? ['files'] : [])
    .concat(['versions'])
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

  const askedVis = params.get('vis')
  const visibility: Visibility = (VISIBILITIES as readonly string[]).includes(askedVis ?? '')
    ? (askedVis as Visibility)
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

  /* 技能级下线：改 `skill.offline_at`，不改任何版本. */
  const setOffline = (next: boolean) => {
    setOfflineState(next)
    const query = new URLSearchParams(params)
    query.set('page', 'skill')
    query.set('tab', 'settings')
    query.set('sub', 'general')
    if (next) query.set('offline', '1')
    else query.delete('offline')
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
            <span className="avatar" aria-hidden="true">d</span>
            <div className="skill-who">
              <div className="skill-name-row">
                <h1><span className="ns">dev /</span> <a className="name" href="?page=skill">pdf-tools</a><span className="crumb" id="skillCrumb">{crumb}</span></h1>
                <a
                  className="chip is-plain"
                  id="skillBadge"
                  href="?page=skill&tab=settings&sub=general"
                  hidden={!atLeast('admin')}
                >
                  {atLeast('browse') && publicRung !== null ? '公开' : '私有'}
                </a>
                <span className="chip is-offline" id="skillOfflineBadge" hidden={!offline}>已下线</span>
              </div>
              <p className="skill-title" hidden={state === 'no-title'}>把 PDF 拆页、合并与压缩。</p>
            </div>
            {/* **加入 / 移除在页头，和仓库页的 Star 同一个位置。** 它是我这边的一条关系，
                不是这个 skill 的属性；申请在正文下方，两者因此不会读成同一件事。 */}
            <div className="skill-ident-actions">
              {membership === 'visible' && (
                <button type="button" className="button" id="joinSkill" disabled={joining} onClick={joinLibrary}>
                  {joining ? '加入中…' : '加入'}
                </button>
              )}
              {membership === 'joined' && (
                <button type="button" className="button is-quiet" id="removeSkill" onClick={askRemove}>
                  移除
                </button>
              )}
            </div>
          </div>

          <nav className="skill-tabs" id="skillTabs" aria-label="这个 skill 的分区">
            <a
              className={'skill-tab' + (tab === 'info' ? ' is-current' : '')}
              data-tab="info"
              href="?page=skill"
              aria-current={tab === 'info' ? 'page' : undefined}
              hidden={!openTabs.includes('info')}
            >
              <svg className="skill-tab-icon" aria-hidden="true"><use href="#infoMark" /></svg>
              <span className="skill-tab-label">信息</span>
            </a>
            <a
              className={'skill-tab' + (tab === 'files' ? ' is-current' : '')}
              data-tab="files"
              href="?page=skill&tab=files"
              aria-current={tab === 'files' ? 'page' : undefined}
              hidden={!openTabs.includes('files')}
            >
              <svg className="skill-tab-icon" aria-hidden="true"><use href="#fileMark" /></svg>
              <span className="skill-tab-label">文件</span>
            </a>
            <a
              className={'skill-tab' + (tab === 'versions' ? ' is-current' : '')}
              data-tab="versions"
              href="?page=skill&tab=versions"
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
              className={'skill-tab' + (tab === 'settings' ? ' is-current' : '')}
              data-tab="settings"
              href="?page=skill&tab=settings"
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

        <SettingsPane
          hidden={tab !== 'settings'}
          sub={sub}
          atLeast={atLeast}
          params={params}
          versions={versions}
          visibility={visibility}
          publicRung={publicRung}
          offline={offline}
          grants={grants}
          requests={requests}
          onVisibility={setVisibility}
          onOffline={setOffline}
          onAdd={(who, role) => setGrants((list) => [...list, { who, role }])}
          onRole={(who, role) => setGrants((list) => list.map((grant) => (
            grant.who === who ? { ...grant, role } : grant
          )))}
          onWithdraw={(who) => setGrants((list) => list.filter((grant) => grant.who !== who))}
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
