#!/usr/bin/env node
// The `LIMITS` below are the authority; docs/current/rules.md §五 points here.
//
// Existing violations live in scripts/file-size-baseline.json and are allowed to stay,
// but they may not grow; a file not in the baseline that is over its limit fails the
// check. `--update-baseline` rewrites the baseline from the current tree and is refused
// in CI: a guard that can be satisfied by widening itself is not a guard.

import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..')
const baselinePath = join(root, 'scripts', 'file-size-baseline.json')

const LIMITS = {
  page: 600,
  component: 400,
  general: 500,
  java: 500,
  go: 600,
  main: 300,
  test: 1000,
}

const SKIP_DIRS = new Set([
  'node_modules', 'dist', 'target', 'build', '.git', 'reference-python', 'references',
])

const ROOTS = [
  'skillmaster-server/src/main/java',
  'skillmaster-server/src/test/java',
  'skillmaster-cli',
  'skillmaster-console/src',
  'skillmaster-console/tests',
  'skillmaster-site/src',
]

function kindOf(rel) {
  const p = rel.split(sep).join('/')
  if (/(^|\/)(node_modules|dist|target|build|reference-python|references)\//.test(p)) return null
  if (/_test\.go$/.test(p) || /\.test\.(ts|tsx|js|jsx)$/.test(p) || /Tests?\.(java)$/.test(p) || /IT\.java$/.test(p)) {
    return 'test'
  }
  if (p.startsWith('skillmaster-console/src/pages/')) return 'page'
  if (p.startsWith('skillmaster-console/src/components/')) return 'component'
  if (p.startsWith('skillmaster-server/src/main/java/')) return 'java'
  if (p === 'skillmaster-cli/cmd/skillmaster/main.go') return 'main'
  if (p.startsWith('skillmaster-cli/') && p.endsWith('.go')) return 'go'
  if (/\.(ts|tsx|js|jsx|css)$/.test(p)) return 'general'
  if (/\.(go|java)$/.test(p)) return 'general'
  return null
}

function walk(dir, out) {
  if (!existsSync(dir)) return
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue
    const full = join(dir, entry)
    const st = statSync(full)
    if (st.isDirectory()) walk(full, out)
    else if (st.isFile()) out.push(full)
  }
}

function linesOf(file) {
  const text = readFileSync(file, 'utf8')
  if (text === '') return 0
  // Physical lines, the same number `wc -l` reports.
  let n = 0
  for (let i = 0; i < text.length; i++) if (text[i] === '\n') n++
  return text.endsWith('\n') ? n : n + 1
}

function collect() {
  const files = []
  for (const rel of ROOTS) walk(join(root, rel), files)
  return files
}

function loadBaseline() {
  if (!existsSync(baselinePath)) return {}
  try {
    return JSON.parse(readFileSync(baselinePath, 'utf8')).entries ?? {}
  } catch (e) {
    console.error(`cannot read ${relative(root, baselinePath)}: ${e.message}`)
    process.exit(2)
  }
}

function main() {
  const update = process.argv.includes('--update-baseline')
  if (update && process.env.CI) {
    console.error('--update-baseline is refused in CI: the baseline is a debt list, not a way to pass.')
    process.exit(2)
  }

  const baseline = loadBaseline()
  const violations = []
  const grew = []
  const allowed = []

  for (const file of collect()) {
    const rel = relative(root, file).split(sep).join('/')
    const kind = kindOf(rel)
    if (!kind) continue
    const limit = LIMITS[kind]
    const lines = linesOf(file)
    if (lines <= limit) continue
    const previous = baseline[rel]
    if (previous === undefined) {
      violations.push({ rel, kind, lines, limit })
    } else if (lines > previous) {
      grew.push({ rel, kind, lines, limit, previous })
    } else {
      allowed.push({ rel, kind, lines, limit, previous })
    }
  }

  if (update) {
    const entries = {}
    for (const v of [...violations, ...grew, ...allowed]) entries[v.rel] = v.lines
    for (const [rel, lines] of Object.entries(baseline)) {
      if (!entries[rel] && lines > 0) {
        // Keep entries whose file still exists and is still over its limit only.
        const file = join(root, rel)
        if (!existsSync(file)) continue
        const kind = kindOf(rel)
        if (!kind || linesOf(file) > LIMITS[kind]) entries[rel] = linesOf(file)
      }
    }
    writeFileSync(baselinePath, JSON.stringify({ generatedAt: new Date().toISOString(), entries }, null, 2) + '\n')
    console.log(`baseline updated: ${Object.keys(entries).length} entries`)
    return
  }

  if (allowed.length > 0) {
    console.log(`baseline (allowed, may only shrink): ${allowed.length}`)
    for (const a of allowed) console.log(`  ${a.lines}/${a.limit}  ${a.rel}  (baseline ${a.previous})`)
  }
  if (violations.length > 0) {
    console.error(`\nnew files over the limit: ${violations.length}`)
    for (const v of violations) console.error(`  ${v.lines}/${v.limit}  ${v.rel}  (${v.kind})`)
  }
  if (grew.length > 0) {
    console.error(`\nbaseline files that grew: ${grew.length}`)
    for (const g of grew) console.error(`  ${g.lines}/${g.limit}  ${g.rel}  (baseline ${g.previous})`)
  }
  if (violations.length === 0 && grew.length === 0) {
    console.log('file-size check passed')
    return
  }
  process.exit(1)
}

main()
