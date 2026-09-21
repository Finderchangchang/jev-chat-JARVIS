# Jev 聊天助手 (Jev Chat Assistant)

一个**非侵入式**的实时对话理解与回复辅助层——挂在任意聊天窗口旁边，读懂对方在说什么，用 [TypeSafe **Jev**](https://typesafe.ai/) 判断模型给出「对方真实意图 / 危险等级 / 该不该马上回 / 最佳动作」，再用一个生成式模型起草 3 条候选回复并让 Jev 排序，最后以半透明悬浮窗展示，一键**填入**输入框。

> **目标是全平台。** 微信（Android）只是我们跑通可行性的第一站。核心不依赖任何 App 的接口或账号——它只读「当前屏幕上正在发生的对话」，所以同一套 Jev 判断 + 生成排序内核可以平移到其它 IM、桌面端、乃至任何有聊天的地方——**手机 QQ** 已经这样接进来了，**飞书（Lark）** 采集分发已接入、正文待补。
>
> **发送始终由你手动点。** 程序只读消息、只把回复填进输入框，从不自动发送、不碰转账/红包/收款。

<p align="center"><em>A non-invasive, real-time conversation-understanding layer that sits beside any chat surface. It reads whatever conversation is on screen (no app integration, no account), uses Jev for typed judgments plus a generative model for 3 ranked candidate replies, shows them in a translucent overlay, and fills the input box — you press send. WeChat on Android is just the first platform we proved it on.</em></p>

## 项目目标

- **一层通用的「对话副驾」**：不是再造一个聊天软件，而是浮在你已在用的**任何**聊天之上的分析层。看得懂语义、给得出该怎么回，你保留最终决定权（只填入不发送、不碰转账/红包/收款）。
- **非侵入 = 可跨平台的前提**：不 hook、不改包、不走对方 App 的 API，只从屏幕采集正在显示的对话。换平台换的只是「采集方式」，判断与生成内核不变：
  - **Android 各类 App**：无障碍读屏——微信已跑通；**手机 QQ（`com.tencent.mobileqq`，9.3.50 实测节点开放，正文 `id/mjn`）** 已接入并真机跑通全链路；**飞书（Lark，`com.ss.android.lark`）** 已接入同一套采集分发：会话标题、气泡位置、输入框都能拿到，判断 → 候选 → 填入整条链在飞书里真机跑通。但飞书的消息正文是自绘控件、不在无障碍树里，正文采集要补「截图 + 本地 OCR」（进行中，见已知限制）
  - **桌面端 / 控件树被隐藏的场景**：截图 + OCR/视觉提取文本
  - 采集出的文本 → 同一个 **Jev 判断 + 生成模型起草 + Jev 排序** → 同一套悬浮窗展示
- **已验证**：微信 Android 端（8.0.78 实测）——伪装无障碍服务读到聊天节点、Jev 判断 + DeepSeek 起草 + Jev 排序、悬浮窗填入，闭环打通；手机 QQ（9.3.50 实测）——同一套内核换一个适配器，采集 / 判断 / 候选 / 填入全链路跑通。
- **下一步**：飞书正文走「截图 + OCR」补齐；再扩展到更多 IM / 桌面端 / 网页。

> 说明：微信、QQ、飞书等都是**通用聊天场景**的适配对象；本项目只读你自己设备上、你自己有权查看的聊天，不针对任何单一平台。

## 界面截图

<p align="center">
  <img src="docs/images/overlay.png" width="320" alt="悬浮窗实拍：微信聊天上方的 Jev 分析面板" />
  &nbsp;&nbsp;
  <img src="docs/images/settings.png" width="320" alt="设置页：接口 / 分析 / 外观" />
</p>

- **左：悬浮窗实拍**——挂在微信聊天上方的半透明面板：危险等级（如「危险 1/9 安全」）、对方真实意图与把握度、Jev 排好序的候选回复（每条带占比，可**复制**或**填入**输入框，发送始终你自己点）。
- **右：设置页**——接口（OpenRouter 密钥、回复生成模型）、分析（关系描述、会话白名单、对方发消息时自动分析）、外观（悬浮窗不透明度）。

## 它怎么工作

```
微信 / QQ / 飞书聊天 ──(无障碍读节点)──▶ 采集最近消息
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

- **采集**：一个 App 一个适配器（`app/src/main/java/com/jev/probe/capture/ChatAppAdapter.kt`），服务按前台包名分发；适配器只负责把当前窗口变成「标题 + 消息列表（谁说的、说了什么）」，下游判断 / 悬浮窗 / 填入全部通用。微信适配器读聊天气泡（`com.tencent.mm:id/bkl`），按气泡位置判断谁说的；QQ 适配器读正文节点（`com.tencent.mobileqq:id/mjn`，QQ 不混淆节点），标题取 `id/371`，按气泡哪边贴着头像列判断谁说的。微信 8.0.52+ 对普通无障碍服务混淆节点，所以服务类名伪装成系统的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService` 才能读到（实测微信 8.0.78 有效）。
- **判断**：[Jev](https://docs.typesafe.ai/)（System One 判断模型）只回答选择/打分/是非，一次请求发全部题目，约 1 秒返回。
- **回复**：生成式模型（默认 DeepSeek）起草 3 条候选，Jev 排序。
- **回填**：`ACTION_SET_TEXT` / 剪贴板 `ACTION_PASTE` 把选中的回复填进输入框，**不发送**。

## 适配一个新的聊天 App

1. 在 `capture/ChatAppAdapter.kt` 里实现 `ChatAppAdapter`：`pkg` 是目标 App 包名，`extract(root, res)` 从当前窗口的无障碍树里取出会话标题和消息列表（`Msg(side, text)`，`side` 是 `me` / `other`），当前窗口不是聊天时返回 `null`。
2. 在 `capture/ChatCaptureService.kt` 的 `adapters` 列表里加一行。
3. 其余不用动：判断、候选、悬浮窗、填入（`findEditable` 找可编辑输入框）都是通用的。

先用 `adb shell uiautomator dump` 看目标 App 暴露了哪些节点：像 QQ 这样节点开放的，照着 id 写就行；像微信这样混淆节点的，要靠伪装服务才看得到；像飞书这样正文自绘的，正文要另走 OCR。

## 下载安装

不想自己编译，直接装仓库里编好的包：[`apk/jev-assistant-v1.1-release.apk`](apk/jev-assistant-v1.1-release.apk)（2026-09-21 构建，release 签名，Android 11+）。

```bash
adb install -r apk/jev-assistant-v1.1-release.apk
```

之前装过 debug 包的要先卸载再装（签名不同，覆盖会失败），卸载会清掉已填的密钥和设置。小米 / HyperOS 重装后悬浮窗权限会被重置，装完按主页向导再开一次。

## 构建

需要 JDK 17 + Android SDK（platform 35 / build-tools 35）。

```bash
# 用 JAVA_HOME 指向 JDK 17，local.properties 里写 sdk.dir
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# release 签名包：把密钥库信息写在仓库外的 properties 文件里
# （storeFile / storePassword / keyAlias / keyPassword），路径由 JEV_KEYSTORE_PROPS 指定
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 配置与授权

1. 装 APK（见上面「下载安装」，或自己构建），打开「Jev 聊天助手」。
2. 在**设置**里填你自己的 [OpenRouter](https://openrouter.ai/) API Key（走 `POST /api/alpha/decisions` 调 Jev），选回复生成模型（默认 `deepseek/deepseek-chat-v3.1`；国内 Gemini/OpenAI 会被区域限制）。
3. 按主页向导开三项权限：
   - **无障碍**（读消息）
   - **悬浮窗 / 显示在其他应用上层**（展示分析）
   - **自启动 + 省电无限制**（小米/HyperOS 必做，否则后台进程被冻结、读不到消息）

密钥只存在 App 私有存储，不出设备、不进日志。

## 已知限制

- **国产 ROM 后台冻结**：小米/HyperOS 会激进地杀后台进程，即使配了前台保活、自启动、省电无限制仍可能被杀——被杀后气泡会短暂消失，需在聊天 App 里再交互一下自愈。这是所有「无障碍+悬浮窗」类 App 的公认难题。
- **飞书正文**：飞书 Android 端的消息正文由自绘控件渲染，无障碍树里只有气泡的位置和大小，没有文字（`uiautomator dump` 与伪装服务读到的一致）。目前飞书里能分析到的只有文档卡片等带 TextView 的内容，正文要补「`AccessibilityService.takeScreenshot()` 裁气泡区 + ML Kit 中文识别」。飞书默认左对齐布局下「我 / 对方」也不能按左右判，要另找依据（如已读状态）。
- **群聊**：目前按一对一分析，「对方」与关系设定对群聊不准。
- **中文**：Jev 主训练语言是英文，题目 instructions/criteria 用英文、聊天内容保留中文；上线前建议用自己的真实对话做一批标注校准（见 `tools/jev/`）。
- 伪装无障碍服务是绕过微信节点混淆的手段，微信版本更新可能失效。

## 目录

- `app/` — Android 应用（Kotlin，传统 View，无 Compose）
  - `capture/` 无障碍采集（`ChatAppAdapter.kt` 各 App 适配器、`ChatCaptureService.kt` 分发服务）与前台保活 · `jev/` Jev 客户端与题目集 · `overlay/` 悬浮窗 · `core/` 配置与数据模型
- `tools/jev/` — Jev 题目集与校准脚手架（Python，PC 上跑）
- `docs/` — 设计与验收文档

## 免责声明

仅供个人学习与研究使用。只处理你自己设备上、你自己有权查看的聊天。请遵守微信、QQ、飞书等各软件的许可协议与当地法律法规。作者不对使用后果负责。

## License

[MIT](LICENSE)

## 交流群 / 需求收集

项目刚起步，想听真实需求：你在哪个聊天 App 上最想要这个副驾？希望它判断什么、怎么提示、什么绝对不能碰？扫码进微信群直接说。

**1 群已满。** 2、3、4 群任选一个扫码加入，**请勿重复加入**，内容完全一样。

<p align="center">
  <img src="docs/images/wechat-group-2.png" width="200" alt="jev-chat-JARVIS 2 群" />
  &nbsp;&nbsp;
  <img src="docs/images/wechat-group-3.png" width="200" alt="jev-chat-JARVIS 3 群" />
  &nbsp;&nbsp;
  <img src="docs/images/wechat-group-4.png" width="200" alt="jev-chat-JARVIS 4 群" />
</p>

二维码 7 天有效（本批到 2026-09-28），过期了请开一个 [issue](https://github.com/Finderchangchang/jev-chat-JARVIS/issues) 留言，会更新。
