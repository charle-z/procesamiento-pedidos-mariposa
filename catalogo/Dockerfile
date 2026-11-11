FROM golang:1.27.3-alpine@sha256:0fd028db9bdd4f01657bc8047c209b04d8ce19b853d776bd6f1e76a41729b936 AS build
WORKDIR /src
COPY go.mod .
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /catalogo .

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=build /catalogo /catalogo
EXPOSE 8081
ENTRYPOINT ["/catalogo"]
