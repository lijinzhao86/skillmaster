import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CaptchaField from '../src/components/CaptchaField.vue'
import CodeField from '../src/components/CodeField.vue'
import CodeInput from '../src/components/CodeInput.vue'
import FieldFeedback from '../src/components/FieldFeedback.vue'
import FormBanner from '../src/components/FormBanner.vue'
import PasswordField from '../src/components/PasswordField.vue'

/**
 * The pieces the two-step forms are built from.
 *
 * Small on purpose, and the pages cover them in use — what is pinned here is the part a page test
 * sees only by accident: that nothing renders when there is nothing to say, and that the send button
 * has exactly three states.
 */

describe('what a field says for itself', () => {
  it('renders nothing at all when there is nothing to say', () => {
    // Not an empty paragraph: an empty element still takes the space its margin reserves, and a form
    // whose fields shift when a message appears is one where the message is easy to miss.
    expect(mount(FieldFeedback, { props: { message: null } }).find('p').exists()).toBe(false)
    expect(mount(FieldFeedback, { props: { message: undefined } }).find('p').exists()).toBe(false)
    expect(mount(FieldFeedback, { props: {} }).find('p').exists()).toBe(false)
  })

  it('shows the sentence it was given, with a mark that is not doing the telling', () => {
    const wrapper = mount(FieldFeedback, { props: { message: '太短了。' } })

    expect(wrapper.find('.field-error').text()).toBe('太短了。')
    // The cross is decoration: the sentence is what says what is wrong, so the icon stays out of the
    // accessible name and the text above is all of it.
    expect(wrapper.find('.field-error svg').attributes('aria-hidden')).toBe('true')
  })

  it('marks a field that passed, in a shape rather than only in a colour', () => {
    const wrapper = mount(FieldFeedback, { props: { verdict: 'ok' } })

    // Named, because there is no text beside it — a colour alone is not a signal somebody with a
    // colour deficiency can read, and a tick and a cross differ in form as well as in hue.
    expect(wrapper.find('.field-ok svg').attributes('aria-label')).toBe('通过')
    expect(wrapper.find('.field-ok').text()).toBe('')
    expect(wrapper.find('.field-error').exists()).toBe(false)
  })

  it('lets a message win over a verdict, because it is the more specific answer', () => {
    const wrapper = mount(FieldFeedback, { props: { verdict: 'ok', message: '这个用户名已经被占用。' } })

    expect(wrapper.find('.field-error').text()).toBe('这个用户名已经被占用。')
    expect(wrapper.find('.field-ok').exists()).toBe(false)
  })

  it('says it is still asking, rather than showing nothing and looking finished', () => {
    expect(mount(FieldFeedback, { props: { verdict: 'checking' } }).find('.field-checking').text()).toBe(
      '检查中…',
    )
  })
})

describe('a form banner', () => {
  it('renders nothing when there is nothing to say', () => {
    expect(mount(FormBanner, { props: { message: null } }).find('.banner').exists()).toBe(false)
  })

  it('announces itself, because it appears without the page changing', () => {
    // A refusal that arrives after a submit is invisible to somebody who cannot see the form, unless
    // it says so.
    const wrapper = mount(FormBanner, { props: { message: '手机号或密码不正确。' } })

    expect(wrapper.find('[role="alert"]').text()).toBe('手机号或密码不正确。')
  })
})

