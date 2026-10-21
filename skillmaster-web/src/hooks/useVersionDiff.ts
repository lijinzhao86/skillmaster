import { useCallback, useRef, useState } from 'react'
import { codeMessage } from '../api/errors'
import { getSkillDiff } from '../api/skills'
import type { DiffFile, SkillDiff } from '../api/types'

/**
 * The 改动 pane's data: the file list first, each file's body on demand.
 *
 * **清单先行、内容按批** (TD §改动): the first request returns every changed file with its numstat;
 * a file whose body was not sent has `hunks: null`. The response's `next` is the cursor for the
 * next batch of *bodies*, not a page of the list — and this pane does not follow it. Bodies are
 * requested one file at a time with `file=<relpath>`, which is what lets the 124th file be opened
 * without fetching the first 19 batches.
 *
 * A non-binary file whose `hunks` are still null after a body was requested for it is one the
 * server chose not to render (past the per-file size or line cap). The pane can then say so; it
 * never draws `+0 −0` for it (PRD #25).
 *
 * **What changed in the port.** Vue's refs are `useState` values for what the page draws, and
 * `useRef`s for what an `await` has to read again afterwards — in React a closure would still be
 * holding the values from the render the request was started in. The refs are written
 * synchronously with the state they mirror, so the two cannot disagree. `load` and `loadFile` are
 * `useCallback`s because a pane calls them from effects, and Vue's setup-scope functions were
 * stable by construction.
 */
