# 飞书消息源（开放平台 API，只读）

> 2026-09-21 新增。飞书是继微信（无障碍读屏）之后的**第二个消息源**：采集方式换成飞书官方
> 开放平台 API，Jev 判断 + 生成排序 + 悬浮窗内核不变。**只读**——客户端刻意不实现任何写接口
> （发消息/建群等），回复只能「复制」，粘贴发送永远由人手动完成。

## 两条路线的对比

| | 微信模式 | 飞书模式 |
|---|---|---|
| 采集方式 | 无障碍服务读气泡（伪装类名） | 开放平台 API 轮询（`GET /im/v1/messages`） |
| 官方态度 | 混淆节点、随时可能失效 | 官方开放能力，稳定 |
| 能读什么 | 当前打开的聊天窗口 | **机器人所在的群**（单聊 p2p 不在群列表里） |
| 触发时机 | 停在聊天页 + 对方最新一条 | 任意前台，轮询发现新消息 + 对方最新一条 |
| 回复方式 | 复制 / 填入输入框（不发送） | 仅复制（粘贴发送由人完成） |
| 依赖配置 | 无障碍 + 悬浮窗权限 | 飞书自建应用 App ID/Secret + 机器人入群 |

## 飞书后台配置（一次性）

1. [飞书开发者后台](https://open.feishu.cn/app) → 创建**企业自建应用**，拿到 `App ID`（`cli_` 开头）和 `App Secret`。
2. 应用详情 → **添加应用能力** → 开启**机器人**。
3. **权限管理** 开通（应用身份）：
   - `im:chat:readonly` —— 获取机器人所在群列表
   - `im:message:readonly` —— 读取单聊/群聊消息
   - `im:message.group_msg` —— 读取**群聊中所有消息**（缺了这个只能读到 @机器人 的消息）
4. **版本管理与发布** → 创建版本并发布（企业自建应用通常管理员直接通过）；**可用范围**要包含会话成员。
5. 把机器人**加入目标群**（群设置 → 群机器人 → 添加）。机器人只能读到它所在的群。
6. 可选：拿到自己的 `open_id`（`ou_` 开头）。最省事的办法：跑一次 `tools/feishu/smoke_test.py`，从输出里认领自己发的消息的 sender id；App 的连通测试也能看到。

## App 内配置

设置 → **飞书** 区：

| 配置项 | 说明 |
|---|---|
| 启用飞书消息源 | 总开关；保存后立即按新配置启停轮询服务 |
| App ID / App Secret | 与 OpenRouter key 同级：App 私有存储，不出设备、不进日志 |
| 我的 Open ID | 区分消息是谁发的；留空则除机器人外都按「对方」处理 |
| 轮询间隔（秒） | 10–300，默认 20 |
| 会话白名单 | 群名关键词，每行一个；空 = 监控机器人所在的所有群 |

主页状态卡会显示「飞书消息源：已配置 / 未配置（可选）」。设置页的**飞书连通测试**按钮
会真实跑一遍 `token → 群列表 → 最新消息`，是上机前最快的排障入口。

## 工作原理

```
飞书群聊 ──(开放平台 API，20s 轮询)──▶ latestMessages(10)
                                          │
                      水位判定：create_time > 已处理水位？
                                          │
                    最新一条是「对方」发的？──是──▶ ChatSnapshot(source="feishu")
                                          │              │
                              Jev 判断（7 题）    生成模型起草 3 条
                                          └──────┬───────┘
                                            Jev 排序
                                                 ▼
                        共享悬浮窗展示 → 复制 → 用户自己去飞书粘贴发送
```

- **水位**：每个群记住已处理到的 `create_time`（毫秒）。首次见到的群只登记水位不分析
  （不会把入群前的历史消息当成新消息轰炸）。
- **忙回滚**：上一条分析还在跑时触发新分析会失败，此时回滚水位，下一轮重新拉取重试，不丢消息。
- **退避**：连续失败按 2/4/8 倍间隔退避（上限 300s），只在失败连击的第一报一次悬浮窗错误。
- **共享悬浮窗**：与微信共用一个气泡（进程单例），内容互相替换；飞书分析在屏时离开微信
  不会藏气泡，「重新分析」按钮按当前来源路由。

## 客户端覆盖的 API（全部为读接口）

代码位置：`app/src/main/java/com/jev/probe/feishu/FeishuClient.kt`。
基地址 `https://open.feishu.cn`（构造参数可换 `https://open.larksuite.com` 国际版）。

| 方法 | 路径 | 关键参数 | 响应关键字段 | 权限 | 频率上限 |
|---|---|---|---|---|---|
| POST | `/open-apis/auth/v3/tenant_access_token/internal` | body: `app_id`, `app_secret` | `code`, `msg`, `tenant_access_token`, `expire`（秒） | 无需 | — |
| GET | `/open-apis/im/v1/chats` | `page_size`(≤100), `page_token` | `data.items[].chat_id/name/external`, `has_more`, `page_token` | `im:chat:readonly` | 1000/min, 50/s |
| GET | `/open-apis/im/v1/messages` | `container_id_type=chat`, `container_id`, `start_time`/`end_time`（**秒级**时间戳）, `sort_type`(ByCreateTimeAsc/Desc), `page_size`(≤50), `page_token` | `data.items[].message_id/create_time`（**毫秒字符串**）/`msg_type`/`deleted`/`sender{id,sender_type}`/`body.content`(JSON 字符串)/`mentions[]`, `has_more`, `page_token` | `im:message:readonly` + `im:message.group_msg`（群） | 1000/min, 50/s |
| GET | `/open-apis/im/v1/messages/{message_id}` | 路径参数 | 同上（`data.items` 单元素） | 同上 | 同上 |

**错误处理**：业务码 `99991400`（限流）退避 800ms 重试一次；`99991663`/`99991661`（凭证失效）
强制刷新 token 重试一次；`230002` 机器人不在群、`230006` 未开机器人能力、`230027` 缺权限、
`231203` 保密群 —— 都会转成中文提示显示在悬浮窗/设置页。

**刻意不实现**：发消息（`POST /im/v1/messages`）、建群、撤回等一切写接口。
硬约束「绝不自动发送消息」在飞书路线上的直接体现：**客户端没有能力代替用户发出任何消息**。

## 消息文本解析（FeishuMapper）

- `text`：取 `body.content` 里的 `text`；`@_user_N` 占位用 `mentions[].name` 还原成真名。
- `post`（富文本）：递归收集 `text`/`a` 片段，标题作前缀；`img`→`[图片]`。
- 其它类型 → 占位符（`[图片]`/`[文件]`/`[表情包]`…），保证 Jev 拿到的都是可读文字。
- 撤回（`deleted=true`）与 `system` 消息在生成快照前过滤。
- 侧别：`sender_type=app`（机器人）或 `sender.id == 我的 open_id` → `me`；否则 `other`。

## 已知限制

- 机器人**只能读它所在的群**——这是飞书的权限边界（也正好框死了隐私边界：读不到没加机器人的会话）。
- 单聊 p2p（你和机器人私聊）**不在** `GET /im/v1/chats` 群列表里，目前不作为监控对象。
- 群里多人发言时「关系描述」语义会失真（与微信模式的群聊限制相同）；建议用白名单圈定小群。
- 轮询延迟 = 轮询间隔（默认 20s），弱网/限流退避时更长；不如无障碍读屏实时。
- 应用需发布版本且成员在可用范围内，否则报 `230013`。

## 测试

| 层级 | 内容 | 位置 |
|---|---|---|
| 单元测试（JVM，无需凭据） | token 缓存/强制刷新、鉴权头、查询参数、分页排序、限流/凭证失效重试、业务错误转译、消息文本解析、侧别判定 | `app/src/test/java/com/jev/probe/feishu/` |
| PC 冒烟（真实凭据） | `token → 群列表 → 各群最近消息`，标记谁发的、哪些群当前会触发 | `tools/feishu/smoke_test.py` |
| 真机端到端 | 设置页「飞书连通测试」→ 群里让对方发一条 → 悬浮窗出分析 → 复制 → 手动粘贴发送 | 见 `_reports/feishu_api_integration_report.md` 的验收清单 |

运行单测（在有 JDK17 + SDK 的构建机上）：

```bash
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ./gradlew testDebugUnitTest
```
