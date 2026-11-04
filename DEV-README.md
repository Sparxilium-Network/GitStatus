# gitstatus — 開發者文件

使用者安裝與操作請看 [README.md](README.md)。AI agent / 貢獻者的工作規則請看 [AGENTS.md](AGENTS.md)。
需求來源：`git_discord_notification_system_requirements.docx`（已完成 Phase 1–4）。

## 技術選型

| 項目 | 選擇 |
|---|---|
| 語言 / 建置 | Java 25、Maven |
| 框架 | Spring Boot 4.1（`spring-boot-starter-webmvc`、`spring-boot-starter-jdbc`） |
| JSON | Jackson 3（`tools.jackson.*`，`asString()` 等新方法名） |
| Discord | JDA 6：只發送，無指令、無訊息監聽、無 privileged intent |
| TUI | Lanterna 3（只用於鍵盤輸入與游標控制，畫面為自繪純文字） |
| 資料庫 | SQLite（WAL、`busy_timeout=5000`、`transaction_mode=IMMEDIATE`）+ `JdbcTemplate`，不用 ORM |
| GitHub 認證 | 一或多個 GitHub App：RS256 JWT（自行簽章，支援 PKCS#1 / PKCS#8）→ Installation token（僅存於記憶體） |

## 執行模式

同一個 JAR、同一個資料庫：

| 模式 | 指令 | Spring profile | Web |
|---|---|---|---|
| Daemon | `java -jar gitstatus.jar run` | `daemon` | Tomcat，`server.port` |
| Admin TUI | `java -jar gitstatus.jar admin` | `admin` | 無；console log 關閉，log 寫到 `${java.io.tmpdir}/gitstatus-admin.log` |

## 架構

```
GitHub ──webhook──▶ WebhookController ─▶ WebhookService
                                          │ source IP → verify (HMAC) → dedupe (delivery_log) → normalize
                                          ▼
                                    RuleSet (AtomicReference) ◀── RuleSetHolder ◀── poll revision
                                          │ Repo × Event → Destinations
                                          ▼
                    MessageFormatter (per language) → NotificationDispatcher → DiscordGateway (JDA)
                                                       bounded queue, retry/backoff

Admin TUI ──▶ ConfigStore.mutate (transaction + revision+1) ──▶ SQLite ◀── daemon
          ──▶ GitHubProvider (installations / repositories / capabilities)
          ──▶ DiscordGateway (guilds / channels / test message)
```

## 程式結構（`com.sparxilium.gitstatus`）

| 套件 / 類別 | 職責 |
|---|---|
| `GitStatusApplication` | 解析 `run` / `admin`，設定 profile |
| `DaemonConfig` | daemon 專用 bean：dispatcher、webhook service、Discord 連線、排程（狀態回報、delivery_log 清理） |
| `config.AppSettings` | 系統設定；secret 欄位存的是檔案路徑，讀取時才開檔 |
| `db.SchemaInitializer` | 檢查資料目錄可寫入、套用 `schema.sql`、補欄位 migration、DB 檔案權限 0600 |
| `config.StartupCheckException` | 管理者可自行修正的啟動問題（Description / Action），由 `StartupCheckFailureAnalyzer` 輸出 |
| `db.ConfigStore` | 所有 SQL；設定寫入都經 `mutate` |
| `model` | `EventType`、`Language`、`NormalizedEvent`、`Records` |
| `provider` | `GitProvider`（API 端）、`WebhookAdapter`（webhook 端）介面 |
| `provider.github` | `GitHubApps`（探索已設定的 App）、`GitHubAppAuth`（JWT）、`GitHubProvider`、`GitHubWebhookAdapter`、`GitHubAppUpgrade`（舊安裝補上 App） |
| `routing` | `RuleSet`（不可變快照）、`RuleSetLoader`（載入 + 驗證）、`RuleSetHolder`（輪詢 + 原子替換） |
| `notify` | `Sanitizer`、`MessageFormatter`、`DiscordGateway`、`NotificationDispatcher` |
| `webhook` | `WebhookController`（HTTP）、`WebhookService`（流程） |
| `tui` | 純文字 Admin TUI（英文介面）；`Tui` 負責繪製與對話框，各畫面繼承 `Tui.View` |

