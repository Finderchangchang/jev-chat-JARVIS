<div align="center">

<img src="docs/images/logo.png" width="150" alt="Jev 聊天助手" />

# Jev 聊天助手

**装在手机上的「对话副驾」：你在任何聊天 App 里聊天，它在旁边读懂对方、告诉你该怎么回，一键填进输入框，发不发由你。**

[![Stars](https://img.shields.io/github/stars/jev-chat/jev-chat-jarvis?style=flat-square&logo=github&label=Stars)](https://github.com/jev-chat/jev-chat-jarvis/stargazers)
[![Forks](https://img.shields.io/github/forks/jev-chat/jev-chat-jarvis?style=flat-square&logo=github&label=Forks)](https://github.com/jev-chat/jev-chat-jarvis/forks)
[![Version](https://img.shields.io/badge/%E7%89%88%E6%9C%AC-v1.3-1f6feb?style=flat-square)](CHANGELOG.md)
[![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#快速开始)
[![License](https://img.shields.io/github/license/jev-chat/jev-chat-jarvis?style=flat-square)](LICENSE)

[官网](https://chatjevs.com) · [下载 APK](apk/jev-assistant-v1.3-release.apk) · [历史版本](https://github.com/jev-chat/jev-chat-jarvis/releases) · [更新日志](CHANGELOG.md) · [macOS 版](https://github.com/jev-chat/jev-chat-mac) · [Windows 版](https://github.com/jev-chat/jev-chat-windows)

</div>

## ❤️赞助商

> [想出现在这里？](#交流群--需求收集)

<details open>
<summary>点击折叠</summary>

<table>
<tr>
<td width="240"><a href="https://faka.rainlanguage.top"><img src="docs/images/sponsors/xiaoyou.png" alt="小优店铺" width="220"></a></td>
<td>感谢 <b>小优店铺</b> 赞助了本项目！小优店铺是一家数字商品与账号服务店铺，为本项目的用户提供选购渠道。<a href="https://faka.rainlanguage.top">点此前往</a>。</td>
</tr>  <tr>
  <td width="240"><a href="https://agent.ai-tools.cn" target="_blank"><img src="docs/images/sponsors/vytal.jpg" alt="速创猫 Vytal" width="220"></a></td>
  <td>感谢 <b>速创猫 Vytal</b> 赞助了本项目！速创猫 Vytal 专业的 AI 视频工作流平台，可批量复用的视频工作流，降低内容制作门槛，服务内容创作者、培训机构及中小团队。<a href="https://agent.ai-tools.cn" target="_blank">点此前往</a>。</td>
  </tr>
</table>

</details>

## 截图

<table align="center">
<tr>
<td align="center"><img src="docs/images/overlay.png" width="300" alt="悬浮窗：聊天上方的 Jev 分析面板" /><br/><sub>悬浮窗：危险等级、对方真实意图、排好序的 3 条候选回复</sub></td>
<td align="center"><img src="docs/images/settings.png" width="300" alt="设置页" /><br/><sub>设置页：判断 / 回复 / 视觉三路接口分别可配</sub></td>
</tr>
</table>

## 为什么用它

- **它先判断，再写字。** 大多数工具直接让模型编一句回复。Jev 先用判断模型给出对方真实意图、危险等级、该不该马上回，再据此起草回复。
- **不动你的聊天软件。** 不 hook、不改包、不走任何 App 的接口或账号、不读数据库，只用系统无障碍服务读「屏幕上正在显示的对话」。微信这种混淆节点的也能读到。
- **发送权永远在你手里。** 程序只把回复填进输入框，从不自动发送，不碰转账 / 红包 / 收款。
- **一套内核，多平台。** 微信、QQ、X 真机跑通，飞书靠 OCR 补正文。新增一个 App 只需写一个几十行的适配器。
- **它认识你的人和事。** 本地知识库与联系人档案，分析时自动带上命中的笔记和这个人的历史，回复不会和你的设定打架。
- **接口自己配。** 判断 / 回复 / 视觉三路分别可填，用你自己的密钥和额度，不经过任何中间服务器。
- **隐私在本机。** 密钥存 App 私有空间，聊天内容只在分析那一刻发给你配置的接口，不落盘、不进日志。

## 平台支持

| 平台 | 状态 | 采集方式 | 备注 |
|---|---|---|---|
| 微信 Android | ✅ 全链路 | 伪装系统无障碍服务读气泡节点 | 8.0.52+ 混淆节点，伪装后 8.0.78 实测可读 |
| QQ Android | ✅ 全链路 | 无障碍读节点 | 9.3.50 实测（群聊）；1v1 按同结构推断 |
| X / Twitter 私信 | ✅ 全链路 | 解析 Compose 节点的 content-desc | 12.25 实测，中文界面；英文界面未验 |
| 飞书 / Lark | ✅ OCR 兜底（真机验证） | 无障碍读气泡矩形 + ML Kit 离线 OCR 识别正文 | 正文自绘不在无障碍树里，1.3 起对每个气泡矩形做 OCR；我/对方按已读状态判 |
| 任意其它 App | ✅ 手动 | 悬浮窗菜单「截屏识别一次」整屏 OCR | 不自动、不分我/对方（全部当作对方所说并在面板标注） |
| 桌面端 / 网页 | ⏳ 规划 | 截图 + OCR / 视觉 | 同一内核，换采集方式 |

本项目只读你自己设备上、你自己有权查看的聊天，不针对任何单一平台。

## 快速开始

**1. 装包。** 仓库里有签好名的 release 包：[`apk/jev-assistant-v1.3-release.apk`](apk/jev-assistant-v1.3-release.apk)（Android 11+）。各版本安装包也在 [Releases](https://github.com/jev-chat/jev-chat-jarvis/releases)。

```bash
adb install -r apk/jev-assistant-v1.3-release.apk
```

**2. 填密钥。** 打开 App → 设置 →「接口」分三张卡：判断接口 / 回复接口 / 视觉接口。最简单只填「判断接口」一栏的 [OpenRouter](https://openrouter.ai/) API Key，其余两栏留空会自动继承这把密钥就能用。想换回复模型（默认 `deepseek/deepseek-chat-v3.1`，国内 Gemini / OpenAI 会被区域限制）就在「回复接口」选预设（OpenRouter / DeepSeek 官方 / 通义兼容）或自填地址，每张卡都有独立的一键连通测试。

**3. 开权限。** 按主页向导开三项：

- 无障碍（读消息；升级到 1.3 后需要把无障碍关掉再打开一次，截屏能力才生效）
- 悬浮窗 / 显示在其他应用上层（展示分析）
- 自启动 + 省电无限制（小米 / HyperOS 必做，否则后台被冻结读不到消息）

装过 debug 包的要先卸载再装 release（签名不同），卸载会清掉密钥和设置。小米 / HyperOS 重装后悬浮窗权限会被重置，装完按向导再开一次。

## 功能

### 判断与候选回复

- 判断模型一次给出：对方真实意图、危险等级（1–9）、对方要什么、该不该马上回、最佳动作。约 1 秒，带把握度。
- 生成模型起草 3 条口语化候选，判断模型按「最合适」排序并给出占比。
- 悬浮窗里点一下复制或填入，填入用 `ACTION_SET_TEXT`，失败自动退到剪贴板粘贴，**任何情况下都不发送**。

### 知识库与联系人

在设置 → 分析 →「知识库与联系人」。

- **笔记**：标题 / 内容 / 标签 / 常驻。常驻笔记每次都带；其它笔记要标签或标题出现在会话标题或最近 6 条消息里才带，最多 5 条。支持多行文本粘贴导入，空行分段，每段首行当标题。
- **联系人**：姓名 / 别名（每行一个）/ 关系 / 备注。会话标题匹配姓名或任一别名时生效，自动忽略群名尾部人数、首尾空白和大小写差异。悬浮窗气泡长按可把当前会话一键存为联系人。
- **历史**：「记录聊天历史（只存本机）」默认关闭；开启后每次分析带上最近 N 条（默认 30），并自动去掉屏幕上已经显示过的部分。
- **清除**：知识库与历史都存在 App 私有目录，设置里「清空知识库与历史」一键删除，不进日志、不进 git。
- 悬浮窗面板顶部会显示一行「知识库 N 条 · 历史 M 条」，方便确认到底带了什么。

### 接口与模型

- 判断 / 回复 / 视觉三路的地址、密钥、模型分别可填。
- 内置 OpenRouter、TypeSafe 直连、DeepSeek 官方、通义兼容四套预设，每张卡一键连通测试。
- 只有一把密钥也能用：回复、视觉留空自动继承判断接口的配置。
- 从旧版本升级时，原来那把密钥会一次性迁移到新的三卡结构。

### 采集与 OCR

- 一个 App 一个适配器，服务按前台包名分发，适配器只负责把当前窗口变成「标题 + 消息列表」。
- 无障碍树里没有正文时，自动截屏并用 ML Kit 中文离线模型识别，不上传图片、不需要 Google 服务。
- 截屏有限频和失败退避，不会每秒连拍；识别时会躲开自己的悬浮窗。
- 任何 App 都能在悬浮窗菜单里手动触发「截屏识别一次」。

## 常见问题

<details>
<summary><b>它会替我发消息吗？</b></summary>

不会。程序只把选中的回复填进输入框，发送键永远由你自己点。转账、红包、收款一律不碰。

</details>

<details>
<summary><b>需要 root 或 Xposed 吗？会不会封号？</b></summary>

不需要 root，也不用装任何模块。它不修改聊天软件的安装包、不注入进程、不调用对方 App 的接口或账号体系，只读系统无障碍服务暴露出来的界面内容，和读屏软件的工作方式一样。

</details>

<details>
<summary><b>我的聊天记录会被上传吗？</b></summary>

聊天内容只在你触发分析的那一刻，发给你自己在设置里配置的模型接口。项目没有任何自建服务器，不收集、不落盘、不进日志。历史记录默认关闭，开启后也只存在手机的 App 私有目录里。

</details>

<details>
<summary><b>悬浮球不见了，或者读不到消息怎么办？</b></summary>

多半是国产 ROM 把后台进程冻结了。先确认无障碍、悬浮窗、自启动、省电无限制四项都开着，小米 / HyperOS 尤其要开后两项。重装后悬浮窗权限会被重置，按主页向导再开一次。在聊天界面里随便点一下通常能自愈。

</details>

<details>
<summary><b>飞书里读不到正文？其它 App 能用吗？</b></summary>

飞书的消息正文是自绘控件，无障碍树里没有文字，1.3 起改为对每个气泡矩形做离线 OCR。没有专门适配的 App，可以在悬浮窗菜单里点「截屏识别一次」，整屏 OCR 后同样能分析，只是不区分我方和对方。

</details>

<details>
<summary><b>要花钱吗？</b></summary>

软件本身免费开源。模型调用走你自己的 API Key，按用量在对应服务商那边结算，项目不经手任何费用。

</details>

<details>
<summary><b>升级到 1.3 之后没反应？</b></summary>

把系统设置里的无障碍开关关掉再打开一次。1.3 新增了截屏能力，服务需要重新绑定才会生效。

</details>

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

- **采集**：一个 App 一个适配器，服务按前台包名分发。适配器只负责把当前窗口变成「标题 + 消息列表（谁说的、说了什么）」，下游全部通用；树里没有正文时走截屏 + 离线 OCR 兜底（限频、失败退避，不会每秒连拍）。
- **判断**：[Jev](https://docs.typesafe.ai/) 只回答选择 / 打分 / 是非，一次请求发全部题目，约 1 秒返回；命中知识库时 state 里会带 `background`（关系 + 联系人备注 + 命中笔记）和 `history`（历史消息）。
- **回复**：生成模型起草 3 条候选，Jev 排序；提示词要求回复必须与知识库一致，不编造知识库没有的事实。
- **回填**：`ACTION_SET_TEXT`，失败则剪贴板 + `ACTION_PASTE`，不发送。

<details>
<summary><b>适配一个新的聊天 App</b></summary>

1. 在 `capture/ChatAppAdapter.kt` 实现 `ChatAppAdapter`：`pkg` 是包名，`extract(root, res)` 从无障碍树取出标题和消息列表（`Msg(side, text)`，`side` 为 `me` / `other`），不在聊天窗时返回 `null`。
2. 在 `capture/ChatCaptureService.kt` 的 `adapters` 加一行。
3. 判断、候选、悬浮窗、填入都不用动。

先用 `adb shell uiautomator dump` 看目标 App 暴露了什么，已有四个适配器覆盖了四种情况：

| App | 树的情况 | 适配器怎么做 |
|---|---|---|
| QQ | 节点开放，有 id | 正文 `id/mjn`、标题 `id/371`，按气泡贴哪侧头像判谁说的 |
| 微信 | 对普通无障碍服务混淆节点 | 服务类名伪装成系统的 `SelectToSpeakService`，读 `id/bkl` 气泡，按左右判 |
| X | Compose，无 id，text 为空 | 解析 content-desc `发件人：正文。时间。Read`，发件人是「你」即我方 |
| 飞书 | 正文自绘，树里没有文字 | 树上拿 bubble_content_container 矩形与已读状态，OCR 每个矩形的正文 |

适配器返回 `null` 表示不在聊天窗，返回空消息列表表示在聊天窗但树里没正文——只有后者会触发 OCR 兜底。

QQ、X 全程只有一个 Activity，判「是不是聊天窗」要看树里有没有该有的节点（如输入框），不能看 Activity 名。

</details>

<details>
<summary><b>构建与目录结构</b></summary>

JDK 17 + Android SDK（platform 35 / build-tools 35）。

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 需要仓库外的签名 properties，路径由 JEV_KEYSTORE_PROPS 指定
```

- `app/` — Android 应用（Kotlin，传统 View）
  - `capture/` 无障碍采集：`ChatAppAdapter.kt` 各 App 适配器、`ChatCaptureService.kt` 分发服务、前台保活、`ocr/` 截屏与离线识别
  - `jev/` Jev 客户端与题目集 · `overlay/` 悬浮窗 · `core/` 配置与数据模型（含 `core/kb/` 知识库存储与上下文构建）
  - `KnowledgeActivity` 知识库管理页（笔记 / 联系人）
- `tools/jev/` — Jev 题目集与校准脚手架（Python）
- `docs/` — 设计与验收文档
- `apk/` — 签好名的 release 包

</details>

## 已知限制

- **国产 ROM 后台冻结**：小米 / HyperOS 会杀后台进程，前台保活、自启动、省电无限制都配了仍可能被杀，气泡短暂消失，在聊天里再交互一下自愈。
- **飞书正文靠 OCR**：飞书正文是自绘控件，无障碍树里只有气泡矩形，1.3 起对每个矩形做离线 OCR；我 / 对方按已读状态判断，判反时请用「存为联系人」并在备注里说明，或关掉自动分析改手动。
- **X 只按中文界面验过**：分隔符 `：`、`上午 / 下午`、`Read` 是中文界面实测；英文界面只做了兜底，未验。
- **群聊**：按一对一分析，「对方」与关系设定对群聊不准。
- **中文**：Jev 主训练语言是英文，题目用英文、聊天内容保留中文；建议用自己的真实对话做一批标注校准（见 `tools/jev/`）。
- 伪装无障碍服务是绕过微信混淆的手段，微信版本更新可能失效。
- **知识库检索是标签/标题包含匹配**，不做语义检索，笔记请打好标签才能被命中。历史按「谁说 + 原文」去重，同一个人重复说同一句只记一次。
- **OCR 依赖系统放行截屏**：无障碍服务要被系统允许截屏才能用，小米 / HyperOS 可能拒绝（面板会提示失败原因）；受保护窗口（`FLAG_SECURE`）截不到。
- **OCR 只认屏幕上看得见的部分**：长消息被截断的部分读不到；识别有错字。
- **包体变大**：ML Kit 中文离线模型让 APK 从约 12 MB 增至约 27 MB，且只打 arm64-v8a。

## 交流群 / 需求收集

**如需联系，请公众号私信。** 合作、赞助、反馈、进群失败、二维码过期，都走公众号私信，其它渠道不一定看得到。

<p align="center"><img src="docs/images/wechat-mp.png" width="180" alt="公众号二维码" /></p>

想听真实需求：你在哪个聊天 App 上最想要这个副驾？希望它判断什么、怎么提示、什么绝对不能碰？扫码进群直接说。**1 至 7 群已满，不要再扫；8、9 群任选一个，请勿重复加入。**

<table align="center"><tr>
  <td align="center"><img src="docs/images/wechat-group-8.png" width="200" alt="8 群" /><br/><b>8 群</b></td>
  <td align="center"><img src="docs/images/wechat-group-9.png" width="200" alt="9 群" /><br/><b>9 群</b></td>
</tr></table>

<p align="center"><sub>以下七群已满，请勿再扫：</sub></p>

<table align="center"><tr>
  <td align="center"><img src="docs/images/wechat-group-1.png" width="80" alt="1 群（已满）" /><br/><sub>1 群</sub></td>
  <td align="center"><img src="docs/images/wechat-group-2.png" width="80" alt="2 群（已满）" /><br/><sub>2 群</sub></td>
  <td align="center"><img src="docs/images/wechat-group-3.png" width="80" alt="3 群（已满）" /><br/><sub>3 群</sub></td>
  <td align="center"><img src="docs/images/wechat-group-4.png" width="80" alt="4 群（已满）" /><br/><sub>4 群</sub></td>
  <td align="center"><img src="docs/images/wechat-group-5.png" width="80" alt="5 群（已满）" /><br/><sub>5 群</sub></td>
  <td align="center"><img src="docs/images/wechat-group-6.png" width="80" alt="6 群（已满）" /><br/><sub>6 群</sub></td>
  <td align="center"><img src="docs/images/wechat-group-7.png" width="80" alt="7 群（已满）" /><br/><sub>7 群</sub></td>
</tr></table>

<p align="center"><sub>群二维码 7 天有效（本批到 2026-09-29），过期了公众号私信要新码。</sub></p>

## 姊妹项目

同在 [jev-chat](https://github.com/jev-chat) 组织下：

- [Jev 聊天助手 macOS 版](https://github.com/jev-chat/jev-chat-mac)：微信消息意图识别悬浮窗，看屏 + 本地小模型判断意图和风险，再按话术生成回复候选，纯只读。
- [Jev 聊天助手 Windows 版](https://github.com/jev-chat/jev-chat-windows)：微信 Windows 4.x 旁挂的回复辅助，窗口截图 + 本地离线 OCR，3 条候选一键填入，发送永远手动。
- [微墨 WeChat Ink](https://github.com/Snowwit88/wechat-ink)：微信公众号写作、配图与排版助手，支持资料核验、学术风图文和草稿发布。

## 版权与许可

Copyright © 2026 Finderchangchang 与 jev-chat 贡献者。代码以 [MIT](LICENSE) 协议开源，另见 [NOTICE](NOTICE)。

- **可以商用**：个人和公司都可以使用、修改、再分发，或集成进自己的产品，不需要付费或事先授权。
- **必须注明出处**：分发或商用时保留 LICENSE 与 NOTICE，并在产品「关于」页、说明文档或发布页写明来源。推荐写法：`基于 Jev 聊天助手（https://github.com/jev-chat/jev-chat-jarvis）二次开发`。
- 不要用「Jev 聊天助手」「jev-chat」名称或 chatjevs.com 域名暗示由原作者出品或背书。

**免责声明**：本项目只处理你自己设备上、你自己有权查看的聊天。请遵守微信、QQ、X、飞书等各软件的许可协议与当地法律法规，作者不对使用后果负责。

## ☕ 请我喝杯咖啡

如果你觉得我写的这玩意儿对你有点帮助，欢迎请我喝杯咖啡。咖啡因一到位，脑子就开始冒泡，源源不断地驱动我往前跑；哪天我更新得特别勤，说明这杯续上了 😄

<p align="center">
  <img src="docs/images/donate/wechat-donate-v3.png" width="260" alt="微信赞赏码（姓名已隐去）" />
</p>

<p align="center"><sub>随手支持，不用有压力；不支持也没关系，点个 Star 或提条建议同样能让我开心很久。</sub></p>
