import { formatMib } from '../format'
import { issueSentence } from '../nameRules'
import type { ErrorDetail } from './types'

/** The fields these forms have. The server names them the same way in `details[].field`. */
export type Field =
  | 'phone'
  | 'username'
  | 'password'
  | 'code'
  | 'captcha'
  | 'handle'
  | 'description'
  // The credential screen's fields. `id` is here because refusing to renew a revoked key is about
  // the key the request named rather than about anything in the body.
  | 'name'
  | 'note'
  | 'scope'
  | 'expires_at'
  | 'id'

/**
 * What to say for each issue code the server can send.
 *
 * The codes come from the server's own policy classes, and they are written here rather than shown
 * to anybody: an issue code is a stable string for programs, and "invalid_length" is not a sentence.
 */
const ISSUE_MESSAGES: Record<string, string> = {
  required: '这一项必填。',
  invalid_format: '填写的内容格式不正确，请检查后重试。',
  invalid_length: '长度不符合要求，请检查后重试。',
  // The fields that can actually earn these two say something more useful in FIELD_SPECIFIC below;
  // what is here is the floor, for a field this version has not met.
  too_short: '长度不足，请检查后重试。',
  too_long: '长度超出限制，请检查后重试。',
  same_as_username: '密码不能与用户名相同。',
  same_as_phone: '密码不能与手机号相同。',
  already_taken: '这个用户名已经被占用。',
  already_registered: '这个手机号已经注册过了。',
  no_account: '这个手机号还没有注册。',
  invalid: '填写的内容不正确，请重新输入。',
  // One sentence for two causes — a password from the common list, and one built out of the
  // handle or the number — because both are answered the same way and neither is worth a second
  // round of copy.
  too_common: '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。',
  // The credential screen's four refusals (technical-design.md §接口). Written as advice rather
  // than as a restatement of the server's rule: the person reading them wants to know what to do.
  blank: '这一项不能为空。',
  empty: '至少要选一项权限——一把什么都做不了的密钥，用起来像是坏的。',
  unknown: '这里有一个不认识的取值，请刷新页面后重试。',
  not_in_the_future: '到期时间要在将来——填一个过去的日期，签出来的密钥一天也活不了。',
  revoked: '这把密钥已经被撤销，不能再续期。请新建一把。',
  // The deployment's lifetime is a ceiling, not a suggestion (ADR 0042). Written without the number:
  // the message table is static, and the form's own hint already states how many days this deployment
  // allows — a second copy of that number here is a second thing to keep in step.
  beyond_maximum: '超过这个部署允许的最长有效期，把日期往前调。',
  // v14's tag writes. Every one travels as a 400 `invalid_request` with the rule in `details[].issue`
  // (TD §错误形状), so the code alone cannot say which sentence to show.
  name_taken: '同一个名字已经有了。换一个名字；已有的那个可以直接贴到 skill 上（信息页的「+」或 CLI）。',
  category_name_taken: '这个名字和公开分类重名，换一个。',
  tag_limit_reached:
    '标签数量到上限了：每个 skill 最多 20 个我的标签、10 个公开标签；每个账号最多 100 个定义。',
  invalid_name: '名字要是 1–24 个字（去掉首尾空格、内部连续空格压成一个），不能带 #。',
  missing: '缺少分类。',
  unknown_category: '词表里没有这个分类。',
  category_retired: '这个分类已经退役，不能再用了。',
  category_stopped: '这个分类已停用，不能再新设；已经贴着的可以保留或换走。',
}

/**
 * What to say for each error code the envelope can carry.
 *
 * The envelope's own `message` is written for whoever is reading a log — the server's strings are
 * English and phrased for an operator — so it is a fallback, never the thing on screen. A code this
 * version does not know still gets said out loud rather than swallowed.
 */
