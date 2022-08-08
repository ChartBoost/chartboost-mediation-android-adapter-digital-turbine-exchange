package com.chartboost.helium.digitalturbineexchangeadapter

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.chartboost.heliumsdk.BuildConfig.HELIUM_VERSION
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.LogController
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
         * The tag used for log messages.
         */
        private val TAG = "[${this::class.java.simpleName}]"

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
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies = false

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
        return suspendCoroutine { continuation ->
            partnerConfiguration.credentials[APP_ID_KEY]?.let { appId ->
                InneractiveAdManager.initialize(
                    context,
                    appId
                ) { status: OnFyberMarketplaceInitializedListener.FyberInitStatus ->
                    continuation.resume(getInitResult(status))
                }
            } ?: run {
                LogController.e("$TAG Digital Turbine Exchange failed to initialize. Missing app ID.")
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED)))
            }
        }
    }

    /**
     * Save the current GDPR applicability state for later use.
     *
     * @param context The current [Context].
     * @param gdprApplies True if GDPR applies, false otherwise.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        this.gdprApplies = gdprApplies
    }

    /**
     * Notify Digital Turbine Exchange of the user's GDPR consent status, if applicable.
     *
     * @param context The current [Context].
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        if (gdprApplies) {
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
     * @param hasGivenCcpaConsent True if the user has given CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGivenCcpaConsent: Boolean,
        privacyString: String?
    ) {
        InneractiveAdManager.setUSPrivacyString(privacyString)
    }

    /**
     * Notify Digital Turbine Exchange of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
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
    ) = emptyMap<String, String>()

    /**
     * Attempt to load a Digital Turbine Exchange ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
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
        val listener = listeners.remove(partnerAd.request.heliumPlacement)

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            AdFormat.BANNER -> Result.success(partnerAd)
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
                Result.success(LogController.i("$TAG Digital Turbine Exchange successfully initialized."))
            }
            OnFyberMarketplaceInitializedListener.FyberInitStatus.FAILED_NO_KITS_DETECTED -> {
                LogController.e("$TAG Digital Turbine Exchange failed to initialize. No kits detected.")
                Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
            }
            OnFyberMarketplaceInitializedListener.FyberInitStatus.INVALID_APP_ID -> {
                LogController.e("$TAG Digital Turbine Exchange failed to initialize. Invalid app ID.")
                Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
            }
        }
    }

    /**
     * Attempt to load a Digital Turbine Exchange banner ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: AdLoadRequest,
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
                            LogController.e(
                                "$TAG Digital Turbine Exchange returned an ad for a different ad spot: $ad. " +
                                        "Failing ad request."
                            )

                            Result.failure(
                                HeliumAdException(
                                    HeliumErrorCode.PARTNER_ERROR
                                )
                            )
                        } else {
                            val controller =
                                ad?.selectedUnitController as InneractiveAdViewUnitController
                            val bannerView = BannerView(context, adSpot)

                            // TODO: [HB-4292] Recheck InneractiveAdViewEventsListener. None of these callbacks work.
                            controller.eventsListener = object : InneractiveAdViewEventsListener {
                                override fun onAdImpression(ad: InneractiveAdSpot?) {
                                    listener.onPartnerAdImpression(
                                        PartnerAd(
                                            ad = ad,
                                            details = emptyMap(),
                                            request = request
                                        )
                                    )
                                }

                                override fun onAdClicked(ad: InneractiveAdSpot?) {
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
                                    LogController.e(
                                        "$TAG Digital Turbine Exchange failed to show the banner ad. " +
                                                "Error: $error"
                                    )
                                }

                                override fun onAdExpanded(ad: InneractiveAdSpot?) {
                                }

                                override fun onAdResized(ad: InneractiveAdSpot?) {
                                }

                                override fun onAdCollapsed(ad: InneractiveAdSpot?) {
                                }
                            }

                            controller.bindView(bannerView)

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
                    LogController.e("$TAG Digital Turbine Exchange failed to load a banner ad. Error code: $errorCode")
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
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
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadFullscreenAd(
        request: AdLoadRequest,
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
                    LogController.e(
                        "$TAG Digital Turbine Exchange failed to load a fullscreen ad for ad spot $adSpot. " +
                                "Error code: $errorCode"
                    )
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
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
                LogController.e("$TAG Digital Turbine Exchange failed to show the fullscreen ad. Context is not an activity.")
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
            LogController.e("$TAG Digital Turbine Exchange failed to show the fullscreen ad. Ad is not ready.")
            return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
        }

        (partnerAd.ad as? InneractiveAdSpot)?.let { adSpot ->
            val controller = adSpot.selectedUnitController as? InneractiveFullscreenUnitController
                ?: return Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR))

            return suspendCoroutine { continuation ->
                controller.eventsListener = object : InneractiveFullscreenAdEventsListener {
                    override fun onAdImpression(ad: InneractiveAdSpot) {
                        listener?.onPartnerAdImpression(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = partnerAd.request
                            )
                        ) ?: LogController.e(
                            "$TAG Unable to fire onPartnerAdImpression for Digital Turbine Exchange " +
                                    "adapter. Listener is null"
                        )

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
                        listener?.onPartnerAdClicked(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = partnerAd.request
                            )
                        ) ?: LogController.e(
                            "$TAG Unable to fire onPartnerAdClicked for Digital Turbine Exchange " +
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
                        LogController.e(
                            "$TAG Digital Turbine Exchange failed to show the fullscreen ad. Error: $error"
                        )
                        continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))

                        ad.destroy()
                    }

                    override fun onAdDismissed(ad: InneractiveAdSpot) {
                        listener?.onPartnerAdDismissed(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = partnerAd.request
                            ), null
                        ) ?: LogController.e(
                            "$TAG Unable to fire onPartnerAdDismissed for Digital Turbine Exchange adapter. Listener " +
                                    "is null."
                        )

                        ad.destroy()
                    }
                }

                controller.rewardedListener = InneractiveFullScreenAdRewardedListener {
                    listener?.onPartnerAdRewarded(
                        partnerAd, Reward(0, "")
                    ) ?: LogController.e(
                        "$TAG Unable to fire onPartnerAdRewarded for Digital Turbine Exchange adapter. Listener " +
                                "is null."
                    )
                }

                controller.show(context as Activity)
            }
        } ?: run {
            LogController.e("$TAG Digital Turbine Exchange failed to show the fullscreen ad. Ad is not an InneractiveAdSpot.")
            return Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
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
                    LogController.e(
                        "$TAG Digital Turbine Exchange failed to destroy the ad. Ad " +
                                "is neither a BannerView nor an InneractiveAdSpot."
                    )
                    return Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
                }
            }

            Result.success(partnerAd)
        } ?: run {
            LogController.e("$TAG Digital Turbine Exchange failed to destroy the ${partnerAd.request.format} ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }
}
