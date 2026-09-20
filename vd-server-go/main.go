package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed index.html
var indexHTML []byte

const (
	statusFile = "/data/local/tmp/vd_status.json"
	stopSignal = "/data/local/tmp/vd_stop"
)

type StatusResp struct {
	Status          string `json:"status"`
	DisplayID       int    `json:"display_id"`
	PID             int    `json:"pid"`
	Width           int    `json:"width"`
	Height          int    `json:"height"`
	DPI             int    `json:"dpi"`
	Mode            string `json:"mode"`
	TargetDisplayID int    `json:"target_display_id"`
}

var (
	modeMu      sync.Mutex
	currentMode = "background" // "background" (default) or "foreground"
)

func getCurrentMode() string {
	modeMu.Lock()
	defer modeMu.Unlock()
	return currentMode
}

func setCurrentMode(m string) string {
	modeMu.Lock()
	defer modeMu.Unlock()
	lower := strings.ToLower(strings.TrimSpace(m))
	if lower == "foreground" || lower == "fg" || lower == "0" {
		currentMode = "foreground"
		go setEdgeGlow(true)
	} else {
		currentMode = "background"
		go setEdgeGlow(false)
	}
	return currentMode
}

func setEdgeGlow(enable bool) {
	if enable {
		exec.Command("/system/bin/sh", "-c", "am start-foreground-service -a START com.agent.mobileuse/.GlowService").Run()
	} else {
		exec.Command("/system/bin/sh", "-c", "am start-foreground-service -a STOP com.agent.mobileuse/.GlowService").Run()
	}
}

func broadcastTouch(touchType int, x, y, x1, y1, x2, y2, duration int) {
	if getCurrentMode() != "foreground" {
		return
	}
	if touchType == 1 {
		cmd := fmt.Sprintf("am broadcast -a com.agent.mobileuse.ACTION_TOUCH -p com.agent.mobileuse --ei type 1 --ei x %d --ei y %d", x, y)
		go exec.Command("/system/bin/sh", "-c", cmd).Run()
	} else if touchType == 2 {
		cmd := fmt.Sprintf("am broadcast -a com.agent.mobileuse.ACTION_TOUCH -p com.agent.mobileuse --ei type 2 --ei x1 %d --ei y1 %d --ei x2 %d --ei y2 %d --ei duration %d", x1, y1, x2, y2, duration)
		go exec.Command("/system/bin/sh", "-c", cmd).Run()
	}
}

func getTargetDisplayID(st StatusResp) int {
	if getCurrentMode() == "foreground" {
		return 0
	}
	return st.DisplayID
}

func handoffToBackground() map[string]interface{} {
	st := getStatus()
	vdDid := st.DisplayID
	if vdDid <= 0 {
		st = startVirtualDisplay()
		vdDid = st.DisplayID
	}

	// 1. 获取 Display 0 当前顶层的组件名并无缝平移至副屏
	out, _ := exec.Command("/system/bin/sh", "-c", `dumpsys activity activities | grep -A 5 "Display #0" | grep "topResumedActivity"`).Output()
	re := regexp.MustCompile(`u0\s+([a-zA-Z0-9._]+/[a-zA-Z0-9._]+)`)
	matches := re.FindStringSubmatch(string(out))
	migratedComponent := ""
	if len(matches) > 1 {
		comp := matches[1]
		if !strings.Contains(comp, "launcher") && !strings.Contains(comp, "systemui") {
			migratedComponent = comp
			if vdDid > 0 {
				_ = exec.Command("/system/bin/am", "start", "--display", strconv.Itoa(vdDid), "-n", comp).Run()
			}
		}
	}

	// 2. 主屏退回桌面
	_ = exec.Command("/system/bin/input", "-d", "0", "keyevent", "3").Run()

	// 3. 模式设置为 background，并熄灭光效
	setCurrentMode("background")

	return map[string]interface{}{
		"success":            true,
		"mode":               "background",
		"target_display_id":  vdDid,
		"migrated_component": migratedComponent,
		"message":            "Successfully handed off to background",
	}
}