const CODE_MESSAGES: Record<string, string> = {
  // The author plane's two "there is nothing at this address" codes. Without a wording the person
  // gets the envelope's own text, which this file says is English and written for a log — and the
  // skill pages do reach these: a comparison whose `?from=` names nothing, or a file a version no
  // longer has, are both addresses a person can arrive at.
  skill_not_found: '这个地址在你的 skill 里找不到。',
  file_not_found: '这一版里没有这个文件。',
  too_many_requests: '操作太频繁了，请稍后再试。',
  invalid_credentials: '手机号或密码不正确。',
  verification_code_invalid: '短信验证码不正确或已过期，请重新获取。',
  forbidden: '请求被拒绝了，请刷新页面后重试。',
  unauthenticated: '登录状态已失效，请重新登录。',
  invalid_request: '提交的内容有问题，请检查后重试。',
  key_not_found: '这把密钥不在你的账号里——它可能已经被删掉了。',
  session_not_found: '这台设备不在列表里了——它可能已经在别处退出过。',
  invalid_scope: '权限的取值不对，请刷新页面后重试。',
  invalid_expiry: '到期时间不合法，请重新选择。',
  // v6's version lifecycle and labels. Each one says what happened and what to do; the server's
  // English text is written for an operator and is only a fallback.
  draft_name_required: '这一版需要一个名称，1–40 个字符。',
  draft_name_too_long: '名称最多 40 个字符。',
  // The server raises these for any row that is no longer a draft — published, revoked **or
  // discarded** — so the sentence must not claim it was published.
  draft_name_is_frozen: '这一版已经不是草稿，名称不能再改。',
  note_too_long: '说明最多 200 个字符。',
  note_is_frozen: '这一版已经不是草稿，说明不能再改。',
  // v18: the draft's kind, and the two refusals the publish path can meet because of it. The kind is
  // frozen once a version is live — it is an input to a number that has already been computed. The
  // other one says what to do, because "choose another kind" cannot help: a baseline with no number
  // or a prerelease baseline needs a number typed in.
  release_type_is_frozen: '这一版已经不是草稿，版本类型不能再改。',
  release_type_needs_explicit_name:
    '线上这一版没有可递增的版本号（没有号，或是预发布版本），上线时要直接指定一个。',
  // The batch route's request-level refusal: a skill appears twice, so "which one counts" has no
  // answer. The page makes it unreachable (two drafts of one skill cannot both be ticked); this is
  // for a stale tab.
  duplicate_target: '同一批里同一个 skill 出现了两次；请刷新页面后重新选择。',
  invalid_release_name: '版本号要写成 SemVer，例如 1.2.3。',
  release_name_required: '这个草稿还没有版本号，上线时要指定一个。',
  version_already_exists: '这个版本号已经被同一 skill 的另一版用了；版本号不能复用。',
  version_name_is_assigned: '这一版已经有版本号了，不能改成别的。',
  invalid_version_state: '这一版目前的状态不能做这个操作，刷新页面看看它现在是什么状态。',
  // v8's editor. The three content refusals that reach a banner have a sentence here because the
  // banner's own line is a prefix: it says the save failed, this says why.
  if_match_required: '缺少这一版的基线版本，刷新页面后重试。',
  digest_mismatch: '这一版在你编辑期间被改过了。',
  content_already_exists: '同一 skill 下不能存在相同内容。',
  file_already_exists: '这一版里已经有这个路径了。',
  skill_md_cannot_be_deleted: 'SKILL.md 是这一版的正文，不能删除或改名。',
  last_file_cannot_be_deleted: '一版至少要有一个文件。',
  invalid_path: '这个路径不合法：不能与已有的文件或文件夹同名。',
  invalid_upload: '这份内容超出了这一版允许的大小，或 frontmatter 不合法。',
  // v9: a name that still belongs to a deleted skill. Its only way out is restoring it in the
  // browser, so the sentence says where (ADR 0071); the dialog draws the link beside it.
  skill_deleted: '这个名字属于一个已删除的 skill；要先恢复它，才能继续用这个名字。',
  skill_already_exists: '这个名字已经有了；去那一页继续，别再建一个。',
  // `reserved_path` deliberately has no entry here: the editor's refusal (writing / moving to the
  // metadata path) has no sentence in the design yet — Q6 in the version's technical design — so it
  // falls back to the server's own text rather than to a sentence this file invented.
  version_is_not_draft: '这一版已经不是草稿，文件不能再改。',
  version_revoked: '这一版已下架，调用会失败；要恢复读取，先重新上架。',
  skill_offline: '这个 skill 已下线，暂时不提供服务。',
  network_error: '网络请求失败，请检查网络后重试。',
  timeout: '服务响应太慢，请稍后重试。',
  internal_error: '服务暂时不可用，请稍后重试。',
  // v14's library and tag refusals. Each one is a different answer, which is why they are codes
  // rather than one 409 sentence; the console shows the same words the CLI does.
  skill_not_joinable:
    '这个 skill 现在加入不了：它可能已经在你的库里（你创建的或共享给你的），也可能已下线、或还没有可用的版本。',
  library_reference_missing: '这个 skill 没有可移除的加入引用。',
  library_entry_dormant:
    '这个 skill 的库条目现在不生效（可能已下线或暂时不可见），恢复可见后再试。',
  tag_not_attached: '这个 skill 上没有这个标签，没有可摘的。',
  tag_definition_not_found: '这个标签不在你的账号里了——它可能已经被删掉。',
  skill_not_deleted: '这个 skill 没有被删除，不需要恢复。',
}

