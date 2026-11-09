/**
 * 服务凭据的夹具。
 *
 * ⚠️ 后端还没有表。这里不发明的是**形状**：
 * - **身份是 id**（`sec_7f3a`），名称只是给人看的标签——所以名称可以是中文、可以带空格；
 * - 值属于用户，**绑定** `(凭据, skill, 环境变量名)` 才是暴露面，一条值可以有多条绑定；
 * - 界面永远不显示值；用量按「取用次数」计（每次 `skmr run` 用一条凭据算一次）。
 */

export interface CredentialBinding {
  /** 绑定的 skill 地址。 */
  skill: string
  /** 注入到子进程的环境变量名。 */
  env: string
}

/** 一条已经生效的共享：哪一条凭据、在哪个 skill 上、给了谁。 */
export interface SecretShareRow {
  skill: string
  who: string
}

export interface ServiceCredential {
  /** 命令里用的那个身份；名称不是身份。 */
  id: string
  name: string
  /** 一条值可以有多个绑定；没绑过的 skill 取不到它。 */
  bindings: CredentialBinding[]
  /** 今天已经取用了几次（按凭据计，不按绑定计）。 */
  used: number
  /** 每日上限；超过就是「已达上限」。 */
  limit: number
  lastUsed: string
  /** **已经共享给谁。** 打开页面时要能看见并撤销——只靠本次会话的内存，第二天就撤不掉了。 */
  shared: SecretShareRow[]
}

/** 默认上限是 **500**，硬上限 10,000 在服务端；演示件只画默认那一档。 */
export const SECRET_LIMIT = 500

export const SECRETS: ServiceCredential[] = [
  {
    id: 'sec_7f3a',
    name: '知乎 cookie',
    // 同一把 cookie 用在两个 skill 上，变量名也不同——只存这一条。
    bindings: [
      { skill: 'dev/zhihu', env: 'ZHIHU_COOKIE' },
      { skill: 'dev/xhs-helper', env: 'XHS_COOKIE' },
    ],
    used: 12,
    limit: SECRET_LIMIT,
    lastUsed: '今天 09:24',
    // 已经共享出去的：重新打开这一页也要看得见，否则「随时能撤销」在界面上没有落点。
    shared: [{ skill: 'dev/zhihu', who: 'yuhan' }],
  },
  {
    id: 'sec_2b91',
    name: '搜索 key',
    bindings: [{ skill: 'dev/zhihu', env: 'SEARCH_API_KEY' }],
    used: 3,
    limit: SECRET_LIMIT,
    lastUsed: '昨天 18:02',
    shared: [],
  },
  {
    id: 'sec_c40e',
    name: '观察者网 cookie',
    // 四个绑定：这一行用来说清「绑得多的时候长什么样」。
    bindings: [
      { skill: 'dev/guancha', env: 'GUANCHA_COOKIE' },
      { skill: 'dev/guancha-digest', env: 'GUANCHA_COOKIE' },
      { skill: 'dev/news-brief', env: 'NEWS_COOKIE' },
      { skill: 'dev/daily-read', env: 'READ_COOKIE' },
    ],
    used: SECRET_LIMIT,
    limit: SECRET_LIMIT,
    lastUsed: '今天 11:10',
    shared: [],
  },
]

/**
 * 共享面板里能选的人：**按 skill 取**，只列这个 skill 已接受的协作者。
 *
 * 与 `fixtures/skill.ts` 的 `GRANTS` 是同一批人——真实产品里两边读的是同一份授权；这里不按
 * skill 各存一份，是因为演示件只需要说明「名单随 skill 变」这件事。
 */
const COLLABORATORS: Record<string, string[]> = {
  'dev/zhihu': ['yuhan', 'hexu', 'zhao'],
  'dev/xhs-helper': ['yuhan'],
  'dev/guancha': ['chen', 'li'],
  'dev/guancha-digest': ['chen'],
  'dev/news-brief': ['wang', 'wu'],
  'dev/daily-read': [],
}

export function collaboratorsOf(skill: string): string[] {
  return COLLABORATORS[skill] ?? []
}

/** 能绑定到的 skill。真实产品里是「你能调用的全部 skill」。 */
export const SECRET_SKILLS = ['dev/zhihu', 'dev/guancha', 'dev/xhs-helper', 'dev/news-brief', 'dev/daily-read']
