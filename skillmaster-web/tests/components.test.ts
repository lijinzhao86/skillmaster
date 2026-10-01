import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CaptchaField from '../src/components/CaptchaField.vue'
import CodeField from '../src/components/CodeField.vue'
import FieldError from '../src/components/FieldError.vue'
import FormBanner from '../src/components/FormBanner.vue'

/**
 * The four pieces the two-step forms are built from.
 *
 * Small on purpose, and the pages cover them in use — what is pinned here is the part a page test
 * sees only by accident: that nothing renders when there is nothing to say, and that the send button
 * has exactly three states.
 */

describe('a field error', () => {
  it('renders nothing at all when there is nothing wrong', () => {
    // Not an empty paragraph: an empty element still takes the space its margin reserves, and a form
    // whose fields shift when a message appears is one where the message is easy to miss.
    expect(mount(FieldError, { props: { message: null } }).find('p').exists()).toBe(false)
    expect(mount(FieldError, { props: { message: undefined } }).find('p').exists()).toBe(false)
  })

  it('shows the sentence it was given', () => {
    const wrapper = mount(FieldError, { props: { message: '太短了。' } })

    expect(wrapper.find('.field-error').text()).toBe('太短了。')
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
