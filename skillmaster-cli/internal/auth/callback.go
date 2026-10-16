package auth

import (
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"
)

// Callback is the loopback listener the browser comes back to.
//
// Bound before the browser is opened, never after: `login` has no way to know how fast somebody
// will click, and a redirect that arrives at a port nothing is listening on is lost — the browser
// shows a connection error and the CLI waits out its timeout for a code that was already sent.
type Callback struct {
	listener net.Listener
	server   *http.Server
	codes    chan string
	failures chan error
	state    string
}

// Listen binds the loopback port and starts waiting for exactly one redirect.
//
// **`127.0.0.1` and not `0.0.0.0`.** Binding every interface would let anybody on the same network
// reach this listener, and the redirect carries an authorization code — RFC 8252 §7.3 says to bind
// the loopback address, and the reason is that the code is a credential for the moment it exists.
func Listen(port int, state string) (*Callback, error) {
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return nil, fmt.Errorf(
			"listening on 127.0.0.1:%d: %w — the port is fixed (ADR 0028), so something else is"+
				" using it; stop that process and try again", port, err)
	}

	callback := &Callback{
		listener: listener,
		codes:    make(chan string, 1),
		failures: make(chan error, 1),
		state:    state,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/callback", callback.handle)
	callback.server = &http.Server{Handler: mux}

	go func() {
		// A closed listener is the normal way this ends — the serve loop returning an error after
		// Close is not a failure worth reporting.
		_ = callback.server.Serve(listener)
	}()
	return callback, nil
}

// handle is the one request that matters. Anything else — a favicon, a scanner — is answered and
// ignored; only a redirect carrying the right state produces a code.
func (c *Callback) handle(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()

	// The state check is the reason this half of the flow is safe at all. Without it, somebody can
	// send this CLI to *their* authorization request, let it come back with *their* code, and the
	// CLI would exchange it and store a credential for an account its user does not own. The state
	// is ours, we generated it, and it is compared literally.
	//
	// **A request that is not ours ends the wait, and that is deliberate rather than intolerant.** A
	// stray process on this machine can reach the fixed port (ADR 0028) and cancel a login with it —
	// true, and accepted: retrying costs one command, while ignoring the mismatch would turn a
	// genuine state failure into five minutes of silence with nothing on screen saying why. The
	// mismatch is a signal, and it is answered rather than swallowed.
	if got := query.Get("state"); got != c.state {
		http.Error(w, "state 不匹配，已拒绝这次回调。", http.StatusBadRequest)
		c.fail(fmt.Errorf(
			"the callback's state did not match the one this login sent — refusing it"))
		return
	}
	if refused := query.Get("error"); refused != "" {
		description := query.Get("error_description")
		// **「你拒绝了」 is true here, and it is not a reading of the error code.** The only producer
		// of `error` on this path is the server's refusal route (`PgCliSignIns.refuse`), which writes
		// `access_denied` and nothing else — so the person did press 拒绝. **The branch still keys off
		// the code**, because a second producer would make this sentence false, and the day one
		// appears the fix is another sentence here rather than a rewrite of this one.
		//
		// What it does not do is point at the terminal. It said 「回到终端查看原因」 until
		// 2026-10-16, which sends somebody to look up a reason they *are* — and the sentence before
		// that 「没有新增登录设备，可以回到终端重新登录」, which answers a refusal by offering the
		// thing that was just refused. The card owes one fact and then stops; the drafts that failed
		// all failed by treating it as a card that owes an instruction
		// (`design/README.md` §授权结果).
		writePage(w, "授权未完成", "你拒绝了这次授权请求。")
		c.fail(fmt.Errorf("the authorization was refused: %s %s", refused, description))
		return
	}
	code := query.Get("code")
	if code == "" {
		http.Error(w, "回调里没有授权码。", http.StatusBadRequest)
		c.fail(errors.New("the callback carried no authorization code"))
		return
	}

	// Written before the code is handed over, so the browser has its page even though the CLI is
	// about to close the listener.
	writePage(w, "授权成功", "请关闭此页面回到终端继续。")
	c.codes <- code
}

func (c *Callback) fail(err error) {
	// Buffered and never blocking: an ignored second failure must not wedge the handler.
	select {
	case c.failures <- err:
	default:
	}
}

