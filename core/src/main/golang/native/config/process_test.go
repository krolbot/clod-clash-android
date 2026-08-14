package config

import (
	"testing"

	mihomoConfig "github.com/metacubex/mihomo/config"
)

func TestPatchExternalControllerUsesLoopbackDefault(t *testing.T) {
	cfg := mihomoConfig.DefaultRawConfig()
	cfg.ExternalController = "0.0.0.0:1234"
	cfg.ExternalControllerTLS = "0.0.0.0:1235"

	if err := patchExternalController(cfg, ""); err != nil {
		t.Fatal(err)
	}
	if cfg.ExternalController != "127.0.0.1:9090" {
		t.Fatalf("external controller = %q", cfg.ExternalController)
	}
	if cfg.ExternalControllerTLS != "" {
		t.Fatalf("external TLS controller = %q", cfg.ExternalControllerTLS)
	}
	if cfg.AllowLan {
		t.Fatal("allow-lan must stay disabled")
	}
}

func TestExternalControllerOverrideCannotEscapeLoopback(t *testing.T) {
	previousSessionOverride := sessionOverride
	sessionOverride = `{"allow-lan":true,"external-controller":"0.0.0.0:1234","external-controller-tls":"0.0.0.0:1235"}`
	t.Cleanup(func() { sessionOverride = previousSessionOverride })

	cfg := mihomoConfig.DefaultRawConfig()
	for _, apply := range processors[:2] {
		if err := apply(cfg, ""); err != nil {
			t.Fatal(err)
		}
	}

	if cfg.ExternalController != "127.0.0.1:9090" {
		t.Fatalf("external controller = %q", cfg.ExternalController)
	}
	if cfg.ExternalControllerTLS != "" {
		t.Fatalf("external TLS controller = %q", cfg.ExternalControllerTLS)
	}
	if cfg.AllowLan {
		t.Fatal("allow-lan override must not expose proxy listeners")
	}
}
