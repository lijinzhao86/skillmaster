import type { AuthoredVersion } from '../../api/types'
import { canMaintain } from '../../versionActions'
import { statusOf } from '../../version'

/**
 * What this version's state means for the people calling it — said before any action.
 *
 * The status chip says *where in its life* the version is; this strip says *what that does*: whether
 * it can be read, and what replaces it. The live state draws no strip at all — the chip already says
 * it is live, and "callers pinning an old version are unaffected" is a consequence of the
 * publish/rollback moment, written in those actions' own explanations.
 */
export interface Props {
  version: AuthoredVersion
  live: AuthoredVersion | null
  rung: string
}

export default function VersionStatusBar({ version, live, rung }: Props) {
  const status = statusOf(version)
  let info: { tone: string; text: string; reason?: string } | null = null

  if (status === 'draft') {
    let text = '这个版本还没有上线，调用者读不到它。上线时才会分配版本号，之后可以按 @版本 调用。'
    if (!canMaintain(rung)) {
      text += '上线需要「维护」档位。'
    }
    info = { tone: 'is-draft', text }
  } else if (status === 'revoked') {
    info = {
      tone: 'is-danger',
      text: '指定这个版本的调用会失败（410）。它仍对你可见，可以重新上架。',
      reason: version.revoked_reason ?? '',
    }
  } else if (status === 'discarded') {
    info = { tone: 'is-plain', text: '这个版本从未上线，也不会再上线；它只在作者面留作记录。' }
  } else if (status !== 'live') {
    info = {
      tone: 'is-plain',
      text: `指定 ${version.name === null ? '这一版' : `@${version.name}`} 的调用仍可读取；不指定版本的调用现在读到 ${
        live?.name === null || live?.name === undefined ? '另一版' : `@${live.name}`
      }。`,
    }
  }

  if (info === null) {
    return null
  }

  return (
    <div className={`vp-status ${info.tone}`}>
      <span className="vp-status-dot" aria-hidden="true" />
      <p>
        {info.text}
        {info.reason !== undefined && info.reason !== '' ? (
          <span className="vp-status-reason">原因：{info.reason}</span>
        ) : null}
      </p>
    </div>
  )
}
