// Package version is what the binary says about itself.
package version

// Version is the release this binary came from.
//
// **Empty until a build fills it in.** The release pipeline passes
//
//	-ldflags "-X github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version.Version=1.0.0"
//
// and a plain `go build` or `go run` never does — that binary is a development build, and saying so
// is better than inventing a number that looks like a release (ADR 0088).
//
// It is a variable rather than a constant for one reason: `-X` only writes variables.
var Version = "dev"
