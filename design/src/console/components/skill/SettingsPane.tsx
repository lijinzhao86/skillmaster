/**
 * 设置: a column of second-level items, one line each, and the one being set beside it.
 *
 * No grouping and no delete in the nav: there are only three items, and a group label over three
 * items makes small unclickable words. The danger actions live at the bottom of 常规 instead.
 */

import type { AtLeast, Grant, Sub, Version, Visibility } from '../../fixtures/skill'
import {
  DIGEST,
  FRONTMATTER,
  SUBS,
  liveVersion,
} from '../../fixtures/skill'
import CollaboratorsPane from './CollaboratorsPane'
import GeneralPane from './GeneralPane'
import { FactRow } from './parts'

export interface SettingsPaneProps {
  hidden: boolean
  sub: Sub
  atLeast: AtLeast
  params: URLSearchParams
  versions: Version[]
  visibility: Visibility
  publicRung: string | null
  offline: boolean
  grants: Grant[]
  requests: Grant[]
  onVisibility: (value: Visibility) => void
  onOffline: (next: boolean) => void
  onAdd: (who: string, role: string) => void
  onRole: (who: string, role: string) => void
  onWithdraw: (who: string) => void
  onApprove: (index: number, role: string) => void
  onDeny: (index: number) => void
}

const LABELS: Record<Sub, string> = {
  general: '常规',
  collaborators: '协作者',
  details: '详细信息',
}

export default function SettingsPane({
  hidden,
  sub,
  atLeast,
  params,
  versions,
  visibility,
  publicRung,
  offline,
  grants,
  requests,
  onVisibility,
  onOffline,
  onAdd,
  onRole,
  onWithdraw,
  onApprove,
  onDeny,
}: SettingsPaneProps) {
  const browse = atLeast('browse')
  const live = liveVersion(versions)
  const subHref = (name: Sub) =>
    '?page=skill&tab=settings&sub=' + name +
    (browse && visibility !== 'private' ? '&vis=' + visibility : '')

  return (
    <div className="tab-panel" data-tab="settings" hidden={hidden}>
      <div className="settings">
        <nav className="settings-nav" aria-label="设置的分区">
          {SUBS.map((name) => (
            <a
              key={name}
              className={'settings-item' + (name === sub ? ' is-current' : '')}
              data-sub={name}
              href={subHref(name)}
              aria-current={name === sub ? 'page' : undefined}
            >
              {LABELS[name]}
              {name === 'collaborators' && (
                <span className="count" id="collabCount" hidden={!browse || requests.length === 0}>
                  {browse ? String(requests.length) : ''}
                </span>
              )}
            </a>
          ))}
        </nav>

        <div className="settings-body">
          <CollaboratorsPane
            hidden={sub !== 'collaborators'}
            enabled={browse}
            params={params}
            grants={grants}
            requests={requests}
            publicRung={publicRung}
            onAdd={onAdd}
            onRole={onRole}
            onWithdraw={onWithdraw}
            onApprove={onApprove}
            onDeny={onDeny}
          />

          <GeneralPane
            hidden={sub !== 'general'}
            atLeast={atLeast}
            visibility={visibility}
            offline={offline}
            onVisibility={onVisibility}
            onOffline={onOffline}
          />

          <section className="settings-pane" data-sub="details" hidden={sub !== 'details'}>
            <h2>详细信息</h2>
            <p className="hint">这些是 SKILL.md 中声明的字段。调用者在「信息」页只能看到描述、适用场景、运行环境与版本。</p>
            <dl className="info-rows" id="detailRows">
              {browse && <DetailRows live={live} />}
            </dl>
          </section>
        </div>
      </div>
    </div>
  )
}

function DetailRows({ live }: { live: Version | null }) {
  const rest = Object.keys(FRONTMATTER.metadata)
    .filter((key) => key !== 'when_to_use')
    .map((key) => key + ': ' + FRONTMATTER.metadata[key])
  return (
    <>
      <FactRow label="名字" value={FRONTMATTER.name} mono />
      <FactRow label="版本" value={live ? (live.name ?? '') + ' · ' + DIGEST : '未上线'} mono />
      <FactRow label="许可" value={FRONTMATTER.license} mono />
      <FactRow label="allowed-tools" value={FRONTMATTER['allowed-tools']} mono />
      <FactRow label="metadata" value={rest.join('\n')} mono />
    </>
  )
}
