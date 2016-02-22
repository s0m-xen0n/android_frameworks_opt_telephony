package com.mediatek.internal.telephony.ltedc.svlte.apirat;

import java.util.ArrayList;
import java.util.List;

import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.ServiceState;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.cdma.CdmaServiceStateTracker;
import com.android.internal.telephony.uicc.UiccController;
import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRatController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRatController.RoamingMode;

/**
 * International roaming controller for ap based IR on SVLTE IRAT.
 *
 * @hide
 */
public class SvlteIrController {
    private static final boolean DEBUG = true;
    private static final String LOG_TAG_PHONE = "PHONE";
    private static final String TAG_PREFIX = "[IRC]";

    private static final int NO_SERVICE_DELAY_TIME = 20 * 1000;

    private static final Object mLock = new Object();
    private static SvlteIrController sInstance;
    private static RoamingMode sRoamingMode;

    private LteController mLteControllerObj;
    private CdmaController mCdmaControllerObj;
    private Object mSwitchStrategy;
    private PhoneBase mLtePhone;
    private PhoneBase mCdmaPhone;
    private LteDcPhoneProxy mLteDcPhoneProxy;

    /**
     * Interface for Strategy to operate network controller.
     *
     * @hide
     */
    interface INetworkControllerListener {
        void onRadioStateChanged(boolean isRadioOn);
        String onPreSelectPlmn(String[] plmnList);
        void onPlmnChanged(String plmn);
        void onNetworkInfoReady(List<OperatorInfo> networkInfoArray);
        void onServiceStateChanged(boolean hasService);
        boolean onRegisterNetworkManuallyDone(boolean success);
    }

    /**
     * Interface for Strategy to listen network controller information.
     *
     * @hide
     */
    interface INetworkController {
        void setRoamingMode(RoamingMode roamingMode);
        void findAvailabeNetwork();
        void registerNetworkManually(OperatorInfo oi);
        void dispose();
        void registerListener(INetworkControllerListener listener);
    }

    private static void setRoaming(boolean roaming, Message response) {
        logd("setRoaming, roaming=" + roaming);
        sRoamingMode = roaming ? RoamingMode.ROAMING_MODE_NORMAL_ROAMING
                       : RoamingMode.ROAMING_MODE_HOME;
        SvlteRatController.getInstance().setRoamingMode(roaming, response);
    }

    private static void setRoaming(RoamingMode roamingMode, Message response) {
        logd("setRoaming, roamingMode=" + roamingMode);
        sRoamingMode = roamingMode;
        SvlteRatController.getInstance().setRoamingMode(roamingMode, response);
    }

    // SvlteRatController set the roaming mode async, so we need
    // to record the latest roaming mode by ourselves
    private static RoamingMode getRoamingMode() {
        logd("getRoamingMode, sRoamingMode=" + sRoamingMode);
        return sRoamingMode;
    }

    public SvlteIrController(LteDcPhoneProxy lteDcPhoneProxy) {
        logd(" constructor, lteDcPhoneProxy=" + lteDcPhoneProxy);
        mLteDcPhoneProxy = lteDcPhoneProxy;
        mLtePhone =  (PhoneBase) lteDcPhoneProxy.getLtePhone();
        mCdmaPhone = (PhoneBase) lteDcPhoneProxy.getNLtePhone();
        mLteControllerObj = new LteController(mLtePhone);
        mCdmaControllerObj = new CdmaController(mCdmaPhone);

        if (SystemProperties.get("persist.sys.ct.ir.mode", "0").equals("0")) {
            logd(" constructor, Strategy5M");
            mSwitchStrategy = new Strategy5M(mLteControllerObj, mCdmaControllerObj);
        } else {
            logd(" constructor, Strategy4M");
            mSwitchStrategy = new Strategy4M(mLteControllerObj, mCdmaControllerObj);
        }

        sRoamingMode = RoamingMode.ROAMING_MODE_HOME;
    }

    public static SvlteIrController make(LteDcPhoneProxy lteDcPhoneProxy) {
        synchronized (mLock) {
            if (sInstance != null) {
                throw new RuntimeException("LteDcIRController.make() should only be called once");
            }
            if (SystemProperties.get("persist.sys.ct.ir.switcher", "0").equals("1")) {
                sInstance = new SvlteIrController(lteDcPhoneProxy);
                return sInstance;
            } else {
                return null;
            }
        }
    }

    private void dispose() {
        mLteControllerObj.dispose();
        mCdmaControllerObj.dispose();
    }

    /**
     * Base class of network controller.
     *
     * @hide
     */
    private abstract static class PhoneController extends Handler implements INetworkController {
        protected static final int STATE_UNKNOWN = 0;
        protected static final int STATE_INIT = 1;
        protected static final int STATE_NO_SERVICE = 2;
        protected static final int STATE_GETTING_PLMN = 3;
        protected static final int STATE_SELECTING_NETWORK = 4;
        protected static final int STATE_NETWORK_SELECTED = 5;

        protected static final int EVENT_RADIO_NO_SERVICE = 310;
        protected static final int EVENT_SERVICE_STATE_CHANGED = 311;

        protected PhoneBase mPhone;
        protected CommandsInterface mCi;

        protected boolean mHasService;

        protected int mState = STATE_UNKNOWN;
        protected int mPreState = STATE_UNKNOWN;

        protected String[] mPlmns = null;

        protected INetworkControllerListener mListener;

        protected PhoneController(PhoneBase phone) {
            super();
            mPhone = phone;
            mCi = phone.mCi;
        }

