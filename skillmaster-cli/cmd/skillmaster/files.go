// The commands that read and write a version's files (v7).
//
// **A file, a version, an address** — the same three things in every command here. What differs is
// which plane answers: a row-id pin (`@<版本码>`) is the author plane and the only one that can
// reach a draft; a version number or digest is the consumer plane, which is the only one an `invoke`
// credential can use. `resolve` below decides that once, and every command takes its answer rather
// than deciding again.
//
// **stdout is content; stderr is everything else.** The rule is what makes `cat | head` and
// `grep | sort` behave like the local tools, and it is why the "按 X 操作" line is on stderr even
// though a person reads it first — a pipeline must not find it in its input.
package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"net/http"
	"regexp"
	"strings"
)

// fileTarget is one resolved command target: which version, on which plane.
type fileTarget struct {
	address string // namespace/name, no pin
	pin     string // the suffix sent after `@` or as the version header
	rowID   bool   // the author plane
	note    string // what stderr says about which version this was
}

// resolve decides the plane and the pin for a read command.
//
// Explicit pin → remembered pin → the command's default. The defaults differ by command because
// they ask different questions: `cat` can reach the consumer plane and asks it what is current, while
// `files` / `wc` / `grep` are author-plane commands and ask the author list for the live row (or the
// newest visible one when nothing is live).
func resolve(ctx context.Context, client api.Client, address string, authorOnly bool) (fileTarget, error) {
	base := bareAddress(address)
	version, named, err := versionInAddress(address)
	if err != nil {
		return fileTarget{}, err
	}
	if named {
		if id, ok := rowIDOfPin(version); ok {
			return fileTarget{address: base, pin: id, rowID: true,
				note: fmt.Sprintf("（按 %s@%s 操作。）", base, id)}, nil
		}
		if strings.HasPrefix(version, "draft:") {
			// The one spelling the grammar refuses. It is still an *author-plane* attempt — a
			// consumer request would turn it into a 400 about the header, while the author plane
			// answers the same 404 everywhere else in this version (PRD #23).
			return fileTarget{address: base, pin: version, rowID: true,
				note: fmt.Sprintf("（按 %s@%s 操作。）", base, version)}, nil
		}
		// A suffix the consumption plane has no name for (`@latest`, `@foo`, a malformed digest) is
		// an address the author plane answers: sending it as a version header would be a 400 about
		// the header, while the grammar's answer here is the author plane's 404 (v7 PRD #23). The
		// suffix travels as written, so the CLI does not invent a second answer.
		return fileTarget{address: base, pin: version, rowID: !isConsumerPin(version),
			note: fmt.Sprintf("（按 %s@%s 操作。）", base, version)}, nil
	}
	remembered, found, err := rememberedPin(base)
	if err != nil {
		return fileTarget{}, err
	}
	if found {
		return fileTarget{address: base, pin: remembered.Suffix(), rowID: remembered.UsesAuthorPlane(),
			note: fmt.Sprintf("（按 %s@%s 操作。）", base, remembered.Suffix())}, nil
	}
	if authorOnly {
		pin, note, err := authorDefault(ctx, client, base)
		if err != nil {
			return fileTarget{}, err
		}
		return fileTarget{address: base, pin: pin, rowID: true, note: note}, nil
	}
	return contentTarget(ctx, client, base)
}

// consumerName is SemVer 2.0.0's grammar, the only version spelling the consumption plane accepts
// besides a digest (the server mirrors this in SkillAddress).
var consumerName = regexp.MustCompile(`^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)` +
	`(?:-(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*)?` +
	`(?:\+[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*)?$`)

var consumerDigest = regexp.MustCompile(`^sha256:[0-9a-f]{64}$`)

// isConsumerPin says whether the consumption plane's parser has a name for this suffix. Anything
// else is an address the author plane must answer, so the CLI does not turn it into a bad header.
func isConsumerPin(pin string) bool {
	return consumerName.MatchString(pin) || consumerDigest.MatchString(pin)
}

// rowIDOfPin answers the row-id spellings the author plane accepts, and only those.
//
// A bare 8/26-character code is a row. The legacy `draft:` prefix is accepted only in front of a
// 26-character ULID — `draft:<short>` is a spelling v7 refuses everywhere (PRD #23), and stripping
// the prefix locally would make the CLI succeed where the server says 404.
func rowIDOfPin(version string) (string, bool) {
	if strings.HasPrefix(version, "draft:") {
		rest := strings.TrimPrefix(version, "draft:")
		if len(rest) == 26 && isRowID(rest) {
			return rest, true
		}
		return "", false
	}
	if isRowID(version) {
		return version, true
	}
	return "", false
}

