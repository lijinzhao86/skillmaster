/**
 * The demo's own vocabulary: which pages exist, which states each one can be in, and the switches
 * the strip along the bottom offers.
 *
 * **This is the demo's stand-in for a server, and nothing else.** A page's state is in the address
 * because that is the property the demo is built on — a screen can be linked to, reloaded and sent
 * to somebody else — and the same property the real app has. The names below are the demo's; the
 * app gets the same facts from an API, so none of this travels into `skillmaster-web/`.
 */

export const CONSOLE_PAGES = [
  'discover', 'skills', 'my-tags', 'skill', 'editor', 'account', 'keys', 'sessions',
] as const
export const AUTH_PAGES = ['login', 'register', 'reset'] as const

export type ConsolePage = (typeof CONSOLE_PAGES)[number]
export type AuthPage = (typeof AUTH_PAGES)[number]
export type PageName = ConsolePage | AuthPage

export interface StateOption {
  key: string
  label: string
  /** The strip's way into a moment that needs facts in the address; the key is its name. */
  params?: 'created' | 'rotated'
  /** A state whose address is more than `?page=&state=` — discovery's results need a query. */
  href?: string
}

export const STATES: Record<ConsolePage, StateOption[]> = {
  /* **首次进入没有结果，而且不该有。** 公域不整表列，所以默认态是「输入关键词或选一个分类」；
     「有结果」必须带一个真实查询（`q=PDF`），因为没有任何地址能让公域列出全部。 */
  discover: [
    { key: 'first', label: '首次进入' },
    { key: 'results', label: '有结果', href: '?page=discover&q=PDF' },
    { key: 'empty', label: '没有结果', href: '?page=discover&q=zzz' },
    { key: 'joining', label: '加入中', href: '?page=discover&q=PDF&join=busy' },
    { key: 'join-failed', label: '加入失败', href: '?page=discover&q=PDF&join=failed' },
    { key: 'loading', label: '加载中' },
    { key: 'failed', label: '出错' },
  ],
  skills: [
    { key: 'ready', label: '正常' },
    { key: 'empty', label: '空' },
    { key: 'dormant', label: '只有休眠' },
    { key: 'loading', label: '加载中' },
    { key: 'failed', label: '出错' },
  ],
  'my-tags': [
    /* **演示条上的名字也照用户会看到的状态写**（2026-10-23）：「只有休眠」「达到 100 个」是模型
       与计数的说法，读的人得先翻译一次；这里换成那一屏上真正的那句话。 */
    { key: 'ready', label: '正常' },
    { key: 'empty', label: '还没有标签' },
    { key: 'dormant', label: '标签暂不可见' },
    { key: 'full', label: '已满 100 个' },
    { key: 'loading', label: '加载中' },
    { key: 'failed', label: '加载失败' },
  ],
  skill: [
    { key: 'ready', label: '正常' },
    { key: 'no-title', label: '没有 title' },
    { key: 'missing', label: '没有这个 skill' },
  ],
  account: [
    { key: 'ready', label: '正常' },
    { key: 'anonymous', label: '没登录' },
    { key: 'unknown', label: '状态读不到' },
  ],
  keys: [
    { key: 'ready', label: '正常' },
    { key: 'new', label: '新建表单' },
    { key: 'empty', label: '暂无密钥' },
    { key: 'created', label: '已创建', params: 'created' },
    { key: 'rotated', label: '已续期', params: 'rotated' },
    { key: 'failed', label: '加载失败' },
  ],
  sessions: [
    { key: 'ready', label: '正常' },
    { key: 'empty', label: '仅当前设备' },
    { key: 'failed', label: '加载失败' },
  ],
  /**
   * **The editor's states are the ones a screenshot has to show and a click cannot reach.**
   * 新建 with nothing typed is the default; 已有草稿 is the ordinary working state; the rest are the
   * moments the page exists to handle (a buffer, a conflict, a duplicate, a binary, a path field, a
   * rename, a delete, a leave, a failed save, a version that is gone, loading).
   */
  editor: [
    { key: 'new', label: '新版本' },
    { key: 'blank', label: '空白底稿' },
    { key: 'base-draft', label: '基于草稿' },
    { key: 'draft', label: '已有草稿' },
    { key: 'note', label: '说明已保存' },
    { key: 'name', label: '名称已保存' },
    { key: 'name-error', label: '名称清空' },
    { key: 'label-error', label: '标签写入失败' },
    { key: 'dirty', label: '未保存' },
    { key: 'conflict', label: '冲突' },
    { key: 'duplicate', label: '内容重复' },
    { key: 'duplicate-draft', label: '内容重复（草稿）' },
    { key: 'binary', label: '二进制文件' },
    { key: 'new-file', label: '新建文件' },
    { key: 'new-file-error', label: '新建：路径占用' },
    { key: 'new-folder', label: '新建文件夹' },
    { key: 'empty-folder', label: '空文件夹' },
    { key: 'rename', label: '重命名' },
    { key: 'delete', label: '删除确认' },
    { key: 'leave', label: '离开确认' },
    { key: 'error', label: '保存失败' },
    { key: 'loading', label: '加载中' },
    { key: 'missing', label: '没有这一版' },
    { key: 'denied', label: '不能编辑' },
  ],
}

export const DEFAULT_STATE: Record<ConsolePage, string> = {
  discover: 'first',
  skills: 'ready',
  'my-tags': 'ready',
  skill: 'ready',
  editor: 'new',
  account: 'ready',
  keys: 'ready',
  sessions: 'ready',
}

export function isConsolePage(page: string | null): page is ConsolePage {
  return page !== null && (CONSOLE_PAGES as readonly string[]).includes(page)
}

export function isAuthPage(page: string | null): page is AuthPage {
  return page !== null && (AUTH_PAGES as readonly string[]).includes(page)
}

/** The state a page is in: the one the address asks for when it is one of that page's states. */
export function stateOf(page: ConsolePage, params: URLSearchParams): string {
  const asked = params.get('state')
  return STATES[page].some((option) => option.key === asked) ? (asked as string) : DEFAULT_STATE[page]
}

/** **一页画出六种人看到的东西。** The demo's stand-in for a server-computed permission. */
export const TIERS = [
  { key: 'owner', label: '所有者' },
  { key: 'admin', label: '管理' },
  { key: 'maintain', label: '维护' },
  { key: 'write', label: '写入' },
  { key: 'browse', label: '浏览' },
  { key: 'invoke', label: '调用' },
] as const

export function tierRank(key: string | null): number {
  if (key === null) return -1
  return TIERS.findIndex((tier) => tier.key === key)
}
