/**
 * The details sub-page's rows: the fields `SKILL.md` declares, for the author to check.
 *
 * Framework-free — these are readings of the frontmatter the server lifted, not page state.
 */

import type { AuthoredSkill, AuthoredVersion } from '../../api/types'
import { versionLabel, versionSuffix } from '../../version'

export function metadataRestOf(frontmatter: Record<string, unknown>): string {
  const metadata = frontmatter.metadata
  if (typeof metadata !== 'object' || metadata === null) {
    return ''
  }
  return Object.entries(metadata as Record<string, unknown>)
    .filter(([key]) => key !== 'when_to_use')
    .map(([key, value]) => `${key}: ${String(value)}`)
    .join('\n')
}

export interface DetailRow {
  label: string
  value: string
  mono: boolean
}

export function detailRowsOf(
  skill: AuthoredSkill | null,
  liveVersion: AuthoredVersion | null,
  selected: AuthoredVersion | null,
  frontmatter: Record<string, unknown>,
): DetailRow[] {
  const version = liveVersion ?? selected
  return [
    { label: '名字', value: skill?.name ?? '', mono: true },
    {
      label: '版本',
      value:
        version === null
          ? ''
          : `@${versionLabel(versionSuffix(version))} · ${version.digest}`,
      mono: true,
    },
    {
      label: '许可',
      value: typeof frontmatter.license === 'string' ? (frontmatter.license as string) : '',
      mono: true,
    },
    {
      label: 'allowed-tools',
      value:
        typeof frontmatter['allowed-tools'] === 'string'
          ? (frontmatter['allowed-tools'] as string)
          : '',
      mono: true,
    },
    { label: 'metadata', value: metadataRestOf(frontmatter), mono: true },
  ].filter((row) => row.value !== '')
}
