/**
 * The sheets, and what opened them.
 *
 * A sheet is not a page's: it covers whatever is behind it, and the same sheet is opened from more
 * than one place. So its state lives here rather than inside the page that happens to ask for it,
 * and the pages ask with `openNewSkill` / `openImport` the way they used to set a global.
 *
 * The trigger is kept because closing a sheet has to hand the focus back to the control that opened
 * it — otherwise a keyboard reader lands at the top of the document with no idea where they were.
 */

import { useSyncExternalStore } from 'react'

export type NewSkillMode = 'open' | 'error' | 'deleted'
export type ImportMode = 'list' | 'skill'
export type ImportAsked =
  | 'open' | 'error' | 'exists' | 'exists-draft' | 'exists-hidden' | 'deleted' | 'mismatch'

export interface NewSkillOpen {
  mode: NewSkillMode
  trigger: HTMLElement | null
}

export interface ImportOpen {
  mode: ImportMode
  asked: ImportAsked
  trigger: HTMLElement | null
}

/** 批量执行里**一条**的结果。 */
export interface BatchOutcome {
  ok: boolean
  /** 成功时的实际号。**可能与确认单上预演的那个不同**——有人抢先发了更高的版本。 */
  version?: string
  /** 失败时的一句话原因，写在这一行上，不另起一屏。 */
  message?: string
}

/**
 * **一张执行单**（v18 批量上线）。给了它，确认之后这张单子不回关上、也不换成别的界面——它就地
 * 变成"正在上线"，逐条落到结果，最后停在结算态。
 *
 * 为什么不是"转圈等一次请求回来再一起变"：一批可以有很多条，一条一条看得见地落地，人才知道
 * **走到哪了、哪一条卡住了**；一次全变，中间那段就只剩一个转圈，失败的那一条还要自己去找。
 */
export interface BatchRun {
  /** 与 `lines` 一一对应，顺序就是执行的顺序。 */
  outcomes: BatchOutcome[]
  /** 一条走完之后停多久。演示件把它放慢到看得见；产品里由流式的返回决定。 */
  stepMs: number
  /** 执行中的标题。条数由调用方写进去——这一屏的动词是它的，不是这张通用弹窗的。 */
  runningTitle: string
  doneTitle: (ok: number, failed: number) => string
  doneBody: (ok: number, failed: number) => string
  doneLabel?: string
  /** **地址里的静态快照**：进来就停在第 `at` 条（`finished` 时为结算态），不放动画。 */
  snapshot?: { at: number; finished: boolean }
}

export interface DangerOpen {
  title: string
  body: string
  /**
   * **逐条列出的那份清单**（v18 批量上线）。一句「确定上线选中的 8 个吗？」把唯一有信息量的部分
   * 藏了起来——人要知道的是**哪几条、各会变成什么号**。所以它不是正文的一部分，是正文下面那张单子。
   */
  lines?: { name: string; version: string; note?: string }[]
  /** 这张单子确认之后要一条一条地走（见 `BatchRun`）。没有它，确认就是普通的一次请求。 */
  run?: BatchRun
  confirmLabel: string
  /** A reason field: 下架 asks why. Required unless `optionalField`. */
  field?: string
  placeholder?: string
  optionalField?: boolean
  /** 回滚 is confirmed but is not a dangerous action: the answer wears the ordinary accent. */
  danger?: boolean
  /** 离开那一问's other answer is 继续编辑, which says what staying does rather than undoing. */
  cancelLabel?: string
  pendingLabel?: string
  failMessage?: string
  onConfirm: (reason?: string) => void
  returnFocus?: HTMLElement | null
  focusAfter?: HTMLElement | null
}

export interface PublicBrowseOpen {
  onConfirm: () => void
  returnFocus?: HTMLElement | null
}

export interface DialogSnapshot {
  newSkill: NewSkillOpen | null
  importSkill: ImportOpen | null
  danger: DangerOpen | null
  publicBrowse: PublicBrowseOpen | null
}

let snapshot: DialogSnapshot = {
  newSkill: null,
  importSkill: null,
  danger: null,
  publicBrowse: null,
}
const listeners = new Set<() => void>()

function set(next: Partial<DialogSnapshot>) {
  snapshot = { ...snapshot, ...next }
  listeners.forEach((listener) => listener())
}

export const dialogs = {
  openNewSkill(mode: NewSkillMode = 'open', trigger: HTMLElement | null = null) {
    set({ newSkill: { mode, trigger } })
  },
  closeNewSkill() {
    const trigger = snapshot.newSkill?.trigger ?? null
    set({ newSkill: null })
    trigger?.focus()
  },
  openImport(mode: ImportMode, asked: ImportAsked = 'open', trigger: HTMLElement | null = null) {
    set({ importSkill: { mode, asked, trigger } })
  },
  closeImport() {
    const trigger = snapshot.importSkill?.trigger ?? null
    set({ importSkill: null })
    trigger?.focus()
  },
  openDanger(open: DangerOpen) {
    set({ danger: open })
  },
  closeDanger() {
    set({ danger: null })
  },
  openPublicBrowse(open: PublicBrowseOpen) {
    set({ publicBrowse: open })
  },
  closePublicBrowse() {
    set({ publicBrowse: null })
  },
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useDialogs(): DialogSnapshot {
  return useSyncExternalStore(subscribe, () => snapshot, () => snapshot)
}
