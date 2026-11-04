import type { RefObject } from 'react'
import type { AuthoredSkill, AuthoredVersion } from '../../api/types'
import { formatSize } from '../../format'
import { formatTime } from '../../time'
import CommandMenu from '../../components/CommandMenu'
import type { CommandMenuHandle, CommandMenuItem } from '../../components/CommandMenu'
import MarkdownView from '../../components/MarkdownView'
import VersionPicker from '../../components/VersionPicker'
import { versionSuffix } from '../../version'
import { documentHrefOf } from './documentLinks'
import { isDirectoryOf, type Entry } from './fileListing'

/**
 * 文件: one level of the skill's tree, and the file being read under it the way a repository
 * renders its README under its files.
 *
 * The version row belongs to this table, not to the page: it chooses which snapshot these files
 * are, a question that only means something while the files are on screen.
 */
export interface FilesTabProps {
  skill: AuthoredSkill
  levelEntries: Entry[]
  openRelpath: string | undefined
  openFile: { relpath: string; isBinary: boolean } | null
  openIsMarkdown: boolean
  fileContent: string | null
  readingFile: boolean
  fileProblem: string | null
  pickerVersions: AuthoredVersion[]
  fileTabVersion: AuthoredVersion | null
  disabledPickerValues: string[]
  versionCount: number
  onPickVersion: (suffix: string) => void
  fileDownloadHref: string | null
  canWrite: boolean
  filesMenu: RefObject<CommandMenuHandle | null>
  filesCreateItems: CommandMenuItem[]
  fileHref: (file: string) => string
  dirHref: (dir: string) => string
  commitVersionHref: string
  commitVersionLabel: string
}

