// Package auth is the half of the CLI that holds a credential: getting one, saying which stored
// credential a command may present, and ending the one a login replaces.
package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Metadata is the part of the authorization server's discovery document this CLI uses.
//
// Read at runtime rather than written into the CLI, and that is the point of the document
// existing: the endpoints are the server's to move (ADR 0023 configures them to `/oauth/*` rather
// than following the framework's defaults), and a client that hardcoded them would be the thing
// that broke when they moved.
//
// **Four fields used to be here and two are left, which is what v4 changed about this file.** The
// authorization and token endpoints were what `login` drove the authorization-code flow with; the
// first party no longer walks that road (it has its own sign-in page and its own exchange), so what
// remains is the **issuer** — which is where that page lives — and the revocation endpoint, which is
// how `login` ends the authorization it replaces. `/oauth/*` itself is untouched: it is the third
// party's way in (ADR 0041), and this CLI is not a third party.
type Metadata struct {
	Issuer             string `json:"issuer"`
	RevocationEndpoint string `json:"revocation_endpoint"`
}

// DiscoveryPath is RFC 8414's well-known location.
const DiscoveryPath = "/.well-known/oauth-authorization-server"

// discoveryTimeout bounds the metadata read. Short, because it happens before anything has been
// shown to the person: a server that is not answering should say so quickly rather than after the
// default client timeout, which has none.
const discoveryTimeout = 10 * time.Second

// Discover reads the authorization server's metadata.
func Discover(ctx context.Context, client *http.Client, server string) (Metadata, error) {
	ctx, cancel := context.WithTimeout(ctx, discoveryTimeout)
	defer cancel()

	url := server + DiscoveryPath
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return Metadata{}, fmt.Errorf("building a request for %s: %w", url, err)
	}
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return Metadata{}, fmt.Errorf("reading %s: %w", url, err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return Metadata{}, fmt.Errorf("reading %s: %w", url, err)
	}
	if resp.StatusCode != http.StatusOK {
		return Metadata{}, fmt.Errorf("%s answered %d: %s", url, resp.StatusCode, summarise(body))
	}

	var metadata Metadata
	if err := json.Unmarshal(body, &metadata); err != nil {
		return Metadata{}, fmt.Errorf("%s did not return a metadata document: %w", url, err)
	}
	// Checked here rather than at the first use: a document without an issuer is a server that is not
	// going to work — the issuer is the one address a login has to have, because it is where the
	// person is sent — and finding that out now costs a message instead of a browser that opens onto
	// a 404.
	if metadata.Issuer == "" {
		return Metadata{}, fmt.Errorf(
			"%s's metadata has no issuer, so this CLI does not know which page to open", url)
	}
	return metadata, nil
}

// summarise keeps an error message to something a person can read.
func summarise(body []byte) string {
	const limit = 200
	if len(body) > limit {
		return string(body[:limit]) + "…"
	}
	return string(body)
}
