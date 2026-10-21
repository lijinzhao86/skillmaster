import { useEffect, useRef, useState } from 'react'
import type { MouseEvent as ReactMouseEvent } from 'react'
import { codeMessage, fieldErrors } from '../../api/errors'
import { createKey } from '../../api/keys'
import type { ApiKey, ApiKeyPolicy } from '../../api/types'
import FormBanner from '../FormBanner'

/**
 * 新建 API 密钥 — the form screen of 访问凭据, on its own.
 *
 * **It owns everything the form alone knows**: the four fields, the date floor and ceiling the
 * deployment's policy gives, the field-level refusal, and the in-flight flag that closes both ways
 * out while a request is running. What it does not own is what happens to the key afterwards — the
 * string exists on the next screen, which is the page's — so it reports `onCreated` and stops.
 *
 * **Why it is a component and not part of the page.** The page is three screens of one flow; the
 * list and the moment share the page's facts, while this one shares none of them except the banner
 * it writes its request failures into and the list both ways out return to.
 */
export interface KeyCreateFormProps {
  /** The deployment's expiry policy: it seeds the date and caps it. `null` until the list loads. */
  policy: ApiKeyPolicy | null
  /** Where this form's two ways out go — the list, with the filter and search the reader had. */
  listHref: string
  /** The page's banner, so this form's failures appear where the list's do. */
  banner: string | null
  onBanner: (message: string | null) => void
  onCreated: (key: ApiKey, token: string) => void
  onCancel: () => void
}

interface FormState {
  name: string
  note: string
  scope: 'read' | 'write'
  expires: string
}

/**
 * The day a key should stop working, as a date input wants it: `YYYY-MM-DD`, **read in Shanghai**.
 *
 * The value the picker holds means "the end of that day here", and `expiresAt` pins the offset to
 * +08:00 — so the calendar day has to come from the same zone as the offset. Read from the browser's
 * clock instead, it is off by one for anybody west of us: at 20:00 UTC it is already the 16th in
 * Shanghai, and a picker whose `min` said the 15th would offer a day that is over before it starts
 * (the server refuses it: `invalid_expiry` / `not_in_the_future`).
 */
const SHANGHAI_DAY = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  timeZone: 'Asia/Shanghai',
})

function shanghaiDay(): { year: number; month: number; day: number } {
  // `formatToParts` rather than parsing the formatted string: the numbers are read as numbers instead
  // of as a shape one locale's separator happens to have, and a bad parse would produce a `NaN` date
  // that reaches the input and then `toISOString` — which throws, leaving a submit button disabled
  // with nothing on screen to explain it.
  const parts = SHANGHAI_DAY.formatToParts(new Date())
  const value = (type: Intl.DateTimeFormatPartTypes): number =>
    Number(parts.find((part) => part.type === type)?.value ?? Number.NaN)
  return { year: value('year'), month: value('month'), day: value('day') }
}

function todayPlus(days: number): string {
  const { year, month, day } = shanghaiDay()
  // Arithmetic in UTC on a date-only value: no zone is involved in "three days after the 13th", and
  // going through a local `Date` here is what would reintroduce the shift this function exists to
  // avoid.
  const at = new Date(Date.UTC(year, month - 1, day + days))
  const m = String(at.getUTCMonth() + 1).padStart(2, '0')
  const d = String(at.getUTCDate()).padStart(2, '0')
  return `${at.getUTCFullYear()}-${m}-${d}`
}

