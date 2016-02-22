
package com.mediatek.internal.telephony.ltedc.svlte.apirat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.util.Pair;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.IccCardConstants.CardType;
import com.android.internal.telephony.Phone;
import com.mediatek.internal.telephony.ltedc.svlte.IratController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteIratUtils;
import com.mediatek.internal.telephony.ltedc.svlte.SvltePhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRatController;

/**
 * AP IRAT controller.
 * @hide
 */
public class ApIratController extends IratController {
    private static final String LOG_TAG = "[AP_IRAT_CTRL]";

    private static final String PROP_NAME_TEST_SET_SOURCE_RAT = "mtk.test.set.source.rat";
    private static final String PROP_NAME_TEST_SET_TARGET_RAT = "mtk.test.set.target.rat";

    private static final String PREF_NAME_AP_IRAT = "ap_irat_control";
    private static final String PREF_KEY_CARD_TYPE = "ct_card_type";

    private static final int EVENT_DATA_DISCONNECT_DONE_ON_SOURCE_RAT = 200;
    private static final int EVENT_AIRPLANE_MODE_CHANGED = 201;
    private static final int EVENT_IRAT_SIM_RADIO_POWER_CHANGED = 202;
    private static final int EVENT_LTE_ON_CDMA_MODE_CHANGED = 203;
    private static final int EVENT_ROAMING_ON = 204;
    private static final int EVENT_ROAMING_OFF = 205;
    private static final int EVENT_CDMA_CARD_TYPE_LOADED = 206;
    // CM/AOSP
    private static final int EVENT_VOICE_ROAMING_ON = 900;
    private static final int EVENT_VOICE_ROAMING_OFF = 901;
    private static final int EVENT_DATA_ROAMING_ON = 902;
    private static final int EVENT_DATA_ROAMING_OFF = 903;

    private Handler mLteBgSearchHandler = new Handler();
    private Handler mMultiModeHandler = new Handler();

    private LteBgSearchController mLteBgSearchController;

    private MultiModeController mMultiModeController;

    // Whether the device is in airplane mode.
    private boolean mAirplaneMode;

    // Whether RAT mode is 2/3/4G, 4G switch in Settings.
    private boolean mRatMode234GAuto;

    // Whether the SIM is 4G support SIM, false if it is 2G/3G SIM.
    private IccCardConstants.CardType mCardType = IccCardConstants.CardType.UNKNOW_CARD;
    private boolean mSimSupportLte;

    // Whether the LTE SIM is radio on.
    private boolean mIratSimRadioOn;

    // Whether the device is in roaming area.
    // CM/AOSP: separate voice and data roaming statuses
    private boolean mVoiceRoamingOn;
    private boolean mDataRoamingOn;

    private BroadcastReceiver mIntentReceiver;

    private RegistrantList mLteDataRegStateOrRatChangedRegistrants = new RegistrantList();
    private RegistrantList mCdmaDataRegStateOrRatChangedRegistrants = new RegistrantList();

