package main

import "testing"

func TestNormalizeLaunchTarget(t *testing.T) {
	tests := []struct {
		name        string
		pkg         string
		act         string
		user        *int
		expectedPkg string
		expectedAct string
		expectedUID int
	}{
		{
			name:        "clean package",
			pkg:         "com.tencent.mm",
			act:         "",
			user:        nil,
			expectedPkg: "com.tencent.mm",
			expectedAct: "",
			expectedUID: 0,
		},
		{
			name:        "package with embedded activity",
			pkg:         "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
			act:         "",
			user:        nil,
			expectedPkg: "com.tencent.mm",
			expectedAct: "com.tencent.mm.ui.LauncherUI",
			expectedUID: 0,
		},
		{
			name:        "package with --user 999",
			pkg:         "com.tencent.mm --user 999",
			act:         "",
			user:        nil,
			expectedPkg: "com.tencent.mm",
			expectedAct: "",
			expectedUID: 999,
		},
		{
			name:        "package with activity and --user 999",
			pkg:         "com.tencent.mm/com.tencent.mm.ui.LauncherUI --user 999",
			act:         "",
			user:        nil,
			expectedPkg: "com.tencent.mm",
			expectedAct: "com.tencent.mm.ui.LauncherUI",
			expectedUID: 999,
		},
		{
			name:        "package with pipe user format",
			pkg:         "com.tencent.mm | user = 999",
			act:         "",
			user:        nil,
			expectedPkg: "com.tencent.mm",
			expectedAct: "",
			expectedUID: 999,
		},
		{
			name:        "explicit user takes precedence",
			pkg:         "com.tencent.mm --user 999",
			act:         "",
			user:        intPtr(0),
			expectedPkg: "com.tencent.mm",
			expectedAct: "",
			expectedUID: 0,
		},
		{
			name:        "separate package and activity with explicit user",
			pkg:         "com.tencent.mm",
			act:         "com.tencent.mm.ui.LauncherUI",
			user:        intPtr(10),
			expectedPkg: "com.tencent.mm",
			expectedAct: "com.tencent.mm.ui.LauncherUI",
			expectedUID: 10,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			gotPkg, gotAct, gotUID := normalizeLaunchTarget(tt.pkg, tt.act, tt.user)
			if gotPkg != tt.expectedPkg {
				t.Errorf("normalizeLaunchTarget() gotPkg = %v, want %v", gotPkg, tt.expectedPkg)
			}
			if gotAct != tt.expectedAct {
				t.Errorf("normalizeLaunchTarget() gotAct = %v, want %v", gotAct, tt.expectedAct)
			}
			if gotUID != tt.expectedUID {
				t.Errorf("normalizeLaunchTarget() gotUID = %v, want %v", gotUID, tt.expectedUID)
			}
		})
	}
}

func intPtr(i int) *int {
	return &i
}
