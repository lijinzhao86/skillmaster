import { useEffect, useRef, useState } from 'react'
import { go, query } from '../../address'
import { listAccessRequests } from '../../api/skills'
import type { AuthoredVersion } from '../../api/types'
import type { SignalUsage } from '../../api/signals'
import { useSkillDetail } from '../../hooks/useSkillDetail'
import { useSkillTags } from '../../hooks/useSkillTags'
import { useSkillPageRoute } from '../../hooks/useSkillPageRoute'
import { useSkillMembership } from '../../hooks/useSkillMembership'
import { useSkillAsk } from '../../hooks/useSkillAsk'
import { useSkillVersions } from '../../hooks/useSkillVersions'
import { useSkillFiles } from '../../hooks/useSkillFiles'
import { useSkillSettings } from '../../hooks/useSkillSettings'
import { useSkillImport } from '../../hooks/useSkillImport'
import { atLeast } from '../../rungs'
import { statusOf, versionLabel, versionSuffix } from '../../version'
import { useSession } from '../../store'
import { setSkillOwner } from '../../skillOwnership'
import type { EditGroup } from '../../components/skill/SkillTagGroups'
import type { CommandMenuHandle } from '../../components/CommandMenu'
import FormBanner from '../../components/FormBanner'
import SkillPageHeader from './SkillPageHeader'
import SkillSymbols from './SkillSymbols'
import InfoTab from './InfoTab'
import FilesTab from './FilesTab'
import VersionsTab from './VersionsTab'
import UsagePane from '../../components/usage/UsagePane'
import SettingsTab from './SettingsTab'
import SkillPageDialogs from './SkillPageDialogs'
import {
  countedVersionsOf,
  detailRouteOf,
  detailVersionOf,
  fileVersionStateOf,
  liveLabelOf,
  liveVersionOf,
  visibleVersionsOf,
} from './versionSelection'
import { detailRowsOf } from './skillDetails'

/** The page head's one line of evidence: call bucket · join count, or the empty sentence. */
function evidenceLine(usage: SignalUsage | null): string {
  const calls = usage?.calls_bucket ?? null
  const joins = usage?.joins ?? 0
  if (calls === null && joins === 0) {
    return '还没有人用过'
  }
  const parts: string[] = []
  if (calls !== null) {
    parts.push(calls + ' 次调用')
  }
  if (joins > 0) {
    parts.push(joins + ' 人入库')
  }
  return parts.join(' · ')
}

/**
 * One skill, in the four sections the design gives it: 信息, 文件, 版本, 设置.
 *
 * **The page is drawn to the caller's rung** (ADR 0051). 信息 is open to everybody, 文件 needs
 * `browse`, drafts in 版本 need `write`, and 设置 is `admin`; anything a rung cannot reach is not
 * drawn at all rather than drawn greyed — the design's own rule ("够不到的东西不画"). The server
 * says which rung this is, so the page never has to probe a write route for a 403.
 *
 * **Every section is an address.** `?tab=` chooses the section, `?sub=` the settings sub-page, and
 * the file being read is a path under the skill (`/files/…`), which is the same spelling the server
 * advertises. A listing, a version's files, or one file is therefore something somebody can send to
 * somebody else.
 *
 * **There is no banner and no second screen for a draft.** What may be done to a version is said on
 * that version's row, and the row is where the action is. A banner's problem is that it disappears
 * when there is no draft — so 上线 only exists when you happen to be waiting on one.
 *
 * **What changed in the port.** The address is read once, in `useState` initialisers, exactly where
 * Vue's `setup` read it: `?tab=`, the version route and the settings sub-page are the page's opening
 * state, and the only writes afterwards are `replaceState` for the version list's filter. The two
 * `watch`es that corrected a stale choice (`tabs`, `above`) became effects guarded so they do not
 * fire on mount, which is what Vue's `watch` does.
 *
 * **The split.** This file is the state machine and the composition. The route lives in
 * {@link useSkillPageRoute}, the versions in {@link useSkillVersions}, the files in
 * {@link useSkillFiles}, settings and the ask block in their own hooks, and every section is a tab
 * component. The readings they share are pure modules under this folder.
 */
