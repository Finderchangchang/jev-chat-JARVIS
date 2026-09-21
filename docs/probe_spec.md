# 探针 App 技术规格（P1 门禁）

**唯一目的**：回答一个问题——伪装成系统无障碍服务，能不能读到微信 8.0.78 的聊天节点文字？

结论决定整个项目走路线 A（无障碍实时文字，毫秒级）还是路线 B（无障碍截屏 + 本地 OCR，约 0.5 秒）。

## 核心机制：为什么"伪装"可能有效

微信 8.0.52 起对普通第三方无障碍服务混淆/隐藏节点树。社区做法是把无障碍服务的**类全名**注册成系统内置的那个：

```
com.google.android.accessibility.selecttospeak.SelectToSpeakService
```

关键点：**Android 的无障碍服务用「包名/类全名」作为组件标识**。我们的 App 包名是 `com.jev.probe`，服务类全名是上面那串，组件就是
`com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService`，
与系统 TalkBack 包下的同名类**不冲突**，可以共存。假设成立的前提是：微信只比对类名字符串，不校验包名和签名。**这个假设正确与否，就是本探针要测的。**

早期社区版本用的是 `com.google.android.marvin.talkback.TalkBackService`，但在小米机型上会导致屏幕常驻朗读提示，**不要用它**。

## 必须做 A/B 对照

只测伪装服务无法证明伪装是有效变量。两个服务都要有，分别独立开关：

| 组 | 服务类全名 | 作用 |
|---|---|---|
| 实验组 | `com.google.android.accessibility.selecttospeak.SelectToSpeakService` | 伪装 |
| 对照组 | `com.jev.probe.PlainAccessibilityService` | 普通命名 |

两组代码逻辑必须**完全一致**，只有类名和注册信息不同。测试时一次只开一个。

## 功能清单

1. **节点树 dump**
   - 遍历 `rootInActiveWindow`，递归所有子节点
   - 每个节点记录：`className`、`viewIdResourceName`、`text`、`contentDescription`、`packageName`、`bounds`、`isClickable`、`isEditable`、深度
   - 输出 JSON 到 `getExternalFilesDir(null)/dumps/dump_<组名>_<时间戳>.json`
   - 同时把摘要打进 logcat，tag 固定为 `JEVPROBE`：节点总数、有文本的节点数、前 10 条文本各截前 10 个字
2. **手动触发**：注册 BroadcastReceiver，`adb shell am broadcast -a com.jev.probe.DUMP` 立即 dump 一次当前前台窗口。这是主要测试手段，不要依赖事件自动触发。
3. **事件观察**：`onAccessibilityEvent` 里只记录事件类型和来源包名到 logcat，不要在事件里做重活。
4. **截屏能力验证**：收到 `com.jev.probe.SHOT` 广播时调 `AccessibilityService.takeScreenshot()`（API 30+），存 PNG 到同目录。用来验证路线 B 的可行性（重点：这个 API 不弹录屏授权框）。
5. **MainActivity**：三个按钮——打开无障碍设置页、立即 dump、显示最近一次 dump 的摘要（节点数、有文本节点数）。界面丑无所谓。

## 配置要点（容易写错的地方）

`res/xml/` 下两份独立的 service config：

- `android:canRetrieveWindowContent="true"` 必须开
- `accessibilityFlags` 至少包含 `flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews`
- `android:accessibilityEventTypes="typeAllMask"`
- **不要**用 `android:packageNames` 限定只监听微信——限定了就没法跟别的 App 做对照，测不出"是微信特意挡我"还是"我服务本身就没起来"
- `android:isAccessibilityTool="true"`（API 29+），否则在部分 ROM 的无障碍列表里归类异常
- `android:notificationTimeout` 设 100

Manifest 里服务声明：

```xml
<service
    android:name="com.google.android.accessibility.selecttospeak.SelectToSpeakService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter android:priority="10000">
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data android:name="android.accessibilityservice"
               android:resource="@xml/config_disguised" />
</service>
```

对照组同理，换 `android:name` 和 `@xml/config_plain`。

## 验收（真机）

手机停在微信**某个聊天窗口**，只开实验组服务：

```
adb shell am broadcast -a com.jev.probe.DUMP
adb logcat -d -s JEVPROBE | tail -20
adb shell ls /sdcard/Android/data/com.jev.probe/files/dumps/
adb pull /sdcard/Android/data/com.jev.probe/files/dumps/<最新文件>
```

判定：
- **PASS**：节点数 > 20 且能看到聊天气泡的中文文本
- **FAIL**：节点数 ≤ 3 或全部 text 为空

然后关实验组、只开对照组，重复一遍，记录差异。再把同样的 dump 在一个普通 App（比如系统设置）上各跑一次，作为"服务本身是好的"的基线。

四组结果必须都记进报告：

| | 微信聊天页 | 系统设置页 |
|---|---|---|
| 伪装服务 | ? | ? |
| 普通服务 | ? | ? |

## 红线

- 探针只读不写：不许调用任何 `performAction`（点击、输入、滑动一律禁止）
- 不许触碰转账、红包、收款相关界面
- dump 文件留在手机和本地，不上传任何服务器
- 不许自动发送任何消息
