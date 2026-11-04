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

## 推薦的部署方式：Cloudflare Tunnel

**請透過你自己的 Cloudflare Tunnel 讓 GitHub 連進來，不要把服務的 port 直接開放到網路上。**

```
GitHub ──HTTPS──▶ Cloudflare ──Tunnel──▶ 你的 cloudflared ──▶ http://localhost:33444（通知服務）
                                         （伺服器只往外連，不開任何 port）
```

- 伺服器不需要開放任何連入的 port，防火牆可以全部關閉，也不會暴露伺服器的真實 IP。
- HTTPS 憑證由 Cloudflare 處理，不用自己架 Nginx / Caddy。
- 服務會做**來源驗證**：只接受 GitHub 官方公布的 webhook IP，再加上簽章驗證，其他來源一律拒絕。
- 通知服務預設只在本機的 `127.0.0.1:33444` 監聽，Tunnel 指向這個位址。

Tunnel 由你自己建立，照著 [Cloudflare Tunnel 設定教學](docs/CLOUDFLARE-TUNNEL.md) 填入網域與 token 即可完成。

> 不建議自己開 port 或用反向代理直接對外：伺服器 IP 會曝光、要自己維護 TLS 與防火牆。

## 你需要準備

- 一台可以執行 Docker 的 Linux 伺服器（只需要能連外網）。
- 一個由 Cloudflare 管理 DNS 的網域（Cloudflare 免費方案即可）。
- GitHub 帳號或 Organization 的管理權限（用來建立 GitHub App）。
- Discord Server 的管理權限（用來邀請 Bot）。

## 安裝步驟

### 1. 決定 Webhook 網址

先決定要用哪個子網域收 webhook，例如 `git-hooks.example.com`。你的 Webhook 網址就是：

```
https://git-hooks.example.com/webhooks/github
```

### 2. 建立 GitHub App

到 GitHub → Settings → Developer settings → GitHub Apps → New GitHub App：

- **Webhook URL**：步驟 1 的網址
- **Webhook secret**：自訂一串隨機字串，等一下要用。也可以先留空，步驟 4 的 `setup.sh` 會幫你產生一組，再貼回這裡。
- **Repository permissions**（全部選 **Read-only**）：Metadata、Contents、Actions、Pull requests
- **Subscribe to events**：Push、Pull request、Workflow run、Release
- 建立後在 App 設定頁的 **General**，記下 **About** 區塊的 **App ID**（一串數字，例如 `1234567`）。不是下面那個 `Iv23...` 開頭的 Client ID。
- 產生 Private key：在 App 設定頁的**左側選單**點 **Credentials** → **Key pairs** → **New key**，瀏覽器會下載一個 `.pem` 檔。之後要用的是**這個檔案**；頁面上顯示的 `SHA256:...` 只是指紋，不是金鑰。（舊版 GitHub 介面是在 General 頁的 **Private keys** → **Generate a private key**。）
- 到 App 頁面按 **Install App**，安裝到你的帳號或 Organization，只勾選需要通知的 Repository。

> **要通知多個 Organization？** 私人（Only on this account）的 GitHub App 只能安裝在擁有它的帳號上。請在**每個** Organization 各建立一個 App（設定同上，Webhook URL 都填同一個），之後在設定精靈逐一加入。每個 App 的 Webhook secret 請用不同的字串。

### 3. 建立 Discord Bot

