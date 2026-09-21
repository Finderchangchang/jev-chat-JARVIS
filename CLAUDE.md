# Jev 聊天助手（安卓）— 全平台非侵入对话副驾

挂在任意聊天 App 旁边（微信、飞书已适配）的非侵入式助手：读到对方最新消息 → 调 Jev 判断 → 悬浮窗给出分析和 3 条候选回复（Jev 排序）→ 人一键填入微信输入框。**发送永远由人手动点，程序不自动发。**

## 硬约束（所有人必须遵守）

1. **不 hook、不 Xposed、不改目标 App、不读其数据库**。只用系统无障碍服务与截屏。
2. **绝不自动发送消息**，绝不点微信的发送按钮。填入输入框后停手。
3. **不碰钱**：不触碰转账、红包、收款码相关任何界面元素。
4. **路径全 ASCII**：Android 构建工具在 Windows 上不接受中文路径。项目只能在 `H:\ai_tool\jev-android`。
5. **密钥不落盘、不进日志、不进 git**：OpenRouter key 从环境变量 `OPENROUTER_API_KEY` 读，或 App 加密设置项。任何文件里都不许出现 `sk-or-` 开头的字符串。
6. 编码一律 UTF-8。Windows 中文环境下 PowerShell 用 pwsh 7+，Python 读写文件必须显式 `encoding='utf-8'`。
7. 禁止 `git commit` / `git push`，由人拍板。

## 技术栈

- Kotlin，传统 View + XML，**不用 Compose**
- minSdk 30，compileSdk / targetSdk 35
- JDK 17（`H:\android\jdk`），Android SDK 在 `H:\android\sdk`，Gradle 缓存 `H:\android\gradle-home`
- 目标机：小米 14（houji / 23127PN0CC），HyperOS 3.0 / Android 16 (SDK 36)，微信 8.0.78

## 关键背景（2026-09-21 实测结论，别重复踩）

- 微信 8.0.52 起对普通无障碍服务**混淆/隐藏节点**。本机实测 `uiautomator dump` 对微信任何界面只返回一个空根节点。
- 社区绕法：把无障碍服务的类名注册成系统内置的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`。**对 8.0.78 是否仍有效未验证，这就是探针 App 要回答的问题。**
- 兜底路线：无障碍服务的 `takeScreenshot()` + 本地 OCR（ML Kit），同样零 token。
- 飞书 Android（2026-09-21 实测）：消息正文自绘，无障碍树里**没有文字**（伪装服务与 `uiautomator dump` 一致），只有 `bubble_content_container` 气泡位置、`group_name` 标题、`kb_rich_text_content` 输入框；正文要走 takeScreenshot + OCR。飞书默认左对齐布局，我/对方不能按左右判。
- 采集层按 App 分发：`capture/ChatAppAdapter.kt` 一个 App 一个适配器，`ChatCaptureService` 按前台包名查表；下游通用。
- Jev = TypeSafe 的判断模型，只回答选择题/打分/是非，不生成文字。走 OpenRouter：
  `POST https://openrouter.ai/api/alpha/decisions`，model `typesafe/jev-1.13`，
  body `{model, state, questions}`，答案在 `answers`。实测 7 题一次约 900 ms、约 1000 输入 token、0.00004 美元。
- Jev 主训练语言是英文：**题目的 instructions 和 criteria 用英文写，state 里的聊天内容保留中文原文。**

## 目录与文件锁

| 目录 | 归属 | 说明 |
|---|---|---|
| `app/`、`gradle/`、根 gradle 文件 | Android 构建方 | 安卓工程 |
| `tools/jev/` | Jev 判断方 | Python 题目集与校准脚手架，PC 上跑 |
| `docs/` | 主控 | 验收标准、报告 |
| `_reports/` | 所有人 | 每个任务的交付报告写这里 |

跨边界的问题**只报告，不改**，由主控收口。

## 报告格式

任务完成后写 `_reports/<任务名>_report.md`：根因或做法（带证据）→ 改了什么（逐条列文件）→ 验收命令的**真实输出**（贴原文，不许编）→ **自验缺口**（没验到的明说）。
