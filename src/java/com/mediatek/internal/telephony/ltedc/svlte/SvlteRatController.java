package com.mediatek.internal.telephony.ltedc.svlte;

import android.app.ActivityManagerNative;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.PhoneBase;
import com.mediatek.internal.telephony.RadioManager;
import com.mediatek.internal.telephony.cdma.FeatureOptionUtils;
import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SvlteRatController used to switch the SVLTE RAT mode.
 *
 * @hide
 */
public class SvlteRatController {
    private static final boolean DEBUG = true;
    private static final String LOG_TAG_PHONE = "PHONE";
    private static final String TAG_PREFIX = "[SvlteRatController]";

    /**
     * Define the type of SVLTE RAT mode.
     */
    public enum SvlteRatMode {
        SVLTE_RAT_MODE_4G,
        SVLTE_RAT_MODE_3G,
        SVLTE_RAT_MODE_4G_DATA_ONLY,
        SVLTE_RAT_MODE_UNKNOWN;

        public boolean isCdmaOn() {
            return this != SVLTE_RAT_MODE_4G_DATA_ONLY;
        }

        public boolean isLteOn() {
            return this != SVLTE_RAT_MODE_3G;
        }

        public boolean isGsmOn() {
            return this == SVLTE_RAT_MODE_3G;
        }
    }

    /**
     * Define the type of roaming mode.
     */
    public enum RoamingMode {
        ROAMING_MODE_HOME,
        ROAMING_MODE_NORMAL_ROAMING,
        ROAMING_MODE_JPKR_CDMA, // only for 4M version.
        ROAMING_MODE_UNKNOWN;

        public boolean isCdmaOn() {
            return this == ROAMING_MODE_HOME || this == ROAMING_MODE_JPKR_CDMA;
        }

        public boolean isLteOn() {
            return this != ROAMING_MODE_JPKR_CDMA;
        }

        public boolean isGsmOn() {
            return this == ROAMING_MODE_NORMAL_ROAMING;
        }
    }

    public static final String INTENT_ACTION_START_SWITCH_SVLTE_RAT_MODE =
            "com.mediatek.intent.action.START_SWITCH_SVLTE_RAT_MODE";
    public static final String INTENT_ACTION_FINISH_SWITCH_SVLTE_RAT_MODE =
            "com.mediatek.intent.action.FINISH_SWITCH_SVLTE_RAT_MODE";

    private static final Object mLock = new Object();
    private static SvlteRatController sInstance;

    private LteDcPhoneProxy mLteDcPhoneProxy;
    private PhoneBase mCdmaPhone;
    private PhoneBase mLtePhone;

    private RatSwitchHandler mRatSwitchHandler;

    private SvlteRatMode mSvlteRatMode = SvlteRatMode.SVLTE_RAT_MODE_UNKNOWN;
    private RoamingMode mRoamingMode = RoamingMode.ROAMING_MODE_UNKNOWN;
    private SvlteRatMode mNewSvlteRatMode;
    private RoamingMode mNewRoamingMode;

    /**
     * Record the pending switch modes.
     */
    private class PendingSwitchRecord {
        SvlteRatMode mPendingSvlteRatMode;
        RoamingMode mPendingRoamingMode;
        Message mPendingResponse;
        PendingSwitchRecord(SvlteRatMode svlteRatMode, RoamingMode roamingMode, Message response) {
            mPendingSvlteRatMode = svlteRatMode;
            mPendingRoamingMode = roamingMode;
            mPendingResponse = response;
        }
    }
    private AtomicBoolean mInSwitching = new AtomicBoolean(false);
    private PendingSwitchRecord mPendingSwitchRecord = null;

    private SvlteRatController(LteDcPhoneProxy lteDcPhoneProxy) {
        mLteDcPhoneProxy = lteDcPhoneProxy;
        mCdmaPhone = (PhoneBase) mLteDcPhoneProxy.getNLtePhone();
        mLtePhone = (PhoneBase) mLteDcPhoneProxy.getLtePhone();
        mRatSwitchHandler = new RatSwitchHandler(Looper.myLooper());
    }

    /**
     * @return the single instance of RatController.
     */
    public static SvlteRatController getInstance() {
        synchronized (mLock) {
            if (sInstance == null) {
                throw new RuntimeException(
                        "SvlteRatController.getInstance can't be called before make()");
            }
            return sInstance;
        }
    }

