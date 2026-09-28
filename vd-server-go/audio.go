package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
)

var (
	audioMu            sync.Mutex
	vdAudioMuteEnabled = true
	// mutedPackages tracks package -> userId for apps silenced on the virtual display
	mutedPackages = make(map[string]int)
)

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

	audioMu.Lock()
	if mute {
		mutedPackages[pkg] = userId
	} else {
		delete(mutedPackages, pkg)
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
	audioMu.Lock()
	toUnmute := make(map[string]int, len(mutedPackages))
	for k, v := range mutedPackages {
		toUnmute[k] = v
	}
	mutedPackages = make(map[string]int)
	audioMu.Unlock()

	for pkg, uId := range toUnmute {
		uStr := strconv.Itoa(uId)
		_ = exec.Command("/system/bin/cmd", "appops", "set", "--user", uStr, pkg, "PLAY_AUDIO", "allow").Run()
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
	vdPkgs := make(map[string]int)
	for _, s := range stacks {
		if s.DisplayID == vdDid {
			for _, pkg := range s.Packages {
				if strings.Contains(pkg, "launcher") || strings.Contains(pkg, "systemui") || pkg == "android" {
					continue
				}
				vdPkgs[pkg] = s.UserID
			}
		}
	}

	audioMu.Lock()
	currentMuted := make(map[string]int, len(mutedPackages))
	for k, v := range mutedPackages {
		currentMuted[k] = v
	}
	audioMu.Unlock()

	// 1. Mute apps now on VD
	for pkg, uId := range vdPkgs {
		if _, ok := currentMuted[pkg]; !ok {
			setPackageMuted(pkg, uId, true)
		}
	}

	// 2. Unmute apps that left VD
	for pkg, uId := range currentMuted {
		if _, ok := vdPkgs[pkg]; !ok {
			setPackageMuted(pkg, uId, false)
		}
	}
}

// initAudioGuard sets up fail-safe sweeps and signal handlers
func initAudioGuard() {
	// 1. Startup self-healing sweep to clear any previous crash remnants
	unmuteAllAudio()

	// 2. Trap process termination signals to guarantee clean audio restoration
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	go func() {
		sig := <-sigChan
		fmt.Printf("[audio] caught signal %v, restoring audio for all apps...\n", sig)
		unmuteAllAudio()
		os.Exit(0)
	}()
}
