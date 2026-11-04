# 設定畫面使用說明

通知要送到哪裡，全部在這個文字介面裡設定。介面是英文的，這份說明逐一解釋每個畫面。

## 開啟設定畫面

SSH 登入伺服器，在 `deploy/` 目錄執行：

```bash
docker compose exec bot java -jar /app/gitstatus.jar admin
```

設定一儲存就寫進資料庫，正在執行的服務約 5 秒內自動套用，**不需要重新啟動**。

## 通用按鍵

| 按鍵 | 作用 |
|---|---|
| `Up` / `Down` | 移動游標（`>` 標示目前位置） |
| `PageUp` / `PageDown`、`Home` / `End` | 清單很長時快速移動 |
| `Enter` | 選擇、進入下一層 |
| `Space` | 勾選或取消 `[ ]` / `[*]` |
| `Esc` | 返回上一層；在主選單是離開 |

每個畫面最下方都會列出這個畫面能用的按鍵。

## 先搞懂：一則通知什麼時候會送出

GitHub 傳來一個事件時，**下面四個條件都成立**才會送到某個頻道：

1. 這個 repo 所屬的帳號或 Organization 在 **Git Provider Sources** 有勾選。
2. 這個 repo 在 **Repositories** 清單裡有啟用（`[*]`）。
3. 這個 repo 的事件設定裡，**針對這個頻道**勾了這個事件。
4. 這個頻道在 **Discord Destinations** 沒有被停用。

通知是**照頻道分別設定**的，不是「選了 repo 就送到所有頻道」。例如同一個 repo，可以把 Push 送到 `#dev`，只把 Release 送到 `#announcements`。

## 建議的設定順序

```
1. Discord Destinations   新增要收通知的頻道
2. Git Provider Sources   勾選要通知的 GitHub 帳號 / Organization（所有頻道共用）
3. Repositories           啟用 repo，再進去替每個頻道勾選事件
4. System Status          確認一切正常
```

## 1. Discord Destinations：頻道

列出所有已新增的頻道，以及名稱、Channel ID、通知語言、狀態。

**新增頻道**，選清單最下面的其中一項：

- `+ Add destination (pick from channels visible to the bot)`：從 Bot 已加入的 Server 挑選頻道，最方便。只會列出 Bot 有 View / Send / Embed Links 權限的頻道。
- `+ Add destination (enter Channel ID manually)`：手動輸入 Channel ID。Bot 看不到的頻道也可以先存起來。

接著替頻道取個名字（預設是 `Server / #頻道`），再選通知語言 `English (en)` 或 `Traditional Chinese (zh-TW)`。

**管理既有頻道**：在頻道上按 `Enter`，可以：

| 選項 | 作用 |
|---|---|
| `Send test message` | 送一則測試訊息。新增後建議先試一次 |
| `Notification language` | 改通知語言 |
| `Disable` / `Enable` | 暫停或恢復這個頻道的所有通知，設定會保留 |
| `Reset failure state` | 清除送信失敗的紀錄（例如修好 Bot 權限之後） |
| `Delete` | 刪除頻道，**連同所有 repo 對這個頻道的事件設定** |

## 2. Git Provider Sources：來源

列出 GitHub App 已安裝的帳號或 Organization。用 `Space` 勾選要通知的來源，所有頻道共用這份設定。

- 進入畫面時會自動向 GitHub 同步一次；按 `R` 可以重新同步（例如剛把 App 安裝到新的 Organization）。
- 每一列最後的 `App 1234567` 是這個來源所屬的 GitHub App。有多個 Organization 時，每個 Organization 各有一個 App。
- 顯示 `(no App yet)` 表示這是舊版升級上來、還沒對應到 App 的來源，按 `R` 同步一次就會補上。

## 3. Repositories / Notification Rules：repo 與事件

先選一個來源，會列出它底下的 repo（第一次進入會向 GitHub 抓清單，失敗時顯示上次的結果）。

```
> [*] acme/api                                           PRIVATE
  [ ] acme/web                                           PRIVATE  *
  [ ] acme/old-site                                      ARCHIVED
```

