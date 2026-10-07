/// <reference types="vite/client" />

// No `declare module '*.vue'` shim on purpose: vue-tsc resolves .vue files itself, and a shim would
// type every component as an opaque one and throw away the props it could have checked.
