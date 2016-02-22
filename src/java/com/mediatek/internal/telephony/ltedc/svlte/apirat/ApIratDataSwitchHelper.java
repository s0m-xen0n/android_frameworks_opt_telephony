
package com.mediatek.internal.telephony.ltedc.svlte.apirat;

import android.os.Message;
import android.telephony.Rlog;
import android.telephony.ServiceState;

import com.mediatek.internal.telephony.ltedc.svlte.IratDataSwitchHelper;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteIratUtils;
import com.mediatek.internal.telephony.ltedc.svlte.SvltePhoneProxy;

/**
 * AP IRAT data switch helper class.
 * @hide
 */
public class ApIratDataSwitchHelper extends IratDataSwitchHelper {

    private boolean mCdmaDataAllowed;
    private boolean mGsmDataAllowed;
    private boolean mCdmaDataAllowResponsed;
    private boolean mGsmDataAllowResponsed;

    /**
     * Create AP IRAT data switch helper.
     * @param svltePhoneProxy Instance of SvltePhoneProxy.
     */
    public ApIratDataSwitchHelper(SvltePhoneProxy svltePhoneProxy) {
        super(svltePhoneProxy);
    }

    @Override
    protected void onCdmaDataAttached() {
        notifyDataConnectionAttached();
    }

    @Override
    protected void onLteDataAttached() {
        notifyDataConnectionAttached();
    }

    @Override
    protected void onCdmaDataDetached() {
        notifyDataConnectionDetached();
    }

    @Override
    protected void onLteDataDetached() {
        notifyDataConnectionDetached();
    }

    @Override
    protected void onCdmaDataAllowUrc() {
        mCdmaDataAllowed = true;
        notifyDataAllowed();
    }

    @Override
    protected void onGsmDataAllowUrc() {
        mGsmDataAllowed = true;
        notifyDataAllowed();
    }

    @Override
    protected void onCdmaSetDataAllowedDone() {
        mCdmaDataAllowResponsed = true;
        if (mDataAllowResponseMessage != null) {
            final int svltePhoneProxyMode = getSvltePhoneProxyMode();
            if (svltePhoneProxyMode == SvlteIratUtils.PHONE_IN_CDMA_MODE
                    || (svltePhoneProxyMode == SvlteIratUtils.PHONE_IN_SVLTE_MODE
                    && mGsmDataAllowResponsed)) {
                mDataAllowResponseMessage.sendToTarget();
                mDataAllowResponseMessage = null;
            }
        }
    }

    @Override
    protected void onGsmSetDataAllowedDone() {
        mGsmDataAllowResponsed = true;
        if (mDataAllowResponseMessage != null) {
            final int svltePhoneProxyMode = getSvltePhoneProxyMode();
            if (svltePhoneProxyMode == SvlteIratUtils.PHONE_IN_GSM_MODE
                    || (svltePhoneProxyMode == SvlteIratUtils.PHONE_IN_SVLTE_MODE
                    && mCdmaDataAllowResponsed)) {
                mDataAllowResponseMessage.sendToTarget();
                mDataAllowResponseMessage = null;
            }
        }
    }

    @Override
    public void setDataAllowed(boolean allowed, Message result) {
        final int svltePhoneProxyMode = getSvltePhoneProxyMode();
        mCdmaDataAllowResponsed = false;
        mGsmDataAllowResponsed = false;
        mDataAllowResponseMessage = result;
        log("setDataAllowed: allowed = " + allowed + ", svltePhoneProxyMode = "
                + svltePhoneProxyMode + ",mPsServiceType = " + mPsServiceType);
        // Always set data allowed to two phone.
        if (svltePhoneProxyMode == SvlteIratUtils.PHONE_IN_SVLTE_MODE) {
            mCdmaCi.setDataAllowed(allowed,
                    obtainMessage(EVENT_CDMA_SET_DATA_ALLOW_DONE));
            mLteCi.setDataAllowed(allowed,
                    obtainMessage(EVENT_LTE_SET_DATA_ALLOW_DONE));
        } else if (svltePhoneProxyMode == SvlteIratUtils.PHONE_IN_CDMA_MODE) {
            mCdmaCi.setDataAllowed(allowed,
                    obtainMessage(EVENT_CDMA_SET_DATA_ALLOW_DONE));
            mLteCi.setDataAllowed(allowed,
                    obtainMessage(EVENT_LTE_SET_DATA_ALLOW_DONE));
        } else if (svltePhoneProxyMode == SvlteIratUtils.PHONE_IN_GSM_MODE) {
            mCdmaCi.setDataAllowed(allowed,
                    obtainMessage(EVENT_CDMA_SET_DATA_ALLOW_DONE));
            mLteCi.setDataAllowed(allowed,
                    obtainMessage(EVENT_LTE_SET_DATA_ALLOW_DONE));
        }
    }

    @Override
    public void syncAndNotifyAttachState() {
        notifyDataConnectionAttached();
    }

    private void notifyDataConnectionAttached() {
        log("notifyDataConnectionAttached: mPsServiceType = " + mPsServiceType);
        if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
            if (getCurrentDataConnectionState(mCdmaPhone) == ServiceState.STATE_IN_SERVICE) {
                mAttachedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
            if (getCurrentDataConnectionState(mLtePhone) == ServiceState.STATE_IN_SERVICE) {
                mAttachedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_UNKNOWN) {
            if (getCurrentDataConnectionState(mLtePhone) == ServiceState.STATE_IN_SERVICE
                    && getCurrentDataConnectionState(mCdmaPhone) == ServiceState.STATE_IN_SERVICE) {
                mAttachedRegistrants.notifyRegistrants();
            }
        }
    }

    private void notifyDataConnectionDetached() {
        log("notifyDataConnectionDetached: mPsServiceType = " + mPsServiceType);
        if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
            if (getCurrentDataConnectionState(mCdmaPhone) != ServiceState.STATE_IN_SERVICE) {
                mDetachedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
            if (getCurrentDataConnectionState(mLtePhone) != ServiceState.STATE_IN_SERVICE) {
                mDetachedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_UNKNOWN) {
            if (getCurrentDataConnectionState(mLtePhone) != ServiceState.STATE_IN_SERVICE
                    && getCurrentDataConnectionState(mCdmaPhone) == ServiceState.STATE_IN_SERVICE) {
                mDetachedRegistrants.notifyRegistrants();
            }
        }
    }

    private void notifyDataAllowed() {
        log("notifyDataAllowed: mPsServiceType = " + mPsServiceType
                + ", mCdmaDataAllowed = " + mCdmaDataAllowed
                + ", mGsmDataAllowed = " + mGsmDataAllowed);
        if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
            if (mCdmaDataAllowed) {
                mDataAllowedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
            if (mGsmDataAllowed) {
                mDataAllowedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_UNKNOWN) {
            if (mGsmDataAllowed && mCdmaDataAllowed) {
                mDataAllowedRegistrants.notifyRegistrants();
            }
        }
    }

    protected void log(String s) {
        Rlog.d(LOG_TAG, s);
    }

    protected void loge(String s) {
        Rlog.e(LOG_TAG, s);
    }
}
