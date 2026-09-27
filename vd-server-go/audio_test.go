package main

import (
	"reflect"
	"testing"
)

func TestParseUserIDsFromOutput(t *testing.T) {
	sample := `Users:
	UserInfo{0:机主:4c13} running
	UserInfo{999:MultiApp:4001010} running
`
	got := parseUserIDsFromOutput(sample)
	want := []int{0, 999}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parseUserIDsFromOutput mismatch: got %v, want %v", got, want)
	}

	emptySample := "No users"
	gotDefault := parseUserIDsFromOutput(emptySample)
	if len(gotDefault) != 1 || gotDefault[0] != 0 {
		t.Fatalf("expected fallback [0], got %v", gotDefault)
	}
}

func TestMutedPackagesTracking(t *testing.T) {
	audioMu.Lock()
	mutedPackages = make(map[string]int)
	audioMu.Unlock()

	if len(mutedPackages) != 0 {
		t.Fatal("expected empty mutedPackages initially")
	}

	// Verify state mutation under lock
	audioMu.Lock()
	mutedPackages["com.test.app"] = 0
	mutedPackages["com.test.clone"] = 999
	audioMu.Unlock()

	audioMu.Lock()
	count := len(mutedPackages)
	u0 := mutedPackages["com.test.app"]
	u999 := mutedPackages["com.test.clone"]
	mutedPackages = make(map[string]int)
	audioMu.Unlock()

	if count != 2 || u0 != 0 || u999 != 999 {
		t.Fatalf("mutedPackages tracking error: count=%d, u0=%d, u999=%d", count, u0, u999)
	}
}
