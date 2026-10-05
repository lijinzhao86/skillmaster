//go:build linux

package credentials

import "os"

// keychainUsable reports whether this Linux session can reach a Secret Service.
//
// **This check is the whole reason the constant exists**, and it has to happen before anything
// touches the keychain rather than being discovered by trying. The library reaches the secret
// service over the session D-Bus, and with no bus it does not fail — it waits, for roughly three
// seconds, on every invocation. A container, a CI runner, an SSH session: all of them have no
// DBUS_SESSION_BUS_ADDRESS, and all of them would pay that on every single command a task makes.
//
// The variable is the cheap, reliable signal. A bus address that is set but dead is possible, and
// that case surfaces as an error from the store rather than as a silent wait — which is the right
// way round: a wrong answer here costs a wait, so the check errs towards "usable" only when
// something claims there is a bus.
func keychainUsable() bool {
	return os.Getenv("DBUS_SESSION_BUS_ADDRESS") != ""
}