        protected void setState(int state) {
            logdForController(" setState:" + stateToString(state)
                              + " mState = " + stateToString(mState)
                              + " mPreState = " + stateToString(mPreState));
            if (mState != state) {
                mPreState = mState;
                mState = state;
            }
        }

        protected int getState() {
            logdForController(" getState:" + stateToString(mState));
            return mState;
        }


        protected String msgToString(int msgWhat) {
            return "unknown";
        }

        protected void setHasService(boolean hasService) {
            logdForController(" setHasService(" + hasService + ") mHasService = " + mHasService);

            if (!hasService) {
                setState(STATE_NO_SERVICE);
            } else if (getState() == STATE_NO_SERVICE) {
                setState(mPreState);
            }

            if (mHasService != hasService) {
                mHasService = hasService;
                if (hasService) {
                    removeNoServiceMessage();
                    if (mListener != null) {
                        // if has service, call listener immediaetlly
                        mListener.onServiceStateChanged(true);
                    }
                } else {
                    // need delay 20s to callback no service state
                    // as the service would be back soon
                    sendNoServiceMessage(NO_SERVICE_DELAY_TIME);
                }
            }
        }

        @Override
        public void handleMessage(Message msg) {
            logdForController(" handleMessage: " + msgToString(msg.what));
            switch (getState()) {
                case STATE_INIT:
                    processInitState(msg);
                    break;
                case STATE_NO_SERVICE:
                    processNoServiceState(msg);
                    break;
                case STATE_GETTING_PLMN:
                    processGettingPlmnState(msg);
                    break;
                case STATE_SELECTING_NETWORK:
                    processSelectingNWState(msg);
                    break;
                case STATE_NETWORK_SELECTED:
                    defaultMessageHandler(msg);
                    break;
                default:
                    break;
            }
        }

        protected void defaultMessageHandler(Message msg) {
            switch (msg.what) {
                case EVENT_SERVICE_STATE_CHANGED:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    ServiceState serviceState = (ServiceState) ar.result;
                    final int regState = serviceState.getRilVoiceRegState();
                    final int regDataState = serviceState.getRilDataRegState();
                    logdForController(" EVENT_SERVICE_STATE_CHANGED-VoiceState: " + regState
                                      + " DataState: " + regDataState);
                    if (regState == ServiceState.REGISTRATION_STATE_HOME_NETWORK
                        || regState == ServiceState.REGISTRATION_STATE_ROAMING
                        || regDataState == ServiceState.REGISTRATION_STATE_HOME_NETWORK
                        || regDataState == ServiceState.REGISTRATION_STATE_ROAMING) {
                        setHasService(true);
                    } else if (regState ==
                               ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_NOT_SEARCHING
                               && regDataState ==
                               ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_NOT_SEARCHING) {
                        setHasService(false);
                    }
                    break;
                default:
                    break;
            }
        }

        protected void processInitState(Message msg) {
            defaultMessageHandler(msg);
        }

        protected void processNoServiceState(Message msg) {
            switch (msg.what) {
                case EVENT_RADIO_NO_SERVICE:
                    if (mListener != null && !mHasService) {
                        mListener.onServiceStateChanged(false);
                    }
                    break;
                default:
                    defaultMessageHandler(msg);
                    break;
            }
        }

        protected void processGettingPlmnState(Message msg) {
            defaultMessageHandler(msg);
        }

        protected void processSelectingNWState(Message msg) {
            defaultMessageHandler(msg);
        }

        @Override
        public void registerListener(INetworkControllerListener listener) {
            mListener = listener;
        }

        @Override
        public void setRoamingMode(RoamingMode roamingMode) {}

        @Override
        public void dispose() {}

        @Override
        public void findAvailabeNetwork() {}

        @Override
        public void registerNetworkManually(OperatorInfo oi) {}

        protected String stateToString(int state) {
            switch (state) {
                case STATE_UNKNOWN:
                    return "STATE_UNKNOWN";
                case STATE_INIT:
                    return "STATE_INIT";
                case STATE_NO_SERVICE:
                    return "STATE_NO_SERVICE";
                case STATE_GETTING_PLMN:
                    return "STATE_GETTING_PLMN";
                case STATE_SELECTING_NETWORK:
                    return "STATE_SELECTING_NETWORK";
                case STATE_NETWORK_SELECTED:
                    return "STATE_NETWORK_SELECTED";
                default:
                    return "STATE_INVALID";
            }
        }
        /**
         * Send no service message to request switch phone after the given duration.
         *
         * @param delayedTime
         */
        protected void sendNoServiceMessage(int delayedTime) {
            if (!hasMessages(EVENT_RADIO_NO_SERVICE)) {
                sendMessageDelayed(obtainMessage(EVENT_RADIO_NO_SERVICE), delayedTime);
            }
        }

        protected void removeNoServiceMessage() {
            removeMessages(EVENT_RADIO_NO_SERVICE);
        }

        protected void logdForController(String msg) {}
    }

    /**
     * Network controller of LTE.
     *
     * @hide
     */
    private static class LteController extends PhoneController {
        private static final int EVENT_DUAL_PHONE_AVAILABLE = 101;
        private static final int EVENT_DUAL_PHONE_POWER_ON = 102;
        private static final int EVENT_RADIO_OFF_NOT_AVAILABLE = 103;
        private static final int EVENT_GSM_PLMN_CHANGED = 104;
        private static final int EVENT_GSM_SUSPENDED = 105;
        private static final int EVENT_GSM_GET_AVAILABLE_NETWORKS_COMPLETED = 140;
        private static final int EVENT_GSM_SELECT_NETWORK_MANUALLY_DONE = 141;
        private static final int EVENT_GSM_SELECT_NETWORK_MANUALLY_TIMEOUT = 142;

