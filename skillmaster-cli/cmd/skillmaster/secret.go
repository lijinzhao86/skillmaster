// `secret`: the vault, the bindings, and the one place a value goes in (v52).
//
// **Nothing here prints a value, and there is no command that reads one back.** The server has no
// such endpoint (ADR 0168); a value leaves the platform in exactly one response, the lease that
// `skmr run` asks for, and it lands in a child process's environment rather than in a terminal.
//
// **Sharing is a page action, not a request.** Granting somebody else a value is the one
// irreversible-feeling action in this feature — the grantee can read the plaintext on their own
// machine afterwards — so it happens on the console page that states the risk (ADR 0081's first
// exception, agreed 2026-11-08). `secret share` therefore opens that page and says nothing has
// happened yet (ADR 0126); the server has no API-plane route for it at all. **Revoking is the other
// half and stays direct**: taking access back is not the risky direction.

package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"strconv"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// secretValueLimit is the server's limit, repeated here so an oversized paste fails before it
// travels. The server is still the authority; this is the copy that can say so in one round trip.
const secretValueLimit = 32 * 1024

// stdinIsTerminal is a seam, so a test can stand where a person would and where a pipe would.
var stdinIsTerminal = func() bool {
	info, err := os.Stdin.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

func secretVerb(ctx context.Context, args []string) error {
	// **Bare `skmr secret` is the list**, the same default `notifications` uses: the question a
	// person arrives with is "what do I have".
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		return secretList(ctx, args)
	}
	switch args[0] {
	case "set":
		return secretSet(ctx, args[1:])
	case "list":
		return secretList(ctx, args[1:])
	case "rotate":
		return secretRotate(ctx, args[1:])
	case "rm":
		return secretRemove(ctx, args[1:])
	case "bind":
		return secretBind(ctx, args[1:])
	case "unbind":
		return secretUnbind(ctx, args[1:])
	case "shares":
		return secretShares(ctx, args[1:])
	case "share":
		return secretShare(ctx, args[1:])
	case "unshare":
		return secretUnshare(ctx, args[1:])
	case "incoming":
		return secretIncoming(ctx, args[1:])
	case "quota":
		return secretQuota(ctx, args[1:])
	default:
		return unknownName("secret", args[0], []string{
			"set", "list", "rotate", "rm", "bind", "unbind", "shares", "share", "unshare",
			"incoming", "quota"})
	}
}

// secretArgs is one command's flags and its one positional id.
//
// **The flag names live on the commands, not here.** A shared parser that switched over `--skill`
// and `--env` by name would make every command look as if it read every flag, which is exactly the
// drift the help-conformance test exists to catch; the names stay in the one command that accepts
// them, and this only carries what was found.
type secretArgs struct {
	id       string
	values   map[string]string
	switches map[string]bool
	json     bool
}

func (a secretArgs) on(name string) bool { return a.switches[name] }

// parseSecretArgs reads one command's arguments. `valued` maps each flag that takes a value to where
// its value goes, `booleans` names the flags that do not; anything else is refused by name.
func parseSecretArgs(path string, args []string, valued map[string]*string, booleans []string) (secretArgs, error) {
	parsed := secretArgs{switches: map[string]bool{}}
	allowedSwitch := map[string]bool{}
	for _, flag := range booleans {
		allowedSwitch[flag] = true
	}
	jsonMode := jsonModeIn(args)
	for index := 0; index < len(args); index++ {
		arg := args[index]
		if !strings.HasPrefix(arg, "-") {
			if parsed.id != "" {
				return parsed, versionErr(jsonMode, usagef("%s", usageAt(path)), "")
			}
			parsed.id = arg
			continue
		}
		// `--flag=value` is one token, and reading it as one unknown flag would quote the whole
		// token back for a mistake that is only in the name.
		name, inline, hasInline := strings.Cut(arg, "=")
		if target, takesValue := valued[name]; takesValue {
			value := inline
			if !hasInline {
				next, err := flagValue(args, index, name, path)
				if err != nil {
					return parsed, err
				}
				value = next
				index++
			}
			*target = value
			continue
		}
		switch {
		case allowedSwitch[name], name == "--json":
			// `--json` is universal, so it needs no per-command list: every command here declares
			// it, and refusing it would mean saying no to a flag the help offers.
			if hasInline {
				return parsed, versionErr(jsonMode, usagef("%s 不带值。%s", name, usageAt(path)), "")
			}
			parsed.switches[name] = true
		default:
			return parsed, versionErr(jsonMode, unknownFlag(path, name), "")
		}
	}
	parsed.json = jsonMode
	return parsed, nil
}

