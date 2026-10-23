import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import CaptchaField from '../src/components/CaptchaField'
import CodeField from '../src/components/CodeField'
import CodeInput from '../src/components/CodeInput'
import FieldFeedback from '../src/components/FieldFeedback'
import FormBanner from '../src/components/FormBanner'
import PasswordField from '../src/components/PasswordField'

/**
 * The pieces the two-step forms are built from.
 *
 * Small on purpose, and the pages cover them in use — what is pinned here is the part a page test
 * sees only by accident: that nothing renders when there is nothing to say, and that the send button
 * has exactly three states.
 */

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('what a field says for itself', () => {
  it('renders nothing at all when there is nothing to say', () => {
    // Not an empty paragraph: an empty element still takes the space its margin reserves, and a form
    // whose fields shift when a message appears is one where the message is easy to miss.
    expect(render(<FieldFeedback message={null} />).container.querySelector('p')).toBeNull()
    expect(render(<FieldFeedback message={undefined} />).container.querySelector('p')).toBeNull()
    expect(render(<FieldFeedback />).container.querySelector('p')).toBeNull()
  })

  it('shows the sentence it was given, with a mark that is not doing the telling', () => {
    const { container } = render(<FieldFeedback message="太短了。" />)

    expect(text(container.querySelector('.field-error'))).toBe('太短了。')
    // The cross is decoration: the sentence is what says what is wrong, so the icon stays out of the
    // accessible name and the text above is all of it.
    expect(container.querySelector('.field-error svg')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('marks a field that passed, in a shape rather than only in a colour', () => {
    const { container } = render(<FieldFeedback verdict="ok" />)

    // Named, because there is no text beside it — a colour alone is not a signal somebody with a
    // colour deficiency can read, and a tick and a cross differ in form as well as in hue.
    expect(container.querySelector('.field-ok svg')?.getAttribute('aria-label')).toBe('通过')
    expect(text(container.querySelector('.field-ok'))).toBe('')
    expect(container.querySelector('.field-error')).toBeNull()
  })

  it('lets a message win over a verdict, because it is the more specific answer', () => {
    const { container } = render(<FieldFeedback verdict="ok" message="这个用户名已经被占用。" />)

    expect(text(container.querySelector('.field-error'))).toBe('这个用户名已经被占用。')
    expect(container.querySelector('.field-ok')).toBeNull()
  })

  it('says it is still asking, rather than showing nothing and looking finished', () => {
    const { container } = render(<FieldFeedback verdict="checking" />)

    expect(text(container.querySelector('.field-checking'))).toBe('检查中…')
  })
})

describe('a form banner', () => {
  it('renders nothing when there is nothing to say', () => {
    expect(render(<FormBanner message={null} />).container.querySelector('.banner')).toBeNull()
  })

  it('announces itself, because it appears without the page changing', () => {
    // A refusal that arrives after a submit is invisible to somebody who cannot see the form, unless
    // it says so.
    const { container } = render(<FormBanner message="手机号或密码不正确。" />)

    expect(text(container.querySelector('[role="alert"]'))).toBe('手机号或密码不正确。')
  })
})

describe('the captcha field', () => {
  const props = { value: '', image: '', error: null, onChange: () => undefined, onRefresh: () => undefined }

  it('shows the picture it was given, and a way to get another one without one', () => {
    const without = render(<CaptchaField {...props} />)
    expect(without.container.querySelector('img')).toBeNull()
    // The button is there either way: when the image failed to load, replacing it is the only thing
    // that can be done about it.
    expect(without.container.querySelector('button')).not.toBeNull()

    const withImage = render(
      <CaptchaField {...props} image="data:image/png;base64,AAAA" />,
    )
    expect(withImage.container.querySelector('img')?.getAttribute('src')).toBe(
      'data:image/png;base64,AAAA',
    )
  })

  it('reports what was typed and asks for a new picture', () => {
    const onChange = vi.fn()
    const onRefresh = vi.fn()
    const { container } = render(
      <CaptchaField {...props} onChange={onChange} onRefresh={onRefresh} />,
    )

    fireEvent.change(container.querySelector('input') as HTMLInputElement, {
      target: { value: 'TEST' },
    })
    fireEvent.click(container.querySelector('button') as HTMLButtonElement)

    expect(onChange.mock.calls).toEqual([['TEST']])
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('shows the error it was handed', () => {
    const { container } = render(<CaptchaField {...props} error="图形验证码不正确或已过期。" />)

    expect(text(container.querySelector('.field-error'))).toBe('图形验证码不正确或已过期。')
  })
})

describe('the password field', () => {
  const props = {
    id: 'password',
    label: '密码',
    value: '',
    error: null,
    autoComplete: 'new-password' as const,
    onChange: () => undefined,
  }

  it('is masked until the eye is clicked, and masked again when it is clicked once more', () => {
    // The reveal is the whole point of this component: a password that cannot be read is typed
    // blind, and typing it twice blind — a confirmation field — measures worse, not better. NIST
    // 800-63B-4 §3.1.1.2 advises offering this and says nothing about confirmation fields.
    const { container } = render(<PasswordField {...props} />)
    const input = container.querySelector('input') as HTMLInputElement
    const button = container.querySelector('button') as HTMLButtonElement

    expect(input.getAttribute('type')).toBe('password')
    expect(button.getAttribute('aria-label')).toBe('显示密码')
    expect(button.querySelector('svg')).not.toBeNull()

    fireEvent.click(button)

    expect(input.getAttribute('type')).toBe('text')
    // The icon has no text, so the name comes from `aria-label` — and it has to say which way the
    // switch now goes, or somebody who cannot see the icon cannot tell what the button does.
    expect(button.getAttribute('aria-label')).toBe('隐藏密码')

    fireEvent.click(button)

    expect(input.getAttribute('type')).toBe('password')
    expect(button.getAttribute('aria-label')).toBe('显示密码')
  })

  it('takes its icon from an established set rather than drawing one', () => {
    // Heroicons' `eye` and `eye-slash` (MIT). Pinned as paths, because the point of using that set
    // is that the shape is one people already recognise — a redrawn approximation would not be.
    const { container } = render(<PasswordField {...props} />)
    const paths = [...container.querySelectorAll('button svg path')].map(
      (path) => path.getAttribute('d') ?? '',
    )

    expect(paths[0]).toContain('M2.036 12.322a1.012 1.012 0 0 1 0-.639')
  })

  it('reports being left, so the form can judge the value then and not on the way in', () => {
    const onBlur = vi.fn()
    const { container } = render(<PasswordField {...props} onBlur={onBlur} />)

    fireEvent.blur(container.querySelector('input') as HTMLInputElement)

    // Passed down explicitly rather than left to attribute fallthrough: the root here is the wrapping
    // `div`, and `blur` does not bubble, so a listener placed there would never fire.
    expect(onBlur).toHaveBeenCalledTimes(1)
  })

  it('is a button that does not submit the form it sits in', () => {
    // The default for a `<button>` inside a form is `submit`. Without `type="button"`, revealing the
    // password would send the form — on the login page that is a sign-in attempt with an empty
    // password, and on the register page a step forward nobody asked for.
    const { container } = render(<PasswordField {...props} />)

    expect(container.querySelector('button')?.getAttribute('type')).toBe('button')
  })

  it('reports what was typed, and keeps the autocomplete the browser needs', () => {
    const onChange = vi.fn()
    const { container } = render(
      <PasswordField {...props} autoComplete="current-password" onChange={onChange} />,
    )

    fireEvent.change(container.querySelector('input') as HTMLInputElement, {
      target: { value: 'correct-horse' },
    })

    expect(onChange.mock.calls).toEqual([['correct-horse']])
    expect(container.querySelector('input')?.getAttribute('autocomplete')).toBe(
      'current-password',
    )
  })

  it('shows the error and the rule, and nothing where there is neither', () => {
    const bare = render(<PasswordField {...props} />)
    expect(bare.container.querySelector('.field-error')).toBeNull()
    expect(bare.container.querySelector('.hint')).toBeNull()

    const full = render(<PasswordField {...props} error="这一项必填。" hint="至少 8 个字符。" />)
    expect(text(full.container.querySelector('.field-error'))).toBe('这一项必填。')
    expect(text(full.container.querySelector('.hint'))).toBe('至少 8 个字符。')
  })
})

describe('the code input', () => {
  const props = { id: 'sms-code', value: '', onChange: () => undefined }

  it('draws one box per digit, and puts each character in its own', () => {
    const { container } = render(<CodeInput {...props} value="12" />)

    expect([...container.querySelectorAll('.code-box')].map((box) => text(box))).toEqual([
      '1',
      '2',
      '',
      '',
      '',
      '',
    ])
  })

  it('keeps one real field, which is what the phone fills in', () => {
    // The reason the digits are drawn rather than being six inputs: `one-time-code` is what makes iOS
    // and Safari offer the code that just arrived, and it is an attribute of a field — six of them
    // would be six fields with no code to offer, and six tab stops to walk through.
    const { container } = render(<CodeInput {...props} />)
    const inputs = container.querySelectorAll('input')

    expect(inputs).toHaveLength(1)
    expect(inputs[0]?.getAttribute('autocomplete')).toBe('one-time-code')
    expect(inputs[0]?.getAttribute('inputmode')).toBe('numeric')
  })

  it('reports what was typed', () => {
    const onChange = vi.fn()
    const { container } = render(<CodeInput {...props} onChange={onChange} />)

    fireEvent.change(container.querySelector('input') as HTMLInputElement, {
      target: { value: '123456' },
    })

    expect(onChange.mock.calls).toEqual([['123456']])
  })

  it('marks one box while it is in use, and none once it is left', () => {
    const onBlur = vi.fn()
    const { container } = render(<CodeInput {...props} onBlur={onBlur} />)
    const input = container.querySelector('input') as HTMLInputElement

    fireEvent.focus(input)
    expect(container.querySelectorAll('.code-box.is-active')).toHaveLength(1)

    fireEvent.blur(input)
    expect(container.querySelector('.code-box.is-active')).toBeNull()
    expect(onBlur).toHaveBeenCalledTimes(1)
  })

  it('puts the caret in the box that was pointed at', () => {
    // In the document, because the caret and the focus are what this asks about: a detached field
    // cannot be focused and has nowhere to put a selection.
    const { container } = render(<CodeInput {...props} value="12" />)
    const boxes = container.querySelectorAll('.code-box')

    fireEvent.click(boxes[1] as HTMLElement)

    const input = container.querySelector('input') as HTMLInputElement
    expect(document.activeElement).toBe(input)
    expect(input.selectionStart).toBe(1)
    expect(boxes[1]?.classList.contains('is-active')).toBe(true)
  })

  it('carries on from the last digit when a box past it is pointed at', () => {
    // Pointing at the sixth box with two digits typed is not a request for a caret in a box the code
    // cannot reach yet: the browser clamps the range, and the mark has to follow the clamp.
    const { container } = render(<CodeInput {...props} value="12" />)
    const boxes = container.querySelectorAll('.code-box')

    fireEvent.click(boxes[5] as HTMLElement)

    expect(boxes[2]?.classList.contains('is-active')).toBe(true)
  })
})

describe('the sms code field', () => {
  const props = {
    value: '',
    error: null,
    remaining: 0,
    canSend: true,
    sending: false,
    onChange: () => undefined,
    onSend: () => undefined,
  }

  it('names the wait while it is counting down, and the action otherwise', () => {
    // `canSend` arrives from the page, which computes it as "not sending and nothing left to wait
    // for" — so these two are the pairs that actually occur, rather than every combination the props
    // allow.
    const waiting = render(<CodeField {...props} remaining={42} canSend={false} />)
    expect(text(waiting.container.querySelector('button'))).toBe('42 秒后可重发')
    // Disabled rather than merely labelled: the server would refuse, and a button that looks usable
    // is one somebody presses.
    expect(waiting.container.querySelector('button')?.hasAttribute('disabled')).toBe(true)

    const ready = render(<CodeField {...props} />)
    expect(text(ready.container.querySelector('button'))).toBe('获取验证码')
    expect(ready.container.querySelector('button')?.hasAttribute('disabled')).toBe(false)
  })

  it('says it is sending, and cannot be asked twice while it is', () => {
    const { container } = render(<CodeField {...props} sending canSend={false} />)

    expect(text(container.querySelector('button'))).toBe('发送中…')
    expect(container.querySelector('button')?.hasAttribute('disabled')).toBe(true)
  })

  it('reports what was typed and asks for a code', () => {
    const onChange = vi.fn()
    const onSend = vi.fn()
    const { container } = render(
      <CodeField {...props} onChange={onChange} onSend={onSend} />,
    )

    fireEvent.change(container.querySelector('input') as HTMLInputElement, {
      target: { value: '123456' },
    })
    fireEvent.click(container.querySelector('button') as HTMLButtonElement)

    expect(onChange.mock.calls).toEqual([['123456']])
    expect(onSend).toHaveBeenCalledTimes(1)
  })

  it('shows the error it was handed', () => {
    const { container } = render(<CodeField {...props} error="这一项必填。" />)

    expect(text(container.querySelector('.field-error'))).toBe('这一项必填。')
  })
})