    /**
     * Handles broadcast such as airplane mode change.
     */
    private class IratControlReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            log("Receive action " + action);
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                sendEmptyMessage(EVENT_AIRPLANE_MODE_CHANGED);
            } else if (Intent.ACTION_MSIM_MODE_CHANGED.equals(action)) {
                sendEmptyMessage(EVENT_IRAT_SIM_RADIO_POWER_CHANGED);
            }
        }
    };

    /**
     * Handles changes to LTE on CDMA mode.
     */
    private class LteOnCdmaChangeObserver extends ContentObserver {
        public LteOnCdmaChangeObserver() {
            super(ApIratController.this);
        }

        @Override
        public void onChange(boolean selfChange) {
            // 4G switch state change.
            log("Settings change, selfChange=" + selfChange);
            sendEmptyMessage(EVENT_LTE_ON_CDMA_MODE_CHANGED);
        }
    }
    private LteOnCdmaChangeObserver mObserver;

    /**
     * Constructor, register for IRAT status change and data reg state.
     * @param svltePhoneProxy SVLTE phone proxy
     */
    public ApIratController(SvltePhoneProxy svltePhoneProxy) {
        super(svltePhoneProxy);

        mCardType = getCardTypeFromPref();
        mSimSupportLte = isSimSupportLte();

        mRatMode234GAuto = isRatMode234GAuto();
        mIratSimRadioOn = isIratSimPowerOn();
        mAirplaneMode = isInAirPlaneMode();
        mIratControllerEnabled = (!mAirplaneMode && mSimSupportLte
                && mIratSimRadioOn && mRatMode234GAuto && !mVoiceRoamingOn
                && !mDataRoamingOn);
        log("ApIratController: mCardType = " + mCardType
                + ", mSimSupportLte = " + mSimSupportLte + ", mRatMode234GAuto = "
                + mRatMode234GAuto + ", mIratSimRadioOn = " + mIratSimRadioOn
                + ",mAirplaneMode = " + mAirplaneMode
                + ", mIratControllerEnabled = " + mIratControllerEnabled);

        mLteBgSearchController = new LteBgSearchController(svltePhoneProxy,
                mIratControllerEnabled, this, mLteBgSearchHandler);
        mLteBgSearchController.start();

        mMultiModeController = new MultiModeController(svltePhoneProxy,
                mIratControllerEnabled, this, mMultiModeHandler);
        mMultiModeController.start();
    }

    /**
     * Unregister from all events it registered for.
     */
    public void dispose() {
        log("dispose");
        super.dispose();
        mLteBgSearchController.dispose();
        mMultiModeController.dispose();
    }

    @Override
    protected void registerForAllEvents() {
        super.registerForAllEvents();

        // Register for screen on message
        mIntentReceiver = new IratControlReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_MSIM_MODE_CHANGED);
        mContext.registerReceiver(mIntentReceiver, filter);

        // Register for 4G switch state change.
        mObserver = new LteOnCdmaChangeObserver();
        mContext.getContentResolver()
                .registerContentObserver(
                        Settings.Global
                                .getUriFor(Settings.Global.LTE_ON_CDMA_RAT_MODE),
                        false, mObserver);

        mCdmaPhone.getServiceStateTracker().registerForVoiceRoamingOn(this,
                EVENT_VOICE_ROAMING_ON, null);
        mCdmaPhone.getServiceStateTracker().registerForVoiceRoamingOff(this,
                EVENT_VOICE_ROAMING_OFF, null);
        mCdmaPhone.getServiceStateTracker().registerForDataRoamingOn(this,
                EVENT_DATA_ROAMING_ON, null);
        mCdmaPhone.getServiceStateTracker().registerForDataRoamingOff(this,
                EVENT_DATA_ROAMING_OFF, null);
        mCdmaPhone.mCi.registerForCdmaCardType(this, EVENT_CDMA_CARD_TYPE_LOADED, null);
    }

    @Override
    protected void unregisterForAllEvents() {
        super.unregisterForAllEvents();

        mContext.unregisterReceiver(mIntentReceiver);
        mContext.getContentResolver().unregisterContentObserver(mObserver);

        mCdmaPhone.getServiceStateTracker().unregisterForVoiceRoamingOn(this);
        mCdmaPhone.getServiceStateTracker().unregisterForVoiceRoamingOff(this);
        mCdmaPhone.getServiceStateTracker().unregisterForDataRoamingOn(this);
        mCdmaPhone.getServiceStateTracker().unregisterForDataRoamingOff(this);
        mCdmaPhone.mCi.unregisterForCdmaCardType(this);
    }

    @Override
    public void setInitialPsType(int newPsType) {
        super.setInitialPsType(newPsType);
        mMultiModeController.setInitialPsServiceType(newPsType);
    }

    @Override
    protected void onLteDataRegStateOrRatChange(int drs, int rat) {
        log("onLteDataRegStateOrRatChange: mLteRegState = " + mLteRegState
                + ", mPsType = " + mPsType + ", rat = " + rat);
        notifyLteDataRegStateRilRadioTechnologyChanged();
    }

    @Override
    protected void onCdmaDataRegStateOrRatChange(int drs, int rat) {
        log("onCdmaDataRegStateOrRatChange: mPsType = " + mPsType + ", drs = "
                + drs + ", rat = " + rat);
        notifyCdmaDataRegStateRilRadioTechnologyChanged();

        // Only update current RAT when PS type is on CDMA, for 1xRTT and EVDO
        // RAT change.
        if (mPsType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
            updateCurrentRat(rat);
        }
    }

    @Override
    protected void onSimMissing() {
        resetStatus();
    }

    @Override
    protected void updateCurrentRat(int newRat) {
        log("updateCurrentRat: mIsDuringIrat = " + mIsDuringIrat
                + ", newRat = " + newRat + ", mCurrentRat = " + mCurrentRat
                + ", mLteRegState = " + mLteRegState + ", mCdmaRegState = "
                + mCdmaRegState + ", mLteRat = " + mLteRat + ", mCdmaRat = "
                + mCdmaRat + ", mPsType = " + mPsType);

        mPrevRat = mCurrentRat;
        if (mPsType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
            // Use LTE RAT only if current service on LTE.
            mCurrentRat = mLteRat;
        } else {
            mCurrentRat = mCdmaRat;
            // Notify data attached since the switch may cause DcTracker run
            // into detach state when PS on LTE, CDMA won't report ECGREG to
            // notify PS state.
            if (mPsType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
                mSvltePhoneProxy.getIratDataSwitchHelper()
                        .syncAndNotifyAttachState();
            }
        }

        if (mPrevRat != mCurrentRat
                && mPrevRat == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
            mSvltePhoneProxy.updatePsPhone(mPrevRat, mCurrentRat);
            notifyRatChange(mPrevRat, mCurrentRat);
        }
    }

    @Override
    protected boolean processMessage(Message msg) {
        boolean ret = false;

        switch (msg.what) {
            case EVENT_DATA_DISCONNECT_DONE_ON_SOURCE_RAT:
                log("EVENT_DATA_DISCONNECT_DONE_ON_SOURCE_RAT: notify IRAT end.");
                notifyIratEnd((ApIratInfo) msg.obj);
                ret = true;
                break;
            case EVENT_AIRPLANE_MODE_CHANGED:
                log("EVENT_AIRPLANE_MODE_CHANGE");
                onAirplaneModeChanged();
                ret = true;
                break;
            case EVENT_IRAT_SIM_RADIO_POWER_CHANGED:
                log("EVENT_LTE_SIM_RADIO_POWER_OFF");
                onIratSimPowerStateChange();
                ret = true;
                break;
            case EVENT_LTE_ON_CDMA_MODE_CHANGED:
                log("EVENT_LTE_ON_CDMA_MODE_CHANGE");
                onLteOnCdmaModeChanged();
                ret = true;
                break;
            // CM/AOSP
            case EVENT_VOICE_ROAMING_ON:
                log("EVENT_VOICE_ROAMING_ON");
                mVoiceRoamingOn = true;
                onVoiceRoamingStateChanged();
                ret = true;
                break;
            case EVENT_VOICE_ROAMING_OFF:
                log("EVENT_VOICE_ROAMING_OFF");
                mVoiceRoamingOn = false;
                onVoiceRoamingStateChanged();
                ret = true;
                break;
            case EVENT_DATA_ROAMING_ON:
                log("EVENT_DATA_ROAMING_ON");
                mDataRoamingOn = true;
                onDataRoamingStateChanged();
                ret = true;
                break;
            case EVENT_DATA_ROAMING_OFF:
                log("EVENT_DATA_ROAMING_OFF");
                mDataRoamingOn = false;
                onDataRoamingStateChanged();
                ret = true;
                break;
            case EVENT_CDMA_CARD_TYPE_LOADED:
                log("EVENT_CDMA_CARD_TYPE_LOADED: msg.obj = " + msg.obj);
                onCdmaCardTypeLoaded((AsyncResult) msg.obj);
                ret = true;
                break;
            default:
                break;
        }
        return ret || super.processMessage(msg);
    }

    /**
     * Registration for LTE DataConnection RIL Data Radio Technology changing.
     * To make sure the notify message will be sent later than IRAT controller
     * updated the reg state and RAT.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForLteDataRegStateOrRatChanged(Handler h, int what,
            Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mLteDataRegStateOrRatChangedRegistrants.add(r);
    }

    /**
     * Unregister for LTE DataConnection RIL Data Radio Technology changing.
     * @param h handler to notify
     */
    public void unregisterForLteDataRegStateOrRatChanged(Handler h) {
        mLteDataRegStateOrRatChangedRegistrants.remove(h);
    }

    private void notifyLteDataRegStateRilRadioTechnologyChanged() {
        log("notifyLteDataRegStateRilRadioTechnologyChanged: mLteRegState="
                + mLteRegState + ", mLteRat=" + mLteRat);
        mLteDataRegStateOrRatChangedRegistrants
                .notifyResult(new Pair<Integer, Integer>(mLteRegState, mLteRat));
    }

    /**
     * Registration for CDMA DataConnection RIL Data Radio Technology changing.
     * To make sure the notify message will be sent later than IRAT controller
     * updated the reg state and RAT.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForCdmaDataRegStateOrRatChanged(Handler h, int what,
            Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mCdmaDataRegStateOrRatChangedRegistrants.add(r);
    }

    /**
     * Unregister for CDMA DataConnection RIL Data Radio Technology changing.
     * @param h handler to notify
     */
    public void unregisterForCdmaDataRegStateOrRatChanged(Handler h) {
        mCdmaDataRegStateOrRatChangedRegistrants.remove(h);
    }

    private void notifyCdmaDataRegStateRilRadioTechnologyChanged() {
        log("notifyCdmaDataRegStateRilRadioTechnologyChanged: mCdmaRegState="
                + mCdmaRegState + ", mCdmaRat=" + mCdmaRat);
        mCdmaDataRegStateOrRatChangedRegistrants
                .notifyResult(new Pair<Integer, Integer>(mCdmaRegState,
                        mCdmaRat));
    }

    private void onAirplaneModeChanged() {
        mAirplaneMode = isInAirPlaneMode();
        log("onAirplaneModeChanged: mAirplaneMode = " + mAirplaneMode);
        updateIratControllerEnableState();
    }

    private void onLteOnCdmaModeChanged() {
        mRatMode234GAuto = isRatMode234GAuto();
        updateIratControllerEnableState();

        if (!mRatMode234GAuto) {
            if (mPsType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
                if (mSvltePhoneProxy.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA
                        && isRatMode23GAuto()) {
                    // Switch to 3G CDMA mode and current in CDMA home or roaming.
                    notifyIratEvent(SvlteIratUtils.PS_SERVICE_ON_CDMA);
                }
            } else if (mPsType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
                if (isTddDataOnlyMode() && mSimSupportLte) {
                    // Switch to TDD data only mode
                    notifyIratEvent(SvlteIratUtils.PS_SERVICE_ON_LTE);
                }
            }
        }
    }

    private void onIratSimPowerStateChange() {
        mIratSimRadioOn = isIratSimPowerOn();
        updateIratControllerEnableState();
    }

    private void onVoiceRoamingStateChanged() {
        // TODO: need to disable or switch mode for 4M project?
        updateIratControllerEnableState();
    }

    private void onDataRoamingStateChanged() {
        // TODO: need to disable or switch mode for 4M project?
        updateIratControllerEnableState();
    }

    private void onCdmaCardTypeLoaded(AsyncResult ar) {
        if (ar.exception == null) {
            int[] resultType = (int[]) ar.result;
            if (resultType != null) {
                mCardType = CardType.values()[resultType[0]];
                saveCardTypeToPref();
                mSimSupportLte = isSimSupportLte();
                log("onCdmaCardTypeLoaded: resultType[0] = " + resultType[0]
                        + ", mCardType = " + mCardType + ", mSimSupportLte = "
                        + mSimSupportLte);
                updateIratControllerEnableState();
            }
        }
    }

    /**
     * Notify IRAT event with current PS type.
     * @param curPsType Current PS type.
     */
    public void notifyIratEvent(int curPsType) {
        log("notifyIratEvent: mPsType = " + mPsType + ", curPsType = "
                + curPsType + ", mCdmaRegState = " + mCdmaRegState
                + ", mLteRegState = " + mLteRegState + ", mPrevRat = "
                + mPrevRat + ", mCurrentRat = " + mCurrentRat + ", mLteRat = "
                + mLteRat + ", mCdmaRat = " + mCdmaRat);
        mPsType = curPsType;
        updateCurrentRat(mCurrentRat);

        if (!isNetworkRegistered(mCdmaRegState)
                && !isNetworkRegistered(mLteRegState)) {
            log("Ignore fake IRAT event since no network is registered.");
            return;
        }

        sendPsTypeChangeBroadcast();

        ApIratInfo info = new ApIratInfo();
        int testSourceRat = SystemProperties.getInt(
                PROP_NAME_TEST_SET_SOURCE_RAT, 0);
        if (testSourceRat != 0) {
            log("Use " + testSourceRat + " instead fo " + mPrevRat
                    + " for test IRAT.");
            info.sourceRat = testSourceRat;
        } else {
            if (curPsType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
                info.sourceRat = mCdmaRat;
            } else if (curPsType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
                info.sourceRat = mLteRat;
            } else {
                info.sourceRat = mCurrentRat;
            }
        }

        int testTargetRat = SystemProperties.getInt(
                PROP_NAME_TEST_SET_TARGET_RAT, 0);
        if (testSourceRat != 0) {
            log("Use " + testTargetRat + " instead fo " + mCurrentRat
                    + " for test IRAT.");
            info.targetRat = testTargetRat;
        } else {
            if (curPsType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
                // The reg state is delayed report to IRAT controller
                if (mLteRat != ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
                    logw("IRAT to LTE but LTE RAT is not right: mLteRat = " + mLteRat);
                    info.targetRat = ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
                } else {
                    info.targetRat = mLteRat;
                }
            } else if (curPsType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
                // The CDMA reg state is delayed report to IRAT controller
                if (mCdmaRat == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                    logw("IRAT to CDMA but CDMA RAT is unkown.");
                    info.targetRat = ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT;
                } else {
                    info.targetRat = mCdmaRat;
                }
            } else {
                info.targetRat = ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
            }
        }

        if (info.sourceRat == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
            info.type = ApIratInfo.IratType.IRAT_TYPE_LTE_HRPD;
        } else if (info.targetRat == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
            info.type = ApIratInfo.IratType.IRAT_TYPE_HRPD_LTE;
        } else {
            info.type = ApIratInfo.IratType.IRAT_TYPE_UNKNOWN;
        }
        notifyIratStart(info);
    }

    private void notifyIratStart(ApIratInfo info) {
        log("notifyIratStart: info = " + info);
        Message disconnectMessage = Message.obtain(this,
                EVENT_DATA_DISCONNECT_DONE_ON_SOURCE_RAT);
        disconnectMessage.obj = info;
        mDcTracker.cleanUpAllConnections(Phone.REASON_CDMA_FALLBACK_HAPPENED,
                disconnectMessage);
        for (OnIratEventListener listener : mIratEventListener) {
            log("notifyIratStart: listener = " + listener);
            listener.onIratStarted(info);
        }
    }

    private void notifyIratEnd(ApIratInfo info) {
        log("notifyIratEnd: info = " + info);
        mSvltePhoneProxy.updatePsPhone(info.sourceRat, info.targetRat);
        for (OnIratEventListener listener : mIratEventListener) {
            log("notifyIratEnd: listener = " + listener);
            listener.onIratEnded(info);
        }
    }

    private void sendPsTypeChangeBroadcast() {
        // Send broadcast
        Intent intent = new Intent(SvlteIratUtils.ACTION_IRAT_PS_TYPE_CHANGED);
        intent.putExtra(SvlteIratUtils.EXTRA_PS_TYPE, mPsType);
        mContext.sendBroadcast(intent);
    }

    private void updateIratControllerEnableState() {
        mIratControllerEnabled = (!mAirplaneMode && mSimSupportLte
                && mIratSimRadioOn && mRatMode234GAuto && !mVoiceRoamingOn
                && !mDataRoamingOn);
        log("updateIratControllerEnableState: mAirplaneMode = " + mAirplaneMode
                + ", mSimLteSupport = " + mSimSupportLte
                + ", mIratSimRadioOn = " + mIratSimRadioOn
                + ", mLteSwtichOn = " + mRatMode234GAuto + ", mVoiceRoamingOn = "
                + mVoiceRoamingOn + ", mDataRoamingOn = " + mDataRoamingOn
                + ", mIratControllerEnabled = "
                + mIratControllerEnabled);

        mLteBgSearchController.setEnabled(mIratControllerEnabled);
        mMultiModeController.setEnabled(mIratControllerEnabled);
    }

    /**
     * Whether IRAT controller enabled, if it is disabled, MMC and LTE
     * background search function will be disabled.
     * @return True for some condition.
     */
    public boolean isIratControllerEnabled() {
        return mIratControllerEnabled;
    }

    /**
     * Whether the RAT mode is 2/3/4G auto.
     * @return True if RAT mode is 2/3/4G auto.
     */
    private boolean isRatMode234GAuto() {
        SvlteRatController.SvlteRatMode ratMode = mSvltePhoneProxy.getRatMode();
        log("isRatMode234GAuto: ratMode = " + ratMode);
        return (ratMode == SvlteRatController.SvlteRatMode.SVLTE_RAT_MODE_4G);
    }

    /**
     * Whether current is in TDD data only mode.
     * @return True if current is in TDD data only mode
     */
    private boolean isTddDataOnlyMode() {
        SvlteRatController.SvlteRatMode ratMode = mSvltePhoneProxy.getRatMode();
        log("isTddDataOnlyMode: ratMode = " + ratMode);
        return (ratMode == SvlteRatController.SvlteRatMode.SVLTE_RAT_MODE_4G_DATA_ONLY);
    }

    /**
     * Whether the RAT mode is 2/3G auto.
     * @return True if RAT mode is 2/3G auto.
     */
    private boolean isRatMode23GAuto() {
        SvlteRatController.SvlteRatMode ratMode = mSvltePhoneProxy.getRatMode();
        log("isRatMode23GAuto: ratMode = " + ratMode);
        return (ratMode == SvlteRatController.SvlteRatMode.SVLTE_RAT_MODE_3G);
    }

    /**
     * Whether the SIM card support LTE.
     * @return True if the SIM is CT 4G UICC or CDMA UICC card(test card).
     */
    private boolean isSimSupportLte() {
        return (mCardType == IccCardConstants.CardType.CT_4G_UICC_CARD ||
                mCardType == IccCardConstants.CardType.CDMA_UICC_CARD);
    }

    /**
     * Save card type to preference, it will be used for next boot.
     */
    private void saveCardTypeToPref() {
        SharedPreferences pref = mContext.getSharedPreferences(
                PREF_NAME_AP_IRAT, 0);
        Editor editor = pref.edit();
        editor.putInt(PREF_KEY_CARD_TYPE, mCardType.ordinal());
        editor.commit();
        log("saveCardTypeToPref: mCardType = " + mCardType + ", index = "
                + mCardType.ordinal());
    }

    /**
     * Get card type from preference since the new card type is not loaded from
     * MD.
     * @return Card type.
     */
    private IccCardConstants.CardType getCardTypeFromPref() {
        SharedPreferences pref = mContext.getSharedPreferences(
                PREF_NAME_AP_IRAT, 0);
        int cardTypeIndex = pref.getInt(PREF_KEY_CARD_TYPE, -1);
        log("getCardTypeFromPref: cardTypeIndex = " + cardTypeIndex);
        if (cardTypeIndex == -1) {
            // Set initial card type for LTE SIM for most condition.
            return IccCardConstants.CardType.CT_4G_UICC_CARD;
        } else {
            return CardType.values()[cardTypeIndex];
        }
    }

    /**
     * Check whether the radio of IRAT SIM is powered on.
     * @return True if radio is on.
     */
    private boolean isIratSimPowerOn() {
        boolean iratSimPowerOn = true;
        int currentSimMode = Settings.System.getInt(
                mContext.getContentResolver(),
                Settings.System.MSIM_MODE_SETTING, -1);
        // IRAT SIM is power on.
        if ((currentSimMode & (1 << SvlteIratUtils.getIratSupportSlotId())) == 0) {
            iratSimPowerOn = false;
        }
        log("isIratSimPowerOn: currentSimMode = " + currentSimMode
                + ", iratSimPowerOn = " + iratSimPowerOn);
        return iratSimPowerOn;
    }

    /**
     * Whether the device is in airplane mode.
     * @return True is the device is in airplane mode.
     */
    private boolean isInAirPlaneMode() {
        return (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
    }

    /**
     * Whether the given reg state represent network registered.
     * @param regState Reg state.
     * @return True if registered.
     */
    private boolean isNetworkRegistered(int regState) {
        return regState == ServiceState.STATE_IN_SERVICE;
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, s);
    }

    private void logw(String s) {
        Rlog.w(LOG_TAG, s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, s);
    }
}