// readSecretValue is the only way a value enters the CLI.
//
// **It is read from standard input, never from an argument and never from a terminal.** An argument
// is in the shell history and in `ps`; a terminal echoes what is typed and keeps it in the
// scrollback, which is the same losing bargain. A person who runs this without a pipe is told how
// to hand the value over instead of being allowed to paste it.
//
// **The bytes are kept exactly**, trailing newline included — a PEM's final newline is part of the
// value, and trimming it would change what the third party sees. The one thing done about it is
// saying so, because `echo key | skmr secret set` is the natural thing to type and its extra
// newline breaks an API key in a way nobody can see.
func readSecretValue(path string) (string, error) {
	if stdinIsTerminal() {
		return "", usagef("值只从标准输入读，而且终端会把输入回显到屏幕上。"+
			"用文件：skmr %s < key.txt，或管道：printf '%%s' \"$KEY\" | skmr %s。%s",
			path, path, usageAt(path))
	}
	// **Bounded before it is read, not after.** Reading a whole file and then refusing it would let
	// `skmr secret set < /dev/zero` fill memory first and report the size second.
	raw, err := io.ReadAll(io.LimitReader(stdin, secretValueLimit+1))
	if err != nil {
		return "", fmt.Errorf("读标准输入：%w", err)
	}
	if len(raw) == 0 {
		return "", usagef("没有从标准输入读到值。%s", usageAt(path))
	}
	if len(raw) > secretValueLimit {
		// A usage error, and exit 2: the help says an oversized value is one.
		return "", usagef("值超过上限 %d 字节（环境变量也放不下更大的），没有保存。%s",
			secretValueLimit, usageAt(path))
	}
	if strings.HasSuffix(string(raw), "\n") || strings.HasSuffix(string(raw), "\r") {
		fmt.Fprintf(os.Stderr,
			"提醒：值以换行结尾，换行会原样保存——echo 会多带一个，用 printf '%%s' 或 < 文件就没有。\n")
	}
	return string(raw), nil
}