func ensureTargetReady() (StatusResp, int, error) {
	st := getStatus()
	targetDid := getTargetDisplayID(st)
	if targetDid == 0 {
		return st, 0, nil
	}
	if st.Status != "running" {
		st = startVirtualDisplay()
		if st.Status != "running" {
			return st, -1, fmt.Errorf("Virtual display not running")
		}
	}
	return st, st.DisplayID, nil
}

func getStatus() StatusResp {
	resp := StatusResp{Status: "stopped", DisplayID: -1}
	data, err := os.ReadFile(statusFile)
	if err == nil {
		var parsed StatusResp
		if err := json.Unmarshal(data, &parsed); err == nil {
			resp = parsed
			if resp.Status == "running" && resp.PID > 0 {
				process, err := os.FindProcess(resp.PID)
				if err != nil || process.Signal(syscall.Signal(0)) != nil {
					resp.Status = "stopped"
					resp.DisplayID = -1
				} else {
					cmdline, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", resp.PID))
					if err != nil || !strings.Contains(string(cmdline), "DaemonMain") {
						resp.Status = "stopped"
						resp.DisplayID = -1
					}
				}
			}
		}
	}
	resp.Mode = getCurrentMode()
	resp.TargetDisplayID = getTargetDisplayID(resp)
	return resp
}

// displaySize resolves the pixel size of the display the agent is currently driving.
// For the virtual display the daemon already knows it; for the physical display it is
// parsed from `wm size`, which reports the override or physical resolution.
func displaySize(targetDid int, st StatusResp) (int, int) {
	if targetDid != 0 {
		if st.Width > 0 && st.Height > 0 {
			return st.Width, st.Height
		}
		return 0, 0
	}
	out, err := exec.Command("/system/bin/wm", "size").Output()
	if err != nil {
		return 0, 0
	}
	re := regexp.MustCompile(`([0-9]+)x([0-9]+)`)
	matches := re.FindAllStringSubmatch(string(out), -1)
	if len(matches) == 0 {
		return 0, 0
	}
	// When an override is active `wm size` prints "Override size: WxH" first; the last
	// match is the effective one either way.
	last := matches[len(matches)-1]
	w, _ := strconv.Atoi(last[1])
	h, _ := strconv.Atoi(last[2])
	return w, h
}

func getSfDisplayID() string {	out, err := exec.Command("/system/bin/dumpsys", "SurfaceFlinger", "--display-id").Output()
	if err != nil {
		return ""
	}
	re := regexp.MustCompile(`Display\s+([0-9]+).*Agent.*VirtualDisplay`)
	matches := re.FindStringSubmatch(string(out))
	if len(matches) > 1 {
		return matches[1]
	}
	return ""
}

