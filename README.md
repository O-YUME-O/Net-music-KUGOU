# NetMusic Echo Addon

> 适用于 Minecraft 1.20.1 + Forge 的 `netmusic` 模组扩展。
> 主要给音乐刻录机增加**酷狗概念版**搜索 / 刻录 / 自动续期 URL 的能力。

---

## ⚠️ 已知限制：烧入的音频 URL 会过期

这是一个**功能性提醒**，务必先读：

CD 物品 NBT 里保存的 `songUrl` 是**酷狗的短时签名 URL**（signed URL），
CDN 用签名 + 时间戳来鉴权，过期后服务端会返回 `403 Forbidden`。

- **有效时长不固定**：从几个小时到几天不等，取决于酷狗 CDN 的策略
- **过期后表现**：把 CD 放进唱片机，播放失败（`播放音乐失败`），日志里能看到 `IOException: Audio not found at <url>: 403`
- **影响范围**：所有**已经刻好的 CD** 都受这个限制影响
- **注意**：这不是本 mod 的 bug，是酷狗的 URL 设计固有限制。原版 `netmusic` 模组也有这个毛病

### 自动续期机制（v1.0+）

本 mod 自带**多层防护 + 自动续期**功能，尽最大努力让 CD 保持可播放：

1. 烧入新 CD 时，本 mod 会把**原曲识别信息**（`fileHash` + `albumId`）写进 CD 的 NBT
2. **进入世界时立即给该玩家跑一次扫描**（仅在有酷狗登录态时；不必等 4 小时）
3. **放 CD 进唱片机的瞬间同步探测 URL**：失效就当场重新拉，新 URL 写回 CD 再放进唱片机（用户零感知）
4. 之后每 `url_refresh.intervalHours` 小时（默认 4 小时）扫描**所有在线玩家**的：
   - 玩家背包
   - 末影箱
5. 对每个有 fileHash 记录的 CD：
   - 用 HEAD 请求探测 `songUrl`（HEAD 不支持就退化为 Range GET 取 1 字节）
   - 检测到 403 / 410 → 调 `getSongUrl(fileHash, albumId)` 重新拉一个 URL
   - 把新 URL 写回 CD NBT
6. 用户**完全无感**，CD 在过期前/后都会被自动修复

⚠️ **仅对升级 v1.0+ 之后烧的 CD 生效**。之前烧的 CD NBT 里没有 `fileHash`，巡检器会跳过它们（不会破坏现有数据）—— 只能通过**手动重新刻录**来恢复。

### 配置项

`config/netmusic-echo-addon-client.toml` 里的 `url_refresh` 段：

```toml
[url_refresh]
    # 是否启用 URL 自动续期
    enabled = true
    
    # 巡检间隔（小时），范围 1-168
    intervalHours = 4
    
    # HEAD 探测超时（秒）
    checkTimeoutSeconds = 5
```

### 如果不想用自动续期

`url_refresh.enabled = false` 即可。

### 手动恢复失效 CD

1. 把失效的 CD 放回刻录机（**不要勾"只读"**）
2. 在搜索界面里搜同一首歌
3. 重新"刻录"覆盖即可

---

## 音质选择

`audio_quality.quality` 段：

| 取值 | 含义 | 备注 |
|------|------|------|
| `128` | 标准 | 默认大部分免费歌能拿到的最高音质 |
| `320` | HQ | **默认值** |
| `flac` | 无损 | VIP 歌曲才有 |
| `high` | 高品质 | VIP |
| `super` | 臻品音质 DSD | VIP |

v1.0+ 加了**自动降级链**：当用户选的音质在服务端没有时，会按
`super → flac → high → 320 → 128` 顺序逐级试，**不报错**直接给可播放的 URL。
日志里会显示降级过程：

```
[NetMusicEchoAddon] Fetching song URL for hash=xxx, requested=FLAC, ladder=[flac → high → 320 → 128]
[NetMusicEchoAddon] Quality flac unavailable for hash=xxx, falling back to high
[NetMusicEchoAddon] Got URL via v5/url at quality=320 (step 3/4)
```

注意：烧好的 CD 之后**音质不会变**——NBT 里的 URL 已经是定值。

---

## 自动领取每日 VIP

`vip` 段：

```toml
[vip]
    # 启动时自动领取（仅酷狗概念版登录后有效）
    autoReceiveVip = false
    
    # 领取失败后的重试间隔（分钟）
    vipRetryIntervalMinutes = 10
```

领取规则遵循**自然日 0 点刷新**（不是严格 24h）：
- 昨天任意时间领取 → 今天的 0 点刷新
- 今天任意时间可以再次观看广告领取

mod 内置的 [`shouldRetryToday()`](src/main/java/com/github/tartaricacid/netmusic/echo/api/KuGouVipApi.java) 会在跨日时自动放开重试。

---

## 配置文件位置

所有配置 / 状态文件都放在 Minecraft 配置目录下：

```
<游戏目录>/config/
├── netmusic-echo-addon-client.toml       # 用户配置（ClothConfig）
└── netmusic-echo-addon-state.json        # 登录状态（自动）
```

老版本（v1.0 之前）状态文件可能在游戏根目录 `netmusic-echo-addon-state.json`，
首次启动新版时会**自动迁移**到 `config/` 下并删掉旧文件。

---

## 调试日志关键字

- `[NetMusicEchoAddon]` — 通用
- `[UrlRefresher]` — URL 续期相关
- `[KuGouApiClient]` — 酷狗 API 调用

日志文件位置：`logs/latest.log`
