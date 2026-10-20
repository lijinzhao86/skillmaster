import { computed, ref } from 'vue'
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
 */
export function useVersionDiff(namespace: string, name: string) {
  const files = ref<DiffFile[]>([])
  const from = ref<string | null>(null)
  const to = ref('')
  const loading = ref(false)
  const failed = ref(false)
  const finished = ref(false)
  /** Whether the server sent only the list (bodies are fetched per file) rather than all bodies. */
  const truncated = ref(false)
  const loadingFiles = ref<Set<string>>(new Set())
  const loadedFiles = ref<Set<string>>(new Set())
  /** Files this pane has already asked the server for, whether or not a body came back. */
  const attemptedFiles = ref<Set<string>>(new Set())
  const fileErrors = ref<Record<string, string>>({})

  let targetPin = ''
  let fromPin: string | undefined

  function merge(incoming: DiffFile[]): void {
    const byPath = new Map(files.value.map((file) => [file.relpath, file]))
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
        loadedFiles.value = new Set(loadedFiles.value).add(file.relpath)
      }
    }
    files.value = [...byPath.values()]
  }

  function take(response: SkillDiff): void {
    from.value = response.from
    to.value = response.to
    truncated.value = truncated.value || response.truncated
    merge(response.files)
  }

  async function load(target: string, baseline?: string): Promise<void> {
    targetPin = target
    fromPin = baseline
    files.value = []
    loadedFiles.value = new Set()
    attemptedFiles.value = new Set()
    fileErrors.value = {}
    loading.value = true
    failed.value = false
    finished.value = false
    truncated.value = false

    const first = await getSkillDiff(namespace, name, target, baseline)
    if (!first.ok) {
      loading.value = false
      failed.value = true
      return
    }
    take(first.data)
    // **One request, deliberately.** `next` names the next batch of bodies; following it here would
    // fetch every file's content up front (seven requests for a 124-file comparison) and defeat the
    // per-file mode the design asks for. The tree is complete from this response: the server's list
    // is not paginated, only the bodies are.
    finished.value = true
    loading.value = false
  }

  async function loadFile(relpath: string): Promise<void> {
    if (loadedFiles.value.has(relpath) || loadingFiles.value.has(relpath)) {
      return
    }
    loadingFiles.value = new Set(loadingFiles.value).add(relpath)
    attemptedFiles.value = new Set(attemptedFiles.value).add(relpath)
    fileErrors.value = { ...fileErrors.value, [relpath]: '' }
    const result = await getSkillDiff(namespace, name, targetPin, fromPin, { file: relpath })
    const remaining = new Set(loadingFiles.value)
    remaining.delete(relpath)
    loadingFiles.value = remaining
    if (!result.ok) {
      fileErrors.value = {
        ...fileErrors.value,
        [relpath]: codeMessage(result.code, result.message),
      }
      return
    }
    merge(result.data.files)
    loadedFiles.value = new Set(loadedFiles.value).add(relpath)
  }

  // **Countable files only.** A binary or over-limit file has no line count (`null`), and one of
  // those must not turn the whole tab's total into `—`: the design's summary sums the files it can
  // count. The per-file row still shows `—` for that file.
  const totalAdded = computed(() =>
    files.value.reduce((sum, file) => sum + (file.added ?? 0), 0),
  )
  const totalRemoved = computed(() =>
    files.value.reduce((sum, file) => sum + (file.removed ?? 0), 0),
  )

  function isLoaded(relpath: string): boolean {
    return loadedFiles.value.has(relpath)
  }

  function isLoading(relpath: string): boolean {
    return loadingFiles.value.has(relpath)
  }

  function errorOf(relpath: string): string {
    return fileErrors.value[relpath] ?? ''
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
      (finished.value && !truncated.value) ||
      attemptedFiles.value.has(file.relpath)
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