- `Space`：啟用或停用這個 repo 的通知。**停用時所有頻道都收不到**，但事件設定會保留。
- `Enter`：進入事件設定（見下方）。
- 結尾有 `*`：這個 repo 也能從另一個 GitHub App 看到。兩邊都啟用的話，每則通知會收到兩次，只要在其中一邊啟用就好。

### 事件設定

```
Repository   : acme/api  [PRIVATE]
Subscription : enabled
Destination  : < Acme / #dev >  (1/3)

  [*] Code
      [*] Push
> [-] Pull Request
      [*] Opened
      [ ] Merged
      [ ] Closed
  [ ] GitHub Actions
      [ ] Started
      ...
  [ ] Security   (unavailable / not supported yet)
```

- **`Destination`** 是目前正在設定的頻道。用 `Left` / `Right` 切換頻道，每個頻道的勾選是分開的。
- **事件那一列按 `Space`**：開關單一事件。
- **分類那一列按 `Space`**：一次開關整個分類。分類的勾選框代表：
  - `[*]`：整個分類都開
  - `[-]`：只開了一部分
  - `[ ]`：全部關閉

  不是全開時按一下會全部打開，全開時按一下會全部關閉。
- **`S`**：儲存。所有頻道的變更會一起存，切換頻道時不會遺失。沒儲存就按 `Esc` 會先詢問。
- 事件後面有括號說明的，表示目前不能開啟：
  - `unavailable / not supported yet`：程式還不支援這類事件。
  - `unavailable / insufficient permission`、`unavailable / App not subscribed to this webhook event`：GitHub App 缺少對應的 Permission，或沒勾選這個 webhook event。到 GitHub App 的設定補上後，再回 **Git Provider Sources** 按 `R` 同步。
- `Subscription : disabled` 表示這個 repo 在清單裡還沒啟用，事件可以先設好，但啟用之前不會送出。

## 4. System Status：狀態

檢查目前的運作狀態，按 `R` 重新整理，按 `D` 測試 Discord 連線。

| 區塊 | 看什麼 |
|---|---|
| `Database` | 設定的版本號；每次儲存都會加 1 |
| `Daemon` | 服務目前套用的設定版本。和上面的版本相同，表示最新設定已生效；顯示 `No heartbeat yet` 表示服務沒在執行。`Started at` 是服務最後一次啟動的時間，可用來確認重啟是否完成 |
| `Source verification` | 已載入幾個 GitHub webhook IP 範圍。顯示 `no GitHub hook ranges loaded` 時，所有 webhook 都會被拒絕 |
| `Git Providers` | 每個 GitHub App 能不能登入 |
| `Last webhook` | 最後一次收到的 webhook 和處理結果 |
| `Destination send results` | 每個頻道最近一次送信的結果與錯誤 |

## 5. Restart Service：重啟服務

確認後服務會正常關閉，再由 Docker 自動啟動（`docker-compose.yml` 的 `restart: unless-stopped`），大約需要 10–20 秒。

- 容器重啟時，**這個設定畫面也會一起關閉**，等一下再重新開啟即可。回到 **System Status** 看 `Started at` 是否更新。
- 重啟期間 GitHub 送來的 webhook 會失敗，需要的話到 GitHub App 的 **Advanced → Recent Deliveries** 按 **Redeliver**。
- 在這個畫面改的設定約 5 秒內就生效，**不需要重啟**。重啟適合用在 Discord 連線異常、改了 `secrets/` 裡的檔案之後。
- 重啟**不會**套用新版程式、`docker-compose.yml` 或 `config/config.conf` 的變更，這些請執行 `sudo ./setup.sh`（或 `docker compose up -d --build --force-recreate`）。

## 沒收到通知時

照順序檢查：

1. **System Status → `Last webhook`**：有沒有收到？沒有的話，問題在 GitHub 或 Cloudflare Tunnel 那一段，參考 README 的「常見問題」。
2. 有收到但結果是 `NO_ROUTE`（沒有任何頻道要這個事件）：回頭確認上面「一則通知什麼時候會送出」的四個條件，特別是**事件設定裡目前的 `Destination` 是不是你要的頻道**。
3. **System Status → `Destination send results`** 有錯誤：通常是 Bot 在頻道沒有權限。修好後到 **Discord Destinations** 用 `Send test message` 確認，再按 `Reset failure state`。
