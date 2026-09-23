package com.jev.probe.core

/** 网络结果所属的应用、会话和消息；任一变化都不能复用旧结果。 */
data class AnalysisIdentity(val app: String, val title: String?, val signature: String) {
    companion object {
        fun of(app: String, snapshot: ChatSnapshot): AnalysisIdentity =
            AnalysisIdentity(app, snapshot.title, snapshot.signature())
    }
}