        private static final int GSM_SELECT_NETWORK_MANUALLY_TIMEOUT = 90 * 1000;
        private boolean mSelectNetworkManuallyDone = true;
        private boolean mSelectNetworkManuallySuccess = true;
        private int mModemResumeSessionId;

        public LteController(PhoneBase ltePhone) {
            super(ltePhone);
            registerBaseListener();
            setState(STATE_INIT);
        }

        /**
         * Dispose the the LTE controller.
         */
        @Override
        public void dispose() {
            unregisterBaseListener();
            unregisterSuspendListener();
            unregisterSpecialCasesListener();
        }

        @Override
        public void setRoamingMode(RoamingMode roamingMode) {
            RoamingMode currentRoamingMode = getRoamingMode();
            logdForController(" setRoamingMode: " + roamingMode
                              + " currentRoamingMode: " + currentRoamingMode);
            if (currentRoamingMode == roamingMode) {
                resumeNetwork();
            }
            setRoaming(roamingMode, null);
            setState(STATE_NETWORK_SELECTED);
        }

        @Override
        public void findAvailabeNetwork() {
            logdForController(" findAvailabeNetwork");
            mPhone.getAvailableNetworks(obtainMessage(EVENT_GSM_GET_AVAILABLE_NETWORKS_COMPLETED));
        }

        @Override
        public void registerNetworkManually(OperatorInfo oi) {
            logdForController(" registerNetworkManually");
            mSelectNetworkManuallyDone = false;
            sendSelectNetworkManuallyTimeoutMessage();
            mPhone.selectNetworkManually(oi, obtainMessage(EVENT_GSM_SELECT_NETWORK_MANUALLY_DONE));
        }

        private void sendSelectNetworkManuallyTimeoutMessage() {
            if (!hasMessages(EVENT_GSM_SELECT_NETWORK_MANUALLY_TIMEOUT)) {
                sendMessageDelayed(obtainMessage(EVENT_GSM_SELECT_NETWORK_MANUALLY_TIMEOUT),
                            GSM_SELECT_NETWORK_MANUALLY_TIMEOUT);
            }
        }

        @Override
        protected void defaultMessageHandler(Message msg) {
            switch (msg.what) {
                case EVENT_DUAL_PHONE_POWER_ON:
                    removeNoServiceMessage();
                    registerSpecialCasesListener();
                    if (mListener != null) {
                        mListener.onRadioStateChanged(true);
                    }
                    break;
                case EVENT_GSM_PLMN_CHANGED:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    removeNoServiceMessage();
                    if (ar.exception == null && ar.result != null) {
                        setHasService(true);
                        mPlmns = (String[]) ar.result;
                        for (int i = 0; i < mPlmns.length; i++) {
                            logdForController("EVENT_GSM_PLMN_CHANGED: i = " + i + ", mPlmns="
                                    + mPlmns[i]);
                        }
                    }
                    setState(STATE_SELECTING_NETWORK);
                    break;
                case EVENT_RADIO_OFF_NOT_AVAILABLE:
                    removeNoServiceMessage();
                    unregisterSpecialCasesListener();
                    if (!mCi.getRadioState().isAvailable()) {
                        unregisterSuspendListener();
                        setState(STATE_INIT);
                    } else {
                        setState(STATE_GETTING_PLMN);
                    }
                    if (mListener != null) {
                        mListener.onRadioStateChanged(false);
                    }
                    setHasService(false);
                    break;
                default:
                    super.defaultMessageHandler(msg);
                    break;
            }
        }

        @Override
        protected void processInitState(Message msg) {
            switch (msg.what) {
                case EVENT_DUAL_PHONE_AVAILABLE:
                    removeNoServiceMessage();
                    enableSuspend(true);
                    registerSuspendListener();
                    setState(STATE_GETTING_PLMN);
                    break;
                default:
                    super.processInitState(msg);
                    break;
            }
        }

        @Override
        protected void processSelectingNWState(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
                case EVENT_GSM_SUSPENDED:
                    if (ar.exception == null && ar.result != null) {
                        mModemResumeSessionId = ((int[]) ar.result)[0];
                        if (mListener != null) {
                            mListener.onPlmnChanged(selectedPlmn());
                        }
                    }
                    break;
                case EVENT_GSM_GET_AVAILABLE_NETWORKS_COMPLETED:
                    sendSelectNetworkManuallyTimeoutMessage();
                    if (ar.exception == null) {
                        List<OperatorInfo> networkInfoArray = (List<OperatorInfo>) ar.result;
                        if (mListener != null) {
                            mListener.onNetworkInfoReady(networkInfoArray);
                        }
                    }
                    break;
                case EVENT_GSM_SELECT_NETWORK_MANUALLY_TIMEOUT:
                    if (mListener != null) {
                        if (mListener.onRegisterNetworkManuallyDone(false)) {
                            // if failed to register network manually,
                            // set to no service state
                            mSelectNetworkManuallyDone = true;
                            setHasService(false);
                        }
                    }
                    break;
                case EVENT_GSM_SELECT_NETWORK_MANUALLY_DONE:
                    removeMessages(EVENT_GSM_SELECT_NETWORK_MANUALLY_TIMEOUT);
                    if (ar.exception != null) {
                        mSelectNetworkManuallySuccess = false;
                    } else {
                        mSelectNetworkManuallySuccess = true;
                        mSelectNetworkManuallyDone = true;
                        setState(STATE_NETWORK_SELECTED);
                    }

                    logdForController(" mSelectNetworkManuallySuccess: "
                                        + mSelectNetworkManuallySuccess);

                    if (mListener != null) {
                        if (mListener.onRegisterNetworkManuallyDone(
                                       mSelectNetworkManuallySuccess)) {
                            // if failed to register network manually,
                            // set to no service state
                            mSelectNetworkManuallyDone = true;
                            setHasService(false);
                        }
                    }
                    break;
                default:
                    super.processSelectingNWState(msg);
                    break;
            }
        }

