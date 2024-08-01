/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.digitalturbineexchangeadapter

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import com.chartboost.chartboostmediationsdk.ChartboostMediationSdk
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentValue
import com.chartboost.core.consent.ConsentValues
import com.fyber.inneractive.sdk.external.*
import com.fyber.inneractive.sdk.external.InneractiveAdSpot.RequestListener
import com.fyber.inneractive.sdk.external.InneractiveUnitController.AdDisplayError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation Digital Turbine Exchange adapter
 */
class DigitalTurbineExchangeAdapter : PartnerAdapter {
    companion object {
        /**
         * Key for parsing the Digital Turbine Exchange app ID.
         */
        private const val APP_ID_KEY = "fyber_app_id"

        /**
         * Key for identifying the mediation platform (Chartboost) to the partner.
         */
        private const val MEDIATOR_NAME = "Chartboost"

        /**
         * Convert a given Digital Turbine Exchange error code into a [ChartboostMediationError].
         *
         * @param error The Digital Turbine Exchange error code.
         *
         * @return The corresponding [ChartboostMediationError].
         */
        private fun getChartboostMediationError(error: InneractiveErrorCode?) =
            when (error) {
                InneractiveErrorCode.NO_FILL -> ChartboostMediationError.LoadError.NoFill
                InneractiveErrorCode.CONNECTION_ERROR -> ChartboostMediationError.OtherError.NoConnectivity
                InneractiveErrorCode.SERVER_INTERNAL_ERROR -> ChartboostMediationError.LoadError.ServerError
                InneractiveErrorCode.SERVER_INVALID_RESPONSE -> ChartboostMediationError.LoadError.InvalidBidResponse
                InneractiveErrorCode.LOAD_TIMEOUT -> ChartboostMediationError.LoadError.AdRequestTimeout
                InneractiveErrorCode.ERROR_CODE_NATIVE_VIDEO_NOT_SUPPORTED -> ChartboostMediationError.LoadError.MismatchedAdFormat
                else -> ChartboostMediationError.OtherError.PartnerError
            }
    }