## 資料模型

| 表 | 用途 |
|---|---|
| `configuration_state` | 單列，`revision` 供 daemon 偵測設定變更 |
| `discord_destination` | 目的地：guild / channel、`bot_profile`、`enabled`、`language`（`en` / `zh-TW`） |
| `provider_installation` | GitHub Installation；`status` = `SELECTED` / `AVAILABLE` / `REVOKED`；權限與訂閱事件 JSON |
| `repository` | Repo 快取；API 不再回傳時 `accessible = 0` |
| `subscription` | Repo 是否啟用通知 |
| `notification_rule` | Repo × Event × Destination |
| `delivery_log` | 去重 + 有限 audit（只存 metadata，預設保留 30 天） |
| `destination_health` | daemon 執行期：最近發送結果、`FAILED` 狀態 |
| `runtime_status` | daemon 心跳、已載入 revision、啟動時間、重啟要求、Discord 狀態、reload 錯誤、GitHub hook IP 清單 |

Schema 以 `CREATE TABLE IF NOT EXISTS` 建立；之後新增的欄位在 `SchemaInitializer.migrate()` 以 `ALTER TABLE ... ADD COLUMN` 補上（冪等）。

## 設定 reload

1. TUI 呼叫 `ConfigStore.mutate`：在單一 transaction 內寫入並 `revision + 1`；失敗則 rollback、revision 不變。
2. `RuleSetHolder` 每 `reload.intervalMs`（預設 5 秒）讀 revision；有變化就由 `RuleSetLoader` 以單一 SELECT 載入路由，並在前後各讀一次 revision 確認是一致快照。
3. 驗證（事件類型、Channel ID 格式）通過才 `AtomicReference.set`；失敗則保留舊規則並寫入 `runtime_status.daemon.reload_error`，同一個錯誤 revision 不會重試。
4. 只有「完全啟用」的路由會進入 `RuleSet`：Installation `SELECTED`、Repo `accessible` 且已訂閱、規則啟用、目的地啟用。被標記 `FAILED` 的目的地由 dispatcher 略過，TUI 重設後（會 bump revision）重新載入。

## 從 TUI 重啟

admin TUI 是同一個容器裡另一個 JVM，碰不到 daemon，也不掛載 Docker socket，所以重啟透過資料庫進行：

1. **Restart Service** 呼叫 `ConfigStore.requestRestart()`，把新的 UUID 寫進 `runtime_status.daemon.restart_request`。
2. `RestartWatcher` 在啟動時記下當時的 token，之後每 `reload.intervalMs` 檢查一次。token 變了就在另一條執行緒呼叫 `SpringApplication.exit`（graceful shutdown）再 `System.exit(0)`，因為關閉 context 會等 scheduler 結束，不能在 scheduler 執行緒裡做。
3. Docker 的 `restart: unless-stopped` 把容器拉起來。新的 process 啟動時看到的是同一個 token，所以不會一直重啟。`runtime_status.daemon.started_at` 記錄啟動時間，顯示在 System Status。

這只是重啟 process，bind mount、映像檔、環境變數都不會更新，這些仍要靠 `docker compose up -d --force-recreate`。

## 通知語言

- `Language` enum：`EN`（`en`）、`ZH_TW`（`zh-TW`）；未知代碼回退為 `EN`。
- 語言設定在每個 destination 上；`WebhookService` 針對同一事件，每種語言只格式化一次。
- `MessageFormatter` 的所有措辭集中在 `Texts` record（每種語言一份）；資料值（repo、分支、標題）不翻譯，且一律先經 `Sanitizer`。
- 新增語言：在 `Language` 加列舉值 → 在 `MessageFormatter` 加一份 `Texts` 並接到 `texts()` → 補 `MessageFormatterTest`。
- 伺服器端（TUI、log、錯誤訊息）一律英文。

