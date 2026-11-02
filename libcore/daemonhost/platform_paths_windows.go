//go:build windows

package daemonhost

import (
	"os"
	"path/filepath"
)

func defaultWorkingDir() string {
	if programData := os.Getenv("ProgramData"); programData != "" {
		return filepath.Join(programData, "shiftvps")
	}
	return `C:\ProgramData\shiftvps`
}

func defaultSocketPath() string {
	return DefaultDaemonPipePath
}
