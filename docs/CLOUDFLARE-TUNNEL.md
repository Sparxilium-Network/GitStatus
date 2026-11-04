# Cloudflare Tunnel 設定教學

讓 GitHub 透過 Cloudflare Tunnel 連到伺服器上的通知服務，**伺服器不用開放任何連入的 port**。

```
GitHub ──HTTPS──▶ Cloudflare ──Tunnel──▶ 你的伺服器上的 cloudflared ──▶ http://localhost:33444（通知服務）
```

Tunnel 由你自己建立與管理。照著下面做，大約 10 分鐘可以完成。

## 0. 先填好這張表

後面的步驟會用到這些值，先決定好：

| 代號 | 說明 | 你的值 | 範例 |
|---|---|---|---|
| `DOMAIN` | 你在 Cloudflare 管理 DNS 的網域 | | `example.com` |
| `SUBDOMAIN` | 給 webhook 用的子網域 | | `git-hooks` |
| `TUNNEL_NAME` | Tunnel 的名字，自己取 | | `git-notifier` |

組合起來：

- **HOSTNAME** = `SUBDOMAIN.DOMAIN`，例如 `git-hooks.example.com`
- **Webhook URL** = `https://HOSTNAME/webhooks/github`，例如 `https://git-hooks.example.com/webhooks/github`

這個 Webhook URL 就是建立 GitHub App 時要填的網址。

**前提**：通知服務已經用 `deploy/docker-compose.yml` 啟動，伺服器上可以用以下指令看到 `{"status":"UP"}`：

```bash
curl http://127.0.0.1:33444/health
```

## 選擇設定方式

| 方式 | 適合 | 說明 |
|---|---|---|
| **A. Dashboard（推薦）** | 大部分人 | 在網頁上設定，伺服器只需要一個 token |
| B. 指令列 | 想把設定存成檔案的人 | 用 `cloudflared` 指令建立，設定放在 `config.yml` |

兩種方式擇一即可。

---

## 方式 A：Dashboard（推薦）

### A-1. 建立 Tunnel