    /**
     * Create the SvlteRatController.
     * @param lteDcPhoneProxy the LteDcPhoneProxy object.
     * @return The instance of SvlteRatController
     */
    public static SvlteRatController make(LteDcPhoneProxy lteDcPhoneProxy) {
        synchronized (mLock) {
            if (sInstance != null) {
                throw new RuntimeException(
                        "SvlteRatController.make() should only be called once");
            }
            sInstance = new SvlteRatController(lteDcPhoneProxy);
            return sInstance;
        }
    }

    /**
     * Get the svlte rat mode.
     * @return svlte mode.
     */
    public SvlteRatMode getSvlteRatMode() {
        return mSvlteRatMode;
    }

    /**
     * Set SVLTE RAT mode.
     * @param svlteRatMode SVLTE RAT mode index.
     * @param response the responding message.
     */
    public void setSvlteRatMode(int svlteRatMode, Message response) {
        setSvlteRatMode(SvlteRatMode.values()[svlteRatMode], response);
    }

    /**
     * Set SVLTE RAT mode.
     * @param svlteRatMode SVLTE RAT mode.
     * @param response the responding message.
     */
    public void setSvlteRatMode(SvlteRatMode svlteRatMode, Message response) {
        RoamingMode mode;
        if (mPendingSwitchRecord != null) {
            mode = mPendingSwitchRecord.mPendingRoamingMode;
        } else {
            mode = mRoamingMode;
        }
        setSvlteRatMode(svlteRatMode, mode, response);
    }

    /**
     * Get the roaming mode.
     * @return roaming mode.
     */
    public RoamingMode getRoamingMode() {
        return mRoamingMode;
    }

    /**
     * Set if on roaming.
     * @param roaming The roaming mode.
     * @param response The responding message.
     */
    public void setRoamingMode(boolean roaming, Message response) {
        RoamingMode mode = roaming ? RoamingMode.ROAMING_MODE_NORMAL_ROAMING
                : RoamingMode.ROAMING_MODE_HOME;
        setRoamingMode(mode, response);
    }

    /**
     * Set if on roaming.
     * @param roamingMode The roaming mode.
     * @param response The responding message.
     */
    public void setRoamingMode(RoamingMode roamingMode, Message response) {
        SvlteRatMode mode;
        if (mPendingSwitchRecord != null) {
            mode = mPendingSwitchRecord.mPendingSvlteRatMode;
        } else {
            mode = mSvlteRatMode;
        }
        mode = (mode == SvlteRatMode.SVLTE_RAT_MODE_UNKNOWN) ?
                SvlteRatMode.SVLTE_RAT_MODE_4G : mode;
        setSvlteRatMode(mode, roamingMode, response);
    }

    /**
     * Set SVLTE RAT mode and ROAMING mode.
     * @param svlteRatMode SVLTE RAT mode.
     * @param roamingMode The roaming mode.
     * @param response the responding message.
     */
    public void setSvlteRatMode(SvlteRatMode svlteRatMode, RoamingMode roamingMode,
            Message response) {
        logd("setSvlteRatMode(), SvlteRatMode from " + mSvlteRatMode + " to " + svlteRatMode);
        logd("setSvlteRatMode(), RoamingMode from " + mRoamingMode + " to " + roamingMode);
        if (mSvlteRatMode == svlteRatMode && mRoamingMode == roamingMode) {
            return;
        }

        logd("setSvlteRatMode(), mInSwitching=" + mInSwitching.get());
        if (mInSwitching.get()) {
            mPendingSwitchRecord = new PendingSwitchRecord(svlteRatMode, roamingMode, response);
            return;
        }

        mNewSvlteRatMode = svlteRatMode;
        mNewRoamingMode = roamingMode;
        startSwitchMode();

        mRatSwitchHandler.doSwitch(response);
    }

