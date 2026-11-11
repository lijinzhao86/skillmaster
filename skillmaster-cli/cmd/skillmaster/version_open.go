// `version open`: the browser hand-off and its URL.

package main

import (
	"context"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"net/url"
	"os"
	"strings"
)

func versionOpen(ctx context.Context, args []string) error {
	// The two value flags must be known to the pre-scan: `--sub --json` names the value `--json`,
	// and reading it as a switch would change the shape of the error a script receives.
	jsonMode := jsonModeIn(args, "--sub", "--bump")
	target, options, err := parseOpenArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	// The console's address grammar has no `draft:` prefix (ADR 0063). A legacy spelling is
	// normalised here rather than pasted into `?version=`, where it would match no row; a bare row id
	// is left as it is — for a published version it is not a web address, and the page says so with
	// 「没有这一版」 rather than silently opening another one.
	pin := normalizedPin(target.pin)
	address := versionWebURL(target.skill, pin, options.sub, options.bump)
	// v37: the same one read every page action makes (ADR 0127). A version the caller cannot see is
	// not worth opening a page for; an unreadable answer (no credential, network) still opens.
	facts := readPageFacts(ctx, pageSpec{path: "version open"},
		pageArgs{skill: target.skill, pin: pin})
	if facts.known {
		if code, reason := pageReason(pageSpec{path: "version open"}, facts, target.skill); reason != "" {
			return pageRefusal(jsonMode, code, reason, address)
		}
	}
	if !facts.fromAuthor {
		fmt.Fprintln(os.Stderr,
			"（没能预检：读不到作者页（没有可用凭据、网络失败，或这把凭据的范围不够）；页面会按你的登录状态显示。）")
	}
	opened := false
	warning := ""
	if !options.printOnly {
		if openErr := openBrowser(address); openErr != nil {
			warning = fmt.Sprintf("没能自动打开浏览器（地址在上面，也可以自己打开）：%v", openErr)
		} else {
			opened = true
		}
	}
	if warning != "" {
		fmt.Fprintln(os.Stderr, warning)
	}

	if jsonMode {
		return writeJSON(struct {
			URL    string `json:"url"`
			Opened bool   `json:"opened"`
		}{URL: address, Opened: opened})
	}
	fmt.Println(address)
	switch {
	case opened:
		fmt.Println("（已在浏览器里打开。）")
	case options.printOnly:
		fmt.Println("（--print：只打印地址，没有打开浏览器。）")
	default:
		fmt.Println("（没能自动打开浏览器；地址就在上面。）")
	}
	return nil
}

// normalizedPin turns a legacy `draft:<ULID>` pin into the console's bare row id. **Shared with the
// page actions**, because a page action pastes the pin into `?version=` too, and a `draft:` prefix
// there matches no row.
func normalizedPin(pin string) string {
	if strings.HasPrefix(pin, "draft:") {
		if id, ok := rowIDOfPin(pin); ok {
			return id
		}
	}
	return pin
}

// openOptions are the two address facts `version open` can add: which pane, and which publish
// choice the form should start on. Both are absent by default, and absent means "leave the address
// exactly as it has always been" (v11 PRD 验收 #12).
type openOptions struct {
	printOnly bool
	sub       string
	bump      string
}

func parseOpenArgs(args []string) (versionTarget, openOptions, error) {
	address := ""
	options := openOptions{}
	seenSub := false
	seenBump := false
	for i := 0; i < len(args); i++ {
		switch arg := args[i]; {
		case arg == "--json":
		case arg == "--print":
			options.printOnly = true
		case arg == "--sub":
			if seenSub {
				return versionTarget{}, openOptions{}, usagef("%s", "--sub 只能给一次。")
			}
			seenSub = true
			if i+1 >= len(args) {
				return versionTarget{}, openOptions{}, usagef("--sub 后面要跟 record、diff 或 actions。%s", usageAt("version"))
			}
			value := args[i+1]
			i++
			if value != "record" && value != "diff" && value != "actions" {
				return versionTarget{}, openOptions{}, usagef("--sub 只接受 record、diff、actions（%s）。%s", redactOr(value), usageAt("version"))
			}
			// `record` is the pane the address already means; writing it would be a second spelling.
			if value != "record" {
				options.sub = value
			}
		case arg == "--bump":
			if seenBump {
				return versionTarget{}, openOptions{}, usagef("%s", "--bump 只能给一次。")
			}
			seenBump = true
			if i+1 >= len(args) {
				return versionTarget{}, openOptions{}, usagef("--bump 后面要跟 patch、minor 或 major。%s", usageAt("version"))
			}
			value := args[i+1]
			i++
			if !isBumpKind(value) {
				return versionTarget{}, openOptions{}, usagef("--bump 只接受 patch、minor、major（%s）。%s", redactOr(value), usageAt("version"))
			}
			options.bump = value
		case strings.HasPrefix(arg, "-"):
			return versionTarget{}, openOptions{}, usagef("不认识的参数 %s。%s", namedArgument(arg), usageAt("version"))
		default:
			if address != "" {
				return versionTarget{}, openOptions{}, usagef("version open 只接一个地址（收到 %s）。", namedArgument(arg))
			}
			address = arg
		}
	}
	if address == "" {
		return versionTarget{}, openOptions{}, usagef("%s", usageAt("version"))
	}
	target, err := parseVersionTarget(address)
	return target, options, err
}

// versionWebURL is the single-version page: one route, with the pin in the query.
//
// Both path segments are escaped because a skill name may be non-ASCII. The pin is escaped only
// when it has to be: `:`, `.` and `-` are legal in a query value, and leaving them readable keeps
// `<版本码>` and `sha256:…` copyable from the terminal.
//
// `sub` and `bump` are appended only when asked for, and in that order, so an address that names
// neither is byte-for-byte the one this command has always printed (v11 PRD 验收 #12). Both values
// come from closed sets validated by the caller, so they need no escaping.
func versionWebURL(skill, pin, sub, bump string) string {
	namespace, name, _ := strings.Cut(skill, "/")
	base := fmt.Sprintf("%s/skills/%s/%s", config.WebURL(),
		url.PathEscape(namespace), url.PathEscape(name))
	address := base + "?tab=versions&version=" + queryValue(pin)
	if sub != "" {
		address += "&sub=" + sub
	}
	if bump != "" {
		address += "&bump=" + bump
	}
	return address
}

func queryValue(value string) string {
	for _, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') ||
			strings.ContainsRune("-._~:", r) {
			continue
		}
		return url.QueryEscape(value)
	}
	return value
}

func containsString(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
