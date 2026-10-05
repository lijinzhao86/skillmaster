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
		// The person declined, or the server refused. Either way the page is done and the message
		// belongs on the terminal rather than in the browser, where nobody is looking.
		writePage(w, "授权未完成", "你可以关闭这个页面，回到终端查看原因。")
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
	writePage(w, "授权成功", "你可以关闭这个页面，回到终端继续。")
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

const page = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>%s</title>
<style>body{font:16px/1.6 -apple-system,system-ui,sans-serif;margin:4rem auto;max-width:32rem;padding:0 1rem}</style>
</head><body><h1>%s</h1><p>%s</p></body></html>`

func writePage(w http.ResponseWriter, title, message string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, fmt.Sprintf(page, title, title, message))
}
