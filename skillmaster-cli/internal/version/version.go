// Package version is what the binary says about itself.
package version

// Version is the release this binary came from.
//
// **`dev` until a build fills it in.** The release pipeline passes
//
//	-ldflags "-X github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version.Version=1.0.0"
//
// and a plain `go build` or `go run` never does — that binary is a development build, and saying so
// is better than inventing a number that looks like a release (ADR 0088).
//
// It is a variable rather than a constant for one reason: `-X` only writes variables.
var Version = "dev"

// Revision is the commit the binary was built from, short form (`a1b2c3d`).
//
// **A beta's version string does not move between builds** (ADR 0099): every beta of 1.1.0 says
// `1.1.0-beta`, and without this the person holding a binary could not tell two of them apart —
// nor could a bug report say which build it came from. Formal releases leave it empty: the version
// number already names one commit for good, and a second identity would only be a second thing to
// keep in step.
var Revision = ""

// UpdateBase is where this binary updates itself from, without a trailing slash.
//
// **Empty means "this build does not update itself".** A plain `go build` leaves it empty, which is
// the same answer ADR 0088 gives for `Version == "dev"`: a working copy must not replace itself
// with a release, and tests say where to look with `SKILLMASTER_UPDATE_BASE` instead.
//
// The release pipeline passes, in the same `-ldflags` line as `DefaultServer`,
//
//	-X github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version.UpdateBase=$BASE
//
// where `$BASE` is the channel the build belongs to: `https://dl.skillmasterai.com` for a formal
// release and `https://dl.skillmasterai.com/beta` for a beta (ADR 0103). The channel is therefore a
// property of the binary, not something read at run time — a machine cannot be talked into pulling
// a beta over a formal install by a file it reads.
var UpdateBase = ""

// String is what `--version` prints for the release half: the version, and the build when there is
// one to tell apart.
func String() string {
	if Revision == "" {
		return Version
	}
	return Version + " (" + Revision + ")"
}
