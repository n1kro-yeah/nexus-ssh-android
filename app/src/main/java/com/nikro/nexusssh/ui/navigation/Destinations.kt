package com.nikro.nexusssh.ui.navigation

/**
 * Every screen in the app, as a route.
 *
 * Routes are plain strings rather than typed destinations so deep links (`ssh://user@host:port`)
 * and app shortcuts can build them without pulling the navigation graph into those entry points.
 */
object Routes {
    const val HOSTS = "hosts"
    const val TERMINAL = "terminal"
    const val SFTP = "sftp"
    const val KEYCHAIN = "keychain"
    const val MORE = "more"

    const val HOST_EDITOR = "host_editor"
    const val GROUP_EDITOR = "group_editor"
    const val IDENTITY_LIST = "identities"
    const val IDENTITY_EDITOR = "identity_editor"
    const val KEY_DETAIL = "key_detail"
    const val KEY_GENERATE = "key_generate"
    const val KEY_IMPORT = "key_import"
    const val KNOWN_HOSTS = "known_hosts"
    const val SNIPPETS = "snippets"
    const val SNIPPET_EDITOR = "snippet_editor"
    const val PORT_FORWARDS = "port_forwards"
    const val PORT_FORWARD_EDITOR = "port_forward_editor"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val TERMINAL_SETTINGS = "settings/terminal"
    const val SECURITY_SETTINGS = "settings/security"
    const val BACKUP = "settings/backup"
    const val ABOUT = "settings/about"
    const val QUICK_CONNECT = "quick_connect"
    const val ONBOARDING = "onboarding"
    const val LOCK = "lock"

    /** Editor for an existing host, or a new one when [hostId] is null. */
    fun hostEditor(hostId: Long? = null): String =
        if (hostId == null) "$HOST_EDITOR/0" else "$HOST_EDITOR/$hostId"

    fun groupEditor(groupId: Long? = null): String =
        if (groupId == null) "$GROUP_EDITOR/0" else "$GROUP_EDITOR/$groupId"

    fun identityEditor(identityId: Long? = null): String =
        if (identityId == null) "$IDENTITY_EDITOR/0" else "$IDENTITY_EDITOR/$identityId"

    fun keyDetail(keyId: Long): String = "$KEY_DETAIL/$keyId"

    fun snippetEditor(snippetId: Long? = null): String =
        if (snippetId == null) "$SNIPPET_EDITOR/0" else "$SNIPPET_EDITOR/$snippetId"

    fun portForwardEditor(ruleId: Long? = null, hostId: Long? = null): String =
        "$PORT_FORWARD_EDITOR/${ruleId ?: 0}?hostId=${hostId ?: 0}"

    /** Terminal tab for a specific session; without an id the last active tab is shown. */
    fun terminal(sessionId: String? = null): String =
        if (sessionId == null) TERMINAL else "$TERMINAL?session=$sessionId"

    /** SFTP browser attached to a host; a session is reused when one is already open. */
    fun sftp(hostId: Long): String = "$SFTP?hostId=$hostId"

    /** Argument names, kept in one place so the graph and the screens cannot drift apart. */
    object Args {
        const val HOST_ID = "hostId"
        const val GROUP_ID = "groupId"
        const val IDENTITY_ID = "identityId"
        const val KEY_ID = "keyId"
        const val SNIPPET_ID = "snippetId"
        const val RULE_ID = "ruleId"
        const val SESSION_ID = "session"
    }
}

/** The five destinations in the bottom navigation / navigation rail. */
enum class TopLevelDestination(val route: String, val labelResName: String) {
    HOSTS(Routes.HOSTS, "nav_hosts"),
    TERMINAL(Routes.TERMINAL, "nav_terminal"),
    SFTP(Routes.SFTP, "nav_sftp"),
    KEYCHAIN(Routes.KEYCHAIN, "nav_keychain"),
    MORE(Routes.MORE, "nav_more"),
}