## Webhook 來源驗證

在 HMAC 簽章之前多一道檢查：只有 GitHub 公布的 webhook 來源 IP 可以送 webhook。

1. **取得真實 IP**（`ClientIpResolver`）：只有當 TCP 對端是 `webhook.trustedProxies` 中的主機時，才採信 `webhook.clientIpHeader`（`CF-Connecting-IP`）。
   預設為 `gateway`：容器的預設 gateway，也就是經由主機 published port（使用者的 cloudflared 連進來的地方）進來的連線的對端。每 60 秒重新讀取；讀不到就不信任任何人。其他來源即使偽造這個 header 也無效。
2. **比對 GitHub hook IP**（`GitHubHookSources`）：清單來自 `GET {github.apiUrl}/meta` 的 `hooks`。
   - 有 Installation 時用 installation token 呼叫（5000 次/小時）；尚未設定 Installation 時才用未認證呼叫（每個 IP 60 次/小時，共用出口 IP 很容易用完）。
   - 預設每 6 小時更新；尚未載入任何清單時每 10 分鐘重試。
   - 清單存在 `runtime_status.github.hook_cidrs`，重啟時若 GitHub 暫時無法連線，沿用上次的清單。
   - **Fail-closed**：從未成功載入清單時，所有 webhook 都回 403。
3. 被拒絕的請求回 403、只記一行 WARN（來源 IP），不寫入 delivery_log，所以同一個 delivery id 之後從正確來源送來仍會處理。
4. `webhook.verifySource=false` 可關閉（例如本機開發，或 GHES 不提供 hook IP 清單時）。TUI 系統狀態頁會顯示 `DISABLED`。

這是縱深防禦，HMAC 簽章仍是真正的身分驗證。

## 安全設計對照

| 需求 | 實作 |
|---|---|
| SEC-001 | 來源 IP 必須在 GitHub hook 範圍內（403）；`X-Hub-Signature-256` HMAC-SHA256，`MessageDigest.isEqual` 常數時間比對（401）；兩者失敗都不寫入 delivery_log |
| SEC-002 | `(provider, provider_delivery_id)` 唯一鍵去重（`INSERT OR IGNORE`） |
| SEC-003 | Secret 只從檔案讀取；錯誤訊息只含設定名稱與路徑；Installation token 只在記憶體 |
| SEC-004 | 不保存 raw body；body 大小上限 `webhook.maxBodyBytes` |
| SEC-005 | 全域 `MessageRequest.setDefaultMentions(none)` + 每則訊息 `setAllowedMentions(none)` |
| SEC-006 | `Sanitizer`：移除控制字元、跳脫 Markdown、中和 mention、依 code point 截斷 |
| SEC-007 | `NormalizedEvent` 只帶白名單欄位；URL 只允許 https |
| SEC-008 | HTTP 只有 `POST /webhooks/{provider}`、`GET /health`；Discord 無指令 |
| SEC-009 | DB 檔案 0600；容器以 uid 10001 執行；secrets 唯讀掛載 |

## 建置與測試

```bash
mvn test
```

```bash
mvn -DskipTests package
```

測試：

| 測試 | 涵蓋 |
|---|---|
| `SanitizerTest` | mention、控制字元、Markdown、截斷、URL |
| `MessageFormatterTest` | 英文 / 繁中措辭、各語言皆經脫敏 |
| `GitHubAppAuthTest` | PKCS#1 / PKCS#8 金鑰、JWT 可被公鑰驗證 |
| `GitHubWebhookAdapterTest` | 簽章、各事件正規化、form-encoded payload |
| `SchemaMigrationTest` | 舊資料庫補上 `language` 欄位 |
| `DatabaseWritableCheckTest` | 資料目錄自動建立、無法建立或唯讀時回報 `StartupCheckException` |
| `AppSettingsTest` | `setupProblems()` 列出缺少的設定與 secret，不含內容 |
| `TuiTest` | 虛擬終端機：畫面從左上角繪製、游標與 `[*]` 勾選、長清單捲動、選擇 / 確認 / 輸入對話框、輸入結束時停止 |
| `ClientIpResolverTest` | CIDR 比對（v4 / v6）、`/proc/net/route` gateway 解析、只信任 trusted proxy 的 header、不做 DNS 誤判 |
| `WebhookPipelineTest` | 真實 SQLite 端到端：路由、多目的地、各自語言、去重、錯誤簽章、來源驗證（含 fail-closed 與沿用已存清單）、reload、停用 / 未訂閱 / 撤銷授權 |