func secretSet(ctx context.Context, args []string) error {
	var skill, env, flagName string
	parsed, err := parseSecretArgs("secret set", args, map[string]*string{
		"--skill": &skill, "--env": &env, "--name": &flagName,
	}, nil)
	if err != nil {
		return err
	}
	if parsed.id != "" {
		return usagef("%s", usageAt("secret set"))
	}
	if skill == "" || env == "" {
		return usagef("set 要 --skill <地址> 和 --env <NAME>。%s", usageAt("secret set"))
	}
	if err := checkSecretSkill("secret set", skill); err != nil {
		return err
	}
	if !validSecretEnv(env) {
		return usagef("--env 要是合法的环境变量名（字母、数字、下划线，不以数字开头），收到 %q。%s",
			env, usageAt("secret set"))
	}
	value, err := readSecretValue("secret set")
	if err != nil {
		return err
	}
	name := flagName
	if name == "" {
		name = env
	}

	secret, err := authenticated(ctx, func(client api.Client) (api.Secret, error) {
		return client.CreateSecret(ctx, name, value, skill, env)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		// The declared fields, and only them: a caller that validates the object against
		// `--help` must not meet keys the command never promised.
		return writeJSON(map[string]any{
			"id": secret.ID, "name": secret.Name, "per_day": secret.PerDay,
			"used": secret.Used, "created_at": secret.CreatedAt, "bindings": secret.Bindings,
		})
	}
	fmt.Printf("已保存 %s（%s），绑定到 %s 的 %s。值不回显，也读不回来。\n",
		secret.Name, secret.ID, skill, env)
	fmt.Println("任何机器上运行这个 skill 都会自动拿到它。")
	return nil
}

func secretList(ctx context.Context, args []string) error {
	var skill string
	parsed, err := parseSecretArgs("secret list", args, map[string]*string{"--skill": &skill}, nil)
	if err != nil {
		return err
	}
	if parsed.id != "" {
		return usagef("%s", usageAt("secret list"))
	}
	if skill != "" {
		if err := checkSecretSkill("secret list", skill); err != nil {
			return err
		}
	}
	result, err := authenticated(ctx, func(client api.Client) (api.SecretList, error) {
		return client.ListSecrets(ctx, skill)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		return writeJSON(result)
	}
	if len(result.Items) == 0 {
		fmt.Printf("还没有服务凭据。加一条：printf '%%s' \"$KEY\" | skmr secret set --skill <地址> --env <NAME>\n")
		return nil
	}
	for _, secret := range result.Items {
		printSecretLine(secret)
	}
	fmt.Println("值不会出现在这里，也读不回来；要换就 rotate。")
	return nil
}

func secretRotate(ctx context.Context, args []string) error {
	parsed, err := parseSecretArgs("secret rotate", args, nil, nil)
	if err != nil {
		return err
	}
	if parsed.id == "" {
		return usagef("%s", usageAt("secret rotate"))
	}
	value, err := readSecretValue("secret rotate")
	if err != nil {
		return err
	}
	secret, err := authenticated(ctx, func(client api.Client) (api.Secret, error) {
		return client.RotateSecret(ctx, parsed.id, value)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		return writeJSON(map[string]any{"id": parsed.id, "rotated_at": secret.RotatedAt})
	}
	fmt.Printf("已更新 %s 的值。绑定与共享不变，下一次运行就用新值。\n", parsed.id)
	return nil
}

func secretRemove(ctx context.Context, args []string) error {
	parsed, err := parseSecretArgs("secret rm", args, nil, []string{"--yes"})
	if err != nil {
		return err
	}
	if parsed.id == "" {
		return usagef("%s", usageAt("secret rm"))
	}
	if !parsed.on("--yes") {
		// ADR 0056: the consequence and the exact command, and nothing executed.
		consequence := fmt.Sprintf("删除后 %s 立即失效：绑定它的 skill 再也拿不到值，"+
			"共享给别人的也一起失效；备份保留期过后无法恢复。", parsed.id)
		command := "skmr secret rm " + parsed.id + " --yes"
		if parsed.json {
			return &cliFailure{Code: "confirmation_required",
				Message: consequence + "确认后执行：" + command, JSON: true}
		}
		fmt.Println(consequence)
		fmt.Printf("确认后执行：\n%s\n", command)
		return &refusalError{message: "没有删除：删除要加 --yes。"}
	}
	if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.DeleteSecret(ctx, parsed.id)
	}); err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		return writeJSON(map[string]any{"id": parsed.id, "deleted": true})
	}
	fmt.Printf("已删除 %s；它的绑定与共享同时失效。\n", parsed.id)
	return nil
}

