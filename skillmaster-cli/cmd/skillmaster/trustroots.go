// The CLI carries its own trust store, and this is where it is built.
//
// **Why macOS does not use the OS verifier.** On macOS, Go verifies certificates through
// Security.framework, which asks com.apple.trustd.agent over Mach IPC. An agent's sandbox blocks
// that IPC, so every HTTPS request fails with `x509: OSStatus -26276` — not because the certificate
// is bad, but because the verifier could not be reached. curl and Python read the CA bundle file
// directly and keep working, which is why the failure looks like a Go-only problem.
//
// **The blank import is not enough.** `golang.org/x/crypto/x509roots/fallback` only calls
// x509.SetFallbackRoots, and Go uses that pool only when the system roots are unavailable. On macOS
// loadSystemRoots returns a CertPool with `systemPool: true` and no error, so the system pool looks
// "available" and the fallback is discarded; Verify then calls systemVerify and hits trustd again.
// `//go:debug x509usefallbackroots=1` is what forces the fallback pool, and it lives in
// trustroots_darwin.go because the directive is process-wide and macOS is the only platform with the
// problem (ADR 0167).
//
// **Consequences, stated rather than discovered.** On macOS the CLI no longer consults the system
// trust store: public roots are the embedded NSS bundle, and a private CA (an enterprise MITM proxy,
// a self-hosted deployment) is added through SKILLMASTER_CA_BUNDLE, whose value is a PEM path. That
// is the one escape hatch, and it is deliberately explicit. The platform verifier no longer runs, so
// its revocation and CT checks do not either — the remaining checks are crypto/x509's chain and
// hostname verification. Linux and Windows are untouched: there the system store is used, and a
// corporate CA still goes into it.
package main

import (
	"crypto/x509"
	"fmt"
	"os"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/sandbox"
	"golang.org/x/crypto/x509roots/fallback/bundle"
)

func init() {
	x509.SetFallbackRoots(buildTrustPool())
}

// buildTrustPool is the whole trust store: the embedded public roots plus, when the variable names
// one, a PEM file of private roots.
func buildTrustPool() *x509.CertPool {
	pool := x509.NewCertPool()
	for root := range bundle.Roots() {
		cert, err := x509.ParseCertificate(root.Certificate)
		if err != nil {
			// The bundle is compiled into the binary; a parse failure is a broken build, not a user
			// error, and continuing with a partial store would hide it.
			panic(fmt.Sprintf("parsing an embedded root certificate: %v", err))
		}
		if root.Constraint == nil {
			pool.AddCert(cert)
		} else {
			pool.AddCertWithConstraint(cert, root.Constraint)
		}
	}
	if path := os.Getenv(sandbox.CAVariable); path != "" {
		pem, err := os.ReadFile(path)
		if err != nil {
			// Not fatal: the public roots still work. The warning is the only way the person learns
			// that the file they pointed at was not read.
			fmt.Fprintf(os.Stderr, "（%s：%v）\n", sandbox.CAVariable, err)
		} else if !pool.AppendCertsFromPEM(pem) {
			fmt.Fprintf(os.Stderr, "（%s 里没有可用的证书：%s）\n", sandbox.CAVariable, path)
		}
	}
	return pool
}
