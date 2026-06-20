package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/bsfdsagfadg/vertex/internal/config"
)

func TestPublicModelReadWithoutAPIKey(t *testing.T) {
	srv := NewServer(nil, NewAPIKeyManager(), config.AppConfig{})
	handler := srv.Handler()

	modelReq := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	modelRec := httptest.NewRecorder()
	handler.ServeHTTP(modelRec, modelReq)
	if modelRec.Code != http.StatusOK {
		t.Fatalf("GET /v1/models without key = %d, want 200", modelRec.Code)
	}

	modelSlashReq := httptest.NewRequest(http.MethodGet, "/v1/models/", nil)
	modelSlashRec := httptest.NewRecorder()
	handler.ServeHTTP(modelSlashRec, modelSlashReq)
	if modelSlashRec.Code != http.StatusOK {
		t.Fatalf("GET /v1/models/ without key = %d, want 200", modelSlashRec.Code)
	}

	rootReq := httptest.NewRequest(http.MethodHead, "/v1", nil)
	rootRec := httptest.NewRecorder()
	handler.ServeHTTP(rootRec, rootReq)
	if rootRec.Code != http.StatusOK {
		t.Fatalf("HEAD /v1 without key = %d, want 200", rootRec.Code)
	}

	rootSlashReq := httptest.NewRequest(http.MethodGet, "/v1/", nil)
	rootSlashRec := httptest.NewRecorder()
	handler.ServeHTTP(rootSlashRec, rootSlashReq)
	if rootSlashRec.Code != http.StatusOK {
		t.Fatalf("GET /v1/ without key = %d, want 200", rootSlashRec.Code)
	}

	chatReq := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", nil)
	chatRec := httptest.NewRecorder()
	handler.ServeHTTP(chatRec, chatReq)
	if chatRec.Code != http.StatusUnauthorized {
		t.Fatalf("POST /v1/chat/completions without key = %d, want 401", chatRec.Code)
	}
}
