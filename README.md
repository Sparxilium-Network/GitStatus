# gitstatus — GitHub 通知到 Discord

把 GitHub 上發生的事（Push、Pull Request、GitHub Actions 結果、Release）自動通知到你指定的 Discord 頻道。

- 每個 Repository 可以分別選擇要通知哪些事件、送到哪些頻道。
- 每個 Discord 頻道可以選擇通知語言：**繁體中文**或 **English**。
- 支援 Private Repository；只會看到你授權給 GitHub App 的 Repository。
- 所有設定都在伺服器上完成，Discord 端沒有任何指令或按鈕，別人無法從 Discord 改設定。

> 開發與技術細節請看 [DEV-README.md](DEV-README.md)。

## 通知長什麼樣子

| 事件 | English | 繁體中文 |
|---|---|---|
| Push | `[acme/api] Push to main` | `[acme/api] 推送到 main` |
| Pull Request | `[acme/api] PR #5 merged` | `[acme/api] PR #5 已合併` |
| GitHub Actions | `[acme/api] CI #12 failed` | `[acme/api] CI #12 失敗` |
| Release | `[acme/api] Release v1.2.0 published` | `[acme/api] 版本 v1.2.0 已發布` |

每則通知都附上 GitHub 連結。Commit 或 PR 標題裡的 `@everyone`、`@here` 不會觸發提及。

## 你需要準備

- 一台可以執行 Docker 的 Linux 伺服器，並有一個 HTTPS 網址可以讓 GitHub 連進來（例如透過 Nginx / Caddy 反向代理）。
- GitHub 帳號或 Organization 的管理權限（用來建立 GitHub App）。
- Discord Server 的管理權限（用來邀請 Bot）。

## 安裝步驟

### 1. 建立 GitHub App

到 GitHub → Settings → Developer settings → GitHub Apps → New GitHub App：

- **Webhook URL**：`https://<你的網址>/webhooks/github`
- **Webhook secret**：自訂一串隨機字串，等一下要用。
- **Repository permissions**（全部選 **Read-only**）：Metadata、Contents、Actions、Pull requests
- **Subscribe to events**：Push、Pull request、Workflow run、Release
- 建立後記下 **App ID**，並產生、下載 **Private key**（`.pem` 檔）。
- 到 App 頁面按 **Install App**，安裝到你的帳號或 Organization，只勾選需要通知的 Repository。

### 2. 建立 Discord Bot

到 [Discord Developer Portal](https://discord.com/developers/applications) → New Application → Bot：

- 複製 **Bot Token**。
- 不需要開啟任何 Privileged Gateway Intents。
- 用 OAuth2 URL Generator 產生邀請連結：Scope 只勾 `bot`，權限勾 **View Channels**、**Send Messages**、**Embed Links**。
- 用連結把 Bot 邀請到你的 Discord Server。

### 3. 在伺服器上準備檔案

把專案原始碼放到伺服器上（例如 `/opt/git-notifier`），之後的操作都在其中的 `deploy/` 目錄進行：

```
/opt/git-notifier/deploy/
├── docker-compose.yml        ← 已附上，通常不需修改
├── config/config.conf        ← 填入步驟 1 的 App ID
├── data/                     ← 自行建立，資料庫會放在這裡
└── secrets/                  ← 自行建立
    ├── github-app.pem         ← 步驟 1 下載的 Private key
    ├── github-webhook-secret  ← 步驟 1 自訂的 Webhook secret
    └── discord-token          ← 步驟 2 的 Bot Token
```

在 `deploy/` 目錄設定檔案權限：

```bash
sudo chown -R 10001:10001 data secrets && sudo chmod 700 data secrets && sudo chmod 400 secrets/*
```

### 4. 啟動

第一次啟動會從原始碼建置 image（需要幾分鐘）：

```bash
docker compose up -d --build
```

確認服務正常：

```bash
curl http://127.0.0.1:8080/health
```

看到 `{"status":"UP"}` 就代表啟動成功。接著設定反向代理，把 `https://<你的網址>/webhooks/github` 轉到 `127.0.0.1:8080`。

## 設定通知

SSH 登入伺服器，在 `deploy/` 目錄執行：

```bash
docker compose exec bot java -jar /app/gitstatus.jar admin
```

會出現一個英文的設定畫面，用方向鍵選擇、Enter 確認、Esc 返回。依序完成：

1. **Discord Destinations**：新增要收通知的頻道。
   - 從 Bot 已加入的 Server 與頻道中挑選，並為它取個名字。
   - 選擇這個頻道的通知語言：`Traditional Chinese (zh-TW)` 或 `English (en)`。
   - 建議先用 **Send test message** 確認 Bot 能在頻道發訊息。
2. **Git Provider Sources**：按 Space 勾選要通知的 GitHub 帳號或 Organization。
3. **Repositories / Notification Rules**：選擇帳號後會列出 Repository。
   - 按 **Space** 啟用該 Repository 的通知。
   - 按 **Enter** 進入事件設定：用 **Left/Right** 切換頻道，**Space** 勾選要通知的事件，最後按 **S** 儲存。
4. **System Status**：檢查 GitHub、Discord 連線狀態，以及最近一次收到的通知。

設定儲存後約 5 秒內自動生效，不需要重新啟動。設定完選 **Exit** 離開即可。

### 修改頻道的通知語言

在 **Discord Destinations** 選擇該頻道 → **Notification language** → 選擇新的語言。

## 常見問題

**事件旁邊顯示 `unavailable / insufficient permission`**
GitHub App 沒有這個事件需要的權限。回到 App 設定加上權限（Read-only），並在安裝頁面接受新的權限。

**顯示 `unavailable / App not subscribed to this webhook event`**
GitHub App 的 Subscribe to events 沒有勾選這個事件。

**看不到某個 Repository**
GitHub App 沒有被授權存取它。到 GitHub 的 App 安裝設定中把它加進去，再回到設定畫面重新進入該帳號。

**頻道收不到通知**
到 **System Status** 查看該頻道的發送結果。如果顯示 `FAILED`，通常是 Bot 被移出頻道或缺少權限；修正後在 **Discord Destinations** 選擇該頻道 → **Reset failure state**。

**Discord 顯示 `ERROR ... discord-token`**
`secrets/discord-token` 不存在或無法讀取，請檢查檔案與權限。

## 更新版本

取得新版原始碼後重新建置並啟動：

```bash
docker compose up -d --build
```

更新時資料庫會自動升級，既有設定都會保留。
