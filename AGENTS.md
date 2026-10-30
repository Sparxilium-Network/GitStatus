# AGENTS.md

給在此專案工作的 AI agent / 開發者的指引。需求來源：`git_discord_notification_system_requirements.docx`。

## Git 規則（必須遵守）

- **每完成一個功能就進行一次 `git commit`**；一個 commit 只包含一個功能，訊息說明做了什麼。
- commit 前先跑 `mvn test`，測試失敗不得 commit。
- **禁止 `git push`**（任何 remote、任何分支、包含 `--force`）。推送由人工決定與執行。
- 不得改寫已存在的歷史（不 `rebase`、不 `commit --amend` 已完成的 commit、不 `reset --hard`）。

## 專案概要

自架的 Git Provider → Discord 通知服務。Discord 只是輸出端；所有設定只能在伺服器上透過 Admin TUI 修改。

- Java 25、Spring Boot 4.1、Maven
- JDA 6（只發送訊息，不註冊指令、不監聽訊息、不用 privileged intent）
- Lanterna 3（Admin TUI）
- SQLite + `JdbcTemplate`（不使用 ORM）
- Jackson 3（套件為 `tools.jackson.*`，方法名為 `asString()` 等）

兩種執行模式共用同一個 JAR 與資料庫：

```bash
java -jar target/gitstatus.jar run
```

```bash
java -jar target/gitstatus.jar admin
```

本機執行用 `GITSTATUS_CONFIG` 指定設定檔（預設 `/config/config.conf`）。Windows 上路徑要用 `C:/...`，不要用 Git Bash 的 `/c/...`。

## 建置與測試

```bash
mvn test
```

```bash
mvn -DskipTests package
```

## 程式結構（`com.sparxilium.gitstatus`）

| 套件 | 職責 |
|---|---|
| `GitStatusApplication` | 依 `run` / `admin` 啟動不同 profile |
| `DaemonConfig` | 僅 daemon 模式的 bean（dispatcher、webhook service、排程） |
| `config` | `AppSettings`：系統設定；secret 只從檔案讀取 |
| `db` | `schema.sql` 初始化、`ConfigStore`（所有 SQL 集中於此） |
| `model` | `EventType`、`NormalizedEvent`、資料 record |
| `provider` | `GitProvider`（API）、`WebhookAdapter`（webhook）介面；`github` 實作 |
| `routing` | `RuleSet` 快照、載入驗證、revision 輪詢與原子替換 |
| `notify` | `Sanitizer`、`MessageFormatter`、`DiscordGateway`、`NotificationDispatcher` |
| `webhook` | `WebhookController`（HTTP）與 `WebhookService`（流程） |
| `tui` | Lanterna 畫面 |

## 不可破壞的設計約束

- **設定變更一律走 `ConfigStore.mutate`**：同一個 transaction 內寫入並 `revision + 1`。daemon 執行期的狀態（`destination_health`、`delivery_log`、`runtime_status`）不 bump revision。
- **不得新增任何可讀取或修改設定的 HTTP 端點或 Discord 指令**（SEC-008）。公開 HTTP 只有 `POST /webhooks/{provider}` 與 `GET /health`。
- **Webhook 必須先通過來源 IP 驗證與簽章驗證**才能做任何處理或寫入 delivery_log（SEC-001）。來源驗證必須維持 fail-closed；`CF-Connecting-IP` 只能在對端是 trusted proxy 時採信。
- **不得記錄或保存 raw payload**；log 只寫 delivery id、provider、repo id、event type、結果、latency（SEC-003/004）。
- **不得在 log 或錯誤訊息中輸出 token、private key、webhook secret**；錯誤訊息只寫設定名稱與路徑。
- **送往 Discord 的內容只能來自 `NormalizedEvent` 的白名單欄位**，經 `Sanitizer` 處理，並維持 `allowed_mentions` 為空（SEC-005/006/007）。
- 新增事件：在 `EventType` 加入列舉值 → 在 provider adapter 中正規化 → 在 `GitHubProvider.capabilities` 對應所需權限 → 在 `MessageFormatter` 加入格式 → 補測試。
- 新增 provider（例如 GitLab）：實作 `GitProvider` 與 `WebhookAdapter` 並註冊為 Spring bean，不要改動路由與發送層。
- GitHub App 只申請 Read 權限。

## 程式風格

- 跟隨周邊程式碼的命名、註解密度與寫法。
- 伺服器端一律使用英文：TUI 顯示文字、錯誤訊息、log、程式碼與識別字。
- 新功能必須附測試；`WebhookPipelineTest` 是以真實 SQLite 跑端到端流程的範例。

## 目前進度

- 已完成：Phase 1–4（見 DEV-README.md）、伺服器端英文介面、每個目的地可選通知語言（English / 繁體中文）。

## 文件

- `README.md`：給一般使用者（安裝、設定、常見問題），不放技術細節。
- `DEV-README.md`：技術細節（架構、資料模型、安全對照、測試、擴充方式）。
- `docs/CLOUDFLARE-TUNNEL.md`：給使用者自行架設 Cloudflare Tunnel 的填空式教學；範本在 `deploy/tunnel/`。服務 port 預設 33444。
- 改動功能時同步更新對應文件。
- 待辦：Phase 5（Security 事件、GitLab Adapter）、Phase 6（外部 Queue、Multi-bot；`bot_profile` 欄位已預留）。