export default function FilesTab({
  skill,
  levelEntries,
  openRelpath,
  openFile,
  openIsMarkdown,
  fileContent,
  readingFile,
  fileProblem,
  pickerVersions,
  fileTabVersion,
  disabledPickerValues,
  versionCount,
  onPickVersion,
  fileDownloadHref,
  canWrite,
  filesMenu,
  filesCreateItems,
  fileHref,
  dirHref,
  commitVersionHref,
  commitVersionLabel,
}: FilesTabProps) {
  return (
    <div className="tab-panel" data-tab="files">
      <section>
        <h2 className="visually-hidden">{`文件（${levelEntries.length}）`}</h2>
        <div className="file-table">
          {/* The version row belongs to this table, not to the page: it chooses which snapshot
              these files are, a question that only means something while the files are on
              screen. */}
          <div className="skill-version-row">
            <label className="version-picker">
              <span className="visually-hidden">选择版本</span>
              <VersionPicker
                versions={pickerVersions}
                selected={fileTabVersion === null ? '' : versionSuffix(fileTabVersion)}
                disabledValues={disabledPickerValues}
                onSelect={onPickVersion}
              />
            </label>
            <span className="skill-version-count">{`${versionCount} 个版本`}</span>
            <div className="skill-version-tail">
              <span className="skill-address">
                <span className="visually-hidden">地址</span>
                {`${skill.namespace.slug}/${skill.name}`}
              </span>
              {/* **The row's right end: 下载 then 新建版本 (2026-10-21, design §一个 skill).**
                  GitHub puts its file actions there; the download is the same archive route and
                  the same condition as the version page's, and 新建版本 is the same menu as the
                  version list's — one way to produce a version, wherever it is opened from. */}
              <div className="skill-version-actions">
                {fileDownloadHref !== null ? (
                  <a className="button is-quiet" href={fileDownloadHref}>
                    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path
                        d="M12 4v10M8 10l4 4 4-4"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                      <path
                        d="M5 19h14"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                      />
                    </svg>
                    下载
                  </a>
                ) : null}
                {canWrite ? (
                  <CommandMenu
                    controlRef={filesMenu}
                    groups={[filesCreateItems]}
                    menuLabel="新建版本"
                    resize="reposition"
                  >
                    {({ open, toggle }) => (
                      <button
                        type="button"
                        className="button menu-button is-compact"
                        aria-haspopup="menu"
                        aria-expanded={open ? 'true' : 'false'}
                        onClick={toggle}
                      >
                        新建版本
                      </button>
                    )}
                  </CommandMenu>
                ) : null}
              </div>
            </div>
          </div>

          {/* The row is always drawn: with nothing readable it says 「未上线」 (design's own
              sentence), so the file column does not lose its head. */}
          <div className="file-commit">
            <span className="avatar" aria-hidden="true">
              {fileTabVersion === null ? '' : (fileTabVersion.submitted_by ?? '?').slice(0, 1)}
            </span>
            <span className="commit-who">
              {fileTabVersion === null ? '' : (fileTabVersion.submitted_by ?? '')}
            </span>
            <span className="commit-msg">
              {fileTabVersion === null ? '未上线' : (fileTabVersion.note ?? '')}
            </span>
            {/* **The right end is this version's identity, so it is the way back to it.** The
                mirror of the version page's 「查看文件」; the time stays plain text. */}
            {fileTabVersion !== null ? (
              <span className="commit-meta">
                <a className="commit-version" href={commitVersionHref}>
                  {commitVersionLabel}
                </a>
                {fileTabVersion.name !== null ? (
                  <>{`· ${formatTime(fileTabVersion.first_published_at ?? fileTabVersion.submitted_at)}`}</>
                ) : null}
              </span>
            ) : null}
          </div>

          {/* **One level at a time.** A folder goes into its own listing; a file opens below.
              The middle column — "the version that last changed this file, and its date" — is
              left empty: the product has no per-file history to say it with, and the design
              records that fact as the demo's own fixture (design/README §已知的取舍). */}
          <ul className="files">
            {levelEntries.map((entry) => (
              <li key={entry.relpath}>
                <svg className="file-icon" aria-hidden="true">
                  <use href={entry.kind === 'dir' ? '#folderMark' : '#fileMark'} />
                </svg>
                <a
                  className={
                    entry.kind === 'file' && entry.relpath === openRelpath
                      ? 'file-name is-current'
                      : 'file-name'
                  }
                  href={entry.kind === 'dir' ? dirHref(entry.relpath) : fileHref(entry.relpath)}
                >
                  {entry.kind === 'dir' ? `${entry.name}/` : entry.name}
                </a>
                <span className="file-changed" />
                {entry.kind === 'file' ? (
                  <span className="size">{formatSize(entry.size)}</span>
                ) : (
                  <span className="size" />
                )}
              </li>
            ))}
          </ul>
        </div>
      </section>

      {/* The file being read, under the listing the way a repository renders its README under
          its files — and in the same kind of box, with the file's own name where the version
          was. The root opens `SKILL.md` by default; a folder opens nothing until a file is
          chosen. */}
      {openFile !== null ? (
        <section className="block file-open">
          <h2 className="visually-hidden">文件内容</h2>
          <div className="file-table">
            <div className="file-tab">{openFile.relpath}</div>
            <div className="file-body">
              {openFile.isBinary ? (
                <p className="hint">
                  这是二进制文件，不在这里展示。本机用 <code>skmr skill get</code> 取它。
                </p>
              ) : readingFile ? (
                <p className="loading">加载中…</p>
              ) : fileProblem !== null ? (
                <p className="banner">{fileProblem}</p>
              ) : fileContent !== null && openIsMarkdown ? (
                // **A link written beside this document points at a path beside this document.**
                // Resolved against the bar's address it would not: the browser knows the skill's
                // page, not the folder the file lives in (the default address is the page's, with
                // no `files/` segment at all). Answered here, where that folder and the version on
                // screen are both known.
                <MarkdownView
                  source={fileContent}
                  resolveHref={(href) =>
                    documentHrefOf(openFile.relpath, href, fileHref, dirHref, (relpath) =>
                      isDirectoryOf(skill.files, relpath),
                    )
                  }
                />
              ) : fileContent !== null ? (
                <pre className="file-source">{fileContent}</pre>
              ) : (
                <p className="hint">这个版本里没有 {openFile.relpath}。</p>
              )}
            </div>
          </div>
        </section>
      ) : null}
    </div>
  )
}