/**
 * The last day this deployment issues, or nothing when it does not care about expiry.
 *
 * **The server's day, not this page's arithmetic**, and the same value the form preselects: the
 * deployment's lifetime is both the default and the ceiling (ADR 0042), so the last day it will
 * issue is exactly the day the form opens on. Handing it to the picker as `max` means nobody is
 * offered a date the server would refuse — and taking it from `policy` rather than from `todayPlus`
 * is what keeps that true when this browser's clock is ahead of the server's.
 *
 * **The fallback is for a server that sends a policy without `latest_day`** — a rolled-back
 * deployment, or a bundle from this build against one. It is deliberately the arithmetic the server
 * would have done, used only where the server did not answer, and it can be a day long if the
 * server's clock is behind this one's: the server then refuses that day with `beyond_maximum`, which
 * is the graceful half of the two. The alternative — no preselection at all — is a form whose own
 * hint says it preselected something, which is the sentence this exists to keep true.
 */
function latestDayOf(current: ApiKeyPolicy | null): string | undefined {
  if (current?.expires !== true) {
    return undefined
  }
  // `typeof` rather than a truthiness test: a policy from a server older than `latest_day` may also be
  // older than `lifetime_days`, and `todayPlus(undefined)` would build a `NaN` date that reaches the
  // input. Nothing is offered in that case, and the hint says so.
  return (
    current.latest_day ??
    (typeof current.lifetime_days === 'number' ? todayPlus(current.lifetime_days) : undefined)
  )
}

