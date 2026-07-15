package unicomMonitor

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// WebDavClient WebDAV 客户端
type WebDavClient struct {
	baseURL string
	client  *http.Client
}

// NewWebDavClient 创建 WebDAV 客户端
// urlStr 格式: http://user:pass@host:port/path
func NewWebDavClient(urlStr string) (*WebDavClient, error) {
	if urlStr == "" {
		return nil, fmt.Errorf("WebDAV 地址为空")
	}

	// 解析 URL
	parsedURL, err := url.Parse(urlStr)
	if err != nil {
		return nil, fmt.Errorf("解析 WebDAV 地址失败: %w", err)
	}

	// 提取用户信息
	username := parsedURL.User.Username()
	password, _ := parsedURL.User.Password()

	// 构建基础 URL (不包含用户信息)
	baseURL := fmt.Sprintf("%s://%s%s", parsedURL.Scheme, parsedURL.Host, parsedURL.Path)

	client := &http.Client{
		Timeout: 30 * time.Second,
	}

	// 如果有认证信息，设置 Transport
	if username != "" {
		client.Transport = &basicAuthTransport{
			username: username,
			password: password,
		}
	}

	return &WebDavClient{
		baseURL: baseURL,
		client:  client,
	}, nil
}

// basicAuthTransport 基础认证 Transport
type basicAuthTransport struct {
	username string
	password string
	base     http.RoundTripper
}

func (t *basicAuthTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if t.base == nil {
		t.base = http.DefaultTransport
	}
	req2 := req.Clone(req.Context())
	req2.SetBasicAuth(t.username, t.password)
	return t.base.RoundTrip(req2)
}

// EnsureDir 确保 WebDAV 目录存在
func (c *WebDavClient) EnsureDir(dirPath string) error {
	// 将本地路径转换为 WebDAV 路径
	webdavPath := strings.ReplaceAll(dirPath, "\\", "/")
	webdavPath = strings.TrimPrefix(webdavPath, "/")

	// 逐级创建目录
	parts := strings.Split(webdavPath, "/")
	currentPath := ""
	for _, part := range parts {
		if part == "" || part == "." {
			continue
		}
		currentPath += "/" + part
		// 尝试创建目录 (MKCOL)
		req, err := http.NewRequest("MKCOL", c.baseURL+currentPath+"/", nil)
		if err != nil {
			return err
		}
		resp, err := c.client.Do(req)
		if err != nil {
			return err
		}
		resp.Body.Close()
		// 201 Created 或 405 Method Not Allowed (已存在) 都视为成功
		if resp.StatusCode != 201 && resp.StatusCode != 405 && resp.StatusCode != 409 {
			return fmt.Errorf("创建目录失败 %s: %s", currentPath, resp.Status)
		}
	}
	return nil
}

// UploadFile 上传文件到 WebDAV
func (c *WebDavClient) UploadFile(localPath, remotePath string) error {
	// 读取本地文件
	data, err := os.ReadFile(localPath)
	if err != nil {
		return fmt.Errorf("读取本地文件失败: %w", err)
	}

	// 确保远程目录存在
	remoteDir := filepath.Dir(remotePath)
	if err := c.EnsureDir(remoteDir); err != nil {
		return fmt.Errorf("创建远程目录失败: %w", err)
	}

	// 上传文件 (PUT)
	webdavPath := strings.ReplaceAll(remotePath, "\\", "/")
	webdavPath = strings.TrimPrefix(webdavPath, "/")
	req, err := http.NewRequest("PUT", c.baseURL+"/"+webdavPath, bytes.NewReader(data))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/octet-stream")

	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 201 && resp.StatusCode != 204 {
		return fmt.Errorf("上传文件失败: %s", resp.Status)
	}

	return nil
}

// IsEnabled 检查 WebDAV 是否启用
func IsWebDavEnabled(config *Config) bool {
	return config != nil && config.WebDav != ""
}

// SaveToWebDav 保存文件到 WebDAV (本地 + 远程)
func SaveToWebDav(config *Config, localPath string) error {
	if !IsWebDavEnabled(config) {
		return nil
	}

	client, err := NewWebDavClient(config.WebDav)
	if err != nil {
		return fmt.Errorf("创建 WebDAV 客户端失败: %w", err)
	}

	// 构建远程路径: WebDAV 根路径 + 本地相对路径
	relPath, _ := filepath.Rel(config.Path, localPath)
	remotePath := filepath.ToSlash(relPath)

	FmtPrint("上传到 WebDAV: %s -> %s", localPath, remotePath)
	if err := client.UploadFile(localPath, remotePath); err != nil {
		return fmt.Errorf("WebDAV 上传失败: %w", err)
	}

	FmtPrint("WebDAV 上传成功")
	return nil
}

// WebDavListFiles 列出 WebDAV 目录中的文件
func WebDavListFiles(config *Config, dir string) ([]string, error) {
	if !IsWebDavEnabled(config) {
		return nil, nil
	}

	client, err := NewWebDavClient(config.WebDav)
	if err != nil {
		return nil, err
	}

	// PROPFIND 请求
	webdavDir := strings.ReplaceAll(dir, "\\", "/")
	webdavDir = strings.TrimPrefix(webdavDir, "/")
	req, err := http.NewRequest("PROPFIND", client.baseURL+"/"+webdavDir+"/", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Depth", "1")
	req.Header.Set("Content-Type", "application/xml")

	resp, err := client.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 207 {
		return nil, fmt.Errorf("PROPFIND 失败: %s", resp.Status)
	}

	// 解析 XML 响应 (简化版: 只提取 href)
	body, _ := io.ReadAll(resp.Body)
	var files []string
	// 简单的 XML 解析 (提取 <d:href> 内容)
	lines := strings.Split(string(body), "<d:href>")
	for _, line := range lines[1:] {
		if end := strings.Index(line, "</d:href>"); end > 0 {
			href := line[:end]
			href = strings.TrimPrefix(href, "/")
			if href != "" && href != webdavDir+"/" {
				files = append(files, filepath.Base(href))
			}
		}
	}

	return files, nil
}
