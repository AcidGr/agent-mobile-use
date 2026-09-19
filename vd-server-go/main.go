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

func getTargetDisplayID(st StatusResp) int {
	if getCurrentMode() == "foreground" {
		return 0
	}
	return st.DisplayID
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

func getSfDisplayID() string {
	out, err := exec.Command("/system/bin/dumpsys", "SurfaceFlinger", "--display-id").Output()
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

func runTool(args ...string) (string, error) {
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
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		did := strconv.Itoa(targetDid)
		out, err := runTool("tree", did)
		if err != nil {
			out, _ = runTool("dump", did)
		}
		trimmed := strings.TrimSpace(out)
		if strings.HasPrefix(trimmed, "[") && strings.HasSuffix(trimmed, "]") {
			w.Write([]byte(trimmed))
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: "Raw dump", Data: trimmed})
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
			X int `json:"x"`
			Y int `json:"y"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		did := strconv.Itoa(targetDid)
		cmd := exec.Command("/system/bin/input", "-d", did, "tap", strconv.Itoa(p.X), strconv.Itoa(p.Y))
		if err := cmd.Run(); err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true})
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
			Title   string `json:"title"`
			Content string `json:"content"`
			Tag     string `json:"tag"`
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
		// Post notification using su 2000 to assign to com.android.shell via environment variables
		cmd := exec.Command("/system/bin/su", "2000", "-c", `cmd notification post -S bigtext -t "$NOTIFY_TITLE" "$NOTIFY_TAG" "$NOTIFY_CONTENT"`)
		cmd.Env = append(os.Environ(),
			"NOTIFY_TITLE="+p.Title,
			"NOTIFY_TAG="+p.Tag,
			"NOTIFY_CONTENT="+p.Content,
		)
		out, err := cmd.CombinedOutput()
		if err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Data: string(out)})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: string(out)})
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
