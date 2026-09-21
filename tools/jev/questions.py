"""Fixed Jev question set. Instructions/criteria in English; chat text stays Chinese."""

from __future__ import annotations

JUDGE_QUESTIONS: dict = {
    "literal_question": {
        "type": "noul",
        "instructions": (
            "Is the other person's latest message meant purely literally, with no subtext? "
            "Judge from the whole thread, not one sentence in isolation."
        ),
        "criteria": {
            "true": (
                "The latest message is a straightforward statement, question, or plan "
                "with no implied accusation, test, sarcasm, hint, or unsaid request."
            ),
            "false": (
                "There is subtext: a test of whether you remember or care, sarcasm, "
                "an implied complaint, a hint they will not say outright, a trap question, "
                "an accusation dressed as a question, or a cold/short line that really means blame."
            ),
        },
    },
    "true_intent": {
        "type": "choice",
        "instructions": (
            "What is the other person's true intent in the latest message, given the full conversation? "
            "Prefer tone and context over surface wording. "
            "If they are checking whether you remember something or still care, choose confirm_you_care "
            "even if the words look like a request to 'say it' or to do something. "
            "If they already accepted and closed the matter peacefully, choose close_topic. "
            "Ending the relationship, deleting you, or 'don't talk to me' is vent_anger, never close_topic."
        ),
        "criteria": {
            "confirm_you_care": (
                "They are testing whether you remember, pay attention, or still care. "
                "Signals: 'did you forget again', 'then say it', 'you better', sarcastic 'busy person', "
                "asking you to prove you know a past conversation. "
                "If they mainly want a new deliverable or a yes on a time, do not use this."
            ),
            "vent_anger": (
                "They are angry or hurt and mainly want the feeling acknowledged. "
                "They are blaming or raising the temperature; a specific plan is not the main point yet."
            ),
            "request_action": (
                "They want a concrete action, time, deliverable, or commitment from you now, "
                "and this is a real ask, not a loyalty test."
            ),
            "seek_explanation": (
                "They want a factual explanation of why something happened. "
                "They asked why or what is going on, not mainly for an apology or a new plan."
            ),
            "casual_chat": (
                "Light talk, banter, sharing, teasing with a laugh, or friendly logistics "
                "with no emotional test and no conflict. A friend suggesting a meal time can be this "
                "if the thread is warm."
            ),
            "close_topic": (
                "Peaceful wrap-up only: they accepted an apology, confirmed a happy plan, said thanks, "
                "or clearly signaled they need nothing more. "
                "Not a breakup, not 'don't contact me', not sarcastic 'I'm used to it'."
            ),
        },
    },
    "danger_level": {
        "type": "score",
        "instructions": (
            "How close is this conversation to a fight or to hurting the relationship? "
            "Match the current scene. "
            "If they genuinely accepted an apology or confirmed a happy plan, score the cooled-down present, "
            "not an earlier complaint. "
            "If an ultimatum (break up, report to the boss, stop covering for you) is still in force "
            "and has not been withdrawn, stay in that high bin even if the latest line names a specific task."
        ),
        "criteria": [
            "Light chat or joking; no complaint, no test, no deadline.",
            "Mild tease or a small reminder that is easy to laugh off; a clumsy reply would only feel slightly awkward.",
            "A mild complaint or 'please remember next time' said without heat; they still send warm or practical follow-ups.",
            "Noticeable unhappiness; they mention being forgotten, ignored, or kept waiting, but still give you a chance to make it right.",
            "Sarcasm, cold short replies, or 'you better'; they are testing you, and a sloppy or fake-confident reply will escalate.",
            "Openly upset; they accuse you of not listening or not caring; they expect a real response, not a joke.",
            "Clearly angry and blaming you; a wrong reply will turn this into a fight.",
            "Last-chance warning. They will not cover for you, do not want to keep talking unless this changes, "
            "or tell you to finish a named checklist yourself because trust is almost gone.",
            "An ultimatum is already on the table even if they also give a practical next step: "
            "break up if you forget again, report you tonight, or stop working together if you miss this.",
            "Active rupture: they said it is over, told you not to reply, deleted you, or are exploding.",
        ],
    },
    "should_reply_now": {
        "type": "noul",
        "instructions": (
            "Should your next message contain substantive content? "
            "Substantive means: admitting a specific known fault, giving a concrete time/plan/deliverable, "
            "explaining facts you actually know, or reciting the recalled content they asked you to say. "
            "This is NOT 'should you send any message'. Timing is irrelevant. "
            "Answer FALSE if the thing they want you to recite or prove is not present in this snippet "
            "(you would be guessing). 'Then say it' / 'you better' while you are stalling is FALSE. "
            "Answer FALSE if they already accepted and closed the topic. "
            "Answer true only if the needed fact, plan, or named fault is already in this snippet."
        ),
        "criteria": {
            "true": (
                "The needed fact, named fault, or named time/place is already in this snippet, "
                "and they are waiting for that substance now."
            ),
            "false": (
                "Do not put substance in the next message: the recalled content is not in this snippet, "
                "they are testing whether you remember, a holding line is enough, "
                "saying less is safer, or they already closed the topic."
            ),
        },
    },
    "best_action": {
        "type": "choice",
        "instructions": (
            "What type of next action is best? Do not decide whether to send a message immediately. "
            "Ignore timing. Choose only the action type. "
            "If they asked you to recall a specific past message or event and you have not shown that you actually remember it, "
            "choose check_history — do not apologize or invent a plan instead."
        ),
        "criteria": {
            "check_history": (
                "Look up prior chat or facts before taking a position. "
                "Use when they ask you to repeat, recall, or prove you remember something specific."
            ),
            "apologize": (
                "Lead with a sincere apology for a real mistake or hurt already identified. "
                "Not for an unnamed forgotten thing when you should first find out what it was."
            ),
            "give_commitment": (
                "Give a concrete promise, deadline, or arrangement they asked for "
                "in a conflict or work-pressure setting."
            ),
            "explain": (
                "Explain what happened or why, without leading with apology or a new plan."
            ),
            "acknowledge": (
                "Show you heard them and care, without new facts, an apology, or a plan. "
                "Use for light chat or when they mainly need to feel seen."
            ),
            "say_less": (
                "Keep it short or add nothing. Extra words would over-explain, reopen a closed topic, "
                "or pour fuel on an ultimatum that told you not to talk."
            ),
            "make_plan": (
                "Propose or confirm logistics (time, place, task) for a non-conflict request "
                "such as a meal or a meeting."
            ),
        },
    },
    "she_needs": {
        "type": "choice",
        "instructions": (
            "What does the other person need from you right now? Judge the LATEST message first. "
            "If they genuinely accepted (thanks / got it / 没事了 / 那就这样 / 收到了 / 过去了), "
            "you MUST choose nothing, even if earlier they wanted action or an apology. "
            "Sarcastic 'I'm used to it', 'whatever', 'I don't want to hear it', 'don't bother coming' "
            "is NOT genuine satisfaction — do not choose nothing. "
            "If they asked you to recap a named time/place/date, choose action. "
            "If they are testing whether you remember or still care, and the content is unnamed, choose care."
        ),
        "criteria": {
            "apology": (
                "They need a sincere apology for hurt or a mistake, and they have not accepted one yet."
            ),
            "action": (
                "They need a concrete action, time, commitment, recap of a named fact, or follow-through, "
                "and they have not yet accepted one."
            ),
            "explanation": (
                "They need a clear explanation of what happened or why, and have not received it."
            ),
            "care": (
                "They need proof you remember, listen, or care — a loyalty or attention test — "
                "not yet a plan or an apology. Sarcastic 'I am used to it' belongs here, not nothing."
            ),
            "nothing": (
                "They need nothing further. Genuine acceptance, a peaceful closed topic, "
                "warm casual chat with no ask, or a rupture where they told you not to reply. "
                "Not sarcasm pretending to be fine."
            ),
        },
    },
    "tension_resolved": {
        "type": "noul",
        "instructions": (
            "Has interpersonal tension already been resolved? "
            "Answer true only if there was never tension, or the other person has clearly accepted, "
            "cooled down, joked again, or said it is fine. "
            "A sarcastic 'you better', an unanswered test, leftover blame, or an open ultimatum means false."
        ),
        "criteria": {
            "true": (
                "No remaining tension: they accepted, joked again, said it's fine, "
                "confirmed a happy plan, or the chat was never tense."
            ),
            "false": (
                "Tension is still present: they are waiting, testing, angry, sarcastic, "
                "issuing an ultimatum, or the issue is open."
            ),
        },
    },
}


