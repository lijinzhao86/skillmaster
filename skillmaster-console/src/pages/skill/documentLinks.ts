/**
 * The links a read document carries, resolved to addresses inside the skill.
 *
 * A `SKILL.md` is written to be read beside its own files — `[拆分](references/split.md)`,
 * `[回到总览](../SKILL.md)` — and the console draws it from the Markdown renderer's output, whose
 * `href` is the authored string. Left alone, the browser resolves that string against the **page's**
 * address, which is not the document's directory. The default address, `/skills/ns/name?tab=files`,
 * therefore turns `references/split.md` into `/skills/ns/references/split.md` — not a page the
 * console has, so it falls back to the list — and even where the arithmetic happens to land on the
 * right path, the version being read is lost.
 *
 * This module turns the authored string into "the same version, the same relative path" by
 * resolving it against the document's own directory. Everything that is not a relative path inside
 * the skill — a full URL, a protocol-relative `//host/…`, a bare `#anchor`, `mailto:` — answers
 * `null`, and the caller leaves it exactly as written.
 *
 * **Only relative paths are rewritten.** A path that starts with `/` is a site path — the console
 * has those, an author may mean one, and a browser would have resolved it as written. It is left
 * alone, exactly like an external URL.
 *
 * **A target this version does not have is still rewritten.** The reader lands on the address of
 * the file the document names; the file table shows that file's directory, and the design's rule —
 * a directory that is not in this version falls back to the root — keeps that from being a blank
 * page. Leaving the authored path alone would send them to the skill list instead, which says less.
 */
export interface DocumentTarget {
  /** The path inside the skill, after `./` and `../` are resolved. */
  relpath: string
  /** Names a directory: the root, a path written as one, or a path the manifest knows is one. */
  isDir: boolean
  /** `#fragment` or `?query` as it was written after the path. */
  suffix: string
}

const SCHEME = /^[a-zA-Z][a-zA-Z0-9+.-]*:/

/**
 * The console address a link written in `fromRelpath` should lead to, or `null` to keep the
 * authored `href`.
 *
 * The two builders are the file tab's own — this module decides *what* the link names, not how an
 * address is spelled; `fileHref` and `dirHref` are the ones that already answer that.
 */
export function documentHrefOf(
  fromRelpath: string,
  href: string,
  fileHref: (relpath: string) => string,
  dirHref: (relpath: string) => string,
  isDirectory?: (relpath: string) => boolean,
): string | null {
  const target = documentTargetOf(fromRelpath, href, isDirectory)
  if (target === null) {
    return null
  }
  const address = target.isDir ? dirHref(target.relpath) : fileHref(target.relpath)
  return withSuffix(address, target.suffix)
}

/**
 * The authored `?query` / `#fragment` on the end of the resolved address.
 *
 * A file address has no query, so the suffix goes on as written. A directory's does — `?tab=files`
 * — and a second `?` would let the first parameter's value swallow the rest (`tab=files?plain=1`),
 * which is an address that names no tab at all. Appending the parameters instead keeps both.
 */
function withSuffix(address: string, suffix: string): string {
  if (suffix === '' || !suffix.startsWith('?') || !address.includes('?')) {
    return `${address}${suffix}`
  }
  return `${address}&${suffix.slice(1)}`
}

/** What the authored `href` names inside the skill, or `null` when it names nothing inside it. */
export function documentTargetOf(
  fromRelpath: string,
  href: string,
  isDirectory?: (relpath: string) => boolean,
): DocumentTarget | null {
  if (
    href === '' ||
    href.startsWith('/') ||
    href.startsWith('#') ||
    SCHEME.test(href)
  ) {
    return null
  }
  const cut = firstMarkOf(href)
  const written = cut < 0 ? href : href.slice(0, cut)
  const suffix = cut < 0 ? '' : href.slice(cut)
  if (written === '') {
    return null
  }
  const decoded = decodePath(written)
  if (decoded === null) {
    return null
  }

  const segments: string[] = directoryOf(fromRelpath)
  for (const segment of decoded.split('/')) {
    if (segment === '' || segment === '.') {
      continue
    }
    if (segment === '..') {
      if (segments.length === 0) {
        // Above the skill's root there is no target to name; the authored href is all there is.
        return null
      }
      segments.pop()
      continue
    }
    segments.push(segment)
  }
  const relpath = segments.join('/')
  // The root is always a directory; so are a trailing slash, `.` and `..`, by construction. A
  // folder's bare name is only known from the manifest, which the caller answers for.
  const isDir =
    relpath === '' ||
    written.endsWith('/') ||
    written === '.' ||
    written === '..' ||
    isDirectory?.(relpath) === true
  return { relpath, isDir, suffix }
}

function directoryOf(relpath: string): string[] {
  const slash = relpath.lastIndexOf('/')
  return slash < 0 ? [] : relpath.slice(0, slash).split('/')
}

/** The first `#` or `?` — whichever the authored href reaches first. */
function firstMarkOf(href: string): number {
  const hash = href.indexOf('#')
  const query = href.indexOf('?')
  if (hash < 0) {
    return query
  }
  if (query < 0) {
    return hash
  }
  return Math.min(hash, query)
}

/**
 * The path with each segment decoded, so `%E6%8B%86%E5%88%86.md` is the file named `拆分.md` before
 * it is resolved. A malformed escape is not a path, and `null` says so rather than throwing during
 * render.
 */
function decodePath(written: string): string | null {
  try {
    const segments = written.split('/').map(decodeURIComponent)
    // `%2F` decodes to the separator itself. A skill's file names cannot contain one, and letting
    // it through would turn one segment into two — a path the author did not write.
    return segments.some((segment) => segment.includes('/')) ? null : segments.join('/')
  } catch {
    return null
  }
}
