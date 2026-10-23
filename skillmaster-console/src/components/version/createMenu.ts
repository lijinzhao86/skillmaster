/**
 * The two sources a version can come from (v9): the editor, and a package.
 *
 * **Defined once, because both triggers say the same thing.** The version list's toolbar and the
 * file table's head both offer 「新建版本」, and the design insists they are one menu with one name
 * (design §一个 skill, 2026-10-21). Two literal copies would let the items drift apart — one menu
 * gaining a source the other does not — while both still looked right on their own.
 */
export function versionCreateItems(
  newHref: string,
  openImport: () => void,
): Array<{ label: string; href?: string; onSelect?: () => void }> {
  return [
    { label: '在线编辑', href: newHref },
    { label: '导入压缩包', onSelect: openImport },
  ]
}