## 本機開發

用 `GITSTATUS_CONFIG` 指定設定檔（預設 `/config/config.conf`）。Windows 上設定檔中的路徑要寫 `C:/...`，不要用 Git Bash 的 `/c/...`。

```bash
GITSTATUS_CONFIG=C:/path/to/config.conf java -jar target/gitstatus.jar run
```

Admin TUI 在 Windows 上會開 Swing 終端模擬視窗；在 Linux / Docker 內使用真實終端機（需要 TTY）。

TUI 畫面是逐行從左上角輸出的純文字（`=====` 分隔線、`>` 游標、`[*]` / `[ ]` 勾選、底部按鍵說明），不使用 Lanterna 的視窗元件，也不依賴終端機回報的畫面大小。透過 SSH 時有些終端機回報的大小不正確，舊版置中視窗會畫在可見範圍外而看起來一片空白。清單超過 15 列時隨游標捲動。

事件設定畫面（`RepoEventsScreen`）把分類也列成一列：分類列的勾選框是 `[*]` 全開、`[-]` 部分、`[ ]` 全關；在分類列按 Space 時，若可開啟的事件還沒全開就全部打開，否則整個分類關閉。無法開啟的事件（權限不足、未訂閱、尚未支援）不會被分類勾選打開。使用者說明在 `docs/ADMIN-TUI.md`。

## Log

格式由 `application.properties` 的 `logging.pattern.console` / `logging.pattern.file` 設定：

```
[2026-10-30 20:29:56 WARN]: Rejected github webhook delivery=... invalid signature
```

- 時間使用 JVM 時區；Docker 容器預設為 UTC，需要本地時間可在 compose 設定 `TZ` 環境變數。
- Daemon 輸出到 console（`docker compose logs`）；Admin TUI 不輸出到 console，寫到 `${java.io.tmpdir}/gitstatus-admin.log`。
- Log 內容規範見 AGENTS.md：只記 metadata，不記 payload 與 secret。

### 啟動檢查

- SQLite 開檔失敗時只會回報 `unable to open database file`。所以 `SchemaInitializer` 在連線前先檢查資料目錄（以及既有的 `bot.db`、`-wal`、`-shm`）是否可寫入。不可寫入時丟出 `StartupCheckException`，由 `StartupCheckFailureAnalyzer`（註冊於 `META-INF/spring.factories`）輸出 Spring Boot 的 `APPLICATION FAILED TO START` 報告：Description 寫路徑、擁有者 uid 與執行使用者，Action 指向 `sudo ./setup.sh`。
- Admin 模式關閉了 console log，所以 `GitStatusApplication.runAdmin` 會把啟動失敗直接印到 stderr（`StartupCheckException` 印 Description / Action，其他錯誤印訊息與 log 檔路徑）。
- Daemon 啟動完成後，`GitHubApps.setupProblems()` 與 `AppSettings.setupProblems()` 列出的問題（沒有任何 App、App 目錄名稱不是數字、secret 檔不存在 / 無法讀取 / 為空）會以 `Setup:` 開頭的 WARN 記錄，只寫設定名稱與路徑。這些問題不會阻止啟動。

## 多個 GitHub App

私人 GitHub App 只能安裝在擁有它的帳號上，所以要涵蓋多個 Organization 就需要每個 Organization 一個 App。