// Wait blocks until the redirect arrives, the listener fails, or the deadline passes.
//
// The timeout is not a nicety — without it, somebody who closes the browser tab leaves the command
// hanging with no way out but Ctrl-C, and nothing on screen saying why.
func (c *Callback) Wait(timeout time.Duration) (string, error) {
	select {
	case code := <-c.codes:
		return code, nil
	case err := <-c.failures:
		return "", err
	case <-time.After(timeout):
		return "", fmt.Errorf(
			"waited %s and the browser never came back; if it did not open, run this command again"+
				" and paste the URL it prints", timeout)
	}
}

// Close stops listening. Safe to call more than once, and safe after Wait has already returned.
func (c *Callback) Close() {
	_ = c.server.Close()
}

// page is the loopback page, and it is drawn as the product's card rather than as a document.
//
// **Until 2026-10-16 this page had no design.** The design deliverable carried its two sentences and
// nothing else about it, so this template was a bare `h1` and `p` with three lines of body styling —
// and what landed in the browser was the browser's own default type, on a page unlike any other in
// the product. The values below are the design's card, **copied rather than derived**
// (`design/README.md` §授权结果): the person has just answered a card that looks exactly like this one,
// and the two screens being the same object is what makes them read as two halves of one flow. Change
// a number here and it has to change there first.
//
// **There is no close button, and the reason is the design's rather than this template's.** The page
// has nothing behind it: the authorization is over, and there is no control on it that would do
// anything. (Until the countdown below existed, the reason written here was a constraint — generated
// HTML with no script cannot call `window.close()` at all. That stopped being true, and the design
// recorded the change; the × would be refused on the stronger ground that it has no work to do.)
//
// **The stylesheet is inline and self-contained**, and that one is a constraint of where it runs: this
// is served from `127.0.0.1` by a process that is not the web app, cannot reach its assets, and is
// about to exit.
//
// **Both sentences are written to fit one line**, which at this measure is 21 characters
// (`design/README.md` §授权结果 records the measurement). A longer one wraps and leaves two or three
// characters alone on the second line — the refusal's first draft was 23 characters and did exactly
// that, and the fix was the sentence, not the break. The current two are 13 and 11.
//
// **The page's one piece of behaviour is a silent attempt to close itself, and the silence is the
// decision.** Three seconds after the card renders it calls `window.close()` and says nothing about
// it, whether that works or not. It used to announce the attempt with a countdown and then say so out
// loud when the browser refused; **both halves went on 2026-10-16, because the promise can never be
// kept here.**
//
// That is a spec answer rather than an observation. `window.close()` acts only on a *script-closable*
// top-level tab, and the HTML standard defines that as — verbatim — "its is created by web content is
// true; **or** its session history entries's size is 1" (WHATWG §7.2.2.1). This page fails both: the
// OS launcher opened the tab, not any web content; and the person arrived by answering the card
// before it, which is a navigation, so the history holds two entries rather than one. **Neither is
// reachable from inside this flow** — the one-time code this page carries is minted by that very
// answer, so the page can only ever be *arrived at*, never *opened*. A promise about the future that
// can be checked by waiting, and that is certain to come out false, is the last thing this card
// should make. (`design/README.md` §授权结果 carries the full reasoning and the industry precedent:
// LangChain's deepagents removed the same close-then-rewrite pair in PR #4463 for the same reason —
// the sentence reads correctly either way, so a rewrite buys nothing and costs a visible jump.)
const page = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>%s</title>
<style>
*,*::before,*::after{box-sizing:border-box}
body{
  min-height:100vh;margin:0;padding:24px;
  display:grid;justify-items:center;align-content:center;
  background:#fdfcfa;color:#1f2328;
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  font-size:15px;line-height:1.75;letter-spacing:0.02em;
  -webkit-font-smoothing:antialiased;
}
.card{
  width:100%%;max-width:404px;padding:38px 38px 34px;
  background:#fff;border:1px solid #d8dee4;border-radius:10px;
  box-shadow:0 4px 14px rgba(31,35,40,.08);
}
.brand{display:flex;align-items:center;gap:9px;margin:0 0 14px;font-size:14px;font-weight:600;letter-spacing:0.04em}
.brand svg{display:block;width:20px;height:20px;flex:none}
h1{margin:0;font-size:22px;font-weight:700;line-height:1.5;letter-spacing:0.05em}
p{margin:30px 0 0}
</style>
</head><body><div class="card">
<p class="brand"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor"/><rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6"/><rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32"/></svg>SkillMaster</p>
<h1>%s</h1>
<p>%s</p>
</div>
<script>setTimeout(function () { window.close(); }, 3000);</script>
</body></html>`

func writePage(w http.ResponseWriter, title, message string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, fmt.Sprintf(page, title, title, message))
}
