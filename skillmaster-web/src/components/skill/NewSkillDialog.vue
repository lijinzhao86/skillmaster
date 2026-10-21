<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { importFailure, type ImportFailure } from '../../api/errors'
import { createSkill } from '../../api/skills'

/**
 * 「新建 skill › 在线编辑」 (design §新建 skill, ADR 0070).
 *
 * **One action, not two.** The form creates the skill and its first draft together; the editor then
 * opens on that draft, which already has a real {@code SKILL.md}. A skill with no version would be
 * a row every surface has to special-case, and it would exist only because somebody closed a tab.
 *
 * The name and description are {@code SKILL.md}'s two required facts; the version name defaults to
 * 「第一版」 and is editable. All three are required before 创建 is available.
 */
const props = defineProps<{
  open: boolean
  /** The caller's own namespace slug — the only place this sheet's skill can land. */
  namespace: string
}>()
const emit = defineEmits<{ close: []; navigate: [href: string] }>()

const name = ref('')
const description = ref('')
const versionName = ref('第一版')
const pending = ref(false)
const failure = ref<ImportFailure | null>(null)
const nameInput = ref<HTMLInputElement | null>(null)

const acceptDisabled = computed(
  () =>
    pending.value ||
    name.value.trim() === '' ||
    description.value.trim() === '' ||
    versionName.value.trim() === '',
)

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    name.value = ''
    description.value = ''
    versionName.value = '第一版'
    pending.value = false
    failure.value = null
    void nextTick(() => nameInput.value?.focus())
  },
)

function cancel(): void {
  if (!pending.value) {
    emit('close')
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    cancel()
  }
}

function onNameInput(): void {
  const chars = Array.from(name.value)
  if (chars.length > 64) {
    name.value = chars.slice(0, 64).join('')
  }
}

function onVersionNameInput(): void {
  const chars = Array.from(versionName.value)
  if (chars.length > 40) {
    versionName.value = chars.slice(0, 40).join('')
  }
}

async function submit(): Promise<void> {
  if (acceptDisabled.value) {
    return
  }
  pending.value = true
  failure.value = null
  const answer = await createSkill(name.value.trim(), description.value.trim(), versionName.value.trim())
  pending.value = false

  if (!answer.ok) {
    failure.value = importFailure(answer, 'create')
    // This sheet knows the name it asked for, so «这个名字已经有了» can lead straight to it.
    if (answer.code === 'skill_already_exists') {
      const typed = name.value.trim()
      failure.value = {
        message: '这个名字已经有了。',
        action: {
          label: `查看 ${typed}`,
          href: `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(typed)}`,
        },
      }
    }
    return
  }

  const draft = answer.data.version.draft_id
  if (draft !== null) {
    emit(
      'navigate',
      `/skills/${encodeURIComponent(answer.data.namespace)}/${encodeURIComponent(answer.data.name)}@${encodeURIComponent(draft)}/edit`,
    )
  }
}
</script>

<template>
  <div v-if="open" class="confirm" @keydown="onKeydown">
    <div
      class="confirm-sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="newSkillTitle"
      aria-describedby="newSkillIntro"
    >
      <h2 id="newSkillTitle" class="confirm-title">新建 skill</h2>
      <p id="newSkillIntro" class="confirm-body">
        名字会成为地址的一部分；描述来自 SKILL.md 的 frontmatter。创建后是一条草稿，不会上线。
      </p>

      <label class="confirm-field">
        <span class="confirm-label">名字</span>
        <input
          ref="nameInput"
          v-model="name"
          class="confirm-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="skill 的名字"
          aria-label="skill 的名字（最多 64 个字符）"
          :disabled="pending"
          @input="onNameInput"
        />
      </label>
      <label class="confirm-field">
        <span class="confirm-label">描述</span>
        <input
          v-model="description"
          class="confirm-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="这个 skill 是做什么的"
          aria-label="描述"
          :disabled="pending"
        />
      </label>
      <label class="confirm-field">
        <span class="confirm-label">版本名称</span>
        <input
          v-model="versionName"
          class="confirm-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          aria-label="版本名称（最多 40 个字符）"
          :disabled="pending"
          @input="onVersionNameInput"
        />
      </label>

      <p v-if="failure !== null" class="confirm-error" role="alert">
        {{ failure.message }}
        <a v-if="failure.action !== undefined" :href="failure.action.href">{{ failure.action.label }}</a>
      </p>

      <div class="confirm-acts">
        <button type="button" class="button is-quiet" :disabled="pending" @click="cancel">
          取消
        </button>
        <button type="button" class="button" :disabled="acceptDisabled" @click="submit">
          {{ pending ? '正在创建…' : '创建' }}
        </button>
      </div>
    </div>
  </div>
</template>
