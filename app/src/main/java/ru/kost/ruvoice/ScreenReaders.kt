package ru.kost.ruvoice

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager

/**
 * Кто прислал запрос: экранный чтец или читалка. Система кладёт в SynthesisRequest.callerUid uid
 * приложения, вызвавшего speak (TextToSpeechService: Binder.getCallingUid()), TalkBack говорит из
 * своего процесса (FailoverTextToSpeech) — значит, uid его пакета.
 *
 * Экранный чтец — если пакет в [KNOWN] или у него включена служба доступности с речевым откликом
 * (FEEDBACK_SPOKEN): так ловятся и сборки с другим именем пакета (открытый TalkBack —
 * com.android.talkback, у Samsung и Huawei свои), и чтецы, которых нет в списке. Решение
 * пользователя на вкладке правил («Кто читает через движок») перекрывает оба признака.
 * Службы доступности видны приложению через <queries> в манифесте (Android 11+).
 */
object ScreenReaders {
    /** Известные экранные чтецы (по имени пакета). Проверен по исходникам только TalkBack. */
    val KNOWN = setOf(
        "com.google.android.marvin.talkback",        // TalkBack из Google Play (Android Accessibility Suite)
        "com.android.talkback",                      // TalkBack, собранный из открытых исходников
        "com.samsung.android.accessibility.talkback", // TalkBack в прошивках Samsung
        "com.samsung.android.app.talkback",          // Voice Assistant, старые Samsung
        "com.bjbyhd.screenreader_huawei",            // экранный диктор Huawei
        "com.nirenr.talkman",                        // Jieshuo
        "com.dianming.phoneapp",                     // Dianming
    )

    /** Отправитель запроса: пакет, подпись и что решила автоматика. */
    data class Caller(val pkg: String, val label: String, val auto: Boolean)

    private const val TTL_MS = 30_000L
    private val cache = HashMap<Int, Pair<Long, Caller?>>()

    /** null — запрос из самого RuVoice (прослушивание в настройках) или uid без пакета. */
    fun caller(context: Context, uid: Int): Caller? {
        if (uid == Process.myUid() || uid < 0) return null
        val now = SystemClock.elapsedRealtime()
        synchronized(cache) { cache[uid]?.let { (at, c) -> if (now - at < TTL_MS) return c } }
        val c = runCatching { resolve(context, uid) }.getOrNull()
        synchronized(cache) { cache[uid] = now to c }
        return c
    }

    private fun resolve(context: Context, uid: Int): Caller? {
        val pm = context.packageManager
        val pkgs = pm.getPackagesForUid(uid)?.toList().orEmpty()
        if (pkgs.isEmpty()) return null
        val spoken = context.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            ?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }?.toSet().orEmpty()
        // общий uid на несколько пакетов — берём тот, что похож на чтеца; системный uid 1000 делят
        // «Настройки» и десятки служб, первым там попадался RilErrorNotifier с подписью «Ошибка»
        val pkg = pkgs.firstOrNull { it in KNOWN || it in spoken } ?: if ("android" in pkgs) "android" else pkgs.first()
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        return Caller(pkg, label, pkg in KNOWN || pkg in spoken)
    }

    /** Включён ли в системе хоть один экранный чтец — служба доступности с речью или исследование касанием. */
    fun anyActive(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        am.isTouchExplorationEnabled || am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
    }.getOrDefault(false)

    /** С учётом решения пользователя. */
    fun isScreenReader(prefs: Prefs, c: Caller): Boolean = when (c.pkg) {
        in prefs.srForce -> true
        in prefs.srNever -> false
        else -> c.auto
    }
}
