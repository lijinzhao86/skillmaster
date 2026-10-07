package credentials

import (
	"errors"
	"fmt"

	"github.com/zalando/go-keyring"
)

// keychainService names this application in the OS keychain. Changing it orphans every stored
// credential — the old entries stay behind, invisible, and the next run looks like a machine that
// was never signed in.
const keychainService = "skillmaster-cli"

// keychainStore keeps the credential in the OS keychain.
//
// Keyed by the **server**, not by a constant. Two servers — somebody's local stack and the real
// one — otherwise share one entry, and the symptom is a 401 from whichever was used second while
// the credential that works sits in the keychain under a name nobody is looking at.
type keychainStore struct {
	server string
}

func (s *keychainStore) Where() string {
	return fmt.Sprintf("系统钥匙串（%s / %s）", keychainService, s.server)
}

func (s *keychainStore) Load() (Credentials, bool, error) {
	blob, err := keyring.Get(keychainService, s.server)
	if errors.Is(err, keyring.ErrNotFound) {
		return Credentials{}, false, nil
	}
	if err != nil {
		return Credentials{}, false, fmt.Errorf("reading the keychain entry: %w", err)
	}
	parsed, err := ParseBlob([]byte(blob))
	if err != nil {
		return Credentials{}, false, fmt.Errorf("parsing the keychain entry: %w", err)
	}
	return parsed, true, nil
}

func (s *keychainStore) Save(c Credentials) error {
	blob, err := c.Blob()
	if err != nil {
		return err
	}
	if err := keyring.Set(keychainService, s.server, string(blob)); err != nil {
		return fmt.Errorf("writing the keychain entry: %w", err)
	}
	return nil
}

func (s *keychainStore) Delete() error {
	err := keyring.Delete(keychainService, s.server)
	if err == nil || errors.Is(err, keyring.ErrNotFound) {
		return nil
	}
	return fmt.Errorf("removing the keychain entry: %w", err)
}

// openWith is Open's decision on its own, so it can be tested on any machine.
//
// The platform probe that feeds it lives in keychain_linux.go and keychain_other.go, because the
// question it answers — "is there a session D-Bus?" — only exists on Linux and cannot be asked by
// a test running on macOS.
//
// The keychain branch is wrapped in a deadline, because "there is one" and "it will answer" are
// different questions and only the first is answerable from the environment — see responsiveKeychain.
func openWith(usable bool, path, server string) (Store, string) {
	if usable {
		return newResponsiveKeychain(&keychainStore{server: server}, &fileStore{path: path}), ""
	}
	return &fileStore{path: path}, fallbackNotice(path)
}
