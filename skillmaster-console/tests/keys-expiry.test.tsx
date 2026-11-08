import { describe, expect, it } from 'vitest'
import { fireEvent } from '@testing-library/react'
import { errorBody, flush, json } from './support'
import {
  POLICY,
  chooseExpiry,
  expiryOptions,
  installKeysTestHooks,
  key,
  posted,
  showPage,
  text,
} from './keysSupport'

/**
 * The create form's expiry choice, split from `keys.test.tsx` when that file reached the
 * repository's size baseline.
 *
 * What is here is the decision the form adds in v53: a select with six choices, the deployment's
 * one switch over the sixth, and the explicit `never_expires` field that keeps 「不过期」 from
 * sharing a `null` with "use the default" (ADR 0171 §决定 4).
 */

installKeysTestHooks()

describe("the key form's expiry choice", () => {
  it('offers the six choices in order and preselects 90', async () => {
    const { container } = await showPage('/account/keys', () => json(200, { keys: [], policy: POLICY }))
    fireEvent.click(container.querySelector('[data-key-new]') as Element)

    expect(expiryOptions(container)).toEqual([
      '30 天', '60 天', '90 天', '365 天', '自定义日期', '不过期',
    ])
    expect((container.querySelector('#key-expires') as HTMLElement).dataset.value).toBe('90')
    // The calm sentence, not the warning, until the costly choice is actually made.
    expect(container.querySelector('#key-expires-hint')?.className).not.toContain('choice-warn')
  })

  it('offers 「不过期」 only when the deployment allows it', async () => {
    const allowed = await showPage('/account/keys', () => json(200, { keys: [], policy: POLICY }))
    fireEvent.click(allowed.container.querySelector('[data-key-new]') as Element)
    expect(expiryOptions(allowed.container)).toContain('不过期')

    const forbidden = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: { ...POLICY, allow_no_expiry: false } }),
    )
    fireEvent.click(forbidden.container.querySelector('[data-key-new]') as Element)
    expect(expiryOptions(forbidden.container)).not.toContain('不过期')
  })

  it('asks for 「不过期」 as its own field, not as a null date', async () => {
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: POLICY }),
      () => json(201, { key: key({ id: 'k8', expires_at: null }), token: 'skm_z' }),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: '永久' },
    })
    chooseExpiry(container, 'never')
    expect(container.querySelector('#key-expires-date')).toBeNull()
    const hint = container.querySelector('#key-expires-hint')
    expect(hint?.className).toContain('choice-warn')
    // The design's whole sentence, not just its class: the cost is what makes it red.
    expect(hint?.textContent).toContain('不建议长期不过期')
    expect(hint?.textContent).toContain('365 天')
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    // `expires_at: null` already means "use the default"; the explicit field is what asks for never.
    // A form that sent only the null would get a 90-day key back.
    expect(posted(requests, '/web/keys')[0]?.body).toMatchObject({
      expires_at: null,
      never_expires: true,
    })
  })

  it('refuses a custom date past the maximum without sending it', async () => {
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: POLICY }),
      () => json(201, { key: key({ id: 'k8' }), token: 'skm_z' }),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: '太远' },
    })
    chooseExpiry(container, 'custom')
    fireEvent.change(container.querySelector('#key-expires-date') as HTMLInputElement, {
      target: { value: '2027-10-16' }, // one day past the fixture's latest_day
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(posted(requests, '/web/keys')).toHaveLength(0)
    expect(container.textContent).toContain('请选择一个在允许范围内的到期日期')
  })

  it('counts a never key as active, not expired', async () => {
    const { container } = await showPage(
      '/account/keys',
      () => json(200, { keys: [key({ expires_at: null })], policy: POLICY }),
    )
    const stat = (label: string): string =>
      text(
        [...container.querySelectorAll('.stats .stat')]
          .find((one) => text(one.querySelector('.stat-head')) === label)
          ?.querySelector('.stat-number') ?? null,
      )

    expect(stat('活跃')).toBe('1')
    expect(stat('已过期')).toBe('0')
  })

  it('refuses an empty custom date instead of letting it become the default', async () => {
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: POLICY }),
      () => json(201, { key: key({ id: 'k8' }), token: 'skm_z' }),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: '空白日期' },
    })
    chooseExpiry(container, 'custom')
    fireEvent.change(container.querySelector('#key-expires-date') as HTMLInputElement, {
      target: { value: '' },
    })
    // Enter / a script can submit past a disabled button; the request builder must refuse too.
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(posted(requests, '/web/keys')).toHaveLength(0)
    expect(container.textContent).toContain('请选择一个在允许范围内的到期日期')
  })

  it('shows the deployment sentence when a never key cannot be renewed', async () => {
    const never = { keys: [key({ expires_at: null })], policy: POLICY }
    const { container } = await showPage(
      '/account/keys',
      () => json(200, never),
      () =>
        json(
          400,
          errorBody('invalid_expiry', 'the request was refused', [
            { field: 'expires_at', issue: 'not_allowed' },
          ]),
        ),
      () => json(200, never),
    )

    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    // The server names `expires_at`; the sentence says to create a dated key, because this screen
    // has no date field to choose in. Reading only `id` showed the generic 「到期时间不合法」.
    expect(container.textContent).toContain(
      '这个部署不允许签发永不过期的密钥；请新建一把带日期的。',
    )
  })
})
