# Jev 聊天助手 (Jev Chat Assistant)

一个挂在微信旁边的**非侵入式**聊天辅助器（Android）。它读取你当前打开的微信聊天，用 [TypeSafe **Jev**](https://typesafe.ai/) 判断模型给出「对方真实意图 / 危险等级 / 该不该马上回 / 最佳动作」，再用一个生成式模型起草 3 条候选回复并让 Jev 排序，最后以半透明悬浮窗展示，一键**填入**微信输入框。

> **发送始终由你手动点。** 程序只读消息、只把回复填进输入框，从不自动发送、不碰转账/红包/收款。

<p align="center"><em>An on-screen assistant for WeChat: it reads the open chat via an accessibility service, uses Jev for typed judgments plus a generative model for 3 ranked candidate replies, shows them in a translucent overlay, and fills the input box — you press send.</em></p>

## 它怎么工作

```
微信聊天 ──(无障碍读节点)──▶ 采集最近消息
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

- **采集**：无障碍服务读取微信聊天气泡（`com.tencent.mm:id/bkl`），按气泡位置判断谁说的。微信 8.0.52+ 对普通无障碍服务混淆节点，所以服务类名伪装成系统的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService` 才能读到（实测微信 8.0.78 有效）。
- **判断**：[Jev](https://docs.typesafe.ai/)（System One 判断模型）只回答选择/打分/是非，一次请求发全部题目，约 1 秒返回。
- **回复**：生成式模型（默认 DeepSeek）起草 3 条候选，Jev 排序。
- **回填**：`ACTION_SET_TEXT` / 剪贴板 `ACTION_PASTE` 把选中的回复填进输入框，**不发送**。

## 构建

需要 JDK 17 + Android SDK（platform 35 / build-tools 35）。

```bash
# 用 JAVA_HOME 指向 JDK 17，local.properties 里写 sdk.dir
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 配置与授权

1. 装 APK，打开「Jev 聊天助手」。
2. 在**设置**里填你自己的 [OpenRouter](https://openrouter.ai/) API Key（走 `POST /api/alpha/decisions` 调 Jev），选回复生成模型（默认 `deepseek/deepseek-chat-v3.1`；国内 Gemini/OpenAI 会被区域限制）。
3. 按主页向导开三项权限：
   - **无障碍**（读消息）
   - **悬浮窗 / 显示在其他应用上层**（展示分析）
   - **自启动 + 省电无限制**（小米/HyperOS 必做，否则后台进程被冻结、读不到消息）

密钥只存在 App 私有存储，不出设备、不进日志。

## 已知限制

- **国产 ROM 后台冻结**：小米/HyperOS 会激进地杀后台进程，即使配了前台保活、自启动、省电无限制仍可能被杀——被杀后气泡会短暂消失，需在微信里再交互一下自愈。这是所有「无障碍+悬浮窗」类 App 的公认难题。
- **群聊**：目前按一对一分析，「对方」与关系设定对群聊不准。
- **中文**：Jev 主训练语言是英文，题目 instructions/criteria 用英文、聊天内容保留中文；上线前建议用自己的真实对话做一批标注校准（见 `tools/jev/`）。
- 伪装无障碍服务是绕过微信节点混淆的手段，微信版本更新可能失效。

## 目录

- `app/` — Android 应用（Kotlin，传统 View，无 Compose）
  - `capture/` 无障碍采集与前台保活 · `jev/` Jev 客户端与题目集 · `overlay/` 悬浮窗 · `core/` 配置与数据模型
- `tools/jev/` — Jev 题目集与校准脚手架（Python，PC 上跑）
- `docs/` — 设计与验收文档

## 免责声明

仅供个人学习与研究使用。只处理你自己设备上、你自己有权查看的聊天。请遵守微信软件许可协议与当地法律法规。作者不对使用后果负责。

## License

[MIT](LICENSE)
