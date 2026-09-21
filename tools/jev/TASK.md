# 任务：搭 Jev 判断层的题目集 + 校准脚手架（Python，PC 上跑）

你在 `H:\ai_tool\jev-android`。**先读 `CLAUDE.md` 和 `docs/acceptance.md`**，里面有硬约束和验收标准。

## 背景

我们在做一个挂在微信旁边的聊天辅助器。对方发来消息后，程序把最近若干条对话交给 **Jev**（TypeSafe 的判断模型，只回答选择题/打分/是非，不生成文字），拿到"她真实意图是什么、危险等级多少、该不该马上回、最佳动作是什么"等判断，再把 3 条候选回复交给 Jev 排序，最后人自己决定发不发。

Jev 的接口（已实测通，别改协议）：

```
POST https://openrouter.ai/api/alpha/decisions
Authorization: Bearer $OPENROUTER_API_KEY
Content-Type: application/json

{
  "model": "typesafe/jev-1.13",
  "state": { ... 任意 JSON，放聊天内容 ... },
  "questions": {
    "题目名": {
      "type": "noul" | "choice" | "score",
      "instructions": "英文问题",
      "criteria": ...
    }
  }
}
```

- `noul`：是非题。`criteria` 可选，形如 `{"true": "...", "false": "..."}`。返回 `{"type":"noul","noul":0.0~1.0}`
- `choice`：单选。`criteria` 必填，形如 `{"key": "英文描述"}`，最多 255 项。返回 `{"type":"choice","choice":"key","probabilities":{...},"confidence":0~1}`
- `score`：分档打分。`criteria` 必填，是**有序数组**，2~10 档，每档写**具体情景**不写抽象程度（官方明确要求）。返回 `{"type":"score","score":加权值,"legend":{...},"probabilities":{...},"confidence":0~1}`
- 响应还有 `usage`（`input_tokens` / `output_tokens` / `cost`）和 `provider`
- 错误码：401 key 错、422 body 不合法、429 限流、529 过载。429/529 要指数退避重试（最多 3 次）

**已实测的坑（必须解决，这是本任务的核心价值）**：用截图那段对话测时，`should_answer_now`（该不该马上答具体内容）给 0.77，而 `best_action` 给"先翻聊天记录" 0.60，两题互相打架；对话结束时"她还需要什么"给"行动" 0.62 压过了"什么都不用了" 0.38，但正确答案是后者。**题目措辞要重写，靠标注集把这类矛盾压下去。**

## 口径（已定死，照做）

1. **instructions 和 criteria 一律用英文**（Jev 主训练语言是英文，中文效果明显差）；**state 里的聊天内容保留中文原文**，不要翻译。
2. state 用 JSON 对象，形如：
   ```json
   {"chat": {"relationship": "...", "messages": [{"from": "her", "text": "中文原文"}, {"from": "me", "text": "..."}], "latest_from": "her"}}
   ```
   `from` 只用 `her` / `me` 两个值（`her` 泛指对方，不特指性别，描述里写 "the other person"）。最多带最近 10 条。
3. 题目集固定 7 道判断题 + 1 道排序题，一次请求全发（官方推荐的 speculative fan-out，省时省钱）：
   - `literal_question`（noul）：对方最新消息是不是字面意思，还是话里有话
   - `true_intent`（choice）：对方真实意图，5~6 个选项
   - `danger_level`（score）：这段对话离吵架/伤感情有多近，**10 档**，每档写具体情景（例如"语气轻松或带调侃"→"明显不高兴，回错会升级"→"已经在指责或下最后通牒"）
   - `should_reply_now`（noul）：现在该不该马上给出实质回复
   - `best_action`（choice）：下一步最佳动作，含"翻聊天记录确认事实""直接给出承诺和具体安排""先道歉""少说两句别画蛇添足"等
   - `she_needs`（choice）：对方现在要的是什么（道歉 / 具体行动 / 解释 / 什么都不用了）
   - `tension_resolved`（noul）：紧张是否已经解除
   - `best_reply`（choice）：给定 3 条候选回复文本，选最合适的一条。criteria 的 key 是 `reply_a/reply_b/reply_c`，value 是**候选回复的中文原文**（这里是唯一允许 criteria 用中文的地方，因为它就是待选内容）。
