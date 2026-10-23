import type { CSSProperties } from 'react'
import { formatSize } from '../../format'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { AuthoredVersion, DiffFile } from '../../api/types'
import { go, query, relativeAddress, replaceAddress } from '../../address'
import { useVersionDiff } from '../../hooks/useVersionDiff'
import { previousPublished, readableBy } from '../../versionActions'
import {
  answersPin,
  fileAnchorId,
  noDifferenceText,
  statusOf,
  versionSuffix,
  versionTitle,
} from '../../version'
import DiffFileBlock from '../DiffFileBlock'
import VersionFileTree from './VersionFileTree'
import VersionPicker from '../VersionPicker'

/**
 * The 改动 tab: the comparison baseline, the file tree, and each file's diff.
 *
 * **清单先行、内容按需、单文件分段** (TD §改动). The list and every file's numstat arrive first;
 * a file's body is fetched when it enters the viewport or when 显示改动 is pressed; each body draws
 * its first 400 lines and 500 more per press. More than 100 files puts the pane into single-file
 * mode, where the tree still lists everything but only the current file is drawn. 「只看文件列表」
 * is an address state (`list=1`). The filter above the tree narrows only the tree (`fq`).
 *
 * The baseline picker lists every version this rung can see, including drafts (`write`) and revoked
 * or discarded versions (`maintain`). The default is the previous published version; a draft, a
 * first version, or a previous version this rung cannot see falls back to the live version, and with
 * neither there is no 改动 tab at all (the parent does not draw it).
 */
export interface VersionDiffPaneProps {
  namespace: string
  name: string
  version: AuthoredVersion
  versions: AuthoredVersion[]
  rung: string
  from?: string
  fq: string
  file?: string
  list: boolean
  live: AuthoredVersion | null
  /**
   * The parent keeps this pane mounted and hides the inactive one (`v-show`): the display it hands
   * down lands on this component's root, exactly as Vue's attribute fallthrough did.
   */
  style?: CSSProperties
  onSelectBaseline: (pin: string) => void
  onSummary: (value: { files: number; added: number | null; removed: number | null } | null) => void
}

