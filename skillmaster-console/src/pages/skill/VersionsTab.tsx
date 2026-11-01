import type { RefObject } from 'react'
import type { AuthoredVersion, Standing } from '../../api/types'
import { versionSuffix, type VersionFilterKey, type VersionRoute } from '../../version'
import VersionDetail from '../../components/version/VersionDetail'
import VersionFilterBar from '../../components/version/VersionFilterBar'
import type { VersionFilterBarHandle } from '../../components/version/VersionFilterBar'
import VersionRow from '../../components/version/VersionRow'

/**
 * 版本: one route, two states — the list, or the version whose pin the query carries.
 *
 * A pin naming nothing this rung can see gets one answer, with a way back and no leak. The list's
 * rows, chips and count all read the same visible set, so they cannot disagree.
 */
export interface VersionsTabProps {
  namespace: string
  name: string
  rung: Standing
  username: string | null
  detailRoute: VersionRoute
  detailVersion: AuthoredVersion | null
  visibleVersions: AuthoredVersion[]
  liveVersion: AuthoredVersion | null
  backToListHref: string
  onSelectBaseline: (pin: string) => void
  filterBar: RefObject<VersionFilterBarHandle | null>
  filter: VersionFilterKey
  query: string
  newHref: string
  onUpdateFilter: (value: VersionFilterKey) => void
  onUpdateQuery: (value: string) => void
  onOpenImport: () => void
  matchedVersions: AuthoredVersion[]
  listQueryString: string
  hasBaselineFor: (version: AuthoredVersion) => boolean
  onClearFilters: () => void
}

export default function VersionsTab({
  namespace,
  name,
  rung,
  username,
  detailRoute,
  detailVersion,
  visibleVersions,
  liveVersion,
  backToListHref,
  onSelectBaseline,
  filterBar,
  filter,
  query,
  newHref,
  onUpdateFilter,
  onUpdateQuery,
  onOpenImport,
  matchedVersions,
  listQueryString,
  hasBaselineFor,
  onClearFilters,
}: VersionsTabProps) {
  return (
    <div className="tab-panel" data-tab="versions">
      {/* **One route, two states.** A version pin in the query switches the tab from the list
          to that version's own page; without one the list is what the tab is. A pin naming
          nothing this rung can see gets one answer, with a way back and no leak. */}
      {detailRoute.version !== undefined && detailVersion !== null ? (
        <VersionDetail
          namespace={namespace}
          name={name}
          version={detailVersion}
          versions={visibleVersions}
          rung={rung}
          username={username}
          live={liveVersion}
          route={detailRoute}
          onSelectBaseline={onSelectBaseline}
        />
      ) : detailRoute.version !== undefined ? (
        <div id="versionMissing">
          <div className="notfound">
            <h2>没有这一版</h2>
            <p className="hint">
              这个地址下没有你能打开的版本：它可能已经被丢弃，也可能属于一个你看不到的 skill。
            </p>
            <p>
              <a href={backToListHref}>回到版本列表</a>
            </p>
          </div>
        </div>
      ) : (
        <div id="versionListWrap">
          <VersionFilterBar
            controlRef={filterBar}
            versions={visibleVersions}
            filter={filter}
            query={query}
            rung={rung}
            newHref={newHref}
            onUpdateFilter={onUpdateFilter}
            onUpdateQuery={onUpdateQuery}
            onOpenImport={onOpenImport}
          />
          <div className="file-table version-list-table">
            <ul className="versions">
              {matchedVersions.map((candidate) => (
                <VersionRow
                  key={versionSuffix(candidate)}
                  version={candidate}
                  namespace={namespace}
                  name={name}
                  rung={rung}
                  username={username}
                  listQuery={listQueryString}
                  hasBaseline={hasBaselineFor(candidate)}
                />
              ))}
            </ul>
            {matchedVersions.length === 0 ? (
              <p className="version-empty">
                没有匹配的版本。
                <button type="button" className="link-button" onClick={onClearFilters}>
                  清除筛选
                </button>
              </p>
            ) : null}
          </div>
        </div>
      )}
    </div>
  )
}
