package credentials

import (
	"errors"
	"fmt"
)

// fileFirstStore is the default since ADR 0185: the file is written and read first, and the keychain
// is a read fallback, so a machine that signed in before this version stays signed in.
//
// **Why the keychain is still here at all.** Dropping it entirely would sign out every existing
// machine on upgrade. Reading it only when the file has nothing keeps that migration free and keeps
// an ordinary command off the keychain: the file answers, and the fallback is never called.
//
// The keychain it is given is a responsiveKeychain, so even the migration path cannot hang a command
// behind an authorization prompt.
type fileFirstStore struct {
	file     *fileStore
	keychain Store // nil when this platform has no keychain
	served   string
}

func newFileFirst(file *fileStore, keychain Store) *fileFirstStore {
	return &fileFirstStore{file: file, keychain: keychain}
}

func (s *fileFirstStore) Load() (Credentials, bool, error) {
	creds, found, err := s.file.Load()
	if err != nil {
		// **A broken file is not a reason to refuse to be signed in.** It is also not silently
		// ignored: if the keychain has a credential, that one is served and the file error is not
		// returned; if it has nothing, the file's error is what the person needs to see.
		if s.keychain != nil {
			if fromKeychain, keychainFound, keychainErr := s.keychain.Load(); keychainErr == nil && keychainFound {
				s.served = sourceOf(s.keychain)
				return fromKeychain, true, nil
			}
		}
		return creds, false, err
	}
	if found {
		s.served = s.file.Source()
		return creds, true, nil
	}
	if s.keychain == nil {
		return Credentials{}, false, nil
	}
	creds, found, err = s.keychain.Load()
	if found {
		s.served = sourceOf(s.keychain)
	}
	return creds, found, err
}

func (s *fileFirstStore) Save(creds Credentials) error {
	if err := s.file.Save(creds); err != nil {
		return err
	}
	s.served = s.file.Source()
	return nil
}

// Clear takes the credential out of both places, because the keychain may still hold one from a
// version that wrote there. **A keychain that does not answer is reported, not forgiven**: what
// survives is a live credential on a machine the person was told was clean — the same reason
// responsiveKeychain.Clear reports its timeout.
func (s *fileFirstStore) Clear() error {
	s.served = ""
	fileErr := s.file.Clear()
	if s.keychain == nil {
		return fileErr
	}
	keychainErr := s.keychain.Clear()
	switch {
	case keychainErr != nil && fileErr == nil:
		if errors.Is(keychainErr, errKeychainSilent) {
			// The keychain store already phrased this ("文件里那份已经清掉，但…"); wrapping it again
			// would repeat the first half.
			return keychainErr
		}
		return fmt.Errorf("文件里那份已经清掉，但钥匙串没清掉：%w", keychainErr)
	case keychainErr != nil && fileErr != nil:
		return errors.Join(fileErr, keychainErr)
	default:
		return fileErr
	}
}

func (s *fileFirstStore) Where() string { return s.file.Where() }

func (s *fileFirstStore) Source() string {
	if s.served != "" {
		return s.served
	}
	return s.file.Where()
}
