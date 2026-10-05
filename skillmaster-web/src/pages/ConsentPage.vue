<script setup lang="ts">
import { computed } from 'vue'
import { csrfToken } from '../api/client'

/**
 * The page a person sees once, when `skillmaster login` hands their browser over.
 *
 * **It submits a plain HTML form, not `fetch`.** The answer to this POST is a 302 to the CLI's
 * loopback port with the authorization code; `fetch` would follow that redirect itself, and the
 * navigation has to be the browser's. `method="post"` with `action` on the framework's own endpoint
 * is what does it.
 *
 * **Two forms, not one with a disabled button.** Declining is the same submission with nothing
 * agreed to — the server reads an empty scope set as `access_denied` — so the second form simply
 * carries no `scope` inputs. Doing it with JavaScript would mean the refusal depended on script
 * having run.
 *
 * Nothing here is a parameter this page invents: `client_id`, `state` and `scope` all came from the
 * redirect the framework sent, and it is the framework that checks them again.
 */
const query = new URLSearchParams(window.location.search)

const clientId = query.get('client_id')
const state = query.get('state')

/** The redirect puts every scope in one space-separated parameter, the submission wants them apart. */
const scopes = computed(() =>
  query
    .getAll('scope')
    .flatMap((value) => value.split(/\s+/))
    .filter((value) => value !== ''),
)

/**
 * What each scope actually lets the tool do.
 *
 * Written as consequences rather than as scope names, because this page is the product's only moment
 * of disclosure: somebody who typed `skillmaster login` has not thereby learned that `skills:write`
 * means "publish under my name". A scope with no entry here is shown raw **and said to be
 * undescribed** — a silent blank would read as "harmless", which is the one thing an unknown scope
 * must not read as.
 */
const SCOPE_MEANINGS: Record<string, string> = {
  'skills:read': '读取你能看到的 skill — 包括你自己命名空间里未公开的那些',
  'skills:write': '以你的名义发布、更新和删除 skill',
}

/**
 * Who is asking.
 *
 * v1 has exactly one client, seeded at deployment, and M2 deliberately has no read seam to ask for
 * its display name (see the module doc). A name typed here is therefore a name that can rot — so an
 * unrecognised `client_id` is shown as itself with a warning rather than as something friendly.
 */
const CLIENT_NAMES: Record<string, string> = {
  'skillmaster-cli': 'SkillMaster CLI',
}

const clientName = computed(() => (clientId === null ? null : (CLIENT_NAMES[clientId] ?? null)))

/** The redirect is incomplete if either of these is missing, and posting anyway would be refused. */
const incomplete = computed(() => clientId === null || state === null || clientId === '')
</script>

<template>
  <div class="card">
    <h2>授权请求</h2>

    <!-- The framework only builds this redirect; a person can also arrive here by editing the
         address or following an old link. Saying so is better than a form that cannot work. -->
    <p v-if="incomplete" class="muted">
      这个链接不完整，缺少 <code>client_id</code> 或 <code>state</code>，无法完成授权。
      请回到终端重新运行 <code>skillmaster login</code>。
    </p>

    <template v-else>
      <p>
        <strong>{{ clientName ?? clientId }}</strong>
        <template v-if="clientName === null">
          <span class="muted">（这个客户端不在已知名单里，请确认它确实是你安装的）</span>
        </template>
        请求以你的名义：
      </p>

      <ul>
        <li v-for="scope in scopes" :key="scope">
          <template v-if="SCOPE_MEANINGS[scope] !== undefined">
            {{ SCOPE_MEANINGS[scope] }}
          </template>
          <template v-else>
            <code>{{ scope }}</code>
            <span class="muted">（我们不知道这一项的含义，请谨慎同意）</span>
          </template>
        </li>
      </ul>

      <p class="muted">
        同意之后，这个工具会拿到一份长期凭据，存在你的机器上；此后它替你做的事都不再询问。
        凭据可以随时用 <code>skillmaster logout</code> 撤销。
      </p>

      <!-- `_csrf` is rendered because this is a browser write and the field is what such a form owes
           the server. It is currently not checked — the framework exempts its own endpoints from CSRF
           protection — so this is here for the day that changes, not because it is load-bearing now.
           See the module doc, §已知的不精确. -->
      <form method="post" action="/oauth/authorize">
        <input type="hidden" name="client_id" :value="clientId!" />
        <input type="hidden" name="state" :value="state!" />
        <input type="hidden" name="_csrf" :value="csrfToken() ?? ''" />
        <input v-for="scope in scopes" :key="scope" type="hidden" name="scope" :value="scope" />
        <button type="submit">同意并继续</button>
      </form>

      <form method="post" action="/oauth/authorize">
        <input type="hidden" name="client_id" :value="clientId!" />
        <input type="hidden" name="state" :value="state!" />
        <input type="hidden" name="_csrf" :value="csrfToken() ?? ''" />
        <button type="submit" class="link">拒绝</button>
      </form>
    </template>
  </div>
</template>
