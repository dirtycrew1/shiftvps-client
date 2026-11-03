//go:build !darwin

package daemonhost

import "errors"

func ServiceInstallIfAbsent(string) error {
	return errors.New("create-only service installation is supported only on macOS")
}
