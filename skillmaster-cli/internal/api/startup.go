package api

import (
	"context"
	"fmt"
	"strings"
)

// StartupPath is where the journey body lives. It is **not** under APIPrefix on purpose: this is the
// anonymous discovery channel, not a versioned API route, and the two generations of the API will
// always share it (ADR 0088, ADR 0093).
const StartupPath = "/gateway/STARTUP.md"

// Startup fetches the journey body that `skillmaster startup` prints.
//
// **Anonymous on the server; authenticated here only incidentally.** The command ensures this
// machine has a credential before it asks (that is the order the journey needs), and the client it
// then uses carries the token like every other call. The route does not require it — a machine with
// no credential can read the same document — so this method has no reason to strip it.
func (c Client) Startup(ctx context.Context) (string, error) {
	body, err := c.get(ctx, StartupPath)
	if err != nil {
		return "", err
	}
	// **A 200 is not proof this is the journey.** A reverse proxy that answers unknown paths with an
	// SPA's index.html would hand back a web page, and the first version of `setup` wrote exactly
	// that into a SKILL.md and reported success (2026-10-05). The document starts with a heading;
	// checking that costs one comparison and turns a silent wrong answer into a sentence.
	if !strings.HasPrefix(strings.TrimSpace(string(body)), "# ") {
		return "", fmt.Errorf("%s 返回的不是旅程正文，多半是前面那层代理把这条路径当成未知路径回了一张网页", StartupPath)
	}
	return string(body), nil
}