export function useVersionDiff(namespace: string, name: string) {
  const [files, setFiles] = useState<DiffFile[]>([])
  const [from, setFrom] = useState<string | null>(null)
  const [to, setTo] = useState('')
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const [finished, setFinished] = useState(false)
  /** Whether the server sent only the list (bodies are fetched per file) rather than all bodies. */
  const [truncated, setTruncated] = useState(false)
  const [loadingFiles, setLoadingFiles] = useState<Set<string>>(new Set())
  const [loadedFiles, setLoadedFiles] = useState<Set<string>>(new Set())
  /** Files this pane has already asked the server for, whether or not a body came back. */
  const [attemptedFiles, setAttemptedFiles] = useState<Set<string>>(new Set())
  const [fileErrors, setFileErrors] = useState<Record<string, string>>({})

  // The same values as the state above, readable synchronously after an `await`.
  const filesRef = useRef<DiffFile[]>([])
  const loadingFilesRef = useRef<Set<string>>(new Set())
  const loadedFilesRef = useRef<Set<string>>(new Set())
  const attemptedFilesRef = useRef<Set<string>>(new Set())
  const fileErrorsRef = useRef<Record<string, string>>({})
  const truncatedRef = useRef(false)

  const targetPin = useRef('')
  const fromPin = useRef<string | undefined>(undefined)

  function merge(incoming: DiffFile[]): void {
    const byPath = new Map(filesRef.current.map((file) => [file.relpath, file]))
    for (const file of incoming) {
      const previous = byPath.get(file.relpath)
      if (previous === undefined) {
        byPath.set(file.relpath, file)
        continue
      }
      // A later batch may carry content; a later list-only response must not erase it.
      byPath.set(file.relpath, {
        ...previous,
        ...file,
        size: file.size ?? previous.size,
        added: file.added ?? previous.added,
        removed: file.removed ?? previous.removed,
        hunks: file.hunks ?? previous.hunks,
      })
      if (file.hunks !== null) {
        loadedFilesRef.current = new Set(loadedFilesRef.current).add(file.relpath)
      }
    }
    const merged = [...byPath.values()]
    filesRef.current = merged
    setFiles(merged)
    setLoadedFiles(loadedFilesRef.current)
  }

  function take(response: SkillDiff): void {
    setFrom(response.from)
    setTo(response.to)
    const nextTruncated = truncatedRef.current || response.truncated
    truncatedRef.current = nextTruncated
    setTruncated(nextTruncated)
    merge(response.files)
  }

  const load = useCallback(
    async (target: string, baseline?: string): Promise<void> => {
      targetPin.current = target
      fromPin.current = baseline
      filesRef.current = []
      setFiles([])
      const emptyLoaded = new Set<string>()
      loadedFilesRef.current = emptyLoaded
      setLoadedFiles(emptyLoaded)
      const emptyAttempted = new Set<string>()
      attemptedFilesRef.current = emptyAttempted
      setAttemptedFiles(emptyAttempted)
      fileErrorsRef.current = {}
      setFileErrors({})
      setLoading(true)
      setFailed(false)
      setFinished(false)
      truncatedRef.current = false
      setTruncated(false)

      const first = await getSkillDiff(namespace, name, target, baseline)
      if (!first.ok) {
        setLoading(false)
        setFailed(true)
        return
      }
      take(first.data)
      // **One request, deliberately.** `next` names the next batch of bodies; following it here would
      // fetch every file's content up front (seven requests for a 124-file comparison) and defeat the
      // per-file mode the design asks for. The tree is complete from this response: the server's list
      // is not paginated, only the bodies are.
      setFinished(true)
      setLoading(false)
    },
    // `take` and `merge` are rebuilt each render but read only refs and stable setters, so the first
    // render's copy stays correct; the two values that really change are the address's own.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [namespace, name],
  )

  const loadFile = useCallback(
    async (relpath: string): Promise<void> => {
      if (loadedFilesRef.current.has(relpath) || loadingFilesRef.current.has(relpath)) {
        return
      }
      const loading = new Set(loadingFilesRef.current).add(relpath)
      loadingFilesRef.current = loading
      setLoadingFiles(loading)
      const attempted = new Set(attemptedFilesRef.current).add(relpath)
      attemptedFilesRef.current = attempted
      setAttemptedFiles(attempted)
      const asking = { ...fileErrorsRef.current, [relpath]: '' }
      fileErrorsRef.current = asking
      setFileErrors(asking)
      const result = await getSkillDiff(namespace, name, targetPin.current, fromPin.current, {
        file: relpath,
      })
      const remaining = new Set(loadingFilesRef.current)
      remaining.delete(relpath)
      loadingFilesRef.current = remaining
      setLoadingFiles(remaining)
      if (!result.ok) {
        const refused = {
          ...fileErrorsRef.current,
          [relpath]: codeMessage(result.code, result.message),
        }
        fileErrorsRef.current = refused
        setFileErrors(refused)
        return
      }
      merge(result.data.files)
      const loaded = new Set(loadedFilesRef.current).add(relpath)
      loadedFilesRef.current = loaded
      setLoadedFiles(loaded)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [namespace, name],
  )

  // **Countable files only.** A binary or over-limit file has no line count (`null`), and one of
  // those must not turn the whole tab's total into `—`: the design's summary sums the files it can
  // count. The per-file row still shows `—` for that file.
  const totalAdded = files.reduce((sum, file) => sum + (file.added ?? 0), 0)
  const totalRemoved = files.reduce((sum, file) => sum + (file.removed ?? 0), 0)

  function isLoaded(relpath: string): boolean {
    return loadedFiles.has(relpath)
  }

  function isLoading(relpath: string): boolean {
    return loadingFiles.has(relpath)
  }

  function errorOf(relpath: string): string {
    return fileErrors[relpath] ?? ''
  }

  /**
   * A file the server decided not to render: it is not binary, and no body arrived.
   *
   * Two ways to know it. When the response was complete, every null body is a decision. When the
   * response was truncated (`truncated`), a null body normally means "not fetched yet" — but a file
   * this pane asked for by name and still got back without a body is a decision all the same.
   */
  function unrenderable(file: DiffFile): boolean {
    return (
      (finished && !truncated) ||
      attemptedFiles.has(file.relpath)
    ) && file.hunks === null && !file.binary
  }

  return {
    files,
    from,
    to,
    loading,
    failed,
    finished,
    totalAdded,
    totalRemoved,
    load,
    loadFile,
    isLoaded,
    isLoading,
    errorOf,
    unrenderable,
  }
}