export default function VersionDiffPane({
  namespace,
  name,
  version,
  versions,
  rung,
  from,
  fq,
  file,
  list,
  live,
  style,
  onSelectBaseline,
  onSummary,
}: VersionDiffPaneProps) {
  const comparable = versions.filter((v) => v !== version && readableBy(v, rung))

  /** The previous published version, by first-publish time — the same order rollback defaults to. */
  const previous: AuthoredVersion | null = previousPublished(version, comparable)

  /** The baseline: the address's `from`, else the previous version, else the live one. */
  const baseline: AuthoredVersion | null = (() => {
    const chosen =
      from === undefined ? undefined : comparable.find((v) => answersPin(v, from))
    if (chosen !== undefined) {
      return chosen
    }
    if (previous !== null) {
      return previous
    }
    return live !== null && live !== version && comparable.includes(live) ? live : null
  })()

  /** The picker's order: live, then history/discarded, then drafts, then revoked (design). */
  const pickerVersions = (() => {
    const of = (predicate: (v: AuthoredVersion) => boolean) => comparable.filter(predicate)
    return [
      ...of((v) => statusOf(v) === 'live'),
      ...of((v) => statusOf(v) === 'history' || statusOf(v) === 'discarded'),
      ...of((v) => statusOf(v) === 'draft'),
      ...of((v) => statusOf(v) === 'revoked'),
    ]
  })()

  const baselinePin = baseline === null ? '' : versionSuffix(baseline)

  const compareTrigger = ((): string => {
    if (baseline === null) {
      return ''
    }
    const title = versionTitle(baseline, rung)
    return baseline === previous ? `${title}（上一版）` : title
  })()

  const diff = useVersionDiff(namespace, name)

  const [listOnly] = useState(list)
  const [filter, setFilterState] = useState(fq)
  const [current, setCurrent] = useState(file ?? '')

  const singleMode = !listOnly && diff.files.length > 100

  const currentFile = diff.files.find((changed: DiffFile) => changed.relpath === current) ?? diff.files[0]

  /** Stable inside the template, where Vue's narrowing of a computed does not survive into calls. */
  const currentPath = currentFile?.relpath ?? ''

  // **A failed load is not "no changes".** With the list empty, the old shape reported 0 files and
  // `+0 −0` in the tab row — the header claiming the opposite of the pane's own failure sentence.
  const summary = useMemo(
    () =>
      // Null while the list is still on its way, too: an empty list is not a fact yet, and the tab
      // would claim 「改动 0」 / `+0 −0` for the length of the request.
      diff.failed || !diff.finished
        ? null
        : {
            files: diff.files.length,
            added: diff.totalAdded,
            removed: diff.totalRemoved,
          },
    [diff.failed, diff.finished, diff.files, diff.totalAdded, diff.totalRemoved],
  )

  useEffect(() => {
    onSummary(summary)
    // `onSummary` is the parent's setter; the value it is handed is what this effect is about.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [summary])

  function updateAddress(mutate: (params: URLSearchParams) => void): void {
    const params = query()
    mutate(params)
    replaceAddress(relativeAddress(params))
  }

  function setFilter(value: string): void {
    setFilterState(value)
    updateAddress((params) => {
      if (value.trim() === '') {
        params.delete('fq')
      } else {
        params.set('fq', value)
      }
    })
  }

  function toggleList(): void {
    // **An address state, not a local switch** (design README: 「它是地址里的一态，不是本地开关」).
    // Flipping it in place left single-file mode with no load: the file list and the current file had
    // not changed, so neither watcher that fetches a body fired, and the pane sat on 「正在加载改动…」
    // with no button to leave. The design navigates to the other state; so does this.
    const params = query()
    params.delete('file')
    if (listOnly) {
      params.delete('list')
    } else {
      params.set('list', '1')
    }
    go(relativeAddress(params))
  }

  function hrefFor(relpath: string): string {
    const params = query()
    params.set('file', relpath)
    params.delete('list')
    return relativeAddress(params)
  }

  /**
   * In the many-file layout every block is already drawn, so a tree row scrolls to it. Writing `file=`
   * here would open the single-file layout, which is the other mode's job; the address's `file=` is
   * only for it.
   */
  function scrollToFile(relpath: string): void {
    document.getElementById(fileAnchorId(relpath))?.scrollIntoView({ block: 'start' })
  }

  /** What the pane says when the comparison is real but empty. */
  const noDifference = noDifferenceText(
    baselinePin === '' ? null : baselinePin,
    baseline === live,
  )

  function selectBaseline(pin: string): void {
    onSelectBaseline(pin)
  }

  /**
   * Bodies are fetched when they enter the viewport (or when the button is pressed). jsdom has no
   * `IntersectionObserver`, and that is fine: the button is the same road.
   */
  const observer = useRef<IntersectionObserver | null>(null)

  function observePending(element: Element | null, relpath: string): void {
    if (element === null || observer.current === null) {
      return
    }
    observer.current.observe(element)
    void relpath
  }

  useEffect(() => {
    if ('IntersectionObserver' in window) {
      observer.current = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            if (!entry.isIntersecting) {
              continue
            }
            observer.current?.unobserve(entry.target)
            const relpath = entry.target.getAttribute('data-relpath')
            if (relpath !== null) {
              void diff.loadFile(relpath)
            }
          }
        },
        { rootMargin: '240px' },
      )
    }
    const pin = baselinePin
    if (pin !== '') {
      void diff.load(versionSuffix(version), pin)
    }
    return () => {
      observer.current?.disconnect()
    }
    // The pane loads its one comparison when it mounts; the address's baseline is read once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    // The list arrives after the address's `file=` was read; an empty list must not throw that away.
    if (diff.files.length === 0) {
      return
    }
    if (current === '' || !diff.files.some((changed: DiffFile) => changed.relpath === current)) {
      setCurrent(diff.files[0]?.relpath ?? '')
    }
    if (singleMode && currentFile !== undefined) {
      // Single-file mode's address names the open file (design README 1157): entering it without
      // `file=` writes the first one, so the page can be sent to somebody and opens the same file.
      updateAddress((params) => params.set('file', currentFile.relpath))
      void diff.loadFile(currentFile.relpath)
    } else if (file !== undefined) {
      // Multi-file mode reached through `?file=`: the tree row and the block exist, so bring the
      // block into view instead of leaving the reader at the top of the list.
      const addressed = file
      requestAnimationFrame(() => {
        document.getElementById(fileAnchorId(addressed))?.scrollIntoView({ block: 'start' })
      })
    }
    // Vue's watcher was on the list's length alone.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [diff.files.length])

  const currentFirstRun = useRef(true)
  useEffect(() => {
    if (currentFirstRun.current) {
      currentFirstRun.current = false
      return
    }
    if (singleMode && currentFile !== undefined) {
      void diff.loadFile(currentFile.relpath)
    }
    // Vue's watcher was on `current` alone and did not fire on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current])

  const currentIndex = diff.files.findIndex(
    (changed: DiffFile) => changed.relpath === currentFile?.relpath,
  )
  /** The design marks the pager's ends as a link that cannot go further (demo's `is-disabled`). */
  const atFirstFile = currentIndex <= 0
  const atLastFile = currentIndex === diff.files.length - 1

  function prevHref(): string {
    return hrefFor(diff.files[Math.max(0, currentIndex - 1)]?.relpath ?? '')
  }

  function nextHref(): string {
    return hrefFor(
      diff.files[
        Math.min(diff.files.length - 1, currentIndex + 1)
      ]?.relpath ?? '',
    )
  }

  return (
    <div className="vp-pane" data-vpane="diff" style={style}>
      <div className="vp-compare">
        <span className="vp-compare-caption">对比</span>
        <VersionPicker
          versions={pickerVersions}
          selected={baselinePin}
          ariaLabel="对比的版本"
          triggerText={compareTrigger}
          previousPin={previous === null ? undefined : versionSuffix(previous)}
          onSelect={selectBaseline}
        />
        <span className="vp-file-count">{diff.files.length} 个文件</span>
        <button type="button" className="link-button" onClick={toggleList}>
          {listOnly ? '逐个查看' : '只看文件列表'}
        </button>
      </div>

      {diff.files.length > 100 || listOnly ? (
        <p className="hint vp-big-note">
          {listOnly
            ? `只看文件列表：${diff.files.length} 个文件有变化。`
            : `这次改动有 ${diff.files.length} 个文件。文件内容按需加载。`}
        </p>
      ) : null}

      {diff.failed ? (
        <p className="diff-pending diff-failed">
          <span>加载失败：服务器暂时没有响应。</span>
          <button
            type="button"
            className="link-button"
            onClick={() => void diff.load(versionSuffix(version), baselinePin)}
          >
            重试
          </button>
        </p>
      ) : diff.loading && diff.files.length === 0 ? (
        <p className="diff-pending">正在加载改动…</p>
      ) : diff.finished && diff.files.length === 0 ? (
        <p className="hint">{noDifference}</p>
      ) : (
        <div className="vp-changes">
          <VersionFileTree
            files={diff.files}
            filter={filter}
            current={singleMode ? (currentFile?.relpath ?? null) : null}
            addressMode={singleMode || listOnly}
            hrefFor={hrefFor}
            onChange={setFilter}
            onSelect={scrollToFile}
          />

          <div className="vp-files">
            {listOnly ? (
              <p className="hint vp-file-list-note">
                只看文件列表。点左边的文件，或「逐个查看」，打开内容。
              </p>
            ) : singleMode && currentFile !== undefined ? (
              <>
                <div className="vp-file-mode">
                  <span className="hint">一次显示一个文件</span>
                  <div className="vp-file-pager">
                    <a
                      className={atFirstFile ? 'link-button is-disabled' : 'link-button'}
                      href={prevHref()}
                      onClick={(event) => {
                        if (atFirstFile) {
                          event.preventDefault()
                        }
                      }}
                    >
                      上一个
                    </a>
                    <span>{currentIndex + 1} / {diff.files.length}</span>
                    <a
                      className={atLastFile ? 'link-button is-disabled' : 'link-button'}
                      href={nextHref()}
                      onClick={(event) => {
                        if (atLastFile) {
                          event.preventDefault()
                        }
                      }}
                    >
                      下一个
                    </a>
                  </div>
                </div>
                <DiffFileBlock
                  file={currentFile}
                  initialLimit={400}
                  pending={
                    currentFile.binary ? (
                      <p className="diff-pending">二进制文件，不显示内容。</p>
                    ) : diff.errorOf(currentPath) !== '' ? (
                      <div className="diff-pending diff-failed">
                        <span>加载失败：服务器暂时没有响应。</span>
                        <button
                          type="button"
                          className="link-button"
                          onClick={() => void diff.loadFile(currentPath)}
                        >
                          重试
                        </button>
                      </div>
                    ) : diff.isLoading(currentPath) || !diff.isLoaded(currentPath) ? (
                      <p className="diff-pending">正在加载改动…</p>
                    ) : diff.unrenderable(currentFile) ? (
                      <p className="diff-pending">
                        这个文件太大，没有展开（{formatSize(currentFile.size)}）。
                      </p>
                    ) : null
                  }
                />
              </>
            ) : (
              <>
                {diff.files.map((changed: DiffFile) => (
                  <DiffFileBlock
                    key={changed.relpath}
                    id={fileAnchorId(changed.relpath)}
                    file={changed}
                    initialLimit={400}
                    pending={
                      changed.binary ? (
                        <p className="diff-pending">二进制文件，不显示内容。</p>
                      ) : diff.errorOf(changed.relpath) !== '' ? (
                        <div className="diff-pending diff-failed">
                          <span>加载失败：服务器暂时没有响应。</span>
                          <button
                            type="button"
                            className="link-button"
                            onClick={() => void diff.loadFile(changed.relpath)}
                          >
                            重试
                          </button>
                        </div>
                      ) : diff.isLoading(changed.relpath) ? (
                        <p className="diff-pending">正在加载改动…</p>
                      ) : diff.unrenderable(changed) ? (
                        <p className="diff-pending">
                          这个文件太大，没有展开（{formatSize(changed.size)}）。
                        </p>
                      ) : !diff.isLoaded(changed.relpath) ? (
                        <div
                          ref={(element) => observePending(element, changed.relpath)}
                          className="diff-pending"
                          data-relpath={changed.relpath}
                        >
                          <span>进入视野后加载</span>
                          <button
                            type="button"
                            className="link-button"
                            onClick={() => void diff.loadFile(changed.relpath)}
                          >
                            显示改动
                          </button>
                        </div>
                      ) : null
                    }
                  />
                ))}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
