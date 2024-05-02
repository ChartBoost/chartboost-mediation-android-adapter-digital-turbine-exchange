package com.chartboost.mediation.digitalturbineexchangeadapter

import android.util.Log
import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.fyber.inneractive.sdk.external.InneractiveAdManager

object DigitalTurbineExchangeAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "fyber"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "Digital Turbine Exchange"

    /**
     * The version of the partner SDK.
     */
    override val partnerSdkVersion: String = InneractiveAdManager.getVersion()

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_DIGITAL_TURBINE_EXCHANGE_ADAPTER_VERSION

    /**
     * Flag that can optionally be set to mute video creatives served by Digital Turbine Exchange.
     */
    var mute = false
        set(value) {
            field = value
            InneractiveAdManager.setMuteVideo(value)
            PartnerLogController.log(
                    PartnerLogController.PartnerAdapterEvents.CUSTOM,
                    "Digital Turbine Exchange video creatives will be " +
                            "${
                                if (value) {
                                    "muted"
                                } else {
                                    "unmuted"
                                }
                            }.",
            )
        }

    /**
     * Set Digital Turbine Exchange's log level.
     *
     * @param level The log level to set. Must be one of the constants in Android's [Log] class.
     */
    fun setLogLevel(level: Int) {
        InneractiveAdManager.setLogLevel(level)
        PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "Digital Turbine Exchange log level set to ${
                    when (level) {
                        Log.VERBOSE -> "Log.VERBOSE"
                        Log.DEBUG -> "Log.DEBUG"
                        Log.INFO -> "Log.INFO"
                        Log.WARN -> "Log.WARN"
                        Log.ERROR -> "Log.ERROR"
                        Log.ASSERT -> "Log.ASSERT"
                        else -> "UNKNOWN"
                    }
                }.",
        )
    }
}
