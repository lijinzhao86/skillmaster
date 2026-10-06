<script setup lang="ts">
import type { DiffFile, SkillDiff } from '../api/types'

/**
 * Two versions of a skill, side by side.
 *
 * Rendered from the line prefix rather than from the line's position: `' '`, `'-'` and `'+'` are
 * unified diff's own, the server sends them, and reading them is what makes this view correct
 * without knowing anything about how a hunk was computed. It is also what makes the diff readable as
 * plain text — the sign is on the line, not only in its colour.
 *
 * Three states of a file are kept apart, because they mean different things and a page that merged
 * them would be saying something untrue about at least one:
 *
 * - `hunks: null` — nothing was rendered. A binary file, one past the server's size cap, or one the
 *   response's budget ran out before. The file is still in the list, with its status.
 * - `hunks: []` — rendered, and no line changed; the two versions differ in bytes this comparison
 *   does not look at, such as a trailing newline.
 * - anything else — the diff.
 */
/**
 * @param diff the comparison
 * @param fromIsLive whether `from` is the version consumers get right now. It usually is — the
 *        baseline defaults to it — but `from` is part of the address and a comparison between any two
 *        versions can be linked to, so the label is told rather than assumed. Saying "线上版本 @2"
 *        about a draft is a sentence the page has no way to make true.
 */
defineProps<{ diff: SkillDiff; fromIsLive: boolean }>()

function lineClass(line: string): string {
  if (line.startsWith('+')) {
    return 'is-added'
  }
  return line.startsWith('-') ? 'is-removed' : ''
}

const STATUS_LABELS: Record<DiffFile['status'], string> = {
  added: '新增',
  removed: '删除',
  modified: '修改',
}

function statusLabel(status: DiffFile['status']): string {
  return STATUS_LABELS[status] ?? status
}

/**
 * Why a file is listed without its content.
 *
 * Three causes — a binary, a file past the per-file size cap, and one the response's line budget ran
 * out before — and the view cannot tell the last two apart, because the caps are the server's and are
 * not reported per file. So it says what it knows rather than naming a cap that may not be the one.
 */
function unrendered(file: DiffFile): string {
  return file.binary ? '二进制文件，不显示内容。' : '这个文件的内容没有渲染（太大，或者这次比较的额度用完了）。'
}

/** What an empty comparison says, which depends on what it was compared against. */
function noDifference(from: number | null, live: boolean): string {
  if (from === null) {
    // Nothing to compare with. Reachable only for an empty version, which a real skill cannot be, so
    // the sentence says what is true rather than naming a baseline that does not exist.
    return '这一版没有文件。'
  }
  return `这一版和${live ? '线上版本' : `@${from}`}没有区别。`
}

function baseline(from: number | null, live: boolean): string {
  // Not "@0": there is no version 0, and the honest statement is that there was nothing to compare
  // with — which is what a skill that has never been published looks like.
  if (from === null) {
    return '还没有上线过（下面的文件都算新增）'
  }
  return live ? `线上版本 @${from}` : `对比基准 @${from}`
}
</script>

<template>
  <div class="diff">
    <p class="muted">
      对比：{{ baseline(diff.from, fromIsLive) }} → @{{ diff.to }}　·　{{ diff.files.length }} 个文件有变化
    </p>

    <!-- Said rather than hidden: a comparison that quietly shows half the change is worse than one
         that refuses, and the list below is still complete — it is only the content that was cut. -->
    <p v-if="diff.truncated" class="banner">
      这次比较太大，只展开了其中一部分文件。没有展开的仍然列在下面，只是看不到内容和行数。
    </p>

    <p v-if="diff.files.length === 0" class="hint">{{ noDifference(diff.from, fromIsLive) }}</p>

    <article v-for="file in diff.files" :key="file.relpath" class="diff-file">
      <header class="diff-head">
        <span class="relpath">{{ file.relpath }}</span>
        <span class="badge">{{ statusLabel(file.status) }}</span>
        <!-- Null rather than zero when nothing was rendered: a count of a file that was never
             diffed is unknown, and printing "0" would state something the server did not say. -->
        <span v-if="file.added !== null" class="counts is-added">+{{ file.added }}</span>
        <span v-if="file.removed !== null" class="counts is-removed">-{{ file.removed }}</span>
      </header>

      <p v-if="file.hunks === null" class="muted">{{ unrendered(file) }}</p>

      <template v-else>
        <template v-for="(hunk, index) in file.hunks" :key="index">
          <p class="hunk-header">{{ hunk.header }}</p>
          <pre class="hunk"><span
            v-for="(line, position) in hunk.lines"
            :key="position"
            class="line"
            :class="lineClass(line)"
          >{{ line }}</span></pre>
        </template>
      </template>
    </article>
  </div>
</template>
