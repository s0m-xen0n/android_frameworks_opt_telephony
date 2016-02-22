
package com.mediatek.internal.telephony.ltedc.svlte.apirat;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.util.Pair;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteIratUtils;

/**
 * Multiple mode controller, control the switch between LTE and CDMA.
 * @hide
 */
public class MultiModeController extends StateMachine {
    private static final String LOG_TAG = "[IRAT_MMC]";
    private static final String STATE_MACHINE_NAME = "MMM";

    private static final int DISABLED = 0;
    private static final int ENABLED = 1;

    private static final String PROP_NAME_TEST_SET_LTE_SIGNAL = "mtk.test.lte.signal";
    private static final String PROP_NAME_TEST_SET_CDMA_SIGNAL = "mtk.test.cdma.signal";
    private static final String PROP_NAME_TEST_SET_LTE_REG_STATE = "mtk.test.lte.regstate";
    private static final String PROP_NAME_TEST_SET_CDMA_REG_STATE = "mtk.test.cdma.regstate";

    private static final int DEFAULT_BOOT_WAIT_LTE_TIME = 20 * 1000;
    private static final String PROP_NAME_BOOT_WAIT_LTE_TIME = "persist.boot.wait.lte.time";

    private static final int DEFAULT_SWITCH_DIRECTLY_TIME = 5 * 1000;
    private static final String PROP_NAME_SWITCH_DIRECTLY_TIME =
            "persist.switch.directly.time";

    private static final int DEFAULT_SIGNAL_DETECT_COUNT_DOWN = 5;
    private static final String PROP_NAME_SIGNAL_DETECT_COUNT_DOWN =
            "persist.signal.detect.countdown";

    private static final int DEFAULT_WAIT_SIGNAL_STABLE_TIME = 20 * 1000;
    private static final String PROP_NAME_WAIT_SIGNAL_STABLE_TIME =
            "persist.wait.signal.stable.time";

    private static final int DEFAULT_THRESHOLD_LTE_GOOD_ENOUGH = -114;
    private static final String PROP_NAME_THRESHOLD_LTE_GOOD_ENOUGH = "persist.threshold.lte.good";

    private static final int DEFAULT_THRESHOLD_LTE_WEAK_ENOUGH = -118;
    private static final String PROP_NAME_THRESHOLD_LTE_WEAK_ENOUGH = "persist.threshold.lte.weak";

    private static final int DEFAULT_THRESHOLD_CDMA_GOOD_ENOUGH = -150;
    private static final String PROP_NAME_THRESHOLD_CDMA_GOOD_ENOUGH =
            "persist.threshold.cdma.good";

