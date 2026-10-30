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
| TUI | Lanterna 3 |
| 資料庫 | SQLite（WAL、`busy_timeout=5000`、`transaction_mode=IMMEDIATE`）+ `JdbcTemplate`，不用 ORM |
| GitHub 認證 | GitHub App：RS256 JWT（自行簽章，支援 PKCS#1 / PKCS#8）→ Installation token（僅存於記憶體） |

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
| `db.SchemaInitializer` | 套用 `schema.sql`、補欄位 migration、DB 檔案權限 0600 |
| `db.ConfigStore` | 所有 SQL；設定寫入都經 `mutate` |
| `model` | `EventType`、`Language`、`NormalizedEvent`、`Records` |
| `provider` | `GitProvider`（API 端）、`WebhookAdapter`（webhook 端）介面 |
| `provider.github` | `GitHubAppAuth`（JWT）、`GitHubProvider`、`GitHubWebhookAdapter` |
| `routing` | `RuleSet`（不可變快照）、`RuleSetLoader`（載入 + 驗證）、`RuleSetHolder`（輪詢 + 原子替換） |
| `notify` | `Sanitizer`、`MessageFormatter`、`DiscordGateway`、`NotificationDispatcher` |
| `webhook` | `WebhookController`（HTTP）、`WebhookService`（流程） |
| `tui` | Lanterna 畫面（英文介面） |

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
| `runtime_status` | daemon 心跳、已載入 revision、Discord 狀態、reload 錯誤、GitHub hook IP 清單 |

Schema 以 `CREATE TABLE IF NOT EXISTS` 建立；之後新增的欄位在 `SchemaInitializer.migrate()` 以 `ALTER TABLE ... ADD COLUMN` 補上（冪等）。

## 設定 reload

1. TUI 呼叫 `ConfigStore.mutate`：在單一 transaction 內寫入並 `revision + 1`；失敗則 rollback、revision 不變。
2. `RuleSetHolder` 每 `reload.intervalMs`（預設 5 秒）讀 revision；有變化就由 `RuleSetLoader` 以單一 SELECT 載入路由，並在前後各讀一次 revision 確認是一致快照。
3. 驗證（事件類型、Channel ID 格式）通過才 `AtomicReference.set`；失敗則保留舊規則並寫入 `runtime_status.daemon.reload_error`，同一個錯誤 revision 不會重試。
4. 只有「完全啟用」的路由會進入 `RuleSet`：Installation `SELECTED`、Repo `accessible` 且已訂閱、規則啟用、目的地啟用。被標記 `FAILED` 的目的地由 dispatcher 略過，TUI 重設後（會 bump revision）重新載入。

## 通知語言

- `Language` enum：`EN`（`en`）、`ZH_TW`（`zh-TW`）；未知代碼回退為 `EN`。
- 語言設定在每個 destination 上；`WebhookService` 針對同一事件，每種語言只格式化一次。
- `MessageFormatter` 的所有措辭集中在 `Texts` record（每種語言一份）；資料值（repo、分支、標題）不翻譯，且一律先經 `Sanitizer`。
- 新增語言：在 `Language` 加列舉值 → 在 `MessageFormatter` 加一份 `Texts` 並接到 `texts()` → 補 `MessageFormatterTest`。
- 伺服器端（TUI、log、錯誤訊息）一律英文。

## Webhook 來源驗證

在 HMAC 簽章之前多一道檢查：只有 GitHub 公布的 webhook 來源 IP 可以送 webhook。

1. **取得真實 IP**（`ClientIpResolver`）：只有當 TCP 對端是 `webhook.trustedProxies` 中的主機時，才採信 `webhook.clientIpHeader`（`CF-Connecting-IP`）。
   預設信任的是 compose 服務名稱 `cloudflared`，每 60 秒重新解析成 IP；解析不到就不信任任何人。外部即使偽造這個 header 也無效。
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
| `ClientIpResolverTest` | CIDR 比對（v4 / v6）、只信任 trusted proxy 的 header、不做 DNS 誤判 |
| `WebhookPipelineTest` | 真實 SQLite 端到端：路由、多目的地、各自語言、去重、錯誤簽章、來源驗證（含 fail-closed 與沿用已存清單）、reload、停用 / 未訂閱 / 撤銷授權 |

