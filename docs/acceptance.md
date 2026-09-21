# 验收标准（公共尺子）

所有任务以此为准。**建者不自证，贴真实输出。**

## A. 构建

```
H:\ai_tool\jev-android\gradlew.bat assembleDebug
```
零 error。产物 `app/build/outputs/apk/debug/app-debug.apk` 存在。

## B. 探针门禁（P1，决定整个项目走哪条路线）

装上探针 App，开启无障碍服务，手机停在微信**某个聊天窗口**，然后：

```
adb shell am broadcast -a com.jev.probe.DUMP
adb shell run-as com.jev.probe ls -l files/
```

判定：
- **PASS-A**：dump 文件里含有聊天气泡文本（能看到消息内容、`android.widget.TextView`、微信包名 `com.tencent.mm` 的节点），且节点数 > 20。→ 走路线 A（无障碍实时文字）
- **FAIL**：只有空根节点或节点数 ≤ 3。→ 走路线 B（截屏 + 本地 OCR）
- 对照组（普通类名服务）结果必须一并记录，用来证明"伪装"是否是有效变量。

同时验证 `takeScreenshot()` 能否拿到微信界面位图（存 PNG，非全黑）。

## C. Jev 判断层

```
python tools/jev/calibrate.py
```
输出每道题在标注集上的命中率表格 + 置信度分布。要求：
- 标注集不少于 20 条中文对话片段
- `danger_level` 打分与人工标注的平均绝对误差 < 1.0 档
- `true_intent` / `she_needs` 命中率 ≥ 60%
- 全部请求 HTTP 200，无 key 泄漏到 stdout

## D. 真机冒烟（最后一次验证，只跑一遍）

1. 对方发来新消息后 **1.5 秒内**悬浮窗出现分析，候选回复不少于 3 条且已排序
2. 点"填入"后微信输入框出现文本，**且未发送**
3. 自己发的消息不触发；切换会话上下文重置；微信切后台悬浮窗隐藏
4. 密钥不出现在 logcat；断网时给可读错误不崩溃
5. 10 分钟静默期 Jev 调用次数为 0