export default function KeyCreateForm({
  policy,
  listHref,
  banner,
  onBanner,
  onCreated,
  onCancel,
}: KeyCreateFormProps) {
  const latestDay = latestDayOf(policy)

  /** A fresh form on every opening: the component mounts when the screen opens, so this is that. */
  const [form, setFormState] = useState<FormState>({
    name: '',
    note: '',
    scope: 'read',
    expires: latestDay ?? '',
  })
  const formRef = useRef(form)
  const [fieldMessage, setFieldMessage] = useState<string | null>(null)
  const [submitting, setSubmittingState] = useState(false)
  const submittingRef = useRef(false)

  // The first day the picker may offer: today, in the zone `expiresAt` pins the date to.
  const [today] = useState(() => todayPlus(0))

  /** The ref is written with the state it mirrors, in the same tick. */
  function setForm(next: FormState): void {
    formRef.current = next
    setFormState(next)
  }

  function setSubmitting(next: boolean): void {
    submittingRef.current = next
    setSubmittingState(next)
  }

  /** One field's edit, written through the ref so nothing that reads the form can see an older draft. */
  function updateForm(patch: Partial<FormState>): void {
    setForm({ ...formRef.current, ...patch })
  }

  // **The preselection arrives with the policy, which may land after this form opened.** The listing
  // is what carries the deployment's lifetime, so a form opened before it answers starts with an
  // empty date; seeding only at mount would leave it empty for exactly the reader who was quick.
  useEffect(() => {
    if (formRef.current.expires !== '') {
      return
    }
    setForm({
      ...formRef.current,
      expires: policy?.expires === true ? (latestDayOf(policy) ?? '') : '',
    })
    // The seed is about a policy arriving; the form's own fields are read through the ref.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policy])

  // Leaving the form without creating anything: the list keeps its filter and its search term (they
  // were the reader's, not the form's), and the messages about this form go with it.
  function leaveForm(): void {
    onBanner(null)
    setFieldMessage(null)
    onCancel()
  }

  /**
   * The breadcrumb is a way out of this form, so it is closed while the form is submitting.
   *
   * **It is the same rule as 取消's `:disabled`, and it has to be applied to both or the pair argues
   * with itself.** Leaving mid-submission is what makes the answer land on a screen nobody is on — and
   * on this screen the answer is the only appearance the new key's string ever makes, so a click on a
   * link that merely *looks* like navigation (a real `href`, a real page load) destroys it. `preventDefault`
   * rather than a greyed-out span, because this is a link and its whole value is that it can be opened
   * in a new tab: what is refused is leaving *this* form, not the address.
   */
  function guardCrumb(event: ReactMouseEvent<HTMLAnchorElement>): void {
    if (submittingRef.current) {
      event.preventDefault()
    }
  }

  async function submit(): Promise<void> {
    if (submittingRef.current) {
      // **A second submission would throw the first one's string away.** Two keys would be created and
      // the second shown: the first one's only appearance was already spent, and nothing on this page
      // can show it again. The disabled button is the visible half of this rule and it is not the
      // load-bearing half — an implicit form submission (Enter, a script, an extension) does not
      // consult a disabled default button, and this is one line.
      return
    }
    setFieldMessage(null)
    onBanner(null)
    setSubmitting(true)
    const current = formRef.current
    const result = await createKey({
      name: current.name.trim(),
      note: current.note.trim() === '' ? null : current.note.trim(),
      // Only the intent is sent: 「读写」 asks for write, and that write implies read is the server's
      // rule, applied when it writes the row (ADR 0039 §决定 2). Sending both here would put the rule
      // in two places, and the one that forgot it would be this one.
      scopes: current.scope === 'write' ? ['skills:write'] : ['skills:read'],
      expires_at: expiresAt(),
    })
    setSubmitting(false)
    if (!result.ok) {
      const onField = fieldErrors(result.details, result.message)
      // `scope` is the one the server can now name on this form — the permission group is a field like
      // any other, and「至少要选一项权限」was written for it and had no way of reaching the screen.
      const named = onField.name ?? onField.note ?? onField.scope ?? onField.expires_at
      if (named !== undefined) {
        setFieldMessage(named)
        return
      }
      onBanner(codeMessage(result.code, result.message))
      return
    }
    onCreated(result.data.key, result.data.token)
  }

  /**
   * The chosen day, as the instant the server stores.
   *
   * End of that day in this product's own timezone rather than midnight UTC: a key that expires "on
   * 2027/01/13" is one that works through that day, and the listing shows the same date back. Sending
   * midnight would make it stop working at eight in the morning here, which is not what the date
   * picker said.
   */
  function expiresAt(): string | null {
    if (!policy?.expires || formRef.current.expires === '') {
      return null
    }
    const at = new Date(`${formRef.current.expires}T23:59:59+08:00`)
    if (Number.isNaN(at.getTime())) {
      // A date the picker would not produce, typed or pasted in. Sent as "no date named" rather than
      // thrown about: `toISOString` on an invalid date raises, and this runs after 提交 has already been
      // disabled, so a bad string would leave a dead button and no explanation.
      return null
    }
    return at.toISOString()
  }

  return (
        <section className="page">
          <nav className="crumbs" aria-label="面包屑">
            {/* **The breadcrumb goes where 取消 goes** — design §访问凭据: 「面包屑和这个按钮去同一处」.
               Cancelling keeps the list's state (a filter and a search term were the reader's, not the
               form's), so a breadcrumb that dropped them would leave this screen's two ways out pointing
               at two different lists. And it is closed exactly when 取消 is: see `guardCrumb`.
               Three segments while the form is open: the area, the page — a link *back*, because the
               trail's last segment is the action you are taking — and then the action. */}
            <a href="/account">设置</a>
            <span className="crumbs-sep" aria-hidden="true">
              /
            </span>
            <a
              href={listHref}
              className={submitting ? 'is-blocked' : undefined}
              onClick={guardCrumb}
            >
              API 密钥
            </a>
            <span className="crumbs-sep" aria-hidden="true">
              /
            </span>
            <span className="crumbs-here" aria-current="page">
              新建 API 密钥
            </span>
          </nav>

          <header className="page-head">
            <h1>新建 API 密钥</h1>
          </header>

          <FormBanner message={banner} />

          <div className="key-card">
            <div className="key-head">
              <span className="key-count">基本信息</span>
            </div>
            <div className="key-body">
              <form
                className="key-form"
                noValidate
                onSubmit={(event) => {
                  event.preventDefault()
                  void submit()
                }}
              >
                <div className="field">
                  <label htmlFor="key-name">名称</label>
                  <input
                    id="key-name"
                    value={form.name}
                    className="control"
                    type="text"
                    maxLength={60}
                    autoComplete="off"
                    placeholder="笔记本"
                    aria-describedby="key-name-hint"
                    onChange={(event) => updateForm({ name: event.target.value })}
                  />
                  {/* The deliverable's own sentence promises a rename this version cannot do
                       (「之后还能改」); `docs/.../technical-design.md` §接口 has no rename endpoint and
                       「允许重名」 is what the model actually permits, so this is the deliverable's stale
                       side — recorded in `design/README.md` rather than copied. */}
                  <p className="hint" id="key-name-hint">
                    用在哪台机器、哪个用途。名字只给你自己看，允许重名。
                  </p>
                </div>

                <div className="field">
                  <label htmlFor="key-note">说明</label>
                  <textarea
                    id="key-note"
                    value={form.note}
                    className="control"
                    rows={2}
                    maxLength={255}
                    placeholder="例如：CI 上读 pdf-tools 用的"
                    onChange={(event) => updateForm({ note: event.target.value })}
                  ></textarea>
                  <p className="hint">
                    会显示在列表里。名称只够认出它是哪一把，说明才是你半年后需要的那句话。
                  </p>
                </div>

                <fieldset className="field">
                  <legend>权限</legend>
                  <label className="key-choice">
                    <input
                      type="radio"
                      value="read"
                      checked={form.scope === 'read'}
                      onChange={() => updateForm({ scope: 'read' })}
                    />
                    <span className="key-choice-body">
                      <span className="key-choice-title">读取</span>
                      <span className="key-choice-note">能读你的 skill 与它们的文件。</span>
                    </span>
                  </label>
                  <label className="key-choice">
                    <input
                      type="radio"
                      value="write"
                      checked={form.scope === 'write'}
                      onChange={() => updateForm({ scope: 'write' })}
                    />
                    <span className="key-choice-body">
                      <span className="key-choice-title">读写</span>
                      <span className="key-choice-note">
                        上面全部，另加提交新版本——<strong>上线仍然只能在浏览器里由人点</strong>。
                      </span>
                    </span>
                  </label>
                </fieldset>

                {policy !== null && policy.expires ? (
                  <div className="field">
                    <label htmlFor="key-expires">有效期</label>
                    <input
                      id="key-expires"
                      value={form.expires}
                      className="control"
                      type="date"
                      min={today}
                      max={latestDay}
                      onChange={(event) => updateForm({ expires: event.target.value })}
                    />
                    <p className="hint">
                      {latestDay !== undefined ? (
                        <>
                          已按这个部署的上限预选 {policy.lifetime_days}{' '}
                          天——这里签出的密钥不会超过这个期限。
                        </>
                      ) : (
                        <>留空就按这个部署的默认值签——这里签出的密钥不会超过这个期限。</>
                      )}{' '}
                      到期后这把密钥就失效了，用它的机器会收到 401；可以在列表里续期。
                    </p>
                  </div>
                ) : null}

                {fieldMessage !== null ? (
                  <p className="field-error">{fieldMessage}</p>
                ) : null}

                <div className="key-form-foot">
                  {/* `leaveForm` rather than a bare `mode = 'list'`: the banner belongs to the request being
                     abandoned, and left up it sits above the list saying something about a creation nobody
                     is doing any more. */}
                  {/* Disabled while a submission is in flight, the way a row's own actions are: leaving
                     then is what makes the answer arrive on a screen nobody is on. */}
                  <button
                    type="button"
                    className="button is-quiet"
                    disabled={submitting}
                    onClick={leaveForm}
                  >
                    取消
                  </button>
                  <button
                    type="submit"
                    className="button"
                    data-key-submit=""
                    disabled={submitting || form.name.trim() === ''}
                  >
                    新建密钥
                  </button>
                </div>
              </form>
            </div>
          </div>
        </section>
  )
}