1. 開啟 [Cloudflare Zero Trust](https://one.dash.cloudflare.com/) → **Networks** → **Tunnels** → **Create a tunnel**。
2. 類型選 **Cloudflared**，名稱填 **`TUNNEL_NAME`**，按 **Save tunnel**。
3. 接下來的頁面會顯示安裝指令，例如：
   ```
   sudo cloudflared service install eyJhIjoiNDU2...（很長一串）
   ```
   複製 `install` 後面那一長串，這就是你的 **Tunnel token**。先存起來，**不要外流**。
4. 進入路由設定，按 **新增路由**（Add route）。

> Cloudflare 的介面名稱偶爾會調整，找不到時以 Tunnels 頁面內的說明為準。

### A-2. 新增路由

1. 路由類型選 **已發佈的應用程式**（Published application）。另外三種（私有主機名稱、私有 CIDR、Workers VPC）是給內部網路用的，這裡用不到。
2. 依下表填寫：

| 欄位 | 填入 | 說明 |
|---|---|---|
| 子網域（Subdomain） | `SUBDOMAIN` | 例如 `git-hooks` |
| 網域（Domain） | `DOMAIN` | 從下拉選單選擇 |
| 路徑（Path） | `^/webhooks/github$` | 正規表達式，只讓 webhook 路徑通過 |
| 服務 URL（Service URL） | `http://localhost:33444` | 開頭要寫 `http://`，**不是** `https://` |

3. 確認 **完整主機名稱** 顯示的是你的 `HOSTNAME`（例如 `git-hooks.example.com`），不是只有 `DOMAIN`。子網域留空的話，整個網域都會指到通知服務。
4. **其他應用程式設定** 不用改，按 **新增路由**。Cloudflare 會自動建立 DNS 紀錄。

**路徑一定要填。** 填了之後只有 webhook 路徑對外，其他路徑（例如 `/health`）都不會公開。

**服務 URL 要用 `http://`。** 通知服務在本機只提供 HTTP，對外的 HTTPS 由 Cloudflare 處理。填成 `https://` 會連不上，出現 `502`。

### A-3. 在伺服器上執行 cloudflared

以下兩種擇一。

**選項 1：用 Docker（專案已附範本）**

在專案的 `deploy/tunnel/` 目錄：

1. 建立 `token` 檔，內容是 A-1 複製的 Tunnel token（一行）。
2. 設定權限並啟動：

```bash
sudo chown 65532:65532 token && sudo chmod 400 token
```

```bash
docker compose up -d
```

範本使用 `network_mode: host`，cloudflared 才能連到 `localhost:33444`。

**選項 2：安裝成系統服務**

1. 依 [Cloudflare 官方下載頁](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/) 安裝 `cloudflared`。
2. 執行 A-1 頁面上的那行指令（把 `<TOKEN>` 換成你的 token）：

```bash
sudo cloudflared service install <TOKEN>
```

### A-4. 確認

跳到下方的 [驗證](#驗證)。

---

## 方式 B：指令列

在伺服器上安裝 `cloudflared`（[官方下載頁](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)），然後依序執行。

### B-1. 登入並建立 Tunnel

會開啟瀏覽器授權連結，選擇 `DOMAIN`：

```bash
cloudflared tunnel login
```

把 `TUNNEL_NAME` 換成你的值：

```bash
cloudflared tunnel create TUNNEL_NAME
```

畫面會印出兩個資訊，記下來：

- **Tunnel ID**：一串 UUID，例如 `6ff42ae2-765d-4adf-8112-31c55c1551ef`
- **Credentials file**：一個 `.json` 檔的完整路徑

### B-2. 建立 DNS 紀錄

把 `TUNNEL_NAME`、`HOSTNAME` 換成你的值：

```bash
cloudflared tunnel route dns TUNNEL_NAME HOSTNAME
```

### B-3. 建立設定檔

複製範本：

```bash
cp deploy/tunnel/config.yml.example ~/.cloudflared/config.yml
```

編輯 `~/.cloudflared/config.yml`，替換三個值：

| 範本中的文字 | 換成 |
|---|---|
| `<TUNNEL_ID>` | B-1 的 Tunnel ID |
| `<CREDENTIALS_FILE>` | B-1 的 Credentials file 路徑 |
| `<HOSTNAME>` | 你的 `HOSTNAME` |

範本已設定好只公開 `/webhooks/github`，其他路徑一律回 404。

### B-4. 測試並安裝成服務

先在前景執行，確認沒有錯誤（Ctrl+C 結束）：

```bash
cloudflared tunnel run TUNNEL_NAME
```

沒問題後安裝成系統服務，開機自動啟動：

```bash
sudo cloudflared service install
```

---

## 驗證

**1. Tunnel 狀態**：Cloudflare Zero Trust → Networks → Tunnels，你的 Tunnel 應顯示 **HEALTHY**。

**2. 從任何一台電腦測試 webhook 網址**：

```bash
curl -i -X POST https://HOSTNAME/webhooks/github
```

預期結果是 **`403`**（`forbidden`）。這代表：Tunnel 已經接通服務，而且服務正確拒絕了「不是 GitHub 的來源」（你的電腦）。

| 你看到的 | 意思 |
|---|---|
| `403 forbidden` | 正確 |
| `502` / `503` / Cloudflare 錯誤頁 | Tunnel 連不到服務：確認通知服務在執行，且服務 URL 是 `http://localhost:33444`（不是 `https://`） |
| `404` | 路徑設定錯誤，或網址少了 `/webhooks/github` |
| 連線逾時 / 找不到主機 | DNS 還沒生效（等幾分鐘），或 Tunnel 沒在執行 |

**3. `/health` 不應該對外**：

```bash
curl -i https://HOSTNAME/health
```

應該是 `404`。如果看到 `{"status":"UP"}`，表示路徑沒設定，請回去補上 `^/webhooks/github$`。

**4. 從 GitHub 測試**：建立好 GitHub App 後，到 App 設定 → **Advanced** → **Recent Deliveries**，對 `ping` 按 **Redeliver**，回應應為 **`202`**。

## 常見問題

**GitHub 送來的也是 403**
服務還沒取得 GitHub 的 IP 清單。到管理介面的 **System Status** 看 `Source verification`：顯示 `no GitHub hook ranges loaded` 時，服務會每 10 分鐘自動重試，通常很快就會恢復。

**cloudflared 跑在另一台機器，或另一個 Docker 網路**
不建議。服務只信任「從本機 33444 port 進來的連線」所帶的真實 IP。cloudflared 必須和通知服務在同一台主機，並連到 `localhost:33444`。

**可以不填路徑，整個網域都轉過來嗎？**
可以運作，但 `/health` 會對外公開。建議保留路徑。

**之前填的路徑是 `webhooks/github`（沒有 `^` 和 `$`）**
可以繼續用，GitHub 的請求一樣會通過。差別是沒有錨定，例如 `/xxx/webhooks/github` 也會被轉到服務（服務會回 `404`），想更嚴格可以改成 `^/webhooks/github$`。

**要換 Tunnel 或 token**
- 方式 A 選項 1：更新 `deploy/tunnel/token` 後執行 `docker compose up -d --force-recreate`。
- 方式 A 選項 2：執行 `sudo cloudflared service uninstall`，再用新 token 重新 `install`。
