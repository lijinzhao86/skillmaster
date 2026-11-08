import { useEffect, useRef, useState } from 'react'
import type { MouseEvent as ReactMouseEvent } from 'react'
import { fieldErrors, failureMessage } from '../../api/errors'
import { createKey } from '../../api/keys'
import type { ApiKey, ApiKeyPolicy } from '../../api/types'
import FormBanner from '../FormBanner'
import MenuSelect from '../MenuSelect'

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

type ExpiryChoice = '30' | '60' | '90' | '365' | 'custom' | 'never'

/** The presets the form offers, in days. `lifetime_days` is the same list's last entry; the
 *  console does not invent a seventh. */
const PRESETS = [30, 60, 90, 365] as const
const DEFAULT_DAYS = 90

interface FormState {
  name: string
  note: string
  scope: 'read' | 'write'
  expiry: ExpiryChoice
  /** The day the custom-date input holds; only read when `expiry === 'custom'`. */
  customExpiry: string
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

/** The choice a fresh form opens on: the product default, or a custom day if the server's default
 *  is not one of the presets. */
function defaultChoice(current: ApiKeyPolicy | null): ExpiryChoice {
  const days = current?.default_days ?? DEFAULT_DAYS
  return (PRESETS as readonly number[]).includes(days) ? (String(days) as ExpiryChoice) : 'custom'
}

/** The custom input's initial day: the product default, in the zone the form writes dates in. */
function defaultCustomDay(current: ApiKeyPolicy | null): string {
  return todayPlus(current?.default_days ?? DEFAULT_DAYS)
}

/** Whether the custom input holds a day this form can send: a `YYYY-MM-DD` inside the offered
 *  range. */
function usableDay(value: string, latest: string | null | undefined): boolean {
  return (
    /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    value >= todayPlus(0) &&
    (latest == null || value <= latest)
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
  const latestDay = policy?.latest_day ?? undefined

  /** A fresh form on every opening: the component mounts when the screen opens, so this is that. */
  const [form, setFormState] = useState<FormState>({
    name: '',
    note: '',
    scope: 'read',
    expiry: defaultChoice(policy),
    customExpiry: defaultCustomDay(policy),
  })
  const formRef = useRef(form)
  const [fieldMessage, setFieldMessage] = useState<string | null>(null)
  const [submitting, setSubmittingState] = useState(false)
  const submittingRef = useRef(false)
  const policySeeded = useRef(policy !== null)
  /** Whether the reader has already touched a choice; a late policy must not overwrite it. */
  const touched = useRef(false)

  // The first day the picker may offer: today, in the zone `expiresAt` pins the date to. Read on
  // every render rather than frozen at mount: a form left open across Shanghai midnight would
  // otherwise keep offering the day that has just ended, which the server refuses.
  const today = todayPlus(0)

  // A custom date the server would refuse is a disabled button, not a 400 after a round trip.
  const dayChosen = form.expiry !== 'custom' || usableDay(form.customExpiry, latestDay)

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
    touched.current = true
    setForm({ ...formRef.current, ...patch })
  }

  // **The choices arrive with the policy, which may land after this form opened.** The form can be
  // entered from a listing that has already answered, but a quick reader can beat it; when the
  // policy lands, align the default choice and the custom day with it (ADR 0171 §接口).
  useEffect(() => {
    if (policy === null || policySeeded.current) {
      return
    }
    policySeeded.current = true
    if (touched.current) {
      // The form only opens after the policy is loaded today, so this is a guard for a future
      // caller: a person's own choice outranks a seed that arrived late.
      return
    }
    setForm({
      ...formRef.current,
      expiry: defaultChoice(policy),
      customExpiry: defaultCustomDay(policy),
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
    if (!dayChosen) {
      // **The same rule the disabled button states, enforced again where the request is built.** An
      // empty or out-of-range custom date would otherwise reach `expiryRequest`, whose invalid-date
      // branch sends "no date named" — the server would answer with the 90-day default, creating a
      // key the person did not ask for. Enter can submit past a disabled button.
      setFieldMessage('请选择一个在允许范围内的到期日期。')
      return
    }
    setFieldMessage(null)
    onBanner(null)
    setSubmitting(true)
    const current = formRef.current
    const expiry = expiryRequest()
    const result = await createKey({
      name: current.name.trim(),
      note: current.note.trim() === '' ? null : current.note.trim(),
      // Only the intent is sent: 「读写」 asks for write, and that write implies read is the server's
      // rule, applied when it writes the row (ADR 0039 §决定 2). Sending both here would put the rule
      // in two places, and the one that forgot it would be this one.
      scopes: current.scope === 'write' ? ['skills:write'] : ['skills:read'],
      expires_at: expiry.expires_at,
      never_expires: expiry.never_expires,
    })
    setSubmitting(false)
    if (!result.ok) {
      const onField = fieldErrors(result.details, result.message)
      // `scope` is the one the server can now name on this form — the permission group is a field like
      // any other, and「至少要选一项权限」was written for it and had no way of reaching the screen.
      const named = onField.name ?? onField.note ?? onField.scope ?? onField.expires_at ??
        onField.never_expires
      if (named !== undefined) {
        setFieldMessage(named)
        return
      }
      onBanner(failureMessage(result))
      return
    }
    onCreated(result.data.key, result.data.token)
  }

  /**
   * The two fields the create request carries: a date, or the explicit 「不过期」.
   *
   * The day is the end of it in this product's own timezone rather than midnight UTC: a key that
   * expires "on 2027/01/13" is one that works through that day, and the listing shows the same date
   * back. The 365 preset is the boundary itself, so it sends the server's `latest_day` rather than
   * this browser's idea of "365 days from today" — a clock a little ahead of the server's would
   * otherwise ask for one day past the maximum.
   */
  function expiryRequest(): { expires_at: string | null; never_expires: boolean } {
    const current = formRef.current
    if (current.expiry === 'never') {
      return { expires_at: null, never_expires: true }
    }
    const day = current.expiry === 'custom'
      ? current.customExpiry
      : current.expiry === '365'
        ? (latestDay ?? todayPlus(365))
        : todayPlus(Number(current.expiry))
    const at = new Date(`${day}T23:59:59+08:00`)
    if (Number.isNaN(at.getTime())) {
      // A date the picker would not produce, typed or pasted in. Sent as "no date named" rather than
      // thrown about: `toISOString` on an invalid date raises, and this runs after 提交 has already
      // been disabled, so a bad string would leave a dead button and no explanation.
      return { expires_at: null, never_expires: false }
    }
    return { expires_at: at.toISOString(), never_expires: false }
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

                <div className="field">
                  <label id="key-expires-label">有效期</label>
                  <MenuSelect
                    id="key-expires"
                    value={form.expiry}
                    labelledby="key-expires-label"
                    describedby="key-expires-hint"
                    groups={[{
                      options: [
                        ...PRESETS.map((days) => ({ value: String(days), label: `${days} 天` })),
                        { value: 'custom', label: '自定义日期' },
                        ...(policy?.allow_no_expiry ? [{ value: 'never', label: '不过期' }] : []),
                      ],
                    }]}
                    onSelect={(value) => updateForm({ expiry: value as ExpiryChoice })}
                  />
                  {/* **The one choice on this form with a cost.** `.choice-warn` is the console's
                     own vocabulary for exactly that (「已经发生的下载与复制无法收回」 uses it); the
                     palette has no warning colour, so danger is the louder of the two it has. */}
                  <p className={form.expiry === 'never' ? 'choice-warn' : 'hint'} id="key-expires-hint">
                    {form.expiry === 'never' ? (
                      <>
                        <strong>不建议长期不过期。</strong>连续 365 天没有使用的密钥会被自动撤销，用它的机器会收到 401。
                      </>
                    ) : (
                      <>到期后这把密钥就失效了，用它的机器会收到 401；可以在列表里续期。</>
                    )}
                  </p>
                </div>

                {form.expiry === 'custom' ? (
                  <div className="field">
                    <label htmlFor="key-expires-date">到期日期</label>
                    <input
                      id="key-expires-date"
                      value={form.customExpiry}
                      className="control"
                      type="date"
                      min={today}
                      max={latestDay}
                      aria-describedby="key-expires-hint"
                      onChange={(event) => updateForm({ customExpiry: event.target.value })}
                    />
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
                    disabled={submitting || form.name.trim() === '' || !dayChosen}
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
