//go:build darwin

package daemonhost

func defaultWorkingDir() string {
	return "/Library/Application Support/shiftvps"
}

func defaultSocketPath() string {
	return DefaultDaemonSocketPath
}
