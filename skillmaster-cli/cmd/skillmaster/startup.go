// The startup journey and the local store it opens.

package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
	"os"
	"strings"
	"time"
)

// startupSkill is the official skill that carries the journey (ADR 0096).
//
// **It is content on the platform, not an asset of this binary.** Changing a sentence in the
// journey means publishing a new version of the skill, not shipping a release — which is why the
// address is written here and nothing else about the text is.
const startupSkill = "skillmaster/skillmaster-startup"

// startup loads the journey, but only after this machine can act on it.
//
// **The order is the design**: the journey's first step is the login, so printing a journey whose
// first instruction is already satisfied is one less thing for the agent to work out. The
// credential therefore comes first — `clientFor` drives the browser login when there is none.
//
// **No browser and no credential is a refusal, not a partial success** (v17 acceptance 1): the
// sentence names both ways in, and the exit code is the credential one, so an agent can tell this
// apart from a network failure.
func startup(ctx context.Context, args []string) error {
	if len(args) > 0 {
		return unknownFlag("startup", args[0])
	}
	client, err := clientFor(ctx, false)
	if errors.Is(err, auth.ErrNotSignedIn) || errors.Is(err, errNoBrowserNoCredential) {
		return &credentialError{message: fmt.Sprintf(
			"本机没有凭据，startup 不会假装成功。有浏览器就运行 skmr login 登录或注册；"+
				"会话里没有浏览器时，把一把在控制台签发的 API key 放进 %s。", config.ApiKeyEnv)}
	}
	if err != nil {
		return err
	}
	// **Where they are, before what to do.** The body is the same document on every run; this line
	// is what makes a second visit readable (v17 acceptance 29).
	//
	// **On stderr, and on its own clock.** The declaration promises the journey verbatim on stdout,
	// so a line that is not the journey does not belong there — `startup > journey.md` must capture
	// the journey. And "best effort" has to mean best effort in time too: four requests at the
	// client's two-minute budget each would let a silent proxy hold the command for eight minutes
	// before it asks for the body the caller is actually waiting for.
	stateCtx, cancelState := context.WithTimeout(ctx, 20*time.Second)
	if line, ok := whereYouAre(stateCtx, client); ok {
		fmt.Fprintln(os.Stderr, line)
		fmt.Fprintln(os.Stderr)
	}
	cancelState()
	// **Which AI tools this machine has already been configured for.** Local and best effort: this
	// is the line that answers 「我换了一个 AI，还要再写吗」 without a server request. It goes to
	// stderr because the declaration promises the journey verbatim on stdout.
	if line, ok := promptRecordLine(); ok {
		fmt.Fprintln(os.Stderr, line)
		fmt.Fprintln(os.Stderr)
	}
	// **Pinned like `invoke`, because the journey is read one file at a time.** The next step is
	// another `skill read` against the same address, and a publish landing mid-journey must not
	// swap the files underneath somebody who is already at step three.
	fetched, err := authenticated(ctx, func(client api.Client) (loaded, error) {
		return fetchBody(ctx, client, startupSkill, false)
	})
	if err != nil {
		return startupUnavailable(err)
	}
	// **A pin that cannot be written does not withhold the journey.** The body is already in hand;
	// the pin is this machine's note of which version it read, and the store's own documentation
	// calls its file safe to delete. Failing the whole command over it would turn a read-only
	// `$HOME` into "startup does not work".
	// `VersionSuffix`, exactly as `invoke` computes it: a published version may carry no name, and
	// then the pin is the digest. Comparing a name against a digest would report a move on every run.
	now := api.VersionSuffix(fetched.meta.Version.Name, fetched.meta.Version.Digest)
	// The pin keeps the version's own kind (`name` or `digest`), exactly as `invoke` writes it;
	// `now` is only what the sentence says. Passing the suffix here would record a digest as a name.
	previous, had, err := rememberPin(startupSkill,
		consumerPin(fetched.meta.Version.Name, fetched.meta.Version.Digest))
	if err != nil {
		fmt.Fprintf(os.Stderr, "（没能记下这一版：%v。下面的正文照常，但下一步 skill read 取的可能是最新版，不一定是这一版。）\n", err)
	} else if had {
		if before := previous.Suffix(); before != now {
			// The same sentence `invoke` prints, for the same reason: the pin moves silently, and
			// every `skill read` afterwards follows it.
			fmt.Fprintf(os.Stderr, "（%s 上次钉在 @%s，现在是 @%s——中间有人发布过。）\n", startupSkill, before, now)
		}
	}
	fmt.Print(string(fetched.content))
	if !strings.HasSuffix(string(fetched.content), "\n") {
		fmt.Println()
	}
	return nil
}

