/**
 * 文件: one level of the tree at a time, and under it whichever file is being read.
 *
 * Both live in the address — `path` is the directory being listed, `file` is the file being read —
 * so a listing, or a single file, is a thing somebody can send to somebody else. The two never
 * appear together: a file carries its own directory inside its path.
 *
 * **The version row belongs to this table, not to the page.** It chooses which snapshot these
 * files are — a question that means nothing on 版本 or 设置.
 */

import type { RefObject } from 'react'
import SkillMenu from './SkillMenu'
import type { AtLeast, Version } from '../../fixtures/skill'
import {
  TREE,
  encodePath,
  liveVersion,
  versionDetailAddress,
  versionMenuOption,
  visibleVersions,
} from '../../fixtures/skill'
import { go } from '../../address'

export interface FilesPaneProps {
  hidden: boolean
  atLeast: AtLeast
  params: URLSearchParams
  versions: Version[]
  shown: Version | null
  here: string
  reading: string | null
  countText: string
  address: string
  menuOpen: boolean
  menuOwner: 'files' | 'versions' | null
  filesCreateRef: RefObject<HTMLButtonElement | null>
  onToggleMenu: (owner: 'files' | 'versions') => void
}

export default function FilesPane({
  hidden,
  atLeast,
  params,
  versions,
  shown,
  here,
  reading,
  countText,
  address,
  menuOpen,
  menuOwner,
  filesCreateRef,
  onToggleMenu,
}: FilesPaneProps) {
  const browse = atLeast('browse')
  const live = liveVersion(versions)
  const pinnedName = params.get('version')
  let pinned: Version | null = null
  if (pinnedName) {
    for (const version of versions) {
      if ((version.name !== null && version.name === '@' + pinnedName) || version.pin === pinnedName) {
        pinned = version
      }
    }
  }

  const readable = visibleVersions(versions, atLeast).filter((version) => version.state !== 'discarded')
  const byTime = (a: Version, b: Version) => b.at.localeCompare(a.at)
  const ordered: Version[] = []
  if (live) ordered.push(live)
  readable.filter((version) => version.draft).sort(byTime).forEach((version) => ordered.push(version))
  readable.filter((version) => !version.draft && !version.live).sort(byTime).forEach((version) => ordered.push(version))
  if (pinned && ordered.indexOf(pinned) < 0) ordered.push(pinned)

  const wanted = shown ? (shown.name || shown.pin || '') : ''
  const options = ordered.map((version) => versionMenuOption(version, browse, readable.indexOf(version) < 0))

  const picker = wanted ? (
    <SkillMenu
      groups={[{ options }]}
      value={wanted}
      config={{
        ariaLabel: '选择版本',
        search: true,
        searchPlaceholder: '按名称或版本号筛选',
        emptyText: '没有匹配的版本。',
        /* **换一版就是换这一屏看的是哪一份快照。** The address carries the pin — the version
           number without `@`, or the draft's row id. */
        onPick: (picked) => {
          const query = new URLSearchParams(params)
          query.set('version', picked.charAt(0) === '@' ? picked.slice(1) : picked)
          go('?' + query.toString())
        },
      }}
    />
  ) : (
    <SkillMenu
      groups={[{ options: [{ value: '', label: '未上线', disabled: true }] }]}
      value=""
      config={{ ariaLabel: '选择版本', triggerText: '未上线', disabled: true }}
    />
  )

  const rows = browse ? (TREE[here] ?? []) : []
  const changedVersion = (changed: string) => versions.find((version) => version.name === '@' + changed) ?? null

  return (
    <div className="tab-panel" data-tab="files" hidden={hidden}>
      <section>
        <h2 className="visually-hidden" id="fileListHeading">{browse ? '文件（' + rows.length + '）' : '文件'}</h2>
        <div className="file-table">
          <div className="skill-version-row">
            <label className="version-picker">
              <span className="visually-hidden">选择版本</span>
              <span id="versionPickerSlot">{picker}</span>
            </label>
            <span className="skill-version-count" id="versionCount">{countText}</span>
            {/* **右端的两颗动作.** 下载与版本页那颗同条件；新建版本只在 `write` 及以上画. */}
            <div className="skill-version-tail">
              <span className="skill-address"><span className="visually-hidden">地址</span><span id="skillAddress">{address}</span></span>
              <div className="skill-version-actions">
                <a className="button is-quiet" id="filesDownload" href="#" hidden={shown === null}>
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M12 4v10M8 10l4 4 4-4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                    <path d="M5 19h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                  </svg>
                  下载
                </a>
                <button
                  type="button"
                  className="button menu-button is-compact"
                  id="filesCreate"
                  aria-haspopup="menu"
                  aria-expanded={menuOpen && menuOwner === 'files' ? 'true' : 'false'}
                  hidden={!atLeast('write')}
                  ref={filesCreateRef}
                  onClick={() => onToggleMenu('files')}
                  onKeyDown={(event) => {
                    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                      event.preventDefault()
                      if (!(menuOpen && menuOwner === 'files')) onToggleMenu('files')
                    }
                  }}
                >
                  新建版本
                </button>
              </div>
            </div>
          </div>

          <div className="file-commit">
            <span className="avatar" id="commitAvatar" aria-hidden="true">{shown ? shown.by.slice(0, 1) : ''}</span>{' '}
            <span className="commit-who" id="commitWho">{shown ? shown.by : ''}</span>{' '}
            <span className="commit-msg" id="commitMsg">{shown ? shown.note : '未上线'}</span>{' '}
            <span className="commit-meta" id="commitMeta">
              {shown && (
                <>
                  <a className="commit-version" href={versionDetailAddress(shown, params)}>
                    {shown.draft
                      ? (shown.draftName || shown.pin)
                      : (shown.draftName ? shown.draftName + ' ' + (shown.name ?? '') : shown.name)}
                  </a>
                  {!shown.draft && ' · ' + shown.at}
                </>
              )}
            </span>
          </div>
          <ul className="files" id="fileList">
            {rows.map((entry) => {
              const isDir = entry.kind === 'dir'
              const full = here === '' ? entry.name : here + '/' + entry.name
              const changed = changedVersion(entry.changed)
              return (
                <li key={entry.name}>
                  <svg className="file-icon" aria-hidden="true"><use href={isDir ? '#folderMark' : '#fileMark'} /></svg>
                  <a
                    className={'file-name' + (full === reading ? ' is-current' : '')}
                    href={isDir
                      ? '?page=skill&tab=files&path=' + encodePath(full)
                      : '?page=skill&tab=files&file=' + encodePath(full)}
                  >
                    {entry.name + (isDir ? '/' : '')}
                  </a>
                  <span className="file-changed">
                    {changed === null ? '' : (changed.name ?? '') + ' · ' + changed.at.slice(0, 10)}
                  </span>
                  <span className="size">{entry.size === undefined ? '' : entry.size}</span>
                </li>
              )
            })}
          </ul>
        </div>
      </section>

      <section className="block" id="fileOpen" hidden={!browse || reading === null}>
        <h2 className="visually-hidden">文件内容</h2>
        <div className="file-table">
          <div className="file-tab"><span id="fileTabLabel">{browse && reading !== null ? reading : ''}</span></div>
          <div className="file-body">
            <div className="file-view" data-file="SKILL.md" hidden={browse ? reading !== 'SKILL.md' : undefined}>
              <div className="doc">
                <h1>pdf-tools</h1>
                <p>把 PDF 拆页、合并与压缩。加载后可用 <code>split</code> 与 <code>merge</code> 两个动作。</p>

                <h2>用法</h2>
                <ul>
                  <li><code>split</code> — 按页码范围拆成多个文件</li>
                  <li><code>merge</code> — 把多个 PDF 合成一个</li>
                  <li><code>compress</code> — 压到目标体积，先降采样再压流</li>
                </ul>

                <h2>注意</h2>
                <p>扫描件里没有文字层。拆分仍然能用，但按内容切之前要先 OCR。</p>
              </div>
            </div>

            <div className="file-view" data-file="references/split.md" hidden={browse ? reading !== 'references/split.md' : undefined}>
              <div className="doc">
                <h1>split</h1>
                <p>按页码范围把一份 PDF 拆成多个文件。页码从 1 开始，两端都算在内。</p>

                <h2>用法</h2>
                <ul>
                  <li><code>split 1-3</code> — 取前三页</li>
                  <li><code>split 4-</code> — 从第四页到结尾</li>
                  <li><code>-2</code> — 只取最后两页</li>
                </ul>

                <h2>注意</h2>
                <p>拆出来的文件名沿用原文件，后面缀上页码范围。空白页不会自动丢掉。</p>
              </div>
            </div>

            <div className="file-view" data-file="scripts/compress.sh" hidden={browse ? reading !== 'scripts/compress.sh' : undefined}>
              <pre className="file-source">{`#!/bin/sh
# 压到目标体积。降采样和压流分两步做，一次到位会把文字层压花。
set -eu

input="\${1:?用法: compress.sh <文件> [目标体积]}"
target="\${2:-2m}"

gs -sDEVICE=pdfwrite \\
   -dPDFSETTINGS=/ebook \\
   -dDownsampleColorImages=true \\
   -dColorImageResolution=150 \\
   -o "\${input%.pdf}.compressed.pdf" "$input"

ls -l "\${input%.pdf}.compressed.pdf" | awk '{ print $5, $9 }'`}</pre>
            </div>
          </div>
        </div>
      </section>
    </div>
  )
}