- **設定位置**：`github.appsDir`（預設 `/run/secrets/github-apps`）底下每個 App 一個子目錄，目錄名稱就是 App ID，內含 `private-key.pem` 與 `webhook-secret`。`GitHubApps` 每次呼叫時重新掃描目錄，所以新增 App 不需要重啟。舊版的 `github.appId` / `github.privateKey` / `github.webhookSecret` 在 `github.appId` 有值時仍算一個 App；同 ID 的目錄優先。
- **資料**：`provider_installation.app_id` 記錄安裝屬於哪個 App。`ConfigStore.syncInstallations(provider, appId, …)` 只會把**同一個 App** 不再回傳的安裝標成 `REVOKED`，同步某個 App 不會影響其他 App。Installation token 用安裝所屬 App 的私鑰換取；私鑰檔修改時間變了就重新載入（換 key 不必重啟）。
- **升級**：舊資料庫的安裝 `app_id` 為 NULL。`GitHubAppUpgrade` 在啟動時若只設定一個 App，就把它們全部歸給該 App；有多個 App 時記 WARN，等 TUI 的 **Git Provider Sources** 重新同步時補上。`app_id` 為 NULL 的安裝不會產生任何路由。
- **Webhook 驗證**：所有 App 共用 `/webhooks/github`。GitHub 對 App webhook 會帶 `X-GitHub-Hook-Installation-Target-Type: integration` 與 `X-GitHub-Hook-Installation-Target-ID: <App ID>`。這個 header 沒有簽章，只用來挑選要比對哪個 App 的 secret，HMAC 必須和那個 App 的 secret 相符；ID 不在設定中就拒絕（401）。沒有這個 header 時逐一嘗試每個 App 的 secret，必須剛好一個相符（多個 App 共用同一個 secret 時無法判斷，拒絕）。
- **路由**：`RuleSet.RepoKey` 包含 `appId`。`WebhookAdapter.authenticate` 回傳簽章所屬的 App ID，路由時只會比對屬於該 App 的安裝；用 App A 的 secret 簽的 delivery 即使 payload 寫的是 App B 的 installation id，也不會送出（`NO_ROUTE`）。
- **TUI**：Sources 每個 App 各同步一次並顯示各自的結果；Status 逐一檢查每個 App 的認證；同一個 Repository 透過兩個以上的安裝可見時，在 Repository 清單以 `*` 標示（兩邊都啟用會重複通知）。

## 部署架構

推薦使用者自行架設的 Cloudflare Tunnel；使用者教學在 [docs/CLOUDFLARE-TUNNEL.md](docs/CLOUDFLARE-TUNNEL.md)。

```
GitHub → Cloudflare → 使用者的 cloudflared（主機上）→ 127.0.0.1:33444 → docker-proxy → bot:33444
```

- `deploy/docker-compose.yml` 只 publish `127.0.0.1:33444:33444`。Docker publish 的 port 會繞過 ufw / firewalld，所以一定要保留 `127.0.0.1:` 前綴。
- 經由 published port 進來的連線，在容器內看到的對端都是**容器的預設 gateway**（docker-proxy 或 hairpin NAT）。所以 `webhook.trustedProxies` 預設為 `gateway`：從 `/proc/net/route` 讀出預設 gateway，只信任它送來的 `CF-Connecting-IP`。同一個 Docker 網路上的其他容器無法偽造。
- cloudflared 必須和 bot 在同一台主機，並連到 `localhost:33444`（系統服務，或 Docker `network_mode: host`）。
- 教學附的範本：
  - `deploy/tunnel/docker-compose.yml`：以 Docker 執行 remotely-managed tunnel（token 檔，uid 65532，固定版本 `2026.10.6`，`--no-autoupdate`）。
  - `deploy/tunnel/config.yml.example`：指令列方式的 ingress，只公開 `^/webhooks/github$`，其他路徑回 404。
- `deploy/tunnel/token`、`*.json`、`config.yml` 已列入 `.gitignore`。