        @Override
        protected String msgToString(int msgWhat) {
            String msg = "[LteController]-";
            switch (msgWhat) {
                case EVENT_DUAL_PHONE_AVAILABLE:
                    msg += "EVENT_DUAL_PHONE_AVAILABLE";
                    break;
                case EVENT_DUAL_PHONE_POWER_ON:
                    msg += "EVENT_DUAL_PHONE_POWER_ON";
                    break;
                case EVENT_GSM_PLMN_CHANGED:
                    msg += "EVENT_GSM_PLMN_CHANGED";
                    break;
                case EVENT_GSM_SUSPENDED:
                    msg += "EVENT_GSM_SUSPENDED";
                    break;
                case EVENT_SERVICE_STATE_CHANGED:
                    msg += "EVENT_SERVICE_STATE_CHANGED";
                    break;
                case EVENT_RADIO_OFF_NOT_AVAILABLE:
                    msg += "EVENT_RADIO_OFF_NOT_AVAILABLE";
                    break;
                case EVENT_RADIO_NO_SERVICE:
                    msg += "EVENT_RADIO_NO_SERVICE";
                    break;
                case EVENT_GSM_GET_AVAILABLE_NETWORKS_COMPLETED:
                    msg += "EVENT_GSM_GET_AVAILABLE_NETWORKS_COMPLETED";
                    break;
                case EVENT_GSM_SELECT_NETWORK_MANUALLY_DONE:
                    msg += "EVENT_GSM_SELECT_NETWORK_MANUALLY_DONE";
                    break;
                case EVENT_GSM_SELECT_NETWORK_MANUALLY_TIMEOUT:
                    msg += EVENT_GSM_SELECT_NETWORK_MANUALLY_TIMEOUT;
                    break;
                default:
                    break;
            }
            return msg;
        }
        private void resumeNetwork() {
            RoamingMode currentRoamingMode = getRoamingMode();
            logdForController(" resumeNetwork: " + " currentRoamingMode: " + currentRoamingMode);
            mPhone.mCi.setResumeRegistration(mModemResumeSessionId, null);
        }

        private void registerBaseListener() {
            logdForController(" registerBaseListener");
            mCi.registerForAvailable(this, EVENT_DUAL_PHONE_AVAILABLE, null);
            mCi.registerForOn(this, EVENT_DUAL_PHONE_POWER_ON, null);
        }

        private void unregisterBaseListener() {
            logdForController(" unregisterBaseListener");
            mCi.unregisterForAvailable(this);
            mCi.unregisterForOn(this);
        }

        private void registerSuspendListener() {
            logdForController(" registerSuspendListener");
            mCi.setOnPlmnChangeNotification(this, EVENT_GSM_PLMN_CHANGED, null);
            mCi.setOnRegistrationSuspended(this, EVENT_GSM_SUSPENDED, null);
        }

        private void unregisterSuspendListener() {
            logdForController(" unregisterSuspendListener");
            mCi.unSetOnPlmnChangeNotification(this);
            mCi.unSetOnRegistrationSuspended(this);
        }

        private void enableSuspend(boolean enable) {
            logdForController(" enableSuspend: " + enable);
            int enableVal = enable ? 1 : 0;
            mCi.setRegistrationSuspendEnabled(enableVal, null);
        }

