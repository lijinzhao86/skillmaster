/**
 * The public entry point for one skill's page.
 *
 * The implementation lives in `./skill/` (state machine + composition, section components, and
 * the pure readings they share). This file stays as the stable import path so `App.tsx` and the
 * page's tests keep importing `../pages/SkillPage` unchanged.
 */
export { default, type SkillPageProps } from './skill/SkillPage'