func startVirtualDisplay() StatusResp {
	st := getStatus()
	if st.Status == "running" {
		return st
	}
	_ = os.Remove(stopSignal)

	runScript := "/data/adb/modules/agent_mobile_use/bin/run_daemon.sh"
	if _, err := os.Stat(runScript); err != nil {
		runScript = "/data/local/tmp/run_daemon.sh"
	}

	logFile, _ := os.OpenFile("/data/local/tmp/daemon.log", os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	cmd := exec.Command("/system/bin/sh", runScript)
	if logFile != nil {
		cmd.Stdout = logFile
		cmd.Stderr = logFile
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	_ = cmd.Start()

	for i := 0; i < 30; i++ {
		time.Sleep(100 * time.Millisecond)
		st = getStatus()
		if st.Status == "running" {
			return st
		}
	}
	return getStatus()
}

func stopVirtualDisplay() StatusResp {
	_ = os.WriteFile(stopSignal, []byte("1"), 0644)
	for i := 0; i < 20; i++ {
		time.Sleep(100 * time.Millisecond)
		st := getStatus()
		if st.Status == "stopped" {
			return st
		}
	}
	st := getStatus()
	if st.PID > 0 {
		proc, err := os.FindProcess(st.PID)
		if err == nil {
			_ = proc.Kill()
		}
	}
	_ = os.WriteFile(statusFile, []byte(`{"status":"stopped","display_id":-1}`), 0644)
	return getStatus()
}

// ── Accessibility service, switched on only for the duration of a tool call ──
//
// WeChat only exposes its node tree to a genuinely registered accessibility service;
// without one, `tree` on its chat list returns zero nodes (tree_blocked). The user does
// not want that service resident — it is a device-wide accessibility setting, and leaving
// it on changes how the whole phone behaves and costs battery.
//
// So it is enabled immediately before a tool call that reads the tree, and switched back
// to whatever it was before, right after.
//
// Measured timing on this device:
//   enable  -> takes effect about 1s later (at +0s the tree is still blocked, at +1s it reads)
//   disable -> asynchronous; the tree stays readable for ~2s after the value flips
// which is why a single fixed wait is needed on the enable side and none on the disable side.
const selectToSpeakService = "com.google.android.marvin.talkback/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

const a11yEnableWait = 1000 * time.Millisecond

// Serialises the toggle so two concurrent tool calls cannot interleave their
// save/restore and leave the setting stuck on.
var a11yToggleMu sync.Mutex

func readSecure(key string) string {
	out, err := exec.Command("/system/bin/settings", "get", "secure", key).Output()
	if err != nil {
		return ""
	}
	v := strings.TrimSpace(string(out))
	if v == "null" {
		return ""
	}
	return v
}

func writeSecure(key, value string) {
	_ = exec.Command("/system/bin/settings", "put", "secure", key, value).Run()
}

// toolNeedsA11yService reports whether a tool command reads the accessibility tree.
// `type` does not, so it is left alone rather than paying the switch cost.
func toolNeedsA11yService(args []string) bool {
	if len(args) == 0 {
		return false
	}
	switch args[0] {
	case "tree", "dump", "tapnode", "tapgesture", "tapfocus", "clicknode":
		return true
	}
	return false
}

// withA11yService runs fn with the accessibility service temporarily enabled and then
// restores the device's accessibility settings exactly as they were.
//
// It APPENDS to whatever services the user already had rather than replacing them, so a
// user running their own screen reader does not lose it for the duration of a dump.
func withA11yService(fn func() (string, error)) (string, error) {
	a11yToggleMu.Lock()
	defer a11yToggleMu.Unlock()

	origEnabled := readSecure("accessibility_enabled")
	origServices := readSecure("enabled_accessibility_services")

	// Already on: toggling would cost a second and change nothing.
	if origEnabled == "1" && strings.Contains(origServices, "SelectToSpeakService") {
		return fn()
	}

	merged := selectToSpeakService
	if origServices != "" && !strings.Contains(origServices, selectToSpeakService) {
		merged = origServices + ":" + selectToSpeakService
	}
	writeSecure("enabled_accessibility_services", merged)
	writeSecure("accessibility_enabled", "1")
	time.Sleep(a11yEnableWait)

	out, err := fn()

	// Put it back. An unset accessibility_enabled restores as "0" rather than "" so the
	// setting is left in a defined state.
	writeSecure("enabled_accessibility_services", origServices)
	if origEnabled == "" {
		writeSecure("accessibility_enabled", "0")
	} else {
		writeSecure("accessibility_enabled", origEnabled)
	}
	return out, err
}

func runTool(args ...string) (string, error) {
	if toolNeedsA11yService(args) {
		return withA11yService(func() (string, error) { return runToolRaw(args...) })
	}
	return runToolRaw(args...)
}

func runToolRaw(args ...string) (string, error) {
	dexPath := "/data/adb/modules/agent_mobile_use/bin/agent_tools.dex"
	if _, err := os.Stat(dexPath); err != nil {
		dexPath = "/data/local/tmp/agent_tools.dex"
	}
	cmdArgs := append([]string{"/system/bin", "com.agent.ToolMain"}, args...)
	cmd := exec.Command("/system/bin/app_process", cmdArgs...)
	cmd.Env = append(os.Environ(),
		"ANDROID_ROOT=/system",
		"ANDROID_DATA=/data",
		"ANDROID_ART_ROOT=/apex/com.android.art",
		"ANDROID_I18N_ROOT=/apex/com.android.i18n",
		"ANDROID_TZDATA_ROOT=/apex/com.android.tzdata",
		"BOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/framework-ondeviceintelligence-platform.jar:/system/framework/framework-nfc.jar:/system/framework/tcmiface.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/UxPerformance.jar:/system/framework/WfdCommon.jar:/system/framework/oplus-framework.jar:/system/framework/subsystem-framework.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.adservices/javalib/framework-adservices.jar:/apex/com.android.adservices/javalib/framework-sdksandbox.jar:/apex/com.android.appsearch/javalib/framework-appsearch.jar:/apex/com.android.configinfrastructure/javalib/framework-configinfrastructure.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar:/apex/com.android.crashrecovery/javalib/framework-crashrecovery.jar:/apex/com.android.devicelock/javalib/framework-devicelock.jar:/apex/com.android.healthfitness/javalib/framework-healthfitness.jar:/apex/com.android.ipsec/javalib/android.net.ipsec.ike.jar:/apex/com.android.media/javalib/updatable-media.jar:/apex/com.android.mediaprovider/javalib/framework-mediaprovider.jar:/apex/com.android.mediaprovider/javalib/framework-pdf.jar:/apex/com.android.mediaprovider/javalib/framework-pdf-v.jar:/apex/com.android.mediaprovider/javalib/framework-photopicker.jar:/apex/com.android.ondevicepersonalization/javalib/framework-ondevicepersonalization.jar:/apex/com.android.os.statsd/javalib/framework-statsd.jar:/apex/com.android.permission/javalib/framework-permission.jar:/apex/com.android.permission/javalib/framework-permission-s.jar:/apex/com.android.profiling/javalib/framework-profiling.jar:/apex/com.android.scheduling/javalib/framework-scheduling.jar:/apex/com.android.sdkext/javalib/framework-sdkextensions.jar:/apex/com.android.tethering/javalib/framework-connectivity.jar:/apex/com.android.tethering/javalib/framework-connectivity-b.jar:/apex/com.android.tethering/javalib/framework-connectivity-t.jar:/apex/com.android.tethering/javalib/framework-tethering.jar:/apex/com.android.uwb/javalib/framework-ranging.jar:/apex/com.android.uwb/javalib/framework-uwb.jar:/apex/com.android.virt/javalib/framework-virtualization.jar:/apex/com.android.wifi/javalib/framework-wifi.jar",
		"DEX2OATBOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/framework-ondeviceintelligence-platform.jar:/system/framework/framework-nfc.jar:/system/framework/tcmiface.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/UxPerformance.jar:/system/framework/WfdCommon.jar:/system/framework/oplus-framework.jar:/system/framework/subsystem-framework.jar:/apex/com.android.i18n/javalib/core-icu4j.jar",
		"CLASSPATH="+dexPath,
	)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

// a11yTap asks the tool to click the actionable node at (x, y) using only
// AccessibilityNodeInfo.performAction, which injects no touch event. It returns the raw
// JSON the tool printed, or nil when the tool could not be run at all.
//
// The distinction matters to the caller: nil means "I could not even ask", whereas a
// parsed {ok:false} means "I asked and there is nothing actionable there". The /api/click
// handler treats both as a reason to consider a coordinate fallback, but only the second
// one carries a reason worth reporting back.
//
// The tool never falls back to injecting a touch itself, so `ok:false` is a real answer
// rather than a silent substitution.
func a11yTap(w http.ResponseWriter, targetDisplayID, x, y int) []byte {
	out, err := runTool("tapnode", strconv.Itoa(targetDisplayID),
		strconv.Itoa(x), strconv.Itoa(y))
	if err != nil && strings.TrimSpace(out) == "" {
		return nil
	}
	s := strings.TrimSpace(out)
	start := strings.Index(s, "{")
	end := strings.LastIndex(s, "}")
	if start < 0 || end <= start {
		return nil
	}
	return []byte(s[start : end+1])
}

func parseKeycode(key string) string {
	k := strings.ToUpper(strings.TrimSpace(key))
	switch k {
	case "BACK":
		return "4"
	case "HOME":
		return "3"
	case "ENTER":
		return "66"
	case "TAB":
		return "61"
	case "SPACE":
		return "62"
	case "DEL", "DELETE", "BACKSPACE":
		return "67"
	case "APP_SWITCH", "RECENTS":
		return "187"
	case "PASTE":
		return "279"
	default:
		return key
	}
}

type ActionResponse struct {
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
	Data    any    `json:"data,omitempty"`
}

func main() {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(indexHTML)
	})

	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(getStatus())
	})

	mux.HandleFunc("/api/start", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(startVirtualDisplay())
	})

	mux.HandleFunc("/api/stop", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(stopVirtualDisplay())
	})

	mux.HandleFunc("/api/mode", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == http.MethodPost {
			var p struct {
				Mode string `json:"mode"`
			}
			if err := json.NewDecoder(r.Body).Decode(&p); err == nil && p.Mode != "" {
				setCurrentMode(p.Mode)
			}
		}
		st := getStatus()
		targetDid := getTargetDisplayID(st)
		mode := getCurrentMode()
		json.NewEncoder(w).Encode(map[string]interface{}{
			"success":           true,
			"mode":              mode,
			"target_display_id": targetDid,
			"message":           fmt.Sprintf("Current mode is %s (Target Display %d)", mode, targetDid),
		})
	})

	mux.HandleFunc("/api/handoff", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		res := handoffToBackground()
		json.NewEncoder(w).Encode(res)
	})

	mux.HandleFunc("/api/screenshot", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		var out []byte
		if targetDid == 0 {
			out, err = exec.Command("/system/bin/screencap", "-p").Output()
		} else {
			sfID := getSfDisplayID()
			if sfID != "" {
				out, err = exec.Command("/system/bin/screencap", "-d", sfID, "-p").Output()
			} else {
				out, err = exec.Command("/system/bin/screencap", "-d", strconv.Itoa(targetDid), "-p").Output()
			}
		}

		if err != nil || len(out) == 0 {
			http.Error(w, "Capture error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-store, must-revalidate")
		w.Header().Set("Content-Length", strconv.Itoa(len(out)))
		w.Write(out)
	})

	mux.HandleFunc("/api/dump_ui", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		st, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		did := strconv.Itoa(targetDid)
		// Optional vertical paging window (?y_min=&y_max=, both half-open in layout
		// coordinates). Lets the caller fetch the part of a long screen that the
		// budget could not fit, instead of guessing what is down there.
		args := []string{"tree", did}
		if yMin := r.URL.Query().Get("y_min"); yMin != "" {
			if _, convErr := strconv.Atoi(yMin); convErr != nil {
				w.WriteHeader(http.StatusBadRequest)
				json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "y_min must be an integer"})
				return
			}
			args = append(args, yMin)
			yMax := r.URL.Query().Get("y_max")
			if _, convErr := strconv.Atoi(yMax); yMax != "" && convErr != nil {
				w.WriteHeader(http.StatusBadRequest)
				json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "y_max must be an integer"})
				return
			}
			args = append(args, yMax)
		}
		out, err := runTool(args...)
		trimmed := strings.TrimSpace(out)
		if err != nil || trimmed == "" {
			json.NewEncoder(w).Encode(ActionResponse{
				Success: false,
				Message: "UI dump failed: the accessibility tree could not be read for this display",
				Data:    trimmed,
			})
			return
		}

		// ToolMain emits a JSON envelope. Decode, decorate, re-encode: this adds the
		// geometry and mode that only the daemon knows, so every observation reaches the
		// model together with the coordinate space it is expressed in.
		var env map[string]interface{}
		if jsonErr := json.Unmarshal([]byte(trimmed), &env); jsonErr != nil {
			json.NewEncoder(w).Encode(ActionResponse{
				Success: false,
				Message: "UI dump returned malformed JSON",
				Data:    trimmed,
			})
			return
		}

		env["mode"] = getCurrentMode()
		env["target_display_id"] = targetDid
		if wv, ok := env["width"].(float64); !ok || int(wv) <= 0 {
			dw, dh := displaySize(targetDid, st)
			env["width"] = dw
			env["height"] = dh
		}
		json.NewEncoder(w).Encode(env)
	})

	mux.HandleFunc("/api/click", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			X   int    `json:"x"`
			Y   int    `json:"y"`
			Via string `json:"via"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		did := strconv.Itoa(targetDid)

		// Two delivery channels, both explicit. There is deliberately NO automatic
		// fallback between them.
		//
		// WHY: `input tap` injects a real touch event into InputDispatcher. Measured on this
		// device, an injected touch issued while the user's finger was on the physical screen
		// terminated the user's gesture with ACTION_CANCEL. performAction() calls the View's
		// click handler directly and produces no InputEvent, so it cannot enter touch
		// arbitration.
		//
		// CORRECTION — this replaces an earlier claim made in this file. performAction is NOT
		// harmless to the user's foreground session: measured with 25Hz sampling, BOTH
		// channels move the input method's token to this display (dumpsys input_method
		// mCurTokenDisplayId 0 -> 3), which collapses the user's soft keyboard. What actually
		// separates them is narrower than "disturbs / does not disturb": only the coordinate
		// tap ALSO cancels an in-flight user gesture.
		//
		// WHY no fallback between the two: each channel is one real click. Chaining "try
		// accessibility, then try a coordinate tap" means the second attempt fires blind at
		// whatever the first attempt left on screen. The tool cannot tell whether the first
		// attempt landed, so the caller must decide — it can dump the screen and see.
		//
		// `via`:
		//   a11y  (default) - AccessibilityNodeInfo.performAction only.
		//   coord           - an injected `input tap`; the caller explicitly wants a real
		//                     touch event and accepts that it can cancel their gesture.
		mode := strings.ToLower(strings.TrimSpace(p.Via))
		if mode == "" {
			mode = "a11y"
		}
		if mode != "a11y" && mode != "coord" {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{
				Success: false,
				Message: "unknown via " + strconv.Quote(p.Via) + ": use \"a11y\" or \"coord\"",
			})
			return
		}

		if mode == "a11y" {
			a := a11yTap(w, targetDid, p.X, p.Y)
			if a == nil {
				json.NewEncoder(w).Encode(map[string]interface{}{
					"success":     false,
					"via":         "a11y",
					"error":       "tool_unavailable",
					"side_effect": false,
					"hint":        "could not run the accessibility tool at all; nothing was clicked",
				})
				return
			}
			var res struct {
				OK    bool   `json:"ok"`
				Error string `json:"error"`
				Type  string `json:"type"`
				Desc  string `json:"desc"`
				Text  string `json:"text"`
				Vid   string `json:"vid"`
				B     []int  `json:"b"`
			}
			if json.Unmarshal(a, &res) != nil {
				json.NewEncoder(w).Encode(map[string]interface{}{
					"success":     false,
					"via":         "a11y",
					"error":       "unparseable_tool_output",
					"side_effect": false,
					"hint":        strings.TrimSpace(string(a)),
				})
				return
			}
			if res.OK {
				label := res.Desc
				if label == "" {
					label = res.Text
				}
				// type/bounds are reported even when label is empty, because an empty label
				// is NOT a miss: nodes that carry no text (layout containers) are the common
				// case in apps like Meituan, and without type/bounds the caller has no way to
				// tell "clicked a textless container" from "clicked nothing".
				json.NewEncoder(w).Encode(map[string]interface{}{
					"success": true,
					"via":     "a11y",
					"label":   label,
					"vid":     res.Vid,
					"type":    res.Type,
					"bounds":  res.B,
					"detail": "clicked via performAction; no touch event was injected, so this " +
						"cannot cancel the user's own gesture. It does still move the input " +
						"method's token to this display. An empty label alongside a type and " +
						"bounds means the target carries no text: that is a hit, not a miss",
				})
				return
			}
			// The two failure reasons have opposite consequences, so they must never be
			// collapsed into one "failed" answer:
			//   no_actionable_node_at_point       - performAction was never called, so the
			//                                       screen is untouched and retrying with
			//                                       via=coord is safe.
			//   performAction(...) returned false - an action WAS dispatched and refused.
			//                                       State is unknown; dump before retrying.
			sideEffect := res.Error != "no_actionable_node_at_point"
			hint := "nothing was clicked and the screen is unchanged; retrying with via=coord " +
				"is safe if an injected touch is what you want"
			if sideEffect {
				hint = "an action was dispatched and refused; do NOT retry blindly — dump the " +
					"screen first to see what state it is in"
			}
			json.NewEncoder(w).Encode(map[string]interface{}{
				"success":     false,
				"via":         "a11y",
				"error":       res.Error,
				"side_effect": sideEffect,
				"hint":        hint,
			})
			return
		}

		if targetDid == 0 {
			broadcastTouch(1, p.X, p.Y, 0, 0, 0, 0, 0)
		}
		cmd := exec.Command("/system/bin/input", "-d", did, "tap", strconv.Itoa(p.X), strconv.Itoa(p.Y))
		if err := cmd.Run(); err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		// Reached only when the caller asked for via=coord explicitly — there is no path
		// here that arrived by falling back from an accessibility failure.
		json.NewEncoder(w).Encode(map[string]interface{}{
			"success":     true,
			"via":         "coord",
			"side_effect": true,
			"detail": "injected a coordinate tap (via=coord): this is a real touch event and can " +
				"interrupt the user's own gesture or collapse the soft keyboard",
		})
	})

	mux.HandleFunc("/api/swipe", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			X1       int `json:"x1"`
			Y1       int `json:"y1"`
			X2       int `json:"x2"`
			Y2       int `json:"y2"`
			Duration int `json:"duration"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		if p.Duration <= 0 {
			p.Duration = 300
		}
		did := strconv.Itoa(targetDid)
		if targetDid == 0 {
			broadcastTouch(2, 0, 0, p.X1, p.Y1, p.X2, p.Y2, p.Duration)
		}
		cmd := exec.Command("/system/bin/input", "-d", did, "swipe",
			strconv.Itoa(p.X1), strconv.Itoa(p.Y1), strconv.Itoa(p.X2), strconv.Itoa(p.Y2), strconv.Itoa(p.Duration))
		if err := cmd.Run(); err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true})
	})

	mux.HandleFunc("/api/type", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			Text string `json:"text"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		did := strconv.Itoa(targetDid)
		out, err := runTool("type", did, p.Text)
		if err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Data: out})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: out})
	})

	mux.HandleFunc("/api/key", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			Key string `json:"key"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		did := strconv.Itoa(targetDid)
		kc := parseKeycode(p.Key)
		cmd := exec.Command("/system/bin/input", "-d", did, "keyevent", kc)
		if err := cmd.Run(); err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true})
	})

	mux.HandleFunc("/api/launch", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			Package  string `json:"package"`
			Activity string `json:"activity"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		did := strconv.Itoa(targetDid)
		args := []string{"start"}
		if targetDid != 0 {
			args = append(args, "--display", did)
		}
		if p.Activity != "" {
			args = append(args, "-n", p.Package+"/"+p.Activity)
		} else {
			actBytes, _ := exec.Command("/system/bin/cmd", "package", "resolve-activity", "--brief", p.Package).Output()
			actLines := strings.Split(strings.TrimSpace(string(actBytes)), "\n")
			targetAct := ""
			if len(actLines) > 0 && !strings.Contains(actLines[len(actLines)-1], "No activity found") {
				targetAct = actLines[len(actLines)-1]
			}
			if targetAct != "" {
				args = append(args, "-n", targetAct)
			} else {
				args = append(args, p.Package)
			}
		}
		cmd := exec.Command("/system/bin/am", args...)
		out, err := cmd.CombinedOutput()
		if err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Data: string(out)})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: string(out)})
	})

	mux.HandleFunc("/api/notify", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		var p struct {
			Title     string `json:"title"`
			Content   string `json:"content"`
			Tag       string `json:"tag"`
			URL       string `json:"url"`
			Total     int    `json:"total"`
			Completed int    `json:"completed"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		if p.Tag == "" {
			p.Tag = "dsh_agent"
		}
		if p.Title == "" {
			p.Title = "Mobile Agent 任务状态"
		}
		if p.URL == "" {
			p.URL = "http://127.0.0.1:3080"
		}

		// Primary: Send broadcast to com.agent.mobileuse/.NotifyReceiver (native Android App notification with click jump & black whale avatar)
		// Ensure process is thawed if frozen by ColorOS Hans/Freezer, and pass --receiver-foreground for immediate dispatch
		exec.Command("/system/bin/sh", "-c", "echo 0 > /sys/fs/cgroup/apps/uid_10044/cgroup.freeze 2>/dev/null").Run()
		cmd := exec.Command("/system/bin/sh", "-c", `/system/bin/am broadcast --receiver-foreground -n com.agent.mobileuse/.NotifyReceiver -a com.agent.mobileuse.ACTION_NOTIFY --es title "$NOTIFY_TITLE" --es tag "$NOTIFY_TAG" --es content "$NOTIFY_CONTENT" --es url "$NOTIFY_URL" --ei total "$NOTIFY_TOTAL" --ei completed "$NOTIFY_COMPLETED"`)
		cmd.Env = append(os.Environ(),
			"NOTIFY_TITLE="+p.Title,
			"NOTIFY_TAG="+p.Tag,
			"NOTIFY_CONTENT="+p.Content,
			"NOTIFY_URL="+p.URL,
			fmt.Sprintf("NOTIFY_TOTAL=%d", p.Total),
			fmt.Sprintf("NOTIFY_COMPLETED=%d", p.Completed),
		)
		out, err := cmd.CombinedOutput()
		if err == nil && strings.Contains(string(out), "result=0") {
			json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: string(out)})
			return
		}

		// Fallback: Use cmd notification post if APK broadcast fails
		flags := "-S bigtext"
		if _, statErr := os.Stat("/data/local/tmp/dsh_whale_icon.png"); statErr == nil {
			flags += " -i file:///data/local/tmp/dsh_whale_icon.png"
		}
		if _, statErr := os.Stat("/data/local/tmp/dsh_whale_avatar.png"); statErr == nil {
			flags += " -I file:///data/local/tmp/dsh_whale_avatar.png"
		}

		fallbackCmd := exec.Command("/system/bin/su", "2000", "-c", `cmd notification post `+flags+` -t "$NOTIFY_TITLE" "$NOTIFY_TAG" "$NOTIFY_CONTENT"`)
		fallbackCmd.Env = append(os.Environ(),
			"NOTIFY_TITLE="+p.Title,
			"NOTIFY_TAG="+p.Tag,
			"NOTIFY_CONTENT="+p.Content,
		)
		fallbackOut, fallbackErr := fallbackCmd.CombinedOutput()
		if fallbackErr != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: fallbackErr.Error(), Data: string(fallbackOut)})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: string(fallbackOut)})
	})

	mux.HandleFunc("/api/shell", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		var p struct {
			Command string `json:"command"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		cmd := exec.Command("/system/bin/sh", "-c", p.Command)
		out, err := cmd.CombinedOutput()
		exitCode := 0
		if err != nil {
			if exitErr, ok := err.(*exec.ExitError); ok {
				exitCode = exitErr.ExitCode()
			} else {
				exitCode = 1
			}
		}
		json.NewEncoder(w).Encode(map[string]any{
			"output":    string(out),
			"exit_code": exitCode,
			"success":   exitCode == 0,
		})
	})

	port := "3070"
	if p := os.Getenv("PORT"); p != "" {
		port = p
	}
	fmt.Printf("[AgentVD-Web-Go] Listening on 0.0.0.0:%s\n", port)
	if err := http.ListenAndServe("0.0.0.0:"+port, mux); err != nil {
		fmt.Fprintf(os.Stderr, "Server error: %v\n", err)
	}
}