不推薦自行開 port 或用其他反向代理對外。若一定要這樣做，把 `webhook.trustedProxies` 改成該代理的位址、`webhook.clientIpHeader` 改成它送出的真實 IP header；否則來源驗證拿到的是代理的 IP，所有 webhook 都會被 403 拒絕（fail-closed）。

本機開發不經過 Tunnel 時，用 `webhook.verifySource=false` 關閉來源驗證。

## Docker

- Multi-stage build：`maven:3.10-eclipse-temurin-25` → `eclipse-temurin:25-jre`。
- ENTRYPOINT 帶 `-XX:MaxRAMPercentage=40`，保留空間給 `docker compose exec` 啟動的 TUI JVM（預設 25%）。不使用 `JAVA_TOOL_OPTIONS`，避免 JVM 在 TUI 畫面印出 "Picked up" 訊息。
- `HEALTHCHECK` 以 bash `/dev/tcp` 呼叫 `/health`。
- JAR manifest 含 `Enable-Native-Access: ALL-UNNAMED`（sqlite-jdbc 載入 native library）。

### `deploy/setup.sh`

使用者的設定精靈（需 root，非 root 時自動以 `sudo` 重新執行）：

0. `config/config.conf`：不存在時從 `config.conf.example` 建立；沒有 `config.version` 時在開頭補上 `config.version=1`；版本比腳本的 `CONFIG_VERSION` 新時中止；較舊時依序執行 `upgrade` 步驟（`case $config_version in N) ...`）並更新版本號。
1. 升級：若有舊版的 `secrets/github-app.pem` / `secrets/github-webhook-secret`，搬到 `secrets/github-apps/<App ID>/`（App ID 取自 `config.conf` 的 `github.appId`，沒有就詢問）。
   - 模式選擇：已安裝（每個 App 都有 key 和 secret，而且有 Discord token）時，顯示 `1) Upgrade`（預設）/ `2) Setup`。Upgrade 跳過步驟 2、3，直接修正權限並重建啟動，不再詢問。`sudo ./setup.sh upgrade` 不顯示選單直接 Upgrade；尚未安裝時以這個參數執行會中止。腳本不執行 `git pull`。
2. GitHub Apps：沒有任何 App 時直接新增一個；已有 App 時列出狀態，可選擇更換其中一個的 key / secret。之後重複詢問 `Add another GitHub App?`。每個 App：
   - App ID 必須是數字（輸入 `Iv...` 時提示那是 Client ID）。
   - Private key 只接受檔案：搜尋 `deploy/` 目錄（不含子目錄）的 `*.pem`。一個時請使用者確認，多個時選編號，沒有時可重新搜尋或輸入路徑。檢查內容含 `PRIVATE KEY-----` 後以 `install -m 400` 複製到 `secrets/github-apps/<App ID>/private-key.pem`，預設刪除原檔。`deploy/*.pem` 已列入 `.gitignore`。
   - Webhook secret 以 `read -s` 輸入兩次；留空時從 `/dev/urandom` 產生 32 bytes hex 並顯示一次。
3. Discord token 以 `read -s` 輸入，檢查三段式格式（不符時可確認後仍使用）。
4. `chown -R 10001:10001 data secrets`、`data` 與 `secrets` 底下所有目錄 700、所有檔案 400、`config.conf` 644，並列出各 App 的狀態。
5. 選擇啟動時執行 `docker compose up -d --build --force-recreate`（bind mount 的檔案變更需要重建容器），最多等 90 秒 `/health`；失敗時印出最近 40 行 log。

Secret 以 `printf '%s'` 寫入（無換行、`umask 077`），不出現在指令列參數或 shell history。重複執行時已存在的檔案預設保留，所以也可以當作「修正權限」使用。

不使用精靈時的手動做法：目錄結構與權限見 `deploy/docker-compose.yml` 開頭的註解。

## 設定檔（`config.conf`）

`deploy/config/config.conf` 不受 git 管理（`.gitignore`），由 `setup.sh` 從受管理的 `deploy/config/config.conf.example` 建立，所以使用者的修改不會讓 `git pull` 衝突。

