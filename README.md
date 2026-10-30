# gitstatus — Git Provider → Discord 通知系統

依 `git_discord_notification_system_requirements.docx` 實作 Phase 1–4：
Java Daemon + Docker + SQLite + Discord Bot、GitHub App Installation / Repository Discovery、
Webhook（Push / Pull Request / workflow_run / Release）、Server-side Admin TUI、revision reload。

技術選型：Spring Boot 4.1（Java 25）、JDA 6（只發送，無指令）、Lanterna 3（TUI）、SQLite + JdbcTemplate（無 ORM）。

## 執行模式

| 模式 | 指令 | 職責 |
|---|---|---|
| Daemon | `java -jar gitstatus.jar run` | 接收 Webhook、路由、發送 Discord |
| Admin TUI | `java -jar gitstatus.jar admin` | 設定目的地 / 來源 / Repo / 事件規則，離開即結束 |

Docker 內：

```bash
docker compose exec bot java -jar /app/gitstatus.jar admin
```

## 部署

1. 建立 GitHub App（見下方），下載 private key。
2. 建立 Discord Bot（見下方），邀請到需要的 Server。
3. 準備 Host 目錄（範例在 [deploy/](deploy/)）：

```
/opt/git-notifier/
├── docker-compose.yml
├── config/config.conf
├── data/
└── secrets/
    ├── github-app.pem
    ├── github-webhook-secret
    └── discord-token
```

4. 權限（SEC-009）：容器以 uid 10001 執行。

```bash
sudo chown -R 10001:10001 data secrets && sudo chmod 700 data secrets && sudo chmod 400 secrets/*
```

5. 啟動：

```bash
docker compose up -d --build
```

6. 反向代理（TLS）將 `https://<your-host>/webhooks/github` 轉到 `127.0.0.1:8080`。
   公開 HTTP 只有 `POST /webhooks/{provider}` 與 `GET /health`，沒有任何設定端點。

## GitHub App 設定

- Webhook URL：`https://<your-host>/webhooks/github`，Content type 建議 `application/json`，設定 Webhook secret（寫入 `secrets/github-webhook-secret`）。
- Repository permissions（全部 Read-only）：Metadata、Contents、Actions、Pull requests。
- Subscribe to events：Push、Pull request、Workflow run、Release。
- 不要申請任何 Write / Administration 權限。
- 安裝 App 到要通知的帳號 / Organization，並只授權需要的 Repository。TUI 只會列出 App 實際被授權的 Installation 與 Repo。

TUI 會依 Installation 的權限與訂閱事件判斷每個事件是否可用，不足時顯示 `unavailable / insufficient permission` 或 `unavailable / App not subscribed to this webhook event`。

## Discord Bot 設定

- OAuth2 scope 只需 `bot`（不需 `applications.commands`）。
- Bot 權限：View Channel、Send Messages、Embed Links。不要給 Administrator / Manage 類權限。
- 不需要任何 Privileged Gateway Intent（包含 Message Content）。
- 所有訊息都以 `allowed_mentions: none` 發送，且文字經過 Sanitizer，`@everyone` / `@here` / role mention 不會觸發。

## Admin TUI 操作流程

TUI 介面為英文。

1. **Discord Destinations**：從 Bot 可見的 Guild → 可發訊息的頻道選擇，或手動輸入 Channel ID；可發送測試訊息、停用、重設異常狀態。
2. **Git Provider Sources**：自動從 GitHub API 讀取 Installation，Space 勾選要納入的帳號 / Organization。
3. **Repositories / Notification Rules**：選來源 → 從 API 同步 Repo 清單（分頁），Space 啟用/停用訂閱，Enter 進入事件設定。
   事件設定頁以 Left/Right 切換目的地，Space 勾選事件，`S` 保存。
4. **System Status**：DB revision、Daemon 已載入的 revision 與心跳、GitHub 認證、Discord 連線、最近一次 Webhook、各目的地最近發送結果。

每次保存都在單一 transaction 內寫入並把 `configuration_state.revision + 1`；Daemon 每 5 秒檢查 revision，
載入並驗證成功後才原子替換規則，失敗則保留上一版。一般規則修改不需要 restart。

## 路由模型

`Repository × Event Type × Discord Destination`。一則通知只有在以下條件全部成立時才會發送：
來源 Installation 已選取、Repo 仍被授權且已訂閱、該事件 × 目的地規則已啟用、目的地已啟用且未被標記異常。

## 安全設計對照

| 需求 | 實作 |
|---|---|
| SEC-001 | `X-Hub-Signature-256` HMAC-SHA256，常數時間比對；失敗回 401，不寫入 delivery_log |
| SEC-002 | `X-GitHub-Delivery` 唯一鍵去重 |
| SEC-003 | Secret 只從檔案讀取，錯誤訊息只含設定名稱與路徑；Installation token 僅存在記憶體 |
| SEC-004 | 不保存 raw body；delivery_log 只存 metadata，預設保留 30 天 |
| SEC-005/006 | `allowed_mentions` 為空 + Sanitizer（控制字元、Markdown、mention、長度） |
| SEC-007 | `NormalizedEvent` 只帶 allowlist 欄位，Formatter 只輸出摘要 + 連結（僅 https） |
| SEC-008 | 設定只能透過 TUI；HTTP 無設定端點；Discord 無指令 |
| SEC-009 | DB 檔案 0600、容器非 root、secrets 唯讀掛載 |

## 開發

```bash
mvn test
```

```bash
mvn -DskipTests package
```

本機執行時用 `GITSTATUS_CONFIG` 指定設定檔位置（預設 `/config/config.conf`）。
Admin TUI 在 Windows 上會開 Swing 終端模擬視窗（僅供開發）；在 Linux / Docker 內使用真實終端機。

## 尚未實作（後續階段）

- Phase 5：Security 事件（Dependabot / Code scanning）、GitLab Adapter（介面 `GitProvider` / `WebhookAdapter` 已預留，TUI 會顯示 `not supported yet`）。
- Phase 6：外部 Queue、Multi-bot（`bot_profile` 欄位已預留，目前只支援 `default`）。
- Deployment 事件、Raw payload 加密除錯模式（SEC-004 選配）、Replay timestamp window（SEC-002 選配）。
