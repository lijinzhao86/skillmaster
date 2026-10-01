package com.skillmasterai.common;

/**
 * Whether a value is nothing but space characters.
 *
 * <p><strong>Unicode's White_Space property, not {@link String#isBlank()}.</strong> They differ in
 * both directions. Java's test excludes the <em>non-breaking</em> spaces — U+00A0, U+2007, U+202F —
 * which every browser includes, because JavaScript's {@code trim()} and {@code \s} follow
 * White_Space: so eight non-breaking spaces, which is what a paste out of a word processor produces,
 * were "an empty field" to the browser mirror and "a malformed value" to the server. In the other
 * direction Java counts U+001C–U+001F, the file and group separators, which occupy no space and show
 * nothing. And U+0085 is White_Space while neither of Java's own tests claims it. The method below
 * is the property itself rather than either of those approximations.
 *
 * <p>The server moved, rather than the mirror, because White_Space is the property that has a
 * definition: it is a Unicode character property, whereas {@code Character.isWhitespace} is a Java
 * rule about line breaking that happens to be narrower. Enumerating Java's set inside the browser
 * would have been a list of exceptions frozen into a second language.
 *
 * <p>Null counts as blank, so that a caller does not have to write {@code x == null || …} — "nothing
 * was filled in" is the right answer for a field that was not there, and it is the answer every
 * caller wanted.
 */
public final class Text {

    private Text() {
    }

    /**
     * @return true when the value is null, empty, or holds only characters that occupy space and
     *         show nothing
     */
    public static boolean isBlank(String value) {
        return value == null || value.chars().allMatch(Text::isWhiteSpace);
    }

    /**
     * Unicode's White_Space property, written out.
     *
     * <p>Neither of Java's own tests is it. {@link Character#isWhitespace} is narrower — it excludes
     * the non-breaking spaces — and wider in the wrong place: it counts U+001C–U+001F, the file and
     * group separators, which occupy no space and show nothing to anybody. {@link
     * Character#isSpaceChar} knows only the separators, so on its own it would call a tab a value.
     * The union of the two is close to the property but not equal to it: both leave out U+0085.
     *
     * <p>Following the property rather than a runtime's rule is what lets the browser mirror state
     * the same set instead of inheriting a different one — see {@code isBlank} in
     * {@code skillmaster-web/src/validation.ts}.
     */
    private static boolean isWhiteSpace(int c) {
        // isSpaceChar is Zs, Zl and Zp — the separators, non-breaking ones included. The rest of
        // White_Space is the five control characters and the next-line character, which are not
        // separators and so are named individually.
        return Character.isSpaceChar(c) || (c >= 0x0009 && c <= 0x000D) || c == 0x0085;
    }
}