/** The interim import ceiling (P3 removes it); it covers the package itself and its decompressed total. */
const IMPORT_CEILING_BYTES = 128 * 1024 * 1024

/**
 * One of v39's four size sentences, from the numbers the server sends with the refusal.
 *
 * <p>`package_too_large` is one issue for two ceilings — a version's total and the import cap,
 * which itself covers the package's own size and its decompressed total — and only the limit the
 * detail carries tells them apart. Choosing by the operation instead would make the sentence wrong
 * for whichever path the page did not think of.
 */
export function sizeSentence(detail: ErrorDetail): string | null {
  // The server's wire is snake_case (`property-naming-strategy: SNAKE_CASE`), so these are the
  // names that arrive — `actualBytes` would silently be `undefined` forever.
  const limit = detail.limit_bytes
  const actual = detail.actual_bytes
  // No limit, no sentence: a refusal from an older server says only the issue, and guessing the
  // number would be stating a ceiling this client has no business inventing.
  if (limit === undefined) return null
  switch (detail.issue) {
    case 'file_too_large': {
      // `field` is the file's path when the server knew it, and the literal `file` when the
      // refusal came from a single-file route that never saw a path.
      if (actual === undefined) {
        return `这个文件超过了单文件上限 ${formatMib(limit)}。`
      }
      return detail.field !== 'file' && detail.field !== ''
        ? `文件 ${detail.field} 有 ${formatMib(actual)}，超过单文件上限 ${formatMib(limit)}。`
        : `这个文件有 ${formatMib(actual)}，超过单文件上限 ${formatMib(limit)}。`
    }
    case 'package_too_large':
      if (limit <= IMPORT_CEILING_BYTES) {
        // The same sentence covers both guards — the package's own size and its decompressed total.
        return `整包导入最多 ${formatMib(limit)}；更大的包请逐个文件上传。`
      }
      return actual === undefined
        ? `这一版超过了 ${formatMib(limit)} 的上限。`
        : `这一版有 ${formatMib(actual)}，超过 ${formatMib(limit)} 的上限。`
    case 'too_many_files':
      return `这一版最多 ${limit} 个文件。`
    default:
      return null
  }
}

/**
 * @param fallback the envelope's own message, used when this version has no wording for the code
 * @param details  the envelope's `details`; only consulted for `invalid_request`, where the code
 *                 alone cannot say which rule was broken
 */
