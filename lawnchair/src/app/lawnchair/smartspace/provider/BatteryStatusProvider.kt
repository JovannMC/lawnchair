package app.lawnchair.smartspace.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.BatteryManager
import androidx.core.content.getSystemService
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.util.broadcastReceiverFlow
import app.lawnchair.util.formatShortElapsedTimeRoundingUpToMinutes
import com.android.launcher3.R
import com.android.launcher3.Utilities
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map


class BatteryStatusProvider(context: Context) : SmartspaceDataSource(
    context,
    R.string.smartspace_battery_status,
    { smartspaceBatteryStatus },
) {
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val batteryManager = context.getSystemService<BatteryManager>()

    override val internalTargets = broadcastReceiverFlow(context, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        .map { intent ->
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val full = status == BatteryManager.BATTERY_STATUS_FULL
            val level = (
                100f *
                    intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) /
                    intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                ).toInt()
            listOfNotNull(getSmartspaceTarget(charging, full, level))
        }

    private suspend fun getSmartspaceTarget(charging: Boolean, full: Boolean, level: Int): SmartspaceTarget? {
        val prioritizeLowBattery = prefs2.smartspacePrioritizeLowBattery.get().map{it}.first()
        val lowBatteryTrigger = prefs2.smartspaceBatteryLowPriority.get().map{it}.first()

        val title = when {
            full || level == 100 -> return null
            charging -> context.getString(R.string.smartspace_battery_charging)
            level <= lowBatteryTrigger -> context.getString(R.string.smartspace_battery_low)
            else -> return null
        }

        /*
        TODO(@validcube): The name of the variable doesn't make sense,
              Consider checking out once the changes is PR-ready.
                1) prioritizeLowBattery could be isPrioritizeLowBattery
                2) lowBatteryTrigger could be ... *something*
              Don't forget to checkout prefs name
        */
        val score = if (prioritizeLowBattery || (level <= lowBatteryTrigger)) {
            SmartspaceScores.SCORE_LOW_BATTERY
        } else {
            SmartspaceScores.SCORE_BATTERY
        }
        val chargingTimeRemaining = computeChargeTimeRemaining()
        val subtitle = if (charging && chargingTimeRemaining > 0) {
            val chargingTime = formatShortElapsedTimeRoundingUpToMinutes(context, chargingTimeRemaining)
            context.getString(
                R.string.battery_charging_percentage_charging_time,
                level,
                chargingTime,
            )
        } else {
            context.getString(R.string.n_percent, level)
        }
        val iconResId = if (charging) R.drawable.ic_charging else R.drawable.ic_battery_low
        return SmartspaceTarget(
            id = "batteryStatus",
            headerAction = SmartspaceAction(
                id = "batteryStatusAction",
                icon = Icon.createWithResource(context, iconResId),
                title = title,
                subtitle = subtitle,
            ),
            score = score,
            featureType = SmartspaceTarget.FeatureType.FEATURE_CALENDAR,
        )
    }

    private fun computeChargeTimeRemaining(): Long {
        if (!Utilities.ATLEAST_P) return -1
        return runCatching { batteryManager?.computeChargeTimeRemaining() ?: -1 }.getOrDefault(-1)
    }
}
