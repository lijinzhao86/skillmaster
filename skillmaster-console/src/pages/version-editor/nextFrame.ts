/** One frame, for what measures or focuses after the DOM has caught up (Vue's `nextTick`). */
export function nextFrame(): Promise<void> {
  return new Promise((resolve) => {
    requestAnimationFrame(() => resolve())
  })
}