def build_state(messages: list, relationship: str) -> dict:
    """messages: list of (from, text) or [from, text]. from is 'her' or 'me'. Keep last 10."""
    cleaned = []
    for item in messages:
        if isinstance(item, dict):
            who, text = item.get("from"), item.get("text")
        else:
            who, text = item[0], item[1]
        if who not in ("her", "me"):
            raise ValueError(f"message from must be 'her' or 'me', got {who!r}")
        cleaned.append({"from": who, "text": str(text)})
    cleaned = cleaned[-10:]
    latest_from = cleaned[-1]["from"] if cleaned else "her"
    return {
        "chat": {
            "relationship": relationship,
            "messages": cleaned,
            "latest_from": latest_from,
        }
    }


def build_rank_question(candidates: list[str]) -> dict:
    """Build the best_reply choice question. criteria values stay in original Chinese."""
    if len(candidates) != 3:
        raise ValueError("build_rank_question expects exactly 3 candidate replies")
    keys = ("reply_a", "reply_b", "reply_c")
    return {
        "best_reply": {
            "type": "choice",
            "instructions": (
                "Which candidate reply is the most appropriate next message, "
                "given the conversation and the other person's true need? "
                "Prefer a reply that matches the best action type. "
                "Penalize dismissive, over-promising, or off-topic replies. "
                "If the facts are not yet confirmed, prefer the candidate that looks them up "
                "instead of faking memory or a vague apology."
            ),
            "criteria": {key: text for key, text in zip(keys, candidates)},
        }
    }