func secretBind(ctx context.Context, args []string) error {
	var skill, env string
	parsed, err := parseSecretArgs("secret bind", args, map[string]*string{
		"--skill": &skill, "--env": &env,
	}, nil)
	if err != nil {
		return err
	}
	if parsed.id == "" || skill == "" || env == "" {
		return usagef("bind 要 <id>、--skill <地址> 和 --env <NAME>。%s", usageAt("secret bind"))
	}
	if err := checkSecretSkill("secret bind", skill); err != nil {
		return err
	}
	if !validSecretEnv(env) {
		return usagef("--env 要是合法的环境变量名（字母、数字、下划线，不以数字开头），收到 %q。%s",
			env, usageAt("secret bind"))
	}
	binding, err := authenticated(ctx, func(client api.Client) (api.SecretBinding, error) {
		return client.BindSecret(ctx, parsed.id, skill, env)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		return writeJSON(binding)
	}
	fmt.Printf("已绑定：%s → %s 的 %s。\n", parsed.id, binding.Skill, binding.Env)
	return nil
}

func secretUnbind(ctx context.Context, args []string) error {
	var skill, env string
	parsed, err := parseSecretArgs("secret unbind", args, map[string]*string{
		"--skill": &skill, "--env": &env,
	}, nil)
	if err != nil {
		return err
	}
	if parsed.id == "" || skill == "" || env == "" {
		return usagef("unbind 要 <id>、--skill <地址> 和 --env <NAME>。%s", usageAt("secret unbind"))
	}
	if err := checkSecretSkill("secret unbind", skill); err != nil {
		return err
	}
	if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.UnbindSecret(ctx, parsed.id, skill, env)
	}); err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		return writeJSON(map[string]any{
			"id": parsed.id, "skill": skill, "env": env, "bound": false})
	}
	fmt.Printf("已解除绑定：%s 不再拿到 %s。值还在，别的绑定不受影响。\n", skill, env)
	return nil
}

func secretShares(ctx context.Context, args []string) error {
	var skill string
	parsed, err := parseSecretArgs("secret shares", args, map[string]*string{"--skill": &skill}, nil)
	if err != nil {
		return err
	}
	if parsed.id == "" {
		return usagef("%s", usageAt("secret shares"))
	}
	if skill != "" {
		// Same guard as the commands that bind: a key pasted into --skill must be refused here,
		// not carried to the server inside a query string.
		if err := checkSecretSkill("secret shares", skill); err != nil {
			return err
		}
	}
	result, err := authenticated(ctx, func(client api.Client) (api.SecretShares, error) {
		return client.ReadSecretShares(ctx, parsed.id, skill)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		return writeJSON(result)
	}
	if len(result.Items) == 0 {
		fmt.Println("这条凭据没有共享给任何人。")
	} else {
		for _, share := range result.Items {
			fmt.Printf("%s\t%s\t%s\t%s\n", share.User, share.Skill, share.Env, share.CreatedAt)
		}
	}
	// **The sentence that points at the command that does exist.** Sharing is the console's, and a
	// person who read this far wants to give one — telling them 「没有命令」 would send them looking
	// for something that is there under another name.
	fmt.Println("要共享用 skmr secret share；撤销用 skmr secret unshare。共享本身必须在网页上由人确认：" +
		"共享出去的值对方能拿到明文。")
	return nil
}

// secretShare is a **page action** (ADR 0126): it opens 权限 › 服务凭据 with the credential, the
// skill and the person already chosen, and its first line says the action has not happened.
//
// **It sends no request.** Creating a grant is ADR 0081's first exception, and the server enforces
// that by not having the route: `POST /api/v1/secrets/{id}/shares` answers 405. So this command's
// job is to be findable and to put a person in front of the screen where the risk is written out.
func secretShare(ctx context.Context, args []string) error {
	_ = ctx
	var skill, user string
	parsed, err := parseSecretArgs("secret share", args, map[string]*string{
		"--skill": &skill,
		"--user":  &user,
	}, []string{"--print"})
	if err != nil {
		return err
	}
	if parsed.id == "" || skill == "" {
		return usagef("%s", usageAt("secret share"))
	}
	// The same guard the commands that write a binding use: an address, not a key somebody pasted.
	if err := checkSecretSkill("secret share", skill); err != nil {
		return err
	}
	query := url.Values{}
	// **`state` is what opens the card.** Without it the console shows the list, and the two
	// parameters below are read by nothing — a command whose whole job is to put a person in front
	// of the confirmation would drop them one screen short of it.
	query.Set("state", "share")
	query.Set("secret", parsed.id)
	query.Set("share", skill)
	if user != "" {
		query.Set("user", user)
	}
	target := config.WebURL() + "/account/secrets?" + query.Encode()

	opened := false
	if !parsed.on("--print") {
		if openErr := openBrowser(target); openErr != nil {
			fmt.Fprintf(os.Stderr, "没能自动打开浏览器（地址在上面，也可以自己打开）：%v\n", openErr)
		} else {
			opened = true
		}
	}
	if parsed.json {
		return writeJSON(struct {
			Action    string `json:"action"`
			Performed bool   `json:"performed"`
			URL       string `json:"url"`
			Opened    bool   `json:"opened"`
		}{"share", false, target, opened})
	}
	// The first line is the sentence, not the address — the same shape `skills publish` uses.
	fmt.Println("还没执行：共享只能在网页上由人确认。")
	fmt.Println(target)
	switch {
	case opened:
		fmt.Println("（已在浏览器里打开。）")
	case parsed.on("--print"):
		fmt.Println("（--print：只打印地址，没有打开浏览器。）")
	default:
		fmt.Println("（没能自动打开浏览器；地址就在上面。）")
	}
	return nil
}

// secretUnshare withdraws one person's grant. **Direct, unlike share** — taking access back is not
// the risky direction, so it needs no page (ADR 0056 is about the console asking before it).
func secretUnshare(ctx context.Context, args []string) error {
	var skill, user string
	parsed, err := parseSecretArgs("secret unshare", args, map[string]*string{
		"--skill": &skill,
		"--user":  &user,
	}, nil)
	if err != nil {
		return err
	}
	if parsed.id == "" || skill == "" || user == "" {
		return usagef("%s", usageAt("secret unshare"))
	}
	if err := checkSecretSkill("secret unshare", skill); err != nil {
		return err
	}
	_, err = authenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.UnshareSecret(ctx, parsed.id, skill, user)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		return writeJSON(struct {
			ID      string `json:"id"`
			Skill   string `json:"skill"`
			User    string `json:"user"`
			Revoked bool   `json:"revoked"`
		}{parsed.id, skill, user, true})
	}
	fmt.Printf("%s 在 %s 上的下一次运行就用不了这把凭据了。值本身不变，绑定也不变。\n", user, skill)
	return nil
}

