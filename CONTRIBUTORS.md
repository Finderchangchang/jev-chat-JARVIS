# 贡献者

Jev 聊天助手是一个三端并行的开源项目，只读屏幕、不注入、不 hook、不替你发送。这份名单记录谁在做哪一部分，方便使用者知道该找谁，也方便新贡献者找到入口。

## 维护者与分工

| 平台 | 仓库 | 主要维护者 |
|---|---|---|
| Android（主入口） | [jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis) | [@Finderchangchang](https://github.com/Finderchangchang) |
| macOS | [jev-chat-jarvis-mac](https://github.com/jev-chat/jev-chat-jarvis-mac) | [@eatmoreduck](https://github.com/eatmoreduck) |
| Windows | [jev-chat-windows](https://github.com/jev-chat/jev-chat-windows) | [@rezoch340](https://github.com/rezoch340) |
| 三端测试 | 全部仓库 | [@HeiGeAi](https://github.com/HeiGeAi) |
| 组织与项目管理 | [jev-chat](https://github.com/jev-chat) | [@Finderchangchang](https://github.com/Finderchangchang) |
| 官网与组织页 | [jev-chat.github.io](https://github.com/jev-chat/jev-chat.github.io) | [@Finderchangchang](https://github.com/Finderchangchang) |

## 三端支持范围

| 平台 | 当前覆盖 |
|---|---|
| Android | QQ、X / Twitter 私信支持全链路，飞书支持 OCR 兜底；其它未适配 App（微信除外）支持手动「截屏识别一次」 |
| macOS | 桌面聊天窗口，看屏 + 本地小模型 |
| Windows | 桌面聊天窗口，窗口截图 + 离线 OCR |

三端共用同一套判断内核，差异在采集方式：Android 走无障碍节点加离线 OCR，macOS 与 Windows 走屏幕录制加 OCR。

## 贡献者

### 测试

- [@HeiGeAi](https://github.com/HeiGeAi)：三端真机验证与问题复现，覆盖 Android、macOS、Windows 的日常使用场景

### Android

- [@Finderchangchang](https://github.com/Finderchangchang)

### macOS

- [@eatmoreduck](https://github.com/eatmoreduck)
- [@shanyazhou](https://github.com/shanyazhou)
- [@baishatan](https://github.com/baishatan)
- [@xixikaixin](https://github.com/xixikaixin)
- [@liubai00](https://github.com/liubai00)

### Windows

- [@rezoch340](https://github.com/rezoch340)

## 想参与

三条项目红线，任何一条被破坏的改动都不会被接受：

1. **纯只读**。不注入目标 App、不 hook、不解密数据。
2. **发送永远手动**。程序不替你按发送键，填入只是把候选放进输入框。
3. **只处理你自己有权查看的聊天**。

贡献流程、认领规则与自测要求写在 macOS 仓库的 [CONTRIBUTING.md](https://github.com/jev-chat/jev-chat-jarvis-mac/blob/master/CONTRIBUTING.md)，同样适用于另外两端：先认领再动手、一个 PR 只做一件事、改哪层跑哪层的自测。

联系与需求反馈走公众号私信，或加入交流群（见各仓库 README）。

## 许可证

代码以 MIT 协议开源。使用名称或域名时不得暗示由原作者出品或背书，详见各仓库根目录的 [NOTICE](https://github.com/jev-chat/jev-chat-jarvis/blob/main/NOTICE)。
