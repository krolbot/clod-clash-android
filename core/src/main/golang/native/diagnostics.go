package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	chisel "github.com/jpillora/chisel/client"
	"github.com/metacubex/mihomo/log"
)

const (
	diagnosticsReverseAddress = "0.0.0.0:9091"
	diagnosticsTargetAddress  = "127.0.0.1:9090"
	diagnosticsProbePath      = "/controller/version"
	diagnosticsRemote         = "R:" + diagnosticsReverseAddress + ":" + diagnosticsTargetAddress
)

// These values are intentionally empty in source builds. CI may temporarily add
// diagnostics_credentials_generated.go, whose init assigns it. It must never
// be read from preferences, files, or the network.
var diagnosticsFingerprint string

type diagnosticsStatus struct {
	Available bool `json:"available"`
	Running   bool `json:"running"`
}

var diagnostics = struct {
	sync.Mutex
	client  *chisel.Client
	running bool
}{}

func diagnosticsLog(warning bool, message string) {
	if warning {
		log.Warnln("[APP] [Diagnostics] %s", message)
	} else {
		log.Infoln("[APP] [Diagnostics] %s", message)
	}
}

func diagnosticsChiselEvent(event chisel.Event) {
	context := "Chisel"
	switch event.Stage {
	case chisel.EventStageConnect:
		context = "Подключение"
	case chisel.EventStageDNS:
		context = "DNS"
	case chisel.EventStageNetwork:
		context = "Сеть"
	case chisel.EventStageWebSocket:
		context = "WebSocket"
	case chisel.EventStageSSH:
		context = "SSH"
	case chisel.EventStageFingerprint:
		context = "Проверка fingerprint"
	case chisel.EventStageAuth:
		context = "Авторизация"
	case chisel.EventStageReverseConfig:
		context = fmt.Sprintf("Reverse listen=%s target=%s", diagnosticsReverseAddress, diagnosticsTargetAddress)
	case chisel.EventStageTunnel:
		context = fmt.Sprintf("Туннель listen=%s target=%s", diagnosticsReverseAddress, diagnosticsTargetAddress)
	case chisel.EventStageRetry:
		context = "Повтор"
	}

	message := fmt.Sprintf("%s; stage=%s result=%s", context, event.Stage, event.Result)
	if event.Attempt > 0 {
		message += fmt.Sprintf(" attempt=%d", event.Attempt)
	}
	if event.HTTPStatus > 0 {
		message += fmt.Sprintf(" http=%d", event.HTTPStatus)
	}
	diagnosticsLog(event.Result == chisel.EventResultFailed || event.Result == chisel.EventResultDisconnected, message)
}

func normalizeDiagnosticsEndpoint(raw string) (string, bool) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || !strings.EqualFold(parsed.Scheme, "https") || parsed.Hostname() == "" || parsed.User != nil ||
		(parsed.Path != "" && parsed.Path != "/") || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", false
	}

	host := strings.ToLower(parsed.Hostname())
	if port := parsed.Port(); port != "" {
		portNumber, err := strconv.Atoi(port)
		if err != nil || portNumber < 1 || portNumber > 65535 {
			return "", false
		}
		host = net.JoinHostPort(host, port)
	} else if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	return "https://" + host, true
}

func diagnosticsConfiguration(endpoint, auth string) (chisel.Config, bool) {
	server, validEndpoint := normalizeDiagnosticsEndpoint(endpoint)
	if !validEndpoint || strings.TrimSpace(diagnosticsFingerprint) == "" || strings.TrimSpace(auth) == "" {
		return chisel.Config{}, false
	}

	return chisel.Config{
		Server:        server,
		Fingerprint:   diagnosticsFingerprint,
		Auth:          auth,
		Remotes:       []string{diagnosticsRemote},
		MaxRetryCount: -1,
		EventHandler:  diagnosticsChiselEvent,
		DisableLogger: true,
		TLS: chisel.TLSConfig{
			SkipVerify: false,
		},
	}, true
}

func diagnosticsStart(endpoint, auth string) {
	config, available := diagnosticsConfiguration(endpoint, auth)
	if !available {
		diagnosticsLog(true, "Запуск отклонён: конфигурация недоступна")
		return
	}

	diagnostics.Lock()
	if diagnostics.client != nil {
		diagnostics.Unlock()
		return
	}

	client, err := chisel.NewClient(&config)
	if err != nil {
		diagnostics.Unlock()
		diagnosticsLog(true, "Не удалось создать клиент")
		return
	}
	if err = client.Start(context.Background()); err != nil {
		_ = client.Close()
		diagnostics.Unlock()
		diagnosticsLog(true, "Не удалось запустить клиент")
		return
	}

	diagnostics.client = client
	diagnostics.running = false
	diagnostics.Unlock()
	diagnosticsLog(false, fmt.Sprintf("Канал запущен: reverse=%s target=%s probe=%s", diagnosticsReverseAddress, diagnosticsTargetAddress, diagnosticsProbePath))

	go diagnosticsProbeLoop(client, config.Server+diagnosticsProbePath)

	go func() {
		client.Wait()

		diagnostics.Lock()
		if diagnostics.client != client {
			diagnostics.Unlock()
			return
		}
		diagnostics.client = nil
		diagnostics.running = false
		diagnostics.Unlock()
		diagnosticsLog(true, "Канал остановлен")
	}()
}

func diagnosticsProbeLoop(client *chisel.Client, endpoint string) {
	httpClient := &http.Client{Timeout: 5 * time.Second}
	for {
		ready := diagnosticsControllerReady(httpClient, endpoint)

		diagnostics.Lock()
		if diagnostics.client != client {
			diagnostics.Unlock()
			return
		}
		wasReady := diagnostics.running
		diagnostics.running = ready
		diagnostics.Unlock()

		if ready != wasReady {
			if ready {
				diagnosticsLog(false, "Контроллер подключён")
			} else {
				diagnosticsLog(true, "Связь с контроллером потеряна")
			}
		}

		time.Sleep(time.Second)
	}
}

func diagnosticsControllerReady(client *http.Client, endpoint string) bool {
	response, err := client.Get(endpoint)
	if err != nil {
		return false
	}
	defer response.Body.Close()

	payload, err := io.ReadAll(io.LimitReader(response.Body, 64*1024))
	return err == nil && response.StatusCode == http.StatusOK && json.Valid(payload)
}

func diagnosticsStop() {
	diagnostics.Lock()
	client := diagnostics.client
	diagnostics.client = nil
	diagnostics.running = false
	diagnostics.Unlock()

	if client != nil {
		_ = client.Close()
		diagnosticsLog(false, "Канал выключен")
	}
}

func diagnosticsQuery() string {
	diagnostics.Lock()
	status := diagnosticsStatus{
		Available: strings.TrimSpace(diagnosticsFingerprint) != "",
		Running:   diagnostics.running,
	}
	diagnostics.Unlock()

	payload, _ := json.Marshal(status)
	return string(payload)
}
