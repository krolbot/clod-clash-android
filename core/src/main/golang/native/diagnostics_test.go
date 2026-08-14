package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	chisel "github.com/jpillora/chisel/client"
	"github.com/metacubex/mihomo/log"
)

func TestDiagnosticsConfigurationIsUnavailableWithoutBuildCredentials(t *testing.T) {
	oldFingerprint := diagnosticsFingerprint
	diagnosticsFingerprint = ""
	t.Cleanup(func() {
		diagnosticsFingerprint = oldFingerprint
	})

	if _, ok := diagnosticsConfiguration("https://example.com", "user:password"); ok {
		t.Fatal("expected empty build fingerprint to disable diagnostics")
	}
}

func TestDiagnosticsConfigurationUsesRuntimeEndpointAndFixedReverseRemote(t *testing.T) {
	oldFingerprint := diagnosticsFingerprint
	diagnosticsFingerprint = "SHA256:placeholder="
	t.Cleanup(func() {
		diagnosticsFingerprint = oldFingerprint
	})

	config, ok := diagnosticsConfiguration("https://example.com:8443/", "user:password")
	if !ok {
		t.Fatal("expected non-empty build credentials to enable diagnostics")
	}
	if config.Server != "https://example.com:8443" {
		t.Fatalf("server = %q", config.Server)
	}
	if len(config.Remotes) != 1 || config.Remotes[0] != diagnosticsRemote {
		t.Fatalf("remotes = %#v, want only %q", config.Remotes, diagnosticsRemote)
	}
	if config.TLS.SkipVerify {
		t.Fatal("TLS verification must stay enabled")
	}
	if config.DialContext != nil {
		t.Fatal("diagnostic control connection must use the VPN route to avoid fake-IP escape")
	}
	if config.Fingerprint != diagnosticsFingerprint || config.Auth != "user:password" {
		t.Fatal("fingerprint or supplied auth was not propagated")
	}
	if config.MaxRetryCount != -1 {
		t.Fatal("diagnostics must keep retrying while the manual session is enabled")
	}
	if !config.DisableLogger {
		t.Fatal("chisel logger must stay disabled")
	}
	if config.EventHandler == nil {
		t.Fatal("missing diagnostics event handler")
	}
}

func TestDiagnosticsConfigurationIsUnavailableWithoutAuth(t *testing.T) {
	oldFingerprint := diagnosticsFingerprint
	diagnosticsFingerprint = "SHA256:placeholder="
	t.Cleanup(func() { diagnosticsFingerprint = oldFingerprint })

	if _, ok := diagnosticsConfiguration("https://example.com", ""); ok {
		t.Fatal("expected empty local auth to disable diagnostics")
	}
}

func TestDiagnosticsConfigurationRejectsNonOriginEndpoints(t *testing.T) {
	oldFingerprint := diagnosticsFingerprint
	diagnosticsFingerprint = "SHA256:placeholder="
	t.Cleanup(func() { diagnosticsFingerprint = oldFingerprint })

	for _, endpoint := range []string{
		"",
		"http://example.com",
		"https://user:password@example.com",
		"https://example.com/path",
		"https://example.com?query=value",
		"https://example.com#fragment",
		"https://example.com:0",
		"https://example.com:65536",
	} {
		if _, ok := diagnosticsConfiguration(endpoint, "user:password"); ok {
			t.Errorf("accepted endpoint %q", endpoint)
		}
	}
}

func TestDiagnosticsStatusNeverExportsInternalErrors(t *testing.T) {
	oldFingerprint := diagnosticsFingerprint
	diagnosticsFingerprint = ""
	t.Cleanup(func() { diagnosticsFingerprint = oldFingerprint })

	diagnosticsStart("https://example.com", "user:password")
	status := diagnosticsQuery()
	if strings.Contains(status, "error") || strings.Contains(status, "недоступна") {
		t.Fatalf("status exposed internal error: %s", status)
	}
}

func TestDiagnosticsLogAppearsInCoreLogStream(t *testing.T) {
	subscriber := log.Subscribe()
	defer log.UnSubscribe(subscriber)

	diagnosticsLog(false, "Запуск канала")

	awaitDiagnosticsLog(t, subscriber, "[APP] [Diagnostics] Запуск канала")
}

func TestDiagnosticsChiselEventDoesNotExposeEndpointOrSecrets(t *testing.T) {
	subscriber := log.Subscribe()
	defer log.UnSubscribe(subscriber)

	diagnosticsChiselEvent(chisel.Event{
		Stage:      chisel.EventStageWebSocket,
		Result:     chisel.EventResultFailed,
		Attempt:    2,
		HTTPStatus: http.StatusUnauthorized,
	})

	want := "WebSocket; stage=websocket result=failed attempt=2 http=401"
	payload := awaitDiagnosticsLog(t, subscriber, want)
	if strings.Contains(payload, "server=") {
		t.Fatalf("payload exposed endpoint: %q", payload)
	}
}

func TestDiagnosticsReverseEventIncludesBothAddresses(t *testing.T) {
	subscriber := log.Subscribe()
	defer log.UnSubscribe(subscriber)

	diagnosticsChiselEvent(chisel.Event{
		Stage:   chisel.EventStageReverseConfig,
		Result:  chisel.EventResultFailed,
		Attempt: 3,
	})

	want := "Reverse listen=0.0.0.0:9091 target=127.0.0.1:9090; stage=reverse_config result=failed attempt=3"
	awaitDiagnosticsLog(t, subscriber, want)
}

func awaitDiagnosticsLog(t *testing.T, subscriber <-chan log.Event, want string) string {
	t.Helper()
	timer := time.NewTimer(time.Second)
	defer timer.Stop()

	for {
		select {
		case message := <-subscriber:
			if strings.Contains(message.Payload, want) {
				return message.Payload
			}
		case <-timer.C:
			t.Fatalf("diagnostics log %q was not published", want)
		}
	}
}

func TestDiagnosticsControllerReadyRequiresSuccessfulJSONResponse(t *testing.T) {
	tests := []struct {
		name        string
		status      int
		body        string
		expectReady bool
	}{
		{name: "controller", status: http.StatusOK, body: `{"version":"test"}`, expectReady: true},
		{name: "missing tunnel", status: http.StatusBadGateway, body: "bad gateway"},
		{name: "panel html", status: http.StatusOK, body: "<html></html>"},
		{name: "invalid json", status: http.StatusOK, body: "{"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
				response.WriteHeader(test.status)
				_, _ = response.Write([]byte(test.body))
			}))
			defer server.Close()

			if ready := diagnosticsControllerReady(server.Client(), server.URL); ready != test.expectReady {
				t.Fatalf("ready = %v, want %v", ready, test.expectReady)
			}
		})
	}
}
