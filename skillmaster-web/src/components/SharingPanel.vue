<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { codeMessage, fieldErrors } from '../api/errors'
import { grantSkill, listGrants, revokeGrant } from '../api/skills'
import type { SkillGrant } from '../api/types'
import FieldFeedback from './FieldFeedback.vue'
import FormBanner from './FormBanner.vue'

/**
 * Who this skill is shared with, and handing it to somebody else (ADR 0034).
 *
 * **The two roles are explained rather than named.** `viewer` and `editor` are the wire's words, and
 * a person choosing between them is not choosing a label — they are choosing whether the other
 * person can add versions. So each option says what it lets somebody do, and the editor's says what
 * it does not: an editor adds drafts and still cannot publish, because publishing decides what every
 * reader of the service gets (ADR 0031). A picker that left that out would be offering a choice with
 * a consequence nobody was told about.
 *
 * **Whether to draw at all is the caller's decision** — see `SkillPage`, which shows this only for a
 * skill in the caller's own namespace. That is not a check this component repeats: the server refuses
 * a share from anyone who does not own the skill, and a second copy of the rule here would be one
 * more thing to keep in step with it.
 */
const props = defineProps<{ namespace: string; name: string }>()

const grants = ref<SkillGrant[] | null>(null)
const banner = ref<string | null>(null)
const handle = ref('')
const role = ref<'viewer' | 'editor'>('viewer')
/** The handle whose row is being withdrawn, so only that row disables while the request is out. */
const withdrawing = ref<string | null>(null)
const adding = ref(false)
const fieldMessage = ref<string | null>(null)

onMounted(async () => {
  const result = await listGrants(props.namespace, props.name)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  grants.value = result.data.grants
})

async function add(): Promise<void> {
  const wanted = handle.value.trim()
  if (wanted === '') {
    fieldMessage.value = '请填写对方的用户名。'
    return
  }
  adding.value = true
  banner.value = null
  fieldMessage.value = null
  const result = await grantSkill(props.namespace, props.name, wanted, role.value)
  adding.value = false

  if (!result.ok) {
    // `handle` is the field the server names for both of its refusals — a username nobody has, and
    // the caller's own — so both land under the input rather than in the banner, which is where
    // somebody fixing a typo is looking.
    const onField = fieldErrors(result.details, result.message).handle
    if (onField !== undefined) {
      fieldMessage.value = onField
      return
    }
    banner.value = codeMessage(result.code, result.message)
    return
  }

  handle.value = ''
  // Re-read rather than pushing the answer into the list: the same call changes a role, so "what
  // this person can do now" is not always a new row, and a list patched by hand would have to know
  // which of the two happened.
  const reloaded = await listGrants(props.namespace, props.name)
  if (reloaded.ok) {
    grants.value = reloaded.data.grants
  }
}

async function withdraw(handle: string): Promise<void> {
  withdrawing.value = handle
  banner.value = null
  const result = await revokeGrant(props.namespace, props.name, handle)
  withdrawing.value = null

  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  grants.value = (grants.value ?? []).filter((grant) => grant.handle !== handle)
}

/**
 * The role as the row says it, short enough for a chip.
 *
 * The same words the picker uses, because they are the same distinction — and not "只读" for the
 * editor, which would be the one wrong thing to call it: an editor writes, they just cannot publish.
 */
function roleLabel(role: SkillGrant['role']): string {
  return role === 'editor' ? '可以提新版本' : '只读'
}
</script>

<template>
  <section class="sharing">
    <h3>共享</h3>
    <FormBanner :message="banner" />

    <p class="hint">
      只有这个 skill 归你时才共享得出去。共享的是「读这个 skill」，不是把它交给对方——
      <strong>上线始终只有你能做</strong>。
    </p>

    <ul v-if="grants && grants.length > 0" class="grants">
      <li v-for="grant in grants" :key="grant.handle">
        <span class="grant-who">{{ grant.handle }}</span>
        <span class="chip">{{ roleLabel(grant.role) }}</span>
        <button
          type="button"
          class="link danger"
          :disabled="withdrawing !== null"
          @click="withdraw(grant.handle)"
        >
          {{ withdrawing === grant.handle ? '撤回中…' : '撤回' }}
        </button>
      </li>
    </ul>
    <!-- Nothing shared yet is a state of its own, not an empty list: an empty <ul> would leave the
         heading above a blank space, which reads as a page that failed to load. -->
    <p v-else-if="grants" class="muted sharing-empty">还没有共享给任何人。</p>
    <p v-else-if="banner === null" class="muted">加载中…</p>

    <div class="field">
      <label for="sharing-handle">共享给</label>
      <div class="row">
        <input
          id="sharing-handle"
          v-model="handle"
          type="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="对方的用户名"
          @keyup.enter="add"
        />
        <select v-model="role" aria-label="权限">
          <option value="viewer">只读</option>
          <option value="editor">可以提新版本，不能上线</option>
        </select>
        <button type="button" :disabled="adding" @click="add">
          {{ adding ? '共享中…' : '共享' }}
        </button>
      </div>
      <FieldFeedback :message="fieldMessage" />
    </div>
  </section>
</template>