## 本機開發

用 `GITSTATUS_CONFIG` 指定設定檔（預設 `/config/config.conf`）。Windows 上設定檔中的路徑要寫 `C:/...`，不要用 Git Bash 的 `/c/...`。

```bash
GITSTATUS_CONFIG=C:/path/to/config.conf java -jar target/gitstatus.jar run
```

Admin TUI 在 Windows 上會開 Swing 終端模擬視窗；在 Linux / Docker 內使用真實終端機（需要 TTY）。

## Log

格式由 `application.properties` 的 `logging.pattern.console` / `logging.pattern.file` 設定：

```
[2026-10-30 20:29:56 WARN]: Rejected github webhook delivery=... invalid signature
```

- 時間使用 JVM 時區；Docker 容器預設為 UTC，需要本地時間可在 compose 設定 `TZ` 環境變數。
- Daemon 輸出到 console（`docker compose logs`）；Admin TUI 不輸出到 console，寫到 `${java.io.tmpdir}/gitstatus-admin.log`。
- Log 內容規範見 AGENTS.md：只記 metadata，不記 payload 與 secret。

## Docker

- Multi-stage build：`maven:3.10-eclipse-temurin-25` → `eclipse-temurin:25-jre`。
- ENTRYPOINT 帶 `-XX:MaxRAMPercentage=40`，保留空間給 `docker compose exec` 啟動的 TUI JVM（預設 25%）。不使用 `JAVA_TOOL_OPTIONS`，避免 JVM 在 TUI 畫面印出 "Picked up" 訊息。
- `HEALTHCHECK` 以 bash `/dev/tcp` 呼叫 `/health`。
- JAR manifest 含 `Enable-Native-Access: ALL-UNNAMED`（sqlite-jdbc 載入 native library）。

## 設定檔（`config.conf`）

| Key | 預設 | 說明 |
|---|---|---|
| `server.port` | `8080` | |
| `database` | `/data/bot.db` | |
| `github.appId` | | |
| `github.privateKey` | `/run/secrets/github-app.pem` | 檔案路徑 |
| `github.webhookSecret` | `/run/secrets/github-webhook-secret` | 檔案路徑 |
| `github.apiUrl` | `https://api.github.com` | GHES：`https://<host>/api/v3` |
| `discord.token` | `/run/secrets/discord-token` | 檔案路徑 |
| `reload.intervalMs` | `5000` | |
| `webhook.maxBodyBytes` | `10485760` | |
| `webhook.verifySource` | `true` | 只允許 GitHub hook IP |
| `webhook.sourceRefreshMs` | `21600000` | GitHub hook IP 清單更新間隔（6 小時） |
| `webhook.clientIpHeader` | `CF-Connecting-IP` | 真實 IP header |
| `webhook.trustedProxies` | `cloudflared` | 可信任的代理：主機名稱或 CIDR，逗號分隔 |
| `deliveryLog.retentionDays` | `30` | |

## 擴充

- **新增事件**：`EventType` 加列舉值 → provider adapter 正規化 → `GitHubProvider.capabilities` 對應權限 → `MessageFormatter` 各語言措辭 → 測試。
- **新增 provider（GitLab）**：實作 `GitProvider` 與 `WebhookAdapter` 並註冊為 Spring bean；路由與發送層不需修改。

## 尚未實作

- Phase 5：Security 事件（Dependabot / Code scanning）、GitLab Adapter。
- Phase 6：外部 Queue、Multi-bot（`bot_profile` 已預留，目前只支援 `default`）。
- Deployment 事件、加密的 raw payload 除錯模式（SEC-004 選配）、replay timestamp window（SEC-002 選配）。