describe('the captcha field', () => {
  const props = { modelValue: '', image: '', error: null }

  it('shows the picture it was given, and a way to get another one without one', () => {
    const without = mount(CaptchaField, { props })
    expect(without.find('img').exists()).toBe(false)
    // The button is there either way: when the image failed to load, replacing it is the only thing
    // that can be done about it.
    expect(without.find('button').exists()).toBe(true)

    const with_ = mount(CaptchaField, { props: { ...props, image: 'data:image/png;base64,AAAA' } })
    expect(with_.find('img').attributes('src')).toBe('data:image/png;base64,AAAA')
  })

  it('reports what was typed and asks for a new picture', async () => {
    const wrapper = mount(CaptchaField, { props })

    await wrapper.find('input').setValue('TEST')
    await wrapper.find('button').trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([['TEST']])
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('shows the error it was handed', () => {
    const wrapper = mount(CaptchaField, { props: { ...props, error: '图形验证码不正确或已过期。' } })

    expect(wrapper.find('.field-error').text()).toBe('图形验证码不正确或已过期。')
  })
})

describe('the password field', () => {
  const props = {
    id: 'password',
    label: '密码',
    modelValue: '',
    error: null,
    autocomplete: 'new-password' as const,
  }

  it('is masked until the eye is clicked, and masked again when it is clicked once more', async () => {
    // The reveal is the whole point of this component: a password that cannot be read is typed
    // blind, and typing it twice blind — a confirmation field — measures worse, not better. NIST
    // 800-63B-4 §3.1.1.2 advises offering this and says nothing about confirmation fields.
    const wrapper = mount(PasswordField, { props })

    expect(wrapper.find('input').attributes('type')).toBe('password')
    expect(wrapper.find('button').attributes('aria-label')).toBe('显示密码')
    expect(wrapper.find('button svg').exists()).toBe(true)

    await wrapper.find('button').trigger('click')

    expect(wrapper.find('input').attributes('type')).toBe('text')
    // The icon has no text, so the name comes from `aria-label` — and it has to say which way the
    // switch now goes, or somebody who cannot see the icon cannot tell what the button does.
    expect(wrapper.find('button').attributes('aria-label')).toBe('隐藏密码')

    await wrapper.find('button').trigger('click')

    expect(wrapper.find('input').attributes('type')).toBe('password')
    expect(wrapper.find('button').attributes('aria-label')).toBe('显示密码')
  })

  it('takes its icon from an established set rather than drawing one', () => {
    // Heroicons' `eye` and `eye-slash` (MIT). Pinned as paths, because the point of using that set
    // is that the shape is one people already recognise — a redrawn approximation would not be.
    const wrapper = mount(PasswordField, { props })
    const paths = wrapper.findAll('button svg path').map((path) => path.attributes('d') ?? '')

    expect(paths[0]).toContain('M2.036 12.322a1.012 1.012 0 0 1 0-.639')
  })

  it('reports being left, so the form can judge the value then and not on the way in', async () => {
    const wrapper = mount(PasswordField, { props })

    await wrapper.find('input').trigger('blur')

    // Declared as an emit rather than left to attribute fallthrough: the root here is the wrapping
    // `div`, and `blur` does not bubble, so a listener placed there would never fire.
    expect(wrapper.emitted('blur')).toHaveLength(1)
  })

  it('is a button that does not submit the form it sits in', () => {
    // The default for a `<button>` inside a form is `submit`. Without `type="button"`, revealing the
    // password would send the form — on the login page that is a sign-in attempt with an empty
    // password, and on the register page a step forward nobody asked for.
    const wrapper = mount(PasswordField, { props })

    expect(wrapper.find('button').attributes('type')).toBe('button')
  })

  it('reports what was typed, and keeps the autocomplete the browser needs', async () => {
    const wrapper = mount(PasswordField, { props: { ...props, autocomplete: 'current-password' } })

    await wrapper.find('input').setValue('correct-horse')

    expect(wrapper.emitted('update:modelValue')).toEqual([['correct-horse']])
    expect(wrapper.find('input').attributes('autocomplete')).toBe('current-password')
  })

  it('shows the error and the rule, and nothing where there is neither', () => {
    const bare = mount(PasswordField, { props })
    expect(bare.find('.field-error').exists()).toBe(false)
    expect(bare.find('.hint').exists()).toBe(false)

    const full = mount(PasswordField, { props: { ...props, error: '这一项必填。', hint: '至少 8 个字符。' } })
    expect(full.find('.field-error').text()).toBe('这一项必填。')
    expect(full.find('.hint').text()).toBe('至少 8 个字符。')
  })
})

describe('the code input', () => {
  const props = { id: 'sms-code', modelValue: '' }

  /**
   * In the document, because the caret and the focus are what these two ask about: a detached field
   * cannot be focused and has nowhere to put a selection.
   */
  function mountAttached(modelValue: string) {
    return mount(CodeInput, { props: { ...props, modelValue }, attachTo: document.body })
  }

  it('draws one box per digit, and puts each character in its own', () => {
    const wrapper = mount(CodeInput, { props: { ...props, modelValue: '12' } })

    expect(wrapper.findAll('.code-box').map((box) => box.text())).toEqual([
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
    const wrapper = mount(CodeInput, { props })

    expect(wrapper.findAll('input')).toHaveLength(1)
    expect(wrapper.find('input').attributes('autocomplete')).toBe('one-time-code')
    expect(wrapper.find('input').attributes('inputmode')).toBe('numeric')
  })

  it('reports what was typed', async () => {
    const wrapper = mount(CodeInput, { props })

    await wrapper.find('input').setValue('123456')

    expect(wrapper.emitted('update:modelValue')).toEqual([['123456']])
  })

  it('marks one box while it is in use, and none once it is left', async () => {
    const wrapper = mount(CodeInput, { props })

    await wrapper.find('input').trigger('focus')
    expect(wrapper.findAll('.code-box.is-active')).toHaveLength(1)

    await wrapper.find('input').trigger('blur')
    expect(wrapper.find('.code-box.is-active').exists()).toBe(false)
    expect(wrapper.emitted('blur')).toHaveLength(1)
  })

  it('puts the caret in the box that was pointed at', async () => {
    const wrapper = mountAttached('12')

    await wrapper.get('.code-box:nth-child(2)').trigger('click')

    const input = wrapper.find('input').element as HTMLInputElement
    expect(document.activeElement).toBe(input)
    expect(input.selectionStart).toBe(1)
    expect(wrapper.get('.code-box:nth-child(2)').classes('is-active')).toBe(true)
    wrapper.unmount()
  })

  it('carries on from the last digit when a box past it is pointed at', async () => {
    // Pointing at the sixth box with two digits typed is not a request for a caret in a box the code
    // cannot reach yet: the browser clamps the range, and the mark has to follow the clamp.
    const wrapper = mountAttached('12')

    await wrapper.get('.code-box:nth-child(6)').trigger('click')

    expect(wrapper.get('.code-box:nth-child(3)').classes('is-active')).toBe(true)
    wrapper.unmount()
  })
})

describe('the sms code field', () => {
  const props = { modelValue: '', error: null, remaining: 0, canSend: true, sending: false }

  it('names the wait while it is counting down, and the action otherwise', async () => {
    // `canSend` arrives from the page, which computes it as "not sending and nothing left to wait
    // for" — so these two are the pairs that actually occur, rather than every combination the props
    // allow.
    const waiting = mount(CodeField, { props: { ...props, remaining: 42, canSend: false } })
    expect(waiting.find('button').text()).toBe('42 秒后可重发')
    // Disabled rather than merely labelled: the server would refuse, and a button that looks usable
    // is one somebody presses.
    expect(waiting.find('button').attributes('disabled')).toBeDefined()

    const ready = mount(CodeField, { props })
    expect(ready.find('button').text()).toBe('获取验证码')
    expect(ready.find('button').attributes('disabled')).toBeUndefined()
  })

  it('says it is sending, and cannot be asked twice while it is', () => {
    const wrapper = mount(CodeField, { props: { ...props, sending: true, canSend: false } })

    expect(wrapper.find('button').text()).toBe('发送中…')
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
  })

  it('reports what was typed and asks for a code', async () => {
    const wrapper = mount(CodeField, { props })

    await wrapper.find('input').setValue('123456')
    await wrapper.find('button').trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([['123456']])
    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('shows the error it was handed', () => {
    const wrapper = mount(CodeField, { props: { ...props, error: '这一项必填。' } })

    expect(wrapper.find('.field-error').text()).toBe('这一项必填。')
  })
})
