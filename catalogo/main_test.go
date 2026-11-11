package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCatalog(t *testing.T) {
	handler := newHandler()
	for _, tc := range []struct {
		path string
		want int
	}{
		{"/products/product-789", http.StatusOK},
		{"/products/missing", http.StatusNotFound},
		{"/customers/customer-456", http.StatusOK},
		{"/customers/missing", http.StatusNotFound},
		{"/health", http.StatusNoContent},
	} {
		t.Run(tc.path, func(t *testing.T) {
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, tc.path, nil))
			if response.Code != tc.want {
				t.Fatalf("status = %d, want %d", response.Code, tc.want)
			}
		})
	}
}

func TestProductBatch(t *testing.T) {
	for _, tc := range []struct {
		name string
		body string
		want int
	}{
		{"all found", `{"productIds":["product-789","product-456"]}`, http.StatusOK},
		{"missing", `{"productIds":["product-789","missing"]}`, http.StatusNotFound},
		{"empty", `{"productIds":[]}`, http.StatusBadRequest},
		{"invalid", `{"productIds":["product-789"]} {}`, http.StatusBadRequest},
	} {
		t.Run(tc.name, func(t *testing.T) {
			response := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodPost, "/products/batch", bytes.NewBufferString(tc.body))
			newHandler().ServeHTTP(response, request)
			if response.Code != tc.want {
				t.Fatalf("status = %d, want %d", response.Code, tc.want)
			}
			if tc.want == http.StatusOK {
				var got []product
				if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil || len(got) != 2 {
					t.Fatalf("unexpected response: %s", response.Body.String())
				}
			}
		})
	}
}

func TestCustomerStatus(t *testing.T) {
	response := httptest.NewRecorder()
	newHandler().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/customers/customer-789", nil))
	var got customer
	if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.ID != "customer-789" || got.Active {
		t.Fatalf("unexpected customer: %+v", got)
	}
}
