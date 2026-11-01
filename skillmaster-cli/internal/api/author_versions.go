// The author plane's version list (v37 split): the read every page action's precheck starts from.
package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
)

// AuthorVersions lists the versions this credential may see, newest first.
func (c Client) AuthorVersions(ctx context.Context, address string, query AuthorVersionQuery) (AuthorVersionList, error) {
	base, err := authorPath(address)
	if err != nil {
		return AuthorVersionList{}, err
	}
	values := url.Values{}
	for _, status := range query.Statuses {
		values.Add("status", status)
	}
	if query.Query != "" {
		values.Set("q", query.Query)
	}
	limit := query.Limit
	if limit == 0 {
		limit = 50
	}
	values.Set("limit", strconv.Itoa(limit))
	if query.Cursor != "" {
		values.Set("cursor", query.Cursor)
	}

	body, err := c.authorRequest(ctx, http.MethodGet, base+"/versions?"+values.Encode(), nil)
	if err != nil {
		return AuthorVersionList{}, err
	}
	var listed AuthorVersionList
	if err := json.Unmarshal(body, &listed); err != nil {
		return AuthorVersionList{}, fmt.Errorf("版本列表响应不是 JSON：%w", err)
	}
	return listed, nil
}
