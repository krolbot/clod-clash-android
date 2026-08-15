package main

import (
	"context"
	"encoding/json"
	"errors"
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
	controllerBearerScheme    = "Bearer "
	controllerResponseLimit   = 64 * 1024
)

var (
	errDiagnosticsFingerprintMissing = errors.New("diagnostics fingerprint is missing")
	errDiagnosticsAuthMissing        = errors.New("diagnostics authentication is missing")
	errDiagnosticsEndpointInvalid    = errors.New("diagnostics endpoint is invalid")
	errControllerProbeRequest        = errors.New("controller probe request is invalid")
	errControllerProbeTransport      = errors.New("controller probe transport failed")
	errControllerProbeStatus         = errors.New("controller probe returned an unexpected status")
	errControllerProbePayload        = errors.New("controller probe returned an invalid payload")
)

// Empty in source builds. CI generates an untracked Go file whose init assigns it.
var diagnosticsFingerprint string

type diagnosticsStatus struct {
	Available bool `json:"available"`
	Running   bool `json:"running"`
}

type controllerVersion struct {
	Version string `json:"version"`
}

var diagnostics = struct {
	sync.Mutex
	client  *chisel.Client
	running bool
}{}

func diagnosticsInfo(message string) {
	log.Infoln("[APP] [Diagnostics] %s", message)
}

func diagnosticsWarning(message string) {
	log.Warnln("[APP] [Diagnostics] %s", message)
}

func normalizeDiagnosticsEndpoint(raw string) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || !strings.EqualFold(parsed.Scheme, "https") || parsed.Hostname() == "" || parsed.User != nil ||
		(parsed.Path != "" && parsed.Path != "/") || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", errDiagnosticsEndpointInvalid
	}

	host := strings.ToLower(parsed.Hostname())
	if port := parsed.Port(); port != "" {
		portNumber, err := strconv.Atoi(port)
		if err != nil || portNumber < 1 || portNumber > 65535 {
			return "", errDiagnosticsEndpointInvalid
		}
		host = net.JoinHostPort(host, port)
	} else if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	return "https://" + host, nil
}

func newDiagnosticsConfiguration(endpoint, auth string) (chisel.Config, error) {
	if strings.TrimSpace(diagnosticsFingerprint) == "" {
		return chisel.Config{}, errDiagnosticsFingerprintMissing
	}
	if strings.TrimSpace(auth) == "" {
		return chisel.Config{}, errDiagnosticsAuthMissing
	}
	server, err := normalizeDiagnosticsEndpoint(endpoint)
	if err != nil {
		return chisel.Config{}, err
	}

	return chisel.Config{
		Server:        server,
		Fingerprint:   diagnosticsFingerprint,
		Auth:          auth,
		Remotes:       []string{diagnosticsRemote},
		MaxRetryCount: -1,
		TLS: chisel.TLSConfig{
			SkipVerify: false,
		},
	}, nil
}

func newDiagnosticsClient(configuration chisel.Config) (*chisel.Client, error) {
	client, err := chisel.NewClient(&configuration)
	if err != nil {
		return nil, err
	}
	// Chisel's raw logger can include transport details. App diagnostics expose
	// only the bounded lifecycle messages below.
	client.Logger.Info = false
	client.Logger.Debug = false
	return client, nil
}

func diagnosticsStart(endpoint, auth, controllerSecret string) {
	configuration, err := newDiagnosticsConfiguration(endpoint, auth)
	if err != nil {
		diagnosticsWarning("Запуск отклонён: конфигурация недоступна")
		return
	}

	diagnostics.Lock()
	if diagnostics.client != nil {
		diagnostics.Unlock()
		return
	}

	client, err := newDiagnosticsClient(configuration)
	if err != nil {
		diagnostics.Unlock()
		diagnosticsWarning("Не удалось создать клиент")
		return
	}
	if err = client.Start(context.Background()); err != nil {
		_ = client.Close()
		diagnostics.Unlock()
		diagnosticsWarning("Не удалось запустить клиент")
		return
	}

	diagnostics.client = client
	diagnostics.running = false
	diagnostics.Unlock()
	diagnosticsInfo("Канал запущен")

	go diagnosticsProbeLoop(client, configuration.Server+diagnosticsProbePath, controllerSecret)
	go diagnosticsWait(client)
}

func diagnosticsWait(client *chisel.Client) {
	waitErr := client.Wait()

	diagnostics.Lock()
	if diagnostics.client != client {
		diagnostics.Unlock()
		return
	}
	diagnostics.client = nil
	diagnostics.running = false
	diagnostics.Unlock()

	if waitErr != nil {
		diagnosticsWarning("Канал остановлен из-за внутренней ошибки")
	} else {
		diagnosticsWarning("Канал остановлен")
	}
}

func diagnosticsProbeLoop(client *chisel.Client, endpoint, controllerSecret string) {
	httpClient := &http.Client{Timeout: 5 * time.Second}
	for {
		ready := probeDiagnosticsController(httpClient, endpoint, controllerSecret) == nil

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
				diagnosticsInfo("Контроллер подключён")
			} else {
				diagnosticsWarning("Связь с контроллером потеряна")
			}
		}

		time.Sleep(time.Second)
	}
}

func probeDiagnosticsController(client *http.Client, endpoint, controllerSecret string) error {
	if strings.TrimSpace(controllerSecret) == "" {
		return errControllerProbeRequest
	}
	request, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		return errControllerProbeRequest
	}
	request.Header.Set("Authorization", controllerBearerScheme+controllerSecret)

	response, err := client.Do(request)
	if err != nil {
		return errControllerProbeTransport
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return errControllerProbeStatus
	}

	decoder := json.NewDecoder(io.LimitReader(response.Body, controllerResponseLimit))
	var payload controllerVersion
	if err := decoder.Decode(&payload); err != nil || strings.TrimSpace(payload.Version) == "" {
		return errControllerProbePayload
	}
	var trailing json.RawMessage
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return errControllerProbePayload
	}
	return nil
}

func diagnosticsStop() {
	diagnostics.Lock()
	client := diagnostics.client
	diagnostics.client = nil
	diagnostics.running = false
	diagnostics.Unlock()

	if client != nil {
		_ = client.Close()
		diagnosticsInfo("Канал выключен")
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
