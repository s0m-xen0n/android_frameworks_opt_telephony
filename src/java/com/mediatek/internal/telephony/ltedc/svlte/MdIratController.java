
package com.mediatek.internal.telephony.ltedc.svlte;

import android.content.Intent;
import android.os.AsyncResult;
import android.os.Message;
import android.telephony.Rlog;
import android.telephony.ServiceState;

import com.android.internal.telephony.dataconnection.DctController;

/**
 * It register for IRAT related URC and data reg state to track data rat. It
 * will notify to his registrants and send broadcasts when data rat changed.
 * IRAT status can be get from it.
 */
public class MdIratController extends IratController {
    private static final String LOG_TAG = "CDMA";

    // Broadcast action and extras for IRAT.
    public static final String ACTION_IRAT_STARTED = "com.mediatek.irat.action.started";
    public static final String ACTION_IRAT_FINISHED = "com.mediatek.irat.action.finished";
    public static final String ACTION_IRAT_SUCCEEDED = "com.mediatek.irat.action.succeeded";
    public static final String ACTION_IRAT_FAILED = "com.mediatek.irat.action.failed";
    public static final String SOURCE_RAT = "extra_source_rat";
    public static final String TARGET_RAT = "extra_target_rat";

    // IRAT actions.
    public static final int IRAT_ACTION_SOURCE_STARTED = 1;
    public static final int IRAT_ACTION_SOURCE_FINISHED = 2;
    public static final int IRAT_ACTION_TARGET_STARTED = 3;
    public static final int IRAT_ACTION_TARGET_FINISHED = 4;

    // Message for events.
    private static final int EVENT_LTE_INTER_3GPP_IRAT = 100;
    private static final int EVENT_CDMA_INTER_3GPP_IRAT = 101;
    protected static final int EVENT_SYNC_DATA_CALL_LIST_DONE = 102;

    // Rat indicator reported from modem when IRAT
    private static final int RAT_FOR_INTER_3GPP_IRAT_NOT_SPECIFIED = 0;
    private static final int RAT_FOR_INTER_3GPP_IRAT_1xRTT = 1;
    private static final int RAT_FOR_INTER_3GPP_IRAT_HRPD = 2;
    private static final int RAT_FOR_INTER_3GPP_IRAT_EHRPD = 3;
    private static final int RAT_FOR_INTER_3GPP_IRAT_LTE = 4;

    // IRAT confirm flag
    private static final int IRAT_CONFIRM_ACCEPTED = 1;
    private static final int IRAT_CONFIRM_DENIED = 0;

    /**
     * Constructor, register for IRAT status change and data reg state.
     * @param svltePhoneProxy SVLTE phone proxy
     */
    public MdIratController(SvltePhoneProxy svltePhoneProxy) {
        super(svltePhoneProxy);
    }

    /**
     * Unregister from all events it registered for.
     */
    public void dispose() {
        log("dispose");
        super.dispose();
    }

    @Override
    protected void registerForAllEvents() {
        super.registerForAllEvents();
        mLteCi.registerForIratStateChanged(this, EVENT_LTE_INTER_3GPP_IRAT,
                null);

        mCdmaCi.registerForIratStateChanged(this, EVENT_CDMA_INTER_3GPP_IRAT,
                null);
    }

    @Override
    protected void unregisterForAllEvents() {
        super.unregisterForAllEvents();
        mLteCi.unregisterForIratStateChanged(this);

        mCdmaCi.unregisterForIratStateChanged(this);
    }

    private synchronized void notifyIratEvent(int eventType, MdIratInfo info) {
        for (OnIratEventListener listener : mIratEventListener) {
            log("notifyIratEvent: listener = " + listener);
            if (eventType == IRAT_ACTION_SOURCE_STARTED) {
                listener.onIratStarted(info);
            } else if (eventType == IRAT_ACTION_TARGET_FINISHED) {
                listener.onIratEnded(info);
            }
        }
    }

    @Override
    public boolean isDrsInService() {
        log("isDrsInService: mLteRegState = " + mLteRegState
                + ", mCdmaRegState = " + mCdmaRegState);
        return mLteRegState == ServiceState.STATE_IN_SERVICE
                || mCdmaRegState == ServiceState.STATE_IN_SERVICE;
    }

    @Override
    protected boolean processMessage(Message msg) {
        boolean ret = false;
        AsyncResult ar = null;
        log("processMessage, msg.what = " + msg.what);
        switch (msg.what) {
            case EVENT_LTE_INTER_3GPP_IRAT:
            case EVENT_CDMA_INTER_3GPP_IRAT:
                ar = (AsyncResult) msg.obj;
                MdIratInfo info = (MdIratInfo) ar.result;
                log("processMessage, EVENT_INTER_3GPP_IRAT[" + msg.what
                        + "] status = " + info.toString());

                if (info.action == IRAT_ACTION_SOURCE_STARTED) {
                    mSstProxy.setEnabled(false);
                    notifyIratEvent(info.action, info);
                    onIratStarted(info);
                } else if (info.action == IRAT_ACTION_TARGET_FINISHED) {
                    mSstProxy.setEnabled(true);
                    onIratFinished(info);
                    notifyIratEvent(info.action, info);
                }
                ret = true;
                break;

            case EVENT_SYNC_DATA_CALL_LIST_DONE:
                onSyncDataCallListDone((AsyncResult) msg.obj);
                ret = true;
                break;
            default:
                break;
        }
        return ret || super.processMessage(msg);
    }

    @Override
    protected void onLteDataRegStateOrRatChange(int drs, int rat) {
        updateCurrentRat(rat);
    }