// secretIncoming lists the variables this machine would receive from other people's credentials on
// one skill — the read that makes 「这个 skill 为什么能跑」 answerable for someone who bound nothing.
func secretIncoming(ctx context.Context, args []string) error {
	var skill string
	parsed, err := parseSecretArgs("secret incoming", args, map[string]*string{
		"--skill": &skill,
	}, nil)
	if err != nil {
		return err
	}
	if parsed.id != "" || skill == "" {
		return usagef("%s", usageAt("secret incoming"))
	}
	if err := checkSecretSkill("secret incoming", skill); err != nil {
		return err
	}
	result, err := authenticated(ctx, func(client api.Client) (api.IncomingSecrets, error) {
		return client.IncomingSecrets(ctx, skill)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if parsed.json {
		return writeJSON(result)
	}
	if len(result.Items) == 0 {
		fmt.Printf("没有人在 %s 上共享凭据给你。\n", skill)
		return nil
	}
	for _, item := range result.Items {
		fmt.Printf("%s\t%s\t来自 %s 的「%s」\n", item.Env, item.Skill, item.Owner, item.Name)
	}
	return nil
}

func secretQuota(ctx context.Context, args []string) error {
	var perDayRaw string
	parsed, err := parseSecretArgs("secret quota", args, map[string]*string{
		"--per-day": &perDayRaw,
	}, nil)
	if err != nil {
		return err
	}
	if parsed.id == "" || perDayRaw == "" {
		return usagef("quota 要 <id> 和 --per-day <N>。%s", usageAt("secret quota"))
	}
	perDay, err := strconv.Atoi(perDayRaw)
	if err != nil || perDay < 1 || perDay > 10000 {
		return usagef("--per-day 是 1 到 10000 的整数，收到 %q。%s", perDayRaw, usageAt("secret quota"))
	}

	// **Raising is the direction that has to be a person's decision** (T12): the whole reason a
	// limit exists is a runaway skill or a leaked lease, and a caller that can raise its own limit
	// has neither. Lowering needs no gate — it only takes access away from itself.
	current, err := authenticated(ctx, func(client api.Client) (api.Secret, error) {
		return findSecret(ctx, client, parsed.id)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	if perDay > current.PerDay {
		if parsed.json {
			// A confirmation and a machine-readable result cannot be the same answer: the prompt
			// would land on stdout, in front of the JSON. Raising has to be an interactive command.
			return usagef("提高上限要在终端里确认，不能和 --json 一起用。%s", usageAt("secret quota"))
		}
		if err := confirmQuotaRaise(parsed.id, current.PerDay, perDay); err != nil {
			return err
		}
	}

	updated, err := authenticated(ctx, func(client api.Client) (api.Secret, error) {
		return client.SetSecretQuota(ctx, parsed.id, perDay)
	})
	if err != nil {
		return versionErr(parsed.json, secretFailure(err, parsed.json), "")
	}
	limit := updated.PerDay
	if limit == 0 {
		limit = perDay
	}
	if parsed.json {
		return writeJSON(map[string]any{
			"id": parsed.id, "per_day": limit, "was": current.PerDay})
	}
	fmt.Printf("上限已改为 %d 次/天（原来是 %d）。\n", limit, current.PerDay)
	return nil
}

// confirmQuotaRaise refuses in an environment where nobody can answer, and otherwise asks.
//
// **A TTY is the gate, not `--yes`.** `--yes` is a flag, and an agent that just hit the limit can
// type one; what this is meant to stop is precisely that agent. The terminal check is what makes
// the answer come from a person.
func confirmQuotaRaise(id string, from, to int) error {
	if !stdinIsTerminal() {
		return usagef("提高上限要有人在终端里确认，而这里不是终端。"+
			"请让用户运行：skmr secret quota %s --per-day %d。"+
			"不要自行提高上限——上限存在的意义就是拦住失控的调用。%s", id, to,
			usageAt("secret quota"))
	}
	fmt.Printf("这是提高上限：%s 从 %d 次/天 到 %d 次/天。\n", id, from, to)
	fmt.Println("上限拦住的是失控的 skill 或泄露的租约；确认这是你要的再回答。")
	fmt.Print("确认提高？[y/N] ")
	var answer string
	fmt.Fscanln(stdin, &answer)
	switch strings.ToLower(strings.TrimSpace(answer)) {
	case "y", "yes":
		return nil
	default:
		return &refusalError{message: "没有改：回答不是 y。"}
	}
}

// findSecret reads the vault for one id. There is no `GET /secrets/{id}` — the list is the only
// reader, and a credential is one row in it.
func findSecret(ctx context.Context, client api.Client, id string) (api.Secret, error) {
	result, err := client.ListSecrets(ctx, "")
	if err != nil {
		return api.Secret{}, err
	}
	for _, secret := range result.Items {
		if secret.ID == id {
			return secret, nil
		}
	}
	// **The same code the server would have answered.** This path never reaches the server (the id
	// is simply not in the listing), and a plain error would be classified as `network` — a script
	// would then retry a typo instead of fixing it.
	return api.Secret{}, &cliFailure{
		Code:       "credential_not_found",
		ServerCode: "credential_not_found",
		Message:    "找不到凭据 " + id + "，或者它不是你的。看你的凭据：skmr secret list",
	}
}

func printSecretLine(secret api.Secret) {
	used := fmt.Sprintf("%d/%d", secret.Used, secret.PerDay)
	where := "未绑定任何 skill（等于没有，没有 skill 能拿到它）"
	if len(secret.Bindings) > 0 {
		targets := make([]string, 0, len(secret.Bindings))
		for _, binding := range secret.Bindings {
			targets = append(targets, binding.Env+"@"+binding.Skill)
		}
		where = strings.Join(targets, ",")
	}
	last := secret.LastUsedAt
	if last == "" {
		last = "从未使用"
	}
	fmt.Printf("%s\t%s\t%s\t%s\t%s\n", secret.ID, secret.Name, where, used, last)
}

// secretFailure turns the credential codes into the sentence a person can act on.
//
// **The code travels with the sentence.** A plain `errors.New` would lose it, and `versionErr`
// would then classify the failure as `network` — so `--json` would answer a 409 about a duplicate
// binding with `{"code":"network"}`. `cliFailure` keeps both halves: what to say, and what a script
// switches on.
func secretFailure(err error, jsonMode bool) error {
	var server *api.ServerError
	if !errors.As(err, &server) {
		return err
	}
	wire := strings.ToLower(server.Code)
	failure := func(message string) error {
		return &cliFailure{Code: wire, Message: message, ServerCode: wire, JSON: jsonMode}
	}
	switch wire {
	case "credential_not_found":
		return failure("找不到这条凭据，或者它不是你的。看你的凭据：skmr secret list")
	case "credential_binding_not_found":
		return failure("这条凭据在这个 skill 的这个变量上没有绑定，没有可解除的。" +
			"看它绑到哪了：skmr secret list")
	case "credential_name_conflict":
		return failure("你已经有一条同名凭据了。换一个名字：" +
			"skmr secret set --name <另一个名字> …")
	case "credential_binding_conflict":
		return failure("这个 skill 的这一个环境变量上已经有一条绑定了。" +
			"先解除旧的（skmr secret unbind <id> --skill <地址> --env <NAME>），或换一个变量名。")
	case "credential_quota_exceeded":
		return failure("这条凭据今天的取用次数到上限了。让用户提高上限——" +
			"不要自行提高：" + server.Message)
	case "credential_value_too_large":
		return failure(fmt.Sprintf("值太大：环境变量装不下。上限 %d 字节。", secretValueLimit))
	case "credential_share_not_found":
		// **Its own sentence, because the fact is its own.** Reusing the binding's "no binding on
		// that variable" here sent the reader to `secret list` when what they need is
		// `secret shares` — the two tables and the two next steps are different.
		return failure("这个人在这个 skill 上没有共享给你，没有可撤销的。" +
			"看谁在用：skmr secret shares <id> --skill <地址>")
	case "credential_not_shareable":
		return failure("没有这个账号。核对用户名拼写。")
	}
	// **No generic 404 fallback.** It used to rewrite every 404 into "no such credential", which
	// `run` would print for a skill that was deleted between the metadata read and the lease. A code
	// this build does not know is passed through with the server's own words.
	return server
}

// leaseCredentials asks for the values this run may inject, as `NAME=value` entries.
//
// **Called only when the metadata said there are bindings.** A lease for a skill with none would be
// a request made to learn what the server already answered, and it is the one request this version
// is allowed to add — so it is added only where it does something.
func leaseCredentials(ctx context.Context, address string) ([]string, error) {
	client, err := session(ctx)
	if err != nil {
		return nil, err
	}
	lease, err := client.LeaseCredentials(ctx, address)
	if err != nil {
		// The quota refusal is the one a run can meet, and its sentence names the command a person
		// runs; an untranslated one would reach the child's stderr in English.
		return nil, secretFailure(err, false)
	}
	return lease.EnvPairs(), nil
}

// checkSecretSkill refuses a malformed address before it becomes a request path — the same guard
// the other commands use, reached here so a key pasted into `--skill` is not quoted back.
func checkSecretSkill(path, address string) error {
	if err := checkAddressSyntax(path, address); err != nil {
		return err
	}
	return nil
}

// validSecretEnv is the same rule the server applies when the binding is written.
func validSecretEnv(name string) bool {
	if name == "" {
		return false
	}
	for index, r := range name {
		letter := r == '_' || (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z')
		digit := r >= '0' && r <= '9'
		if index == 0 && !letter {
			return false
		}
		if !letter && !digit {
			return false
		}
	}
	return true
}
