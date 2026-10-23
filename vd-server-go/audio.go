package main

import (
	"fmt"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
)

type mutedAppKey struct {
	Pkg    string
	UserID int
}

func formatMutedAppKey(k mutedAppKey) string {
	if k.UserID == 0 {
		return k.Pkg
	}
	return fmt.Sprintf("%s (user %d)", k.Pkg, k.UserID)
}

var (
	// audioOpMu serializes shell-level `cmd appops` mutations so an in-flight mute
	// during app launch cannot interleave after unmuteAllAudio() on stop/idle.
	audioOpMu          sync.Mutex
	audioMu            sync.Mutex
	vdAudioMuteEnabled = true
	// mutedPackages tracks (package, userId) pairs silenced on the virtual display
	mutedPackages = make(map[mutedAppKey]struct{})
)

func listMutedApps() []string {
	audioMu.Lock()
	list := make([]string, 0, len(mutedPackages))
	for k := range mutedPackages {
		list = append(list, formatMutedAppKey(k))
	}
	audioMu.Unlock()
	sort.Strings(list)
	return list
}

func isAudioMuteEnabled() bool {
	audioMu.Lock()
	defer audioMu.Unlock()
	return vdAudioMuteEnabled
}

func setAudioMuteEnabled(enabled bool) {
	audioMu.Lock()
	vdAudioMuteEnabled = enabled
	audioMu.Unlock()
	if !enabled {
		unmuteAllAudio()
	} else {
		// ponytail: immediate check on active VD; routine sweep will maintain it
		st := getStatus()
		if st.DisplayID > 0 && getCurrentMode() == "background" {
			reconcileVdAudio(st.DisplayID)
		}
	}
}

func setPackageMuted(pkg string, userId int, mute bool) {
	if pkg == "" || strings.Contains(pkg, "launcher") || strings.Contains(pkg, "systemui") || pkg == "android" {
		return
	}

	audioOpMu.Lock()
	defer audioOpMu.Unlock()

	if mute {
		st := getStatus()
		if !isAudioMuteEnabled() || getCurrentMode() != "background" || st.Status != "running" || st.DisplayID <= 0 {
			return
		}
	}

	mode := "allow"
	if mute {
		mode = "ignore"
	}

	uStr := strconv.Itoa(userId)
	cmd := exec.Command("/system/bin/cmd", "appops", "set", "--user", uStr, pkg, "PLAY_AUDIO", mode)
	if err := cmd.Run(); err != nil {
		fmt.Printf("[audio] failed to set %s (user %d) PLAY_AUDIO %s: %v\n", pkg, userId, mode, err)
		return
	}
	fmt.Printf("[audio] %s (user %d) PLAY_AUDIO -> %s\n", pkg, userId, mode)

	key := mutedAppKey{Pkg: pkg, UserID: userId}
	audioMu.Lock()
	if mute {
		mutedPackages[key] = struct{}{}
	} else {
		delete(mutedPackages, key)
	}
	audioMu.Unlock()
}

func parseUserIDsFromOutput(out string) []int {
	re := regexp.MustCompile(`UserInfo\{(\d+):`)
	matches := re.FindAllStringSubmatch(out, -1)
	var uids []int
	for _, m := range matches {
		if len(m) > 1 {
			if id, err := strconv.Atoi(m[1]); err == nil {
				uids = append(uids, id)
			}
		}
	}
	if len(uids) == 0 {
		return []int{0}
	}
	return uids
}

func parseUserIDs() []int {
	out, err := exec.Command("/system/bin/pm", "list", "users").Output()
	if err != nil {
		return []int{0, 999}
	}
	return parseUserIDsFromOutput(string(out))
}

// unmuteAllAudio restores all muted packages and sweeps system AppOps to prevent stuck silence
func unmuteAllAudio() {
	audioOpMu.Lock()
	defer audioOpMu.Unlock()

	audioMu.Lock()
	toUnmute := make([]mutedAppKey, 0, len(mutedPackages))
	for k := range mutedPackages {
		toUnmute = append(toUnmute, k)
	}
	mutedPackages = make(map[mutedAppKey]struct{})
	audioMu.Unlock()

	for _, k := range toUnmute {
		uStr := strconv.Itoa(k.UserID)
		_ = exec.Command("/system/bin/cmd", "appops", "set", "--user", uStr, k.Pkg, "PLAY_AUDIO", "allow").Run()
	}

	// Defensive sweep: scan all users for any orphaned PLAY_AUDIO ignore
	users := parseUserIDs()
	for _, uId := range users {
		uStr := strconv.Itoa(uId)
		out, err := exec.Command("/system/bin/cmd", "appops", "query-op", "--user", uStr, "PLAY_AUDIO", "ignore").Output()
		if err == nil && len(out) > 0 {
			for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
				pkg := strings.TrimSpace(line)
				if pkg != "" {
					fmt.Printf("[audio_sweep] restoring orphaned muted package: %s (user %d)\n", pkg, uId)
					_ = exec.Command("/system/bin/cmd", "appops", "set", "--user", uStr, pkg, "PLAY_AUDIO", "allow").Run()
				}
			}
		}
	}
}

// reconcileVdAudio synchronizes package audio states with current VD tasks
func reconcileVdAudio(vdDid int) {
	if !isAudioMuteEnabled() || vdDid <= 0 || getCurrentMode() != "background" {
		audioMu.Lock()
		needsUnmute := len(mutedPackages) > 0
		audioMu.Unlock()
		if needsUnmute {
			unmuteAllAudio()
		}
		return
	}

	stacks := getStackList()
	vdPkgs := make(map[mutedAppKey]struct{})
	for _, s := range stacks {
		if s.DisplayID == vdDid {
			for _, pkg := range s.Packages {
				if strings.Contains(pkg, "launcher") || strings.Contains(pkg, "systemui") || pkg == "android" {
					continue
				}
				vdPkgs[mutedAppKey{Pkg: pkg, UserID: s.UserID}] = struct{}{}
			}
		}
	}

	audioMu.Lock()
	currentMuted := make(map[mutedAppKey]struct{}, len(mutedPackages))
	for k := range mutedPackages {
		currentMuted[k] = struct{}{}
	}
	audioMu.Unlock()

	// 1. Mute apps now on VD
	for k := range vdPkgs {
		if _, ok := currentMuted[k]; !ok {
			setPackageMuted(k.Pkg, k.UserID, true)
		}
	}

	// 2. Unmute apps that left VD
	for k := range currentMuted {
		if _, ok := vdPkgs[k]; !ok {
			setPackageMuted(k.Pkg, k.UserID, false)
		}
	}
}

// initAudioGuard performs a startup self-healing sweep to clear any previous crash remnants.
// Process termination cleanup is coordinated in main()'s unified signal handler.
func initAudioGuard() {
	unmuteAllAudio()
}