        private void registerSpecialCasesListener() {
            logdForController(" registerSpecialCasesListener");
            mPhone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
            mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_NOT_AVAILABLE, null);
        }

        private void unregisterSpecialCasesListener() {
            logdForController(" unregisterSpecialCasesListener");
            mPhone.unregisterForServiceStateChanged(this);
            mCi.unregisterForOffOrNotAvailable(this);
        }

        private String selectedPlmn() {
            String ret = null;
            // ask Strategy to select a prefer plmn
            // otherwise, just select the first one
            if (mListener != null) {
                ret = mListener.onPreSelectPlmn(mPlmns);
            }

            if (ret == null) {
                ret = mPlmns[0];
            }
            return ret;
        }

        @Override
        protected void logdForController(String msg) {
            logd(" LteController, " + msg);
        }
    }

    /**
     * Network controller of CDMA.
     *
     * @hide
     */
    private class CdmaController extends PhoneController {
        private static final int EVENT_DUAL_PHONE_AVAILABLE = 201;
        private static final int EVENT_DUAL_PHONE_POWER_ON = 202;
        private static final int EVENT_CDMA_PLMN_CHANGED = 203;
        private static final int EVENT_RADIO_OFF_NOT_AVAILABLE = 207;

        private static final int EVENT_NO_SERVICE_DELAY = 221;
        private String[] mPlmn;

        private static final String PREF_IR_ROAMING_INFO = "mediatek_ir_roaming_info";
        private static final String PREF_IR_CDMA_NETWORK_TYPE = "com.mediatek.ir.cdma.network.type";

        public CdmaController(PhoneBase nltePhone) {
            super(nltePhone);
            logdForController(" nltePhone=" + nltePhone);
            registerBaseListener();
            setState(STATE_INIT);
        }

        private void registerBaseListener() {
            logdForController(" registerBaseListener");
            mCi.registerForAvailable(this, EVENT_DUAL_PHONE_AVAILABLE, null);
            mCi.registerForOn(this, EVENT_DUAL_PHONE_POWER_ON, null);
        }

        private void unregisterBaseListener() {
            logdForController(" unregisterBaseListener");
            mCi.unregisterForAvailable(this);
            mCi.unregisterForOn(this);
        }

        private void enablePause(boolean enabled) {
            logdForController(" enablePause: " + enabled);
            mCi.setCdmaRegistrationSuspendEnabled(enabled, null);
        }

        private void registerPlmnChangedListener() {
            logdForController(" registerPlmnChangedListener");
            mCi.registerForMccMncChange(this, EVENT_CDMA_PLMN_CHANGED, null);
        }

        private void unregisterPlmnChangedListener() {
            logdForController(" unregisterPlmnChangedListener");
            mCi.unregisterForMccMncChange(null);
        }

        private void registerSpecialCasesListener() {
            logdForController(" registerSpecialCasesListener");
            mPhone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
            mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_NOT_AVAILABLE, null);
        }

        private void unregisterSpecialCasesListener() {
            logdForController(" unregisterSpecialCasesListener");
            mPhone.unregisterForServiceStateChanged(this);
            mCi.unregisterForOffOrNotAvailable(this);
        }

        @Override
        public void dispose() {
            unregisterBaseListener();
            unregisterPlmnChangedListener();
            unregisterSpecialCasesListener();
        }

        @Override
        protected void defaultMessageHandler(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
                case EVENT_DUAL_PHONE_POWER_ON:
                    removeNoServiceMessage();
                    registerSpecialCasesListener();
                    if (mListener != null) {
                        mListener.onRadioStateChanged(true);
                    }
                    break;
                case EVENT_CDMA_PLMN_CHANGED:
                    removeNoServiceMessage();
                    if (ar.exception == null && ar.result != null) {
                        mPlmn = new String[1];
                        mPlmn[0] = (String) ar.result;
                        setHasService(true);
                        enableServiceStateNotify(false);
                        setState(STATE_SELECTING_NETWORK);
                        if (mListener != null) {
                            mListener.onPlmnChanged(mPlmn[0]);
                        }
                        enableServiceStateNotify(true);
                    }
                    break;
                case EVENT_RADIO_OFF_NOT_AVAILABLE:
                    removeNoServiceMessage();
                    unregisterSpecialCasesListener();
                    if (!mCi.getRadioState().isAvailable()) {
                        unregisterPlmnChangedListener();
                        setState(STATE_INIT);
                    } else {
                        setState(STATE_GETTING_PLMN);
                    }
                    if (mListener != null) {
                        mListener.onRadioStateChanged(false);
                    }
                    setHasService(false);
                    break;
                default:
                    super.defaultMessageHandler(msg);
                    break;
            }
        }

        @Override
        protected void processInitState(Message msg) {
            switch (msg.what) {
                case EVENT_DUAL_PHONE_AVAILABLE:
                    removeNoServiceMessage();
                    enablePause(true);
                    registerPlmnChangedListener();
                    setState(STATE_GETTING_PLMN);
                    break;
                default:
                    super.processInitState(msg);
                    break;
            }
        }

        @Override
        protected String msgToString(int msgWhat) {
            String msg = "[CdmaController]-";
            switch (msgWhat) {
                case EVENT_DUAL_PHONE_AVAILABLE:
                    msg += "EVENT_DUAL_PHONE_AVAILABLE";
                    break;
                case EVENT_DUAL_PHONE_POWER_ON:
                    msg += "EVENT_DUAL_PHONE_POWER_ON";
                    break;
                case EVENT_CDMA_PLMN_CHANGED:
                    msg += "EVENT_CDMA_PLMN_CHANGED";
                    break;
                case EVENT_SERVICE_STATE_CHANGED:
                    msg += "EVENT_SERVICE_STATE_CHANGED";
                    break;
                case EVENT_RADIO_OFF_NOT_AVAILABLE:
                    msg += "EVENT_RADIO_OFF_NOT_AVAILABLE";
                    break;
                case EVENT_RADIO_NO_SERVICE:
                    msg += "EVENT_RADIO_NO_SERVICE";
                    break;
                case EVENT_NO_SERVICE_DELAY:
                    msg += "EVENT_NO_SERVICE_DELAY";
                    break;
                default:
                    break;
            }
            return msg;
        }

        @Override
        public void setRoamingMode(RoamingMode roamingMode) {
            RoamingMode currentRoamingMode = getRoamingMode();
            logdForController(" setRoamingMode: " + roamingMode
                              + " currentRoamingMode: " + currentRoamingMode);
            if (currentRoamingMode == roamingMode) {
                resumeNetwork();
            }
            setRoaming(roamingMode, null);
            setState(STATE_NETWORK_SELECTED);
        }

        private void resumeNetwork() {
            RoamingMode currentRoamingMode = getRoamingMode();
            logdForController(" resumeNetwork: " + " currentRoamingMode: " + currentRoamingMode);
            mPhone.mCi.setResumeCdmaRegistration(null);
        }

        private void enableServiceStateNotify(boolean enable) {
            logdForController(" enableServiceStateNotify(" + enable + ")");
            CdmaServiceStateTracker csst = (CdmaServiceStateTracker) mPhone
                    .getServiceStateTracker();
            csst.enableServiceStateNotify(enable);
        }

        @Override
        protected void logdForController(String msg) {
            logd(" CdmaController, " + msg);
        }
    }

    /**
     * Network selection strategy of 5M project.
     *
     * @hide
     */
    private class Strategy5M extends Handler {
        private static final String CHINA_TELECOM_MAINLAND_MCC = "460";
        private static final String CHINA_TELECOM_MACCO_MCC = "455";
        protected INetworkController mLteController;
        protected INetworkController mCdmaController;
        protected RoamingMode mCdmaRoamingMode = RoamingMode.ROAMING_MODE_UNKNOWN;
        protected boolean mLteServiceState = false;
        protected boolean mCdmaServiceState = false;
        protected boolean mIsCdmaRadioOn = false;
        protected boolean mIsLwgRadioOn = false;
        protected static final int EVENT_NO_SEERVICE_WATCHDOG = 101;
        protected static final int NO_SEERVICE_WATCHDOG_DELAY_TIME = 90 * 1000;
        protected int mCurrentTryingIndex = -1;
        protected List<OperatorInfo> mLteNetworkInfoArray;
        protected long mNoServiceTimeStamp = 0;

        public Strategy5M(INetworkController lteController, INetworkController cdmaController) {
            super();
            mLteController = lteController;
            mCdmaController = cdmaController;
            mLteController.registerListener(mLteListener);
            mCdmaController.registerListener(mCdmaListener);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_NO_SEERVICE_WATCHDOG:
                    triggerNoServiceWatchdog();
                    break;
                default:
                    break;
            }
        }

        private RoamingMode getRoamingModeByPlmn5M(String plmn) {
            if (plmn != null) {
                // For 5m project
                if (plmn.startsWith(CHINA_TELECOM_MAINLAND_MCC)
                    || plmn.startsWith(CHINA_TELECOM_MACCO_MCC)) {
                    logdForStrategy("getRoamingModeByPlmn5M, plmn=" + plmn + " ret: " +
                                     RoamingMode.ROAMING_MODE_HOME);
                    return RoamingMode.ROAMING_MODE_HOME;
                } else {
                    logdForStrategy("getRoamingModeByPlmn5M, plmn=" + plmn + " ret: " +
                                     RoamingMode.ROAMING_MODE_NORMAL_ROAMING);
                    return RoamingMode.ROAMING_MODE_NORMAL_ROAMING;
                }
            } else {
                logdForStrategy("getRoamingModeByPlmn5M, plmn=" + plmn + " ret: " +
                                     RoamingMode.ROAMING_MODE_NORMAL_ROAMING);
                return RoamingMode.ROAMING_MODE_NORMAL_ROAMING;
            }
        }

        protected RoamingMode getRoamingModeByPlmnCdma(String plmn) {
            return getRoamingModeByPlmn5M(plmn);
        }

        protected RoamingMode getRoamingModeByPlmnLwg(String plmn) {
            return getRoamingModeByPlmn5M(plmn);
        }

        protected boolean is4GSimCard() {
            IccCardConstants.CardType cardType = UiccController.getInstance().getCardType();
            if (cardType == IccCardConstants.CardType.CT_4G_UICC_CARD) {
                return true;
            } else {
                return true;
            }
        }

        protected boolean supportRoaming() {
            boolean bSupportRoaming = is4GSimCard();
            logdForStrategy("supportRoaming: " + bSupportRoaming);
            return bSupportRoaming;
        }

        protected void onNoService() {
            logdForStrategy("onNoService mLteServiceState: " + mLteServiceState
                            + " mCdmaServiceState: " + mCdmaServiceState);
            if (!mCdmaServiceState && !mLteServiceState) {
                long curTime = System.currentTimeMillis();
                long duration = curTime - mNoServiceTimeStamp;

                // prevent no service switch happens too
                // frequently, use a time stamp
                if (mNoServiceTimeStamp == 0 ||
                    duration > NO_SEERVICE_WATCHDOG_DELAY_TIME) {
                    if (getRoamingMode() == RoamingMode.ROAMING_MODE_HOME) {
                        setRoaming(RoamingMode.ROAMING_MODE_NORMAL_ROAMING, null);
                    } else {
                        setRoaming(RoamingMode.ROAMING_MODE_HOME, null);
                    }
                    mNoServiceTimeStamp = curTime;
                    startNoServiceWatchdog();
                } else {
                    stopNoServiceWatchdog();
                    sendMessageDelayed(obtainMessage(EVENT_NO_SEERVICE_WATCHDOG),
                                                 NO_SEERVICE_WATCHDOG_DELAY_TIME - duration);
                }
            } else {
                stopNoServiceWatchdog();
            }
        }

        protected void onRadioStateChanged() {
            logdForStrategy("onRadioStateChanged mIsLwgRadioOn: " + mIsLwgRadioOn
                            + " mIsCdmaRadioOn: " + mIsCdmaRadioOn);
            if (mIsLwgRadioOn || mIsCdmaRadioOn) {
                startNoServiceWatchdog();
            } else {
                stopNoServiceWatchdog();
            }
        }

        protected void triggerNoServiceWatchdog() {
            logdForStrategy("triggerNoServiceWatchdog mLteServiceState: " + mLteServiceState
                            + " mCdmaServiceState: " + mCdmaServiceState);
            if (!mCdmaServiceState && !mLteServiceState) {
                onNoService();
                startNoServiceWatchdog();
            } else {
                stopNoServiceWatchdog();
            }
        }

        protected void startNoServiceWatchdog() {
            logdForStrategy("startNoServiceWatchdog");
            if (!hasMessages(EVENT_NO_SEERVICE_WATCHDOG)) {
                sendMessageDelayed(obtainMessage(EVENT_NO_SEERVICE_WATCHDOG),
                                                 NO_SEERVICE_WATCHDOG_DELAY_TIME);
            }
        }

        protected void stopNoServiceWatchdog() {
            logdForStrategy("stopNoServiceWatchdog");
            removeMessages(EVENT_NO_SEERVICE_WATCHDOG);
        }

        protected boolean onLteRegisterNetworkManuallyDone(boolean success) {
            logdForStrategy("onLteRegisterNetworkManuallyDone(" + success + ")");
            // if failed to register network manually
            // try to register the next network in the list got in onNetworkInfoReady
            if (!success) {
                if (!tryNextNetworkManually()) {
                    mCurrentTryingIndex = -1;
                    mLteNetworkInfoArray.clear();
                    mLteNetworkInfoArray = null;
                    // tried all networks, can't register successfully
                    // return true to go to no service state
                    logdForStrategy("onLteRegisterNetworkManuallyDone, tried all network");
                    return true;
                } else {
                    // go on trying
                    logdForStrategy("onLteRegisterNetworkManuallyDone, go on trying");
                    return false;
                }
            } else {
                mCurrentTryingIndex = -1;
                mLteNetworkInfoArray.clear();
                mLteNetworkInfoArray = null;
                // register successfully, resume network
                logdForStrategy("onLteRegisterNetworkManuallyDone, resume network");
                setRoaming(getRoamingMode(), null);
                return false;
            }

        }

        protected boolean isSameWithCdmaRatMode(OperatorInfo oi) {
            if (mCdmaRoamingMode == getRoamingModeByPlmnLwg(oi.getOperatorNumeric())
                && oi.getState() != OperatorInfo.State.FORBIDDEN) {
                return true;
            } else {
                return false;
            }
        }

        protected boolean tryNextNetworkManually() {
            final int count = mLteNetworkInfoArray.size();
            for (int i = mCurrentTryingIndex + 1; i < count; i++) {
                OperatorInfo oi = mLteNetworkInfoArray.get(i);
                if (isSameWithCdmaRatMode(oi)) {
                    logdForStrategy("[LTE]registerNetworkManually: " + oi.toString());
                    mLteController.registerNetworkManually(oi);
                    return true;
                }
            }

            return false;
        }

        private void logdForStrategy(String msg) {
            logd(" [Strategy5M], " + msg);
        }

        protected void onLwgPlmnChanged(String plmn) {
            logdForStrategy("onLwgPlmnChanged plmn: " + plmn);

            if (supportRoaming()) {
                RoamingMode targetMode = getRoamingModeByPlmnLwg(plmn);
                boolean registerManually = false;

                logdForStrategy("onLwgPlmnChanged mCdmaRoamingMode: " + mCdmaRoamingMode +
                                " targetMode" + targetMode);

                // these are OP09 IR 5M stratrgy deails
                // cdma roaming mode has higher priority
                if (mCdmaRoamingMode == RoamingMode.ROAMING_MODE_UNKNOWN) {
                    // keep initial values
                } else if (mCdmaRoamingMode == RoamingMode.ROAMING_MODE_HOME) {
                    if (targetMode == RoamingMode.ROAMING_MODE_NORMAL_ROAMING) {
                        targetMode = RoamingMode.ROAMING_MODE_HOME;
                        registerManually = true;
                    }
                } else if (mCdmaRoamingMode == RoamingMode.ROAMING_MODE_NORMAL_ROAMING) {
                    if (targetMode == RoamingMode.ROAMING_MODE_HOME) {
                        targetMode = RoamingMode.ROAMING_MODE_NORMAL_ROAMING;
                        registerManually = true;
                    }
                }

                mLteController.setRoamingMode(targetMode);

                if (registerManually) {
                    // cdma set mode as higher priority, so lwg need to find
                    // and select a similar roaming mode network of cdma manually
                    mLteController.findAvailabeNetwork();
                }
            } else {
                mLteController.setRoamingMode(RoamingMode.ROAMING_MODE_HOME);
            }
        }

        protected void onCdmaPlmnChanged(String plmn) {

            // record cdma latest roaming mode for LWG
            // to decide its roaming status
            mCdmaRoamingMode = getRoamingModeByPlmnCdma(plmn);

            if (supportRoaming()) {
                // these are OP09 IR 5M stratrgy deails
                // cdma roaming mode has higher priority
                mCdmaController.setRoamingMode(mCdmaRoamingMode);
            } else {
                mCdmaController.setRoamingMode(RoamingMode.ROAMING_MODE_HOME);
            }
        }

        private INetworkControllerListener mLteListener = new INetworkControllerListener() {
            @Override
            public void onRadioStateChanged(boolean isRadioOn) {
                if (mIsLwgRadioOn != isRadioOn) {
                    logdForStrategy("[LTE]onRadioStateChanged :" + isRadioOn);
                    mIsLwgRadioOn = isRadioOn;
                    Strategy5M.this.onRadioStateChanged();
                }
            }

            @Override
            public String onPreSelectPlmn(String[] plmnList) {
                for (int i = 0; i < plmnList.length; i++) {
                    // need to get a same mcc with cdma selected
                    // so use getRoamingModeByPlmnCdma() to get mapped roaming mode
                    if (mCdmaRoamingMode == getRoamingModeByPlmnCdma(plmnList[i])) {
                        return plmnList[i];
                    }
                }
                return plmnList[0];
            }

            @Override
            public void onPlmnChanged(String plmn) {
                logdForStrategy("[LTE]onPlmnChanged :" + plmn);
                onLwgPlmnChanged(plmn);
            }

            @Override
            public void onNetworkInfoReady(List<OperatorInfo> networkInfoArray) {
                logdForStrategy("[LTE]onNetworkInfoReady");
                mCurrentTryingIndex = -1;
                mLteNetworkInfoArray = new ArrayList<OperatorInfo>(networkInfoArray.size());
                mLteNetworkInfoArray.addAll(networkInfoArray);
                tryNextNetworkManually();
            }

            @Override
            public void onServiceStateChanged(boolean hasService) {
                logdForStrategy("[LTE]onServiceStateChanged(" + hasService + ")");
                if (hasService != mLteServiceState) {
                    mLteServiceState = hasService;
                    if (!hasService) {
                        onNoService();
                    } else {
                        stopNoServiceWatchdog();
                    }
                }
            }

            @Override
            public boolean onRegisterNetworkManuallyDone(boolean success) {
                return onLteRegisterNetworkManuallyDone(success);
            }
        };

        private INetworkControllerListener mCdmaListener = new INetworkControllerListener() {

            @Override
            public void onRadioStateChanged(boolean isRadioOn) {
                if (mIsCdmaRadioOn != isRadioOn) {
                    logdForStrategy("[CDMA]onRadioStateChanged :" + isRadioOn);
                    mIsCdmaRadioOn = isRadioOn;
                    Strategy5M.this.onRadioStateChanged();
                }
            }

            @Override
            public String onPreSelectPlmn(String[] plmnList) {
                return plmnList[0];
            }

            @Override
            public void onPlmnChanged(String plmn) {
                logdForStrategy("[CDMA]onPlmnChanged :" + plmn);
                onCdmaPlmnChanged(plmn);
            }

            @Override
            public void onNetworkInfoReady(List<OperatorInfo> networkInfoArray) {
                logdForStrategy("[CDMA]onNetworkInfoReady");
            }

            @Override
            public void onServiceStateChanged(boolean hasService) {
                logdForStrategy("[CDMA]onServiceStateChanged(" + hasService + ")");
                if (hasService != mCdmaServiceState) {
                    mCdmaServiceState = hasService;
                    if (!hasService) {
                        onNoService();
                        // reset cdma roaming mode as it's in no service state
                        mCdmaRoamingMode = RoamingMode.ROAMING_MODE_UNKNOWN;
                    } else {
                        stopNoServiceWatchdog();
                    }
                }
            }

            @Override
            public boolean onRegisterNetworkManuallyDone(boolean success) {
                return true;
            }
        };
    }

    private static void logd(String msg) {
        Log.d(LOG_TAG_PHONE, TAG_PREFIX + msg);
    }

    /**
     * Network selection strategy of 4M project.
     *
     * @hide
     */
    private class Strategy4M extends Strategy5M {
        private static final String JAP_MCC = "440";
        private static final String KOR_MCC = "450";

        public Strategy4M(INetworkController lteController, INetworkController cdmaController) {
            super(lteController, cdmaController);
        }

        @Override
        protected RoamingMode getRoamingModeByPlmnCdma(String plmn) {
            if (plmn != null) {
                if (plmn.startsWith(JAP_MCC) || plmn.startsWith(KOR_MCC)) {
                    logdForStrategy("getRoamingModeByPlmnCdma, plmn=" + plmn + " ret: " +
                                     RoamingMode.ROAMING_MODE_JPKR_CDMA);
                    return RoamingMode.ROAMING_MODE_JPKR_CDMA;
                }
            }
            return super.getRoamingModeByPlmnCdma(plmn);
        }

        @Override
        protected void onLwgPlmnChanged(String plmn) {
            logdForStrategy("onLwgPlmnChanged plmn: " + plmn);
            if (supportRoaming()) {
                RoamingMode targetMode = getRoamingModeByPlmnLwg(plmn);
                logdForStrategy("onLwgPlmnChanged mCdmaRoamingMode: " + mCdmaRoamingMode +
                                " targetMode" + targetMode);
                if (mCdmaRoamingMode == RoamingMode.ROAMING_MODE_JPKR_CDMA) {
                    // LWG doesn't override ROAMING_MODE_JPKR_CDMA
                    mLteController.setRoamingMode(mCdmaRoamingMode);

                    // cdma set mode as higher priority, so lwg need to find
                    // and select a similar roaming mode network of cdma manually
                    mLteController.findAvailabeNetwork();
                    return;
                }
            }

            // Strategy4M handle ROAMING_MODE_JPKR_CDMA related logic,
            // other mode related cases, back to super to handle
            super.onLwgPlmnChanged(plmn);
        }

        private void logdForStrategy(String msg) {
            logd(" [Strategy4M], " + msg);
        }
    }
}