// startupUnavailable adds the one thing the server's error cannot know: where the journey lives, so
// an operator can check whether somebody took it offline. **No fallback text** — a copy of the
// journey in this binary is the second home ADR 0096 exists to prevent.
func startupUnavailable(err error) error {
	if errors.Is(err, api.ErrNotFound) {
		return fmt.Errorf("旅程在 %s，服务端说它不存在或你看不到——多半是它被下线或删除了（%w）。"+
			"去控制台确认它还在线上，或重试 skmr startup。", startupSkill, err)
	}
	return err
}

// frontmatterScalar is the value of a key in the frontmatter block, for the two keys this file
// reads out of one.
//
// Deliberately not a YAML parser: this reads one key out of the frontmatter `submit` already
// requires, both keys are plain scalars on their own line, and the CLI has no other use for YAML. A real parser would be
// a dependency, and this is a dozen lines — but it is also why it refuses anything it is not sure
// about rather than guessing: an absent or empty value has to read as "not there".
//
// **Indentation is allowed through, because one of the two keys is nested.** `name` is top level and
// `platform_api_version` sits under `metadata:`, so anchoring at column 0 would silently never find
// the second one — a failure the caller cannot see, since a missing version just means no notice is
// printed. First match wins, and the two keys do not collide in a real frontmatter.
func frontmatterScalar(content []byte, key string) (string, bool) {
	text := strings.TrimPrefix(string(content), "\ufeff")
	if !strings.HasPrefix(text, "---") {
		return "", false
	}
	rest := text[3:]
	end := strings.Index(rest, "\n---")
	if end < 0 {
		return "", false
	}
	for _, line := range strings.Split(rest[:end], "\n") {
		trimmed := strings.TrimSpace(line)
		if !strings.HasPrefix(trimmed, key+":") {
			continue
		}
		value := strings.TrimSpace(strings.TrimPrefix(trimmed, key+":"))
		if value == "" {
			return "", false
		}
		return strings.Trim(value, `"'`), true
	}
	return "", false
}

// openStore picks where the credential lives and reports the fallback, if any.
//
// The path is only computed when it will be used: a keychain store never touches the file, and
// creating the directory for a store that does not need it would leave a config directory behind on
// a machine that never falls back.
// openStore picks both the place a credential lives and what the next command will present.
//
// **The environment is wrapped around the store here, once**, rather than checked in each command:
// `SKILLMASTER_API_KEY` wins over anything stored on this machine (ADR 0038 §决定 6), and a rule
// applied per command is a rule one command will not have. The concrete type is returned so that the
// commands which have to explain the difference — `auth status` and `logout` — can ask which of the
// two answered.
// machineStore is the seam that keeps the test suite off the machine it runs on.
//
// **On macOS this call is the real login keychain**, and a test that runs `login` or `auth status`
// rather than a shape function reaches it. Reading an entry there needs the item's ACL to trust the
// caller, and `go test` builds a fresh, unsigned binary on every run, so an approval cannot stick:
// the suite raised a keychain authorization dialog on the developer's screen once per run, and this
// machine's own credential sat in the path of tests that only wanted to know which flag reached
// which shape. Replaced once in TestMain rather than injected per test, because the failure mode of
// forgetting is a dialog on somebody's screen rather than a red test.
var machineStore = credentials.Open

// openStore picks where the credential lives and reports the fallback, if any.
func openStore(server string) (credentials.EnvStore, string) {
	path, err := config.CredentialPath(server)
	if err != nil {
		fmt.Fprintf(os.Stderr, "找不到用户配置目录（%v），将只使用钥匙串。\n", err)
		path = ""
	}
	fallback, notice := machineStore(path, server)
	return credentials.EnvStore{
		Var:      config.ApiKeyEnv,
		Value:    os.Getenv(config.ApiKeyEnv),
		Fallback: fallback,
	}, notice
}
