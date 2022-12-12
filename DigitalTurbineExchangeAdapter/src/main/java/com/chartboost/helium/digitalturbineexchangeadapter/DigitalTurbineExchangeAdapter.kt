package com.chartboost.helium.digitalturbineexchangeadapter

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import com.chartboost.heliumsdk.BuildConfig.HELIUM_VERSION
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.fyber.inneractive.sdk.external.*
import com.fyber.inneractive.sdk.external.InneractiveAdSpot.RequestListener
import com.fyber.inneractive.sdk.external.InneractiveUnitController.AdDisplayError
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium Digital Turbine Exchange adapter
 */
class DigitalTurbineExchangeAdapter : PartnerAdapter {
    companion object {
        /**
         * Flag that can optionally be set to mute video creatives served by Digital Turbine Exchange.
         */
        public var mute = false
            set(value) {
                field = value
                InneractiveAdManager.setMuteVideo(value)
                PartnerLogController.log(
                    CUSTOM,
                    "Digital Turbine Exchange video creatives will be " +
                            "${
                                if (value) "muted"
                                else "unmuted"
                            }."
                )
            }

        /**
         * Set Digital Turbine Exchange's log level.
         *
         * @param level The log level to set. Must be one of the constants in Android's [Log] class.
         */
        public fun setLogLevel(level: Int) {
            InneractiveAdManager.setLogLevel(level)
            PartnerLogController.log(
                CUSTOM,
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
                }."
            )
        }

        /**
         * Key for parsing the Digital Turbine Exchange app ID.
         */
        private const val APP_ID_KEY = "fyber_app_id"

        /**
         * Key for identifying the Helium mediation platform to the partner.
         */
        private const val MEDIATOR_NAME = "Helium"
    }

    /**
     * A map of Helium's listeners for the corresponding Helium placements.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Get the Digital Turbine Exchange SDK version.
     */
    override val partnerSdkVersion: String
        get() = InneractiveAdManager.getVersion()

    /**
     * Get the Digital Turbine Exchange adapter version.
     *
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = BuildConfig.HELIUM_DIGITAL_TURBINE_EXCHANGE_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "fyber"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Digital Turbine Exchange"

    /**
     * Initialize the Digital Turbine Exchange SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Digital Turbine Exchange.
     *
     * @return Result.success() if Digital Turbine Exchange was initialized successfully, Result.failure() otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCoroutine { continuation ->
            partnerConfiguration.credentials.optString(APP_ID_KEY).trim().takeIf { it.isNotEmpty() }
                ?.let { appId ->
                    InneractiveAdManager.initialize(
                        context,
                        appId
                    ) { status: OnFyberMarketplaceInitializedListener.FyberInitStatus ->
                        continuation.resume(getInitResult(status))
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing app ID.")
                continuation.resume(Result.failure(HeliumAdException(HeliumError.HE_INITIALIZATION_FAILURE_INVALID_CREDENTIALS)))
            }
        }
    }

    /**
     * Notify the Digital Turbine Exchange SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        if (applies == true) {
            InneractiveAdManager.setGdprConsent(
                gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED,
                InneractiveAdManager.GdprConsentSource.External
            )
        } else {
            InneractiveAdManager.clearGdprConsentData()
        }
    }

    /**
     * Notify Digital Turbine Exchange of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String?
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )

        InneractiveAdManager.setUSPrivacyString(privacyString)
    }

    /**
     * Notify Digital Turbine Exchange of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
        )

        // Digital Turbine Exchange does not have an API for setting COPPA
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return emptyMap()
    }

    /**
     * Attempt to load a Digital Turbine Exchange ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            AdFormat.BANNER -> {
                loadBannerAd(context, request, partnerAdListener)
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> {
                loadFullscreenAd(request, partnerAdListener)
            }
        }
    }

    /**
     * Attempt to show the currently loaded Digital Turbine Exchange ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        val listener = listeners.remove(partnerAd.request.heliumPlacement)

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            AdFormat.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> showFullscreenAd(
                context,
                partnerAd,
                listener
            )
        }
    }

    /**
     * Discard unnecessary Digital Turbine Exchange ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        listeners.remove(partnerAd.request.heliumPlacement)
        return destroyAd(partnerAd)
    }

    /**
     * Determine the corresponding [Result] for a given Digital Turbine Exchange init status.
     *
     * @param status The Digital Turbine Exchange init status.
     *
     * @return Result.success() if Digital Turbine Exchange was initialized successfully, Result.failure() otherwise.
     */
    private fun getInitResult(status: OnFyberMarketplaceInitializedListener.FyberInitStatus): Result<Unit> {
        return when (status) {
            // Digital Turbine Exchange's failed init is recoverable. It will be re-attempted on
            // the first ad request.
            OnFyberMarketplaceInitializedListener.FyberInitStatus.SUCCESSFULLY,
            OnFyberMarketplaceInitializedListener.FyberInitStatus.FAILED -> {
                Result.success(PartnerLogController.log(SETUP_SUCCEEDED))
            }
            OnFyberMarketplaceInitializedListener.FyberInitStatus.FAILED_NO_KITS_DETECTED -> {
                PartnerLogController.log(SETUP_FAILED, "No kits detected.")
                Result.failure(HeliumAdException(HeliumError.HE_INITIALIZATION_FAILURE_UNKNOWN))
            }
            OnFyberMarketplaceInitializedListener.FyberInitStatus.INVALID_APP_ID -> {
                PartnerLogController.log(SETUP_FAILED, "Invalid app ID.")
                Result.failure(HeliumAdException(HeliumError.HE_INITIALIZATION_FAILURE_INVALID_CREDENTIALS))
            }
        }
    }

    /**
     * Attempt to load a Digital Turbine Exchange banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        val adSpot = InneractiveAdSpotManager.get().createSpot()
        val unitController = InneractiveAdViewUnitController()

        adSpot.addUnitController(unitController)
        adSpot.setMediationName(MEDIATOR_NAME)
        adSpot.mediationVersion = HELIUM_VERSION

        return suspendCoroutine { continuation ->
            adSpot.setRequestListener(object : RequestListener {
                override fun onInneractiveSuccessfulAdRequest(ad: InneractiveAdSpot?) {
                    continuation.resume(
                        if (ad != adSpot) {
                            PartnerLogController.log(
                                LOAD_FAILED,
                                "Digital Turbine Exchange returned an ad for a different ad spot: $ad."
                            )

                            Result.failure(
                                HeliumAdException(
                                    HeliumError.HE_LOAD_FAILURE_MISMATCHED_AD_PARAMS
                                )
                            )
                        } else {
                            val controller =
                                ad?.selectedUnitController as InneractiveAdViewUnitController
                            val bannerView = BannerView(context, adSpot)

                            controller.eventsListener = object : InneractiveAdViewEventsListener {
                                override fun onAdImpression(ad: InneractiveAdSpot?) {
                                    PartnerLogController.log(DID_TRACK_IMPRESSION)
                                    listener.onPartnerAdImpression(
                                        PartnerAd(
                                            ad = ad,
                                            details = emptyMap(),
                                            request = request
                                        )
                                    )
                                }

                                override fun onAdClicked(ad: InneractiveAdSpot?) {
                                    PartnerLogController.log(DID_CLICK)
                                    listener.onPartnerAdClicked(
                                        PartnerAd(
                                            ad = ad,
                                            details = emptyMap(),
                                            request = request
                                        )
                                    )
                                }

                                override fun onAdWillCloseInternalBrowser(ad: InneractiveAdSpot?) {
                                }

                                override fun onAdWillOpenExternalApp(ad: InneractiveAdSpot?) {
                                }

                                override fun onAdEnteredErrorState(
                                    ad: InneractiveAdSpot?,
                                    error: AdDisplayError?
                                ) {
                                    PartnerLogController.log(SHOW_FAILED, "Error: $error")
                                }

                                override fun onAdExpanded(ad: InneractiveAdSpot?) {
                                }

                                override fun onAdResized(ad: InneractiveAdSpot?) {
                                }

                                override fun onAdCollapsed(ad: InneractiveAdSpot?) {
                                }
                            }

                            controller.bindView(bannerView)

                            PartnerLogController.log(LOAD_SUCCEEDED)
                            Result.success(
                                PartnerAd(
                                    ad = bannerView,
                                    details = emptyMap(),
                                    request = request
                                )
                            )
                        }
                    )
                }

                override fun onInneractiveFailedAdRequest(
                    adSpot: InneractiveAdSpot?,
                    errorCode: InneractiveErrorCode?
                ) {
                    PartnerLogController.log(LOAD_FAILED, "$errorCode")
                    continuation.resume(Result.failure(HeliumAdException(getHeliumError(errorCode))))
                }
            })

            adSpot.requestAd(InneractiveAdRequest(request.partnerPlacement))
        }
    }

    /**
     * Digital Turbine Exchange needs a view to display the banner.
     * https://developer.fyber.com/hc/en-us/articles/360019744297-Android-Ad-Formats
     *
     * @param context The current [Context].
     * @param spot: The Digital Turbine Exchange ad spot (ad object).
     */
    private class BannerView(context: Context, val spot: InneractiveAdSpot) :
        FrameLayout(context)

    /**
     * Attempt to load a Digital Turbine Exchange fullscreen ad. This method supports both interstitial and rewarded ads.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadFullscreenAd(
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.heliumPlacement] = listener

        val videoSpot = InneractiveAdSpotManager.get().createSpot()
        val unitController = InneractiveFullscreenUnitController()
        val videoController = InneractiveFullscreenVideoContentController()

        unitController.addContentController(videoController)
        videoSpot.addUnitController(unitController)
        videoSpot.setMediationName(MEDIATOR_NAME)
        videoSpot.mediationVersion = adapterVersion

        return suspendCoroutine { continuation ->
            videoSpot.setRequestListener(object : RequestListener {
                override fun onInneractiveSuccessfulAdRequest(adSpot: InneractiveAdSpot) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = adSpot,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onInneractiveFailedAdRequest(
                    adSpot: InneractiveAdSpot,
                    errorCode: InneractiveErrorCode
                ) {
                    PartnerLogController.log(LOAD_FAILED, "Ad spot $adSpot. Error code: $errorCode")
                    continuation.resume(Result.failure(HeliumAdException(getHeliumError(errorCode))))
                }
            })

            videoSpot.requestAd(InneractiveAdRequest(request.partnerPlacement))
        }
    }

    /**
     * Determine if a Digital Turbine Exchange ad is ready to show.
     *
     * @param context The current [Context].
     * @param format The format of the ad to be shown.
     * @param ad The generic Digital Turbine Exchange ad object.
     *
     * @return True if the ad is ready to show, false otherwise.
     */
    private fun readyToShow(context: Context, format: AdFormat, ad: Any?): Boolean {
        return when {
            context !is Activity -> {
                PartnerLogController.log(SHOW_FAILED, "Context is not an activity.")
                false
            }
            format == AdFormat.BANNER -> (ad is BannerView)
            format == AdFormat.INTERSTITIAL || format == AdFormat.REWARDED -> (ad as InneractiveAdSpot).isReady
            else -> false
        }
    }

    /**
     * Attempt to show a Digital Turbine Exchange fullscreen ad.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the Digital Turbine Exchange ad to be shown.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showFullscreenAd(
        context: Context,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?
    ): Result<PartnerAd> {
        if (!readyToShow(context, partnerAd.request.format, partnerAd.ad)) {
            PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
            return Result.failure(HeliumAdException(HeliumError.HE_SHOW_FAILURE_AD_NOT_READY))
        }

        (partnerAd.ad as? InneractiveAdSpot)?.let { adSpot ->
            val controller = adSpot.selectedUnitController as? InneractiveFullscreenUnitController
                ?: return Result.failure(HeliumAdException(HeliumError.HE_SHOW_FAILURE_WRONG_RESOURCE_TYPE))

            return suspendCoroutine { continuation ->
                controller.eventsListener = object : InneractiveFullscreenAdEventsListener {
                    override fun onAdImpression(ad: InneractiveAdSpot) {
                        PartnerLogController.log(DID_TRACK_IMPRESSION)
                        listener?.onPartnerAdImpression(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = partnerAd.request
                            )
                        ) ?: PartnerLogController.log(
                            CUSTOM,
                            "Unable to fire onPartnerAdImpression for Digital Turbine Exchange " +
                                    "adapter. Listener is null"
                        )

                        PartnerLogController.log(SHOW_SUCCEEDED)
                        continuation.resume(
                            Result.success(
                                PartnerAd(
                                    ad = ad,
                                    details = emptyMap(),
                                    request = partnerAd.request
                                )
                            )
                        )
                    }

                    override fun onAdClicked(ad: InneractiveAdSpot) {
                        PartnerLogController.log(DID_CLICK)
                        listener?.onPartnerAdClicked(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = partnerAd.request
                            )
                        ) ?: PartnerLogController.log(
                            CUSTOM,
                            "Unable to fire onPartnerAdClicked for Digital Turbine Exchange " +
                                    "adapter. Listener is null."
                        )
                    }

                    override fun onAdWillCloseInternalBrowser(ad: InneractiveAdSpot) {
                    }

                    override fun onAdWillOpenExternalApp(ad: InneractiveAdSpot) {
                    }

                    override fun onAdEnteredErrorState(
                        ad: InneractiveAdSpot,
                        error: AdDisplayError
                    ) {
                        PartnerLogController.log(SHOW_FAILED, "Error: $error")
                        continuation.resume(Result.failure(HeliumAdException(HeliumError.HE_SHOW_FAILURE_UNKNOWN)))

                        ad.destroy()
                    }

                    override fun onAdDismissed(ad: InneractiveAdSpot) {
                        PartnerLogController.log(DID_DISMISS)
                        listener?.onPartnerAdDismissed(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = partnerAd.request
                            ), null
                        ) ?: PartnerLogController.log(
                            CUSTOM,
                            "Unable to fire onPartnerAdDismissed for Digital Turbine Exchange adapter. Listener " +
                                    "is null."
                        )

                        ad.destroy()
                    }
                }

                controller.rewardedListener = InneractiveFullScreenAdRewardedListener {
                    PartnerLogController.log(DID_REWARD)
                    listener?.onPartnerAdRewarded(
                        partnerAd, Reward(0, "")
                    ) ?: PartnerLogController.log(
                        CUSTOM,
                        "Unable to fire onPartnerAdRewarded for Digital Turbine Exchange adapter. Listener " +
                                "is null."
                    )
                }

                controller.show(context as Activity)
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is not an InneractiveAdSpot.")
            return Result.failure(HeliumAdException(HeliumError.HE_SHOW_FAILURE_WRONG_RESOURCE_TYPE))
        }
    }

    /**
     * Attempt to the destroy the current Digital Turbine Exchange ad.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            when (it) {
                is BannerView -> {
                    it.spot.destroy()
                }
                is InneractiveAdSpot -> {
                    it.destroy()
                }
                else -> {
                    PartnerLogController.log(
                        INVALIDATE_FAILED,
                        "Ad is neither a BannerView nor an InneractiveAdSpot."
                    )
                    return Result.failure(HeliumAdException(HeliumError.HE_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
                }
            }

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(HeliumAdException(HeliumError.HE_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Convert a given Digital Turbine Exchange error code into a [HeliumError].
     *
     * @param error The Digital Turbine Exchange error code.
     *
     * @return The corresponding [HeliumError].
     */
    private fun getHeliumError(error: InneractiveErrorCode?) = when (error) {
        InneractiveErrorCode.NO_FILL -> HeliumError.HE_LOAD_FAILURE_NO_FILL
        InneractiveErrorCode.CONNECTION_ERROR -> HeliumError.HE_NO_CONNECTIVITY
        InneractiveErrorCode.SERVER_INTERNAL_ERROR -> HeliumError.HE_AD_SERVER_ERROR
        InneractiveErrorCode.SERVER_INVALID_RESPONSE -> HeliumError.HE_LOAD_FAILURE_INVALID_BID_RESPONSE
        InneractiveErrorCode.LOAD_TIMEOUT -> HeliumError.HE_LOAD_FAILURE_TIMEOUT
        InneractiveErrorCode.ERROR_CODE_NATIVE_VIDEO_NOT_SUPPORTED -> HeliumError.HE_LOAD_FAILURE_MISMATCHED_AD_FORMAT
        else -> HeliumError.HE_PARTNER_ERROR
    }
}