export function codeMessage(
  code: string,
  fallback: string,
  details: ErrorDetail[],
): string {
  // v33: a write's name refusal carries the path and the reason in `details`, exactly like the
  // editor's own inline check does before the request. Same sentence from the same module.
  if (code === 'invalid_path') {
    for (const detail of details) {
      const sentence = issueSentence(detail.issue, detail.field)
      if (sentence !== null) return sentence
    }
  }
  if (code === 'invalid_upload') {
    // v39's size refusals. The client checks the same numbers before sending, so this is the
    // fallback for a server-side answer (a race, or a path that bypassed the precheck); the server
    // sends the actual and allowed counts, so the sentence is the voice-spec one rather than a
    // restatement of the limit alone (voice-spec §上传与大小的四句话).
    for (const detail of details) {
      const sentence = sizeSentence(detail)
      if (sentence !== null) return sentence
    }
  }
  if (code === 'invalid_request') {
    for (const detail of details) {
      // Field-specific wording first: `too_long` on a tag's description and on a password are not
      // the same sentence even though they share an issue code.
      const byField = FIELD_SPECIFIC[detail.field as Field]?.[detail.issue]
      const issue = byField ?? ISSUE_MESSAGES[detail.issue]
      if (issue !== undefined) {
        return issue
      }
    }
  }
  return CODE_MESSAGES[code] ?? fallback
}

/**
 * The sentence for a failed call, carrying the envelope's `details` along.
 *
 * Most call sites hold the whole failure and used to unpack it only to drop `details`, which is
 * how every `invalid_request` became the same generic sentence. Handing over the result keeps the
 * two from drifting apart; `codeMessage` still exists for the few callers that have a code and a
 * message without details (a batch row, a refusal built from two fields).
 */
export function failureMessage(result: {
  code: string
  message: string
  details: ErrorDetail[]
}): string {
  return codeMessage(result.code, result.message, result.details)
}

/** Where the generic wording would not be enough to act on. */
const FIELD_SPECIFIC: Partial<Record<Field, Record<string, string>>> = {
  username: {
    invalid_format: '只能用 6–30 位小写字母、数字或连字符，且不能以连字符开头。',
    invalid_length: '长度需要 6–30 个字符。',
  },
  phone: {
    invalid_format: '请填写 11 位的大陆手机号。',
  },
  captcha: {
    invalid: '图形验证码不正确或已过期，已为你换了一张。',
  },
  code: {
    invalid_format: '请填写 6 位数字验证码。',
  },
  handle: {
    // Sharing names a person, and the two ways that can fail are both about the name rather than
    // about the request. The design has no field-error slot under the share input, so these land in
    // the block's banner; if that state is added to the design, move them there.
    // Saying that a username does not exist is deliberate here and not an oracle: registration
    // answers the same question with `already_taken`, by ADR 0034's own decision.
    no_such_user: '没有这个用户名，检查一下拼写。',
    already_yours: '不用共享给自己——这个 skill 本来就在你名下。',
  },
  description: { too_long: '说明最多 200 个字。' },
  password: {
    // The one rule here a person cannot guess, so it has to say what is allowed rather than that
    // something is wrong.
    invalid_format: '密码只能使用半角英文字母、数字和符号。',
    // Stated as the requirement rather than as the failure: 「太短了。」 says something is wrong
    // without saying what would be right, which leaves the person guessing at the rule.
    too_short: '密码至少需要 8 个字符。',
    too_long: '密码最多 72 个字符。',
  },
}

/**
 * The rule, said once for the two pages that set a password.
 *
 * It is shown rather than hidden because it is a restriction the person has to be told about: the
 * form refuses characters they may well have typed on purpose — a Chinese password, a full-width
 * one — and that has to be visible where they type rather than only as a refusal afterwards. The
 * second sentence is the answer to a rule that reads like an obstacle: length is what buys strength
 * here, not a pile of symbols.
 */
export const PASSWORD_HINT =
  '至少 8 个字符，只能使用半角英文字母、数字和符号（不能用中文或全角字符）。用一句长口令比堆特殊符号更安全。'

