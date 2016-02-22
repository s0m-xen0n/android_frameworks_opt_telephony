
package com.mediatek.internal.telephony.ltedc.svlte.apirat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;

/**
 * LTE background search controller.
 * @hide
 */
public class LteBgSearchController extends StateMachine {
    private static final String LOG_TAG = "[IRAT_BgSearch]";

    private static final String STATE_MACHINE_NAME = "LteBgSearch";

    private static final int MAX_LTE_NEIGHBOR_EARFCN_NUMBER = 7;

    private static final int DISABLED = 0;
    private static final int ENABLED = 1;

    // LTE background search strategy enable guard timer.
    private static final long DEFAULT_LTE_POWER_OFF_GUARD_TIME = 20 * 60 * 1000;
    private static final String PROP_NAME_LTE_POWER_OFF_GUARD_TIME =
            "persist.lte.poweroff.guardtime";

    // LTE search how many rounds with no signal before power off.
    private static final int DEFAULT_SEARCH_ROUND_BEFORE_POWER_OFF = 1;
    private static final String PROP_NAME_LTE_POWER_OFF_ROUND = "persist.lte.poweroff.round";

    // LTE power on after power off.
    private static final long DEFAULT_LTE_POWER_ON_TIMER = 5 * 60 * 1000;
    private static final String PROP_NAME_LTE_POWER_ON_TIMER = "persist.lte.poweron.timer";

    private static final int EVENT_LTE_BACKGROUND_SEARCH_BASE = 1000;
    private static final int EVENT_GUARD_TIMER_TIME_OUT = EVENT_LTE_BACKGROUND_SEARCH_BASE + 0;
    private static final int EVENT_POWER_ON_TIMER_TIME_OUT = EVENT_LTE_BACKGROUND_SEARCH_BASE + 1;
    private static final int EVENT_SET_BG_SEARCH_STRATEGY_ENABLED =
            EVENT_LTE_BACKGROUND_SEARCH_BASE + 2;
    private static final int EVENT_LTE_RADIO_ON = EVENT_LTE_BACKGROUND_SEARCH_BASE + 3;
    private static final int EVENT_LTE_RADIO_OFF = EVENT_LTE_BACKGROUND_SEARCH_BASE + 4;
    private static final int EVENT_CDMA_RADIO_AVAILABLE = EVENT_LTE_BACKGROUND_SEARCH_BASE + 5;
    private static final int EVENT_LTE_NEIGHBOR_EARFCN_CHANGE =
            EVENT_LTE_BACKGROUND_SEARCH_BASE + 6;
    private static final int EVENT_LTE_NETWORK_STATE_CHANGED = EVENT_LTE_BACKGROUND_SEARCH_BASE + 7;
    private static final int EVENT_SCREEN_ON = EVENT_LTE_BACKGROUND_SEARCH_BASE + 8;
    private static final int EVENT_LTE_BG_SEARCH_STATE_CHANGED =
            EVENT_LTE_BACKGROUND_SEARCH_BASE + 9;

