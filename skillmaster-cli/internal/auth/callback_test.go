package auth

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
)

// The page the browser is left standing on, both halves of it.
//
// **Asserted here because nothing on the web side can notice this drifting.** The page is the CLI's
// own (`127.0.0.1:<port>/callback`), while its design lives in the design deliverable — so the two
// copies of these sentences are in two languages, in two repositories' worth of directories, and
// nothing compiles or renders them together. v4-cli-login's T22 asks for both halves to be judged
// (`design/scripts/interaction-check.mjs` §8 pins the demo's), and this is the Go half.
//
// **The sentences are copied verbatim from the design, and they are also constrained by it**: each
// one fits a single line at the card's measure, which is 21 characters (`design/README.md` §授权结果).
// The refusal's first draft named the machine instead of saying 这次 and came to 23 — it wrapped and
// left 「因。」 alone on a second line. So a longer sentence here is not a wording preference; it is a
// layout defect in a file this test cannot see. The pair now in use is 13 and 11.
func TestTheCardTheBrowserIsLeftOn(t *testing.T) {
	const state = "our-state"

	cases := []struct {
		name    string
		query   string
		title   string
		message string
	}{
		{
			name:  "agreed",
			query: "code=the-code",
			title: "授权成功",
			// **The instruction half is load-bearing** (2026-10-16): the card no longer promises to
			// close itself — it cannot (see the doc comment on `page`) — so "what to do about this
			// page" is a question nothing else on the card answers. Told, the person closes it; the
			// promise it replaced was one the page could not keep.
			message: "请关闭此页面回到终端继续。",
		},
		{
			name:    "refused",
			query:   "error=access_denied",
			title:   "授权未完成",
			message: "你拒绝了这次授权请求。",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			port := freePort(t)
			callback, err := Listen(port, state)
			if err != nil {
				t.Fatalf("listening: %v", err)
			}
			defer callback.Close()

			response, err := http.Get(fmt.Sprintf(
				"http://127.0.0.1:%d/callback?%s&state=%s", port, tc.query, state))
			if err != nil {
				t.Fatalf("calling back: %v", err)
			}
			defer response.Body.Close()
			body, err := io.ReadAll(response.Body)
			if err != nil {
				t.Fatalf("reading the page: %v", err)
			}
			page := string(body)

			if !strings.Contains(page, "<h1>"+tc.title+"</h1>") {
				t.Errorf("the page does not carry the title %q:\n%s", tc.title, page)
			}
			if !strings.Contains(page, "<p>"+tc.message+"</p>") {
				t.Errorf("the page does not carry the sentence %q:\n%s", tc.message, page)
			}
			// The card, and the one number that says whether this is the product's card or the
			// browser's default document. It is here rather than in a snapshot because the value is
			// the design's, taken from `.dialog` — a change to it is a change to the design first.
			if !strings.Contains(page, `class="card"`) || !strings.Contains(page, "max-width:404px") {
				t.Errorf("the page is not drawn as the design's card:\n%s", page)
			}
			// And no close button, which is the design's decision rather than a constraint: the
			// authorization is over, so there is nothing left for a control on this page to do.
			if !strings.Contains(page, "SkillMaster") {
				t.Errorf("the page does not carry the brand line:\n%s", page)
			}
			if strings.Contains(page, "<button") {
				t.Errorf("the page draws a control, and nothing on it can act:\n%s", page)
			}

			// The page still tries to close itself, says nothing about the attempt, and never
			// rewrites a word of what it says.
			//
			// **What can be asserted from Go is the markup** — the script is run by a browser and this
			// is not one. The behaviour is asserted where it can be executed: `interaction-check.mjs`
			// §8 intercepts `window.close` to count the attempt, reads the sentence twice to catch a
			// rewrite, and pins the absence of any automatic-close wording. So the two things this
			// side owns are the attempt itself and **the words that must never come back**.
			if !strings.Contains(page, "window.close()") {
				t.Errorf("the page never tries to close itself:\n%s", page)
			}
			// **`自动` is the word that may not appear.** It is the whole of what was removed: a
			// countdown that promised to close in three seconds, and the sentence that withdrew the
			// promise when the browser refused. Neither can be kept on this page (see the `page` doc
			// comment), so both are defects rather than wording, and they are pinned by the shape
			// rather than by the exact drafts — the reason a future version reaches for this page is
			// to make it *do* something, and "something that happens by itself" is the only way it
			// can go wrong here.
			if strings.Contains(page, "自动") {
				t.Errorf("the page promises something will happen by itself:\n%s", page)
			}
		})
	}
}