/**
 * Shown when the server asks for a captcha the client had not been showing.
 *
 * Registration's first send from an address needs no captcha and every send after it does, so a
 * client cannot know which one it is about to make. The server's answer to a request carrying none
 * is what tells it — and that answer is not a mistake the person made, so it needs a sentence that
 * says what to do rather than one that says something was wrong.
 */
export const CAPTCHA_NEEDED_HINT = '请先完成图形验证码，然后再继续。'

/**
 * Shown when a check could not be put to the server at all — it was unreachable, refused the request,
 * or answered in a shape that says nothing.
 *
 * Said rather than passed over in silence. It is **not** a verdict: nothing was concluded, so the
 * field shows no tick and no 「检查中…」. But the form's button waits for an answer, and a dark button
 * with nothing beside it is a dead end — so this is a sentence, drawn like every other message rather
 * than as a state of its own, and its wording is what says it is not a refusal.
 */
export const COULD_NOT_CHECK_HINT = '没能确认这一项是否可用，改动一下可以重新检查。'

/**
 * Shown when a check came back with something this version has no wording for.
 *
 * Not {@link COULD_NOT_CHECK_HINT}: that one says the question could not be put, and here it was put
 * and answered — with an issue code from a newer server, which is a refusal of some kind. This says
 * only what is true either way, because a sentence about the wrong thing is worse than a vague one.
 */
export const CHECK_REFUSED_HINT = '这一项没能通过检查，改动一下可以重新检查。'

/**
 * The server's `details` as a map keyed by the field the form shows.
 *
 * All three names for a captcha collapse onto `captcha`: the server refuses one under `captcha`, and
 * names the two request fields when it is complaining about which of them was sent.
 *
 * @param fallback what to say for an issue code this version does not know — the envelope's own
 *        message, so a server that learns a new word for something still says something true
 */
export function fieldErrors(
  details: ErrorDetail[],
  fallback: string,
): Partial<Record<Field, string>> {
  const errors: Partial<Record<Field, string>> = {}
  for (const detail of details) {
    const field = fieldOf(detail.field)
    if (field === null) {
      continue
    }
    errors[field] = messageFor(field, detail.issue, fallback)
  }
  return errors
}

/**
 * One field's wording for one issue code.
 *
 * Shared with the client-side checks in `validation.ts`, which produce the same issue codes as the
 * server: a value refused before it is sent has to read exactly like one refused after.
 */
export function messageFor(field: Field, issue: string, fallback: string): string {
  return FIELD_SPECIFIC[field]?.[issue] ?? ISSUE_MESSAGES[issue] ?? fallback
}

function fieldOf(wireField: string): Field | null {
  switch (wireField) {
    case 'captcha':
    case 'captcha_id':
    case 'captcha_answer':
      return 'captcha'
    case 'phone':
    case 'username':
    case 'password':
    case 'code':
    case 'handle':
    // The credential form's fields (ADR 0038). `id` is a field this form has in the sense that
    // matters: refusing to renew a revoked key is about the key the request named, and the sentence
    // belongs on the row's own error rather than in the banner above a list.
    case 'name':
    case 'note':
    case 'expires_at':
    case 'id':
      return wireField
    // The request body spells this one `scopes`, and so does the server's error — the field a client
    // has to fix is the one on the wire, not the concept. The screen calls it 权限, and one name for
    // it is enough.
    case 'scopes':
      return 'scope' 
    default:
      // A field this form does not have. Dropping it is right: showing it under a field that is not
      // on screen would be worse than the banner that already carries the message.
      return null
  }
}

/**
 * Whether an error belongs on a field or in the banner at the top.
 *
 * `invalid_credentials` and `verification_code_invalid` carry no details at all, deliberately: the
 * first says the same thing for three different failures, and hanging it on the password field would
 * quietly tell the visitor that the phone number exists.
 */
export function isBannerOnly(code: string): boolean {
  return code === 'invalid_credentials' || code === 'verification_code_invalid'
}