    private static final int EVENT_MMC_BASE = 2000;
    private static final int EVENT_BOOT_WAIT_LTE_TIMER_TIME_OUT = EVENT_MMC_BASE + 0;
    private static final int EVENT_CDMA_PS_STATE_CHANGED = EVENT_MMC_BASE + 1;
    private static final int EVENT_LTE_PS_STATE_CHANGED = EVENT_MMC_BASE + 2;
    private static final int EVENT_CDMA_SIGNAL_CHANGED = EVENT_MMC_BASE + 3;
    private static final int EVENT_LTE_SIGNAL_CHANGED = EVENT_MMC_BASE + 4;
    private static final int EVENT_NO_SERVICE_SWITCH_TIMER_TIME_OUT = EVENT_MMC_BASE + 5;
    private static final int EVENT_MMC_ENABLE_STATE_CHANGED = EVENT_MMC_BASE + 6;
    private static final int EVENT_SWITCH_PS_SERVICE = EVENT_MMC_BASE + 7;
    private static final int EVENT_WAIT_SIGNAL_STABLE_TIME_OUT = EVENT_MMC_BASE + 8;
    private static final int CMD_TO_STRING_COUNT = EVENT_WAIT_SIGNAL_STABLE_TIME_OUT
            - EVENT_MMC_BASE + 1;

    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[EVENT_BOOT_WAIT_LTE_TIMER_TIME_OUT - EVENT_MMC_BASE] =
                "EVENT_BOOT_WAIT_LTE_TIMER_TIME_OUT";
        sCmdToString[EVENT_CDMA_PS_STATE_CHANGED - EVENT_MMC_BASE] = "EVENT_CDMA_PS_STATE_CHANGED";
        sCmdToString[EVENT_LTE_PS_STATE_CHANGED - EVENT_MMC_BASE] = "EVENT_LTE_PS_STATE_CHANGED";
        sCmdToString[EVENT_CDMA_SIGNAL_CHANGED - EVENT_MMC_BASE] = "EVENT_CDMA_SIGNAL_CHANGED";
        sCmdToString[EVENT_LTE_SIGNAL_CHANGED - EVENT_MMC_BASE] = "EVENT_LTE_SIGNAL_CHANGED";
        sCmdToString[EVENT_NO_SERVICE_SWITCH_TIMER_TIME_OUT - EVENT_MMC_BASE] =
                "EVENT_NO_SERVICE_SWITCH_TIMER_TIME_OUT";
        sCmdToString[EVENT_MMC_ENABLE_STATE_CHANGED - EVENT_MMC_BASE] =
                "EVENT_MMC_ENABLE_STATE_CHANGED";
        sCmdToString[EVENT_SWITCH_PS_SERVICE - EVENT_MMC_BASE] =
                "EVENT_SWITCH_PS_SERVICE";
        sCmdToString[EVENT_WAIT_SIGNAL_STABLE_TIME_OUT - EVENT_MMC_BASE] =
                "EVENT_WAIT_SIGNAL_STABLE_TIME_OUT";
    }

    private static final int PS_REG_STATE_UNKOWN = -1;
    private static final int PS_REG_STATE_NOT_REGISTERRED = 0;
    private static final int PS_REG_STATE_REGISTERRED = 1;

    // LTE register state, get from +CEREG
    private int mCdmaRegState = PS_REG_STATE_UNKOWN;
    private int mLteRegState = PS_REG_STATE_UNKOWN;

    private int mPsServiceType = SvlteIratUtils.PS_SERVICE_UNKNOWN;

    private SignalStrength mCdmaSignalStrength;
    private SignalStrength mLteSignalStrength;

    private int mLteSignalGoodThreshold;
    private int mLteSignalWeakThreshold;
    private int mCdmaSignalGoodThreshold;

    private Context mContext;
    private LteDcPhoneProxy mLteDcPhoneProxy;
    private PhoneBase mLtePhone;
    private PhoneBase mCdmaPhone;
    private ApIratController mIratController;

    // Timers and count down value.
    private int mBootWaitLteTime;
    private int mDirecltySwitchWaitTime;
    private int mWaitSignalStableTime;
    private int mSignalDetectCountDown;
    private int mCurCountDown;

    // Whether to enable MMC function.
    private boolean mEnabled;

    /**
     * Construct MultiModeController.
     * @param lteDcPhoneProxy SvltePhoneProxy.
     * @param enabled Initial enable state.
     * @param controller ApIratController.
     * @param handler Handler for state machine.
     */
    public MultiModeController(LteDcPhoneProxy lteDcPhoneProxy,
            boolean enabled, ApIratController controller, Handler handler) {
        super(STATE_MACHINE_NAME, handler);
        mLteDcPhoneProxy = lteDcPhoneProxy;
        mLtePhone = lteDcPhoneProxy.getLtePhone();
        mCdmaPhone = lteDcPhoneProxy.getNLtePhone();
        mContext = mLteDcPhoneProxy.getContext();
        mIratController = controller;
        mEnabled = enabled;

        initParams();
        addState(mDefaultState);
        addState(mInactiveState, mDefaultState);
        addState(mLteNormalServiceState, mDefaultState);
        addState(mLteWeakSignalState, mDefaultState);
        addState(mLteNoServiceState, mDefaultState);
        addState(mCdmaNoServiceState, mDefaultState);
        addState(mCToLMpsrState, mDefaultState);
        addState(mCdmaNormalServiceState, mDefaultState);
        addState(mDisabledState, mDefaultState);
        if (mEnabled) {
            setInitialState(mInactiveState);
            registerForSignalStrengthUpdate();
        } else {
            setInitialState(mDisabledState);
        }
        registerForPsNetworkStateChanged();
    }

    /**
     * Dispose the component.
     */
    public void dispose() {
        log("dispose...");
        unregisterForPsNetworkStateChanged();
        unregisterForSignalStrengthUpdate();
    }

    private void registerForSignalStrengthUpdate() {
        log("registerForSignalStrengthUpdate...");
        mLtePhone.getServiceStateTracker().registerForSignalStrengthChanged(getHandler(),
                EVENT_LTE_SIGNAL_CHANGED, null);
        mCdmaPhone.getServiceStateTracker().registerForSignalStrengthChanged(getHandler(),
                EVENT_CDMA_SIGNAL_CHANGED, null);
    }

    private void unregisterForSignalStrengthUpdate() {
        log("unregisterForSignalStrengthUpdate...");
        mLtePhone.getServiceStateTracker().unregisterForSignalStrengthChanged(getHandler());
        mCdmaPhone.getServiceStateTracker().unregisterForSignalStrengthChanged(getHandler());
    }

    private void registerForPsNetworkStateChanged() {
        log("registerForPsNetworkStateChanged...");
        registerForDrsRatChanged(mLtePhone, EVENT_LTE_PS_STATE_CHANGED);
        registerForDrsRatChanged(mCdmaPhone, EVENT_CDMA_PS_STATE_CHANGED);
    }

    private void unregisterForPsNetworkStateChanged() {
        log("unregisterForPsNetworkStateChanged...");
        unregisterForDrsRatChanged(mLtePhone);
        unregisterForDrsRatChanged(mCdmaPhone);
    }

    private void initParams() {
        mBootWaitLteTime = SystemProperties.getInt(
                PROP_NAME_BOOT_WAIT_LTE_TIME, DEFAULT_BOOT_WAIT_LTE_TIME);
        mDirecltySwitchWaitTime = SystemProperties.getInt(
                PROP_NAME_SWITCH_DIRECTLY_TIME, DEFAULT_SWITCH_DIRECTLY_TIME);
        mSignalDetectCountDown = SystemProperties.getInt(
                PROP_NAME_SIGNAL_DETECT_COUNT_DOWN,
                DEFAULT_SIGNAL_DETECT_COUNT_DOWN);
        mWaitSignalStableTime  = SystemProperties.getInt(
                PROP_NAME_WAIT_SIGNAL_STABLE_TIME,
                DEFAULT_WAIT_SIGNAL_STABLE_TIME);

        mLteSignalGoodThreshold = SystemProperties.getInt(
                PROP_NAME_THRESHOLD_LTE_GOOD_ENOUGH,
                DEFAULT_THRESHOLD_LTE_GOOD_ENOUGH);
        mLteSignalWeakThreshold = SystemProperties.getInt(
                PROP_NAME_THRESHOLD_LTE_WEAK_ENOUGH,
                DEFAULT_THRESHOLD_LTE_WEAK_ENOUGH);
        mCdmaSignalGoodThreshold = SystemProperties.getInt(
                PROP_NAME_THRESHOLD_CDMA_GOOD_ENOUGH,
                DEFAULT_THRESHOLD_CDMA_GOOD_ENOUGH);
        log("initParams: mBootWaitLteTime = " + mBootWaitLteTime
                + ", mSignalDetectCountDown = " + mSignalDetectCountDown
                + ",mLteSignalWeakThreshold = " + mLteSignalWeakThreshold
                + ", mLteSignalWeakThreshold = " + mLteSignalWeakThreshold
                + ", mCdmaSignalGoodThreshold = " + mCdmaSignalGoodThreshold
                + ", mWaitSignalStableTime = " + mWaitSignalStableTime);
    }

    /**
     * Enable/Disable multiple mode controller, reset status when function
     * disabled. Handle RAT or radio power change cases such as 4G switch/SIM
     * power off/flight mode.
     * @param enable Whether to enable MMC.
     */
    public void setEnabled(boolean enable) {
        log("setEnabled: enable = " + enable + ", mEnabled = " + mEnabled);

        // Ignore the call if no change.
        if (mEnabled == enable) {
            return;
        }
        mEnabled = enable;
        Message msg = getHandler().obtainMessage(EVENT_MMC_ENABLE_STATE_CHANGED);
        msg.arg1 = (enable ? ENABLED : DISABLED);
        sendMessage(msg);
    }

    /**
     * Default state, parent of all active states.
     */
    private class DefaultState extends State {
        @Override
        public void enter() {
            log("DefaultState: enter");
        }

        @Override
        public void exit() {
            log("DefaultState: exit");
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[Default] processMessage: msg = " + getWhatToString(msg.what));
            boolean retVal;
            switch (msg.what) {
                case EVENT_LTE_PS_STATE_CHANGED:
                    updateLteRegState((AsyncResult) msg.obj);
                    log("[Default] EVENT_LTE_PS_STATE_CHANGED: mLteRegState = "
                            + mLteRegState);
                    retVal = HANDLED;
                    break;
                case EVENT_CDMA_PS_STATE_CHANGED:
                    updateCdmaRegState((AsyncResult) msg.obj);
                    log("[Default] EVENT_CDMA_PS_STATE_CHANGED: mCdmaRegState = "
                            + mCdmaRegState);
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_SIGNAL_CHANGED:
                    updateLteSignalStrength((AsyncResult) msg.obj);
                    retVal = HANDLED;
                    break;
                case EVENT_CDMA_SIGNAL_CHANGED:
                    updateCdmaSignalStrength((AsyncResult) msg.obj);
                    retVal = HANDLED;
                    break;
                case EVENT_MMC_ENABLE_STATE_CHANGED:
                    if (msg.arg1 == ENABLED) {
                        log("[Default] MMC in enable state: state = "
                                + getCurrentState());
                    } else if (msg.arg1 == DISABLED) {
                        log("[Default] MMC transfer to diable state: state = "
                                + getCurrentState());
                        transitionTo(mDisabledState);
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_SWITCH_PS_SERVICE:
                    onSwitchPsService(msg.arg1);
                    retVal = HANDLED;
                    break;
                case EVENT_WAIT_SIGNAL_STABLE_TIME_OUT:
                    log("[Default] EVENT_WAIT_SIGNAL_STABLE_TIME_OUT: state = "
                            + getCurrentState());
                    retVal = HANDLED;
                    break;
                default:
                    log("DefaultState: shouldn't happen but ignore msg = "
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private DefaultState mDefaultState = new DefaultState();

    /**
     * Inactive state, represent no network is available state.
     */
    private class InactiveState extends State {

        @Override
        public void enter() {
            log("InactiveState: enter");
        }

        @Override
        public void exit() {
            log("InactiveState: exit");
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[Inactive] processMessage: msg = " + cmdToString(msg.what));
            boolean retVal;
            switch (msg.what) {
                case EVENT_CDMA_PS_STATE_CHANGED:
                    int cdmaRegState = getCdmaRegState((AsyncResult) msg.obj);
                    log("[Inactive] EVENT_CDMA_PS_STATE_CHANGED: regState = "
                            + cdmaRegState + ", mCdmaRegState = "
                            + mCdmaRegState);
                    if (cdmaRegState != mCdmaRegState) {
                        mCdmaRegState = cdmaRegState;
                        if (cdmaRegState == PS_REG_STATE_REGISTERRED) {
                            sendMessageToWaitLteRegResult(mBootWaitLteTime);
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_PS_STATE_CHANGED:
                    int lteRegState = getLteRegState((AsyncResult) msg.obj);
                    log("[Inactive] : lteRegState = " + lteRegState
                            + ", mLteRegState = " + mLteRegState);
                    if (lteRegState != mLteRegState) {
                        mLteRegState = lteRegState;
                        if (lteRegState == PS_REG_STATE_REGISTERRED) {
                            transitionTo(mLteNormalServiceState);
                        } else {
                            if (mCdmaRegState == PS_REG_STATE_REGISTERRED) {
                                // Trigger a wait timeout message to trigger PS
                                // on CDMA.
                                removeWaitLteRegResultMessage();
                                sendMessageToWaitLteRegResult(0);
                            }
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_BOOT_WAIT_LTE_TIMER_TIME_OUT:
                    log("[Inactive] wait but no LTE reg result: mLteRegState = "
                            + mLteRegState
                            + ", mCdmaRegState = "
                            + mCdmaRegState);
                    transitionTo(mCdmaNormalServiceState);
                    retVal = HANDLED;
                    break;
                default:
                    log("[Inactive] not handled msg.what="
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private InactiveState mInactiveState = new InactiveState();

    /**
     * PS on LTE state, represent LTE is serving for PS.
     */
    private class LteNormalServiceState extends State {

        @Override
        public void enter() {
            log("LteNormalServiceState: enter");
            // Notify AP IRAT controller PS change to LTE
            trySwitchPsService(SvlteIratUtils.PS_SERVICE_ON_LTE);
            resetCountDown();
        }

        @Override
        public void exit() {
            log("LteNormalServiceState: exit");
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[PsOnLte] processMessage: msg = " + cmdToString(msg.what));
            boolean retVal;
            switch (msg.what) {
                case EVENT_LTE_PS_STATE_CHANGED:
                    int lteRegState = getLteRegState((AsyncResult) msg.obj);
                    log("[PsOnLte] : lteRegState = " + lteRegState
                            + ", mLteRegState = " + mLteRegState);
                    if (lteRegState != mLteRegState) {
                        mLteRegState = lteRegState;
                        if (mLteRegState != PS_REG_STATE_REGISTERRED) {
                            log("[PsOnLte] : LTE lost connection.");
                            transitionTo(mLteNoServiceState);
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_SIGNAL_CHANGED:
                    updateLteSignalStrength((AsyncResult) msg.obj);
                    if (isLteSignalWeakEnough()) {
                        log("[PsOnLte] Signal weak, transition to weak state.");
                        transitionTo(mLteWeakSignalState);
                    }
                    retVal = HANDLED;
                    break;
                default:
                    log("[PsOnLte] not handled msg.what="
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private LteNormalServiceState mLteNormalServiceState = new LteNormalServiceState();

    /**
     * PS on CDMA state, represent CDMA is serving for PS.
     */
    private class CdmaNormalServiceState extends State {
        @Override
        public void enter() {
            log("CdmaNormalServiceState: enter");
            // Notify AP IRAT controller PS change to CDMA
            trySwitchPsService(SvlteIratUtils.PS_SERVICE_ON_CDMA);
        }

        @Override
        public void exit() {
            log("CdmaNormalServiceState: exit");
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[CdmaNormalService] processMessage: msg = "
                    + cmdToString(msg.what));
            boolean retVal;
            switch (msg.what) {
                case EVENT_CDMA_PS_STATE_CHANGED:
                    int cdmaRegState = getCdmaRegState((AsyncResult) msg.obj);
                    log("[CdmaNormalService] : cdmaRegState = " + cdmaRegState
                            + ", mCdmaRegState = " + mCdmaRegState);
                    if (cdmaRegState != mCdmaRegState) {
                        mCdmaRegState = cdmaRegState;
                        if (mCdmaRegState != PS_REG_STATE_REGISTERRED) {
                            log("[CdmaNormalService] : CDMA lost connection.");
                            transitionTo(mCdmaNoServiceState);
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_PS_STATE_CHANGED:
                    updateLteRegState((AsyncResult) msg.obj);
                    if (mLteRegState == PS_REG_STATE_REGISTERRED) {
                        log("[CdmaNormalService] : MPSR case happens.");
                        transitionTo(mCToLMpsrState);
                    }
                    retVal = HANDLED;
                    break;
                default:
                    log("[CdmaNormalService] not handled msg.what="
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private CdmaNormalServiceState mCdmaNormalServiceState = new CdmaNormalServiceState();

    /**
     * LTE no service, try to switch to LTE or C2K or LTE weak signal state.
     */
    private class LteNoServiceState extends State {
        @Override
        public void enter() {
            log("LteNoServiceState: enter");
            sendMessageToWaitNoServiceSwitch();
        }

        @Override
        public void exit() {
            log("LteNoServiceState: exit");
            removeNoServiceSwitchMessage();
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[LteNoService] processMessage: msg = " + cmdToString(msg.what));
            boolean retVal;
            switch (msg.what) {
                case EVENT_LTE_PS_STATE_CHANGED:
                    updateLteRegState((AsyncResult) msg.obj);
                    if (mLteRegState == PS_REG_STATE_REGISTERRED) {
                        log("[LteNoService] : LTE connection recovered.");
                        removeNoServiceSwitchMessage();
                        // Switch to normal service first, then measure the
                        // signal to transition to weak signal state if the
                        // signal is weak.
                        transitionTo(mLteNormalServiceState);
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_NO_SERVICE_SWITCH_TIMER_TIME_OUT:
                    log("[LteNoService] Switch guard timer timeout: mCdmaRegState = "
                            + mCdmaRegState);
                    if (mCdmaRegState == PS_REG_STATE_REGISTERRED) {
                        transitionTo(mCdmaNormalServiceState);
                    } else {
                        sendMessageToWaitNoServiceSwitch();
                    }
                    retVal = HANDLED;
                    break;
                default:
                    log("[LteNoService]  not handled msg.what="
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private LteNoServiceState mLteNoServiceState = new LteNoServiceState();

    /**
     * CDMA no service, try to switch to LTE or C2K or C2K weak signal state.
     */
    private class CdmaNoServiceState extends State {
        @Override
        public void enter() {
            log("CdmaNoServiceState: enter");
            sendMessageToWaitNoServiceSwitch();
        }

        @Override
        public void exit() {
            log("CdmaNoServiceState: exit");
            removeNoServiceSwitchMessage();
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[CdmaNoService] processMessage: msg = "
                    + cmdToString(msg.what));
            boolean retVal;
            switch (msg.what) {
                case EVENT_CDMA_PS_STATE_CHANGED:
                    updateCdmaRegState((AsyncResult) msg.obj);
                    if (mCdmaRegState == PS_REG_STATE_REGISTERRED) {
                        log("[CdmaNoService] CDMA connection recoveried.");
                        removeNoServiceSwitchMessage();
                        // Switch to normal service first, then measure the
                        // signal to transition to weak signal state if the
                        // signal is weak.
                        transitionTo(mCdmaNormalServiceState);
                    }
                    retVal = HANDLED;
                    break;
                // TODO: Need to handle LTE service state change?
                case EVENT_NO_SERVICE_SWITCH_TIMER_TIME_OUT:
                    log("[CdmaNoService] LTE lost connection and not recoveried but CDMA has signal.");
                    if (mLteRegState == PS_REG_STATE_REGISTERRED) {
                        transitionTo(mLteNormalServiceState);
                    }
                    retVal = HANDLED;
                    break;
                default:
                    log("[CdmaNoService]  not handled msg.what="
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private CdmaNoServiceState mCdmaNoServiceState = new CdmaNoServiceState();

    /**
     * PS is on LTE, but the signal is weak.
     */
    private class LteWeakSignalState extends State {
        private boolean mPreviousWeakSignal;

        @Override
        public void enter() {
            log("LteWeakSignalState: enter");
            startCountDown();
        }

        @Override
        public void exit() {
            log("LteWeakSignalState: exit");
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[LteSignalWeak] processMessage: msg = "
                    + cmdToString(msg.what));
            boolean retVal;
            switch (msg.what) {
                case EVENT_LTE_PS_STATE_CHANGED:
                    int lteRegState = getCdmaRegState((AsyncResult) msg.obj);
                    log("[LteSignalWeak] : lteRegState = " + lteRegState
                            + ", mLteRegState = " + mLteRegState);
                    if (lteRegState != mLteRegState) {
                        mLteRegState = lteRegState;
                        if (mLteRegState != PS_REG_STATE_REGISTERRED) {
                            log("[LteSignalWeak] : CDMA lost connection.");
                            transitionTo(mLteNoServiceState);
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_SIGNAL_CHANGED:
                    updateLteSignalStrength((AsyncResult) msg.obj);
                    if (isLteSignalWeakEnough()) {
                        if (!mPreviousWeakSignal) {
                            resetCountDown();
                            mPreviousWeakSignal = true;
                        }
                        log("[LteWeakSignal] Count down = "
                                + getCountDownValue()
                                + ",mPreviousWeakSignal = "
                                + mPreviousWeakSignal);
                        if (countDown()) {
                            if (mCdmaRegState == PS_REG_STATE_REGISTERRED) {
                                updateCdmaSignalStrength((AsyncResult) msg.obj);
                                if (isCdmaSignalGoodEnough()) {
                                    log("[LteWeakSignal] CDMA signal good enough, swith to C2K.");
                                    transitionTo(mCToLMpsrState);
                                } else {
                                    resetCountDown();
                                }
                            } else {
                                resetCountDown();
                            }
                            // Do not switch to CDMA if CDMA is no service or
                            // the signal is also very weak.
                        }
                    } else {
                        if (mPreviousWeakSignal) {
                            resetCountDown();
                            mPreviousWeakSignal = false;
                        }
                        log("[LteWeakSignal] Signal becomes good, count down = "
                                + getCountDownValue()
                                + ",mPreviousWeakSignal = "
                                + mPreviousWeakSignal);

                        if (countDown()) {
                            transitionTo(mLteNormalServiceState);
                        }
                    }
                    retVal = HANDLED;
                    break;
                default:
                    log("[LteSignalWeak] not handled msg = "
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private LteWeakSignalState mLteWeakSignalState = new LteWeakSignalState();

    /**
     * PS on CDMA, and LTE is registered, (both in service) measure the signal
     * to decide whether to switch to LTE or back to CDMA, implement for MPSR
     * cases.
     */
    private class CToLMpsrState extends State {
        @Override
        public void enter() {
            log("CToLMpsrState: enter");
            trySwitchPsService(SvlteIratUtils.PS_SERVICE_ON_CDMA);
            startCountDown();
            sendMessageToWaitSignalStable(mWaitSignalStableTime);
        }

        @Override
        public void exit() {
            log("CToLMpsrState: exit");
            removeWaitSignalStableMessage();
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[MPSR] processMessage: msg = " + cmdToString(msg.what));
            boolean retVal;
            AsyncResult ar;
            switch (msg.what) {
                case EVENT_LTE_PS_STATE_CHANGED:
                    int lteRegState = getLteRegState((AsyncResult) msg.obj);
                    log("[MPSR] EVENT_LTE_PS_STATE_CHANGED lteRegState = " + lteRegState
                            + ", mLteRegState = " + mLteRegState);
                    if (lteRegState != mLteRegState) {
                        mLteRegState = lteRegState;
                        if (mLteRegState != PS_REG_STATE_REGISTERRED) {
                            log("[MPSR] LTE lost connection.");
                            transitionTo(mCdmaNormalServiceState);
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_CDMA_PS_STATE_CHANGED:
                    int cdmaRegState = getCdmaRegState((AsyncResult) msg.obj);
                    log("[MPSR] EVENT_CDMA_PS_STATE_CHANGED cdmaRegState = " + cdmaRegState
                            + ", mCdmaRegState = " + mCdmaRegState);
                    if (cdmaRegState != mCdmaRegState) {
                        mCdmaRegState = cdmaRegState;
                        if (mCdmaRegState != PS_REG_STATE_REGISTERRED) {
                            log("[MPSR] CDMA lost connection: mLteRegState = "
                                    + mLteRegState);
                            if (mLteRegState == PS_REG_STATE_REGISTERRED) {
                                transitionTo(mLteNormalServiceState);
                            } else {
                                transitionTo(mCdmaNoServiceState);
                            }
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_SIGNAL_CHANGED:
                    ar = (AsyncResult) msg.obj;
                    updateLteSignalStrength(ar);
                    if (isLteSignalGoodEnough()) {
                        log("[MPSR] : LTE signal is good: count down = " + getCountDownValue());
                        if (countDown()) {
                            transitionTo(mLteNormalServiceState);
                        }
                    } else {
                        log("[MPSR] : LTE signal is not good, waiting for signal.");
                        resetCountDown();
                        removeWaitSignalStableMessage();
                        sendMessageToWaitSignalStable(mWaitSignalStableTime);
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_WAIT_SIGNAL_STABLE_TIME_OUT:
                    transitionTo(mLteNormalServiceState);
                    retVal = HANDLED;
                    break;
                default:
                    log("[MPSR]  not handled msg.what="
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private CToLMpsrState mCToLMpsrState = new CToLMpsrState();

    /**
     * Disabled state.
     */
    private class DisabledState extends State {
        @Override
        public void enter() {
            log("DisabledState: enter");
            unregisterForSignalStrengthUpdate();
            resetStates();
        }

        @Override
        public void exit() {
            log("DisabledState: exit");
            registerForSignalStrengthUpdate();
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            switch (msg.what) {
                case EVENT_MMC_ENABLE_STATE_CHANGED:
                    log("[Disabled] processMessage: msg = "
                            + cmdToString(msg.what));
                    if (msg.arg1 == ENABLED) {
                        log("[Disabled] Enable MMC.");
                        onMmcEnabled();
                    } else if (msg.arg1 == DISABLED) {
                        log("[Disabled] MMC in disabled state, do nothing.");
                    }
                    retVal = HANDLED;
                    break;
                default:
                    log("[Disabled] not handled msg.what="
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private DisabledState mDisabledState = new DisabledState();

    private void onMmcEnabled() {
        log("onMmcEnabled: mLteRegState = " + mLteRegState
                + ", mCdmaRegState = " + mCdmaRegState);
        if (mLteRegState == PS_REG_STATE_REGISTERRED) {
            transitionTo(mLteNormalServiceState);
        } else if (mCdmaRegState == PS_REG_STATE_REGISTERRED) {
            transitionTo(mCdmaNormalServiceState);
        } else {
            transitionTo(mInactiveState);
        }
    }

    private void resetStates() {
        log("resetStates: mLteRegState = " + mLteRegState
                + ", mCdmaRegState = " + mCdmaRegState);
        mPsServiceType = SvlteIratUtils.PS_SERVICE_UNKNOWN;
        mLteSignalStrength = null;
        mCdmaSignalStrength = null;
        resetCountDown();
    }

    private void sendMessageToWaitLteRegResult(int waitTime) {
        getHandler().sendEmptyMessageDelayed(
                EVENT_BOOT_WAIT_LTE_TIMER_TIME_OUT, waitTime);
    }

    private void removeWaitLteRegResultMessage() {
        getHandler().removeMessages(EVENT_BOOT_WAIT_LTE_TIMER_TIME_OUT);
    }

    private void sendMessageToWaitNoServiceSwitch() {
        getHandler()
                .sendEmptyMessageDelayed(
                        EVENT_NO_SERVICE_SWITCH_TIMER_TIME_OUT,
                        mDirecltySwitchWaitTime);
    }

    private void removeNoServiceSwitchMessage() {
        getHandler().removeMessages(EVENT_NO_SERVICE_SWITCH_TIMER_TIME_OUT);
    }

    private void sendMessageToWaitSignalStable(int waitTime) {
        getHandler().sendEmptyMessageDelayed(EVENT_WAIT_SIGNAL_STABLE_TIME_OUT,
                waitTime);
    }

    private void removeWaitSignalStableMessage() {
        getHandler().removeMessages(EVENT_WAIT_SIGNAL_STABLE_TIME_OUT);
    }

    private void startCountDown() {
        resetCountDown();
    }

    private synchronized void resetCountDown() {
        mCurCountDown = mSignalDetectCountDown;
    }

    private synchronized boolean countDown() {
        mCurCountDown--;
        log("countDown: mCurCountDown = " + mCurCountDown);
        return (mCurCountDown == 0);
    }

    private synchronized int getCountDownValue() {
        return mCurCountDown;
    }

    private void updateLteSignalStrength(AsyncResult ar) {
        if ((ar.exception == null) && (ar.result != null)) {
            mLteSignalStrength = (SignalStrength) ar.result;
            mLteSignalStrength.validateInput();
            log("updateLteSignalStrength after validate: mLteSignalStrength = "
                    + mLteSignalStrength);
        } else {
            log("updateLteSignalStrength Exception from RIL : " + ar.exception);
        }
    }

    private void updateCdmaSignalStrength(AsyncResult ar) {
        if ((ar.exception == null) && (ar.result != null)) {
            mCdmaSignalStrength = (SignalStrength) ar.result;
            mCdmaSignalStrength.validateInput();
            log("updateCdmaSignalStrength after validate: mCdmaSignalStrength = "
                    + mCdmaSignalStrength);
        } else {
            log("updateCdmaSignalStrength Exception from RIL : " + ar.exception);
        }
    }

    private boolean isLteSignalGoodEnough() {
        int testSignal = SystemProperties.getInt(PROP_NAME_TEST_SET_LTE_SIGNAL, 0);
        if (testSignal != 0) {
            log("isLteSignalGoodEnough: testSignal = " + testSignal);
            return testSignal > mLteSignalGoodThreshold;
        } else {
            log("isLteSignalGoodEnough: RSRP = " + mLteSignalStrength.getLteRsrp());
            return (mLteSignalStrength.getLteRsrp() != SignalStrength.INVALID)
                    && (mLteSignalStrength.getLteRsrp() > mLteSignalGoodThreshold);
        }
    }

    private boolean isLteSignalWeakEnough() {
        int testSignal = SystemProperties.getInt(PROP_NAME_TEST_SET_LTE_SIGNAL, 0);
        if (testSignal != 0) {
            log("isLteSignalWeakEnough: testSignal = " + testSignal);
            return testSignal < mLteSignalWeakThreshold;
        } else {
            log("isLteSignalWeakEnough: RSRP = " + mLteSignalStrength.getLteRsrp());
            return mLteSignalStrength.getLteRsrp() < mLteSignalWeakThreshold;
        }
    }

    private boolean isCdmaSignalGoodEnough() {
        // TODO: check signal with algorithm
        int testSignal = SystemProperties.getInt(PROP_NAME_TEST_SET_CDMA_SIGNAL, 0);
        if (testSignal != 0) {
            log("isCdmaSignalGoodEnough: testSignal = " + testSignal);
            return testSignal > mCdmaSignalGoodThreshold;
        } else {
            log("isCdmaSignalGoodEnough: dbm = " + mCdmaSignalStrength.getEvdoDbm());
            return mCdmaSignalStrength.getEvdoDbm() > mCdmaSignalGoodThreshold;
        }
    }

    /**
     * Set intial PS service type.
     * @param initialPsType intial PS service type
     */
    public void setInitialPsServiceType(int initialPsType) {
        mPsServiceType = initialPsType;
    }

    /**
     * Get PS service type, unknow, on CDMA or on LTE.
     * @return PS service type.
     */
    public int getPsServiceType() {
        return mPsServiceType;
    }

    private void trySwitchPsService(int psType) {
        log("trySwitchPsService: psType = " + psType + ", mPsServiceType = "
                + mPsServiceType);
        if (mPsServiceType != psType) {
            Message msg = getHandler().obtainMessage(EVENT_SWITCH_PS_SERVICE);
            msg.arg1 = psType;
            // Delay to send the message to make the PS state updated.
            sendMessage(msg);
        }
    }

    private void onSwitchPsService(int psType) {
        mPsServiceType = psType;
        mIratController.notifyIratEvent(psType);
        log("switchPsService: psType = " + psType);
    }

    private void registerForDrsRatChanged(PhoneBase phone, int msgId) {
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            mIratController.registerForLteDataRegStateOrRatChanged(
                    getHandler(), msgId, null);
        } else if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            mIratController.registerForCdmaDataRegStateOrRatChanged(
                    getHandler(), msgId, null);
        }
    }

    private void unregisterForDrsRatChanged(PhoneBase phone) {
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            mIratController
                    .unregisterForLteDataRegStateOrRatChanged(getHandler());
        } else if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            mIratController
                    .unregisterForCdmaDataRegStateOrRatChanged(getHandler());
        }
    }

    private boolean updateLteRegState(AsyncResult ar) {
        int regState = getLteRegState(ar);
        if (mLteRegState != regState) {
            mLteRegState = regState;
            return true;
        }
        return false;
    }

    private int getLteRegState(AsyncResult ar) {
        int testRegState = SystemProperties.getInt(
                PROP_NAME_TEST_SET_LTE_REG_STATE, -1);
        if (testRegState != -1) {
            log("getLteRegState: testRegState = " + testRegState);
            return testRegState;
        } else {
            @SuppressWarnings("unchecked")
            Pair<Integer, Integer> lteDrsRat = (Pair<Integer, Integer>) ar.result;
            return mappingRegState(lteDrsRat.first);
        }
    }

    private boolean updateCdmaRegState(AsyncResult ar) {
        int regState = getCdmaRegState(ar);
        if (mCdmaRegState != regState) {
            mCdmaRegState = regState;
            return true;
        }
        return false;
    }

    private int getCdmaRegState(AsyncResult ar) {
        int testRegState = SystemProperties.getInt(
                PROP_NAME_TEST_SET_CDMA_REG_STATE, -1);
        if (testRegState != -1) {
            log("getCdmaRegState: testRegState = " + testRegState);
            return testRegState;
        } else {
            @SuppressWarnings("unchecked")
            Pair<Integer, Integer> cdmaDrsRat = (Pair<Integer, Integer>) ar.result;
            return mappingRegState(cdmaDrsRat.first);
        }
    }

    private int mappingRegState(int dataRegState) {
        int regState;
        if (dataRegState == ServiceState.STATE_IN_SERVICE) {
            regState = PS_REG_STATE_REGISTERRED;
        } else {
            regState = PS_REG_STATE_NOT_REGISTERRED;
        }
        return regState;
    }

    @Override
    protected String getWhatToString(int what) {
        return cmdToString(what);
    }

    // Convert cmd to string or null if unknown
    static String cmdToString(int cmd) {
        String value = null;
        if ((cmd >= 0) && (cmd - EVENT_MMC_BASE < sCmdToString.length)) {
            value = sCmdToString[cmd - EVENT_MMC_BASE];
        }

        if (value == null) {
            value = "0x" + Integer.toHexString(cmd);
        }
        return value;
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, s);
    }
}
