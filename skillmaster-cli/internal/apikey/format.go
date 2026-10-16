// Package apikey is what an API key looks like from this side of the wire.
//
// One fact, in one place, for the same reason the server keeps its own copy in one class: the
// prefix is what tells a key apart from an OAuth token, and it is read in two places here — the
// paste that login checks before spending a request on it, and the branch that decides a refused
// credential cannot be renewed. Two spellings of it would be one that drifts, and the way that
// drifts is a key that is treated as a token: the CLI would try to refresh something that has no
// refresh token and report a message about the wrong thing.
//
// **It is a shape check and never a security boundary.** The server decides by hashing, and a
// string that merely looks like a key is refused by it like any other unknown credential. What
// this buys is that a paste which cannot possibly be a key is refused before it costs a round trip,
// with a sentence about the paste rather than a 401.
package apikey

import (
	"regexp"
	"strconv"
	"strings"
)

// Prefix begins every key this service issues (ADR 0038 §决定 2).
const Prefix = "skm_"

// secretLength is the rest of the string: 32 bytes as base64url without padding.
const secretLength = 43

// TotalLength is what a whole key measures, for the sentence that tells somebody their paste was
// truncated. Derived rather than written down, so the two can never disagree.
const TotalLength = len(Prefix) + secretLength

// InAnArgument is what a refusal says instead of repeating a key it was handed by mistake.
//
// **One copy, because two refusals say it**: the command layer's `namedArgument`, when a key turns up
// where a subcommand or a flag goes, and the address grammar's own message, when one turns up where
// `<namespace>/<name>` goes. Both were quoting what they were given, which puts the secret in the
// terminal and in whatever collects a CI job's output — one more place it lives, in the message that
// was supposed to protect it. What is left to name is the part the person has to change anyway.
//
// **It says where a key goes, and v4 changed where that is.** It used to name the `login` flag that
// read a key from standard input, which this version removed: a key is a long-lived credential, it is
// handed to a machine through the environment, and pasting one onto a machine is not what a login
// does any more. A refusal that named a command that no longer exists would be a dead end dressed as
// help — and this is the one sentence a person sees at the moment they are holding a key and
// wondering what to do with it, so it says where a key does go: the environment, for a machine with
// nobody at it. The variable's name is deliberately not spelled here — it is `config.ApiKeyEnv`, this
// package is below that layer and is not going to import it for one string, and the CLI's own usage
// text is where the name belongs.
const InAnArgument = "（这是一把 API key。它不写在命令行上——无人值守的机器把它放进环境变量交给 CLI；" +
	"要换一把就到控制台的「API 密钥」里签）"

// whole is the whole key: the prefix and nothing but the base64url alphabet after it.
var whole = regexp.MustCompile(
	`^` + regexp.QuoteMeta(Prefix) + `[A-Za-z0-9_-]{` + strconv.Itoa(secretLength) + `}$`)

// IsKey answers the routing question — is this a key rather than an OAuth token?
//
// **Deliberately loose, and deliberately the same test the server makes**: a string that begins with
// the prefix takes the key path there and then fails the hash lookup like any other unknown
// credential, so this side has to route it the same way. Requiring the full shape here would be a
// second opinion about what a key is, and the day the two disagree the wrong one would be this one.
//
// **The bare prefix counts, and that is not pedantry.** `strings.HasPrefix` rather than a length
// floor, because `skm_` alone is a key to the server (it routes it, finds no hash, answers
// `unauthenticated`) and an OAuth token to a length check — which would send the CLI down the
// renewal path and report「请重新运行 login --client-credentials」for a credential that was simply
// pasted short. The two sides' tests pin the same answer for this string on purpose.
func IsKey(token string) bool {
	return strings.HasPrefix(token, Prefix)
}

// HasShape is the paste check: could this string be a key at all?
func HasShape(token string) bool {
	return whole.MatchString(token)
}