export interface SkillPageProps {
  namespace: string
  name: string
  version?: string
  /** A file being read, from `/skills/…/files/…`. Opens the files section with it in view. */
  relpath?: string
  /** A comparison, from `/skills/…/diff?from=&to=`. Opens the versions section with it above the table. */
  from?: string
  to?: string
}

export default function SkillPage({
  namespace,
  name,
  version: pathVersion,
  relpath,
  from,
  to,
}: SkillPageProps) {
  const detail = useSkillDetail()
  const { skill, state } = detail
  const { account } = useSession()

  const route = useSkillPageRoute({
    namespace,
    name,
    pathVersion,
    relpath,
    from,
    to,
    skill,
  })
  const {
    versionRoute,
    legacyDiff,
    rung,
    canWrite,
    canMaintain,
    canAdmin,
    tabs: reachableTabs,
    tab: askedTab,
    sub,
    parts,
    baseAddress,
    tabHref,
    subHref,
  } = route

  /* Read once the skill is here, on **every** tab: the join/remove action lives in the shared page
     head, not in 信息's body, so it must not wait for that section to be opened. */
  const skillTags = useSkillTags(namespace, name, state === 'ready', detail.setBanner)

  useEffect(() => {
    skillTags.startIfNeeded()
    // The hook asks once, when the information section has a skill to ask about.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [skillTags.startIfNeeded])

  const params = useRef(query()).current
  const membership = useSkillMembership({
    skillTags,
    params,
    offlineAt: skill?.offline_at ?? null,
  })

  /* **版本 is a library section** (2026-11-05): a public skill the caller has not joined shows 信息
     only. An owner or a collaborator is in the library by definition, which is why `browse` opens it
     too. The library facts load right after the skill, so the tab appears one read later for a
     joined skill. */
  const inLibrary = membership.library !== null && membership.library.source !== null
  /* **Until the library facts arrive, 版本 stays** — the tab is optimistic for one read, then it
     disappears for a public skill the caller has not joined. The alternative (hide it first and add
     it back) unmounts the version pane and its comparison, which the address may have opened. */
  const tabs = reachableTabs.filter(
    (entry) => entry.key !== 'versions' || inLibrary || membership.library === null,
  )
  const tab = askedTab === 'versions' && !tabs.some((entry) => entry.key === 'versions')
    ? 'info'
    : askedTab

  /* **The shell lights 发现 or 我的 skill from this** (2026-11-05): the relationship is a library
     fact, so it is only known after the groups read. Until then neither item is lit. */
  useEffect(() => {
    if (skill === null || state !== 'ready') {
      return
    }
    setSkillOwner(
      membership.library === null
        ? null
        : membership.library.source === null
          ? 'discover'
          : 'skills',
    )
    return () => setSkillOwner(null)
  }, [skill, state, membership.library])

  const ask = useSkillAsk({
    namespace,
    name,
    skill,
    rung,
    inLibrary: membership.library !== null && membership.library.source !== null &&
      membership.library.active,
    setBanner: detail.setBanner,
  })

  /* --------------------------------------------------------------- the loaded skill */

  const selected: AuthoredVersion | null = skill?.version ?? null

  /** The version consumers get. Null is an ordinary state: a skill nobody has published from yet. */
  const liveVersion = liveVersionOf(skill)

  /** Every version the server sent this rung. The server already applies the draft/revoked ladder. */
  const allVersions: AuthoredVersion[] = skill?.versions ?? []

  const visibleVersions = visibleVersionsOf(allVersions, canWrite, canMaintain)
  const countedVersions = countedVersionsOf(visibleVersions)

  /* ---------------------------------------------------------------- 复制 (v54) */

  /* **Who may copy: browse or above, not the owner, and at least one readable published version.**
     A private skill shared with me counts — copying is library to library, not public to private;
     an offline skill, a dormant entry, or a skill with nothing published draws no 「⋯」. Drafts,
     discarded and revoked rows are not choices: they are either not a complete version to take, or
     the author has taken it back. */
  const copyableVersions = visibleVersions.filter((candidate) => {
    const status = statusOf(candidate)
    return status === 'live' || status === 'history'
  })
  const canCopy =
    skill !== null &&
    rung !== 'owner' &&
    atLeast(rung, 'browse') &&
    skill.offline_at === null &&
    copyableVersions.length > 0 &&
    membership.library !== null &&
    (membership.library.source === null || membership.library.active)
  const copyVersions = copyableVersions.map((candidate) => {
    const suffix = versionSuffix(candidate)
    const label =
      candidate.name ?? candidate.draft_name ?? candidate.code ?? versionLabel(suffix)
    return {
      value: suffix,
      label: statusOf(candidate) === 'live' ? `线上 · ${label}` : label,
    }
  })
  const copyInitialVersion =
    copyVersions.find((entry) => entry.label.startsWith('线上 · '))?.value ??
    copyVersions[0]?.value ??
    ''
  const [copyOpen, setCopyOpen] = useState(false)
  /* The trigger the sheet hangs from: closing it (cancel, Esc, 留在当前页) puts focus back there,
     so a keyboard user does not fall to the body and re-tab the whole page. */
  const copyMoreRef = useRef<CommandMenuHandle | null>(null)
  const closeCopy = (): void => {
    setCopyOpen(false)
    copyMoreRef.current?.focusTrigger()
  }
  const detailRoute = detailRouteOf(
    legacyDiff,
    versionRoute,
    to,
    pathVersion,
    liveVersion,
    from,
  )
  const detailVersion = detailVersionOf(detailRoute, visibleVersions)
  const fileVersions = fileVersionStateOf(
    allVersions,
    visibleVersions,
    pathVersion,
    liveVersion,
    rung,
  )

  const frontmatter: Record<string, unknown> = skill?.frontmatter ?? {}

  // **The server lifted these two fields** (TD 附录 A): one extraction rule, read by `skill show`
  // and this page alike. Parsing the author's document here would be the second copy of the rule,
  // and the two already disagreed on a present-but-empty `metadata.when_to_use`.
  const whenToUse: string = skill?.when_to_use ?? ''
  const compatibility: string = skill?.compatibility ?? ''

  const liveLabel = liveLabelOf(liveVersion)
  const versionCount = countedVersions.length

  /* **The evidence line is data, not a relationship** (2026-11-06): a public skill with a live
     version has it — an author's own skill included — while a private, never-published or
     offline one has nothing to say. The number is a bucket, never an exact call count. */
  const evidence =
    skill !== null && skill.visibility === 'public' && liveVersion !== null && skill.offline_at == null
      ? evidenceLine(skill.usage ?? null)
      : null
  const library = membership.library
  const showFavorite =
    library !== null &&
    ((library.source === null && library.joinable) ||
      (library.source === 'joined' && library.active))
  const detailRows = detailRowsOf(skill, liveVersion, selected, frontmatter)

  const versions = useSkillVersions({
    versionRoute,
    visibleVersions,
    rung,
    liveVersion,
    detailRoute,
    parts,
  })

  const files = useSkillFiles({
    namespace,
    name,
    skill,
    relpath,
    params,
    parts,
    tab,
    rung,
    shownVersion: fileVersions.shownVersion,
    fileTabVersion: fileVersions.fileTabVersion,
  })

  const settings = useSkillSettings({ namespace, name, skill, setBanner: detail.setBanner })
  const importState = useSkillImport({ parts })

  const askedEdit = params.get('edit')
  const initialEdit: EditGroup | null =
    askedEdit === 'categories' || askedEdit === 'public-tags' || askedEdit === 'my-tags'
      ? askedEdit
      : null

  useEffect(() => {
    // The file tab reads the snapshot its path pins; the version tab reads the whole list so a bad
    // pin can be answered with 「没有这一版」 rather than 「没有这个 skill」. The legacy `/diff` alias
    // still pins its target (`?to=` or the path's `@`), because its comparison is about that version.
    /* The address's section, not the effective one: on the first render the library facts are not
       back yet, so `tab` may still be the 信息 fallback — but a legacy diff address already says
       which version it means. */
    const loadPin =
      askedTab === 'versions' ? (legacyDiff ? (to ?? pathVersion) : undefined) : pathVersion
    void detail.load(namespace, name, loadPin)
    // The address is fixed for the life of the page: every other navigation is a full load.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /**
    * The file's bytes are read once the skill that names them is here.
    *
    * **Not from the mount effect.** `loadFile` decides from `openFile` and `shownVersion`, and at
    * mount the skill is still null — the effect would close over that first render's nulls and read
    * nothing. Vue read the same values through its refs and never met the problem.
    */
  useEffect(() => {
    if (state !== 'ready') {
      return
    }
    let cancelled = false
    void (async () => {
      await files.loadFile()
      if (cancelled) {
        return
      }
      // The 协作者 sub-page hands its own queue size up instead — one read, two views.
      if (skill !== null && atLeast(skill.rung, 'admin') && !(tab === 'settings' && sub === 'collaborators')) {
        const result = await listAccessRequests(namespace, name)
        if (!cancelled && result.ok) {
          settings.setRequestCount(result.data.total)
        }
      }
    })()
    return () => {
      cancelled = true
    }
    // `state` is the trigger; the rest are the values Vue read through refs at the same moment.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state])

  return (
    <section className="page">
      <FormBanner message={detail.banner} />

      {state === 'missing' ? (
        <div className="notfound">
          <h2>没有这个 skill</h2>
          {/* **Same screen, several reasons.** The address may name nothing, name a skill that was
              deleted, or name one this person cannot reach. The 404 is one answer on purpose, so the
              sentence says only what is true of all of them. */}
          <p className="hint">这个地址下没有你能打开的 skill，也可能它已经被删除了。</p>
          <p>
            <a href="/">回到我的 skill</a>　·　<a href="/?show=deleted">查看已删除的 skill</a>
          </p>
        </div>
      ) : state === 'loading' ? (
        <p className="loading">加载中…</p>
      ) : skill !== null ? (
        <>
          <SkillPageHeader
            skill={skill}
            crumbs={files.crumbs}
            canAdmin={canAdmin}
            tabs={tabs}
            tab={tab}
            versionCount={versionCount}
            library={membership.library}
            joining={membership.joining}
            evidence={evidence}
            showFavorite={showFavorite}
            canCopy={canCopy}
            onCopy={() => setCopyOpen(true)}
            moreRef={copyMoreRef}
            onJoin={membership.joinLibrary}
            removeButton={membership.removeButton}
            onAskRemove={membership.askRemove}
            homeHref={baseAddress(null)}
            tabHref={tabHref}
            subHref={subHref}
          />

          <SkillSymbols />

          {tab === 'info' ? (
            <InfoTab
              skill={skill}
              whenToUse={whenToUse}
              compatibility={compatibility}
              liveLabel={liveLabel}
              canAdmin={canAdmin}
              groups={skillTags.groups}
              categories={skillTags.categories}
              myTagDefs={skillTags.myTagDefs}
              publicTagDefs={skillTags.publicTagDefs}
              initialEdit={initialEdit}
              membershipNote={membership.membershipNote}
              onLoadMyTagDefs={skillTags.loadMyTagDefs}
              onLoadPublicTagDefs={skillTags.loadPublicTagDefs}
              onCategoryChange={(key) => void skillTags.changeCategory(key)}
              onPublicTagAdd={(tagName) => void skillTags.addPublic(tagName)}
              onPublicTagRemove={(tagName) => void skillTags.removePublic(tagName)}
              onMyTagAdd={(tagName) => void skillTags.addMine(tagName)}
              onMyTagRemove={(tagName) => void skillTags.removeMine(tagName)}
              showAsk={ask.showAsk}
              askWhy={ask.askWhy}
              pendingRung={ask.pendingRung}
              askedRung={ask.askedRung}
              above={ask.above}
              askNote={ask.askNote}
              askLabel={ask.askLabel}
              onAskedRungChange={ask.setAskedRung}
              onSubmitAsk={ask.submitAsk}
              onWithdrawAsk={ask.withdrawAsk}
              origin={skill.origin ?? null}
              license={skill.license ?? null}
              copiedFrom={skill.copied_from ?? null}
            />
          ) : tab === 'files' ? (
            <FilesTab
              skill={skill}
              levelEntries={files.levelEntries}
              openRelpath={files.openRelpath}
              openFile={files.openFile}
              openIsMarkdown={files.openIsMarkdown}
              fileContent={files.fileContent}
              readingFile={files.readingFile}
              fileProblem={files.fileProblem}
              pickerVersions={fileVersions.pickerVersions}
              fileTabVersion={fileVersions.fileTabVersion}
              disabledPickerValues={fileVersions.disabledPickerValues}
              versionCount={versionCount}
              onPickVersion={files.onPickVersion}
              fileDownloadHref={files.fileDownloadHref}
              canWrite={canWrite}
              filesMenu={importState.filesMenu}
              filesCreateItems={importState.filesCreateItems}
              fileHref={files.fileHref}
              dirHref={files.dirHref}
              commitVersionHref={files.commitVersionHref}
              commitVersionLabel={files.commitVersionLabel}
            />
          ) : tab === 'versions' ? (
            <VersionsTab
              namespace={namespace}
              name={name}
              rung={rung}
              username={account?.username ?? null}
              detailRoute={detailRoute}
              detailVersion={detailVersion}
              visibleVersions={visibleVersions}
              liveVersion={liveVersion}
              backToListHref={versions.backToListHref}
              onSelectBaseline={versions.selectBaseline}
              filterBar={importState.filterBar}
              filter={versions.filter}
              query={versions.query}
              newHref={`${baseAddress(null)}/new`}
              onUpdateFilter={versions.setFilter}
              onUpdateQuery={versions.setQuery}
              onOpenImport={importState.openImportFromFilter}
              matchedVersions={versions.matchedVersions}
              listQueryString={versions.listQueryString}
              hasBaselineFor={versions.hasBaselineFor}
              onClearFilters={versions.clearFilters}
            />
          ) : tab === 'usage' ? (
            <UsagePane skill={skill} namespace={namespace} name={name} />
          ) : tab === 'settings' ? (
            <SettingsTab
              skill={skill}
              sub={sub}
              subHref={subHref}
              requestCount={settings.requestCount}
              setRequestCount={settings.setRequestCount}
              rung={rung}
              visibilityPane={settings.visibilityPane}
              visibilityChoice={settings.visibilityChoice}
              onChangeVisibility={(choice) => void settings.changeVisibility(choice)}
              offline={settings.offline}
              offlineButton={settings.offlineButton}
              onAskOffline={settings.askOffline}
              deleteButton={settings.deleteButton}
              onAskDelete={settings.askDelete}
              detailRows={detailRows}
            />
          ) : null}
        </>
      ) : null}

      <SkillPageDialogs
        publicBrowseOpen={settings.publicBrowseOpen}
        publicBrowsePending={settings.publicBrowsePending}
        publicBrowseError={settings.publicBrowseError}
        onAcceptPublicBrowse={settings.acceptPublicBrowse}
        onCancelPublicBrowse={settings.cancelPublicBrowse}
        offline={settings.offline}
        offlineOpen={settings.offlineOpen}
        offlinePending={settings.offlinePending}
        offlineError={settings.offlineError}
        onConfirmOffline={settings.confirmOffline}
        onCancelOffline={settings.cancelOffline}
        removeOpen={membership.removeOpen}
        removePending={membership.removePending}
        onConfirmRemove={membership.confirmRemove}
        onCancelRemove={membership.cancelRemove}
        deleteOpen={settings.deleteOpen}
        deletePending={settings.deletePending}
        deleteError={settings.deleteError}
        onConfirmDelete={settings.confirmDelete}
        onCancelDelete={settings.cancelDelete}
        namespace={namespace}
        name={name}
        copyOpen={copyOpen}
        copyVersions={copyVersions}
        copyInitialVersion={copyInitialVersion}
        onCloseCopy={closeCopy}
        importOpen={importState.importOpen}
        importRung={skill?.rung ?? 'browse'}
        onCloseImport={importState.closeImport}
        onNavigate={go}
      />
    </section>
  )
}
