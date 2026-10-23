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
	mutedPackages = make(map[mutedAppKey]struct{})
	audioMu.Unlock()

	if len(listMutedApps()) != 0 {
		t.Fatal("expected empty mutedPackages initially")
	}

	// Verify dual-app (same package across user 0 and user 999) does NOT overwrite each other
	audioMu.Lock()
	mutedPackages[mutedAppKey{Pkg: "com.tencent.mm", UserID: 0}] = struct{}{}
	mutedPackages[mutedAppKey{Pkg: "com.tencent.mm", UserID: 999}] = struct{}{}
	mutedPackages[mutedAppKey{Pkg: "com.test.app", UserID: 0}] = struct{}{}
	audioMu.Unlock()

	got := listMutedApps()
	want := []string{
		"com.tencent.mm",
		"com.tencent.mm (user 999)",
		"com.test.app",
	}

	audioMu.Lock()
	mutedPackages = make(map[mutedAppKey]struct{})
	audioMu.Unlock()

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("mutedPackages tracking mismatch: got %v, want %v", got, want)
	}
}