| Key | 預設 | 說明 |
|---|---|---|
| `config.version` | | 檔案格式版本（目前 `1`）。見下方「版本」 |
| `server.port` | `33444` | 容器內監聽的 port；Dockerfile 的 EXPOSE 與 HEALTHCHECK 也使用 33444 |
| `database` | `/data/bot.db` | |
| `github.appsDir` | `/run/secrets/github-apps` | 每個 GitHub App 一個子目錄（見「多個 GitHub App」） |
| `github.appId` | | 舊版單一 App 設定；有值時仍可使用 |
| `github.privateKey` | `/run/secrets/github-app.pem` | 舊版單一 App 的私鑰檔路徑 |
| `github.webhookSecret` | `/run/secrets/github-webhook-secret` | 舊版單一 App 的 webhook secret 檔路徑 |
| `github.apiUrl` | `https://api.github.com` | GHES：`https://<host>/api/v3` |
| `discord.token` | `/run/secrets/discord-token` | 檔案路徑 |
| `reload.intervalMs` | `5000` | |
| `webhook.maxBodyBytes` | `10485760` | |
| `webhook.verifySource` | `true` | 只允許 GitHub hook IP |
| `webhook.sourceRefreshMs` | `21600000` | GitHub hook IP 清單更新間隔（6 小時） |
| `webhook.clientIpHeader` | `CF-Connecting-IP` | 真實 IP header |
| `webhook.trustedProxies` | `gateway` | 可信任的代理：`gateway`（容器預設 gateway）、主機名稱或 CIDR，逗號分隔 |
| `deliveryLog.retentionDays` | `30` | |

## 版本

設定有兩個版本號，都只在格式改變時增加：

| 對象 | 存放位置 | 程式中的版本 | 升級者 |
|---|---|---|---|
| `config.conf` | `config.version=N` | `ConfigVersionCheck.CURRENT`、`setup.sh` 的 `CONFIG_VERSION` | `setup.sh` 的升級迴圈 |
| SQLite schema | `PRAGMA user_version` | `SchemaInitializer.SCHEMA_VERSION` | `SchemaInitializer.migrate` 啟動時自動執行 |

- 版本比程式支援的新：丟出 `StartupCheckException`，拒絕啟動（避免舊版用看不懂的設定或 schema 執行）。
- `config.version` 缺少：視為版本 1 之前的檔案，照常啟動並記 `Setup:` WARN，提醒執行 `setup.sh`。新增的設定在 `application.properties` 一律要有預設值，舊檔案才能繼續使用。
- 資料庫 `user_version` 為 0（版本化之前建立）：套用 `from < 1` 的補欄位步驟後設為 1。

**新增或更名 config 設定**：`config.conf.example` 與 `application.properties`（預設值）加入設定 → `ConfigVersionCheck.CURRENT` 與 `setup.sh` 的 `CONFIG_VERSION` 加 1 → 在 `setup.sh` 的 `case` 加入從舊版本升級的步驟（就地修改使用者的檔案）→ 更新上方表格。

**變更 schema**：`schema.sql`（新資料庫）→ `SCHEMA_VERSION` 加 1 → `migrate` 加入 `if (from < N)` 步驟（既有資料庫）→ 在 `SchemaMigrationTest` 補測試。

## 擴充

- **新增事件**：`EventType` 加列舉值 → provider adapter 正規化 → `GitHubProvider.capabilities` 對應權限 → `MessageFormatter` 各語言措辭 → 測試。
- **新增 provider（GitLab）**：實作 `GitProvider` 與 `WebhookAdapter` 並註冊為 Spring bean；路由與發送層不需修改。

## 尚未實作

- Phase 5：Security 事件（Dependabot / Code scanning）、GitLab Adapter。
- Phase 6：外部 Queue、Multi-bot（`bot_profile` 已預留，目前只支援 `default`）。
- Deployment 事件、加密的 raw payload 除錯模式（SEC-004 選配）、replay timestamp window（SEC-002 選配）。
