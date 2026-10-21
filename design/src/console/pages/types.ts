/**
 * What every demo page is given.
 *
 * `state` is the demo's stand-in for the server's answer (正常 / 空 / 加载中 / …), `params` is the
 * address, and `active` says whether this is the page the address names. All six pages are rendered
 * and five are hidden, which is what the demo has always done: a page that is not up is still a page,
 * and the check that exactly one is visible is what proves the switch works.
 */
export interface DemoPageProps {
  active: boolean
  state: string
  params: URLSearchParams
}
