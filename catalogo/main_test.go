package main

import (
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
