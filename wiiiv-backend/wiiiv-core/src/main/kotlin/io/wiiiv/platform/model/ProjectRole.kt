package io.wiiiv.platform.model

enum class ProjectRole {
    OWNER,
    MEMBER,
    VIEWER;

    fun canExecute(): Boolean = this == OWNER || this == MEMBER
    fun canManage(): Boolean = this == OWNER
    fun canView(): Boolean = true
}
