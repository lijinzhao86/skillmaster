package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/sandbox"
)

// TestTheFallbackRootsAreForcedOnlyWhereTheProblemExists is the guard for the fix itself.
//
// **A unit test on buildTrustPool cannot catch a regression here.** Deleting the //go:debug line, or
// moving it back into the shared file, leaves every other test green — the pool is built the same
// way either way. What changes is whether Go *uses* it, and that is baked into the binary's
// DefaultGODEBUG, so the binary is what the test reads. macOS must have it; the other platforms must
// not, because there the system trust store is the right one (ADR 0167).
func TestTheFallbackRootsAreForcedOnlyWhereTheProblemExists(t *testing.T) {
	forced := settingsContain(t, "DefaultGODEBUG", "x509usefallbackroots=1")
	if want := runtime.GOOS == "darwin"; forced != want {
		t.Fatalf("x509usefallbackroots forced = %v on %s, want %v: check the //go:debug directive in trustroots_darwin.go",
			forced, runtime.GOOS, want)
	}
}

func settingsContain(t *testing.T, key, value string) bool {
	t.Helper()
	info, ok := debug.ReadBuildInfo()
	if !ok {
		t.Fatal("this binary carries no build info, so the guard cannot run")
	}
	for _, setting := range info.Settings {
		if setting.Key != key {
			continue
		}
		for _, one := range strings.Split(setting.Value, ",") {
			if one == value {
				return true
			}
		}
	}
	return false
}

func TestBuildTrustPoolCarriesTheEmbeddedRoots(t *testing.T) {
	pool := buildTrustPool()
	if pool == nil || len(pool.Subjects()) == 0 {
		t.Fatal("the trust pool has no roots")
	}
}

func TestBuildTrustPoolAddsTheExtraBundle(t *testing.T) {
	before := len(buildTrustPool().Subjects())
	path := filepath.Join(t.TempDir(), "ca.pem")
	if err := os.WriteFile(path, extraCA(t), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv(sandbox.CAVariable, path)

	after := len(buildTrustPool().Subjects())
	if after != before+1 {
		t.Fatalf("extra roots = %d, want 1", after-before)
	}
}

func extraCA(t *testing.T) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "skillmaster test CA"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
}
