# Jev 聊天助手 (Jev Chat Assistant)

**装在手机上的「对话副驾」：你在任何聊天 App 里聊天，它在旁边读懂对方、告诉你该怎么回，一键填进输入框，发不发由你。**

已在 **微信、QQ、X（Twitter 私信）** 三个平台真机跑通，飞书采集已接入。一套内核，一个 App 一个几十行的适配器。

**联系 / 反馈 / 合作：请公众号私信**（二维码见文末）。

<p align="center">
  <img src="docs/images/overlay.png" width="300" alt="悬浮窗：聊天上方的 Jev 分析面板" />
  &nbsp;&nbsp;&nbsp;
  <img src="docs/images/settings.png" width="300" alt="设置页" />
</p>
<p align="center"><sub>左：悬浮窗——危险等级、对方真实意图、Jev 排好序的 3 条候选回复，复制或填入。右：设置页。</sub></p>

## 亮点

- **一套内核，多平台。** 微信 8.0.78、QQ 9.3.50、X 12.25 真机验证，读消息 → 判断 → 候选 → 填入整条链全通。新增一个 App 只需实现一个 `ChatAppAdapter`，其余全部复用。
- **非侵入。** 不 hook、不改包、不走任何 App 的接口或账号、不读数据库，只用系统无障碍服务读「屏幕上正在显示的对话」。微信这种混淆节点的也能读到。
- **看得懂，不止会写。** 用 [TypeSafe Jev](https://typesafe.ai/) 判断模型一次给出：对方真实意图、危险等级（1–9）、对方要什么、该不该马上回、最佳动作。约 1 秒，带把握度。
- **3 条候选，Jev 排序。** 生成模型（默认 DeepSeek）起草 3 条口语化回复，Jev 按「最合适」排序并给出占比。
- **发送永远由你点。** 程序只把回复填进输入框，从不自动发送，不碰转账 / 红包 / 收款。
- **隐私在本机。** 密钥只存 App 私有空间，聊天内容只在分析那一刻发给模型接口，不落盘、不进日志。

## 平台支持

| 平台 | 状态 | 采集方式 | 备注 |
|---|---|---|---|
| 微信 Android | ✅ 全链路 | 伪装系统无障碍服务读气泡节点 | 8.0.52+ 混淆节点，伪装后 8.0.78 实测可读 |
| QQ Android | ✅ 全链路 | 无障碍读节点 | 9.3.50 实测（群聊）；1v1 按同结构推断 |
| X / Twitter 私信 | ✅ 全链路 | 解析 Compose 节点的 content-desc | 12.25 实测，中文界面；英文界面未验 |
| 飞书 / Lark | 🟡 部分 | 无障碍读标题、气泡位置、输入框 | 正文是自绘控件，要补「截图 + OCR」 |
| 桌面端 / 网页 | ⏳ 规划 | 截图 + OCR / 视觉 | 同一内核，换采集方式 |

本项目只读你自己设备上、你自己有权查看的聊天，不针对任何单一平台。

## 快速开始

**1. 装包。** 仓库里有签好名的 release 包：[`apk/jev-assistant-v1.2-release.apk`](apk/jev-assistant-v1.2-release.apk)（Android 11+）。

```bash
adb install -r apk/jev-assistant-v1.2-release.apk
```

**2. 填密钥。** 打开 App → 设置 → 填你自己的 [OpenRouter](https://openrouter.ai/) API Key；回复模型默认 `deepseek/deepseek-chat-v3.1`（国内 Gemini / OpenAI 会被区域限制）。

**3. 开权限。** 按主页向导开三项：
- 无障碍（读消息）
- 悬浮窗 / 显示在其他应用上层（展示分析）
- 自启动 + 省电无限制（小米 / HyperOS 必做，否则后台被冻结读不到消息）

装过 debug 包的要先卸载再装 release（签名不同），卸载会清掉密钥和设置。小米 / HyperOS 重装后悬浮窗权限会被重置，装完按向导再开一次。

## 它怎么工作

```
微信 / QQ / X / 飞书 ──(无障碍读节点)──▶ 采集最近消息
                                  │
              ┌───────────────────┴───────────────────┐
              ▼                                        ▼
   Jev 判断（一次 7 道题）                    生成模型起草 3 条候选
   意图 / 危险 / 需求 / 动作 / 该不该回          │
              └───────────────────┬───────────────────┘
                                  ▼
                        Jev 给 3 条候选排序
                                  ▼
                半透明悬浮窗展示 → 复制 / 填入（不发送）
```

- **采集**：一个 App 一个适配器，服务按前台包名分发。适配器只负责把当前窗口变成「标题 + 消息列表（谁说的、说了什么）」，下游全部通用。
- **判断**：[Jev](https://docs.typesafe.ai/) 只回答选择 / 打分 / 是非，一次请求发全部题目，约 1 秒返回。
- **回复**：生成模型起草 3 条候选，Jev 排序。
- **回填**：`ACTION_SET_TEXT`，失败则剪贴板 + `ACTION_PASTE`，不发送。

## 适配一个新的聊天 App

1. 在 `capture/ChatAppAdapter.kt` 实现 `ChatAppAdapter`：`pkg` 是包名，`extract(root, res)` 从无障碍树取出标题和消息列表（`Msg(side, text)`，`side` 为 `me` / `other`），不在聊天窗时返回 `null`。
2. 在 `capture/ChatCaptureService.kt` 的 `adapters` 加一行。
3. 判断、候选、悬浮窗、填入都不用动。

先用 `adb shell uiautomator dump` 看目标 App 暴露了什么，已有四个适配器覆盖了四种情况：

| App | 树的情况 | 适配器怎么做 |
|---|---|---|
| QQ | 节点开放，有 id | 正文 `id/mjn`、标题 `id/371`，按气泡贴哪侧头像判谁说的 |
| 微信 | 对普通无障碍服务混淆节点 | 服务类名伪装成系统的 `SelectToSpeakService`，读 `id/bkl` 气泡，按左右判 |
| X | Compose，无 id，text 为空 | 解析 content-desc `发件人：正文。时间。Read`，发件人是「你」即我方 |
| 飞书 | 正文自绘，树里没有文字 | 只能拿到标题 / 气泡位置 / 输入框，正文待 OCR |

QQ、X 全程只有一个 Activity，判「是不是聊天窗」要看树里有没有该有的节点（如输入框），不能看 Activity 名。

## 构建

JDK 17 + Android SDK（platform 35 / build-tools 35）。

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 需要仓库外的签名 properties，路径由 JEV_KEYSTORE_PROPS 指定
```

## 已知限制

- **国产 ROM 后台冻结**：小米 / HyperOS 会杀后台进程，前台保活、自启动、省电无限制都配了仍可能被杀，气泡短暂消失，在聊天里再交互一下自愈。
- **飞书正文**：自绘控件，不在无障碍树里，目前只能分析到文档卡片等带 TextView 的内容，正文要补「`takeScreenshot()` 裁气泡 + ML Kit 中文识别」；左对齐布局下我 / 对方也不能按左右判。
- **X 只按中文界面验过**：分隔符 `：`、`上午 / 下午`、`Read` 是中文界面实测；英文界面只做了兜底，未验。
- **群聊**：按一对一分析，「对方」与关系设定对群聊不准。
- **中文**：Jev 主训练语言是英文，题目用英文、聊天内容保留中文；建议用自己的真实对话做一批标注校准（见 `tools/jev/`）。
- 伪装无障碍服务是绕过微信混淆的手段，微信版本更新可能失效。

## 目录

- `app/` — Android 应用（Kotlin，传统 View）
  - `capture/` 无障碍采集：`ChatAppAdapter.kt` 各 App 适配器、`ChatCaptureService.kt` 分发服务、前台保活
  - `jev/` Jev 客户端与题目集 · `overlay/` 悬浮窗 · `core/` 配置与数据模型
- `tools/jev/` — Jev 题目集与校准脚手架（Python）
- `docs/` — 设计与验收文档
- `apk/` — 签好名的 release 包

## 交流群 / 需求收集

**如需联系，请公众号私信。** 合作、反馈、进群失败、二维码过期，都走公众号私信，其它渠道不一定看得到。

<p align="center"><img src="docs/images/wechat-mp.png" width="180" alt="公众号二维码" /></p>

想听真实需求：你在哪个聊天 App 上最想要这个副驾？希望它判断什么、怎么提示、什么绝对不能碰？扫码进群直接说。**1 群已满，不要再扫；2、3、4 群任选一个，请勿重复加入。**

<table align="center"><tr>
  <td align="center"><img src="docs/images/wechat-group-1.png" width="160" alt="1 群（已满）" /><br/><b>1 群 · 已满</b></td>
  <td align="center"><img src="docs/images/wechat-group-2.png" width="160" alt="2 群" /><br/>2 群</td>
  <td align="center"><img src="docs/images/wechat-group-3.png" width="160" alt="3 群" /><br/>3 群</td>
  <td align="center"><img src="docs/images/wechat-group-4.png" width="160" alt="4 群" /><br/>4 群</td>
</tr></table>

<p align="center"><sub>群二维码 7 天有效（本批到 2026-09-28），过期了公众号私信要新码。</sub></p>

## 免责声明与许可

仅供个人学习与研究使用。只处理你自己设备上、你自己有权查看的聊天。请遵守微信、QQ、X、飞书等各软件的许可协议与当地法律法规，作者不对使用后果负责。代码以 [MIT](LICENSE) 协议开源。
