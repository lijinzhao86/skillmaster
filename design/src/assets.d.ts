/**
 * An image imported by the page is a URL esbuild produces — in the delivered demo, a `data:` URI
 * (the build inlines it so the file stays self-contained). TypeScript needs to hear that a `.png`
 * import is a string, not a module with no exports.
 */
declare module '*.png' {
  const url: string
  export default url
}
