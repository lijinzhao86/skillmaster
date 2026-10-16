// 一条命令跑完设计稿的验收判据：两套检查 + 产物与源一致。
//
//   node design/scripts/check-all.mjs [--headful] [--shots <root>]
//
// **这就是「全量」。** 它是给「方向定稿」和「要交给别人」用的，不是日常动作：日常是
// `console-states.mjs --only <组>`（一两秒），而它要跑二十秒量级、还要把整套图重出一遍。
//
// **它先问，而且得是人回答。** 没有终端就拒绝（退出码 3）——脚本、agent 一律跑不动它。
// 在终端里它会先说清"要跑什么、会写哪些文件"，再问一次；答 y 才动。
// 规则见仓库 CLAUDE.md「设计检查：全量要审批」。
//
// 它自己不做检查，只按顺序叫那两套（各自的用法见它们的文件头），外加一件它们都不做的事：
// **把两个演示件从源重出一遍，和仓库里那份逐字节比**——「交源不交产物」是这仓库的常见漏项，
// 而它便宜到顺手。

import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { createInterface } from 'node:readline/promises'

const rawArgs = process.argv.slice(2)
const HEADFUL = rawArgs.includes('--headful')
const shotsAt = rawArgs.indexOf('--shots')
const shotsRoot = shotsAt < 0 ? 'design/shots' : rawArgs[shotsAt + 1]
if (shotsAt >= 0 && !shotsRoot) {
  console.error('usage: check-all.mjs [--headful] [--shots <root>]')
  process.exit(1)
}

/* 两个演示件：源 → 交付件。顺序就是下面要跑的顺序。 */
const DEMOS = [
  {
    name: '首页演示',
    source: 'design/demos/dir3-hara.html',
    artifact: 'design/skillmaster-首页演示.html',
    check: 'design/scripts/interaction-check.mjs',
    shots: join(shotsRoot, 'states'),
  },
  {
    name: '控制台演示',
    source: 'design/demos/dir3-hara-console.html',
    artifact: 'design/skillmaster-控制台演示.html',
    check: 'design/scripts/console-states.mjs',
    shots: join(shotsRoot, 'console'),
  },
]

const plan = [
  ...DEMOS.map((demo) => `跑 ${demo.name}（${demo.check.split('/').pop()}）→ ${demo.shots}/`),
  ...DEMOS.map((demo) => `比对 ${demo.artifact} 与它的源是否逐字节一致`),
]

if (!process.stdin.isTTY) {
  console.error(
    '全量检查要人在终端里确认，而这里没有终端——拒绝执行（2026-10-16 定）。\n' +
      '  日常只跑改动的那一处：node design/scripts/console-states.mjs "…" <out> --only <组>；\n' +
      '  真要跑全套，请在终端里跑这个脚本，它会先列清单再问一次。',
  )
  process.exit(3)
}
const ask = createInterface({ input: process.stdin, output: process.stdout })
let answer = ''
try {
  console.log(`全套要做这些事${HEADFUL ? '（有窗口）' : '（无窗口）'}：`)
  plan.forEach((line, i) => console.log(`  ${i + 1}. ${line}`))
  answer = await ask.question(`跑吗？[y/N] `)
} catch {
  /* Stdin 在回答之前断了（管道用尽、终端没了）。那不是"同意"，也不是崩。 */
  answer = ''
}
ask.close()
if (!/^y(es)?$/i.test(answer.trim())) {
  console.error('没有批准，什么都没有跑。')
  process.exit(3)
}

const failures = []

/* ---- 产物与源：重出一遍，逐字节比 ---- */
const scratch = mkdtempSync(join(tmpdir(), 'design-artifacts-'))
for (const demo of DEMOS) {
  const rebuilt = join(scratch, demo.artifact.split('/').pop())
  execFileSync('node', ['design/scripts/build-demo.mjs', demo.source, rebuilt], { stdio: 'inherit' })
  const same = Buffer.compare(readFileSync(rebuilt), readFileSync(resolve(demo.artifact))) === 0
  console.log(`${same ? '一致' : '不一致'}：${demo.artifact} ← ${demo.source}`)
  if (!same) failures.push(`${demo.artifact} 与它的源不一致（重出之后跑一遍 build-demo 再比）`)
}

/* ---- 两套检查 ---- */
for (const demo of DEMOS) {
  const flags = HEADFUL ? ['--headful'] : []
  console.log(`\n──── ${demo.name} ────`)
  const run = spawnSync(
    'node',
    [demo.check, demo.artifact, demo.shots, ...flags],
    {
      // 输出直接流到终端：两个套各自会印自己的那行开头与那份 JSON，这里转述不如让它们自己说。
      stdio: 'inherit',
      // **审批已经问过了**：告诉子进程别再问一遍。这个变量是给这条 runner 用的；手写它
      // 正是这道闸要防的那件事（见 CLAUDE.md）。
      env: { ...process.env, DESIGN_CHECKS_APPROVED: '1' },
    },
  )
  if (run.status !== 0) failures.push(`${demo.name}那套检查没通过（退出码 ${run.status}）`)
}

/* ---- 汇总 ---- */
console.log('\n──── 全套 ────')
if (failures.length === 0) {
  console.log(
    `全绿：两套检查都过、两个演示件与源逐字节一致${HEADFUL ? '' : '（无窗口跑法：滚动条那条断言报的是 skipped，' +
      '要看它请用 --headful 再跑一次）'}。`,
  )
  process.exit(0)
}
failures.forEach((line) => console.error('  ×', line))
process.exit(1)
