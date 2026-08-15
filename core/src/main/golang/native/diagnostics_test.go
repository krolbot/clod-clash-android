package main

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/log"
)

func withDiagnosticsFingerprint(t *testing.T, fingerprint string) {
	t.Helper()
	previous := diagnosticsFingerprint
	diagnosticsFingerprint = fingerprint
	t.Cleanup(func() { diagnosticsFingerprint = previous })
}

func TestDiagnosticsConfigurationIsUnavailableWithoutBuildFingerprint(t *testing.T) {
	withDiagnosticsFingerprint(t, "")

	if _, err := newDiagnosticsConfiguration("https://example.com", "user:password"); !errors.Is(err, errDiagnosticsFingerprintMissing) {
		t.Fatalf("error = %v", err)
	}
}

func TestDiagnosticsConfigurationUsesOfficialChiselAndFixedReverseRemote(t *testing.T) {
	withDiagnosticsFingerprint(t, "SHA256:placeholder=")

	configuration, err := newDiagnosticsConfiguration("https://example.com:8443/", "user:password")
	if err != nil {
		t.Fatal(err)
	}
	if configuration.Server != "https://example.com:8443" {
		t.Fatalf("server = %q", configuration.Server)
	}
	if len(configuration.Remotes) != 1 || configuration.Remotes[0] != diagnosticsRemote {
		t.Fatalf("remotes = %#v, want only %q", configuration.Remotes, diagnosticsRemote)
	}
	if configuration.TLS.SkipVerify {
		t.Fatal("TLS verification must stay enabled")
	}
	if configuration.DialContext != nil {
		t.Fatal("diagnostic control connection must use the VPN route to avoid fake-IP escape")
	}
	if configuration.Fingerprint != diagnosticsFingerprint || configuration.Auth != "user:password" {
		t.Fatal("fingerprint or supplied auth was not propagated")
	}
	if configuration.MaxRetryCount != -1 {
		t.Fatal("diagnostics must keep retrying while the manual session is enabled")
	}

	client, err := newDiagnosticsClient(configuration)
	if err != nil {
		t.Fatal(err)
	}
	if client.Logger.Info || client.Logger.Debug {
		t.Fatal("official chisel logger must stay disabled")
	}
}

func TestDiagnosticsConfigurationRejectsMissingAuth(t *testing.T) {
	withDiagnosticsFingerprint(t, "SHA256:placeholder=")

	if _, err := newDiagnosticsConfiguration("https://example.com", ""); !errors.Is(err, errDiagnosticsAuthMissing) {
		t.Fatalf("error = %v", err)
	}
}

func TestDiagnosticsConfigurationRejectsNonOriginEndpoints(t *testing.T) {
	withDiagnosticsFingerprint(t, "SHA256:placeholder=")

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
		if _, err := newDiagnosticsConfiguration(endpoint, "user:password"); !errors.Is(err, errDiagnosticsEndpointInvalid) {
			t.Errorf("endpoint %q: error = %v", endpoint, err)
		} else if endpoint != "" && strings.Contains(err.Error(), endpoint) {
			t.Errorf("error exposed rejected endpoint %q", endpoint)
		}
	}
}

func TestDiagnosticsStatusNeverExportsInternalErrors(t *testing.T) {
	withDiagnosticsFingerprint(t, "")

	diagnosticsStart("https://example.com", "user:password", "controller-secret")
	status := diagnosticsQuery()
	if strings.Contains(status, "error") || strings.Contains(status, "недоступна") {
		t.Fatalf("status exposed internal error: %s", status)
	}
}

func TestDiagnosticsLogAppearsInCoreLogStream(t *testing.T) {
	subscriber := log.Subscribe()
	defer log.UnSubscribe(subscriber)

	diagnosticsInfo("Запуск канала")

	awaitDiagnosticsLog(t, subscriber, "[APP] [Diagnostics] Запуск канала")
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

func TestDiagnosticsControllerProbeRequiresAuthenticatedVersionResponse(t *testing.T) {
	tests := []struct {
		name        string
		status      int
		body        string
		expectError error
	}{
		{name: "controller", status: http.StatusOK, body: `{"version":"test"}`},
		{name: "unauthorized", status: http.StatusUnauthorized, body: `{}`, expectError: errControllerProbeStatus},
		{name: "missing version", status: http.StatusOK, body: `{}`, expectError: errControllerProbePayload},
		{name: "panel html", status: http.StatusOK, body: "<html></html>", expectError: errControllerProbePayload},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
				if got := request.Header.Get("Authorization"); got != "Bearer controller-secret" {
					t.Errorf("authorization = %q", got)
				}
				response.WriteHeader(test.status)
				_, _ = response.Write([]byte(test.body))
			}))
			defer server.Close()

			err := probeDiagnosticsController(server.Client(), server.URL, "controller-secret")
			if !errors.Is(err, test.expectError) {
				t.Fatalf("error = %v, want %v", err, test.expectError)
			}
		})
	}
}
