package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"

	"github.com/skratchdot/open-golang/open"
)

type TokenResp struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
}

func main() {
	const AUTH_END_POINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
	const REDIRECT_URI = "http://localhost:3072"
	var clientId string
	var clientSecret string
	var scopes string
	reader := bufio.NewReader(os.Stdin)
	fmt.Printf("Client ID: ")
	fmt.Scanln(&clientId)
	fmt.Printf("Client Secret: ")
	fmt.Scanln(&clientSecret)
	fmt.Printf("Scopes(Space separated): ")
	line, err := reader.ReadString('\n')
	if err != nil {
		fmt.Println("Read Error:", err)
		return
	}
	scopes = line
	reqUrl := AUTH_END_POINT + "?client_id=" + clientId + "&scope=" + url.QueryEscape(scopes) + "&response_type=code" + "&redirect_uri=" + url.QueryEscape(REDIRECT_URI) + "&response_mode=query"
	err = open.Run(reqUrl)
	if err != nil {
		panic(err)
	}

	http.HandleFunc("/shutdown", func(w http.ResponseWriter, r *http.Request) {
		fmt.Println("Shutting down...")
		os.Exit(0)
	})

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		code := r.URL.Query().Get("code")
		if code == "" {
			fmt.Fprint(w, "Code empty, please retry")
		} else {
			fmt.Printf("code: %s\n", code)
			fmt.Println("Requesting...\n\n")
			// 使用code换取access_token
			resp, postErr := http.PostForm("https://login.microsoftonline.com/common/oauth2/v2.0/token", url.Values{
				"grant_type":    {"authorization_code"},
				"client_id":     {clientId},
				"client_secret": {clientSecret},
				"code":          {code},
				"redirect_uri":  {REDIRECT_URI},
				"scope":         {scopes},
			})
			if postErr != nil {
				fmt.Println("Error: ", postErr)
				panic(postErr)
			}
			respBody, bodyReadErr := io.ReadAll(resp.Body)
			if bodyReadErr != nil {
				fmt.Println("Error: ", bodyReadErr)
				panic(bodyReadErr)
			}
			var tokenResp TokenResp
			fmt.Println("Response status:", resp.Status)
			fmt.Println(string(respBody))
			json.Unmarshal(respBody, &tokenResp)
			fmt.Fprint(w, "<p>Successfully got token.</p>")
			fmt.Fprintf(w, "<p>Access token: <textarea disabled>%s</textarea></p>", tokenResp.AccessToken)
			fmt.Fprintf(w, "<p>Refresh token: <textarea disabled>%s</textarea></p>", tokenResp.RefreshToken)
			defer resp.Body.Close()
		}
		fmt.Fprint(w, "<p><a href='/shutdown'>OK</a></p>")
	})

	// 启动HTTP服务器，监听端口3072
	err = http.ListenAndServe(":3072", nil)
	if err != nil {
		fmt.Println("Error:", err)
	}
}