    private static final int CMD_TO_STRING_COUNT = EVENT_LTE_BG_SEARCH_STATE_CHANGED
            - EVENT_LTE_BACKGROUND_SEARCH_BASE + 1;

    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[EVENT_GUARD_TIMER_TIME_OUT
                - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_GUARD_TIMER_TIME_OUT";
        sCmdToString[EVENT_POWER_ON_TIMER_TIME_OUT
                - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_POWER_ON_TIMER_TIME_OUT";
        sCmdToString[EVENT_SET_BG_SEARCH_STRATEGY_ENABLED
                - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_SET_BG_SEARCH_STRATEGY_ENABLED";
        sCmdToString[EVENT_LTE_RADIO_ON - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_LTE_RADIO_ON";
        sCmdToString[EVENT_LTE_RADIO_OFF - EVENT_LTE_BACKGROUND_SEARCH_BASE] =
                "EVENT_LTE_RADIO_OFF";
        sCmdToString[EVENT_CDMA_RADIO_AVAILABLE
                - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_CDMA_RADIO_AVAILABLE";
        sCmdToString[EVENT_LTE_NEIGHBOR_EARFCN_CHANGE
                - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_LTE_NEIGHBOR_EARFCN_CHANGE";
        sCmdToString[EVENT_LTE_NETWORK_STATE_CHANGED
                - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_LTE_NETWORK_STATE_CHANGED";
        sCmdToString[EVENT_SCREEN_ON - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_SCREEN_ON";
        sCmdToString[EVENT_LTE_BG_SEARCH_STATE_CHANGED
                - EVENT_LTE_BACKGROUND_SEARCH_BASE] = "EVENT_LTE_BG_SEARCH_STATE_CHANGED";
    }

    private static final int LTE_REG_STATE_UNKOWN = -1;
    private static final int LTE_REG_STATE_NOT_REGISTERRED = 0;
    private static final int LTE_REG_STATE_REGISTERRED = 1;

    // LTE register state, get from +CEREG
    private int mLteRegState = ServiceState.REGISTRATION_STATE_UNKNOWN;

    private Context mContext;
    private LteDcPhoneProxy mLteDcPhoneProxy;
    private ApIratController mIratController;
    private PhoneBase mLtePhone;
    private PhoneBase mCdmaPhone;
    private CommandsInterface mLteCi;
    private CommandsInterface mCdmaCi;

    // Whether the background search strategy is enabled.
    private boolean mEnabled;

    // Strategy start timer, doesn't power off/on LTE stack in this duration
    // after device power on or exit airplane mode or SIM radio on.
    private long mLtePowerOffGuardTime;

    // Indicate how many round to search background before LTE power off.
    private int mSearchRoundBeforePowerOff;

    // Indicate how much time wait to power on LTE modem
    private long mLtePowerOnTimer;

    // Whether LTE stack is powered of by the strategy.
    private boolean mIsLtePowerOff;

    // LTE background search round, when searching for a while and no network
    // avaible, LTE stack will be powered off
    private int mLteBgSearchRound;

    // LTE ARFCNs reported by CDMA, the number should be less than 7.
    private int mNumberOfArfcns;
    private int[] mLteArfcns = new int[MAX_LTE_NEIGHBOR_EARFCN_NUMBER];

    private boolean mBgSearchBlocked;

    // Whether the events are regisitered.
    private boolean mEventRegistered;

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("Receive intent " + intent);
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                // Trigger an background search.
                LteBgSearchController.this.getHandler().sendEmptyMessage(
                        EVENT_SCREEN_ON);
            }
        }
    };

    /**
     * Construct LteBgSearchController.
     * @param lteDcPhoneProxy SvltePhoneProxy.
     * @param enabled Initial enable state.
     * @param controller ApIratController.
     * @param handler Handler for state machine.
     */
    public LteBgSearchController(LteDcPhoneProxy lteDcPhoneProxy,
            boolean enabled, ApIratController controller, Handler handler) {
        super(STATE_MACHINE_NAME, handler);
        mLteDcPhoneProxy = lteDcPhoneProxy;
        mLtePhone = lteDcPhoneProxy.getLtePhone();
        mCdmaPhone = lteDcPhoneProxy.getNLtePhone();
        mLteCi = mLtePhone.mCi;
        mCdmaCi = mCdmaPhone.mCi;
        mContext = mLteDcPhoneProxy.getContext();
        mIratController = controller;
        mEnabled = enabled;

        initParams();

        addState(mDefaultState);
        addState(mInactiveState, mDefaultState);
        addState(mBgSearchingState, mDefaultState);
        addState(mRegisteredState, mDefaultState);
        addState(mPowerOffState, mDefaultState);
        addState(mDisabledState);
        if (mEnabled) {
            setInitialState(mInactiveState);
        } else {
            setInitialState(mDisabledState);
        }

        registerForAllEvents();
    }

    private void registerForAllEvents() {
        log("registerForAllEvents: mEventRegistered = " + mEventRegistered);
        if (mEventRegistered) {
            log("Warning: try to register for events repeatedly.");
            return;
        }

        // Register for screen on message
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(mIntentReceiver, filter);

        // Register for C2K LTE neighbor information.
        mCdmaCi.registerForLteEarfcnInfo(getHandler(),
                EVENT_LTE_NEIGHBOR_EARFCN_CHANGE, null);
        mCdmaCi.registerForOn(getHandler(), EVENT_CDMA_RADIO_AVAILABLE, null);

        mLteCi.registerForPsNetworkStateChanged(getHandler(),
                EVENT_LTE_NETWORK_STATE_CHANGED, null);
        mLteCi.registerForOn(getHandler(), EVENT_LTE_RADIO_ON, null);
        mLteCi.registerForOffOrNotAvailable(getHandler(), EVENT_LTE_RADIO_OFF,
                null);

        mEventRegistered = true;
    }

    private void unregisterForAllEvents() {
        log("unregisterForAllEvents: mEventRegistered = " + mEventRegistered);
        if (!mEventRegistered) {
            log("Warning: try to deregister for events repeatedly.");
            return;
        }

        // Unregister for C2K LTE neighbor information and screen on message
        mContext.unregisterReceiver(mIntentReceiver);

        mLteCi.unregisterForOn(getHandler());
        mLteCi.unregisterForOffOrNotAvailable(getHandler());

        mCdmaCi.unregisterForLteEarfcnInfo(getHandler());
        mCdmaCi.unregisterForAvailable(getHandler());

        mLteCi.unregisterForPsNetworkStateChanged(getHandler());

        mEventRegistered = false;
    }

    private void initParams() {
        mLtePowerOffGuardTime = SystemProperties.getLong(
                PROP_NAME_LTE_POWER_OFF_GUARD_TIME,
                DEFAULT_LTE_POWER_OFF_GUARD_TIME);

        mSearchRoundBeforePowerOff = SystemProperties.getInt(
                PROP_NAME_LTE_POWER_OFF_ROUND,
                DEFAULT_SEARCH_ROUND_BEFORE_POWER_OFF);
        mLtePowerOnTimer = SystemProperties.getLong(
                PROP_NAME_LTE_POWER_ON_TIMER, DEFAULT_LTE_POWER_ON_TIMER);

        mEnabled = mIratController.isIratControllerEnabled();

        for (int i = 0; i < MAX_LTE_NEIGHBOR_EARFCN_NUMBER; i++) {
            mLteArfcns[i] = -1;
        }

        log("initParams: mLtePowerOffGuardTime = " + mLtePowerOffGuardTime
                + ", mLtePowerOffRound = " + mSearchRoundBeforePowerOff
                + ", mLtePowerOnTimer = " + mLtePowerOnTimer
                + ", mEnabled = " + mEnabled);
    }

    private void startGuardTimer() {
        removeMessages(EVENT_GUARD_TIMER_TIME_OUT);
        sendMessageDelayed(obtainMessage(EVENT_GUARD_TIMER_TIME_OUT),
                mLtePowerOffGuardTime);
    }

    private void startPowerOnMdTimer() {
        removeMessages(EVENT_POWER_ON_TIMER_TIME_OUT);
        sendMessageDelayed(obtainMessage(EVENT_POWER_ON_TIMER_TIME_OUT),
                mLtePowerOnTimer);
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
            AsyncResult ar;
            switch (msg.what) {
                case EVENT_SET_BG_SEARCH_STRATEGY_ENABLED:
                    if (msg.arg1 == ENABLED) {
                        log("[Default] LTE background search enabled: state = "
                                + getCurrentState());
                    } else if (msg.arg1 == DISABLED) {
                        log("[Default] LTE background search disabled: state = "
                                + getCurrentState());
                        transitionTo(mDisabledState);
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_RADIO_ON:
                    // Start guard timer.
                    startGuardTimer();
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_RADIO_OFF:
                    // TODO: check whether LTE radio off should be handled.
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_NETWORK_STATE_CHANGED:
                    int regState = getLteRegState((AsyncResult) msg.obj);
                    log("[Default] : regState = " + regState
                            + ", mLteRegState = " + mLteRegState);
                    if (regState != mLteRegState) {
                        mLteRegState = regState;
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_NEIGHBOR_EARFCN_CHANGE:
                    // Update LTE neighbor EARFCN information.
                    updateLteArfcnInfo((AsyncResult) msg.obj);
                    retVal = HANDLED;
                    break;
                default:
                    log("DefaultState: shouldn't happen but ignore msg.what="
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private DefaultState mDefaultState = new DefaultState();

    /**
     * Active state, it is an initial state when LTE is enabled.
     */
    private class InactiveState extends State {
        @Override
        public void enter() {
            log("InactiveState: enter");
            // Reset all status when enter inactive state.
            mLteBgSearchRound = 0;
            enableLteNeighborArfcnReport(true);
        }

        @Override
        public void exit() {
            log("InactiveState: exit");
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            switch (msg.what) {
                case EVENT_GUARD_TIMER_TIME_OUT:
                    log("[Inactive] EVENT_GUARD_TIMER_TIME_OUT: mLteRegState = "
                            + mLteRegState);
                    if (!isNetworkRegistered(mLteRegState)) {
                        triggerBgSearch();
                        transitionTo(mBgSearchingState);
                    } else {
                        transitionTo(mRegisteredState);
                    }
                    retVal = HANDLED;
                    break;

                default:
                    log("[Inactive] not handled msg = "
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private InactiveState mInactiveState = new InactiveState();

    /**
     * LTE background searching state, temp state and will transfer to right
     * state with LTE reg state.
     */
    private class LteBgSearchingState extends State {
        @Override
        public void enter() {
            mLteBgSearchRound = 0;
            log("LteBgSearchingState: enter mLteBgSearchRound = "
                    + mLteBgSearchRound);
        }

        @Override
        public void exit() {
            mLteBgSearchRound = 0;
            log("LteBgSearchingState: exit mLteBgSearchRound = "
                    + mLteBgSearchRound);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            switch (msg.what) {
                case EVENT_LTE_NETWORK_STATE_CHANGED:
                    int regState = getLteRegState((AsyncResult) msg.obj);
                    log("[Searching] regState = " + regState
                            + ", mLteRegState = " + mLteRegState);
                    if (regState != mLteRegState) {
                        mLteRegState = regState;
                        if (isNetworkRegistered(mLteRegState)) {
                            transitionTo(mRegisteredState);
                        } else {
                            if (isNoService(mLteRegState)) {
                                mLteBgSearchRound++;
                                // Power off LTE MD after search for
                                if (mLteBgSearchRound >= mSearchRoundBeforePowerOff) {
                                    powerOffLteModem();
                                }
                            }
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_NEIGHBOR_EARFCN_CHANGE:
                    log("[Searching] EVENT_LTE_NEIGHBOR_EARFCN_CHANGE: mLteBgSearchRound = "
                            + mLteBgSearchRound);
                    // Update LTE neighbor EARFCN information and trigger
                    // background search.
                    mLteBgSearchRound = 0;
                    updateLteArfcnInfo((AsyncResult) msg.obj);
                    triggerBgSearch();
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_RADIO_OFF:
                    log("[Searching] EVENT_LTE_RADIO_OFF: transition to power off state.");
                    transitionTo(mPowerOffState);
                    retVal = HANDLED;
                    break;
                default:
                    log("[Searching] not handled msg = "
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private LteBgSearchingState mBgSearchingState = new LteBgSearchingState();

    /**
     * LTE registered state.
     */
    private class LteRegisteredState extends State {
        @Override
        public void enter() {
            log("LteRegisteredState: enter");
            enableLteNeighborArfcnReport(false);
        }

        @Override
        public void exit() {
            log("LteRegisteredState: exit");
            enableLteNeighborArfcnReport(true);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            switch (msg.what) {
                case EVENT_LTE_NETWORK_STATE_CHANGED:
                    int regState = getLteRegState((AsyncResult) msg.obj);
                    log("[Registered] regState = " + regState
                            + ", mLteRegState = " + mLteRegState);
                    if (regState != mLteRegState) {
                        mLteRegState = regState;
                        if (!isNetworkRegistered(regState)) {
                            triggerBgSearch();
                            transitionTo(mBgSearchingState);
                        }
                    }
                    retVal = HANDLED;
                    break;
                default:
                    log("[Registered] not handled msg = "
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private LteRegisteredState mRegisteredState = new LteRegisteredState();

    /**
     * LTE power off state, in this state to saving power.
     */
    private class LtePowerOffState extends State {
        @Override
        public void enter() {
            startPowerOnMdTimer();
            log("LtePowerOffState: enter");
        }

        @Override
        public void exit() {
            log("LtePowerOffState: exit");
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            switch (msg.what) {
                case EVENT_POWER_ON_TIMER_TIME_OUT:
                case EVENT_LTE_NEIGHBOR_EARFCN_CHANGE:
                    log("[PowerOff] power on modem by " + getWhatToString(msg.what));
                    powerOnLteModem();
                    retVal = HANDLED;
                    break;
                case EVENT_LTE_RADIO_ON:
                    log("[PowerOff] EVENT_LTE_RADIO_ON");
                    triggerBgSearch();
                    transitionTo(mBgSearchingState);
                    retVal = HANDLED;
                    break;
                default:
                    log("[PowerOff] not handled msg = "
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private LtePowerOffState mPowerOffState = new LtePowerOffState();

    /**
     * Inactive state, represent LTE is in off state.
     */
    private class DisabledState extends State {
        @Override
        public void enter() {
            log("DisabledState: enter");
            unregisterForAllEvents();

            // Remove power on timer.
            removeMessages(EVENT_POWER_ON_TIMER_TIME_OUT);
            // Reset all status when enter inactive state.
            enableLteNeighborArfcnReport(false);
        }

        @Override
        public void exit() {
            log("DisabledState: exit");
        }

        @Override
        public boolean processMessage(Message msg) {
            log("[Disabled] processMessage: msg = " + getWhatToString(msg.what));
            boolean retVal;
            switch (msg.what) {
                case EVENT_SET_BG_SEARCH_STRATEGY_ENABLED:
                    log("[Disabled] EVENT_SET_BG_SEARCH_STRATEGY_ENABLED");
                    if (msg.arg1 == ENABLED) {
                        log("[Disabled] Enable LTE background search.");
                        onLteBackgroundSearchEnabled();
                    } else if (msg.arg1 == DISABLED) {
                        log("[Disabled] LTE background search in disabled, do nothing.");
                    }
                    retVal = HANDLED;
                    break;

                default:
                    log("[Disabled] not handled msg = "
                            + getWhatToString(msg.what));
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private DisabledState mDisabledState = new DisabledState();

    /**
     * Set background search strategy enable.
     * @param enable True to enable the strategy.
     */
    public void setEnabled(boolean enable) {
        log("setEnabled: enable = " + enable + ", mEnabled = " + mEnabled);

        // Ignore the call if no change.
        if (mEnabled == enable) {
            return;
        }

        mEnabled = enable;
        Message msg = getHandler().obtainMessage(
                EVENT_SET_BG_SEARCH_STRATEGY_ENABLED);
        msg.arg1 = (enable ? ENABLED : DISABLED);
        sendMessage(msg);
    }

    private void onLteBackgroundSearchEnabled() {
        mLteRegState = mLtePhone.getServiceState().getRilDataRegState();

        log("onLteBackgroundSearchEnabled: mLteRegState = " + mLteRegState);
        if (isNetworkRegistered(mLteRegState)) {
            registerForAllEvents();
            transitionTo(mRegisteredState);
        } else {
            transitionTo(mInactiveState);
        }
    }

    /**
     * Dispose the component.
     */
    public void dispose() {
        log("dispose...");
        unregisterForAllEvents();
    }

    private void triggerBgSearch() {
        // AT+ECARFCN & AT+EBGS
        if (isNetworkRegistered(mLteRegState)) {
            log("triggerBgSearch but LTE already registered.");
            return;
        }
        log("triggerBgSearch: mNumberOfArfcns = " + mNumberOfArfcns);
        mLteCi.requestTriggerLteBgSearch(mNumberOfArfcns, mLteArfcns, null);
    }

    private void powerOffLteModem() {
        log("powerOffLteModem...");
        // AT+EFUN=1
        mLteDcPhoneProxy.setRadioPower(false,
                SubscriptionManager.LTE_DC_PHONE_ID);
        mIsLtePowerOff = true;
    }

    private void powerOnLteModem() {
        log("powerOnLteModem...");
        // AT+EFUN=0
        mLteDcPhoneProxy.setRadioPower(true,
                SubscriptionManager.LTE_DC_PHONE_ID);
        mIsLtePowerOff = false;
    }

    public boolean isLtePowerOff() {
        return mIsLtePowerOff;
    }

    private void enableLteNeighborArfcnReport(boolean enable) {
        log("enableLteNeighborArfcnReport: enable = " + enable);
        mCdmaCi.requestSetLteEarfcnEnabled(enable, null);
    }

    private int getLteRegState(AsyncResult ar) {
        int regState = mLteRegState;
        if (ar.exception != null || ar.result == null) {
            loge("getLteRegState with exception.");
        } else {
            int info[] = (int[]) ar.result;
            regState = (info[0]);
            log("getLteRegState: regState = " + regState);
        }
        return regState;
    }

    private int mappingRegState(int networkRegState) {
        int regState = LTE_REG_STATE_UNKOWN;
        if (networkRegState == ServiceState.REGISTRATION_STATE_HOME_NETWORK
                || networkRegState == ServiceState.REGISTRATION_STATE_ROAMING) {
            regState = LTE_REG_STATE_REGISTERRED;
        } else {
            regState = LTE_REG_STATE_NOT_REGISTERRED;
        }
        return regState;
    }

    /**
     * Whether the given reg state represent network registered.
     * @param regState Reg state.
     * @return True if registered.
     */
    private boolean isNetworkRegistered(int networkRegState) {
        return (mappingRegState(networkRegState) == LTE_REG_STATE_REGISTERRED);
    }

    private boolean isNoService(int networkRegState) {
        return networkRegState == ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_NOT_SEARCHING;
    }

    private void updateLteArfcnInfo(AsyncResult ar) {
        if (ar.exception != null || ar.result == null) {
            loge("updateLteArfcnInfo with exception");
        } else {
            int info[] = (int[]) ar.result;
            mNumberOfArfcns = info[0];
            log("updateLteArfcnInfo: mNumberOfArfcns = " + mNumberOfArfcns);
            for (int i = 0; i < mNumberOfArfcns; i++) {
                mLteArfcns[i] = info[i + 1];
            }

            for (int i = mNumberOfArfcns; i < MAX_LTE_NEIGHBOR_EARFCN_NUMBER; i++) {
                mLteArfcns[i] = -1;
            }
        }
    }

    @Override
    protected String getWhatToString(int what) {
        return cmdToString(what);
    }

    // Convert cmd to string or null if unknown
    static String cmdToString(int cmd) {
        String value = null;
        if ((cmd >= 0)
                && (cmd - EVENT_LTE_BACKGROUND_SEARCH_BASE < sCmdToString.length)) {
            value = sCmdToString[cmd - EVENT_LTE_BACKGROUND_SEARCH_BASE];
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