    /**
     * The Digital Turbine Exchange adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = DigitalTurbineExchangeAdapterConfiguration

    /**
     * A map of Chartboost Mediation's listeners for the corresponding load identifier.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

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
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> = withContext(IO) {
        PartnerLogController.log(SETUP_STARTED)

        suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, Any>>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue(APP_ID_KEY),
            ).trim()
                .takeIf { it.isNotEmpty() }
                ?.let { appId ->
                    InneractiveAdManager.initialize(
                        context,
                        appId,
                    ) { status: OnFyberMarketplaceInitializedListener.FyberInitStatus ->
                        resumeOnce(getInitResult(status))
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing app ID.")
                resumeOnce(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials)),
                )
            }
        }
    }

    /**
     * Notify Digital Turbine Exchange of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        // Digital Turbine Exchange does not have an API for setting COPPA
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return Result.success(emptyMap())
    }

    /**
     * Attempt to load a Digital Turbine Exchange ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            PartnerAdFormats.BANNER -> {
                loadBannerAd(context, request, partnerAdListener)
            }
            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED -> {
                loadFullscreenAd(request, partnerAdListener)
            }
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded Digital Turbine Exchange ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        val listener = listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            PartnerAdFormats.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED ->
                showFullscreenAd(
                    activity,
                    partnerAd,
                    listener,
                )
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
            }
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

        listeners.remove(partnerAd.request.identifier)
        return destroyAd(partnerAd)
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>
    ) {
        val consent = consents[configuration.partnerId]?.takeIf { it.isNotBlank() }
            ?: consents[ConsentKeys.GDPR_CONSENT_GIVEN]?.takeIf { it.isNotBlank() }
        consent?.let {
            if (it == ConsentValues.DOES_NOT_APPLY) {
                PartnerLogController.log(PartnerLogController.PartnerAdapterEvents.GDPR_NOT_APPLICABLE)
                return@let
            }

            PartnerLogController.log(
                when (it) {
                    ConsentValues.GRANTED -> PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
                    ConsentValues.DENIED -> PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
                    else -> PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
                },
            )

            InneractiveAdManager.setGdprConsent(it == ConsentValues.GRANTED)
        }

        consents[ConsentKeys.TCF]?.let {
            PartnerLogController.log(CUSTOM, "${PartnerLogController.PRIVACY_TAG} TCF String set")
            InneractiveAdManager.setGdprConsentString(it)
        }

        consents[ConsentKeys.USP]?.let {
            PartnerLogController.log(CUSTOM, "${PartnerLogController.PRIVACY_TAG} USP String: $it")
            InneractiveAdManager.setUSPrivacyString(it)
        }
    }

    /**
     * Determine the corresponding [Result] for a given Digital Turbine Exchange init status.
     *
     * @param status The Digital Turbine Exchange init status.
     *
     * @return Result.success() if Digital Turbine Exchange was initialized successfully, Result.failure() otherwise.
     */
    private fun getInitResult(status: OnFyberMarketplaceInitializedListener.FyberInitStatus): Result<Map<String, Any>> {
        return when (status) {
            // Digital Turbine Exchange's failed init is recoverable. It will be re-attempted on
            // the first ad request.
            OnFyberMarketplaceInitializedListener.FyberInitStatus.SUCCESSFULLY,
            OnFyberMarketplaceInitializedListener.FyberInitStatus.FAILED,
            -> {
                PartnerLogController.log(SETUP_SUCCEEDED)
                Result.success(emptyMap())
            }
            OnFyberMarketplaceInitializedListener.FyberInitStatus.FAILED_NO_KITS_DETECTED -> {
                PartnerLogController.log(SETUP_FAILED, "No kits detected.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown))
            }
            OnFyberMarketplaceInitializedListener.FyberInitStatus.INVALID_APP_ID -> {
                PartnerLogController.log(SETUP_FAILED, "Invalid app ID.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials))
            }
        }
    }

    /**
     * Attempt to load a Digital Turbine Exchange banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        val adSpot = InneractiveAdSpotManager.get().createSpot()
        val unitController = InneractiveAdViewUnitController()

        adSpot.addUnitController(unitController)
        adSpot.setMediationName(MEDIATOR_NAME)
        adSpot.mediationVersion = ChartboostMediationSdk.getVersion()

        return suspendCancellableCoroutine { continuation ->
            adSpot.setRequestListener(
                object : RequestListener {
                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onInneractiveSuccessfulAdRequest(ad: InneractiveAdSpot?) {
                        resumeOnce(
                            if (ad != adSpot) {
                                PartnerLogController.log(
                                    LOAD_FAILED,
                                    "Digital Turbine Exchange returned an ad for a different ad spot: $ad.",
                                )

                                Result.failure(
                                    ChartboostMediationAdException(
                                        ChartboostMediationError.LoadError.MismatchedAdFormat,
                                    ),
                                )
                            } else {
                                val controller =
                                    ad?.selectedUnitController as InneractiveAdViewUnitController
                                val bannerView = BannerView(context, adSpot)

                                controller.eventsListener =
                                    object : InneractiveAdViewEventsListener {
                                        override fun onAdImpression(ad: InneractiveAdSpot?) {
                                            PartnerLogController.log(DID_TRACK_IMPRESSION)
                                            listener.onPartnerAdImpression(
                                                PartnerAd(
                                                    ad = ad,
                                                    details = emptyMap(),
                                                    request = request,
                                                ),
                                            )
                                        }

                                        override fun onAdClicked(ad: InneractiveAdSpot?) {
                                            PartnerLogController.log(DID_CLICK)
                                            listener.onPartnerAdClicked(
                                                PartnerAd(
                                                    ad = ad,
                                                    details = emptyMap(),
                                                    request = request,
                                                ),
                                            )
                                        }

                                        override fun onAdWillCloseInternalBrowser(ad: InneractiveAdSpot?) {
                                        }

                                        override fun onAdWillOpenExternalApp(ad: InneractiveAdSpot?) {
                                        }

                                        override fun onAdEnteredErrorState(
                                            ad: InneractiveAdSpot?,
                                            error: AdDisplayError?,
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
                                        request = request,
                                    ),
                                )
                            },
                        )
                    }

                    override fun onInneractiveFailedAdRequest(
                        adSpot: InneractiveAdSpot?,
                        errorCode: InneractiveErrorCode?,
                    ) {
                        PartnerLogController.log(LOAD_FAILED, "$errorCode")
                        resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(errorCode))))
                    }
                },
            )

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
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadFullscreenAd(
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener

        val videoSpot = InneractiveAdSpotManager.get().createSpot()
        val unitController = InneractiveFullscreenUnitController()
        val videoController = InneractiveFullscreenVideoContentController()

        unitController.addContentController(videoController)
        videoSpot.addUnitController(unitController)
        videoSpot.setMediationName(MEDIATOR_NAME)
        videoSpot.mediationVersion = configuration.adapterVersion

        return suspendCancellableCoroutine { continuation ->
            videoSpot.setRequestListener(
                InterstitialAdLoadListener(
                    WeakReference(continuation),
                    request = request,
                ),
            )

            videoSpot.requestAd(InneractiveAdRequest(request.partnerPlacement))
        }
    }

    /**
     * Determine if a Digital Turbine Exchange ad is ready to show.
     *
     * @param format The format of the ad to be shown.
     * @param ad The generic Digital Turbine Exchange ad object.
     *
     * @return True if the ad is ready to show, false otherwise.
     */
    private fun readyToShow(
        format: PartnerAdFormat,
        ad: Any?,
    ): Boolean {
        return when {
            format == PartnerAdFormats.BANNER -> (ad is BannerView)
            format == PartnerAdFormats.INTERSTITIAL || format == PartnerAdFormats.REWARDED -> (ad as InneractiveAdSpot).isReady
            else -> false
        }
    }

    /**
     * Attempt to show a Digital Turbine Exchange fullscreen ad.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the Digital Turbine Exchange ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showFullscreenAd(
        activity: Activity,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
        if (!readyToShow(partnerAd.request.format, partnerAd.ad)) {
            PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
        }

        (partnerAd.ad as? InneractiveAdSpot)?.let { adSpot ->
            val controller =
                adSpot.selectedUnitController as? InneractiveFullscreenUnitController
                    ?: return Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.WrongResourceType))

            return suspendCancellableCoroutine { continuation ->
                controller.eventsListener =
                    InterstitialAdShowListener(
                        WeakReference(continuation),
                        listener = listener,
                        partnerAd = partnerAd
                    )

                controller.rewardedListener =
                    InneractiveFullScreenAdRewardedListener {
                        PartnerLogController.log(DID_REWARD)
                        listener?.onPartnerAdRewarded(partnerAd) ?: PartnerLogController.log(
                            CUSTOM,
                            "Unable to fire onPartnerAdRewarded for Digital Turbine Exchange adapter. Listener " +
                                "is null.",
                        )
                    }

                controller.show(activity)
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is not an InneractiveAdSpot.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.WrongResourceType))
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
                        "Ad is neither a BannerView nor an InneractiveAdSpot.",
                    )
                    return Result.failure(
                        ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType),
                    )
                }
            }

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
        }
    }

    /**
     * Callback for loading interstitial ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param request A [PartnerAdLoadRequest] object containing the request.
     */
    private class InterstitialAdLoadListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val request: PartnerAdLoadRequest,
    ): RequestListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(
                    LOAD_FAILED,
                    "Unable to resume continuation. Continuation is null."
                )
            }
        }

        override fun onInneractiveSuccessfulAdRequest(adSpot: InneractiveAdSpot) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = adSpot,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onInneractiveFailedAdRequest(
            adSpot: InneractiveAdSpot,
            errorCode: InneractiveErrorCode,
        ) {
            PartnerLogController.log(LOAD_FAILED, "Ad spot $adSpot. Error code: $errorCode")
            resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(errorCode))))
        }
    }

    /**
     * Callback for showing interstitial ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param listener A [PartnerAdListener] to be notified of ad events.
     * @param partnerAd A [PartnerAd] object containing the ad to show.
     */
    private class InterstitialAdShowListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val listener: PartnerAdListener?,
        private val partnerAd: PartnerAd,
    ): InneractiveFullscreenAdEventsListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(
                    SHOW_FAILED,
                    "Unable to resume continuation. Continuation is null."
                )
            }
        }

        override fun onAdImpression(ad: InneractiveAdSpot) {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            listener?.onPartnerAdImpression(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = partnerAd.request,
                ),
            ) ?: PartnerLogController.log(
                CUSTOM,
                "Unable to fire onPartnerAdImpression for Digital Turbine Exchange " +
                        "adapter. Listener is null",
            )

            PartnerLogController.log(SHOW_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = partnerAd.request,
                    ),
                ),
            )
        }

        override fun onAdClicked(ad: InneractiveAdSpot) {
            PartnerLogController.log(DID_CLICK)
            listener?.onPartnerAdClicked(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = partnerAd.request,
                ),
            ) ?: PartnerLogController.log(
                CUSTOM,
                "Unable to fire onPartnerAdClicked for Digital Turbine Exchange " +
                        "adapter. Listener is null.",
            )
        }

        override fun onAdWillCloseInternalBrowser(ad: InneractiveAdSpot) {
        }

        override fun onAdWillOpenExternalApp(ad: InneractiveAdSpot) {
        }

        override fun onAdEnteredErrorState(
            ad: InneractiveAdSpot,
            error: AdDisplayError,
        ) {
            PartnerLogController.log(SHOW_FAILED, "Error: $error")
            resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.Unknown)))

            ad.destroy()
        }

        override fun onAdDismissed(ad: InneractiveAdSpot) {
            PartnerLogController.log(DID_DISMISS)
            listener?.onPartnerAdDismissed(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = partnerAd.request,
                ),
                null,
            ) ?: PartnerLogController.log(
                CUSTOM,
                "Unable to fire onPartnerAdDismissed for Digital Turbine Exchange adapter. Listener " +
                        "is null.",
            )

            ad.destroy()
        }
    }
}