// contentTarget is the default pin for `invoke` / `cat`: what the consumer plane serves, or — when
// nothing is live — the newest version the caller's rung can read on the author plane.
//
// **The fallback is for `write` and above only** (PRD #27). There is no runtime rung name, so it is
// learnt from two answers: asking the author plane for drafts succeeds for `write`+ and is refused
// for `browse`/`invoke`; and the consumer detail separates those two — a denied detail is `invoke`
// (403, and its answer stays the consumer's 404, so nothing about the skill is revealed), while a
// non-denied answer is `browse`, which gets the "nothing is live" sentence the PRD promises.
func contentTarget(ctx context.Context, client api.Client, base string) (fileTarget, error) {
	meta, err := client.Metadata(ctx, base, "")
	if err == nil {
		pin := api.VersionSuffix(meta.Version.Name, meta.Version.Digest)
		return fileTarget{address: base, pin: pin,
			note: fmt.Sprintf("（按 %s@%s 操作。）", base, pin)}, nil
	}
	if !errors.Is(err, api.ErrNotFound) {
		var offline *api.ServerError
		if errors.As(err, &offline) && offline.Status == http.StatusServiceUnavailable {
			// The skill is offline on the consumption plane. The author plane may still serve it
			// (`browse`+), and for `invoke` the author list answers 403 — in which case the
			// consumer's 503 is the truthful answer and is kept.
			pin, note, authorErr := authorDefault(ctx, client, base)
			if authorErr != nil {
				return fileTarget{}, err
			}
			return fileTarget{address: base, pin: pin, rowID: true,
				note: "（skill 下线中）" + note}, nil
		}
		return fileTarget{}, err
	}
	_, detailErr := client.Detail(ctx, base, "")
	var denied *api.Denial
	if errors.As(detailErr, &denied) &&
		(denied.Code == api.Forbidden || denied.Code == api.InsufficientScope) {
		return fileTarget{}, err
	}
	pin, note, authorErr := authorDefault(ctx, client, base)
	if authorErr != nil {
		return fileTarget{}, authorErr
	}
	return fileTarget{address: base, pin: pin, rowID: true, note: note}, nil
}

// authorDefault is the author plane's answer to "which version" when nothing is pinned.
//
// The live row is the current version, not "the newest row": a draft or an older published row can
// have a higher number, and reading one of those while a live version exists would silently change
// what the caller sees (PRD §地址与钉). Only when nothing is live does the fallback apply — and only
// for a credential that can see drafts (`write`+). `browse` cannot, and gets the sentence the PRD
// promises instead of a version it is not entitled to.
//
// The note is returned rather than formatted by the caller because it is the half that says *why*
// this pin is the one being used; the commands put it on stderr, where a pipeline will not read it.
func authorDefault(ctx context.Context, client api.Client, base string) (string, string, error) {
	live, err := client.AuthorVersions(ctx, base,
		api.AuthorVersionQuery{Statuses: []string{"live"}, Limit: 1})
	if err != nil {
		return "", "", addressRefusal(err)
	}
	if len(live.Versions) > 0 {
		pin := pinOf(live.Versions[0])
		return pin, fmt.Sprintf("（按 %s@%s 操作。）", base, pin), nil
	}
	drafts, draftErr := client.AuthorVersions(ctx, base,
		api.AuthorVersionQuery{Statuses: []string{"draft"}, Limit: 1})
	if draftErr != nil {
		var denied *api.Denial
		if errors.As(draftErr, &denied) &&
			(denied.Code == api.Forbidden || denied.Code == api.InsufficientScope) {
			return "", "", errors.New("这个 skill 还没有上线版本")
		}
		return "", "", addressRefusal(draftErr)
	}
	_ = drafts // the query is the probe; the actual pick is "newest visible", below
	all, allErr := client.AuthorVersions(ctx, base, api.AuthorVersionQuery{Limit: 1})
	if allErr != nil {
		return "", "", addressRefusal(allErr)
	}
	if len(all.Versions) == 0 {
		return "", "", errors.New("这个 skill 还没有上线版本")
	}
	chosen := all.Versions[0]
	pin := pinOf(chosen)
	state := stateLabel(chosen.Status)
	return pin, fmt.Sprintf("（当前没有上线版本，按最近可见的版本 %s@%s（%s）操作。）",
		base, pin, state), nil
}

// writeTarget is the write commands' resolver: a row id, or a refusal.
//
// **Names and digests are refused locally**, because the server's answer for them is about state
// ("not a draft") while the real problem is that the address names content rather than a row. The
// sentence is the same one the server would send, minus a round trip.
func writeTarget(address string) (fileTarget, error) {
	base := bareAddress(address)
	version, named, err := versionInAddress(address)
	if err != nil {
		return fileTarget{}, err
	}
	if named {
		if id, ok := rowIDOfPin(version); ok {
			return fileTarget{address: base, pin: id, rowID: true,
				note: fmt.Sprintf("（按 %s@%s 操作。）", base, id)}, nil
		}
		if strings.HasPrefix(version, "draft:") {
			// The one spelling the grammar refuses. It is still an *author-plane* attempt — a
			// consumer request would turn it into a 400 about the header, while the author plane
			// answers the same 404 everywhere else in this version (PRD #23).
			return fileTarget{address: base, pin: version, rowID: true,
				note: fmt.Sprintf("（按 %s@%s 操作。）", base, version)}, nil
		}
		return fileTarget{}, fmt.Errorf("写只针对草稿；草稿的地址是 @<版本码>。" +
			"用 version list --status draft 找到它。")
	}
	remembered, found, err := rememberedPin(base)
	if err != nil {
		return fileTarget{}, err
	}
	if !found || !remembered.UsesAuthorPlane() {
		return fileTarget{}, fmt.Errorf("写只针对草稿；草稿的地址是 @<版本码>。" +
			"用 version list --status draft 找到它。")
	}
	return fileTarget{address: base, pin: remembered.Suffix(), rowID: true}, nil
}

// rejectJSON is the content commands' one shared refusal.
//
// **Content is not a document.** `version list` and the other author-plane commands answer JSON
// because their subject is facts; `files` / `cat` / `wc` / `grep` answer lines, bytes and counts, and
// a `--json` on them would either invent a schema nobody asked for or silently do nothing. The
// refusal is explicit and the help does not offer the flag (PRD 验收 #21).
func rejectJSON(command string, args []string) error {
	for _, arg := range args {
		if arg == "--json" {
			return fmt.Errorf("%s 不提供 --json；按未知参数处理，去掉它再试。", command)
		}
	}
	return nil
}
