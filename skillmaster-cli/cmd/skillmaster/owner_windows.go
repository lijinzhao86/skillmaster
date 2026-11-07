//go:build windows

package main

import "os"

// ownedByMe has no portable answer on Windows, and `%TEMP%` is the user's own directory there —
// the shared-directory problem this guards against does not arise.
func ownedByMe(os.FileInfo) bool { return true }
