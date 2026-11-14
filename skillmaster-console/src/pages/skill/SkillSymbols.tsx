/**
 * The page's own symbol sheet (design 2026-10-20): the marks the tabs and the file table name by
 * `#id`. One copy, drawn once, so a file row and a version row cannot disagree about a glyph.
 */
export default function SkillSymbols() {
  return (
    <svg style={{ position: 'absolute', width: 0, height: 0, overflow: 'hidden' }} aria-hidden="true">
      <symbol id="fileMark" viewBox="0 0 24 24" fill="none">
        <path
          d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
        <path d="M14 3v5h5" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      </symbol>
      <symbol id="descMark" viewBox="0 0 24 24" fill="none">
        <path
          d="M5 6h14M5 10.5h14M5 15h9"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </symbol>
      {/* v65: 预期结果 — a target, not a paragraph: two rings and a centre. */}
      <symbol id="outcomeMark" viewBox="0 0 24 24" fill="none">
        <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
        <circle cx="12" cy="12" r="3.6" stroke="currentColor" strokeWidth="1.8" />
        <circle cx="12" cy="12" r="1.2" fill="currentColor" />
      </symbol>
      <symbol id="clockMark" viewBox="0 0 24 24" fill="none">
        <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
        <path
          d="M12 7.4V12l3.2 1.9"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </symbol>
      <symbol id="personMark" viewBox="0 0 24 24" fill="none">
        <circle cx="12" cy="8.2" r="3.4" stroke="currentColor" strokeWidth="1.8" />
        <path
          d="M5.6 19.4c.7-3.4 3.4-5.4 6.4-5.4s5.7 2 6.4 5.4"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </symbol>
      <symbol id="versionMark" viewBox="0 0 24 24" fill="none">
        <path
          d="M20.1 12.7l-7.4 7.4a1.8 1.8 0 0 1-2.5 0L4 14.1V4h10.1l6 6a1.8 1.8 0 0 1 .0 2.7z"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
        <circle cx="8.4" cy="8.4" r="1.25" fill="currentColor" />
      </symbol>
      <symbol id="envMark" viewBox="0 0 24 24" fill="none">
        <path
          d="M4.8 7.2l4.6 4.8-4.6 4.8"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path d="M12.4 16.8h6.8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </symbol>
      <symbol id="infoMark" viewBox="0 0 24 24" fill="none">
        <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
        <path
          d="M12 11v5M12 8.1v.2"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </symbol>
      <symbol id="settingsMark" viewBox="0 0 24 24" fill="none">
        <path
          d="M4 7h9M18 7h2M4 12h4M13 12h7M4 17h9M18 17h2"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
        <circle cx="15.5" cy="7" r="2" stroke="currentColor" strokeWidth="1.8" />
        <circle cx="10.5" cy="12" r="2" stroke="currentColor" strokeWidth="1.8" />
        <circle cx="15.5" cy="17" r="2" stroke="currentColor" strokeWidth="1.8" />
      </symbol>
      <symbol id="folderMark" viewBox="0 0 24 24" fill="none">
        <path
          d="M3.5 18.5V6.5a1 1 0 0 1 1-1h4.2l2 2.5h8.8a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1z"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
      </symbol>
    </svg>
  )
}