到 [Discord Developer Portal](https://discord.com/developers/applications) → New Application → Bot：

- 複製 **Bot Token**。
- 不需要開啟任何 Privileged Gateway Intents。
- 用 OAuth2 URL Generator 產生邀請連結：Scope 只勾 `bot`，權限勾 **View Channels**、**Send Messages**、**Embed Links**。
- 用連結把 Bot 邀請到你的 Discord Server。

### 4. 在伺服器上執行設定精靈

在伺服器上取得專案原始碼：

```bash
git clone https://github.com/Sparxilium-Network/GitStatus.git
```

把步驟 2 下載的 `.pem` 檔放進伺服器上的 `GitStatus/deploy/` 目錄，例如在你的電腦上執行（換成你的檔名、帳號與伺服器位址）：

```bash
scp ~/Downloads/your-app.2026-11-05.private-key.pem user@your-server:~/GitStatus/deploy/
```

進入 `deploy/` 目錄執行設定精靈：

```bash
cd GitStatus/deploy && sudo ./setup.sh
```

精靈會依序詢問：

| 步驟 | 要輸入的內容 |
|---|---|
| 1. GitHub Apps | 每個 App 依序輸入：<br>• **App ID**：步驟 2 記下的數字<br>• **Private key**：精靈會在 `deploy/` 目錄找 `.pem` 檔並詢問 `Is this the correct .pem file you want to use?`，按 Enter 確認。有多個時選編號；還沒放進來的話，放好後按 Enter 重新搜尋，或直接輸入檔案路徑。複製後預設刪除原檔<br>• **Webhook secret**：步驟 2 自訂的字串（輸入兩次確認）。GitHub 之後不會再顯示它；忘記的話留空，精靈會產生一組新的，再到 App 設定的 **Webhook** 按 **Change secret** 貼上並 **Save changes**<br>最後會問 `Add another GitHub App?`，有其他 Organization 的 App 就回答 `y` 繼續加入 |
| 2. Discord Bot Token | 步驟 3 複製的 Token |

每一步都會先顯示這個值在 GitHub / Discord 的哪裡取得。輸入 secret 與 Token 時畫面不會顯示，也不會留在指令歷史紀錄裡。

接著精靈會自動設定好檔案擁有者與權限，並詢問是否啟動服務。選擇啟動後會建置 image（第一次需要幾分鐘），並等到服務正常運作才結束。看到 `OK  The service is running.` 就完成了。

之後要新增 GitHub App、更換 Token 或 secret、或遇到權限問題，再執行一次 `sudo ./setup.sh` 即可；要保留的項目直接按 Enter。新增 App 後不需要重新啟動服務，到設定畫面的 **Git Provider Sources** 就會看到它的安裝。

從只支援單一 App 的舊版升級時，執行 `sudo ./setup.sh` 會自動把原本的 `secrets/github-app.pem` 與 `secrets/github-webhook-secret` 搬到新的位置，原本的設定照常運作。

### 5. 確認服務正常

在伺服器上執行：

```bash
curl http://127.0.0.1:33444/health
```

看到 `{"status":"UP"}` 就代表服務正常。

### 6. 設定 Cloudflare Tunnel

依照 [Cloudflare Tunnel 設定教學](docs/CLOUDFLARE-TUNNEL.md) 建立 Tunnel：

- 路由類型：**已發佈的應用程式**（Published application）
- **子網域 / 網域**：步驟 1 的網址
- **路徑**：`^/webhooks/github$`
- **服務 URL**：`http://localhost:33444`

教學最後的「驗證」會帶你確認 Tunnel 已接通，以及 GitHub 重送的 `ping` 回應為 `202`。

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
   - 按 **Enter** 進入事件設定：用 **Left/Right** 切換頻道，**Space** 勾選要通知的事件（在分類那一列按可以一次開關整個分類），最後按 **S** 儲存。
4. **System Status**：檢查 GitHub、Discord 連線狀態，以及最近一次收到的通知。

設定儲存後約 5 秒內自動生效，不需要重新啟動。設定完選 **Exit** 離開即可。

每個畫面的詳細說明、通知送出的條件，以及沒收到通知時怎麼排查，請看 [設定畫面使用說明](docs/ADMIN-TUI.md)。

### 修改頻道的通知語言

在 **Discord Destinations** 選擇該頻道 → **Notification language** → 選擇新的語言。

## 常見問題

**`curl http://127.0.0.1:33444/health` 連不到（`Could not connect`）**
服務剛啟動需要 10–20 秒；稍等再試。仍然失敗時查看 log：

```bash
docker compose logs --tail=40 bot
```

- 看到 `APPLICATION FAILED TO START`：照下方 `Action:` 的說明處理。最常見的是 `/data ... is not writable`，代表 `data/` 目錄權限不對（例如還沒執行 `setup.sh` 就先啟動，Docker 自動建立的目錄屬於 root）。執行 `sudo ./setup.sh` 修正即可。
- 看到 `Setup: ... is not configured` 或 `secret file is not readable`：設定或 secret 檔缺少、權限不對，執行 `sudo ./setup.sh`。

**事件旁邊顯示 `unavailable / insufficient permission`**
GitHub App 沒有這個事件需要的權限。回到 App 設定加上權限（Read-only），並在安裝頁面接受新的權限。

**顯示 `unavailable / App not subscribed to this webhook event`**
GitHub App 的 Subscribe to events 沒有勾選這個事件。

**看不到某個 Repository**
GitHub App 沒有被授權存取它。到 GitHub 的 App 安裝設定中把它加進去，再回到設定畫面重新進入該帳號。

**頻道收不到通知**
到 **System Status** 查看該頻道的發送結果。如果顯示 `FAILED`，通常是 Bot 被移出頻道或缺少權限；修正後在 **Discord Destinations** 選擇該頻道 → **Reset failure state**。

**Discord 顯示 `ERROR ... discord-token`**
`secrets/discord-token` 不存在或無法讀取。在 `deploy/` 目錄執行 `sudo ./setup.sh` 重新輸入 Token。

**GitHub 的 Recent Deliveries 顯示 `403`**
請求沒有通過來源驗證。到 **System Status** 看 `Source verification` 那一行：
- 顯示 `no GitHub hook ranges loaded`：服務還沒取得 GitHub 的 IP 清單（剛啟動或暫時連不到 GitHub）。服務會每 10 分鐘自動重試；在「設定通知」完成 **Git Provider Sources** 後，取得清單會更穩定。
- 有顯示清單數量：請確認 cloudflared 和通知服務在同一台主機，並連到 `localhost:33444`。

**GitHub 的 Recent Deliveries 顯示 `401`**
Webhook secret 不一致。確認 `secrets/github-apps/<App ID>/webhook-secret` 與**同一個** GitHub App 設定的完全相同（檔案結尾的換行不影響）；也確認這個 App 已經用 `setup.sh` 加入。

**同一個 Repository 收到兩次通知**
同一個 Organization 裝了兩個 App，而且兩邊都能存取這個 Repository。Repository 清單中會以 `*` 標示；只在其中一個來源啟用它即可。

**Tunnel 相關問題**
請看 [Cloudflare Tunnel 設定教學](docs/CLOUDFLARE-TUNNEL.md) 的「驗證」與「常見問題」。

## 進一步加強（選用）

- **SSH 也走 Cloudflare Tunnel 並用 Cloudflare Access 保護**：設定權限等於 SSH 權限，這樣可以把 22 port 也關掉，並在登入前多一層身分驗證（例如公司帳號 + MFA）。
- **Cloudflare WAF 規則**：在 Cloudflare 擋掉非 GitHub IP 的請求，讓無效流量在進到伺服器之前就被擋下。服務本身已會驗證來源，這只是減少雜訊。

## 更新版本

在 `deploy/` 目錄取得新版原始碼，執行設定精靈（會把 `config/config.conf` 升級到新版格式，要保留的項目直接按 Enter），再重新建置並啟動：

```bash
git pull && sudo ./setup.sh
```

精靈最後選擇啟動即可；不想跑精靈時也可以直接 `docker compose up -d --build`，舊格式的設定檔仍可使用，只是 log 會提醒你執行 `setup.sh`。資料庫會自動升級，既有設定都會保留。

`config/config.conf` 與資料庫都有版本號。如果它們是由更新的版本寫入的（例如更新後又退回舊版），服務會拒絕啟動並說明原因，不會用看不懂的設定繼續執行。

**從 2026-11 以前的版本更新（只需要做一次）**：舊版的 `config/config.conf` 由 git 管理，設定精靈寫入後會讓 `git pull` 失敗（`Your local changes ... would be overwritten`）。新版改由 `config.conf.example` 範本產生，你的設定檔不再受 git 管理。請先備份再更新：

```bash
cp config/config.conf ~/config.conf.bak && git checkout -- config/config.conf
```

```bash
git pull && cp ~/config.conf.bak config/config.conf && sudo ./setup.sh
```

**`sed: read error on config/config.conf: Is a directory`**：`config/config.conf` 還不存在時就執行了 `docker compose up`，Docker 會把它建立成一個**空目錄**。新版 `setup.sh` 會自動移除這個目錄並重新建立設定檔；舊版請手動處理：

```bash
sudo rm -rf config/config.conf && sudo ./setup.sh
```

如果有照上面的步驟備份過 `~/config.conf.bak`，先用 `cp ~/config.conf.bak config/config.conf` 放回去再執行 `setup.sh`。