4. **题目之间不许互相矛盾**：`should_reply_now` 的措辞要限定为"是否该给出实质内容"，`best_action` 的选项里不能再出现"要不要现在回"这个维度，只描述动作类型。`she_needs` 必须有明确的"nothing / 事情已经过去了"档，并在 instructions 里点明"如果对方已经表示满意，选 nothing"。
5. 超时 20 秒，单次请求失败要有可读错误，**任何情况下不许把 key 打进 stdout 或写进文件**。

## 交付物（只许动 `tools/jev/` 目录，别碰 `app/`、`gradle/`、根目录任何文件）

1. `tools/jev/jev_client.py`
   - `ask(state: dict, questions: dict, timeout=20) -> dict`：只用标准库 `urllib`，不要 requests
   - 从环境变量 `OPENROUTER_API_KEY` 读 key；没有就抛可读异常
   - 429/529 指数退避重试 3 次；返回原始 JSON
2. `tools/jev/questions.py`
   - `JUDGE_QUESTIONS`：上面 7 道判断题的 dict
   - `build_rank_question(candidates: list[str]) -> dict`：给 3 条候选回复，产出 `best_reply` 题
   - `build_state(messages: list[tuple[str, str]], relationship: str) -> dict`
3. `tools/jev/fixtures/labeled_set.json`
   - **不少于 25 条**中文对话片段，每条形如：
     ```json
     {"id": "c01", "relationship": "...", "messages": [["her","..."],["me","..."]],
      "expect": {"true_intent": "confirm_you_care", "danger_level": 6, "she_needs": "action", "tension_resolved": false}}
     ```
   - 你自己编写这些片段，要覆盖：情侣拌嘴、对方明显生气、对方已经满意、纯闲聊无冲突、工作同事催进度、朋友约饭、对方阴阳怪气、对方直接下最后通牒。**危险等级要覆盖 0~9 全程**，别集中在中间。
   - `expect.danger_level` 写 0~9 的整数（人工标注）
4. `tools/jev/calibrate.py`
   - 跑完整个标注集，串行（别并发，防限流），每条之间 sleep 0.3 秒
   - 输出一张表：每道题的命中率、`danger_level` 的平均绝对误差、平均置信度、平均延迟、总花费
   - 把逐条明细（含模型答案与人工标注的差异）写 `tools/jev/report/calibration.json` 和一份可读的 `tools/jev/report/calibration.md`
   - 支持 `--limit N` 只跑前 N 条，方便调试
5. `tools/jev/demo_meme.py`
   - 用下面这段真实对话（截图里的）跑一遍完整流程：7 道判断题 + 3 条候选回复排序，打印结果。候选回复你自己写 3 条（一条敷衍、一条道歉、一条给具体安排）。
     ```
     her: 你今天是不是又忘了我跟你说过什么？
     me:  记得，你先别提示我，让我自己说。
     her: 那你说。
     me:  等一下，我想说完整一点。
     her: 你最好是。
     ```
   - 期望：`true_intent` 应该落在"确认你在不在乎"，`best_action` 应该是"翻聊天记录"，`danger_level` 中高档

## 验收（自己跑完把**真实输出**贴进报告）

```
set OPENROUTER_API_KEY=<已在你的环境变量里>
python tools/jev/demo_meme.py
python tools/jev/calibrate.py
```

1. 两个脚本都零异常退出，全部请求 HTTP 200
2. `calibrate.py` 输出的表格里：`danger_level` 平均绝对误差 < 1.0 档；`true_intent` 和 `she_needs` 命中率 ≥ 60%
3. **如果第一轮没达标，就改题目措辞和选项描述再跑**（这正是本任务的价值所在），最多迭代 3 轮，把每轮的数字变化记进报告
4. 在 `tools/` 下搜索 OpenRouter 密钥前缀必须无结果（密钥不得出现在任何文件里）

## 铁律

- 禁 `git commit` / `git push`
- 只改 `tools/jev/` 下的文件
- Python 读写文件一律显式 `encoding='utf-8'`（本机是中文 Windows，默认 CP936 会乱码）
- 中文输出写文件，别指望 print 到控制台不乱码；脚本开头加
  `sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')`
- 不许把 key 写进任何文件

## 交付

报告写 `_reports/jev_questions_report.md`：做法 → 文件清单 → 三轮迭代的数字变化 → 验收命令真实输出 → **自验缺口**（哪些题你觉得还不稳、标注集哪些场景覆盖不足）。
