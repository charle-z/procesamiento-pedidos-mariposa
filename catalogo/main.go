package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"time"
)

type product struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Price       int64  `json:"price"`
}

type customer struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Active bool   `json:"active"`
}

func newHandler() http.Handler {
	products := map[string]product{
		"product-789": {ID: "product-789", Name: "Laptop", Description: "Equipo portátil", Price: 999},
		"product-456": {ID: "product-456", Name: "Monitor", Description: "Pantalla", Price: 250},
	}
	customers := map[string]customer{
		"customer-456": {ID: "customer-456", Name: "Cliente activo", Active: true},
		"customer-789": {ID: "customer-789", Name: "Cliente inactivo", Active: false},
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /products/{id}", func(w http.ResponseWriter, r *http.Request) {
		item, ok := products[r.PathValue("id")]
		if !ok {
			http.NotFound(w, r)
			return
		}
		writeJSON(w, item)
	})
	mux.HandleFunc("GET /customers/{id}", func(w http.ResponseWriter, r *http.Request) {
		item, ok := customers[r.PathValue("id")]
		if !ok {
			http.NotFound(w, r)
			return
		}
		writeJSON(w, item)
	})
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})
	return mux
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("error writing response: %v", err)
	}
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8081"
	}
	server := &http.Server{
		Addr:              ":" + port,
		Handler:           newHandler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	log.Printf("catalog listening on %s", server.Addr)
	log.Fatal(server.ListenAndServe())
}
