//go:build darwin

package daemonhost

import "testing"

// The CI runner exercises only the bounded read-only inventory query, never setup.
func TestDarwinCreateOnlyRealSystemInventory(t *testing.T) {
	if err := requireDarwinServiceAbsent(); err != nil {
		t.Fatal("macOS_system_inventory=unavailable_or_existing")
	}
	t.Log("macOS_system_inventory=recognized_absent")
}
