// Parsing a version address: name, digest, row id, and the draft pin.

package main

import (
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"regexp"
	"strings"
)

type versionTarget struct {
	skill string
	pin   string
}

// parseVersionTarget reads `<namespace>/<name>@<pin>`. Every read/write verb under `version` takes
// one: the design refuses "the current one" as a default because a slip would change a different
// version than the one the person was looking at.
func parseVersionTarget(arg string) (versionTarget, error) {
	skill, pin, found := strings.Cut(arg, "@")
	if !found || pin == "" {
		return versionTarget{}, usagef("要写明是哪一版：<命名空间>/<名字>@<版本>。%s", usageAt("version"))
	}
	if _, err := api.SkillPath(skill); err != nil {
		return versionTarget{}, &usageError{message: err.Error()}
	}
	return versionTarget{skill: skill, pin: pin}, nil
}

// parseSkillAddress reads `<namespace>/<name>`, and refuses a pin: a version list is about the whole
// skill, and a pin here would be silently dropped.
func parseSkillAddress(arg string) (string, error) {
	if strings.Contains(arg, "@") {
		return "", usagef("版本列表是针对整个 skill 的，地址后面不要带 @版本。%s", usageAt("version"))
	}
	if _, err := api.SkillPath(arg); err != nil {
		return "", &usageError{message: err.Error()}
	}
	return arg, nil
}

// isRowID reports whether a pin is a version row's own id: a v7 eight-character short code, or a
// v6 26-character ULID (ADR 0063).
//
// The alphabet is Crockford's — no I, L, O or U — which is what keeps an eight-character code from
// being confused with a typo of a version number. A ULID's first character additionally encodes the
// timestamp's top bits, so it never exceeds 7; that is the same check the server makes.
func isRowID(pin string) bool {
	if len(pin) == 8 {
		return crockford.MatchString(pin)
	}
	if len(pin) == 26 && crockford.MatchString(pin) {
		return pin[0] <= '7'
	}
	return false
}

var crockford = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]+$`)

// isDraftPin is the row-id question a read asks before choosing a plane. It still accepts the v6
// `draft:` prefix, which an older pin file or an older command line may carry; the canonical
// spelling is the bare id.
func isDraftPin(pin string) bool {
	return strings.HasPrefix(pin, "draft:") || isRowID(pin)
}

func isDigestPin(pin string) bool { return strings.HasPrefix(pin, "sha256:") }

// writablePin is the pin a rename, note or discard may send.
//
// **A digest is refused locally; a number is not.** The draft's identity is its row id, not its
// content hash, so `@sha256:…` would name something this write cannot address. A published or
// revoked *number* goes to the server unchanged: it, not the CLI, decides the frozen/state answer,
// because the same command must give the same reason under every rung.
func writablePin(arg string) (versionTarget, error) {
	target, err := parseVersionTarget(arg)
	if err != nil {
		return versionTarget{}, err
	}
	if isDigestPin(target.pin) || strings.Contains(target.pin, ".") {
		return versionTarget{}, usagef("写只针对草稿；草稿的地址是 @<版本码>，不是版本号或内容摘要。"+
			"先列出版本、找到它的版本码：skmr version list %s", target.skill)
	}
	return target, nil
}