    private void startSwitchMode() {
        mInSwitching.set(true);

        Intent intent = new Intent(INTENT_ACTION_START_SWITCH_SVLTE_RAT_MODE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        ActivityManagerNative.broadcastStickyIntent(intent, null, 0);
    }

    private void finishSwitchMode() {
        mSvlteRatMode = mNewSvlteRatMode;
        mRoamingMode = mNewRoamingMode;

        Intent intent = new Intent(INTENT_ACTION_FINISH_SWITCH_SVLTE_RAT_MODE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        ActivityManagerNative.broadcastStickyIntent(intent, null, 0);

        mInSwitching.set(false);
        if (mPendingSwitchRecord != null) {
            setSvlteRatMode(mPendingSwitchRecord.mPendingSvlteRatMode,
                    mPendingSwitchRecord.mPendingRoamingMode,
                    mPendingSwitchRecord.mPendingResponse);
            mPendingSwitchRecord = null;
        }
    }

    /**
     * Handler used to enable/disable each dual connection.
     */
    private class RatSwitchHandler extends Handler {
        private static final int EVENT_SWITCH_SVLTE_MODE = 101;
        private static final int EVENT_CONFIG_MODEM_STATUS = 102;
        private static final int EVENT_SWITCH_ACTION_PHONE = 103;

        private Message mResponseMessage;

        public RatSwitchHandler(Looper looper) {
            super(looper);
        }

        public void doSwitch(Message response) {
            mResponseMessage = response;
            // radio off.
            boolean lteOn = (mNewSvlteRatMode.isLteOn() && mNewRoamingMode.isLteOn()) ||
                    (mNewSvlteRatMode.isGsmOn() && mNewRoamingMode.isGsmOn());
            if (!lteOn & mLtePhone.mCi.getRadioState().isOn()) {
                RadioManager.getInstance().setRadioPower(lteOn, mLtePhone.getPhoneId());
            }
            boolean cdmaOn = mNewSvlteRatMode.isCdmaOn() && mNewRoamingMode.isCdmaOn();
            if (!cdmaOn && mCdmaPhone.mCi.getRadioState().isOn()) {
                RadioManager.getInstance().setRadioPower(cdmaOn,  mCdmaPhone.getPhoneId());
            }
            // start switch.
            obtainMessage(RatSwitchHandler.EVENT_SWITCH_SVLTE_MODE).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_SWITCH_SVLTE_MODE:
                logd("EVENT_SWITCH_SVLTE_MODE.");

                // Set SVLTE RAT mode
                mLtePhone.mCi.setSvlteRatMode(mSvlteRatMode.ordinal(), mNewSvlteRatMode.ordinal(),
                        mRoamingMode.ordinal(), mNewRoamingMode.ordinal(), null);

                // Update IRAT mode for MD IRAT.
                if (FeatureOptionUtils.isCdmaMdIratSupport() && mSvlteRatMode != mNewSvlteRatMode) {
                    // TODO: Configure CDMA IRAT mode when enter/exit CDMA only mode.
                    if (FeatureOptionUtils.isCdmaMdIratSupport() &&
                            mNewSvlteRatMode == SvlteRatMode.SVLTE_RAT_MODE_3G) {
                        if (!mCdmaPhone.mCi.getRadioState().isOn()) {
                            mCdmaPhone.mCi.configIratMode(0, null);
                        } else {
                            mCdmaPhone.mCi.setRadioPower(false, null);
                            mCdmaPhone.mCi.configIratMode(0, null);
                        }
                    } else if (FeatureOptionUtils.isCdmaMdIratSupport() &&
                            mSvlteRatMode == SvlteRatMode.SVLTE_RAT_MODE_3G) {
                        if (!mCdmaPhone.mCi.getRadioState().isOn()) {
                            mCdmaPhone.mCi.configIratMode(1, null);
                        } else {
                            mCdmaPhone.mCi.setRadioPower(false, null);
                            mCdmaPhone.mCi.configIratMode(1, null);
                        }
                    }
                }

                // switch action phone
                mLteDcPhoneProxy.toggleActivePhone(
                        mNewSvlteRatMode == SvlteRatMode.SVLTE_RAT_MODE_4G_DATA_ONLY);

                // radio power
                boolean lteOn = mNewSvlteRatMode.isLteOn() && mNewRoamingMode.isLteOn();
                RadioManager.getInstance().setRadioPower(lteOn, mLtePhone.getPhoneId());
                boolean cdmaOn = mNewSvlteRatMode.isCdmaOn() && mNewRoamingMode.isCdmaOn();
                RadioManager.getInstance().setRadioPower(cdmaOn,  mCdmaPhone.getPhoneId());
                logd("EVENT_SWITCH_SVLTE_MODE lteOn=" + lteOn + ", cdmaOn=" + cdmaOn +
                        ", mNewSvlteRatMode=" + mNewSvlteRatMode);

                // invoke responding message.
                if (mResponseMessage != null) {
                    mResponseMessage.sendToTarget();
                }

                // invoke done
                finishSwitchMode();
                break;
            default:
                break;
            }
        }
    }

    private static void logd(String msg) {
        if (DEBUG) {
            Log.d(LOG_TAG_PHONE, TAG_PREFIX + msg);
        }
    }
}
