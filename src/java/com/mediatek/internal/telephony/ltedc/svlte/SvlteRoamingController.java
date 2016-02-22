package com.mediatek.internal.telephony.ltedc.svlte;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.PhoneBase;
import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;

/**
 * SvlteRoamingController used to international roaming.
 * @hide
 */
public class SvlteRoamingController {
    private static final boolean DEBUG = true;
    private static final String LOG_TAG_PHONE = "PHONE";
    private static final String TAG_PREFIX = "[Svlte][SvlteRoamingController]";

    // GMSS rat change message.
    private static final int EVENT_GMSS_RAT_CHANGED = 101;

    private static final int CHINA_TELECOM_MAINLAND_MCC = 460;
    private static final int CHINA_TELECOM_MACCO_MCC = 455;

    private static final Object mLock = new Object();

    private static SvlteRoamingController sInstance;

    private LteDcPhoneProxy mLteDcPhoneProxy;
    private PhoneBase mLtePhone;

    private SvlteRoamingController(LteDcPhoneProxy lteDcPhoneProxy) {
        logd("SvlteRoamingController, lteDcPhoneProxy=" + lteDcPhoneProxy);
        mLteDcPhoneProxy = lteDcPhoneProxy;
        mLtePhone = (PhoneBase) mLteDcPhoneProxy.getLtePhone();
        init();
    }

    /**
     * @return the single instance of SvlteRoamingController.
     */
    public static SvlteRoamingController getInstance() {
        return sInstance;
    }

    /**
     * Make the instance of the international roaming controller.
     * @param lteDcPhoneProxy the lteDcPhoneProxy.
     * @return the instance.
     */
    public static SvlteRoamingController make(LteDcPhoneProxy lteDcPhoneProxy) {
        synchronized (mLock) {
            if (sInstance != null) {
                throw new RuntimeException(
                        "SvlteRoamingController.make() should only be called once");
            }
            sInstance = new SvlteRoamingController(lteDcPhoneProxy);
            return sInstance;
        }
    }

    /**
     * Initialize.
     * URC:
     * 1.1  Unsolicited Result Code: +EGMSS
     * 1.1.1.1 Description
     * This URC is used inform AP the RAT selected by GMSS procedure
     * 1.1.1.2 Format
     * Unsolicited result code
     * +EGMSS: <rat>
     * 1.1.1.3 Field
     * <rat> Integer type
     * 0: Any RAT in 3GPP2 RAT group
     * 1: Any RAT in 3GPP RAT group
     * 2: CDMA2000 1x
     * 3: CDMA2000 HRPD
     * 4: GERAN
     * 5: UTRAN
     * 6: EUTRAN
     */
    private void init() {
        logd("init, registerForGmssRatChanged");
        mLtePhone.mCi.registerForGmssRatChanged(mIRHandler, EVENT_GMSS_RAT_CHANGED, null);
    }

    private Handler mIRHandler = new Handler() {
        // CDMA network group.
        private static final int EVENT_3GPP2_RAT_GROUP = 0;
        // Not CDMA network group.
        private static final int EVENT_3GPP_RAT_GROUP = 1;
        /// CDMA network group.
        private static final int EVENT_CDMA2000_1X = 2;
        private static final int EVENT_CDMA2000_HRPD = 3;
        // Not CDMA network group.
        private static final int EVENT_GERAN = 4;
        private static final int EVENT_UTRAN = 5;
        private static final int EVENT_EUTRAN = 6;

        @Override
        public void handleMessage(Message msg) {
            logd("mIRHandler--handleMessage, " + msg);
            switch(msg.what) {
            case EVENT_GMSS_RAT_CHANGED:
                processEgmssResult(msg.obj);
                break;
            default:
                break;
            }
        }

        private void processEgmssResult(Object obj) {
            AsyncResult asyncRet = (AsyncResult) obj;
            if (asyncRet.exception == null && asyncRet.result != null) {
                int[] urcResults = (int[]) asyncRet.result;
                logd("processEgmssResult, urcResults=" + urcResults);
                if (urcResults != null) {
                    logd("processEgmssResult, urcResults.length=" + urcResults.length);
                    if (urcResults.length >= 2) {
                        logd("processEgmssResult, GMSS report code urcResults[0]=" + urcResults[0]
                                + "urcResult[1]=" + urcResults[1]);
                        SvlteRatController.getInstance().setRoamingMode(isRoaming(urcResults[1]),
                                null);
                    }
                }
            } else {
                logd("processEgmssResult, asyncRet.exception=" + asyncRet.exception
                        + " asyncRet.result=" + asyncRet.result);
            }
        }
    };

    private static void logd(String msg) {
        if (DEBUG) {
            Log.d(LOG_TAG_PHONE, TAG_PREFIX + msg);
        }
    }

    private boolean isRoaming(int mcc) {
        boolean bRoaming = (mcc != CHINA_TELECOM_MAINLAND_MCC
                && mcc != CHINA_TELECOM_MACCO_MCC);
        logd("isRoaming, bRoaming=" + bRoaming);
        return bRoaming;
    }
}