    @Override
    protected void onCdmaDataRegStateOrRatChange(int drs, int rat) {
        updateCurrentRat(rat);
    }

    @Override
    protected void onSimMissing() {
        resetStatus();
    }

    private void onIratStarted(MdIratInfo info) {
        log("onIratStarted: info = " + info + ", mCurrentRat = " + mCurrentRat);
        mIsDuringIrat = true;

        suspendDataRequests();

        // confirm IRAT start
        if (info.sourceRat == RAT_FOR_INTER_3GPP_IRAT_LTE) {
            mLteCi.confirmIratChange(IRAT_CONFIRM_ACCEPTED, null);
        } else {
            mCdmaCi.confirmIratChange(IRAT_CONFIRM_ACCEPTED, null);
        }

        notifyIratStarted(info);
    }

    private void onIratFinished(MdIratInfo info) {
        log("onIratFinished: mPrevRat = " + mPrevRat + ", mCurrentRat = " + mCurrentRat
                + ", info =" + info);
        mIsDuringIrat = false;

        if (info.sourceRat != info.targetRat) {
            // We need to update RAT because +ECGREG/+CEREG may be handled after
            // IRAT finished.
            mPrevRat = mappingRatToRadioTech(info.sourceRat);
            mCurrentRat = mappingRatToRadioTech(info.targetRat);

            log("onIratFinished: mCurrentRat = "
                    + ServiceState.rilRadioTechnologyToString(mCurrentRat)
                    + ", mPrevRat = "
                    + ServiceState.rilRadioTechnologyToString(mPrevRat));
            if (SvlteIratUtils.getRadioGroupByRat(mPrevRat) != SvlteIratUtils
                    .getRadioGroupByRat(mCurrentRat)) {
                mSvltePhoneProxy.updatePsPhone(mPrevRat, mCurrentRat);
                mPsCi = mSvltePhoneProxy.getPsPhone().mCi;
            }

            // Only get data call list in non Fallback case.
            if (info.type.isIpContinuousCase()) {
                log("onIratFinished: mPsCi = " + mPsCi);
                mPsCi.getDataCallList(obtainMessage(EVENT_SYNC_DATA_CALL_LIST_DONE));
            } else {
                sendMessage(obtainMessage(EVENT_SYNC_DATA_CALL_LIST_DONE));
            }
        } else {
            resumeDataRequests();
        }

        notifyIratFinished(info);
    }

    private void onSyncDataCallListDone(AsyncResult dcList) {
        log("onSyncDataCallListDone: dcList = " + dcList);
        if (dcList != null) {
            mPsCi.syncNotifyDataCallList(dcList);
        }
        resumeDataRequests();
    }

    private void suspendDataRequests() {
        log("suspendDataRequests...");
        // Suspend network request and data RIL request.
        DctController.getInstance().suspendNetworkRequest();
        mSvltePhoneProxy.getRilDcArbitrator().suspendDataRilRequest();
    }

    private void resumeDataRequests() {
        log("resumeDataRequests...");
        // Resume network request and data RIL request.
        DctController.getInstance().resumeNetworkRequest();
        mSvltePhoneProxy.getRilDcArbitrator().resumeDataRilRequest();
    }

    private void notifyIratStarted(MdIratInfo info) {
        // Send broadcast
        Intent intent = new Intent(ACTION_IRAT_STARTED);
        intent.putExtra(SOURCE_RAT, mCurrentRat);
        mContext.sendBroadcast(intent);
    }

    private void notifyIratFinished(MdIratInfo info) {
        notifyRatChange(mPrevRat, mCurrentRat);

        // Send broadcast
        Intent intent = new Intent(ACTION_IRAT_FINISHED);
        intent.putExtra(SOURCE_RAT, mPrevRat);
        intent.putExtra(TARGET_RAT, mCurrentRat);
        mContext.sendBroadcast(intent);
    }

    /**
     * Mapping RAT from modem to real radio technology.
     * @param rat RAT from MD during IRAT.
     * @return Radio technology suppose to be.
     */
    private int mappingRatToRadioTech(int rat) {
        if (rat == RAT_FOR_INTER_3GPP_IRAT_LTE) {
            return ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
        } else if (rat == RAT_FOR_INTER_3GPP_IRAT_EHRPD) {
            return ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD;
        } else if (rat == RAT_FOR_INTER_3GPP_IRAT_HRPD) {
            return ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A;
        } else if (rat == RAT_FOR_INTER_3GPP_IRAT_1xRTT) {
            return ServiceState.RIL_RADIO_TECHNOLOGY_IS95A;
        }
        return ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
    }

    @Override
    protected void updateCurrentRat(int newRat) {
        log("updateCurrentRat: mIsDuringIrat = " + mIsDuringIrat
                + ", newRat = " + newRat + ", mCurrentRat = " + mCurrentRat);
        mPrevRat = mCurrentRat;
        mCurrentRat = newRat;

        if (!mIsDuringIrat) {
            // Update PS phone when first register on network before notify to
            // make PS phone right.
            if (mPrevRat == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                mSvltePhoneProxy.updatePsPhone(mPrevRat, mCurrentRat);
            }

            if (mPrevRat != mCurrentRat) {
                notifyRatChange(mPrevRat, mCurrentRat);
            }

            if (mPrevRat == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN
                && mCurrentRat != ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
                // PS ATTACHED EVENT may be ahead RAT CHANGE EVENT.
                // Resend ATTACEHD event after first RAT change
                //     to fire DctController / DcTracker
                mSvltePhoneProxy.getIratDataSwitchHelper().syncAndNotifyAttachState();
            }
        }
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[MD_IRAT_Controller] " + s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[MD_IRAT_Controller] " + s);
    }
}
